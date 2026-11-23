import Foundation

/// A LoRa channel as a CONVERSATION, the way Meshtastic shows one.
///
/// Straight port of `OSHI-Android/.../service/lora/LoRaChannelThread.kt`
/// (commit `d606a977`), where the reasoning is written out in full. The short
/// version, because it is the part that has to stay true on both platforms:
///
/// ## Why this exists
///
/// A LoRa channel is a room. Everyone in radio range on the same channel hears
/// everyone else, and the whole point is that several people talk at once. OSHI
/// stored none of that: every inbound packet was filed as "that node → me",
/// which is a PRIVATE conversation, so three people talking on LongFast
/// produced three separate one-to-one threads that each looked like a direct
/// message. There was nowhere in the app to see the channel itself, and no way
/// to tell a public broadcast from someone writing to you alone.
///
/// ## How a channel becomes a thread with no migration
///
/// A broadcast is stored with the CHANNEL as the recipient and the real author
/// as the sender. Both fields already exist and that is what they mean, so
/// every speaker stays individually identifiable with no new column.
///
/// The cost has to be paid explicitly: a thread is selected by sender OR
/// recipient, so the same row also matches the speaker's own one-to-one thread.
/// ``isChannelKey(_:)`` exists so the one-to-one side can exclude these rows,
/// and a channel message therefore appears in the channel and nowhere else.
/// That second copy is the dangerous one — it invites a reply the user believes
/// only one person can read.
///
/// ## Why not a group
///
/// Because a LoRa channel is not one. An OSHI group has a member list, an
/// admin, invitations and end-to-end encryption; a public LoRa channel has no
/// membership at all, and on the default key no meaningful encryption either —
/// anyone in range is in it. Modelling it as a group would require inventing a
/// roster nobody agreed to and would imply a privacy guarantee that does not
/// exist.
enum LoRaChannelThread {

    /// A NAMED channel: `lorachan!manif`.
    ///
    /// The name follows the `!`, so whatever the user typed cannot be mistaken
    /// for the slot form below.
    static let prefix = "lorachan!"

    /// An UNNAMED channel, by slot: `lorachan#3`.
    ///
    /// A separate prefix rather than `prefix + "#3"`, because a channel the user
    /// NAMED "#3" would otherwise produce exactly the same key as the unnamed
    /// slot 3 and the two rooms would merge silently. The Android test
    /// `LoRaChannelThreadTest` caught that before it shipped; the two spellings
    /// differ at character nine, so they can never overlap.
    static let slotPrefix = "lorachan#"

