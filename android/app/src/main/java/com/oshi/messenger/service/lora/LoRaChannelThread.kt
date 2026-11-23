package com.oshi.messenger.service.lora

/**
 * LoRaChannelThread — a LoRa channel as a CONVERSATION, the way Meshtastic shows one.
 *
 * WHY THIS EXISTS
 * ---------------
 * A LoRa channel is a room: everyone in radio range on the same channel hears
 * everyone else, and the whole point is that several people talk at once. OSHI
 * stored none of that. `handleLoRaInteropText` wrote every inbound packet as
 * `senderPublicKey = <that node>, recipientPublicKey = me`, which is a PRIVATE
 * conversation, so three people talking on LongFast produced three separate
 * one-to-one threads that each looked like a direct message. There was nowhere
 * in the app to see the channel itself, and no way to tell a public broadcast
 * from someone writing to you alone.
 *
 * HOW A CHANNEL BECOMES A THREAD WITHOUT A MIGRATION
 * --------------------------------------------------
 * A thread is selected by `MessageDao.getMessagesForContact`:
 *
 *     WHERE senderPublicKey = :key OR recipientPublicKey = :key
 *
 * So a broadcast stored as `sender = <node>, recipient = <channel key>` already
 * lands in the channel's thread, and arrives there carrying the real author in
 * `senderPublicKey`. Every speaker stays individually identifiable with no new
 * column and no schema change; the room is the recipient, the person is the
 * sender, which is what those two fields mean anyway.
 *
 * The cost of that choice, and it has to be paid explicitly: the same row also
 * matches the SENDER's own one-to-one thread, because the query is an `OR`.
 * [isChannelKey] exists so the one-to-one side can exclude these rows, and a
 * channel message therefore appears in the channel and nowhere else.
 *
 * WHY NOT A GROUP
 * ---------------
 * Because a LoRa channel is not one. An OSHI group has a member list, an admin,
 * invitations and end-to-end encryption; a public LoRa channel has no
 * membership at all, and on the default key no meaningful encryption either —
 * anyone in range is in it. Modelling it as a group would require inventing a
 * roster nobody agreed to and would imply a privacy guarantee that does not
 * exist. The participants of a channel are simply whoever has been heard on it,
 * which is derived from the messages rather than declared.
 */
object LoRaChannelThread {

    /**
     * Prefix for the synthetic key. Shares the `lora` family with
     * `LoRaIdentityBridge`'s `lora!<nodehex>` pseudo-key so anything that
     * already reasons about "not a real public key" keeps working, and is
     * distinct from it so the two can never be confused.
     */
    /**
     * A NAMED channel: `lorachan!manif`.
     *
     * The name follows the `!`, so whatever the user typed cannot be mistaken
     * for the slot form below, even if they type "lorachan#0" as a channel name.
     */
    const val PREFIX = "lorachan!"

    /**
     * An UNNAMED channel, by slot: `lorachan#3`.
     *
     * A separate prefix rather than `PREFIX + "#3"`, because a channel the user
     * NAMED "#3" would otherwise produce exactly the same key as the unnamed
     * slot 3 and the two rooms would merge. Caught by
     * `LoRaChannelThreadTest."a channel whose name looks like a slot is not read
     * as one"`, which failed when this was one prefix.
     *
     * The two differ at character nine, so they can never overlap.
     */
    const val PREFIX_SLOT = "lorachan#"

    /**
     * The thread key for a channel.
     *
     * Keyed on the NAME when there is one, because that is the part two radios
     * actually agree on: `MeshPacket.channel` is an index into the node's own
     * table and the proto says so out loud, "the index is local to each node, so
     * it has no meaning when sent between nodes". Keying on the index alone
     * would move every message to a different room the moment the user
     * reordered their channels.
     *
     * Falls back to the index when the name is blank, which is the stock
     * unnamed primary channel. Lower-cased so a channel does not split into two
     * rooms over capitalisation.
     */
    fun keyFor(channelIndex: Int, channelName: String?): String {
        val name = channelName?.trim().orEmpty()
        return if (name.isEmpty()) "$PREFIX_SLOT$channelIndex"
               else PREFIX + name.lowercase()
    }

    /** True for a key produced by [keyFor]. */
    fun isChannelKey(key: String): Boolean =
        key.startsWith(PREFIX) || key.startsWith(PREFIX_SLOT)

    /**
     * What to call the room on screen.
     *
     * An unnamed primary channel is shown as "LongFast", the name every stock
     * Meshtastic install uses for it, rather than as "#0" — see
     * [LoRaChannel.DEFAULT_NAME]. A secondary slot with no name keeps its
     * number, because there is nothing truer to call it.
     */
    fun displayName(key: String): String {
        if (key.startsWith(PREFIX)) return key.removePrefix(PREFIX)
        if (!key.startsWith(PREFIX_SLOT)) return key
        val index = key.removePrefix(PREFIX_SLOT).toIntOrNull() ?: return key
        return if (index == 0) LoRaChannel.DEFAULT_NAME else "#$index"
    }

    /**
     * __CHANNEL_ROOMS_ALL_SENT_ON_SLOT_ZERO_2026_11_07__ The slot on THIS radio a
     * room key refers to, or null when this radio has no such channel.
     *
     * The inverse of [keyFor], and the piece that was missing on both platforms:
     * `sendToLoRaChannel` broadcast every room's text without ever setting
     * `MeshPacket.channel`, so a message typed in a private room went out on
     * slot 0 — the primary, normally LongFast and normally public. Wrong
     * delivery, and for a named private channel a disclosure.
     *
     * Returns null rather than 0 when the channel is absent, because "I do not
     * know which slot" must never be answered with "the public one". The caller
     * refuses the send.
     *
     * Matching is by NAME for a named key: that is what the key is built from
     * and what two radios agree on, while the index is node-local and the user
     * may have reordered their slots since the room was created.
     *
     * Mirrors `OSHI/LoRaChannelThread.swift` `channelIndex(forKey:in:)`.
     */
    fun channelIndexFor(key: String, channels: List<LoRaPacketParser.NodeChannel>): Int? {
        val usable = channels.filterNot { it.isDisabled }
        if (key.startsWith(PREFIX)) {
            val wanted = key.removePrefix(PREFIX).trim().lowercase()
            if (wanted.isEmpty()) return null
            return usable.firstOrNull { it.name.trim().lowercase() == wanted }?.index
        }
        if (!key.startsWith(PREFIX_SLOT)) return null
        val index = key.removePrefix(PREFIX_SLOT).toIntOrNull() ?: return null
        // A slot key is only minted for an UNNAMED channel, so it stays valid
        // only while that slot is still unnamed. Once the user names it, [keyFor]
        // produces the named form and this room is the stale one — matching it by
        // number anyway would put stale text on a repurposed channel.
        return usable.firstOrNull { it.index == index && it.name.trim().isEmpty() }?.index
    }
}
