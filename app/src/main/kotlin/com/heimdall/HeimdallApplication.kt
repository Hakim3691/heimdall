package com.heimdall

import android.app.Application

/**
 * Process entry point.
 *
 * Holds the single [AppGraph] for the process. Heimdall deliberately uses a hand-written graph
 * instead of an annotation-processor DI framework: the object graph is a dozen objects deep, and a
 * generated graph would add build time and reflection surface to a security-sensitive service for
 * no benefit (see docs/adr/0003-manual-dependency-graph.md).
 */
class HeimdallApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.log.info(TAG, "Heimdall ${BuildConfig.VERSION_NAME} starting (native=${graph.nativeStatus()})")
    }

    private companion object {
        const val TAG = "HeimdallApplication"
    }
}
