package com.oshi.messenger.devsync

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.oshi.messenger.BuildConfig
import com.oshi.messenger.data.local.AppDatabase
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.v2.CoverSettings
import com.oshi.messenger.network.v2.V2Signer
import com.oshi.messenger.network.v2.devsync.ApplyResult
import com.oshi.messenger.network.v2.devsync.DevSyncConfig
import com.oshi.messenger.network.v2.devsync.DevSyncEngine
import com.oshi.messenger.network.v2.devsync.DevSyncListener
import com.oshi.messenger.network.v2.devsync.DevSyncNet
import com.oshi.messenger.network.v2.devsync.DevSyncSession
import com.oshi.messenger.network.v2.devsync.LinkedDevice
import com.oshi.messenger.network.v2.devsync.PairingRequest
import com.oshi.messenger.network.v2.devsync.SessionInfo
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.service.MultiDeviceSyncManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What the Linked devices screen renders. */
data class DevSyncUiState(
    /** Effective gate: server `devsync_enabled` or the local debug override. */
    val enabled: Boolean = false,
    val serverEnabled: Boolean = false,
    /** null = follow the server; true/false = the local debug override. */
    val localOverride: Boolean? = null,
    val running: Boolean = false,
    val deviceId: String? = null,
    val deviceName: String = "",
    val linked: List<LinkedDevice> = emptyList(),
    val pending: List<PairingRequest> = emptyList(),
    val sessions: List<SessionInfo> = emptyList(),
    val lastAlert: String? = null,
    val legacyPurged: Boolean = false,
)

/**
 * __DEVSYNC_DIRECT_2026_09_22__ Direct own-device sync on Android (docs/OSHI_DEVICE_SYNC_DIRECT.md).
 *
 *  - Gate: `/v2/config.devsync_enabled` (default OFF) OR the local debug override. Relay and LAN
 *    each have their own server lever (`devsync_relay_enabled`, `devsync_lan_enabled`, default on).
 *  - When the gate is on: the legacy 30 s `/api/sync` uploads/pulls STOP
 *    ([MultiDeviceSyncManager.setDirectSyncActive]) and `POST /v2/devsync/purge-legacy` is called
 *    once (signed); the relay socket also purges on its first authentication.
 *  - The engine runs while the app is in the FOREGROUND (same rule as iOS, design §9); an engine is
 *    single-use, so each foreground builds a fresh one over the persistent state.
 */
