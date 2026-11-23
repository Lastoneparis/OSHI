package com.oshi.messenger.mail

/**
 * The mailbox's own address book — built LOCALLY from decrypted envelopes (from/to/cc),
 * never fetched from the server. There is deliberately no server-side contact store: the
 * mailbox already contains everyone this device has corresponded with, and only this
 * device can read it, so a separate directory would be one more place addresses could
 * leak from.
 */
object MailContacts {

    /** address (lowercased) -> the friendliest display name seen for it, sorted by address. */
    /**
     * @param own the account's OWN addresses (primary and aliases). They are skipped:
     *   every inbox message is addressed to you, so without this your own address is the
     *   most frequent "contact" in the book and the first thing autocomplete offers when
     *   you start typing a recipient — which is never what you meant.
     */
    fun harvest(
        messages: List<OshiMailClient.Message>,
        own: Set<String> = emptySet(),
    ): List<Pair<String, String>> {
        val mine = own.map { it.trim().lowercase() }.toSet()
        val seen = LinkedHashMap<String, String>()
        for (m in messages) {
            val e = m.envelope ?: continue
            for (field in listOf(e.from, e.to, e.cc)) {
                for (piece in field.split(",")) {
                    if (piece.isBlank()) continue
                    val address = MailMime.emailAddress(piece).trim().lowercase()
                    if (!address.contains("@") || address.length > 120) continue
                    if (address in mine) continue
                    val name = MailMime.displayName(piece)
                    val existing = seen[address]
                    if (existing == null || (name.isNotEmpty() && name != address)) seen[address] = name
                }
            }
        }
        return seen.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    /** Addresses whose text contains [prefix] — matched by address or by display name. */
    fun suggestions(contacts: List<Pair<String, String>>, prefix: String, limit: Int = 5): List<Pair<String, String>> {
        val p = prefix.trim().lowercase()
        if (p.isEmpty()) return emptyList()
        return contacts
            .filter { (address, name) -> address.contains(p) || name.lowercase().contains(p) }
            .take(limit)
    }
}
