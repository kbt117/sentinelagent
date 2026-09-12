package com.sentinelagent.debug

import android.Manifest
import android.app.Activity
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.*

class CaptureService : Service() {

    companion object {
        private const val TAG = "SentinelAgent"
        private const val NOTIFICATION_ID = 1001

        // Commands
        const val ACTION_START = "com.sentinelagent.debug.ACTION_START"
        const val ACTION_STOP = "com.sentinelagent.debug.ACTION_STOP"

        // State broadcasts sent to MainActivity so the UI always reflects
        // what the service is actually doing (running / stopped / error).
        const val ACTION_SERVICE_STARTED = "com.sentinelagent.debug.ACTION_SERVICE_STARTED"
        const val ACTION_SERVICE_STOPPED = "com.sentinelagent.debug.ACTION_SERVICE_STOPPED"
        const val ACTION_SERVICE_ERROR = "com.sentinelagent.debug.ACTION_SERVICE_ERROR"
        const val ACTION_SERVICE_STATUS = "com.sentinelagent.debug.ACTION_SERVICE_STATUS"

        // Optional human-readable message carried by the state broadcasts
        const val EXTRA_STATUS_MESSAGE = "extra_status_message"

        // Camera modes
        const val CAMERA_OFF = "off"
        const val CAMERA_FRONT = "front"
        const val CAMERA_REAR = "rear"

        // Intent extras
        const val EXTRA_SERVER_URL = "extra_server_url"
        const val EXTRA_INTERVAL_SECONDS = "extra_interval_seconds"
        const val EXTRA_CAMERA_MODE = "extra_camera_mode"
        const val EXTRA_MIC_ENABLED = "extra_mic_enabled"
        const val EXTRA_SENSORS_ENABLED = "extra_sensors_enabled"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"

        // Static running flag accessible from MainActivity
        @Volatile
        var isRunning = false
    }

    // Coroutine scope tied to service lifetime
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Configuration
    private var serverUrl: String = "https://example.com/upload"
    private var intervalSeconds: Int = 10
    private var cameraMode: String = CAMERA_OFF
    private var micEnabled: Boolean = false
    private var sensorsEnabled: Boolean = false

    // Managers
    private var screenCaptureManager: ScreenCaptureManager? = null
    private var cameraCaptureManager: CameraCaptureManager? = null
    private var audioCaptureManager: AudioCaptureManager? = null
    private var sensorCaptureManager: SensorCaptureManager? = null

    // MediaProjection
    private var mediaProjection: MediaProjection? = null

    // Whether startForeground() has completed for the current instance.
    // After a startForegroundService() call Android requires startForeground()
    // to be called even if we decide to stop right away, otherwise the system
    // throws "did not then call startForeground()" and crashes the app.
    private var foregroundStarted = false

    // Wake lock to keep CPU alive
    private var wakeLock: PowerManager.WakeLock? = null

    // Coroutine jobs for each capture loop
    private var screenshotJob: Job? = null
    private var cameraJob: Job? = null
    private var audioJob: Job? = null
    private var sensorJob: Job? = null

    // Set when the service stops for a reason the UI should explain (start
    // failure, projection revoked). Carried into the ACTION_SERVICE_STOPPED
    // broadcast from onDestroy() so the explanation is never lost.
    private var stopReason: String? = null

    // Fires when the projection ends: user taps the system "stop sharing"
    // chip, the device is locked (Android 15+), or the system revokes it.
    // A dead MediaProjection token can NEVER be reused, and the old consent
    // Intent cannot mint a new one — the user must tap Start again so
    // MainActivity shows a fresh createScreenCaptureIntent() dialog.
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped by system/user")
            stopReason = getString(R.string.status_reconsent)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CaptureService onCreate")

