package com.oshi.messenger.network.mesh

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.oshi.messenger.network.encryption.CryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.*
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What to do with a length-prefixed frame header. See
 * [CrossPlatformMesh.Companion.frameAction], which is where the rule lives.
 *
 * Top-level rather than nested in the companion so a JVM test can name it without
 * going through `Companion`.
 */
internal enum class MeshFrameAction {
    /** In range: read the body and dispatch it. */
    ACCEPT,

    /** Too big to buffer, but drainable: skip the body, KEEP the connection. */
    DROP,

    /** Not a plausible length at all — the stream is desynced. Tear down. */
    RESET,
}

/**
 * CrossPlatformMesh - Direct iOS ↔ Android Mesh Communication
 *
 * Uses protocols supported by BOTH platforms:
 * 1. BLE (Bluetooth Low Energy) - for discovery
 * 2. mDNS/Bonjour (NsdManager) - for service advertisement
 * 3. TCP Sockets - for data transfer
 *
 * This enables direct mesh communication between iOS and Android
 * WITHOUT requiring internet or the VPS bridge.
 *
 * Protocol matches iOS CrossPlatformMesh.swift exactly.
 */
@Singleton
class CrossPlatformMesh @Inject constructor(
    @ApplicationContext private val context: Context,
    // 🔐 PEER AUTH: needed to compute DH(our_private, peer_public) for the
    // proof-of-possession challenge-response. Same @Singleton instance that holds
    // the loaded identity keys (see AppModule.provideCryptoManager).
    private val cryptoManager: CryptoManager
) {
    companion object {
        private const val TAG = "CrossPlatformMesh"

        // UUIDs - MUST match iOS exactly
        // iOS advertises as 16-bit UUID (0x0541) for cross-platform reliability.
        // Android expands 16-bit to full 128-bit form: 00000541-0000-1000-8000-00805F9B34FB
        // ScanRecord.getServiceUuids() returns the expanded form, so this matches.
        val SERVICE_UUID: UUID = UUID.fromString("00000541-0000-1000-8000-00805F9B34FB")
        val CHAR_UUID: UUID = UUID.fromString("00000542-0000-1000-8000-00805F9B34FB")

        /** Durable home for the user's real Bluetooth adapter name. */
        internal const val PREFS_BLE = "oshi_mesh_ble"
        internal const val KEY_SAVED_ADAPTER_NAME = "saved_adapter_name"

        // Bonjour/mDNS - MUST be compatible with iOS
        // Android NsdManager requires the trailing dot to match iOS services
        private const val SERVICE_TYPE = "_oshi-mesh._tcp."
        private const val SERVICE_NAME_PREFIX = "OSHI-"

        /**
         * __A_SPLICED_READ_IS_RECOVERABLE_2026_10_23__ How many times to re-read
         * an announce that came back as broken JSON before giving the peer up.
         *
         * Two, not ten: a splice is a race against the peer recomputing its own
         * announce, so if it has not settled in three reads it is not going to,
         * and each attempt costs a GATT round trip on a connection somebody else
         * may be waiting for.
         */
        private const val ANNOUNCE_READ_MAX_RETRIES = 2

        /**
         * __THE_IP_WE_ANNOUNCE_MUST_BE_THE_LAN_ONE_2026_10_22__
         *
         * The address that goes into the GATT characteristic and the NSD record is
         * the one the iPhone dials. The old code returned the first IPv4 of the
         * first non-loopback interface that was up, in whatever order the kernel
         * hands them back — and on a phone that order usually puts the CELLULAR
         * interface (`rmnet_data0`, `ccmni0`) before `wlan0`, because those are
         * created at boot. So with mobile data on, this device announced its
         * carrier-NAT address. The iPhone then opened a TCP connection to an
         * address that does not exist on the LAN, got nothing, and
         * `isPeerLiveConnected` stayed false for good: every cross-platform mesh
         * send fell back to the relay. Same story, worse, with a VPN up: `tun0`
         * is up, has an IPv4, and is very often first.
         *
         * iOS has always restricted itself to `en0`/`en1` for exactly this reason
         * (`CrossPlatformMesh.swift`), so Android was the asymmetric half.
         *
         * Pure and `internal` so the ranking can be tested against real Android
         * interface lists — the enumeration itself needs a device.
         *
         * @param candidates (interface name, IPv4 literal) in enumeration order.
         */
        internal fun pickLanIPv4(candidates: List<Pair<String, String>>): String? {
            // Interfaces that are up, carry an IPv4, and are USELESS to a peer on
            // the local network. Cellular and 464XLAT are carrier NAT; tun/ipsec/ppp
            // is a VPN; p2p is a Wi-Fi Direct group an iPhone cannot join; dummy and
            // sit carry nothing.
            val unusable = listOf(
                "rmnet", "ccmni", "pdp", "clat", "v4-", "rev_rmnet",   // cellular
                "tun", "utun", "ipsec", "ppp", "ppoe",                  // VPN
                "p2p", "aware",                                         // Wi-Fi Direct / Aware
                "dummy", "sit", "lo"
            )
            // Preference order, best first. A station link beats the hotspot we are
            // hosting, which beats a wired adapter, which beats anything unknown.
            val tiers = listOf(
                listOf("wlan"),
                listOf("ap", "swlan", "softap", "bridge", "wigig"),
                listOf("eth", "usb", "rndis", "ncm")
            )

            fun rank(name: String): Int {
                val n = name.lowercase()
                if (unusable.any { n.startsWith(it) }) return -1
                tiers.forEachIndexed { i, prefixes ->
                    if (prefixes.any { n.startsWith(it) }) return i
                }
                return tiers.size            // unknown but not excluded: last resort
            }

            // A link-local 169.254.x.x means DHCP never completed. It is a last
            // resort, never a choice — the same rule iOS applies.
            fun isLinkLocal(ip: String) = ip.startsWith("169.254.")

            val usable = candidates
                .mapNotNull { (name, ip) ->
                    val r = rank(name)
                    if (r < 0 || ip.isBlank() || ip == "0.0.0.0") null else Triple(r, name, ip)
                }
            if (usable.isEmpty()) return null
            return usable
                .sortedWith(compareBy({ if (isLinkLocal(it.third)) 1 else 0 }, { it.first }))
                .first()
                .third
        }

        // Relay settings - same as iOS
        private const val MAX_HOPS = 50
        private const val MAX_CACHE_SIZE = 5000
        private const val MESH_MAX_PAYLOAD_SIZE = 10 * 1024 * 1024L  // 10 MB max for mesh media

        /**
         * The largest length-prefixed frame we will BUFFER — the same number iOS
         * uses (`CrossPlatformMesh.swift:685`, `maxFrameSize = 100 * 1024 * 1024`).
         *
         * It was 16 MiB here while iOS accepted 100 MiB, so a large iPhone→Android
         * LAN media send was refused by the only side that could refuse it. Sharing
         * the constant is the whole point: a frame one side is willing to WRITE must
         * be a frame the other side is willing to READ.
         */
        internal const val MAX_FRAME_SIZE = 100 * 1024 * 1024

        /**
         * Above [MAX_FRAME_SIZE] but at or below this, we still trust the header
         * enough to SKIP the body and carry on. Beyond it, a header that large is
         * far more likely to be a desynced stream than a real frame, and skipping
         * would mean reading gigabytes of someone else's noise.
         */
        internal const val MAX_DRAINABLE_FRAME = 4 * MAX_FRAME_SIZE  // 400 MiB

        /**
         * Pure, because the rule it encodes is the one that used to cost a session.
         *
         * A peer that sends ONE oversize frame must not cost us the link. The old
         * code `break`-ed out of the receive loop for anything over 16 MiB, so a
         * single large iPhone media send both failed AND reset the TCP connection —
         * the peer then had to be rediscovered. Dropping the frame (draining its
         * body so the stream stays framed) leaves every LATER message deliverable.
         *
         * `RESET` is kept for the case it was actually written for: a non-positive
         * length, or one so large it cannot be a frame, means the next 4 bytes are
         * not a length either and there is nothing to resynchronise to.
         */
        internal fun frameAction(length: Int): MeshFrameAction = when {
            length <= 0 -> MeshFrameAction.RESET
            length <= MAX_FRAME_SIZE -> MeshFrameAction.ACCEPT
            length <= MAX_DRAINABLE_FRAME -> MeshFrameAction.DROP
            else -> MeshFrameAction.RESET
        }

        /**
         * Whether a relayed message has run out of hops.
         *
         * Port of iOS `CrossPlatformMesh.swift:1052`,
         * `guard message.hopCount < min(message.maxHops, maxHops)`. The clamp is the
         * point: `maxHops` arrives ON THE WIRE and is therefore attacker-controlled.
         * Android trusted it, so a peer could set `maxHops = 9999` and turn every
         * Android relay into a re-flooder for the lifetime of the dedup cache.
         * Legitimate traffic hops far below 50, so real delivery is unchanged.
         */
        internal fun shouldDropForHops(hopCount: Int, maxHops: Int): Boolean =
            hopCount >= minOf(maxHops, MAX_HOPS)

        /**
         * The bytes a GATT long-read at [offset] must be answered with.
         *
         * In the companion object, and pure, so the one rule that matters here
         * is covered by a JVM test rather than by two phones in a room.
         *
         * The rule: a GATT client reading a value longer than ATT_MTU−1 issues
         * successive READ_BLOB requests and stops only when it receives a
         * response SHORTER than the MTU — so the final blob, at
         * `offset == size`, must be EMPTY. The previous code was
         *
         *     if (offset > 0 && offset < data.size) data.copyOfRange(offset, data.size) else data
         *
         * whose `else` also fires for `offset >= data.size` and returns **the
         * whole payload again**. The client appends a second complete copy of
         * the JSON to what it has already read, parsing throws on the duplicated
         * tail (iOS: `JSONSerialization` at OSHI/CrossPlatformMesh.swift:1431
         * returns nil and the peer is dropped without a log), and the iPhone
         * loses a peer it had successfully found.
         *
         * iOS gets this right — OSHI/CrossPlatformMesh.swift:1520-1524 answers
         * `.invalidOffset` past the end and an empty `subdata` exactly at the
         * end. This matches that, minus the error code: Android's
         * `BluetoothGattServer.sendResponse` carries the status separately and
         * the empty slice is the part that terminates the read.
         *
         * Today the payload (~100-130 bytes) usually fits one MTU, so this is a
         * latent bug rather than a firing one — it starts firing the moment a
         * display name grows or an IPv6 address appears.
         */
        internal fun gattReadSlice(data: ByteArray, offset: Int): ByteArray = when {
            offset <= 0 -> data
            offset >= data.size -> ByteArray(0)
            else -> data.copyOfRange(offset, data.size)
        }

        /**
         * __MESH_RECIPIENT_SPELLING_2026_10_12__
         *
         * Fold the two spellings an OSHI public key can arrive in into one:
         * base64url (`-`, `_`) to standard base64 (`+`, `/`), `=` padding dropped.
         *
         * The SAME rule already guards every send-side lookup in this file
         * ([normalizeKey], used by `findStreamForRecipient`, `isPeerReachable`,
         * `isPeerDiscovered`, `findConnectedPeer`). It was never applied to the
         * receive side, which is the side that decides whether a frame is OURS.
         */
        internal fun normalizeMeshKey(key: String): String =
            key.trim().replace('-', '+').replace('_', '/').replace("=", "")

        /**
         * __MESH_WIRE_SPELLING_FOR_OLD_IOS_2026_10_26__
         *
         * The spelling we STAMP ON THE WIRE. [normalizeMeshKey] folds the two
         * spellings for COMPARISON on this device; this one decides what the peer
         * actually receives, and the peer may be an App Store iPhone that cannot
         * be updated.
         *
         * The shipping iOS build (1.0.43) decides "is this frame for me?" with
         * `trim + lowercased` equality and a 32-character prefix fallback. Neither
         * folds base64url into base64, so a frame addressed to `eAbM-cyc_…` never
         * matches an iPhone holding `eAbM+cyc/…` and is relayed into nothing. The
         * iPhone-side fold exists only in an unreleased build, so ANDROID has to
         * speak the spelling that build already understands.
         *
         * Byte-identical to iOS `MessageManager.canonicalIdentity`, which is what
         * every identity stored on that iPhone has been through — same fast path,
         * same re-padding to a multiple of 4, same "leave it alone if it is not
         * base64 after all". Keep the two in step.
         */
        internal fun canonicalWireKey(key: String): String {
            // Fast path: already canonical (mirrors the iOS fast path exactly).
            if (key.length % 4 == 0 && !key.contains('-') && !key.contains('_')) return key
            val trimmed = key.trim()
            if (trimmed.isEmpty()) return key
            val sb = StringBuilder(trimmed.replace('-', '+').replace('_', '/'))
            while (sb.isNotEmpty() && sb.last() == '=') sb.setLength(sb.length - 1)
            while (sb.length % 4 != 0) sb.append('=')
            val candidate = sb.toString()
            return try {
                java.util.Base64.getDecoder().decode(candidate)
                candidate
            } catch (_: IllegalArgumentException) {
                // Not base64 at all — hand back what we were given rather than
                // invent a key, exactly as iOS does.
                key
            }
        }

        /**
         * __WE_DIALLED_WHATEVER_ADDRESS_NSD_HANDED_BACK_2026_11_07__
         *
         * The mirror image of [pickLanIPv4], which exists because the address we
         * ANNOUNCE has to be the one a peer can reach. Nothing applied the same
         * care to the address we DIAL: `onServiceResolved` took
         * `serviceInfo.host?.hostAddress` and connected to it, whatever it was.
         *
         * An iPhone publishes its Bonjour service with `NetService`, which
         * registers A *and* AAAA records, so a resolve can hand back an IPv6
         * link-local (`fe80:…`). `NsdServiceInfo` carries no zone index, and a
         * `fe80:` address without one cannot be dialled at all — the kernel has
         * no interface to put the packet on. `Socket.connect` throws, and until
         * today a throw here was that peer's last chance (see
         * [MeshReconnectPolicy]).
         *
         * Preference, best first: a routable IPv4; then an IPv6 that is not
         * loopback and not an unscoped link-local; then a 169.254 IPv4, which
         * [pickLanIPv4] calls "a last resort, never a choice" and which this
         * side must keep for the same reason — two phones on a Wi-Fi network
         * with no DHCP server self-assign one and can still reach each other,
         * which for a mesh app is a case that matters.
         *
         * Never dialled: loopback and 0.0.0.0 (they are this phone), and an
         * unscoped `fe80:` (the kernel has no interface to send it on, so the
         * connect cannot do anything but throw).
         *
         * Pure and `internal` so the ordering is tested without a network.
         *
         * @param candidates address literals in the order the resolver gave them
         */
        internal fun pickDialAddress(candidates: List<String>): String? {
            val list = candidates.map { it.trim() }.filter { it.isNotEmpty() }
            val v4 = list.filter { !it.contains(':') }
                .filterNot { it.startsWith("127.") || it == "0.0.0.0" }
            val v6 = list.filter { it.contains(':') }
                .filterNot { it == "::1" || it == "::" }
                // A link-local v6 is dialable only with a zone index ("%wlan0").
                .filter { !it.lowercase().startsWith("fe80:") || it.contains('%') }

            return v4.firstOrNull { !it.startsWith("169.254.") }
                ?: v6.firstOrNull()
                ?: v4.firstOrNull()
        }

        /**
         * Is a mesh frame carrying [recipientPublicKey] addressed to us?
         *
         * Returning false here does NOT park the frame, it hands it to
         * `relayToRecipient`, which has no route to a key that is in fact our own
         * and therefore DROPS IT IN SILENCE. So a wrong answer costs the message.
         *
         * The old test was `recipient.trim().lowercase() == mine.trim().lowercase()`
         * with a first-32-character prefix fallback, and neither half reconciles
         * base64url with standard base64: lowercasing leaves `-` as `-` and `_` as
         * `_`. A 43-character key holds a `+` or a `/` in its first 32 characters
         * about two times in three, so for most contacts the equality failed, the
         * prefix fallback failed with it, and the message was relayed into nothing.
         *
         * It bit ONE direction. iOS stamps the canonical spelling on every identity
         * it stores (`MessageManager.canonicalIdentity`), so an iPhone addresses us
         * in standard base64 and matched our own standard-base64 key. Android has
         * no such funnel — contacts arrive from a QR scan, the v2 relay and the VPS
         * in either spelling, and this file's own comments record keys "stored under
         * two slightly-different keys (whitespace, base64 vs base64url)". Whichever
         * spelling the contact row held went out in the frame, so Android to iOS
         * mesh text died on the iPhone's copy of this check while iOS to Android
         * kept working.
         *
         * An empty recipient is a broadcast and is for everyone (unchanged).
         */
        internal fun isAddressedToUs(recipientPublicKey: String, myPublicKey: String): Boolean {
            if (recipientPublicKey.isEmpty()) return true
            val theirs = normalizeMeshKey(recipientPublicKey)
            val mine = normalizeMeshKey(myPublicKey)
            if (theirs.isEmpty() || mine.isEmpty()) return false
            if (theirs == mine) return true
            // Legacy safety net, kept exactly as tolerant as it was: a case- and
            // length-insensitive prefix match for any skew the fold above misses.
            val prefixLen = minOf(mine.length, theirs.length, 32)
            return prefixLen >= 16 &&
                theirs.take(prefixLen).equals(mine.take(prefixLen), ignoreCase = true)
        }

        /**
         * __A_SIGHTING_WITH_AN_ADDRESS_WAS_THROWN_AWAY_2026_11_09__
         *
         * Do two announced keys name the same device? Both platforms put only the
         * first 32 characters of their key in the BLE characteristic, and the full
         * key in the NSD/Bonjour TXT record, so one phone reaches this list under
         * two spellings. Equal after the base64url fold, or one is a ≥32-char
         * prefix of the other.
         */
        internal fun sameMeshIdentity(a: String, b: String): Boolean {
            val x = normalizeMeshKey(a)
            val y = normalizeMeshKey(b)
            if (x.isEmpty() || y.isEmpty()) return false
            if (x == y) return true
            val (short, long) = if (x.length <= y.length) x to y else y to x
            return short.length >= 32 && long.startsWith(short)
        }

        /**
         * __A_SIGHTING_WITH_AN_ADDRESS_WAS_THROWN_AWAY_2026_11_09__
         *
         * Fold a new sighting (BLE announce or NSD resolve) into the discovered list.
         *
         * Both callers used to do `if (none { it.publicKey == pk }) add(peer)`: the
         * FIRST sighting of a phone won forever. An iPhone read over BLE before it
         * had a LAN address (Wi-Fi not joined yet, or a build that announced "" on
         * Personal Hotspot) sat in the list with `ipAddress = null`; every later
         * sighting that DID carry an address was dialled once, directly, and then
         * dropped. One failed dial and the peer was addressless again for the
         * sweep and for Connecter, which answered "no address yet — searching"
         * for good. Anatole's screenshot, 2026-11-09: 0 connected, 1 nearby.
         *
         * Now: one row per device. The longer key wins (full NSD key over the
         * 32-char BLE prefix), and a non-null address, a real name and a known
         * platform from the newer sighting replace the old ones — an address is
         * never replaced by null.
         */
        internal fun mergeSighting(peers: List<CrossPlatformPeer>, sighting: CrossPlatformPeer): List<CrossPlatformPeer> {
            if (sighting.publicKey.isEmpty()) return peers
            val (same, others) = peers.partition { sameMeshIdentity(it.publicKey, sighting.publicKey) }
            if (same.isEmpty()) return peers + sighting
            var merged = same.first()
            for (p in same.drop(1) + sighting) {
                merged = CrossPlatformPeer(
                    publicKey = if (p.publicKey.length > merged.publicKey.length) p.publicKey else merged.publicKey,
                    displayName = p.displayName.takeIf { it.isNotBlank() && it != "Unknown" } ?: merged.displayName,
                    platform = p.platform.takeIf { it.isNotBlank() && it != "unknown" } ?: merged.platform,
                    ipAddress = p.ipAddress ?: merged.ipAddress,
                    port = p.port ?: merged.port,
                )
            }
            // Keep the row where it was so the Peers list does not reorder.
            val at = peers.indexOfFirst { sameMeshIdentity(it.publicKey, sighting.publicKey) }
            return others.toMutableList().apply { add(minOf(at, size), merged) }
        }

        /**
         * __A_SIGHTING_WITH_AN_ADDRESS_WAS_THROWN_AWAY_2026_11_09__
         *
         * iOS Personal Hotspot always hands out 172.20.10.0/28 with the iPhone at
         * .1. Every iOS build before __THE_IP_WE_ANNOUNCE_MUST_BE_THE_LAN_ONE_2026_10_22__
         * — including the App Store one, which cannot be updated from here —
         * announces an EMPTY ip while hosting, because its address lives on
         * `bridge100`. When this phone is a client of that hotspot the host is the
         * gateway, so its address is known without being announced.
         *
         * @return the hotspot host's address when [myIp] is a client address in
         *   that subnet, else null.
         */
        internal fun iosHotspotHostFor(myIp: String?): String? {
            val parts = myIp?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return null
            if (parts.size != 4 || parts[0] != 172 || parts[1] != 20 || parts[2] != 10) return null
            return if (parts[3] in 2..14) "172.20.10.1" else null
        }
    }

    // State
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _crossPlatformPeers = MutableStateFlow<List<CrossPlatformPeer>>(emptyList())
    val crossPlatformPeers: StateFlow<List<CrossPlatformPeer>> = _crossPlatformPeers.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<CrossPlatformPeer>>(emptyList())
    val connectedPeers: StateFlow<List<CrossPlatformPeer>> = _connectedPeers.asStateFlow()

    // 🔐 PEER AUTH (proof-of-possession, additive/advisory — see MeshPeerAuth.kt).
    // Public keys of peers that answered a DH challenge, proving they hold the
    // key's private half. SIGNAL only (a UI could show a "verified peer" badge);
    // routing/delivery never consult it, so an unverified peer is served as before.
    private val _verifiedPeerKeys = MutableStateFlow<Set<String>>(emptySet())
    val verifiedPeerKeys: StateFlow<Set<String>> = _verifiedPeerKeys.asStateFlow()
    // Nonces we sent and are awaiting a response for, keyed by peer public key.
    private val pendingChallengeNonces = ConcurrentHashMap<String, ByteArray>()

    // Diagnostic state (visible on Peers screen for debugging)
    private val _diagnostics = MutableStateFlow("")
    val diagnostics: StateFlow<String> = _diagnostics.asStateFlow()

    // BLE
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null

    /**
     * The user's own Bluetooth adapter name, saved before we overwrite it with
     * "OSHI-xxx" so [stopBLEAdvertising] can put it back. See the note in
     * [startBLEAdvertising].
     *
     * **Durable on purpose.** The previous fix kept it in a plain field, which
     * does not survive the process: swipe OSHI away while it is advertising and
     * the phone stays called "OSHI-Hugo" — and the next launch reads *that* as
     * the original and "restores" it. The user's real adapter name, the one
     * their car and headphones display, would be gone for good.
     * [BleRadioPolicy.adapterNameToSave] is the guard, and [start] repairs an
     * orphaned rename before it makes a new one.
     */
    private val blePrefs by lazy {
        context.getSharedPreferences(PREFS_BLE, Context.MODE_PRIVATE)
    }

    private var savedAdapterName: String?
        get() = blePrefs.getString(KEY_SAVED_ADAPTER_NAME, null)
        // commit(), not apply(): the whole reason this moved out of a field is
        // that the process can be killed at any moment. An asynchronous write
        // that has not reached disk when that happens loses the only copy of the
        // user's Bluetooth name — the failure this is here to prevent.
        set(value) {
            blePrefs.edit().apply {
                if (value == null) remove(KEY_SAVED_ADAPTER_NAME) else putString(KEY_SAVED_ADAPTER_NAME, value)
            }.commit()
        }

    /**
     * Whether BLE is ACTUALLY advertising / scanning right now — as opposed to
     * [isRunning], which says only that [start] was called.
     *
     * These exist because [isRunning] lies by construction: `start()` sets
     * `_isRunning.value = true` unconditionally at the end, after the BLE
     * bring-up has already bailed at its permission guard. `PeersViewModel`
     * mapped that flag straight to "Searching…", so a phone holding no Bluetooth
     * permission at all — the default, since nothing requests them outside the
     * Peers route — displayed a mesh that was searching for nobody, forever.
     *
     * [bleAdvertising] is the direction that matters for an iPhone: iOS learns
     * an Android peer only by reading GATT characteristic 0542, and that
     * characteristic is only served while we advertise.
     */
    private val _bleAdvertising = MutableStateFlow(false)
    val bleAdvertising: StateFlow<Boolean> = _bleAdvertising.asStateFlow()

    private val _bleScanning = MutableStateFlow(false)
    val bleScanning: StateFlow<Boolean> = _bleScanning.asStateFlow()

    /**
     * The last radio rejection we have not recovered from, read through
     * [BleRadioPolicy.scanFault] / [BleRadioPolicy.advertiseFault].
     *
     * `onScanFailed` used to write one `Log.e` and return. The Galaxy A17
     * capture on 2026-08-24 shows `❌ BLE scan failed: errorCode=1` sitting in
     * a log nobody reads, in a run that otherwise worked. A rejected start that
     * is never checked cannot be told apart from one that succeeded — which is
     * the same failure mode as the permission denial this whole lane was fixed
     * for. The Peers screen now surfaces the ones a user can act on.
     */
    private val _bleFault = MutableStateFlow(BleRadioPolicy.RadioFault.NONE)
    val bleFault: StateFlow<BleRadioPolicy.RadioFault> = _bleFault.asStateFlow()

    /**
     * The BLE permissions we do not hold, recomputed at every permission gate
     * — which the 30 s ticker crosses whether or not anything else happens, so
     * this cannot go stale for longer than one tick.
     *
     * Deliberately the **BLE lanes only**, unlike
     * [MeshNetworkManager.missingMeshPermissions] which covers Nearby
     * Connections too. A denied `NEARBY_WIFI_DEVICES` never touches the iOS
     * lane, and reporting "Permission needed" over a BLE link that is up and
     * talking to an iPhone would be exactly the class of lie this work removes.
     */
    private val _blePermissionsMissing = MutableStateFlow<List<String>>(emptyList())
    val blePermissionsMissing: StateFlow<List<String>> = _blePermissionsMissing.asStateFlow()

    /** True when the adapter exists and the radio is switched on. */
    private val _bluetoothEnabled = MutableStateFlow(false)
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    /**
     * Which [start] the periodic coroutines belong to. See [MeshRunGeneration] —
     * without it, `stop()` + `start()` (every launch, via `MainViewModel`) left
     * the previous launch's tickers running alongside the new ones.
     */
    private val runGeneration = MeshRunGeneration()

    /**
     * __A_LATE_SUCCESS_MUST_NOT_RESURRECT_A_DEAD_ADVERTISEMENT_2026_10_23__
     *
     * `startAdvertising` answers asynchronously, and `reconfigureIdentity()` —
     * which runs on every launch right after `startMesh()` — calls `stop()` and
     * restarts 300 ms later. Measured on a Galaxy A17:
     *
     *   26.036  advertise: asking the radio to start
     *   26.069  advertise: TEARING DOWN          <- stop() for the identity swap
     *   26.181  advertise: LIVE                  <- the callback for the TORN DOWN one
     *   26.418  advertise: already up, nothing to re-arm
     *
     * So `_bleAdvertising` was set true by the success of an advertisement that
     * no longer existed, and every later attempt took the idempotent early exit.
     * The phone believed it was discoverable for the rest of the process; a scan
     * from a Mac sitting next to it found 45 devices and not this one.
     *
     * The epoch is bumped on every teardown. A callback carrying an old epoch is
     * answering about an advertisement nobody is waiting on any more.
     */
    @Volatile private var advertiseEpoch: Int = 0

    /** The epoch of the `startAdvertising` we are currently waiting on. */
    @Volatile private var advertisingEpochInFlight: Int = -1

    /**
     * The epoch the radio has actually CONFIRMED, by answering `onStartSuccess`.
     *
     * `_bleAdvertising` is set optimistically at call time, so on its own it
     * only ever means "we asked". This is the second half of the same lesson:
     * a flag nobody checks against reality is how a phone spent an afternoon
     * believing it was discoverable while a scan one metre away never saw it.
     */
    @Volatile private var advertiseConfirmedEpoch: Int = -1

    /** Timer coroutines owned by the current generation, cancelled by [stop]. */
    private val periodicJobs = Collections.synchronizedList(mutableListOf<Job>())

    /**
     * The GATT payload as handed to the client for offset 0, kept so the
     * continuation reads of a long read reassemble the *same* bytes.
     *
     * [createAdvertisementData] is recomputed per request and embeds `ip` and
     * `port`; a Wi-Fi handover between the first read and the `READ_BLOB` that
     * follows it would otherwise splice two different JSON documents together
     * and iOS's parser (`CrossPlatformMesh.swift:1431`) would return nil and
     * drop the peer with no log — the same silent outcome as the off-by-one
     * that [gattReadSlice] fixes.
     */
    @Volatile private var gattPayloadSnapshot: ByteArray = ByteArray(0)

    private val discoveredDevices = ConcurrentHashMap<String, BluetoothDevice>()
    @Volatile private var totalScanResults = 0  // Diagnostic: total BLE scan callbacks
    @Volatile private var nsdRegistered = false
    @Volatile private var nsdDiscovering = false
    @Volatile private var nsdFoundCount = 0

    // NSD (Bonjour/mDNS)
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // TCP
    private var serverSocket: ServerSocket? = null
    private var serverPort: Int = 0
    private val tcpConnections = ConcurrentHashMap<String, Socket>()  // publicKey -> socket
    private val outputStreams = ConcurrentHashMap<String, DataOutputStream>()

    /**
     * __A_SPLICED_READ_IS_RECOVERABLE_2026_10_23__ How many times we have re-read
     * a peer's announce after it came back as broken JSON, keyed by device
     * address. Cleared on success and on disconnect, so a peer that recovers
     * starts from a clean slate the next time it is seen.
     */
    private val announceReadRetries = ConcurrentHashMap<String, Int>()

    // __NOTHING_EVER_DIALLED_A_PEER_TWICE_2026_11_07__
    //
    // See [MeshReconnectPolicy] for the report and the diagnosis. In short: the
    // only two callers of `connectToPeer` are one-shot discovery callbacks, so
    // a peer whose first dial failed stayed on the discovered list, unreachable,
    // for the life of the process — and every control on the Peers screen that
    // looks like a retry returns without touching this lane.
    //
    // Keyed by the peer public key exactly as the discovered peer announced it;
    // lookups against live sockets go through [hasLiveConnection], which folds
    // base64url into base64 first, because the two sides do not always spell a
    // key the same way (see `normalizeKey`).

    /** Consecutive failed dials per peer key. Cleared on a successful connect. */
    private val dialFailures = ConcurrentHashMap<String, Int>()

    /** When the last dial for a peer key was STARTED (not when it finished). */
    private val lastDialAt = ConcurrentHashMap<String, Long>()

    /** Peer keys with a connect attempt still in flight. */
    private val dialsInFlight = Collections.synchronizedSet(mutableSetOf<String>())

    /** Last time NSD browsing was torn down and restarted, for the sweep's floor. */
    @Volatile private var lastDiscoveryRestartAt = 0L

    // Identity
    private var myPublicKey: String = ""
    private var myDisplayName: String = ""

    // Relay
    /**
     * Duplicate-suppression cache for relayed ids.
     *
     * ⚠️ `mutableSetOf` IS LOAD-BEARING: it is a `LinkedHashSet`, so iteration is
     * INSERTION order and the trim in [markSeen] therefore drops the OLDEST ids.
     * Swap in a `HashSet` and the trim starts discarding ids at random — including
     * ones still being re-broadcast — and the mesh re-delivers messages the user has
     * already read. (That is the bug the iOS audit found on 2026-09-12, where the
     * cache was an unordered `Set` trimmed with `suffix(n)`; this side is correct
     * only by virtue of the collection chosen here, which is why it is written down.)
     */
    private val seenMessageIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val routingTable = ConcurrentHashMap<String, RouteInfo>()

    // Track connections we've already sent identity to (prevent infinite exchange loop)
    private val sentIdentityTo = Collections.synchronizedSet(mutableSetOf<String>())

    // Callbacks
    // 🔧 FIX: Use synchronized lists to prevent ConcurrentModificationException when
    // callbacks are added on UI thread while mesh delivery iterates on IO scope.
    private val messageCallbacks = Collections.synchronizedList(mutableListOf<(CrossPlatformMessage) -> Unit>())
    private val groupAdCallbacks = Collections.synchronizedList(mutableListOf<(String) -> Unit>())  // PublicGroupAd JSON payload

    // Coroutines
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ============================================================================
    // PUBLIC API
    // ============================================================================

    /**
     * Start cross-platform mesh networking
     */
    fun start(publicKey: String, displayName: String) {
        // Prevent double-start
        if (_isRunning.value) {
            Log.d(TAG, "⚠️ CrossPlatformMesh already running")
            MeshDiag.log("mesh: start IGNORED, already running")
            return
        }

        if (publicKey.isEmpty()) {
            Log.e(TAG, "❌ Cannot start: empty public key")
            return
        }

        MeshDiag.log("mesh: bring-up BEGINS")
        myPublicKey = publicKey
        myDisplayName = displayName

        Log.d(TAG, "🚀 Starting CrossPlatformMesh...")
        Log.d(TAG, "   Public key: ${publicKey.take(16)}...")
        Log.d(TAG, "   Display name: $displayName")
        Log.d(TAG, "   Service type: $SERVICE_TYPE")

        // Initialize Bluetooth
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        _bluetoothEnabled.value = bluetoothAdapter?.isEnabled == true

        Log.d(TAG, "   BLE scanner: ${bleScanner != null}")
        Log.d(TAG, "   BLE advertiser: ${bleAdvertiser != null}")

        // Repair a rename orphaned by a previous process that was killed while
        // advertising, BEFORE making a new one — otherwise the next
        // `startBLEAdvertising` would record "OSHI-…" as the user's own name.
        restoreAdapterName()

        // Claim this run's generation. Retires every timer from an earlier
        // start() that has not noticed its cancellation yet — the second ticker
        // behind the doubled "Periodic BLE scan restart" in the A17 capture.
        val generation = runGeneration.begin()

        // Recover instantly when the user switches Bluetooth on, instead of
        // waiting up to 30 s for the next ensureBleRunning() tick.
        registerBluetoothStateReceiver()
        // (N23) So a car connecting over classic Bluetooth sees the real name.
        registerClassicBluetoothReceiver()

        // Start TCP server first
        startTCPServer()

        // Start BLE (may fail if permissions not yet granted — retry below)
        startBLEAdvertising()
        startBLEScanning()

        // If BLE permissions aren't granted yet, schedule retries
        // MainActivity requests permissions in onCreate(), but the dialog is async
        if (!hasBluetoothPermissions()) {
            Log.w(TAG, "⚠️ BLE permissions not yet granted — scheduling retry")
            scheduleBLERetry(generation)
        }

        // Start NSD (Bonjour/mDNS) — stagger discovery to avoid Android NSD bug
        // where concurrent registration and discovery of the same service type fail
        startNSDAdvertising()
        scope.launch {
            delay(2000)
            startNSDDiscovery()
        }

        _isRunning.value = true
        MeshDiag.log("mesh: bring-up ENDS (generation $generation)")
        Log.d(TAG, "✅ CrossPlatformMesh started (generation $generation)")

        // Periodic BLE scan restart for robustness (every 30s)
        schedulePeriodicScanRestart(generation)

        // Periodically update diagnostics for the Peers screen
        scheduleDiagnosticsUpdate(generation)

        // Periodic stale route cleanup (every 60s, removes entries older than 5 minutes)
        scheduleRouteCleanup(generation)

        // Periodic 2-hop identity gossip (every 30s)
        scheduleIdentityAnnounce(generation)

        // __NOTHING_EVER_DIALLED_A_PEER_TWICE_2026_11_07__ Retry peers that are
        // discovered but not connected (every 15s).
        scheduleReconnectSweep(generation)
    }

    /**
     * Every periodic loop body starts here. Cancellation alone is not enough:
     * it is cooperative, and the loops' old exit test (`_isRunning.value`) is
     * put *back* to true by the `start()` that follows a `stop()` — which is
     * every launch, since `MainViewModel` starts the mesh and then immediately
     * calls `reconfigureIdentity()`. A loop parked in `delay(30_000)` across
     * that pair simply never exited, and the app ran two of every ticker.
     */
    private fun stillCurrent(generation: Int): Boolean =
        _isRunning.value && runGeneration.isCurrent(generation)

    /** Register a periodic coroutine so [stop] can cancel it. */
    private fun trackPeriodic(job: Job) {
        periodicJobs.add(job)
        job.invokeOnCompletion { periodicJobs.remove(job) }
    }

    /**
     * Tear down and restart with a new identity. Required after the user
     * rotates / regenerates their public key — otherwise the stale
     * [myPublicKey] keeps being stamped on every outgoing CrossPlatformMessage
     * and the peer (iPhone) attributes the message to the OLD conversation
     * instead of creating a fresh one for the new identity.
     */
    fun reconfigureIdentity(publicKey: String, displayName: String) {
        if (publicKey.isEmpty()) {
            Log.w(TAG, "⚠️ reconfigureIdentity called with empty public key — ignoring")
            return
        }
        Log.d(TAG, "🔄 Reconfiguring identity ${myPublicKey.take(12)}… → ${publicKey.take(12)}…")
        stop()
        // start() short-circuits when _isRunning.value is true; stop() above
        // flips it to false, so a brief delay lets in-flight stop callbacks
        // settle before the restart.
        scope.launch {
            delay(300)
            start(publicKey, displayName)
        }
    }

    /**
     * Stop cross-platform mesh
     */
    fun stop() {
        Log.d(TAG, "🛑 Stopping CrossPlatformMesh...")

        // Retire this run's generation FIRST, then cancel. A loop already past
        // its `delay` when the cancel lands still runs one body; the generation
        // check at the top of that body is what actually stops it re-arming the
        // scanner behind the restart that is about to replace it.
        runGeneration.end()
        synchronized(periodicJobs) { periodicJobs.toList() }.forEach { it.cancel() }
        periodicJobs.clear()

        unregisterBluetoothStateReceiver()
        unregisterClassicBluetoothReceiver()

        stopBLEAdvertising()
        stopBLEScanning()
        stopNSDAdvertising()
        stopNSDDiscovery()
        stopTCPServer()

        sentIdentityTo.clear()
        // iOS drops the dedup cache in `stop()` too (`CrossPlatformMesh.swift:297-301`,
        // alongside `sentIdentityTo` and `routingTable`). Keeping it across a restart
        // meant a legitimate RE-SEND of a message whose id the previous run had already
        // seen was swallowed in silence — and a restart is exactly when a user re-sends,
        // because the first attempt is what made them toggle the mesh off and on.
        clearSeenMessageIds()

        _isRunning.value = false
        _bleFault.value = BleRadioPolicy.RadioFault.NONE
        _crossPlatformPeers.value = emptyList()
        _connectedPeers.value = emptyList()

        // A peer that failed five times before the user toggled the mesh off
        // must not come back owing two minutes of backoff.
        dialFailures.clear()
        lastDialAt.clear()
        dialsInFlight.clear()
        lastDiscoveryRestartAt = 0L
    }

    /**
     * Send message to a peer (direct or via relay)
     */
    fun sendMessage(recipientPublicKey: String, content: String, type: String = "TEXT_MESSAGE") {
        val message = CrossPlatformMessage(
            id = UUID.randomUUID().toString(),
            type = type,
            senderPublicKey = myPublicKey,
            senderName = myDisplayName,
            recipientPublicKey = recipientPublicKey,
            payload = content,
            timestamp = System.currentTimeMillis().toDouble(),
            hopCount = 0,
            maxHops = MAX_HOPS,
            seenBy = mutableListOf(myPublicKey),
            platform = "android"
        )

        sendOrRelay(message)
    }

    /**
     * Relay a message from MeshNetworkManager
     */
    fun relayMessage(originalMessage: ByteArray, recipientPublicKey: String) {
        val content = String(originalMessage, Charsets.UTF_8)
        sendMessage(recipientPublicKey, content, "RELAY")
    }

    // __LEAK_FIX_2026_09_25__ `sendLocation`, `sendMedia` and `sendDocument` are GONE.
    // They put raw GPS JSON / base64 media / documents + file names in the CLEAR on
    // the LAN (LOCATION_MESSAGE / MEDIA_MESSAGE / DOCUMENT_MESSAGE frames). Nothing
    // called them any more; deleting them makes sure nothing can again. Location and
    // media to an iPhone ride the ratchet-encrypted TEXT_MESSAGE path
    // (`MessageRepository`), mirroring iOS F-mesh#1 (2026-09-10). The raw frame
    // types are still ACCEPTED on receive (`handleReceivedData`) for older peers.

    /**
     * Send call audio via cross-platform mesh
     */
    fun sendCallAudio(recipientPublicKey: String, audioData: ByteArray) {
        val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)
        sendMessage(recipientPublicKey, base64Audio, "CALL_AUDIO")
    }

    /**
     * Send call video via cross-platform mesh (separate from audio so iOS can distinguish)
     *
     * ⚠️ iOS DROPS THIS — and `CALL_VIDEO` is now the ONLY type it drops that we
     * send. Checked against `OSHI/CrossPlatformMesh.swift` on 2026-10-23: its
     * dispatcher handles fourteen types, this one not among them, and everything
     * else falls into `default:` with "Unknown message type".
     *
     * The rest of this warning was out of date and said the opposite of the truth:
     * `GROUP_UPDATE`, `GROUP_MESSAGE` and `PUBLIC_GROUP_AD` ARE handled on iOS now,
     * and `PEER_CHALLENGE` / `PEER_CHALLENGE_RESPONSE` as well.
     * `CrossPlatformMeshParityTest.e_both_dispatchers_accept_the_same_types` reads
     * both switches and will fail rather than let this comment rot again.
     *
     * This has no callers today (mesh calls send AUDIO — `CallManager.kt:879`; video
     * goes over VPS/WebSocket), which is the only reason it is not a live bug. Before
     * wiring it up, add the case on the iOS side first.
     */
    fun sendCallVideo(recipientPublicKey: String, videoData: ByteArray) {
        val base64Video = Base64.encodeToString(videoData, Base64.NO_WRAP)
        sendMessage(recipientPublicKey, base64Video, "CALL_VIDEO")
    }

    /**
     * Send call signal via cross-platform mesh
     */
    fun sendCallSignal(recipientPublicKey: String, signalData: ByteArray) {
        val base64Signal = Base64.encodeToString(signalData, Base64.NO_WRAP)
        sendMessage(recipientPublicKey, base64Signal, "CALL_SIGNAL")
    }

    /**
     * Add callback for received messages
     */
    fun addMessageCallback(callback: (CrossPlatformMessage) -> Unit) {
        messageCallbacks.add(callback)
    }

    /**
     * Add callback for received PublicGroupAd broadcasts
     */
    fun addGroupAdCallback(callback: (String) -> Unit) {
        groupAdCallbacks.add(callback)
    }

    /**
     * Broadcast data to ALL connected cross-platform peers
     */
    fun broadcastToAllPeers(content: String, type: String) {
        val myPk = myPublicKey
        if (myPk.isEmpty()) return
        val connections = tcpConnections.toMap()
        if (connections.isEmpty()) return

        val message = CrossPlatformMessage(
            id = UUID.randomUUID().toString(),
            type = type,
            senderPublicKey = myPk,
            senderName = myDisplayName,
            recipientPublicKey = "",
            payload = content,
            timestamp = System.currentTimeMillis().toDouble(),
            hopCount = 0,
            maxHops = 5,
            seenBy = mutableListOf(myPk),
            platform = "android"
        )
        val data = message.toJSON()
        Log.d(TAG, "📡 Broadcasting $type to ${connections.size} cross-platform peer(s)")
        connections.values.forEach { socket ->
            try {
                val output = DataOutputStream(socket.getOutputStream())
                sendData(data, output)
            } catch (e: Exception) {
                Log.e(TAG, "Broadcast send error: ${e.message}")
            }
        }
    }

    /**
     * Check if peer is reachable via cross-platform mesh.
     * Uses normalized base64 comparison so a key stored as `eAbM…cyc=` matches
     * an inbound query for `eAbM…cyc` (no padding) — without this, the
     * sendTextMessage routing falls through to VPS even when the peer is
     * connected on TCP/BLE, which is why iPhone was only ever seeing
     * Android messages via IPFS.
     *
     * Routing-table entries are only honored if their lastSeen is within the
     * last 90s — older entries are treated as stale (peer probably gone).
     */
    fun isPeerReachable(publicKey: String): Boolean {
        val want = normalizeKey(publicKey)
        if (_connectedPeers.value.any { normalizeKey(it.publicKey) == want }) {
            return true
        }
        val now = System.currentTimeMillis()
        return routingTable.entries.any { (k, route) ->
            normalizeKey(k) == want && (now - route.lastSeen) < 90_000L
        }
    }

    /**
     * Check if peer is locally discoverable (BLE/NSD seen, TCP handshake may still be in progress).
     * Used by the routing layer to wait briefly for a fresh connection before falling back to VPS.
     * Also uses normalized matching for the same reason as [isPeerReachable].
     */
    fun isPeerDiscovered(publicKey: String): Boolean {
        val want = normalizeKey(publicKey)
        return _crossPlatformPeers.value.any { normalizeKey(it.publicKey) == want }
    }

    /**
     * Look up the connected peer for a recipient using normalized base64
     * comparison. Returns null if not connected. Callers should prefer this
     * over `connectedPeers.value.find { it.publicKey == … }` to avoid
     * silently falling back to VPS when only the formatting differs.
     */
    fun findConnectedPeer(publicKey: String): CrossPlatformPeer? {
        val want = normalizeKey(publicKey)
        return _connectedPeers.value.firstOrNull { normalizeKey(it.publicKey) == want }
    }

    /**
     * Get iOS peers
     */
    fun getIOSPeers(): List<CrossPlatformPeer> {
        return _crossPlatformPeers.value.filter { it.platform == "ios" }
    }

    // ============================================================================
    // TCP SERVER
    // ============================================================================

    private fun startTCPServer() {
        scope.launch {
            try {
                serverSocket = ServerSocket(0)  // Dynamic port
                serverPort = serverSocket!!.localPort
                Log.d(TAG, "📡 TCP server started on port $serverPort")

                while (isActive && serverSocket != null) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        Log.d(TAG, "📥 Incoming TCP connection from ${clientSocket.inetAddress}")
                        handleIncomingConnection(clientSocket)
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TAG, "TCP accept error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TCP server error: ${e.message}")
            }
        }
    }

    private fun stopTCPServer() {
        // 🔧 FIX: Wrap socket teardown in try/catch — `serverSocket.close()` and
        // `Socket.close()` can throw IOException if the socket was already closed by
        // the kernel (peer reset, network change) and a stray exception during shutdown
        // would otherwise propagate up the stop() chain and crash service teardown.
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "stopTCPServer: serverSocket close failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        serverSocket = null

        // Snapshot the values so we don't hit ConcurrentModificationException if
        // another coroutine is mid-write into the map while we tear it down.
        val socketsToClose = tcpConnections.values.toList()
        for (s in socketsToClose) {
            try { s.close() } catch (_: Exception) { /* best-effort teardown */ }
        }
        tcpConnections.clear()
        outputStreams.clear()
    }

    private fun handleIncomingConnection(socket: Socket) {
        scope.launch {
            try {
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())

                // Receive loop
                while (isActive && socket.isConnected) {
                    // Read length header (4 bytes, big endian)
                    val length = input.readInt()
                    // The old cap was 1 MiB with a `continue` that did NOT drain the
                    // `length` body bytes, so any iPhone media whose base64 exceeded
                    // 1 MiB desynced this TCP stream permanently — every later frame
                    // from that peer became garbage. Legit frames go up to the send
                    // cap (MESH_MAX_PAYLOAD_SIZE, base64-expanded) plus JSON overhead.
                    //
                    // Then it was 16 MiB with a `break`, and BOTH halves of that were
                    // wrong: iOS writes frames up to 100 MiB ([MAX_FRAME_SIZE], its
                    // `maxFrameSize`), so a large iPhone→Android LAN media send was
                    // refused; and refusing it TORE DOWN the connection, so one
                    // oversize frame cost the whole session. One peer's bad frame is
                    // now that frame's problem: drain it and keep reading.
                    when (frameAction(length)) {
                        MeshFrameAction.ACCEPT -> Unit
                        MeshFrameAction.DROP -> {
                            Log.w(TAG, "Mesh frame length=$length over ${MAX_FRAME_SIZE}B cap — dropping frame, keeping connection")
                            // Skip the body so the stream stays framed. `skipBytes`
                            // may return short, so loop; a peer that never sends the
                            // bytes it promised trips the socket's own read failure,
                            // which is the same outcome as before.
                            var remaining = length.toLong()
                            while (remaining > 0) {
                                val skipped = input.skipBytes(minOf(remaining, Int.MAX_VALUE.toLong()).toInt())
                                if (skipped <= 0) throw java.io.EOFException("peer stopped mid-frame with $remaining B left")
                                remaining -= skipped
                            }
                            continue
                        }
                        MeshFrameAction.RESET -> {
                            Log.w(TAG, "Mesh frame length=$length not a plausible frame — stream desynced, resetting connection")
                            break
                        }
                    }

                    // Read message
                    val buffer = ByteArray(length)
                    input.readFully(buffer)

                    handleReceivedData(buffer, socket, output)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Connection closed: ${e.message}")
                // 🔧 FIX: Clean up peer state when connection dies
                cleanupDeadConnection(socket)
            }
        }
    }

    // 🔧 CRITICAL: Clean up all state when a TCP connection dies
    private fun cleanupDeadConnection(socket: Socket) {
        val peerKey = tcpConnections.entries.find { it.value === socket }?.key
            ?: outputStreams.entries.find {
                try { it.value.toString().contains(socket.toString()) } catch (_: Exception) { false }
            }?.key
        if (peerKey != null) {
            Log.d(TAG, "🧹 Cleaning up dead connection for ${peerKey.take(16)}...")
            tcpConnections.remove(peerKey)
            outputStreams.remove(peerKey)
            routingTable.remove(peerKey)
            sentIdentityTo.remove(peerKey)
            dropConnectedPeer(peerKey)
            // Allow BLE to re-discover this peer
            discoveredDevices.clear()
            Log.d(TAG, "🧹 Peer removed (connected: ${_connectedPeers.value.size})")
        }
        try { socket.close() } catch (_: Exception) {}
    }

    /**
     * Take a peer off the connected list and put it back on the discovered one.
     *
     * __A_DROPPED_PEER_FELL_OUT_OF_BOTH_LISTS_2026_11_07__ Every eviction path
     * in this class used to only remove. A peer that connects is removed from
     * the discovered list on purpose (so the Peers screen does not show it
     * twice), so removing it from the connected list as well left it in neither
     * — invisible to the UI and to the reconnect sweep, and un-re-discoverable,
     * because NSD announces a service once per browse. The peer still holds the
     * address we were just talking to; hand it back to the sweep.
     */
    private fun dropConnectedPeer(peerKey: String) {
        val current = _connectedPeers.value.toMutableList()
        val dropped = current.firstOrNull { it.publicKey == peerKey }
        current.removeAll { it.publicKey == peerKey }
        _connectedPeers.value = current

        if (dropped?.ipAddress == null || dropped.port == null) return
        val discovered = _crossPlatformPeers.value.toMutableList()
        if (discovered.none { normalizeKey(it.publicKey) == normalizeKey(dropped.publicKey) }) {
            discovered.add(dropped)
            _crossPlatformPeers.value = discovered
        }
        // It was a working link a moment ago; do not make it serve a backoff
        // earned by some earlier failure.
        dialFailures.remove(dropped.publicKey)
        dialFailures.remove(peerKey)
    }

    /**
     * Is there a live socket for this peer key?
     *
     * Normalized, because an outgoing dial keys the map on the spelling the
     * peer ANNOUNCED while an incoming connection keys it on the spelling that
     * peer stamped on its IDENTITY_EXCHANGE, and those differ whenever one side
     * uses base64url. A strict `containsKey` therefore read "not connected" for
     * a peer we were actively talking to, and dialled it a second time.
     *
     * A socket that is closed or half-open does not count: that is the state
     * the whole reconnect sweep exists to get out of.
     */
    private fun hasLiveConnection(publicKey: String): Boolean {
        val want = normalizeKey(publicKey)
        val socket = tcpConnections.entries
            .firstOrNull { normalizeKey(it.key) == want }
            ?.value
            ?: return false
        return socket.isConnected && !socket.isClosed && !socket.isOutputShutdown
    }

    private fun connectToPeer(peer: CrossPlatformPeer) {
        if (peer.ipAddress == null || peer.port == null) return
        if (peer.publicKey.isEmpty()) return
        // __MESH_DIAL_LAN_ONLY_2026_11_20__ never dial a non-local address handed to us
        // by an unauthenticated announcement (see MeshDialPolicy).
        if (!MeshDialPolicy.isDialable(peer.ipAddress)) {
            Log.w(TAG, "Refusing to dial non-local mesh address from an announcement")
            return
        }
        if (hasLiveConnection(peer.publicKey)) return
        // `add` returns false when the key is already there: one dial per peer
        // at a time, however many callers ask. The sweep, the discovery
        // callbacks and the Connecter button all land here.
        if (!dialsInFlight.add(peer.publicKey)) return
        lastDialAt[peer.publicKey] = System.currentTimeMillis()

        scope.launch {
            try {
                Log.d(TAG, "🔗 Connecting to ${peer.displayName} at ${peer.ipAddress}:${peer.port}")

                val socket = Socket()
                socket.connect(InetSocketAddress(peer.ipAddress, peer.port!!.toInt()), 10000)

                tcpConnections[peer.publicKey] = socket
                val output = DataOutputStream(socket.getOutputStream())
                outputStreams[peer.publicKey] = output

                // Add to connected peers
                val currentPeers = _connectedPeers.value.toMutableList()
                if (currentPeers.none { it.publicKey == peer.publicKey }) {
                    currentPeers.add(peer)
                    _connectedPeers.value = currentPeers
                }

                // Remove from discovered list (prevent duplicates in UI)
                val discoveredPeers = _crossPlatformPeers.value.toMutableList()
                discoveredPeers.removeAll { sameMeshIdentity(it.publicKey, peer.publicKey) || (it.ipAddress != null && it.ipAddress == peer.ipAddress) }
                _crossPlatformPeers.value = discoveredPeers

                Log.d(TAG, "✅ Connected to ${peer.displayName}")

                // Send identity and mark as sent
                sentIdentityTo.add(peer.publicKey)
                sendIdentity(output)
                // 2-hop gossip: announce ourselves so the new peer can route to us
                sendIdentityAnnounce(output)

                // Start receive loop
                handleIncomingConnection(socket)

                dialFailures.remove(peer.publicKey)

            } catch (e: Exception) {
                val failures = (dialFailures[peer.publicKey] ?: 0) + 1
                dialFailures[peer.publicKey] = failures
                // Used to be the end of the road for this peer. Now it is a
                // number the sweep reads: see [MeshReconnectPolicy.backoffMs].
                Log.e(
                    TAG,
                    "❌ Failed to connect to ${peer.displayName}: ${e.javaClass.simpleName}: ${e.message} " +
                        "— attempt $failures, next in ${MeshReconnectPolicy.backoffMs(failures)}ms"
                )
            } finally {
                dialsInFlight.remove(peer.publicKey)
            }
        }
    }

    // ============================================================================
    // RECONNECT (see MeshReconnectPolicy)
    // ============================================================================

    /** The outcome of a user asking, in so many words, to connect to one peer. */
    enum class ConnectRequest {
        /** A socket is already up — the UI should be showing this peer connected. */
        ALREADY_CONNECTED,

        /** A TCP connection to the peer's announced address is being opened. */
        DIALING,

        /** No address for this peer yet; discovery has been restarted to find one. */
        SEARCHING,

        /** We have never seen this key. Discovery has been restarted anyway. */
        NOT_DISCOVERED,
    }

    /**
     * Connect to one discovered peer, now, because the user asked.
     *
     * Before this existed the Connecter button on the Peers screen called a
     * `PeersViewModel` branch whose body was a comment. Every retry path in
     * this class was a discovery callback that fires once.
     */
    fun connectNow(publicKey: String): ConnectRequest {
        if (publicKey.isEmpty()) return ConnectRequest.NOT_DISCOVERED
        if (hasLiveConnection(publicKey)) return ConnectRequest.ALREADY_CONNECTED

        val want = normalizeKey(publicKey)
        val peer = _crossPlatformPeers.value.firstOrNull { sameMeshIdentity(it.publicKey, want) }
        if (peer == null) {
            restartDiscovery(force = true)
            return ConnectRequest.NOT_DISCOVERED
        }

        // The user pressing the button forgives every earlier failure: they can
        // see the other phone, and the backoff cannot.
        dialFailures.remove(peer.publicKey)

        return when (
            MeshReconnectPolicy.decide(
                connected = false,
                hasAddress = peer.ipAddress != null && peer.port != null,
                dialInFlight = dialsInFlight.contains(peer.publicKey),
                failures = 0,
                msSinceLastDial = Long.MAX_VALUE,
                userAsked = true,
            )
        ) {
            MeshReconnectPolicy.Attempt.DIAL -> {
                connectToPeer(peer)
                ConnectRequest.DIALING
            }
            MeshReconnectPolicy.Attempt.REDISCOVER -> {
                restartDiscovery(force = true)
                ConnectRequest.SEARCHING
            }
            // A dial is already running for this key — pressing again should
            // say so, not start a second socket.
            MeshReconnectPolicy.Attempt.WAIT -> ConnectRequest.DIALING
            MeshReconnectPolicy.Attempt.ALREADY_CONNECTED -> ConnectRequest.ALREADY_CONNECTED
        }
    }

    /**
     * Tear down NSD browsing and start it again, and re-arm BLE.
     *
     * This is the only way to make a peer that is ALREADY registered on the LAN
     * announce itself to us a second time: `onServiceFound` fires once per
     * service per browse. It is what the Refresh button on the Peers screen
     * always looked like it did.
     *
     * @param force skip the [MeshReconnectPolicy.REDISCOVER_MIN_INTERVAL_MS]
     *   floor. True for a user action, false for the automatic sweep.
     */
    fun restartDiscovery(force: Boolean = false) {
        if (!_isRunning.value) return
        val now = System.currentTimeMillis()
        if (!force && now - lastDiscoveryRestartAt < MeshReconnectPolicy.REDISCOVER_MIN_INTERVAL_MS) return
        lastDiscoveryRestartAt = now

        Log.d(TAG, "🔄 Restarting discovery (force=$force, discovered=${_crossPlatformPeers.value.size}, connected=${_connectedPeers.value.size})")

        // Let BLE re-read announces from devices we have already seen, and give
        // a spliced announce a clean slate.
        discoveredDevices.clear()
        announceReadRetries.clear()
        retryBleIfPermitted()

        scope.launch {
            stopNSDDiscovery()
            delay(500)   // NsdManager does not like a start that races its own stop
            if (_isRunning.value) startNSDDiscovery()
        }
    }

    /**
     * Dial every discovered peer that is not connected and whose backoff has
     * elapsed. Runs every [MeshReconnectPolicy.SWEEP_INTERVAL_MS].
     */
    private fun sweepDiscoveredPeers() {
        val now = System.currentTimeMillis()
        var addressless = 0

        for (peer in _crossPlatformPeers.value) {
            if (peer.publicKey.isEmpty()) continue
            val decision = MeshReconnectPolicy.decide(
                connected = hasLiveConnection(peer.publicKey),
                hasAddress = peer.ipAddress != null && peer.port != null,
                dialInFlight = dialsInFlight.contains(peer.publicKey),
                failures = dialFailures[peer.publicKey] ?: 0,
                msSinceLastDial = now - (lastDialAt[peer.publicKey] ?: 0L),
                userAsked = false,
            )
            when (decision) {
                MeshReconnectPolicy.Attempt.DIAL -> {
                    Log.d(TAG, "🔁 Reconnect sweep: dialling ${peer.displayName} (failures=${dialFailures[peer.publicKey] ?: 0})")
                    connectToPeer(peer)
                }
                MeshReconnectPolicy.Attempt.REDISCOVER -> addressless++
                MeshReconnectPolicy.Attempt.WAIT,
                MeshReconnectPolicy.Attempt.ALREADY_CONNECTED -> Unit
            }
        }

        if (addressless > 0) {
            Log.d(TAG, "🔁 Reconnect sweep: $addressless peer(s) seen with no address — asking discovery again")
            restartDiscovery()
        }
    }

    private fun scheduleReconnectSweep(generation: Int) = trackPeriodic(
        scope.launch {
            while (isActive && stillCurrent(generation)) {
                delay(MeshReconnectPolicy.SWEEP_INTERVAL_MS)
                if (!stillCurrent(generation)) break
                try {
                    sweepDiscoveredPeers()
                } catch (e: Exception) {
                    Log.w(TAG, "Reconnect sweep error: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    )

    private fun sendIdentity(output: DataOutputStream) {
        // Build the inner identity payload with JSONObject so a display name that
        // contains " or \ produces valid JSON. A quote-free name yields byte-identical
        // output to the previous string-interpolated form, so this stays wire-compatible
        // with the shipping iOS peer (which parses this payload).
        val identityPayload = JSONObject().apply {
            put("publicKey", myPublicKey)
            put("name", myDisplayName)
            put("platform", "android")
        }.toString()
        val identity = CrossPlatformMessage(
            id = UUID.randomUUID().toString(),
            type = "IDENTITY_EXCHANGE",
            senderPublicKey = myPublicKey,
            senderName = myDisplayName,
            recipientPublicKey = "",
            payload = identityPayload,
            timestamp = System.currentTimeMillis().toDouble(),
            hopCount = 0,
            maxHops = 1,
            seenBy = mutableListOf(),
            platform = "android"
        )
        sendData(identity.toJSON(), output)
    }

    private fun sendData(data: ByteArray, output: DataOutputStream) {
        // 🔧 DIAG_XMESH_TX_PRECHECK: detect half-open sockets BEFORE writing —
        // OutputStream.write() succeeds for many bytes after the peer is gone (kernel
        // buffer absorbs the writes silently), so a "successful" return here is not
        // proof of delivery. Find the socket that owns this DataOutputStream and
        // verify it is still alive; if not, evict and bail so the caller can retry
        // via VPS.
        val owningEntry = outputStreams.entries.firstOrNull { it.value === output }
        val owningKey = owningEntry?.key
        val owningSocket = owningKey?.let { tcpConnections[it] }
        if (owningSocket != null) {
            val dead = owningSocket.isClosed || owningSocket.isOutputShutdown || !owningSocket.isConnected
            if (dead) {
                Log.w(TAG, "DIAG_XMESH_TX_DEAD | key=${owningKey.take(16)} closed=${owningSocket.isClosed} outShut=${owningSocket.isOutputShutdown} connected=${owningSocket.isConnected} — evicting and aborting send")
                outputStreams.remove(owningKey)
                tcpConnections.remove(owningKey)
                routingTable.remove(owningKey)
                sentIdentityTo.remove(owningKey)
                dropConnectedPeer(owningKey)
                try { owningSocket.close() } catch (_: Exception) {}
                return
            }
        }
        try {
            // Write length header (4 bytes, big endian)
            // 🔧 FIX: Synchronize on the stream to prevent interleaved writes when
            // multiple threads (mesh broadcast + relay + direct send) target the same peer.
            // Without this, two concurrent writes can corrupt the length header.
            synchronized(output) {
                output.writeInt(data.size)
                output.write(data)
                output.flush()
            }
            if (owningKey != null) {
                Log.d(TAG, "DIAG_XMESH_TX_OK | key=${owningKey.take(16)} | bytes=${data.size}")
            }
        } catch (e: Exception) {
            // 🔧 FIX: Log exception class even when message is null (e.g. SocketException
            // from a closed peer arrives with null message — was masked as "Send error: null").
            Log.e(TAG, "DIAG_XMESH_TX_FAIL | key=${owningKey?.take(16) ?: "?"} | ${e.javaClass.simpleName}: ${e.message ?: "(no message)"}")
            // Evict on write failure too — the next send shouldn't keep hitting the same dead stream.
            if (owningKey != null) {
                outputStreams.remove(owningKey)
                tcpConnections.remove(owningKey)
                routingTable.remove(owningKey)
                sentIdentityTo.remove(owningKey)
                dropConnectedPeer(owningKey)
            }
        }
    }

    /**
     * Normalize a base64/base64url public key for tolerant lookups.
     * Mirrors the Android-side `MessageRepository.normalizeBase64Key`:
     * - trim whitespace
     * - base64url → standard ("-" → "+", "_" → "/")
     * - drop "=" padding
     * Used by sendOrRelay to recover from format skew between iPhone's
     * IDENTITY_EXCHANGE key and the Contact.publicKey stored on Android.
     */
    private fun normalizeKey(key: String): String = normalizeMeshKey(key)

    /**
     * Look up an outputStream by tolerant key match.
     * Returns Pair(actualKey, stream) or null. The actualKey is the key as
     * stored in `outputStreams` so callers can log whether a normalized hit
     * occurred (tells us iPhone↔Android format skew is the culprit).
     */
    private fun findStreamForRecipient(recipientPublicKey: String): Pair<String, DataOutputStream>? {
        // Strict-equality fast path
        outputStreams[recipientPublicKey]?.let { return recipientPublicKey to it }
        // Normalized fallback
        val want = normalizeKey(recipientPublicKey)
        val match = outputStreams.entries.firstOrNull { normalizeKey(it.key) == want }
        return match?.let { it.key to it.value }
    }

    private fun handleReceivedData(data: ByteArray, socket: Socket, output: DataOutputStream) {
        try {
            val message = CrossPlatformMessage.fromJSON(data)
            if (message == null) {
                Log.e(TAG, "Failed to parse message")
                return
            }

            Log.d(TAG, "📥 Received ${message.type} from ${message.senderName} (${message.platform})")

            when (message.type) {
                "IDENTITY_EXCHANGE" -> handleIdentityExchange(message, socket, output)
                "IDENTITY_ANNOUNCE" -> handleIdentityAnnounce(message, socket)
                // 🔐 PEER AUTH proof-of-possession (additive/advisory, see MeshPeerAuth.kt).
                // Older peers on either platform never send these and hit the `else`
                // branch (log-and-ignore an unknown type) — safe one-sided.
                "PEER_CHALLENGE" -> handlePeerChallenge(message, output)
                "PEER_CHALLENGE_RESPONSE" -> handlePeerChallengeResponse(message)
                "TEXT_MESSAGE", "MEDIA_MESSAGE", "CALL_SIGNAL", "RELAY",
                "LOCATION_MESSAGE", "DOCUMENT_MESSAGE",
                // 👥 iOS sends group sync responses as GROUP_UPDATE type and group messages
                // as GROUP_MESSAGE type via CrossPlatformMesh — route through content pipeline
                "GROUP_UPDATE", "GROUP_MESSAGE" -> handleContentMessage(message)
                "CALL_AUDIO" -> handleCallAudio(message)
                "CALL_VIDEO" -> handleCallAudio(message)  // Same handler — callbacks route by type
                "PUBLIC_GROUP_AD" -> {
                    Log.d(TAG, "📡 Received PUBLIC_GROUP_AD from ${message.senderName}")
                    // 🔧 FIX: Snapshot under lock to avoid ConcurrentModificationException.
                    val snapshot = synchronized(groupAdCallbacks) { groupAdCallbacks.toList() }
                    snapshot.forEach {
                        try { it(message.payload) } catch (e: Exception) {
                            Log.w(TAG, "groupAdCallback threw: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                }
                else -> Log.d(TAG, "Unknown message type: ${message.type}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Handle message error: ${e.message}")
        }
    }

    private fun handleIdentityExchange(message: CrossPlatformMessage, socket: Socket, output: DataOutputStream) {
        // Store connection
        tcpConnections[message.senderPublicKey] = socket
        outputStreams[message.senderPublicKey] = output

        // Update routing table
        routingTable[message.senderPublicKey] = RouteInfo(
            nextHop = message.senderPublicKey,
            hopCount = 1,
            lastSeen = System.currentTimeMillis()
        )

        // Update connected peers
        val currentPeers = _connectedPeers.value.toMutableList()
        if (currentPeers.none { it.publicKey == message.senderPublicKey }) {
            currentPeers.add(CrossPlatformPeer(
                publicKey = message.senderPublicKey,
                displayName = message.senderName,
                platform = message.platform,
                ipAddress = socket.inetAddress.hostAddress,
                port = socket.port
            ))
            _connectedPeers.value = currentPeers
        }

        // Remove from discovered list by publicKey or IP (prevent duplicate entries in UI)
        val peerIp = socket.inetAddress.hostAddress
        val discoveredPeers = _crossPlatformPeers.value.toMutableList()
        discoveredPeers.removeAll { it.publicKey == message.senderPublicKey || (peerIp != null && it.ipAddress == peerIp) }
        _crossPlatformPeers.value = discoveredPeers

        // CRITICAL: Send OUR identity back so the other side knows who we are!
        // Only send if we haven't already sent identity on this connection (prevents infinite loop)
        if (!sentIdentityTo.contains(message.senderPublicKey)) {
            sentIdentityTo.add(message.senderPublicKey)
            Log.d(TAG, "🤝 Identity exchange complete: ${message.senderName} (${message.platform}) — sending our identity back")
            sendIdentity(output)
        } else {
            Log.d(TAG, "🤝 Identity exchange complete: ${message.senderName} (${message.platform}) — already sent our identity")
        }

        // 2-hop gossip: tell the new peer our identity (and let them re-broadcast)
        sendIdentityAnnounce(output)

        // 🔐 PEER AUTH: challenge the peer to prove possession of the key it
        // announced (additive/advisory).
        sendPeerChallenge(message.senderPublicKey, output)
    }

    // ============================================================================
    // PEER AUTHENTICATION (proof-of-possession, see MeshPeerAuth.kt)
    // ============================================================================
    //
    // Wire format (JSON payload on the existing CrossPlatformMessage envelope,
    // identical to iOS CrossPlatformMesh.swift):
    //   PEER_CHALLENGE           payload = {"nonce":"<base64(32)>"}
    //   PEER_CHALLENGE_RESPONSE  payload = {"nonce":"<base64(32)>","tag":"<base64(32)>"}
    // The challenger's identity is message.senderPublicKey; the responder computes
    // tag = HMAC-SHA256(DH(their_private, challenger_public), nonce). Symmetry lets
    // the challenger verify with DH(challenger_private, responder_public).

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** Send a fresh DH challenge to a peer whose announced key we just learned. */
    private fun sendPeerChallenge(peerKey: String, output: DataOutputStream) {
        if (peerKey.isEmpty()) return
        val nonce = MeshPeerAuth.makeNonce()
        pendingChallengeNonces[peerKey] = nonce
        val payload = JSONObject().apply { put("nonce", b64(nonce)) }.toString()
        val msg = CrossPlatformMessage(
            id = UUID.randomUUID().toString(),
            type = "PEER_CHALLENGE",
            senderPublicKey = myPublicKey,
            senderName = myDisplayName,
            recipientPublicKey = peerKey,
            payload = payload,
            timestamp = System.currentTimeMillis().toDouble(),
            hopCount = 0,
            maxHops = 1,
            seenBy = mutableListOf(),
            platform = "android"
        )
        try {
            sendData(msg.toJSON(), output)
            Log.d(TAG, "🔐 Sent peer-auth challenge to ${peerKey.take(16)}…")
        } catch (e: Exception) {
            // Advisory only — a failed challenge never blocks delivery.
            Log.w(TAG, "🔐 Failed to send peer-auth challenge: ${e.message}")
        }
    }

    /**
     * A peer challenged us: return HMAC(DH(our_private, their_public), nonce).
     * On any failure (no key pair / bad payload) we stay silent — advisory only.
     */
    private fun handlePeerChallenge(message: CrossPlatformMessage, output: DataOutputStream) {
        try {
            if (!cryptoManager.hasKeyPair()) return
            val nonce = Base64.decode(JSONObject(message.payload).getString("nonce"), Base64.NO_WRAP)
            if (nonce.size != MeshPeerAuth.NONCE_LENGTH) return
            val sharedSecret = cryptoManager.deriveSharedSecret(message.senderPublicKey)
            val tag = MeshPeerAuth.tag(sharedSecret, nonce)
            val payload = JSONObject().apply {
                put("nonce", b64(nonce))
                put("tag", b64(tag))
            }.toString()
            val msg = CrossPlatformMessage(
                id = UUID.randomUUID().toString(),
                type = "PEER_CHALLENGE_RESPONSE",
                senderPublicKey = myPublicKey,
                senderName = myDisplayName,
                recipientPublicKey = message.senderPublicKey,
                payload = payload,
                timestamp = System.currentTimeMillis().toDouble(),
                hopCount = 0,
                maxHops = 1,
                seenBy = mutableListOf(),
                platform = "android"
            )
            sendData(msg.toJSON(), output)
            Log.d(TAG, "🔐 Answered peer-auth challenge from ${message.senderPublicKey.take(16)}…")
        } catch (e: Exception) {
            Log.w(TAG, "🔐 Cannot answer peer-auth challenge — skipping (advisory): ${e.message}")
        }
    }

    /**
     * A peer answered our challenge. Verify the tag (constant-time) against the
     * nonce we sent and mark the peer verified on match. A mismatch never drops
     * the peer — verification is advisory.
     */
    private fun handlePeerChallengeResponse(message: CrossPlatformMessage) {
        try {
            val obj = JSONObject(message.payload)
            val nonce = Base64.decode(obj.getString("nonce"), Base64.NO_WRAP)
            val tag = Base64.decode(obj.getString("tag"), Base64.NO_WRAP)
            val pending = pendingChallengeNonces[message.senderPublicKey]
            if (pending == null || !MeshPeerAuth.constantTimeEquals(nonce, pending)) {
                Log.d(TAG, "🔐 Peer-auth response nonce mismatch — ignoring")
                return
            }
            if (!cryptoManager.hasKeyPair()) return
            val sharedSecret = cryptoManager.deriveSharedSecret(message.senderPublicKey)
            if (MeshPeerAuth.verify(tag, sharedSecret, nonce)) {
                pendingChallengeNonces.remove(message.senderPublicKey)
                _verifiedPeerKeys.value = _verifiedPeerKeys.value + message.senderPublicKey
                Log.d(TAG, "✅ Peer VERIFIED possession of ${message.senderPublicKey.take(16)}…")
            } else {
                Log.d(TAG, "🔐 Peer-auth tag mismatch — staying unverified (advisory, delivery unaffected)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "🔐 Failed to process peer-auth response (advisory): ${e.message}")
        }
    }

    /** Advisory: has this peer (by public key) proven possession of its key? */
    fun isPeerVerified(publicKey: String): Boolean = _verifiedPeerKeys.value.contains(publicKey)

    /**
     * Claim [id] for delivery, or report it as already seen. True means "this caller
     * owns it, carry on".
     *
     * 🔴 THE CHECK AND THE INSERT MUST BE ONE OPERATION. They used to be two
     * (`contains` then `add`), and the mesh runs several transports at once — BLE,
     * NSD/TCP, Wi-Fi Direct — all delivering on their own threads. The same relayed
     * id arriving down two lanes could pass `contains` twice before either `add`
     * landed, and the message was delivered twice. `LinkedHashSet.add` already
     * answers both questions atomically; it just has to be called under the lock.
     *
     * The trim needs the lock for a second reason: `take` ITERATES, and iterating a
     * `Collections.synchronizedSet` without holding its monitor is documented
     * undefined behaviour — in practice a `ConcurrentModificationException` thrown
     * inside inbound delivery.
     */
    private fun markSeen(id: String): Boolean {
        synchronized(seenMessageIds) {
            if (!seenMessageIds.add(id)) return false
            if (seenMessageIds.size > MAX_CACHE_SIZE) {
                // Insertion-ordered ⇒ the first half is the oldest half. See the note
                // on [seenMessageIds].
                val toRemove = seenMessageIds.take(MAX_CACHE_SIZE / 2)
                seenMessageIds.removeAll(toRemove.toSet())
            }
        }
        return true
    }

    /**
     * Empty the dedup cache. Called from [stop], mirroring iOS
     * (`CrossPlatformMesh.swift:297-301`), and `internal` so a JVM test can prove
     * the restart really does forget — the alternative is asserting on a private
     * field through reflection, which stops being a test of the behaviour.
     */
    internal fun clearSeenMessageIds() {
        synchronized(seenMessageIds) { seenMessageIds.clear() }
    }

    /** How many ids the dedup cache is holding. For [clearSeenMessageIds]'s test. */
    internal fun seenMessageIdCount(): Int = synchronized(seenMessageIds) { seenMessageIds.size }

    /** Record an id as seen; false when it was already there. For tests. */
    internal fun markSeenForTest(id: String): Boolean = markSeen(id)

    private fun handleContentMessage(message: CrossPlatformMessage) {
        // Duplicate check
        if (!markSeen(message.id)) return

        // Update routing table
        routingTable[message.senderPublicKey] = RouteInfo(
            nextHop = message.senderPublicKey,
            hopCount = message.hopCount,
            lastSeen = System.currentTimeMillis()
        )

        // Is it for us? __MESH_RECIPIENT_SPELLING_2026_10_12__ — base64url and
        // standard base64 are the same key; see [isAddressedToUs] for why getting
        // this wrong loses the message rather than parking it.
        if (isAddressedToUs(message.recipientPublicKey, myPublicKey)) {
            Log.d(TAG, "🎯 Message is for us!")

            // Notify callbacks
            scope.launch(Dispatchers.Main) {
                dispatchToMessageCallbacks(message)
            }
        } else {
            Log.w(TAG, "⚠️ Recipient key mismatch — relaying. mine=${normalizeMeshKey(myPublicKey).take(24)}... msg=${normalizeMeshKey(message.recipientPublicKey).take(24)}...")
            // Relay to recipient
            relayToRecipient(message)
        }
    }

    // Handle location messages from iOS
    private fun handleLocationMessage(message: CrossPlatformMessage) {
        Log.d(TAG, "📍 Location message from ${message.senderName} (${message.platform})")

        // Duplicate check
        if (!markSeen(message.id)) return

        // Decode base64 location data
        try {
            val locationJson = String(Base64.decode(message.payload, Base64.DEFAULT))
            Log.d(TAG, "   Location data: $locationJson")

            // Notify callbacks with location data
            scope.launch(Dispatchers.Main) {
                dispatchToMessageCallbacks(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode location: ${e.message}")
        }
    }

    // Handle document messages from iOS
    private fun handleDocumentMessage(message: CrossPlatformMessage) {
        Log.d(TAG, "📄 Document message from ${message.senderName} (${message.platform})")

        // Duplicate check
        if (!markSeen(message.id)) return

        // Parse document payload (JSON with data, fileName, mimeType)
        try {
            val docPayload = JSONObject(message.payload)
            val base64Data = docPayload.getString("data")
            val fileName = docPayload.getString("fileName")
            val mimeType = docPayload.optString("mimeType", "application/octet-stream")
            val fileSize = docPayload.optInt("fileSize", 0)

            Log.d(TAG, "   Document: $fileName ($mimeType, $fileSize bytes)")

            // Notify callbacks
            scope.launch(Dispatchers.Main) {
                dispatchToMessageCallbacks(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode document: ${e.message}")
        }
    }

    // Handle call audio from iOS
    private fun handleCallAudio(message: CrossPlatformMessage) {
        // Log.v(TAG, "🔊 Call audio from ${message.senderName}")  // Commented to reduce log spam

        // Duplicate check
        if (!markSeen(message.id)) return

        // Decode base64 audio data and notify callbacks
        try {
            val audioData = Base64.decode(message.payload, Base64.DEFAULT)

            scope.launch(Dispatchers.Main) {
                dispatchToMessageCallbacks(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode audio: ${e.message}")
        }
    }

    /**
     * 🔧 FIX: Snapshot the synchronized callback list before iterating, and isolate each
     * callback in try/catch so a misbehaving listener cannot kill the dispatch loop.
     * This eliminates ConcurrentModificationException seen when listeners register/unregister
     * (e.g., during chat screen transitions) while messages are being delivered.
     */
    private fun dispatchToMessageCallbacks(message: CrossPlatformMessage) {
        val snapshot = synchronized(messageCallbacks) { messageCallbacks.toList() }
        snapshot.forEach { cb ->
            try {
                cb(message)
            } catch (e: Exception) {
                Log.w(TAG, "messageCallback threw: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // ============================================================================
    // SEND/RELAY LOGIC
    // ============================================================================

    private fun sendOrRelay(message: CrossPlatformMessage) {
        // CRITICAL: every TCP write MUST go on the IO dispatcher. The previous
        // code ran the routing decision (cheap) plus `sendData` (DataOutputStream
        // I/O) on the caller's thread. When MessageRepository invoked us from
        // a viewModelScope on Main, the very first `output.write(...)` threw
        // android.os.NetworkOnMainThreadException, was caught + logged
        // (`DIAG_XMESH_TX_FAIL: NetworkOnMainThreadException`), and the message
        // was silently dropped — the textbook signature of every Android→iPhone
        // mesh delivery failure observed in logcat. Hop the entire send path
        // onto our existing CoroutineScope (`scope`, Dispatchers.IO) so the
        // throw is impossible regardless of caller context.
        scope.launch {
            // Direct connection? Use tolerant lookup (handles base64 vs base64url and "=" padding skew).
            val direct = findStreamForRecipient(message.recipientPublicKey)
            if (direct != null) {
                val (matchedKey, output) = direct
                val normalizedHit = matchedKey != message.recipientPublicKey
                Log.d(TAG, "DIAG_XMESH_TX_DIRECT | want=${message.recipientPublicKey.take(24)} | matched=${matchedKey.take(24)} | normalizedHit=$normalizedHit | type=${message.type}")
                sendData(message.toJSON(), output)
                return@launch
            }

            // Check routing table (also tolerant)
            val route = routingTable[message.recipientPublicKey]
                ?: routingTable.entries.firstOrNull { normalizeKey(it.key) == normalizeKey(message.recipientPublicKey) }?.value
            if (route != null) {
                val routeOutput = outputStreams[route.nextHop]
                    ?: outputStreams.entries.firstOrNull { normalizeKey(it.key) == normalizeKey(route.nextHop) }?.value
                if (routeOutput != null) {
                    Log.d(TAG, "DIAG_XMESH_TX_ROUTED | via=${route.nextHop.take(16)} | type=${message.type}")
                    sendData(message.toJSON(), routeOutput)
                    return@launch
                }
            }

            // Strict mesh delivery — DO NOT broadcast.
            //
            // Previously the fallback iterated `outputStreams.values` and sent
            // the JSON envelope to EVERY connected peer when no direct/routed
            // path existed. The CrossPlatformMessage envelope carries
            // `senderPublicKey` and `recipientPublicKey` IN PLAINTEXT (the body
            // is encrypted, but the envelope metadata is not), so an unrelated
            // mesh peer Z would see "A messaging W" and learn private contact
            // graph data. That violates the user's spec: "send via mesh ONLY
            // when the receiver is in the Mesh relay, otherwise IPFS."
            //
            // For cross-platform texts, MessageRepository already issues a
            // parallel IPFS upload (the dual-send pattern), so dropping the
            // mesh side here is non-fatal — the IPFS path delivers. For
            // signaling / control packets (CALL_SIGNAL, CALL_AUDIO, etc.) the
            // caller should already have ensured the peer is reachable via
            // ICE/VPS before invoking us.
            val keysPresent = outputStreams.keys.map { it.take(24) }
            Log.w(TAG, "DIAG_XMESH_TX_NO_PATH | want=${message.recipientPublicKey.take(24)} | wantNorm=${normalizeKey(message.recipientPublicKey).take(24)} | streams=${outputStreams.size} | keys=$keysPresent | type=${message.type}")
        }
    }

    private fun relayToRecipient(message: CrossPlatformMessage) {
        // Check hop count
        // Clamp against our LOCAL ceiling — see [shouldDropForHops]. `message.maxHops`
        // comes off the wire; taking it at face value let one peer make every Android
        // node re-flood indefinitely.
        if (shouldDropForHops(message.hopCount, message.maxHops)) {
            Log.w(TAG, "Max hops reached, dropping message")
            return
        }

        // Check if already relayed. FOLD the comparison: we stamp the canonical
        // spelling on the wire (see canonicalWireKey), so our own entry can come
        // back spelled differently from the local `myPublicKey`, and a literal
        // `contains` would miss it and re-flood the frame.
        if (message.seenBy.any { normalizeMeshKey(it) == normalizeMeshKey(myPublicKey) }) {
            return
        }

        // Update for relay
        val relayMessage = message.copy(
            hopCount = message.hopCount + 1,
            seenBy = (message.seenBy + myPublicKey).toMutableList()
        )

        // Try direct route
        val route = routingTable[message.recipientPublicKey]
        if (route != null) {
            val output = outputStreams[route.nextHop]
            if (output != null) {
                Log.d(TAG, "🔄 Relaying via ${route.nextHop.take(16)}...")
                sendData(relayMessage.toJSON(), output)
                return
            }
        }

        // Broadcast to all except sender
        Log.d(TAG, "📡 Broadcast relay (hop ${relayMessage.hopCount})")
        outputStreams.entries
            .filter { !message.seenBy.contains(it.key) }
            .forEach { (_, output) ->
                sendData(relayMessage.toJSON(), output)
            }
    }

    // ============================================================================
    // 2-HOP IDENTITY GOSSIP (IDENTITY_ANNOUNCE)
    // ============================================================================

    /**
     * Build an IDENTITY_ANNOUNCE message describing the originator at the given hop count.
     * Wire keys (originator, displayName, platform, hops, ttl) are kept identical with iOS.
     * If `messageId` is supplied, that id is reused (used when re-broadcasting so the
     * upstream dedupe-by-id still works); otherwise a fresh UUID is generated.
     */
    private fun buildIdentityAnnounce(
        originatorPublicKey: String,
        originatorDisplayName: String,
        originatorPlatform: String,
        hopCount: Int,
        ttl: Int,
        messageId: String? = null
    ): CrossPlatformMessage {
        val payload = JSONObject().apply {
            put("originator", originatorPublicKey)
            put("displayName", originatorDisplayName)
            put("platform", originatorPlatform)
            put("hops", hopCount)
            put("ttl", ttl)
        }.toString()
        return CrossPlatformMessage(
            id = messageId ?: UUID.randomUUID().toString(),
            type = "IDENTITY_ANNOUNCE",
            senderPublicKey = myPublicKey,
            senderName = myDisplayName,
            recipientPublicKey = "",
            payload = payload,
            timestamp = System.currentTimeMillis().toDouble(),
            hopCount = hopCount,
            maxHops = ttl,
            seenBy = mutableListOf(),
            platform = "android"
        )
    }

    /**
     * Send a fresh IDENTITY_ANNOUNCE for ourselves to a single peer's output stream.
     * Used when a peer just connected so they can immediately learn about us.
     */
    private fun sendIdentityAnnounce(output: DataOutputStream) {
        if (myPublicKey.isEmpty()) return
        val msg = buildIdentityAnnounce(
            originatorPublicKey = myPublicKey,
            originatorDisplayName = myDisplayName,
            originatorPlatform = "android",
            hopCount = 0,
            ttl = 2
        )
        markSeen(msg.id)
        sendData(msg.toJSON(), output)
    }

    /**
     * Broadcast our own IDENTITY_ANNOUNCE to all connected peers (hopCount=0, ttl=2).
     */
    private fun broadcastOwnIdentityAnnounce() {
        if (myPublicKey.isEmpty()) return
        val streams = outputStreams.toMap()
        if (streams.isEmpty()) return
        val msg = buildIdentityAnnounce(
            originatorPublicKey = myPublicKey,
            originatorDisplayName = myDisplayName,
            originatorPlatform = "android",
            hopCount = 0,
            ttl = 2
        )
        markSeen(msg.id)
        val data = msg.toJSON()
        Log.d(TAG, "📢 IDENTITY_ANNOUNCE broadcast (peers=${streams.size})")
        streams.values.forEach { sendData(data, it) }
    }

    /**
     * Schedule a 30s recurring IDENTITY_ANNOUNCE broadcast.
     */
    private fun scheduleIdentityAnnounce(generation: Int) = trackPeriodic(
        scope.launch {
            // Initial short delay so TCP connections have a chance to settle
            delay(5_000)
            while (isActive && stillCurrent(generation)) {
                try {
                    broadcastOwnIdentityAnnounce()
                } catch (e: Exception) {
                    Log.w(TAG, "broadcastOwnIdentityAnnounce error: ${e.javaClass.simpleName}: ${e.message}")
                }
                delay(30_000)
            }
        }
    )

    /**
     * Handle an inbound IDENTITY_ANNOUNCE: dedupe, learn route, optionally re-broadcast.
     */
    private fun handleIdentityAnnounce(message: CrossPlatformMessage, fromSocket: Socket) {
        // Dedupe (also drops our own echoes)
        if (!markSeen(message.id)) return

        val obj = try { JSONObject(message.payload) } catch (_: Exception) { return }
        val originator = obj.optString("originator", "")
        val displayName = obj.optString("displayName", "")
        val platform = obj.optString("platform", "unknown")
        val hopsIn = obj.optInt("hops", 0)
        val ttl = obj.optInt("ttl", 2)

        if (originator.isEmpty()) return
        // Folded for the same reason as the seenBy guard above: our own announce
        // comes back in the canonical spelling, which need not be the local one.
        if (normalizeMeshKey(originator) == normalizeMeshKey(myPublicKey)) return // echo

        // Find the link this came in on (publicKey of direct neighbor)
        val nextHopKey = tcpConnections.entries.firstOrNull { it.value === fromSocket }?.key
            ?: message.senderPublicKey

        val newHopCount = hopsIn + 1
        val now = System.currentTimeMillis()

        val existing = routingTable[originator]
        if (existing == null ||
            newHopCount < existing.hopCount ||
            (newHopCount == existing.hopCount && now > existing.lastSeen)
        ) {
            routingTable[originator] = RouteInfo(
                nextHop = nextHopKey,
                hopCount = newHopCount,
                lastSeen = now
            )
        }

        Log.d(TAG, "📢 IDENTITY_ANNOUNCE $displayName ($platform) hops=$newHopCount via ${nextHopKey.take(12)}…")

        // Re-broadcast if we haven't reached TTL
        if (newHopCount < ttl) {
            val relay = buildIdentityAnnounce(
                originatorPublicKey = originator,
                originatorDisplayName = displayName,
                originatorPlatform = platform,
                hopCount = newHopCount,
                ttl = ttl,
                // Reuse the same id so receivers dedupe and don't loop
                messageId = message.id
            )
            val relayData = relay.toJSON()
            tcpConnections.forEach { (peerKey, peerSocket) ->
                if (peerSocket === fromSocket) return@forEach     // skip the link it came from
                if (peerKey == originator) return@forEach         // skip the originator itself
                val out = outputStreams[peerKey] ?: return@forEach
                sendData(relayData, out)
            }
        }
    }

    // ============================================================================
    // BLE ADVERTISING
    // ============================================================================

    private fun startBLEAdvertising() {
        if (!canAdvertise()) {
            Log.e(TAG, "Missing Bluetooth permissions for advertising")
            MeshDiag.fail("advertise refused: missing bluetooth permission",
                          reason = grantedBlePermissions().let { "granted=${it.size}" })
            _bleAdvertising.value = false
            return
        }

        // Idempotent: the ticker calls ensureBleRunning() which calls this, and
        // startAdvertising with a callback that is already registered comes back
        // ADVERTISE_FAILED_ALREADY_STARTED — the advertise-side twin of the
        // errorCode=1 in the Galaxy A17 scan capture.
        if (_bleAdvertising.value && advertiseConfirmedEpoch == advertiseEpoch) {
            MeshDiag.log("advertise: already up and confirmed, nothing to re-arm")
            return
        }
        if (_bleAdvertising.value) {
            // The flag says yes and the radio has never confirmed THIS epoch.
            // That is exactly the state that made a phone invisible for an
            // afternoon, so believe the radio rather than the flag and ask
            // again. A redundant request answers ALREADY_STARTED, which costs
            // nothing and is itself the confirmation we were missing.
            MeshDiag.log("advertise: flag says up but the radio never confirmed this epoch — re-arming")
            _bleAdvertising.value = false
        }

        val advertiser = bleAdvertiser ?: run {
            MeshDiag.fail("advertise refused: no BluetoothLeAdvertiser (radio off or unsupported)")
            return
        }

        applyOshiAdapterName()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            // Add manufacturer data (0xFFFF + "OSHI") — iOS can detect this in broad scan
            // even if the service UUID isn't parsed correctly
            .addManufacturerData(0xFFFF, "OSHI".toByteArray(Charsets.UTF_8))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)  // Will now include "OSHI-xxx"
            .build()

        MeshDiag.log("advertise: asking the radio to start")
        val epoch = advertiseEpoch
        advertisingEpochInFlight = epoch
        // __BLE_OFF_RACE_2026_11_10__ same race as startScan: never let a radio
        // switched off mid-call take the app down.
        try {
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startAdvertising refused: ${e.message}")
            advertisingEpochInFlight = -1
            return
        } catch (e: SecurityException) {
            MeshDiag.fail("advertise refused: ${e.message}")
            advertisingEpochInFlight = -1
            return
        }
        _bleAdvertising.value = true
        Log.d(TAG, "📡 BLE advertising started")

        // Also start GATT server
        startGattServer()
    }

    /**
     * Set adapter name to "OSHI-xxx" so iOS can identify us.
     *
     * This renames the WHOLE PHONE's Bluetooth adapter — the name the user
     * sees in Settings → Bluetooth, and the name every car, headset and
     * laptop they have ever paired with will show. `originalName` used to be
     * read into a local and then never used again, and `stopBLEAdvertising`
     * restored nothing: installing OSHI renamed the user's phone to
     * "OSHI-Hugo" permanently. Save it once (durably — see [savedAdapterName]
     * and [BleRadioPolicy.adapterNameToSave]) and restore it on stop.
     *
     * Also called by [classicBluetoothReceiver] to put the name BACK to
     * "OSHI-xxx" once a classic (non-BLE) connection — a car head unit, a
     * headset — disconnects. Advertising itself is never paused for this:
     * only the name shown while that connection was live changes, which is
     * also why this is split out of [startBLEAdvertising] rather than
     * re-running the whole advertise start/stop cycle on every car trip.
     */
    private fun applyOshiAdapterName() {
        val currentName = try {
            bluetoothAdapter?.name
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot read BT name: ${e.message}"); null
        }
        BleRadioPolicy.adapterNameToSave(currentName, savedAdapterName)?.let { original ->
            savedAdapterName = original
            Log.d(TAG, "💾 Saved the phone's Bluetooth name so it can be given back")
        }
        try {
            bluetoothAdapter?.name = "${BleRadioPolicy.ADAPTER_NAME_PREFIX}${myDisplayName.take(8)}"
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot set BT name: ${e.message}")
        }
    }

    private fun stopBLEAdvertising() {
        // Anything still in flight is now answering about a dead advertisement.
        advertiseEpoch++
        MeshDiag.log("advertise: TEARING DOWN")
        try {
            bleAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopAdvertising: ${e.javaClass.simpleName}: ${e.message}")
        }
        try { gattServer?.close() } catch (e: Exception) { Log.w(TAG, "GATT close: ${e.message}") }
        gattServer = null
        _bleAdvertising.value = false
        restoreAdapterName()
    }

    /**
     * Give the user their phone's Bluetooth name back.
     *
     * Called on stop AND once at [start], so a rename left behind by a process
     * that was killed while advertising — swipe the app away and `stop()` never
     * runs — is repaired on the next launch rather than being permanent.
     */
    private fun restoreAdapterName() {
        val saved = savedAdapterName ?: return
        val current = try {
            bluetoothAdapter?.name
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot read BT name: ${e.message}"); null
        }
        val target = BleRadioPolicy.adapterNameToRestore(saved, current)
        if (target == null) {
            // Nothing of ours left on the adapter; drop the saved copy so a
            // later rename records the (possibly new) real name.
            if (current != null && !current.startsWith(BleRadioPolicy.ADAPTER_NAME_PREFIX)) {
                savedAdapterName = null
            }
            return
        }
        try {
            bluetoothAdapter?.name = target
            Log.d(TAG, "↩️ Restored the phone's own Bluetooth name")
            savedAdapterName = null
        } catch (e: SecurityException) {
            // Keep the saved copy: without BLUETOOTH_CONNECT we cannot put the
            // name back now, and discarding it would lose the only record of it.
            Log.w(TAG, "Cannot restore BT name: ${e.message}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            if (advertisingEpochInFlight != advertiseEpoch) {
                // __A_LATE_SUCCESS_MUST_NOT_RESURRECT_A_DEAD_ADVERTISEMENT_2026_10_23__
                MeshDiag.log("advertise: ignoring a success for an advertisement already torn down")
                return
            }
            Log.d(TAG, "✅ BLE advertising success")
            MeshDiag.log("advertise: LIVE — the mesh service is now discoverable")
            advertiseConfirmedEpoch = advertiseEpoch
            _bleAdvertising.value = true
            _bleFault.value = BleRadioPolicy.RadioFault.NONE
        }

        override fun onStartFailure(errorCode: Int) {
            // Honest state, and a free retry: `startAdvertising` is asynchronous,
            // so the optimistic `_bleAdvertising = true` set at call time can be
            // wrong (ADVERTISE_FAILED_TOO_MANY_ADVERTISERS, or the radio going
            // away mid-call). Clearing it lets `ensureBleRunning` pick the
            // advertisement back up on the next 30 s tick instead of leaving the
            // phone permanently invisible to iOS while claiming otherwise.
            //
            // ALREADY_STARTED is the exception and must NOT clear it: the
            // advertisement really is up, from an earlier call, and pretending
            // otherwise would make every tick re-arm and be rejected again.
            val fault = BleRadioPolicy.advertiseFault(errorCode)
            // ALREADY_STARTED means the radio IS advertising — it is a
            // confirmation, not a failure, and `fault.live` already says so.
            if (fault.live) advertiseConfirmedEpoch = advertiseEpoch
            Log.e(TAG, "❌ BLE advertising failed: errorCode=$errorCode ($fault)")
            MeshDiag.fail("advertise FAILED", code = errorCode, reason = fault.name)
            _bleAdvertising.value = fault.live
            if (!fault.live) _bleFault.value = fault
        }
    }

    private fun startGattServer() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        // Idempotent: `ensureBleRunning` can reach this more than once, and each
        // `openGattServer` that is not closed holds a server-side app slot open.
        gattServer?.let {
            MeshDiag.log("gatt: closing an EXISTING server before opening a new one")
            try { it.close() } catch (e: Exception) { Log.w(TAG, "GATT server close: ${e.message}") }
        }
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // Create characteristic with our info
        val characteristic = BluetoothGattCharacteristic(
            CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        characteristic.value = createAdvertisementData()
        service.addCharacteristic(characteristic)

        gattServer?.addService(service)
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            Log.d(TAG, "📡 GATT server: device=${device?.name ?: device?.address} status=$status state=$newState")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            if (characteristic?.uuid == CHAR_UUID) {
                Log.d(TAG, "📖 GATT read request from ${device?.name ?: device?.address} offset=$offset")
                // Snapshot at offset 0 and serve the continuation reads of a long
                // read from that same snapshot. Recomputing per request embeds a
                // fresh `ip`/`port`, so a Wi-Fi handover mid-read would splice two
                // different JSON documents — nil on iOS's parser, peer silently
                // dropped, no log on either side.
                val data = if (offset <= 0) {
                    createAdvertisementData().also { gattPayloadSnapshot = it }
                } else {
                    gattPayloadSnapshot
                }
                val responseData = gattReadSlice(data, offset)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, responseData)
            }
        }
    }

    private fun createAdvertisementData(): ByteArray {
        val json = JSONObject().apply {
            // Canonical BEFORE truncating: the iPhone stores the peer key it reads
            // here and later dials it, so a base64url prefix strands the link.
            put("pk", canonicalWireKey(myPublicKey).take(32))
            put("name", myDisplayName)
            put("port", serverPort)
            put("platform", "android")
            put("ip", getLocalIpAddress() ?: "")
        }
        return json.toString().toByteArray()
    }

    /**
     * The LAN address we announce. Collect every candidate, then let
     * [pickLanIPv4] choose — see the reasoning there, and do NOT go back to
     * "first interface wins".
     */
    private fun getLocalIpAddress(): String? {
        val candidates = mutableListOf<Pair<String, String>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        candidates.add(networkInterface.name to ip)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get IP error: ${e.message}")
        }
        val picked = pickLanIPv4(candidates)
        if (picked == null && candidates.isNotEmpty()) {
            Log.w(TAG, "no LAN IPv4 to announce; saw ${candidates.map { it.first }}")
        }
        return picked
    }

    // ============================================================================
    // BLE SCANNING
    // ============================================================================

    private fun startBLEScanning() {
        if (!canScan()) {
            MeshDiag.fail("scan refused: missing bluetooth permission")
            _bleScanning.value = false
            return
        }

        // THE defect from the Galaxy A17 capture. `startScan` with a
        // `ScanCallback` that is already registered comes straight back as
        // SCAN_FAILED_ALREADY_STARTED (errorCode=1) — the call is rejected
        // outright, it burns radio, and nothing above ever looked at the result.
        // `bleScanning` is the honest state introduced by the mesh pass; this is
        // what it is for.
        if (_bleScanning.value) {
            Log.d(TAG, "🔍 BLE scan already running — not re-arming")
            return
        }

        val scanner = bleScanner ?: return

        // Use broad scan (null filters) with manual triple-check in callback:
        // 1. Parsed service UUIDs (reliable for 16-bit UUIDs from iOS)
        // 2. Device name starts with "OSHI"
        // 3. Raw byte parsing of scan record
        // Broad scan is more reliable than ScanFilter for cross-platform discovery.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // __BLE_OFF_RACE_2026_11_10__ Play vitals, 7 users on vc117–127:
        // IllegalStateException "BT Adapter is not turned ON" thrown straight out of
        // startScan when Bluetooth is switched off between our checks and the call
        // (or the periodic restart fires while it is off). Uncaught, it killed the
        // whole app. Bluetooth being off is a state, not an error: skip and let the
        // periodic restart try again.
        if (bluetoothAdapter?.isEnabled != true) {
            Log.d(TAG, "🔍 Bluetooth is off — not scanning")
            return
        }
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startScan refused: ${e.message}")
            return
        } catch (e: SecurityException) {
            MeshDiag.fail("scan refused: ${e.message}")
            return
        }
        _bleScanning.value = true
        Log.d(TAG, "🔍 BLE scanning started (broad scan with triple-check: UUID/name/raw)")
    }

    private fun stopBLEScanning() {
        // Guard the same way round: `stopScan` on a callback that is not
        // registered logs a framework warning and, on some stacks, throws.
        if (!_bleScanning.value) return
        try {
            bleScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan: ${e.javaClass.simpleName}: ${e.message}")
        }
        _bleScanning.value = false
    }

    /**
     * Bring BLE up, from whatever state it is in. Idempotent, safe to call on a
     * timer, and the ONLY thing that recovers a mesh that started deaf and mute.
     *
     * Three things could leave BLE dead after [start] and, before this existed,
     * only one of them ever recovered:
     *
     *  1. **Permissions granted late.** `start` fires
     *     [startBLEAdvertising]/[startBLEScanning], both bail at their permission
     *     guard, and [scheduleBLERetry] watches for the grant — but for 30
     *     seconds only (10 × 3 s) and then never again. Since the only place the
     *     app ever asks for BLUETOOTH_* is `MeshPermissionGate` on the Peers
     *     route (MainActivity.kt:2491), reached from Settings → Nearby
     *     (MainActivity.kt:1187), the grant essentially always lands long after
     *     that window has closed.
     *  2. **Bluetooth switched on after launch.** `bleScanner` / `bleAdvertiser`
     *     are captured once, at CrossPlatformMesh.kt:157-159. With the radio off
     *     at launch both are null forever: `startBLEScanning`'s `?: return` and
     *     `startBLEAdvertising`'s `?: return` fire on every subsequent attempt.
     *     Only `scheduleBLERetry` re-fetched the adapter, and only inside its
     *     30 s window. There is no `ACTION_STATE_CHANGED` receiver here (the LoRa
     *     lane has one, MeshtasticManager.kt:349 — this lane never did).
     *  3. **The asymmetry that actually bit.** The old periodic ticker restarted
     *     SCANNING and nothing else. So a late grant could leave Android able to
     *     see an iPhone while the iPhone could never see Android — because
     *     advertising never restarted, and because [startGattServer] is only
     *     ever called from inside [startBLEAdvertising], so characteristic 0542
     *     — the thing iOS reads to learn our public key and our IP/port — was
     *     never being served at all.
     *
     * Recovering scan without advertise is worse than recovering neither: the
     * Peers screen fills with iPhones that will never answer.
     */
    private fun ensureBleRunning() {
        // Recompute the honest permission state on every pass. The 30 s ticker
        // crosses here whether or not anything else happens, so
        // `blePermissionsMissing` can never be stale for more than one tick —
        // and `retryBleIfPermitted()` makes answering the dialog immediate.
        refreshBlePermissionState()

        if (!canAdvertise() && !canScan()) {
            _bleAdvertising.value = false
            _bleScanning.value = false
            return
        }

        // Re-fetch: these are null when Bluetooth was off at start() and stay
        // null for the process lifetime otherwise.
        if (bleScanner == null || bleAdvertiser == null) {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager.adapter
            bleScanner = bluetoothAdapter?.bluetoothLeScanner
            bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        }
        _bluetoothEnabled.value = bluetoothAdapter?.isEnabled == true

        if (!_bleAdvertising.value && canAdvertise()) {
            Log.d(TAG, "🔄 BLE advertising is down — starting it")
            startBLEAdvertising()
        }
        if (!_bleScanning.value && canScan()) {
            Log.d(TAG, "🔄 BLE scanning is down — starting it")
            startBLEScanning()
        }
    }

    /**
     * Called when something outside knows a permission grant may just have
     * landed (the Peers screen coming to the foreground). Cheap and idempotent
     * — it is [ensureBleRunning] without waiting up to 30 s for the next tick.
     */
    fun retryBleIfPermitted() {
        // Refresh the permission snapshot even when the mesh is not running, so
        // the Peers screen can say "permission needed" rather than "Offline"
        // before anything has been started.
        refreshBlePermissionState()
        if (!_isRunning.value) return
        ensureBleRunning()
    }

    /**
     * The 30 s ticker.
     *
     * It has exactly one job on the scanner per tick — that is the fix. It used
     * to call [ensureBleRunning] (which starts the scan when it is down) and
     * then unconditionally cycle `stop / delay(500) / start`, so a tick could
     * issue two `startScan` calls half a second apart. Combined with the two
     * tickers that used to run at once (see [MeshRunGeneration]) that is the
     * three "🔍 BLE scanning started" lines and the
     * `❌ BLE scan failed: errorCode=1` in the Galaxy A17 capture.
     *
     * [BleRadioPolicy.scanTick] now decides, from the honest `bleScanning`
     * state, whether this tick is a bring-up or a refresh. It is never both.
     */
    private fun schedulePeriodicScanRestart(generation: Int) = trackPeriodic(
        scope.launch {
            while (isActive && stillCurrent(generation)) {
                delay(30_000)
                if (!stillCurrent(generation)) break

                // Advertising is recovered on every tick regardless: recovering
                // the scan alone fills the Peers screen with iPhones that can
                // never see us back.
                val wasScanning = _bleScanning.value
                ensureBleRunning()

                when (BleRadioPolicy.scanTick(permitted = canScan(), scanning = wasScanning)) {
                    BleRadioPolicy.ScanTick.SKIP_NO_PERMISSION -> {
                        // ensureBleRunning already put the flags straight.
                    }
                    BleRadioPolicy.ScanTick.BRING_UP -> {
                        // ensureBleRunning started it. Starting it again here is
                        // precisely the rejected duplicate we are fixing.
                    }
                    BleRadioPolicy.ScanTick.REFRESH -> {
                        Log.d(TAG, "🔄 Periodic BLE scan restart (connected: ${_connectedPeers.value.size})")
                        try {
                            stopBLEScanning()
                            delay(500)
                            if (stillCurrent(generation)) startBLEScanning()
                        } catch (e: Exception) {
                            Log.w(TAG, "Scan restart error: ${e.message}")
                        }
                    }
                }
            }
        }
    )

    /**
     * Retry BLE start after permissions are granted.
     * MainActivity requests BLE permissions asynchronously — this waits for them.
     */
    private fun scheduleBLERetry(generation: Int) {
        trackPeriodic(scope.launch {
            for (attempt in 1..10) {
                delay(3000)
                if (!stillCurrent(generation)) return@launch
                if (hasBluetoothPermissions()) {
                    Log.d(TAG, "✅ BLE permissions now granted! Starting BLE (attempt $attempt)")
                    ensureBleRunning()
                    return@launch
                }
                Log.d(TAG, "⏳ Waiting for BLE permissions... (attempt $attempt/10)")
            }
            // NOT "disabled" any more — the 30 s window is just the fast path.
            // `schedulePeriodicScanRestart` keeps calling `ensureBleRunning` every
            // 30 s for as long as the mesh runs, and `retryBleIfPermitted` gives
            // the Peers screen a way to force it the moment a dialog is answered.
            Log.w(TAG, "⚠️ BLE permissions still not granted after 30s — will keep retrying every 30s")
        })
    }

    /**
     * 🔧 P0 FIX (device walkthrough #2, 2026-08-13).
     *
     * This string is a raw engineering telemetry line
     * (`v2 BT:ON Perm:✓ IP:10.0.2.15 TCP:34601 BLE[0/0] NSD[-D:0] Peers:0/0`) and it
     * was rendered **verbatim in the Nearby Peers status card of a release build**,
     * putting the device's local IP address on screen in a privacy product
     * (`PeersViewModel.statusSubtitle`).
     *
     * It is a genuine developer aid for BLE/NSD bring-up, so it is kept — but:
     *   1. it is no longer rendered anywhere in the UI (see `PeersViewModel`), and
     *   2. it is only ever computed in `BuildConfig.DEBUG`, and goes to logcat.
     *
     * Gating at the source (not just at the one UI call site) means the local IP
     * cannot reach a release `StateFlow` at all, so a future consumer cannot
     * re-leak it by accident. In release the flow stays empty.
     */
    private fun scheduleDiagnosticsUpdate(generation: Int) {
        if (!com.oshi.messenger.BuildConfig.DEBUG) return
        trackPeriodic(scope.launch {
            while (isActive && stillCurrent(generation)) {
                val blePerm = hasBluetoothPermissions()
                val btOn = bluetoothAdapter?.isEnabled == true
                val scannerOk = bleScanner != null
                val advertiserOk = bleAdvertiser != null
                val ip = getLocalIpAddress() ?: "none"
                val port = serverPort
                val bleDevices = discoveredDevices.size
                val discovered = _crossPlatformPeers.value.size
                val connected = _connectedPeers.value.size
                val tcpConns = tcpConnections.size

                _diagnostics.value = buildString {
                    append("v2 ")
                    append("BT:${if (btOn) "ON" else "OFF"} ")
                    append("Perm:${if (blePerm) "✓" else "✗"} ")
                    append("IP:$ip TCP:$port ")
                    append("BLE[$totalScanResults/$bleDevices] ")
                    append("NSD[${if (nsdRegistered) "R" else "-"}${if (nsdDiscovering) "D" else "-"}:$nsdFoundCount] ")
                    append("Peers:$discovered/$connected")
                }
                // Developer aid only — logcat, never the screen.
                Log.d(TAG, "🔎 mesh diag: ${_diagnostics.value}")
                delay(5000)
            }
        })
    }

    /**
     * Periodically removes stale entries from the routing table.
     * Runs every 60 seconds, evicting routes whose lastSeen is older than 5 minutes.
     */
    private fun scheduleRouteCleanup(generation: Int) = trackPeriodic(
        scope.launch {
            while (isActive && stillCurrent(generation)) {
                delay(60_000)
                val cutoff = System.currentTimeMillis() - 300_000 // 5 minutes ago
                val staleKeys = routingTable.entries
                    .filter { it.value.lastSeen < cutoff }
                    .map { it.key }
                for (key in staleKeys) {
                    routingTable.remove(key)
                }
                if (staleKeys.isNotEmpty()) {
                    Log.d(TAG, "Route cleanup: removed ${staleKeys.size} stale route(s)")
                }
            }
        }
    )

    /**
     * Parse raw scan record bytes to check for our 16-bit service UUID (0x0541).
     * Fallback for devices where ScanRecord.getServiceUuids() doesn't work.
     * Handles both 16-bit (AD type 0x02/0x03) and 128-bit (AD type 0x06/0x07) formats.
     */
    private fun rawBytesContainOshiUuid(scanRecord: ScanRecord?): Boolean {
        val bytes = scanRecord?.bytes ?: return false
        var i = 0
        while (i < bytes.size - 1) {
            val length = bytes[i].toInt() and 0xFF
            if (length == 0) break
            if (i + length >= bytes.size) break
            val adType = bytes[i + 1].toInt() and 0xFF

            // 16-bit UUID lists (AD type 0x02 = incomplete, 0x03 = complete)
            if (adType == 0x02 || adType == 0x03) {
                var j = i + 2
                while (j + 1 <= i + 1 + length) {
                    val uuid16 = (bytes[j].toInt() and 0xFF) or ((bytes[j + 1].toInt() and 0xFF) shl 8)
                    if (uuid16 == 0x0541) return true
                    j += 2
                }
            }

            // 128-bit UUID lists (AD type 0x06 = incomplete, 0x07 = complete)
            // UUIDs stored in little-endian: last 4 bytes of 16-byte UUID contain our 0x00000541
            if (adType == 0x06 || adType == 0x07) {
                var j = i + 2
                while (j + 15 <= i + 1 + length) {
                    if (j + 15 < bytes.size &&
                        bytes[j + 12].toInt() and 0xFF == 0x41 &&
                        bytes[j + 13].toInt() and 0xFF == 0x05 &&
                        bytes[j + 14].toInt() and 0xFF == 0x00 &&
                        bytes[j + 15].toInt() and 0xFF == 0x00) {
                        return true
                    }
                    j += 16
                }
            }

            i += 1 + length
        }
        return false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            totalScanResults++
            val device = result.device
            val name = try { device.name } catch (_: SecurityException) { null }
                ?: result.scanRecord?.deviceName

            // Check if this is an OSHI device using FOUR methods:
            // 1. Parsed service UUIDs (reliable for 16-bit UUIDs from iOS)
            // 2. Name starts with "OSHI" (iOS advertises short name "OSHI")
            // 3. Manufacturer data contains "OSHI" bytes (Android↔Android)
            // 4. Raw byte parsing (fallback for edge cases)
            val hasServiceUuid = result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
            val hasOshiName = name?.startsWith("OSHI") == true
            val hasOshiMfg = result.scanRecord?.getManufacturerSpecificData(0xFFFF)?.let {
                String(it, Charsets.UTF_8).startsWith("OSHI")
            } == true
            val hasRawUuid = if (!hasServiceUuid && !hasOshiName && !hasOshiMfg) rawBytesContainOshiUuid(result.scanRecord) else false

            if (!hasServiceUuid && !hasOshiName && !hasOshiMfg && !hasRawUuid) return

            val displayName = name ?: "Unknown"
            Log.d(TAG, "🔍 BLE discovered OSHI device: $displayName (RSSI: ${result.rssi}, addr: ${device.address}, uuid:$hasServiceUuid, name:$hasOshiName, mfg:$hasOshiMfg, raw:$hasRawUuid)")

            // Avoid duplicate connections
            if (discoveredDevices.containsKey(device.address)) return

            // Store device
            discoveredDevices[device.address] = device

            // Connect to read characteristics (TRANSPORT_LE required for iOS BLE peripherals)
            // 🔧 FIX: Wrap connectGatt in try/catch — can throw DeadObjectException when
            // Bluetooth stack restarts, IllegalArgumentException for unsupported devices,
            // or SecurityException if BLUETOOTH_CONNECT permission gets revoked at runtime.
            try {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } catch (e: Exception) {
                Log.w(TAG, "connectGatt failed for ${device.address}: ${e.javaClass.simpleName}: ${e.message}")
                // Drop from discovered so we can retry next scan cycle
                discoveredDevices.remove(device.address)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val fault = BleRadioPolicy.scanFault(errorCode)
            Log.e(TAG, "❌ BLE scan failed: errorCode=$errorCode ($fault)")

            // The state flag must match reality, in BOTH directions.
            //
            // Before: the flag was left saying `true` after any rejection, so
            // `ensureBleRunning` believed a scan was up and never brought it
            // back — a genuine failure was one unread `Log.e` and then silence,
            // indistinguishable from a healthy scan that had found nobody. That
            // is the same shape as the permission denial this lane was fixed for.
            //
            // The one code that must NOT clear it is SCAN_FAILED_ALREADY_STARTED
            // (errorCode=1, the Galaxy A17 capture): the scan really is running,
            // from the call before ours. Clearing it would make the next tick
            // re-arm and be rejected again, forever.
            _bleScanning.value = fault.live
            if (!fault.live) _bleFault.value = fault

            if (fault.retryable) {
                scope.launch {
                    // THROTTLED means Android has refused us for the rest of a
                    // 30 s window (5 starts / 30 s). Waiting it out beats
                    // hammering the quota; anything else gets the short retry.
                    delay(if (fault == BleRadioPolicy.RadioFault.THROTTLED) 31_000 else 3_000)
                    if (_isRunning.value) startBLEScanning()
                }
            }
        }
    }

    /**
     * 🔧 FIX: Helper to safely tear down a GATT instance. Calls to `disconnect()` /
     * `close()` can throw `DeadObjectException` or `IllegalStateException` when the
     * underlying binder has died (Android Bluetooth stack restart) — without this
     * helper those would crash the BLE callback thread.
     */
    private fun safeGattDisconnect(gatt: BluetoothGatt) {
        // __A_SPLICED_READ_IS_RECOVERABLE_2026_10_23__ Every path out of a GATT
        // session comes through here, so this is the one place that cannot leak
        // a retry count for a device we are no longer talking to.
        try { announceReadRetries.remove(gatt.device?.address) } catch (_: Exception) {}
        try { gatt.disconnect() } catch (e: Exception) {
            Log.w(TAG, "safeGattDisconnect: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun safeGattClose(gatt: BluetoothGatt) {
        try { gatt.close() } catch (e: Exception) {
            Log.w(TAG, "safeGattClose: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceName = gatt.device.name ?: gatt.device.address
            Log.d(TAG, "🔗 GATT client state: device=$deviceName status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "✅ BLE GATT connected: $deviceName")
                try { gatt.discoverServices() } catch (e: Exception) {
                    Log.w(TAG, "discoverServices failed: ${e.javaClass.simpleName}: ${e.message}")
                    safeGattClose(gatt)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "🔌 BLE GATT disconnected: $deviceName (status=$status)")
                safeGattClose(gatt)
            } else {
                Log.w(TAG, "⚠️ BLE GATT unexpected: device=$deviceName status=$status state=$newState")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "❌ GATT service discovery failed: status=$status")
                safeGattDisconnect(gatt)
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                // iOS devices: OSHI service may not be visible via GATT due to iOS BLE limitations.
                // This is OK — iOS discovers Android's GATT and initiates the TCP connection.
                // The bidirectional TCP link handles all messaging.
                Log.d(TAG, "ℹ️ OSHI service not found on ${gatt.device.name ?: gatt.device.address} (iOS BLE limitation — TCP via iOS-initiated connection)")
                // Keep in discoveredDevices to avoid retry loop
                safeGattDisconnect(gatt)
                return
            }
            val char = service.getCharacteristic(CHAR_UUID)
            if (char == null) {
                Log.w(TAG, "⚠️ OSHI characteristic not found")
                safeGattDisconnect(gatt)
                return
            }
            Log.d(TAG, "📖 Reading BLE characteristic from ${gatt.device.name ?: gatt.device.address}")
            try { gatt.readCharacteristic(char) } catch (e: Exception) {
                Log.w(TAG, "readCharacteristic failed: ${e.javaClass.simpleName}: ${e.message}")
                safeGattDisconnect(gatt)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "❌ GATT read failed: status=$status")
                safeGattDisconnect(gatt)
                return
            }
            if (characteristic.uuid == CHAR_UUID && characteristic.value != null) {
                try {
                    val json = JSONObject(String(characteristic.value))
                    val pk = json.optString("pk", "")
                    val name = json.optString("name", "Unknown")
                    val port = json.optInt("port", 0)
                    val platform = json.optString("platform", "unknown")
                    val announcedIp = json.optString("ip", "")

                    Log.d(TAG, "📋 BLE peer info: $name ($platform) ip=$announcedIp port=$port pk=${pk.take(16)}...")

                    // __A_SIGHTING_WITH_AN_ADDRESS_WAS_THROWN_AWAY_2026_11_09__ An
                    // iPhone hosting the hotspot we are on announces "" on every
                    // pre-10-22 build; it is our gateway. Only when it is the one
                    // addressless iPhone we know of, so a second iPhone on cellular
                    // nearby is never dialled at the host's address.
                    val ip = announcedIp.ifEmpty {
                        val host = if (platform == "ios" && port > 0) iosHotspotHostFor(getLocalIpAddress()) else null
                        val otherAddresslessIos = _crossPlatformPeers.value.any {
                            it.platform == "ios" && it.ipAddress == null && !sameMeshIdentity(it.publicKey, pk)
                        }
                        if (host != null && !otherAddresslessIos) {
                            Log.d(TAG, "📋 $name announced no ip and we are on an iPhone hotspot — dialling the host $host")
                            host
                        } else ""
                    }

                    if (pk.isNotEmpty()) {
                        val peer = CrossPlatformPeer(
                            publicKey = pk,
                            displayName = name,
                            platform = platform,
                            ipAddress = ip.ifEmpty { null },
                            port = if (port > 0) port else null
                        )
                        // Always in the discovered list (visible in UI), and a
                        // later announce that carries an address must update it.
                        _crossPlatformPeers.value = mergeSighting(_crossPlatformPeers.value, peer)
                        // Try TCP connection if we have IP and port
                        if (ip.isNotEmpty() && port > 0) {
                            Log.d(TAG, "🔗 BLE provided IP+port, connecting via TCP to $name at $ip:$port")
                            connectToPeer(peer)
                        } else {
                            Log.w(TAG, "⚠️ BLE peer $name has no IP/port — will rely on NSD for TCP connection")
                        }
                    }
                    announceReadRetries.remove(gatt.device?.address)
                } catch (e: Exception) {
                    // __A_SPLICED_READ_IS_RECOVERABLE_2026_10_23__
                    //
                    // MEASURED on a Galaxy A17, 2026-10-23, against a peer on an
                    // older build:
                    //
                    //   Parse BLE data error: Expected ':' after platform at
                    //   character 45 of {"port":63688,"ip":"192.168.1.18",
                    //   "platform"DJtkC24fKa3c1n","port"3688,…}
                    //
                    // That is not corrupt data, it is TWO documents spliced
                    // together. The announce is ~115 bytes and the default ATT
                    // MTU carries 22, so it arrives as a long read — offset 0,
                    // then 22, then 44 — and a peer that recomputes the JSON per
                    // request (which is what every build before
                    // __ONE_READ_MUST_SEE_ONE_DOCUMENT_2026_10_23__ does) answers
                    // the continuations from a DIFFERENT document.
                    //
                    // The old code logged this and called `safeGattDisconnect`.
                    // The peer was dropped on the spot — while its TCP endpoint
                    // was alive and accepting: `nc 192.168.1.18 63688` succeeded
                    // at that very moment. A whole mesh link lost to a condition
                    // that announces itself with a parse error and that one more
                    // read very often fixes, because the splice needs the peer's
                    // ip/port to change BETWEEN two reads.
                    //
                    // So: read it again. Bounded, because a peer whose announce
                    // is genuinely malformed must not be re-read forever.
                    //
                    // NOT salvaged from the broken text, deliberately. The
                    // fragments here happened to carry a usable ip and port, but
                    // a splice is by definition two snapshots: trusting half of
                    // one and half of the other is how you dial a port that
                    // closed a second ago.
                    val address = gatt.device?.address
                    val tries = (announceReadRetries[address] ?: 0) + 1
                    if (address != null && tries <= ANNOUNCE_READ_MAX_RETRIES) {
                        announceReadRetries[address] = tries
                        Log.w(TAG, "announce did not parse (${e.message}) — re-reading, attempt $tries of $ANNOUNCE_READ_MAX_RETRIES")
                        val again = try { gatt.readCharacteristic(characteristic) } catch (se: SecurityException) {
                            Log.w(TAG, "re-read refused: ${se.message}"); false
                        }
                        if (again) return   // keep the connection: the result lands back here
                    } else {
                        Log.e(TAG, "announce still unreadable after $ANNOUNCE_READ_MAX_RETRIES attempts: ${e.message}")
                        announceReadRetries.remove(address)
                    }
                }
            }
            safeGattDisconnect(gatt)
        }
    }

    // ============================================================================
    // NSD (BONJOUR/MDNS)
    // ============================================================================

    private fun initNsdManager() {
        if (nsdManager == null) {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            Log.d(TAG, "📡 NSD manager initialized")
        }
    }

    private fun startNSDAdvertising() {
        // Wait for TCP server
        if (serverPort == 0) {
            scope.launch {
                delay(1000)
                startNSDAdvertising()
            }
            return
        }

        initNsdManager()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$SERVICE_NAME_PREFIX${myPublicKey.take(8)}"
            serviceType = SERVICE_TYPE
            port = serverPort
            setAttribute("pk", canonicalWireKey(myPublicKey))
            setAttribute("name", myDisplayName)
            setAttribute("platform", "android")
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                nsdRegistered = true
                Log.d(TAG, "📡 NSD registered: ${info.serviceName} on port $serverPort")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                nsdRegistered = false
                Log.e(TAG, "NSD registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "NSD unregistered: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD unregistration failed: $errorCode")
            }
        }

        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun stopNSDAdvertising() {
        registrationListener?.let { nsdManager?.unregisterService(it) }
        registrationListener = null
    }

    private fun startNSDDiscovery() {
        initNsdManager()

        if (nsdManager == null) {
            Log.e(TAG, "❌ Cannot start NSD discovery: nsdManager is null")
            return
        }

        Log.d(TAG, "🔍 Starting NSD discovery for: $SERVICE_TYPE")

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                nsdDiscovering = true
                Log.d(TAG, "🔍 NSD discovery started for: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                nsdFoundCount++
                Log.d(TAG, "🔍 NSD found service: ${service.serviceName} (type: ${service.serviceType})")

                // Don't discover ourselves
                if (service.serviceName.contains(myPublicKey.take(8))) {
                    Log.d(TAG, "   (skipping - it's us)")
                    return
                }

                Log.d(TAG, "🔍 NSD found: ${service.serviceName}")
                nsdManager?.resolveService(service, createResolveListener())
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "📴 NSD lost: ${service.serviceName}")

                // Remove from peers
                val currentPeers = _crossPlatformPeers.value.toMutableList()
                currentPeers.removeAll { it.displayName == service.serviceName }
                _crossPlatformPeers.value = currentPeers
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "NSD discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD discovery start failed: $errorCode — retrying in 3s")
                scope.launch {
                    delay(3000)
                    if (_isRunning.value) {
                        startNSDDiscovery()
                    }
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD discovery stop failed: $errorCode")
            }
        }

        nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun stopNSDDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager?.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.w(TAG, "Stop discovery error: ${e.message}")
            }
        }
        discoveryListener = null
        nsdDiscovering = false
    }

    /**
     * The address to dial for a resolved service — see [pickDialAddress].
     *
     * `NsdServiceInfo.getHostAddresses()` (API 34) returns EVERY address the
     * resolver found; `getHost()` returns one, chosen by the platform, and on a
     * dual-stack iPhone that is often the IPv6 link-local. Below API 34 there
     * is only the one, so the reconnect sweep is what covers us there.
     */
    private fun dialHostFor(info: NsdServiceInfo): String? {
        val candidates: List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                info.hostAddresses.mapNotNull { it.hostAddress }
            } else {
                listOfNotNull(info.host?.hostAddress)
            }
        val picked = pickDialAddress(candidates)
        if (picked == null && candidates.isNotEmpty()) {
            Log.w(TAG, "NSD resolved ${info.serviceName} to ${candidates.size} address(es), none dialable")
        } else if (picked != null && candidates.size > 1) {
            Log.d(TAG, "NSD resolved ${info.serviceName} to $candidates — dialling $picked")
        }
        return picked
    }

    private fun createResolveListener(attempt: Int = 0): NsdManager.ResolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "NSD resolve failed: $errorCode (${serviceInfo.serviceName}, attempt ${attempt + 1})")
            // __A_SIGHTING_WITH_AN_ADDRESS_WAS_THROWN_AWAY_2026_11_09__ Below API 34
            // NsdManager resolves ONE service at a time and refuses the next with
            // FAILURE_ALREADY_ACTIVE. `onServiceFound` fires once per browse, so a
            // peer found while another resolve ran was never resolved — its only
            // address source gone until someone restarted discovery. Retry it.
            if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attempt < 5) {
                scope.launch {
                    delay(750L * (attempt + 1))
                    if (!_isRunning.value) return@launch
                    try {
                        nsdManager?.resolveService(serviceInfo, createResolveListener(attempt + 1))
                    } catch (e: Exception) {
                        Log.w(TAG, "NSD resolve retry: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = dialHostFor(serviceInfo) ?: return
            val port = serviceInfo.port

            // Get attributes
            val pk = serviceInfo.attributes["pk"]?.let { String(it) } ?: ""
            val name = serviceInfo.attributes["name"]?.let { String(it) } ?: serviceInfo.serviceName
            val platform = serviceInfo.attributes["platform"]?.let { String(it) } ?: "unknown"

            Log.d(TAG, "✅ NSD resolved: $name ($platform) at $host:$port")

            val peer = CrossPlatformPeer(
                publicKey = pk,
                displayName = name,
                platform = platform,
                ipAddress = host,
                port = port
            )

            // Add to discovered — or give the row BLE created its address
            // (__A_SIGHTING_WITH_AN_ADDRESS_WAS_THROWN_AWAY_2026_11_09__).
            _crossPlatformPeers.value = mergeSighting(_crossPlatformPeers.value, peer)

            // Connect via TCP
            connectToPeer(peer)
        }
    }

    // ============================================================================
    // PERMISSIONS
    // ============================================================================

    /**
     * The BLE permissions granted right now, as [MeshPermissionPolicy] names
     * them. Both lane checks below go through this so the set the app REQUESTS,
     * the set it REPORTS MISSING and the set it GATES ON cannot drift apart.
     */
    private fun grantedBlePermissions(): Set<String> =
        (MeshPermissionPolicy.permissionsFor(MeshPermissionPolicy.Lane.BLE_ADVERTISE, Build.VERSION.SDK_INT) +
            MeshPermissionPolicy.permissionsFor(MeshPermissionPolicy.Lane.BLE_SCAN, Build.VERSION.SDK_INT))
            .distinct()
            .filter { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            .toSet()

    /**
     * Advertise and scan are gated **separately**.
     *
     * The single conjunctive `hasBluetoothPermissions()` — all three of SCAN,
     * ADVERTISE and CONNECT or nothing — made [MeshPermissionPolicy.Reach.MUTE]
     * and [MeshPermissionPolicy.Reach.DEAF] unreachable in practice: a phone
     * holding two of the three did neither, and the policy's carefully separated
     * lanes described a state the code could not produce. Per-lane gating is
     * what makes "we can see the iPhone, it can never see us" a state the app
     * can be *in* and therefore report.
     */
    private fun canAdvertise(): Boolean = MeshPermissionPolicy.canRun(
        MeshPermissionPolicy.Lane.BLE_ADVERTISE, Build.VERSION.SDK_INT, grantedBlePermissions()
    )

    private fun canScan(): Boolean = MeshPermissionPolicy.canRun(
        MeshPermissionPolicy.Lane.BLE_SCAN, Build.VERSION.SDK_INT, grantedBlePermissions()
    )

    /** True when either BLE lane can run. Used for retry scheduling, not gating. */
    private fun hasBluetoothPermissions(): Boolean = canAdvertise() || canScan()

    /** Recompute [blePermissionsMissing] from what is granted right now. */
    private fun refreshBlePermissionState() {
        val granted = grantedBlePermissions()
        val needed = (MeshPermissionPolicy.permissionsFor(MeshPermissionPolicy.Lane.BLE_ADVERTISE, Build.VERSION.SDK_INT) +
            MeshPermissionPolicy.permissionsFor(MeshPermissionPolicy.Lane.BLE_SCAN, Build.VERSION.SDK_INT))
            .distinct()
        _blePermissionsMissing.value = needed.filterNot { it in granted }
    }

    // ============================================================================
    // BLUETOOTH RADIO STATE
    // ============================================================================

    private var bluetoothStateReceiver: android.content.BroadcastReceiver? = null

    /**
     * Recover the moment the user switches Bluetooth on.
     *
     * `bleScanner` / `bleAdvertiser` are captured once in [start]; with the radio
     * off at launch both are null and every subsequent `?: return` fires.
     * [ensureBleRunning] re-fetches them, but only on the 30 s tick, so the user
     * who turns Bluetooth on while staring at the Peers screen waits up to half
     * a minute for anything to happen and reasonably concludes it is broken.
     * The LoRa lane has had this receiver since `MeshtasticManager.kt:349`; this
     * one never did.
     */
    private fun registerBluetoothStateReceiver() {
        if (bluetoothStateReceiver != null) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.d(TAG, "📶 Bluetooth switched on — bringing BLE back up")
                        _bluetoothEnabled.value = true
                        _bleFault.value = BleRadioPolicy.RadioFault.NONE
                        if (_isRunning.value) ensureBleRunning()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        // The stack drops our registrations without telling the
                        // callbacks. Say so, rather than claiming a scan that no
                        // longer exists.
                        Log.d(TAG, "📴 Bluetooth switched off — BLE is down")
                        _bluetoothEnabled.value = false
                        _bleAdvertising.value = false
                        _bleScanning.value = false
                        bleScanner = null
                        bleAdvertiser = null
                    }
                }
            }
        }
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            bluetoothStateReceiver = receiver
        } catch (e: Exception) {
            Log.w(TAG, "Cannot register Bluetooth state receiver: ${e.message}")
        }
    }

    private fun unregisterBluetoothStateReceiver() {
        bluetoothStateReceiver?.let {
            try { context.unregisterReceiver(it) } catch (e: Exception) {
                Log.w(TAG, "unregisterReceiver: ${e.message}")
            }
        }
        bluetoothStateReceiver = null
    }

    private var classicBluetoothReceiver: android.content.BroadcastReceiver? = null

    /**
     * (N23) "Lors d'une connexion à une voiture en bluetooth elle affiche
     * toujours Nom-Oshi et pas le nom défini par l'utilisateur."
     *
     * The BLE advertising this mesh needs stays up the whole time the app
     * runs (see MeshNetworkService), so "OSHI-xxx" was permanent, not just
     * while actively pairing — the car reads the adapter's name over
     * CLASSIC Bluetooth (A2DP/HFP), a completely different radio mode from
     * the BLE advertising that needed the rename in the first place, and it
     * reads it at connect time, while the rename was still in effect.
     *
     * Scoped deliberately to the car-audio device class rather than any
     * classic connection (a headset, say): that is exactly what was
     * reported, and it keeps the blast radius of restoring the real name —
     * however briefly — as small as the complaint that asked for it.
     */
    private fun registerClassicBluetoothReceiver() {
        if (classicBluetoothReceiver != null) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
                val device = intent?.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val isCarAudio = try {
                    device?.bluetoothClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
                } catch (e: SecurityException) {
                    false
                }
                if (!isCarAudio) return
                when (intent?.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        Log.d(TAG, "🚗 Car audio connected — showing the real Bluetooth name")
                        restoreAdapterName()
                    }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        if (_bleAdvertising.value) {
                            Log.d(TAG, "🚗 Car audio disconnected — back to \"OSHI-xxx\" for mesh discovery")
                            applyOshiAdapterName()
                        }
                    }
                }
            }
        }
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                android.content.IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            classicBluetoothReceiver = receiver
        } catch (e: Exception) {
            Log.w(TAG, "Cannot register classic Bluetooth receiver: ${e.message}")
        }
    }

    private fun unregisterClassicBluetoothReceiver() {
        classicBluetoothReceiver?.let {
            try { context.unregisterReceiver(it) } catch (e: Exception) {
                Log.w(TAG, "unregisterReceiver: ${e.message}")
            }
        }
        classicBluetoothReceiver = null
    }

    // ============================================================================
    // CLEANUP
    // ============================================================================

    fun cleanup() {
        stop()
        scope.cancel()
    }
}

// ============================================================================
// DATA CLASSES
// ============================================================================

/**
 * CrossPlatformPeer - A peer discovered via cross-platform mesh
 */
data class CrossPlatformPeer(
    val publicKey: String,
    val displayName: String,
    val platform: String,  // "ios" or "android"
    val ipAddress: String?,
    val port: Int?
)

/**
 * CrossPlatformMessage - Message format compatible with iOS
 */
data class CrossPlatformMessage(
    val id: String,
    val type: String,
    val senderPublicKey: String,
    val senderName: String,
    val recipientPublicKey: String,
    val payload: String,
    val timestamp: Double,
    val hopCount: Int,
    val maxHops: Int,
    val seenBy: MutableList<String>,
    val platform: String
) {
    fun toJSON(): ByteArray {
        val json = JSONObject().apply {
            put("id", id)
            put("type", type)
            // __MESH_WIRE_SPELLING_FOR_OLD_IOS_2026_10_26__ Canonicalise HERE, the
            // single point every outgoing frame passes through (sends, relays,
            // identity announces alike), so no new call site can reintroduce a
            // base64url key the shipping iPhone will not recognise.
            put("senderPublicKey", CrossPlatformMesh.canonicalWireKey(senderPublicKey))
            put("senderName", senderName)
            put("recipientPublicKey", CrossPlatformMesh.canonicalWireKey(recipientPublicKey))
            put("payload", payload)
            put("timestamp", timestamp)
            put("hopCount", hopCount)
            put("maxHops", maxHops)
            put("seenBy", org.json.JSONArray(seenBy.map { CrossPlatformMesh.canonicalWireKey(it) }))
            put("platform", platform)
        }
        return json.toString().toByteArray()
    }

    companion object {
        fun fromJSON(data: ByteArray): CrossPlatformMessage? {
            return try {
                val json = JSONObject(String(data))
                val seenByArray = json.optJSONArray("seenBy")
                val seenBy = mutableListOf<String>()
                if (seenByArray != null) {
                    for (i in 0 until seenByArray.length()) {
                        seenBy.add(seenByArray.getString(i))
                    }
                }

                CrossPlatformMessage(
                    id = json.getString("id"),
                    type = json.getString("type"),
                    senderPublicKey = json.getString("senderPublicKey"),
                    senderName = json.getString("senderName"),
                    recipientPublicKey = json.optString("recipientPublicKey", ""),
                    payload = json.getString("payload"),
                    timestamp = json.getDouble("timestamp"),
                    hopCount = json.getInt("hopCount"),
                    maxHops = json.getInt("maxHops"),
                    seenBy = seenBy,
                    platform = json.getString("platform")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Route information for relay
 */
data class RouteInfo(
    val nextHop: String,
    val hopCount: Int,
    val lastSeen: Long
)
