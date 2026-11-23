package com.oshi.messenger.devsync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.oshi.messenger.data.local.atrest.AtRestKey
import com.oshi.messenger.data.local.atrest.SealedFile
import com.oshi.messenger.network.v2.V2Signer
import com.oshi.messenger.network.v2.devicemailbox.DeviceKeys
import com.oshi.messenger.network.v2.devsync.DevSyncRelayAuth
import com.oshi.messenger.network.v2.devsync.DevSyncStateStore
import com.oshi.messenger.network.v2.devsync.DeviceKeyProvider
import com.oshi.messenger.network.v2.devsync.LanDiscovery
import com.oshi.messenger.network.v2.devsync.RelayConnector
import com.oshi.messenger.network.v2.devsync.RelayDeviceCredentials
import com.oshi.messenger.network.v2.devsync.RelayListener
import com.oshi.messenger.network.v2.devsync.RelaySocket
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/*
 * __DEVSYNC_DIRECT_2026_09_22__ The Android seams of the shared own-device sync core
 * (`network/v2/devsync/`, compiled by Android AND Desktop). Everything Android-specific lives HERE,
 * in `com.oshi.messenger.devsync`, never in `network/v2/devsync/` (Desktop compiles that directory
 * wholesale and has no android.*).
 */

private const val TAG = "DevSync"

/**
 * The per-install device key DK (design §3): 32 random bytes in EncryptedSharedPreferences under a
 * Keystore MasterKey. Never exported; a backup restored onto another phone cannot decrypt it (the
 * Keystore key does not travel), so a restored device simply gets a NEW device key and re-pairs —
 * exactly the design's "ThisDeviceOnly" behaviour.
 */
class AndroidDeviceKeyProvider(private val context: Context) : DeviceKeyProvider {

    override fun loadOrCreate(): ByteArray {
        val prefs = try { prefs() } catch (e: Exception) {
            // Undecryptable (restored from a backup onto a new Keystore): start over.
            Log.w(TAG, "device key store unreadable, creating a new device key: ${e.javaClass.simpleName}")
            context.deleteSharedPreferences(PREFS)
            prefs()
        }
        prefs.getString(KEY, null)?.let { b64 ->
            val raw = runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
            if (raw != null && raw.size == 32) return raw
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        check(prefs.edit().putString(KEY, Base64.getEncoder().encodeToString(raw)).commit()) { "could not persist the device key" }
        return raw
    }

    private fun prefs() = EncryptedSharedPreferences.create(
        context,
        PREFS,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    companion object {
        const val PREFS = "oshi_devsync_device_key"
        private const val KEY = "dk_b64"
    }
}

/** Linked devices + pending media, sealed at rest (OSHISEA1 under the files key). */
class AndroidDevSyncStateStore(private val dir: File) : DevSyncStateStore {
    init { dir.mkdirs() }

    override fun read(name: String): String? = try {
        SealedFile.readText(File(dir, "$name.json"), "devsync:$name", AtRestKey)
    } catch (e: Exception) {
        Log.w(TAG, "devsync state $name unreadable: ${e.javaClass.simpleName}")
        null
    }

    override fun write(name: String, json: String) {
        try {
            SealedFile.writeAtomic(File(dir, "$name.json"), "devsync:$name", AtRestKey, json.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "devsync state $name not saved: ${e.javaClass.simpleName}")
        }
    }
}

/**
 * `GET wss://…/v2/devsync?device=<id>` over OkHttp, account- AND device-signed
 * (__DEVSYNC_DEVICE_AUTH_2026_09_23__, [DevSyncRelayAuth]): the account signature covers sha256(B)
 * instead of an empty body, the per-install `dsk` ([DeviceMailboxManager.keys], the SAME key the
 * per-device mailbox registers) signs `OSHI-DEVICE/1…` over the same B, and every attempt has a fresh
 * nonce. Without device credentials there is no relay (the relay refuses account-only upgrades).
 *
 * Deliberately a PLAIN client, not the v2 clients' cover interceptor: the cover transport rewrites a
 * request into a POST to a cover endpoint, which cannot carry a WebSocket upgrade.
 */
class OkHttpRelayConnector(
    private val signer: V2Signer,
    private val baseUrl: String,
    private val credentials: () -> RelayDeviceCredentials?,
) : RelayConnector {

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override fun connect(deviceIdHex: String, listener: RelayListener): RelaySocket {
        val creds = credentials() ?: throw IllegalStateException("devsync device keys unavailable")
        val headers = DevSyncRelayAuth.upgradeHeaders(signer.userKey(), deviceIdHex, creds, { b ->
            signer.sign("GET", RelayConnector.PATH, b, withUserHeader = true)
        })
        val wsBase = when {
            baseUrl.startsWith("https://") -> "wss://" + baseUrl.removePrefix("https://")
            baseUrl.startsWith("http://") -> "ws://" + baseUrl.removePrefix("http://")
            else -> baseUrl
        }.trimEnd('/')
        val req = Request.Builder().url("$wsBase${RelayConnector.PATH}?device=$deviceIdHex")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        val closed = AtomicBoolean(false)
        fun closedOnce(code: Int, reason: String) { if (closed.compareAndSet(false, true)) listener.onClosed(code, reason) }
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onBinary(bytes.toByteArray())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closedOnce(code, reason)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                closedOnce(response?.code?.let { -it } ?: -1, t.javaClass.simpleName)
        })
        return object : RelaySocket {
            override fun sendBinary(bytes: ByteArray): Boolean = ws.send(bytes.toByteString())
            override fun close() { ws.close(1000, null) }
        }
    }
}

