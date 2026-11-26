package com.oshi.messenger.service.lora

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Text from a MeshCore USER (not OSHI): a direct message ([channelKey] null) or a channel message.
 * [conversationKey] is `meshcore!<pubkeyhex>` for a known contact, `meshcore!<prefixhex>` otherwise
 * ([MeshCoreThread]); for a channel message it is the channel key and [senderName] is the unauthenticated
 * name the sender's app put in front of the text.
 */
data class MeshCoreInboundText(
    val conversationKey: String,
    val channelKey: String?,
    val senderName: String?,
    val text: String,
    val isPublicChannel: Boolean,
)

data class MeshCoreSendOutcome(val messageId: String, val state: MeshCoreSession.SendState)

/**
 * What the chat layer ([com.oshi.messenger.data.repository.MeshCoreChats]) needs from a MeshCore radio. The
 * BLE manager implements it through its [MeshCoreLink]; tests drive a link over a fake transport.
 */
interface MeshCoreChatRadio {
    val isChatReady: Boolean
    val contacts: StateFlow<List<MeshCore.Contact>>
    fun channelSlotFor(channelKey: String): Int?
    fun sendDirectText(messageId: String, conversationKey: String, text: String): Boolean
    fun sendChannelText(messageId: String, channelKey: String, text: String): Boolean
}

/**
 * The companion conversation with one MeshCore radio, minus the radio link itself: [MeshCoreSession] (pure
 * protocol) plus the one-request-at-a-time pump, the reply watchdog, the clock that drives retries and the
 * 1.5 s text pacing, and the flows the app reads. [MeshCoreManager] feeds it BLE notifications and gives it a
 * GATT write; a JVM test feeds it captured frames and records what it writes. Nothing here touches android.*.
 *
 * [write] returns true when the bytes reached the radio (a GATT write with response, a socket write).
 */
