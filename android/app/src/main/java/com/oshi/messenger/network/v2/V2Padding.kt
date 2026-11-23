package com.oshi.messenger.network.v2

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * __V2_PADDING_2026_11_09__ Bucketed plaintext padding on the INTERNET RELAY path only.
 *
 * Pure JVM (org.json only) and COMPILED into OSHI-Desktop too (`build.gradle.kts` include
 * list), so the phone and the desktop run one implementation. iOS mirrors it in
 * `OSHI/V2MessageRouter.swift` (`V2Padding`, `V2PaddingPeers`); the three are pinned by the
 * same test vector (`V2PaddingTest`, `V2PaddingDesktopTest`, `V2PaddingTests.swift`).
 *
 * WHAT IT HIDES. A relay envelope's ciphertext was plaintext + 16 B of GCM tag: the relay
 * could tell a delivery receipt (~60 B) from a sentence and read every message's exact
 * length. Padding to a few buckets leaves only a coarse size class.
 *
 * WHERE. On the plaintext, INSIDE the ratchet AEAD (confidential, integrity-protected), at
 * the router's encrypt sites — which build relay envelopes and nothing else. The BLE/Wi-Fi
 * mesh and LoRa/MeshCore paths use the legacy `DoubleRatchet`/their own frames and never go
 * through [V2MessageRouter], so their byte budgets are untouched by construction.
 *
 * FORMAT (v1). `FF 50 01` ‖ plaintext ‖ `80` ‖ `00…` up to the bucket. 0xFF never occurs in
 * UTF-8 and every v2 plaintext any client ever sent is UTF-8 text or JSON, so the leading
 * byte IS the explicit flag; padding is never inferred from the tail of an unflagged message.
 *
 * COMPATIBILITY. Padded only toward a session whose counterpart announced [CAP_PADDING] (or
 * proved it by sending us a padded message); a group message only when every member
 * announces it. Receivers accept both forms forever.
 */
object V2Padding {
    /** Prekey-bundle `caps` bit "strips v2 plaintext padding" (iOS `RatchetHealSignal.Caps.padding`). */
    const val CAP_PADDING: Int = 1 shl 2

    /**
     * __MEDIA_PADDING_2026_11_10__ Prekey-bundle `caps` bit "reads padded v2 media"
     * (docs/MEDIA_PADDING_AND_OHTTP_MEDIA_SPEC.md §3, iOS `Caps.mediaPadding`). A blob is
     * padded ([OSHICryptoV2.encryptFile] `pad = true`) only when EVERY recipient device of
     * that upload advertises it; older readers assert `reassembled == size` and would reject.
     */
    const val CAP_MEDIA_PADDING: Int = 1 shl 3

    private val MAGIC = byteArrayOf(0xFF.toByte(), 0x50, 0x01)
    private const val TERMINATOR: Byte = 0x80.toByte()

    /**
     * 256 B swallows every control payload and the typical chat line; then ×4. The last
     * bucket's ciphertext (131 088 B → 174 784 B base64) stays under the relay's 256 KiB
     * `MAX_CIPHERTEXT_BYTES`; anything larger is sent unpadded, exactly as before.
     */
    val BUCKETS: List<Int> = listOf(256, 1024, 4096, 16384, 65536, 131072)

    fun paddedLength(plaintextLength: Int): Int? {
        val need = plaintextLength + MAGIC.size + 1
        return BUCKETS.firstOrNull { it >= need }
    }

    fun pad(plaintext: ByteArray): ByteArray {
        val total = paddedLength(plaintext.size) ?: return plaintext
        val out = ByteArray(total)                       // zero-filled tail
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.size)
        System.arraycopy(plaintext, 0, out, MAGIC.size, plaintext.size)
        out[MAGIC.size + plaintext.size] = TERMINATOR
        return out
    }

    sealed class Unpadded {
        /** No flag: an ordinary (old-format) plaintext, untouched. */
        class Plain(val bytes: ByteArray) : Unpadded()
        /** Flag present and well-formed. */
        class Padded(val bytes: ByteArray) : Unpadded()
        /** Flag present, tail broken: never shown, never "repaired". */
        object Malformed : Unpadded()
    }

    fun unpad(data: ByteArray): Unpadded {
        if (data.isEmpty() || data[0] != MAGIC[0]) return Unpadded.Plain(data)
        if (data.size < MAGIC.size + 1 || data[1] != MAGIC[1] || data[2] != MAGIC[2]) return Unpadded.Malformed
        var i = data.size - 1
        while (i >= MAGIC.size && data[i] == 0.toByte()) i--
        if (i < MAGIC.size || data[i] != TERMINATOR) return Unpadded.Malformed
        return Unpadded.Padded(data.copyOfRange(MAGIC.size, i))
    }

    /** The one sending decision: the caller's group rule AND this session's counterpart. */
    fun shouldPad(padGate: Boolean, slotCapable: Boolean): Boolean = padGate && slotCapable
}

