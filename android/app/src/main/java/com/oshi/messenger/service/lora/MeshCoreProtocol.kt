package com.oshi.messenger.service.lora

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * OSHI messages over a MeshCore radio — Kotlin twin of the codec half of OSHI/MeshCoreRadio.swift,
 * byte-for-byte. Pure JVM (no android.*), so the desktop app carries an identical copy.
 *
 * A LoRa radio listens to one network at a time, and MeshCore is not Meshtastic. So OSHI rides MeshCore
 * the way oshi-meshcore-bridge does:
 *
 *   OMP DATA frame (OshiMeshProtocol.kt)     the same frames as on Meshtastic, origin = our id
 *     → "OB" envelope, split to ≤160 B        [OshiBridgeWire] == oshi_bridge/wire.py
 *     → MeshCore group-channel datagram       CMD_SEND_CHANNEL_DATA, data type 0xFF4F,
 *                                             channel "#oshi-bridge" (secret = SHA-256(name)[0:16])
 *
 * Companion protocol: MeshCore examples/companion_radio/MyMesh.cpp (BLE Nordic UART, one frame per
 * write/notify; USB serial / TCP wrap each frame in '<'/'>' + LE16 length). Requests are strictly one
 * at a time; PUSH codes ≥ 0x80 are unsolicited.
 */
object MeshCore {
    const val SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    const val RX_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"   // app → radio (write)
    const val TX_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"   // radio → app (notify)

    const val CMD_APP_START = 1
    const val CMD_SEND_TXT_MSG = 2
    const val CMD_SEND_CHANNEL_TXT_MSG = 3
    const val CMD_GET_CONTACTS = 4
    const val CMD_SEND_SELF_ADVERT = 7
    /** Same layout as a contact frame (RESP_CONTACT / PUSH_NEW_ADVERT) with this code: adds or updates the entry. */
    const val CMD_ADD_UPDATE_CONTACT = 9
    const val CMD_SYNC_NEXT_MESSAGE = 10
    const val CMD_GET_CONTACT_BY_KEY = 30
    const val CMD_GET_CHANNEL = 31
    const val CMD_SET_CHANNEL = 32
    const val CMD_SEND_CHANNEL_DATA = 62

    const val RESP_OK = 0
    const val RESP_ERR = 1
    const val RESP_CONTACTS_START = 2
    const val RESP_CONTACT = 3
    const val RESP_END_OF_CONTACTS = 4
    const val RESP_SELF_INFO = 5
    const val RESP_SENT = 6
    /** Contact / channel text, app_target_ver < 3 (no SNR header). */
    const val RESP_CONTACT_MSG_RECV = 7
    const val RESP_CHANNEL_MSG_RECV = 8
    const val RESP_NO_MORE_MESSAGES = 10
    /** Contact / channel text, app_target_ver >= 3: code, snr×4, 0, 0, then the same body as 7 / 8. */
    const val RESP_CONTACT_MSG_RECV_V3 = 16
    const val RESP_CHANNEL_MSG_RECV_V3 = 17
    const val RESP_CHANNEL_INFO = 18
    const val RESP_CHANNEL_DATA_RECV = 27
    const val PUSH_ADVERT = 0x80
    const val PUSH_SEND_CONFIRMED = 0x82
    const val PUSH_MSG_WAITING = 0x83
    const val PUSH_NEW_ADVERT = 0x8A
    const val PUSH_CONTACT_DELETED = 0x8F

    const val TXT_TYPE_PLAIN = 0
    const val TXT_TYPE_SIGNED_PLAIN = 2

    const val ADV_TYPE_CHAT = 1
    const val ADV_TYPE_REPEATER = 2
    const val ADV_TYPE_ROOM = 3

    const val PUB_KEY_SIZE = 32
    const val PUB_KEY_PREFIX = 6
    const val MAX_PATH_SIZE = 64
    /** BaseChatMesh MAX_TEXT_LEN: a DM's text, or a channel's "<sender name>: " + text, in bytes. */
    const val MAX_TEXT_BYTES = 160
    /** What MeshCore clients recommend per message, in characters (MAX_TEXT_LEN less room for UTF-8). */
    const val RECOMMENDED_TEXT_CHARS = 133

    /** The channel every companion firmware pre-configures in slot 0: "Public", PUBLIC_GROUP_PSK. */
    const val PUBLIC_CHANNEL_NAME = "Public"
    val PUBLIC_CHANNEL_SECRET: ByteArray = byteArrayOf(
        0x8b.toByte(), 0x33, 0x87.toByte(), 0xe9.toByte(), 0xc5.toByte(), 0xcd.toByte(), 0xea.toByte(), 0x6a,
        0xc9.toByte(), 0xe5.toByte(), 0xed.toByte(), 0xba.toByte(), 0xa1.toByte(), 0x15, 0xcd.toByte(), 0x72,
    )

    /** MAX_FRAME_SIZE (176) - 9 in the current firmware. */
    const val MAX_CHANNEL_DATA = 167
    const val CHANNEL_SLOTS = 8
    const val PATH_FLOOD = 0xFF

    fun appStart(appName: String = "OSHI"): ByteArray =
        byteArrayOf(CMD_APP_START.toByte()) + ByteArray(7) + appName.toByteArray(Charsets.UTF_8)

    fun getChannel(idx: Int): ByteArray = byteArrayOf(CMD_GET_CHANNEL.toByte(), idx.toByte())

