package com.heimdall.common

/**
 * Minimal logging seam.
 *
 * Heimdall has no network permission, so there is no remote crash/telemetry pipeline by design.
 * Diagnostics are written to a bounded in-memory ring (exportable by the user via SAF) and, in the
 * app module, mirrored to a rotating file.
 */
interface HeimdallLog {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String, error: Throwable? = null)
    fun error(tag: String, message: String, error: Throwable? = null)

    companion object {
        val NONE: HeimdallLog = object : HeimdallLog {
            override fun debug(tag: String, message: String) = Unit
            override fun info(tag: String, message: String) = Unit
            override fun warn(tag: String, message: String, error: Throwable?) = Unit
            override fun error(tag: String, message: String, error: Throwable?) = Unit
        }
    }
}

/**
 * Thread-safe, bounded log buffer for on-device diagnostics.
 *
 * Bounded memory is a hard requirement: an ambient service that logs forever would leak. Oldest
 * entries are evicted first, and entries are formatted eagerly so export is a plain string read.
 */
class RingBufferLog(private val capacity: Int = 2_000) : HeimdallLog {
    private val lines = ArrayDeque<String>(capacity)
    private val lock = Any()

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    override fun debug(tag: String, message: String) = append("D", tag, message, null)

    override fun info(tag: String, message: String) = append("I", tag, message, null)

    override fun warn(tag: String, message: String, error: Throwable?) = append("W", tag, message, error)

    override fun error(tag: String, message: String, error: Throwable?) = append("E", tag, message, error)

    private fun append(level: String, tag: String, message: String, error: Throwable?) {
        val suffix = error?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        val line = "$level/$tag: $message$suffix"
        synchronized(lock) {
            if (lines.size == capacity) lines.removeFirst()
            lines.addLast(line)
        }
    }

    /** Snapshot of the buffer, oldest first. */
    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() = synchronized(lock) { lines.clear() }

    fun asText(): String = snapshot().joinToString(separator = "\n")
}
