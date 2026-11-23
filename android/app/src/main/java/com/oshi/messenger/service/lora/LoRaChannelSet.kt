package com.oshi.messenger.service.lora

// `java.util.Base64`, not `android.util.Base64`, for the same reason
// [LoRaChannel] uses it: the android.util one is a stub in local unit tests and
// throws, so a codec written against it cannot be tested without Robolectric.
// minSdk is 26, so it is available everywhere this ships.
import java.util.Base64

/**
 * LoRaChannelSet — the `https://meshtastic.org/e/#…` link, parsed and produced.
 *
 * WHY THIS EXISTS
 * ---------------
 * [LoRaChannel] let a user type a channel name and key, which is enough to join a
 * mesh you already know the credentials for. But nobody shares a public channel
 * that way: every community channel, every regional mesh, every "join us" post on
 * a forum is a LINK, and a QR code of that link. Without the link format, joining
 * the public channel for your city meant reading a 32-character base64 key off
 * someone's screenshot by hand, or installing the Meshtastic app to import it and
 * coming back. "See and join public channels" is this format.
 *
 * It is also how a channel is SHARED, which matters just as much: a user who has
 * set up a mesh for a protest or a hike needs to hand it to the people with them,
 * and they will not type a PSK.
 *
 * WHAT IS ON THE WIRE
 * -------------------
 * The fragment after `/#` is a base64url `ChannelSet` protobuf
 * (`meshtastic/apponly.proto`):
 *
 *     ChannelSet       { settings = 1 (repeated), lora_config = 2 }
 *     ChannelSettings  { psk = 2, name = 3 }
 *     LoRaConfig       { use_preset = 1, modem_preset = 2, region = 7, hop_limit = 8 }
 *
 * The FIRST entry in `settings` is the primary channel — "It includes only one
 * PRIMARY channel (which must be first) and any SECONDARY channels", from the
 * proto's own comment. Order is therefore meaning, not presentation, and this
 * preserves it.
 *
 * ⚠️ A channel link is NOT a secret-sharing mechanism. The PSK travels in the
 * link in plain base64, so anyone who sees the link is on the channel. That is
 * inherent to the format and the UI must say so rather than implying a link is
 * safe to post publicly.
 *
 * WHAT THE REFERENCE IMPLEMENTATION DOES, AND WHY WE COPY IT EXACTLY
 * ------------------------------------------------------------------
 * `meshtastic/python/meshtastic/node.py`, `getURL`/`setURL`:
 *
 *  - generating: `base64.urlsafe_b64encode`, then `=` stripped and `+`/`/` mapped
 *    to `-`/`_`, behind the prefix `https://meshtastic.org/e/#`;
 *  - parsing: split on `/#` (or on `/?add=true#`), take the LAST segment, restore
 *    the `=` padding, `base64.urlsafe_b64decode`.
 *
 * The parser only ever looks for the `/#` delimiter, so the older `/c/#` and the
 * `/d/#` spelling in its own comment both parse. We accept all of them for the
 * same reason: links posted in 2021 are still in forum threads, and refusing one
 * would look like a broken app rather than a stale link.
 */
object LoRaChannelSet {

    /** What we PRODUCE. Parsing is deliberately more tolerant — see [parse]. */
    const val URL_PREFIX = "https://meshtastic.org/e/#"

    /** `Channel.Role`. A link never carries DISABLED entries. */
    const val ROLE_PRIMARY = 1L
    const val ROLE_SECONDARY = 2L

    /**
     * The firmware holds eight channel slots, index 0-7, and slot 0 is the
     * primary. A link with more than eight entries is malformed rather than
     * interesting.
     */
    const val MAX_CHANNELS = 8

