package com.oshi.messenger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

class ScheduledCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra("eventId") ?: return
        val reminderType = intent.getIntExtra("reminderType", 0)

        // The channel name and the body already came from strings.xml; the title —
        // the one line the user actually reads — was hardcoded English in an app
        // shipping 33 locales.
        val title = context.getString(
            when (reminderType) {
                1 -> com.oshi.messenger.R.string.scheduled_call_reminder_15
                2 -> com.oshi.messenger.R.string.scheduled_call_reminder_5
                3 -> com.oshi.messenger.R.string.scheduled_call_reminder_now
                else -> com.oshi.messenger.R.string.scheduled_call_reminder_default
            }
        )

        val channelId = "scheduled_calls"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                context.getString(com.oshi.messenger.R.string.channel_scheduled_calls_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.oshi.messenger.R.drawable.ic_notification_call)
            .setContentTitle(title)
            .setContentText(context.getString(com.oshi.messenger.R.string.channel_scheduled_calls_action))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(eventId.hashCode(), notification)

        Log.d("ScheduledCallReceiver", "Reminder $reminderType for call $eventId")
    }
}
