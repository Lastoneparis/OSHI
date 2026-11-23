package com.oshi.messenger.network.encryption

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/**
 * __REPLAY_IS_NOT_A_DESYNC_2026_10_14__ This envelope is BEHIND the chain — we
 * have already read it, or it arrived after its key was consumed. It says
 * nothing about the health of the session.
 *
 * It needs its own type because the two mesh receive paths in `MessageRepository`
 * react to a decrypt failure by RESETTING the ratchet session, and resetting on a
 * replay destroys a session that is perfectly fine: the peer's next message then
 * cannot be read, which resets it again. That is the "one message in two" the
 * 14/10 report describes (Huawei nova 5T to iPad 9). iOS carries the same rule in
 * `MessageManager.ratchetFailureProvesSessionIsBroken`.
 *
 * A mesh floods and retries, so a second copy is routine, not exotic.
 */
class ReplayedEnvelopeException(message: String) : IllegalStateException(message)

/**
 * Does a failed decrypt PROVE the ratchet session is broken, or only that THIS
 * envelope cannot be read?
 *
 * The counterpart of iOS `MessageManager.ratchetFailureProvesSessionIsBroken`.
 * Stated once, here, so the four receive paths in `MessageRepository` cannot
 * drift apart — and so the rule can be tested without an Android runtime.
 */
object RatchetFailurePolicy {
    fun provesSessionIsBroken(error: Throwable): Boolean = error !is ReplayedEnvelopeException
}

/**
 * Double Ratchet Protocol Implementation - iOS Compatible
 *
 * Matches iOS DoubleRatchet.swift EXACTLY:
 * - Same HKDF info strings: "RootKey", "InitialChain", "MessageKey", "ChainKey"
 * - Same session ID: SHA256(sorted([key1, key2]).joined(":")) as hex
 * - Same initiator logic: ourKey < theirKey
 * - Same AAD construction for AES-GCM
 * - Same first-message handling (store key, no DH ratchet)
 * - Ephemeral X25519 keys for DH ratchet (NOT identity keys)
 */

private const val TAG = "DoubleRatchet"
// SECURITY (CSPN 2026-09): compile-time guard. Must stay false in release.
// When false, root/chain key material is never handed to Log.*.
private const val LOG_SENSITIVE = false

/**
 * iOS DoubleRatchetMessage format (matches Swift Codable output)
 */
data class IOSRatchetHeader(
    val publicKey: ByteArray,        // 32-byte ephemeral key (base64 in JSON)
    val messageNumber: Int,          // UInt32
    val previousChainLength: Int,    // UInt32
    val isDHRatchet: Boolean,
    val timestamp: Double,           // Seconds since 2001-01-01 (Swift Date)
    val sessionVersion: Int,
    val sessionId: String,
    /**
     * `"hybridPQ"` quand la racine de cette session a absorbé du X-Wing, `null`
     * sinon. Absent = classique, ce qui est ce qu'un pair d'avant le
     * post-quantique envoie — et doit continuer de marcher à l'identique.
     */
    val securityMode: String? = null,
    /**
     * Le chiffré X-Wing, porté jusqu'à ce que le pair ait montré qu'il l'a reçu.
     *
     * Un KEM est unidirectionnel: contrairement à Diffie-Hellman, le répondeur
     * ne peut pas dériver le secret depuis des clés qu'il détient déjà. C'est ce
     * seul fait qui force ces 1120 octets sur le fil.
     */
    val pqCiphertext: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IOSRatchetHeader) return false
        return publicKey.contentEquals(other.publicKey) &&
                messageNumber == other.messageNumber &&
                sessionId == other.sessionId
    }

    override fun hashCode(): Int {
        var result = publicKey.contentHashCode()
        result = 31 * result + messageNumber
        result = 31 * result + sessionId.hashCode()
        return result
    }
}

