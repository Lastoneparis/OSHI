package com.oshi.messenger.network.mesh.wifiaware

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * __WIFI_AWARE_2026_11_25__ The wire of the Wi-Fi Aware (NAN) lane, kept free of
 * `android.*` so every byte of it runs under JVM unit tests.
 *
 * Wi-Fi Aware carries exactly what Nearby Connections already carries: the
 * `MeshMessage` / `RelayMessage` JSON of [com.oshi.messenger.network.mesh.MeshNetworkManager],
 * whose payload is the already end-to-end encrypted OSHI envelope. Nothing in
 * this file encrypts content; it only frames it and decides who is on the other
 * end of the socket.
 *
 * Three layers:
 *
 *  1. DISCOVERY ([Discovery]) — what goes in the publish `serviceSpecificInfo`
 *     and in the one discovery message (HELLO). Small (≤ 32 bytes, well under the
 *     255-byte minimum every Aware chip must accept). It carries a per-attach
 *     RANDOM token, never the public key: a stable key-derived value broadcast
 *     in the clear would let anybody with a radio follow a phone around. The
 *     "identity hint" is `SHA-256(publicKey ‖ token)[0..4)`, so only a device that
 *     already knows the key (a contact, or a peer we are already connected to)
 *     can recognise it, and it changes every time the token does.
 *
 *  2. FRAMING ([Framing]) — `[type:1][length:4 big-endian][payload]` on the
 *     TCP socket opened over the Aware data path.
 *
 *  3. HANDSHAKE ([Handshake]) — both sides send IDENT (public key, name, 32-byte
 *     nonce) and must answer the other's nonce with a PROOF:
 *     `HMAC-SHA256(DH(myPriv, theirPub), LABEL ‖ proverKey ‖ nonce)`. Only a
 *     device holding the private half of the key it announced can produce it.
 *     Unlike the advisory challenge on the BLE/TCP lane
 *     ([com.oshi.messenger.network.mesh.MeshPeerAuth]) this one is MANDATORY:
 *     both ends of this lane are new code, so there is no older peer to stay
 *     compatible with, and an unproven peer must never be listed as "connected"
 *     — the send path marks a message delivered the moment it hands it to a
 *     listed mesh peer, so an impostor claiming a contact's key would otherwise
 *     swallow that contact's messages. The prover's own key is inside the MAC,
 *     so a nonce reflected back at us on a second connection yields a tag bound
 *     to OUR key, not to the key being claimed.
 */
object WifiAwareProtocol {

    /** Aware service name both sides publish and subscribe. */
    const val SERVICE_NAME = "oshi-mesh"

    /** Bumped only for an incompatible change; a peer with another version is ignored. */
    const val VERSION: Byte = 1

    const val TOKEN_LENGTH = 8
    const val HINT_LENGTH = 4
    const val SALT_LENGTH = 8
    const val NONCE_LENGTH = 32
    const val TAG_LENGTH = 32

    // ───────────────────────────── 1. discovery ─────────────────────────────

    object Discovery {
        private val MAGIC = byteArrayOf('O'.code.toByte(), 'A'.code.toByte())
        const val TYPE_ANNOUNCE: Byte = 1
        const val TYPE_HELLO: Byte = 2
        /**
         * Responder → initiator: "my side of the data path is requested, go".
         * Aware rejects an initiator's request that reaches a responder which has
         * not asked for the network yet, so the initiator waits for this.
         */
        const val TYPE_READY: Byte = 3

        /** 2 magic + 1 version + 1 type + token + hint. */
        const val ANNOUNCE_LENGTH = 4 + TOKEN_LENGTH + HINT_LENGTH
        /** ANNOUNCE + salt. */
        const val HELLO_LENGTH = ANNOUNCE_LENGTH + SALT_LENGTH

        data class Message(
            val type: Byte,
            val token: ByteArray,
            val hint: ByteArray,
            val salt: ByteArray?,
        ) {
            override fun equals(other: Any?): Boolean =
                other is Message && type == other.type && token.contentEquals(other.token) &&
                    hint.contentEquals(other.hint) &&
                    ((salt == null && other.salt == null) || (salt != null && other.salt != null && salt.contentEquals(other.salt)))
            override fun hashCode(): Int = token.contentHashCode()
        }

