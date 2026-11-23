package com.oshi.messenger.network.v2.devsync

/**
 * Key and identifier derivations of docs/OSHI_DEVICE_SYNC_DIRECT.md §3, §4.3, §5.1.
 * Golden vectors: docs/fixtures/devsync/keys.json.
 *
 *  - PSK      = HKDF-SHA256(ikm = IK, salt = "OSHI-DEVSYNC-v1", info = "noise-psk", 32)
 *  - DISC     = HKDF-SHA256(ikm = IK, salt = "OSHI-DEVSYNC-v1", info = "mdns-tag", 32)
 *  - deviceId = SHA-256("OSHI-DEVSYNC-id" || DK_pub)[0..16]   (32 lowercase hex)
 *  - mdnsTag  = hex(HMAC-SHA256(DISC, "t" || u64be(floor(unix / 3600))))[0..16]
 *  - SAS      = u32be(HKDF-SHA256(ikm = h, salt = empty, info = "OSHI-DEVSYNC-sas", 32)[0..4]) mod 10^6
 *
 * IK is the RAW 32-byte X25519 account private key exactly as stored (not clamped).
 */
object DevSyncKeys {

    private val SALT = "OSHI-DEVSYNC-v1".toByteArray(Charsets.UTF_8)
    private val INFO_PSK = "noise-psk".toByteArray(Charsets.UTF_8)
    private val INFO_DISC = "mdns-tag".toByteArray(Charsets.UTF_8)
    private val INFO_SAS = "OSHI-DEVSYNC-sas".toByteArray(Charsets.UTF_8)
    private val ID_PREFIX = "OSHI-DEVSYNC-id".toByteArray(Charsets.UTF_8)
    private val PROLOGUE_PREFIX = "OSHI-DEVSYNC/1\n".toByteArray(Charsets.UTF_8)

    const val DEVICE_ID_BYTES = 16
    const val TRANSPORT_LAN = "lan"
    const val TRANSPORT_RELAY = "relay"

    fun psk(ikPriv: ByteArray): ByteArray {
        require(ikPriv.size == 32) { "account key must be 32 bytes" }
        return DevSyncCrypto.hkdf(ikPriv, SALT, INFO_PSK, 32)
    }

    fun disc(ikPriv: ByteArray): ByteArray {
        require(ikPriv.size == 32) { "account key must be 32 bytes" }
        return DevSyncCrypto.hkdf(ikPriv, SALT, INFO_DISC, 32)
    }

    fun deviceId(dkPub: ByteArray): ByteArray =
        DevSyncCrypto.sha256(ID_PREFIX, dkPub).copyOf(DEVICE_ID_BYTES)

    fun deviceIdHex(dkPub: ByteArray): String = DevSyncCrypto.hex(deviceId(dkPub))

    fun isDeviceIdHex(s: String?): Boolean =
        s != null && s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' }

    fun mdnsTag(disc: ByteArray, unixSeconds: Long): String {
        val hour = Math.floorDiv(unixSeconds, 3600L)
        val msg = ByteArray(9)
        msg[0] = 't'.code.toByte()
        for (i in 0 until 8) msg[1 + i] = (hour ushr (56 - 8 * i)).toByte()
        return DevSyncCrypto.hex(DevSyncCrypto.hmacSha256(disc, msg)).substring(0, 16)
    }

    /** Tags a device of this account accepts at [unixSeconds]: the current hour and the previous one. */
    fun acceptedTags(disc: ByteArray, unixSeconds: Long): Set<String> =
        setOf(mdnsTag(disc, unixSeconds), mdnsTag(disc, unixSeconds - 3600))

    fun sasCode(handshakeHash: ByteArray): String {
        val okm = DevSyncCrypto.hkdf(handshakeHash, ByteArray(0), INFO_SAS, 32)
        val v = ((okm[0].toLong() and 0xFF) shl 24) or ((okm[1].toLong() and 0xFF) shl 16) or
            ((okm[2].toLong() and 0xFF) shl 8) or (okm[3].toLong() and 0xFF)
        return String.format(java.util.Locale.ROOT, "%06d", v % 1_000_000L)
    }

    /**
     * Noise prologue. Relay: "OSHI-DEVSYNC/1\n" || "relay" || "\n" || min(idA, idB) || max(idA, idB)
     * (raw 16-byte ids, unsigned byte order). LAN: only "OSHI-DEVSYNC/1\nlan\n" — the responder's id is
     * not known before message 2 there.
     */
    fun prologue(transport: String, idA: ByteArray? = null, idB: ByteArray? = null): ByteArray {
        val head = PROLOGUE_PREFIX + transport.toByteArray(Charsets.UTF_8) + byteArrayOf('\n'.code.toByte())
        if (transport == TRANSPORT_LAN) return head
        requireNotNull(idA); requireNotNull(idB)
        require(idA.size == DEVICE_ID_BYTES && idB.size == DEVICE_ID_BYTES)
        val (lo, hi) = if (compareUnsigned(idA, idB) <= 0) idA to idB else idB to idA
        return head + lo + hi
    }

    fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}
