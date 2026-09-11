package com.sentinelagent.debug

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Manages camera still captures using the Camera2 API.
 *
 * @param context       Application context
 * @param cameraFacing  FACING_FRONT or FACING_REAR
 */
class CameraCaptureManager(
    private val context: Context,
    private val cameraFacing: Int
) {

    companion object {
        private const val TAG = "SentinelAgent"
        const val FACING_FRONT = CameraCharacteristics.LENS_FACING_FRONT
        const val FACING_REAR = CameraCharacteristics.LENS_FACING_BACK
        private const val CAPTURE_TIMEOUT_MS = 5000L
        private const val JPEG_QUALITY: Byte = 85
    }

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private val cameraOpenCloseLock = Semaphore(1)

    // Background thread for camera callbacks
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Semaphore to wait for capture result
    private val captureCompleteSemaphore = Semaphore(0)

    // Latest captured JPEG bytes
    @Volatile
    private var latestJpegBytes: ByteArray? = null

    // Camera ID resolved from facing
    private var cameraId: String? = null

    // Best JPEG output size for the camera
    private var jpegSize: Size = Size(1280, 720)

    /**
     * Find the camera ID for the desired facing direction.
     */
    private fun findCameraId(): String? {
        return try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == cameraFacing) {
                    // Find best JPEG output size
                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    if (map != null) {
                        val sizes = map.getOutputSizes(ImageFormat.JPEG)
                        if (sizes != null && sizes.isNotEmpty()) {
                            // Pick a reasonable size (not max to save resources)
                            jpegSize = sizes.firstOrNull { it.width <= 1920 && it.height <= 1080 }
                                ?: sizes[0]
                        }
                    }
                    Log.d(TAG, "Found camera id=$id facing=$facing size=$jpegSize")
                    return id
                }
            }
            Log.w(TAG, "No camera found with facing=$cameraFacing")
            null
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error finding camera: ${e.message}")
            null
        }
    }

    /**
     * Open the camera. Must be called before captureStill().
     */
    fun openCamera() {
        Log.d(TAG, "openCamera()")

        // Check camera permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "CAMERA permission not granted")
            return
        }

        cameraId = findCameraId()
        val camId = cameraId ?: run {
            Log.e(TAG, "No suitable camera found")
            return
        }

        // Start background thread
        startBackgroundThread()

        // Create ImageReader for JPEG
        imageReader = ImageReader.newInstance(
            jpegSize.width,
            jpegSize.height,
            ImageFormat.JPEG,
            2
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            try {
                if (image != null) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    latestJpegBytes = bytes
                    Log.d(TAG, "JPEG image acquired: ${bytes.size} bytes")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error acquiring JPEG image: ${e.message}")
            } finally {
                image?.close()
                captureCompleteSemaphore.release()
            }
        }, backgroundHandler)

        if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "Timeout waiting to open camera lock")
            return
        }

        try {
            cameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "Camera opened: $camId")
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "openCamera CameraAccessException: ${e.message}")
            cameraOpenCloseLock.release()
        } catch (e: Exception) {
            Log.e(TAG, "openCamera exception: ${e.message}")
            cameraOpenCloseLock.release()
        }
    }

    /**
     * Create a CameraCaptureSession with the ImageReader surface.
     */
    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return

        try {
            val surface = reader.surface
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "CaptureSession configured")
                        captureSession = session
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "CaptureSession configuration failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "createCaptureSession error: ${e.message}")
        }
    }

    /**
     * Capture a single still image from the camera.
     *
     * @return A Bitmap from the camera, or null if capture failed.
     *         The caller is responsible for recycling the Bitmap.
     */
    fun captureStill(): Bitmap? {
        val device = cameraDevice ?: run {
            Log.w(TAG, "captureStill: cameraDevice is null")
            return null
        }
        val session = captureSession ?: run {
            Log.w(TAG, "captureStill: captureSession is null")
            return null
        }
        val reader = imageReader ?: run {
            Log.w(TAG, "captureStill: imageReader is null")
            return null
        }

        return try {
            latestJpegBytes = null

            // Build STILL_CAPTURE request
            val captureRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY)
                // Orientation: 0 = no rotation
                set(CaptureRequest.JPEG_ORIENTATION, 0)
            }

            session.capture(
                captureRequestBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        Log.d(TAG, "Capture completed")
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        Log.e(TAG, "Capture failed: ${failure.reason}")
                        captureCompleteSemaphore.release()
                    }
                },
                backgroundHandler
            )

            // Wait for the image to be available (with timeout)
            val acquired = captureCompleteSemaphore.tryAcquire(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!acquired) {
                Log.e(TAG, "Timeout waiting for camera capture")
                return null
            }

            val jpegBytes = latestJpegBytes
            if (jpegBytes == null) {
                Log.w(TAG, "No JPEG bytes after capture")
                return null
            }

            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode JPEG to Bitmap")
            } else {
                Log.d(TAG, "Camera still decoded: ${bitmap.width}x${bitmap.height}")
            }
            bitmap

        } catch (e: CameraAccessException) {
            Log.e(TAG, "captureStill CameraAccessException: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "captureStill error: ${e.message}")
            null
        }
    }

    /**
     * Close the camera and release all resources.
     */
    fun closeCamera() {
        Log.d(TAG, "closeCamera()")
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "Timeout waiting to acquire camera lock for close")
            }
            try {
                captureSession?.close()
                captureSession = null
                cameraDevice?.close()
                cameraDevice = null
                imageReader?.close()
                imageReader = null
            } finally {
                cameraOpenCloseLock.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "closeCamera error: ${e.message}")
        } finally {
            stopBackgroundThread()
        }
    }

    /**
     * Start the background HandlerThread for camera callbacks.
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        Log.d(TAG, "Camera background thread started")
    }

    /**
     * Stop the background HandlerThread.
     */
    private fun stopBackgroundThread() {
        try {
            backgroundThread?.quitSafely()
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
            Log.d(TAG, "Camera background thread stopped")
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted stopping background thread: ${e.message}")
        }
    }
}