        fun encodeAnnounce(token: ByteArray, hint: ByteArray): ByteArray =
            header(TYPE_ANNOUNCE, token, hint)

        fun encodeReady(token: ByteArray, hint: ByteArray): ByteArray =
            header(TYPE_READY, token, hint)

        fun encodeHello(token: ByteArray, hint: ByteArray, salt: ByteArray): ByteArray {
            require(salt.size == SALT_LENGTH) { "salt must be $SALT_LENGTH bytes" }
            return header(TYPE_HELLO, token, hint) + salt
        }

        private fun header(type: Byte, token: ByteArray, hint: ByteArray): ByteArray {
            require(token.size == TOKEN_LENGTH) { "token must be $TOKEN_LENGTH bytes" }
            require(hint.size == HINT_LENGTH) { "hint must be $HINT_LENGTH bytes" }
            return MAGIC + byteArrayOf(VERSION, type) + token + hint
        }

        /**
         * Null for anything that is not a well-formed message of OUR version —
         * another app's service with the same name, a future version, a truncated
         * frame. Discovery input comes from any radio in range: it is parsed,
         * never trusted.
         */
        fun decode(bytes: ByteArray?): Message? {
            if (bytes == null || bytes.size < ANNOUNCE_LENGTH) return null
            if (bytes[0] != MAGIC[0] || bytes[1] != MAGIC[1] || bytes[2] != VERSION) return null
            val type = bytes[3]
            val token = bytes.copyOfRange(4, 4 + TOKEN_LENGTH)
            val hint = bytes.copyOfRange(4 + TOKEN_LENGTH, ANNOUNCE_LENGTH)
            return when (type) {
                TYPE_ANNOUNCE, TYPE_READY -> if (bytes.size == ANNOUNCE_LENGTH) Message(type, token, hint, null) else null
                TYPE_HELLO -> if (bytes.size == HELLO_LENGTH)
                    Message(type, token, hint, bytes.copyOfRange(ANNOUNCE_LENGTH, HELLO_LENGTH)) else null
                else -> null
            }
        }

        /** `SHA-256(publicKey UTF-8 ‖ token)[0..4)` — see the file header for why not the bare key. */
        fun identityHint(publicKey: String, token: ByteArray): ByteArray =
            sha256(publicKey.toByteArray(Charsets.UTF_8) + token).copyOf(HINT_LENGTH)

        /** True when [hint] was made from [publicKey] with [token]. */
        fun hintMatches(publicKey: String, token: ByteArray, hint: ByteArray): Boolean =
            MessageDigest.isEqual(identityHint(publicKey, token), hint)

        /**
         * Exactly one side of a pair opens the data path. Each phone both
         * publishes and subscribes, so each discovers the other once; without an
         * election both would initiate and the pair would hold two data paths
         * (or, on chips with a single NDP, fail both). The smaller token
         * (unsigned, byte by byte) initiates. Equal tokens — 1 in 2^64, or a
         * phone hearing its own publication — initiate nothing.
         */
        fun shouldInitiate(myToken: ByteArray, theirToken: ByteArray): Boolean =
            compareUnsigned(myToken, theirToken) < 0

        /**
         * Passphrase for the data path's PSK. 32 characters (Aware accepts 8–63).
         *
         * NOT a secret: every input crosses the air in the clear during
         * discovery. It exists because a data path must have one and because it
         * pins the link to this one pairing. Confidentiality of messages comes
         * from the OSHI end-to-end envelope, authenticity of the peer from
         * [Handshake] — never from this string.
         */
        fun passphrase(initiatorToken: ByteArray, responderToken: ByteArray, salt: ByteArray): String {
            val digest = sha256("OSHI-AWARE-PSK-v1".toByteArray(Charsets.UTF_8) + initiatorToken + responderToken + salt)
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest).take(32)
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

    // ───────────────────────────── 2. framing ─────────────────────────────

