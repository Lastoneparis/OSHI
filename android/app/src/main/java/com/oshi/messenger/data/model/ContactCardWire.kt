package com.oshi.messenger.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The bytes a shared contact card travels as, in BOTH directions.
 *
 * ## Why this file exists
 *
 * A contact card is not a new message type: it is an ordinary media message whose
 * `mediaType` is `contact` and whose payload bytes are the JSON body below. iOS
 * builds those bytes in `MediaManager.createContactCard`
 * (`OSHI/MediaManager.swift:867-897`) and reads them back in `decodeContactCard`
 * (`:901-908`), which decodes
 *
 * ```swift
 * struct ContactCard: Codable {          // OSHI/MediaManager.swift:913-917
 *     let publicKey: String
 *     let alias: String?
 *     let timestamp: Date                // NOT optional
 * }
 * ```
 *
 * with a **bare `JSONDecoder()`**. Two consequences decide everything here, and
 * both are read off iOS's own encoder/decoder rather than inferred:
 *
 * 1. **`timestamp` is required.** Android's [ContactPayload] never carried it, so
 *    an Android→iPhone card threw `keyNotFound` inside the `try?` at
 *    `MediaMessageView.swift:65` and the iPhone drew a blank
 *    `person.crop.circle` placeholder. [encode] therefore always emits it.
 *
 * 2. **A bare `JSONDecoder` has no `dateDecodingStrategy`**, i.e. `.deferredToDate`
 *    — a `Date` is a **JSON number of seconds since 2001-01-01**, *not* an
 *    ISO-8601 string. Handing iOS `"2026-08-24T09:00:00Z"` here would throw
 *    `typeMismatch` and sink the card exactly the same way the missing key did.
 *    The offset is the same [APPLE_EPOCH_OFFSET_SECONDS] the location and
 *    check-in wires already use (`network/location/LocationSharingManager.kt`,
 *    `data/model/CheckInMessage.kt`).
 *
 * The reverse direction has its own trap, already fixed but re-homed here so both
 * halves live in one file: `Json.Default` has `ignoreUnknownKeys = false`, so
 * iOS's `timestamp` used to blow up the whole decode and the card fell through to
 * a raw-text bubble. [decode] is lenient, and additionally understands the vCard
 * shape [com.oshi.messenger.service.ContactShareManager.exportToVCard] emits.
 *
 * Everything here is a pure function of its arguments — no Android types — so it
 * is unit-testable on the JVM against hand-written literals rather than against
 * itself.
 */
object ContactCardWire {

    /** Seconds between the Unix epoch (1970-01-01) and Apple's reference date (2001-01-01). */
    const val APPLE_EPOCH_OFFSET_SECONDS = 978307200.0

    /**
     * Lenient reader. Unknown keys must degrade a FIELD, never the whole card:
     * `timestamp` was the first extra key iOS sent and it will not be the last.
     */
    private val reader = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Unix millis → Apple reference-date seconds. */
    fun millisToAppleSeconds(millis: Long): Double =
        (millis / 1000.0) - APPLE_EPOCH_OFFSET_SECONDS

    /**
     * Apple reference-date seconds → Unix millis.
     *
     * The `> 1e10` guard is the same legacy escape hatch
     * `LocationMessage.appleTimestampToUnixMillis` carries: an older Android build
     * that wrote raw Unix millis into this field would otherwise be read as a date
     * roughly 300 000 years from now.
     */
    fun appleSecondsToMillis(seconds: Double): Long =
        if (seconds > 10_000_000_000.0) seconds.toLong()
        else ((seconds + APPLE_EPOCH_OFFSET_SECONDS) * 1000.0).toLong()

