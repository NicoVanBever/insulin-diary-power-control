package com.insulindiary.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONObject

/**
 * Rings an alarm: plays the alarm sound on the alarm volume (so it also sounds when the phone is on silent),
 * vibrates, and shows a full-screen alarm over the lock screen. Stops by itself after 3 minutes.
 */
class AlarmService : Service() {

    companion object {
        const val ACTION_SNOOZE = "com.insulindiary.app.SNOOZE"
        const val ACTION_SKIP = "com.insulindiary.app.SKIP"
        private const val ACTION_START = "com.insulindiary.app.RING"
        private const val ACTION_STOP = "com.insulindiary.app.STOP"
        private const val NOTIF_ID = 4711

        @Volatile var ringingKey: String? = null
        @Volatile var ringing: Alarms.Occ? = null

        fun start(c: Context, o: Alarms.Occ) {
            startServiceCompat(c, Intent(c, AlarmService::class.java).setAction(ACTION_START).putExtra("key", o.key))
        }

        fun stop(c: Context) {
            if (ringingKey == null) return
            try { c.startService(Intent(c, AlarmService::class.java).setAction(ACTION_STOP)) } catch (_: Exception) { }
        }

        /** What "I took it" / "Log glucose now" sends to the page. */
        fun actionJson(o: Alarms.Occ): String =
            JSONObject().put("key", o.key).put("kind", o.kind).put("insulinId", o.insulinId).put("units", o.units).toString()

        fun openAppIntent(c: Context, o: Alarms.Occ): PendingIntent {
            val i = Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_ACTION, actionJson(o))
                .putExtra(MainActivity.EXTRA_KEY, o.key)
            return PendingIntent.getActivity(c, o.key.hashCode() + 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { quiet(); stopSelfClean(keepNotification = true) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val o = intent.getStringExtra("key")?.let { Alarms.find(this, it) }
                if (o == null) { stopSelfClean(false); return START_NOT_STICKY }
                quiet()
                ringingKey = o.key; ringing = o
                Alarms.ensureChannel(this)
                val n = buildNotification(o)
                val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
                ServiceCompat.startForeground(this, NOTIF_ID, n, type)
                ring()
                // Open the full-screen alarm straight away when allowed; otherwise the notification's full-screen intent does it.
                try { startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
                handler.removeCallbacks(autoStop)
                handler.postDelayed(autoStop, 3 * 60_000L)
            }
            ACTION_STOP -> { quiet(); stopSelfClean(false) }
            else -> stopSelfClean(false)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(o: Alarms.Occ): Notification {
        val full = PendingIntent.getActivity(this, 7, Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(a: String) = PendingIntent.getBroadcast(this, (o.key + a).hashCode(),
            Intent(this, AlarmActionReceiver::class.java).setAction(a).putExtra("key", o.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val main = if (o.kind == "dose") Alarms.label(this, "took", "I took it") else Alarms.label(this, "logGlucose", "Log glucose now")
        return NotificationCompat.Builder(this, Alarms.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setContentTitle(o.title)
            .setContentText(o.body)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(0, main, openAppIntent(this, o))
            .addAction(0, Alarms.label(this, "snooze", "Snooze"), action(ACTION_SNOOZE))
            .addAction(0, if (o.kind == "low") Alarms.label(this, "dismiss", "Dismiss") else Alarms.label(this, "skip", "Skip"), action(ACTION_SKIP))
            .build()
    }

    private fun ring() {
        if (Alarms.soundOn(this)) {
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                player = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    setDataSource(this@AlarmService, uri)
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (_: Exception) { player = null }
        }
        vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
        else getSystemService(Vibrator::class.java)
        try {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        } catch (_: Exception) { }
    }

    private fun quiet() {
        try { player?.stop() } catch (_: Exception) { }
        player?.release(); player = null
        try { vibrator?.cancel() } catch (_: Exception) { }
    }

    private fun stopSelfClean(keepNotification: Boolean) {
        handler.removeCallbacks(autoStop)
        ringingKey = null; ringing = null
        ServiceCompat.stopForeground(this, if (keepNotification) ServiceCompat.STOP_FOREGROUND_DETACH else ServiceCompat.STOP_FOREGROUND_REMOVE)
        sendBroadcast(Intent(AlarmActivity.ACTION_CLOSE).setPackage(packageName))
        stopSelf()
    }

    override fun onDestroy() {
        quiet()
        handler.removeCallbacks(autoStop)
        super.onDestroy()
    }
}
