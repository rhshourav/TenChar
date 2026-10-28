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
import com.rhshourav.tenchar.crypto.Wire
import com.rhshourav.tenchar.net.FailReason
import com.rhshourav.tenchar.net.PairOutcome
import com.rhshourav.tenchar.net.PairingFlow
import com.rhshourav.tenchar.net.RelayClient
import com.rhshourav.tenchar.net.Reply
import com.rhshourav.tenchar.store.Authenticator
import com.rhshourav.tenchar.store.Conversation
import com.rhshourav.tenchar.store.KeysLostException
import com.rhshourav.tenchar.store.OutboxItem
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
import java.security.SecureRandom
import javax.crypto.Cipher

enum class Screen { Setup, Gate, Pair, EnterCode, Waiting, Verify, Chat, Settings, About, KeysReset }

enum class AutoLock(val millis: Long) {
    Immediately(0L),
    Seconds30(Constants.AUTO_LOCK_SHORT_MS),
    Minutes5(Constants.AUTO_LOCK_LONG_MS),
}

enum class Delivery { Queued, Sent }

enum class Notice { Mismatch, Forgotten, Timeout, Unreachable, Tampered, Full, Storage }

/** How the relays look from here. Gone means every relay says the conversation no longer exists. */
enum class Connection { Online, Offline, Gone }

class ChatMessage(val id: Long, val text: String, val mine: Boolean, initial: Delivery?) {
    var delivery by mutableStateOf(initial)
}

