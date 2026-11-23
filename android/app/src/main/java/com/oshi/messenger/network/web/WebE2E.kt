package com.oshi.messenger.network.web

import com.oshi.messenger.network.v2.OSHICryptoV2
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * __WEBSESSION_E2E_2026_09_23__ OSHI Web v2 ("OSHI-WS2"), the PHONE side.
 *
 * Spec: `ServerPatches/websession_e2e/CLIENT_SPEC.md` §2-§4. Reference implementations:
 * `test/phone_ref.js` (phone) and `oshi/files/oshi-websession-v2.js` (browser); byte vectors
 * `test/vectors.json`, reproduced by `WebE2EVectorsTest`.
 *
 * Pure JVM code (no android.*): X25519/HKDF through BouncyCastle via [OSHICryptoV2], HMAC and
 * AES-256-GCM through javax.crypto, base64 through java.util.
 *
 * What this buys: the browser and the phone agree on keys during the QR pairing. The QR secret
 * `s` never reaches the server, so the server cannot derive the keys and, after pairing, only
 * relays opaque `{type:"e2e"}` frames.
 */
object WebE2E {

    const val LABEL_TH = "OSHI-WS2/th"
    const val LABEL_B2P = "OSHI-WS2 b2p"
    const val LABEL_P2B = "OSHI-WS2 p2b"
    const val LABEL_CONFIRM = "OSHI-WS2 confirm"
    const val LABEL_SAS = "OSHI-WS2 sas"
    const val LABEL_PHONE_CONFIRM = "OSHI-WS2 phone-confirm"

    /** `|now - _ts|` bound for the FIRST frame of an epoch never seen before. */
    const val FRESH_MS = 5 * 60 * 1000L
    /** Epochs remembered per session (LRU). */
    const val MAX_EPOCHS = 64
    /** Server refuses a frame whose `ct` base64 exceeds 512 KiB. */
    const val MAX_CT_B64 = 512 * 1024
    /** Plaintext budget per frame, well below the ct limit (base64 = 4/3 + tag). */
    const val MAX_PLAINTEXT = 360 * 1024
    /** Media chunk size (raw bytes) for `media_chunk`, and the page's `send_file_chunk`. */
    const val MEDIA_CHUNK = 192 * 1024
    /** Inline media rule for v2 (`mediaData` only up to 256 KiB, else `mediaUrl`). */
    const val INLINE_MEDIA_LIMIT_V2 = 256 * 1024

    private val rng = SecureRandom()

    // ------------------------------------------------------------------ QR (§2.1)

    sealed interface Qr {
        /** No `v=2`: an old page, or a browser without WebCrypto. */
        data class V1(val token: String) : Qr
        data class V2(val token: String, val browserKey: ByteArray, val secret: ByteArray) : Qr
    }

    /**
     * Parse `oshi://web-auth?token=…&v=2&k=…&s=…`. Returns null when the string is not an OSHI Web
     * QR, or when it claims v2 with keys of the wrong length (the spec's "reject the QR").
     */
    fun parseQr(qr: String): Qr? {
        if (!qr.startsWith("oshi://web-auth")) return null
        val query = runCatching { URI(qr).rawQuery }.getOrNull() ?: return null
        val params = HashMap<String, String>()
        for (part in query.split('&')) {
            val i = part.indexOf('=')
            if (i <= 0) continue
            val k = URLDecoder.decode(part.substring(0, i), "UTF-8")
            val v = URLDecoder.decode(part.substring(i + 1), "UTF-8")
            params.putIfAbsent(k, v)
        }
        val token = params["token"]?.takeIf { it.isNotBlank() } ?: return null
        if (params["v"] != "2") return Qr.V1(token)
        val k = unb64url(params["k"]) ?: return null
        val s = unb64url(params["s"]) ?: return null
        if (k.size != 32 || s.size != 16) return null
        return Qr.V2(token, k, s)
    }

    /** base64url (or standard) with padding restored; null on garbage. */
    fun unb64url(s: String?): ByteArray? {
        if (s.isNullOrEmpty()) return null
        var t = s.replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return runCatching { Base64.getDecoder().decode(t) }.getOrNull()
    }

    // ------------------------------------------------------------------ keys (§3)

