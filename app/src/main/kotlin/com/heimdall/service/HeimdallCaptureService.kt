package com.heimdall.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.heimdall.AppGraph
import com.heimdall.HeimdallApplication
import com.heimdall.R
import com.heimdall.engine.vlm.InterventionGate
import com.heimdall.engine.vlm.NativeVlmEngine
import com.heimdall.engine.vlm.PromptBuilder
import com.heimdall.engine.vlm.VlmEngine
import com.heimdall.engine.vlm.VlmException
import com.heimdall.engine.vlm.VlmRequest
import com.heimdall.model.GateResult
import com.heimdall.model.OverlayPolicy
import com.heimdall.model.RgbImage
import com.heimdall.model.VlmConfig
import com.heimdall.model.VlmOutcome
import com.heimdall.ui.OnboardingActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The ambient pipeline (FR-04): projection frames → cascade → VLM → overlay.
 *
 * Platform reality that shapes this class:
 * * `MediaProjection` consent is per session. The token arrives in the start intent, and capture can
 *   never be restarted from a boot receiver — the platform forbids it. Hence START_NOT_STICKY.
 * * Android 14+ requires a `mediaProjection` foreground service that is started *before* the
 *   projection is used, with a visible notification. That ordering is enforced here.
 * * The projection callback must be registered before `createVirtualDisplay`, otherwise the
 *   platform throws.
 *
 * Everything expensive (frame conversion, hashing, inference) runs on [Dispatchers.Default]; only
 * the overlay touch happens on the main thread.
 */
