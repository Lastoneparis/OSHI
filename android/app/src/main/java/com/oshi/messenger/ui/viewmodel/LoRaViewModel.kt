package com.oshi.messenger.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.service.lora.DiscoveredLoRaNode
import com.oshi.messenger.service.lora.LoRaBluetoothPairing
import com.oshi.messenger.service.lora.LoRaFirmwareFamily
import com.oshi.messenger.service.lora.LoRaMeshNode
import com.oshi.messenger.service.lora.LoRaChannel
import com.oshi.messenger.service.lora.LoRaChannelSet
import com.oshi.messenger.service.lora.LoRaModemPreset
import com.oshi.messenger.service.lora.LoRaNodeHealth
import com.oshi.messenger.service.lora.LoRaNodeStatus
import com.oshi.messenger.service.lora.LoRaPacketParser
import com.oshi.messenger.service.lora.LoRaRegion
import com.oshi.messenger.service.lora.LoRaSendRefusal
import com.oshi.messenger.service.lora.LoRaJammingDetector
import com.oshi.messenger.service.lora.MeshtasticManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * LoRaViewModel — the bridge between the two LoRa screens and the radio core.
 *
 * Ports the state the iOS views observe on `MeshtasticManager`
 * (`LoRaNodeSetupView.swift`, `MeshtasticSettingsView.swift`) plus the pure
 * presentation rules those views apply, which live in [LoRaFormat] so they can
 * be unit-tested without a device.
 *
 * WHY AN INTERFACE AND NOT `MeshtasticManager` DIRECTLY
 * -----------------------------------------------------
 * The Android radio core (protobuf codec + BLE transport) is being written
 * separately. This file talks to [LoRaRadio], which is exactly the surface the
 * screens need; the concrete manager is attached at start-up through
 * [LoRaRadioProvider]. Everything except the small "must implement" block of
 * [LoRaRadio] has a null-object default, so the screens compile, preview and
 * run against a radio-less build and light up the moment the adapter is
 * installed.
 */
