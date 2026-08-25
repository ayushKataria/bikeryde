package com.ayushkataria.bikeryde.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ayushkataria.bikeryde.MainActivity
import com.ayushkataria.bikeryde.R

/** Notifications for the background video-render job (design doc §5.3/§6). */
object RenderNotifications {

    const val CHANNEL_ID = "ride_render"
    const val PROGRESS_NOTIFICATION_ID = 2001
    const val READY_NOTIFICATION_ID = 2002

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_render),
            NotificationManager.IMPORTANCE_LOW
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun progressNotification(context: Context, percent: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_rendering_video))
            .setContentText(context.getString(R.string.notif_rendering_video_percent, percent))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    fun showReady(context: Context, rideId: Long) {
        val contentIntent = PendingIntent.getActivity(
            context,
            rideId.toInt(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_RENDER_RIDE_ID, rideId)
                putExtra(MainActivity.EXTRA_OPEN_RENDER_TYPE, RenderType.VIDEO.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_video_ready))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(READY_NOTIFICATION_ID, notification)
    }
}