    /** idx, name (32, NUL-padded, ≤31 bytes), secret (16). The firmware refuses a 32-byte secret. */
    fun setChannel(idx: Int, name: String, secret: ByteArray): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8).let { it.copyOfRange(0, minOf(31, it.size)) }
        return byteArrayOf(CMD_SET_CHANNEL.toByte(), idx.toByte()) + n + ByteArray(32 - n.size) +
            secret.copyOfRange(0, minOf(16, secret.size))
    }

    fun sendChannelData(idx: Int, dataType: Int, payload: ByteArray): ByteArray =
        byteArrayOf(
            CMD_SEND_CHANNEL_DATA.toByte(), idx.toByte(), PATH_FLOOD.toByte(),
            (dataType and 0xFF).toByte(), ((dataType shr 8) and 0xFF).toByte(),
        ) + payload

    val syncNextMessage: ByteArray get() = byteArrayOf(CMD_SYNC_NEXT_MESSAGE.toByte())

    /**
     * CMD_SEND_SELF_ADVERT: announce this radio (name + key) so MeshCore users can add it and write to it.
     * [flood] = 1 relays it through repeaters; 0 is heard only by radios in direct range. Answer OK / ERR.
     */
    fun sendSelfAdvert(flood: Boolean = true): ByteArray =
        byteArrayOf(CMD_SEND_SELF_ADVERT.toByte(), if (flood) 1 else 0)

    /**
     * CMD_ADD_UPDATE_CONTACT from a contact frame the radio pushed (PUSH_NEW_ADVERT when the owner turned
     * auto-add off): the firmware reads it with the same layout (MyMesh.cpp updateContactFromFrame), so the
     * frame is replayed with the command code. Without this the radio answers a DM to that key NOT_FOUND.
     */
    fun addContactFrom(contactFrame: ByteArray): ByteArray? {
        if (contactFrame.size < CONTACT_FRAME_BYTES) return null
        return contactFrame.copyOf().also { it[0] = CMD_ADD_UPDATE_CONTACT.toByte() }
    }

    private fun le32(v: Long): ByteArray =
        byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun u32(f: ByteArray, at: Int): Long =
        (f[at].toLong() and 0xFF) or ((f[at + 1].toLong() and 0xFF) shl 8) or
            ((f[at + 2].toLong() and 0xFF) shl 16) or ((f[at + 3].toLong() and 0xFF) shl 24)

    private fun i32(f: ByteArray, at: Int): Int = u32(f, at).toInt()

    /** NUL-terminated (or field-filling) UTF-8 inside [from, from+len). */
    private fun cString(f: ByteArray, from: Int, len: Int): String {
        var end = from
        val limit = minOf(f.size, from + len)
        while (end < limit && f[end].toInt() != 0) end++
        return String(f, from, end - from, Charsets.UTF_8)
    }

    /**
     * CMD_SEND_TXT_MSG: code, txt_type, attempt (0-3, the app retries), sender timestamp (LE32, seconds),
     * recipient public-key prefix (6), text. The firmware answers RESP_SENT with the ACK tag to wait for, or
     * ERR (NOT_FOUND when the prefix is not in its contact table).
     */
    fun sendTextMessage(attempt: Int, timestampSec: Long, pubKeyPrefix: ByteArray, text: String): ByteArray =
        byteArrayOf(CMD_SEND_TXT_MSG.toByte(), TXT_TYPE_PLAIN.toByte(), (attempt and 3).toByte()) +
            le32(timestampSec) + pubKeyPrefix.copyOf(PUB_KEY_PREFIX) + text.toByteArray(Charsets.UTF_8)

    /** CMD_SEND_CHANNEL_TXT_MSG: code, txt_type (PLAIN), channel idx, timestamp (LE32), text. Answer OK / ERR. */
    fun sendChannelText(idx: Int, timestampSec: Long, text: String): ByteArray =
        byteArrayOf(CMD_SEND_CHANNEL_TXT_MSG.toByte(), TXT_TYPE_PLAIN.toByte(), idx.toByte()) +
            le32(timestampSec) + text.toByteArray(Charsets.UTF_8)

    /** CMD_GET_CONTACTS, optionally only contacts modified after [since] (radio clock, seconds). */
    fun getContacts(since: Long? = null): ByteArray =
        if (since == null) byteArrayOf(CMD_GET_CONTACTS.toByte()) else byteArrayOf(CMD_GET_CONTACTS.toByte()) + le32(since)

    fun getContactByKey(pubKey: ByteArray): ByteArray =
        byteArrayOf(CMD_GET_CONTACT_BY_KEY.toByte()) + pubKey.copyOf(PUB_KEY_SIZE)

    /** One entry of the radio's contact table (MyMesh.cpp writeContactRespFrame). */
    data class Contact(
        val publicKey: ByteArray,
        val type: Int,
        val flags: Int,
        val name: String,
        /** The contact's own advert clock, seconds. */
        val lastAdvert: Long,
        /** The radio's clock when the entry last changed, seconds. */
        val lastMod: Long,
    ) {
        val publicKeyHex: String get() = hex(publicKey)
        fun hasPrefix(prefix: ByteArray): Boolean =
            prefix.isNotEmpty() && prefix.size <= publicKey.size && prefix.indices.all { publicKey[it] == prefix[it] }
        override fun equals(other: Any?) = other is Contact && publicKey.contentEquals(other.publicKey) &&
            type == other.type && flags == other.flags && name == other.name &&
            lastAdvert == other.lastAdvert && lastMod == other.lastMod
        override fun hashCode() = publicKey.contentHashCode() * 31 + name.hashCode()
    }

    /** Frame size of writeContactRespFrame: code, key 32, type, flags, path len, path 64, name 32, 4 × u32. */
    const val CONTACT_FRAME_BYTES = 1 + PUB_KEY_SIZE + 3 + MAX_PATH_SIZE + 32 + 16

    /** RESP_CODE_CONTACT (3) and PUSH_CODE_NEW_ADVERT (0x8A) share one layout. */
    fun parseContact(f: ByteArray): Contact? {
        if (f.size < CONTACT_FRAME_BYTES) return null
        val code = f[0].toInt() and 0xFF
        if (code != RESP_CONTACT && code != PUSH_NEW_ADVERT) return null
        var i = 1
        val key = f.copyOfRange(i, i + PUB_KEY_SIZE); i += PUB_KEY_SIZE
        val type = f[i++].toInt() and 0xFF
        val flags = f[i++].toInt() and 0xFF
        i++                                   // out_path_len
        i += MAX_PATH_SIZE                    // out_path
        val name = cString(f, i, 32); i += 32
        val lastAdvert = u32(f, i); i += 4
        i += 8                                // gps lat, lon
        val lastMod = u32(f, i)
        return Contact(key, type, flags, name, lastAdvert, lastMod)
    }

    data class DirectText(
        val senderPrefix: ByteArray,
        val pathLen: Int,
        val txtType: Int,
        val senderTimestamp: Long,
        val text: String,
    ) {
        override fun equals(other: Any?) = other is DirectText && senderPrefix.contentEquals(other.senderPrefix) &&
            pathLen == other.pathLen && txtType == other.txtType && senderTimestamp == other.senderTimestamp && text == other.text
        override fun hashCode() = senderPrefix.contentHashCode() * 31 + text.hashCode()
    }

    /**
     * RESP_CODE_CONTACT_MSG_RECV (7): code, sender prefix (6), path len, txt_type, timestamp (LE32), [4-byte
     * author prefix when txt_type = SIGNED_PLAIN], text. V3 (16) inserts snr×4, 0, 0 after the code.
     */
    fun parseDirectText(f: ByteArray): DirectText? {
        if (f.isEmpty()) return null
        val start = when (f[0].toInt() and 0xFF) {
            RESP_CONTACT_MSG_RECV -> 1
            RESP_CONTACT_MSG_RECV_V3 -> 4
            else -> return null
        }
        if (f.size < start + PUB_KEY_PREFIX + 6) return null
        var i = start
        val prefix = f.copyOfRange(i, i + PUB_KEY_PREFIX); i += PUB_KEY_PREFIX
        val pathLen = f[i++].toInt() and 0xFF
        val txtType = f[i++].toInt() and 0xFF
        val ts = u32(f, i); i += 4
        if (txtType == TXT_TYPE_SIGNED_PLAIN) i += 4
        if (i > f.size) return null
        return DirectText(prefix, pathLen, txtType, ts, String(f, i, f.size - i, Charsets.UTF_8))
    }

    data class ChannelText(val channelIdx: Int, val pathLen: Int, val txtType: Int, val senderTimestamp: Long, val text: String)

    /** RESP_CODE_CHANNEL_MSG_RECV (8): code, channel idx, path len, txt_type, timestamp (LE32), text. V3 (17) as above. */
    fun parseChannelText(f: ByteArray): ChannelText? {
        if (f.isEmpty()) return null
        val start = when (f[0].toInt() and 0xFF) {
            RESP_CHANNEL_MSG_RECV -> 1
            RESP_CHANNEL_MSG_RECV_V3 -> 4
            else -> return null
        }
        if (f.size < start + 7) return null
        val i = start
        return ChannelText(
            f[i].toInt() and 0xFF, f[i + 1].toInt() and 0xFF, f[i + 2].toInt() and 0xFF, u32(f, i + 3),
            String(f, i + 7, f.size - (i + 7), Charsets.UTF_8),
        )
    }

    /**
     * A channel message is written by BaseChatMesh::sendGroupMessage as "<sender name>: <text>"; the sender
     * is only that name, chosen by whoever sent it (unauthenticated). Null name when the text has no such prefix.
     */
    fun splitChannelSender(text: String): Pair<String?, String> {
        val at = text.indexOf(": ")
        if (at <= 0 || at > 32) return null to text
        return text.substring(0, at) to text.substring(at + 2)
    }

    /** RESP_CODE_SENT: code, 1 = flood / 0 = direct, expected ACK (LE32), estimated timeout ms (LE32). */
    data class Sent(val flood: Boolean, val ackTag: Long, val estTimeoutMs: Long)

    fun parseSent(f: ByteArray): Sent? {
        if (f.size < 10 || (f[0].toInt() and 0xFF) != RESP_SENT) return null
        return Sent(f[1].toInt() != 0, u32(f, 2), u32(f, 6))
    }

    /** PUSH_CODE_SEND_CONFIRMED: code, ACK tag (LE32), round trip ms (LE32). The tag of a RESP_SENT. */
    fun parseSendConfirmed(f: ByteArray): Long? {
        if (f.size < 5 || (f[0].toInt() and 0xFF) != PUSH_SEND_CONFIRMED) return null
        return u32(f, 1)
    }

    /** PUSH_CODE_ADVERT / PUSH_CODE_CONTACT_DELETED: code, public key (32). */
    fun parsePushedKey(f: ByteArray): ByteArray? {
        if (f.size < 1 + PUB_KEY_SIZE) return null
        return f.copyOfRange(1, 1 + PUB_KEY_SIZE)
    }

    /** The node name at the end of RESP_CODE_SELF_INFO (after radio params, offset 58). */
    fun selfName(f: ByteArray): String? {
        if (f.size < 58 || (f[0].toInt() and 0xFF) != RESP_SELF_INFO) return null
        return cString(f, 58, f.size - 58).ifEmpty { null }
    }

    /**
     * A hashtag channel's secret: SHA-256 of its name WITH the '#', first 16 bytes — the rule MeshCore
     * clients use and the one #oshi-bridge already follows. Case-sensitive: "#Paris" and "#paris" differ.
     */
    fun hashtagSecret(name: String): ByteArray {
        val n = if (name.startsWith("#")) name else "#$name"
        return MessageDigest.getInstance("SHA-256").digest(n.toByteArray(Charsets.UTF_8)).copyOfRange(0, 16)
    }

    /** A channel anyone can join knowing only its name: the stock Public channel, or a hashtag channel. */
    fun isPublicChannel(name: String, secret: ByteArray): Boolean =
        secret.contentEquals(PUBLIC_CHANNEL_SECRET) || (name.startsWith("#") && secret.contentEquals(hashtagSecret(name)))

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun unhex(s: String): ByteArray? {
        if (s.length % 2 != 0 || s.any { Character.digit(it, 16) < 0 }) return null
        return ByteArray(s.length / 2) { ((Character.digit(s[2 * it], 16) shl 4) or Character.digit(s[2 * it + 1], 16)).toByte() }
    }

    /**
     * A private channel's 16-byte secret as people paste it: 32 hex characters, or base64 (standard or URL-safe,
     * padded or not). Null for anything that is not exactly 16 bytes.
     */
    fun parseSecret(input: String): ByteArray? {
        val s = input.trim().replace(" ", "")
        if (s.length == 32) unhex(s)?.let { return it }
        val b = runCatching { java.util.Base64.getDecoder().decode(s.replace('-', '+').replace('_', '/').let { t ->
            t.trimEnd('=').let { u -> u + "=".repeat((4 - u.length % 4) % 4) } }) }.getOrNull()
        return b?.takeIf { it.size == 16 }
    }

    /** RESP_CODE_SELF_INFO: code, adv type, tx power, max tx power, public key (32), ... */
    fun selfPublicKey(f: ByteArray): ByteArray? {
        if (f.size < 36 || (f[0].toInt() and 0xFF) != RESP_SELF_INFO) return null
        return f.copyOfRange(4, 36)
    }

    data class ChannelInfo(val idx: Int, val name: String, val secret: ByteArray) {
        override fun equals(other: Any?) =
            other is ChannelInfo && idx == other.idx && name == other.name && secret.contentEquals(other.secret)
        override fun hashCode() = (idx * 31 + name.hashCode()) * 31 + secret.contentHashCode()
    }

    /** RESP_CODE_CHANNEL_INFO: code, idx, name (32, NUL-padded), secret (16). */
    fun parseChannelInfo(f: ByteArray): ChannelInfo? {
        if (f.size < 50 || (f[0].toInt() and 0xFF) != RESP_CHANNEL_INFO) return null
        var end = 2
        while (end < 34 && f[end].toInt() != 0) end++
        return ChannelInfo(f[1].toInt() and 0xFF, String(f, 2, end - 2, Charsets.UTF_8), f.copyOfRange(34, 50))
    }

    data class ChannelData(val channelIdx: Int, val dataType: Int, val payload: ByteArray) {
        override fun equals(other: Any?) = other is ChannelData && channelIdx == other.channelIdx &&
            dataType == other.dataType && payload.contentEquals(other.payload)
        override fun hashCode() = (channelIdx * 31 + dataType) * 31 + payload.contentHashCode()
    }

    /** RESP_CODE_CHANNEL_DATA_RECV: code, snr×4, 0, 0, channel idx, path len, data type (LE16), len, data. */
    fun parseChannelData(f: ByteArray): ChannelData? {
        if (f.size < 9 || (f[0].toInt() and 0xFF) != RESP_CHANNEL_DATA_RECV) return null
        val len = f[8].toInt() and 0xFF
        if (f.size < 9 + len) return null
        return ChannelData(
            f[4].toInt() and 0xFF,
            (f[6].toInt() and 0xFF) or ((f[7].toInt() and 0xFF) shl 8),
            f.copyOfRange(9, 9 + len),
        )
    }

    /**
     * USB serial (and the WiFi companion's TCP stream) wrap every companion frame:
     * app → radio '<' (0x3C) + len LE16 + payload, radio → app '>' (0x3E) + len LE16 + payload.
     * BLE needs none of this — one write or notification is one frame.
     */
    object Serial {
        const val TO_RADIO = 0x3C
        const val FROM_RADIO = 0x3E
        const val HEADER_BYTES = 3
        /** Larger than any companion frame; a length beyond it means we are not on a header. */
        const val MAX_FRAME_BYTES = 256

        fun encode(payload: ByteArray, marker: Int = TO_RADIO): ByteArray =
            byteArrayOf(marker.toByte(), (payload.size and 0xFF).toByte(), ((payload.size shr 8) and 0xFF).toByte()) +
                payload

        class Decoded(val payload: ByteArray, val consumed: Int)

        /**
         * The first complete frame in [buffer], or null when more bytes are needed. Bytes before a
         * plausible header are skipped (and counted in `consumed`) so a stream that starts mid-frame resyncs.
         */
        fun decode(buffer: ByteArray, marker: Int = FROM_RADIO): Decoded? {
            var i = 0
            while (i + HEADER_BYTES <= buffer.size) {
                if ((buffer[i].toInt() and 0xFF) != marker) { i++; continue }
                val len = (buffer[i + 1].toInt() and 0xFF) or ((buffer[i + 2].toInt() and 0xFF) shl 8)
                if (len == 0 || len > MAX_FRAME_BYTES) { i++; continue }
                if (i + HEADER_BYTES + len > buffer.size) return null
                return Decoded(buffer.copyOfRange(i + HEADER_BYTES, i + HEADER_BYTES + len), i + HEADER_BYTES + len)
            }
            return null
        }
    }
}