    class Keys(
        val th: ByteArray,
        val b2p: ByteArray,
        val p2b: ByteArray,
        val confirmKey: ByteArray,
        val confirm: ByteArray,
        val sas: String,
    )

    fun transcriptHash(token: String, browserPub: ByteArray, phonePub: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(LABEL_TH.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(token.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(browserPub)
        md.update(phonePub)
        return md.digest()
    }

    private fun hkdf(shared: ByteArray, salt: ByteArray, label: String, th: ByteArray, len: Int) =
        OSHICryptoV2.hkdf(shared, salt, label.toByteArray(Charsets.UTF_8) + th, len)

    fun deriveKeys(shared: ByteArray, qrSecret: ByteArray, token: String, browserPub: ByteArray, phonePub: ByteArray): Keys {
        val th = transcriptHash(token, browserPub, phonePub)
        val confirmKey = hkdf(shared, qrSecret, LABEL_CONFIRM, th, 32)
        val sasBytes = hkdf(shared, qrSecret, LABEL_SAS, th, 4)
        val v = ((sasBytes[0].toLong() and 0xFF) shl 24) or ((sasBytes[1].toLong() and 0xFF) shl 16) or
            ((sasBytes[2].toLong() and 0xFF) shl 8) or (sasBytes[3].toLong() and 0xFF)
        return Keys(
            th = th,
            b2p = hkdf(shared, qrSecret, LABEL_B2P, th, 32),
            p2b = hkdf(shared, qrSecret, LABEL_P2B, th, 32),
            confirmKey = confirmKey,
            confirm = OSHICryptoV2.hmac(confirmKey, LABEL_PHONE_CONFIRM.toByteArray(Charsets.UTF_8)),
            sas = String.format(java.util.Locale.ROOT, "%06d", v % 1_000_000L),
        )
    }

    /** The phone's half of a pairing: fresh ephemeral key, derived keys, the proof for the page. */
    class Pairing(
        val token: String,
        val phonePub: ByteArray,
        val keys: Keys,
    ) {
        val phoneKeyB64: String get() = Base64.getEncoder().encodeToString(phonePub)
        val confirmB64: String get() = Base64.getEncoder().encodeToString(keys.confirm)
        val sas: String get() = keys.sas
    }

    /**
     * Pair from a v2 QR. [phonePriv] is for the vector test only; production always draws a FRESH
     * key (never the account identity key) and forgets the private half right here.
     */
    fun pair(qr: Qr.V2, phonePriv: ByteArray? = null): Pairing {
        val priv = phonePriv?.copyOf() ?: OSHICryptoV2.generateX25519().priv
        try {
            val pub = OSHICryptoV2.x25519PubFromPriv(priv)
            val shared = OSHICryptoV2.dh(priv, qr.browserKey)
            require(!shared.all { it == 0.toByte() }) { "low-order browser key" }
            val keys = deriveKeys(shared, qr.secret, qr.token, qr.browserKey, pub)
            shared.fill(0)
            return Pairing(qr.token, pub, keys)
        } finally {
            priv.fill(0)
        }
    }

    // ------------------------------------------------------------------ frames (§3)

    fun aad(sid: String, dir: String, ep: String, seq: Long): ByteArray =
        "OSHI-WS2|$sid|$dir|$ep|$seq".toByteArray(Charsets.UTF_8)

    fun nonce(ep: ByteArray, seq: Long): ByteArray = ep + OSHICryptoV2.u32be(seq)

    class FrameException(message: String) : Exception(message)

    /**
     * One paired browser, as seen from the phone: seals `p2b`, opens `b2p`.
     *
     * The send epoch is chosen once per instance (the caller creates a new channel per socket
     * connection) and `seq` counts from 1 inside it. Receive state keeps, per browser epoch, the
     * highest seq accepted (replay refusal) — at most [MAX_EPOCHS] epochs, least recently used first
     * out.
     */
    class Channel(
        private val sid: String,
        private val sendKey: ByteArray,
        private val recvKey: ByteArray,
        private val now: () -> Long = System::currentTimeMillis,
        epoch: ByteArray? = null,
    ) {
        private var epBytes: ByteArray = epoch?.copyOf() ?: ByteArray(8).also { rng.nextBytes(it) }
        private var ep: String = Base64.getEncoder().encodeToString(epBytes)
        private var seq = 0L
        private val recv = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > MAX_EPOCHS
        }

        val epoch: String get() = ep

        @Synchronized
        private fun nextSeq(): Pair<String, Long> {
            if (seq >= 0xFFFF_FFFFL) {
                epBytes = ByteArray(8).also { rng.nextBytes(it) }
                ep = Base64.getEncoder().encodeToString(epBytes)
                seq = 0
            }
            seq += 1
            return ep to seq
        }

        /** Seal the exact plaintext bytes (UTF-8 JSON of the inner object, `_ts` included). */
        fun sealPlaintext(plaintext: ByteArray): JSONObject {
            val (epS, s) = nextSeq()
            val epRaw = Base64.getDecoder().decode(epS)
            val ct = OSHICryptoV2.aesGcmSeal(sendKey, nonce(epRaw, s), plaintext, aad(sid, "p2b", epS, s))
            return JSONObject()
                .put("type", "e2e").put("v", 2).put("sid", sid).put("dir", "p2b")
                .put("ep", epS).put("seq", s)
                .put("ct", Base64.getEncoder().encodeToString(ct))
        }

        /** Seal `{t: type, …fields, _ts}`. [inner] must already carry `t`; `_ts` is added here. */
        fun seal(inner: JSONObject, tsMs: Long = now()): JSONObject {
            inner.put("_ts", tsMs)
            return sealPlaintext(inner.toString().toByteArray(Charsets.UTF_8))
        }

        /** Open a `b2p` frame. Throws [FrameException] on anything that must be dropped unseen. */
        @Synchronized
        fun open(frame: JSONObject): JSONObject {
            if (frame.optString("type") != "e2e" || frame.optInt("v") != 2) throw FrameException("bad-frame")
            if (frame.optString("sid") != sid) throw FrameException("wrong-sid")
            if (frame.optString("dir") != "b2p") throw FrameException("wrong-dir")
            val epS = frame.optString("ep")
            val s = frame.optLong("seq", -1)
            if (s < 1 || s > 0xFFFF_FFFFL) throw FrameException("bad-seq")
            val epRaw = runCatching { Base64.getDecoder().decode(epS) }.getOrNull()
            if (epRaw == null || epRaw.size != 8) throw FrameException("bad-epoch")
            val last = recv[epS]
            if (last != null && s <= last) throw FrameException("replay")
            val raw = runCatching { Base64.getDecoder().decode(frame.optString("ct")) }.getOrNull()
                ?: throw FrameException("bad-ct")
            val pt = try {
                OSHICryptoV2.aesGcmOpen(recvKey, nonce(epRaw, s), raw, aad(sid, "b2p", epS, s))
            } catch (e: Exception) { throw FrameException("auth-failed") }
            val inner = try { JSONObject(String(pt, Charsets.UTF_8)) } catch (e: Exception) { throw FrameException("bad-json") }
            if (last == null) {
                val ts = inner.optLong("_ts", Long.MIN_VALUE)
                if (ts == Long.MIN_VALUE || kotlin.math.abs(now() - ts) > FRESH_MS) throw FrameException("stale")
            }
            recv[epS] = s
            return inner
        }
    }

    // ------------------------------------------------------------------ v1 → v2 inner types (§4.4)

    /**
     * The v1 phone→page `type` names that v2 renames. Every other type keeps its name; the field
     * shapes are the v1 ones, so the existing builders are reused unchanged.
     */
    private val V1_TO_V2 = mapOf(
        "sync_conversations" to "conversations_sync",
        "sync_messages" to "messages_sync",
        "sync_groups" to "groups_sync",
        "sync_group_messages" to "group_messages_sync",
    )

    fun innerTypeFor(v1Type: String): String = V1_TO_V2[v1Type] ?: v1Type

    /** `{type:X, …}` (a v1 dict) → `{t:innerTypeFor(X), …}` (a v2 inner object, no `_ts` yet). */
    fun toInner(v1: JSONObject): JSONObject {
        val out = JSONObject()
        out.put("t", innerTypeFor(v1.optString("type")))
        val it = v1.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (k != "type") out.put(k, v1.get(k))
        }
        return out
    }

