package com.oshi.messenger.data.model

/**
 * Pinned conversations sort to the top — N18's first half.
 *
 * __PIN_A_CONVERSATION_2026_10_28__ (reported by a user: "pouvoir épingler des personnes ou
 * groupes dans le menu principal ainsi que dans les messages de conversations")
 *
 * The second half of that request already existed: a MESSAGE can be pinned inside a
 * conversation. A CONVERSATION could not be pinned anywhere, on either platform.
 *
 * Pure, and takes the ids rather than reading a store, because the only part worth testing
 * is the ORDER — and the two properties below are the ones a hand-rolled `sortedBy` gets
 * wrong:
 *
 *  • **the existing order survives inside each group.** The list arrives already sorted by
 *    the repository (most recent first) and pinning must not reshuffle what it does not
 *    pin. `sortedBy` in Kotlin is STABLE, which is what makes a single key enough; written
 *    down because "sort by pinned" silently becoming unstable is a change nobody sees in
 *    review and everybody sees in the app.
 *  • **a pin for a conversation that no longer exists is inert.** Someone pins a thread,
 *    deletes it, and the id lingers in the store. It must not resurrect a row or leave a
 *    hole at the top.
 */
object ConversationPinOrder {

    /**
     * [items] in their existing order, with the pinned ones moved to the front.
     *
     * @param idOf how to read a conversation's id — the list holds a different type on
     *   every screen, and this object should not know any of them.
     */
    fun <T> apply(items: List<T>, pinnedIds: Set<String>, idOf: (T) -> String): List<T> {
        if (pinnedIds.isEmpty()) return items
        // `sortedBy` is a stable sort: equal keys keep their relative order, so everything
        // the repository already decided about recency is preserved within each half.
        return items.sortedBy { if (pinnedIds.contains(idOf(it))) 0 else 1 }
    }

    /** Is this conversation pinned? Centralised so no screen re-derives it from a raw set. */
    fun isPinned(conversationId: String, pinnedIds: Set<String>): Boolean =
        pinnedIds.contains(conversationId)

    /**
     * Toggle, returning the new set.
     *
     * Returns a NEW set rather than mutating: the caller persists it, and a mutation that
     * was written to the store but not to the flow — or the reverse — is how a pin appears
     * to work until the app restarts.
     */
    fun toggled(conversationId: String, pinnedIds: Set<String>): Set<String> =
        if (pinnedIds.contains(conversationId)) pinnedIds - conversationId
        else pinnedIds + conversationId
}
