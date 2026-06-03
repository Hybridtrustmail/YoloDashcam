package com.dashcam

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.common.util.concurrent.ListenableFuture

class DashcamService : LifecycleService() {
    inner class LocalBinder : Binder() {
        val service: DashcamService get() = this@DashcamService
    }

    interface Listener {
        fun onState(monitoring: Boolean, recording: Boolean, status: String)
    }

    private val binder = LocalBinder()
    private var listener: Listener? = null

    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var pendingSurface: Preview.SurfaceProvider? = null
    private var lastBoundSurface: Preview.SurfaceProvider? = null
    private var cameraBound = false
    private var lastBoundWithPreview = false

    private lateinit var recordingWorker: RecordingWorker
    private lateinit var clipStorage: ClipStorage
    private lateinit var clipAnalysisWorker: ClipAnalysisWorker
    private lateinit var sensorDetector: SensorMotionDetector
    private lateinit var deviceSensorManager: DeviceSensorManager
    private lateinit var clipTelemetryLogger: ClipTelemetryLogger
    private lateinit var settings: Settings

    @Volatile var monitoring = false
        private set
    @Volatile var isRecording = false
        private set
    @Volatile var analyzePrevious = false
        private set
    @Volatile private var startImmediately = false
    @Volatile private var manualRecording = false
    @Volatile private var pendingStopAfterRecording = false
    @Volatile private var shutdownAfterStop = false
    @Volatile var isParkingMode = false
        private set
    @Volatile var parkingModeEnabled = false

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastMotionMs = 0L
    private var lowSpeedSinceMs = 0L   // elapsedRealtime when speed dropped to parking threshold

    private val stopCheck = object : Runnable {
        override fun run() {
            val stopDelay = if (isParkingMode) STOP_DELAY_PARK_MS else STOP_DELAY_DRIVE_MS
            if (monitoring && isRecording &&
                !manualRecording &&
                SystemClock.elapsedRealtime() - lastMotionMs > stopDelay
            ) {
                recordingWorker.stop()
            }
            if (monitoring) handler.postDelayed(this, 1000)
        }
    }

