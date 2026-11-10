package com.rhshourav.tenchar.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.rhshourav.tenchar.MainActivity
import com.rhshourav.tenchar.R

/**
 * The two notifications the app ever shows. The message alert is always the same fixed text: it never names the sender,
 * the chat or the content, and on a locked screen Android shows it with its contents hidden.
 */
object Notifier {
    private const val CHANNEL_MESSAGES = "messages"
    private const val CHANNEL_SERVICE = "service"
    const val ID_SERVICE = 1
    private const val ID_MESSAGE = 2

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.notify_channel_messages), NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, context.getString(R.string.notify_channel_service), NotificationManager.IMPORTANCE_MIN).apply {
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setShowBadge(false)
            },
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The quiet, permanent notice Android requires while the alert service runs. */
    fun serviceNotification(context: Context): Notification =
        Notification.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.notify_service_title))
            .setContentText(context.getString(R.string.notify_service_text))
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setContentIntent(openApp(context))
            .build()

    /** "New message". Replaces the previous one instead of piling up. */
    fun showMessageAlert(context: Context) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        manager.notify(
            ID_MESSAGE,
            Notification.Builder(context, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.notify_message_text))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build(),
        )
    }

    fun cancelMessageAlert(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_MESSAGE)
    }
}
