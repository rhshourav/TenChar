package com.rhshourav.tenchar.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.hardware.biometrics.BiometricManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.rhshourav.tenchar.crypto.BlobCrypto
import com.rhshourav.tenchar.crypto.Handshake
import com.rhshourav.tenchar.crypto.Kdf
import com.rhshourav.tenchar.crypto.LocalMember
import com.rhshourav.tenchar.crypto.Ratchet
import com.rhshourav.tenchar.crypto.SystemRandom
import com.rhshourav.tenchar.crypto.Wire
import com.rhshourav.tenchar.media.ImageCompressor
import com.rhshourav.tenchar.media.MediaLoader
import com.rhshourav.tenchar.media.TempFiles
import com.rhshourav.tenchar.media.VideoTranscoder
import com.rhshourav.tenchar.media.VoicePlayer
import com.rhshourav.tenchar.media.VoiceRecorder
import com.rhshourav.tenchar.net.FailReason
import com.rhshourav.tenchar.net.PairOutcome
import com.rhshourav.tenchar.net.PairingFlow
import com.rhshourav.tenchar.net.RelayClient
import com.rhshourav.tenchar.net.Reply
import com.rhshourav.tenchar.notify.Alerts
import com.rhshourav.tenchar.notify.WatchStore
import com.rhshourav.tenchar.store.AppState
import com.rhshourav.tenchar.store.Attachment
import com.rhshourav.tenchar.store.Authenticator
import com.rhshourav.tenchar.store.Conversation
import com.rhshourav.tenchar.store.KeysLostException
import com.rhshourav.tenchar.store.KeysLostReason
import com.rhshourav.tenchar.store.MediaStore
import com.rhshourav.tenchar.store.OutboxItem
import com.rhshourav.tenchar.store.Profile
import com.rhshourav.tenchar.store.Quotes
import com.rhshourav.tenchar.store.ReplyRef
import com.rhshourav.tenchar.store.StateCodec
import com.rhshourav.tenchar.store.StoredMessage
import com.rhshourav.tenchar.store.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
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

/** How often the alert service asks the relays whether a message is waiting. */
enum class AlertInterval(val seconds: Int) {
    Seconds30(Constants.ALERT_INTERVAL_SHORT_S),
    Minute1(Constants.ALERT_INTERVAL_MEDIUM_S),
    Minutes5(Constants.ALERT_INTERVAL_LONG_S),
}

/** What Android says about this phone's biometric sensor, as far as unlocking the vault key is concerned. */
enum class BiometricState {
    /** A strong sensor with a finger enrolled: the fingerprint can open the key. */
    Strong,

    /** Rated "weak" (common for some optical under-display sensors): Android does not let it open a hardware key. */
    WeakOnly,
    NoneEnrolled,
    Unavailable,
}

/** The message a reply answers, as shown above the reply: [targetId] is null if it is no longer in the chat. */
class QuoteUi(val targetId: Long?, val mine: Boolean, val text: String, val kind: Int)

/** Why a photo, voice message or video was not sent. */
enum class MediaNotice { TooLong, TooBig, TooShort, Unsupported, Failed, MicBusy, MicDenied, RelayOutdated }

/** What the screens know about an attachment. The key and the content stay in the store. [state] is an [Attachment] state. */
class AttachmentUi(
    val id: String,
    val kind: Int,
    val width: Int,
    val height: Int,
    val durationDs: Int,
    val wave: ByteArray,
    initialState: Int,
) {
    var state by mutableStateOf(initialState)
}

