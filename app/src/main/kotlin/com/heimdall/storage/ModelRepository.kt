package com.heimdall.storage

import com.heimdall.common.HeimdallLog
import com.heimdall.model.ModelFile
import com.heimdall.model.ModelValidation
import com.heimdall.model.ModelValidator
import java.io.File

/**
 * Resolves and validates the GGUF weights in `/sdcard/Heimdall/models/` (FR-07).
 *
 * The files are mmap'd by llama.cpp from this directory: nothing is copied into app storage, so a
 * ~6 GB model set costs no additional disk. Validation rules live in [ModelValidator] (pure); this
 * class only performs the IO and never throws into the service.
 */
class ModelRepository(
    private val modelsDir: File,
    private val log: HeimdallLog,
) {
    fun pathFor(file: ModelFile): String = File(modelsDir, file.fileName).absolutePath

    fun exists(file: ModelFile): Boolean = File(pathFor(file)).isFile

    /** Validates every required file. IO failures degrade to [ModelValidation.Invalid]. */
    fun validateAll(): Map<ModelFile, ModelValidation> {
        val free = freeBytes()
        return ModelFile.entries.associateWith { file ->
            val handle = File(pathFor(file))
            if (!handle.isFile) {
                return@associateWith ModelValidation.Invalid(file, com.heimdall.model.ModelIssue.MISSING)
            }
            runCatching {
                val header = ByteArray(4)
                val read = handle.inputStream().use { it.read(header) }
                ModelValidator.validate(
                    file = file,
                    sizeBytes = handle.length(),
                    header = if (read > 0) header.copyOf(read) else ByteArray(0),
                    freeBytes = free,
                )
            }.getOrElse { error ->
                log.warn(TAG, "could not inspect ${file.fileName}", error)
                ModelValidation.Invalid(file, com.heimdall.model.ModelIssue.MISSING, error.message)
            }
        }
    }

    fun allReady(): Boolean = validateAll().values.all { it is ModelValidation.Ready }

    fun absent(): List<ModelFile> = validateAll().filterValues { it !is ModelValidation.Ready }.keys.toList()

    fun freeBytes(): Long = runCatching { modelsDir.usableSpace }
        .getOrElse { 0L }

    fun describe(): String = validateAll().entries.joinToString(separator = ", ") { (file, validation) ->
        "${file.fileName}=${if (validation is ModelValidation.Ready) "ready" else validation.toString()}"
    }

    private companion object {
        const val TAG = "ModelRepository"
    }
}
