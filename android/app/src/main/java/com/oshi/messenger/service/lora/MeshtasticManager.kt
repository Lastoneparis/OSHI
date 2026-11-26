package com.oshi.messenger.service.lora

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import kotlin.random.Random

/**
 * OSHI's LoRa lane: a paired Meshtastic-firmware radio over BLE.
 *
 * PORT OF OSHI/MeshtasticManager.swift (1224 lines), which is the reference
 * implementation and the one that ships. Every wire constant, field number and
 * framing decision below cites the Swift line it came from; where Android forces
 * a genuine difference it is called out as DIVERGENCE with the reason.
 *
 * What it does, unchanged from iOS:
 *  • AUTO-DETECTION — scans for the Meshtastic BLE service UUID, so any
 *    compatible radio nearby turns up with no manual entry (:6-8).
 *  • INTEROP — plain text goes out on TEXT_MESSAGE_APP (portnum 1), so users of
 *    the official Meshtastic app receive it (:9-10).
 *  • OSHI↔OSHI — full encrypted envelopes ride PRIVATE_APP (portnum 256),
 *    chunked to LoRa frame size. The radio and every relaying node only ever see
 *    ciphertext; broadcast reaches everyone and only the intended recipient can
 *    decrypt, so a message finds the right user with no routing table (:11-15).
 *
 * WHAT IT DOES NOT DO: it does not encrypt, decrypt, or store anything. Envelopes
 * arrive on [receivedMessages] and leave through [queueOrSendSecureMessage]; the
 * message pipeline owns both ends.
 */