class MeshCoreLink(
    private val scope: CoroutineScope,
    private val write: suspend (ByteArray) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    appName: String = "OSHI",
) : MeshCoreChatRadio {

    val session = MeshCoreSession(appName)

    /** OMP frames heard on "#oshi-bridge" (OSHI over MeshCore); the manager hands them to the envelope pipeline. */
    var onOmpFrame: ((MeshCoreSession.Event.Frame) -> Unit)? = null
    /** "#oshi-bridge" is usable: OSHI messages can ride this radio. */
    var onBridgeReady: (() -> Unit)? = null
    /** Every slot read, so MeshCore users can be written to (first time on this link). */
    var onChatReady: (() -> Unit)? = null
    var onNoFreeChannel: (() -> Unit)? = null

    private val _chatReady = MutableStateFlow(false)
    val chatReady: StateFlow<Boolean> = _chatReady.asStateFlow()

    private val _contacts = MutableStateFlow<List<MeshCore.Contact>>(emptyList())
    override val contacts: StateFlow<List<MeshCore.Contact>> = _contacts.asStateFlow()

    /** The owner's channels (named slots), "#oshi-bridge" left out. */
    private val _channels = MutableStateFlow<List<MeshCore.ChannelInfo>>(emptyList())
    val channels: StateFlow<List<MeshCore.ChannelInfo>> = _channels.asStateFlow()

    private val _channelIdx = MutableStateFlow<Int?>(null)
    val channelIdx: StateFlow<Int?> = _channelIdx.asStateFlow()

    /** The name MeshCore users see for this radio (its advert name, also the prefix of our channel messages). */
    private val _selfName = MutableStateFlow<String?>(null)
    val selfName: StateFlow<String?> = _selfName.asStateFlow()

    /** Public-key hex → phone clock (ms) of the last advert heard this session. */
    private val _heardAt = MutableStateFlow<Map<String, Long>>(emptyMap())
    val heardAt: StateFlow<Map<String, Long>> = _heardAt.asStateFlow()

    /** Messages queued for the radio that it has not taken yet: their bubble shows a clock, not a tick. */
    private val _pendingTexts = MutableStateFlow<Set<String>>(emptySet())
    val pendingTexts: StateFlow<Set<String>> = _pendingTexts.asStateFlow()

    private val _inboundText = MutableSharedFlow<MeshCoreInboundText>(extraBufferCapacity = 64)
    val inboundText: SharedFlow<MeshCoreInboundText> = _inboundText.asSharedFlow()

    private val _sendOutcomes = MutableSharedFlow<MeshCoreSendOutcome>(extraBufferCapacity = 64)
    val sendOutcomes: SharedFlow<MeshCoreSendOutcome> = _sendOutcomes.asSharedFlow()

    /** A channel join answered by the radio: slot → accepted. */
    private val _joinResults = MutableSharedFlow<Pair<Int, Boolean>>(extraBufferCapacity = 8)
    val joinResults: SharedFlow<Pair<Int, Boolean>> = _joinResults.asSharedFlow()

    /** The radio answered "announce me": true = sent. */
    private val _advertResults = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    val advertResults: SharedFlow<Boolean> = _advertResults.asSharedFlow()

    /** Companion requests written so far / answered so far — the reply watchdog compares the two. */
    @Volatile private var requestsWritten = 0L
    @Volatile private var repliesSeen = 0L
    @Volatile private var up = false
    private var tickJob: Job? = null

    val isUp: Boolean get() = up

    /** The link to the radio is open (notifications on / socket connected): introduce ourselves. */
    fun linkUp() {
        up = true
        session.start()
        startTicking()
        pump()
    }

    /** The link went away: whatever waited on it fails now (FAILED on the next tick), not never. */
    fun linkDown() {
        up = false
        tickJob?.cancel(); tickJob = null
        session.reset()
        _chatReady.value = false
        _channelIdx.value = null
        _selfName.value = null
        handleEvents(session.onTick(now()))
    }

    /** One frame from the radio. */
    fun onFrame(f: ByteArray) {
        if (f.isNotEmpty() && (f[0].toInt() and 0xFF) < 0x80) repliesSeen = requestsWritten
        handleEvents(session.onFrame(f, now()))
        pump()
    }

    /** Queue a raw companion command (the OMP datagram pump uses this). */
    fun enqueue(cmd: ByteArray) {
        session.enqueue(cmd)
        pump()
    }

    // ─────────────────────────── Request pump ───────────────────────────

    /** Write the next companion request if none is waiting for its reply. */
    @Synchronized
    fun pump() {
        if (!up) return
        val cmd = session.nextCommand(now()) ?: return
        val n = ++requestsWritten
        scope.launch {
            if (!write(cmd)) {
                handleEvents(session.writeFailed())
                if (up) pump()
                return@launch
            }
            delay(REPLY_TIMEOUT_MS)
            if (repliesSeen < n && requestsWritten == n && up) {
                handleEvents(session.writeFailed())
                pump()
            }
        }
    }

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (true) {
                delay(TICK_MS)
                handleEvents(session.onTick(now()))
                pump()
            }
        }
    }

    private fun handleEvents(events: List<MeshCoreSession.Event>) {
        for (e in events) {
            when (e) {
                is MeshCoreSession.Event.Ready -> onBridgeReady?.invoke()
                is MeshCoreSession.Event.NoFreeChannel -> onNoFreeChannel?.invoke()
                is MeshCoreSession.Event.Frame -> onOmpFrame?.invoke(e)
                is MeshCoreSession.Event.ChatReady -> {
                    _chatReady.value = true
                    onChatReady?.invoke()
                }
                is MeshCoreSession.Event.ContactsChanged -> _contacts.value = session.contacts
                is MeshCoreSession.Event.ChannelsChanged -> _channels.value = session.userChannels
                is MeshCoreSession.Event.ContactHeard ->
                    _heardAt.value = _heardAt.value + (e.publicKeyHex to now())
                is MeshCoreSession.Event.DirectText -> {
                    val key = MeshCoreThread.contactKeyFor(e.senderPrefix, e.contact)
                    _inboundText.tryEmit(MeshCoreInboundText(key, null, e.contact?.name, e.text, isPublicChannel = false))
                }
                is MeshCoreSession.Event.ChannelText -> {
                    val ch = e.channel ?: continue   // a slot we have not read cannot be named or keyed
                    _inboundText.tryEmit(
                        MeshCoreInboundText(
                            MeshCoreThread.channelKey(ch.name), MeshCoreThread.channelKey(ch.name), e.senderName, e.text,
                            isPublicChannel = MeshCore.isPublicChannel(ch.name, ch.secret),
                        )
                    )
                }
                is MeshCoreSession.Event.Send -> _sendOutcomes.tryEmit(MeshCoreSendOutcome(e.messageId, e.state))
                is MeshCoreSession.Event.ChannelJoin -> {
                    _channels.value = session.userChannels
                    _joinResults.tryEmit(e.idx to e.ok)
                }
                is MeshCoreSession.Event.AdvertSent -> _advertResults.tryEmit(e.ok)
            }
        }
        _channelIdx.value = session.channelIdx
        _selfName.value = session.selfName
        _pendingTexts.value = session.pendingTextIds
    }

    // ─────────────────────────── MeshCore users ───────────────────────────

    override val isChatReady: Boolean get() = up && session.chatReady

    override fun channelSlotFor(channelKey: String): Int? = MeshCoreThread.channelIndexFor(channelKey, session.userChannels)

    /**
     * A direct message to the MeshCore user [conversationKey] (`meshcore!<hex>`). True when queued; the outcome
     * then arrives on [sendOutcomes] (SENT, then DELIVERED on SEND_CONFIRMED, or FAILED after the 4th attempt).
     */
    override fun sendDirectText(messageId: String, conversationKey: String, text: String): Boolean {
        val prefix = MeshCoreThread.publicKeyPrefix(conversationKey) ?: return false
        if (!isChatReady) return false
        val ok = session.sendDirect(messageId, prefix, text, now())
        afterQueue()
        return ok
    }

    /** Text on a channel. True when queued; [sendOutcomes] then says SENT (a channel is never acknowledged). */
    override fun sendChannelText(messageId: String, channelKey: String, text: String): Boolean {
        if (!isChatReady) return false
        val idx = channelSlotFor(channelKey) ?: return false
        val ok = session.sendChannel(messageId, idx, text, now())
        afterQueue()
        return ok
    }

    private fun afterQueue() {
        _pendingTexts.value = session.pendingTextIds
        pump()
    }

    enum class JoinResult { QUEUED, NOT_READY, NO_FREE_SLOT, INVALID }

    /** Join a hashtag channel: its secret is SHA-256("#name")[0:16], so anyone who knows the name can read it. */
    fun joinHashtagChannel(name: String): JoinResult {
        val n = name.trim().removePrefix("#")
        if (n.isEmpty() || n.contains(' ')) return JoinResult.INVALID
        val full = "#$n"
        return join(full, MeshCore.hashtagSecret(full))
    }

    /** Join a private channel shared as a name and a 16-byte secret. */
    fun joinPrivateChannel(name: String, secret: ByteArray): JoinResult {
        if (name.isBlank() || secret.size != 16) return JoinResult.INVALID
        return join(name.trim(), secret)
    }

    private fun join(name: String, secret: ByteArray): JoinResult {
        if (!isChatReady) return JoinResult.NOT_READY
        if (name.toByteArray(Charsets.UTF_8).size > 31 || name == OshiBridgeWire.CHANNEL_NAME) return JoinResult.INVALID
        // Already there (same name and secret): nothing to write.
        if (session.userChannels.any { it.name == name && it.secret.contentEquals(secret) }) return JoinResult.QUEUED
        val slot = session.freeChannelSlot() ?: return JoinResult.NO_FREE_SLOT
        if (!session.joinChannel(slot, name, secret)) return JoinResult.INVALID
        pump()
        return JoinResult.QUEUED
    }

    fun refreshContacts() {
        session.refreshContacts()
        pump()
    }

    /** Announce this radio so MeshCore users can add it and write to it. False when not connected. */
    fun sendSelfAdvert(): Boolean {
        if (!isChatReady) return false
        val ok = session.sendSelfAdvert()
        pump()
        return ok
    }

    companion object {
        /** A request the radio never answers must not wedge the one-at-a-time queue for ever. */
        const val REPLY_TIMEOUT_MS = 10_000L
        /** Retries, ACK deadlines and the 1.5 s gap between text parts are checked this often. */
        const val TICK_MS = 250L
    }
}