/**
 * Text for MeshCore users: one radio message holds at most [MeshCore.MAX_TEXT_BYTES] bytes (a channel message
 * also spends "<our name>: " of them), and clients keep to [MeshCore.RECOMMENDED_TEXT_CHARS] characters. Longer
 * text goes as numbered parts, "(1/3) ...", which any MeshCore app shows in order as ordinary messages.
 *
 * The rule is shared with iOS (MeshCoreInterop.splitText) so both platforms cut identically:
 *  1. Trim. Fits in [maxChars] CODE POINTS and [maxBytes] UTF-8 bytes → sent whole, no label.
 *  2. Otherwise every part is "(i/n) " + text; the label room reserved is 6 ("(9/9) ") while there are ≤ 9 parts,
 *     8 ("(99/99) ") beyond.
 *  3. Per part: as many code points as fit both budgets; an end inside a grapheme cluster backs up to the
 *     cluster's start (unless that cluster starts the part); then the cut moves to the last U+0020 at or before
 *     that end when its code-point index is ≥ end / 2 and > 0. The piece's end and the rest's start are trimmed.
 *  4. More than [MAX_PARTS] parts → null: too long to send (the composer says so).
 */
object MeshCoreText {
    const val DM_MAX_BYTES = MeshCore.MAX_TEXT_BYTES - 4
    const val MAX_PARTS = 20

    /** A channel message's byte budget once the firmware has prefixed "<name>: " (31-byte name when unknown). */
    fun channelMaxBytes(selfName: String?): Int {
        val nameBytes = selfName?.toByteArray(Charsets.UTF_8)?.size ?: 31
        return MeshCore.MAX_TEXT_BYTES - minOf(31, nameBytes) - 2 - 2
    }

