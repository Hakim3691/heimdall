package com.heimdall.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.heimdall.common.HeimdallLog
import com.heimdall.model.GgufMetadata
import com.heimdall.model.GgufTensorScan
import com.heimdall.model.GgufTensorScanner
import com.heimdall.model.ModelFile
import com.heimdall.model.ModelIssue
import com.heimdall.model.ModelRole
import com.heimdall.model.ModelValidation
import com.heimdall.model.ModelValidator
import com.heimdall.settings.SettingsStore
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/**
 * Resolves and validates the two GGUF weights from documents the user picked in the system picker
 * (FR-07).
 *
 * Scoped storage means the old `/sdcard/Heimdall/models/` contract could never work: an app on
 * Android 12+ cannot open arbitrary paths on shared storage. Instead the Storage Access Framework
 * hands Heimdall a durable read-only grant, and the weights are used **in place** — the language
 * file is never copied, moved or deleted.
 *
 * Two paths exist because llama.cpp and mtmd need two different things:
 * * the language weights are loaded from the document's **own descriptor** ([openLanguage]). The
 *   native runtime duplicates the fd and feeds it to `llama_model_load_from_file_ptr`, so nothing
 *   ever reopens `/proc/self/fd/<n>` — that reopen is what Android content providers refuse with
 *   `Permission denied`, and a path string cannot be handed to a descriptor-based loader anyway.
 * * the vision projector has no descriptor-based entry point (`mtmd_init_from_file` takes a path),
 *   so it is imported **once** into app storage ([importProjector]) where it is an ordinary file
 *   that llama.cpp can `open()` and `mmap()` without going through the provider again.
 *
 * A cloud-only document is a pipe, cannot be read as a regular file, and is rejected rather than
 * silently loaded as garbage.
 */