/**
 * What each SESSION's counterpart (identity, or identity#device) is known to understand.
 * Process-wide, like `GroupLegacyCompat`'s caps map; persisted through [attach].
 *
 * Written: from a prekey bundle when it is fetched to build that slot's session (exact);
 * from a peer device list (upgrade only); from an inbound padded message (proof); and an
 * inbound X3DH-carrying message resets the slot to what that message proves, because the
 * session's counterpart may just have changed to another (older) install. Absent ⇒ no padding.
 */
object V2PaddingPeers {
    interface Store {
        fun read(): String?
        fun write(json: String)
    }

    private const val CAP = 2000
    private val slots = ConcurrentHashMap<String, Boolean>()

    /**
     * __MEDIA_PADDING_2026_11_10__ Same slots, bit [V2Padding.CAP_MEDIA_PADDING]. Persisted in
     * the SAME store under [MEDIA_PREFIX]-prefixed keys ('!' never occurs in base64, so an
     * older build that reloads the map just carries a few slots no identity ever matches).
     */
    private val mediaSlots = ConcurrentHashMap<String, Boolean>()
    private const val MEDIA_PREFIX = "m!"
    @Volatile private var store: Store? = null

    /** Idempotent; loads what [store] holds. */
    @Synchronized
    fun attach(s: Store) {
        if (store != null) return
        runCatching {
            val o = JSONObject(s.read() ?: "{}")
            o.keys().forEach { k ->
                if (k.startsWith(MEDIA_PREFIX)) mediaSlots.putIfAbsent(k.removePrefix(MEDIA_PREFIX), o.optBoolean(k, false))
                else slots.putIfAbsent(k, o.optBoolean(k, false))
            }
        }
        store = s
    }

    /** Same spelling rule as iOS `V2Device.normalizeIdentity` and Desktop `DeviceAuth`. */
    fun normalizeIdentity(s: String): String {
        val std = s.trim().replace('-', '+').replace('_', '/').trimEnd('=')
        return std + "=".repeat((4 - std.length % 4) % 4)
    }

    fun key(peer: String, device: String?): String {
        val id = normalizeIdentity(peer)
        return if (device.isNullOrEmpty()) id else "$id#$device"
    }

    @Synchronized
    private fun set(k: String, v: Boolean, upgradeOnly: Boolean, map: ConcurrentHashMap<String, Boolean> = slots) {
        if (upgradeOnly && !v) return
        if (map[k] == v) return
        map[k] = v
        while (map.size > CAP) {
            val victim = map.keys.firstOrNull { it != k } ?: break
            map.remove(victim)
        }
        persist()
    }

    private fun persist() {
        val st = store ?: return
        runCatching {
            val o = JSONObject(slots as Map<*, *>)
            for ((k, v) in mediaSlots) o.put(MEDIA_PREFIX + k, v)
            st.write(o.toString())
        }
    }

    fun noteBundle(peer: String, device: String?, caps: Int?) {
        set(key(peer, device), ((caps ?: 0) and V2Padding.CAP_PADDING) != 0, upgradeOnly = false)
        // __MEDIA_PADDING_2026_11_10__ the bundle is exact for this slot: both bits, both ways.
        set(key(peer, device), ((caps ?: 0) and V2Padding.CAP_MEDIA_PADDING) != 0, upgradeOnly = false, map = mediaSlots)
    }

    fun noteDeviceList(peer: String, device: String, caps: Int?) {
        if (caps == null) return
        set(key(peer, device), (caps and V2Padding.CAP_PADDING) != 0, upgradeOnly = true)
        set(key(peer, device), (caps and V2Padding.CAP_MEDIA_PADDING) != 0, upgradeOnly = true, map = mediaSlots)
    }