class LoRaViewModel(
    private val radio: LoRaRadio = LoRaRadioProvider.current
) : ViewModel() {

    // ------------------------------------------------------------ Radio state

    val connState: StateFlow<LoRaConnState> = radio.connState
    val bluetoothOn: StateFlow<Boolean> = radio.bluetoothOn
    val permissionDenied: StateFlow<Boolean> = radio.permissionDenied
    val discovered: StateFlow<List<LoRaDiscoveredUi>> = radio.discovered
    val status: StateFlow<LoRaStatusUi> = radio.status
    val capabilities: StateFlow<LoRaCapabilitiesUi> = radio.capabilities
    val nodes: StateFlow<List<LoRaMeshNodeUi>> = radio.nodes
    val region: StateFlow<LoRaRegion?> = radio.region
    val transmitEnabled: StateFlow<Boolean> = radio.transmitEnabled
    val mediaOverLoRaEnabled: StateFlow<Boolean> = radio.mediaOverLoRaEnabled
    val positionBeaconEnabled: StateFlow<Boolean> = radio.positionBeaconEnabled
    val pairingMode: StateFlow<LoRaBluetoothPairing?> = radio.pairingMode
    val myNodeNum: StateFlow<Long> = radio.myNodeNum
    val connectedName: StateFlow<String?> = radio.connectedName
    val lastError: StateFlow<String?> = radio.lastError
    val lastRefusal: StateFlow<LoRaRefusal?> = radio.lastRefusal
    val traffic: StateFlow<List<LoRaTrafficEntry>> = radio.traffic
    val firmwareFamily: StateFlow<LoRaFirmwareFamilyUi> = radio.firmwareFamily
    val firmwareProbeDone: StateFlow<Boolean> = radio.firmwareProbeDone
    val jamming: StateFlow<LoRaJammingUi> = radio.jamming
    /** __ANTI_JAMMING_OVERRIDE_2026_11_13__ End of "Send over LoRa anyway" (epoch millis), null when off. */
    val jammingOverrideUntil: StateFlow<Long?> = radio.jammingOverrideUntil

    // ------------------------------------------------------------- Screen state

    /**
     * The region the user has picked but not yet written. Pre-filled from the
     * device's locale as a SUGGESTION, never applied on its own — which band a
     * radio may use is a legal question and the phone's locale is not proof of
     * where the node is. iOS `LoRaNodeSetupView.swift:27-29, 69`.
     */
    private val _chosenRegion = MutableStateFlow(LoRaRegion.suggestedFromDevice)
    val chosenRegion: StateFlow<LoRaRegion?> = _chosenRegion.asStateFlow()

    private val _preset = MutableStateFlow(LoRaModemPreset.LONG_FAST)
    val preset: StateFlow<LoRaModemPreset> = _preset.asStateFlow()

    // ----------------------------------------------- Channel ("which mesh")
    //
    // __LORA_CHANNEL_2026_10_26__ Asked for by a user in Chile: OSHI could set
    // a node's region but had nowhere to say which MESH it joins, so joining a
    // country's community channel meant installing a second Meshtastic client.
    //
    // The draft starts at what this phone last wrote, NOT at what the node
    // holds: nothing reads a channel back off the radio, and showing an
    // invented value as the node's state is worse than showing none.

    /** The channel name last written from this phone. Empty = stock LongFast. */
    val channelName: StateFlow<String> = radio.channelName

    // __NODE_CHANNELS_WERE_DROPPED_2026_11_04__ The comment above says "nothing
    // reads a channel back off the radio". That is no longer true: the node
    // streams all eight slots during the ordinary `wantConfig` handshake and we
    // simply had no case for them. These three are what makes "see and join
    // public channels" possible rather than "type a channel you already know".

    /** The channel slots the NODE reports. Empty until the handshake completes. */
    val nodeChannels: StateFlow<List<LoRaPacketParser.NodeChannel>> = radio.nodeChannels

    /** What a pasted link contains, or null when it is not a channel link. */
    private val _pastedLink = MutableStateFlow("")
    val pastedLink: StateFlow<String> = _pastedLink.asStateFlow()

    private val _pastedChannelSet = MutableStateFlow<LoRaChannelSet.Parsed?>(null)
    val pastedChannelSet: StateFlow<LoRaChannelSet.Parsed?> = _pastedChannelSet.asStateFlow()

    /**
     * True when the user has typed something that is NOT a channel link. Kept
     * apart from "nothing typed yet" so an empty field is not an error.
     */
    val pastedLinkInvalid: StateFlow<Boolean> = _pastedLink
        .combine(_pastedChannelSet) { raw, parsed -> raw.isNotBlank() && parsed == null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun onPastedLink(raw: String) {
        _pastedLink.value = raw
        _pastedChannelSet.value = LoRaChannelSet.parse(raw)
    }

    /** Apply the pasted link to the node. No-op when nothing valid is pasted. */
    fun joinPastedChannelSet(): Boolean {
        val parsed = _pastedChannelSet.value ?: return false
        val ok = radio.joinChannelSet(parsed)
        if (ok) {
            _pastedLink.value = ""
            _pastedChannelSet.value = null
        }
        return ok
    }

    fun shareableChannelUrl(): String? = radio.shareableChannelUrl()

    private val _channelDraft = MutableStateFlow(radio.channelName.value)
    val channelDraft: StateFlow<String> = _channelDraft.asStateFlow()

    /** Base64, as pasted from a Meshtastic channel URL. Empty = the default key. */
    private val _channelKeyDraft = MutableStateFlow("")
    val channelKeyDraft: StateFlow<String> = _channelKeyDraft.asStateFlow()

    private val _channelKeyInvalid = MutableStateFlow(false)
    val channelKeyInvalid: StateFlow<Boolean> = _channelKeyInvalid.asStateFlow()

    private val _channelWritten = MutableStateFlow(false)
    val channelWritten: StateFlow<Boolean> = _channelWritten.asStateFlow()

    /** Kept open after a successful write so "Change region" has somewhere to go. */
    private val _regionSectionOpen = MutableStateFlow(false)
    val regionSectionOpen: StateFlow<Boolean> = _regionSectionOpen.asStateFlow()

    /** Which discovered row was tapped, so that row — and only it — spins. iOS `:31-33`. */
    private val _connectingId = MutableStateFlow<String?>(null)
    val connectingId: StateFlow<String?> = _connectingId.asStateFlow()

    private val _connectingName = MutableStateFlow<String?>(null)
    val connectingName: StateFlow<String?> = _connectingName.asStateFlow()

    // ------------------------------------------------------------- Derived

    val isReady: Boolean get() = connState.value == LoRaConnState.READY

    /**
     * The node is connected but muted — the state that looks like success and
     * behaves like failure, and the #1 cause of "my radio does nothing".
     * iOS `LoRaNodeSetupView.swift:40-42`, `MeshtasticManager.swift:577-586`.
     *
     * Note `region == null` is NOT this state: null means the node's config has
     * not been read yet. Unknown is not zero — the same rule the measured values
     * follow. Only a node we know is ready and whose region is null-after-read or
     * explicitly UNSET is flagged, which is what iOS's `isReady &&` guard does.
     */
    fun needsRegion(state: LoRaConnState, r: LoRaRegion?): Boolean =
        state == LoRaConnState.READY && (r == null || r == LoRaRegion.UNSET)

    // ------------------------------------------------------------- Actions

    fun startScan() = radio.startScan()
    fun stopScan() = radio.stopScan()

    fun connect(nodeId: String, name: String?) {
        // Clear the previous attempt's error, otherwise a stale failure sits
        // under a fresh, working connection. iOS `LoRaNodeSetupView.swift:243-247`.
        radio.clearLastError()
        _connectingId.value = nodeId
        _connectingName.value = name
        radio.connect(nodeId)
    }

    fun onConnectionAttemptSettled() {
        _connectingId.value = null
        _connectingName.value = null
    }

    fun disconnect(forget: Boolean) {
        radio.disconnect(forget)
        onConnectionAttemptSettled()
    }

    fun chooseRegion(r: LoRaRegion) {
        _chosenRegion.value = r
    }

    fun choosePreset(p: LoRaModemPreset) {
        _preset.value = p
    }

    /** Clamped WHILE TYPING: the firmware's name field is 11 bytes, not 11 chars. */
    fun editChannelName(text: String) {
        _channelDraft.value = LoRaChannel.clampName(text)
        _channelWritten.value = false
    }

    fun editChannelKey(text: String) {
        _channelKeyDraft.value = text
        _channelKeyInvalid.value = false
        _channelWritten.value = false
    }

    /**
     * Write the primary channel. An empty name with an empty key is the stock
     * LongFast channel — the Meshtastic default, and the one thing a user who
     * wants to be on "the normal mesh" needs to be able to ask for.
     *
     * A key that is not a key REFUSES rather than falling back to the public
     * default: silently publishing to the open channel under a key the user
     * believed was theirs is the one outcome worth a red line on screen.
     */
    fun applyChannel(): Boolean {
        val psk = LoRaChannel.parsePsk(_channelKeyDraft.value)
        if (psk == null) {
            _channelKeyInvalid.value = true
            _channelWritten.value = false
            return false
        }
        val ok = radio.setChannel(_channelDraft.value, psk)
        _channelWritten.value = ok
        return ok
    }

    fun openRegionSection() {
        _regionSectionOpen.value = true
    }

    /**
     * Write the region. Refuses UNSET the way the manager does — writing 0 is
     * indistinguishable from never having configured the node.
     * iOS `MeshtasticManager.swift:600-604`.
     */
    fun applyRegion(): Boolean {
        val r = _chosenRegion.value ?: return false
        if (r == LoRaRegion.UNSET) return false
        val ok = radio.setRegion(r, _preset.value, DEFAULT_HOP_LIMIT)
        if (ok) _regionSectionOpen.value = true
        return ok
    }

    /** Explicitly joins Chile's public Meshtastic mesh; it never runs from locale alone. */
    fun applyChileMeshDefaults(): Boolean {
        val ok = radio.setChileMeshDefaults()
        if (ok) {
            _chosenRegion.value = LoRaRegion.US
            _preset.value = LoRaModemPreset.LONG_FAST
            _regionSectionOpen.value = true
        }
        return ok
    }

    fun setPairingMode(mode: LoRaBluetoothPairing) = radio.setBluetoothPairing(mode)

    /** Opt media into the LoRa fallback (off by default). [Media-over-LoRa parity] */
    fun setMediaOverLoRa(enabled: Boolean) = radio.setMediaOverLoRaEnabled(enabled)

    /** __ANTI_JAMMING_OVERRIDE_2026_11_13__ The "Send over LoRa anyway" button on the jamming warning. */
    fun sendOverLoRaAnyway() {
        radio.sendOverLoRaAnyway()
    }

    /**
     * Opt into beaconing this phone's position over the mesh (off by default).
     *
     * The ViewModel only flips the persisted switch. `LoRaPositionBeacon`, installed from
     * `OSHIApplication`, is what watches it and does the sending — so the beacon does not
     * stop when this screen closes. [LoRa position beacon 2026-10-28]
     */
    fun setPositionBeacon(enabled: Boolean) = radio.setPositionBeaconEnabled(enabled)

    /**
     * Rename the node. `shortName` is what fits on the node's tiny display and in
     * other clients' lists — 4 characters, enforced here so the radio layer never
     * has to guess. iOS `MeshtasticManager.swift:706-729`.
     */
    fun setOwner(longName: String, shortName: String): Boolean {
        val ln = longName.trim()
        if (ln.isEmpty()) return false
        val sn = shortName.trim().take(4).ifEmpty {
            String.format(Locale.US, "%04X", myNodeNum.value and 0xFFFFL)
        }
        return radio.setOwner(ln, sn)
    }

    /** The name a node adopts once OSHI adopts it. iOS `MeshtasticManager.swift:684-686`. */
    fun suggestedOwnerName(): String =
        String.format(Locale.US, "OSHI Mesh %04X", myNodeNum.value and 0xFFFFL)

    fun sendTestBroadcast(text: String) {
        val t = text.ifBlank { DEFAULT_TEST_TEXT }
        viewModelScope.launch { radio.sendText(t, BROADCAST_ADDR) }
    }

    fun clearError() = radio.clearLastError()

    /** The setup screen's own 20-second give-up. iOS `LoRaNodeSetupView.swift:81-89`. */
    fun reportConnectTimeout(message: String) {
        radio.setLastError(message)
        radio.disconnect(false)
        onConnectionAttemptSettled()
        radio.startScan()
    }

    companion object {
        // 7, not 8: hop_limit is a 3-bit wire field (max 7); Meshtastic's
        // config.proto resets anything > 7 back to the default of 3.
        const val DEFAULT_HOP_LIMIT = 7
        const val BROADCAST_ADDR = 0xFFFFFFFFL
        const val DEFAULT_TEST_TEXT = "Hello from OSHI 👋"
    }

    /**
     * Builds a view model over the real radio, installing the adapter on first
     * use so no application-start-up code is required to make the screens live.
     */
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LoRaViewModel(LoRaRadioProvider.install(context)) as T
    }
}

