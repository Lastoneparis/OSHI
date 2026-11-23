package com.oshi.messenger.service.stego

import com.oshi.messenger.network.v2.OSHICryptoV2
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * OSHI STEGO-v1 — "Disguise as photo". Normative spec: docs/stego/STEGO-v1.md.
 *
 * Pure JVM (no android.*): the same object runs in the unit tests, in the
 * cross-platform vector test and in the app. Crypto is the existing OSHI V2
 * primitive set ([OSHICryptoV2]: X25519, HKDF-SHA256, HMAC-SHA256, AES-256-GCM);
 * nothing here is a new primitive, only their composition, which the spec fixes.
 */
object StegoV1 {

    const val HEADER_PLAIN = 10
    const val HEADER_SEALED = HEADER_PLAIN + 16
    const val PREFIX = 32 + HEADER_SEALED          // E ‖ HEADER = 58 bytes
    const val OVERHEAD = PREFIX + 16               // + body tag = 74 bytes
    const val MAX_BODY = 1 shl 20
    const val MAX_FILE = 256 * 1024
    const val MAX_SLOTS = 3 * 4096 * 4096
    private val MAGIC = byteArrayOf('O'.code.toByte(), 'S'.code.toByte(), 'G'.code.toByte(), '1'.code.toByte())
    private val ZERO_NONCE = ByteArray(12)

    /** 8-bit RGB samples, row-major, 3 bytes per pixel. */
    class RgbImage(val width: Int, val height: Int, val rgb: ByteArray) {
        init { require(width > 0 && height > 0 && rgb.size == width * height * 3) }
        val slots: Int get() = rgb.size
    }

    data class Attachment(val name: String, val mime: String, val data: ByteArray)

    data class Payload(val text: String, val createdAtMs: Long, val file: Attachment? = null)

    sealed class RevealResult {
        data class Found(val senderPublicKey: ByteArray, val payload: Payload) : RevealResult()
        /** The header opened for one of our contacts, but the body did not (image altered). */
        data class Damaged(val senderPublicKey: ByteArray) : RevealResult()
        object NotForYou : RevealResult()
    }

    class CapacityException(val needed: Int, val available: Int) :
        IllegalArgumentException("Hidden message needs $needed bytes; this image holds $available")

    /** Bytes of message body ([Payload] encoding) this image can carry. */
    fun capacityBytes(slots: Int): Int = maxOf(0, slots / 8 - OVERHEAD)

    // ── Randomness ───────────────────────────────────────────────────────────

    /** One byte at a time: direction / fill bits (spec §5). */
    fun interface ByteSource { fun next(): Int }

    fun secureByteSource(): ByteSource {
        val rnd = SecureRandom()
        val buf = ByteArray(4096)
        var pos = buf.size
        return ByteSource {
            if (pos == buf.size) { rnd.nextBytes(buf); pos = 0 }
            buf[pos++].toInt() and 0xFF
        }
    }

    /** HMAC(key, u64be(k)) counter stream — the slot order and the test RNG. */
    class HmacStream(private val key: ByteArray) {
        private var counter = 0L
        private var block = ByteArray(0)
        private var pos = 0
        private val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply {
            init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        }
        fun nextByte(): Int {
            if (pos == block.size) {
                block = mac.doFinal(ByteBuffer.allocate(8).putLong(counter++).array()); pos = 0
            }
            return block[pos++].toInt() and 0xFF
        }
        fun nextU32(): Long =
            (nextByte().toLong() shl 24) or (nextByte().toLong() shl 16) or (nextByte().toLong() shl 8) or nextByte().toLong()
    }

    fun testByteSource(): ByteSource {
        val s = HmacStream("OSHI-STEGO-v1 test rng".toByteArray(Charsets.UTF_8))
        return ByteSource { s.nextByte() }
    }

    // ── Keys (spec §3) ──────────────────────────────────────────────────────

    fun orderKey(ssStatic: ByteArray): ByteArray =
        OSHICryptoV2.hkdf(ssStatic, "OSHI-STEGO-v1".toByteArray(), "order".toByteArray(), 32)

    fun sealKeys(ssEph: ByteArray, ssStatic: ByteArray, e: ByteArray, s: ByteArray, r: ByteArray): Pair<ByteArray, ByteArray> {
        val ikm = ssEph + ssStatic
        val salt = e + s + r
        return OSHICryptoV2.hkdf(ikm, salt, "OSHI-STEGO-v1 header".toByteArray(), 32) to
            OSHICryptoV2.hkdf(ikm, salt, "OSHI-STEGO-v1 body".toByteArray(), 32)
    }

