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
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.random.Random

/**
 * One MeshCore companion radio over Bluetooth — port of MeshCoreManager in OSHI/MeshCoreRadio.swift.
 *
 * OSHI rides MeshCore as OMP DATA frames in "OB" envelopes on the "#oshi-bridge" group channel
 * (MeshCoreProtocol.kt), so a phone on a MeshCore radio talks to other such phones through MeshCore
 * repeaters, and to the Meshtastic side through any oshi-meshcore-bridge — or through a phone that holds
 * one radio of each ([LoRaCrossBridge]). The companion conversation itself (setup, channel slot, receive
 * path) is [MeshCoreSession]; this class only moves its bytes over BLE.
 *
 * Link: Nordic UART service, RX written WITH response, TX notified; one companion request in flight at a
 * time. The radio asks for its PIN on first use; Android shows its own pairing prompt when we bond.
 *
 * Received envelopes go into the SAME pipeline as Meshtastic ones ([MeshtasticManager.deliverForeignEnvelope]),
 * without binding the MeshCore-derived id as a Meshtastic node number.
 */
@SuppressLint("MissingPermission")   // every call site is guarded by `hasPermission`
class MeshCoreManager private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    companion object {
        private const val TAG = "MeshCore"
        val SERVICE_UUID: UUID = UUID.fromString(MeshCore.SERVICE_UUID)
        val RX_UUID: UUID = UUID.fromString(MeshCore.RX_UUID)
        val TX_UUID: UUID = UUID.fromString(MeshCore.TX_UUID)
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        /** Same key names as iOS, in the same prefs file as the Meshtastic radio. */
        private const val PAIRED_KEY = "oshi.meshcore.peripheralId"
        /** The paired radio's Bluetooth name, so settings still name it while it is out of range. */
        private const val PAIRED_NAME_KEY = "oshi.meshcore.peripheralName"
        const val CROSS_BRIDGE_KEY = "oshi.lora.crossBridge"
        private const val PREFS = "oshi.lora"

        /** OMP pacing; a MeshCore datagram is ~0.5-1 s of air. */
        const val SEND_GAP_MS = 2_500L
        private const val GATT_TIMEOUT_MS = 8_000L
        /** A request the radio never answers must not wedge the one-at-a-time queue for ever. */
        private const val REPLY_TIMEOUT_MS = 10_000L
        private const val BOND_TIMEOUT_MS = 60_000L
        const val REQUESTED_MTU = 512

        /** Connected but the radio never finished its setup answers: not a MeshCore companion, or wedged. */
        private const val SETUP_TIMEOUT_MS = 30_000L

        /** String KEYS the UI localizes (MeshCoreUi.errorText). */
        const val ERROR_NO_FREE_CHANNEL = "meshcore.noFreeChannel"
        /** Android pairing failed: wrong PIN, cancelled prompt, or an old bond the radio forgot. */
        const val ERROR_PAIRING_FAILED = "meshcore.pairingFailed"
        /** Connected, but no MeshCore answer in [SETUP_TIMEOUT_MS]. */
        const val ERROR_NO_ANSWER = "meshcore.noAnswer"
        /** The link dropped (out of range, radio off); reconnecting on its own. */
        const val ERROR_CONNECTION_LOST = "meshcore.connectionLost"

        @Volatile
        private var INSTANCE: MeshCoreManager? = null