    data class Channel(
        /** Blank is legitimate: it is the stock LongFast channel. */
        val name: String,
        val psk: ByteArray,
    ) {
        /** For display. Blank means the default, which users know as "LongFast". */
        val displayName: String get() = LoRaChannel.displayName(name)

        /**
         * True when this is the public default key — one byte, value 1 (`AQ==`),
         * the key every stock Meshtastic install shares. Worth saying out loud in
         * the UI: it means the channel is readable by anyone, which is what makes
         * it "public" and is also exactly what a user in a protest must not
         * mistake for privacy.
         */
        val isDefaultPublicKey: Boolean
            get() = psk.size == 1 && psk[0] == 1.toByte()

        /** `psk` of length 0 or a single 0 byte: the radio sends in the CLEAR. */
        val isUnencrypted: Boolean
            get() = psk.isEmpty() || (psk.size == 1 && psk[0] == 0.toByte())

        // ByteArray in a data class: generated equals/hashCode compare it by
        // reference, which would make two identical channels unequal and break
        // every `==` and `distinct()` a caller writes. Same trap as
        // __GROUP_PHOTO_FLICKERED_2026_11_04__, one layer down.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Channel) return false
            return name == other.name && psk.contentEquals(other.psk)
        }

        override fun hashCode(): Int = 31 * name.hashCode() + psk.contentHashCode()
    }

    /**
     * The radio half of a link. Every field is nullable because a link may carry
     * no `lora_config` at all, and because applying a PARTIAL radio config is
     * what `adminSetLoRaConfig` warns about: the whole LoRaConfig is replaced, so
     * a field left out arrives as zero and produces a node that is on the air and
     * unable to talk to anyone. A caller must therefore have all three or write
     * none of them, and null here is how it finds out.
     */
    data class Radio(
        val regionRaw: Long?,
        val presetRaw: Long?,
        val hopLimit: Long?,
    ) {
        val isComplete: Boolean get() = regionRaw != null && presetRaw != null && hopLimit != null
    }

    data class Parsed(
        /** Primary first, then secondaries, in the link's own order. */
        val channels: List<Channel>,
        val radio: Radio?,
    ) {
        val primary: Channel? get() = channels.firstOrNull()
        val secondaries: List<Channel> get() = channels.drop(1)
    }

    // ------------------------------------------------------------------ parse

    /**
     * The [Parsed] form of [url], or null when it is not a channel link.
     *
     * Returns null rather than throwing: this is fed by a paste field and a QR
     * scan, so "not a channel link" is an ordinary thing for a user to do and the
     * caller needs to say so in a sentence, not catch an exception.
     */
    fun parse(url: String): Parsed? {
        val payload = fragmentOf(url) ?: return null
        val bytes = decodeBase64Url(payload) ?: return null
        if (bytes.isEmpty()) return null
        return parseChannelSet(bytes)
    }

    /**
     * The base64 payload of [url].
     *
     * Splits on `/#` exactly as the reference does, which is what makes `/e/#`,
     * `/d/#` and the 2021 `/c/#` all work, and takes the LAST segment so a link
     * wrapped in redirect junk still resolves. A bare fragment with no prefix is
     * also accepted — people paste the half they can see.
     */
    private fun fragmentOf(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null

        val addOnly = "/?add=true#"
        val marker = if (trimmed.contains(addOnly)) addOnly else "/#"
        if (trimmed.contains(marker)) {
            return trimmed.substringAfterLast(marker).takeIf { it.isNotEmpty() }
        }
        // No delimiter at all. Accept a bare payload, but only if it LOOKS like
        // base64 — otherwise every stray paste would be reported as a bad link
        // rather than as not a link.
        if (trimmed.any { it == ':' || it == '/' || it == ' ' }) return null
        return trimmed.takeIf { it.isNotEmpty() && it.all(::isBase64Char) }
    }

    private fun isBase64Char(c: Char): Boolean =
        c.isLetterOrDigit() || c == '+' || c == '/' || c == '-' || c == '_' || c == '='

    /**
     * base64url (and plain base64) to bytes, padding restored.
     *
     * Both alphabets are accepted for the same reason [LoRaChannel.parsePsk]
     * accepts both: a key or a link copied through a chat app, a QR reader or a
     * forum post arrives in whichever one that tool used, and the user cannot
     * tell the difference by looking.
     *
     * A length of `n % 4 == 1` is not short padding, it is impossible base64, and
     * padding it to 4 would decode to silent nonsense. The reference appends
     * `"=" * (4 - len % 4)` unconditionally, which also appends four `=` when the
     * length is already a multiple of four; we do neither.
     */
    private fun decodeBase64Url(raw: String): ByteArray? {
        val normalised = raw.trim().replace('-', '+').replace('_', '/').trimEnd('=')
        if (normalised.isEmpty()) return null
        if (normalised.length % 4 == 1) return null
        val padded = normalised + "=".repeat((4 - normalised.length % 4) % 4)
        return try {
            Base64.getDecoder().decode(padded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** `ChannelSet { settings = 1 (repeated), lora_config = 2 }`. */
    private fun parseChannelSet(d: ByteArray): Parsed? {
        val channels = ArrayList<Channel>()
        var radio: Radio? = null
        for (f in LoRaProto.parseFields(d)) {
            when {
                f.number == 1 && f.wire == 2 -> parseChannelSettings(f.payload)?.let(channels::add)
                f.number == 2 && f.wire == 2 -> radio = parseLoRaConfig(f.payload)
            }
        }
        // No channels at all is not a channel link, whatever else it decoded to.
        // Random bytes parse as protobuf far more often than feels right, so this
        // is the check that stops a stray paste being reported as a valid mesh.
        if (channels.isEmpty()) return null
        return Parsed(channels.take(MAX_CHANNELS), radio)
    }

    /** `ChannelSettings { psk = 2, name = 3 }`. */
    private fun parseChannelSettings(d: ByteArray): Channel? {
        var psk = ByteArray(0)
        var name = ""
        var sawAnything = false
        for (f in LoRaProto.parseFields(d)) {
            when {
                f.number == 2 && f.wire == 2 -> { psk = f.payload; sawAnything = true }
                f.number == 3 && f.wire == 2 -> {
                    name = f.payload.toString(Charsets.UTF_8); sawAnything = true
                }
            }
        }
        // An entry carrying NEITHER a name nor a key is a disabled slot some
        // encoder wrote out anyway; it is not a channel anyone can join.
        if (!sawAnything) return null
        return Channel(name = name, psk = psk)
    }

    /** `LoRaConfig { modem_preset = 2, region = 7, hop_limit = 8 }`. */
    private fun parseLoRaConfig(d: ByteArray): Radio {
        var region: Long? = null
        var preset: Long? = null
        var hop: Long? = null
        for (f in LoRaProto.parseFields(d)) {
            when {
                f.number == 2 && f.wire == 0 -> preset = f.varintValue
                f.number == 7 && f.wire == 0 -> region = f.varintValue
                f.number == 8 && f.wire == 0 -> hop = f.varintValue
            }
        }
        return Radio(regionRaw = region, presetRaw = preset, hopLimit = hop)
    }

    // ------------------------------------------------------------------ build

    /**
     * A shareable link for [channels], primary first.
     *
     * [radio] is omitted unless [Radio.isComplete]: a link carrying a partial
     * LoRaConfig would reconfigure the recipient's radio to zeros on import, for
     * the reason `adminSetLoRaConfig` documents. Better to share a channel alone
     * than to break the radio of everyone who scans it.
     */
    fun buildUrl(channels: List<Channel>, radio: Radio? = null): String {
        require(channels.isNotEmpty()) { "a channel link with no channels is not a link" }
        val set = ProtoWriter()
        for (c in channels.take(MAX_CHANNELS)) {
            val settings = ProtoWriter()
                .bytes(2, c.psk)
                .bytes(3, LoRaChannel.clampName(c.name).toByteArray(Charsets.UTF_8))
                .data
            set.bytes(1, settings)
        }
        if (radio != null && radio.isComplete) {
            val lora = ProtoWriter()
                .varint(1, 1)                   // use_preset = true
                .varint(2, radio.presetRaw!!)   // modem_preset
                .varint(7, radio.regionRaw!!)   // region
                .varint(8, radio.hopLimit!!)    // hop_limit
                .data
            set.bytes(2, lora)
        }
        return URL_PREFIX + encodeBase64Url(set.data)
    }

    /** base64url, padding stripped — byte-for-byte what `getURL` produces. */
    private fun encodeBase64Url(d: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(d)
}
