package com.sentinelagent.debug

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.Log

/**
 * Manages screen capture using MediaProjection and VirtualDisplay with ImageReader.
 *
 * One manager (and its single VirtualDisplay) is bound to exactly one
 * MediaProjection token for one capture session. When the projection ends,
 * this manager is released and a NEW user consent is required — the old
 * token can never create another VirtualDisplay.
 *
 * @param mediaProjection   The MediaProjection instance from the permission grant
 * @param width             Screen width in pixels
 * @param height            Screen height in pixels
 * @param densityDpi        Screen density in dpi
 */
class ScreenCaptureManager(
    private val mediaProjection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int
) {

    companion object {
        private const val TAG = "SentinelAgent"
        private const val VIRTUAL_DISPLAY_NAME = "SentinelAgentCapture"
        private const val MAX_IMAGES = 2
    }

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    init {
        Log.d(TAG, "ScreenCaptureManager init: ${width}x${height} @ ${densityDpi}dpi")
        setupCapture()
    }

    /**
     * Set up the ImageReader and VirtualDisplay for screen capture.
     */
    private fun setupCapture() {
        try {
            // Create ImageReader with RGBA_8888 format
            imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                MAX_IMAGES
            )

            // Create VirtualDisplay backed by the ImageReader surface
            virtualDisplay = mediaProjection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                null
            )

            Log.d(TAG, "VirtualDisplay and ImageReader created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up screen capture: ${e.message}")
            release()
        }
    }

    /**
     * Capture the current screen content.
     *
     * @return A Bitmap of the screen, or null if capture failed.
     *         The caller is responsible for recycling the Bitmap.
     */
    fun captureScreen(): Bitmap? {
        val reader = imageReader ?: run {
            Log.w(TAG, "captureScreen: ImageReader is null")
            return null
        }

        var image: android.media.Image? = null
        return try {
            // Acquire the latest available image (non-blocking)
            image = reader.acquireLatestImage()
            if (image == null) {
                Log.w(TAG, "captureScreen: No image available yet")
                return null
            }

            val planes = image.planes
            if (planes.isEmpty()) {
                Log.w(TAG, "captureScreen: Image has no planes")
                return null
            }

            val plane = planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            // Create a Bitmap from the pixel buffer
            // rowPadding accounts for stride differences
            val bitmapWidth = width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(
                bitmapWidth,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // If rowPadding exists, crop the bitmap to remove padding
            val finalBitmap = if (rowPadding != 0) {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle()
                cropped
            } else {
                bitmap
            }

            Log.d(TAG, "captureScreen: Success, ${finalBitmap.width}x${finalBitmap.height}")
            finalBitmap

        } catch (e: Exception) {
            Log.e(TAG, "captureScreen error: ${e.message}")
            null
        } finally {
            // Always close the Image to free up the buffer slot
            try {
                image?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing image: ${e.message}")
            }
        }
    }

    /**
     * Release the VirtualDisplay and ImageReader.
     * Call this when capture is no longer needed.
     */
    fun release() {
        Log.d(TAG, "ScreenCaptureManager releasing resources")
        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing VirtualDisplay: ${e.message}")
        }

        try {
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ImageReader: ${e.message}")
        }
    }
}