    private fun utf8Width(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    private fun fits(cps: IntArray, from: Int, to: Int, maxChars: Int, maxBytes: Int): Boolean {
        if (to - from > maxChars) return false
        var bytes = 0
        for (i in from until to) bytes += utf8Width(cps[i])
        return bytes <= maxBytes
    }

    private fun str(cps: IntArray, from: Int, to: Int) = String(cps, from, to - from)

    /** [text] as it goes on the air: itself when it fits, else numbered parts; null when empty or too long. */
    fun split(text: String, maxBytes: Int, maxChars: Int = MeshCore.RECOMMENDED_TEXT_CHARS): List<String>? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val cps = t.codePoints().toArray()
        if (fits(cps, 0, cps.size, maxChars, maxBytes)) return listOf(t)
        for (digits in 1..2) {
            val label = 2 * digits + 4                      // "(9/9) " = 6, "(99/99) " = 8; ASCII, chars == bytes
            val chunks = chunk(t, maxChars - label, maxBytes - label)
            if (chunks.size <= if (digits == 1) 9 else 99) {
                if (chunks.size > MAX_PARTS) return null
                return chunks.mapIndexed { i, c -> "(${i + 1}/${chunks.size}) $c" }
            }
        }
        return null
    }

    private fun chunk(text: String, maxChars: Int, maxBytes: Int): List<String> {
        val out = ArrayList<String>()
        var rest = text.trim()
        while (rest.isNotEmpty()) {
            val s = rest.codePoints().toArray()
            if (fits(s, 0, s.size, maxChars, maxBytes)) { out += rest; break }
            var end = 0
            var bytes = 0
            while (end < s.size) {
                val b = utf8Width(s[end])
                if (end + 1 > maxChars || bytes + b > maxBytes) break
                bytes += b; end++
            }
            if (end == 0) end = 1
            // Never end inside a grapheme cluster: back up to the start of the cluster `end` falls in.
            val starts = Graphemes.clusterStarts(s)
            if (!starts[end]) {
                var start = end
                while (start > 0 && !starts[start]) start--
                if (start > 0) end = start
            }
            var cut = end
            var space = minOf(end, s.size - 1)
            while (space >= 0 && s[space] != ' '.code) space--
            if (space >= end / 2 && space > 0) cut = space
            val piece = str(s, 0, cut).trimEnd()
            if (piece.isNotEmpty()) out += piece
            rest = str(s, cut, s.size).trimStart()
        }
        return out
    }
}

/**
 * Grapheme-cluster starts over code points: [java.text.BreakIterator]'s character instance, which on Android is
 * ICU (extended grapheme clusters, like Swift's Character). The JVM's own BreakIterator before JDK 20 splits emoji
 * ZWJ sequences, flags and skin tones, so the emoji rules of UAX #29 (GB9, GB9a, GB11, GB12/13) are applied on top;
 * on Android they agree with ICU and change nothing.
 */
internal object Graphemes {
    /** For each code-point index 0..size: does a cluster start there? (index size: the end, always true). */
    fun clusterStarts(cps: IntArray): BooleanArray {
        val str = String(cps, 0, cps.size)
        val starts = BooleanArray(cps.size + 1)
        // UTF-16 offset of each code point.
        val offset = IntArray(cps.size + 1)
        for (i in cps.indices) offset[i + 1] = offset[i] + Character.charCount(cps[i])
        val byOffset = HashMap<Int, Int>(cps.size * 2)
        for (i in 0..cps.size) byOffset[offset[i]] = i
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(str)
        var b = it.first()
        while (b != java.text.BreakIterator.DONE) { byOffset[b]?.let { starts[it] = true }; b = it.next() }
        starts[0] = true; starts[cps.size] = true
        var riRun = 0
        for (i in 1 until cps.size) {
            val prev = cps[i - 1]; val cur = cps[i]
            riRun = if (isRegional(prev)) riRun + 1 else 0
            val join = cur == ZWJ || isExtend(cur) ||                      // GB9 / GB9a: ZWJ, modifiers, selectors
                (prev == ZWJ && isPictographic(cur)) ||                    // GB11: ZWJ emoji sequences
                (isRegional(prev) && isRegional(cur) && riRun % 2 == 1)    // GB12/13: flags pair up
            if (join) starts[i] = false
        }
        return starts
    }

    private const val ZWJ = 0x200D
    private fun isRegional(cp: Int) = cp in 0x1F1E6..0x1F1FF
    private fun isExtend(cp: Int): Boolean = cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F ||
        cp == 0x20E3 || Character.getType(cp).let {
            it == Character.NON_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
        }
    private fun isPictographic(cp: Int) = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2300..0x23FF ||
        cp in 0x2B00..0x2BFF || cp == 0x00A9 || cp == 0x00AE || cp in 0x2190..0x21FF
}

/** The "OB" envelope of oshi-meshcore-bridge (oshi_bridge/wire.py). */
object OshiBridgeWire {
    const val MARKER0: Byte = 0x4F   // 'O'
    const val MARKER1: Byte = 0x42   // 'B'
    const val VERSION = 1
    const val HEADER_LEN = 9
    const val MAX_DATAGRAM = 160          // conservative vs MeshCore's 167
    const val DATA_TYPE = 0xFF4F          // MeshCore's unregistered dev range
    const val CHANNEL_NAME = "#oshi-bridge"
    val CHANNEL_SECRET: ByteArray =
        MessageDigest.getInstance("SHA-256").digest(CHANNEL_NAME.toByteArray(Charsets.UTF_8)).copyOfRange(0, 16)

    class Part(val sender: UInt, val seq: Int, val part: Int, val total: Int, val chunk: ByteArray)

    /**
     * [sender] is the bridge field: whoever put the frame on MeshCore (a bridge's Meshtastic node number,
     * or our own MeshCore-derived id). Receivers ignore their own. Null beyond 15 parts.
     */
    fun split(frame: ByteArray, sender: UInt, seq: Int, maxDatagram: Int = MAX_DATAGRAM): List<ByteArray>? {
        val room = maxDatagram - HEADER_LEN
        val chunks = if (frame.isEmpty()) listOf(ByteArray(0))
            else (frame.indices step room).map { frame.copyOfRange(it, minOf(frame.size, it + room)) }
        if (chunks.size > 15) return null
        val head = byteArrayOf(
            MARKER0, MARKER1, (VERSION shl 4).toByte(),
            sender.toByte(), (sender shr 8).toByte(), (sender shr 16).toByte(), (sender shr 24).toByte(),
            seq.toByte(),
        )
        return chunks.mapIndexed { i, c -> head + byteArrayOf(((i shl 4) or chunks.size).toByte()) + c }
    }

    fun parse(d: ByteArray): Part? {
        if (d.size < HEADER_LEN || d[0] != MARKER0 || d[1] != MARKER1 || (d[2].toInt() and 0xFF) shr 4 != VERSION) return null
        val sender = (d[3].toUInt() and 0xFFu) or ((d[4].toUInt() and 0xFFu) shl 8) or
            ((d[5].toUInt() and 0xFFu) shl 16) or ((d[6].toUInt() and 0xFFu) shl 24)
        val part = (d[8].toInt() and 0xFF) shr 4
        val total = d[8].toInt() and 0x0F
        if (total == 0 || part >= total) return null
        return Part(sender, d[7].toInt() and 0xFF, part, total, d.copyOfRange(HEADER_LEN, d.size))
    }

    /** Multi-part envelopes, keyed by (sender, seq); incomplete sets expire. */
    class Joiner(private val timeoutMs: Long = 90_000, private val maxPending: Int = 128) {
        private class Pending(val total: Int, val first: Long, val chunks: HashMap<Int, ByteArray> = HashMap())
        private val pending = HashMap<String, Pending>()

        @Synchronized
        fun push(p: Part, nowMs: Long = System.currentTimeMillis()): ByteArray? {
            if (p.total == 1) return p.chunk
            pending.entries.removeAll { nowMs - it.value.first > timeoutMs }
            val key = "${p.sender}:${p.seq}"
            var e = pending[key]
            if (e == null || e.total != p.total) {
                if (pending.size >= maxPending) pending.entries.minByOrNull { it.value.first }?.let { pending.remove(it.key) }
                e = Pending(p.total, nowMs)
            }
            e.chunks[p.part] = p.chunk
            if (e.chunks.size != e.total) { pending[key] = e; return null }
            pending.remove(key)
            val out = ByteArrayOutputStream()
            for (i in 0 until e.total) out.write(e.chunks[i] ?: ByteArray(0))
            return out.toByteArray()
        }
    }
}