    /// The thread key for a channel.
    ///
    /// Keyed on the NAME when there is one, because that is the part two radios
    /// actually agree on: `MeshPacket.channel` is an index into the node's own
    /// table and the proto says so out loud — "the index is local to each node,
    /// so it has no meaning when sent between nodes". Keying on the index alone
    /// would move every message to a different room the moment the user
    /// reordered their channels.
    ///
    /// Falls back to the index when the name is blank, which is the stock
    /// unnamed primary channel. Lower-cased so a channel does not split into
    /// two rooms over capitalisation.
    static func key(channelIndex: Int, channelName: String?) -> String {
        let name = (channelName ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if name.isEmpty { return "\(slotPrefix)\(channelIndex)" }
        return prefix + name.lowercased()
    }

    /// True for a key produced by ``key(channelIndex:channelName:)``.
    static func isChannelKey(_ key: String) -> Bool {
        key.hasPrefix(prefix) || key.hasPrefix(slotPrefix)
    }

    /// What to call the room on screen.
    ///
    /// An unnamed primary channel is shown as "LongFast", the name every stock
    /// Meshtastic install uses for it, rather than as "#0". A secondary slot
    /// with no name keeps its number, because there is nothing truer to call it.
    static func displayName(_ key: String) -> String {
        if key.hasPrefix(prefix) { return String(key.dropFirst(prefix.count)) }
        guard key.hasPrefix(slotPrefix) else { return key }
        guard let index = Int(key.dropFirst(slotPrefix.count)) else { return key }
        return index == 0 ? LoRaChannel.defaultName : "#\(index)"
    }

    /// Who said this, in a channel room.
    ///
    /// A channel is a room with several people in it, so a bubble with no name
    /// on it is unattributed — which in a public channel is the one thing a
    /// reader cannot afford, because the speakers are strangers by default.
    ///
    /// Three sources, in descending order of what they actually tell the reader,
    /// and the last one always answers: the radio's long name, its short name,
    /// then the node id itself. A node id is not a friendly label, but it is
    /// TRUE and it is stable, which is more than an empty line is.
    ///
    /// A key that is not a `lora!<nodehex>` fallback belongs to a peer who
    /// proved an OSHI identity, so their contact alias is the right name and
    /// `MessageManager.resolveContactAlias` already knows it.
    static func speakerName(senderKey: String, nodes: [MeshtasticNode]) -> String {
        guard LoRaNodeIdentityMap.isFallbackConversationKey(senderKey),
              let num = LoRaNodeIdentityMap.nodeNumber(fromFallbackKey: senderKey) else {
            let alias = MessageManager.resolveContactAlias(senderKey)
            return alias.isEmpty ? String(senderKey.prefix(8)) + "\u{2026}" : alias
        }
        if let node = nodes.first(where: { $0.num == num }) {
            let long = node.longName.trimmingCharacters(in: .whitespacesAndNewlines)
            if !long.isEmpty { return long }
            let short = node.shortName.trimmingCharacters(in: .whitespacesAndNewlines)
            if !short.isEmpty { return short }
        }
        return String(format: "!%08x", num)
    }

    /// The name of the slot a packet arrived on, from the radio's own channel
    /// table.
    ///
    /// Nil when the radio has not reported that slot yet — which is ordinary on
    /// a fresh connection, and is why the caller falls back to the slot form
    /// rather than inventing a name. A room that appeared under one name before
    /// the handshake and another after it would be two rooms.
    static func name(forIndex index: Int, in channels: [MeshtasticManager.NodeChannel]) -> String? {
        guard let slot = channels.first(where: { $0.index == index }), !slot.isDisabled else { return nil }
        let trimmed = slot.name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    /// __CHANNEL_ROOMS_ALL_SENT_ON_SLOT_ZERO_2026_11_07__ The slot on THIS radio
    /// that a room key refers to, or nil when this radio has no such channel.
    ///
    /// The inverse of ``key(channelIndex:channelName:)``, and the piece that was
    /// missing: `sendToLoRaChannel` broadcast every room's text without ever
    /// setting `MeshPacket.channel`, so a message typed in a private room went
    /// out on slot 0 — the primary, usually LongFast and usually public. Wrong
    /// delivery, and for a named private channel a disclosure: the text the user
    /// believed was going to their own room was transmitted on the public one.
    ///
    /// Returns nil rather than 0 when the channel is absent, because "I do not
    /// know which slot" must never be answered with "the public one". The caller
    /// refuses the send.
    ///
    /// Matching is by NAME for a named key, because that is what the key is
    /// built from and what two radios agree on; the index is node-local and the
    /// user may have reordered their slots since the room was created.
    static func channelIndex(forKey key: String,
                             in channels: [MeshtasticManager.NodeChannel]) -> Int? {
        let usable = channels.filter { !$0.isDisabled }
        if key.hasPrefix(prefix) {
            let wanted = String(key.dropFirst(prefix.count))
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .lowercased()
            guard !wanted.isEmpty else { return nil }
            return usable.first {
                $0.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == wanted
            }?.index
        }
        guard key.hasPrefix(slotPrefix),
              let index = Int(key.dropFirst(slotPrefix.count)) else { return nil }
        // A slot key is only ever minted for an UNNAMED channel, so it stays
        // valid only while that slot is still unnamed — once the user names it,
        // `key(channelIndex:channelName:)` produces the named form and this room
        // is the stale one. Matching it by number anyway would put the stale
        // room's text on a channel the user has since repurposed.
        return usable.first {
            $0.index == index
                && $0.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }?.index
    }
}