    /**
     * The exact JSON body an iPhone's `decodeContactCard` accepts.
     *
     * Built key by key rather than by `Json.encodeToString(payload)` on purpose:
     * kotlinx omits a property that equals its declared default, so a
     * `timestamp` with a default would silently vanish from the wire on the one
     * value that mattered and hand iOS back its `keyNotFound`. Explicit
     * construction cannot do that.
     *
     * `alias` is omitted when blank — iOS declares it `String?` and its
     * synthesized decoder uses `decodeIfPresent`, so absent and `null` are the
     * same thing to it. `avatarUrl` is deliberately NOT emitted: iOS has never
     * heard of it, and while its synthesized `Codable` ignores unknown keys, a
     * field no reader on either platform consumes is just weight on the wire.
     */
    fun encode(publicKey: String, alias: String?, timestampMillis: Long): String =
        buildJsonObject {
            put("publicKey", JsonPrimitive(publicKey))
            alias?.takeIf { it.isNotBlank() }?.let { put("alias", JsonPrimitive(it)) }
            put("timestamp", JsonPrimitive(millisToAppleSeconds(timestampMillis)))
        }.toString()

    /**
     * The bytes to put on the wire for a CONTACT attachment we are about to send,
     * whatever produced them locally.
     *
     * __CONTACT_CARD_COMPAT_2026_09_27__ Reported: "I received a kind of contact
     * picture of 75 bytes but nothing I can click." 75 bytes is exactly
     * `{"publicKey":"eAbM…E3A=","alias":"Hugo"}` — the welcome card
     * `WelcomeMessage.contactCardJson` used to build with `Json.encodeToString`,
     * which drops the null `timestamp`. The row is stored with those bytes, and
     * FORWARDING it (`ForwardPickerViewModel.forward` → `sendMediaMessage`, or
     * `GroupManager.sendGroupMessage`) sent them as-is; every shipped iPhone
     * decodes with a synthesized `Codable` that requires `timestamp`, threw
     * `keyNotFound`, and drew a grey `person.crop.circle` square with no action.
     *
     * Store builds on iOS cannot be changed, so the fix on this side is to never
     * let anything but [encode]'s shape leave: anything [decode] understands (old
     * welcome card, a card received from an older build, a vCard) is re-encoded
     * with a `timestamp` — the original one when present. Bytes [decode] cannot
     * read are returned untouched, so nothing is lost.
     */
    fun canonicalizeForWire(raw: ByteArray, nowMillis: Long): ByteArray {
        val card = decode(String(raw, Charsets.UTF_8)) ?: return raw
        val millis = card.timestamp?.let { appleSecondsToMillis(it) } ?: nowMillis
        return encode(card.publicKey, card.alias, millis).toByteArray(Charsets.UTF_8)
    }

    /**
     * Read a contact card back out of a payload, whatever platform wrote it.
     *
     * Order matters: JSON first (that is what both iOS and Android send today),
     * vCard second (what [com.oshi.messenger.service.ContactShareManager.shareContact]
     * hands to the system share sheet, and what an older peer may forward back).
     * Returns `null` when the bytes are neither, so the caller can fall back to a
     * text bubble instead of drawing an empty card.
     */
    fun decode(raw: String): ContactPayload? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            runCatching {
                val obj = reader.parseToJsonElement(trimmed) as? JsonObject ?: return@runCatching null
                val pk = obj["publicKey"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: return@runCatching null
                ContactPayload(
                    publicKey = pk,
                    alias = obj["alias"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                    avatarUrl = obj["avatarUrl"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                    timestamp = obj["timestamp"]?.jsonPrimitive?.doubleOrNull
                )
            }.getOrNull()?.let { return it }
        }
        if (trimmed.contains("BEGIN:VCARD")) {
            var pk: String? = null
            var name: String? = null
            for (line in trimmed.lines()) {
                val t = line.trim()
                when {
                    t.startsWith(VCARD_KEY_NOTE) -> pk = t.removePrefix(VCARD_KEY_NOTE).trim()
                    t.startsWith("FN:") -> name = t.removePrefix("FN:").trim()
                }
            }
            return pk?.takeIf { it.isNotBlank() }
                ?.let { ContactPayload(publicKey = it, alias = name?.takeIf { n -> n.isNotBlank() }) }
        }
        return null
    }

    private const val VCARD_KEY_NOTE = "NOTE:OSHI Public Key:"
}