    private val recordingListener = object : RecordingWorker.Listener {
        override fun onSegmentStarted(baseName: String, csvFile: java.io.File) {
            isRecording = true
            clipTelemetryLogger.start(clipStorage.telemetryFileFor(baseName))
            updateNotification()
            pushState()
        }

        override fun onSegmentFinalized(videoFile: java.io.File) {
            Log.d(TAG, "Service saved clip: ${videoFile.name}")
            clipTelemetryLogger.stop()
            if (analyzePrevious) {
                val base = videoFile.name.substringBeforeLast('.')
                clipAnalysisWorker.enqueue(
                    videoFile,
                    clipStorage.csvFileFor(base),
                    clipStorage.telemetryFileFor(base)
                )
            }
        }

        override fun onRecordingStopped() {
            isRecording = false
            clipTelemetryLogger.stop()
            if (pendingStopAfterRecording) {
                if (shutdownAfterStop) {
                    completeStopMonitoring()
                } else {
                    completeSoftStopMonitoring()
                }
                return
            }
            // Still monitoring (e.g. motion lull): VideoCapture stays bound, so
            // the next motion can start a new segment with no rebind needed.
            updateNotification()
            pushState()
        }

        override fun onError(message: String) {
            Log.e(TAG, message)
            isRecording = false
            clipTelemetryLogger.stop()
            if (pendingStopAfterRecording) {
                if (shutdownAfterStop) {
                    completeStopMonitoring()
                } else {
                    completeSoftStopMonitoring()
                }
                return
            }
            updateNotification()
            pushState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        clipStorage = ClipStorage(this)
        settings = Settings(this)
        createRecordingWorker()
        clipAnalysisWorker = ClipAnalysisWorker(this)
        sensorDetector = SensorMotionDetector(this) { onMotion() }
        deviceSensorManager = DeviceSensorManager(this).apply {
            setListener(object : DeviceSensorManager.SensorDataListener {
                override fun onSensorDataUpdate(
                    lat: Double,
                    lon: Double,
                    gpsSpeed: Float,
                    deviceSpeed: Float,
                    bearing: Float,
                    acceleration: Float
                ) {
                    clipTelemetryLogger.updateSensorData(
                        lat, lon, gpsSpeed, deviceSpeed, bearing, acceleration
                    )
                    checkGForce(acceleration)
                    if (parkingModeEnabled) updateParkingMode(gpsSpeed)
                }

                override fun onStationaryStatusChanged(isStationary: Boolean) {
                }
            })
        }
        clipTelemetryLogger = ClipTelemetryLogger()
        createChannel()
        initCamera()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
        pushState()
    }

    fun setPreviewSurface(surface: Preview.SurfaceProvider?) {
        pendingSurface = surface
        // Aiming preview is shown ONLY while idle (not monitoring). During a
        // recording session the pipeline stays headless and is never touched
        // from here, which is what keeps screen on/off stable while recording.
        if (!monitoring && surface != null) {
            bindAimingPreview()
        }
    }

    fun setAnalyzePrevious(enabled: Boolean) {
        analyzePrevious = enabled
        pushState()
    }

    fun ensureCamera() {
        if (provider == null) {
            initCamera()
        } else if (monitoring && !cameraBound) {
            bindUseCases()
        }
    }

    fun requestPreviewRefresh() {
        // While monitoring, the pipeline is headless (VideoCapture only) by
        // design - there is no preview to refresh. Touching the camera here on
        // every screen on/off is exactly what stopped/restarted the recording
        // and froze the app, so this is a hard no-op during a recording session.
        if (monitoring) return
        // Idle (aiming) preview only: re-bind the preview-only pipeline so the
        // user can frame the camera before recording starts.
        if (pendingSurface != null) bindAimingPreview()
    }

    fun startMonitoring() {
        startMonitoring(false)
    }

    fun startMonitoring(startImmediately: Boolean) {
        if (monitoring) return
        prepareForNewMonitoringSession()
        monitoring = true
        this.startImmediately = startImmediately
        manualRecording = startImmediately
        goForeground()
        acquireWake()
        sensorDetector.start()
        deviceSensorManager.startSensors()
        if (provider == null) {
            initCamera()
        } else {
            bindUseCases()
        }
        lastMotionMs = SystemClock.elapsedRealtime()
        handler.postDelayed(stopCheck, 1000)
        pushState()
    }

    fun stopMonitoring() {
        stopMonitoring(false)
    }

    fun stopAndShutdown() {
        stopMonitoring(true)
    }

    private fun stopMonitoring(shutdown: Boolean) {
        if (!monitoring) return
        monitoring = false
        manualRecording = false
        shutdownAfterStop = shutdown
        handler.removeCallbacks(stopCheck)
        sensorDetector.stop()
        deviceSensorManager.stopSensors()
        startImmediately = false
        if (recordingWorker.isRecording) {
            pendingStopAfterRecording = true
            recordingWorker.stop()
            updateNotification()
            pushState()
            return
        }
        clipTelemetryLogger.stop()
        if (shutdown) {
            completeStopMonitoring()
        } else {
            completeSoftStopMonitoring()
        }
    }

    fun shutdownIdleSession() {
        if (monitoring) {
            stopAndShutdown()
            return
        }
        if (!cameraBound && !lastBoundWithPreview && lastBoundSurface == null) {
            return
        }
        completeStopMonitoring()
    }

    private fun completeSoftStopMonitoring() {
        pendingStopAfterRecording = false
        shutdownAfterStop = false
        clipTelemetryLogger.stop()
        releaseWake()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Back to idle: show the aiming preview again so the camera isn't black.
        if (pendingSurface != null) bindAimingPreview()
        pushState()
    }

    private fun completeStopMonitoring() {
        pendingStopAfterRecording = false
        shutdownAfterStop = false
        clipTelemetryLogger.stop()
        lastBoundWithPreview = false
        lastBoundSurface = null
        provider?.unbindAll()
        cameraBound = false
        recordingWorker.close()
        createRecordingWorker()
        releaseWake()
        stopForeground(STOP_FOREGROUND_REMOVE)
        pushState()
        stopSelf()
    }

    private fun createRecordingWorker() {
        recordingWorker = RecordingWorker(this, clipStorage, settings.recordingQuality)
        recordingWorker.setListener(recordingListener)
    }

    private fun prepareForNewMonitoringSession() {
        pendingStopAfterRecording = false
        shutdownAfterStop = false
        lastBoundWithPreview = false
        lastBoundSurface = null
        cameraBound = false
        provider?.unbindAll()
        recordingWorker.close()
        createRecordingWorker()
    }

    private fun onMotion() {
        handler.post {
            if (!monitoring) return@post
            lastMotionMs = SystemClock.elapsedRealtime()
            if (!recordingWorker.isRecording && recordingWorker.getVideoCapture() != null) {
                recordingWorker.start()
            }
        }
    }

    private fun initCamera() {
        val future: ListenableFuture<ProcessCameraProvider> =
            ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                provider = future.get()
                if (monitoring) {
                    bindUseCases()
                } else if (pendingSurface != null) {
                    bindAimingPreview()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Service camera provider failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Recording pipeline: VideoCapture ONLY (headless, no Preview).
     * Never combines Preview with VideoCapture - this device rejects the
     * combined use-case set, and headless recording is immune to the preview
     * surface being destroyed on screen-off.
     */
    private fun bindUseCases() {
        val cameraProvider = provider ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                recordingWorker.getVideoCapture()
            )
            preview = null
            lastBoundWithPreview = false
            lastBoundSurface = null
            cameraBound = true
            Log.d(TAG, "Service bound headless video pipeline")
            if (monitoring && startImmediately && !recordingWorker.isRecording) {
                startImmediately = false
                recordingWorker.start()
            }
        } catch (e: Exception) {
            cameraBound = false
            Log.e(TAG, "Service use case binding failed", e)
        }
    }

    /**
     * Aiming pipeline: Preview ONLY, used while idle (not monitoring) so the
     * user can frame the camera before recording. Replaced by the headless
     * video pipeline the moment monitoring starts.
     */
    private fun bindAimingPreview() {
        if (monitoring) return
        val surface = pendingSurface ?: return
        val cameraProvider = provider ?: run { initCamera(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            cameraProvider.unbindAll()
            val pv = Preview.Builder().build().also { it.setSurfaceProvider(surface) }
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv)
            preview = pv
            lastBoundWithPreview = true
            lastBoundSurface = surface
            cameraBound = false
            Log.d(TAG, "Service bound aiming preview (idle)")
        } catch (e: Exception) {
            Log.e(TAG, "Aiming preview bind failed", e)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Dashcam",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = when {
            isParkingMode && isRecording -> "Parking mode: recording motion event"
            isParkingMode -> "Parking mode: monitoring for motion"
            isRecording -> "Recording"
            analyzePrevious -> "Monitoring + analyzing previous clips"
            else -> "Monitoring motion"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Dashcam")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun goForeground() {
        var type = 0
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), type)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun updateNotification() {
        if (!monitoring) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun acquireWake() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dashcam:monitor")
        }
        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWake() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    /** Basename of the clip currently being recorded, null otherwise. */
    fun getCurrentBaseName(): String? = recordingWorker.getCurrentBaseName()

    fun getElapsedSegmentSeconds(): Int = recordingWorker.getElapsedSegmentSeconds()
    fun getSegmentSeconds(): Int = recordingWorker.getSegmentSeconds()

    private fun checkGForce(accelerationMs2: Float) {
        if (!isRecording || accelerationMs2 < GFORCE_THRESHOLD_MS2) return
        val baseName = recordingWorker.getCurrentBaseName() ?: return
        if (!clipStorage.isProtected(baseName)) {
            clipStorage.protectClip(baseName)
            Log.d(TAG, "G-force auto-locked clip: $baseName (accel=${accelerationMs2}m/s²)")
            updateNotification()
            pushState()
        }
    }

    private fun updateParkingMode(gpsSpeedKmh: Float) {
        val now = SystemClock.elapsedRealtime()
        when {
            gpsSpeedKmh > DRIVE_SPEED_KMH -> {
                lowSpeedSinceMs = 0L
                if (isParkingMode) {
                    isParkingMode = false
                    Log.d(TAG, "Parking mode OFF (speed ${gpsSpeedKmh} km/h)")
                    updateNotification()
                    pushState()
                }
            }
            gpsSpeedKmh < PARKING_SPEED_KMH -> {
                if (lowSpeedSinceMs == 0L) lowSpeedSinceMs = now
                if (!isParkingMode && now - lowSpeedSinceMs > PARKING_TIMEOUT_MS) {
                    isParkingMode = true
                    Log.d(TAG, "Parking mode ON")
                    updateNotification()
                    pushState()
                }
            }
        }
    }

    private fun pushState() {
        listener?.onState(monitoring, isRecording, when {
            isParkingMode && isRecording -> "Parking: Recording"
            isParkingMode -> "Parking mode"
            isRecording -> "Recording"
            monitoring && analyzePrevious && manualRecording -> "Recording + analyzing"
            monitoring && analyzePrevious -> "Monitoring + analyzing"
            monitoring && manualRecording -> "Recording armed"
            pendingStopAfterRecording -> "Stopping"
            monitoring -> "Monitoring"
            else -> "Idle"
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(stopCheck)
        sensorDetector.stop()
        deviceSensorManager.stopSensors()
        clipTelemetryLogger.close()
        releaseWake()
        recordingWorker.close()
        clipAnalysisWorker.close()
    }

    companion object {
        private const val TAG = "DashcamService"
        private const val CHANNEL_ID = "dashcam_service"
        private const val NOTIF_ID = 1001
        private const val STOP_DELAY_DRIVE_MS = 6_000L
        private const val STOP_DELAY_PARK_MS  = 30_000L
        private const val GFORCE_THRESHOLD_MS2 = 15f   // ~1.5g shock
        private const val PARKING_SPEED_KMH = 3f
        private const val DRIVE_SPEED_KMH   = 5f
        private const val PARKING_TIMEOUT_MS = 60_000L // 60s at low speed → parking mode
    }
}