// =============================================================================
// The radio surface the screens need
// =============================================================================

/**
 * What the LoRa screens ask of a radio.
 *
 * The members WITHOUT a default are the ones the radio core must supply; they
 * mirror the agreed `MeshtasticManager` surface one for one. The rest have
 * null-object defaults so this file compiles and runs before the core lands, and
 * so a build with no radio support at all still renders the screens honestly
 * (nothing found, nothing connected).
 */
interface LoRaRadio {

    // --- must be supplied by the radio core -------------------------------
    val discovered: StateFlow<List<LoRaDiscoveredUi>>
    val status: StateFlow<LoRaStatusUi>
    val nodes: StateFlow<List<LoRaMeshNodeUi>>
    val region: StateFlow<LoRaRegion?>
    val transmitEnabled: StateFlow<Boolean>
    val lastRefusal: StateFlow<LoRaRefusal?>
    val loraAcked: StateFlow<Set<String>>

    fun startScan()
    fun stopScan()
    fun connect(nodeId: String)
    fun disconnect(forget: Boolean)
    fun setRegion(region: LoRaRegion, preset: LoRaModemPreset, hopLimit: Int): Boolean
    fun setChileMeshDefaults(): Boolean
    fun setOwner(longName: String, shortName: String): Boolean
    fun setBluetoothPairing(mode: LoRaBluetoothPairing): Boolean
    suspend fun sendText(text: String, to: Long): Boolean

    // --- present on iOS's manager, defaulted here so the port is honest ----

    /**
     * iOS `MeshtasticManager.ConnState` (`:186`). [LoRaNodeHealthUi] cannot stand
     * in for it: the setup screen has to tell "Connecting" from "Setting up" from
     * "Looking for a node", and a tap that changes nothing on screen was a real
     * iOS bug (`LoRaNodeSetupView.swift:132-135`).
     */
    val connState: StateFlow<LoRaConnState> get() = ConstFlows.connState
    val bluetoothOn: StateFlow<Boolean> get() = ConstFlows.falseFlow
    val permissionDenied: StateFlow<Boolean> get() = ConstFlows.falseFlow
    val capabilities: StateFlow<LoRaCapabilitiesUi> get() = ConstFlows.capabilities
    val pairingMode: StateFlow<LoRaBluetoothPairing?> get() = ConstFlows.pairing
    val myNodeNum: StateFlow<Long> get() = ConstFlows.zeroLong
    val connectedName: StateFlow<String?> get() = ConstFlows.nullString
    val lastError: StateFlow<String?> get() = ConstFlows.nullString
    val traffic: StateFlow<List<LoRaTrafficEntry>> get() = ConstFlows.traffic

    /**
     * The connected radio's firmware, and whether the OSHI Mesh probe has had its
     * chance to be answered. Only both together say "stock Meshtastic" — the
     * settings screen offers the OSHI Mesh upgrade on that. iOS
     * `MeshtasticManager.firmwareFamily` / `firmwareProbeDone`.
     */
    val firmwareFamily: StateFlow<LoRaFirmwareFamilyUi> get() = ConstFlows.firmwareFamily
    val firmwareProbeDone: StateFlow<Boolean> get() = ConstFlows.falseFlow

    /**
     * __ANTI_JAMMING_PHASE1_2026_11_13__ What the radio's channel looks like
     * (`MeshtasticManager.jamming`). CLEAR in a radio-less build.
     */
    val jamming: StateFlow<LoRaJammingUi> get() = ConstFlows.jamming

    /**
     * __ANTI_JAMMING_OVERRIDE_2026_11_13__ "Send over LoRa anyway": suspends the jamming exclusion for
     * 10 min or until the channel is CLEAR, and sends what the radio was holding. [jammingOverrideUntil]
     * is when it ends, null when inactive. No-op in a radio-less build.
     */
    val jammingOverrideUntil: StateFlow<Long?> get() = ConstFlows.nullLong
    fun sendOverLoRaAnyway(): Boolean = false

