package com.rhshourav.tenchar.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rhshourav.tenchar.Constants
import com.rhshourav.tenchar.Hex
import com.rhshourav.tenchar.MessageLimits
import com.rhshourav.tenchar.Pairing
import com.rhshourav.tenchar.RelayAddress
import com.rhshourav.tenchar.crypto.Handshake
import com.rhshourav.tenchar.crypto.LocalMember
import com.rhshourav.tenchar.crypto.Ratchet
import com.rhshourav.tenchar.crypto.SystemRandom
import com.rhshourav.tenchar.crypto.Wire
import com.rhshourav.tenchar.net.FailReason
import com.rhshourav.tenchar.net.PairOutcome
import com.rhshourav.tenchar.net.PairingFlow
import com.rhshourav.tenchar.net.RelayClient
import com.rhshourav.tenchar.net.Reply
import com.rhshourav.tenchar.store.AppState
import com.rhshourav.tenchar.store.Authenticator
import com.rhshourav.tenchar.store.Conversation
import com.rhshourav.tenchar.store.KeysLostException
import com.rhshourav.tenchar.store.KeysLostReason
import com.rhshourav.tenchar.store.OutboxItem
import com.rhshourav.tenchar.store.Profile
import com.rhshourav.tenchar.store.StateCodec
import com.rhshourav.tenchar.store.StoredMessage
import com.rhshourav.tenchar.store.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Base64
import javax.crypto.Cipher

enum class Screen { Setup, Gate, Chats, Pair, EnterCode, Waiting, Verify, Chat, ChatSettings, Settings, About, KeysReset }

enum class AutoLock(val millis: Long) {
    Immediately(0L),
    Seconds30(Constants.AUTO_LOCK_SHORT_MS),
    Minutes5(Constants.AUTO_LOCK_LONG_MS),
}

enum class Delivery { Queued, Sent }

enum class Notice { Mismatch, Forgotten, Timeout, Unreachable, Tampered, Full, Storage, TooManyChats }

/** How the relays look from here. Gone means every relay says the conversation no longer exists. */
enum class Connection { Online, Offline, Gone }

/** Why turning notifications on did not work. */
enum class PushProblem { Unsupported, Rejected, Unreachable }

class ChatMessage(val id: Long, val text: String, val mine: Boolean, initial: Delivery?) {
    var delivery by mutableStateOf(initial)
}

/** What the screens show about one chat. A mirror of the saved [Conversation], updated as it changes. */
class ChatEntry(val id: String) {
    /** The other person, as you see them: your own label if you set one, else what they shared. */
    var name by mutableStateOf("")
    var avatar by mutableStateOf("")
    var color by mutableStateOf(0)
    var meName by mutableStateOf("")
    var meAvatar by mutableStateOf("")
    var meColor by mutableStateOf(0)
    var theirLabelIsMine by mutableStateOf(false)
    var unread by mutableStateOf(0)
    var verified by mutableStateOf(false)
    var notifications by mutableStateOf(false)
    var endpoints by mutableStateOf<List<String>>(emptyList())
}