    // ── Slot order (spec §5) ────────────────────────────────────────────────

    /**
     * Lazily produces the keyed Fisher–Yates order over [0, n). Sparse while few
     * outputs are needed (the header trial reads 464), dense once the body is long.
     */
    class SlotOrder(orderKey: ByteArray, private val n: Int) {
        private val stream = HmacStream(orderKey)
        private var t = 0
        private var sparse: HashMap<Int, Int>? = HashMap()
        private var dense: IntArray? = null

        private fun rand(m: Int): Int {
            val range = 1L shl 32
            val limit = range - (range % m)
            while (true) {
                val w = stream.nextU32()
                if (w < limit) return (w % m).toInt()
            }
        }
        private fun get(i: Int): Int = dense?.get(i) ?: sparse!!.getOrDefault(i, i)
        private fun set(i: Int, v: Int) { val d = dense; if (d != null) d[i] = v else sparse!![i] = v }

        fun ensureDense() {
            if (dense != null) return
            val d = IntArray(n) { it }
            sparse!!.forEach { (k, v) -> d[k] = v }
            dense = d; sparse = null
        }

        fun next(): Int {
            require(t < n) { "slot order exhausted" }
            val j = t + rand(n - t)
            val at = get(t); val aj = get(j)
            set(t, aj); set(j, at)
            return get(t).also { t++ }
        }
    }

    // ── Payload encoding (spec §4) ──────────────────────────────────────────

    fun encodePayload(p: Payload): ByteArray {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { require(v in 0..0xFFFF); u8(v ushr 8); u8(v) }
        fun u32(v: Int) { out.write(ByteBuffer.allocate(4).putInt(v).array()) }
        u8(1)
        out.write(ByteBuffer.allocate(8).putLong(p.createdAtMs).array())
        val text = p.text.toByteArray(Charsets.UTF_8)
        u32(text.size); out.write(text)
        val f = p.file
        if (f == null) u8(0) else {
            require(f.data.size <= MAX_FILE) { "Attachment too large" }
            u8(1)
            val name = f.name.toByteArray(Charsets.UTF_8); u16(name.size); out.write(name)
            val mime = f.mime.toByteArray(Charsets.UTF_8); u16(mime.size); out.write(mime)
            u32(f.data.size); out.write(f.data)
        }
        return out.toByteArray().also { require(it.size <= MAX_BODY) { "Message too large" } }
    }

    fun decodePayload(b: ByteArray): Payload {
        val buf = ByteBuffer.wrap(b)
        fun bytes(n: Int): ByteArray { require(n >= 0 && n <= buf.remaining()); return ByteArray(n).also { buf.get(it) } }
        require(buf.get().toInt() == 1) { "unknown kind" }
        val created = buf.long
        val text = String(bytes(buf.int), Charsets.UTF_8)
        val file = when (buf.get().toInt()) {
            0 -> null
            1 -> {
                val name = String(bytes(buf.short.toInt() and 0xFFFF), Charsets.UTF_8)
                val mime = String(bytes(buf.short.toInt() and 0xFFFF), Charsets.UTF_8)
                Attachment(name, mime, bytes(buf.int))
            }
            else -> throw IllegalArgumentException("bad file flag")
        }
        require(!buf.hasRemaining()) { "trailing bytes" }
        return Payload(text, created, file)
    }

    // ── Hide ────────────────────────────────────────────────────────────────

