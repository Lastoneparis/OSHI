package com.oshi.messenger.util

import org.json.JSONObject

/**
 * __MENTIONS_2026_09_23__ The admitted `@name` targets of each group message, keyed by message
 * id — a side table beside the Room row, the same shape as [TickerQuoteSnapshots], so adding
 * mentions needed NO schema migration of `group_messages`.
 *
 * Insertion-ordered and capped at [cap]: the oldest message's mentions go first. Pure JVM, so
 * the JVM tests exercise the exact persistence format (`{"<messageId>":[{"publicKey","name"}]}`,
 * the value being [MentionWire.render]'s bytes).
 */
class GroupMentionIndex(private val cap: Int = DEFAULT_CAP) {

    private val map = LinkedHashMap<String, List<MentionWire.Mention>>()

    @Synchronized
    fun get(messageId: String): List<MentionWire.Mention> =
        map[key(messageId)].orEmpty()

    /** Records [mentions] for [messageId]; an empty list records nothing. */
    @Synchronized
    fun put(messageId: String, mentions: List<MentionWire.Mention>) {
        if (mentions.isEmpty()) return
        val k = key(messageId)
        map.remove(k)
        map[k] = mentions.take(MentionWire.MAX_MENTIONS)
        while (map.size > cap) map.remove(map.keys.first())
    }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun serialize(): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in map) {
            val rendered = MentionWire.render(v) ?: continue
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(com.oshi.messenger.network.v2.OSHICryptoV2.jsonEscape(k)).append("\":").append(rendered)
        }
        return sb.append('}').toString()
    }

    companion object {
        const val DEFAULT_CAP = 5_000

        /** Group-message ids are compared case-insensitively everywhere (iOS uppercases UUIDs). */
        private fun key(messageId: String) = messageId.uppercase(java.util.Locale.US)

        /** A corrupt or missing store is an empty index, never a crash. */
        fun deserialize(raw: String?, cap: Int = DEFAULT_CAP): GroupMentionIndex {
            val index = GroupMentionIndex(cap)
            if (raw.isNullOrBlank()) return index
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return index
            for (k in o.keys()) index.put(k, MentionWire.parse(o.opt(k)))
            return index
        }
    }
}