class ModelStore(
    private val context: Context,
    private val settings: SettingsStore,
    private val log: HeimdallLog,
) {
    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * Last inspection per role. Reading a document's 64 KiB metadata prefix, walking its tensor
     * table and querying DISPLAY_NAME are content-provider round trips, and onboarding refreshes on
     * every focus change; the cache keeps that off the main thread's critical path. It is keyed by
     * document URI and cleared whenever a pick changes, so the verdict never outlives the selection.
     */
    private data class Cached(
        val uri: String,
        val validation: ModelValidation,
        val summary: String?,
        val packing: String?,
    )

    private val cache = HashMap<ModelRole, Cached>()
    private val cacheLock = Any()

    /** Path of the imported projector copy, or null when it has not been imported yet. */
    @Volatile
    private var importedProjector: String? = null

    private val projectorLock = Any()

    /** The persisted document URI for [file], or null when the user has not picked one yet. */
    fun uriFor(file: ModelFile): Uri? =
        settings.modelUri(file.role)?.let { stored -> runCatching { Uri.parse(stored) }.getOrNull() }

    /**
     * Display name of the current document, for onboarding and diagnostics.
     *
     * The provider's DISPLAY_NAME column is the real file name; `lastPathSegment` is only a fallback
     * because a document id can be an encoded path (or a numeric id for some providers).
     */
    fun displayName(file: ModelFile): String? {
        val uri = uriFor(file) ?: return null
        val fromProvider = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    /**
     * One-line description of the selected document: file name, size and GGUF architecture.
     *
     * Showing what was actually picked is the fix for the old hardcoded "MiniCPM-V 2.6" label that
     * appeared above ternary/Bonsai files.
     */
    fun summary(file: ModelFile): String? {
        validate(file)
        return synchronized(cacheLock) { cache[file.role]?.summary }
    }

    /** Tensor packing as the last inspection saw it, or null when it could not be read. */
    fun packing(file: ModelFile): String? {
        validate(file)
        return synchronized(cacheLock) { cache[file.role]?.packing }
    }

    /** Forces the next [validate]/[summary] call to re-read both documents. */
    fun invalidate() {
        synchronized(cacheLock) { cache.clear() }
    }

    private fun formatBytes(bytes: Long): String {
        val gigabytes = bytes / 1_000_000_000.0
        if (gigabytes >= 1.0) return String.format(java.util.Locale.US, "%.1f GB", gigabytes)
        val megabytes = bytes / 1_000_000.0
        return String.format(java.util.Locale.US, "%.0f MB", megabytes)
    }

    /**
     * Remembers the picked document and keeps read access across process restarts.
     *
     * `takePersistableUriPermission` is best effort: a provider may refuse persistence, in which
     * case the selection works for this process only and the user is asked again after a restart.
     */
    fun remember(file: ModelFile, uri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { error ->
            log.warn(TAG, "provider refused a persistable read grant for ${file.fileName}", error)
        }
        settings.setModelUri(file.role, uri.toString())
        synchronized(cacheLock) { cache.remove(file.role) }
        if (file.role == ModelRole.VISION_PROJECTOR) {
            // The imported copy belongs to the previous pick; the next mount imports this one.
            importedProjector = null
        }
        // Log the role and the provider's real name: the canonical slot name made diagnostics read
        // as if the recommended MiniCPM-V file had been chosen when a Bonsai file was.
        log.info(TAG, "selected ${file.role} '${displayName(file) ?: "unknown"}' from $uri")
    }

    fun forget(file: ModelFile) {
        settings.setModelUri(file.role, null)
        synchronized(cacheLock) { cache.remove(file.role) }
        if (file.role == ModelRole.VISION_PROJECTOR) {
            importedProjector = null
            synchronized(projectorLock) { pruneProjectorImports(keep = null) }
        }
    }

    fun validateAll(): Map<ModelFile, ModelValidation> = ModelFile.entries.associateWith { validate(it) }

    /** Non-null when both files are individually valid but belong to different model families. */
    fun pairIssue(
        validations: Map<ModelFile, ModelValidation> = validateAll(),
    ): ModelValidation.Invalid? = ModelValidator.validatePair(
        validations[ModelFile.LANGUAGE] ?: ModelValidation.Invalid(ModelFile.LANGUAGE, ModelIssue.MISSING),
        validations[ModelFile.VISION] ?: ModelValidation.Invalid(ModelFile.VISION, ModelIssue.MISSING),
    )

    fun allReady(): Boolean {
        val validations = validateAll()
        return validations.values.all { it is ModelValidation.Ready } && pairIssue(validations) == null
    }

    /** Slots the user still has to fix; a mismatched pair marks the projector slot. */
    fun absent(): List<ModelFile> {
        val validations = validateAll()
        val broken = validations.filterValues { it !is ModelValidation.Ready }.keys.toMutableList()
        pairIssue(validations)?.file?.let { file -> if (file !in broken) broken += file }
        return broken
    }

    /** Validates one selected document without ever throwing into the service. */
    fun validate(file: ModelFile): ModelValidation {
        val uri = uriFor(file) ?: return ModelValidation.Invalid(file, ModelIssue.MISSING, "no file selected")
        synchronized(cacheLock) {
            cache[file.role]?.takeIf { it.uri == uri.toString() }?.let { return it.validation }
        }
        val (validation, summary, packing) = runCatching {
            val size = resolver.openFileDescriptor(uri, "r")?.use { descriptor -> descriptor.statSize } ?: -1L
            if (size <= 0L) {
                return@runCatching Triple(
                    ModelValidation.Invalid(file, ModelIssue.MISSING, "the document has no readable size"),
                    null,
                    null,
                )
            }
            // Read a metadata prefix, not just the magic: the architecture check needs it, and a
            // 64 KiB read is negligible next to the multi-gigabyte weights.
            val prefix = ByteArray(ModelValidator.METADATA_PREFIX_BYTES)
            val read = resolver.openInputStream(uri)?.use { stream -> stream.read(prefix) } ?: 0
            val header = if (read > 0) prefix.copyOf(read) else ByteArray(0)
            val tensors = scanTensors(file, uri)
            val verdict = ModelValidator.validate(
                file = file,
                sizeBytes = size,
                header = header,
                freeBytes = freeBytes(),
                tensors = tensors,
            )
            Triple(verdict, summarize(file, size, header), tensors?.describe())
        }.getOrElse { error ->
            log.warn(TAG, "could not inspect ${file.role}", error)
            Triple(
                ModelValidation.Invalid(
                    file,
                    ModelIssue.MISSING,
                    error.message ?: "the document is unreadable",
                ),
                null,
                null,
            )
        }
        synchronized(cacheLock) { cache[file.role] = Cached(uri.toString(), validation, summary, packing) }
        return validation
    }

    /**
     * Reads the tensor-info table at pick time.
     *
     * This is the only way to settle the packing question before llama.cpp loads gigabytes: a file
     * can declare a supported architecture and still carry tensors in a type this build cannot
     * decode. Failure to scan is not a verdict — [ModelValidator] then simply keeps quiet about
     * packing.
     */
    private fun scanTensors(file: ModelFile, uri: Uri): GgufTensorScan? = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            GgufTensorScanner.scan(BufferedInputStream(stream))
        }
    }.getOrElse { error ->
        log.warn(TAG, "could not read the tensor table of ${file.fileName}", error)
        null
    }

    /** One line for the model card: picked name, size and (when readable) architecture. */
    private fun summarize(file: ModelFile, size: Long, header: ByteArray): String? {
        val name = displayName(file) ?: return null
        val architecture = if (header.isNotEmpty()) GgufMetadata.parse(header)?.architecture else null
        return buildString {
            append(name)
            if (size > 0L) {
                append(" · ")
                append(formatBytes(size))
            }
            if (!architecture.isNullOrBlank()) {
                append(" · ")
                append(architecture)
            }
        }
    }

    /**
     * Opens the picked language weights as a descriptor llama.cpp loads from directly.
     *
     * The returned handle owns the only `ParcelFileDescriptor`: the native runtime duplicates the
     * fd, so the descriptor must stay open while the model is loaded and is closed with the session.
     * Unlike a `/proc/self/fd/<n>` path it never has to be reopened, which is what providers refuse.
     */
    fun openLanguage(): LanguageHandle? {
        val uri = uriFor(ModelFile.LANGUAGE) ?: return null
        return runCatching {
            val descriptor = resolver.openFileDescriptor(uri, "r") ?: return@runCatching null
            val size = descriptor.statSize
            // A pipe (cloud provider) reports no size and cannot be read as a regular file.
            if (size <= 0L) {
                descriptor.close()
                log.warn(TAG, "${ModelFile.LANGUAGE.fileName} is not a local document (reported size=$size)")
                return@runCatching null
            }
            LanguageHandle(descriptor)
        }.getOrElse { error ->
            log.warn(TAG, "could not open ${ModelFile.LANGUAGE.fileName}", error)
            null
        }
    }

    /**
     * Imports the picked projector into app storage **once** and returns the path llama.cpp reads.
     *
     * `mtmd_init_from_file` only accepts a path, and a path that points back into
     * `/proc/self/fd/<n>` cannot be opened. Copying the projector is the only way to give the
     * runtime a regular file it can mmap; it is skipped entirely when the copy for this exact pick
     * (same document, same size) already exists, so it happens once per pick rather than per
     * session. The language weights — the multi-gigabyte file — are never copied.
     */
    fun importProjector(): String? {
        val uri = uriFor(ModelFile.VISION) ?: return null
        val size = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor -> descriptor.statSize } ?: -1L
        }.getOrElse { error ->
            log.warn(TAG, "could not stat the vision projector", error)
            -1L
        }
        if (size <= 0L) {
            log.warn(TAG, "the vision projector is not a readable local document")
            return null
        }
        synchronized(projectorLock) {
            importedProjector?.let { path ->
                val file = File(path)
                if (file.isFile && file.length() == size) return path
            }
            val directory = projectorDirectory()
            if (!directory.isDirectory && !directory.mkdirs()) {
                log.warn(TAG, "could not create ${directory.absolutePath} for the projector import")
                return null
            }
            val target = File(directory, projectorFileName(uri.toString(), size))
            if (target.isFile && target.length() == size) {
                importedProjector = target.absolutePath
                pruneProjectorImports(keep = target)
                return target.absolutePath
            }
            if (directory.usableSpace in 1 until size) {
                log.warn(TAG, "not enough free space to import the projector (${formatBytes(size)})")
                return null
            }
            log.info(TAG, "importing the vision projector once (${formatBytes(size)}) into app storage")
            val part = File(directory, target.name + ".part")
            val copied = runCatching {
                resolver.openInputStream(uri)?.use { stream ->
                    part.outputStream().use { output -> stream.copyTo(output, COPY_BUFFER_BYTES) }
                } != null && part.length() == size
            }.getOrElse { error ->
                log.warn(TAG, "could not import the vision projector", error)
                false
            }
            if (!copied) {
                log.warn(TAG, "the projector import did not produce a complete copy")
                part.delete()
                return null
            }
            if (!part.renameTo(target)) {
                log.warn(TAG, "could not move the imported projector into place")
                part.delete()
                return null
            }
            importedProjector = target.absolutePath
            pruneProjectorImports(keep = target)
            log.info(TAG, "projector imported as ${target.name} (${formatBytes(target.length())})")
            return target.absolutePath
        }
    }

    /** Path of the projector copy that is already imported, or null when there is none. */
    fun projectorPath(): String? = importedProjector?.takeIf { File(it).isFile }

    /**
     * Everything a load needs: the language descriptor and a regular-file path for the projector.
     *
     * The caller owns the result and must close it once the engine (or probe) is done, which
     * releases the descriptor the native runtime duplicated.
     */
    fun prepare(): Prepared? {
        val language = openLanguage() ?: return null
        val projector = importProjector()
        if (projector == null) {
            language.close()
            return null
        }
        return Prepared(language, projector)
    }

    /**
     * Diagnostics: what the **metadata** says about each file, never a runtime verdict.
     *
     * Metadata can only reject; whether this pair really loads is llama.cpp's answer, reported
     * separately by the model probe. Keeping the two apart stops a "ready" metadata verdict from
     * reading like a promise that inference works.
     */
    fun describe(): String = buildString {
        append(
            validateAll().entries.joinToString(separator = ", ") { (file, validation) ->
                val verdict = when (validation) {
                    is ModelValidation.Ready -> buildString {
                        append("ready")
                        validation.architecture?.let { append("(architecture=$it)") }
                        validation.projectorType?.let { append("(projector=$it)") }
                    }

                    is ModelValidation.Invalid -> validation.toString()
                }
                "${file.role}=$verdict(source=${displayName(file) ?: "none"}, " +
                    "packing=${packing(file) ?: "unknown"})"
            },
        )
        append(", pair=")
        append(pairIssue()?.detail ?: "ok")
        append(", projectorImport=")
        append(
            projectorPath()?.let { path ->
                val file = File(path)
                "${file.name}(${formatBytes(file.length())})"
            } ?: "none",
        )
    }

    private fun projectorDirectory(): File = File(context.filesDir, PROJECTOR_DIRECTORY)

    /** Name is derived from the document and its size, so a re-pick cannot reuse a stale copy. */
    private fun projectorFileName(uri: String, size: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$uri|$size".toByteArray(Charsets.UTF_8))
        val tag = digest.take(8).joinToString(separator = "") { byte ->
            "%02x".format(java.util.Locale.US, byte.toInt() and 0xFF)
        }
        return "mmproj-$tag.gguf"
    }

    /** Removes superseded imports and abandoned partial copies so they cannot fill app storage. */
    private fun pruneProjectorImports(keep: File?) {
        val directory = projectorDirectory()
        directory.listFiles()?.forEach { file ->
            if (keep != null && file.name == keep.name) return@forEach
            if (file.isFile && runCatching { file.delete() }.getOrDefault(false)) {
                log.info(TAG, "removed superseded projector import ${file.name}")
            }
        }
    }

    private fun freeBytes(): Long = runCatching {
        context.getExternalFilesDir(null)?.usableSpace ?: context.filesDir.usableSpace
    }.getOrDefault(0L)

    /** The picked language weights, open read-only for the lifetime of a load. */
    class LanguageHandle internal constructor(
        private val descriptor: ParcelFileDescriptor,
    ) : Closeable {
        /**
         * Raw descriptor for the native loader. llama.cpp duplicates it, so the native side never
         * depends on this object staying alive at a particular moment — but it must not be closed
         * before `nativeInit` has run.
         */
        val fd: Int get() = descriptor.fd

        override fun close() {
            runCatching { descriptor.close() }
        }
    }

    /** A language descriptor plus the imported projector path, ready for one load attempt. */
    class Prepared internal constructor(
        private val language: LanguageHandle,
        val projectorPath: String,
    ) : Closeable {
        val languageFd: Int get() = language.fd

        override fun close() {
            language.close()
        }
    }

    private companion object {
        const val TAG = "ModelStore"
        const val PROJECTOR_DIRECTORY = "models"
        const val COPY_BUFFER_BYTES = 1 shl 20
    }
}
