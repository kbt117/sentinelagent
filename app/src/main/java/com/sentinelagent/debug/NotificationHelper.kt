package com.sentinelagent.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Helper object for creating and managing the foreground service notification.
 */
object NotificationHelper {

    private const val TAG = "SentinelAgent"
    private const val CHANNEL_ID = "sentinel_monitoring"
    private const val CHANNEL_NAME = "SentinelAgent Monitoring"
    private const val CHANNEL_DESCRIPTION = "Persistent notification for SentinelAgent capture service"

    /**
     * Create the notification channel required for Android 8.0+ (API 26+).
     * This must be called before posting any notification.
     *
     * @param context Application or service context
     */
    fun createNotificationChannel(context: Context) {
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
            description = CHANNEL_DESCRIPTION
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        Log.d(TAG, "Notification channel created: $CHANNEL_ID")
    }

    /**
     * Build the persistent foreground service notification.
     * Includes a "Stop" action button that sends a broadcast to stop the service.
     *
     * @param context Service context
     * @return The built Notification
     */
    fun buildNotification(context: Context): Notification {
        // Ensure channel is created first
        createNotificationChannel(context)

        // Intent for tapping the notification — opens MainActivity
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent for the "Stop" action button — sends stop broadcast to CaptureService
        val stopServiceIntent = Intent(context, CaptureService::class.java).apply {
            action = CaptureService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            1,
            stopServiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build the notification
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SentinelAgent Monitoring")
            .setContentText("Capturing screen, camera, mic, sensors")
            .setSubText("Running")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause, // System stop icon
                "Stop",
                stopPendingIntent
            )
            .build()

        Log.d(TAG, "Notification built")
        return notification
    }
}