    object Framing {
        const val TYPE_IDENT: Byte = 1
        const val TYPE_PROOF: Byte = 2
        /** One `MeshMessage` / `RelayMessage` JSON, UTF-8, exactly as Nearby carries it. */
        const val TYPE_ENVELOPE: Byte = 3
        /** Receipt of an ENVELOPE: 8-byte big-endian sequence number. */
        const val TYPE_ACK: Byte = 4
        const val TYPE_PING: Byte = 5
        const val TYPE_BYE: Byte = 6

        /**
         * Largest payload accepted. Media rides the mesh inside a JSON envelope as
         * base64; 32 MiB covers anything the send pipeline produces while keeping
         * a hostile length prefix from allocating the heap away.
         */
        const val MAX_PAYLOAD = 32 * 1024 * 1024

        /** Handshake frames are tiny; a bigger one before authentication is an attack. */
        const val MAX_HANDSHAKE_PAYLOAD = 4 * 1024

        data class Frame(val type: Byte, val payload: ByteArray) {
            override fun equals(other: Any?): Boolean =
                other is Frame && type == other.type && payload.contentEquals(other.payload)
            override fun hashCode(): Int = 31 * type + payload.contentHashCode()
        }

        class FramingException(message: String) : IOException(message)

        fun encode(type: Byte, payload: ByteArray): ByteArray {
            require(payload.size <= MAX_PAYLOAD) { "payload too large: ${payload.size}" }
            val out = ByteArrayOutputStream(5 + payload.size)
            DataOutputStream(out).apply {
                writeByte(type.toInt())
                writeInt(payload.size)
                write(payload)
                flush()
            }
            return out.toByteArray()
        }

        fun write(out: OutputStream, type: Byte, payload: ByteArray) {
            out.write(encode(type, payload))
            out.flush()
        }

        /**
         * Next frame, or null on a clean end of stream BETWEEN frames. A stream
         * that ends inside a frame, a negative or oversized length, or an unknown
         * type is a [FramingException]: the socket is then closed, never
         * resynchronised — a length prefix that cannot be trusted means every
         * later boundary is a guess.
         */
        fun read(input: InputStream, maxPayload: Int = MAX_PAYLOAD): Frame? {
            val first = input.read()
            if (first < 0) return null
            val type = first.toByte()
            if (type < TYPE_IDENT || type > TYPE_BYE) throw FramingException("unknown frame type $first")
            val data = DataInputStream(input)
            val length = try { data.readInt() } catch (e: EOFException) { throw FramingException("truncated header") }
            if (length < 0 || length > maxPayload) throw FramingException("bad length $length")
            val payload = ByteArray(length)
            try { data.readFully(payload) } catch (e: EOFException) { throw FramingException("truncated payload") }
            return Frame(type, payload)
        }

        fun encodeSeq(seq: Long): ByteArray = ByteArray(8) { i -> (seq ushr (56 - 8 * i)).toByte() }

        fun decodeSeq(bytes: ByteArray): Long? {
            if (bytes.size != 8) return null
            var v = 0L
            for (b in bytes) v = (v shl 8) or (b.toLong() and 0xFF)
            return v
        }

        /** ENVELOPE payload: 8-byte sequence number, then the JSON bytes. */
        fun encodeEnvelope(seq: Long, json: ByteArray): ByteArray = encodeSeq(seq) + json

        fun decodeEnvelope(payload: ByteArray): Pair<Long, ByteArray>? {
            if (payload.size < 8) return null
            val seq = decodeSeq(payload.copyOf(8)) ?: return null
            return seq to payload.copyOfRange(8, payload.size)
        }
    }

    // ───────────────────────────── 3. handshake ─────────────────────────────

    data class Ident(val publicKey: String, val displayName: String, val nonce: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Ident && publicKey == other.publicKey && displayName == other.displayName && nonce.contentEquals(other.nonce)
        override fun hashCode(): Int = publicKey.hashCode()
    }

    fun encodeIdent(ident: Ident): ByteArray {
        require(ident.nonce.size == NONCE_LENGTH)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeByte(VERSION.toInt())
            write(ident.nonce)
            val key = ident.publicKey.toByteArray(Charsets.UTF_8)
            val name = ident.displayName.toByteArray(Charsets.UTF_8).let { if (it.size > 256) it.copyOf(256) else it }
            writeShort(key.size); write(key)
            writeShort(name.size); write(name)
            flush()
        }
        return out.toByteArray()
    }