/**
 * The companion-protocol conversation with one MeshCore radio, transport-free: bytes in, commands out.
 * The BLE manager (Android) and the TCP link (desktop) only move bytes; setup, channel choice and the
 * receive path live here once, so both behave like iOS MeshCoreManager.
 *
 * Setup: APP_START → SELF_INFO (our id = first 4 bytes of the radio's public key, LE) → GET_CHANNEL 0..7.
 * "#oshi-bridge" found → ready. Otherwise the FIRST EMPTY slot > 0 is claimed with SET_CHANNEL; a channel
 * the owner configured is never overwritten (their MeshCore app would silently lose it). No empty slot →
 * [Event.NoFreeChannel] and the radio stays unusable for OSHI. Every slot is read either way, so the
 * owner's channels are known ([channels]); then the contact table is read (GET_CONTACTS) and the radio's
 * queued messages are pulled.
 *
 * MeshCore USERS (not OSHI): their direct and channel text (RESP 7/16, 8/17) surfaces as [Event.DirectText]
 * and [Event.ChannelText]; we write to them with [sendDirect] / [sendChannel]. A direct message is retried
 * by us (attempt 0..3, the firmware never retries on its own) until the radio pushes SEND_CONFIRMED for one
 * of its ACK tags; a channel message has no ACK at all and is reported SENT, never DELIVERED. That traffic is
 * MeshCore's own AES-128 + 2-byte MAC, NOT OSHI end-to-end encryption.
 */
class MeshCoreSession(private val appName: String = "OSHI") {
    enum class State { OFF, CONFIGURING, READY }

    enum class SendState { SENT, DELIVERED, FAILED }

    sealed class Event {
        object Ready : Event()
        object NoFreeChannel : Event()
        /** An OMP DATA frame another sender put on "#oshi-bridge". */
        class Frame(val frame: ByteArray, val parsed: OshiMeshProtocol.DataFrame) : Event()
        /** A MeshCore user wrote to us. [contact] is null while their prefix is not in the synced table. */
        class DirectText(val senderPrefix: ByteArray, val contact: MeshCore.Contact?, val text: String, val senderTimestamp: Long) : Event()
        /** Someone wrote on one of the owner's channels (never "#oshi-bridge"). */
        class ChannelText(val channel: MeshCore.ChannelInfo?, val channelIdx: Int, val senderName: String?, val text: String, val senderTimestamp: Long) : Event()
        /**
         * Every slot has been read: MeshCore users can be written to, with or without "#oshi-bridge" (a radio
         * whose 8 slots are all taken still chats; only OSHI-over-MeshCore needs the bridge channel).
         */
        object ChatReady : Event()
        object ContactsChanged : Event()
        object ChannelsChanged : Event()
        class ContactHeard(val publicKeyHex: String) : Event()
        /** A message given to [sendDirect] / [sendChannel] moved on: once SENT, then DELIVERED or FAILED. */
        class Send(val messageId: String, val state: SendState) : Event()
        class ChannelJoin(val idx: Int, val ok: Boolean) : Event()
        /** The radio answered [sendSelfAdvert]. */
        class AdvertSent(val ok: Boolean) : Event()
    }

    var state = State.OFF; private set
    /** The slot our "#oshi-bridge" channel lives in, or null while unknown / when every slot is taken. */
    var channelIdx: Int? = null; private set
    /** Our OMP origin / envelope sender on MeshCore. A collision with a Meshtastic node number only costs a reassembly key. */
    var nodeId: UInt = 0u; private set
    /** The radio's advert name (what a channel message is prefixed with). */
    var selfName: String? = null; private set
    val isReady: Boolean get() = state == State.READY && channelIdx != null
    /** MeshCore users can be written to (see [Event.ChatReady]). */
    var chatReady = false; private set

    private enum class Kind { OTHER, PROBE, CLAIM, CONTACTS, CONTACT_BY_KEY, SEND, JOIN, ADD_CONTACT, ADVERT }
    private class Cmd(val bytes: ByteArray, val kind: Kind, val part: OutPart? = null, val join: MeshCore.ChannelInfo? = null, val key: String? = null)

    private class OutPart(val msg: OutMessage, val direct: Boolean, val build: (Int) -> ByteArray) {
        var attempt = 0
        var accepted = false
        var acked = false
        /** Handed to the radio and not answered yet (or accepted and waiting for its ACK). */
        var onAir = false
        var deadline = 0L
        val tags = HashSet<Long>()
    }
    private class OutMessage(val id: String, val direct: Boolean) {
        val parts = ArrayList<OutPart>()
        /** The part on the air (or next to go). Parts go strictly one after the other, like iOS. */
        var current = 0
        var sentReported = false
        var done = false
        /** A contact the radio only heard (PUSH_NEW_ADVERT, auto-add off): added to its table first. */
        var addContact: ByteArray? = null
    }

    private val commands = ArrayDeque<Cmd>()
    private var inFlight: Cmd? = null
    private var inFlightSince = 0L
    private var firstEmptySlot: Int? = null
    private var scanDone = false
    private var seq = 0
    private val joiner = OshiBridgeWire.Joiner()
    private val slots = java.util.TreeMap<Int, MeshCore.ChannelInfo>()
    private val contactTable = LinkedHashMap<String, MeshCore.Contact>()
    private var contactsIncoming: LinkedHashMap<String, MeshCore.Contact>? = null
    private val outgoing = LinkedHashMap<String, OutMessage>()
    /**
     * Text waiting for the radio, strictly one message and one part at a time (iOS MeshCoreRadio textQueue):
     * parts sent back to back collided on real radios and cost an ACK. A direct part goes once the previous
     * one is ACKed (or failed); a channel part once the radio said OK; always [PART_GAP_MS] after the last.
     */
    private val textQueue = ArrayDeque<OutMessage>()
    private var activeText: OutMessage? = null
    private var nextTextAt = 0L
    /** Contacts the radio pushed but does not hold (PUSH_NEW_ADVERT): public-key hex → that frame. */
    private val notOnRadio = HashMap<String, ByteArray>()
    /** ACK tag → part, kept after a message finished so a late SEND_CONFIRMED still upgrades it. */
    private val ackIndex = LinkedHashMap<Long, OutPart>()
    /** Incoming text already surfaced (key -> first seen ms); survives [reset] so a reconnect cannot replay a copy. */
    private val seenTexts = LinkedHashMap<String, Long>()
    /** Events produced outside [onFrame] / [onTick] (a reset), handed out by the next [onTick]. */
    private val deferred = ArrayList<Event>()

    /** Every slot read from the radio, "#oshi-bridge" and empty slots included. */
    val channels: List<MeshCore.ChannelInfo> @Synchronized get() = slots.values.toList()
    /** The owner's channels a person can talk in: named, and not "#oshi-bridge". */
    val userChannels: List<MeshCore.ChannelInfo> @Synchronized get() = slots.values.filter { isUserChannel(it) }
    val contacts: List<MeshCore.Contact> @Synchronized get() = contactTable.values.toList()

    @Synchronized
    fun reset() {
        state = State.OFF; channelIdx = null; nodeId = 0u; selfName = null; chatReady = false
        commands.clear(); inFlight = null; firstEmptySlot = null; scanDone = false
        slots.clear(); contactTable.clear(); contactsIncoming = null; notOnRadio.clear()
        // Text the link died under is not lost silently: its owner hears FAILED on the next tick, so the bubble
        // offers tap-to-retry (iOS abandonTexts). A late ACK still upgrades it to DELIVERED (ackIndex is kept).
        for (m in outgoing.values) {
            if (!m.done) { m.done = true; deferred.add(Event.Send(m.id, SendState.FAILED)) }
        }
        textQueue.clear(); activeText = null; nextTextAt = 0L
    }

    /** Messages given to [sendDirect] / [sendChannel] that the radio has not taken yet (the bubble's clock). */
    val pendingTextIds: Set<String>
        @Synchronized get() = outgoing.values.filter { !it.done && !it.sentReported }.map { it.id }.toSet()