/**
 * `_oshi-devsync._tcp` over NsdManager (design §5.1): a distinct service type from the mesh's
 * `_oshi-mesh._tcp`. TXT `v=1`, `t=<hourly tag>`; the instance name is 8 random hex characters so the
 * advertisement cannot be linked to an account or tracked across hours.
 */
class NsdLanDiscovery(context: Context) : LanDiscovery {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var port = 0
    @Volatile private var onFound: ((String, Int, Map<String, String>) -> Unit)? = null
    private val resolving = AtomicBoolean(false)
    private val queue = ArrayDeque<NsdServiceInfo>()

    /**
     * §5.1 "Acquire a WifiManager.MulticastLock only while discovering". Without it many Wi-Fi drivers
     * filter inbound multicast in power-save, so mDNS answers from the Mac/iPhone never reach NSD on
     * Android < 14 (14+ NsdService holds its own lock for foreground discoveries). The engine only runs
     * in the foreground (DevSyncManager), so start..stop IS the discovery window. Needs
     * CHANGE_WIFI_MULTICAST_STATE (normal, install-time).
     */
    private val multicastLock: WifiManager.MulticastLock? = runCatching {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("oshi-devsync").apply { setReferenceCounted(false) }
    }.getOrNull()

    override fun start(port: Int, txt: Map<String, String>, onFound: (String, Int, Map<String, String>) -> Unit) {
        this.port = port
        this.onFound = onFound
        runCatching { multicastLock?.acquire() }.onFailure { Log.w(TAG, "multicast lock: ${it.javaClass.simpleName}") }
        register(txt)
        val d = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "NSD discovery failed: $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(queue) { queue.addLast(serviceInfo) }
                resolveNext()
            }
        }
        discovery = d
        runCatching { nsd.discoverServices("${LanDiscovery.SERVICE_TYPE}.", NsdManager.PROTOCOL_DNS_SD, d) }
    }

    /** NsdManager resolves one service at a time (FAILURE_ALREADY_ACTIVE otherwise). */
    private fun resolveNext() {
        if (!resolving.compareAndSet(false, true)) return
        val next = synchronized(queue) { queue.removeFirstOrNull() }
        if (next == null) { resolving.set(false); return }
        @Suppress("DEPRECATION")
        try { nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolving.set(false); resolveNext()
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = pickHost(info)
                val attrs = info.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) ?: "" }
                if (host != null && info.port > 0) onFound?.invoke(host, info.port, attrs)
                resolving.set(false); resolveNext()
            }
        }) } catch (e: Exception) {
            // A synchronous throw used to leave `resolving` stuck at true: discovery then went deaf for
            // the rest of the session.
            Log.w(TAG, "NSD resolve: ${e.javaClass.simpleName}")
            resolving.set(false)
        }
    }

    /**
     * Prefer a routable IPv4. On API 34+ `host` is just the first of `hostAddresses`, which for a Mac or
     * iPhone peer is often an IPv6 link-local (fe80::…) or a 169.254 address from another interface
     * (Apple's responder answers with every interface's address); LanTransport dials a plain string.
     */
    private fun pickHost(info: NsdServiceInfo): String? {
        @Suppress("DEPRECATION")
        val all: List<java.net.InetAddress> =
            if (android.os.Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val best = all.firstOrNull { it is java.net.Inet4Address && !it.isLinkLocalAddress }
            ?: all.firstOrNull { it is java.net.Inet4Address }
            ?: all.firstOrNull()
        return best?.hostAddress
    }

    private fun register(txt: Map<String, String>) {
        val info = NsdServiceInfo().apply {
            serviceName = LanDiscovery.instanceName()
            serviceType = "${LanDiscovery.SERVICE_TYPE}."
            setPort(this@NsdLanDiscovery.port)
            txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "NSD register failed: $errorCode") }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registration = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    /** Hourly tag rotation: re-register under a fresh instance name with the new TXT. */
    override fun updateTxt(txt: Map<String, String>) {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        register(txt)
    }

    override fun stop() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
        onFound = null
        runCatching { if (multicastLock?.isHeld == true) multicastLock.release() }
    }
}

/**
 * __DEVSYNC_DEVICE_AUTH_2026_09_23__ The per-device mailbox keys ([DeviceKeys]: devsync `dk` public +
 * Keystore-protected `dsk`) as relay upgrade credentials. One device, one `dsk`, one signature scheme.
 */
fun DeviceKeys.relayCredentials(): RelayDeviceCredentials = object : RelayDeviceCredentials {
    override val dkPub: ByteArray = this@relayCredentials.dkPub
    override val dskPub: ByteArray = this@relayCredentials.dsk
    override fun signWithDsk(message: ByteArray): ByteArray =
        Base64.getDecoder().decode(this@relayCredentials.sign(String(message, Charsets.UTF_8)))
}
