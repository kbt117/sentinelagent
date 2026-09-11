package com.sentinelagent.debug

import android.content.Context
import android.graphics.Bitmap
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

object UploadManager {

    private const val TAG = "SentinelAgent"
    private const val MAX_RETRIES = 3
    private const val BASE_BACKOFF_MS = 1000L

    private lateinit var deviceId: String

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Initialize the UploadManager with a context to retrieve device ID.
     * Call this once from Application or MainActivity.
     */
    fun init(context: Context) {
        deviceId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown_device"
        Log.d(TAG, "UploadManager initialized with deviceId=$deviceId")
    }

    /**
     * Upload a Bitmap as a JPEG image via multipart/form-data.
     *
     * @param bitmap The bitmap to upload
     * @param type   Either "screenshot" or "camera"
     * @param serverUrl The target upload URL
     */
    suspend fun uploadImage(bitmap: Bitmap, type: String, serverUrl: String) {
        withContext(Dispatchers.IO) {
            val jpegBytes = bitmapToJpegBytes(bitmap)
            if (jpegBytes == null) {
                Log.e(TAG, "Failed to compress bitmap to JPEG")
                return@withContext
            }

            val timestamp = System.currentTimeMillis()
            val fileName = if (type == "screenshot") "screenshot.jpg" else "camera.jpg"

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("device_id", deviceId)
                .addFormDataPart("type", type)
                .addFormDataPart("timestamp", timestamp.toString())
                .addFormDataPart(
                    "image",
                    fileName,
                    jpegBytes.toRequestBody("image/jpeg".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(serverUrl)
                .post(requestBody)
                .build()

            executeWithRetry(request, "uploadImage[$type]")
        }
    }

    /**
     * Upload a WAV audio chunk via multipart/form-data.
     *
     * @param wavBytes  The WAV file bytes (including 44-byte header)
     * @param serverUrl The target upload URL
     */
    suspend fun uploadAudio(wavBytes: ByteArray, serverUrl: String) {
        withContext(Dispatchers.IO) {
            val timestamp = System.currentTimeMillis()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("device_id", deviceId)
                .addFormDataPart("type", "audio")
                .addFormDataPart("timestamp", timestamp.toString())
                .addFormDataPart(
                    "audio",
                    "chunk.wav",
                    wavBytes.toRequestBody("audio/wav".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(serverUrl)
                .post(requestBody)
                .build()

            executeWithRetry(request, "uploadAudio")
        }
    }

    /**
     * Upload sensor data as a JSON body with application/json content type.
     *
     * @param json      The JSON string to upload
     * @param serverUrl The target upload URL
     */
    suspend fun uploadSensorData(json: String, serverUrl: String) {
        withContext(Dispatchers.IO) {
            val requestBody = json.toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(serverUrl)
                .post(requestBody)
                .build()

            executeWithRetry(request, "uploadSensorData")
        }
    }

    /**
     * Execute an OkHttp request with exponential backoff retry logic.
     * Retries up to MAX_RETRIES times with delays of 1s, 2s, 4s.
     */
    private suspend fun executeWithRetry(request: Request, operationName: String) {
        var attempt = 0
        while (attempt < MAX_RETRIES) {
            try {
                val response = httpClient.newCall(request).execute()
                response.use { resp ->
                    if (resp.isSuccessful) {
                        Log.d(TAG, "$operationName succeeded on attempt ${attempt + 1}: ${resp.code}")
                        return
                    } else {
                        Log.w(TAG, "$operationName failed on attempt ${attempt + 1}: HTTP ${resp.code} ${resp.message}")
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "$operationName network error on attempt ${attempt + 1}: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "$operationName unexpected error on attempt ${attempt + 1}: ${e.message}")
            }

            attempt++
            if (attempt < MAX_RETRIES) {
                val backoffMs = BASE_BACKOFF_MS * (1L shl (attempt - 1)) // 1s, 2s, 4s
                Log.d(TAG, "$operationName retrying in ${backoffMs}ms (attempt ${attempt + 1}/$MAX_RETRIES)")
                delay(backoffMs)
            }
        }

        Log.e(TAG, "$operationName failed after $MAX_RETRIES attempts")
    }

    /**
     * Compress a Bitmap to JPEG bytes at quality 80.
     * Returns null if compression fails.
     */
    private fun bitmapToJpegBytes(bitmap: Bitmap): ByteArray? {
        return try {
            val outputStream = ByteArrayOutputStream()
            val compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            if (compressed) {
                outputStream.toByteArray()
            } else {
                Log.e(TAG, "Bitmap.compress returned false")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error compressing bitmap: ${e.message}")
            null
        }
    }
}