    /** The link is up (BLE notifications on / socket open): introduce ourselves. */
    @Synchronized
    fun start() {
        reset()
        state = State.CONFIGURING
        commands.addLast(Cmd(MeshCore.appStart(appName), Kind.OTHER))
    }

    @Synchronized
    fun enqueue(cmd: ByteArray) { commands.addLast(Cmd(cmd, Kind.OTHER)) }

    /** The next command to write, or null while one is still waiting for its reply. */
    @Synchronized
    fun nextCommand(nowMs: Long = System.currentTimeMillis()): ByteArray? {
        if (inFlight != null || commands.isEmpty()) return null
        val c = commands.removeFirst()
        inFlight = c
        inFlightSince = nowMs
        return c.bytes
    }

    /** A write that failed (or was never answered) will never be answered: free the queue instead of wedging it. */
    @Synchronized
    fun writeFailed(): List<Event> {
        val c = inFlight ?: return emptyList()
        inFlight = null
        val events = ArrayList<Event>(1)
        when (c.kind) {
            Kind.SEND -> c.part?.let { partFailed(it, events) }
            Kind.CONTACTS -> contactsIncoming = null
            Kind.JOIN -> c.join?.let { events.add(Event.ChannelJoin(it.idx, false)) }
            Kind.PROBE -> { probeNext(c.bytes[1].toInt() + 1, events) }
            else -> Unit
        }
        return events
    }

    /** One OMP frame as companion commands: "OB"-wrapped, split, SEND_CHANNEL_DATA each. Empty when not ready. */
    @Synchronized
    fun channelDatagrams(frame: ByteArray): List<ByteArray> {
        val idx = channelIdx ?: return emptyList()
        if (!isReady) return emptyList()
        seq = (seq + 1) and 0xFF
        val parts = OshiBridgeWire.split(frame, nodeId, seq) ?: return emptyList()
        return parts.map { MeshCore.sendChannelData(idx, OshiBridgeWire.DATA_TYPE, it) }
    }

    // ─────────────────────────── MeshCore users ───────────────────────────

    /** The synced contact whose key starts with [prefix], if any. */
    @Synchronized
    fun contactFor(prefix: ByteArray): MeshCore.Contact? = contactTable.values.firstOrNull { it.hasPrefix(prefix) }

    /** The first empty slot > 0 (slot 0 is the radio's Public channel), or null when all are used. */
    @Synchronized
    fun freeChannelSlot(): Int? =
        (1 until MeshCore.CHANNEL_SLOTS).firstOrNull { i -> slots[i]?.let { it.name.isEmpty() } ?: scanDone }