    /**
     * Returns a copy of [cover] carrying [payload] for [recipientPub], from the
     * identity ([senderPriv], [senderPub]). [ephemeralPriv] and [random] exist for
     * the deterministic vectors; production passes neither.
     */
    fun hide(
        cover: RgbImage,
        payload: Payload,
        senderPriv: ByteArray,
        senderPub: ByteArray,
        recipientPub: ByteArray,
        ephemeralPriv: ByteArray? = null,
        random: ByteSource = secureByteSource(),
    ): RgbImage {
        val body = encodePayload(payload)
        val needed = body.size
        val available = capacityBytes(cover.slots)
        if (needed > available) throw CapacityException(needed, available)

        val ePriv = ephemeralPriv ?: OSHICryptoV2.generateX25519().priv
        val ePub = OSHICryptoV2.x25519PubFromPriv(ePriv)
        val ssStatic = OSHICryptoV2.dh(senderPriv, recipientPub)
        val ssEph = OSHICryptoV2.dh(ePriv, recipientPub)
        val (kHeader, kBody) = sealKeys(ssEph, ssStatic, ePub, senderPub, recipientPub)
        val headerPlain = MAGIC + byteArrayOf(1, 0) + ByteBuffer.allocate(4).putInt(body.size).array()
        val header = OSHICryptoV2.aesGcmSeal(kHeader, ZERO_NONCE, headerPlain, ePub)
        val sealedBody = OSHICryptoV2.aesGcmSeal(kBody, ZERO_NONCE, body, ePub + header)
        val stream = ePub + header + sealedBody

        val out = cover.rgb.copyOf()
        val used = java.util.BitSet(cover.slots)
        val order = SlotOrder(orderKey(ssStatic), cover.slots)
        if (stream.size * 8 > 50_000) order.ensureDense()
        for (byteIndex in stream.indices) {
            val v = stream[byteIndex].toInt()
            for (bit in 7 downTo 0) {
                val slot = order.next()
                used.set(slot)
                embed(out, slot, (v ushr bit) and 1, random.next() and 1)
            }
        }
        for (slot in 0 until cover.slots) {
            if (used.get(slot)) continue
            val r = random.next()
            embed(out, slot, (r ushr 7) and 1, r and 1)
        }
        return RgbImage(cover.width, cover.height, out)
    }

    private fun embed(samples: ByteArray, slot: Int, bit: Int, up: Int) {
        val v = samples[slot].toInt() and 0xFF
        if ((v and 1) == bit) return
        val nv = when (v) { 0 -> 1; 255 -> 254; else -> if (up == 1) v + 1 else v - 1 }
        samples[slot] = nv.toByte()
    }

    // ── Reveal (spec §6) ────────────────────────────────────────────────────

    /**
     * Tries each of [contactPubs] as the sender. [myPriv]/[myPub] are this
     * device's identity. Never throws for malformed input: any failure is
     * [RevealResult.NotForYou].
     */
    fun reveal(image: RgbImage, myPriv: ByteArray, myPub: ByteArray, contactPubs: Collection<ByteArray>): RevealResult {
        if (image.slots > MAX_SLOTS || image.slots < OVERHEAD * 8) return RevealResult.NotForYou
        for (sender in contactPubs) {
            if (sender.size != 32) continue
            val result = try { tryContact(image, myPriv, myPub, sender) } catch (_: Exception) { null }
            if (result != null) return result
        }
        return RevealResult.NotForYou
    }

    private fun readBytes(image: RgbImage, order: SlotOrder, count: Int): ByteArray {
        val out = ByteArray(count)
        for (i in 0 until count) {
            var b = 0
            for (k in 0 until 8) b = (b shl 1) or (image.rgb[order.next()].toInt() and 1)
            out[i] = b.toByte()
        }
        return out
    }

    private fun tryContact(image: RgbImage, myPriv: ByteArray, myPub: ByteArray, sender: ByteArray): RevealResult? {
        val ssStatic = OSHICryptoV2.dh(myPriv, sender)
        val order = SlotOrder(orderKey(ssStatic), image.slots)
        val prefix = readBytes(image, order, PREFIX)
        val ePub = prefix.copyOfRange(0, 32)
        val header = prefix.copyOfRange(32, PREFIX)
        val ssEph = OSHICryptoV2.dh(myPriv, ePub)
        val (kHeader, kBody) = sealKeys(ssEph, ssStatic, ePub, sender, myPub)
        val h = try { OSHICryptoV2.aesGcmOpen(kHeader, ZERO_NONCE, header, ePub) } catch (_: Exception) { return null }
        if (!h.copyOfRange(0, 4).contentEquals(MAGIC) || h[4].toInt() != 1 || h[5].toInt() != 0) return RevealResult.Damaged(sender)
        val bodyLen = ByteBuffer.wrap(h, 6, 4).int
        if (bodyLen < 0 || bodyLen > MAX_BODY || (PREFIX + bodyLen + 16).toLong() * 8 > image.slots) return RevealResult.Damaged(sender)
        if ((bodyLen + 16) * 8 > 50_000) order.ensureDense()
        val sealedBody = readBytes(image, order, bodyLen + 16)
        return try {
            val body = OSHICryptoV2.aesGcmOpen(kBody, ZERO_NONCE, sealedBody, ePub + header)
            RevealResult.Found(sender, decodePayload(body))
        } catch (_: Exception) {
            RevealResult.Damaged(sender)
        }
    }
}
