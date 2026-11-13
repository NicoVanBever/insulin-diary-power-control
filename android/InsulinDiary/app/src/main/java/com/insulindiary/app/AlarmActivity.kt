package com.insulindiary.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.Date

/** Full-screen alarm, shown over the lock screen (same look as the design mockup). */
class AlarmActivity : Activity() {

    companion object { const val ACTION_CLOSE = "com.insulindiary.app.CLOSE_ALARM" }

    private val closer = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = finish()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ContextCompat.registerReceiver(this, closer, IntentFilter(ACTION_CLOSE), ContextCompat.RECEIVER_NOT_EXPORTED)

        val o = AlarmService.ringing
        if (o == null) { finish(); return }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#10295C"))
            setPadding(dp(24), dp(64), dp(24), dp(40))
        }
        fun text(s: String, sp: Float, color: String, bold: Boolean = false, top: Int = 0) = TextView(this).apply {
            text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER; if (bold) typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
        }
        col.addView(text(Alarms.label(this, "header", "Insulin Diary · alarm"), 15f, "#C9D8F5"))
        col.addView(text(DateFormat.getTimeFormat(this).format(Date()), 80f, "#FFFFFF", bold = true, top = 4))
        col.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#1F6FEB")); setStroke(dp(16), Color.parseColor("#33507FD6")) }
            layoutParams = LinearLayout.LayoutParams(dp(132), dp(132)).apply { topMargin = dp(32) }
        })
        col.addView(text(o.title, 30f, "#FFFFFF", bold = true, top = 40))
        col.addView(text(o.body, 20f, "#DBE7FD", top = 8))
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        fun button(label: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(if (filled) Color.parseColor("#0F3F99") else Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                if (filled) setColor(Color.WHITE) else { setColor(Color.TRANSPARENT); setStroke(dp(2), Color.parseColor("#8FB0EC")) }
            }
            setOnClickListener { onClick() }
        }
        val main = if (o.kind == "dose") Alarms.label(this, "tookUnits", "I took it — log {0} u").replace("{0}", fmtUnits(o.units))
            else Alarms.label(this, "logGlucose", "Log glucose now")
        col.addView(button(main, true) {
            try { AlarmService.openAppIntent(this, o).send() } catch (_: Exception) { }
            finish()
        }, LinearLayout.LayoutParams(-1, dp(60)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(Alarms.label(this, "snooze", "Snooze"), false) { Alarms.snooze(this, o.key); AlarmService.stop(this); finish() },
            LinearLayout.LayoutParams(0, dp(56), 1f).apply { rightMargin = dp(6) })
        row.addView(button(if (o.kind == "low") Alarms.label(this, "dismiss", "Dismiss") else Alarms.label(this, "skipToday", "Skip today"), false) { Alarms.dismiss(this, o.key); AlarmService.stop(this); finish() },
            LinearLayout.LayoutParams(0, dp(56), 1f).apply { leftMargin = dp(6) })
        col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        setContentView(col)
    }

    private fun fmtUnits(u: Double) = if (u % 1.0 == 0.0) u.toInt().toString() else u.toString()

    override fun onDestroy() {
        try { unregisterReceiver(closer) } catch (_: Exception) { }
        super.onDestroy()
    }
}
