package com.ayushkataria.bikeryde.ride

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.ayushkataria.bikeryde.MainActivity
import com.ayushkataria.bikeryde.R

/** Posts the [StillRidingWatchdog]'s "Still riding?" alert, on whichever channel the calling
 * foreground service (single or multi-day) already created — both use the same "ride tracking"
 * channel, so there's nothing here to create. */
object StillRidingNotifier {

    private const val NOTIFICATION_ID = 1002

    fun show(context: Context, channelId: String) {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(context.getString(R.string.notif_still_riding_title))
            .setContentText(context.getString(R.string.notif_still_riding_message))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }
}
