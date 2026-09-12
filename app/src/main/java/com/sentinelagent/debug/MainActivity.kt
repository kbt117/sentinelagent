package com.sentinelagent.debug

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SentinelAgent"
    }

    /**
     * Config snapshot taken when Start is tapped, consumed when the
     * MediaProjection consent dialog returns. Stashed in memory (never on
     * disk) so the service starts with exactly what the user confirmed, even
     * if the activity is recreated while the system dialog is showing.
     */
    private data class PendingStartConfig(
        val serverUrl: String,
        val intervalSeconds: Int,
        val cameraMode: String,
        val micEnabled: Boolean,
        val sensorsEnabled: Boolean
    )

    private var pendingStartConfig: PendingStartConfig? = null

    // UI references
    private lateinit var etServerUrl: EditText
    private lateinit var etInterval: EditText
    private lateinit var spinnerCamera: Spinner
    private lateinit var switchMic: Switch
    private lateinit var switchSensors: Switch
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView

    // MediaProjection manager
    private lateinit var mediaProjectionManager: MediaProjectionManager

    // MediaProjection consent launcher. A FRESH consent dialog is shown for
    // every capture session: the granted Intent/resultCode token is valid only
    // in this process and only for one getMediaProjection() call, so it is
    // handed straight to CaptureService and never cached or persisted.
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            Log.d(TAG, "MediaProjection permission granted")
            val config = pendingStartConfig
            pendingStartConfig = null
            if (config == null) {
                Log.w(TAG, "Consent granted but no pending start config — ignoring")
                setStoppedUiState(getString(R.string.status_not_running))
                Toast.makeText(this, "Tap Start again to begin monitoring", Toast.LENGTH_SHORT).show()
            } else {
                startCaptureService(result.resultCode, data, config)
            }
        } else {
            Log.w(TAG, "MediaProjection permission denied or cancelled")
            pendingStartConfig = null
            setStoppedUiState(getString(R.string.error_projection_denied))
            Toast.makeText(this, R.string.error_projection_denied, Toast.LENGTH_SHORT).show()
        }
    }

    // Permission launcher for multiple permissions
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            Log.d(TAG, "All permissions granted, launching MediaProjection request")
            launchMediaProjectionRequest()
        } else {
            val denied = results.filter { !it.value }.keys.joinToString(", ")
            Log.w(TAG, "Permissions denied: $denied")
            tvStatus.text = "Permissions denied: $denied"
            Toast.makeText(this, "Some permissions were denied: $denied", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "MainActivity onCreate")

        // Initialize views
        etServerUrl = findViewById(R.id.et_server_url)
        etInterval = findViewById(R.id.et_interval)
        spinnerCamera = findViewById(R.id.spinner_camera)
        switchMic = findViewById(R.id.switch_mic)
        switchSensors = findViewById(R.id.switch_sensors)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        tvStatus = findViewById(R.id.tv_status)

        // Set up camera spinner
        val cameraOptions = arrayOf("Off", "Front", "Rear")
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, cameraOptions)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCamera.adapter = spinnerAdapter

        // Get MediaProjectionManager
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Initialize UploadManager
        UploadManager.init(this)

        // Check if service is already running
        if (CaptureService.isRunning) {
            Log.d(TAG, "Service already running, updating UI")
            setRunningUiState()
        } else {
            setStoppedUiState()
        }

        // Start button click listener
        btnStart.setOnClickListener {
            onStartClicked()
        }

        // Stop button click listener
        btnStop.setOnClickListener {
            onStopClicked()
        }

        // Register broadcast receiver for service state updates
        // (started / stopped / error / status messages)
        val stateFilter = IntentFilter().apply {
            addAction(CaptureService.ACTION_SERVICE_STARTED)
            addAction(CaptureService.ACTION_SERVICE_STOPPED)
            addAction(CaptureService.ACTION_SERVICE_ERROR)
            addAction(CaptureService.ACTION_SERVICE_STATUS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceStateReceiver, stateFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(serviceStateReceiver, stateFilter)
        }
    }

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(CaptureService.EXTRA_STATUS_MESSAGE)
            Log.d(TAG, "Service state broadcast: action=${intent?.action}, message=$message")
            when (intent?.action) {
                CaptureService.ACTION_SERVICE_STARTED -> setRunningUiState()
                CaptureService.ACTION_SERVICE_STOPPED -> setStoppedUiState(message)
                CaptureService.ACTION_SERVICE_ERROR -> {
                    setStoppedUiState(message ?: "Start failed")
                    Toast.makeText(
                        this@MainActivity,
                        message ?: "Capture failed to start",
                        Toast.LENGTH_LONG
                    ).show()
                }
                CaptureService.ACTION_SERVICE_STATUS -> {
                    // Non-fatal note while running (e.g. one capture mode unavailable)
                    if (message != null) tvStatus.text = message
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(serviceStateReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-sync the buttons with the real service state. The status TEXT is
        // intentionally left alone when stopped: onResume fires right after
        // the consent/permission dialogs close, and must not wipe the
        // explanation they (or a service broadcast) just set.
        if (CaptureService.isRunning) {
            setRunningUiState()
        } else {
            btnStart.isEnabled = true
            btnStop.isEnabled = false
            if (tvStatus.text.isNullOrEmpty()) {
                setStoppedUiState()
            }
        }
    }

    private fun onStartClicked() {
        // Nothing to do if the service is already up
        if (CaptureService.isRunning) {
            setRunningUiState()
            return
        }

        // Validate inputs
        val serverUrl = etServerUrl.text.toString().trim()
        if (serverUrl.isEmpty()) {
            etServerUrl.error = "Server URL is required"
            return
        }

        val intervalStr = etInterval.text.toString().trim()
        val intervalSeconds = intervalStr.toIntOrNull()
        if (intervalSeconds == null || intervalSeconds <= 0) {
            etInterval.error = "Please enter a valid positive integer"
            return
        }

        Log.d(TAG, "Start clicked: url=$serverUrl, interval=$intervalSeconds")

        // Build list of permissions to request
        val permissionsNeeded = mutableListOf<String>()

        // Camera permission if camera mode is not "Off"
        val cameraMode = spinnerCamera.selectedItemPosition // 0=Off, 1=Front, 2=Rear
        if (cameraMode != 0) {
            permissionsNeeded.add(Manifest.permission.CAMERA)
        }

        // Microphone permission if mic is enabled
        if (switchMic.isChecked) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }

        // POST_NOTIFICATIONS for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // FOREGROUND_SERVICE_* permissions are normal (install-time) on API 34+;
        // do not request them at runtime.

        // Filter out already-granted permissions
        val notGranted = permissionsNeeded.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: $notGranted")
            permissionLauncher.launch(notGranted.toTypedArray())
        } else {
            Log.d(TAG, "All permissions already granted, launching MediaProjection request")
            launchMediaProjectionRequest()
        }
    }

    private fun launchMediaProjectionRequest() {
        // Snapshot the current UI config so the consent result starts exactly
        // this session, even if the activity is recreated while the system
        // dialog is showing. The consent token itself is never cached or
        // written to disk — it lives only for this one session start.
        pendingStartConfig = PendingStartConfig(
            serverUrl = etServerUrl.text.toString().trim(),
            intervalSeconds = etInterval.text.toString().trim().toIntOrNull() ?: 10,
            cameraMode = when (spinnerCamera.selectedItemPosition) {
                1 -> CaptureService.CAMERA_FRONT
                2 -> CaptureService.CAMERA_REAR
                else -> CaptureService.CAMERA_OFF
            },
            micEnabled = switchMic.isChecked,
            sensorsEnabled = switchSensors.isChecked
        )
        Log.d(TAG, "Launching MediaProjection permission request (fresh consent)")
        projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startCaptureService(
        resultCode: Int,
        projectionData: Intent,
        config: PendingStartConfig
    ) {
        Log.d(TAG, "Starting CaptureService: url=${config.serverUrl}, " +
                "interval=${config.intervalSeconds}, camera=${config.cameraMode}, " +
                "mic=${config.micEnabled}, sensors=${config.sensorsEnabled}")

        val serviceIntent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_SERVER_URL, config.serverUrl)
            putExtra(CaptureService.EXTRA_INTERVAL_SECONDS, config.intervalSeconds)
            putExtra(CaptureService.EXTRA_CAMERA_MODE, config.cameraMode)
            putExtra(CaptureService.EXTRA_MIC_ENABLED, config.micEnabled)
            putExtra(CaptureService.EXTRA_SENSORS_ENABLED, config.sensorsEnabled)
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_PROJECTION_DATA, projectionData)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Don't claim "Monitoring active" yet — the service confirms with an
        // ACTION_SERVICE_STARTED broadcast once startForeground() and
        // getMediaProjection() have actually succeeded. If it fails, an
        // ACTION_SERVICE_ERROR broadcast shows the real reason here instead.
        setStartingUiState()
    }

    private fun onStopClicked() {
        Log.d(TAG, "Stop clicked")
        val stopIntent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_STOP
        }
        startService(stopIntent)
        setStoppedUiState()
    }

    private fun setStartingUiState() {
        btnStart.isEnabled = false
        btnStop.isEnabled = false
        tvStatus.text = getString(R.string.status_starting)
        tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
    }

    private fun setRunningUiState() {
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        tvStatus.text = getString(R.string.status_running)
        tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
    }

    private fun setStoppedUiState(message: String? = null) {
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        tvStatus.text = message ?: getString(R.string.status_not_running)
        tvStatus.setTextColor(
            if (message != null) ContextCompat.getColor(this, android.R.color.holo_red_dark)
            else ContextCompat.getColor(this, android.R.color.darker_gray)
        )
    }
}
