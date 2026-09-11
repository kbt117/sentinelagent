package com.sentinelagent.debug

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages microphone audio capture using AudioRecord.
 * Buffers 5 seconds of 16kHz mono PCM_16BIT audio and provides WAV chunks.
 *
 * @param sampleRate  Audio sample rate in Hz (typically 16000)
 */
class AudioCaptureManager(private val sampleRate: Int = 16000) {

    companion object {
        private const val TAG = "SentinelAgent"
        private const val CHUNK_DURATION_SECONDS = 5
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2 // PCM_16BIT = 2 bytes per sample
        private const val WAV_HEADER_SIZE = 44
    }

    // Number of bytes for 5 seconds of audio
    private val chunkSizeBytes = sampleRate * CHUNK_DURATION_SECONDS * BYTES_PER_SAMPLE

    // Minimum buffer size required by AudioRecord
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
        .coerceAtLeast(4096)

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null

    // Latest complete WAV chunk (thread-safe)
    @Volatile
    private var latestWavChunk: ByteArray? = null

    /**
     * Start audio recording.
     * Creates AudioRecord and begins the recording loop in a background thread.
     */
    fun start() {
        if (isRecording.get()) {
            Log.w(TAG, "AudioCaptureManager already recording")
            return
        }

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "AudioRecord: invalid min buffer size: $minBufferSize")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize
            )

            if (audioRecord!!.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord!!.startRecording()
            isRecording.set(true)
            Log.d(TAG, "AudioRecord started: sampleRate=$sampleRate, bufferSize=$minBufferSize, chunkSize=$chunkSizeBytes")

            // Start the recording thread
            recordingThread = Thread({ recordingLoop() }, "AudioRecordThread").apply {
                isDaemon = true
                start()
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "AudioRecord SecurityException (RECORD_AUDIO permission?): ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord start error: ${e.message}")
            audioRecord?.release()
            audioRecord = null
        }
    }

    /**
     * The main recording loop. Reads PCM data in chunks of minBufferSize bytes,
     * accumulates them into chunkSizeBytes-sized buffers, then packages as WAV.
     */
    private fun recordingLoop() {
        Log.d(TAG, "Recording loop started")
        val readBuffer = ByteArray(minBufferSize)
        val chunkBuffer = ByteArrayOutputStream(chunkSizeBytes)

        while (isRecording.get()) {
            try {
                val record = audioRecord ?: break
                val bytesRead = record.read(readBuffer, 0, readBuffer.size)

                when {
                    bytesRead > 0 -> {
                        chunkBuffer.write(readBuffer, 0, bytesRead)

                        // When we have accumulated a full 5-second chunk
                        if (chunkBuffer.size() >= chunkSizeBytes) {
                            val pcmBytes = chunkBuffer.toByteArray()
                            val wavBytes = pcmToWav(pcmBytes)
                            latestWavChunk = wavBytes
                            Log.d(TAG, "Audio chunk ready: ${wavBytes.size} bytes WAV")

                            // Reset the accumulation buffer
                            chunkBuffer.reset()
                        }
                    }
                    bytesRead == AudioRecord.ERROR_INVALID_OPERATION -> {
                        Log.e(TAG, "AudioRecord: ERROR_INVALID_OPERATION")
                        break
                    }
                    bytesRead == AudioRecord.ERROR_BAD_VALUE -> {
                        Log.e(TAG, "AudioRecord: ERROR_BAD_VALUE")
                        break
                    }
                    bytesRead == AudioRecord.ERROR_DEAD_OBJECT -> {
                        Log.e(TAG, "AudioRecord: ERROR_DEAD_OBJECT")
                        break
                    }
                    bytesRead < 0 -> {
                        Log.e(TAG, "AudioRecord: read error $bytesRead")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording loop error: ${e.message}")
                if (!isRecording.get()) break
            }
        }

        Log.d(TAG, "Recording loop ended")
    }

    /**
     * Get the latest complete WAV audio chunk.
     *
     * @return A ByteArray containing the WAV file (PCM data with 44-byte header),
     *         or null if no complete chunk is available yet.
     */
    fun getAudioChunk(): ByteArray? {
        val chunk = latestWavChunk
        if (chunk != null) {
            // Consume the chunk (set to null so we don't re-upload same chunk)
            latestWavChunk = null
            return chunk
        }
        return null
    }

    /**
     * Stop recording and release all resources.
     */
    fun stop() {
        Log.d(TAG, "AudioCaptureManager stop()")
        isRecording.set(false)

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord stop error: ${e.message}")
        }

        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord release error: ${e.message}")
        }

        audioRecord = null

        try {
            recordingThread?.join(3000)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted waiting for recording thread: ${e.message}")
        }

        recordingThread = null
        latestWavChunk = null
        Log.d(TAG, "AudioCaptureManager stopped")
    }

    /**
     * Convert raw PCM bytes to WAV format by prepending the 44-byte WAV header.
     *
     * WAV header format (little-endian):
     *  4 bytes - "RIFF"
     *  4 bytes - chunk size (file size - 8)
     *  4 bytes - "WAVE"
     *  4 bytes - "fmt "
     *  4 bytes - subchunk1 size (16 for PCM)
     *  2 bytes - audio format (1 = PCM)
     *  2 bytes - num channels (1 = Mono)
     *  4 bytes - sample rate (16000)
     *  4 bytes - byte rate (sampleRate * numChannels * bitsPerSample / 8)
     *  2 bytes - block align (numChannels * bitsPerSample / 8)
     *  2 bytes - bits per sample (16)
     *  4 bytes - "data"
     *  4 bytes - subchunk2 size (pcmData.size)
     *  N bytes - PCM data
     */
    private fun pcmToWav(pcmBytes: ByteArray): ByteArray {
        val numChannels: Short = 1
        val bitsPerSample: Short = 16
        val byteRate = sampleRate * numChannels * (bitsPerSample / 8)
        val blockAlign = (numChannels * bitsPerSample / 8).toShort()
        val dataSize = pcmBytes.size
        val totalSize = WAV_HEADER_SIZE + dataSize

        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(totalSize - 8)     // ChunkSize
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))

        // fmt sub-chunk
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)                // Subchunk1Size (PCM = 16)
        buffer.putShort(1)               // AudioFormat (PCM = 1)
        buffer.putShort(numChannels)     // NumChannels
        buffer.putInt(sampleRate)        // SampleRate
        buffer.putInt(byteRate)          // ByteRate
        buffer.putShort(blockAlign)      // BlockAlign
        buffer.putShort(bitsPerSample)   // BitsPerSample

        // data sub-chunk
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)          // Subchunk2Size

        // PCM audio data
        buffer.put(pcmBytes)

        return buffer.array()
    }
}