class ChatMessage(
    val id: Long,
    val text: String,
    val mine: Boolean,
    initial: Delivery?,
    /** When it was sent or received, milliseconds since 1970; 0 if unknown (saved by an older version). */
    val at: Long = 0L,
    val attachment: AttachmentUi? = null,
    /** Set if this message answers an earlier one. */
    val quote: QuoteUi? = null,
) {
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

    /** True while this phone is joining with a typed code (as opposed to showing its own). */
    var joining by mutableStateOf(false)
        private set
    var unlocking by mutableStateOf(false)
        private set
    var unlockFailed by mutableStateOf(false)
        private set

    /** What the system said when the prompt failed (for example "7: Too many attempts"), shown to help diagnose it. */
    var unlockDetail by mutableStateOf<String?>(null)
        private set

    /** The message the next text will answer, or null. */
    var replyingTo by mutableStateOf<ChatMessage?>(null)
        private set

    /** Set when the saved data cannot be opened. The files are still on the phone. */
    var lostReason by mutableStateOf<KeysLostReason?>(null)
        private set

    /** What Android actually reported when the key was lost (an exception name, never a secret), shown to help diagnose it. */
    var lostDetail by mutableStateOf<String?>(null)
        private set
    var connection by mutableStateOf(Connection.Online)
        private set
    var verification by mutableStateOf<String?>(null)
        private set

    /** What a new chat starts with as "you". */
    var defaultProfile by mutableStateOf(Profile.EMPTY)
        private set

    /** Message alerts from the built-in background service (see [Alerts]). */
    var alertsOn by mutableStateOf(false)
        private set
    var alertInterval by mutableStateOf(AlertInterval.Seconds30)
        private set

    /** True while a photo or video is being prepared. */
    var mediaBusy by mutableStateOf(false)
        private set
    var mediaNotice by mutableStateOf<MediaNotice?>(null)
        private set

    /** Voice recording in progress: how long, and the loudness of the most recent moments (0 to 1, oldest first). */
    var recording by mutableStateOf(false)
        private set
    var recordMs by mutableIntStateOf(0)
        private set
    var recordLevels by mutableStateOf<List<Float>>(emptyList())
        private set
    val voice = VoicePlayer()

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
    internal val media = MediaStore(application)
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
    private var transferJob: Job? = null
    private var recorder: VoiceRecorder? = null
    private var recordJob: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val transferWake = Channel<Unit>(Channel.CONFLATED)
    private val transferRetryAt = HashMap<String, Long>()

    /** The reference carried by the pieces of a long reply, kept until the last piece arrives. Not saved: see [receive]. */
    private val replyHints = HashMap<String, Wire.Quote>()

    val setupDone: Boolean get() = prefs.getBoolean(KEY_SETUP_DONE, false)

    init {
        // Checked again on load, in case a stored value was edited.
        prefs.getString(KEY_RELAY, null)?.let { RelayAddress.normalise(it) }?.let { relayUrl = it }
        fallbackEnabled = prefs.getBoolean(KEY_FALLBACK_ON, true)
        prefs.getString(KEY_FALLBACK, null)?.let { RelayAddress.normalise(it) }?.let { fallbackUrl = it }
        alertsOn = Alerts.enabled(application)
        val seconds = Alerts.intervalSeconds(application)
        AlertInterval.entries.firstOrNull { it.seconds == seconds }?.let { alertInterval = it }
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
    fun unlock(auth: Authenticator, credentialOnly: Boolean = false) {
        if (unlocking || dek != null) return
        unlocking = true
        unlockFailed = false
        unlockDetail = null
        val create = !vault.exists()
        val legacy = !create && vault.isLegacy()
        val cipher = try {
            when {
                create -> {
                    vault.prepareCreate()
                    null
                }
                legacy -> vault.legacyCipherForUnlock()
                else -> null
            }
        } catch (e: KeysLostException) {
            unlocking = false
            keysLost(e.reason, e.cause)
            return
        } catch (e: Exception) {
            // Temporary: nothing is touched, the person can simply try again.
            unlocking = false
            unlockFailed = true
            unlockDetail = e.javaClass.simpleName
            return
        }
        auth.authenticate(
            cipher,
            credentialOnly,
            onSuccess = { authed -> finishUnlock(authed, create, legacy) },
            onFailure = { cancelled, detail ->
                unlocking = false
                unlockFailed = !cancelled
                unlockDetail = if (cancelled) null else detail
            },
        )
    }

    /**
     * What Android says about the fingerprint sensor. A "weak" sensor cannot open a hardware-protected key (Android
     * only lets strong biometrics do that), so on such a phone the screen lock is the way in.
     */
    fun biometricState(): BiometricState {
        val manager = getApplication<Application>().getSystemService(BiometricManager::class.java) ?: return BiometricState.Unavailable
        val strong = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (strong == BiometricManager.BIOMETRIC_SUCCESS) return BiometricState.Strong
        if (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS) {
            return BiometricState.WeakOnly
        }
        return if (strong == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) BiometricState.NoneEnrolled else BiometricState.Unavailable
    }

    private fun finishUnlock(cipher: Cipher?, create: Boolean, legacy: Boolean) {
        val key = try {
            when {
                create -> vault.completeCreate()
                legacy && cipher != null -> vault.completeLegacyUnlock(cipher).also { vault.migrateFromLegacy(it) }
                else -> vault.completeUnlock()
            }
        } catch (e: KeysLostException) {
            unlocking = false
            keysLost(e.reason, e.cause)
            return
        } catch (e: Exception) {
            unlocking = false
            unlockFailed = true
            unlockDetail = e.javaClass.simpleName // for example UserNotAuthenticatedException: the prompt did not open the key
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
                goneRounds.clear(); connectionOf.clear(); retryAt.clear(); nextDue.clear(); transferRetryAt.clear()
                syncAll()
                goto(if (state.conversations.isEmpty()) Screen.Pair else Screen.Chats)
                if (resave) gate.withLock { persist() }
                tidyMedia(state)
                syncWatches(state)
                warmUpRelays(state.conversations.flatMap { it.endpoints } + endpoints())
            } catch (e: KeysLostException) {
                key.fill(0)
                keysLost(e.reason, e.cause)
            } catch (e: Exception) {
                key.fill(0)
                unlockFailed = true
                unlockDetail = e.javaClass.simpleName
            } finally {
                unlocking = false
            }
        }
    }

    /** Drops everything on screen and in memory and returns to the gate. Pairing in progress is cancelled. */
    fun lock() {
        if (screen == Screen.Setup || screen == Screen.KeysReset || screen == Screen.Gate) return
        cancelPairing()
        cancelRecording()
        stopPolling()
        voice.stop()
        MediaLoader.clear()
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
        replyingTo = null
        replyHints.clear()
        notice = null
        mediaNotice = null
        goto(Screen.Gate)
    }

    /**
     * The saved data cannot be opened and trying again will not help (for example Android removed the key). The files are
     * **not** deleted: the person sees why, can try again, and erases only by choosing to.
     */
    private fun keysLost(reason: KeysLostReason, cause: Throwable? = null) {
        cancelPairing()
        cancelRecording()
        stopPolling()
        voice.stop()
        MediaLoader.clear()
        app?.wipe()
        app = null
        dek?.fill(0)
        dek = null
        chats.clear(); entries.clear(); messages.clear()
        currentId = null
        verification = null
        replyingTo = null
        lostReason = reason
        lostDetail = cause?.javaClass?.simpleName
        goto(Screen.KeysReset)
    }

    /** "Try again" on the keys-reset screen: back to the gate with nothing changed. */
    fun retryUnlock() {
        lostReason = null
        lostDetail = null
        unlockFailed = false
        goto(Screen.Gate)
    }

    /** "Erase and start over": the only way saved data is removed after a failure, and only by the person's choice. */
    fun eraseAndStartOver() {
        vault.wipe()
        viewModelScope.launch(Dispatchers.IO) { media.wipeAll() }
        WatchStore.clear(getApplication())
        Alerts.setEnabled(getApplication(), false)
        Alerts.stopService(getApplication())
        alertsOn = false
        lostReason = null
        lostDetail = null
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
        conv.messages.forEachIndexed { i, m -> messages.add(m.toUi(conv, i)) }
        verification = conv.verification
        connection = connectionOf[id] ?: Connection.Online
        draft = ""
        replyingTo = null
        mediaNotice = null
        goto(if (conv.verified) Screen.Chat else Screen.Verify)
        viewModelScope.launch { gate.withLock { persist() } }
        wake.trySend(Unit)
        transferWake.trySend(Unit)
    }

    fun closeChat() {
        cancelRecording()
        voice.stop()
        replyingTo = null
        currentId = null
        messages.clear()
        verification = null
        draft = ""
        goto(Screen.Chats)
    }

    fun startNewChat() {
        warmUpRelays(endpoints())
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
        val state = app
        viewModelScope.launch {
            gate.withLock {
                queueProfile(conv) // tell them how you appear
                persist()
            }
            wake.trySend(Unit)
            if (state != null) syncWatches(state)
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
        val mediaIds = conv.messages.mapNotNull { it.attachment?.id }
        viewModelScope.launch(Dispatchers.IO) { mediaIds.forEach(media::delete) }
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
            cancelRecording()
            voice.stop()
            replyingTo = null
            currentId = null
            messages.clear()
            verification = null
            draft = ""
        }
        notice = reason
        goto(if (state.conversations.isEmpty()) Screen.Pair else Screen.Chats)
        viewModelScope.launch {
            gate.withLock { persist() }
            syncWatches(state) // the service stops asking about a chat that no longer exists
        }
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
     * Wakes sleeping relays. A free Render instance goes to sleep after 15 minutes without traffic and takes about a
     * minute to wake, so asking early (right after unlocking, when opening the pairing screen) means it is awake by the
     * time a message or a pairing needs it. Best effort, and a few bytes each.
     */
    private fun warmUpRelays(urls: List<String>) {
        for (url in urls.distinct()) {
            viewModelScope.launch {
                try {
                    clientFor(url).ping()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Best effort.
                }
            }
        }
    }

    // ---- message alerts ----------------------------------------------------------------------------------------

    /**
     * Turns message alerts on or off for every chat. They come from a small background service in this app (see
     * [Alerts]): no Google, no push server, no third party. Each chat gets a random watch token whose hash is
     * registered with its relays; the service uses the token to ask how many messages wait, and can do nothing else with it.
     */
    fun setAlerts(on: Boolean) {
        val state = app ?: return
        val context = getApplication<Application>()
        Alerts.setEnabled(context, on)
        alertsOn = on
        viewModelScope.launch {
            if (on) {
                syncWatches(state)
                Alerts.startService(context)
            } else {
                Alerts.stopService(context)
                WatchStore.clear(context)
                for (conv in state.conversations.toList()) {
                    if (conv.watchToken == null) continue
                    for (ep in conv.endpoints) {
                        try {
                            clientFor(ep).registerWatch(conv.room, conv.memberToken, "")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Best effort: with no token on this phone the relay's copy of the hash opens nothing.
                        }
                    }
                }
                if (app !== state) return@launch
                gate.withLock {
                    for (conv in state.conversations) conv.watchToken = null
                    persist()
                }
            }
        }
    }

    fun chooseAlertInterval(value: AlertInterval) {
        alertInterval = value
        Alerts.setIntervalSeconds(getApplication(), value.seconds)
    }

    /**
     * Makes the relays and the alert service agree with the chats: every verified chat has a watch token, its hash is
     * registered with each of its relays (again each time, because a relay that lost its data forgets it), and the
     * service's own small list is rewritten. Does nothing while alerts are off.
     */
    private suspend fun syncWatches(state: AppState) {
        if (!alertsOn) return
        val context = getApplication<Application>()
        gate.withLock {
            var created = false
            for (conv in state.conversations) {
                if (conv.verified && conv.watchToken == null) {
                    conv.watchToken = Hex.encode(ByteArray(32).also(random::nextBytes))
                    created = true
                }
            }
            if (created) persist()
        }
        val list = ArrayList<WatchStore.Watch>()
        for (conv in state.conversations.toList()) {
            val token = conv.watchToken ?: continue
            if (!conv.verified) continue
            val hash = Hex.encode(Kdf.sha256(Hex.decode(token)))
            for (ep in conv.endpoints) {
                try {
                    clientFor(ep).registerWatch(conv.room, conv.memberToken, hash)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Best effort.
                }
            }
            list += WatchStore.Watch(conv.room, token, conv.endpoints, LongArray(conv.endpoints.size))
        }
        if (app !== state) return
        WatchStore.replace(context, list)
        if (list.isNotEmpty()) Alerts.startService(context)
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
        // A reply names the message it answers (a short reference; the quoted text itself is never sent). It carries
        // 4 bytes more than a plain message, so its pieces are cut a little shorter.
        val ref: ReplyRef? = replyingTo?.let { target -> conv.messages.firstOrNull { it.id == target.id }?.let(Quotes::refTo) }
        // More than one message's worth of text becomes several messages, each shown as its own bubble.
        val pieces = MessageLimits.split(text, if (ref != null) Constants.MAX_REPLY_MESSAGE_BYTES else Constants.MAX_MESSAGE_BYTES)
        if (pieces.isEmpty()) return
        // Refuse rather than half-send if the queue could not take every frame (it empties as the relay accepts them).
        if (conv.outbox.size + pieces.sumOf { Wire.frameCount(it) } > MAX_OUTBOX_FRAMES) return
        draft = ""
        replyingTo = null
        viewModelScope.launch {
            gate.withLock {
                if (app !== state) return@withLock
                for ((n, piece) in pieces.withIndex()) {
                    val id = conv.nextMsgId++
                    // Only the first bubble of a long text is the reply.
                    val answers = if (n == 0) ref else null
                    // One frame for a short message, several fragments for a longer one; the id ties them together.
                    val frames = Wire.encodeMessage(piece, id.toInt() and 0xFFFF, answers?.let(Quotes::toWire)) ?: continue
                    val envelopes = try {
                        frames.map { conv.ratchet.encrypt(it) }
                    } catch (e: IllegalStateException) {
                        break
                    } finally {
                        frames.forEach { it.fill(0) }
                    }
                    addMessage(conv, StoredMessage(id, piece, true, Conversation.DELIVERY_QUEUED, System.currentTimeMillis(), null, answers))
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
        if (open) messages.add(message.toUi(conv, conv.messages.lastIndex))
        while (conv.messages.size > Conversation.MAX_MESSAGES) {
            val dropped = conv.messages.removeAt(0)
            if (open && messages.isNotEmpty()) messages.removeAt(0)
            dropped.attachment?.let { att ->
                val id = att.id // before the key is wiped
                att.wipe()
                viewModelScope.launch(Dispatchers.IO) { media.delete(id) }
            }
        }
    }

    private fun StoredMessage.toUi(conv: Conversation, index: Int): ChatMessage = ChatMessage(
        id, text, mine,
        when (delivery) {
            Conversation.DELIVERY_QUEUED -> Delivery.Queued
            Conversation.DELIVERY_SENT -> Delivery.Sent
            else -> null
        },
        at,
        attachment?.let { AttachmentUi(it.id, it.kind, it.width, it.height, it.durationDs, it.wave, it.state) },
        reply?.let { ref ->
            // The answered message is looked up in this phone's own copy of the chat; its text was never sent.
            val target = Quotes.resolve(conv.messages, index, ref)
            if (target == null) QuoteUi(null, ref.quotedMine, "", 0)
            else QuoteUi(target.id, target.mine, target.text, target.attachment?.kind ?: 0)
        },
    )

    // ---- replying to a chosen message --------------------------------------------------------------------------

    /** The next text sent will answer this message. Long-press on a message, then Reply. */
    fun startReply(messageId: Long) {
        val conv = conversation(currentId) ?: return
        if (!conv.verified) return
        replyingTo = messages.firstOrNull { it.id == messageId }
    }

    fun cancelReply() {
        replyingTo = null
    }

    // ---- photos, voice messages and videos ---------------------------------------------------------------------
    //
    // An attachment is encrypted with its own key into the media store (see BlobCrypto). The transfer loop then uploads
    // that file to a relay and only afterwards encrypts a one-frame descriptor, with the key in it, through the ratchet.
    // The other phone gets the descriptor, downloads the file, checks it and tells the relay to delete it.

    fun dismissMediaNotice() {
        mediaNotice = null
    }

    /** The microphone permission was refused. */
    fun micDenied() {
        mediaNotice = MediaNotice.MicDenied
    }

    /** A photo or video chosen in the system picker. Photos are shrunk and stripped of metadata, videos are transcoded. */
    fun attachPicked(uri: Uri) {
        val state = app ?: return
        val conv = conversation(currentId) ?: return
        if (!conv.verified || mediaBusy || recording) return
        val context = getApplication<Application>()
        val isVideo = context.contentResolver.getType(uri)?.startsWith("video/") == true
        mediaBusy = true
        mediaNotice = null
        viewModelScope.launch {
            try {
                if (isVideo) prepareVideo(state, conv, uri) else prepareImage(state, conv, uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mediaNotice = MediaNotice.Failed
            } finally {
                mediaBusy = false
            }
        }
    }

    private suspend fun prepareImage(state: AppState, conv: Conversation, uri: Uri) {
        val context = getApplication<Application>()
        val result = withContext(Dispatchers.Default) { ImageCompressor.compress(context.contentResolver, uri) }
        if (result == null) {
            mediaNotice = MediaNotice.Unsupported
            return
        }
        queueAttachment(state, conv, Constants.KIND_IMAGE, result.jpeg, result.width, result.height, 0, ByteArray(0))
        result.jpeg.fill(0)
    }

    private suspend fun prepareVideo(state: AppState, conv: Conversation, uri: Uri) {
        val context = getApplication<Application>()
        val info = withContext(Dispatchers.IO) { VideoTranscoder.probe(context, uri) }
        if (info == null) {
            mediaNotice = MediaNotice.Unsupported
            return
        }
        if (info.durationMs > Constants.VIDEO_MAX_MS + 500) {
            mediaNotice = MediaNotice.TooLong
            return
        }
        val tmp = VideoTranscoder.tempFile(context)
        try {
            if (!VideoTranscoder.transcode(context, uri, info, tmp)) {
                mediaNotice = MediaNotice.Failed
                return
            }
            val out = withContext(Dispatchers.IO) { VideoTranscoder.probe(context, Uri.fromFile(tmp)) } ?: info
            val bytes = withContext(Dispatchers.IO) { if (tmp.length() in 1..Constants.MAX_MEDIA_BYTES.toLong()) tmp.readBytes() else null }
            if (bytes == null) {
                mediaNotice = MediaNotice.TooBig
                return
            }
            queueAttachment(state, conv, Constants.KIND_VIDEO, bytes, out.width, out.height, (info.durationMs / 100).toInt().coerceIn(1, 0xFFFF), ByteArray(0))
            bytes.fill(0)
        } finally {
            withContext(NonCancellable) { withContext(Dispatchers.IO) { VideoTranscoder.wipe(tmp) } }
        }
    }

    /** Encrypts [plain] into the media store and puts a message with it in the chat. The transfer loop sends it. */
    private suspend fun queueAttachment(
        state: AppState,
        conv: Conversation,
        kind: Int,
        plain: ByteArray,
        width: Int,
        height: Int,
        durationDs: Int,
        wave: ByteArray,
    ) {
        if (plain.isEmpty() || plain.size > Constants.MAX_MEDIA_BYTES) {
            mediaNotice = MediaNotice.TooBig
            return
        }
        val att = Attachment(kind, BlobCrypto.newKey(random), plain.size, width, height, durationDs, wave, Attachment.PENDING)
        withContext(Dispatchers.IO) { media.write(att, plain) }
        val added = gate.withLock {
            if (app !== state) return@withLock false
            addMessage(conv, StoredMessage(conv.nextMsgId++, "", true, Conversation.DELIVERY_QUEUED, System.currentTimeMillis(), att))
            persist()
            true
        }
        if (!added) {
            val id = att.id
            withContext(Dispatchers.IO) { media.delete(id) }
            return
        }
        transferWake.trySend(Unit)
    }

    fun startRecording() {
        val conv = conversation(currentId) ?: return
        if (!conv.verified || recording || mediaBusy) return
        val r = VoiceRecorder(getApplication())
        voice.stop()
        if (!r.start(onLimit = { finishRecording() })) {
            mediaNotice = MediaNotice.MicBusy
            return
        }
        recorder = r
        recording = true
        recordMs = 0
        recordLevels = emptyList()
        mediaNotice = null
        recordJob = viewModelScope.launch {
            while (isActive) {
                val live = recorder ?: break
                live.sample()
                recordMs = live.elapsedMs
                recordLevels = live.recent(RECORD_BARS_SHOWN)
                delay(RECORD_TICK_MS)
            }
        }
    }

    fun cancelRecording() {
        recordJob?.cancel()
        recordJob = null
        recorder?.cancel()
        recorder = null
        recording = false
        recordMs = 0
        recordLevels = emptyList()
    }

    /** Stops the recording and sends it. Also called when the two-minute limit is reached. */
    fun finishRecording() {
        val r = recorder ?: return
        val state = app
        val conv = conversation(currentId)
        val length = r.elapsedMs
        recordJob?.cancel()
        recordJob = null
        recorder = null
        recording = false
        recordMs = 0
        recordLevels = emptyList()
        val rec = r.finish()
        if (rec == null || state == null || conv == null) {
            if (state != null && conv != null) mediaNotice = if (length < Constants.VOICE_MIN_MS) MediaNotice.TooShort else MediaNotice.Failed
            return
        }
        viewModelScope.launch {
            try {
                queueAttachment(state, conv, Constants.KIND_VOICE, rec.audio, 0, 0, (rec.durationMs / 100).coerceIn(1, 0xFFFF), rec.wave)
            } finally {
                rec.audio.fill(0)
            }
        }
    }

    private fun storedAttachment(messageId: Long): Attachment? =
        conversation(currentId)?.messages?.firstOrNull { it.id == messageId }?.attachment

    suspend fun attachmentImage(messageId: Long, maxEdge: Int): Bitmap? =
        storedAttachment(messageId)?.let { MediaLoader.image(media, it, maxEdge) }

    suspend fun attachmentVideoFrame(messageId: Long, width: Int): Bitmap? =
        storedAttachment(messageId)?.let { MediaLoader.videoFrame(media, it, width) }

    /** The decrypted content, for the viewer. The caller wipes it when it is done. */
    suspend fun attachmentBytes(messageId: Long): ByteArray? =
        storedAttachment(messageId)?.let { MediaLoader.bytes(media, it) }

    fun toggleVoice(messageId: Long) {
        val att = storedAttachment(messageId) ?: return
        if (att.state != Attachment.READY) return
        if (voice.playingId == att.id) {
            voice.toggle(att.id, ByteArray(0), viewModelScope) // already loaded: pause or resume
            return
        }
        viewModelScope.launch {
            val audio = MediaLoader.bytes(media, att)
            if (audio == null) {
                mediaNotice = MediaNotice.Failed
                return@launch
            }
            voice.toggle(att.id, audio, viewModelScope)
        }
    }

    /** Tap on an attachment that failed: try the transfer again. */
    fun retryAttachment(messageId: Long) {
        val state = app ?: return
        val conv = conversation(currentId) ?: return
        val att = conv.messages.firstOrNull { it.id == messageId }?.attachment ?: return
        if (att.state != Attachment.FAILED) return
        setAttachmentState(conv, state, messageId, att, Attachment.PENDING)
        transferRetryAt.remove("${conv.localId}:$messageId")
        transferWake.trySend(Unit)
    }

    private fun setAttachmentState(conv: Conversation, state: AppState, messageId: Long, att: Attachment, value: Int) {
        att.state = value
        if (conv.localId == currentId) messages.firstOrNull { it.id == messageId }?.attachment?.state = value
        viewModelScope.launch { gate.withLock { if (app === state) persist() } }
    }

    /** Removes files nobody refers to any more (an interrupted transfer, a chat deleted before the last cleanup). */
    private fun tidyMedia(state: AppState) {
        val keep = state.conversations.flatMap { c -> c.messages.mapNotNull { it.attachment?.id } }.toSet()
        viewModelScope.launch(Dispatchers.IO) {
            media.keepOnly(keep)
            TempFiles.sweep(getApplication<Application>().cacheDir)
        }
    }

    // ---- moving attachments to and from the relays ---------------------------------------------------------------
    // Runs beside the message loop and never inside its lock, so a slow upload cannot hold up text messages.

    private suspend fun transferLoop() {
        while (true) {
            val worked = try {
                transfers()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            withTimeoutOrNull(if (worked) 300L else TRANSFER_IDLE_MS) { transferWake.receive() }
        }
    }

    private suspend fun transfers(): Boolean {
        val state = app ?: return false
        var worked = false
        for (conv in state.conversations.toList()) {
            if (!conv.verified) continue
            val pending = gate.withLock { conv.messages.filter { it.attachment?.state == Attachment.PENDING } }
            for (m in pending) {
                if (app !== state) return worked
                val retryKey = "${conv.localId}:${m.id}"
                if ((transferRetryAt[retryKey] ?: 0L) > now()) continue
                val done = if (m.mine) upload(conv, state, m) else download(conv, state, m)
                if (done) {
                    worked = true
                    transferRetryAt.remove(retryKey)
                } else {
                    transferRetryAt[retryKey] = now() + TRANSFER_RETRY_MS
                }
            }
        }
        return worked
    }

    /** True when this attachment is settled (sent, or given up on); false to try again later. */
    private suspend fun upload(conv: Conversation, state: AppState, m: StoredMessage): Boolean {
        val att = m.attachment ?: return true
        val file = media.file(att.id)
        if (!file.isFile) {
            setAttachmentState(conv, state, m.id, att, Attachment.FAILED)
            return true
        }
        var accepted = false
        var refused = false
        var missing = 0
        for (ep in conv.endpoints) {
            if (inBackoff(conv, ep)) continue
            when (val r = clientFor(ep).putBlob(conv.room, conv.memberToken, att.id, file)) {
                is Reply.Ok -> accepted = true
                is Reply.Rejected -> when (r.status) {
                    // These will never succeed. (429, the room's attachment quota being full, will once the other phone downloads.)
                    413, 400 -> refused = true
                    // Not found: the room is gone, or this relay predates attachments and was never updated.
                    404, 405 -> missing++
                }
                Reply.Unreachable -> Unit
            }
            if (app !== state) return false
            if (accepted) break
        }
        if (!accepted) {
            // Say why, not just "failed": the two causes need different fixes.
            if (refused) {
                setAttachmentState(conv, state, m.id, att, Attachment.FAILED)
                if (conv.localId == currentId) mediaNotice = MediaNotice.TooBig
                return true
            }
            if (missing == conv.endpoints.size) {
                setAttachmentState(conv, state, m.id, att, Attachment.FAILED)
                if (conv.localId == currentId) mediaNotice = MediaNotice.RelayOutdated
                return true
            }
            return false
        }
        gate.withLock {
            if (app !== state || att.state != Attachment.PENDING) return@withLock
            val frame = Wire.encodeAttachment(att.kind, att.key, att.size, att.width, att.height, att.durationDs, att.wave)
            var next = Attachment.FAILED
            if (frame != null) {
                try {
                    conv.outbox.add(OutboxItem(m.id, conv.ratchet.encrypt(frame)))
                    next = Attachment.READY
                } catch (e: IllegalStateException) {
                    // The chain is used up: nothing more can be sent on this chat.
                } finally {
                    frame.fill(0)
                }
            }
            att.state = next
            persist()
        }
        if (conv.localId == currentId) messages.firstOrNull { it.id == m.id }?.attachment?.state = att.state
        wake.trySend(Unit)
        return true
    }

    private suspend fun download(conv: Conversation, state: AppState, m: StoredMessage): Boolean {
        val att = m.attachment ?: return true
        if (media.exists(att.id)) {
            setAttachmentState(conv, state, m.id, att, Attachment.READY)
            return true
        }
        val expected = BlobCrypto.cipherSize(att.size).toLong()
        val part = media.partFile(att.id)
        var from: String? = null
        var missing = 0
        for (ep in conv.endpoints) {
            if (inBackoff(conv, ep)) continue
            when (val r = clientFor(ep).getBlob(conv.room, conv.memberToken, att.id, expected, part)) {
                is Reply.Ok -> from = ep
                is Reply.Rejected -> if (r.status == 404 || r.status == 410) missing++
                Reply.Unreachable -> Unit
            }
            if (app !== state) {
                part.delete()
                return false
            }
            if (from != null) break
        }
        if (from == null) {
            // Every relay says it is not there: it expired, or the relay lost it. Waiting will not bring it back.
            if (missing == conv.endpoints.size) {
                setAttachmentState(conv, state, m.id, att, Attachment.FAILED)
                return true
            }
            return false
        }
        val ok = withContext(Dispatchers.Default) { media.commitDownload(att) }
        if (!ok) {
            setAttachmentState(conv, state, m.id, att, Attachment.FAILED) // not what the sender encrypted
            return true
        }
        setAttachmentState(conv, state, m.id, att, Attachment.READY)
        try {
            clientFor(from).deleteBlob(conv.room, conv.memberToken, att.id) // free the relay's copy
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort: the relay deletes it after seven days anyway.
        }
        return true
    }

    // ---- talking to the relays ------------------------------------------------------------------------------

    private fun updatePolling() {
        val state = app
        val should = foreground && dek != null && state != null && state.conversations.any { it.verified } &&
            screen != Screen.Setup && screen != Screen.Gate && screen != Screen.KeysReset
        if (should) {
            if (pollJob?.isActive != true) pollJob = viewModelScope.launch { pollLoop() }
            if (transferJob?.isActive != true) transferJob = viewModelScope.launch { transferLoop() }
        } else {
            stopPolling()
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        transferJob?.cancel()
        transferJob = null
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
        var reply: Wire.Quote? = null
        val text = when (decoded) {
            // A whole message, or one piece of a longer one (shown once the last piece arrives).
            is Wire.Decoded.Text -> {
                reply = decoded.reply
                decoded.text
            }
            is Wire.Decoded.Fragment -> {
                // Every piece of a long reply carries its reference; keep it until the last piece completes the message.
                // (Not saved with the chat: if the app locks halfway, the text still arrives, just without its quote.)
                val hintKey = "${conv.localId}:${decoded.group}"
                decoded.reply?.let {
                    if (replyHints.size > MAX_REPLY_HINTS) replyHints.clear()
                    replyHints[hintKey] = it
                }
                conv.reassembler.accept(decoded)?.also { reply = replyHints.remove(hintKey) }
            }
            is Wire.Decoded.AttachmentDescriptor -> {
                // A photo, voice message or video waiting on a relay: shown at once, filled in when it has downloaded.
                addMessage(
                    conv,
                    StoredMessage(
                        conv.nextMsgId++, "", false, Conversation.DELIVERY_NONE, System.currentTimeMillis(),
                        Attachment(decoded.kind, decoded.key, decoded.size, decoded.width, decoded.height, decoded.durationDs, decoded.wave, Attachment.PENDING),
                    ),
                )
                if (conv.localId != currentId) {
                    conv.unread = (conv.unread + 1).coerceAtMost(UNREAD_CAP)
                    entries[conv.localId]?.let { syncEntry(it, conv) }
                }
                transferWake.trySend(Unit)
                return
            }
            is Wire.Decoded.ProfileUpdate -> {
                // How they appear: cleaned the same way as anything typed here, then shown. Not a message.
                conv.themShared = Profile.of(decoded.name, decoded.avatar, decoded.color)
                entries[conv.localId]?.let { syncEntry(it, conv) }
                null
            }
            null -> null
        } ?: return
        addMessage(
            conv,
            StoredMessage(conv.nextMsgId++, text, false, Conversation.DELIVERY_NONE, System.currentTimeMillis(), null, reply?.let(Quotes::fromWire)),
        )
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
        cancelRecording()
        voice.stop()
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
        const val PREFS = Alerts.PREFS
        const val KEY_RELAY = "relay_url"
        const val KEY_FALLBACK = "fallback_url"
        const val KEY_FALLBACK_ON = "fallback_enabled"
        const val KEY_AUTOLOCK = "auto_lock"
        const val KEY_SETUP_DONE = "setup_done"
        const val MAX_REPLY_HINTS = 64
        const val RECORD_TICK_MS = 80L
        const val RECORD_BARS_SHOWN = 32
        const val TRANSFER_IDLE_MS = 4_000L
        const val TRANSFER_RETRY_MS = 15_000L
        const val POLL_MS = 3_000L
        const val BACKOFF_MS = 10_000L

        /** Chats that are not open are checked this often. */
        const val BACKGROUND_CHAT_POLL_MS = 10_000L
        const val GONE_RETRY_MS = 10 * 60_000L
        const val JOIN_TIMEOUT_MS = 120_000L
        const val MAX_PAGES = 20
    }
}