    /**
     * Split one list-carrying message (`messages_sync`, `group_messages_sync`) into several whose
     * serialised size stays under [maxBytes] each (the frame limit, §4.4 "Sizes"). A single item
     * bigger than the budget still goes out alone; the inline-media rule keeps that rare.
     */
    fun splitList(base: JSONObject, listKey: String, maxBytes: Int = MAX_PLAINTEXT): List<JSONObject> {
        val arr = base.optJSONArray(listKey) ?: return listOf(base)
        val baseSize = JSONObject(base.toString()).apply { remove(listKey) }.toString().length + 64
        if (base.toString().length <= maxBytes) return listOf(base)
        val out = ArrayList<JSONObject>()
        var cur = org.json.JSONArray()
        var curSize = baseSize
        for (i in 0 until arr.length()) {
            val item = arr.get(i)
            val sz = item.toString().length + 1
            if (cur.length() > 0 && curSize + sz > maxBytes) {
                out += copyWith(base, listKey, cur)
                cur = org.json.JSONArray(); curSize = baseSize
            }
            cur.put(item); curSize += sz
        }
        if (cur.length() > 0 || out.isEmpty()) out += copyWith(base, listKey, cur)
        return out
    }

    private fun copyWith(base: JSONObject, listKey: String, list: org.json.JSONArray): JSONObject {
        val o = JSONObject()
        val it = base.keys()
        while (it.hasNext()) { val k = it.next(); if (k != listKey) o.put(k, base.get(k)) }
        o.put(listKey, list)
        return o
    }

