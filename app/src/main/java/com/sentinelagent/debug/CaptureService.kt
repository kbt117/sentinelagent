package com.sentinelagent.debug

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.*

class CaptureService : Service() {

    companion object {
        private const val TAG = "SentinelAgent"
        private const val NOTIFICATION_ID = 1001

        // Actions
        const val ACTION_START = "com.sentinelagent.debug.ACTION_START"
        const val ACTION_STOP = "com.sentinelagent.debug.ACTION_STOP"
        const val ACTION_SERVICE_STOPPED = "com.sentinelagent.debug.ACTION_SERVICE_STOPPED"

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

    // Wake lock to keep CPU alive
    private var wakeLock: PowerManager.WakeLock? = null

    // Coroutine jobs for each capture loop
    private var screenshotJob: Job? = null
    private var cameraJob: Job? = null
    private var audioJob: Job? = null
    private var sensorJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CaptureService onCreate")
        isRunning = true

        // Acquire wake lock
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SentinelAgent::CaptureWakeLock"
        )
        wakeLock?.acquire(10 * 60 * 1000L) // acquire for up to 10 minutes, will be refreshed
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "CaptureService onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Received STOP action")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // Extract configuration from intent
                serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: "https://example.com/upload"
                intervalSeconds = intent.getIntExtra(EXTRA_INTERVAL_SECONDS, 10)
                cameraMode = intent.getStringExtra(EXTRA_CAMERA_MODE) ?: CAMERA_OFF
                micEnabled = intent.getBooleanExtra(EXTRA_MIC_ENABLED, false)
                sensorsEnabled = intent.getBooleanExtra(EXTRA_SENSORS_ENABLED, false)

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                @Suppress("DEPRECATION")
                val projectionData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                }

                Log.d(TAG, "Config: url=$serverUrl, interval=$intervalSeconds, " +
                        "camera=$cameraMode, mic=$micEnabled, sensors=$sensorsEnabled")

                // Create and show notification as foreground
                val notification = NotificationHelper.buildNotification(this)

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Match AndroidManifest foregroundServiceType exactly
                        // (mediaProjection | microphone | camera). specialUse is not used.
                        var fgsType =
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        if (micEnabled) {
                            fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        }
                        if (cameraMode != CAMERA_OFF) {
                            fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                        }
                        startForeground(NOTIFICATION_ID, notification, fgsType)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start foreground: ${e.message}")
                    try {
                        // Fallback: media projection alone (always required for this service)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            startForeground(
                                NOTIFICATION_ID,
                                notification,
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                            )
                        } else {
                            startForeground(NOTIFICATION_ID, notification)
                        }
                    } catch (e2: Exception) {
                        Log.e(TAG, "Critical: cannot start foreground: ${e2.message}")
                    }
                }

                // Initialize MediaProjection
                if (resultCode != -1 && projectionData != null) {
                    val projectionManager =
                        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = projectionManager.getMediaProjection(resultCode, projectionData)
                    mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.d(TAG, "MediaProjection stopped")
                            stopSelf()
                        }
                    }, null)
                } else {
                    Log.e(TAG, "No MediaProjection data, stopping service")
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Initialize managers and start capture loops
                initializeAndStartCapture()
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
            }
        }

        return START_STICKY
    }

    private fun initializeAndStartCapture() {
        serviceScope.launch {
            Log.d(TAG, "Initializing capture managers")

            // Get screen dimensions
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()

            val screenWidth: Int
            val screenHeight: Int
            val densityDpi: Int

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics = windowManager.currentWindowMetrics
                val bounds = windowMetrics.bounds
                screenWidth = bounds.width()
                screenHeight = bounds.height()
                val display = windowManager.defaultDisplay
                @Suppress("DEPRECATION")
                display.getMetrics(displayMetrics)
                densityDpi = displayMetrics.densityDpi
            } else {
                @Suppress("DEPRECATION")
                val display = windowManager.defaultDisplay
                @Suppress("DEPRECATION")
                display.getMetrics(displayMetrics)
                screenWidth = displayMetrics.widthPixels
                screenHeight = displayMetrics.heightPixels
                densityDpi = displayMetrics.densityDpi
            }

            Log.d(TAG, "Screen: ${screenWidth}x${screenHeight} @ ${densityDpi}dpi")

            // Initialize ScreenCaptureManager
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
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize ScreenCaptureManager: ${e.message}")
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
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize CameraCaptureManager: ${e.message}")
                    cameraCaptureManager = null
                }
            }

            // Initialize AudioCaptureManager if needed
            if (micEnabled) {
                try {
                    audioCaptureManager = AudioCaptureManager(16000)
                    audioCaptureManager?.start()
                    Log.d(TAG, "AudioCaptureManager initialized and started")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize AudioCaptureManager: ${e.message}")
                    audioCaptureManager = null
                }
            }

            // Initialize SensorCaptureManager if needed
            if (sensorsEnabled) {
                try {
                    sensorCaptureManager = SensorCaptureManager(this@CaptureService)
                    sensorCaptureManager?.start()
                    Log.d(TAG, "SensorCaptureManager initialized and started")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize SensorCaptureManager: ${e.message}")
                    sensorCaptureManager = null
                }
            }

            // Start all capture loops
            startCaptureLoops()
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

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "CaptureService onDestroy")
        isRunning = false

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

        // Broadcast that service has stopped so MainActivity can update UI
        val stoppedIntent = Intent(ACTION_SERVICE_STOPPED)
        stoppedIntent.setPackage(packageName)
        sendBroadcast(stoppedIntent)

        Log.d(TAG, "CaptureService fully destroyed")
    }
}
