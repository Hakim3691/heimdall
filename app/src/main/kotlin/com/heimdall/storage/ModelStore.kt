package com.heimdall.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.heimdall.common.HeimdallLog
import com.heimdall.model.GgufMetadata
import com.heimdall.model.ModelFile
import com.heimdall.model.ModelIssue
import com.heimdall.model.ModelValidation
import com.heimdall.model.ModelValidator
import com.heimdall.settings.SettingsStore
import java.io.Closeable
import java.io.File

/**
 * Resolves and validates the two GGUF weights from documents the user picked in the system picker
 * (FR-07).
 *
 * Scoped storage means the old `/sdcard/Heimdall/models/` contract could never work: an app on
 * Android 12+ cannot open arbitrary paths on shared storage. Instead the Storage Access Framework
 * hands Heimdall a durable read-only grant, and the file is used **in place** — nothing is copied,
 * moved or deleted.
 *
 * llama.cpp still needs a path it can `open()` and `mmap()`, so [mount] holds the document
 * descriptors open for the capture session and exposes `/proc/self/fd/<n>`. Closing the returned
 * [Mount] drops the grant with the session. A cloud-only document is a pipe, cannot be mmapped, and
 * is rejected rather than silently loaded as garbage.
 */
class ModelStore(
    private val context: Context,
    private val settings: SettingsStore,
    private val log: HeimdallLog,
) {
    private val resolver: ContentResolver get() = context.contentResolver

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
        val name = displayName(file) ?: return null
        val uri = uriFor(file) ?: return name
        return runCatching {
            val size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            val architecture = resolver.openInputStream(uri)?.use { stream ->
                val prefix = ByteArray(ModelValidator.METADATA_PREFIX_BYTES)
                val read = stream.read(prefix)
                if (read <= 0) null else GgufMetadata.parse(prefix.copyOf(read))?.architecture
            }
            buildString {
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
        }.getOrDefault(name)
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
        log.info(TAG, "selected ${file.fileName} from $uri")
    }

    fun forget(file: ModelFile) {
        settings.setModelUri(file.role, null)
    }

    fun validateAll(): Map<ModelFile, ModelValidation> = ModelFile.entries.associateWith { validate(it) }

    fun allReady(): Boolean = validateAll().values.all { it is ModelValidation.Ready }

    fun absent(): List<ModelFile> = validateAll().filterValues { it !is ModelValidation.Ready }.keys.toList()

    /** Validates one selected document without ever throwing into the service. */
    fun validate(file: ModelFile): ModelValidation {
        val uri = uriFor(file) ?: return ModelValidation.Invalid(file, ModelIssue.MISSING, "no file selected")
        return runCatching {
            val size = resolver.openFileDescriptor(uri, "r")?.use { descriptor -> descriptor.statSize } ?: -1L
            if (size <= 0L) {
                return@runCatching ModelValidation.Invalid(
                    file,
                    ModelIssue.MISSING,
                    "the document has no readable size",
                )
            }
            // Read a metadata prefix, not just the magic: the architecture check needs it, and a
            // 64 KiB read is negligible next to the multi-gigabyte weights.
            val prefix = ByteArray(ModelValidator.METADATA_PREFIX_BYTES)
            val read = resolver.openInputStream(uri)?.use { stream -> stream.read(prefix) } ?: 0
            ModelValidator.validate(
                file = file,
                sizeBytes = size,
                header = if (read > 0) prefix.copyOf(read) else ByteArray(0),
                freeBytes = freeBytes(),
            )
        }.getOrElse { error ->
            log.warn(TAG, "could not inspect ${file.fileName}", error)
            ModelValidation.Invalid(file, ModelIssue.MISSING, error.message ?: "the document is unreadable")
        }
    }

    /**
     * Opens both documents and returns the paths llama.cpp can mmap, or null when either document
     * cannot be exposed as a regular local file.
     *
     * The caller owns the result and must close it once inference has stopped; closing releases the
     * raw descriptors that keep `/proc/self/fd/<n>` valid.
     */
    fun mount(): Mount? {
        val language = open(ModelFile.LANGUAGE) ?: return null
        val vision = open(ModelFile.VISION)
        if (vision == null) {
            language.close()
            return null
        }
        return Mount(language, vision)
    }

    /** Diagnostics line: selected source per role plus the validation verdict. */
    fun describe(): String = validateAll().entries.joinToString(separator = ", ") { (file, validation) ->
        val verdict = if (validation is ModelValidation.Ready) "ready" else validation.toString()
        "${file.fileName}=$verdict(source=${displayName(file) ?: "none"})"
    }

    private fun open(file: ModelFile): MountedFile? {
        val uri = uriFor(file) ?: return null
        return runCatching {
            val descriptor = resolver.openFileDescriptor(uri, "r") ?: return@runCatching null
            val path = "/proc/self/fd/${descriptor.fd}"
            // A pipe (cloud provider) reports length 0 and can never be loaded with mmap.
            val length = File(path).length()
            if (length <= 0L) {
                descriptor.close()
                log.warn(TAG, "${file.fileName} is not a local document (reported size=$length)")
                return@runCatching null
            }
            MountedFile(file, path, descriptor)
        }.getOrElse { error ->
            log.warn(TAG, "could not open ${file.fileName}", error)
            null
        }
    }

    private fun freeBytes(): Long = runCatching {
        context.getExternalFilesDir(null)?.usableSpace ?: context.filesDir.usableSpace
    }.getOrDefault(0L)

    /** Live zero-copy view of the two selected documents. */
    class Mount internal constructor(
        private val language: MountedFile,
        private val vision: MountedFile,
    ) : Closeable {
        val languagePath: String get() = language.path
        val visionPath: String get() = vision.path

        override fun close() {
            language.close()
            vision.close()
        }
    }

    /** One open descriptor and the `/proc/self/fd/<n>` alias handed to the native runtime. */
    class MountedFile internal constructor(
        val file: ModelFile,
        val path: String,
        private val descriptor: ParcelFileDescriptor,
    ) : Closeable {
        override fun close() {
            runCatching { descriptor.close() }
        }
    }

    private companion object {
        const val TAG = "ModelStore"
    }
}
