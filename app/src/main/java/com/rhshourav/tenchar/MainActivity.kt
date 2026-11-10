package com.rhshourav.tenchar

import android.app.KeyguardManager
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
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
import com.rhshourav.tenchar.notify.AppVisibility
import com.rhshourav.tenchar.notify.Notifier
import com.rhshourav.tenchar.store.Authenticator
import com.rhshourav.tenchar.ui.AppViewModel
import com.rhshourav.tenchar.ui.AutoLock
import com.rhshourav.tenchar.ui.Screen
import com.rhshourav.tenchar.ui.TenCharApp
import javax.crypto.Cipher

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private var deviceSecure by mutableStateOf(true)
    private var stoppedAt = NOT_STOPPED
    private val handler = Handler(Looper.getMainLooper())
    private val lockWhenIdle = Runnable { vm.lock() }

    /** The unlock prompt is shown once per start, when the window first has focus. */
    private var promptOnFocus = false
    private var promptSignal: CancellationSignal? = null

    /**
     * The system unlock prompt (fingerprint, face or screen lock). The Keystore key it opens is valid for a few
     * seconds afterwards (see Vault), so a plain prompt is enough. Only a vault from an earlier version passes a
     * [cipher], which then has to be used through the prompt itself.
     */
    private val authenticator = Authenticator { cipher, onSuccess, onFailure ->
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.auth_title))
            .setDescription(getString(R.string.auth_body))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        val signal = CancellationSignal()
        promptSignal = signal
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                promptSignal = null
                if (cipher == null) {
                    onSuccess(null)
                } else {
                    val authed: Cipher? = result.cryptoObject?.cipher
                    if (authed != null) onSuccess(authed) else onFailure(false)
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                promptSignal = null
                onFailure(errorCode == ERROR_CANCELED || errorCode == ERROR_USER_CANCELED)
            }
        }
        if (cipher == null) {
            prompt.authenticate(signal, mainExecutor, callback)
        } else {
            prompt.authenticate(BiometricPrompt.CryptoObject(cipher), signal, mainExecutor, callback)
        }
    }

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
            TenCharApp(
                vm = vm,
                deviceSecure = deviceSecure,
                onOpenSecuritySettings = ::openSecuritySettings,
                onUnlock = { vm.unlock(authenticator) },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(lockWhenIdle)
        // The app does not run without a secure lock screen. Checked on every
        // return, because the user may have just come back from settings.
        deviceSecure = getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        // Backup for a frozen process: the timer below may not have run.
        if (stoppedAt != NOT_STOPPED &&
            SystemClock.elapsedRealtime() - stoppedAt >= vm.autoLock.millis
        ) {
            vm.lock()
        }
        stoppedAt = NOT_STOPPED
        AppVisibility.foreground = true
        Notifier.cancelMessageAlert(this)
        vm.setForeground(true)
        // Back at the gate (first run after setup, or after a lock): ask as soon as the window has focus. Asking
        // from onStart, before focus, makes some under-display fingerprint sensors cancel the prompt at once.
        promptOnFocus = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Only the first focus after a start: when the prompt closes, focus returns here, and asking again would
        // trap someone who just cancelled.
        if (hasFocus && promptOnFocus) {
            promptOnFocus = false
            if (deviceSecure && vm.screen == Screen.Gate) vm.unlock(authenticator)
        }
    }

    override fun onStop() {
        super.onStop()
        // A rotation stops and restarts the activity; that is not leaving the app.
        if (isChangingConfigurations) return
        promptOnFocus = false
        promptSignal?.cancel()
        promptSignal = null
        // Nothing talks to a relay while the app is out of sight (the alert service only asks how many messages wait).
        AppVisibility.foreground = false
        vm.setForeground(false)
        if (vm.autoLock == AutoLock.Immediately) {
            vm.lock()
        } else {
            stoppedAt = SystemClock.elapsedRealtime()
            // Keys and messages are wiped from memory when the delay passes, not only on return.
            handler.postDelayed(lockWhenIdle, vm.autoLock.millis)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(lockWhenIdle)
        super.onDestroy()
    }

    private fun openSecuritySettings() {
        startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
    }

    private companion object {
        const val NOT_STOPPED = -1L

        // BiometricPrompt error codes (BIOMETRIC_ERROR_CANCELED, BIOMETRIC_ERROR_USER_CANCELED).
        const val ERROR_CANCELED = 5
        const val ERROR_USER_CANCELED = 10
    }
}