/**
 * The one place that holds state. Secrets live in [conversation] and [dek] only
 * while unlocked; [lock] overwrites them. Everything on disk goes through [Vault].
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
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

    /** The main relay. Not a secret, but it is metadata, so it stays private to the app. */
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
    var connection by mutableStateOf(Connection.Online)
        private set
    var verification by mutableStateOf<String?>(null)
        private set

    val messages = mutableStateListOf<ChatMessage>()

    private val prefs by lazy {
        getApplication<Application>().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
    private val vault = Vault(application)
    private val random = SecureRandom()
    private val gate = Mutex()
    private val clients = HashMap<String, RelayClient>()
    private val retryAt = HashMap<String, Long>()

    private var dek: ByteArray? = null
    private var conversation: Conversation? = null
    private var foreground = false
    private var pairingJob: Job? = null
    private var flow: PairingFlow? = null
    private var pollJob: Job? = null
    private var goneRounds = 0
    private val wake = Channel<Unit>(Channel.CONFLATED)

    val setupDone: Boolean get() = prefs.getBoolean(KEY_SETUP_DONE, false)

    init {
        // Checked again on load, in case a stored value was edited.
        prefs.getString(KEY_RELAY, null)?.let { RelayAddress.normalise(it) }?.let { relayUrl = it }
        fallbackEnabled = prefs.getBoolean(KEY_FALLBACK_ON, true)
        prefs.getString(KEY_FALLBACK, null)?.let { RelayAddress.normalise(it) }?.let { fallbackUrl = it }
        AutoLock.entries.firstOrNull { it.name == prefs.getString(KEY_AUTOLOCK, null) }?.let { autoLock = it }
        screen = if (setupDone) Screen.Gate else Screen.Setup
    }

    // ---- settings --------------------------------------------------------------------------------

    /** Saves the main relay. Returns false, changing nothing, if the address is not acceptable. */
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

    fun setFallbackEnabled(value: Boolean) {
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

    // ---- lock and unlock ---------------------------------------------------------------------------

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
            keysLost()
            return
        } catch (e: Exception) {
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
            keysLost()
            return
        } catch (e: Exception) {
            unlocking = false
            unlockFailed = true
            return
        }
        viewModelScope.launch {
            try {
                val plain = withContext(Dispatchers.IO) { vault.load(key) }
                val conv = plain?.let { bytes ->
                    try {
                        StateCodec.decode(bytes, random)
                    } finally {
                        bytes.fill(0)
                    }
                }
                dek = key
                conversation = conv
                messages.clear()
                conv?.messages?.forEach { messages.add(it.toUi()) }
                verification = conv?.verification
                goneRounds = 0
                connection = Connection.Online
                retryAt.clear()
                goto(
                    when {
                        conv == null -> Screen.Pair
                        !conv.verified -> Screen.Verify
                        else -> Screen.Chat
                    },
                )
            } catch (e: KeysLostException) {
                key.fill(0)
                keysLost()
            } catch (e: IOException) {
                key.fill(0)
                keysLost()
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
        conversation?.wipe()
        conversation = null
        dek?.fill(0)
        dek = null
        messages.clear()
        verification = null
        code = null
        entry = ""
        draft = ""
        notice = null
        goto(Screen.Gate)
    }

    /** The Keystore key is gone (screen lock removed or changed). Nothing can be recovered, by design. */
    private fun keysLost() {
        cancelPairing()
        stopPolling()
        conversation?.wipe()
        conversation = null
        dek?.fill(0)
        dek = null
        messages.clear()
        verification = null
        vault.wipe()
        goto(Screen.KeysReset)
    }

    /** After "Keys were reset": go to the gate, where a new vault is made, then pairing starts. */
    fun pairAgain() {
        notice = null
        goto(Screen.Gate)
    }

    fun setForeground(value: Boolean) {
        foreground = value
        updatePolling()
    }

    // ---- navigation --------------------------------------------------------------------------------

    private fun goto(target: Screen) {
        screen = target
        updatePolling()
    }

    fun back() {
        when (screen) {
            Screen.Settings -> goto(Screen.Chat)
            Screen.About -> goto(Screen.Settings)
            Screen.EnterCode -> goto(Screen.Pair)
            Screen.Waiting -> cancelWaiting()
            else -> Unit
        }
    }

    fun open(target: Screen) = goto(target)

    // ---- pairing ---------------------------------------------------------------------------------

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

    fun onEntry(input: String) {
        entry = Pairing.formatPartial(input)
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
        val relays = endpoints()
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
        )
        val saved = gate.withLock {
            conversation = conv
            persist()
        }
        if (!saved) {
            gate.withLock { conversation = null }
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
        val conv = conversation ?: return
        conv.verified = true
        goto(Screen.Chat)
        viewModelScope.launch { gate.withLock { persist() } }
    }

    fun verifyMismatch() = wipeConversation(Notice.Mismatch)

    fun forgetConversation() = wipeConversation(Notice.Forgotten)

    /** Deletes the conversation here and, best effort, its rooms on the relays. */
    private fun wipeConversation(reason: Notice?) {
        val conv = conversation
        stopPolling()
        if (conv != null) {
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
        }
        conv?.wipe()
        conversation = null
        messages.clear()
        verification = null
        code = null
        entry = ""
        draft = ""
        notice = reason
        goto(Screen.Pair)
        viewModelScope.launch { gate.withLock { persist() } }
    }

    // ---- messaging -----------------------------------------------------------------------------------

    fun onDraft(input: String) {
        draft = MessageLimits.clamp(input)
    }

    fun send() {
        val text = draft.trim()
        val conv = conversation ?: return
        if (!conv.verified || text.isEmpty() || !MessageLimits.fits(text)) return
        draft = ""
        viewModelScope.launch {
            gate.withLock {
                if (conversation !== conv) return@withLock
                val frame = Wire.encodeText(text) ?: return@withLock
                val envelope = try {
                    conv.ratchet.encrypt(frame)
                } catch (e: IllegalStateException) {
                    return@withLock
                } finally {
                    frame.fill(0)
                }
                val id = conv.nextMsgId++
                addMessage(conv, StoredMessage(id, text, true, Conversation.DELIVERY_QUEUED))
                conv.outbox.add(OutboxItem(id, envelope))
                // The ratchet has moved on: save before anything is sent.
                persist()
            }
            wake.trySend(Unit)
        }
    }

    private fun addMessage(conv: Conversation, message: StoredMessage) {
        conv.messages.add(message)
        messages.add(message.toUi())
        while (conv.messages.size > Conversation.MAX_MESSAGES) {
            conv.messages.removeAt(0)
            if (messages.isNotEmpty()) messages.removeAt(0)
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

    private fun updatePolling() {
        val should = foreground && dek != null && conversation?.verified == true &&
            (screen == Screen.Chat || screen == Screen.Settings || screen == Screen.About)
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

    /** One round: send whatever is queued, then collect whatever is waiting. True if a relay answered. */
    private suspend fun cycle(): Boolean {
        val conv = conversation ?: return true
        return gate.withLock {
            if (conversation !== conv) return@withLock true
            flushOutbox(conv)
            var reached = 0
            var gone = 0
            for (ep in conv.endpoints) {
                if (conversation !== conv) return@withLock true
                if (inBackoff(ep)) {
                    gone++
                    continue
                }
                when (pollEndpoint(conv, ep)) {
                    Probe.Reached -> {
                        reached++
                        retryAt.remove(ep)
                    }
                    Probe.Gone -> {
                        gone++
                        retryAt[ep] = now() + GONE_RETRY_MS
                    }
                    Probe.Down -> Unit
                }
            }
            when {
                reached > 0 -> {
                    goneRounds = 0
                    connection = Connection.Online
                }
                gone == conv.endpoints.size -> {
                    goneRounds++
                    if (goneRounds >= 3) connection = Connection.Gone
                }
                else -> connection = Connection.Offline
            }
            reached > 0
        }
    }

    private fun inBackoff(endpoint: String): Boolean = (retryAt[endpoint] ?: 0L) > now()

    private suspend fun flushOutbox(conv: Conversation) {
        while (conv.outbox.isNotEmpty()) {
            val item = conv.outbox.first()
            val text = Wire.toBase64(item.envelope)
            var sent = false
            for (ep in conv.endpoints) {
                if (inBackoff(ep)) continue
                val reply = clientFor(ep).post(conv.room, conv.memberToken, text)
                if (conversation !== conv) return
                if (reply is Reply.Ok) {
                    sent = true
                    break
                }
            }
            if (!sent) return
            conv.outbox.removeAt(0)
            item.envelope.fill(0)
            conv.messages.firstOrNull { it.id == item.msgId }?.delivery = Conversation.DELIVERY_SENT
            messages.firstOrNull { it.id == item.msgId }?.delivery = Delivery.Sent
            persist()
        }
    }

    private suspend fun pollEndpoint(conv: Conversation, endpoint: String): Probe {
        val client = clientFor(endpoint)
        var after = conv.lastIds[endpoint] ?: 0L
        var probe = Probe.Down
        repeat(MAX_PAGES) {
            val reply = client.fetch(conv.room, conv.memberToken, after)
            if (conversation !== conv) return probe
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

    /** Decrypts one envelope. Anything that does not verify (forged, replayed, damaged, duplicate) is dropped. */
    private fun receive(conv: Conversation, envelopeText: String) {
        val envelope = Wire.fromBase64(envelopeText) ?: return
        val frame = conv.ratchet.decrypt(envelope) ?: return
        val text = Wire.decodeText(frame)
        frame.fill(0)
        if (text == null) return
        addMessage(conv, StoredMessage(conv.nextMsgId++, text, false, Conversation.DELIVERY_NONE))
    }

    /** Encrypts the current conversation and writes it. False if locked or the write failed. */
    private suspend fun persist(): Boolean {
        // A copy, so a lock that overwrites the key mid-write cannot make the file unreadable.
        val key = dek?.copyOf() ?: return false
        val plain = StateCodec.encode(conversation)
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
        conversation?.wipe()
        dek?.fill(0)
        super.onCleared()
    }

    private companion object {
        const val PREFS = "tenchar_settings"
        const val KEY_RELAY = "relay_url"
        const val KEY_FALLBACK = "fallback_url"
        const val KEY_FALLBACK_ON = "fallback_enabled"
        const val KEY_AUTOLOCK = "auto_lock"
        const val KEY_SETUP_DONE = "setup_done"
        const val POLL_MS = 3_000L
        const val BACKOFF_MS = 10_000L
        const val GONE_RETRY_MS = 10 * 60_000L
        const val JOIN_TIMEOUT_MS = 120_000L
        const val MAX_PAGES = 20
    }
}
