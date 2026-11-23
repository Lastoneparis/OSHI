package com.oshi.messenger.network.v2.devsync

/**
 * `Noise_XXpsk0_25519_ChaChaPoly_SHA256`, implemented from the Noise Protocol Framework
 * specification, revision 34 (§5 processing rules, §9 PSK modifier, §11.3 Rekey).
 *
 *     -> psk, e
 *     <- e, ee, s, es
 *     -> s, se
 *
 * `psk0` (not `psk3`) so that a device WITHOUT the account key cannot produce or read message 1:
 * the responder drops the attempt before its static device key is revealed.
 *
 * Golden vectors: docs/fixtures/devsync/noise_handshake.json and noise_transport.json.
 */
object Noise {
    val PROTOCOL_NAME: ByteArray = "Noise_XXpsk0_25519_ChaChaPoly_SHA256".toByteArray(Charsets.US_ASCII)
    const val MAX_MESSAGE = 65535
    const val TAG = 16
    const val DH = 32
    /** 2^64 - 1 as an unsigned Long: the nonce reserved for Rekey(). */
    const val MAX_NONCE: Long = -1L
}

/** Noise CipherState (§5.1). [n] is an unsigned 64-bit counter kept in a Long. */
class CipherState(key: ByteArray? = null) {
    var k: ByteArray? = key
        private set
    var n: Long = 0
        private set

    fun hasKey() = k != null

    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val key = k ?: return plaintext
        if (n == Noise.MAX_NONCE) throw NoiseException("nonce exhausted")
        val ct = DevSyncCrypto.chachaEncrypt(key, n, ad, plaintext)
        n++
        return ct
    }

    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val key = k ?: return ciphertext
        if (n == Noise.MAX_NONCE) throw NoiseException("nonce exhausted")
        val pt = DevSyncCrypto.chachaDecrypt(key, n, ad, ciphertext)
        n++
        return pt
    }

    /** §11.3: k = first 32 bytes of ENCRYPT(k, 2^64-1, empty, 32 zero bytes). n is NOT reset. */
    fun rekey() {
        val key = k ?: throw NoiseException("rekey without a key")
        k = DevSyncCrypto.chachaEncrypt(key, Noise.MAX_NONCE, ByteArray(0), ByteArray(32)).copyOf(32)
    }

    /** Test hook: the vectors start a CipherState at a known key. */
    internal fun keyCopy(): ByteArray? = k?.copyOf()
}

/** Noise SymmetricState (§5.2). */
class SymmetricState {
    var h: ByteArray = if (Noise.PROTOCOL_NAME.size <= 32) Noise.PROTOCOL_NAME.copyOf(32)
    else DevSyncCrypto.sha256(Noise.PROTOCOL_NAME)
        private set
    private var ck: ByteArray = h.copyOf()
    var cs = CipherState()
        private set

    private fun hkdf2(ikm: ByteArray): Pair<ByteArray, ByteArray> {
        val temp = DevSyncCrypto.hmacSha256(ck, ikm)
        val o1 = DevSyncCrypto.hmacSha256(temp, byteArrayOf(1))
        val o2 = DevSyncCrypto.hmacSha256(temp, o1, byteArrayOf(2))
        return o1 to o2
    }

    fun mixKey(ikm: ByteArray) {
        val (newCk, tempK) = hkdf2(ikm)
        ck = newCk
        cs = CipherState(tempK.copyOf(32))
    }

    fun mixHash(data: ByteArray) {
        h = DevSyncCrypto.sha256(h, data)
    }

    fun mixKeyAndHash(ikm: ByteArray) {
        val temp = DevSyncCrypto.hmacSha256(ck, ikm)
        val o1 = DevSyncCrypto.hmacSha256(temp, byteArrayOf(1))
        val o2 = DevSyncCrypto.hmacSha256(temp, o1, byteArrayOf(2))
        val o3 = DevSyncCrypto.hmacSha256(temp, o2, byteArrayOf(3))
        ck = o1
        mixHash(o2)
        cs = CipherState(o3.copyOf(32))
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ct = cs.encryptWithAd(h, plaintext)
        mixHash(ct)
        return ct
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val pt = cs.decryptWithAd(h, ciphertext)
        mixHash(ciphertext)
        return pt
    }

    /** Split(): (c1 = initiator→responder, c2 = responder→initiator). */
    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = hkdf2(ByteArray(0))
        return CipherState(k1.copyOf(32)) to CipherState(k2.copyOf(32))
    }
}

/**
 * One XXpsk0 handshake. [ephemeralPriv] is injectable ONLY so the golden vectors can be reproduced;
 * production code always lets it default to fresh randomness.
 */
