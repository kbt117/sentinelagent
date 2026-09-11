package com.sentinelagent.debug

import android.Manifest
import android.app.Activity
import android.content.Intent
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
        private const val REQUEST_MEDIA_PROJECTION = 1001
    }

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

        // Register broadcast receiver to update UI when service stops
        val stopFilter = android.content.IntentFilter(CaptureService.ACTION_SERVICE_STOPPED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceStoppedReceiver, stopFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(serviceStoppedReceiver, stopFilter)
        }
    }

    private val serviceStoppedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            Log.d(TAG, "Received service stopped broadcast")
            setStoppedUiState()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(serviceStoppedReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver: ${e.message}")
        }
    }

    private fun onStartClicked() {
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
        Log.d(TAG, "Launching MediaProjection permission request")
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.d(TAG, "MediaProjection permission granted")
                startCaptureService(resultCode, data)
            } else {
                Log.w(TAG, "MediaProjection permission denied or cancelled")
                tvStatus.text = "Screen capture permission denied"
                Toast.makeText(this, "Screen capture permission is required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCaptureService(resultCode: Int, projectionData: Intent) {
        val serverUrl = etServerUrl.text.toString().trim()
        val intervalSeconds = etInterval.text.toString().trim().toIntOrNull() ?: 10
        val cameraMode = when (spinnerCamera.selectedItemPosition) {
            1 -> CaptureService.CAMERA_FRONT
            2 -> CaptureService.CAMERA_REAR
            else -> CaptureService.CAMERA_OFF
        }
        val micEnabled = switchMic.isChecked
        val sensorsEnabled = switchSensors.isChecked

        Log.d(TAG, "Starting CaptureService: url=$serverUrl, interval=$intervalSeconds, " +
                "camera=$cameraMode, mic=$micEnabled, sensors=$sensorsEnabled")

        val serviceIntent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_SERVER_URL, serverUrl)
            putExtra(CaptureService.EXTRA_INTERVAL_SECONDS, intervalSeconds)
            putExtra(CaptureService.EXTRA_CAMERA_MODE, cameraMode)
            putExtra(CaptureService.EXTRA_MIC_ENABLED, micEnabled)
            putExtra(CaptureService.EXTRA_SENSORS_ENABLED, sensorsEnabled)
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_PROJECTION_DATA, projectionData)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        setRunningUiState()
    }

    private fun onStopClicked() {
        Log.d(TAG, "Stop clicked")
        val stopIntent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_STOP
        }
        startService(stopIntent)
        setStoppedUiState()
    }

    private fun setRunningUiState() {
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        tvStatus.text = "Monitoring active"
        tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
    }

    private fun setStoppedUiState() {
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        tvStatus.text = "Not running"
        tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
    }
}