class HeimdallCaptureService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var graph: AppGraph
    private lateinit var gate: InterventionGate

    private var captureJob: Job? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var engine: VlmEngine? = null
    private var gyro: GyroMonitor? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var powerManager: PowerManager? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            graph.log.info(TAG, "projection stopped by the system or the user")
            stopAmbient("projection revoked")
        }
    }

    override fun onCreate() {
        super.onCreate()
        graph = (application as HeimdallApplication).graph
        gate = InterventionGate(graph.config.vlm, graph.config.overlay)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            stopAmbient("stop requested from notification")
            return START_NOT_STICKY
        }

        // A second consent can arrive while a session is live (e.g. the user taps Start again).
        // Starting another projection would overwrite the fields below and the old callback would
        // then tear down the new session, so an extra start is ignored outright.
        if (isRunning) {
            graph.log.info(TAG, "capture already running; ignoring a second start request")
            startForegroundNotification(getString(R.string.notification_text))
            return START_NOT_STICKY
        }

        startForegroundNotification(getString(R.string.notification_title))

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.projectionData()
        if (resultCode != Activity.RESULT_OK || data == null) {
            graph.log.warn(TAG, "start without projection consent (code=$resultCode)")
            stopAmbient("missing projection consent")
            return START_NOT_STICKY
        }
        beginCapture(resultCode, data)
        // Projection tokens are not re-deliverable; a restart without consent is impossible.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseResources()
        super.onDestroy()
    }

    // ---- startup ---------------------------------------------------------------------------

    private fun beginCapture(resultCode: Int, data: Intent) {
        // Defensive: never stack a second virtual display on top of a live session.
        if (isRunning) {
            graph.log.warn(TAG, "beginCapture ignored: a capture session is already active")
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            stopAmbient("no MediaProjectionManager")
            return
        }
        val mediaProjection = runCatching { manager.getMediaProjection(resultCode, data) }
            .onFailure { graph.log.error(TAG, "getMediaProjection failed", it) }
            .getOrNull()
        if (mediaProjection == null) {
            stopAmbient("projection token rejected")
            return
        }
        projection = mediaProjection
        // Must be registered before the virtual display is created (Android 14+ requirement).
        mediaProjection.registerCallback(projectionCallback, mainHandler)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "Heimdall",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler,
        )

        graph.session.reset()
        val createdEngine = graph.createEngine()
        engine = createdEngine
        graph.session.engineStatus = createdEngine.backendDescription
        // Accessibility is optional: when its service is not connected, the cascade derives
        // interactions from frame changes instead of silently never leaving the hesitation gate.
        graph.cascade.interactionEventsAvailable = HeimdallAccessibilityService.connected

        gyro = GyroMonitor(this, graph.cascade, graph.log).also { it.start() }
        registerThermalListener()
        graph.settings.hasStartedCapture = true

        // Warm the runtime off the main thread: the first inference would otherwise pay the whole
        // model load while the user waits. The engine stays lazy, so a failure only disables inference.
        serviceScope.launch {
            (createdEngine as? NativeVlmEngine)?.ensureInitialised()
            graph.session.engineStatus = createdEngine.backendDescription
            graph.log.info(TAG, "engine status=${graph.session.engineStatus}")
        }

        captureJob = serviceScope.launch { captureLoop() }
        graph.log.info(TAG, "capture started ${width}x$height backend=${engine?.backendDescription}")
        isRunning = true
    }

    // ---- pipeline --------------------------------------------------------------------------

    private suspend fun captureLoop() {
        val captureConfig = graph.config.capture
        val vlmConfig = graph.config.vlm
        while (currentCoroutineContext().isActive) {
            delay(captureConfig.captureIntervalMs)
            if (graph.governor.isExecutionPaused) continue

            val image = acquireFrame() ?: continue
            val analysis = try {
                ScreenFrameConverter.toRgbImage(image, captureConfig.analysisMaxEdgePx)
            } finally {
                image.close()
            } ?: continue

            val observation = graph.cascade.onFrame(analysis)
            graph.session.onFrameObserved()
            // The first frame only establishes the baseline; stability needs two consecutive frames.
            if (observation.hammingDistance == null) continue

            if (graph.cascade.evaluate() !is GateResult.Passed) continue
            graph.session.onGatePassed()
            if (!graph.governor.canRunNow()) continue

            evaluateFrame(analysis, vlmConfig)
        }
    }

    private fun acquireFrame(): Image? = runCatching { imageReader?.acquireLatestImage() }
        .onFailure { graph.log.warn(TAG, "acquireLatestImage failed", it) }
        .getOrNull()

    private suspend fun evaluateFrame(analysis: RgbImage, vlmConfig: VlmConfig) {
        val currentEngine = engine ?: return
        val square = ScreenFrameConverter.toSquare(analysis, vlmConfig.imageSize)
        val request = VlmRequest(image = square, prompt = PromptBuilder.build(graph.config.overlay))

        graph.session.onInferenceStarted()
        val response = try {
            withTimeoutOrNull(vlmConfig.inferenceTimeoutMs) { currentEngine.evaluate(request) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: VlmException) {
            graph.log.error(TAG, "inference failed", failure)
            null
        }

        graph.governor.onInferenceConsumed()
        if (response == null) {
            graph.log.warn(TAG, "inference abandoned (timeout or engine error)")
            return
        }

        when (val outcome = gate.gate(response.text)) {
            is VlmOutcome.Intervene -> {
                val spec = OverlayPolicy.specFor(outcome.intervention, graph.config.overlay)
                val shown = withContext(Dispatchers.Main) { graph.overlay.show(spec) }
                if (shown) {
                    graph.cascade.onInterventionRendered()
                    graph.session.onInterventionRendered()
                }
                graph.log.info(
                    TAG,
                    "intervention shown in ${response.latencyMs}ms (${response.backend})",
                )
            }

            is VlmOutcome.Silent -> graph.log.debug(TAG, "silent: ${outcome.reason} ${outcome.detail.orEmpty()}")
        }
    }

    // ---- thermal ---------------------------------------------------------------------------

    private fun registerThermalListener() {
        val manager = getSystemService(PowerManager::class.java) ?: return
        powerManager = manager
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            graph.governor.onThermalStatus(status)
            graph.log.info(TAG, "thermal status=$status tier=${graph.governor.tier}")
            updateNotification()
        }
        thermalListener = listener
        runCatching { manager.addThermalStatusListener(ContextCompat.getMainExecutor(this), listener) }
            .onFailure { graph.log.warn(TAG, "thermal listener unavailable", it) }
    }

    private fun unregisterThermalListener() {
        val manager = powerManager
        val listener = thermalListener
        if (manager != null && listener != null) {
            runCatching { manager.removeThermalStatusListener(listener) }
        }
        thermalListener = null
        powerManager = null
    }

    // ---- teardown --------------------------------------------------------------------------

    private fun stopAmbient(reason: String) {
        graph.log.info(TAG, "stopping ambient service: $reason")
        releaseResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseResources() {
        captureJob?.cancel()
        captureJob = null
        gyro?.stop()
        gyro = null
        unregisterThermalListener()
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        projection?.let { active ->
            runCatching { active.unregisterCallback(projectionCallback) }
            runCatching { active.stop() }
        }
        projection = null
        engine?.let { runCatching { it.close() } }
        engine = null
        graph.overlay.dismiss()
        graph.cascade.reset()
        graph.governor.reset()
        isRunning = false
    }

    // ---- notification ----------------------------------------------------------------------

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, OnboardingActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, HeimdallCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.notification_stop), stopIntent).build(),
            )
            .build()
    }

    private fun startForegroundNotification(text: String) {
        val notification = buildNotification(text)
        startForeground(
            graph.config.capture.notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
    }

    private fun updateNotification() {
        val text = if (graph.governor.isExecutionPaused) {
            getString(R.string.thermal_paused)
        } else {
            getString(R.string.notification_text)
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(graph.config.capture.notificationId, buildNotification(text)) }
    }

    /** Reads the parcelable projection token across the API 33 split without a deprecation warning. */
    @Suppress("DEPRECATION")
    private fun Intent.projectionData(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            getParcelableExtra(EXTRA_DATA)
        }

    companion object {
        private const val TAG = "HeimdallCapture"
        private const val CHANNEL_ID = "ambient_capture"

        const val ACTION_START = "com.heimdall.action.START_CAPTURE"
        const val ACTION_STOP = "com.heimdall.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "com.heimdall.extra.RESULT_CODE"
        const val EXTRA_DATA = "com.heimdall.extra.PROJECTION_DATA"

        /** True while a capture session is alive; projection consent cannot outlive it. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Builds the service start intent carrying a freshly granted projection token. */
        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, HeimdallCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
    }
}
