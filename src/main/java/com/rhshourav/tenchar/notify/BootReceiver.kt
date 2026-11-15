package com.rhshourav.tenchar.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the alert service again after a restart or an app update, if alerts are on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Alerts.enabled(context)) Alerts.startService(context)
    }
}
