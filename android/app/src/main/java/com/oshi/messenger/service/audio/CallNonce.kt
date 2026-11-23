package com.oshi.messenger.service.audio

/**
 * AES-GCM nonce derivation for OSHI calls — the exact layouts the shipping iOS
 * client emits. Pure Kotlin, no Android dependencies, so the wire layout is
 * unit-testable without a device.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 🔐 WHY THIS FILE EXISTS — two live AES-GCM nonce-reuse breaks on Android.
 *
 * AUDIO (iOS fix 2026-07-26, `VoiceCallManager.swift:2996-3034`)
 *   Android always stamped `sessionNonceSalt` — the SAME 4 bytes on both ends
 *   (the caller generates it and ships it in the offer body; the callee installs
 *   it verbatim). Both ends share one AES key and both start their counter at 1,
 *   so packet *n* in each direction carried a BIT-IDENTICAL (key, nonce) pair.
 *   Textbook GCM break: XOR of the two ciphertexts leaks the XOR of the
 *   plaintexts, and the GHASH authentication subkey H is recoverable from the
 *   pair → forgery. It failed silently because the 12-byte nonce rides on the
 *   wire (`VoiceCallManager.swift:12512`), so decryption never noticed.
 *
 * VIDEO (iOS fix 2026-07-27, `VideoCallManager.swift:241-330`)
 *   Android built `[counter LE(8)][0x00 ×4]` — VERBATIM the layout iOS documents
 *   as "a total GCM break" at `VideoCallManager.swift:244-252`. No salt, no
 *   direction bit, and the counter is reset to 0 on every startVideo.
 *
 * WIRE COMPATIBILITY (verified, not assumed): every receiver on both platforms
 * takes the 12 nonce bytes straight off the wire and feeds them to the GCM
 * decryptor; nothing compares them against a locally derived salt or counter
 * (iOS `VoiceCallManager.swift:12512`, `VideoCallManager.swift:273-278`;
 * Android `EnhancedCallManager.decryptVideoPacket`). So changing what we
 * TRANSMIT is invisible to peers of any version. iOS made both changes
 * unilaterally on exactly this argument and neither negotiates a capability
 * flag — Android mirrors that, with the same residual risk iOS accepted:
 * an OLD peer's transmissions still collide across directions
 * (`VoiceCallManager.swift:3009-3010`, `VideoCallManager.swift:279-280`).
 * ─────────────────────────────────────────────────────────────────────────────
 */
object CallNonce {

    /** Nonce length for AES-GCM on both media paths. */
    const val NONCE_LENGTH = 12

    /**
     * The byte the RESPONDER flips in the shared salt so its transmit salt can
     * never equal the initiator's. iOS `VoiceCallManager.swift:3022` — `s[0] ^= 0xA5`.
     */
    const val RESPONDER_SALT_XOR = 0xA5

    /**
     * Top bit of the 8-byte video counter field. Marks the nonce as VIDEO so it
     * can never alias an audio nonce built under the same key — the audio
     * counter starts at 1 and realistically never passes 2^32, so forcing this
     * bit makes the two spaces disjoint by construction rather than by a 2^-32
     * argument about salt collisions.
     * iOS `VideoCallManager.swift:289` — `VIDEO_NONCE_DOMAIN_BIT`.
     */
    const val VIDEO_NONCE_DOMAIN_BIT: Long = Long.MIN_VALUE // 0x8000_0000_0000_0000

    /**
     * Responder's transmit salt = the shared salt with its first byte XOR-ed by
     * 0xA5. Always differs from the base, so initiator-TX and responder-TX
     * nonces can never collide even at identical counter values.
     * iOS `VoiceCallManager.swift:3019-3024` (`responderNonceSalt(from:)`).
     */
    @JvmStatic
    fun responderNonceSalt(base: ByteArray): ByteArray {
        if (base.size != 4) return base
        val s = base.copyOf()
        s[0] = (s[0].toInt() xor RESPONDER_SALT_XOR).toByte()
        return s
    }

    /**
     * The salt WE stamp into every audio nonce we emit.
     * [isInitiator] = we placed the call (we generated the shared salt).
     * iOS `VoiceCallManager.swift:3031` — `txNonceSalt = isInitiator ? base : responder`.
     */
    @JvmStatic
    fun audioTxSalt(base: ByteArray, isInitiator: Boolean): ByteArray =
        if (isInitiator) base.copyOf() else responderNonceSalt(base)