    /**
     * __ACCOUNT_CAPS_IN_DEVICE_LIST_2026_11_10__ The ACCOUNT bundle's caps, served by
     * `GET /v2/keys/<peer>/devices` as `accountCaps` (the install that reads the account
     * mailbox). Exact for the account slot, media bit only: in device mode the account bundle is
     * never fetched (that would pop a one-time pre-key), so without this the account slot stayed
     * unknown forever and the conservative media gate never padded — measured on production
     * 10/11 between two current desktops. Message padding keeps its own (session) evidence.
     */
    fun noteAccountCaps(peer: String, caps: Int?) {
        if (caps == null) return
        set(key(peer, null), (caps and V2Padding.CAP_MEDIA_PADDING) != 0, upgradeOnly = false, map = mediaSlots)
    }

    /**
     * Learn from a `GET /v2/keys/<peer>/devices` body: `accountCaps` (exact, see
     * [noteAccountCaps]) and each listed device's `caps` (upgrade only, [noteDeviceList]).
     * Never throws; an old server without `accountCaps` simply teaches nothing about the account.
     */
    fun learnFromDeviceList(peer: String, body: String?) {
        val o = runCatching { JSONObject(body ?: return) }.getOrNull() ?: return
        if (o.has("accountCaps") && !o.isNull("accountCaps")) {
            o.optInt("accountCaps", -1).takeIf { it >= 0 }?.let { noteAccountCaps(peer, it) }
        }
        if (!o.optBoolean("enabled", false)) return
        val arr = o.optJSONArray("devices") ?: return
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val id = e.optString("deviceId").takeIf { it.isNotEmpty() } ?: continue
            if (e.has("caps") && !e.isNull("caps")) noteDeviceList(peer, id, e.optInt("caps"))
        }
    }

    fun noteInbound(peer: String, device: String?, padded: Boolean, carriedX3DH: Boolean) {
        if (carriedX3DH) set(key(peer, device), padded, upgradeOnly = false)
        else if (padded) set(key(peer, device), true, upgradeOnly = true)
        // __MEDIA_PADDING_2026_11_10__ A new X3DH means the slot's counterpart may be another
        // (older) install, and an ordinary message proves nothing about media: back to unknown.
        if (carriedX3DH) set(key(peer, device), false, upgradeOnly = false, map = mediaSlots)
    }

    /**
     * __MEDIA_PADDING_2026_11_10__ An inbound file whose manifest carries `paddedSize`: only a
     * build that reads padded media writes one, so this slot's counterpart reads it. Upgrade only.
     */
    fun noteInboundPaddedMedia(peer: String, device: String?) =
        set(key(peer, device), true, upgradeOnly = true, map = mediaSlots)

    fun mediaSlotCapable(peer: String, device: String?): Boolean = mediaSlots[key(peer, device)] == true

    /**
     * __MEDIA_PADDING_2026_11_10__ The ONE media padding decision for an upload to [peer].
     * CONSERVATIVE BY CONSTRUCTION: the key message may be delivered on the account envelope OR
     * on any per-device copy (routing can fall back from one to the other AFTER the blob is
     * sealed), so EVERY path must be confirmed capable — the ACCOUNT slot AND each of [devices]
     * (the peer's full device list; empty = the peer has none). [devices] null = the list could
     * not be fetched = unknown ⇒ false. Any slot unknown or old ⇒ false (unpadded).
     */
    fun mediaAllows(peer: String, devices: Collection<String>?): Boolean =
        devices != null && mediaSlotCapable(peer, null) && devices.all { mediaSlotCapable(peer, it) }

    /** Group: each other member's own [mediaAllows] verdict; at least one other member. */
    fun groupMediaAllows(perMember: Map<String, Boolean>, me: String): Boolean {
        val meId = normalizeIdentity(me)
        val others = perMember.filterKeys { it.isNotBlank() && normalizeIdentity(it) != meId }
        return others.isNotEmpty() && others.values.all { it }
    }

    fun slotCapable(peer: String, device: String?): Boolean = slots[key(peer, device)] == true

    fun identityAdvertises(peer: String): Boolean {
        val id = normalizeIdentity(peer)
        return slots.entries.any { (k, v) -> v && (k == id || k.startsWith("$id#")) }
    }

    /** Same shape as `GroupEnvelopeV3.canUse`: every other member must advertise padding. */
    fun groupAllows(members: Collection<String>, me: String): Boolean {
        val meId = normalizeIdentity(me)
        val others = members.filter { it.isNotBlank() && normalizeIdentity(it) != meId }
        return others.isNotEmpty() && others.all(::identityAdvertises)
    }

    @Synchronized
    fun clearForTesting() {
        slots.clear()
        mediaSlots.clear()
        store = null
    }
}