        fun getInstance(context: Context): MeshCoreManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MeshCoreManager(context).also {
                    INSTANCE = it
                    it.start()
                }
            }

        /** Null until something has asked for the manager. Never constructs one. */
        val instanceOrNull: MeshCoreManager? get() = INSTANCE

        fun isPaired(context: Context): Boolean =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PAIRED_KEY, null) != null
    }

    enum class ConnState { OFF, SCANNING, CONNECTING, CONFIGURING, READY }

    data class Device(val id: String, val name: String, val rssi: Int)

    // ─────────────────────────── Published state ───────────────────────────

    private val _state = MutableStateFlow(ConnState.OFF)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _discovered = MutableStateFlow<List<Device>>(emptyList())
    val discovered: StateFlow<List<Device>> = _discovered.asStateFlow()

    private val _connectedName = MutableStateFlow<String?>(null)
    val connectedName: StateFlow<String?> = _connectedName.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Android's pairing prompt is (or should be) on screen: the person has to type the radio's PIN. */
    private val _awaitingPin = MutableStateFlow(false)
    val awaitingPin: StateFlow<Boolean> = _awaitingPin.asStateFlow()

    private val _framesSent = MutableStateFlow(0)
    val framesSent: StateFlow<Int> = _framesSent.asStateFlow()

    private val _messagesReceived = MutableStateFlow(0)
    val messagesReceived: StateFlow<Int> = _messagesReceived.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The companion conversation (pure, JVM-tested); this class only gives it a BLE link. */
    val link = MeshCoreLink(scope, write = { gattWrite(it) }).also { l ->
        l.onBridgeReady = { becomeReady() }
        l.onNoFreeChannel = {
            _lastError.value = ERROR_NO_FREE_CHANNEL
            Log.i(TAG, "every channel slot is in use; OSHI cannot add #oshi-bridge")
        }
        l.onOmpFrame = { receive(it) }
        l.onChatReady = {
            // A radio with no slot for #oshi-bridge still chats with MeshCore users: it is connected.
            if (_state.value != ConnState.READY) markPaired()
            setupWatchdog?.cancel()
            _awaitingPin.value = false
            if (_lastError.value != ERROR_NO_FREE_CHANNEL) _lastError.value = null
            _state.value = ConnState.READY
        }
    }

    /** The slot "#oshi-bridge" lives in, or null while unknown / when every slot is taken. */
    val channelIdx: StateFlow<Int?> get() = link.channelIdx
    /** The radio's contact table: MeshCore users (and repeaters / rooms) it has heard adverts from. */
    val contacts: StateFlow<List<MeshCore.Contact>> get() = link.contacts
    /** The owner's channels (named slots), "#oshi-bridge" left out. */
    val channels: StateFlow<List<MeshCore.ChannelInfo>> get() = link.channels
    /** MeshCore users can be written to: every slot read (with or without "#oshi-bridge"). */
    val chatReady: StateFlow<Boolean> get() = link.chatReady
    /** Public-key hex → phone clock (ms) of the last advert heard this session, for the "online" count. */
    val heardAt: StateFlow<Map<String, Long>> get() = link.heardAt
    /** How MeshCore users see this radio. */
    val selfName: StateFlow<String?> get() = link.selfName
    val pendingTexts: StateFlow<Set<String>> get() = link.pendingTexts
    val inboundText: SharedFlow<MeshCoreInboundText> get() = link.inboundText
    val sendOutcomes: SharedFlow<MeshCoreSendOutcome> get() = link.sendOutcomes
    val joinResults: SharedFlow<Pair<Int, Boolean>> get() = link.joinResults
    val advertResults: SharedFlow<Boolean> get() = link.advertResults

    /** A contact or channel the person picked in settings, to be listed among their chats. key → name. */
    private val _conversationRequests = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)
    val conversationRequests: SharedFlow<Pair<String, String>> = _conversationRequests.asSharedFlow()

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Repeat OSHI frames between this radio and a Meshtastic one ([LoRaCrossBridge]). On by default, like iOS. */
    private val _crossBridgeEnabled = MutableStateFlow(prefs.getBoolean(CROSS_BRIDGE_KEY, true))
    val crossBridgeEnabled: StateFlow<Boolean> = _crossBridgeEnabled.asStateFlow()

    fun setCrossBridgeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(CROSS_BRIDGE_KEY, enabled).apply()
        _crossBridgeEnabled.value = enabled
    }

    val isReady: Boolean get() = _state.value == ConnState.READY && link.session.isReady

    val isPaired: Boolean get() = prefs.getString(PAIRED_KEY, null) != null

    /** The paired radio's name, connected or not. */
    val pairedName: String? get() = if (isPaired) prefs.getString(PAIRED_NAME_KEY, null) else null

    // ─────────────────────────── Internals ───────────────────────────

    private val bluetoothManager: BluetoothManager? =
        ContextCompat.getSystemService(appContext, BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val reassembler = OmpReassembler()

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var pendingPairId: String? = null
    private var scanning = false
    private var reconnectJob: Job? = null
    private var datagramJob: Job? = null
    private var setupWatchdog: Job? = null

    private val gattMutex = Mutex()
    private var writeAck: CompletableDeferred<Boolean>? = null
    private var descriptorAck: CompletableDeferred<Boolean>? = null
    private var bondAck: CompletableDeferred<Boolean>? = null

    /** SEND_CHANNEL_DATA commands waiting for their slot on the air, paced [SEND_GAP_MS] apart. */
    private val datagrams = Channel<ByteArray>(Channel.UNLIMITED)

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    if (adapter?.isEnabled == true) reconnectIfPaired() else { teardown(); _state.value = ConnState.OFF }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    // Only OUR radio's bond: headphones pairing at the same time must not resolve it.
                    @Suppress("DEPRECATION")
                    val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (dev != null && gatt != null && dev.address != gatt?.device?.address) return
                    val s = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    if (s == BluetoothDevice.BOND_BONDING) _awaitingPin.value = true
                    if (s == BluetoothDevice.BOND_BONDED) bondAck?.complete(true)
                    else if (s == BluetoothDevice.BOND_NONE) bondAck?.complete(false)
                }
            }
        }
    }

    private fun start() {
        ContextCompat.registerReceiver(
            appContext,
            adapterReceiver,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (adapter?.isEnabled == true) reconnectIfPaired()
    }

    private fun hasPermission(p: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(appContext, p) == PackageManager.PERMISSION_GRANTED
    }

    // ─────────────────────────── Pairing ───────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handleDiscovery(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handleDiscovery(it) }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            if (_state.value == ConnState.SCANNING) _state.value = ConnState.OFF
        }
    }

    fun startScan() {
        if (adapter?.isEnabled != true || !hasPermission(Manifest.permission.BLUETOOTH_SCAN) || scanning) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        _discovered.value = emptyList()
        _state.value = ConnState.SCANNING
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
            .onFailure { _lastError.value = it.message }
        scanning = true
    }

    fun stopScan() {
        if (scanning && hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        }
        scanning = false
        if (_state.value == ConnState.SCANNING) _state.value = ConnState.OFF
    }

    private fun handleDiscovery(result: ScanResult) {
        val address = result.device?.address ?: return
        if (_discovered.value.any { it.id == address }) return
        val name = runCatching { result.device.name }.getOrNull() ?: result.scanRecord?.deviceName ?: "MeshCore"
        _discovered.value = (_discovered.value + Device(address, name, result.rssi)).sortedByDescending { it.rssi }
    }

    fun connect(id: String, autoConnect: Boolean = false) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        val device = runCatching { adapter?.getRemoteDevice(id) }.getOrNull() ?: return
        stopScan()
        teardown()
        _lastError.value = null
        _state.value = ConnState.CONNECTING
        pendingPairId = id
        gatt = device.connectGatt(appContext, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun forget() {
        pendingPairId = null
        prefs.edit().remove(PAIRED_KEY).remove(PAIRED_NAME_KEY).apply()
        teardown()
        _state.value = ConnState.OFF
        _lastError.value = null
    }

    private fun reconnectIfPaired() {
        val id = prefs.getString(PAIRED_KEY, null) ?: return
        if (gatt != null || !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        connect(id, autoConnect = true)
    }

    private fun teardown() {
        reconnectJob?.cancel(); reconnectJob = null
        datagramJob?.cancel(); datagramJob = null
        setupWatchdog?.cancel(); setupWatchdog = null
        _awaitingPin.value = false
        while (datagrams.tryReceive().isSuccess) Unit
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null; rxChar = null; txChar = null
        writeAck?.complete(false); writeAck = null
        descriptorAck?.complete(false); descriptorAck = null
        bondAck?.complete(false); bondAck = null
        _connectedName.value = null
        // Whatever was waiting on this link fails now (FAILED → tap to retry), not never.
        link.linkDown()
    }

    private fun handleDisconnected() {
        val wasUp = _state.value == ConnState.READY
        teardown()
        _state.value = ConnState.OFF
        // Walked out of range or the radio rebooted: keep trying, the way the Meshtastic side does.
        if (isPaired) {
            if (wasUp) _lastError.value = ERROR_CONNECTION_LOST
            reconnectJob = scope.launch { delay(3_000); reconnectIfPaired() }
        }
    }

    /** Pairing or setup went wrong on a radio that was never paired: stop, say why, do not loop. */
    private fun fail(error: String) {
        Log.w(TAG, "connection failed: $error")
        pendingPairId = null
        teardown()
        _state.value = ConnState.OFF
        _lastError.value = error
        if (isPaired) reconnectJob = scope.launch { delay(3_000); reconnectIfPaired() }
    }

    // ─────────────────────────── GATT ───────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectedName.value = runCatching { g.device.name }.getOrNull() ?: "MeshCore"
                    _state.value = ConnState.CONFIGURING
                    if (!g.requestMtu(REQUESTED_MTU)) g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> handleDisconnected()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE_UUID)
            rxChar = svc?.getCharacteristic(RX_UUID)
            txChar = svc?.getCharacteristic(TX_UUID)
            val tx = txChar
            if (rxChar == null || tx == null) {
                _lastError.value = "lora.error.characteristics_missing"
                handleDisconnected(); return
            }
            scope.launch {
                if (!ensureBonded(g.device)) { fail(ERROR_PAIRING_FAILED); return@launch }
                // Subscribing is what the radio's PIN protects; one retry covers the bond settling.
                if (!enableNotifications(g, tx)) {
                    delay(2_000)
                    // Still refused: Android holds a bond the radio no longer has (reflashed, PIN changed).
                    if (!enableNotifications(g, tx)) { fail(ERROR_PAIRING_FAILED); return@launch }
                }
                link.linkUp()
                setupWatchdog = scope.launch {
                    delay(SETUP_TIMEOUT_MS)
                    if (!link.chatReady.value) fail(ERROR_NO_ANSWER)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descriptorAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == TX_UUID) handleFrame(value)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (c.uuid == TX_UUID) c.value?.let { handleFrame(it) }
        }
    }

    /**
     * MeshCore companion firmware protects its UART with a PIN. Bonding up front shows Android's own PIN
     * prompt once; after that the stack re-encrypts on every reconnect by itself.
     */
    private suspend fun ensureBonded(device: BluetoothDevice): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true
        val ack = CompletableDeferred<Boolean>()
        bondAck = ack
        _awaitingPin.value = true
        // createBond() refusing outright is not a verdict: some radios bond on the first encrypted write instead.
        if (device.bondState == BluetoothDevice.BOND_NONE && !device.createBond()) { bondAck = null; _awaitingPin.value = false; return true }
        val ok = withTimeoutOrNull(BOND_TIMEOUT_MS) { ack.await() }
        bondAck = null
        _awaitingPin.value = false
        // BONDING → NONE is a wrong PIN or a cancelled prompt; a timeout is the prompt left unanswered.
        return ok == true
    }

    private suspend fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean =
        gattMutex.withLock {
            if (!g.setCharacteristicNotification(c, true)) return@withLock false
            val d = c.getDescriptor(CCCD_UUID) ?: return@withLock false
            val ack = CompletableDeferred<Boolean>()
            descriptorAck = ack
            val issued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(d)
                }
            }
            val ok = issued && (withTimeoutOrNull(GATT_TIMEOUT_MS) { ack.await() } ?: false)
            descriptorAck = null
            ok
        }

    private suspend fun gattWrite(data: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = rxChar ?: return false
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return false
        return gattMutex.withLock {
            val ack = CompletableDeferred<Boolean>()
            writeAck = ack
            val issued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    c.value = data
                    g.writeCharacteristic(c)
                }
            }
            val ok = issued && (withTimeoutOrNull(GATT_TIMEOUT_MS) { ack.await() } ?: false)
            writeAck = null
            ok
        }
    }

    private fun handleFrame(f: ByteArray) = link.onFrame(f)

    private fun markPaired() {
        // The handshake completed: NOW this radio is genuinely paired (same rule as the Meshtastic side).
        pendingPairId?.let { prefs.edit().putString(PAIRED_KEY, it).putString(PAIRED_NAME_KEY, _connectedName.value).apply(); pendingPairId = null }
    }

    private fun becomeReady() {
        _state.value = ConnState.READY
        if (_lastError.value != ERROR_NO_FREE_CHANNEL) _lastError.value = null
        markPaired()
        Log.i(TAG, "ready, #oshi-bridge in slot ${link.session.channelIdx}, id ${link.session.nodeId.toString(16)}")
        startDatagramPump()
    }

    // ─────────────────────────── Sending ───────────────────────────

    /**
     * The same entry point as [MeshtasticManager.queueOrSendSecureMessage]: the whole envelope, broadcast,
     * as OMP. True only when frames were queued for a READY radio; media obeys the same opt-in.
     */
    fun sendSecureMessage(message: LoRaWireMessage): Boolean {
        if (!isReady) return false
        val mediaOk = MeshtasticManager.instanceOrNull?.mediaOverLoRaEnabled?.value ?: false
        if (message.carriesMedia && !mediaOk) return false
        val body = OshiMeshProtocol.encodeBody(message.toWireJson())
        val frames = OshiMeshProtocol.dataFrames(
            Random.nextInt(1, Int.MAX_VALUE).toUInt(), CrossBridgeDedup.BROADCAST, body, origin = link.session.nodeId,
        )
        if (frames == null || frames.size > LoRaEnvelope.MAX_CHUNKS) return false
        frames.forEach { sendFrame(it) }
        Log.d(TAG, "→ OSHI msg ${message.id.take(8)} (${frames.size} OMP frames, ${body.size}B)")
        return true
    }

    /** One OMP frame onto MeshCore under our envelope. Also what the cross-network bridge calls. */
    fun sendFrame(frame: ByteArray) {
        if (!isReady) return
        link.session.channelDatagrams(frame).forEach { datagrams.trySend(it) }
    }

    private fun startDatagramPump() {
        if (datagramJob?.isActive == true) return
        datagramJob = scope.launch {
            while (true) {
                val cmd = datagrams.receive()
                link.enqueue(cmd)
                _framesSent.value += 1
                delay(SEND_GAP_MS)
            }
        }
    }

    // ─────────────────────────── MeshCore users (plain MeshCore, not OSHI) ───────────────────────────

    /** MeshCore users can be written to right now. */
    val isChatReady: Boolean get() = _state.value == ConnState.READY && link.isChatReady

    fun channelSlotFor(channelKey: String): Int? = link.channelSlotFor(channelKey)

    fun joinHashtagChannel(name: String): MeshCoreLink.JoinResult = link.joinHashtagChannel(name)

    fun joinPrivateChannel(name: String, secret: ByteArray): MeshCoreLink.JoinResult = link.joinPrivateChannel(name, secret)

    fun refreshContacts() = link.refreshContacts()

    /** Announce this radio so MeshCore users nearby can add it. */
    fun sendSelfAdvert(): Boolean = link.sendSelfAdvert()

    /** List [key] among the person's chats under [name] (MessageRepository creates the row). */
    fun requestConversation(key: String, name: String) {
        _conversationRequests.tryEmit(key to name)
    }

    // ─────────────────────────── Receiving ───────────────────────────

    private fun receive(e: MeshCoreSession.Event.Frame) {
        if (!link.isUp) return
        LoRaCrossBridge.heardOnMeshCore(e.frame, e.parsed)
        val body = reassembler.accept(e.parsed) ?: return
        val json = OshiMeshProtocol.decodeBody(body) ?: return
        _messagesReceived.value += 1
        // The Meshtastic manager owns the envelope pipeline (recipient filter, receivedMessages). It is
        // always constructed by MessageRepository.startLoRaBridge, radio or not.
        MeshtasticManager.getInstance(appContext).deliverForeignEnvelope(json, e.parsed.origin, e.parsed.count)
    }
}