/**
 * The one place that holds state. Secrets live in [app] and [dek] only while unlocked; [lock] overwrites them.
 * Everything on disk goes through [Vault], and **nothing is ever deleted because of an error**: only the person
 * choosing "erase" or "forget" removes data.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    var screen by mutableStateOf(Screen.Gate)
        private set
    var notice by mutableStateOf<Notice?>(null)
        private set
    var code by mutableStateOf<String?>(null)
        private set

    /** The pairing code as typed, without hyphens. The field draws the hyphens. */
    var entry by mutableStateOf("")
        private set
    var draft by mutableStateOf("")
        private set
    var autoLock by mutableStateOf(AutoLock.Seconds30)
        private set

    /** Relays for the next chat you pair. Each chat keeps the relays it was paired on. */
    var relayUrl by mutableStateOf(Constants.DEFAULT_RELAY_URL)
        private set
    var fallbackEnabled by mutableStateOf(true)
        private set
    var fallbackUrl by mutableStateOf(Constants.FALLBACK_RELAY_URL)
        private set
    var pushServer by mutableStateOf(Constants.DEFAULT_PUSH_SERVER)
        private set

    /** True while this phone is joining with a typed code (as opposed to showing its own). */
    var joining by mutableStateOf(false)
        private set
    var unlocking by mutableStateOf(false)
        private set
    var unlockFailed by mutableStateOf(false)
        private set

    /** Set when the saved data cannot be opened. The files are still on the phone. */
    var lostReason by mutableStateOf<KeysLostReason?>(null)
        private set
    var connection by mutableStateOf(Connection.Online)
        private set
    var verification by mutableStateOf<String?>(null)
        private set

    /** What a new chat starts with as "you". */
    var defaultProfile by mutableStateOf(Profile.EMPTY)
        private set

    /** The address to subscribe to in the push app, shown after notifications are turned on. */
    var pushHelp by mutableStateOf<String?>(null)
        private set
    var pushProblem by mutableStateOf<PushProblem?>(null)
        private set
    var currentId by mutableStateOf<String?>(null)
        private set

    val chats = mutableStateListOf<ChatEntry>()

    /** The messages of the open chat. */
    val messages = mutableStateListOf<ChatMessage>()

    val current: ChatEntry? get() = chats.firstOrNull { it.id == currentId }

    private val prefs by lazy {
        getApplication<Application>().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
    private val vault = Vault(application)
    private val random = SystemRandom
    private val gate = Mutex()
    private val clients = HashMap<String, RelayClient>()
    private val retryAt = HashMap<String, Long>()
    private val goneRounds = HashMap<String, Int>()
    private val connectionOf = HashMap<String, Connection>()
    private val nextDue = HashMap<String, Long>()
    private val entries = HashMap<String, ChatEntry>()

    private var dek: ByteArray? = null
    private var app: AppState? = null
    private var foreground = false
    private var pairingJob: Job? = null
    private var flow: PairingFlow? = null
    private var pollJob: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    val setupDone: Boolean get() = prefs.getBoolean(KEY_SETUP_DONE, false)

    init {
        // Checked again on load, in case a stored value was edited.
        prefs.getString(KEY_RELAY, null)?.let { RelayAddress.normalise(it) }?.let { relayUrl = it }
        fallbackEnabled = prefs.getBoolean(KEY_FALLBACK_ON, true)
        prefs.getString(KEY_FALLBACK, null)?.let { RelayAddress.normalise(it) }?.let { fallbackUrl = it }
        prefs.getString(KEY_PUSH_SERVER, null)?.let { RelayAddress.normalise(it) }?.let { pushServer = it }
        AutoLock.entries.firstOrNull { it.name == prefs.getString(KEY_AUTOLOCK, null) }?.let { autoLock = it }
        screen = if (setupDone) Screen.Gate else Screen.Setup
    }

    // ---- settings --------------------------------------------------------------------------------------

    /** Saves the relay for the next chat. Returns false, changing nothing, if the address is not acceptable. */
    fun saveRelay(input: String): Boolean {
        val value = RelayAddress.normalise(input) ?: return false
        relayUrl = value
        if (value == Constants.DEFAULT_RELAY_URL) prefs.edit().remove(KEY_RELAY).apply()
        else prefs.edit().putString(KEY_RELAY, value).apply()
        return true
    }

    fun resetRelay() {
        relayUrl = Constants.DEFAULT_RELAY_URL
        prefs.edit().remove(KEY_RELAY).apply()
    }

    fun saveFallback(input: String): Boolean {
        val value = RelayAddress.normalise(input) ?: return false
        fallbackUrl = value
        if (value == Constants.FALLBACK_RELAY_URL) prefs.edit().remove(KEY_FALLBACK).apply()
        else prefs.edit().putString(KEY_FALLBACK, value).apply()
        return true
    }

    fun resetFallback() {
        fallbackUrl = Constants.FALLBACK_RELAY_URL
        prefs.edit().remove(KEY_FALLBACK).apply()
    }

    fun enableFallback(value: Boolean) {
        fallbackEnabled = value
        prefs.edit().putBoolean(KEY_FALLBACK_ON, value).apply()
    }

    fun savePushServer(input: String): Boolean {
        val value = RelayAddress.normalise(input) ?: return false
        pushServer = value
        if (value == Constants.DEFAULT_PUSH_SERVER) prefs.edit().remove(KEY_PUSH_SERVER).apply()
        else prefs.edit().putString(KEY_PUSH_SERVER, value).apply()
        return true
    }

    fun resetPushServer() {
        pushServer = Constants.DEFAULT_PUSH_SERVER
        prefs.edit().remove(KEY_PUSH_SERVER).apply()
    }

    fun chooseAutoLock(value: AutoLock) {
        autoLock = value
        prefs.edit().putString(KEY_AUTOLOCK, value.name).apply()
    }

    fun finishSetup() {
        prefs.edit().putBoolean(KEY_SETUP_DONE, true).apply()
        goto(Screen.Gate)
    }

    /** Relays for a new pairing: the main one, then the backup if it is on and different. */
    private fun endpoints(): List<String> = buildList {
        add(relayUrl)
        if (fallbackEnabled && fallbackUrl != relayUrl) add(fallbackUrl)
    }

    private fun clientFor(endpoint: String): RelayClient = clients.getOrPut(endpoint) { RelayClient(endpoint) }

    // ---- lock and unlock ---------------------------------------------------------------------------------

    /** Starts the system unlock prompt. The first run creates the vault, later runs open it. */
    fun unlock(auth: Authenticator) {
        if (unlocking || dek != null) return
        unlocking = true
        unlockFailed = false
        val create = !vault.exists()
        val cipher = try {
            if (create) vault.cipherForCreate() else vault.cipherForUnlock()
        } catch (e: KeysLostException) {
            unlocking = false
            keysLost(e.reason)
            return
        } catch (e: Exception) {
            // Temporary: nothing is touched, the person can simply try again.
            unlocking = false
            unlockFailed = true
            return
        }
        auth.authenticate(
            cipher,
            onSuccess = { authed -> finishUnlock(authed, create) },
            onFailure = { cancelled ->
                unlocking = false
                unlockFailed = !cancelled
            },
        )
    }

    private fun finishUnlock(cipher: Cipher, create: Boolean) {
        val key = try {
            if (create) vault.completeCreate(cipher) else vault.completeUnlock(cipher)
        } catch (e: KeysLostException) {
            unlocking = false
            keysLost(e.reason)
            return
        } catch (e: Exception) {
            unlocking = false
            unlockFailed = true
            return
        }
        viewModelScope.launch {
            try {
                val candidates = withContext(Dispatchers.IO) { vault.loadCandidates(key) }
                var loaded: AppState? = null
                var resave = false
                try {
                    for ((i, bytes) in candidates.withIndex()) {
                        try {
                            // Byte 4 is the format version. An older file is read as it is and saved back as the newest.
                            val older = bytes.size > 4 && bytes[4].toInt() < StateCodec.VERSION
                            loaded = StateCodec.decode(bytes, random)
                            resave = i > 0 || older // from the backup, or from an older format: write the current file again
                            break
                        } catch (e: IOException) {
                            // This copy is unreadable: try the next one.
                        }
                    }
                } finally {
                    candidates.forEach { it.fill(0) }
                }
                if (candidates.isNotEmpty() && loaded == null) throw KeysLostException(KeysLostReason.StateUnreadable)
                val state = loaded ?: AppState()
                dek = key
                app = state
                defaultProfile = state.defaultProfile
                goneRounds.clear(); connectionOf.clear(); retryAt.clear(); nextDue.clear()
                syncAll()
                goto(if (state.conversations.isEmpty()) Screen.Pair else Screen.Chats)
                if (resave) gate.withLock { persist() }
                reRegisterPush()
            } catch (e: KeysLostException) {
                key.fill(0)
                keysLost(e.reason)
            } catch (e: Exception) {
                key.fill(0)
                unlockFailed = true
            } finally {
                unlocking = false
            }
        }
    }

    /** Drops everything on screen and in memory and returns to the gate. Pairing in progress is cancelled. */
    fun lock() {
        if (screen == Screen.Setup || screen == Screen.KeysReset || screen == Screen.Gate) return
        cancelPairing()
        stopPolling()
        app?.wipe()
        app = null
        dek?.fill(0)
        dek = null
        chats.clear(); entries.clear(); messages.clear()
        currentId = null
        verification = null
        code = null
        entry = ""
        draft = ""
        notice = null
        pushHelp = null
        pushProblem = null
        goto(Screen.Gate)
    }

    /**
     * The saved data cannot be opened and trying again will not help (for example Android removed the key). The files are
     * **not** deleted: the person sees why, can try again, and erases only by choosing to.
     */
    private fun keysLost(reason: KeysLostReason) {
        cancelPairing()
        stopPolling()
        app?.wipe()
        app = null
        dek?.fill(0)
        dek = null
        chats.clear(); entries.clear(); messages.clear()
        currentId = null
        verification = null
        lostReason = reason
        goto(Screen.KeysReset)
    }

    /** "Try again" on the keys-reset screen: back to the gate with nothing changed. */
    fun retryUnlock() {
        lostReason = null
        unlockFailed = false
        goto(Screen.Gate)
    }

    /** "Erase and start over": the only way saved data is removed after a failure, and only by the person's choice. */
    fun eraseAndStartOver() {
        vault.wipe()
        lostReason = null
        unlockFailed = false
        notice = null
        goto(Screen.Gate)
    }

    fun setForeground(value: Boolean) {
        foreground = value
        updatePolling()
    }

    // ---- navigation --------------------------------------------------------------------------------------

    private fun goto(target: Screen) {
        screen = target
        updatePolling()
    }

    fun back() {
        when (screen) {
            Screen.Chat, Screen.Verify -> closeChat()
            Screen.ChatSettings -> goto(Screen.Chat)
            Screen.Settings -> goto(Screen.Chats)
            Screen.About -> goto(Screen.Settings)
            Screen.Pair -> if (chats.isNotEmpty()) goto(Screen.Chats)
            Screen.EnterCode -> goto(Screen.Pair)
            Screen.Waiting -> cancelWaiting()
            else -> Unit
        }
    }

    fun open(target: Screen) = goto(target)

    // ---- chats --------------------------------------------------------------------------------------------

    private fun conversation(id: String?): Conversation? = id?.let { app?.find(it) }

    private fun syncEntry(e: ChatEntry, c: Conversation) {
        val them = c.them
        e.name = them.name; e.avatar = them.avatar; e.color = them.color
        e.meName = c.me.name; e.meAvatar = c.me.avatar; e.meColor = c.me.color
        e.theirLabelIsMine = c.themLocal != null
        e.unread = c.unread
        e.verified = c.verified
        e.notifications = c.pushUrl != null
        e.endpoints = c.endpoints
    }

    private fun entryFor(c: Conversation): ChatEntry =
        entries.getOrPut(c.localId) { ChatEntry(c.localId) }.also { syncEntry(it, c) }

    /** Rebuilds the list of chats. Only for structural changes (unlock, add, delete); field changes use [syncEntry]. */
    private fun syncAll() {
        val state = app
        chats.clear()
        if (state == null) {
            entries.clear()
            return
        }
        entries.keys.retainAll(state.conversations.map { it.localId }.toSet())
        for (c in state.conversations) chats.add(entryFor(c))
    }

    fun openChat(id: String) {
        val conv = conversation(id) ?: return
        currentId = id
        conv.unread = 0
        entries[id]?.let { syncEntry(it, conv) }
        messages.clear()
        conv.messages.forEach { messages.add(it.toUi()) }
        verification = conv.verification
        connection = connectionOf[id] ?: Connection.Online
        draft = ""
        goto(if (conv.verified) Screen.Chat else Screen.Verify)
        viewModelScope.launch { gate.withLock { persist() } }
        wake.trySend(Unit)
    }

    fun closeChat() {
        currentId = null
        messages.clear()
        verification = null
        draft = ""
        goto(Screen.Chats)
    }

    fun startNewChat() {
        notice = null
        if (chats.size >= AppState.MAX_CHATS) {
            notice = Notice.TooManyChats
            return
        }
        goto(Screen.Pair)
    }

    // ---- pairing -----------------------------------------------------------------------------------------

    fun startWaiting() {
        notice = null
        joining = false
        val raw = Pairing.generate()
        code = Pairing.format(raw)
        goto(Screen.Waiting)
        beginPairing(raw, creator = true)
    }

    fun openEnterCode() {
        notice = null
        entry = ""
        goto(Screen.EnterCode)
    }

    /** Keeps only the valid letters, upper case, at most 16. The field shows them with hyphens. */
    fun onEntry(input: String) {
        entry = Pairing.normalise(input).filter { it in Constants.CODE_ALPHABET }.take(Constants.CODE_LEN)
    }

    fun join() {
        if (!Pairing.isValid(entry)) return
        val raw = entry
        entry = ""
        notice = null
        code = null
        joining = true
        goto(Screen.Waiting)
        beginPairing(raw, creator = false)
    }

    fun cancelWaiting() {
        cancelPairing()
        code = null
        goto(Screen.Pair)
    }

    private fun cancelPairing() {
        pairingJob?.cancel()
        pairingJob = null
        val f = flow
        flow = null
        joining = false
        if (f != null) viewModelScope.launch { f.cleanup() }
    }

    private fun beginPairing(raw: String, creator: Boolean) {
        pairingJob?.cancel()
        val relays = endpoints() // this chat keeps these relays from now on
        pairingJob = viewModelScope.launch {
            var derived: Pairing.Derived? = null
            var member: LocalMember? = null
            try {
                val d = Pairing.derive(raw)
                derived = d
                val me = LocalMember.create(random)
                member = me
                val f = PairingFlow(
                    endpoints = relays,
                    room = d.room,
                    codeToken = d.codeToken,
                    me = me,
                    clientFor = ::clientFor,
                    timeoutMs = if (creator) Constants.PAIRING_WINDOW_HOURS * 3_600_000L else JOIN_TIMEOUT_MS,
                )
                flow = f
                when (val outcome = f.run()) {
                    is PairOutcome.Success -> completePairing(d, me, outcome)
                    is PairOutcome.Failure -> {
                        flow = null
                        f.cleanup()
                        failPairing(outcome.reason)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val f = flow
                flow = null
                f?.cleanup()
                failPairing(FailReason.Unreachable)
            } finally {
                derived?.wipe()
                member?.keys?.wipe()
            }
        }
    }

    private suspend fun completePairing(derived: Pairing.Derived, me: LocalMember, outcome: PairOutcome.Success) {
        val state = app ?: return
        val peerEk = Hex.decode(outcome.peer.ek)
        val agreement = try {
            Handshake.agree(me.keys, me.memberId, Hex.decode(outcome.peer.ik), peerEk, outcome.peer.memberId, derived.codeKey)
        } catch (e: IllegalArgumentException) {
            // A low-order or otherwise unusable key from the other side.
            val f = flow
            flow = null
            f?.cleanup()
            failPairing(FailReason.Tampered)
            return
        }
        val ratchet = if (agreement.initiator) {
            Ratchet.forInitiator(agreement.sk, agreement.ad, me.keys.ek, peerEk, random)
        } else {
            Ratchet.forResponder(agreement.sk, agreement.ad, me.keys.ek, peerEk, random)
        }
        agreement.wipe()
        val conv = Conversation(
            room = derived.room,
            memberId = me.memberId,
            memberToken = me.memberToken,
            endpoints = outcome.endpoints,
            ratchet = ratchet,
            verification = agreement.verification,
            verified = false,
            me = defaultProfile,
        )
        val saved = gate.withLock {
            state.conversations += conv
            persist()
        }
        if (!saved) {
            gate.withLock { state.conversations.remove(conv) }
            conv.wipe()
            val f = flow
            flow = null
            f?.cleanup()
            notice = Notice.Storage
            code = null
            joining = false
            goto(Screen.Pair)
            return
        }
        flow = null
        chats.add(entryFor(conv))
        currentId = conv.localId
        messages.clear()
        verification = conv.verification
        code = null
        joining = false
        goto(Screen.Verify)
    }

    private fun failPairing(reason: FailReason) {
        code = null
        joining = false
        notice = when (reason) {
            FailReason.Timeout -> Notice.Timeout
            FailReason.Unreachable -> Notice.Unreachable
            FailReason.Tampered -> Notice.Tampered
            FailReason.Full -> Notice.Full
        }
        goto(Screen.Pair)
    }

    fun verifyMatches() {
        val conv = conversation(currentId) ?: return
        conv.verified = true
        entries[conv.localId]?.let { syncEntry(it, conv) }
        goto(Screen.Chat)
        viewModelScope.launch {
            gate.withLock {
                queueProfile(conv) // tell them how you appear
                persist()
            }
            wake.trySend(Unit)
        }
    }

    fun verifyMismatch() = deleteChat(currentId, Notice.Mismatch)

    fun forgetChat(id: String) = deleteChat(id, Notice.Forgotten)

    /** Deletes one chat here and, best effort, its rooms on its relays (which also removes its notification address). */
    private fun deleteChat(id: String?, reason: Notice?) {
        val state = app ?: return
        val conv = id?.let { state.find(it) } ?: return
        val relays = conv.endpoints
        val room = conv.room
        val token = conv.memberToken
        viewModelScope.launch {
            for (ep in relays) {
                try {
                    clientFor(ep).delete(room, token)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Best effort; the relay deletes idle rooms itself.
                }
            }
        }
        conv.wipe()
        state.conversations.remove(conv)
        entries.remove(conv.localId)
        syncAll()
        if (currentId == conv.localId) {
            currentId = null
            messages.clear()
            verification = null
            draft = ""
        }
        notice = reason
        goto(if (state.conversations.isEmpty()) Screen.Pair else Screen.Chats)
        viewModelScope.launch { gate.withLock { persist() } }
    }

    // ---- names, avatars, notifications -----------------------------------------------------------------

    /** How you appear in this chat. Sent to the other person. */
    fun setMyProfile(id: String, profile: Profile) {
        val conv = conversation(id) ?: return
        conv.me = profile
        entries[id]?.let { syncEntry(it, conv) }
        viewModelScope.launch {
            gate.withLock {
                if (conv.verified) queueProfile(conv)
                persist()
            }
            wake.trySend(Unit)
        }
    }

    /** How you see the other person on this phone. Null goes back to whatever they shared. Never sent. */
    fun setThemProfile(id: String, profile: Profile?) {
        val conv = conversation(id) ?: return
        conv.themLocal = profile
        entries[id]?.let { syncEntry(it, conv) }
        viewModelScope.launch { gate.withLock { persist() } }
    }

    /** What new chats start with as "you". Chats you already have are not changed. */
    fun updateDefaultProfile(profile: Profile) {
        val state = app ?: return
        state.defaultProfile = profile
        defaultProfile = profile
        viewModelScope.launch { gate.withLock { persist() } }
    }

    /** Queues a profile frame for the other person. Call with [gate] held. */
    private fun queueProfile(conv: Conversation) {
        if (conv.me.isEmpty) return
        val frame = Wire.encodeProfile(conv.me.name, conv.me.avatar, conv.me.color) ?: return
        try {
            conv.outbox.add(OutboxItem(PROFILE_MSG_ID, conv.ratchet.encrypt(frame)))
        } catch (e: IllegalStateException) {
            // The chain is used up: nothing more can be sent on this chat.
        } finally {
            frame.fill(0)
        }
    }

    /**
     * Turns notifications for a chat on or off. Notifications come from a push service (ntfy by default), not from
     * Google: the relay sends it a short fixed "New message" notice at a secret address only this chat uses. The
     * person subscribes to that address in the push app.
     */
    fun setNotifications(id: String, on: Boolean) {
        val state = app ?: return
        val conv = state.find(id) ?: return
        viewModelScope.launch {
            if (on) {
                val url = pushServer.trimEnd('/') + "/" + newTopic()
                var ok = false
                var problem: PushProblem? = null
                for (ep in conv.endpoints) {
                    when (val r = clientFor(ep).registerPush(conv.room, conv.memberToken, url)) {
                        is Reply.Ok -> ok = true
                        // 400: this relay does not allow that push service. 404 or 501: the relay predates push.
                        is Reply.Rejected -> if (r.status == 400) problem = PushProblem.Rejected
                        else if (problem == null) problem = PushProblem.Unsupported
                        Reply.Unreachable -> if (problem == null) problem = PushProblem.Unreachable
                    }
                }
                if (app !== state) return@launch
                if (ok) {
                    gate.withLock {
                        conv.pushUrl = url
                        persist()
                    }
                    entries[id]?.let { syncEntry(it, conv) }
                    pushProblem = null
                    pushHelp = url
                } else {
                    pushProblem = problem ?: PushProblem.Unreachable
                }
            } else {
                for (ep in conv.endpoints) clientFor(ep).registerPush(conv.room, conv.memberToken, "")
                if (app !== state) return@launch
                gate.withLock {
                    conv.pushUrl = null
                    persist()
                }
                entries[id]?.let { syncEntry(it, conv) }
            }
        }
    }

    fun dismissPushHelp() {
        pushHelp = null
    }

    fun dismissPushProblem() {
        pushProblem = null
    }

    /** A relay that lost its data (a free host restarting) forgets addresses, so they are registered again on unlock. */
    private fun reRegisterPush() {
        val state = app ?: return
        viewModelScope.launch {
            for (conv in state.conversations.toList()) {
                val url = conv.pushUrl ?: continue
                for (ep in conv.endpoints) {
                    try {
                        clientFor(ep).registerPush(conv.room, conv.memberToken, url)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Best effort.
                    }
                }
            }
        }
    }

    private fun newTopic(): String {
        // 24 random bytes: 192 bits, written as 32 characters from A-Z a-z 0-9 _ - (what the relay accepts).
        val bytes = ByteArray(24).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).also { bytes.fill(0) }
    }

    // ---- messaging -----------------------------------------------------------------------------------------

    fun onDraft(input: String) {
        // The composer is one logical line: a pasted line break or tab becomes a space.
        val flat = input.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
        draft = MessageLimits.clamp(flat)
    }

    fun send() {
        val text = draft.trim()
        val state = app ?: return
        val conv = conversation(currentId) ?: return
        if (!conv.verified || text.isEmpty()) return
        // More than one message's worth of text becomes several messages, each shown as its own bubble.
        val pieces = MessageLimits.split(text)
        if (pieces.isEmpty()) return
        // Refuse rather than half-send if the queue could not take every frame (it empties as the relay accepts them).
        if (conv.outbox.size + pieces.sumOf { Wire.frameCount(it) } > MAX_OUTBOX_FRAMES) return
        draft = ""
        viewModelScope.launch {
            gate.withLock {
                if (app !== state) return@withLock
                for (piece in pieces) {
                    val id = conv.nextMsgId++
                    // One frame for a short message, several fragments for a longer one; the id ties them together.
                    val frames = Wire.encodeMessage(piece, id.toInt() and 0xFFFF) ?: continue
                    val envelopes = try {
                        frames.map { conv.ratchet.encrypt(it) }
                    } catch (e: IllegalStateException) {
                        break
                    } finally {
                        frames.forEach { it.fill(0) }
                    }
                    addMessage(conv, StoredMessage(id, piece, true, Conversation.DELIVERY_QUEUED))
                    for (envelope in envelopes) conv.outbox.add(OutboxItem(id, envelope))
                }
                // The ratchet has moved on: save before anything is sent.
                persist()
            }
            wake.trySend(Unit)
        }
    }

    private fun addMessage(conv: Conversation, message: StoredMessage) {
        conv.messages.add(message)
        val open = conv.localId == currentId
        if (open) messages.add(message.toUi())
        while (conv.messages.size > Conversation.MAX_MESSAGES) {
            conv.messages.removeAt(0)
            if (open && messages.isNotEmpty()) messages.removeAt(0)
        }
    }

    private fun StoredMessage.toUi(): ChatMessage = ChatMessage(
        id, text, mine,
        when (delivery) {
            Conversation.DELIVERY_QUEUED -> Delivery.Queued
            Conversation.DELIVERY_SENT -> Delivery.Sent
            else -> null
        },
    )

    // ---- talking to the relays ------------------------------------------------------------------------------

    private fun updatePolling() {
        val state = app
        val should = foreground && dek != null && state != null && state.conversations.any { it.verified } &&
            screen != Screen.Setup && screen != Screen.Gate && screen != Screen.KeysReset
        if (should) {
            if (pollJob?.isActive != true) pollJob = viewModelScope.launch { pollLoop() }
        } else {
            stopPolling()
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollLoop() {
        while (true) {
            val healthy = try {
                cycle()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            withTimeoutOrNull(if (healthy) POLL_MS else BACKOFF_MS) { wake.receive() }
        }
    }

    private enum class Probe { Reached, Gone, Down }

    /** One round over every verified chat: the open one every time, the others now and then. True if a relay answered. */
    private suspend fun cycle(): Boolean {
        val state = app ?: return true
        return gate.withLock {
            if (app !== state) return@withLock true
            var any = false
            for (conv in state.conversations.toList()) {
                if (!conv.verified) continue
                if (app !== state) return@withLock true
                val open = conv.localId == currentId
                if (!open && (nextDue[conv.localId] ?: 0L) > now()) continue
                nextDue[conv.localId] = now() + if (open) 0L else BACKGROUND_CHAT_POLL_MS
                if (cycleChat(conv, state)) any = true
            }
            any
        }
    }

    /** Sends what is queued for [conv], then collects what is waiting. True if one of its relays answered. */
    private suspend fun cycleChat(conv: Conversation, state: AppState): Boolean {
        flushOutbox(conv, state)
        var reached = 0
        var gone = 0
        for (ep in conv.endpoints) {
            if (app !== state) return true
            if (inBackoff(conv, ep)) {
                gone++
                continue
            }
            when (pollEndpoint(conv, state, ep)) {
                Probe.Reached -> {
                    reached++
                    retryAt.remove(backoffKey(conv, ep))
                }
                Probe.Gone -> {
                    gone++
                    retryAt[backoffKey(conv, ep)] = now() + GONE_RETRY_MS
                }
                Probe.Down -> Unit
            }
        }
        val id = conv.localId
        val status = when {
            reached > 0 -> {
                goneRounds[id] = 0
                Connection.Online
            }
            gone == conv.endpoints.size -> {
                val n = (goneRounds[id] ?: 0) + 1
                goneRounds[id] = n
                if (n >= 3) Connection.Gone else connectionOf[id] ?: Connection.Online
            }
            else -> Connection.Offline
        }
        connectionOf[id] = status
        if (id == currentId) connection = status
        return reached > 0
    }

    private fun backoffKey(conv: Conversation, endpoint: String) = "${conv.localId}@$endpoint"

    private fun inBackoff(conv: Conversation, endpoint: String): Boolean =
        (retryAt[backoffKey(conv, endpoint)] ?: 0L) > now()

    private suspend fun flushOutbox(conv: Conversation, state: AppState) {
        while (conv.outbox.isNotEmpty()) {
            val item = conv.outbox.first()
            val text = Wire.toBase64(item.envelope)
            var sent = false
            for (ep in conv.endpoints) {
                if (inBackoff(conv, ep)) continue
                val reply = clientFor(ep).post(conv.room, conv.memberToken, text)
                if (app !== state) return
                if (reply is Reply.Ok) {
                    sent = true
                    break
                }
            }
            if (!sent) return
            conv.outbox.removeAt(0)
            item.envelope.fill(0)
            // A long message is several envelopes; it is Sent when the last of them has been accepted.
            if (item.msgId != PROFILE_MSG_ID && conv.outbox.none { it.msgId == item.msgId }) {
                conv.messages.firstOrNull { it.id == item.msgId }?.delivery = Conversation.DELIVERY_SENT
                if (conv.localId == currentId) messages.firstOrNull { it.id == item.msgId }?.delivery = Delivery.Sent
            }
            persist()
        }
    }

    private suspend fun pollEndpoint(conv: Conversation, state: AppState, endpoint: String): Probe {
        val client = clientFor(endpoint)
        var after = conv.lastIds[endpoint] ?: 0L
        var probe = Probe.Down
        repeat(MAX_PAGES) {
            val reply = client.fetch(conv.room, conv.memberToken, after)
            if (app !== state) return probe
            when (reply) {
                is Reply.Ok -> {
                    probe = Probe.Reached
                    val page = reply.value
                    var top = after
                    for (m in page.messages) {
                        if (m.id <= after) continue
                        top = maxOf(top, m.id)
                        receive(conv, m.env)
                    }
                    if (top > after) {
                        conv.lastIds[endpoint] = top
                        // Save the advanced ratchet first; only then let the relay forget the messages.
                        if (!persist()) return probe
                        client.ack(conv.room, conv.memberToken, top)
                        after = top
                    }
                    if (!page.more) return probe
                }
                is Reply.Rejected -> return if (reply.status == 404 || reply.status == 401 || reply.status == 410) Probe.Gone else Probe.Reached
                Reply.Unreachable -> return probe
            }
        }
        return probe
    }

    /**
     * Decrypts one envelope. Anything that does not verify (forged, replayed, damaged, duplicate) is dropped.
     * There is no length check on what arrives: the sender enforces it, and a receiver that counted
     * differently would lose a valid message for good (see [Wire.decodeText]).
     */
    private fun receive(conv: Conversation, envelopeText: String) {
        val envelope = Wire.fromBase64(envelopeText) ?: return
        val frame = conv.ratchet.decrypt(envelope) ?: return
        val decoded = Wire.decodeFrame(frame)
        frame.fill(0)
        val text = when (decoded) {
            // A whole message, or one piece of a longer one (shown once the last piece arrives).
            is Wire.Decoded.Text -> decoded.text
            is Wire.Decoded.Fragment -> conv.reassembler.accept(decoded)
            is Wire.Decoded.ProfileUpdate -> {
                // How they appear: cleaned the same way as anything typed here, then shown. Not a message.
                conv.themShared = Profile.of(decoded.name, decoded.avatar, decoded.color)
                entries[conv.localId]?.let { syncEntry(it, conv) }
                null
            }
            null -> null
        } ?: return
        addMessage(conv, StoredMessage(conv.nextMsgId++, text, false, Conversation.DELIVERY_NONE))
        if (conv.localId != currentId) {
            conv.unread = (conv.unread + 1).coerceAtMost(UNREAD_CAP)
            entries[conv.localId]?.let { syncEntry(it, conv) }
        }
    }

    /** Encrypts every chat and writes it. False if locked or the write failed. */
    private suspend fun persist(): Boolean {
        // A copy, so a lock that overwrites the key mid-write cannot make the file unreadable.
        val key = dek?.copyOf() ?: return false
        val state = app ?: return false
        val plain = StateCodec.encode(state)
        return try {
            withContext(Dispatchers.IO) { vault.save(key, plain) }
            true
        } catch (e: Exception) {
            false
        } finally {
            key.fill(0)
            plain.fill(0)
        }
    }

    private fun now(): Long = System.nanoTime() / 1_000_000L

    override fun onCleared() {
        stopPolling()
        app?.wipe()
        dek?.fill(0)
        super.onCleared()
    }

    private companion object {
        /** Frames waiting to go out. Ten full messages are about 350; the relay keeps 1,000 per person. */
        const val MAX_OUTBOX_FRAMES = 1500

        /** The outbox id of frames that are not chat messages (profile updates). */
        const val PROFILE_MSG_ID = -1L
        const val UNREAD_CAP = 999
        const val PREFS = "tenchar_settings"
        const val KEY_RELAY = "relay_url"
        const val KEY_FALLBACK = "fallback_url"
        const val KEY_FALLBACK_ON = "fallback_enabled"
        const val KEY_PUSH_SERVER = "push_server"
        const val KEY_AUTOLOCK = "auto_lock"
        const val KEY_SETUP_DONE = "setup_done"
        const val POLL_MS = 3_000L
        const val BACKOFF_MS = 10_000L

        /** Chats that are not open are checked this often. */
        const val BACKGROUND_CHAT_POLL_MS = 10_000L
        const val GONE_RETRY_MS = 10 * 60_000L
        const val JOIN_TIMEOUT_MS = 120_000L
        const val MAX_PAGES = 20
    }
}