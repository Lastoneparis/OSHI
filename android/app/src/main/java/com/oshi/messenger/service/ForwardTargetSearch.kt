package com.oshi.messenger.service

/**
 * ForwardTargetSearch — matching and ordering for the forward picker.
 *
 * WHY THIS EXISTS
 * ---------------
 * Reported: "when I click transfer, make sure there is a box to search the
 * target, by alias and also by public key." Android had a search box that
 * matched `it.name` and nothing else, and iOS had no box at all. Neither could
 * find anyone by key.
 *
 * `name` could never have worked for a key search even by accident.
 * `PeerNickname.resolve` returns alias → shared nickname → `publicKey.take(8) +
 * "..."`, so the most key material a name ever holds is 8 characters, and only
 * for a contact with no alias at all. Pasting a full 64-character key returned
 * zero results — the worst possible answer, because the user has the exact
 * identifier in hand and the app says "no such person".
 *
 * WHY ALIAS AND KEY MATCH WITH DIFFERENT CASE RULES
 * -------------------------------------------------
 * Deliberate, and the asymmetry is a safety property rather than an oversight.
 *
 * - An alias is a human name, so matching is case-insensitive and substring:
 *   typing "nat" finds "Anatole".
 * - A public key is base64, where case is SIGNIFICANT — `a` and `A` are
 *   different bytes, so two distinct people can have keys differing only in
 *   case. Matching keys case-insensitively would let a typed prefix surface the
 *   wrong contact, and the action at the end of this list is "forward a
 *   private message to them". A false negative makes someone retype; a false
 *   positive sends their message to a stranger. So key matching is
 *   case-sensitive, and that costs the user nothing: a key is pasted, never
 *   typed from memory.
 *
 * Keys ARE normalised before comparison (base64url → base64, padding dropped)
 * because the same key is legitimately spelled several ways across platforms
 * and transports — the equivalence [CallAdmissionPolicy.normalizeKey] already
 * defines and that `MessageRepository.normalizeBase64Key` uses. Without it a
 * key copied out of a QR scan would not match the same key stored from a mesh
 * packet.
 *
 * Key matching is PREFIX, not substring: a key's leading characters are what a
 * person sees in the UI and what they would paste a fragment of. A substring
 * match deep inside a base64 blob is almost certainly coincidence, and a
 * coincidence here forwards a message to the wrong person.
 */
object ForwardTargetSearch {

    /** Below this many characters, a query is treated as an alias only. */
    const val MIN_KEY_QUERY = 4

    /**
     * Canonical form for key comparison. Same equivalence as
     * [CallAdmissionPolicy.normalizeKey]; duplicated as a call rather than a
     * reimplementation so the two cannot drift.
     */
    fun normalizeKey(key: String): String = CallAdmissionPolicy.normalizeKey(key)

    /**
     * Does this target match [query]?
     *
     * @param name the display name, as shown in the row
     * @param publicKey the target's key, or null for a group (groups have no key)
     */
    fun matches(query: String, name: String, publicKey: String?): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true

        // Alias: case-insensitive substring.
        if (name.contains(trimmed, ignoreCase = true)) return true

        // Key: normalised, case-SENSITIVE prefix. See the class comment.
        if (publicKey != null && trimmed.length >= MIN_KEY_QUERY) {
            val normalizedQuery = normalizeKey(trimmed)
            if (normalizedQuery.isNotEmpty() &&
                normalizeKey(publicKey).startsWith(normalizedQuery)
            ) {
                return true
            }
        }
        return false
    }
}