    /**
     * The salt we EXPECT from the peer. Diagnostics only — decrypt reads the wire.
     * iOS `VoiceCallManager.swift:3032`.
     */
    @JvmStatic
    fun audioRxSalt(base: ByteArray, isInitiator: Boolean): ByteArray =
        if (isInitiator) responderNonceSalt(base) else base.copyOf()

    /**
     * Audio nonce: `[salt(4)][counter BE(8)]`.
     * iOS `VoiceCallManager.swift:12482-12491`.
     */
    @JvmStatic
    fun audioNonce(txSalt: ByteArray, counter: Long): ByteArray {
        require(txSalt.size == 4) { "audio nonce salt must be 4 bytes, was ${txSalt.size}" }
        val nonce = ByteArray(NONCE_LENGTH)
        System.arraycopy(txSalt, 0, nonce, 0, 4)
        for (i in 0..7) nonce[4 + i] = (counter shr (56 - i * 8)).toByte()
        return nonce
    }

    /**
     * Stamp the DIRECTION BIT into a freshly drawn 4-byte random video salt:
     * bit 7 of byte 0 is 1 for the caller, 0 for the callee. Two devices in one
     * call always disagree on this, so the two directions occupy provably
     * DISJOINT nonce spaces — the cross-direction collision cannot occur even at
     * identical counter values. The remaining 31 bits stay random.
     * iOS `VideoCallManager.swift:297-302`.
     */
    @JvmStatic
    fun videoTxSalt(random4: ByteArray, weAreCaller: Boolean): ByteArray {
        require(random4.size == 4) { "video nonce salt must be 4 bytes, was ${random4.size}" }
        val salt = random4.copyOf()
        salt[0] = ((salt[0].toInt() and 0x7F) or (if (weAreCaller) 0x80 else 0x00)).toByte()
        return salt
    }

    /**
     * Video nonce: `[salt(4)][(counter | VIDEO_NONCE_DOMAIN_BIT) BE(8)]`.
     * [counter] is the raw per-video-session counter, which starts at 1.
     * iOS `VideoCallManager.swift:311-322` (`nextVideoNonceBytes`).
     */
    @JvmStatic
    fun videoNonce(txSalt: ByteArray, counter: Long): ByteArray {
        require(txSalt.size == 4) { "video nonce salt must be 4 bytes, was ${txSalt.size}" }
        val stamped = counter or VIDEO_NONCE_DOMAIN_BIT
        val nonce = ByteArray(NONCE_LENGTH)
        System.arraycopy(txSalt, 0, nonce, 0, 4)
        // BIG-endian — iOS `VideoCallManager.swift:320` (`counter.bigEndian`).
        for (i in 0..7) nonce[4 + i] = (stamped shr (56 - i * 8)).toByte()
        return nonce
    }
}

/**
 * Bounded anti-replay window over whole 12-byte nonces.
 *
 * A set of recently-seen nonces rather than a counter high-water mark,
 * deliberately: peers running the OLD build emit `[counter LE(8)][0000]`, whose
 * bytes 4..11 are constant zero, so any counter-based window would read every one
 * of their packets as "counter 0" and reject the entire stream after the first
 * fragment. Comparing whole nonces is layout-agnostic and therefore safe against
 * every peer version.
 *
 * MUST be consulted only AFTER the GCM tag verifies, so an attacker cannot poison
 * the window with forged nonces.
 *
 * iOS `VideoCallManager.swift:332-384`.
 */
class NonceReplayWindow(
    /** iOS `VideoCallManager.swift:352` — ~2 s of fragments at 30 fps × up to 16 frags/frame. */
    private val capacity: Int = MAX_SEEN_NONCES
) {
    companion object {
        const val MAX_SEEN_NONCES = 1024
    }

    private val seen = object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > capacity
    }

    var dropCount: Long = 0L
        private set

    /** True when [nonce] has already been accepted in this session. */
    @Synchronized
    fun isReplay(nonce: ByteArray): Boolean {
        val key = nonce.joinToString("") { "%02x".format(it) }
        if (seen.containsKey(key)) {
            dropCount++
            return true
        }
        seen[key] = true
        return false
    }

    @Synchronized
    fun reset() {
        seen.clear()
        dropCount = 0L
    }

    @Synchronized
    fun size(): Int = seen.size
}
