package com.insulindiary.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Optional emergency information shown as a quiet, permanent notice on the lock screen. */
object Emergency {
    private const val CHANNEL = "emergency_v1"
    private const val ID = 4712

    fun set(c: Context, text: String) {
        c.getSharedPreferences("ice", Context.MODE_PRIVATE).edit().putString("text", text).apply()
        show(c)
    }

    fun show(c: Context) {
        val text = c.getSharedPreferences("ice", Context.MODE_PRIVATE).getString("text", "") ?: ""
        val nm = NotificationManagerCompat.from(c)
        if (text.isBlank()) { nm.cancel(ID); return }
        val sys = c.getSystemService(NotificationManager::class.java)
        if (sys.getNotificationChannel(CHANNEL) == null) {
            sys.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.ch_emergency), NotificationManager.IMPORTANCE_LOW).apply {
                description = c.getString(R.string.ch_emergency_desc)
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            })
        }
        val lines = text.split("\n")
        val open = PendingIntent.getActivity(c, 3, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setContentTitle(c.getString(R.string.medical_info) + ": " + lines.take(2).joinToString(" · "))
            .setContentText(lines.drop(2).joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.drop(1).joinToString("\n")))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try { nm.notify(ID, n) } catch (_: SecurityException) { }
    }
}