        // Acquire wake lock
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SentinelAgent::CaptureWakeLock"
        )
        wakeLock?.acquire(10 * 60 * 1000L) // acquire for up to 10 minutes, will be refreshed
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "CaptureService onStartCommand action=${intent?.action} startId=$startId")

        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                Log.d(TAG, "Received STOP action")
                satisfyForegroundObligationIfAny()
                stopSelf()
            }
            else -> {
                // Null intent (system restart after process death) or unknown
                // action. The MediaProjection consent result cannot survive
                // process death, so there is nothing to resume here: satisfy
                // any pending startForeground() obligation and shut down
                // instead of leaving a zombie service that the system will
                // kill with "did not call startForeground()".
                Log.w(TAG, "No start data (action=${intent?.action}) - stopping service")
                satisfyForegroundObligationIfAny()
                stopSelf()
            }
        }

        // START_NOT_STICKY on purpose: a sticky restart delivers a null intent
        // and the projection permission is gone anyway, so an automatic
        // restart can only end in another crash.
        return START_NOT_STICKY
    }

    /**
     * Full start path, guarded end-to-end. Any failure is reported to the UI
     * via an ACTION_SERVICE_ERROR broadcast instead of crashing the process
     * (which used to leave the app showing "Not running" with no explanation).
     */
    private fun handleStart(intent: Intent) {
        try {
            // Extract configuration from intent
            serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: serverUrl
            intervalSeconds = intent.getIntExtra(EXTRA_INTERVAL_SECONDS, intervalSeconds)
            cameraMode = intent.getStringExtra(EXTRA_CAMERA_MODE) ?: CAMERA_OFF
            micEnabled = intent.getBooleanExtra(EXTRA_MIC_ENABLED, false)
            sensorsEnabled = intent.getBooleanExtra(EXTRA_SENSORS_ENABLED, false)

            // NOTE: Activity.RESULT_OK is -1, so the "missing" default MUST be
            // RESULT_CANCELED — defaulting to -1 would reject every grant.
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION")
            val projectionData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
            }

            Log.d(TAG, "Config: url=$serverUrl, interval=${intervalSeconds}s, " +
                    "camera=$cameraMode, mic=$micEnabled, sensors=$sensorsEnabled")

            // 1. Promote to a foreground service of the matching type FIRST.
            //    On Android 14+ (API 34, targetSdk 34) getMediaProjection()
            //    throws SecurityException unless a mediaProjection-type
            //    foreground service is already running.
            startForegroundInternal()

            // 2. Create the MediaProjection from the user-approved consent
            //    result. Must happen AFTER startForeground() on Android 14+.
            //    This consent token is single-session: it is valid only in
            //    this process and only for this start — never persist it
            //    (no SharedPreferences, disk, or cross-restart statics).
            //    Every new capture session needs a fresh
            //    createScreenCaptureIntent() grant from MainActivity.
            if (resultCode != Activity.RESULT_OK || projectionData == null) {
                throw IllegalStateException(
                    "Missing MediaProjection consent data " +
                            "(resultCode=$resultCode, hasData=${projectionData != null}). " +
                            "Fresh screen-capture consent is required for every session."
                )
            }
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, projectionData)
            projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            mediaProjection = projection

            isRunning = true
            broadcastServiceState(ACTION_SERVICE_STARTED)

            // Initialize managers and start capture loops
            initializeAndStartCapture()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start capture service", t)
            isRunning = false
            // Remember the message: onDestroy() will broadcast STOPPED right
            // after this, and without stopReason it would wipe the error text
            // back to a bare "Not running".
            val message = "Start failed: ${t.message ?: t.javaClass.simpleName}"
            stopReason = message
            broadcastServiceState(ACTION_SERVICE_ERROR, message)
            stopSelf()
        }
    }

    /**
     * Build the foreground notification and call startForeground() with the
     * exact set of FGS types this run needs:
     * - mediaProjection is always required (screen capture is the core mode);
     * - microphone / camera types only when those captures are enabled AND
     *   the corresponding runtime permission is currently granted. Requesting
     *   a type without its permission throws SecurityException on Android 14+.
     */
    private fun startForegroundInternal() {
        val notification = NotificationHelper.buildNotification(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (micEnabled && hasPermission(Manifest.permission.RECORD_AUDIO)) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                if (cameraMode != CAMERA_OFF && hasPermission(Manifest.permission.CAMERA)) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
            }
            Log.d(TAG, "startForeground with FGS type bitmask=$fgsType")
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    /**
     * Safety net for starts that carry no ACTION_START payload: if this
     * instance was launched with startForegroundService() we MUST call
     * startForeground() before stopping, or the system crashes the app.
     */
    private fun satisfyForegroundObligationIfAny() {
        if (foregroundStarted) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    NotificationHelper.buildNotification(this),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, NotificationHelper.buildNotification(this))
            }
            foregroundStarted = true
        } catch (t: Throwable) {
            Log.e(TAG, "Could not satisfy foreground obligation: ${t.message}")
        }
    }

    private fun hasPermission(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun initializeAndStartCapture() {
        serviceScope.launch {
            try {
                // Get screen dimensions (fall back to resource metrics if the
                // newer window-metrics APIs misbehave on some OEM ROMs)
                val (screenWidth, screenHeight, densityDpi) = resolveDisplayMetrics()
                Log.d(TAG, "Screen: ${screenWidth}x${screenHeight} @ ${densityDpi}dpi")

                // Initialize ScreenCaptureManager. A failure here must not
                // kill the whole service silently — report it in the UI and
                // keep the remaining captures alive.
                try {
                    val projection = mediaProjection
                    if (projection != null) {
                        screenCaptureManager = ScreenCaptureManager(
                            projection,
                            screenWidth,
                            screenHeight,
                            densityDpi
                        )
                        Log.d(TAG, "ScreenCaptureManager initialized")
                    } else {
                        broadcastServiceState(ACTION_SERVICE_STATUS, "Screen capture unavailable")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to initialize ScreenCaptureManager: ${t.message}")
                    screenCaptureManager = null
                    broadcastServiceState(
                        ACTION_SERVICE_STATUS,
                        "Screen capture unavailable: ${t.message ?: t.javaClass.simpleName}"
                    )
                }

                // Initialize CameraCaptureManager if needed
                if (cameraMode != CAMERA_OFF) {
                    try {
                        val facing = when (cameraMode) {
                            CAMERA_FRONT -> CameraCaptureManager.FACING_FRONT
                            CAMERA_REAR -> CameraCaptureManager.FACING_REAR
                            else -> CameraCaptureManager.FACING_REAR
                        }
                        cameraCaptureManager = CameraCaptureManager(this@CaptureService, facing)
                        cameraCaptureManager?.openCamera()
                        Log.d(TAG, "CameraCaptureManager initialized with facing=$facing")
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to initialize CameraCaptureManager: ${t.message}")
                        cameraCaptureManager = null
                        broadcastServiceState(ACTION_SERVICE_STATUS, "Camera capture unavailable")
                    }
                }

                // Initialize AudioCaptureManager if needed
                if (micEnabled) {
                    try {
                        audioCaptureManager = AudioCaptureManager(16000)
                        audioCaptureManager?.start()
                        Log.d(TAG, "AudioCaptureManager initialized and started")
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to initialize AudioCaptureManager: ${t.message}")
                        audioCaptureManager = null
                        broadcastServiceState(ACTION_SERVICE_STATUS, "Microphone capture unavailable")
                    }
                }

                // Initialize SensorCaptureManager if needed
                if (sensorsEnabled) {
                    try {
                        sensorCaptureManager = SensorCaptureManager(this@CaptureService)
                        sensorCaptureManager?.start()
                        Log.d(TAG, "SensorCaptureManager initialized and started")
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to initialize SensorCaptureManager: ${t.message}")
                        sensorCaptureManager = null
                        broadcastServiceState(ACTION_SERVICE_STATUS, "Sensor capture unavailable")
                    }
                }

                // Start all capture loops
                startCaptureLoops()
            } catch (e: CancellationException) {
                Log.d(TAG, "Capture initialization cancelled")
            } catch (t: Throwable) {
                Log.e(TAG, "Capture initialization failed", t)
                broadcastServiceState(
                    ACTION_SERVICE_ERROR,
                    "Init failed: ${t.message ?: t.javaClass.simpleName}"
                )
                stopSelf()
            }
        }
    }

    private fun resolveDisplayMetrics(): Triple<Int, Int, Int> {
        return try {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                val display = windowManager.defaultDisplay
                @Suppress("DEPRECATION")
                display.getMetrics(dm)
                Triple(bounds.width(), bounds.height(), dm.densityDpi)
            } else {
                @Suppress("DEPRECATION")
                val display = windowManager.defaultDisplay
                @Suppress("DEPRECATION")
                display.getMetrics(dm)
                Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Window metrics unavailable, using resource metrics: ${t.message}")
            val dm = resources.displayMetrics
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    private fun startCaptureLoops() {
        Log.d(TAG, "Starting capture loops")

        // Screenshot capture loop
        screenshotJob = serviceScope.launch {
            Log.d(TAG, "Screenshot loop started")
            while (isActive && isRunning) {
                try {
                    val scm = screenCaptureManager
                    if (scm != null) {
                        val bitmap = scm.captureScreen()
                        if (bitmap != null) {
                            Log.d(TAG, "Captured screenshot ${bitmap.width}x${bitmap.height}")
                            UploadManager.uploadImage(bitmap, "screenshot", serverUrl)
                            bitmap.recycle()
                        } else {
                            Log.w(TAG, "Screenshot capture returned null")
                        }
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "Screenshot loop cancelled")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Screenshot loop error: ${e.message}")
                }
                delay(intervalSeconds * 1000L)
            }
            Log.d(TAG, "Screenshot loop ended")
        }

        // Camera capture loop (if enabled)
        if (cameraMode != CAMERA_OFF) {
            cameraJob = serviceScope.launch {
                Log.d(TAG, "Camera loop started")
                while (isActive && isRunning) {
                    try {
                        val ccm = cameraCaptureManager
                        if (ccm != null) {
                            val bitmap = ccm.captureStill()
                            if (bitmap != null) {
                                Log.d(TAG, "Captured camera still ${bitmap.width}x${bitmap.height}")
                                UploadManager.uploadImage(bitmap, "camera", serverUrl)
                                bitmap.recycle()
                            } else {
                                Log.w(TAG, "Camera capture returned null")
                            }
                        }
                    } catch (e: CancellationException) {
                        Log.d(TAG, "Camera loop cancelled")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera loop error: ${e.message}")
                    }
                    delay(intervalSeconds * 1000L)
                }
                Log.d(TAG, "Camera loop ended")
            }
        }

        // Audio capture loop (if enabled)
        if (micEnabled) {
            audioJob = serviceScope.launch {
                Log.d(TAG, "Audio loop started")
                while (isActive && isRunning) {
                    try {
                        val acm = audioCaptureManager
                        if (acm != null) {
                            val chunk = acm.getAudioChunk()
                            if (chunk != null) {
                                Log.d(TAG, "Got audio chunk: ${chunk.size} bytes")
                                UploadManager.uploadAudio(chunk, serverUrl)
                            }
                        }
                    } catch (e: CancellationException) {
                        Log.d(TAG, "Audio loop cancelled")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Audio loop error: ${e.message}")
                    }
                    delay(1000L) // Check every second for new chunks
                }
                Log.d(TAG, "Audio loop ended")
            }
        }

        // Sensor capture loop (if enabled)
        if (sensorsEnabled) {
            sensorJob = serviceScope.launch {
                Log.d(TAG, "Sensor loop started")
                while (isActive && isRunning) {
                    try {
                        val sensorMgr = sensorCaptureManager
                        if (sensorMgr != null) {
                            val json = sensorMgr.getSensorDataJson()
                            Log.d(TAG, "Sensor data: $json")
                            UploadManager.uploadSensorData(json, serverUrl)
                        }
                    } catch (e: CancellationException) {
                        Log.d(TAG, "Sensor loop cancelled")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Sensor loop error: ${e.message}")
                    }
                    delay(intervalSeconds * 1000L)
                }
                Log.d(TAG, "Sensor loop ended")
            }
        }
    }

    private fun broadcastServiceState(action: String, message: String? = null) {
        val broadcastIntent = Intent(action)
        broadcastIntent.setPackage(packageName)
        if (message != null) {
            broadcastIntent.putExtra(EXTRA_STATUS_MESSAGE, message)
        }
        sendBroadcast(broadcastIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "CaptureService onDestroy")
        isRunning = false
        foregroundStarted = false

        // Cancel all coroutines
        screenshotJob?.cancel()
        cameraJob?.cancel()
        audioJob?.cancel()
        sensorJob?.cancel()
        serviceScope.cancel()

        // Release all managers
        try {
            screenCaptureManager?.release()
            screenCaptureManager = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing ScreenCaptureManager: ${e.message}")
        }

        try {
            cameraCaptureManager?.closeCamera()
            cameraCaptureManager = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing CameraCaptureManager: ${e.message}")
        }

        try {
            audioCaptureManager?.stop()
            audioCaptureManager = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioCaptureManager: ${e.message}")
        }

        try {
            sensorCaptureManager?.stop()
            sensorCaptureManager = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing SensorCaptureManager: ${e.message}")
        }

        // Release MediaProjection
        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaProjection: ${e.message}")
        }

        // Release wake lock
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock: ${e.message}")
        }

        // Broadcast that service has stopped so MainActivity can update UI.
        // stopReason preserves the explanation (start failure / projection
        // revoked) so the UI never falls back to a bare "Not running".
        broadcastServiceState(ACTION_SERVICE_STOPPED, stopReason)
        stopReason = null

        Log.d(TAG, "CaptureService fully destroyed")
    }
}
