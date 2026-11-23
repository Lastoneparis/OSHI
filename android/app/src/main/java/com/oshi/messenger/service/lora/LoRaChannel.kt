package com.oshi.messenger.service.lora

import java.util.Base64

/**
 * LoRaChannel — the Meshtastic PRIMARY channel, which is what a user means by
 * "the network".
 *
 * WHY THIS EXISTS
 * ---------------
 * Asked for by a user in Chile (Play review, 2026-10-25): OSHI could set a
 * node's region but had nowhere to say WHICH mesh it joins, so anyone who
 * wanted to be on their country's community channel had to install a second
 * Meshtastic client, set the channel there, and come back. A node's channel is
 * node configuration, exactly like its region and its name, and all three now
 * live on the same screen.
 *
 * WHAT A CHANNEL IS, ON THE WIRE
 * ------------------------------
 * Two fields decide whether two radios hear each other at all:
 *
 *   • `name`  — up to 11 bytes. The firmware stores it in a `char[12]`, so a
 *     12th byte is not "mostly fine", it is truncated by the node and the two
 *     ends then disagree about the channel hash. Clamped here, by BYTES, for
 *     the same reason `clampShortName` counts bytes: one accented character is
 *     two of them and a truncated UTF-8 sequence is not a string.
 *   • `psk`   — the pre-shared key. A single byte is not a key, it is a key
 *     INDEX: 0 means no encryption at all, and 1 means "the default key", the
 *     public one every stock Meshtastic install shares (the `AQ==` everybody
 *     sees in channel URLs). 16 or 32 bytes is a real AES key.
 *
 * An EMPTY name with the default key is precisely what the Meshtastic app
 * ships: the channel it calls LongFast. That is why blank is not an error here
 * — it is the default the user asked us to match.
 */
object LoRaChannel {

    /** The firmware's `ChannelSettings.name` is a `char[12]`: 11 bytes + NUL. */
    const val NAME_MAX_BYTES = 11

    /**
     * What a stock Meshtastic node calls its unnamed primary channel. Display
     * only — writing this as the NAME would produce a different channel hash
     * from every default node out there, which is the opposite of what the user
     * asking for "LongFast by default" wants.
     */
    const val DEFAULT_NAME = "LongFast"

    /**
     * The default key: ONE byte with value 1, the key index that means "the
     * public default key". This is `AQ==` in base64, the string the Meshtastic
     * app puts in a default channel URL.
     *
     * A fresh array every call: a shared `ByteArray` constant is mutable and
     * this one travels into a packet builder.
     */
    val DEFAULT_PSK: ByteArray get() = byteArrayOf(1)

    /** Key sizes the firmware accepts: an index byte, AES-128, or AES-256. */
    private val PSK_SIZES = setOf(1, 16, 32)

    /**
     * [raw] clamped to [NAME_MAX_BYTES] UTF-8 bytes, never cutting a code point
     * in half. Blank stays blank — that is the LongFast default, not an error.
     */
    fun clampName(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.toByteArray(Charsets.UTF_8).size <= NAME_MAX_BYTES) return trimmed
        val out = StringBuilder()
        var used = 0
        var i = 0
        while (i < trimmed.length) {
            val cp = trimmed.codePointAt(i)
            val chars = Character.charCount(cp)
            val piece = trimmed.substring(i, i + chars)
            val n = piece.toByteArray(Charsets.UTF_8).size
            if (used + n > NAME_MAX_BYTES) break
            out.append(piece)
            used += n
            i += chars
        }
        return out.toString()
    }

    /**
     * The key to write, or null when [raw] is not one.
     *
     * Blank gives [DEFAULT_PSK] — the public default key, which is the answer
     * for a community channel that is not encrypted with its own secret.
     *
     * Accepts both base64 alphabets and tolerates missing padding: a Meshtastic
     * channel URL carries the key base64URL-encoded and unpadded, and that URL
     * is exactly what a user pastes.
     *
     * Returning null rather than falling back to the default on purpose. A
     * mistyped key that silently became the public key would put a message the
     * user believed was privately keyed onto the open channel.
     */
    fun parsePsk(raw: String): ByteArray? {
        val t = raw.trim()
        if (t.isEmpty()) return DEFAULT_PSK
        val normalised = t.replace('-', '+').replace('_', '/').replace("=", "")
        if (normalised.isEmpty()) return null
        val padded = normalised + "=".repeat((4 - normalised.length % 4) % 4)
        val bytes = try {
            Base64.getDecoder().decode(padded)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return if (bytes.size in PSK_SIZES) bytes else null
    }

    /** What to show for a channel name that may be blank. */
    fun displayName(name: String): String = name.trim().ifEmpty { DEFAULT_NAME }
}