@Singleton
class DevSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    // Lazy: the manager is injected into Application.onCreate; none of these may be built there.
    private val database: dagger.Lazy<AppDatabase>,
    private val groupManager: dagger.Lazy<GroupManager>,
    private val cryptoManager: dagger.Lazy<CryptoManager>,
    private val signer: dagger.Lazy<V2Signer>,
    private val coverSettings: dagger.Lazy<CoverSettings>,
    private val multiDeviceSyncManager: dagger.Lazy<MultiDeviceSyncManager>,
    /** Design §15.3: the per-device mailbox signs new devices in during devsync pairing. */
    private val deviceMailbox: dagger.Lazy<com.oshi.messenger.network.v2.DeviceMailboxManager>,
    /** __DEVSYNC_PROFILE_2026_09_23__ a synced profile picture is adopted into the repository's copy. */
    private val messageRepository: dagger.Lazy<com.oshi.messenger.data.repository.MessageRepository>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Mutex()
    @Volatile private var engine: DevSyncEngine? = null
    @Volatile private var foreground = false
    @Volatile private var serverFlags = ServerFlags()
    @Volatile private var flagsAt = 0L
    private var installed = false

    private val _state = MutableStateFlow(DevSyncUiState(localOverride = readOverride(), legacyPurged = prefs.getBoolean(KEY_PURGED, false)))
    val state: StateFlow<DevSyncUiState> = _state.asStateFlow()

    private data class ServerFlags(val enabled: Boolean = false, val relay: Boolean = true, val lan: Boolean = true)

    private val http by lazy {
        OkHttpClient.Builder()
        .addInterceptor(coverSettings.get().interceptor())
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    }

    /** Called once from Application.onCreate. */
    fun install() {
        if (installed) return
        installed = true
        // The legacy 30 s timer starts ~5 s after launch: suppress it from the LAST KNOWN gate
        // at once, before /v2/config has answered, so an enabled account never uploads again.
        if (readOverride() ?: prefs.getBoolean(KEY_LAST_SERVER, false)) {
            scope.launch { runCatching { multiDeviceSyncManager.get().setDirectSyncActive(true) } }
        }
        Handler(Looper.getMainLooper()).post {
            runCatching {
                ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) { foreground = true; refresh(force = false) }
                    override fun onStop(owner: LifecycleOwner) { foreground = false; scope.launch { lock.withLock { stopEngine() } } }
                })
            }.onFailure { Log.w(TAG, "lifecycle observer not registered: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------ gate

    private fun readOverride(): Boolean? = when (prefs.getString(KEY_OVERRIDE, null)) {
        "on" -> true
        "off" -> false
        else -> null
    }

    /** Debug override: true/false force the feature on/off on this install; null follows the server. */
    fun setLocalOverride(value: Boolean?) {
        prefs.edit().apply { if (value == null) remove(KEY_OVERRIDE) else putString(KEY_OVERRIDE, if (value) "on" else "off") }.apply()
        _state.value = _state.value.copy(localOverride = value)
        refresh(force = false)
    }

    fun refresh(force: Boolean = true) {
        scope.launch {
            lock.withLock {
                if (force || System.currentTimeMillis() - flagsAt > FLAGS_TTL_MS) {
                    serverFlags = fetchFlags()
                    flagsAt = System.currentTimeMillis()
                }
                applyGate()
            }
        }
    }

    private fun fetchFlags(): ServerFlags = try {
        http.newCall(Request.Builder().url("${BuildConfig.VPS_URL}/v2/config").get().build()).execute().use { r ->
            if (r.code !in 200..299) return ServerFlags()
            val o = JSONObject(r.body?.string().orEmpty())
            prefs.edit().putBoolean(KEY_LAST_SERVER, o.optBoolean("devsync_enabled", false)).apply()
            ServerFlags(
                enabled = o.optBoolean("devsync_enabled", false),
                relay = o.optBoolean("devsync_relay_enabled", true),
                lan = o.optBoolean("devsync_lan_enabled", true),
            )
        }
    } catch (e: Exception) {
        serverFlags // keep the last answer; a first failure stays OFF (fail closed)
    }

    private suspend fun applyGate() {
        val enabled = readOverride() ?: serverFlags.enabled
        _state.value = _state.value.copy(enabled = enabled, serverEnabled = serverFlags.enabled, localOverride = readOverride())
        multiDeviceSyncManager.get().setDirectSyncActive(enabled)
        if (!enabled) { stopEngine(); return }
        purgeLegacyOnce()
        if (foreground) startEngine() else stopEngine()
    }

    /** Design §12 phase 1(b): erase this account's legacy `/api/sync` mirror and `/v2/sync` archive, once. */
    private fun purgeLegacyOnce() {
        if (prefs.getBoolean(KEY_PURGED, false)) return
        try {
            val path = "/v2/devsync/purge-legacy"
            val headers = signer.get().sign("POST", path, ByteArray(0), withUserHeader = true)
            val req = Request.Builder().url("${BuildConfig.VPS_URL}$path").post(ByteArray(0).toRequestBody(null))
                .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            http.newCall(req).execute().use { r ->
                // 200 complete; 207 = v2 archive erased, legacy queue unreachable (the relay socket retries it).
                if (r.code == 200 || r.code == 207) {
                    prefs.edit().putBoolean(KEY_PURGED, r.code == 200).apply()
                    _state.value = _state.value.copy(legacyPurged = r.code == 200)
                    Log.i(TAG, "legacy sync purge: HTTP ${r.code}")
                } else {
                    Log.w(TAG, "legacy sync purge refused: HTTP ${r.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "legacy sync purge failed: ${e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ engine

    private fun startEngine() {
        if (engine?.isRunning == true) return
        val ik = try { cryptoManager.get().exportPrivateKeyBytes().copyOf() } catch (e: Exception) { return } // no identity yet
        val flags = serverFlags
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        val cfg = DevSyncConfig(
            deviceName = Build.MODEL ?: "Android",
            platform = "android",
            appVersion = BuildConfig.VERSION_NAME,
            cacheDir = context.cacheDir,
            network = { DevSyncNet(metered = cm?.isActiveNetworkMetered == true, lowPower = pm?.isPowerSaveMode == true) },
        )
        val syncStore = AndroidDevSyncStore(
            context, database.get(), groupManager.get(),
            selfKey = { cryptoManager.get().getPublicKeyString() },
            adoptProfilePicture = { bytes -> runCatching { messageRepository.get().devSyncAdoptProfilePicture(bytes) } },
        )
        val e = try {
            DevSyncEngine(
                cfg, ik, AndroidDeviceKeyProvider(context),
                syncStore,
                AndroidDevSyncStateStore(File(context.filesDir, "devsync")),
            ) { line -> Log.d(TAG, line) }
        } catch (t: Throwable) {
            Log.e(TAG, "devsync engine not started: ${t.javaClass.simpleName}: ${t.message}")
            return
        } finally {
            ik.fill(0)
        }
        e.addListener(listener)
        // __DEVSYNC_PROFILE_2026_09_23__ the profile picture is fetched by reference (MEDIA_GET).
        syncStore.mediaRequester = { sha, size, conv, id, ts -> e.pendingMedia.add(sha, size, conv, id, ts) }
        // Per-device mailbox (design §15.3, `DeviceMailboxManager`): our public `dsk` rides in HELLO,
        // the peer's is noted before the user taps Allow, and the signed approval rides in APPROVAL.
        runCatching {
            val mailbox = deviceMailbox.get()
            // __DEVSYNC_DEVICE_AUTH_2026_09_23__ `dsk` is now a first-class HELLO field (Hello.dsk, vectors).
            e.deviceDsk = { mailbox.myDskB64() }
            e.onPeerDsk = { peerId, dsk -> mailbox.notePeerDsk(peerId, dsk) }
            e.approvalExtras = { peerId, peerDk -> mailbox.approvalExtras(peerId, peerDk) }
            e.onApprovalReceived = { peerId, body -> mailbox.onApprovalReceived(peerId, body) }
        }.onFailure { Log.w(TAG, "device mailbox hooks not wired: ${it.javaClass.simpleName}") }
        e.start(
            lanDiscovery = if (flags.lan || readOverride() == true) NsdLanDiscovery(context) else null,
            // __DEVSYNC_DEVICE_AUTH_2026_09_23__ the upgrade is also signed by this install's `dsk`.
            relayConnector = if (flags.relay || readOverride() == true) OkHttpRelayConnector(signer.get(), BuildConfig.VPS_URL) {
                runCatching { deviceMailbox.get().keys()?.relayCredentials() }.getOrNull()
            } else null,
        )
        engine = e
        _state.value = _state.value.copy(running = true, deviceId = e.deviceId, deviceName = cfg.deviceName, linked = e.linkedDevices())
        watchJob?.cancel()
        watchJob = scope.launch { mirrorLocalChanges(e, syncStore) }
        startWriteHooks(e, syncStore)
    }

    // __DEVSYNC_LIVE_UPGRADE_2026_09_23__ near-instant mirroring (design §9): every write to the
    // synced tables / group state tells the engine at once; it debounces (~150 ms, max 600 ms) and
    // pushes the new rows as LIVE + a SUMMARY. The poll above stays as the safety net.
    private var roomHook: androidx.room.InvalidationTracker.Observer? = null
    private var groupHookJob: kotlinx.coroutines.Job? = null
    private var profileUnhook: (() -> Unit)? = null

    private fun startWriteHooks(e: DevSyncEngine, syncStore: AndroidDevSyncStore) {
        stopWriteHooks()
        // __DEVSYNC_PROFILE_EVENTS_2026_09_23__ a profile edit is pushed at once, not at the next 5 s poll.
        profileUnhook = runCatching { syncStore.watchProfileChanges(scope) { if (engine === e) e.onLocalStateChanged() } }
            .onFailure { Log.w(TAG, "devsync profile hook not installed: ${it.javaClass.simpleName}") }.getOrNull()
        runCatching {
            val obs = object : androidx.room.InvalidationTracker.Observer(SYNCED_TABLES) {
                override fun onInvalidated(tables: Set<String>) { if (engine === e) e.onLocalStateChanged() }
            }
            database.get().invalidationTracker.addObserver(obs)
            roomHook = obs
        }.onFailure { Log.w(TAG, "devsync Room hook not installed: ${it.javaClass.simpleName}") }
        groupHookJob = scope.launch {
            val gm = groupManager.get()
            launch { gm.groups.drop(1).collect { if (engine === e) e.onLocalStateChanged() } }
            launch { gm.groupMessages.drop(1).collect { if (engine === e) e.onLocalStateChanged() } }
            // __BLOCK_SYNC_LWW_2026_09_24__ the durable block list is SharedPreferences, not Room:
            // a block/unblock of a key with no contact row moved no table, so nothing was pushed.
            launch {
                com.oshi.messenger.service.BlockedKeysStore.get(context).version.drop(1)
                    .collect { if (engine === e) e.onLocalStateChanged() }
            }
        }
    }

    private fun stopWriteHooks() {
        roomHook?.let { obs -> runCatching { database.get().invalidationTracker.removeObserver(obs) } }
        roomHook = null
        groupHookJob?.cancel()
        groupHookJob = null
        profileUnhook?.let { runCatching { it() } }
        profileUnhook = null
    }

    private var watchJob: kotlinx.coroutines.Job? = null

    /**
     * Near-live mirroring (design §9) without hooking every write path: every few seconds a cheap
     * fingerprint of the newest 1:1 row and of the group state; when it moves, every open session
     * re-runs the diff and the peer pulls exactly what changed. (Status changes on older rows reach
     * the other device at the next session or "Sync now".)
     */
    private suspend fun mirrorLocalChanges(e: DevSyncEngine, syncStore: AndroidDevSyncStore) {
        var last: String? = null
        while (engine === e) {
            kotlinx.coroutines.delay(MIRROR_POLL_MS)
            // __DEVSYNC_PROFILE_2026_09_23__ stamp a profile edit close to when it was made (§8.6 updatedAt).
            val profileEdited = runCatching { syncStore.refreshProfileStamp() }.getOrDefault(false)
            val fp = runCatching {
                val m = database.get().messageDao().getLatestMessage()
                val gm = groupManager.get()
                // __DEVSYNC_READ_STATE_2026_09_23__ read state (unread totals) and leaves move the fingerprint too.
                val unreadDirect = database.get().messageDao().getUnreadMessages().first().size
                val unreadGroups = gm.groupMessages.value.values.sumOf { l -> l.count { !it.isRead && !it.isFromMe } }
                "${m?.id}|${m?.timestamp}|${m?.isRead}|${m?.isDelivered}|${m?.editedAt}|${m?.isDeletedForEveryone}|" +
                    "${gm.groupMessages.value.values.sumOf { it.size }}|${gm.groups.value.hashCode()}|$unreadDirect|$unreadGroups|" +
                    "${gm.devSyncLeaves().size}"
            }.getOrNull() ?: continue
            if (profileEdited && last != null && fp == last) e.onLocalStateChanged()
            if (last != null && fp != last) e.onLocalStateChanged()
            last = fp
        }
    }

    private fun stopEngine() {
        val e = engine ?: return
        engine = null
        watchJob?.cancel()
        stopWriteHooks()
        e.removeListener(listener)
        runCatching { e.stop() }
        _state.value = _state.value.copy(running = false, sessions = emptyList(), pending = emptyList())
    }

    private val listener = object : DevSyncListener {
        override fun onDevicesChanged() = snapshot()
        override fun onPairingRequest(request: PairingRequest) = snapshot()
        override fun onPairingResolved(peerId: String, linked: Boolean) = snapshot()
        override fun onSessionChanged(info: SessionInfo) = snapshot()
        override fun onSynced(peerId: String, stats: DevSyncSession.SessionStats) = snapshot()
        override fun onApplied(peerId: String, conversationId: String, result: ApplyResult) {}
        override fun onAlert(kind: String, deviceName: String) {
            _state.value = _state.value.copy(lastAlert = "$kind:$deviceName")
            Log.i(TAG, "devsync alert $kind")
        }
    }

    /** Listener callbacks run ON the sync thread: snapshot from another thread (engine.call would deadlock). */
    private fun snapshot() {
        scope.launch {
            val e = engine ?: return@launch
            runCatching {
                _state.value = _state.value.copy(
                    linked = e.linkedDevices(), pending = e.pendingApprovals(), sessions = e.sessionsInfo(),
                )
            }
        }
    }

    // ------------------------------------------------------------------ UI actions

    fun approve(peerId: String, allow: Boolean) { engine?.approve(peerId, allow); snapshot() }
    fun revoke(peerId: String) { engine?.revoke(peerId); snapshot() }
    fun syncNow() { engine?.syncNow() ?: refresh(force = true) }

    companion object {
        private const val TAG = "DevSync"
        private const val PREFS = "oshi_devsync"
        private const val KEY_OVERRIDE = "oshi_devsync_override"
        private const val KEY_PURGED = "legacy_purged_v1"
        private const val KEY_LAST_SERVER = "last_server_devsync_enabled"
        private const val FLAGS_TTL_MS = 60_000L
        private const val MIRROR_POLL_MS = 5_000L
        /** Room tables whose writes are mirrored at once (__DEVSYNC_LIVE_UPGRADE_2026_09_23__). */
        private val SYNCED_TABLES = arrayOf("messages", "message_reactions", "contacts", "groups", "group_messages")
    }
}
