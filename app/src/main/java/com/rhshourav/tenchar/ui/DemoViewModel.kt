package com.rhshourav.tenchar.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhshourav.tenchar.Constants
import com.rhshourav.tenchar.MessageLimits
import com.rhshourav.tenchar.Pairing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Screen { Gate, Pair, EnterCode, Waiting, Verify, Chat, Settings, About, KeysReset }

enum class AutoLock(val millis: Long) {
    Immediately(0L),
    Seconds30(Constants.AUTO_LOCK_SHORT_MS),
    Minutes5(Constants.AUTO_LOCK_LONG_MS),
}

enum class Delivery { Queued, Sent }

enum class Notice { Mismatch, Forgotten }

/** Where the pairing has got to. Decides where "Unlock" leads. */
enum class Stage { None, Joined, Verified }

class ChatMessage(val id: Long, val text: String, val mine: Boolean, initial: Delivery?) {
    var delivery by mutableStateOf(initial)
}

object DemoData {
    /** The example from the spec. A real number comes from the key exchange. */
    const val SAMPLE_NUMBER = "4839 2011"

    /** (text, mine). Every text must fit the message limits; a test checks this. */
    val sample = listOf("hi" to false, "on my way" to true, "ok!" to false)
}

/**
 * Preview only: no cryptography, no storage, no network. It exists so the
 * screens can be judged before the real implementation replaces it.
 */
class DemoViewModel : ViewModel() {
    var screen by mutableStateOf(Screen.Gate)
        private set
    var notice by mutableStateOf<Notice?>(null)
        private set
    var code by mutableStateOf<String?>(null)
        private set
    var entry by mutableStateOf("")
        private set
    var draft by mutableStateOf("")
        private set
    var autoLock by mutableStateOf(AutoLock.Seconds30)
        private set

    val messages = mutableStateListOf<ChatMessage>()

    private var stage = Stage.None
    private var nextId = 0L

    fun unlock() {
        screen = when (stage) {
            Stage.None -> Screen.Pair
            Stage.Joined -> Screen.Verify
            Stage.Verified -> Screen.Chat
        }
    }

    /** Drops what is on screen and returns to the gate. Pairing in progress is cancelled. */
    fun lock() {
        code = null
        entry = ""
        draft = ""
        if (screen == Screen.Waiting || screen == Screen.EnterCode) stage = Stage.None
        screen = Screen.Gate
    }

    fun back() {
        when (screen) {
            Screen.Settings -> screen = Screen.Chat
            Screen.About -> screen = Screen.Settings
            Screen.EnterCode -> screen = Screen.Pair
            Screen.Waiting -> cancelWaiting()
            else -> Unit
        }
    }

    fun open(target: Screen) {
        screen = target
    }

    fun startWaiting() {
        notice = null
        code = Pairing.format(Pairing.generate())
        screen = Screen.Waiting
    }

    fun cancelWaiting() {
        code = null
        screen = Screen.Pair
    }

    fun previewOtherPhoneJoined() {
        code = null
        stage = Stage.Joined
        screen = Screen.Verify
    }

    fun openEnterCode() {
        notice = null
        entry = ""
        screen = Screen.EnterCode
    }

    fun onEntry(input: String) {
        entry = Pairing.formatPartial(input)
    }

    fun join() {
        if (!Pairing.isValid(entry)) return
        entry = ""
        stage = Stage.Joined
        screen = Screen.Verify
    }

    fun verifyMatches() {
        stage = Stage.Verified
        if (messages.isEmpty()) {
            for ((text, mine) in DemoData.sample) {
                messages.add(ChatMessage(nextId++, text, mine, if (mine) Delivery.Sent else null))
            }
        }
        screen = Screen.Chat
    }

    fun verifyMismatch() = wipe(Notice.Mismatch)

    fun forgetConversation() = wipe(Notice.Forgotten)

    fun showKeysReset() {
        wipe(null)
        screen = Screen.KeysReset
    }

    fun pairAgain() = wipe(null)

    fun chooseAutoLock(value: AutoLock) {
        autoLock = value
    }

    fun onDraft(input: String) {
        draft = MessageLimits.clamp(input)
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return
        val message = ChatMessage(nextId++, text, mine = true, initial = Delivery.Queued)
        messages.add(message)
        draft = ""
        viewModelScope.launch {
            delay(SEND_DELAY_MS)
            message.delivery = Delivery.Sent
        }
    }

    private fun wipe(reason: Notice?) {
        code = null
        entry = ""
        draft = ""
        messages.clear()
        stage = Stage.None
        notice = reason
        screen = Screen.Pair
    }

    private companion object {
        const val SEND_DELAY_MS = 900L
    }
}
