package com.oshi.messenger.util

/**
 * 🔒 __CONTACT_CARD_ALIAS_2026_11_20__ The name a received contact card may give a NEW
 * contact when the person taps it.
 *
 * The alias in a card is chosen by whoever SENT the card. Saved as is, a card for the
 * sender's own key labelled "Mom" (or the exact name of any existing contact) creates a
 * second "Mom" in the list — a one-tap impersonation. Rule (desktop 25e1c3a7, iOS
 * 7a27cbf4): control/format characters stripped, at most 64 chars, and a name that is
 * blank or already used by ANOTHER contact (case-insensitive) is replaced by the key
 * prefix, so the person names it themselves.
 */
object ContactCardAlias {
    const val MAX_LENGTH = 64

    fun safeAlias(cardAlias: String?, publicKey: String, existingAliases: Collection<String>): String {
        val fallback = publicKey.take(8) + "..."
        val cleaned = cardAlias.orEmpty()
            .filter { c -> !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT.toInt() }
            .trim()
            .take(MAX_LENGTH)
            .trim()
        if (cleaned.isEmpty()) return fallback
        val taken = existingAliases.any { it.trim().equals(cleaned, ignoreCase = true) }
        return if (taken) fallback else cleaned
    }
}