class NoiseHandshake(
    val initiator: Boolean,
    private val staticPriv: ByteArray,
    private val psk: ByteArray,
    prologue: ByteArray,
    ephemeralPriv: ByteArray? = null,
) {
    private val staticPub = DevSyncCrypto.x25519Public(staticPriv)
    private val ePriv = ephemeralPriv ?: DevSyncCrypto.generateX25519Private()
    private val ePub = DevSyncCrypto.x25519Public(ePriv)
    private val ss = SymmetricState()
    private var step = 0

    /** The peer's static key once known (after message 2 for the initiator, 3 for the responder). */
    var remoteStatic: ByteArray? = null
        private set
    private var re: ByteArray? = null

    init {
        require(psk.size == 32) { "psk must be 32 bytes" }
        ss.mixHash(prologue)
    }

    val handshakeHash: ByteArray get() = ss.h.copyOf()
    val isComplete: Boolean get() = step == 3

    /** Message 1 (initiator). */
    fun writeMessage1(payload: ByteArray): ByteArray {
        check(initiator && step == 0)
        ss.mixKeyAndHash(psk)          // psk
        ss.mixHash(ePub)               // e
        ss.mixKey(ePub)                //   psk modifier: e also calls MixKey
        val out = ePub + ss.encryptAndHash(payload)
        step = 1
        return checkSize(out)
    }

    fun readMessage1(message: ByteArray): ByteArray {
        check(!initiator && step == 0)
        if (message.size < Noise.DH + Noise.TAG) throw NoiseException("message 1 too short")
        ss.mixKeyAndHash(psk)
        val e = message.copyOfRange(0, Noise.DH)
        re = e
        ss.mixHash(e)
        ss.mixKey(e)
        val pt = ss.decryptAndHash(message.copyOfRange(Noise.DH, message.size))
        step = 1
        return pt
    }

    /** Message 2 (responder). */
    fun writeMessage2(payload: ByteArray): ByteArray {
        check(!initiator && step == 1)
        val remoteE = re!!
        ss.mixHash(ePub)                                          // e
        ss.mixKey(ePub)
        ss.mixKey(DevSyncCrypto.x25519(ePriv, remoteE))           // ee
        val encS = ss.encryptAndHash(staticPub)                   // s
        ss.mixKey(DevSyncCrypto.x25519(staticPriv, remoteE))      // es (responder: DH(s, re))
        val encP = ss.encryptAndHash(payload)
        step = 2
        return checkSize(ePub + encS + encP)
    }

    fun readMessage2(message: ByteArray): ByteArray {
        check(initiator && step == 1)
        if (message.size < Noise.DH + Noise.DH + Noise.TAG + Noise.TAG) throw NoiseException("message 2 too short")
        val e = message.copyOfRange(0, Noise.DH)
        re = e
        ss.mixHash(e)
        ss.mixKey(e)
        ss.mixKey(DevSyncCrypto.x25519(ePriv, e))                 // ee
        val rs = ss.decryptAndHash(message.copyOfRange(Noise.DH, Noise.DH + Noise.DH + Noise.TAG))  // s
        remoteStatic = rs
        ss.mixKey(DevSyncCrypto.x25519(ePriv, rs))                // es (initiator: DH(e, rs))
        val pt = ss.decryptAndHash(message.copyOfRange(Noise.DH + Noise.DH + Noise.TAG, message.size))
        step = 2
        return pt
    }

    /** Message 3 (initiator). */
    fun writeMessage3(payload: ByteArray): ByteArray {
        check(initiator && step == 2)
        val encS = ss.encryptAndHash(staticPub)                   // s
        ss.mixKey(DevSyncCrypto.x25519(staticPriv, re!!))         // se (initiator: DH(s, re))
        val encP = ss.encryptAndHash(payload)
        step = 3
        return checkSize(encS + encP)
    }

    fun readMessage3(message: ByteArray): ByteArray {
        check(!initiator && step == 2)
        if (message.size < Noise.DH + Noise.TAG + Noise.TAG) throw NoiseException("message 3 too short")
        val rs = ss.decryptAndHash(message.copyOfRange(0, Noise.DH + Noise.TAG))  // s
        remoteStatic = rs
        ss.mixKey(DevSyncCrypto.x25519(ePriv, rs))                // se (responder: DH(e, rs))
        val pt = ss.decryptAndHash(message.copyOfRange(Noise.DH + Noise.TAG, message.size))
        step = 3
        return pt
    }

    /** (send, receive) transport ciphers for THIS side. */
    fun split(maxMessages: Long = TransportCipher.REKEY_MESSAGES, maxBytes: Long = TransportCipher.REKEY_BYTES):
        Pair<TransportCipher, TransportCipher> {
        check(step == 3) { "handshake not complete" }
        val (c1, c2) = ss.split()
        val send = if (initiator) c1 else c2
        val recv = if (initiator) c2 else c1
        return TransportCipher(send, maxMessages, maxBytes) to TransportCipher(recv, maxMessages, maxBytes)
    }

    private fun checkSize(b: ByteArray): ByteArray {
        if (b.size > Noise.MAX_MESSAGE) throw NoiseException("handshake message too large")
        return b
    }
}

/**
 * One direction of the transport phase with the rekey policy of the design (§4.1):
 * after every message count += 1 and bytes += ciphertext length; when count >= 2^20 or
 * bytes >= 2^30 the CipherState is rekeyed and both counters reset. The nonce is not reset.
 * Associated data is empty.
 */
class TransportCipher(
    private val cs: CipherState,
    private val maxMessages: Long = REKEY_MESSAGES,
    private val maxBytes: Long = REKEY_BYTES,
) {
    var rekeys = 0
        private set
    private var count = 0L
    private var bytes = 0L

    fun encrypt(plaintext: ByteArray): ByteArray {
        if (plaintext.size > Noise.MAX_MESSAGE - Noise.TAG) throw NoiseException("transport plaintext too large")
        val ct = cs.encryptWithAd(EMPTY, plaintext)
        after(ct.size)
        return ct
    }

    fun decrypt(ciphertext: ByteArray): ByteArray {
        if (ciphertext.size > Noise.MAX_MESSAGE) throw NoiseException("transport message too large")
        val pt = cs.decryptWithAd(EMPTY, ciphertext)
        after(ciphertext.size)
        return pt
    }

    private fun after(ctLen: Int) {
        count++
        bytes += ctLen
        if (count >= maxMessages || bytes >= maxBytes) {
            cs.rekey()
            rekeys++
            count = 0
            bytes = 0
        }
    }

    internal fun keyForTest(): ByteArray? = cs.keyCopy()

    companion object {
        const val REKEY_MESSAGES: Long = 1L shl 20
        const val REKEY_BYTES: Long = 1L shl 30
        private val EMPTY = ByteArray(0)
    }
}