    /**
     * Opt-in: carry media over LoRa (off by default). Defaulted here so a
     * radio-less build still compiles; the real adapter persists and publishes it.
     * [Media-over-LoRa parity 2026-09-11]
     */
    val mediaOverLoRaEnabled: StateFlow<Boolean> get() = ConstFlows.falseFlow
    fun setMediaOverLoRaEnabled(enabled: Boolean) {}

    /**
     * Opt-in: beacon this phone's position over the mesh (off by default). Defaulted here
     * so a radio-less build still compiles; the real adapter persists and publishes it.
     * [LoRa position beacon 2026-10-28]
     */
    val positionBeaconEnabled: StateFlow<Boolean> get() = ConstFlows.falseFlow
    fun setPositionBeaconEnabled(enabled: Boolean) {}

    /**
     * The primary channel name this phone last WROTE (empty = stock LongFast).
     * Not a read-back of the node — see `MeshtasticManager.channelName`.
     * [LoRa channel 2026-10-26]
     */
    val channelName: StateFlow<String> get() = ConstFlows.emptyString
    fun setChannel(name: String, psk: ByteArray): Boolean = false

    /**
     * __NODE_CHANNELS_WERE_DROPPED_2026_11_04__ the slots the NODE reports, which
     * is a different thing from [channelName]: that is what this phone last
     * wrote, this is the radio's own answer. Empty until the first handshake.
     */
    val nodeChannels: StateFlow<List<LoRaPacketParser.NodeChannel>>
        get() = ConstFlows.emptyNodeChannels

    /** Apply every channel in a shared `meshtastic.org/e/#` link. */
    fun joinChannelSet(parsed: LoRaChannelSet.Parsed): Boolean = false

    /** A shareable link for what the node is on, or null when there is nothing. */
    fun shareableChannelUrl(): String? = null

    /**
     * A radio that accepts the BLE link but never answers leaves the setup
     * screen spinning forever, so the screen sets the reason itself rather than
     * spinning. iOS `LoRaNodeSetupView.swift:81-89`.
     */
    fun setLastError(message: String?) {}

    fun clearLastError() = setLastError(null)
}

private object ConstFlows {
    val emptyNodeChannels: StateFlow<List<LoRaPacketParser.NodeChannel>> =
        MutableStateFlow(emptyList<LoRaPacketParser.NodeChannel>()).asStateFlow()
    val connState = MutableStateFlow(LoRaConnState.OFF).asStateFlow()
    val falseFlow = MutableStateFlow(false).asStateFlow()
    val zeroLong = MutableStateFlow(0L).asStateFlow()
    val nullString = MutableStateFlow<String?>(null).asStateFlow()
    val nullLong = MutableStateFlow<Long?>(null).asStateFlow()
    val emptyString = MutableStateFlow("").asStateFlow()
    val pairing = MutableStateFlow<LoRaBluetoothPairing?>(null).asStateFlow()
    val capabilities = MutableStateFlow(LoRaCapabilitiesUi.MESHTASTIC_2X).asStateFlow()
    val traffic = MutableStateFlow<List<LoRaTrafficEntry>>(emptyList()).asStateFlow()
    val firmwareFamily = MutableStateFlow(LoRaFirmwareFamilyUi.MESHTASTIC).asStateFlow()
    val jamming = MutableStateFlow(LoRaJammingUi.CLEAR).asStateFlow()
    val discovered = MutableStateFlow<List<LoRaDiscoveredUi>>(emptyList()).asStateFlow()
    val meshNodes = MutableStateFlow<List<LoRaMeshNodeUi>>(emptyList()).asStateFlow()
    val status = MutableStateFlow(LoRaStatusUi()).asStateFlow()
    val region = MutableStateFlow<LoRaRegion?>(null).asStateFlow()
    val trueFlow = MutableStateFlow(true).asStateFlow()
    val refusal = MutableStateFlow<LoRaRefusal?>(null).asStateFlow()
    val acked = MutableStateFlow<Set<String>>(emptySet()).asStateFlow()
}

/** A radio-less build: everything empty, nothing pretends to work. */
object NullLoRaRadio : LoRaRadio {
    override val discovered = ConstFlows.discovered
    override val status = ConstFlows.status
    override val nodes = ConstFlows.meshNodes
    override val region = ConstFlows.region
    override val transmitEnabled = ConstFlows.trueFlow
    override val lastRefusal = ConstFlows.refusal
    override val loraAcked = ConstFlows.acked

    override fun startScan() {}
    override fun stopScan() {}
    override fun connect(nodeId: String) {}
    override fun disconnect(forget: Boolean) {}
    override fun setRegion(region: LoRaRegion, preset: LoRaModemPreset, hopLimit: Int) = false
    override fun setChileMeshDefaults() = false
    override fun setOwner(longName: String, shortName: String) = false
    override fun setBluetoothPairing(mode: LoRaBluetoothPairing) = false
    override suspend fun sendText(text: String, to: Long) = false
}

/**
 * Where the concrete radio is attached.
 *
 * [install] is idempotent and lazy: the first screen to open builds the adapter
 * over `MeshtasticManager`, so no application-start-up wiring is needed. A test
 * or preview can assign [current] directly instead.
 */
object LoRaRadioProvider {
    @Volatile
    var current: LoRaRadio = NullLoRaRadio

    @Synchronized
    fun install(context: Context): LoRaRadio {
        val existing = current
        if (existing !== NullLoRaRadio) return existing
        return MeshtasticRadioAdapter(context).also { current = it }
    }
}

/**
 * The single seam between the radio core and the screens.
 *
 * `MeshtasticManager` speaks in its own types (`DiscoveredLoRaNode`,
 * `LoRaNodeStatus`, `LoRaMeshNode`, `UInt` node numbers); the screens speak in
 * the `*Ui` types above. Every conversion lives here and nowhere else, so the
 * two halves can move independently.
 */