    fun decodeIdent(bytes: ByteArray): Ident? = try {
        val input = DataInputStream(bytes.inputStream())
        if (input.readByte() != VERSION) null else {
            val nonce = ByteArray(NONCE_LENGTH).also { input.readFully(it) }
            val key = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
            val name = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
            if (input.available() != 0 || key.isEmpty() || key.size > 512) null
            else Ident(String(key, Charsets.UTF_8), String(name, Charsets.UTF_8), nonce)
        }
    } catch (e: IOException) { null }

    private val PROOF_LABEL = "OSHI-AWARE-PROOF-v1".toByteArray(Charsets.UTF_8)

    /** `HMAC-SHA256(sharedSecret, LABEL ‖ len(proverKey) ‖ proverKey ‖ nonce)`. */
    fun proofTag(sharedSecret: ByteArray, proverPublicKey: String, nonce: ByteArray): ByteArray {
        val key = proverPublicKey.toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sharedSecret, "HmacSHA256"))
        mac.update(PROOF_LABEL)
        mac.update(byteArrayOf((key.size ushr 8).toByte(), key.size.toByte()))
        mac.update(key)
        mac.update(nonce)
        return mac.doFinal()
    }

    /**
     * One side of the socket handshake, as a pure state machine. The caller
     * writes whatever [start] / [onFrame] return and enforces the deadline.
     *
     * [sharedSecret] is `DH(myPrivate, peerPublicKey)`; null (malformed key, no
     * key pair yet) fails the handshake.
     */
    class Handshake(
        private val myPublicKey: String,
        private val myName: String,
        private val myNonce: ByteArray,
        private val sharedSecret: (peerPublicKey: String) -> ByteArray?,
    ) {
        sealed class Result {
            /** Keep reading; write [send] (possibly empty) first. */
            data class Continue(val send: List<Framing.Frame>) : Result()
            data class Verified(val peerPublicKey: String, val peerName: String, val send: List<Framing.Frame>) : Result()
            data class Failed(val reason: String) : Result()
        }

        private var peer: Ident? = null
        private var secret: ByteArray? = null
        private var proofReceived = false
        private var done = false

        init { require(myNonce.size == NONCE_LENGTH) }

        fun start(): Framing.Frame =
            Framing.Frame(Framing.TYPE_IDENT, encodeIdent(Ident(myPublicKey, myName, myNonce)))

        fun onFrame(frame: Framing.Frame): Result {
            if (done) return Result.Failed("handshake already finished")
            return when (frame.type) {
                Framing.TYPE_IDENT -> onIdent(frame.payload)
                Framing.TYPE_PROOF -> onProof(frame.payload)
                else -> fail("unexpected frame ${frame.type} before authentication")
            }
        }

        private fun onIdent(payload: ByteArray): Result {
            if (peer != null) return fail("second IDENT")
            val ident = decodeIdent(payload) ?: return fail("malformed IDENT")
            if (ident.publicKey == myPublicKey) return fail("connected to ourselves")
            if (ident.nonce.contentEquals(myNonce)) return fail("reflected nonce")
            val s = try { sharedSecret(ident.publicKey) } catch (e: Exception) { null }
                ?: return fail("no shared secret for announced key")
            peer = ident
            secret = s
            val proof = Framing.Frame(Framing.TYPE_PROOF, proofTag(s, myPublicKey, ident.nonce))
            return Result.Continue(listOf(proof))
        }

        private fun onProof(payload: ByteArray): Result {
            val p = peer ?: return fail("PROOF before IDENT")
            val s = secret ?: return fail("no secret")
            if (proofReceived) return fail("second PROOF")
            proofReceived = true
            if (payload.size != TAG_LENGTH) return fail("bad tag length")
            val expected = proofTag(s, p.publicKey, myNonce)
            if (!MessageDigest.isEqual(expected, payload)) return fail("tag mismatch")
            done = true
            return Result.Verified(p.publicKey, p.displayName, emptyList())
        }

        private fun fail(reason: String): Result { done = true; return Result.Failed(reason) }
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