/**
 * When this phone holds a Meshtastic/OSHI radio AND a MeshCore radio, every OSHI DATA frame heard on one
 * side is repeated on the other — what oshi-meshcore-bridge does on a Raspberry Pi, carried in a pocket.
 * Frames keep their origin and msgId, so receivers dedupe them like any relayed frame; a frame already
 * repeated is never repeated again ([CrossBridgeDedup]), which stops two bridging phones ping-ponging it.
 * Port of LoRaCrossBridge in OSHI/MeshCoreRadio.swift.
 */
object LoRaCrossBridge {
    private val dedup = CrossBridgeDedup()

    fun heardOnMeshtastic(frame: ByteArray, f: OshiMeshProtocol.DataFrame) {
        val mc = MeshCoreManager.instanceOrNull ?: return
        if (!mc.crossBridgeEnabled.value || !mc.isReady || !dedup.firstTime(f)) return
        mc.sendFrame(frame)
    }

    fun heardOnMeshCore(frame: ByteArray, f: OshiMeshProtocol.DataFrame) {
        val mc = MeshCoreManager.instanceOrNull ?: return
        val mt = MeshtasticManager.instanceOrNull ?: return
        if (!mc.crossBridgeEnabled.value || !mt.isReady || !dedup.firstTime(f)) return
        mt.relayOmpFrame(frame)
    }
}
