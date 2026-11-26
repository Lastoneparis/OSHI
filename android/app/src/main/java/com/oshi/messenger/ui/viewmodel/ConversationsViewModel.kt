package com.oshi.messenger.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.data.model.Conversation
import com.oshi.messenger.data.repository.MessageRepository
import com.oshi.messenger.network.location.ActiveLiveShare
import com.oshi.messenger.network.location.LocationSharingManager
import com.oshi.messenger.network.mesh.MeshNetworkManager
import com.oshi.messenger.network.mesh.MeshState
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.service.lora.LoRaOnline
import com.oshi.messenger.service.lora.MeshtasticManager
import androidx.datastore.preferences.core.edit
import com.oshi.messenger.ui.viewmodel.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One of MY live location shares, resolved for the top-bar counter. */
data class LiveShareEntry(
    val share: ActiveLiveShare,
    /** Contact alias or group name; null when unknown (caller shows a short key). */
    val displayName: String?,
    /** True when the share was started in a group chat — opens the group, not a 1:1. */
    val isGroup: Boolean
)

/** What the top-bar antenna shows. `loraOnline` is null while no radio is connected. */
data class MeshBadgeState(
    val meshPeers: Int,
    val loraOnline: Int?,
    /**
     * __SEARCHING_IS_NOT_ALWAYS_TRUE_2026_10_28__ (N15) Why nothing is being found, or
     * null when the radios really are looking. See [MeshReadiness].
     */
    val blocker: com.oshi.messenger.data.model.MeshReadiness.Reason? = null,
) {
    val connected: Boolean get() = meshPeers > 0 || loraOnline != null
}

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val meshManager: MeshNetworkManager,
    locationSharingManager: LocationSharingManager,
    groupManager: GroupManager,
    // __SEARCHING_IS_NOT_ALWAYS_TRUE_2026_10_28__ (N15) now a property: `meshBlocker()`
    // reads the live radio state from it on every badge tick.
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    /**
     * __PIN_A_CONVERSATION_2026_10_28__ (N18) Conversations the user keeps at the top.
     *
     * A plain DataStore string set: these are ids the user already sees on screen, not
     * secrets, and the contact list they name is stored unsealed beside them.
     */
    private val pinnedKey = androidx.datastore.preferences.core.stringSetPreferencesKey(
        "pinned_conversation_ids"
    )

    val pinnedConversations: StateFlow<Set<String>> = appContext.dataStore.data
        .map { it[pinnedKey] ?: emptySet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Pin or unpin, and persist. Idempotent per tap; the flow is the single truth. */
    fun togglePinned(conversationId: String) {
        viewModelScope.launch {
            appContext.dataStore.edit { prefs ->
                prefs[pinnedKey] = com.oshi.messenger.data.model.ConversationPinOrder
                    .toggled(conversationId, prefs[pinnedKey] ?: emptySet())
            }
        }
    }

    /**
     * __MUTE_A_CONVERSATION_2026_11_13__ (Anatole N8) One-to-one threads that do not
     * ring. Normalized keys — test membership with [isMuted], not `in`.
     */
    private val mutes = com.oshi.messenger.data.model.ConversationMutes.get(appContext)
    val mutedConversations: StateFlow<Set<String>> = mutes.keys

    fun isMuted(conversationId: String, muted: Set<String>): Boolean =
        com.oshi.messenger.data.model.ConversationKeySet.normalize(conversationId) in muted

    fun toggleMuted(conversationId: String) {
        mutes.toggle(conversationId)
    }

    val conversations: StateFlow<List<Conversation>> = combine(
        messageRepository.getConversations(),
        pinnedConversations,
    ) { convs, pinned ->
        // __PIN_A_CONVERSATION_2026_10_28__ The repository already ordered this by
        // recency; the pin pass is a STABLE sort, so it moves the pinned rows up and
        // leaves every other decision alone.
        com.oshi.messenger.data.model.ConversationPinOrder
            .apply(convs, pinned) { it.participantPublicKey }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val meshStatus: StateFlow<String> = meshManager.meshState
        .map { state ->
            when (state) {
                MeshState.CONNECTED -> "connected"
                MeshState.DISCOVERING, MeshState.ADVERTISING -> "searching"
                else -> "offline"
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "offline")

    val connectedPeers: StateFlow<Int> = meshManager.connectedPeers
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /**
     * __LIVE_SHARE_COUNTER_2026_09_22__ My running live shares, most recent last,
     * with the name to show in the counter's menu.
     */
    val liveShares: StateFlow<List<LiveShareEntry>> = combine(
        locationSharingManager.activeLiveShares,
        conversations,
        groupManager.groups
    ) { shares, convs, groups ->
        shares.map { share ->
            val group = groups.firstOrNull { it.id == share.conversationId }
            val conv = convs.firstOrNull { it.participantPublicKey == share.conversationId }
            LiveShareEntry(
                share = share,
                displayName = group?.name ?: conv?.participantAlias?.takeIf { it.isNotBlank() },
                isGroup = group != null
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val lora = MeshtasticManager.getInstance(appContext)
    private val meshCore = com.oshi.messenger.service.lora.MeshCoreManager.getInstance(appContext)

    /**
     * __MESHCORE_INTEROP_2026_11_25__ A MeshCore radio counts too: READY (chatting) → the MeshCore users heard
     * in the last [LoRaOnline]-style window; null while not connected. Folded into one flow so the badge's
     * `combine` keeps its five inputs.
     */
    private val loraState = combine(lora.state, meshCore.state, meshCore.heardAt) { mt, mc, heard ->
        Triple(mt, mc == com.oshi.messenger.service.lora.MeshCoreManager.ConnState.READY, heard)
    }

    /** Re-evaluated every 30 s so a LoRa node ages out of the count on time. */
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }

    /**
     * __TOOLBAR_DRIFT_2026_09_22__ The antenna's numbers. A DROP in mesh peers is
     * only shown once it has lasted [PEER_DROP_GRACE_MS]: the peer list flaps on
     * every staleness sweep, and each flap used to redraw the top bar.
     */
    private val debouncedPeers: StateFlow<Int> = run {
        val shown = MutableStateFlow(0)
        viewModelScope.launch {
            meshManager.connectedPeers.map { it.size }.distinctUntilChanged().collectLatest { count ->
                if (count < shown.value) delay(PEER_DROP_GRACE_MS)
                shown.value = count
            }
        }
        shown.asStateFlow()
    }

    val meshBadge: StateFlow<MeshBadgeState> = combine(
        debouncedPeers,
        loraState,
        lora.nodes,
        lora.myNodeNum,
        clock
    ) { peers, (state, meshCoreReady, heard), nodes, myNum, now ->
        val meshtastic = if (state == MeshtasticManager.ConnState.READY) LoRaOnline.count(nodes, myNum, now) else null
        val meshCoreHeard = if (meshCoreReady) heard.values.count { now - it < MESHCORE_ONLINE_MS } else null
        MeshBadgeState(
            meshPeers = peers,
            loraOnline = if (meshtastic == null && meshCoreHeard == null) null
                else (meshtastic ?: 0) + (meshCoreHeard ?: 0),
            // __SEARCHING_IS_NOT_ALWAYS_TRUE_2026_10_28__ (N15) Read fresh on every tick:
            // a person who turns Bluetooth back on must see the badge stop blaming it
            // without reopening the app.
            blocker = meshBlocker(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MeshBadgeState(0, null))

    /**
     * __SEARCHING_IS_NOT_ALWAYS_TRUE_2026_10_28__ (N15) The one thing stopping the scan.
     *
     * The API-level branching lives HERE rather than in [MeshReadiness] because the rule is
     * worth testing and `Build.VERSION` is not: the pure object takes three booleans, and
     * this resolves them for the device it is running on.
     */
    private fun meshBlocker(): com.oshi.messenger.data.model.MeshReadiness.Reason? {
        val hasPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.BLUETOOTH_SCAN
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        val bluetoothOn = runCatching {
            (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
                ?.adapter?.isEnabled == true
        }.getOrDefault(true)   // unknown is not a complaint: never blame a radio we cannot read
        val wifiOn = runCatching {
            (appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
                ?.isWifiEnabled == true
        }.getOrDefault(true)

        return com.oshi.messenger.data.model.MeshReadiness.firstBlocker(
            hasNearbyPermission = hasPermission,
            bluetoothOn = bluetoothOn,
            wifiOn = wifiOn,
        )
    }

    companion object {
        const val PEER_DROP_GRACE_MS = 8_000L
        /** A MeshCore user counts as reachable for this long after their last advert (adverts are hourly-ish). */
        const val MESHCORE_ONLINE_MS = 30 * 60_000L
    }
}
