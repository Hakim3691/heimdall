package com.heimdall.testing

import com.heimdall.common.HeimdallLog

/** Records log lines so scenarios can assert on warnings without touching logcat. */
class RecordingLog : HeimdallLog {
    private val entries = mutableListOf<String>()

    val lines: List<String> get() = entries.toList()

    override fun debug(tag: String, message: String) {
        entries += "D/$tag: $message"
    }

    override fun info(tag: String, message: String) {
        entries += "I/$tag: $message"
    }

    override fun warn(tag: String, message: String, error: Throwable?) {
        entries += "W/$tag: $message${error?.let { " (${it.javaClass.simpleName})" } ?: ""}"
    }

    override fun error(tag: String, message: String, error: Throwable?) {
        entries += "E/$tag: $message${error?.let { " (${it.javaClass.simpleName})" } ?: ""}"
    }
}
