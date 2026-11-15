package com.rhshourav.tenchar.notify

import android.content.Context
import android.content.Intent
import com.rhshourav.tenchar.Constants

/** True while the app's window is in front. The alert service stays quiet then: the app itself is looking. */
object AppVisibility {
    @Volatile
    var foreground = false
}

/**
 * The settings of the built-in message alerts. They are not secret, so they live in plain preferences that both the
 * app and the background service can read.
 *
 * How alerts work, with no Google, push server or other third party involved: a small foreground service asks the
 * relays this chat already uses how many messages are waiting, using a "watch token" that can do nothing else (it
 * cannot read, delete or send), and shows a fixed "New message" notice. The sender, the text and the chat are never
 * in it. The service cannot open the chats, which stay locked.
 */
object Alerts {
    const val PREFS = "tenchar_settings"
    const val KEY_ON = "alerts_on"
    const val KEY_INTERVAL = "alert_interval_s"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    fun intervalSeconds(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL, Constants.ALERT_INTERVAL_SHORT_S).coerceIn(15, 3600)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_ON, on).apply()

    fun setIntervalSeconds(context: Context, seconds: Int) = prefs(context).edit().putInt(KEY_INTERVAL, seconds).apply()

    /** Starts the service. Call while the app is in front or from a boot receiver, where Android allows it. */
    fun startService(context: Context) {
        try {
            context.startForegroundService(Intent(context, NotifyService::class.java))
        } catch (e: Exception) {
            // Android refused to start it from here; it starts the next time the app is opened.
        }
    }

    fun stopService(context: Context) {
        context.stopService(Intent(context, NotifyService::class.java))
    }
}
