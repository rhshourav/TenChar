package com.rhshourav.tenchar

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rhshourav.tenchar.ui.AutoLock
import com.rhshourav.tenchar.ui.DemoViewModel
import com.rhshourav.tenchar.ui.TenCharApp

class MainActivity : ComponentActivity() {
    private val vm: DemoViewModel by viewModels()

    private var deviceSecure by mutableStateOf(true)
    private var stoppedAt = NOT_STOPPED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, blank preview in the recents list.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // Transparent bars with light icons: the aurora runs behind both.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            TenCharApp(vm = vm, deviceSecure = deviceSecure, onOpenSecuritySettings = ::openSecuritySettings)
        }
    }

    override fun onStart() {
        super.onStart()
        // The app does not run without a secure lock screen. Checked on every
        // return, because the user may have just come back from settings.
        deviceSecure = getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        // The delay is only checked on return. The real version will also wipe
        // secrets from memory while in the background; this preview holds none.
        if (stoppedAt != NOT_STOPPED &&
            SystemClock.elapsedRealtime() - stoppedAt >= vm.autoLock.millis
        ) {
            vm.lock()
        }
        stoppedAt = NOT_STOPPED
    }

    override fun onStop() {
        super.onStop()
        // A rotation stops and restarts the activity; that is not leaving the app.
        if (isChangingConfigurations) return
        if (vm.autoLock == AutoLock.Immediately) vm.lock() else stoppedAt = SystemClock.elapsedRealtime()
    }

    private fun openSecuritySettings() {
        startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
    }

    private companion object {
        const val NOT_STOPPED = -1L
    }
}