data class IOSRatchetMessage(
    val header: IOSRatchetHeader,
    val ciphertext: ByteArray       // AES-GCM combined: nonce(12) || ct || tag(16)
) {
    companion object {
        /**
         * Parse iOS DoubleRatchetMessage JSON (Swift Codable format)
         */
        fun fromJSON(json: JSONObject): IOSRatchetMessage {
            val headerJson = json.getJSONObject("header")

            // iOS Codable encodes Data as base64
            val publicKeyBase64 = headerJson.getString("publicKey")
            val publicKeyBytes = RatchetBase64.decode(publicKeyBase64, RatchetBase64.NO_WRAP)

            val header = IOSRatchetHeader(
                publicKey = publicKeyBytes,
                messageNumber = headerJson.getInt("messageNumber"),
                previousChainLength = headerJson.optInt("previousChainLength", 0),
                isDHRatchet = headerJson.optBoolean("isDHRatchet", false),
                timestamp = headerJson.optDouble("timestamp", 0.0),
                sessionVersion = headerJson.optInt("sessionVersion", 2),
                sessionId = headerJson.getString("sessionId"),
                // Absents chez un pair classique: `null`, et tout se comporte
                // exactement comme avant.
                securityMode = if (headerJson.has("securityMode"))
                    headerJson.optString("securityMode", null) else null,
                pqCiphertext = headerJson.optString("pqCiphertext", "")
                    .takeIf { it.isNotEmpty() }
                    ?.let { RatchetBase64.decode(it, RatchetBase64.NO_WRAP) }
            )

            val ciphertextBase64 = json.getString("ciphertext")
            val ciphertext = RatchetBase64.decode(ciphertextBase64, RatchetBase64.NO_WRAP)

            return IOSRatchetMessage(header, ciphertext)
        }
    }

    /**
     * Serialize to JSON matching iOS Codable DoubleRatchetMessage format
     */
    fun toJSON(): JSONObject {
        val headerJson = JSONObject().apply {
            put("publicKey", RatchetBase64.encodeToString(header.publicKey, RatchetBase64.NO_WRAP))
            put("messageNumber", header.messageNumber)
            put("previousChainLength", header.previousChainLength)
            put("isDHRatchet", header.isDHRatchet)
            put("timestamp", header.timestamp)
            put("sessionVersion", header.sessionVersion)
            put("sessionId", header.sessionId)
            // ⚠️ N'ÉCRIRE QUE SI PRÉSENT. Poser `securityMode: null` ou une
            // chaîne vide changerait les octets d'un en-tête CLASSIQUE, que
            // chaque pair déjà déployé sait lire aujourd'hui. Le chemin
            // classique doit rester octet pour octet ce qu'il est.
            header.securityMode?.let { put("securityMode", it) }
            header.pqCiphertext?.let {
                put("pqCiphertext", RatchetBase64.encodeToString(it, RatchetBase64.NO_WRAP))
            }
        }
        return JSONObject().apply {
            put("header", headerJson)
            put("ciphertext", RatchetBase64.encodeToString(ciphertext, RatchetBase64.NO_WRAP))
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IOSRatchetMessage) return false
        return header == other.header && ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int = 31 * header.hashCode() + ciphertext.contentHashCode()
}

/**
 * Ratchet session state - matches iOS DoubleRatchetSession fields
 *
 * THREAD SAFETY. Every mutation of this state goes through [withLock] (iOS serialises
 * the same state on a per-session serial queue, `OSHI/DoubleRatchet.swift:93,170-174,213-217`).
 * The skipped-key map is private behind that lock and only ever leaves it as a COPY —
 * the shape iOS adopted in a585b8e (`OSHI/DoubleRatchet.swift:54-89`) after its own
 * serialiser raced the writer.
 */
class RatchetSession(
    var rootKey: ByteArray,
    var sendingChainKey: ByteArray,
    var receivingChainKey: ByteArray,
    var sendingMessageNumber: Int = 0,
    var receivingMessageNumber: Int = 0,
    var previousSendingChainLength: Int = 0,
    var ourRatchetPrivateKey: ByteArray,    // 32-byte X25519 ephemeral private
    var ourRatchetPublicKey: ByteArray,     // 32-byte X25519 ephemeral public
    var theirRatchetPublicKey: ByteArray?,  // null until first message
    val sessionId: String,
    val isInitiator: Boolean,
    val sessionVersion: Int = 2,
    skippedMessageKeys: Map<String, ByteArray> = emptyMap(),
    skippedMessageKeyTimes: Map<String, Long> = emptyMap(),
    /** Set by [DoubleRatchet.performDHRatchet], carried on the next header, cleared after that send (iOS `:370,190,203`). */
    var needsDHRatchet: Boolean = false,
    /**
     * `"hybridPQ"` quand la racine de cette session a absorbé du X-Wing.
     *
     * ÉPINGLÉ DANS LA SESSION, pas décidé par message. Si le mode pouvait varier
     * d'un message à l'autre, un attaquant qui retire le chiffré KEM
     * rétrograderait la conversation en classique — exactement ce que l'hybride
     * existe pour empêcher.
     */
    val securityMode: String? = null,
    /**
     * Le chiffré X-Wing, porté sur chaque en-tête d'une session hybride.
     *
     * Un KEM est unidirectionnel: le répondeur ne peut pas dériver le secret
     * depuis des clés qu'il détient déjà, il lui faut ces 1120 octets.
     */
    val pqCiphertext: ByteArray? = null,
) {
    private val lock = ReentrantLock()
    private val skipped = LinkedHashMap<String, ByteArray>(skippedMessageKeys)
    /** Unix seconds each skipped key was stored, for the 7-day prune. LOCAL ONLY — never on the wire. */
    private val skippedAt = LinkedHashMap<String, Long>(skippedMessageKeyTimes)

    /** Run [body] with this session's state frozen against every other thread. Reentrant. */
    fun <T> withLock(body: () -> T): T = lock.withLock(body)

    /**
     * The send-side read-modify-write, as ONE atomic step: derive the message key for
     * the current chain, hand it (with its message number) to [body], and only then
     * advance the chain.
     *
     * This is the whole P0. Unguarded, two coroutines entering the send path for the
     * same peer both read the same `sendingChainKey`, both derive the SAME AES-256-GCM
     * message key, and encrypt two different plaintexts under it. [body] runs inside
     * the lock so a throwing encrypt leaves the chain exactly where it was.
     */
    fun <T> withSendKey(
        deriveMessageKey: (ByteArray) -> ByteArray,
        advanceChainKey: (ByteArray) -> ByteArray,
        body: (messageNumber: Int, messageKey: ByteArray, isDHRatchet: Boolean) -> T,
    ): T = withLock {
        val messageKey = deriveMessageKey(sendingChainKey)
        val messageNumber = sendingMessageNumber
        val flag = needsDHRatchet
        val result = body(messageNumber, messageKey, flag)
        sendingChainKey = advanceChainKey(sendingChainKey)
        sendingMessageNumber++
        if (flag) needsDHRatchet = false
        result
    }

    /** A COPY of the skipped-key map (iOS `OSHI/DoubleRatchet.swift:62-65`). */
    fun snapshotSkippedKeys(): Map<String, ByteArray> = withLock { LinkedHashMap(skipped) }

    fun skippedKeyCount(): Int = withLock { skipped.size }

    fun putSkippedKey(key: String, messageKey: ByteArray, nowSeconds: Long = System.currentTimeMillis() / 1000) =
        withLock { skipped[key] = messageKey; skippedAt[key] = nowSeconds }

    /** Remove and return a skipped key, or null. */
    fun takeSkippedKey(key: String): ByteArray? = withLock { skippedAt.remove(key); skipped.remove(key) }

    fun replaceSkippedKeys(keys: Map<String, ByteArray>, times: Map<String, Long>) = withLock {
        skipped.clear(); skipped.putAll(keys)
        skippedAt.clear(); skippedAt.putAll(times)
    }

    fun snapshotSkippedKeyTimes(): Map<String, Long> = withLock { LinkedHashMap(skippedAt) }

    /**
     * Drop skipped keys older than [maxAgeSeconds], mirroring iOS
     * `cleanupOldSkippedKeys(olderThan: 7)` (`OSHI/DoubleRatchet.swift:484-496`).
     * Without it every out-of-order gap adds entries forever: unbounded storage, a
     * full re-serialisation on every save, and message keys kept indefinitely.
     * Entries with no recorded time (written before this field existed) are stamped
     * now rather than dropped.
     */
    fun pruneSkippedKeys(maxAgeSeconds: Long, nowSeconds: Long = System.currentTimeMillis() / 1000): Int = withLock {
        var removed = 0
        for (key in skipped.keys.toList()) {
            val at = skippedAt[key]
            if (at == null) { skippedAt[key] = nowSeconds; continue }
            if (nowSeconds - at > maxAgeSeconds) {
                skipped.remove(key); skippedAt.remove(key); removed++
            }
        }
        removed
    }

    fun toJSON(): JSONObject = withLock {
        val json = JSONObject()
        json.put("rootKey", RatchetBase64.encodeToString(rootKey, RatchetBase64.NO_WRAP))
        json.put("sendingChainKey", RatchetBase64.encodeToString(sendingChainKey, RatchetBase64.NO_WRAP))
        json.put("receivingChainKey", RatchetBase64.encodeToString(receivingChainKey, RatchetBase64.NO_WRAP))
        json.put("sendingMessageNumber", sendingMessageNumber)
        json.put("receivingMessageNumber", receivingMessageNumber)
        json.put("previousSendingChainLength", previousSendingChainLength)
        json.put("ourRatchetPrivateKey", RatchetBase64.encodeToString(ourRatchetPrivateKey, RatchetBase64.NO_WRAP))
        json.put("ourRatchetPublicKey", RatchetBase64.encodeToString(ourRatchetPublicKey, RatchetBase64.NO_WRAP))
        theirRatchetPublicKey?.let {
            json.put("theirRatchetPublicKey", RatchetBase64.encodeToString(it, RatchetBase64.NO_WRAP))
        }
        json.put("sessionId", sessionId)
        json.put("isInitiator", isInitiator)
        json.put("sessionVersion", sessionVersion)

        val skippedJson = JSONObject()
        val timesJson = JSONObject()
        // Serialise the live maps under the same lock every writer takes — iOS reached
        // the same shape by handing out a copy (`OSHI/DoubleRatchet.swift:62-65,154`).
        // Unguarded, this threw ConcurrentModificationException mid-iteration and
        // saveSessions SWALLOWED it: the ratchet advanced in memory, nothing was
        // written, and the next launch rewound to a state whose keys were already used.
        skipped.forEach { (key, value) ->
            skippedJson.put(key, RatchetBase64.encodeToString(value, RatchetBase64.NO_WRAP))
        }
        skippedAt.forEach { (key, value) -> timesJson.put(key, value) }
        json.put("skippedMessageKeys", skippedJson)
        json.put("skippedMessageKeyTimes", timesJson)
        json
    }

    companion object {
        fun fromJSON(json: JSONObject): RatchetSession {
            val skippedJson = json.optJSONObject("skippedMessageKeys")
            val skipped = mutableMapOf<String, ByteArray>()
            skippedJson?.keys()?.forEach { key ->
                skipped[key] = RatchetBase64.decode(skippedJson.getString(key), RatchetBase64.NO_WRAP)
            }
            val timesJson = json.optJSONObject("skippedMessageKeyTimes")
            val times = mutableMapOf<String, Long>()
            timesJson?.keys()?.forEach { key -> times[key] = timesJson.optLong(key, 0L) }

            return RatchetSession(
                rootKey = RatchetBase64.decode(json.getString("rootKey"), RatchetBase64.NO_WRAP),
                sendingChainKey = RatchetBase64.decode(json.getString("sendingChainKey"), RatchetBase64.NO_WRAP),
                receivingChainKey = RatchetBase64.decode(json.getString("receivingChainKey"), RatchetBase64.NO_WRAP),
                sendingMessageNumber = json.getInt("sendingMessageNumber"),
                receivingMessageNumber = json.getInt("receivingMessageNumber"),
                previousSendingChainLength = json.optInt("previousSendingChainLength", 0),
                ourRatchetPrivateKey = RatchetBase64.decode(json.getString("ourRatchetPrivateKey"), RatchetBase64.NO_WRAP),
                ourRatchetPublicKey = RatchetBase64.decode(json.getString("ourRatchetPublicKey"), RatchetBase64.NO_WRAP),
                theirRatchetPublicKey = json.optString("theirRatchetPublicKey", null)?.let {
                    if (it.isNotEmpty()) RatchetBase64.decode(it, RatchetBase64.NO_WRAP) else null
                },
                sessionId = json.getString("sessionId"),
                isInitiator = json.getBoolean("isInitiator"),
                sessionVersion = json.optInt("sessionVersion", 2),
                skippedMessageKeys = skipped,
                skippedMessageKeyTimes = times
            )
        }
    }
}

/**
 * Double Ratchet - iOS Compatible Implementation
 */
@Singleton
class DoubleRatchet @Inject constructor(
    private val cryptoManager: CryptoManager
) {
    companion object {

    /**
     * Construct AAD matching iOS encodeHeaderForAAD:
     * publicKey(32) || messageNumber(4,BE) || previousChainLength(4,BE) ||
     * isDHRatchet(1) || sessionVersion(4,BE,Int32) || sessionId(UTF8)
     */
    fun encodeHeaderForAAD(header: IOSRatchetHeader): ByteArray {
        // La queue post-quantique n'est ajoutée QUE pour une session hybride.
        //
        // C'est elle qui rend la rétrogradation détectable: un attaquant qui
        // retire `pqCiphertext` pour forcer une session classique change l'AAD,
        // et le déchiffrement échoue à l'authentification au lieu de réussir
        // plus faiblement. Le mode est épinglé dans la session, pas décidé par
        // message, pour exactement cette raison.
        val pqTail: ByteArray =
            if (header.securityMode == RatchetSecurityMode.HYBRID_PQ) {
                RatchetSecurityMode.HYBRID_PQ.toByteArray(Charsets.UTF_8) +
                    (header.pqCiphertext ?: ByteArray(0))
            } else ByteArray(0)

        val buffer = ByteBuffer.allocate(
            32 + 4 + 4 + 1 + 4 + header.sessionId.toByteArray(Charsets.UTF_8).size +
                pqTail.size
        )
        buffer.order(ByteOrder.BIG_ENDIAN)

        // publicKey (32 bytes raw)
        buffer.put(header.publicKey)

        // messageNumber (UInt32 big-endian)
        buffer.putInt(header.messageNumber)

        // previousChainLength (UInt32 big-endian)
        buffer.putInt(header.previousChainLength)

        // isDHRatchet (1 byte: 0x00 or 0x01)
        buffer.put(if (header.isDHRatchet) 1.toByte() else 0.toByte())

        // sessionVersion (Int32 big-endian)
        buffer.putInt(header.sessionVersion)

        // sessionId (UTF-8 bytes)
        buffer.put(header.sessionId.toByteArray(Charsets.UTF_8))

        buffer.put(pqTail)

        return buffer.array()
    }

        private const val MAX_SKIP = 200 // Match iOS maxSkippedMessages

        /** iOS `cleanupOldSkippedKeys(olderThan: 7)` — `OSHI/DoubleRatchet.swift:484-496`. */
        private const val SKIPPED_KEY_MAX_AGE_SECONDS = 7L * 24 * 60 * 60

        /**
         * Apple epoch offset. iOS's header `timestamp` is a Swift `Date`, which Codable
         * encodes as seconds since 2001-01-01; Android was emitting Unix seconds, i.e.
         * every Android header decoded on iOS as a year-2055 date. Cosmetic only — the
         * AAD (`encodeHeaderForAAD`) deliberately excludes the timestamp — but the field
         * is what the doc-comment on [IOSRatchetHeader.timestamp] always claimed it was.
         */
        private const val APPLE_EPOCH_OFFSET_SECONDS = 978_307_200.0

        // HKDF info strings - MUST match iOS exactly
        internal const val INFO_ROOT_KEY = "RootKey"
        internal const val INFO_INITIAL_CHAIN = "InitialChain"

        /**
         * Les chaînes info de la variante HYBRIDE. Doivent être identiques à
         * celles d'iOS au caractère près.
         *
         * Elles sont distinctes de leurs jumelles classiques par séparation de
         * domaine: la même matière première ne doit jamais produire la même clé
         * selon qu'on est en hybride ou non, sinon les deux modes pourraient
         * silencieusement se confondre.
         */
        internal const val INFO_ROOT_KEY_HYBRID = "RootKey-XWing-v1"
        internal const val INFO_INITIAL_CHAIN_HYBRID = "InitialChain-XWing-v1"

        /**
         * CV-010 — session version for the ratchet that actually rotates.
         * Matches `DoubleRatchetSession.ratchetingSessionVersion` in Swift, and
         * `decrypt` compares versions strictly, so v2 and v3 traffic stay apart.
         */
        const val RATCHETING_SESSION_VERSION = 3

        /** Must be byte-identical to iOS `InitialRatchetKey-v3`. */
        private const val INFO_INITIAL_RATCHET_KEY = "InitialRatchetKey-v3"

        /** Must be byte-identical to iOS `ResponderInitialChain-v3`. */
        private const val INFO_RESPONDER_INITIAL_CHAIN = "ResponderInitialChain-v3"

        /**
         * The responder's INITIAL ratchet private key (CV-010).
         *
         * Deliberately a PURE companion function with its own HKDF rather than a
         * call through `CryptoManager`: `CryptoManager` needs an Android
         * `Context`, which would have put this behind an instrumented test, and
         * the one number that MUST match iOS exactly is the one that then never
         * gets checked on a plain JVM run. Same rule `GroupWireFormatTest`
         * states for the group wire format.
         *
         * RFC 5869 with an empty salt, which is HashLen zeros — the same rule
         * `CryptoManager.hkdfDerive` follows, and the same CryptoKit applies.
         * `RatchetV3VectorTest` pins the output against a third implementation.
         */
        /**
         * HKDF-SHA256 à sel vide, 32 octets — exactement ce que fait
         * `HKDF<SHA256>.deriveKey(ikm:salt:info:outputByteCount:)` d'iOS avec un
         * `salt: Data()`.
         *
         * Le sel vide devient 32 octets nuls à l'extraction: c'est la règle de
         * la RFC 5869, et c'est ce que CryptoKit applique. Vérifié sur vecteurs,
         * pas supposé.
         */
        internal fun hkdf32(ikm: ByteArray, info: String): ByteArray {
            fun mac(key: ByteArray, data: ByteArray): ByteArray {
                val m = javax.crypto.Mac.getInstance("HmacSHA256")
                m.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
                return m.doFinal(data)
            }
            val prk = mac(ByteArray(32), ikm)
            return mac(prk, info.toByteArray(Charsets.UTF_8) + byteArrayOf(1)).copyOf(32)
        }

        /**
         * La matière première d'une session, et la moitié du contrat hybride.
         *
         * ⚠️ L'ORDRE DE CONCATÉNATION FAIT PARTIE DU FIL. iOS compose
         * `sharedSecret || pqSecret`; l'inverser donnerait deux racines
         * différentes et aucun message ne passerait — sans que rien ne signale
         * une erreur de format.
         */
        internal fun sessionIkm(sharedSecret: ByteArray, pqSecret: ByteArray?): ByteArray =
            if (pqSecret == null) sharedSecret else sharedSecret + pqSecret

        /**
         * La racine et la chaîne initiale d'une session.
         *
         * Les chaînes info sont séparées par domaine entre les deux modes, de
         * sorte qu'une session hybride et une session classique ne puissent
         * jamais dériver la même clé du même secret X25519.
         */
        internal fun deriveRootAndChain(
            sharedSecret: ByteArray,
            pqSecret: ByteArray?
        ): Pair<ByteArray, ByteArray> {
            val ikm = sessionIkm(sharedSecret, pqSecret)
            val hybrid = pqSecret != null
            return Pair(
                hkdf32(ikm, if (hybrid) INFO_ROOT_KEY_HYBRID else INFO_ROOT_KEY),
                hkdf32(ikm, if (hybrid) INFO_INITIAL_CHAIN_HYBRID else INFO_INITIAL_CHAIN)
            )
        }

        fun derivedResponderRatchetPrivateKey(sharedSecret: ByteArray): ByteArray {
            fun mac(key: ByteArray, data: ByteArray): ByteArray {
                val m = javax.crypto.Mac.getInstance("HmacSHA256")
                m.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
                return m.doFinal(data)
            }
            val info = INFO_INITIAL_RATCHET_KEY.toByteArray(Charsets.UTF_8)
            val prk = mac(ByteArray(32), sharedSecret)     // empty salt -> 32 zero bytes
            return mac(prk, info + byteArrayOf(1)).copyOf(32)
        }

        /**
         * The chain carrying the RESPONDER's messages until its first DH ratchet.
         *
         * It replaces `ByteArray(32)`. Those 32 zero bytes were a matched pair —
         * the responder's sending chain and the initiator's receiving chain — so a
         * responder that spoke before it had received anything encrypted every
         * message under HKDF(00x32, "MessageKey"), a constant with no secret input:
         * d663c614...4562, the same on every device in the world.
         *
         * Same pure-HKDF discipline and the same reason as
         * [derivedResponderRatchetPrivateKey]: the one number that MUST match iOS
         * has to be checkable on a plain JVM run.
         *
         * CROSS-PLATFORM CONTRACT: iOS `DoubleRatchetSession.responderInitialChain`
         * must produce identical bytes. This changes the key schedule while leaving
         * the wire format untouched, so a peer running an older build fails the
         * AEAD in SILENCE. iOS and Android must ship this together.
         */
        fun responderInitialChain(sharedSecret: ByteArray): ByteArray {
            fun mac(key: ByteArray, data: ByteArray): ByteArray {
                val m = javax.crypto.Mac.getInstance("HmacSHA256")
                m.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
                return m.doFinal(data)
            }
            val info = INFO_RESPONDER_INITIAL_CHAIN.toByteArray(Charsets.UTF_8)
            val prk = mac(ByteArray(32), sharedSecret)     // empty salt -> 32 zero bytes
            return mac(prk, info + byteArrayOf(1)).copyOf(32)
        }
        /** Must be byte-identical to iOS `ResponderInitialChain-v2`. Distinct
         *  from the v3 info string so the two schedules never collide. */
        private const val INFO_RESPONDER_INITIAL_CHAIN_V2 = "ResponderInitialChain-v2"

        /**
         * The v2 counterpart of [responderInitialChain], for the OLD non-rotating
         * layout.
         *
         * v2 never turns the DH ratchet, so the responder's sending chain — and
         * the initiator's receiving chain — stayed at `ByteArray(32)` FOREVER,
         * encrypting every message under HKDF(00x32, "MessageKey") =
         * d663c614…4562, a constant with no secret input, identical on every
         * device on earth. v3 fixed its own copy on 2026-09-08; v2 shared the
         * defect on a larger installed base, and it is the one a reader of the
         * public repository sees first.
         *
         * Now derived from the shared secret, exactly like the initiator's first
         * chain. v2 still offers no forward secrecy for its opening messages —
         * that is its design limit — but the PUBLIC CONSTANT is gone from both the
         * behaviour and the source.
         *
         * CROSS-PLATFORM CONTRACT: iOS `responderInitialChainV2` must produce
         * identical bytes, or every v2 session where the responder speaks first
         * fails the AEAD in silence. [Audit 2026-09-09]
         */
        fun responderInitialChainV2(sharedSecret: ByteArray): ByteArray {
            fun mac(key: ByteArray, data: ByteArray): ByteArray {
                val m = javax.crypto.Mac.getInstance("HmacSHA256")
                m.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
                return m.doFinal(data)
            }
            val info = INFO_RESPONDER_INITIAL_CHAIN_V2.toByteArray(Charsets.UTF_8)
            val prk = mac(ByteArray(32), sharedSecret)     // empty salt -> 32 zero bytes
            return mac(prk, info + byteArrayOf(1)).copyOf(32)
        }
        private const val INFO_MESSAGE_KEY = "MessageKey"
        private const val INFO_CHAIN_KEY = "ChainKey"
    }

    // ConcurrentHashMap + a create path that resolves a race to ONE session: two
    // coroutines opening the same conversation at once used to build two sessions with
    // two different ephemeral key pairs, and whichever lost the `put` kept encrypting
    // into a chain nobody persisted.
    private val sessions = ConcurrentHashMap<String, RatchetSession>()

    /**
     * Look a session up by the id its messages carry.
     *
     * A group envelope v4 arriving over IPFS or the relay has no
     * transport-level sender, and does not name one on purpose. The only handle
     * is the `sessionId` inside our own wrap's ratchet header — which is exactly
     * enough, because the session it names is the one able to open that wrap.
     *
     * Mirrors `DoubleRatchetSessionManager.session(withId:)` on iOS.
     */
    fun sessionById(sessionId: String): RatchetSession? = sessions[sessionId]
    private val createLock = ReentrantLock()

    /**
     * Compute canonical session ID matching iOS:
     * SHA256(sorted([key1, key2]).joined(":")) as lowercase hex
     */
    fun makeSessionId(publicKey1: String, publicKey2: String): String {
        val sorted = listOf(publicKey1, publicKey2).sorted()
        val combined = sorted.joinToString(":")
        val hash = MessageDigest.getInstance("SHA-256").digest(combined.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Determine initiator role matching iOS: ourKey < theirKey
     */
    private fun isInitiator(ourKey: String, theirKey: String): Boolean {
        return ourKey < theirKey
    }

    /**
     * Get or create a session matching iOS DoubleRatchetSessionManager.getOrCreateSession
     */
    fun getOrCreateSession(
        theirPublicKey: String,
        ourPublicKey: String,
        sharedSecret: ByteArray,
        ratcheting: Boolean = false
    ): RatchetSession {
        val sessionId = makeSessionId(ourPublicKey, theirPublicKey)

        sessions[sessionId]?.let { existing ->
            RatchetLog.d(TAG, "📖 Using existing session: ${sessionId.take(16)}...")
            return existing
        }

        return createLock.withLock {
            // Re-check: another thread may have created it while we waited.
            sessions[sessionId] ?: createSession(sessionId, theirPublicKey, ourPublicKey, sharedSecret, ratcheting)
        }
    }

    private fun createSession(
        sessionId: String,
        theirPublicKey: String,
        ourPublicKey: String,
        sharedSecret: ByteArray,
        ratcheting: Boolean = false
    ): RatchetSession {
        // Derive root key: HKDF(sharedSecret, empty salt, "RootKey", 32)
        val rootKey = cryptoManager.hkdfDerive(
            sharedSecret = sharedSecret,
            salt = ByteArray(0),
            info = INFO_ROOT_KEY,
            outputLength = 32
        )

        // Derive initial chain: HKDF(sharedSecret, empty salt, "InitialChain", 32)
        val initialChain = cryptoManager.hkdfDerive(
            sharedSecret = sharedSecret,
            salt = ByteArray(0),
            info = INFO_INITIAL_CHAIN,
            outputLength = 32
        )

        val weAreInitiator = isInitiator(ourKey = ourPublicKey, theirKey = theirPublicKey)

        // Generate ephemeral ratchet key pair (NOT identity keys!)
        val (ephPrivate, ephPublic) = cryptoManager.generateEphemeralKeyPair()

        val session = if (ratcheting) {
            // CV-010: initialise the way the Double Ratchet specifies. The
            // responder holds a pair DERIVED from the shared secret, so the
            // initiator knows its public half and the responder's first receive
            // can perform a real ratchet. Mirrors
            // `DoubleRatchetSession.makeRatcheting` in Swift.
            val responderPriv = derivedResponderRatchetPrivateKey(sharedSecret)
            val responderPub = X25519PrivateKeyParameters(responderPriv, 0).generatePublicKey().encoded
            val responderChain = responderInitialChain(sharedSecret)
            if (weAreInitiator) {
                val dh = cryptoManager.deriveSharedSecretWithKeys(ephPrivate, responderPub)
                val (rk, sendChain) = kdfRootKey(rootKey, dh)
                RatchetSession(
                    rootKey = rk,
                    sendingChainKey = sendChain,
                    // Matches the responder's sending chain. Was ByteArray(32). [Audit 2026-09-08]
                    receivingChainKey = responderChain,
                    ourRatchetPrivateKey = ephPrivate,
                    ourRatchetPublicKey = ephPublic,
                    theirRatchetPublicKey = responderPub,
                    sessionId = sessionId,
                    isInitiator = true,
                    sessionVersion = RATCHETING_SESSION_VERSION
                )
            } else {
                RatchetSession(
                    rootKey = rootKey,
                    // Was ByteArray(32), a public constant. [Audit 2026-09-08]
                    sendingChainKey = responderChain,
                    receivingChainKey = ByteArray(32),
                    ourRatchetPrivateKey = responderPriv,
                    ourRatchetPublicKey = responderPub,
                    theirRatchetPublicKey = null,
                    sessionId = sessionId,
                    isInitiator = false,
                    sessionVersion = RATCHETING_SESSION_VERSION
                )
            }
        } else {
            // v2, the non-rotating layout. The responder's chain was ByteArray(32)
            // — the public constant d663c614… ; it is now derived from the shared
            // secret (responderInitialChainV2), exactly as the initiator half
            // already was. No constant enters the encryption. [Audit 2026-09-09]
            val responderChainV2 = responderInitialChainV2(sharedSecret)
            RatchetSession(
                rootKey = rootKey,
                sendingChainKey = if (weAreInitiator) initialChain else responderChainV2,
                receivingChainKey = if (weAreInitiator) responderChainV2 else initialChain,
                ourRatchetPrivateKey = ephPrivate,
                ourRatchetPublicKey = ephPublic,
                theirRatchetPublicKey = null,  // Set on first message (matches iOS)
                sessionId = sessionId,
                isInitiator = weAreInitiator
            )
        }

        sessions[sessionId] = session

        val role = if (weAreInitiator) "INITIATOR" else "RESPONDER"
        RatchetLog.d(TAG, "🆕 Created session: ${sessionId.take(16)}... role=$role")
        if (LOG_SENSITIVE) RatchetLog.d(TAG, "   rootKey: ${RatchetBase64.encodeToString(rootKey, RatchetBase64.NO_WRAP).take(20)}...")
        if (LOG_SENSITIVE) RatchetLog.d(TAG, "   initialChain: ${RatchetBase64.encodeToString(initialChain, RatchetBase64.NO_WRAP).take(20)}...")

        return session
    }

    /**
     * Get existing session by session ID
     */
    fun getSession(sessionId: String): RatchetSession? = sessions[sessionId]

    /**
     * Get session for a contact
     */
    fun getSessionForContact(theirPublicKey: String, ourPublicKey: String): RatchetSession? {
        val sessionId = makeSessionId(ourPublicKey, theirPublicKey)
        return sessions[sessionId]
    }

    /**
     * Reset (delete and recreate) a session for a contact.
     * Used when the ratchet state is desynced and decryption fails.
     */
    fun resetSession(theirPublicKey: String, ourPublicKey: String, sharedSecret: ByteArray) {
        val sessionId = makeSessionId(ourPublicKey, theirPublicKey)
        sessions.remove(sessionId)
        RatchetLog.d(TAG, "🔄 Reset session: ${sessionId.take(16)}... — will recreate on next use")
    }

    // ========================================================================
    // ENCRYPTION (matches iOS _encrypt)
    // ========================================================================

    /**
     * Encrypt a message using Double Ratchet (matches iOS _encrypt)
     */
    fun encrypt(session: RatchetSession, plaintext: String): IOSRatchetMessage {
        RatchetLog.d(TAG, "🔒 Encrypt: session=${session.sessionId.take(8)}, send#${session.sendingMessageNumber}")

        // Refuse to encrypt on an all-zero sending chain, v2 or v3. Mirrors iOS
        // `_encrypt` (OSHI/DoubleRatchet.swift). After the 2026-09-09 fix no fresh
        // session has one; the only way to reach here is a session PERSISTED by a
        // build that predates the fix, which would otherwise send under the public
        // constant d663c614…. Throwing fails the send visibly — the caller stamps
        // the row failed and the next attempt rebuilds the session (now always v3)
        // — instead of silently leaking. [Audit 2026-09-09]
        if (session.sendingChainKey.size == 32 && session.sendingChainKey.all { it == 0.toByte() }) {
            RatchetLog.e(TAG, "🚨 Refusing to encrypt on an all-zero sending chain (v${session.sessionVersion})")
            throw IllegalStateException("uninitialized (all-zero) sending chain")
        }

        // Derive → encrypt → advance, atomically per session. iOS gets the same
        // guarantee from `sessionQueue.sync` (OSHI/DoubleRatchet.swift:170-174).
        return session.withSendKey(::deriveMessageKey, ::advanceChainKey) { messageNumber, messageKey, dhFlag ->
            // Create header matching iOS format
            val header = IOSRatchetHeader(
                publicKey = session.ourRatchetPublicKey,
                messageNumber = messageNumber,
                previousChainLength = session.previousSendingChainLength,
                isDHRatchet = dhFlag,
                timestamp = System.currentTimeMillis() / 1000.0 - APPLE_EPOCH_OFFSET_SECONDS,
                sessionVersion = session.sessionVersion,
                sessionId = session.sessionId,
                // Le mode et le chiffré voyagent sur CHAQUE en-tête d'une session
                // hybride: c'est ce qui les fait entrer dans l'AAD, et donc ce
                // qui rend une rétrogradation détectable plutôt que silencieuse.
                securityMode = session.securityMode,
                pqCiphertext = session.pqCiphertext
            )

            // Construct AAD matching iOS encodeHeaderForAAD
            val aad = encodeHeaderForAAD(header)

            // Encrypt with AES-GCM + AAD
            val ciphertext = cryptoManager.encryptAESGCMWithAAD(
                plaintext.toByteArray(Charsets.UTF_8),
                messageKey,
                aad
            )

            RatchetLog.d(TAG, "   ✅ Encrypted msg#$messageNumber")
            IOSRatchetMessage(header, ciphertext)
        }
    }

    // ========================================================================
    // DECRYPTION (matches iOS _decrypt)
    // ========================================================================

    /**
     * Decrypt an iOS DoubleRatchetMessage (matches iOS _decrypt)
     */
    fun decrypt(session: RatchetSession, message: IOSRatchetMessage): String =
        // Same per-session serialisation as [encrypt] — iOS `sessionQueue.sync`
        // (OSHI/DoubleRatchet.swift:213-217). A receive that interleaves with a send
        // clobbers the very counters the send just read.
        session.withLock { decryptLocked(session, message) }

    private fun decryptLocked(session: RatchetSession, message: IOSRatchetMessage): String {
        RatchetLog.d(TAG, "🔓 Decrypt: session=${session.sessionId.take(8)}, incoming msg#${message.header.messageNumber}")
        RatchetLog.d(TAG, "   recv#${session.receivingMessageNumber}")

        // Check session ID match (matches iOS)
        if (message.header.sessionId != session.sessionId) {
            RatchetLog.w(TAG, "   ⚠️ Session ID mismatch: msg=${message.header.sessionId.take(8)} vs session=${session.sessionId.take(8)}")
            throw IllegalStateException("Session ID mismatch")
        }

        if (message.header.sessionVersion != session.sessionVersion) {
            throw IllegalStateException("Session version mismatch")
        }

        // Check skipped messages first
        val hdrPubB64 = RatchetBase64.encodeToString(message.header.publicKey, RatchetBase64.NO_WRAP)
        val skipKey = "$hdrPubB64:${message.header.messageNumber}"
        session.takeSkippedKey(skipKey)?.let { skippedKey ->
            RatchetLog.d(TAG, "   🔑 Using skipped key for msg#${message.header.messageNumber}")
            val aad = encodeHeaderForAAD(message.header)
            return String(cryptoManager.decryptAESGCMWithAAD(message.ciphertext, skippedKey, aad), Charsets.UTF_8)
        }

        // Save state for rollback on failure
        val savedRootKey = session.rootKey.copyOf()
        val savedSendChain = session.sendingChainKey.copyOf()
        val savedRecvChain = session.receivingChainKey.copyOf()
        val savedSendNum = session.sendingMessageNumber
        val savedRecvNum = session.receivingMessageNumber
        val savedPrevLen = session.previousSendingChainLength
        val savedOurPriv = session.ourRatchetPrivateKey.copyOf()
        val savedOurPub = session.ourRatchetPublicKey.copyOf()
        val savedTheirPub = session.theirRatchetPublicKey?.copyOf()
        val savedSkipped = session.snapshotSkippedKeys()
        val savedSkippedTimes = session.snapshotSkippedKeyTimes()

        try {
            // CRITICAL: Match iOS first-message handling (lines 198-219)
            if (session.theirRatchetPublicKey == null) {
                // CV-010 — this branch is why the DH ratchet never turned.
                //
                // `performDHRatchet` runs only when the peer's key CHANGES, and a
                // side replaces its own key pair only INSIDE `performDHRatchet`.
                // Storing the first key without ratcheting meant neither side
                // ever rotated, neither ever saw a change, and the chain stayed
                // derivable from the long-term identity keys for the whole
                // session.
                //
                // v3 ratchets here, which it CAN because its own ratchet key is
                // derived from the shared secret, so the sender computed the same
                // DH. v2 keeps the old behaviour exactly — its key is random and
                // unknown to the sender, so ratcheting would derive a chain the
                // sender never used and break every existing conversation.
                if (session.sessionVersion >= RATCHETING_SESSION_VERSION) {
                    RatchetLog.d(TAG, "   🔄 First message from sender — DH ratchet (v3)")
                    performDHRatchet(session, message.header.publicKey)
                } else {
                    RatchetLog.d(TAG, "   📝 First message from sender, storing their key (no DH ratchet)")
                    session.theirRatchetPublicKey = message.header.publicKey
                }
            } else if (!session.theirRatchetPublicKey!!.contentEquals(message.header.publicKey)) {
                // Key changed - check if old message or DH ratchet needed.
                //
                // v2 ONLY: that comparison is sound only while the ratchet never
                // turns and numbering is monotonic for the life of the session.
                // Once it DOES turn, every new chain restarts at 0, so the first
                // message of a new chain looks "older" than the last of the
                // previous one and was rejected. A genuinely stale message is
                // still caught by the skipped-key lookup, which runs first.
                if (session.sessionVersion < RATCHETING_SESSION_VERSION &&
                    message.header.messageNumber < session.receivingMessageNumber) {
                    RatchetLog.w(TAG, "   ⚠️ Old message with old ratchet key")
                    throw ReplayedEnvelopeException("Old message")
                }
                // Bank the keys still owed on the OLD chain before moving on;
                // without this, anything from it that arrives after the ratchet —
                // routine on a mesh that reorders — is unrecoverable.
                val current = session.theirRatchetPublicKey
                if (session.sessionVersion >= RATCHETING_SESSION_VERSION && current != null &&
                    message.header.previousChainLength > session.receivingMessageNumber) {
                    skipMessageKeys(session, message.header.previousChainLength, current)
                }
                RatchetLog.d(TAG, "   🔄 Key changed, DH ratchet")
                performDHRatchet(session, message.header.publicKey)
            } else if (message.header.isDHRatchet) {
                RatchetLog.d(TAG, "   🔄 Flagged for ratchet")
                performDHRatchet(session, message.header.publicKey)
            }

            // Handle message gaps
            if (message.header.messageNumber > session.receivingMessageNumber) {
                val gap = message.header.messageNumber - session.receivingMessageNumber
                RatchetLog.d(TAG, "   ⏭️ Message gap: $gap message(s)")
                skipMessageKeys(session, message.header.messageNumber, message.header.publicKey)
            }

            // Decrypt in-order message
            if (message.header.messageNumber == session.receivingMessageNumber) {
                val messageKey = deriveMessageKey(session.receivingChainKey)
                val aad = encodeHeaderForAAD(message.header)
                val plaintext = String(
                    cryptoManager.decryptAESGCMWithAAD(message.ciphertext, messageKey, aad),
                    Charsets.UTF_8
                )

                session.receivingChainKey = advanceChainKey(session.receivingChainKey)
                session.receivingMessageNumber++

                RatchetLog.d(TAG, "   ✅ Decrypted in-order msg, recv#${session.receivingMessageNumber}")
                return plaintext
            } else {
                // Reachable only when messageNumber < receivingMessageNumber on a
                // chain we are already on: an envelope we have ALREADY read.
                throw ReplayedEnvelopeException("Invalid message number")
            }
        } catch (e: Exception) {
            // Rollback on failure
            RatchetLog.e(TAG, "   ❌ Decrypt failed: ${e.message}, rolling back state")
            session.rootKey = savedRootKey
            session.sendingChainKey = savedSendChain
            session.receivingChainKey = savedRecvChain
            session.sendingMessageNumber = savedSendNum
            session.receivingMessageNumber = savedRecvNum
            session.previousSendingChainLength = savedPrevLen
            session.ourRatchetPrivateKey = savedOurPriv
            session.ourRatchetPublicKey = savedOurPub
            session.theirRatchetPublicKey = savedTheirPub
            session.replaceSkippedKeys(savedSkipped, savedSkippedTimes)
            throw e
        }
    }

    // ========================================================================
    // DH RATCHET (matches iOS performDHRatchet)
    // ========================================================================

    private fun performDHRatchet(session: RatchetSession, newPublicKey: ByteArray) {
        // Step 1: DH with our current ratchet key + their new key
        val shared1 = cryptoManager.deriveSharedSecretWithKeys(
            session.ourRatchetPrivateKey, newPublicKey
        )
        val (rk1, recvChain) = kdfRootKey(session.rootKey, shared1)

        // Step 2: Generate new ephemeral key pair, DH with their new key
        val (newPriv, newPub) = cryptoManager.generateEphemeralKeyPair()
        val shared2 = cryptoManager.deriveSharedSecretWithKeys(newPriv, newPublicKey)
        val (rk2, sendChain) = kdfRootKey(rk1, shared2)

        session.previousSendingChainLength = session.sendingMessageNumber
        session.rootKey = rk2
        session.receivingChainKey = recvChain
        session.receivingMessageNumber = 0
        session.sendingChainKey = sendChain
        session.sendingMessageNumber = 0
        session.ourRatchetPrivateKey = newPriv
        session.ourRatchetPublicKey = newPub
        session.theirRatchetPublicKey = newPublicKey
        // Carried on the next header and cleared after that send — iOS sets
        // `needsDHRatchet` here (OSHI/DoubleRatchet.swift:370), emits it at :190 and
        // clears it at :203. Android hard-coded `false`, so the one case iOS's receiver
        // reads the flag for (a ratchet required while the key is unchanged) was mute.
        session.needsDHRatchet = true

        RatchetLog.d(TAG, "   ✅ DH ratchet complete")
    }

    // ========================================================================
    // SKIP MESSAGE KEYS (matches iOS skipMessageKeys)
    // ========================================================================

    private fun skipMessageKeys(session: RatchetSession, targetNumber: Int, chainPublicKey: ByteArray) {
        val gap = targetNumber - session.receivingMessageNumber
        if (gap > MAX_SKIP) {
            throw IllegalStateException("Too many skipped messages: $gap")
        }

        val chainPubB64 = RatchetBase64.encodeToString(chainPublicKey, RatchetBase64.NO_WRAP)

        while (session.receivingMessageNumber < targetNumber) {
            val skipKey = "$chainPubB64:${session.receivingMessageNumber}"
            val messageKey = deriveMessageKey(session.receivingChainKey)
            session.putSkippedKey(skipKey, messageKey)

            session.receivingChainKey = advanceChainKey(session.receivingChainKey)
            session.receivingMessageNumber++
        }

        RatchetLog.d(TAG, "   ✅ Skipped to msg#${session.receivingMessageNumber}, stored $gap key(s)")
    }

    // ========================================================================
    // KDF FUNCTIONS (matching iOS HKDF info strings EXACTLY)
    // ========================================================================

    /**
     * Derive message key: HKDF(chainKey, empty salt, "MessageKey", 32)
     * Matches iOS deriveMessageKey
     */
    private fun deriveMessageKey(chainKey: ByteArray): ByteArray {
        return cryptoManager.hkdfDerive(
            sharedSecret = chainKey,
            salt = ByteArray(0),
            info = INFO_MESSAGE_KEY,
            outputLength = 32
        )
    }

    /**
     * Advance chain key: HKDF(chainKey, empty salt, "ChainKey", 32)
     * Matches iOS advanceChainKey
     */
    private fun advanceChainKey(chainKey: ByteArray): ByteArray {
        return cryptoManager.hkdfDerive(
            sharedSecret = chainKey,
            salt = ByteArray(0),
            info = INFO_CHAIN_KEY,
            outputLength = 32
        )
    }

    /**
     * KDF for root key derivation: HKDF(dhOutput, rootKey as salt, "RootKey", 64)
     * Returns (new_root_key, chain_key) - matches iOS deriveRootKeys
     */
    /**
     * The responder's INITIAL ratchet private key, derived from the shared secret
     * so BOTH sides can compute its public half.
     *
     * This is the piece that was missing on both platforms. A Double Ratchet
     * bootstraps from a responder key the initiator already knows — in Signal the
     * signed pre-key. This path has no bundle (it is the offline mesh path), so
     * the key comes from the static X25519 secret both sides already share.
     *
     * Consequence, stated plainly: the initiator's FIRST message stays derivable
     * by anyone holding the identity keys, because this key is. From the
     * responder's first reply onward the ratchet uses random keys and that stops
     * being true.
     *
     * Byte-identical to `DoubleRatchetSession.derivedResponderRatchetKey` in
     * Swift; `RatchetV3VectorTest` pins the bytes.
     */
    internal fun derivedResponderRatchetPrivateKey(sharedSecret: ByteArray): ByteArray =
        Companion.derivedResponderRatchetPrivateKey(sharedSecret)

    private fun kdfRootKey(rootKey: ByteArray, dhOutput: ByteArray): Pair<ByteArray, ByteArray> {
        val derived = cryptoManager.hkdfDerive(
            sharedSecret = dhOutput,
            salt = rootKey,
            info = INFO_ROOT_KEY,
            outputLength = 64
        )
        return Pair(
            derived.sliceArray(0 until 32),
            derived.sliceArray(32 until 64)
        )
    }

    // ========================================================================
    // AAD CONSTRUCTION (matches iOS encodeHeaderForAAD exactly)
    // ========================================================================


    // ========================================================================
    // SESSION PERSISTENCE
    // ========================================================================
    //
    // __RATCHET_IS_SHARED_SOURCE_2026_10_28__ The STORE is a seam; the serialisation is
    // not. What used to be here — `getSecurePrefs`, `saveSessions(Context)`,
    // `loadSessions(Context)`, `resetAllSessions(Context)` — was a thin shell of Android
    // Keystore plumbing around `session.toJSON()` and `RatchetSession.fromJSON()`, which
    // were already pure. Those four now live in `DoubleRatchetAndroidStore.kt` as
    // extension functions with the SAME names and signatures, so every call site in the
    // app compiles unchanged, and they are written entirely in terms of the three
    // JVM-clean members below.
    //
    // The point is not tidiness. It is that this file no longer imports `android.*`, so
    // the desktop client compiles THIS ratchet rather than a second one that would have
    // to be proven equal to it. See `RatchetSeams.kt`.

    /**
     * Every session as one JSON object, keyed by session id — the exact string the
     * Android store writes under `sessions_v2`, and the one the desktop seals in its own
     * vault.
     *
     * Prunes expired skipped keys first, as the old `saveSessions` did: they are message
     * keys, so keeping them past their window is a growing file of live secrets.
     *
     * A ConcurrentModificationException here is NOT recoverable noise — it means the
     * write did not happen while the in-memory ratchet had already advanced, so the next
     * launch rewinds to a state whose message keys are already on the wire. The map is
     * snapshotted and each session locks itself in `toJSON()`, so it cannot happen; it is
     * rethrown rather than logged if it somehow does.
     */
    fun exportSessionsJson(): String {
        val jsonObj = JSONObject()
        val snapshot = HashMap(sessions)
        snapshot.forEach { (key, session) ->
            session.pruneSkippedKeys(SKIPPED_KEY_MAX_AGE_SECONDS)
            jsonObj.put(key, session.toJSON())
        }
        return jsonObj.toString()
    }

    /**
     * Merge persisted sessions back in. Returns how many loaded.
     *
     * One unreadable session costs that session, never the others: a single bad entry used
     * to be caught per-key here and still is, because the alternative is one corrupt record
     * silently throwing away every conversation's state.
     */
    fun importSessionsJson(json: String): Int {
        val jsonObj = JSONObject(json)
        var loaded = 0
        jsonObj.keys().forEach { key ->
            try {
                sessions[key] = RatchetSession.fromJSON(jsonObj.getJSONObject(key))
                loaded++
            } catch (e: Exception) {
                RatchetLog.w(TAG, "Failed to load session $key: ${e.message}")
            }
        }
        return loaded
    }

    /** Drop every in-memory session. Returns how many went. The DISK copies are the store's. */
    fun clearSessionsInMemory(): Int {
        val count = sessions.size
        sessions.clear()
        return count
    }
}