    /**
     * A direct message to the MeshCore contact whose key starts with [pubKeyPrefix] (6+ bytes), split into
     * numbered parts when long. False when the radio is not ready (nothing queued, nothing will be reported).
     */
    @Synchronized
    fun sendDirect(messageId: String, pubKeyPrefix: ByteArray, text: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!chatReady || pubKeyPrefix.size < MeshCore.PUB_KEY_PREFIX || text.isEmpty()) return false
        val prefix = pubKeyPrefix.copyOf(MeshCore.PUB_KEY_PREFIX)
        val parts = MeshCoreText.split(text, MeshCoreText.DM_MAX_BYTES) ?: return false   // too long (> 20 parts)
        val m = OutMessage(messageId, direct = true)
        parts.forEachIndexed { i, p ->
            val ts = nowMs / 1000 + i
            m.parts += OutPart(m, true) { attempt -> MeshCore.sendTextMessage(attempt, ts, prefix, p) }
        }
        contactFor(prefix)?.let { c -> notOnRadio[c.publicKeyHex]?.let { m.addContact = MeshCore.addContactFrom(it) } }
        queue(m, nowMs)
        return true
    }

    /** Text on the owner's channel in slot [idx]. Never "#oshi-bridge". */
    @Synchronized
    fun sendChannel(messageId: String, idx: Int, text: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!chatReady || text.isEmpty() || idx == channelIdx) return false
        val info = slots[idx] ?: return false
        if (!isUserChannel(info)) return false
        val parts = MeshCoreText.split(text, MeshCoreText.channelMaxBytes(selfName)) ?: return false
        val m = OutMessage(messageId, direct = false)
        parts.forEachIndexed { i, p ->
            val ts = nowMs / 1000 + i
            m.parts += OutPart(m, false) { MeshCore.sendChannelText(idx, ts, p) }
        }
        queue(m, nowMs)
        return true
    }

    /** Put [name]/[secret] in slot [idx] (callers pick a free one: [freeChannelSlot]). Answered by [Event.ChannelJoin]. */
    @Synchronized
    fun joinChannel(idx: Int, name: String, secret: ByteArray): Boolean {
        if (!chatReady || idx <= 0 || idx >= MeshCore.CHANNEL_SLOTS || idx == channelIdx) return false
        if (name.isBlank() || name == OshiBridgeWire.CHANNEL_NAME || secret.size != 16) return false
        val trimmed = name.trim()
        commands.addLast(Cmd(MeshCore.setChannel(idx, trimmed, secret), Kind.JOIN, join = MeshCore.ChannelInfo(idx, trimmed, secret)))
        return true
    }

    /** Re-read the contact table (after a pull-to-refresh or an advert from someone new). */
    @Synchronized
    fun refreshContacts() {
        if (commands.none { it.kind == Kind.CONTACTS } && inFlight?.kind != Kind.CONTACTS) {
            commands.addLast(Cmd(MeshCore.getContacts(), Kind.CONTACTS))
        }
    }

    /** Announce this radio so MeshCore users nearby (and through repeaters) can add it as a contact. */
    @Synchronized
    fun sendSelfAdvert(): Boolean {
        if (!chatReady) return false
        if (commands.none { it.kind == Kind.ADVERT }) commands.addLast(Cmd(MeshCore.sendSelfAdvert(true), Kind.ADVERT))
        return true
    }

    private fun queue(m: OutMessage, nowMs: Long) {
        outgoing[m.id]?.let { old -> old.done = true; textQueue.remove(old); if (activeText === old) activeText = null }
        outgoing[m.id] = m
        textQueue.addLast(m)
        driveText(nowMs)
    }

    /** Put the next text part on the command queue when the lane is free and the gap has passed. */
    private fun driveText(nowMs: Long) {
        if (activeText?.done == true) activeText = null
        val m = activeText ?: textQueue.removeFirstOrNull()?.also { activeText = it } ?: return
        if (m.current >= m.parts.size) return
        val p = m.parts[m.current]
        if (p.onAir || nowMs < nextTextAt) return
        m.addContact?.let { add ->
            m.addContact = null
            commands.addLast(Cmd(add, Kind.ADD_CONTACT, key = MeshCore.hex(add.copyOfRange(1, 1 + MeshCore.PUB_KEY_SIZE))))
        }
        p.onAir = true
        commands.addLast(Cmd(p.build(p.attempt), Kind.SEND, p))
    }

    /** The active message's current part is finished (ACKed, or OK for a channel): the next one, after the gap. */
    private fun partDone(p: OutPart, nowMs: Long) {
        val m = p.msg
        if (activeText !== m || m.parts.getOrNull(m.current) !== p) return
        m.current++
        nextTextAt = nowMs + PART_GAP_MS
        if (m.current >= m.parts.size) {
            m.done = true
            activeText = null
        }
    }

    /**
     * Time passes: a direct part whose ACK did not come in time goes again (attempt + 1, same timestamp, so the
     * recipient's radio drops a duplicate), up to attempt 3; then the message is FAILED. A request the radio
     * never finished answering (a contact listing cut short) is released after [STUCK_MS].
     */
    @Synchronized
    fun onTick(nowMs: Long = System.currentTimeMillis()): List<Event> {
        val events = ArrayList<Event>(deferred)
        deferred.clear()
        if (inFlight?.kind == Kind.CONTACTS && nowMs - inFlightSince > STUCK_MS) { inFlight = null; contactsIncoming = null }
        activeText?.takeIf { !it.done }?.let { m ->
            val p = m.parts.getOrNull(m.current) ?: return@let
            if (p.direct && p.accepted && !p.acked && nowMs >= p.deadline) {
                if (p.attempt >= MAX_ATTEMPT) {
                    partFailed(p, events, nowMs)
                } else {
                    // Again at once, same timestamp: the recipient's radio drops the copy if the first did arrive.
                    p.attempt++
                    p.accepted = false
                    p.onAir = true
                    commands.addLast(Cmd(p.build(p.attempt), Kind.SEND, p))
                }
            }
        }
        driveText(nowMs)
        // Finished messages keep their tags (late ACKs) for a while, not for ever.
        while (ackIndex.size > MAX_ACK_TAGS) ackIndex.remove(ackIndex.keys.first())
        outgoing.entries.removeAll { it.value.done && it.value.parts.none { p -> p.tags.isNotEmpty() && !p.acked } }
        return events
    }

    private fun partFailed(p: OutPart, events: MutableList<Event>, nowMs: Long = 0L) {
        val m = p.msg
        if (m.done) return
        m.done = true
        commands.removeAll { it.part?.msg === m }
        if (activeText === m) { activeText = null; nextTextAt = maxOf(nextTextAt, nowMs + PART_GAP_MS) }
        events.add(Event.Send(m.id, SendState.FAILED))
    }

    private fun partAccepted(p: OutPart, sent: MeshCore.Sent?, nowMs: Long, events: MutableList<Event>) {
        val m = p.msg
        p.accepted = true
        if (sent != null) {
            p.tags += sent.ackTag
            ackIndex[sent.ackTag] = p
            p.deadline = nowMs + (sent.estTimeoutMs * 3 / 2).coerceIn(MIN_ACK_WAIT_MS, MAX_ACK_WAIT_MS)
        }
        if (m.done) return
        // SENT once the radio took the first part (iOS textAccepted): it is on the air from here on.
        if (!m.sentReported) {
            m.sentReported = true
            events.add(Event.Send(m.id, SendState.SENT))
        }
        // A channel part ends here: nobody acknowledges a broadcast.
        if (!p.direct) partDone(p, nowMs)
    }

    private fun acked(tag: Long, events: MutableList<Event>, nowMs: Long) {
        val p = ackIndex[tag] ?: return
        if (p.acked) return
        p.acked = true
        val m = p.msg
        if (!m.done) partDone(p, nowMs)
        if (m.parts.all { it.acked }) {
            // Also after FAILED: a late ACK proves it arrived, and that is what the bubble must say.
            m.done = true
            commands.removeAll { it.part?.msg === m }
            events.add(Event.Send(m.id, SendState.DELIVERED))
        }
        driveText(nowMs)
    }

    // ─────────────────────────── Frames from the radio ───────────────────────────

    /** One frame from the radio. */
    @Synchronized
    fun onFrame(f: ByteArray, nowMs: Long = System.currentTimeMillis()): List<Event> {
        if (f.isEmpty()) return emptyList()
        val code = f[0].toInt() and 0xFF
        val events = ArrayList<Event>(1)
        if (code >= 0x80) {
            when (code) {
                MeshCore.PUSH_MSG_WAITING -> commands.addLast(Cmd(MeshCore.syncNextMessage, Kind.OTHER))
                MeshCore.PUSH_SEND_CONFIRMED -> MeshCore.parseSendConfirmed(f)?.let { acked(it, events, nowMs) }
                MeshCore.PUSH_NEW_ADVERT -> MeshCore.parseContact(f)?.let { c ->
                    contactTable[c.publicKeyHex] = c
                    // Heard, but NOT in the radio's table (auto-add is off): remembered so a DM can add it first.
                    notOnRadio[c.publicKeyHex] = f.copyOf()
                    events.add(Event.ContactsChanged)
                    events.add(Event.ContactHeard(c.publicKeyHex))
                }
                MeshCore.PUSH_ADVERT -> MeshCore.parsePushedKey(f)?.let { k ->
                    val hex = MeshCore.hex(k)
                    if (hex !in contactTable && state == State.READY) commands.addLast(Cmd(MeshCore.getContactByKey(k), Kind.CONTACT_BY_KEY))
                    events.add(Event.ContactHeard(hex))
                }
                MeshCore.PUSH_CONTACT_DELETED -> MeshCore.parsePushedKey(f)?.let { k ->
                    if (contactTable.remove(MeshCore.hex(k)) != null) events.add(Event.ContactsChanged)
                }
            }
            return events
        }
        val c = inFlight
        // A contact listing is ONE request answered by START, CONTACT × N, END: only END frees the queue.
        val listing = c?.kind == Kind.CONTACTS && (code == MeshCore.RESP_CONTACTS_START || code == MeshCore.RESP_CONTACT)
        if (!listing) inFlight = null

        when (c?.kind) {
            Kind.SEND -> c.part?.let { p ->
                when (code) {
                    MeshCore.RESP_SENT -> { partAccepted(p, MeshCore.parseSent(f), nowMs, events); driveText(nowMs); return events }
                    MeshCore.RESP_OK -> { partAccepted(p, null, nowMs, events); driveText(nowMs); return events }
                    MeshCore.RESP_ERR -> { partFailed(p, events, nowMs); driveText(nowMs); return events }
                    else -> Unit
                }
            }
            Kind.ADD_CONTACT -> if (code == MeshCore.RESP_OK || code == MeshCore.RESP_ERR) {
                if (code == MeshCore.RESP_OK) c.key?.let { notOnRadio.remove(it) }
                return events
            }
            Kind.ADVERT -> if (code == MeshCore.RESP_OK || code == MeshCore.RESP_ERR) {
                events.add(Event.AdvertSent(code == MeshCore.RESP_OK))
                return events
            }
            Kind.JOIN -> c.join?.let { j ->
                if (code == MeshCore.RESP_OK) { slots[j.idx] = j; events.add(Event.ChannelsChanged) }
                if (code == MeshCore.RESP_OK || code == MeshCore.RESP_ERR) { events.add(Event.ChannelJoin(j.idx, code == MeshCore.RESP_OK)); return events }
            }
            Kind.PROBE -> if (code == MeshCore.RESP_ERR) {
                // A slot the firmware does not have ends the scan like an unknown one.
                scanFinished(events)
                return events
            }
            Kind.CLAIM -> {
                if (code == MeshCore.RESP_OK) {
                    channelIdx?.let { slots[it] = MeshCore.ChannelInfo(it, OshiBridgeWire.CHANNEL_NAME, OshiBridgeWire.CHANNEL_SECRET) }
                    becomeReady(events)
                    afterScan(events)
                } else if (code == MeshCore.RESP_ERR) {
                    channelIdx = null
                    events.add(Event.NoFreeChannel)
                    afterScan(events)
                }
                return events
            }
            Kind.CONTACTS -> when (code) {
                MeshCore.RESP_CONTACTS_START -> { contactsIncoming = LinkedHashMap(); return events }
                MeshCore.RESP_CONTACT -> {
                    MeshCore.parseContact(f)?.let { ct ->
                        (contactsIncoming ?: LinkedHashMap<String, MeshCore.Contact>().also { contactsIncoming = it })[ct.publicKeyHex] = ct
                        notOnRadio.remove(ct.publicKeyHex)
                    }
                    return events
                }
                MeshCore.RESP_END_OF_CONTACTS -> {
                    // The radio's table, plus contacts it only heard (auto-add off), which it never lists.
                    contactsIncoming?.let { fresh ->
                        val heardOnly = contactTable.filterKeys { it in notOnRadio && it !in fresh }
                        contactTable.clear(); contactTable.putAll(fresh); contactTable.putAll(heardOnly)
                    }
                    contactsIncoming = null
                    events.add(Event.ContactsChanged)
                    return events
                }
                else -> contactsIncoming = null
            }
            else -> Unit
        }

        when (code) {
            MeshCore.RESP_SELF_INFO -> {
                MeshCore.selfPublicKey(f)?.let { k ->
                    nodeId = (k[0].toUInt() and 0xFFu) or ((k[1].toUInt() and 0xFFu) shl 8) or
                        ((k[2].toUInt() and 0xFFu) shl 16) or ((k[3].toUInt() and 0xFFu) shl 24)
                }
                selfName = MeshCore.selfName(f)
                commands.addLast(Cmd(MeshCore.getChannel(0), Kind.PROBE))
            }
            MeshCore.RESP_CHANNEL_INFO -> MeshCore.parseChannelInfo(f)?.let { channelProbed(it, events) }
            MeshCore.RESP_CONTACT -> MeshCore.parseContact(f)?.let { ct ->
                contactTable[ct.publicKeyHex] = ct
                events.add(Event.ContactsChanged)
            }
            MeshCore.RESP_CHANNEL_DATA_RECV -> {
                MeshCore.parseChannelData(f)?.let { receive(it, nowMs)?.let(events::add) }
                commands.addLast(Cmd(MeshCore.syncNextMessage, Kind.OTHER))
            }
            MeshCore.RESP_CONTACT_MSG_RECV, MeshCore.RESP_CONTACT_MSG_RECV_V3 -> {
                MeshCore.parseDirectText(f)?.let { d ->
                    // CLI data / commands (txt types 1, 3) are radio administration, not conversation.
                    if ((d.txtType == MeshCore.TXT_TYPE_PLAIN || d.txtType == MeshCore.TXT_TYPE_SIGNED_PLAIN) && d.text.isNotEmpty() &&
                        firstTimeText("d:${MeshCore.hex(d.senderPrefix)}:${d.senderTimestamp}:${d.text}", nowMs)
                    ) {
                        events.add(Event.DirectText(d.senderPrefix, contactFor(d.senderPrefix), d.text, d.senderTimestamp))
                    }
                }
                commands.addLast(Cmd(MeshCore.syncNextMessage, Kind.OTHER))
            }
            MeshCore.RESP_CHANNEL_MSG_RECV, MeshCore.RESP_CHANNEL_MSG_RECV_V3 -> {
                MeshCore.parseChannelText(f)?.let { t ->
                    val info = slots[t.channelIdx]
                    val bridge = t.channelIdx == channelIdx || info?.name == OshiBridgeWire.CHANNEL_NAME
                    if (!bridge && t.text.isNotEmpty() && firstTimeText("c:${t.channelIdx}:${t.senderTimestamp}:${t.text}", nowMs)) {
                        val (sender, body) = MeshCore.splitChannelSender(t.text)
                        events.add(Event.ChannelText(info, t.channelIdx, sender, body, t.senderTimestamp))
                    }
                }
                commands.addLast(Cmd(MeshCore.syncNextMessage, Kind.OTHER))
            }
            MeshCore.RESP_NO_MORE_MESSAGES -> Unit
        }
        return events
    }

    /**
     * The sender's app retries a direct message whose ACK it did not hear (attempt 1..3, SAME timestamp and
     * text), but the attempt byte changes the packet hash, so the receiving radio's own duplicate filter does
     * not catch it: the radio hands us a byte-identical second copy. Seen on real T1000-E radios (firmware
     * v1.18.0): an ACK lost to a collision made "(2/3)" arrive twice. MeshCore apps drop such copies; so do
     * we, keyed on sender + timestamp + text (iOS gets the same effect from its stable message id).
     */
    private fun firstTimeText(key: String, nowMs: Long): Boolean {
        seenTexts.entries.removeAll { nowMs - it.value > TEXT_DEDUP_MS }
        if (key in seenTexts) return false
        if (seenTexts.size >= MAX_SEEN_TEXTS) seenTexts.remove(seenTexts.keys.first())
        seenTexts[key] = nowMs
        return true
    }

    private fun isUserChannel(info: MeshCore.ChannelInfo): Boolean =
        info.name.isNotEmpty() && info.idx != channelIdx && info.name != OshiBridgeWire.CHANNEL_NAME

    private fun channelProbed(info: MeshCore.ChannelInfo, events: MutableList<Event>) {
        slots[info.idx] = info
        if (channelIdx == null && info.name == OshiBridgeWire.CHANNEL_NAME && info.secret.contentEquals(OshiBridgeWire.CHANNEL_SECRET)) {
            channelIdx = info.idx
            becomeReady(events)
        }
        if (info.name.isEmpty() && info.idx > 0 && firstEmptySlot == null) firstEmptySlot = info.idx
        probeNext(info.idx + 1, events)
    }

    private fun probeNext(next: Int, events: MutableList<Event>) {
        if (next < MeshCore.CHANNEL_SLOTS) { commands.addLast(Cmd(MeshCore.getChannel(next), Kind.PROBE)); return }
        scanFinished(events)
    }

    private fun scanFinished(events: MutableList<Event>) {
        scanDone = true
        events.add(Event.ChannelsChanged)
        if (channelIdx != null) { afterScan(events); return }
        val slot = firstEmptySlot ?: run { events.add(Event.NoFreeChannel); afterScan(events); return }
        channelIdx = slot
        commands.addLast(Cmd(MeshCore.setChannel(slot, OshiBridgeWire.CHANNEL_NAME, OshiBridgeWire.CHANNEL_SECRET), Kind.CLAIM))
    }

    /** Every slot is known: read who the radio knows, then what it queued while we were away. */
    private fun afterScan(events: MutableList<Event>) {
        if (state != State.READY && channelIdx != null) return   // claiming the bridge slot: wait for its answer
        if (chatReady) return
        chatReady = true
        refreshContacts()
        commands.addLast(Cmd(MeshCore.syncNextMessage, Kind.OTHER))
        events.add(Event.ChatReady)
    }

    private fun becomeReady(events: MutableList<Event>) {
        if (state == State.READY) return
        state = State.READY
        events.add(Event.Ready)
    }

    private fun receive(d: MeshCore.ChannelData, nowMs: Long): Event? {
        if (d.channelIdx != channelIdx || d.dataType != OshiBridgeWire.DATA_TYPE) return null
        val part = OshiBridgeWire.parse(d.payload) ?: return null
        if (part.sender == nodeId) return null
        val frame = joiner.push(part, nowMs) ?: return null
        if (OshiMeshProtocol.typeOf(frame) != OshiMeshProtocol.FrameType.DATA) return null
        val f = OshiMeshProtocol.parseData(frame) ?: return null
        return Event.Frame(frame, f)
    }

    companion object {
        /** MeshCore clients give up after the 4th attempt (attempt 3); so do we. */
        const val MAX_ATTEMPT = 3
        /** Between two text parts (and two messages) on the air: iOS textPartGap. */
        const val PART_GAP_MS = 1_500L
        const val MIN_ACK_WAIT_MS = 6_000L
        const val MAX_ACK_WAIT_MS = 60_000L
        const val STUCK_MS = 30_000L
        private const val MAX_ACK_TAGS = 256
        /** Longer than a sender's 4 attempts at up to [MAX_ACK_WAIT_MS] each. */
        const val TEXT_DEDUP_MS = 10 * 60_000L
        private const val MAX_SEEN_TEXTS = 512
    }
}

