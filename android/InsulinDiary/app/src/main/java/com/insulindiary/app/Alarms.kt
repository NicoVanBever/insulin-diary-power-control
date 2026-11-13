package com.insulindiary.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * The page works out which alarms are due (from your plan and logged entries) and hands the list over.
 * This object schedules them with Android's alarm clock, so they ring even when the app is closed.
 *
 * Per-occurrence state kept here: "fired" (already rang), "snooze" (ring again at), "dismissed" (skipped).
 * When a dose is logged, the page sends a new list without that occurrence and its alarm is cancelled.
 */
object Alarms {
    const val CHANNEL = "alarms_v1"
    private const val PREFS = "alarms"
    private const val ACTION_FIRE = "com.insulindiary.app.FIRE"

    data class Occ(
        val key: String, val at: Long, val until: Long, val kind: String,
        val title: String, val body: String, val insulinId: String, val units: Double,
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun obj(c: Context, name: String) = try { JSONObject(prefs(c).getString(name, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    private fun putObj(c: Context, name: String, o: JSONObject) = prefs(c).edit().putString(name, o.toString()).apply()

    fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        // The sound is played by AlarmService on the alarm stream, so the channel itself stays quiet.
        val ch = NotificationChannel(CHANNEL, c.getString(R.string.ch_alarms), NotificationManager.IMPORTANCE_HIGH).apply {
            description = c.getString(R.string.ch_alarms_desc)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    fun config(c: Context): JSONObject = try { JSONObject(prefs(c).getString("cfg", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    fun soundOn(c: Context) = config(c).optBoolean("sound", true)

    /** Button texts in the language the user chose in the app (sent along with the alarms). */
    fun label(c: Context, key: String, fallback: String): String =
        config(c).optJSONObject("labels")?.optString(key)?.takeIf { it.isNotBlank() } ?: fallback
    private fun snoozeMin(c: Context) = config(c).optInt("snoozeMin", 10).coerceIn(1, 60)

    fun list(c: Context): List<Occ> {
        val arr = try { JSONArray(prefs(c).getString("list", "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Occ(o.optString("key"), o.optLong("at"), o.optLong("until"), o.optString("kind"),
                o.optString("title"), o.optString("body"), o.optString("insulinId"), o.optDouble("units", 0.0))
        }.filter { it.key.isNotEmpty() }
    }

    fun find(c: Context, key: String) = list(c).firstOrNull { it.key == key }

    /** New list from the page. */
    fun update(c: Context, json: String) {
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        prefs(c).edit()
            .putString("list", (o.optJSONArray("list") ?: JSONArray()).toString())
            .putString("cfg", (o.optJSONObject("cfg") ?: JSONObject()).toString())
            .apply()
        // The alarm that is ringing right now was answered (dose/reading logged) → stop it.
        AlarmService.ringingKey?.let { k -> if (list(c).none { it.key == k }) AlarmService.stop(c) }
        reschedule(c)
    }

    fun snooze(c: Context, key: String) {
        val s = obj(c, "snooze"); s.put(key, System.currentTimeMillis() + snoozeMin(c) * 60_000L); putObj(c, "snooze", s)
        reschedule(c)
    }

    fun dismiss(c: Context, key: String) {
        val d = obj(c, "dismissed"); d.put(key, System.currentTimeMillis()); putObj(c, "dismissed", d)
        val s = obj(c, "snooze"); s.remove(key); putObj(c, "snooze", s)
        reschedule(c)
    }

    fun markFired(c: Context, key: String) {
        val f = obj(c, "fired"); f.put(key, System.currentTimeMillis()); putObj(c, "fired", f)
        val s = obj(c, "snooze"); s.remove(key); putObj(c, "snooze", s)
    }

    private fun firePending(c: Context, key: String, create: Boolean): PendingIntent? {
        val i = Intent(c, AlarmReceiver::class.java).setAction(ACTION_FIRE).putExtra("key", key)
        val flags = PendingIntent.FLAG_IMMUTABLE or (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(c, key.hashCode(), i, flags)
    }

    /**
     * When each occurrence should ring (pure logic, unit-tested): skipped ones never, ones that already rang
     * only again when snoozed, nothing after its window, and one that is only just due rings now.
     */
    fun plan(occ: List<Occ>, fired: Set<String>, snooze: Map<String, Long>, dismissed: Set<String>, now: Long): List<Pair<String, Long>> =
        occ.mapNotNull { o ->
            if (o.key in dismissed) return@mapNotNull null
            val sn = snooze[o.key] ?: 0L
            if (sn == 0L && o.key in fired) return@mapNotNull null
            var at = if (sn > 0) sn else o.at
            if (at >= o.until) return@mapNotNull null
            if (at < now) { if (now - at > 90_000L) return@mapNotNull null; at = now + 1000L }
            o.key to at
        }

    /** Cancel everything previously set, then set the alarm clock for every occurrence that should still ring. */
    @Synchronized
    fun reschedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val p = prefs(c)
        p.getStringSet("scheduled", emptySet())?.forEach { k -> firePending(c, k, false)?.let { am.cancel(it); it.cancel() } }

        val now = System.currentTimeMillis()
        val occ = list(c)
        val keys = occ.map { it.key }.toSet()
        val fired = obj(c, "fired"); val snooze = obj(c, "snooze"); val dismissed = obj(c, "dismissed")
        // forget state for occurrences that no longer exist
        for (o in listOf(fired, snooze, dismissed)) o.keys().asSequence().toList().forEach { k -> if (k !in keys) o.remove(k) }
        putObj(c, "fired", fired); putObj(c, "snooze", snooze); putObj(c, "dismissed", dismissed)

        val exactOk = if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
        val showApp = PendingIntent.getActivity(c, 1, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val scheduled = mutableSetOf<String>()
        val firedKeys = fired.keys().asSequence().toSet()
        val dismissedKeys = dismissed.keys().asSequence().toSet()
        val snoozes = snooze.keys().asSequence().associateWith { snooze.optLong(it, 0L) }
        for ((key, at) in plan(occ, firedKeys, snoozes, dismissedKeys, now)) {
            val pi = firePending(c, key, true) ?: continue
            if (exactOk) am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showApp), pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            scheduled.add(key)
        }
        p.edit().putStringSet("scheduled", scheduled).apply()
    }
}

/** An alarm time was reached. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        val o = Alarms.find(context, key) ?: return
        if (System.currentTimeMillis() >= o.until) return
        Alarms.markFired(context, key)
        AlarmService.start(context, o)
    }
}

/** Snooze / Skip pressed on the notification. */
class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        when (intent.action) {
            AlarmService.ACTION_SNOOZE -> Alarms.snooze(context, key)
            AlarmService.ACTION_SKIP -> Alarms.dismiss(context, key)
        }
        AlarmService.stop(context)
    }
}

/** Alarms are forgotten by Android on restart, time change and app update: set them again. */
class BootReceiver : BroadcastReceiver() {
    private val handled = setOf(
        Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in handled) return
        Alarms.ensureChannel(context)
        Emergency.show(context)
        Alarms.reschedule(context)
    }
}

internal fun startServiceCompat(c: Context, i: Intent) = ContextCompat.startForegroundService(c, i)
