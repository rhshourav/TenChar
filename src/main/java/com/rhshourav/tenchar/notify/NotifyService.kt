package com.rhshourav.tenchar.notify

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.rhshourav.tenchar.net.RelayClient
import com.rhshourav.tenchar.net.Reply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The built-in message alert. While it runs, every [Alerts.intervalSeconds] seconds it asks each chat's relays how many
 * messages are waiting and, if there are new ones and the app is not in front, shows one fixed "New message" notice.
 *
 * It holds no keys and cannot open a chat: all it has is each chat's watch token (see [WatchStore]). It stops by itself
 * when alerts are switched off or there is nothing to watch.
 */
class NotifyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.ensureChannels(this)
        val notice = Notifier.serviceNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifier.ID_SERVICE, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            } else {
                startForeground(Notifier.ID_SERVICE, notice)
            }
        } catch (e: Exception) {
            stopSelf() // Android does not allow a foreground service to start from here
            return START_NOT_STICKY
        }
        if (job?.isActive != true) job = scope.launch { loop() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun loop() {
        while (scope.isActive) {
            val watches = WatchStore.load(this)
            if (!Alerts.enabled(this) || watches.isEmpty()) {
                stopSelf()
                return
            }
            if (!AppVisibility.foreground) {
                // Keeps the CPU awake just long enough for the requests to finish.
                val wake = getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tenchar:alerts")
                wake?.acquire(WAKE_MS)
                try {
                    check(watches)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failed round is simply tried again next time.
                } finally {
                    if (wake?.isHeld == true) wake.release()
                }
            }
            delay(Alerts.intervalSeconds(this) * 1000L)
        }
    }

    private suspend fun check(watches: List<WatchStore.Watch>) {
        var fresh = false
        for (w in watches) {
            for ((i, endpoint) in w.endpoints.withIndex()) {
                val reply = try {
                    RelayClient(endpoint).peek(w.room, w.token)
                } catch (e: IllegalArgumentException) {
                    continue
                }
                if (reply is Reply.Ok && reply.value.waiting > 0 && reply.value.latest > w.notified[i]) {
                    WatchStore.markNotified(this, w.room, endpoint, reply.value.latest)
                    fresh = true
                }
            }
        }
        if (fresh && !AppVisibility.foreground) Notifier.showMessageAlert(this)
    }

    private companion object {
        const val WAKE_MS = 30_000L
    }
}