/**
 * The dedup of the phone-as-bridge (iOS LoRaCrossBridge): when a device holds a Meshtastic/OSHI radio AND a
 * MeshCore radio, every broadcast OMP DATA frame heard on one side is repeated on the other, once.
 *
 * Keyed on msgId:idx:count, NOT on origin: an OSHI Mesh radio re-stamps the origin of what its phone hands
 * it but keeps the msgId, so an origin key would let two bridging phones ping-pong a frame. Only broadcast
 * DATA: unicast OMP belongs to the firmware's SACK/receipt machinery, which repeated fragments would confuse.
 */
class CrossBridgeDedup(private val ttlMs: Long = 30 * 60_000L, private val maxSeen: Int = 2048) {
    private val seen = HashMap<String, Long>()

    @Synchronized
    fun firstTime(f: OshiMeshProtocol.DataFrame, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (f.dest != BROADCAST) return false
        val key = "${f.msgId}:${f.idx}:${f.count}"
        seen[key]?.let { if (nowMs - it < ttlMs) return false }
        if (seen.size >= maxSeen) seen.entries.removeAll { nowMs - it.value >= ttlMs }
        if (seen.size >= maxSeen) seen.entries.minByOrNull { it.value }?.let { seen.remove(it.key) }
        seen[key] = nowMs
        return true
    }

    companion object {
        const val BROADCAST: UInt = 0xFFFFFFFFu
    }
}