class MeshtasticRadioAdapter(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : LoRaRadio {

    private val radio = MeshtasticManager.getInstance(context.applicationContext)

    private fun <T, R> StateFlow<T>.mapState(transform: (T) -> R): StateFlow<R> =
        map(transform).stateIn(scope, SharingStarted.Eagerly, transform(value))

    override val discovered: StateFlow<List<LoRaDiscoveredUi>> =
        radio.discovered.mapState { list -> list.map { it.toUi() } }
    override val status: StateFlow<LoRaStatusUi> = radio.status.mapState { it.toUi() }
    override val nodes: StateFlow<List<LoRaMeshNodeUi>> =
        radio.nodes.mapState { list -> list.map { it.toUi() } }
    override val region: StateFlow<LoRaRegion?> = radio.region
    override val transmitEnabled: StateFlow<Boolean> = radio.transmitEnabled
    override val lastRefusal: StateFlow<LoRaRefusal?> =
        combine(radio.lastRefusalReason, radio.lastRefusalBytes) { reason, bytes ->
            reason?.toUi(bytes)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            radio.lastRefusalReason.value?.toUi(radio.lastRefusalBytes.value)
        )
    override val loraAcked: StateFlow<Set<String>> = radio.loraAcked

    override val connState: StateFlow<LoRaConnState> = radio.state.mapState { it.toUi() }
    override val bluetoothOn: StateFlow<Boolean> = radio.bluetoothOn
    override val permissionDenied: StateFlow<Boolean> = radio.permissionDenied

    /**
     * Meshtastic 2.x over BLE is the only family that ships, and the manager
     * speaks to nothing else. OTA stays false: it is not implemented, and
     * claiming it would put a button on screen that cannot work.
     */
    override val capabilities: StateFlow<LoRaCapabilitiesUi> =
        MutableStateFlow(LoRaCapabilitiesUi.MESHTASTIC_2X).asStateFlow()

    override val pairingMode: StateFlow<LoRaBluetoothPairing?> = radio.pairingMode
    override val myNodeNum: StateFlow<Long> = radio.myNodeNum.mapState { it.toLong() }
    override val connectedName: StateFlow<String?> = radio.connectedName
    override val lastError: StateFlow<String?> = radio.lastError
    override val traffic: StateFlow<List<LoRaTrafficEntry>> =
        radio.traffic.mapState { list ->
            list.map { LoRaTrafficEntry(it.direction, it.summary, it.atMillis) }
        }
    override val firmwareFamily: StateFlow<LoRaFirmwareFamilyUi> =
        radio.firmwareFamily.mapState { it.toUi() }
    override val firmwareProbeDone: StateFlow<Boolean> = radio.firmwareProbeDone
    override val jamming: StateFlow<LoRaJammingUi> = radio.jamming.mapState {
        when (it) {
            LoRaJammingDetector.State.CLEAR -> LoRaJammingUi.CLEAR
            LoRaJammingDetector.State.CONGESTED -> LoRaJammingUi.CONGESTED
            LoRaJammingDetector.State.PROBABLE_JAMMING -> LoRaJammingUi.PROBABLE_JAMMING
        }
    }
    override val jammingOverrideUntil: StateFlow<Long?> = radio.jammingOverrideUntil
    override fun sendOverLoRaAnyway(): Boolean = radio.sendOverLoRaAnyway()

    override fun startScan() = radio.startScan()
    override fun stopScan() = radio.stopScan()
    override fun connect(nodeId: String) = radio.connect(nodeId)
    override fun disconnect(forget: Boolean) = radio.disconnect(forget)
    override fun setRegion(region: LoRaRegion, preset: LoRaModemPreset, hopLimit: Int): Boolean =
        radio.setRegion(region, preset, hopLimit.toLong())

    override fun setChileMeshDefaults(): Boolean = radio.setChileMeshDefaults()

    override fun setOwner(longName: String, shortName: String): Boolean =
        radio.setOwner(longName, shortName)

    override fun setBluetoothPairing(mode: LoRaBluetoothPairing): Boolean =
        radio.setBluetoothPairing(mode)

    override val channelName: StateFlow<String> = radio.channelName

    override fun setChannel(name: String, psk: ByteArray): Boolean =
        radio.setChannel(name, psk)

    override val nodeChannels: StateFlow<List<LoRaPacketParser.NodeChannel>> = radio.nodeChannels

    override fun joinChannelSet(parsed: LoRaChannelSet.Parsed): Boolean =
        radio.joinChannelSet(parsed)

    override fun shareableChannelUrl(): String? = radio.shareableChannelUrl()

    override suspend fun sendText(text: String, to: Long) = radio.sendText(text, to.toUInt())

    override fun setLastError(message: String?) = radio.setLastError(message)

    override val mediaOverLoRaEnabled: StateFlow<Boolean> = radio.mediaOverLoRaEnabled
    override fun setMediaOverLoRaEnabled(enabled: Boolean) = radio.setMediaOverLoRaEnabled(enabled)

    override val positionBeaconEnabled: StateFlow<Boolean> = radio.positionBeaconEnabled
    override fun setPositionBeaconEnabled(enabled: Boolean) = radio.setPositionBeaconEnabled(enabled)
}

private fun DiscoveredLoRaNode.toUi() = LoRaDiscoveredUi(
    id = id,
    name = name,
    rssi = rssi,
    family = family.toUi(),
    isKnown = isKnown
)

private fun LoRaFirmwareFamily.toUi() = when (this) {
    LoRaFirmwareFamily.MESHTASTIC -> LoRaFirmwareFamilyUi.MESHTASTIC
    LoRaFirmwareFamily.OSHI -> LoRaFirmwareFamilyUi.OSHI
    LoRaFirmwareFamily.UNKNOWN -> LoRaFirmwareFamilyUi.UNKNOWN
}

private fun LoRaNodeStatus.toUi() = LoRaStatusUi(
    health = when (health) {
        LoRaNodeHealth.DISCONNECTED -> LoRaNodeHealthUi.DISCONNECTED
        LoRaNodeHealth.CONNECTING -> LoRaNodeHealthUi.CONNECTING
        LoRaNodeHealth.DEGRADED -> LoRaNodeHealthUi.DEGRADED
        LoRaNodeHealth.HEALTHY -> LoRaNodeHealthUi.HEALTHY
    },
    displayName = displayName,
    firmwareVersion = firmwareVersion,
    batteryLevel = batteryLevel,
    isCharging = isCharging,
    bluetoothRssi = bluetoothRssi,
    loRaRssi = loRaRssi,
    loRaSnr = loRaSnr,
    region = region,
    frequencyMhz = frequencyMhz,
    uptimeSeconds = uptimeSeconds,
    lastPacketAt = lastPacketAt,
    attention = attention
)

private fun LoRaMeshNode.toUi() = LoRaMeshNodeUi(
    num = num.toLong(),
    longName = longName,
    shortName = shortName,
    rssi = rssi,
    snr = snr.toDouble(),
    lastHeardMillis = lastHeard,
    latitude = latitude,
    longitude = longitude,
    hopsAway = hopsAway
)

private fun MeshtasticManager.ConnState.toUi(): LoRaConnState = when (this) {
    MeshtasticManager.ConnState.OFF -> LoRaConnState.OFF
    MeshtasticManager.ConnState.SCANNING -> LoRaConnState.SCANNING
    MeshtasticManager.ConnState.CONNECTING -> LoRaConnState.CONNECTING
    MeshtasticManager.ConnState.CONFIGURING -> LoRaConnState.CONFIGURING
    MeshtasticManager.ConnState.READY -> LoRaConnState.READY
}

private fun LoRaSendRefusal.toUi(bytes: Int?): LoRaRefusal = when (this) {
    LoRaSendRefusal.NOT_CONNECTED -> LoRaRefusal.NotConnected
    LoRaSendRefusal.RADIO_MUTED -> LoRaRefusal.RadioMuted
    LoRaSendRefusal.NO_REGION -> LoRaRefusal.NoRegion
    LoRaSendRefusal.TOO_LARGE -> LoRaRefusal.TooLarge(bytes ?: 0)
    LoRaSendRefusal.CARRIES_MEDIA -> LoRaRefusal.CarriesMedia
}

// =============================================================================
// UI-facing models
//
// Deliberately separate from the radio core's own types (which live in
// `service/lora` and are owned by the radio work) so the two can be built
// concurrently without a name collision. The adapter above is the single place
// that maps between them.
// =============================================================================

/** iOS `MeshtasticManager.ConnState` (`:186`). */
enum class LoRaConnState { OFF, SCANNING, CONNECTING, CONFIGURING, READY }

/** iOS `LoRaNodeHealth` (`LoRaNodeProtocol.swift:94-105`), worst to best. */
enum class LoRaNodeHealthUi { DISCONNECTED, CONNECTING, DEGRADED, HEALTHY }

/** iOS `LoRaFirmwareFamily` (`LoRaNodeProtocol.swift:40-47`). */
enum class LoRaFirmwareFamilyUi { MESHTASTIC, OSHI, UNKNOWN }

/** __ANTI_JAMMING_PHASE1_2026_11_13__ `LoRaJammingDetector.State`, for the screens. */
enum class LoRaJammingUi { CLEAR, CONGESTED, PROBABLE_JAMMING }

/**
 * What a node can actually do. iOS `LoRaNodeCapabilities`
 * (`LoRaNodeProtocol.swift:52-86`).
 *
 * Never assume a capability — a T-Echo has a screen and a Heltec V3 does not,
 * and an older firmware may not support OTA at all. The UI HIDES what is absent
 * instead of failing at the tap (`:49-51`).
 */
data class LoRaCapabilitiesUi(
    val canConfigureRegion: Boolean = false,
    val canConfigureChannel: Boolean = false,
    val canSetTransmitPower: Boolean = false,
    val canReportBattery: Boolean = false,
    val canReportPosition: Boolean = false,
    val supportsOTA: Boolean = false,
    val supportsRelay: Boolean = false
) {
    companion object {
        /**
         * What Meshtastic 2.x over BLE gives us today. OTA is false on purpose:
         * it is not implemented, and claiming it would put a button on screen
         * that cannot work. iOS `:82-85`.
         */
        val MESHTASTIC_2X = LoRaCapabilitiesUi(
            canConfigureRegion = true, canConfigureChannel = true,
            canSetTransmitPower = true, canReportBattery = true,
            canReportPosition = true, supportsOTA = false, supportsRelay = true
        )
    }
}

/**
 * Everything the LoRa screens show, in one snapshot. iOS `LoRaNodeStatus`
 * (`LoRaNodeProtocol.swift:113-161`).
 *
 * ⚠️ The measured values are all nullable ON PURPOSE: a node that has not
 * reported its battery must render as "—", NEVER as 0 %. The difference between
 * "unknown" and "empty" is the difference between a blank field and a false
 * alarm (`:108-112`). Render them through [LoRaFormat].
 */
data class LoRaStatusUi(
    val health: LoRaNodeHealthUi = LoRaNodeHealthUi.DISCONNECTED,
    val displayName: String = "",
    val firmwareVersion: String? = null,
    /** 0…1. Null until the node reports it. */
    val batteryLevel: Double? = null,
    val isCharging: Boolean? = null,
    /** BLE signal to the phone, dBm. */
    val bluetoothRssi: Int? = null,
    /** Last LoRa packet's signal, dBm, and signal-to-noise ratio, dB. */
    val loRaRssi: Int? = null,
    val loRaSnr: Double? = null,
    val region: String? = null,
    val frequencyMhz: Double? = null,
    val uptimeSeconds: Long? = null,
    val lastPacketAt: Long? = null,
    /** A sentence to show when something needs attention, so the UI invents none. */
    val attention: String? = null
)

/** A node seen while scanning, before any connection exists. iOS `:166-195`. */
data class LoRaDiscoveredUi(
    /** Stable across scans for the same physical node — the BLE device address. */
    val id: String,
    val name: String,
    val rssi: Int,
    val family: LoRaFirmwareFamilyUi = LoRaFirmwareFamilyUi.MESHTASTIC,
    val isKnown: Boolean = false
) {
    /** iOS `:187-194`. */
    val signalBars: Int get() = LoRaFormat.signalBars(rssi)
}

/** A node the radio has heard on the mesh. iOS `MeshtasticNode` (`:87-141`). */
data class LoRaMeshNodeUi(
    val num: Long,
    val longName: String = "",
    val shortName: String = "",
    val rssi: Int? = null,
    val snr: Double = 0.0,
    val lastHeardMillis: Long = 0L,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** 0 = heard directly, null = not reported. */
    val hopsAway: Int? = null
) {
    val userId: String get() = LoRaFormat.nodeId(num)
    val hasPosition: Boolean get() = latitude != null && longitude != null
}

/**
 * Why the last message OSHI tried to put on the radio did not go.
 * iOS `MeshtasticManager.SendRefusal` (`:226-269`).
 *
 * Four refusal paths used to end in a log line and nothing else, so a message
 * failed with no reason given on the one feature people bought hardware for —
 * and three of the four are ten-second fixes.
 */
sealed class LoRaRefusal {
    object NotConnected : LoRaRefusal()
    object RadioMuted : LoRaRefusal()
    object NoRegion : LoRaRefusal()
    data class TooLarge(val bytes: Int) : LoRaRefusal()
    object CarriesMedia : LoRaRefusal()

    /** The two the user can fix from the setup screen. iOS `:171`. */
    val isFixableHere: Boolean get() = this is NoRegion || this is RadioMuted
}

/** iOS `LoRaEvent` (`MeshtasticManager.swift:143-148`). "→" sent, "←" received. */
data class LoRaTrafficEntry(
    val direction: String,
    val summary: String,
    val atMillis: Long
)

/** Four words, not dBm. iOS `LoRaNodeSetupView.swift:349-356`. */
enum class LoRaSignalWord { EXCELLENT, GOOD, FAIR, WEAK }

// =============================================================================
// Presentation rules — pure, so they can be pinned by tests
// =============================================================================

object LoRaFormat {

    /**
     * Four buckets, because "-73 dBm" means nothing to the person holding the
     * phone and "Good" does. iOS `LoRaNodeProtocol.swift:187-194`.
     *
     * Boundaries are inclusive at the top of each band: ≥ -60 → 4,
     * [-70, -60) → 3, [-85, -70) → 2, otherwise 1.
     */
    fun signalBars(rssi: Int): Int = when {
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -85 -> 2
        else -> 1
    }

    /** Same buckets, as words. iOS `LoRaNodeSetupView.swift:349-356`. */
    fun signalWord(rssi: Int): LoRaSignalWord = when (signalBars(rssi)) {
        4 -> LoRaSignalWord.EXCELLENT
        3 -> LoRaSignalWord.GOOD
        2 -> LoRaSignalWord.FAIR
        else -> LoRaSignalWord.WEAK
    }

    /** The em dash every unreported value renders as. iOS `LoRaNodeProtocol.swift:110-112`. */
    const val DASH = "—"

    /**
     * Battery as a percentage, or [DASH] when the node has not said.
     * NEVER "0 %" for an unknown value — that is a false alarm, not a blank field.
     */
    fun batteryPercent(level: Double?): String {
        if (level == null || !level.isFinite()) return DASH
        val clamped = min(1.0, max(0.0, level))
        return String.format(Locale.US, "%.0f %%", clamped * 100.0)
    }

    fun dbm(value: Int?): String =
        if (value == null) DASH else String.format(Locale.US, "%d dBm", value)

    fun snr(value: Double?): String =
        if (value == null || !value.isFinite()) DASH
        else String.format(Locale.US, "SNR %.1f dB", value)

    fun megahertz(value: Double?): String =
        if (value == null || !value.isFinite()) DASH
        else String.format(Locale.US, "%.3f MHz", value)

    fun text(value: String?): String = if (value.isNullOrBlank()) DASH else value

    /** iOS renders the node number as `!%08x` (`LoRaNodeSetupView.swift:124-125`). */
    fun nodeId(num: Long): String =
        if (num == 0L) DASH else String.format(Locale.US, "!%08x", num and 0xFFFFFFFFL)

    /** "3d 4h", "4h 12m", "12m", or [DASH]. */
    /**
     * __LORA_NODE_LIST_2026_11_04__ How long ago a node was last heard, in
     * buckets rather than exact figures.
     *
     * Deliberately coarse. A mesh node's `lastHeard` is the last packet that
     * reached us, and the gap between "30 s ago" and "50 s ago" carries no
     * information a person can act on — whereas the gap between "now" and
     * "yesterday" is the difference between a radio that is there and one that
     * is gone. Precision here would be false precision, and it would also make
     * the row rewrite itself every second.
     *
     * Returns null for a node never heard from (0 or absent), so the caller can
     * leave the field out instead of printing "56 years ago", which is what
     * epoch zero formats to.
     *
     * Bucket keys rather than words, because this is resolved against
     * `strings_lora.xml` by the composable; a formatter that returns English is
     * a formatter that cannot be translated.
     */
    fun lastHeardBucket(lastHeardMillis: Long, nowMillis: Long): LastHeard? {
        if (lastHeardMillis <= 0L) return null
        val ageMs = nowMillis - lastHeardMillis
        // A node whose clock runs ahead of ours reports a FUTURE lastHeard. It
        // was still heard just now, so it is "now" — not a negative age that
        // falls through to "days".
        if (ageMs < 90_000L) return LastHeard.Now
        val minutes = ageMs / 60_000L
        if (minutes < 60L) return LastHeard.Minutes(minutes.toInt())
        val hours = ageMs / 3_600_000L
        if (hours < 24L) return LastHeard.Hours(hours.toInt())
        return LastHeard.Days((ageMs / 86_400_000L).toInt().coerceAtLeast(1))
    }

    /** The outcome of [lastHeardBucket]; the UI supplies the words. */
    sealed interface LastHeard {
        object Now : LastHeard
        data class Minutes(val value: Int) : LastHeard
        data class Hours(val value: Int) : LastHeard
        data class Days(val value: Int) : LastHeard
    }

    /**
     * A node is treated as PRESENT when it was heard within this window.
     *
     * Five minutes because the default Meshtastic node-info broadcast interval
     * is three hours but a node in a mesh with traffic is heard far more often;
     * five minutes is long enough not to flicker and short enough that a radio
     * someone has walked away with stops claiming to be here.
     */
    const val PRESENT_WINDOW_MS = 5 * 60 * 1000L

    fun isPresent(lastHeardMillis: Long, nowMillis: Long): Boolean =
        lastHeardMillis > 0L && nowMillis - lastHeardMillis < PRESENT_WINDOW_MS

    fun uptime(seconds: Long?): String {
        if (seconds == null || seconds < 0) return DASH
        val d = seconds / 86_400
        val h = (seconds % 86_400) / 3_600
        val m = (seconds % 3_600) / 60
        return when {
            d > 0 -> String.format(Locale.US, "%dd %dh", d, h)
            h > 0 -> String.format(Locale.US, "%dh %dm", h, m)
            else -> String.format(Locale.US, "%dm", m)
        }
    }

    /**
     * A distance estimated from signal strength, for the majority of nodes that
     * have no GPS and never broadcast a position.
     * iOS `MeshtasticNode.estimatedRangeKm` (`MeshtasticManager.swift:124-140`).
     *
     * A log-distance path-loss model, and genuinely approximate: the same RSSI
     * means a very different distance across open water than through a town. So
     * it returns a RANGE (path-loss exponent 2.4 open-ish to 3.2 built-up) and
     * the UI must label it an estimate. A single number would be false precision
     * and someone might act on it in the field.
     *
     * Null past roughly -105 dBm: the band opens to tens of kilometres and stops
     * saying anything worth reading (-130 dBm yielded "14 km – 470 km"). Showing
     * that is worse than showing nothing.
     */
    fun estimatedRangeKm(rssi: Int?): Pair<Double, Double>? {
        if (rssi == null || rssi >= 0 || rssi <= -140) return null
        val eirp = 22.0 + 2.0 + 2.0        // tx power + both antenna gains
        val lossAtOneMetre = 31.2
        val budget = eirp - lossAtOneMetre - rssi.toDouble()
        if (budget <= 0) return null

        fun metres(exponent: Double) = 10.0.pow(budget / (10.0 * exponent))
        // Larger exponent = more obstruction = the SAME signal implies a SHORTER
        // distance, so 3.2 gives the near end of the band.
        val near = metres(3.2) / 1000.0
        val far = metres(2.4) / 1000.0
        if (!near.isFinite() || !far.isFinite()) return null
        if (far > 20) return null
        return Pair(min(near, far), max(near, far))
    }

    /**
     * __DISTANCE_FOLLOWS_THE_LOCALE_2026_10_21__ Les distances du maillage
     * etaient ecrites en dur en m/km. Pour quelqu'un aux Etats-Unis ou au
     * Royaume-Uni, « 4,2 km » n'est pas une information, c'est une conversion
     * a faire de tete. Parite iOS: `MeshDistance` (MeshtasticManager.swift).
     *
     * `Locale.UK` compte comme imperial: les routes britanniques sont en miles.
     *
     * Les deux seuils ne sont volontairement pas symetriques — metres sous
     * 1 km, pieds sous 0,1 mile (~160 m) — parce qu'au-dessous d'un dixieme de
     * mile l'affichage decimal devient illisible.
     */
    val localeIsImperial: Boolean
        get() = Locale.getDefault().country.uppercase(Locale.US) in setOf("US", "GB", "LR", "MM")

    /**
     * "300–900 m", "1.2–4.8 km", "0.8–3.0 mi" — l'unite vient du lecteur, et
     * de la borne HAUTE pour que les deux moities ne divergent jamais.
     * iOS `MeshtasticSettingsView.swift` / `MeshDistance.span`.
     */
    /**
     * __ROUNDING_MUST_MATCH_IOS_2026_10_21__ `String.format("%.1f", 4.25)` rend
     * "4.3" en Java et "4.2" en Swift, pour la MEME distance.
     *
     * Ce n'est pas un detail de test: `printf` en C — ce qu'utilise Swift —
     * arrondit au pair le plus proche sur la valeur BINAIRE exacte, alors que
     * `String.format` en Java arrondit au superieur (HALF_UP). Un noeud a 4,25 km
     * s'affichait donc "4.2 km" sur l'iPhone et "4.3 km" sur Android, cote a
     * cote dans la meme piece. Le test de parite l'a attrape.
     *
     * `BigDecimal(double)` prend la valeur binaire EXACTE (pas son ecriture
     * decimale), et HALF_EVEN est la regle de printf. Les deux plateformes
     * disent alors la meme chose.
     */
    private fun fixed(value: Double, decimals: Int): String =
        java.math.BigDecimal(value)
            .setScale(decimals, java.math.RoundingMode.HALF_EVEN)
            .toPlainString()

    fun span(lo: Double, hi: Double, imperial: Boolean = localeIsImperial): String =
        if (imperial) {
            if (hi * 0.621371 < 0.1)
                "${fixed(lo * 3280.84, 0)}–${fixed(hi * 3280.84, 0)} ft"
            else
                "${fixed(lo * 0.621371, 1)}–${fixed(hi * 0.621371, 1)} mi"
        } else {
            if (hi < 1) "${fixed(lo * 1000, 0)}–${fixed(hi * 1000, 0)} m"
            else "${fixed(lo, 1)}–${fixed(hi, 1)} km"
        }

    fun distanceLabel(km: Double, imperial: Boolean = localeIsImperial): String =
        if (imperial) {
            if (km * 0.621371 < 0.1) "${fixed(km * 3280.84, 0)} ft"
            else "${fixed(km * 0.621371, 1)} mi"
        } else {
            if (km < 1) "${fixed(km * 1000, 0)} m"
            else "${fixed(km, 1)} km"
        }

    /**
     * __SHORT_NAME_IS_BYTES_2026_10_21__ Meshtastic plafonne `User.short_name` a
     * quatre OCTETS, pas quatre caracteres — et sur Android le piege est pire
     * qu'ailleurs.
     *
     * `shortName.take(4)` prend quatre `Char`, c'est-a-dire quatre unites
     * UTF-16. Un emoji est une PAIRE DE SUBSTITUTION, donc `take(4)` pouvait
     * laisser passer deux emoji (8 octets) ou, pire, couper une paire en deux et
     * produire un demi-caractere qui s'encode en U+FFFD. L'utilisateur voyait
     * alors un losange noir sur tous les autres clients Meshtastic.
     *
     * On itere donc sur les POINTS DE CODE, et on compte les octets UTF-8.
     * Parite iOS: `MeshtasticManager.clampShortName`.
     */
    fun clampShortName(raw: String): String {
        val out = StringBuilder()
        var used = 0
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            val chars = Character.charCount(cp)
            val piece = raw.substring(i, i + chars)
            val n = piece.toByteArray(Charsets.UTF_8).size
            if (used + n > 4) break
            out.append(piece)
            used += n
            i += chars
        }
        return out.toString()
    }

    /** Great-circle distance in km. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0088
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * atan2(sqrt(a), sqrt(abs(1 - a)))
    }
}
