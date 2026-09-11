package com.sentinelagent.debug

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages device sensor data collection.
 * Registers listeners for accelerometer, gyroscope, light, and proximity sensors.
 * Provides the latest readings as a JSON string.
 *
 * @param context Application context
 */
class SensorCaptureManager(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "SentinelAgent"
    }

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Thread-safe storage for latest sensor readings
    // Key: sensor type (int), Value: SensorReading
    private val latestReadings = ConcurrentHashMap<Int, SensorReading>()

    // References to registered sensors
    private val registeredSensors = mutableListOf<Sensor>()

    // Device ID for JSON
    private val deviceId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    /**
     * Data class holding a sensor reading.
     */
    data class SensorReading(
        val values: FloatArray,
        val accuracy: Int,
        val timestamp: Long
    )

    /**
     * Register sensor listeners for all supported sensor types.
     * Uses SENSOR_DELAY_NORMAL (approximately 200ms between readings).
     */
    fun start() {
        Log.d(TAG, "SensorCaptureManager start()")

        val sensorTypes = listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_PROXIMITY
        )

        for (type in sensorTypes) {
            val sensor = sensorManager.getDefaultSensor(type)
            if (sensor != null) {
                val registered = sensorManager.registerListener(
                    this,
                    sensor,
                    SensorManager.SENSOR_DELAY_NORMAL
                )
                if (registered) {
                    registeredSensors.add(sensor)
                    Log.d(TAG, "Registered sensor: ${sensor.name} (type=$type)")
                } else {
                    Log.w(TAG, "Failed to register sensor type=$type")
                }
            } else {
                Log.w(TAG, "Sensor type=$type not available on this device")
            }
        }

        Log.d(TAG, "Registered ${registeredSensors.size} sensors")
    }

    /**
     * Called when a sensor reading changes.
     */
    override fun onSensorChanged(event: SensorEvent) {
        // Store a copy of the values array (SensorEvent.values is reused)
        val valuesCopy = event.values.copyOf()
        latestReadings[event.sensor.type] = SensorReading(
            values = valuesCopy,
            accuracy = event.accuracy,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Called when sensor accuracy changes.
     */
    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        Log.d(TAG, "Sensor accuracy changed: ${sensor.name} accuracy=$accuracy")
    }

    /**
     * Build and return a JSON string with the latest sensor readings.
     * Includes device_id, type, timestamp, and all available sensor data.
     *
     * @return JSON string
     */
    fun getSensorDataJson(): String {
        val timestamp = System.currentTimeMillis()
        val sb = StringBuilder()

        sb.append("{")
        sb.append("\"device_id\":\"${escapeJson(deviceId)}\",")
        sb.append("\"type\":\"sensors\",")
        sb.append("\"timestamp\":$timestamp,")
        sb.append("\"sensors\":{")

        val sensorEntries = mutableListOf<String>()

        // Accelerometer (TYPE_ACCELEROMETER = 1)
        latestReadings[Sensor.TYPE_ACCELEROMETER]?.let { reading ->
            val x = reading.values.getOrElse(0) { 0f }
            val y = reading.values.getOrElse(1) { 0f }
            val z = reading.values.getOrElse(2) { 0f }
            sensorEntries.add(
                "\"accelerometer\":{" +
                        "\"x\":${formatFloat(x)}," +
                        "\"y\":${formatFloat(y)}," +
                        "\"z\":${formatFloat(z)}," +
                        "\"accuracy\":${reading.accuracy}" +
                        "}"
            )
        }

        // Gyroscope (TYPE_GYROSCOPE = 4)
        latestReadings[Sensor.TYPE_GYROSCOPE]?.let { reading ->
            val x = reading.values.getOrElse(0) { 0f }
            val y = reading.values.getOrElse(1) { 0f }
            val z = reading.values.getOrElse(2) { 0f }
            sensorEntries.add(
                "\"gyroscope\":{" +
                        "\"x\":${formatFloat(x)}," +
                        "\"y\":${formatFloat(y)}," +
                        "\"z\":${formatFloat(z)}," +
                        "\"accuracy\":${reading.accuracy}" +
                        "}"
            )
        }

        // Light (TYPE_LIGHT = 5)
        latestReadings[Sensor.TYPE_LIGHT]?.let { reading ->
            val lux = reading.values.getOrElse(0) { 0f }
            sensorEntries.add(
                "\"light\":{" +
                        "\"lux\":${formatFloat(lux)}," +
                        "\"accuracy\":${reading.accuracy}" +
                        "}"
            )
        }

        // Proximity (TYPE_PROXIMITY = 8)
        latestReadings[Sensor.TYPE_PROXIMITY]?.let { reading ->
            val distance = reading.values.getOrElse(0) { 0f }
            sensorEntries.add(
                "\"proximity\":{" +
                        "\"distance_cm\":${formatFloat(distance)}," +
                        "\"accuracy\":${reading.accuracy}" +
                        "}"
            )
        }

        sb.append(sensorEntries.joinToString(","))
        sb.append("}") // close sensors
        sb.append("}") // close root

        val json = sb.toString()
        Log.d(TAG, "Sensor JSON: $json")
        return json
    }

    /**
     * Unregister all sensor listeners and release resources.
     */
    fun stop() {
        Log.d(TAG, "SensorCaptureManager stop()")
        try {
            sensorManager.unregisterListener(this)
            registeredSensors.clear()
            latestReadings.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SensorCaptureManager: ${e.message}")
        }
    }

    /**
     * Format a Float to a reasonable number of decimal places for JSON.
     * Avoids scientific notation.
     */
    private fun formatFloat(value: Float): String {
        return if (value.isNaN() || value.isInfinite()) {
            "0.0"
        } else {
            "%.6f".format(value)
        }
    }

    /**
     * Escape a string for inclusion in a JSON string value.
     */
    private fun escapeJson(input: String): String {
        return input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
