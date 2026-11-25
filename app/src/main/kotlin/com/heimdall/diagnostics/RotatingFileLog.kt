package com.heimdall.diagnostics

import com.heimdall.common.HeimdallLog
import java.io.File

/**
 * Mirror of every log line into a size-capped file the user can export.
 *
 * Heimdall has no network permission, so on-device diagnostics are the only debugging channel for a
 * sideloaded install. The file is capped at [MAX_BYTES] and rotated once so an ambient service can
 * never fill the user's storage.
 */
class RotatingFileLog(
    private val file: File,
    private val delegate: HeimdallLog,
    private val maxBytes: Long = MAX_BYTES,
) : HeimdallLog {
    private val lock = Any()

    init {
        runCatching { file.parentFile?.mkdirs() }
    }

    override fun debug(tag: String, message: String) {
        delegate.debug(tag, message)
        write("D", tag, message, null)
    }

    override fun info(tag: String, message: String) {
        delegate.info(tag, message)
        write("I", tag, message, null)
    }

    override fun warn(tag: String, message: String, error: Throwable?) {
        delegate.warn(tag, message, error)
        write("W", tag, message, error)
    }

    override fun error(tag: String, message: String, error: Throwable?) {
        delegate.error(tag, message, error)
        write("E", tag, message, error)
    }

    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        val line = "$level/$tag: $message${error?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""}\n"
        synchronized(lock) {
            runCatching {
                if (file.length() + line.length > maxBytes) rotate()
                file.appendText(line)
            }
            // A failed log write must never take the service down.
        }
    }

    private fun rotate() {
        val previous = File(file.parentFile, "${file.name}.1")
        if (previous.exists()) previous.delete()
        file.renameTo(previous)
    }

    private companion object {
        const val MAX_BYTES = 512L * 1024L
    }
}