    /** `media_chunk` bodies for one attachment: 192 KiB raw per chunk, `off` = byte offset. */
    fun mediaChunks(messageId: String, scope: String, mime: String?, fileName: String?, data: ByteArray): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var off = 0
        do {
            val end = minOf(off + MEDIA_CHUNK, data.size)
            val last = end >= data.size
            out += JSONObject().apply {
                put("t", "media_chunk")
                put("messageId", messageId); put("scope", scope)
                put("off", off); put("last", last)
                put("mime", mime ?: "application/octet-stream")
                if (off == 0 && !fileName.isNullOrEmpty()) put("fileName", fileName)
                put("data", Base64.getEncoder().encodeToString(data.copyOfRange(off, end)))
            }
            off = end
        } while (!last)
        return out
    }

    /**
     * Reassembles the page's `send_file_chunk` stream (§4.4) per `uploadId`. Chunks must arrive in
     * order (`off` == bytes received so far); anything else abandons the upload. Bounded: at most
     * [maxBytes] per upload and [maxUploads] concurrent uploads.
     */
    class UploadAssembler(private val maxBytes: Int = 64 * 1024 * 1024, private val maxUploads: Int = 8) {
        class Done(
            val uploadId: String, val recipient: String, val kind: String, val fileName: String,
            val mime: String, val duration: String, val bytes: ByteArray,
        )

        private class Partial(val buf: java.io.ByteArrayOutputStream, var got: Long)
        private val partials = LinkedHashMap<String, Partial>()

        /** Feed one chunk; returns the finished file on the `last` chunk, else null. */
        @Synchronized
        fun feed(c: JSONObject): Done? {
            val id = c.optString("uploadId").takeIf { it.isNotEmpty() } ?: return null
            val off = c.optLong("off", -1)
            val data = runCatching { Base64.getDecoder().decode(c.optString("data")) }.getOrNull() ?: run {
                partials.remove(id); return null
            }
            val size = c.optLong("size", -1)
            if (size < 0 || size > maxBytes) { partials.remove(id); return null }
            var p = partials[id]
            if (p == null) {
                if (off != 0L) return null
                while (partials.size >= maxUploads) partials.remove(partials.keys.first())
                p = Partial(java.io.ByteArrayOutputStream(), 0).also { partials[id] = it }
            }
            if (off != p.got || p.got + data.size > size) { partials.remove(id); return null }
            p.buf.write(data); p.got += data.size
            if (!c.optBoolean("last", false)) return null
            partials.remove(id)
            if (p.got != size) return null
            return Done(
                uploadId = id,
                recipient = c.optString("recipientPublicKey"),
                kind = c.optString("kind", "file"),
                fileName = c.optString("fileName"),
                mime = c.optString("mime", "application/octet-stream"),
                duration = c.optString("duration"),
                bytes = p.buf.toByteArray(),
            )
        }
    }
}
