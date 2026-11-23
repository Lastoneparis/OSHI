package com.oshi.messenger.data.model

import org.json.JSONObject

/**
 * The bio and accent colour a person sets on their own profile, carried on the
 * `📸PROFILE_UPDATE📸` payload to their contacts.
 *
 * __PROFILE_BIO_2026_11_02__ (N4) Asked for as "a biography (ephemeral or
 * permanent), and a colour chosen by the user" on the contact card.
 *
 * ADDITIVE, like every other field on this payload, and for the same reason
 * spelled out beside `pqPublicKey` and `displayName`: Android reads the
 * payload with `JSONObject.opt*`, which ignores keys it was not asked for, and
 * iOS decodes it into a Codable struct, and `JSONDecoder` ignores unknown keys
 * by default. So a build that predates these keys parses the payload exactly
 * as it does today, and one that postdates it simply learns nothing about a
 * peer who sends none.
 *
 * EPHEMERAL IS AN EXPIRY, NOT A SECOND FIELD. "Ephemeral or permanent" is one
 * bio with an optional end time: `bioUntil` absent means permanent. Modelling
 * it as two fields would allow a state that means nothing — an ephemeral bio
 * with no expiry — and would need a rule to resolve a permanent and an
 * ephemeral one set at once.
 *
 * ⚠️ EXPIRY IS A COURTESY, NOT A GUARANTEE, and the UI must never imply
 * otherwise. The peer holds the text; nothing here can reach into their device
 * and delete it. All this does is stop showing it, on a device that chooses to
 * honour the field. It is the same honesty the disappearing-message code owes
 * its users, and the reason the string shown next to the control says "hidden
 * after", not "deleted".
 */
object ProfileBioWire {

    const val BIO_KEY = "bio"
    const val BIO_UNTIL_KEY = "bioUntil"
    const val COLOR_KEY = "accentColor"

    /**
     * Long enough for a sentence or two, short enough that it cannot be used
     * as a covert channel of any size, and short enough to render on a card
     * without scrolling. Counted in CODE POINTS, so an emoji costs one.
     */
    const val MAX_CODE_POINTS = 140

    sealed class Read {
        /** The key was absent: the peer said nothing, so change nothing. */
        object Absent : Read()
        /** The key was present and empty: the peer cleared their bio. */
        object Clear : Read()
        data class Set(val text: String, val until: Long?) : Read()
    }

    /**
     * Trim, collapse runs of whitespace, drop control characters, cap length.
     *
     * Newlines are collapsed rather than kept: a bio is one line on a card, and
     * a peer who sends forty blank lines must not be able to push the rest of
     * the card off the screen.
     */
    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw
            .replace(Regex("[\\p{Cc}\\p{Cf}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isEmpty()) return null
        val cps = cleaned.codePointCount(0, cleaned.length)
        if (cps <= MAX_CODE_POINTS) return cleaned
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_CODE_POINTS)).trim()
    }

    /**
     * `#RRGGBB`, uppercased. Anything else is refused rather than coerced: this
     * string is parsed into a colour on the receiving device, and a value that
     * is nearly a colour is how a crash or a transparent name gets in.
     */
    fun sanitizeColor(raw: String?): String? {
        val s = raw?.trim()?.uppercase() ?: return null
        return if (Regex("^#[0-9A-F]{6}$").matches(s)) s else null
    }

    /** Writes the keys only when there is something to say. */
    fun stamp(json: JSONObject, bio: String?, bioUntil: Long?, color: String?): JSONObject {
        sanitize(bio)?.let { text ->
            json.put(BIO_KEY, text)
            // Only meaningful with a bio, and only when it is in the future:
            // stamping an expiry already past would ask the peer to hide text
            // the moment they receive it, which is just not sending it.
            if (bioUntil != null && bioUntil > System.currentTimeMillis()) {
                json.put(BIO_UNTIL_KEY, bioUntil)
            }
        }
        sanitizeColor(color)?.let { json.put(COLOR_KEY, it) }
        return json
    }

    fun read(json: JSONObject): Read {
        if (!json.has(BIO_KEY)) return Read.Absent
        val text = sanitize(json.optString(BIO_KEY)) ?: return Read.Clear
        val until = if (json.has(BIO_UNTIL_KEY)) json.optLong(BIO_UNTIL_KEY).takeIf { it > 0 } else null
        return Read.Set(text, until)
    }

    fun readColor(json: JSONObject): String? =
        if (json.has(COLOR_KEY)) sanitizeColor(json.optString(COLOR_KEY)) else null

    /** Whether a stored bio should still be shown. Absent expiry = permanent. */
    fun isVisible(until: Long?, now: Long = System.currentTimeMillis()): Boolean =
        until == null || until > now
}