@SuppressLint("MissingPermission")   // every call site is guarded by `hasPermission`
class MeshtasticManager private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    // ─────────────────────────── Constants ───────────────────────────

    companion object {
        /** Meshtastic 2.x BLE UUIDs. MeshtasticManager.swift:162-166 */
        val SERVICE_UUID: UUID = UUID.fromString("6BA1B218-15A8-461F-9FA8-5DCAE273EAFD")
        val TO_RADIO_UUID: UUID = UUID.fromString("F75C76D2-129E-4DAD-A1DD-7866124401E7")
        val FROM_RADIO_UUID: UUID = UUID.fromString("2C55E69E-4993-11ED-B878-0242AC120002")
        val FROM_NUM_UUID: UUID = UUID.fromString("ED9DA18C-A800-4F66-A670-AA7547E34453")

        /** Client Characteristic Configuration Descriptor — how Android subscribes. */
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        /** :168 */
        val BROADCAST_ADDR: UInt = LoRaPacketParser.BROADCAST_ADDR

        /** :171 — payload bytes per LoRa frame (frame budget ~237). */
        const val LORA_CHUNK = LoRaEnvelope.CHUNK_BYTES

        /** :172 — same key name as iOS, so the two platforms describe the same thing. */
        private const val PAIRED_KEY = "oshi.meshtastic.peripheralId"
        private const val PREFS = "oshi.lora"

        /**
         * How long an unacknowledged send stays interesting, ms. Beyond this the
         * mesh has almost certainly not delivered it: Meshtastic retries for well
         * under a minute, so a later ACK would be for a reused packet id. :573
         */
        const val LORA_ACK_WINDOW_MS = 90_000L

        /** __ANTI_JAMMING_PHASE1_2026_11_13__ How often the jamming state is re-evaluated while connected. */
        const val JAMMING_TICK_MS = 30_000L
        /** Ask our radio for LocalStats when no RX counters arrived for this long (stock sends them every 15 min). */
        const val LOCAL_STATS_POLL_MS = 2 * 60_000L
        /** After an unanswered request, try again this much sooner than a full poll interval. */
        const val LOCAL_STATS_RETRY_MS = 60_000L
        /**
         * Routing.Error values that mean "the air did not carry it": NO_ROUTE 1, TIMEOUT 3, MAX_RETRANSMIT 5.
         * Configuration refusals (NO_CHANNEL, TOO_LARGE, PKI_*, DUTY_CYCLE_LIMIT...) say nothing about jamming.
         */
        val LINK_FAILURE_REASONS = setOf(1L, 3L, 5L)

        /** :819 — LongFast airtime is ~1-2 s/frame and the radio's TX queue is shallow. */
        const val SEND_SPACING_MS = 2_000L

        /**
         * Opt-in switch for carrying media over LoRa (default false). Same key
         * name as iOS (`mediaOverLoRaKey`) so both platforms describe the same
         * thing. Stored in [PREFS]. [Media-over-LoRa parity 2026-09-11]
         *
         * The send-cost estimate the confirmation uses is pure arithmetic and
         * lives in [LoRaEnvelope.mediaEstimate] so it can be unit-tested without
         * the Android radio stack.
         */
        const val MEDIA_OVER_LORA_KEY = "oshi_media_over_mesh_lora"

        /**
         * Opt-in switch for BEACONING THIS PHONE'S POSITION over the mesh
         * (default false). Same key name as iOS so both platforms describe the
         * same thing. Stored in [PREFS]. [LoRa position beacon 2026-10-28]
         *
         * Default false is not timidity: [broadcastPosition] is a public
         * cleartext broadcast on a channel whose key is published, so turning
         * this on tells every radio within tens of kilometres where its owner
         * is. That has to be a decision somebody made on purpose.
         */
        const val POSITION_BEACON_KEY = "oshi_lora_position_beacon"

        /**
         * The channel name THIS PHONE last wrote to a node, stored in [PREFS].
         *
         * Not the node's state: nothing here reads a channel back off the radio,
         * so this is only ever "what we asked for last time", and the screen
         * labels it that way. Kept so the field is not empty every time the user
         * returns to it.
         */
        private const val CHANNEL_NAME_KEY = "oshi.lora.channelName"

        /**
         * DIVERGENCE (Android only). iOS gets ~185 usable bytes per BLE write for
         * free; Android negotiates 23 by default, which would fragment every
         * single frame we produce (a full OSHI frame is 188 bytes) and multiply
         * the write count by eight. 512 is the maximum the stack allows.
         */
        const val REQUESTED_MTU = 512

        /** A GATT operation that has not answered by now is not going to. */
        private const val GATT_TIMEOUT_MS = 8_000L

        /** :666-667 — a node still wearing the name the flasher gave it. */
        private val FACTORY_NAME = Regex("^Meshtastic [0-9a-fA-F]{4}$")

        @Volatile
        private var INSTANCE: MeshtasticManager? = null

        fun getInstance(context: Context): MeshtasticManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MeshtasticManager(context).also {
                    INSTANCE = it
                    it.start()
                }
            }

        /** Null until something has asked for the manager. Never constructs one. */
        val instanceOrNull: MeshtasticManager? get() = INSTANCE

        /**
         * True once a radio has completed its config handshake at least once —
         * i.e. this device can end up sending over LoRa, connected right now or
         * not (store-and-forward queues for it).
         *
         * Read by the text splitter, which has to know which byte budget applies:
         * 12 × 180 on the air, or the far larger relay ceiling otherwise. Reads
         * the same key the pairing code writes, not a second copy. :182-184
         */
        fun isRadioPaired(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PAIRED_KEY, null) != null

        /**
         * __LORA_LONG_TEXT_OMP_2026_11_18__ The paired radio is one that answered the OSHI Mesh probe.
         * Stored as the radio's id (not a bare flag), so pairing a different radio forgets it by
         * construction. Remembered across connections because the byte budget is decided at send
         * time — often while the radio is out of Bluetooth range and its messages are being held.
         * iOS `MeshtasticManager.lastKnownOshiFirmware`.
         */
        private const val OSHI_FW_RADIO_KEY = "oshi.meshtastic.oshiFirmwareRadioId"

        fun lastKnownOshiFirmware(context: Context): Boolean {
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val paired = p.getString(PAIRED_KEY, null) ?: return false
            return p.getString(OSHI_FW_RADIO_KEY, null) == paired
        }

        /**
         * __LORA_AIRTIME_HINT_2026_11_18__ Messages an OSHI Mesh radio has held (QUEUED, not SENT)
         * for over a minute — almost always the regional duty-cycle budget. In the companion so a
         * chat bubble can read it without constructing the radio manager. See [LoRaAirtimeWait].
         */
        private val _awaitingAirtime = MutableStateFlow<Set<String>>(emptySet())
        val awaitingAirtime: StateFlow<Set<String>> = _awaitingAirtime.asStateFlow()

        /**
         * Fold base64url/padding variants so key comparison cannot silently drop
         * a legitimate recipient (same normalization as BlockedContactsManager).
         * :469-474
         */
        fun normalizeKey(k: String): String =
            k.replace("-", "+").replace("_", "/").replace("=", "").trim()

        /** :679-681 — "OSHI Mesh 23D8". Spaces and the product name, not a debug string. */
        fun adoptedName(nodeNum: UInt): String =
            "OSHI Mesh " + (nodeNum and 0xFFFFu).toString(16).uppercase().padStart(4, '0')
    }

    /** :186 */
    enum class ConnState { OFF, SCANNING, CONNECTING, CONFIGURING, READY }

    // ─────────────────────────── Published state ───────────────────────────

    private val _bluetoothOn = MutableStateFlow(false)
    val bluetoothOn: StateFlow<Boolean> = _bluetoothOn.asStateFlow()

    private val _permissionDenied = MutableStateFlow(false)
    val permissionDenied: StateFlow<Boolean> = _permissionDenied.asStateFlow()

    private val _state = MutableStateFlow(ConnState.OFF)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredLoRaNode>>(emptyList())
    val discovered: StateFlow<List<DiscoveredLoRaNode>> = _discovered.asStateFlow()

    private val _connectedName = MutableStateFlow<String?>(null)
    val connectedName: StateFlow<String?> = _connectedName.asStateFlow()

    private val _myNodeNum = MutableStateFlow(0u)
    val myNodeNum: StateFlow<UInt> = _myNodeNum.asStateFlow()

    private val _nodes = MutableStateFlow<List<LoRaMeshNode>>(emptyList())
    val nodes: StateFlow<List<LoRaMeshNode>> = _nodes.asStateFlow()

    private val _traffic = MutableStateFlow<List<LoRaEvent>>(emptyList())
    val traffic: StateFlow<List<LoRaEvent>> = _traffic.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * Let the setup screen name a failure the driver cannot see: a radio that
     * accepts the BLE link and then never answers the handshake leaves the screen
     * spinning for ever, and "still trying" is not an answer
     * (iOS LoRaNodeSetupView.swift:81-89). Passing null clears it.
     */
    fun setLastError(message: String?) {
        _lastError.value = message
        refreshStatus()
    }

    /**
     * The node's regulatory region AS REPORTED BY THE NODE, not as we asked for
     * it. Null until its config has been read — and null is NOT the same as
     * UNSET; see [sendSecureMessage]. :202-204
     */
    private val _region = MutableStateFlow<LoRaRegion?>(null)
    val region: StateFlow<LoRaRegion?> = _region.asStateFlow()

    /**
     * The region code EXACTLY as the node reported it, null until read.
     *
     * DIVERGENCE FROM iOS, deliberate. iOS keeps only the enum and writes
     * `region = found ?? .unset` (MeshtasticManager.swift:884) — but `found` is
     * nil for TWO different facts: the field was absent (genuinely UNSET), and
     * the raw value is one THIS BUILD HAS NEVER HEARD OF (a newer firmware
     * region). Collapsing the second onto UNSET refuses to transmit from a node
     * that is correctly configured, which is exactly the "unknown is not zero"
     * mistake the send guard's own comment warns against (:497-502). Keeping the
     * raw value lets [region] stay honestly null for display while the guard
     * refuses only on the evidence of a real 0.
     */
    private val _regionRaw = MutableStateFlow<Long?>(null)
    val regionRaw: StateFlow<Long?> = _regionRaw.asStateFlow()

    /** False when the radio is explicitly muted. A muted node looks healthy everywhere else. :205-207 */
    private val _transmitEnabled = MutableStateFlow(true)
    val transmitEnabled: StateFlow<Boolean> = _transmitEnabled.asStateFlow()

    /** Pairing mode AS REPORTED BY THE NODE. Null until its config has been read. :208-209 */
    private val _pairingMode = MutableStateFlow<LoRaBluetoothPairing?>(null)
    val pairingMode: StateFlow<LoRaBluetoothPairing?> = _pairingMode.asStateFlow()

    /**
     * Why the last message did not go, as a STRING KEY the UI localizes
     * (`res/values*` belongs to the UI agent). Cleared by the first successful
     * send, so it always describes a live problem. :211-224
     */
    private val _lastRefusal = MutableStateFlow<String?>(null)
    val lastRefusal: StateFlow<String?> = _lastRefusal.asStateFlow()

    private val _lastRefusalReason = MutableStateFlow<LoRaSendRefusal?>(null)
    val lastRefusalReason: StateFlow<LoRaSendRefusal?> = _lastRefusalReason.asStateFlow()

    private val _lastRefusalAt = MutableStateFlow<Long?>(null)
    val lastRefusalAt: StateFlow<Long?> = _lastRefusalAt.asStateFlow()

    /** Bytes of the refused payload, for "too large" (which needs a number). */
    private val _lastRefusalBytes = MutableStateFlow<Int?>(null)
    val lastRefusalBytes: StateFlow<Int?> = _lastRefusalBytes.asStateFlow()

    /**
     * Message ids the mesh has CONFIRMED. Read by the UI to show a LoRa message
     * as delivered rather than merely sent. :560-562
     */
    private val _loraAcked = MutableStateFlow<Set<String>>(emptySet())
    val loraAcked: StateFlow<Set<String>> = _loraAcked.asStateFlow()

    // OSHI Mesh firmware — see OshiMeshProtocol.kt. MESHTASTIC until the radio answers the probe.
    private val _firmwareFamily = MutableStateFlow(LoRaFirmwareFamily.MESHTASTIC)
    val firmwareFamily: StateFlow<LoRaFirmwareFamily> = _firmwareFamily.asStateFlow()
    /**
     * True once the OSHI Mesh probe had its chance to be answered: only then does MESHTASTIC mean stock
     * firmware (the settings screen offers the upgrade on that, and must not offer it to an OSHI Mesh
     * radio still answering). iOS `firmwareProbeDone`.
     */
    private val _firmwareProbeDone = MutableStateFlow(false)
    val firmwareProbeDone: StateFlow<Boolean> = _firmwareProbeDone.asStateFlow()
    /** Messages an OSHI Mesh radio is holding because their destination is not reachable yet. */
    private val _loraInCustody = MutableStateFlow<Set<String>>(emptySet())
    val loraInCustody: StateFlow<Set<String>> = _loraInCustody.asStateFlow()
    private val ompRx = OmpReassembler()
    private val ompOutgoing = HashMap<UInt, String>()
    /** The radio's channel table as reported during config: index -> (role, name). */
    private val channelSlots = HashMap<Int, Pair<Long, String>>()
    /** Index of the private OSHI channel on this radio, once seen or created. */
    @Volatile private var oshiChannelIndex: Int? = null

    private val _status = MutableStateFlow(LoRaNodeStatus())
    val status: StateFlow<LoRaNodeStatus> = _status.asStateFlow()

    /**
     * __ANTI_JAMMING_PHASE1_2026_11_13__ What [LoRaJammingDetector] makes of the signals this radio sends
     * us (telemetry, the OSHI Mesh RADIO frame, packets heard, acks). PROBABLE_JAMMING makes
     * [queueOrSendSecureMessage] hold messages for later and the transport policy skip LoRa
     * (`MessageSendPolicy.RoutingConditions.radioJammed`), so the Bluetooth mesh / relay carry them.
     */
    private val jammingDetector = LoRaJammingDetector()
    private val _jamming = MutableStateFlow(LoRaJammingDetector.State.CLEAR)
    val jamming: StateFlow<LoRaJammingDetector.State> = _jamming.asStateFlow()
    /**
     * True while routing must leave LoRa out: the detector says PROBABLE_JAMMING and the person has not
     * overruled it with "Send over LoRa anyway" ([sendOverLoRaAnyway], `LoRaJammingOverride`).
     * `MessageRepository` reads this into `RoutingConditions.radioJammed`.
     */
    val isJammed: Boolean get() = jammingOverride.excludesLoRa(_jamming.value)
    private var jammingTickJob: Job? = null

    /**
     * __ANTI_JAMMING_OVERRIDE_2026_11_13__ "Send over LoRa anyway": a false PROBABLE_JAMMING must not
     * strand an off-grid person with no Bluetooth neighbour. In memory only; ends after 10 min or when the
     * detector returns to CLEAR. [jammingOverrideUntil] is its end (epoch millis), null when inactive.
     */
    private val jammingOverride = LoRaJammingOverride()
    private val _jammingOverrideUntil = MutableStateFlow<Long?>(null)
    val jammingOverrideUntil: StateFlow<Long?> = _jammingOverrideUntil.asStateFlow()
    private var jammingOverrideExpiryJob: Job? = null
    @Volatile private var lastCounterStatsAt = 0L

    /** Reassembled OSHI envelopes. The message pipeline subscribes. */
    private val _receivedMessages = MutableSharedFlow<LoRaReceivedMessage>(extraBufferCapacity = 32)
    val receivedMessages: SharedFlow<LoRaReceivedMessage> = _receivedMessages.asSharedFlow()

    /** Raw Meshtastic text from any client. :999-1056 */
    private val _receivedText = MutableSharedFlow<LoRaInteropText>(extraBufferCapacity = 32)
    val receivedText: SharedFlow<LoRaInteropText> = _receivedText.asSharedFlow()

    /**
     * Our own OSHI public key, injected by whoever owns identity. While it is
     * null NO recipient filtering happens and every envelope is published —
     * dropping messages because a dependency was not wired is worse than
     * publishing a few that are not ours, which the pipeline drops anyway when
     * decryption fails. Mirrors :1093-1096.
     */
    @Volatile
    var myPublicKeyProvider: (() -> String?)? = null

    val isReady: Boolean get() = _state.value == ConnState.READY   // :286

    /** Other LoRa nodes heard within Meshtastic's "online" window (own radio excluded). */
    fun onlineNodeCount(now: Long = System.currentTimeMillis()): Int =
        LoRaOnline.count(_nodes.value, _myNodeNum.value, now)

    /**
     * What to call the connected node on screen.
     *
     * The BLE ADVERTISED name is cached by the stack and does not refresh just
     * because the node renamed itself, so the node's own longName from its
     * NodeInfo is the authoritative answer. :288-302
     */
    val displayName: String?
        get() {
            val mine = _nodes.value.firstOrNull { it.num == _myNodeNum.value }
            if (_myNodeNum.value != 0u && mine != null && mine.longName.isNotEmpty()) return mine.longName
            return _connectedName.value
        }

    // ─────────────────────────── Internals ───────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Opt-in: carry media over LoRa. Off by default. Persisted in [prefs] under
     * the same key iOS uses; read by the send guard and toggled from Settings.
     * [Media-over-LoRa parity 2026-09-11]
     */
    private val _mediaOverLoRaEnabled = MutableStateFlow(prefs.getBoolean(MEDIA_OVER_LORA_KEY, false))
    val mediaOverLoRaEnabled: StateFlow<Boolean> = _mediaOverLoRaEnabled.asStateFlow()

    /** Persist and publish the media-over-LoRa opt-in. */
    fun setMediaOverLoRaEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(MEDIA_OVER_LORA_KEY, enabled).apply()
        _mediaOverLoRaEnabled.value = enabled
    }

    /**
     * Opt-in: beacon this phone's position over the mesh. Off by default.
     * Persisted in [prefs]; read by [LoRaPositionBeacon], toggled from Settings.
     * [LoRa position beacon 2026-10-28]
     *
     * The radio layer only HOLDS this. It does not beacon on its own and has no
     * location source of its own — [broadcastPosition] still has to be called
     * with coordinates from somewhere. That separation is deliberate: this class
     * is a singleton reachable from a `Context`, while the only location source
     * in the app is the injected `LocationSharingManager`, and a second
     * fused-location client would be a second answer to "where are we".
     */
    private val _positionBeaconEnabled = MutableStateFlow(prefs.getBoolean(POSITION_BEACON_KEY, false))
    val positionBeaconEnabled: StateFlow<Boolean> = _positionBeaconEnabled.asStateFlow()

    /** Persist and publish the position-beacon opt-in. */
    fun setPositionBeaconEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(POSITION_BEACON_KEY, enabled).apply()
        _positionBeaconEnabled.value = enabled
    }

    /**
     * The channel name last written from this phone. Empty means the stock
     * LongFast channel — see [LoRaChannel].
     */
    private val _channelName = MutableStateFlow(prefs.getString(CHANNEL_NAME_KEY, "") ?: "")
    val channelName: StateFlow<String> = _channelName.asStateFlow()

    /**
     * __NODE_CHANNELS_WERE_DROPPED_2026_11_04__ the channel slots the NODE
     * reports, ordered by index, DISABLED slots left out.
     *
     * Distinct from [channelName], which is what WE last wrote and is read from
     * prefs so the setup screen can show it before the node is even connected.
     * This is the node's own answer, and until now there was nowhere to see it:
     * the app could overwrite the primary channel but never read back a single
     * slot, so "which mesh am I on?" had no answer and secondary channels were
     * invisible.
     *
     * Empty until the first `wantConfig` handshake completes.
     */
    private val _nodeChannels = MutableStateFlow<List<LoRaPacketParser.NodeChannel>>(emptyList())
    val nodeChannels: StateFlow<List<LoRaPacketParser.NodeChannel>> = _nodeChannels.asStateFlow()

    private val bluetoothManager: BluetoothManager? =
        ContextCompat.getSystemService(appContext, BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var toRadioChar: BluetoothGattCharacteristic? = null
    private var fromRadioChar: BluetoothGattCharacteristic? = null
    private var fromNumChar: BluetoothGattCharacteristic? = null

    private var wantConfigId: UInt = 0u

    /**
     * Identifier of the radio we are mid-handshake with. Promoted to the stored
     * paired key ONLY when the node completes its config handshake — the first
     * moment we actually know the pairing works. Writing it at connect time left
     * a failed attempt marked as paired for ever, and that stale key then raced
     * the user's own manual attempt on every Bluetooth power-on. :309-312, :353-364
     */
    private var pendingPairId: String? = null

    private var scanning = false
    private var wantsScan = false

    private val reassembler = LoRaReassembler()

    /**
     * Envelopes waiting for the radio (out of range, probe pending, channel jammed). :434-435
     * __LORA_HELD_DURABLE_2026_11_18__ Was an unsynchronised in-memory `ArrayDeque` capped at 20 that
     * dropped the oldest in silence; now thread-safe, persisted, and never drops without telling
     * [heldListener]. See [LoRaHeldQueue].
     */
    private val storeAndForward = LoRaHeldQueue(
        LoRaHeldQueue.FileStore(File(appContext.noBackupFilesDir, LoRaHeldQueue.FILE_NAME))
    )
    /** One drain at a time; a flush requested mid-drain is picked up by the running one or the next. */
    private val flushMutex = Mutex()

    /**
     * What the message pipeline needs to hear about held messages. Set by `MessageRepository`
     * when it attaches to the radio. Null (no repository yet) means: send everything held, report nothing.
     */
    interface HeldListener {
        /** False when the row is gone or was delivered some other way: do not spend airtime on it. */
        suspend fun stillWanted(messageId: String): Boolean
        /** A held message's frames reached the radio. */
        suspend fun onHeldSent(messageId: String)
        /**
         * It will never go out over this radio (too large for it, evicted, unreadable), or it went out and
         * the radio says it was not delivered (OMP FAILED/REJECTED, a stock routing failure, or no answer
         * within [LoRaSendTimeout]): show it failed. The listener must leave a row alone that was delivered
         * some other way; a later mesh confirmation (`loraAcked`) upgrades it again.
         */
        suspend fun onHeldUndeliverable(messageId: String)
    }
    @Volatile var heldListener: HeldListener? = null

    /** __LORA_AIRTIME_HINT_2026_11_18__ OMP QUEUED without SENT; published as [awaitingAirtime]. */
    private val airtimeWait = LoRaAirtimeWait()
    private var airtimeTickJob: Job? = null

    /** __LORA_SEND_TIMEOUT_2026_11_23__ OMP sends with no terminal status yet; see [LoRaSendTimeout]. */
    private val sendTimeout = LoRaSendTimeout()
    private var sendTimeoutJob: Job? = null
    private val sendTimeoutLock = Any()

    /** packet id → (message id, sent-at millis) for sends awaiting an ACK. :564-568 */
    private val loraPendingAck = HashMap<UInt, Pair<String, Long>>()

    /** Outbound frames, paced for LoRa duty cycle. :318-319, :820-828 */
    private val sendQueue = Channel<ByteArray>(Channel.BUFFERED)
    private var pumpJob: Job? = null
    private var reconnectJob: Job? = null

    // GATT is strictly one-operation-at-a-time on Android: issuing a read while a
    // write is outstanding makes the second call return false and the callback
    // never arrives. CoreBluetooth queues for you; here we must.
    // DIVERGENCE from iOS, forced by the platform.
    private val gattMutex = Mutex()
    private val drainMutex = Mutex()
    private var writeAck: CompletableDeferred<Boolean>? = null
    private var readAck: CompletableDeferred<ByteArray?>? = null
    private var descriptorAck: CompletableDeferred<Boolean>? = null

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            refreshBluetoothState()
        }
    }

    private fun start() {
        // RECEIVER_NOT_EXPORTED is mandatory from Android 14 for a receiver an app
        // registers at runtime; without the flag the registration throws on a
        // targetSdk-35 build and the manager would never learn Bluetooth came back.
        ContextCompat.registerReceiver(
            appContext,
            adapterReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refreshBluetoothState()
    }

    /** Port of `centralManagerDidUpdateState` (:1123-1140). */
    private fun refreshBluetoothState() {
        val on = adapter?.isEnabled == true
        _bluetoothOn.value = on
        _permissionDenied.value = !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        if (on) {
            reconnectIfPaired()
            if (wantsScan && gatt == null) startScan()
        } else {
            // A stack reset invalidates every object we hold and delivers no
            // disconnect callback, so nothing else clears them — and a stale
            // non-null gatt is exactly what wedges recovery. :1130-1139
            teardownGatt()
            _connectedName.value = null
            _state.value = ConnState.OFF
            refreshStatus()
        }
    }

    // ─────────────────────────── Permissions ───────────────────────────

    private fun hasPermission(p: String): Boolean {
        // BLUETOOTH_SCAN / BLUETOOTH_CONNECT only exist from Android 12; before
        // that the legacy BLUETOOTH/BLUETOOTH_ADMIN pair is install-time.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(appContext, p) == PackageManager.PERMISSION_GRANTED
    }

    // ─────────────────────────── Detection / pairing ───────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleDiscovery(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleDiscovery(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            _lastError.value = "lora.error.scan_failed|$errorCode"
            scanning = false
            if (_state.value == ConnState.SCANNING) _state.value = ConnState.OFF
            refreshStatus()
        }
    }

    /** :328-337 */
    fun startScan() {
        wantsScan = true                                   // resume once BT powers on (first-open race)
        if (!_bluetoothOn.value) return
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            _permissionDenied.value = true
            return
        }
        if (scanning) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        _discovered.value = emptyList()
        _state.value = ConnState.SCANNING
        // Service-filtered scan: ONLY Meshtastic-compatible radios appear. :333-335
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
            .onFailure { _lastError.value = it.message }
        scanning = true
        refreshStatus()
    }

    /** :339-346 */
    fun stopScan() {
        wantsScan = false
        reconnectViaScan = false
        if (scanning && hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        }
        scanning = false
        // Restore the real state — never leave it wedged at SCANNING, which would
        // block isReady and therefore ALL sends until the next reconnect. :344-345
        if (_state.value == ConnState.SCANNING) {
            _state.value = if (gatt != null && toRadioChar != null) ConnState.READY else ConnState.OFF
        }
        refreshStatus()
    }

    private var reconnectViaScan = false

    private fun handleDiscovery(result: ScanResult) {
        val address = result.device?.address ?: return
        val paired = prefs.getString(PAIRED_KEY, null)

        // Re-pair silently with the radio we already know, so a stack reset heals
        // itself instead of dumping the user back into the pairing sheet. :1146-1153
        if (reconnectViaScan && gatt == null && address == paired) {
            reconnectViaScan = false
            connect(address)
            return
        }

        val name = runCatching { result.device.name }.getOrNull()
            ?: result.scanRecord?.deviceName
            ?: "LoRa radio"
        val node = DiscoveredLoRaNode(
            id = address,
            name = name,
            rssi = result.rssi,
            family = LoRaFirmwareFamily.MESHTASTIC,
            isKnown = address == paired,
        )
        val current = _discovered.value
        if (current.any { it.id == address }) return
        _discovered.value = (current + node).sortedByDescending { it.rssi }   // :1154-1157
    }

    /** :348-368 */
    fun connect(id: String, autoConnect: Boolean = false) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            _permissionDenied.value = true
            return
        }
        val device = runCatching { adapter?.getRemoteDevice(id) }.getOrNull() ?: return
        stopScan()
        teardownGatt()
        _state.value = ConnState.CONNECTING
        pendingPairId = id
        // TRANSPORT_LE explicitly: a dual-mode radio otherwise risks being reached
        // over BR/EDR, where the Meshtastic GATT service does not exist.
        gatt = device.connectGatt(appContext, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        refreshStatus()
    }

    /** :370-378 */
    fun disconnect(forget: Boolean = false) {
        // An in-flight attempt is abandoned here, so it must not be promoted later.
        pendingPairId = null
        if (forget) prefs.edit().remove(PAIRED_KEY).apply()
        teardownGatt()
        _connectedName.value = null
        _state.value = ConnState.OFF
        _myNodeNum.value = 0u
        _nodes.value = emptyList()
        refreshStatus()
    }

    private fun teardownGatt() {
        pumpJob?.cancel(); pumpJob = null
        reconnectJob?.cancel(); reconnectJob = null
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        toRadioChar = null; fromRadioChar = null; fromNumChar = null
        writeAck?.complete(false); writeAck = null
        readAck?.complete(null); readAck = null
        descriptorAck?.complete(false); descriptorAck = null
        reassembler.clear()
    }

    /**
     * Reconnect to the previously paired radio, on Bluetooth power-on and after a
     * disconnect. :380-404
     *
     * DIVERGENCE: iOS has to re-SCAN when CoreBluetooth's peripheral cache comes
     * back empty after a stack reset. Android has no such cache —
     * `getRemoteDevice(address)` always resolves — so the direct path is used
     * with `autoConnect = true`, which asks the stack to reconnect whenever the
     * radio next advertises. The scan fallback is kept only for the case where
     * the address no longer resolves at all.
     */
    private fun reconnectIfPaired() {
        val id = prefs.getString(PAIRED_KEY, null) ?: return
        if (gatt != null) return
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        if (runCatching { adapter?.getRemoteDevice(id) }.getOrNull() != null) {
            connect(id, autoConnect = true)
            return
        }
        reconnectViaScan = true
        startScan()
        // Don't scan for ever on a radio that is switched off or out of range. :397-403
        scope.launch {
            delay(30_000)
            if (reconnectViaScan) {
                reconnectViaScan = false
                stopScan()
            }
        }
    }

    // ─────────────────────────── GATT ───────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectedName.value = runCatching { g.device.name }.getOrNull() ?: "LoRa radio"
                    // MTU FIRST, services second: a 23-byte MTU fragments every
                    // frame we write (a full OSHI frame is 188 bytes), and the
                    // negotiation must happen before any traffic. See REQUESTED_MTU.
                    if (!g.requestMtu(REQUESTED_MTU)) g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> handleDisconnected()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE_UUID) ?: run {
                _lastError.value = "lora.error.service_missing"
                handleDisconnected(); return
            }
            toRadioChar = svc.getCharacteristic(TO_RADIO_UUID)
            fromRadioChar = svc.getCharacteristic(FROM_RADIO_UUID)
            fromNumChar = svc.getCharacteristic(FROM_NUM_UUID)
            if (toRadioChar == null || fromRadioChar == null) {
                _lastError.value = "lora.error.characteristics_missing"
                handleDisconnected(); return
            }
            scope.launch {
                fromNumChar?.let { enableNotifications(g, it) }   // :1194
                startConfig()                                     // :1198
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descriptorAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) _lastError.value = "lora.error.write_failed|$status"
            writeAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+ hands the value in; the deprecated overload reads it off the
        // characteristic. Both are overridden because compileSdk 35 with
        // minSdk 26 means BOTH are live on real devices.
        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) {
            // Failed read still delivers the PREVIOUS value — parsing it would
            // re-deliver a stale packet and loop the read chain. :1209-1211
            readAck?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            readAck?.complete(if (status == BluetoothGatt.GATT_SUCCESS) c.value else null)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            if (c.uuid == FROM_NUM_UUID) scope.launch { drainFromRadio() }   // :1202-1206
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (c.uuid == FROM_NUM_UUID) scope.launch { drainFromRadio() }
        }
    }

    /** :1171-1182 */
    private fun handleDisconnected() {
        val hadPair = prefs.getString(PAIRED_KEY, null) != null
        _connectedName.value = null
        _state.value = ConnState.OFF
        teardownGatt()
        stopJammingWatch()
        refreshStatus()
        // Auto-reconnect: the radio may just be rebooting or briefly out of range.
        if (hadPair) {
            reconnectJob = scope.launch {
                delay(3_000)
                reconnectIfPaired()
            }
        }
    }

    private suspend fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        gattMutex.withLock {
            if (!g.setCharacteristicNotification(c, true)) return
            val d = c.getDescriptor(CCCD_UUID) ?: return
            val ack = CompletableDeferred<Boolean>()
            descriptorAck = ack
            val issued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(d)
                }
            }
            if (!issued) { descriptorAck = null; return }
            withTimeoutOrNull(GATT_TIMEOUT_MS) { ack.await() }
            descriptorAck = null
        }
    }

    private suspend fun gattWrite(c: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        val g = gatt ?: return false
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return false
        return gattMutex.withLock {
            val ack = CompletableDeferred<Boolean>()
            writeAck = ack
            val issued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(
                    c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    c.value = data
                    g.writeCharacteristic(c)
                }
            }
            val ok = if (issued) withTimeoutOrNull(GATT_TIMEOUT_MS) { ack.await() } ?: false else false
            writeAck = null
            ok
        }
    }

    private suspend fun gattRead(c: BluetoothGattCharacteristic): ByteArray? {
        val g = gatt ?: return null
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return null
        return gattMutex.withLock {
            val ack = CompletableDeferred<ByteArray?>()
            readAck = ack
            val issued = g.readCharacteristic(c)
            val v = if (issued) withTimeoutOrNull(GATT_TIMEOUT_MS) { ack.await() } else null
            readAck = null
            v
        }
    }

    // ─────────────────────────── Handshake ───────────────────────────

    /** :408-415 */
    private suspend fun startConfig() {
        channelSlots.clear()
        oshiChannelIndex = null
        wantConfigId = Random.nextInt(1, Int.MAX_VALUE).toUInt()
        _state.value = ConnState.CONFIGURING
        refreshStatus()
        val tr = toRadioChar ?: return
        gattWrite(tr, LoRaPacketParser.wantConfig(wantConfigId))   // ToRadio.want_config_id
        startPump()
        drainFromRadio()
    }

    /**
     * Drain `fromRadio` by repeated READ until it returns empty — Meshtastic's
     * documented pattern, and what iOS does through its read chain (:1212-1218).
     */
    private suspend fun drainFromRadio() {
        val fr = fromRadioChar ?: return
        drainMutex.withLock {
            while (true) {
                val v = gattRead(fr) ?: return
                if (v.isEmpty()) return
                handleFromRadio(v)
            }
        }
    }

    // ─────────────────────────── Send paths ───────────────────────────

    /**
     * __LORA_POSITION_BEACON_2026_10_28__ Broadcast OUR position on POSITION_APP, so
     * other radios can draw us on their map — what Meshtastic does, and the half this
     * app did not do. The receive half has existed for a while: positions arriving on
     * portnum 3 already land in [nodes] and, since today, on the map.
     *
     * ⚠️ THIS IS A PUBLIC, CLEARTEXT BROADCAST. It goes to [BROADCAST_ADDR] on the
     * default LongFast channel, whose key is published in Meshtastic's own source, to
     * every radio in range — up to tens of kilometres, to people the user has never
     * met and cannot enumerate. That is the whole point of a mesh beacon and it is also
     * the reason this is never called on a schedule and never defaults to on: the
     * caller must be a deliberate user action. iOS removed group messages from the mesh
     * for precisely this property (PARITY row 0.17-a: it "broadcast group bodies in
     * cleartext to every nearby device, member or not").
     *
     * `Position.latitude_i`/`longitude_i` are **sfixed32 scaled by 1e-7** — the same
     * encoding the parser reads, written here rather than re-derived, so a change to
     * one is visible against the other.
     *
     * A 0/0 fix is REFUSED rather than sent: that is what a receiver with no lock
     * reports, and broadcasting it would put this node in the Gulf of Guinea on every
     * map that heard it — the exact defect the receive path already guards against.
     *
     * @return true when the frame was queued for the radio.
     */
    @Deprecated(
        "Measured 2026-10-28: a client POSITION_APP broadcast is accepted by the radio and " +
            "never reaches another node. Use setFixedPosition().",
        ReplaceWith("setFixedPosition(latitude, longitude)"),
    )
    fun broadcastPosition(latitude: Double, longitude: Double): Boolean {
        if (!isReady) { _lastError.value = LoRaSendRefusal.NOT_CONNECTED.stringKey; return false }
        if (latitude == 0.0 && longitude == 0.0) return false
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return false
        val payload = LoRaPacketParser.positionPayload(
            latitude, longitude, System.currentTimeMillis() / 1000)
        enqueuePacket(BROADCAST_ADDR, LoRaPacketParser.PORT_POSITION, payload, wantAck = false)
        log("→", "position %.5f, %.5f → ALL".format(latitude, longitude))
        return true
    }

    /**
     * __LORA_FIXED_POSITION_2026_10_28__ Tell the RADIO where it is, and let its own
     * PositionModule announce it. **This is the one that works.**
     *
     * [broadcastPosition] above — the mechanism this app shipped in 1.6.x on 2026-10-28 —
     * puts a POSITION_APP packet on the broadcast address, and that is accepted by the
     * radio and reaches nobody. Measured with two ThinkNode M1s in one room: ten such
     * frames over four minutes arrived nowhere, while a plain interop TEXT broadcast from
     * the same radio in the same second arrived in 3 seconds, on a mesh where the receiver
     * was reading 36 other nodes' positions correctly. Every write returned true — a write
     * is bytes leaving the process, not a transmission.
     *
     * ⚠️ Unlike a broadcast this WRITES THE NODE'S CONFIGURATION and persists on the
     * device: a radio told it is in Geneva stays in Geneva for every app that connects to
     * it afterwards, until [clearFixedPosition].
     *
     * @return true when the frame was queued for the radio. Not delivery, and not proof
     *   the firmware accepted it — an admin write is unicast to our own node and acked, but
     *   nothing here waits for that ack.
     */
    fun setFixedPosition(latitude: Double, longitude: Double): Boolean {
        if (!isReady) { _lastError.value = LoRaSendRefusal.NOT_CONNECTED.stringKey; return false }
        if (_myNodeNum.value == 0u) return false
        val admin = LoRaPacketParser.setFixedPositionPayload(latitude, longitude) ?: return false
        enqueuePacket(_myNodeNum.value, LoRaPacketParser.PORT_ADMIN, admin, wantAck = true)
        log("→", "fixed position %.5f, %.5f → my node".format(latitude, longitude))
        return true
    }

    /** Clear the node's stored fixed position — the undo for [setFixedPosition]. */
    fun clearFixedPosition(): Boolean {
        if (!isReady) { _lastError.value = LoRaSendRefusal.NOT_CONNECTED.stringKey; return false }
        if (_myNodeNum.value == 0u) return false
        enqueuePacket(_myNodeNum.value, LoRaPacketParser.PORT_ADMIN,
            LoRaPacketParser.removeFixedPositionPayload(), wantAck = true)
        log("→", "fixed position cleared")
        return true
    }

    /**
     * INTEROP: plain text on the standard Meshtastic text port — readable by the
     * official Meshtastic app and every other client on the mesh. :427-431
     *
     * @param channel which of the radio's channel slots to transmit on. 0 — the
     *   primary — is where every OSHI envelope and every direct message belongs.
     *   A LoRa channel ROOM passes its own slot; see
     *   `__CHANNEL_ROOMS_ALL_SENT_ON_SLOT_ZERO_2026_11_07__`.
     */
    // Returns true only when the frame was actually queued to a connected
    // radio — the same true-means-written contract as `setRegion`/`setOwner`,
    // and what `sendLoRaInteropReply` (MessageRepository.kt) needs to decide
    // whether the reply bubble is delivered or failed.
    // MERGE: ours' Boolean contract + production's per-slot `channel`.
    fun sendText(text: String, to: UInt = BROADCAST_ADDR, channel: Int = 0): Boolean {
        if (!isReady) { _lastError.value = LoRaSendRefusal.NOT_CONNECTED.stringKey; return false }
        enqueuePacket(to, LoRaPacketParser.PORT_TEXT, text.toByteArray(Charsets.UTF_8),
            wantAck = to != BROADCAST_ADDR, channel = channel.coerceAtLeast(0))
        val who = if (to == BROADCAST_ADDR) "ALL" else LoRaPacketParser.nodeIdString(to)
        log("→", "text ${text.take(24)}${if (text.length > 24) "…" else ""} → $who" +
            if (channel != 0) " ch$channel" else "")
        return true
    }

    /**
     * Entry point for the message pipeline's last-resort lane.
     *
     * Returns true ONLY when frames were actually written to a connected radio. A
     * merely-QUEUED message returns false so the caller keeps its normal
     * failed + retry path — the store-and-forward flush is then a free second
     * chance (receivers dedup by message id across transports), never a silently
     * lost "sent". :437-465
     */
    fun queueOrSendSecureMessage(message: LoRaWireMessage): Boolean {
        // Media is checked HERE rather than at the call site so every LoRa refusal
        // is recorded in one place: "my photo won't send off-grid" is precisely
        // the case where a person needs to be told LoRa carries text only. :445-455
        // Media over LoRa is opt-in and off by default (see LoRaSettingsScreen).
        // When OFF keep the historical refusal in one place so the banner explains
        // why the photo did not go out. When the person has turned it ON, fall
        // through: toWireJson() puts only the ciphertext on the air, and the
        // MAX_CHUNKS cap in sendSecureMessage is the real size gate — larger media
        // is refused there as TOO_LARGE. [Media-over-LoRa parity 2026-09-11]
        if (message.carriesMedia && !_mediaOverLoRaEnabled.value) {
            refuse(LoRaSendRefusal.CARRIES_MEDIA)
            return false
        }
        if (isReady) {
            // __LORA_PROBE_BEFORE_FLUSH_2026_11_18__ / __ANTI_JAMMING_PHASE1_2026_11_13__ Held, not sent:
            // the channel is excluded for jamming, or the firmware probe has not been answered yet (sent
            // now, an OSHI Mesh radio's message would take the 12-frame stock path). Return false so the
            // caller's normal chain (Bluetooth mesh, relay) carries it now; receivers dedupe by message id.
            LoRaRadioGate.holdReason(
                probeDone = _firmwareProbeDone.value,
                answeredOshi = _firmwareFamily.value == LoRaFirmwareFamily.OSHI,
                excludedForJamming = isJammed,
            )?.let { why ->
                holdForLater(message, why)
                return false
            }
            return sendSecureMessage(message)
        }
        refuse(LoRaSendRefusal.NOT_CONNECTED)
        if (prefs.getString(PAIRED_KEY, null) != null) holdForLater(message, "radio not connected")
        return false
    }

    /**
     * __NEVER_DROP_HELD_2026_11_18__ Hold [message] for the paired radio without trying to send it now:
     * the "internet sending off" branch of `MessageRepository`, where the policy left the radio out
     * (jammed, not connected) and nothing else may carry the message. Returns false when it cannot be
     * held — no radio paired, or too large for the radio — so the caller marks the row failed.
     */
    fun holdForRadio(message: LoRaWireMessage): Boolean {
        if (prefs.getString(PAIRED_KEY, null) == null) return false
        if (message.carriesMedia && !_mediaOverLoRaEnabled.value) return false
        val held = holdForLater(message, "kept for the radio: internet sending is off")
        if (held) flushStoreAndForward()
        return held
    }

    /** The paired radio is (or was, at its last connection) an OSHI Mesh radio. */
    private fun oshiFirmwareNowOrLast(): Boolean =
        _firmwareFamily.value == LoRaFirmwareFamily.OSHI || lastKnownOshiFirmware(appContext)

    /**
     * Returns false — and records TOO_LARGE — when the envelope cannot fit the path it will take, so
     * the caller's chain decides (and, at the end of it, marks the row failed). Was a silent `return`.
     */
    private fun holdForLater(message: LoRaWireMessage, why: String): Boolean {
        val json = message.toWireJson()
        val body = OshiMeshProtocol.encodeBody(json)
        if (!LoRaRadioGate.fits(body, oshiFirmwareNowOrLast())) {
            refuse(LoRaSendRefusal.TOO_LARGE, body.size)
            log("→", "not held for LoRa ($why): ${body.size}B is too large for this radio")
            return false
        }
        val evicted = storeAndForward.add(message.id, message.recipientPublicKey, json, System.currentTimeMillis())
        log("→", "held for LoRa: $why (${storeAndForward.size} waiting)")
        if (evicted.isNotEmpty()) {
            log("!", "${evicted.size} held message(s) over the ${storeAndForward.capacity} limit: marked not delivered")
            scope.launch { evicted.forEach { heldListener?.onHeldUndeliverable(it) } }
        }
        return true
    }

    /** True while [id] is waiting in the radio's held queue. */
    fun isHeld(id: String): Boolean = storeAndForward.contains(id)

    /**
     * :476-480. __LORA_HELD_DURABLE_2026_11_18__ Was `toList()` + `clear()` on an unlocked deque, which
     * lost anything added between the two, and ran before the firmware probe answered. Now each item
     * leaves the queue only once its frames are on the radio (or it is known undeliverable / unwanted),
     * and the drain stops — keeping the rest — the moment the radio is not usable.
     */
    private fun flushStoreAndForward() {
        if (!canFlush() || storeAndForward.isEmpty()) return
        scope.launch { flushMutex.withLock { drainHeld() } }
    }

    private fun canFlush(): Boolean = LoRaRadioGate.mayFlush(
        ready = isReady,
        probeDone = _firmwareProbeDone.value,
        answeredOshi = _firmwareFamily.value == LoRaFirmwareFamily.OSHI,
        excludedForJamming = isJammed,
    )

    private suspend fun drainHeld() {
        for (h in storeAndForward.snapshot()) {
            if (!canFlush()) return
            val listener = heldListener
            if (listener != null && !listener.stillWanted(h.id)) {
                storeAndForward.remove(h.id)
                log("→", "held msg ${h.id.take(8)} no longer needs LoRa (delivered or deleted)")
                continue
            }
            val oshi = _firmwareFamily.value == LoRaFirmwareFamily.OSHI
            if (!LoRaRadioGate.fits(OshiMeshProtocol.encodeBody(h.wireJson), oshi)) {
                // Held for an OSHI Mesh radio, but the radio here now is stock: it can never carry this.
                storeAndForward.remove(h.id)
                refuse(LoRaSendRefusal.TOO_LARGE, h.wireJson.size)
                listener?.onHeldUndeliverable(h.id)
                continue
            }
            if (sendWire(h.wireJson, h.id, h.recipientPublicKey, null)) {
                storeAndForward.remove(h.id)
                listener?.onHeldSent(h.id)
            } else {
                return   // muted / no region / disconnected: keep it and everything after it
            }
        }
    }

    /**
     * OSHI↔OSHI: a full E2E envelope over LoRa, chunked. Broadcast by default —
     * every OSHI phone on the mesh sees the ciphertext, but only the recipient
     * whose key matches can decrypt; everyone else drops it. :482-547
     */
    /**
     * __LORA_ACK_2026_10_10__ `to` is now NULLABLE, and null means "work it
     * out" — which is what finally makes delivery confirmation possible.
     *
     * THE BUG, identical to iOS's. The default was [BROADCAST_ADDR] and no
     * caller ever passed anything else, so `wantAck = to != BROADCAST_ADDR`
     * below was **always false**. Meshtastic does not acknowledge broadcasts,
     * so `trackPendingAck`, the pending map, the ROUTING_APP handler and
     * `loraAcked` — all already written and correct — could never fire for a
     * 1:1 message. `MessageRepository` even collects `radio.loraAcked` into
     * `promoteLoRaAcked`, a flow that could never emit.
     *
     * Off-grid is where "did it arrive?" matters most, and it was the one path
     * that could not answer.
     *
     * Resolved here, not at the call sites, so every path benefits. A binding
     * exists only for a peer whose envelope actually decrypted, which is
     * precisely when a unicast can be expected to reach them; with no binding
     * we broadcast exactly as before and simply get no ack — the old
     * behaviour, not a regression.
     */
    fun sendSecureMessage(message: LoRaWireMessage, to: UInt? = null): Boolean {
        // A node with no region set, or with its radio muted, ACCEPTS the frame
        // and transmits nothing. Reporting that as sent turns a lost message into
        // a delivered one on screen.
        //
        // But refuse only on EVIDENCE: `region == null` means we have not READ the
        // node's config yet, not that it has no region. Collapsing null to UNSET
        // blocked every send whenever the config had not landed — a worse failure
        // than the one this guard prevents. UNKNOWN IS NOT ZERO. :493-508
        if (!isReady) { refuse(LoRaSendRefusal.NOT_CONNECTED); return false }
        if (!_transmitEnabled.value) { refuse(LoRaSendRefusal.RADIO_MUTED); return false }
        if (_regionRaw.value == LoRaIdentityBridge.REGION_UNSET_RAW) {
            refuse(LoRaSendRefusal.NO_REGION); return false
        }

        // meshWireSafe() is applied inside toWireJson(): `plaintextContent` is in
        // SecureMessage's CodingKeys, so encoding the message whole would put the
        // user's text on the air IN THE CLEAR beside the ciphertext, on the public
        // LongFast channel. :486-491, :509
        return sendWire(message.toWireJson(), message.id, message.recipientPublicKey, to)
    }

    /**
     * [sendSecureMessage] on an already-encoded wire envelope — what the held queue stores, so a held
     * message goes out byte for byte as it was accepted (media included) without re-encoding.
     */
    private fun sendWire(json: ByteArray, messageId: String, recipientPublicKey: String, to: UInt?): Boolean {
        if (!isReady) { refuse(LoRaSendRefusal.NOT_CONNECTED); return false }
        if (!_transmitEnabled.value) { refuse(LoRaSendRefusal.RADIO_MUTED); return false }
        if (_regionRaw.value == LoRaIdentityBridge.REGION_UNSET_RAW) {
            refuse(LoRaSendRefusal.NO_REGION); return false
        }
        // Unicast when we know where the peer is, broadcast when we do not.
        // Unicast is what earns an ACK, and it is also cheaper on shared
        // airtime than flooding every node on the channel.
        // MERGE: resolved BEFORE the OMP split so both the OSHI Mesh firmware
        // path and the stock-radio OMP path address the same resolved node.
        val target = to
            ?: recipientPublicKey.takeIf { it.isNotEmpty() }
                ?.let { LoRaIdentityBridge.nodeFor(it) }
            ?: BROADCAST_ADDR
        if (_firmwareFamily.value == LoRaFirmwareFamily.OSHI) return sendViaOmp(json, messageId, target)
        // Stock radio: the phone does OMP itself — the same DATA frames an OSHI Mesh radio would send, with
        // our node number as origin, on the OSHI channel. Stock relays carry them opaque, OSHI Mesh nodes hand
        // them whole to their phone, and the MeshCore bridge recognises them (it ignores "OM").
        val msgId = Random.nextInt(1, Int.MAX_VALUE).toUInt()
        val body = OshiMeshProtocol.encodeBody(json)
        val frames = OshiMeshProtocol.dataFrames(msgId, target, body, origin = _myNodeNum.value)
        // ~2 KB cap: the radio's TX queue is shallow and LongFast airtime is ~1-2 s/frame. :514-519
        if (frames == null || frames.size > LoRaEnvelope.MAX_CHUNKS) {
            refuse(LoRaSendRefusal.TOO_LARGE, body.size)
            return false
        }

        // Only meaningful when UNICAST: Meshtastic does not acknowledge
        // broadcasts, so asking on one sets a flag nobody will ever answer.
        // :526-534
        val wantAck = target != BROADCAST_ADDR
        if (wantAck) log("→", "unicast to ${LoRaPacketParser.nodeIdString(target)} (ack requested)")
        frames.forEachIndexed { i, frame ->
            // MERGE: production's resolved unicast target + ours' OSHI channel slot.
            val packetId = enqueuePacket(target, LoRaPacketParser.PORT_OSHI, frame, wantAck, oshiChannelIndex ?: 0)
            // Track the LAST frame only. The mesh acks per packet and a
            // multi-frame message is delivered when its final frame lands;
            // tracking every frame would report "delivered" on the first one.
            // :538-542
            if (wantAck && i == frames.size - 1) trackPendingAck(packetId, messageId)
        }
        log("→", "OSHI msg ${messageId.take(8)} as OMP via stock radio (${frames.size} LoRa frame${if (frames.size > 1) "s" else ""}, ${body.size}B)")
        clearRefusal()   // frames are on the wire: whatever was wrong no longer is
        return true
    }

    /** :765-783. Returns the packet id used, so a caller can match the ACK back. */
    private fun enqueuePacket(to: UInt, portnum: Long, payload: ByteArray, wantAck: Boolean,
                              channel: Int = 0, wantResponse: Boolean = false): UInt {
        val packetId = Random.nextInt(1, Int.MAX_VALUE).toUInt()
        val frame = LoRaPacketParser.toRadioPacket(to, portnum, payload, wantAck, packetId, channel, wantResponse)
        sendQueue.trySend(frame)
        startPump()
        return packetId
    }

    /**
     * Space writes ~2 s apart — matches LongFast airtime so the radio's shallow TX
     * queue never overflows on multi-chunk messages. :818-828
     */
    private fun startPump() {
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch {
            while (true) {
                val frame = sendQueue.receive()
                val tr = toRadioChar ?: continue
                gattWrite(tr, frame)
                delay(SEND_SPACING_MS)
            }
        }
    }

    // ─────────────────────────── Node configuration ───────────────────────────

    /**
     * Set the node's regulatory region, and enable transmission.
     *
     * THIS IS THE STEP THAT MAKES A NEW NODE USABLE. Straight from the flasher a
     * node boots with region=UNSET and will not put anything on the air:
     *
     *     INFO | Wanted region 0, using UNSET
     *
     * It pairs, it reports connected, and it silently transmits nothing. Until
     * this is written the node cannot send or receive a single message. :577-631
     */
    fun setRegion(
        region: LoRaRegion,
        preset: LoRaModemPreset = LoRaModemPreset.LONG_FAST,
        // 7, not 8: hop_limit is a 3-bit field on the wire. Meshtastic's
        // config.proto says it outright — "Attempting to set a value > 7
        // results in the default" (3) — so 8 would silently UNDO this change.
        hopLimit: Long = 7L,
    ): Boolean {
        if (!isReady || _myNodeNum.value == 0u) {
            _lastError.value = LoRaSendRefusal.NOT_CONNECTED.stringKey
            return false
        }
        // :602 — refuse to write UNSET back; that is the state we exist to leave.
        if (LoRaIdentityBridge.regionRaw(region) == LoRaIdentityBridge.REGION_UNSET_RAW) return false
        val admin = LoRaPacketParser.adminSetLoRaConfig(
            regionRaw = LoRaIdentityBridge.regionRaw(region),
            presetRaw = preset.rawValue.toLong(),
            hopLimit = hopLimit,
        )
        // Addressed to OURSELVES: this configures the node on the other end of the
        // BLE link, not a peer on the mesh. :624-627
        enqueuePacket(_myNodeNum.value, LoRaPacketParser.PORT_ADMIN, admin, wantAck = true)
        log("→", "set region ${region.displayName}")
        return true
    }

    /**
     * Join the public Meshtastic mesh used by the Chilean community.
     *
     * This is intentionally an explicit action, not something performed from
     * the phone locale: it replaces the PRIMARY channel and therefore moves a
     * node off any private primary mesh its owner previously chose. The two
     * writes share the radio queue, so the LoRa configuration is sent before
     * the Channel update.
     */
    fun setChileMeshDefaults(): Boolean {
        if (!setRegion(LoRaRegion.ANZ, LoRaModemPreset.LONG_FAST)) return false
        val publicLongFast = LoRaPacketParser.adminSetChannel(
            index = 0,
            name = "LongFast",
            psk = byteArrayOf(1), // Meshtastic's documented AQ== shorthand.
            role = 1,             // PRIMARY
        )
        enqueuePacket(_myNodeNum.value, LoRaPacketParser.PORT_ADMIN, publicLongFast, wantAck = true)
        log("→", "set Chile community defaults (ANZ, LongFast)")
        return true
    }

    /**
     * `AdminMessage.set_owner` carrying a User. short_name is what fits on the
     * node's tiny display and in other clients' lists — 4 characters. :702-725
     */
    fun setOwner(longName: String, shortName: String): Boolean {
        if (!isReady || _myNodeNum.value == 0u) {
            _lastError.value = LoRaSendRefusal.NOT_CONNECTED.stringKey
            return false
        }
        val admin = LoRaPacketParser.adminSetOwner(_myNodeNum.value, longName, shortName)
        enqueuePacket(_myNodeNum.value, LoRaPacketParser.PORT_ADMIN, admin, wantAck = true)
        log("→", "renamed node to $longName")
        return true
    }

    /**
     * Write the node's Bluetooth pairing mode. Takes effect on the node's next
     * advertisement; a node already bonded to this phone stays bonded. :727-761
     */
    fun setBluetoothPairing(mode: LoRaBluetoothPairing): Boolean {
        if (!isReady || _myNodeNum.value == 0u) {
            _lastError.value = LoRaSendRefusal.NOT_CONNECTED.stringKey
            return false
        }
        val admin = LoRaPacketParser.adminSetBluetoothConfig(mode.rawValue.toLong())
        enqueuePacket(_myNodeNum.value, LoRaPacketParser.PORT_ADMIN, admin, wantAck = true)
        _pairingMode.value = mode
        log("→", "set pairing $mode")
        return true
    }

    /**
     * Put the node on a channel — the "which mesh am I on" setting.
     *
     * Addressed to OURSELVES like every other config write: this configures the
     * node on the other end of the BLE link, not a peer on the mesh.
     *
     * A blank [name] with [LoRaChannel.DEFAULT_PSK] is the stock LongFast
     * channel, so "put it back the way the Meshtastic app ships" is reachable
     * from the same button. The name is clamped in the packet builder, which is
     * the only place that knows the firmware's 11-byte field.
     */
    fun setChannel(name: String, psk: ByteArray = LoRaChannel.DEFAULT_PSK): Boolean {
        if (!isReady || _myNodeNum.value == 0u) {
            _lastError.value = LoRaSendRefusal.NOT_CONNECTED.stringKey
            return false
        }
        val clamped = LoRaChannel.clampName(name)
        val admin = LoRaPacketParser.adminSetChannel(clamped, psk)
        enqueuePacket(_myNodeNum.value, LoRaPacketParser.PORT_ADMIN, admin, wantAck = true)
        prefs.edit().putString(CHANNEL_NAME_KEY, clamped).apply()
        _channelName.value = clamped
        log("→", "set channel ${LoRaChannel.displayName(clamped)}")
        return true
    }

    /**
     * One channel slot reported by the node during the `wantConfig` handshake.
     *
     * Replaces by INDEX rather than appending: the node re-sends all eight slots
     * on every handshake, and appending would grow the list without bound and
     * show the same channel four times after four reconnects.
     *
     * DISABLED slots are dropped here rather than in the UI, because a node
     * reports seven of them and every consumer would otherwise have to remember
     * to filter — the kind of thing one of them eventually forgets.
     */
    private fun handleNodeChannel(channel: LoRaPacketParser.NodeChannel) {
        val next = _nodeChannels.value.filter { it.index != channel.index }
            .let { if (channel.isDisabled) it else it + channel }
            .sortedBy { it.index }
        // Compared by CONTENT (NodeChannel overrides equals), so an unchanged
        // handshake does not re-emit and re-render the list.
        if (next != _nodeChannels.value) _nodeChannels.value = next
    }

    /**
     * Join everything in a shared `meshtastic.org/e/#` link: primary into slot 0,
     * secondaries into 1..n, and every slot beyond that DISABLED.
     *
     * Disabling the tail is the part that is easy to leave out and wrong to:
     * joining a link with two channels onto a node that had five would otherwise
     * leave three stale channels behind, still receiving, with nothing in the UI
     * explaining where they came from.
     *
     * The radio half of the link is applied ONLY when complete — see
     * [LoRaChannelSet.Radio]. A partial LoRaConfig arrives at the node as zeros
     * and takes it off the air.
     *
     * @return false when there is no node to write to, or the link is empty.
     */
    fun joinChannelSet(parsed: LoRaChannelSet.Parsed): Boolean {
        if (!isReady || _myNodeNum.value == 0u) {
            _lastError.value = LoRaSendRefusal.NOT_CONNECTED.stringKey
            return false
        }
        val channels = parsed.channels.take(LoRaChannelSet.MAX_CHANNELS)
        if (channels.isEmpty()) return false

        channels.forEachIndexed { index, channel ->
            val admin = LoRaPacketParser.adminSetChannel(
                name = channel.name,
                psk = channel.psk,
                index = index,
                roleRaw = if (index == 0) LoRaChannelSet.ROLE_PRIMARY
                          else LoRaChannelSet.ROLE_SECONDARY,
            )
            enqueuePacket(_myNodeNum.value, LoRaPacketParser.PORT_ADMIN, admin, wantAck = true)
        }
        for (index in channels.size until LoRaChannelSet.MAX_CHANNELS) {
            enqueuePacket(
                _myNodeNum.value, LoRaPacketParser.PORT_ADMIN,
                LoRaPacketParser.adminDisableChannel(index), wantAck = true,
            )
        }

        // Mirror the primary into the same prefs `setChannel` writes, so the
        // setup screen shows the channel we just joined rather than the one the
        // user typed last.
        val primary = LoRaChannel.clampName(channels.first().name)
        prefs.edit().putString(CHANNEL_NAME_KEY, primary).apply()
        _channelName.value = primary

        parsed.radio?.takeIf { it.isComplete }?.let { r ->
            enqueuePacket(
                _myNodeNum.value, LoRaPacketParser.PORT_ADMIN,
                LoRaPacketParser.adminSetLoRaConfig(
                    regionRaw = r.regionRaw!!, presetRaw = r.presetRaw!!, hopLimit = r.hopLimit!!,
                ),
                wantAck = true,
            )
        }
        log("→", "joined ${channels.size} channel(s), primary ${LoRaChannel.displayName(primary)}")
        return true
    }

    /**
     * A shareable link for what the NODE currently reports, falling back to the
     * primary we last wrote when the node has not finished its handshake.
     *
     * Null when there is nothing to share, so the UI can hide the button instead
     * of producing a link to an empty mesh.
     *
     * The radio half is deliberately omitted: we do not read back modem_preset or
     * hop_limit from the node (only `region`, in `parseConfig`), so including a
     * partial LoRaConfig would hand everyone who scanned it a radio configured to
     * zeros. A channel-only link is correct and safe.
     */
    fun shareableChannelUrl(): String? {
        val fromNode = _nodeChannels.value
            .sortedBy { it.index }
            .map { LoRaChannelSet.Channel(name = it.name, psk = it.psk) }
        val channels = fromNode.ifEmpty {
            listOf(LoRaChannelSet.Channel(
                name = _channelName.value,
                psk = LoRaChannel.DEFAULT_PSK,
            ))
        }
        return runCatching { LoRaChannelSet.buildUrl(channels) }.getOrNull()
    }

    /**
     * A node straight from the flasher calls itself "Meshtastic 23d8". Only
     * renames one still wearing the FACTORY default — a name the owner chose is
     * theirs, and they may well use the node with other clients too. :659-700
     */
    fun renameIfFactoryDefault(): Boolean {
        if (!isReady || _myNodeNum.value == 0u) return false
        val mine = _nodes.value.firstOrNull { it.num == _myNodeNum.value } ?: return false
        val current = mine.longName
        // Also adopt nodes wearing the PREVIOUS OSHI name, so anyone who paired
        // before the rename picks up the new form instead of being stuck. :691-694
        val wasOldOshiName = current.startsWith("OSHI_Messenger_")
        if (!FACTORY_NAME.matches(current) && !wasOldOshiName) return false
        val hex = (_myNodeNum.value and 0xFFFFu).toString(16).uppercase().padStart(4, '0')
        return setOwner(adoptedName(_myNodeNum.value), hex)
    }

    // ─────────────────────────── Receive ───────────────────────────

    /** :837-861 */
    private fun handleFromRadio(d: ByteArray) {
        for (event in LoRaPacketParser.parseFromRadio(d)) {
            when (event) {
                is LoRaPacketParser.FromRadio.Packet -> handleMeshPacket(event.packet)
                is LoRaPacketParser.FromRadio.MyNodeInfo -> _myNodeNum.value = event.myNodeNum
                is LoRaPacketParser.FromRadio.NodeInfoUpdate -> handleNodeInfo(event.info)
                is LoRaPacketParser.FromRadio.ConfigUpdate -> handleConfig(event.config)
                // MERGE: one field-10 event feeds both the user-visible channel
                // list (production) and the OSHI-channel slot tracking (OMP).
                is LoRaPacketParser.FromRadio.ChannelUpdate -> {
                    handleNodeChannel(event.channel)
                    handleChannel(event.channel)
                }
                is LoRaPacketParser.FromRadio.ConfigComplete -> {
                    if (event.configId != wantConfigId) continue
                    _state.value = ConnState.READY
                    // The handshake completed: NOW this radio is genuinely paired. :850-854
                    pendingPairId?.let {
                        prefs.edit().putString(PAIRED_KEY, it).apply()
                        pendingPairId = null
                    }
                    renameIfFactoryDefault()   // "Meshtastic 23d8" -> "OSHI Mesh 23D8"
                    startJammingWatch()        // fresh baseline for this radio and this link
                    startOshiMeshSetup()       // OSHI Mesh firmware? else give a stock radio the OSHI channel
                    // __LORA_PROBE_BEFORE_FLUSH_2026_11_18__ NOT flushed here any more: the probe has not
                    // been answered, so everything held would take the 12-frame stock path. The probe
                    // answer (handleOmp BEACON) or its 6 s timeout (ensureOshiChannel) flushes instead.
                    refreshStatus()
                }
            }
        }
        refreshStatus()
    }

    /**
     * :870-899. Parsed so the UI can tell "this node is set up" from "this node is
     * connected but will never transmit". Without reading it back we would be
     * TRUSTING that our write landed, and the one state that must never be
     * assumed is the one that silently drops every message.
     */
    private fun handleConfig(c: LoRaPacketParser.NodeConfig) {
        c.regionRaw?.let {
            _regionRaw.value = it
            _region.value = LoRaIdentityBridge.regionFromRaw(it)
        }
        c.txEnabled?.let { _transmitEnabled.value = it }
        c.bluetoothPairingRaw?.let {
            _pairingMode.value = LoRaBluetoothPairing.fromRaw(it.toInt())
                ?: LoRaBluetoothPairing.RANDOM_PIN
        }
    }

    /** :901-950 — MERGE, never replace. */
    private fun handleNodeInfo(info: LoRaPacketParser.NodeInfo) {
        val now = System.currentTimeMillis()
        val list = _nodes.value.toMutableList()
        val idx = list.indexOfFirst { it.num == info.num }
        val base = if (idx >= 0) list[idx] else LoRaMeshNode(num = info.num)
        // A NodeInfo that carries no Position must not ERASE a position we already
        // learned — most updates omit it. :935-947
        val merged = base.copy(
            userId = info.userId.ifEmpty { base.userId },
            longName = info.longName.ifEmpty { base.longName },
            shortName = info.shortName.ifEmpty { base.shortName },
            // __LORA_ONLINE_COUNT_2026_09_22__ The node DB is dumped IN FULL at every
            // connect, so stamping `now` made every node the radio ever stored look
            // "heard just now". Use the radio's own last_heard when it has one, and
            // never move a node we already heard directly back in time.
            lastHeard = info.lastHeardMillis?.let { reported ->
                val clamped = minOf(now, reported)
                if (idx >= 0) maxOf(base.lastHeard, clamped) else clamped
            } ?: now,
            snr = if (info.snr != 0f) info.snr else base.snr,
            latitude = if (info.latitude != null && info.longitude != null &&
                !(info.latitude == 0.0 && info.longitude == 0.0)
            ) info.latitude else base.latitude,
            longitude = if (info.latitude != null && info.longitude != null &&
                !(info.latitude == 0.0 && info.longitude == 0.0)
            ) info.longitude else base.longitude,
            hopsAway = info.hopsAway ?: base.hopsAway,
        )
        if (idx >= 0) list[idx] = merged else list.add(merged)
        _nodes.value = list
    }

    /** :952-997 */
    private fun handleMeshPacket(p: LoRaPacketParser.MeshPacket) {
        // Our own radio speaks OSHI Mesh to us under its own node number: the probe answer, delivery
        // statuses, and mail it pulled as a gateway. Any other packet "from" ourselves is an echo.
        if (p.from == _myNodeNum.value) {
            when (p.portnum) {
                LoRaPacketParser.PORT_OSHI ->
                    if (OshiMeshProtocol.typeOf(p.payload) != null) handleOmp(p.payload, p.from)
                // __ANTI_JAMMING_PHASE1_2026_11_13__ Our radio's own telemetry, and the routing verdicts it
                // generates itself (MAX_RETRANSMIT, or the implicit ack from overhearing a relay) - both
                // were dropped here as "echoes" before, which hid every failed unicast.
                LoRaPacketParser.PORT_TELEMETRY -> handleOwnTelemetry(p.payload)
                LoRaPacketParser.PORT_ROUTING -> handleOwnRouting(p.payload, p.requestId)
            }
            return
        }

        // Another node's packet reached us decoded: the channel carries traffic. OMP DATA from an OSHI Mesh
        // radio may be a REPLAY from its flash inbox, not something heard now, so it does not count.
        if (!(p.portnum == LoRaPacketParser.PORT_OSHI && _firmwareFamily.value == LoRaFirmwareFamily.OSHI)) {
            jam { onValidPacket(System.currentTimeMillis()) }
        }

        // Signal is measured per PACKET, not per NodeInfo — this is the only place
        // it is available, and it is what lets us estimate a distance for the many
        // nodes with no GPS. :966-973
        val list = _nodes.value.toMutableList()
        val idx = list.indexOfFirst { it.num == p.from }
        if (idx >= 0) {
            list[idx] = list[idx].copy(
                rssi = p.rssi ?: list[idx].rssi,
                snr = if (p.snr != null && p.snr != 0f) p.snr else list[idx].snr,
                lastHeard = System.currentTimeMillis(),
            )
            _nodes.value = list
        }

        when (p.portnum) {
            LoRaPacketParser.PORT_TEXT -> handleInteropText(p)
            // "OS" = OSHI Mesh firmware (OMP); anything else is the older "OM" chunking.
            LoRaPacketParser.PORT_OSHI ->
                if (OshiMeshProtocol.typeOf(p.payload) != null) handleOmp(p.payload, p.from)
                else handleOshiChunk(p.payload, p.from)
            LoRaPacketParser.PORT_ROUTING -> handleRouting(p.payload, p.requestId)
            LoRaPacketParser.PORT_POSITION -> handlePosition(p.payload, p.from)
        }
    }

    /**
     * __LORA_POSITION_BEACON_2026_10_28__ POSITION_APP — a node saying where it is.
     *
     * This switch had NO case for portnum 3, so positions only ever arrived in the node
     * DATABASE dump at connect time and a node that moved, or appeared, after that was
     * invisible until the next reconnect. The same hole existed on iOS (its own comment
     * records it) and on the desktop, where it was fixed today; on the air it is the most
     * common packet there is — a single capture on this mesh held 135 of them.
     *
     * `from` is the ORIGINAL sender in mesh.proto and survives relaying, so this is the
     * node that owns the GPS, not the last hop that forwarded it.
     *
     * A 0/0 position is DISCARDED, not stored: that is what a node with no fix reports,
     * and keeping it moves a node that HAS a fix into the Gulf of Guinea. The bounds check
     * is the second half of the same guard — a corrupt frame must not put a node off the
     * planet.
     */
    private fun handlePosition(payload: ByteArray, from: UInt) {
        if (from == 0u) return
        var lat: Double? = null
        var lon: Double? = null
        for (pf in LoRaProto.parseFields(payload)) {
            // sfixed32 scaled by 1e-7, read SIGNED — unsigned puts everything south of
            // the equator or west of Greenwich near the north pole.
            when {
                pf.number == 1 && pf.wire == 5 -> lat = pf.asInt32() * 1e-7
                pf.number == 2 && pf.wire == 5 -> lon = pf.asInt32() * 1e-7
            }
        }
        val la = lat ?: return
        val lo = lon ?: return
        if (la == 0.0 && lo == 0.0) return
        if (la < -90 || la > 90 || lo < -180 || lo > 180) return

        val list = _nodes.value.toMutableList()
        val idx = list.indexOfFirst { it.num == from }
        if (idx >= 0) {
            list[idx] = list[idx].copy(latitude = la, longitude = lo,
                lastHeard = System.currentTimeMillis())
        } else {
            list.add(LoRaMeshNode(num = from, latitude = la, longitude = lo,
                lastHeard = System.currentTimeMillis()))
        }
        _nodes.value = list
        log("←", "position %.5f, %.5f from %s".format(la, lo, LoRaPacketParser.nodeIdString(from)))
    }

    /**
     * A ROUTING_APP packet: the mesh telling us what became of something we sent
     * with `want_ack`. Absent or 0 (NONE) means delivered; anything else is a
     * failure the radio is naming. We only care about the distinction, because the
     * answer to every failure here is the same: the peer did not get it. :786-808
     */
    private fun handleRouting(payload: ByteArray, requestId: UInt) {
        val reason = LoRaPacketParser.routingErrorReason(payload)
        if (requestId == 0u) return
        val pending = synchronized(loraPendingAck) { loraPendingAck.remove(requestId) } ?: return
        if (reason == 0L || reason in LINK_FAILURE_REASONS) jam { onAckResult(System.currentTimeMillis(), reason == 0L) }
        if (reason == 0L) {
            _loraAcked.value = _loraAcked.value + pending.first
            log("←", "LoRa ACK for ${pending.first.take(8)}")
        } else {
            log("←", "LoRa delivery FAILED for ${pending.first.take(8)} (reason $reason)")
            reportNotDelivered(pending.first)
        }
    }

    /**
     * Record that `messageId` is riding `packetId`. Prunes anything past the
     * window on the way in — a map that only grows is a leak in an app that stays
     * resident for days, and this one is fed by every LoRa send. :810-816
     */
    private fun trackPendingAck(packetId: UInt, messageId: String) {
        expireOverdueAcks()
        synchronized(loraPendingAck) { loraPendingAck[packetId] = messageId to System.currentTimeMillis() }
    }

    /**
     * Sends whose ack never came within [LORA_ACK_WINDOW_MS]. They used to be pruned silently; the radio
     * normally answers MAX_RETRANSMIT well before, so a send that got NO verdict at all - typically a radio
     * that could not get on the air - is now also counted against the link by the jamming detector.
     */
    private fun expireOverdueAcks() {
        val now = System.currentTimeMillis()
        val overdue = synchronized(loraPendingAck) {
            val gone = loraPendingAck.entries.filter { now - it.value.second > LORA_ACK_WINDOW_MS }.map { it.key }
            gone.forEach { loraPendingAck.remove(it) }
            gone.size
        }
        repeat(overdue) { jam { onAckResult(now, false) } }
    }

    // ─────────────────────────── Jamming detection ───────────────────────────

    /** Every detector call goes through here: one lock, and the state published when it changes. */
    private fun jam(op: LoRaJammingDetector.() -> LoRaJammingDetector.Assessment) {
        val (before, after) = synchronized(jammingDetector) {
            val b = jammingDetector.state
            b to jammingDetector.op()
        }
        if (after.state == before) return
        _jamming.value = after.state
        val why = if (after.reasons.isEmpty()) "" else ": " + after.reasons.joinToString(", ")
        log("!", "radio channel ${after.state.name.lowercase().replace('_', ' ')}$why")
        if (jammingOverride.onDetectorState(after.state)) {
            publishJammingOverride()
            log("!", "send-over-LoRa-anyway ended: the channel is clear")
        }
        if (before == LoRaJammingDetector.State.PROBABLE_JAMMING) flushStoreAndForward()
    }

    /**
     * __ANTI_JAMMING_OVERRIDE_2026_11_13__ The person tapped "Send over LoRa anyway" on the jamming warning.
     * Suspends the jamming exclusion for [LoRaJammingOverride.DEFAULT_DURATION_MS] or until the detector
     * returns to CLEAR, and sends what the radio was holding right away. Returns false (and changes
     * nothing) when the detector does not currently say PROBABLE_JAMMING.
     */
    fun sendOverLoRaAnyway(): Boolean {
        val until = jammingOverride.activate(_jamming.value) ?: return false
        publishJammingOverride()
        log("!", "send-over-LoRa-anyway for ${(until - System.currentTimeMillis() + 59_999) / 60_000} min: ${storeAndForward.size} held message(s) going out")
        jammingOverrideExpiryJob?.cancel()
        jammingOverrideExpiryJob = scope.launch {
            delay((until - System.currentTimeMillis()).coerceAtLeast(0L))
            publishJammingOverride()
            if (jammingOverride.activeUntil() == null && isJammed) {
                log("!", "send-over-LoRa-anyway ran out: LoRa left out again while the channel looks jammed")
            }
        }
        flushStoreAndForward()
        return true
    }

    private fun publishJammingOverride() {
        _jammingOverrideUntil.value = jammingOverride.activeUntil()
    }

    private fun endJammingOverride() {
        jammingOverrideExpiryJob?.cancel()
        jammingOverrideExpiryJob = null
        jammingOverride.cancel()
        publishJammingOverride()
    }

    private fun startJammingWatch() {
        synchronized(jammingDetector) { jammingDetector.reset(System.currentTimeMillis()) }
        _jamming.value = LoRaJammingDetector.State.CLEAR
        endJammingOverride()
        lastCounterStatsAt = 0L
        jammingTickJob?.cancel()
        jammingTickJob = scope.launch {
            var ticks = 0
            while (isReady) {
                // The first LocalStats request goes out a few seconds in, then whenever nothing has reported RX
                // counters for a while (an OSHI Mesh radio sends its RADIO frame every 30 s, so it is not polled).
                delay(if (ticks == 0) 5_000L else JAMMING_TICK_MS)
                ticks++
                expireOverdueAcks()
                jam { evaluate(System.currentTimeMillis()) }
                if (System.currentTimeMillis() - lastCounterStatsAt >= LOCAL_STATS_POLL_MS && _myNodeNum.value != 0u) {
                    // Addressed to our own node: answered over Bluetooth, never transmitted.
                    enqueuePacket(_myNodeNum.value, LoRaPacketParser.PORT_TELEMETRY,
                        LoRaPacketParser.localStatsRequest(), wantAck = false, wantResponse = true)
                    lastCounterStatsAt = System.currentTimeMillis() - LOCAL_STATS_POLL_MS + LOCAL_STATS_RETRY_MS
                }
            }
        }
    }

    private fun stopJammingWatch() {
        jammingTickJob?.cancel()
        jammingTickJob = null
        synchronized(jammingDetector) { jammingDetector.reset(System.currentTimeMillis()) }
        _jamming.value = LoRaJammingDetector.State.CLEAR
        endJammingOverride()
    }

    private fun handleOwnTelemetry(payload: ByteArray) {
        val t = LoRaPacketParser.parseTelemetry(payload) ?: return
        val now = System.currentTimeMillis()
        if (t.isLocalStats) lastCounterStatsAt = now
        jam {
            onStats(LoRaJammingDetector.Sample(now, t.channelUtilPct, t.airUtilTxPct, t.noiseFloorDbm, t.rxTotal, t.rxBad))
        }
    }

    /** Verdicts our own radio generates about our unicasts. Only a failure settles the pending send. */
    private fun handleOwnRouting(payload: ByteArray, requestId: UInt) {
        if (requestId == 0u) return
        val reason = LoRaPacketParser.routingErrorReason(payload)
        val known = synchronized(loraPendingAck) { loraPendingAck.containsKey(requestId) }
        if (!known) return
        when {
            // An implicit ack: a relay rebroadcast our packet. The link works; delivery is still unproven,
            // so the send stays pending for the destination's own ack.
            reason == 0L -> jam { onAckResult(System.currentTimeMillis(), true) }
            reason in LINK_FAILURE_REASONS -> {
                val pending = synchronized(loraPendingAck) { loraPendingAck.remove(requestId) }
                jam { onAckResult(System.currentTimeMillis(), false) }
                pending?.let {
                    log("←", "LoRa delivery FAILED for ${it.first.take(8)} (reason $reason, no ack after retries)")
                    reportNotDelivered(it.first)
                }
            }
        }
    }

    /**
     * Plain text from ANY Meshtastic client. It carries NO identity — a node
     * number and a string, nothing else — so the conversation it lands in is
     * decided by `LoRaNodeIdentityMap`, never guessed here. :999-1056
     */
    private fun handleInteropText(p: LoRaPacketParser.MeshPacket) {
        val from = p.from
        val text = runCatching { String(p.payload, Charsets.UTF_8) }.getOrNull().orEmpty()
        if (text.isEmpty()) return
        val boundKey = LoRaIdentityBridge.boundKey(from)
        val senderKey = LoRaIdentityBridge.conversationKey(from)
        val fallback = LoRaIdentityBridge.fallbackConversationKey(from)
        // A known identity outranks the radio's own name: the contact's alias is
        // what the rest of the app shows for this conversation, and two different
        // names for one thread reads as two people. :1025-1033
        val display = if (boundKey != null) boundKey else {
            _nodes.value.firstOrNull { it.num == from }?.let {
                if (it.longName.isNotEmpty()) it.longName
                else if (it.shortName.isNotEmpty()) it.shortName else fallback
            } ?: fallback
        }
        val where = if (p.isBroadcast) "channel ${p.channelIndex}" else "direct"
        log("←", "text from $display${if (boundKey != null) " (known node)" else ""} [$where]: ${text.take(32)}")
        _receivedText.tryEmit(
            LoRaInteropText(
                text, from, senderKey, display, boundKey != null,
                // __A_CHANNEL_BROADCAST_IS_NOT_A_PRIVATE_MESSAGE_2026_11_04__
                isBroadcast = p.isBroadcast,
                channelIndex = p.channelIndex,
                // __LORA_CHANNEL_IS_A_ROOM_2026_11_04__ Only this layer holds the
                // node's channel table, and the index is meaningless without it.
                channelName = _nodeChannels.value
                    .firstOrNull { it.index == p.channelIndex }?.name.orEmpty(),
            )
        )
    }

    /**
     * OSHI envelope chunk ("OM" frames) → reassemble → the same pipeline as every
     * other mesh message. If the envelope is not addressed to us the message layer
     * drops it (decryption fails) — E2E addressing for free. :1058-1109
     */
    private fun handleOshiChunk(payload: ByteArray, from: UInt) {
        val full = reassembler.accept(payload, System.currentTimeMillis()) ?: return
        deliverEnvelope(full, from, (full.size + LORA_CHUNK - 1) / LORA_CHUNK)
    }

    /**
     * An OSHI envelope a MeshCore radio carried (MeshCoreManager): the same recipient filter and the same
     * pipeline, without binding its MeshCore-derived [origin] as a Meshtastic node number.
     */
    internal fun deliverForeignEnvelope(json: ByteArray, origin: UInt, frames: Int) =
        deliverEnvelope(json, origin, frames, bindsNode = false, via = "MeshCore")

    /** A reassembled OSHI envelope, from either framing, into the normal mesh pipeline. */
    private fun deliverEnvelope(full: ByteArray, from: UInt, frames: Int, bindsNode: Boolean = true, via: String = "LoRa") {
        val msg = LoRaWireMessage.fromWireJson(full) ?: run {
            log("←", "OSHI envelope reassembled but not decodable (${full.size}B)")   // :1080-1083
            return
        }
        // Not for us? Drop silently — broadcast fans out to everyone by design.
        // Normalized comparison: base64url/padding skew must not drop a legitimate
        // recipient. :1090-1096
        val myKey = myPublicKeyProvider?.invoke()
        if (!myKey.isNullOrEmpty() &&
            normalizeKey(msg.recipientPublicKey) != normalizeKey(myKey)
        ) return

        log("←", "OSHI msg ${msg.id.take(8)} via $via ($frames frames)")
        // These bytes came off our own radio, so we KNOW the transport — the
        // sender's stripped field cannot tell us and must not be trusted to. The
        // node→identity binding is deliberately NOT made here: at this point
        // `senderPublicKey` is only a CLAIM, and a claim is exactly what an
        // attacker would forge to have their raw text filed under a contact's
        // name. The pipeline binds it once the envelope has DECRYPTED. :1084-1104
        _receivedMessages.tryEmit(
            LoRaReceivedMessage(
                message = msg.copy(deliveryMethod = LoRaWireMessage.METHOD_LORA),
                nodeNum = from,
                frames = frames,
                bindsNode = bindsNode,
            )
        )
    }

    // ─────────────────────────── OSHI Mesh firmware (OshiMeshProtocol.kt) ───────────────────────────

    private fun handleChannel(c: LoRaPacketParser.NodeChannel) {
        channelSlots[c.index] = c.roleRaw to c.name
        if (c.roleRaw != 0L && c.name == OshiMeshProtocol.CHANNEL_NAME) oshiChannelIndex = c.index
        // MERGE: production's joinChannelSet DISABLES every trailing slot, the OSHI one
        // included; keeping a stale index would send OMP on a slot that no longer exists.
        else if (oshiChannelIndex == c.index) oshiChannelIndex = null
    }

    /** Probe for OSHI Mesh firmware; a radio that stays silent is stock and gets the OSHI channel instead. */
    private fun startOshiMeshSetup() {
        _firmwareFamily.value = LoRaFirmwareFamily.MESHTASTIC
        _firmwareProbeDone.value = false
        enqueuePacket(_myNodeNum.value, LoRaPacketParser.PORT_OSHI, OshiMeshProtocol.beaconProbe(), wantAck = false)
        scope.launch {
            delay(6_000)
            ensureOshiChannel()
        }
    }

    /**
     * Stock firmware: OSHI frames on the default channel die at every Meshtastic ROUTER
     * (CORE_PORTNUMS_ONLY drops PRIVATE_APP it can decode); on a private secondary channel the same
     * ROUTERs relay them untouched. Same frequency: the slot follows the PRIMARY channel's name.
     */
    private fun ensureOshiChannel() {
        _firmwareProbeDone.value = true
        try {
            configureStockOshiChannel()
        } finally {
            // __LORA_PROBE_BEFORE_FLUSH_2026_11_18__ The probe had its chance: a radio that did not
            // answer is stock (forget any OSHI Mesh memory of it), and what was held goes out now —
            // after the OSHI channel slot above is set, so stock-path frames use it.
            if (isReady && _firmwareFamily.value == LoRaFirmwareFamily.MESHTASTIC) rememberOshiFirmware(false)
            flushStoreAndForward()
        }
    }

    /** __LORA_LONG_TEXT_OMP_2026_11_18__ See [lastKnownOshiFirmware]. */
    private fun rememberOshiFirmware(oshi: Boolean) {
        val paired = prefs.getString(PAIRED_KEY, null)
        if (oshi && paired != null) prefs.edit().putString(OSHI_FW_RADIO_KEY, paired).apply()
        else if (!oshi) prefs.edit().remove(OSHI_FW_RADIO_KEY).apply()
    }

    private fun configureStockOshiChannel() {
        if (_firmwareFamily.value != LoRaFirmwareFamily.MESHTASTIC || !isReady || _myNodeNum.value == 0u) return
        channelSlots.entries.firstOrNull { it.value.first != 0L && it.value.second == OshiMeshProtocol.CHANNEL_NAME }
            ?.let { oshiChannelIndex = it.key; return }
        val free = channelSlots.entries.filter { it.key > 0 && it.value.first == 0L }.minOfOrNull { it.key }
        if (free == null) {
            log("!", "no free channel slot for OSHI; LoRa stays on the primary channel")
            return
        }
        val admin = LoRaPacketParser.adminSetChannel(free, OshiMeshProtocol.CHANNEL_NAME, OshiMeshProtocol.CHANNEL_PSK)
        enqueuePacket(_myNodeNum.value, LoRaPacketParser.PORT_ADMIN, admin, wantAck = true)
        channelSlots[free] = 2L to OshiMeshProtocol.CHANNEL_NAME
        oshiChannelIndex = free
        log("→", "added the OSHI channel at slot $free")
    }

    /** OSHI Mesh firmware takes the whole message and owns delivery: repair, custody, receipts. */
    private fun sendViaOmp(json: ByteArray, messageId: String, to: UInt): Boolean {
        val body = OshiMeshProtocol.encodeBody(json)
        val msgId = Random.nextInt(1, Int.MAX_VALUE).toUInt()
        // Custody only makes sense for a named destination; a broadcast has no one to wait for.
        val flags = if (to == BROADCAST_ADDR) 0 else OshiMeshProtocol.FLAG_CUSTODY_OK
        val frames = OshiMeshProtocol.dataFrames(msgId, to, body, flags)
        if (frames == null) {
            refuse(LoRaSendRefusal.TOO_LARGE, body.size)
            return false
        }
        synchronized(ompOutgoing) {
            if (ompOutgoing.size > 256) ompOutgoing.clear()
            ompOutgoing[msgId] = messageId
        }
        frames.forEach { enqueuePacket(_myNodeNum.value, LoRaPacketParser.PORT_OSHI, it, wantAck = false) }
        sendTimeout.onHandover(messageId, frames.size, broadcast = to == BROADCAST_ADDR, nowMs = System.currentTimeMillis())
        startSendTimeoutTick()
        log("→", "OSHI msg ${messageId.take(8)} to OSHI Mesh radio (${frames.size} frame${if (frames.size > 1) "s" else ""}, ${body.size}B)")
        clearRefusal()
        return true
    }

    /**
     * Repeat one OMP DATA frame heard on another network (MeshCore) onto this mesh, for [LoRaCrossBridge].
     * Stock radio: the frame goes out as is, origin and all, broadcast on the OSHI channel. OSHI Mesh radio:
     * it is handed over like the phone's own frames; the firmware reassembles the message and sends it under
     * its own node number (the msgId survives, which is what receivers and the bridge dedupe on).
     */
    fun relayOmpFrame(frame: ByteArray) {
        // __ANTI_JAMMING_OVERRIDE_PARITY_2026_11_18__ [isJammed], not the detector's raw verdict: the
        // person's "Send over LoRa anyway" lifts the exclusion for bridged frames too, as on iOS
        // (`relayOMPFrame` guards on `excludesLoRaForJamming`).
        if (!isReady || !_transmitEnabled.value || isJammed) return
        if (_firmwareFamily.value == LoRaFirmwareFamily.OSHI) {
            enqueuePacket(_myNodeNum.value, LoRaPacketParser.PORT_OSHI, frame, wantAck = false)
        } else {
            enqueuePacket(BROADCAST_ADDR, LoRaPacketParser.PORT_OSHI, frame, wantAck = false, channel = oshiChannelIndex ?: 0)
        }
    }

    private fun handleOmp(payload: ByteArray, from: UInt) {
        when (OshiMeshProtocol.typeOf(payload)) {
            OshiMeshProtocol.FrameType.BEACON -> {
                // Only our own radio's answer to the probe reaches us; peers' beacons stay in the firmware.
                if (from != _myNodeNum.value || OshiMeshProtocol.parseBeacon(payload) == null) return
                if (_firmwareFamily.value != LoRaFirmwareFamily.OSHI) {
                    _firmwareFamily.value = LoRaFirmwareFamily.OSHI
                    rememberOshiFirmware(true)
                    log("←", "radio runs OSHI Mesh firmware")
                    // __LORA_PROBE_BEFORE_FLUSH_2026_11_18__ Held messages take the OMP path now.
                    flushStoreAndForward()
                }
            }
            OshiMeshProtocol.FrameType.STATUS -> {
                val st = OshiMeshProtocol.parseStatus(payload) ?: return
                val id = synchronized(ompOutgoing) { ompOutgoing[st.msgId] } ?: return
                val node = LoRaPacketParser.nodeIdString(st.node)
                // __LORA_AIRTIME_HINT_2026_11_18__ QUEUED starts the clock; any later state stops it.
                if (st.state == OshiMeshProtocol.State.QUEUED) {
                    airtimeWait.onQueued(id, System.currentTimeMillis())
                    startAirtimeTick()
                } else {
                    airtimeWait.onProgress(id)
                }
                publishAirtimeWait()
                sendTimeout.onStatus(id, st.state, System.currentTimeMillis())
                when (st.state) {
                    OshiMeshProtocol.State.DELIVERED -> {
                        _loraInCustody.value = _loraInCustody.value - id
                        _loraAcked.value = _loraAcked.value + id
                        synchronized(ompOutgoing) { ompOutgoing.remove(st.msgId) }
                        log("←", "OSHI msg ${id.take(8)} delivered by $node")
                    }
                    OshiMeshProtocol.State.IN_CUSTODY -> {
                        _loraInCustody.value = _loraInCustody.value + id
                        log("←", "OSHI msg ${id.take(8)} held by $node until the destination is back")
                    }
                    OshiMeshProtocol.State.FAILED, OshiMeshProtocol.State.REJECTED -> {
                        _loraInCustody.value = _loraInCustody.value - id
                        synchronized(ompOutgoing) { ompOutgoing.remove(st.msgId) }
                        log("←", "OSHI msg ${id.take(8)} ${if (st.state == OshiMeshProtocol.State.FAILED) "expired undelivered" else "refused by the radio"}")
                        // __LORA_SEND_FAILED_VISIBLE_2026_11_23__ Was log-only: the bubble stayed "sent".
                        reportNotDelivered(id)
                    }
                    else -> Unit
                }
            }
            OshiMeshProtocol.FrameType.RADIO -> {
                if (from != _myNodeNum.value) return
                val r = OshiMeshProtocol.parseRadio(payload) ?: return
                val now = System.currentTimeMillis()
                lastCounterStatsAt = now
                jam {
                    onStats(LoRaJammingDetector.Sample(now, r.channelUtilPct.toFloat(), r.airUtilTxPct.toFloat(),
                        r.noiseFloorDbm, r.rxGood + r.rxBad, r.rxBad))
                }
            }
            OshiMeshProtocol.FrameType.DATA -> {
                val f = OshiMeshProtocol.parseData(payload) ?: return
                // Holding a MeshCore radio too? Every OSHI frame heard here is repeated there (LoRaCrossBridge).
                LoRaCrossBridge.heardOnMeshtastic(payload, f)
                val body = ompRx.accept(f) ?: return
                // 'D' (0x44) is an internet message a gateway pulled for us; the v2 pipeline consumes it.
                val json = OshiMeshProtocol.decodeBody(body) ?: return
                deliverEnvelope(json, f.origin, f.count)
            }
            else -> Unit
        }
    }

    private fun publishAirtimeWait() {
        val now = airtimeWait.waiting(System.currentTimeMillis())
        if (_awaitingAirtime.value != now) _awaitingAirtime.value = now
    }

    /** Re-evaluates the 60 s threshold while anything is QUEUED; stops itself when nothing is. */
    private fun startAirtimeTick() {
        if (airtimeTickJob?.isActive == true) return
        airtimeTickJob = scope.launch {
            while (airtimeWait.hasPending()) {
                delay(5_000)
                publishAirtimeWait()
            }
            publishAirtimeWait()
        }
    }

    /**
     * __LORA_SEND_FAILED_VISIBLE_2026_11_23__ The radio (or its silence) says [messageId] did not reach the
     * peer over LoRa. Ignored when the mesh already confirmed it; otherwise the listener decides, and it
     * never downgrades a row delivered some other way.
     */
    private fun reportNotDelivered(messageId: String) {
        if (messageId in _loraAcked.value) return
        val listener = heldListener ?: return
        scope.launch { listener.onHeldUndeliverable(messageId) }
    }

    /**
     * Checks [sendTimeout] while anything is tracked; stops itself when nothing is. The stop decision and
     * the start decision share [sendTimeoutLock], so a send tracked just as the loop ends is never orphaned.
     */
    private fun startSendTimeoutTick() {
        synchronized(sendTimeoutLock) {
            if (sendTimeoutJob?.isActive == true) return
            sendTimeoutJob = scope.launch {
                while (true) {
                    delay(LoRaSendTimeout.TICK_MS)
                    for (id in sendTimeout.expired(System.currentTimeMillis())) {
                        // The msgId → id mapping stays in [ompOutgoing]: a late DELIVERED still upgrades the row.
                        airtimeWait.onProgress(id)
                        log("!", "OSHI msg ${id.take(8)}: no delivery news from the radio in time, shown not delivered")
                        reportNotDelivered(id)
                    }
                    publishAirtimeWait()
                    synchronized(sendTimeoutLock) {
                        if (!sendTimeout.hasPending()) {
                            sendTimeoutJob = null
                            return@launch
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────── Refusals / status ───────────────────────────

    /** :262-276 */
    private fun refuse(reason: LoRaSendRefusal, bytes: Int? = null) {
        // Only meaningful to someone who OWNS a radio. Without this, every user
        // with no node at all would collect "no node connected" refusals from the
        // off-grid fallback path — an error about hardware they never bought. :266-269
        if (prefs.getString(PAIRED_KEY, null) == null) return
        _lastRefusalReason.value = reason
        _lastRefusal.value = reason.stringKey
        _lastRefusalBytes.value = bytes
        _lastRefusalAt.value = System.currentTimeMillis()
        log("→", "✕ ${reason.stringKey}")
    }

    /** :278-284 */
    private fun clearRefusal() {
        if (_lastRefusal.value == null) return
        _lastRefusal.value = null
        _lastRefusalReason.value = null
        _lastRefusalBytes.value = null
        _lastRefusalAt.value = null
    }

    /** :1111-1116 — newest first, capped at 60. */
    private fun log(direction: String, summary: String) {
        _traffic.value = (listOf(LoRaEvent(direction, summary)) + _traffic.value).take(60)
        refreshStatus()
    }

    /**
     * Mirror the driver's state into one [LoRaNodeStatus].
     * Port of `MeshtasticNodeAdapter.refreshStatus` (MeshtasticNodeAdapter.swift:113-144).
     *
     * HONEST CAPABILITIES: this driver does not parse device telemetry, so
     * battery, uptime and frequency stay null and the screen renders "—" rather
     * than a plausible zero (MeshtasticNodeAdapter.swift:21-28).
     */
    private fun refreshStatus() {
        var health = when (_state.value) {
            ConnState.OFF, ConnState.SCANNING -> LoRaNodeHealth.DISCONNECTED
            ConnState.CONNECTING, ConnState.CONFIGURING -> LoRaNodeHealth.CONNECTING
            ConnState.READY -> LoRaNodeHealth.HEALTHY
        }
        var attention: String? = null

        // The best SNR any node reported most recently, presented as link quality
        // because it is the only radio-side figure this parser produces — and it
        // is a real measurement, not a guess. MeshtasticNodeAdapter.swift:121-124
        val freshest = _nodes.value.maxByOrNull { it.lastHeard }
        var lastPacketAt = freshest?.lastHeard?.takeIf { it > 0 }
        _traffic.value.firstOrNull()?.let { latest ->
            lastPacketAt = maxOf(lastPacketAt ?: 0L, latest.atMillis)
        }

        // Degrade rather than claim health when we are reporting a problem:
        // connected-but-erroring is exactly the state a user needs to see, and the
        // one a boolean "connected" hides. MeshtasticNodeAdapter.swift:131-137
        val err = _lastError.value
        if (health == LoRaNodeHealth.HEALTHY && !err.isNullOrEmpty()) {
            health = LoRaNodeHealth.DEGRADED
            attention = err
        }
        if (_permissionDenied.value) {
            health = LoRaNodeHealth.DISCONNECTED
            attention = "lora.error.bluetooth_denied"
        }

        _status.value = LoRaNodeStatus(
            health = health,
            displayName = displayName.orEmpty(),
            loRaRssi = freshest?.rssi,
            loRaSnr = freshest?.snr?.toDouble(),
            region = _region.value?.displayName,
            lastPacketAt = lastPacketAt,
            attention = attention,
        )
    }
}
