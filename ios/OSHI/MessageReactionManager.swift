//
//  MessageReactionManager.swift
//  OSHI - WhatsApp-Style Message Emoji Reactions
//
//  Features:
//  - Horizontal floating emoji bar (WhatsApp-style)
//  - Syncs reactions between sender and receiver
//  - Shows who reacted with which emoji
//  - Works in 1-to-1 and group chats
//

import Foundation
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

// MARK: - Reaction Types

/// A reaction: the exact emoji string that travels on the wire.
///
/// __UNKNOWN_REACTION_AS_ITSELF_2026_11_16__ This was an `enum` of the
/// reactions OSHI offers, and every received emoji that was not one of them
/// was rewritten to 👍 (`ReactionEmoji(rawValue:) ?? .thumbsUp`), so an
/// Android or desktop user reacting 🙏, a flag, or anything newer than this
/// build showed up here as a thumbs-up. It is now a value type around the raw
/// string: the known reactions are static members with the same names (so
/// `.thumbsUp`, `.oshiO`, `== .oshiSmile` read as before), and anything else
/// received is kept and displayed as itself. Counting, toggling and removing
/// compare the exact string.
///
///   * `init?(rawValue:)` still accepts ONLY the known reactions (same
///     contract as the enum) — use it for user input and tests.
///   * `init?(wire:)` accepts any plausible emoji string — use it for
///     anything received (both payload formats, sync).
///   * Codable is the bare string, byte-identical to the enum's encoding, so
///     stored reactions and the iOS payload format are unchanged.
struct ReactionEmoji: RawRepresentable, Hashable, Codable, CaseIterable {
    let rawValue: String

    private init(known: String) { self.rawValue = known }

    /// Known reactions only.
    init?(rawValue: String) {
        guard let k = Self.allCases.first(where: { $0.rawValue == rawValue }) else { return nil }
        self = k
    }

    /// Any reaction received from another device. Known values map to the
    /// known reaction (same string anyway); anything else is kept verbatim
    /// if it looks like one emoji (or the two-glyph OSHI smile): non-empty,
    /// at most 2 grapheme clusters / 64 UTF-8 bytes, no whitespace or
    /// control characters. Junk returns nil and the payload is dropped.
    init?(wire: String) {
        if let k = ReactionEmoji(rawValue: wire) { self = k; return }
        guard Self.isPlausibleWireEmoji(wire) else { return nil }
        self.rawValue = wire
    }

    static func isPlausibleWireEmoji(_ s: String) -> Bool {
        guard !s.isEmpty, s.count <= 2, s.utf8.count <= 64 else { return false }
        // Cc only — NOT `CharacterSet.controlCharacters`, which also holds Cf
        // and so rejected U+200D ZWJ, i.e. every 🧑‍💻 / 👨‍👩‍👧 sequence.
        return !s.unicodeScalars.contains {
            CharacterSet.whitespacesAndNewlines.contains($0) || $0.properties.generalCategory == .control
        }
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        let s = try c.decode(String.self)
        guard let e = ReactionEmoji(wire: s) else {
            throw DecodingError.dataCorruptedError(in: c, debugDescription: "Not a reaction: \(s.debugDescription)")
        }
        self = e
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }

    // Original OSHI emoji selection (8 reactions + 4 religious symbols)
    static let check = ReactionEmoji(known: "✅")           // Approval
    static let cross = ReactionEmoji(known: "❌")           // Disapproval
    static let sad = ReactionEmoji(known: "😢")
    static let middleFinger = ReactionEmoji(known: "🖕")
    static let kiss = ReactionEmoji(known: "😘")
    static let thumbsUp = ReactionEmoji(known: "👍")
    static let thumbsDown = ReactionEmoji(known: "👎")
    static let hundred = ReactionEmoji(known: "💯")
    // 🔧 (2026-08-08) More reactions, added by frequency of actual use — the
    // picker is a row you scan in one glance, not an emoji keyboard.
    static let laugh = ReactionEmoji(known: "😂")
    static let heart = ReactionEmoji(known: "❤️")
    static let fire = ReactionEmoji(known: "🔥")
    static let wow = ReactionEmoji(known: "😮")
    static let clap = ReactionEmoji(known: "👏")
    static let party = ReactionEmoji(known: "🎉")
    static let think = ReactionEmoji(known: "🤔")
    static let eyes = ReactionEmoji(known: "👀")
    // __O_BUBBLE_MASCOT_2026_11_16__ The nine mascot emoji the list above
    // lacked, so the "+" picker offers all twenty mascots. Wire = the plain
    // emoji (no FE0F is needed on any of them). Received, they were already
    // shown as themselves (`init(wire:)`); being known adds a VoiceOver name.
    static let sob = ReactionEmoji(known: "😭")
    static let peek = ReactionEmoji(known: "🫣")
    static let tearSmile = ReactionEmoji(known: "🥲")
    static let pray = ReactionEmoji(known: "🙏")
    static let angry = ReactionEmoji(known: "😡")
    static let wave = ReactionEmoji(known: "👋")
    static let heartEyes = ReactionEmoji(known: "😍")
    static let cool = ReactionEmoji(known: "😎")
    static let sleepy = ReactionEmoji(known: "😴")
    // Religious symbols
    static let christian = ReactionEmoji(known: "✝️")
    static let muslim = ReactionEmoji(known: "☪️")
    static let jewish = ReactionEmoji(known: "✡️")
    static let buddhist = ReactionEmoji(known: "☸️")

    /// __OSHI_SMILE_REACTION_2026_11_09__ OSHI's own mark, smiling — what a
    /// double-tap on a bubble sends.
    ///
    /// WIRE VALUE, shared byte-for-byte with Android and desktop: the string
    /// "⭕😊" = U+2B55 U+1F60A, UTF-8 `E2 AD 95 F0 9F 98 8A`, NO variation
    /// selector. A client that does not know it shows the two standard emoji
    /// it can draw; a new client draws `OSHISmileMark`.
    ///
    /// Only this EXACT value is the OSHI mark: a lone "😊" or "⭕️😊" (with
    /// FE0F) is a different string and is shown as that text.
    static let oshiSmile = ReactionEmoji(known: "\u{2B55}\u{1F60A}")

    /// __OSHI_O_REACTION_2026_11_15__ The bare OSHI "O" — the app icon's ring
    /// and speech tail, nothing inside. First in the long-press bar (Android
    /// parity); double-tap still sends `oshiSmile`.
    ///
    /// WIRE VALUE, shared byte-for-byte with Android: "⭕" = U+2B55 ALONE,
    /// UTF-8 `E2 AD 95`, NO variation selector. Exact match only: "⭕️"
    /// (U+2B55 U+FE0F) is NOT this reaction — Swift `String ==` is canonical
    /// equivalence and FE0F has no canonical decomposition — so it is kept as
    /// the plain emoji "⭕️".
    ///
    /// OLD CLIENTS: iOS ≤ 1.0.46 b203 maps any unknown emoji to 👍, so an old
    /// iPhone shows a 👍 where a new client shows the O. Nothing is lost or
    /// crashes; the remove travels the same way.
    static let oshiO = ReactionEmoji(known: "\u{2B55}")

    /// Every reaction this build offers, in declaration order (the old enum's
    /// `allCases`). Received unknown reactions are not in it.
    static let allCases: [ReactionEmoji] = [
        .check, .cross, .sad, .middleFinger, .kiss, .thumbsUp, .thumbsDown, .hundred,
        .laugh, .heart, .fire, .wow, .clap, .party, .think, .eyes,
        .christian, .muslim, .jewish, .buddhist, .oshiSmile, .oshiO,
        .sob, .peek, .tearSmile, .pray, .angry, .wave, .heartEyes, .cool, .sleepy,
    ]

    /// true for a reaction this build offers in its pickers.
    var isKnown: Bool { Self.allCases.contains(self) }

    /// The OSHI marks are drawn, not typed: anything rendering a reaction
    /// should go through `ReactionGlyph`, which checks this.
    var isOSHIMark: Bool { self == .oshiSmile || self == .oshiO }

    var displayEmoji: String {
        return rawValue
    }

    /// `Localizable.strings` key of the VoiceOver label, nil for a reaction
    /// this build does not know (VoiceOver then reads the emoji itself, which
    /// it names in the user's language).
    var accessibilityKey: String? {
        switch rawValue {
        case Self.check.rawValue: return "reaction.check"
        case Self.cross.rawValue: return "reaction.cross"
        case Self.sad.rawValue: return "reaction.sad"
        case Self.middleFinger.rawValue: return "reaction.middle_finger"
        case Self.kiss.rawValue: return "reaction.kiss"
        case Self.thumbsUp.rawValue: return "reaction.thumbs_up"
        case Self.thumbsDown.rawValue: return "reaction.thumbs_down"
        case Self.hundred.rawValue: return "reaction.hundred"
        case Self.laugh.rawValue: return "reaction.laugh"
        case Self.heart.rawValue: return "reaction.heart"
        case Self.fire.rawValue: return "reaction.fire"
        case Self.wow.rawValue: return "reaction.wow"
        case Self.clap.rawValue: return "reaction.clap"
        case Self.party.rawValue: return "reaction.party"
        case Self.think.rawValue: return "reaction.think"
        case Self.eyes.rawValue: return "reaction.eyes"
        case Self.christian.rawValue: return "reaction.christian"
        case Self.muslim.rawValue: return "reaction.muslim"
        case Self.jewish.rawValue: return "reaction.jewish"
        case Self.buddhist.rawValue: return "reaction.buddhist"
        case Self.oshiSmile.rawValue: return "reaction.oshi_smile"
        case Self.oshiO.rawValue: return "reaction.oshi_o"
        case Self.sob.rawValue: return "reaction.sob"
        case Self.peek.rawValue: return "reaction.peek"
        case Self.tearSmile.rawValue: return "reaction.tear_smile"
        case Self.pray.rawValue: return "reaction.pray"
        case Self.angry.rawValue: return "reaction.angry"
        case Self.wave.rawValue: return "reaction.wave"
        case Self.heartEyes.rawValue: return "reaction.heart_eyes"
        case Self.cool.rawValue: return "reaction.cool"
        case Self.sleepy.rawValue: return "reaction.sleepy"
        default: return nil
        }
    }

    var accessibilityLabel: String {
        guard let key = accessibilityKey else { return rawValue }
        return key.localized
    }

    /// __O_BUBBLE_MASCOT_2026_11_16__ The long-press bar: the OSHI "O", then
    /// the six mascots of `MascotWirePolicy.quickBar` — ❤️ 😂 😢 👍 😮 🔥,
    /// every one known to iOS 1.0.43 (animated when the setting is on) —
    /// then "+". Seven glyphs + "+" = eight 44 pt targets = 352 pt, plus the
    /// bar's 4 pt padding each side = 360 pt, which fits a 375 pt screen with
    /// the overlay's 6 pt inset (`QuickBarMetrics`). The smile (double-tap) is
    /// one tap away in the "+" picker.
    static var quickReactions: [ReactionEmoji] {
        [.oshiO] + MascotWirePolicy.quickBar.compactMap(mascotReaction)
    }

    /// The reaction a mascot sends (`MascotWirePolicy.wireEmoji`).
    static func mascotReaction(_ name: String) -> ReactionEmoji? {
        OSHIMascotCatalog.named(name).flatMap { ReactionEmoji(rawValue: MascotWirePolicy.wireEmoji($0)) }
    }

    /// The "+" picker, main grid: the two OSHI marks, the mascots every OSHI
    /// build knows (manifest order, as their wire emoji), then the reactions
    /// that have no mascot.
    static var pickerReactions: [ReactionEmoji] {
        let marks: [ReactionEmoji] = [.oshiO, .oshiSmile]
        let mascots = OSHIMascotCatalog.all
            .filter { !MascotWirePolicy.newOnWire.contains($0.name) }
            .compactMap { mascotReaction($0.name) }
        let rest: [ReactionEmoji] = [.cross, .thumbsDown, .middleFinger, .eyes,
                                     .christian, .muslim, .jewish, .buddhist]
        return marks + mascots + rest
    }

    /// The "+" picker, second grid under the "new — older iPhones may show 👍"
    /// hint: mascots whose emoji old builds do not know.
    static var pickerNewReactions: [ReactionEmoji] {
        OSHIMascotCatalog.all
            .filter { MascotWirePolicy.newOnWire.contains($0.name) }
            .compactMap { mascotReaction($0.name) }
    }

    /// Everything offered (the Mac right-click menu).
    static var allReactions: [ReactionEmoji] { pickerReactions + pickerNewReactions }

    /// Known reactions deliberately not offered: 😭 is SENT as 😢 (same
    /// crying mascot, see `MascotWirePolicy`); received 😭 still displays.
    static let notOffered: [ReactionEmoji] = [.sob]
}


// MARK: - Reaction Model

struct MessageReaction: Codable, Equatable, Identifiable {
    let id: String
    let messageId: String
    let emoji: ReactionEmoji
    let senderPublicKey: String
    let senderName: String  // Display name for "who reacted" feature
    let timestamp: Date
    
    init(messageId: String, emoji: ReactionEmoji, senderPublicKey: String, senderName: String = "") {
        self.id = UUID().uuidString
        self.messageId = messageId
        self.emoji = emoji
        self.senderPublicKey = senderPublicKey
        self.senderName = senderName
        self.timestamp = Date()
    }
}

// MARK: - Reaction Sync Payload

struct ReactionPayload: Codable {
    static let messagePrefix = "🔥REACTION🔥"
    // 🔧 CROSS-PLATFORM: Android uses different prefix
    static let androidPrefix = "👍 REACTION👍"

    let messageId: String
    let emoji: ReactionEmoji
    let senderPublicKey: String
    let senderName: String
    let timestamp: Date
    let action: ReactionAction

    enum ReactionAction: String, Codable {
        case add
        case remove
    }

    func toMessageString() -> String {
        guard let data = try? JSONEncoder().encode(self),
              let json = String(data: data, encoding: .utf8) else {
            return ""
        }
        return "\(Self.messagePrefix)\(json)"
    }

    /// Parse reaction from message string — supports BOTH iOS and Android formats
    static func fromMessageString(_ string: String) -> ReactionPayload? {
        let jsonString: String
        if string.hasPrefix(messagePrefix) {
            jsonString = String(string.dropFirst(messagePrefix.count))
        } else if string.hasPrefix(androidPrefix) {
            jsonString = String(string.dropFirst(androidPrefix.count))
        } else {
            return nil
        }

        // Try iOS format first (Codable ReactionPayload)
        if let data = jsonString.data(using: .utf8),
           let payload = try? JSONDecoder().decode(ReactionPayload.self, from: data) {
            return payload
        }

        // 🔧 CROSS-PLATFORM: Try Android format
        // Android sends: {"messageId":"...", "emoji":"👍", "isAdding": true}
        if let data = jsonString.data(using: .utf8),
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let messageId = json["messageId"] as? String,
           let emojiStr = json["emoji"] as? String {

            // EXACT string: "⭕" is `.oshiO`, "⭕️" (with FE0F) is kept as the
            // plain emoji "⭕️", and any emoji this build does not offer is
            // kept and shown as itself — it used to become 👍
            // (`?? .thumbsUp`, still the behaviour of iOS ≤ 1.0.46).
            // Junk (empty, text, whitespace) drops the payload.
            guard let emoji = ReactionEmoji(wire: emojiStr) else { return nil }

            // Android uses "isAdding" boolean, iOS uses "action" string
            let isAdding = json["isAdding"] as? Bool ?? (json["action"] as? String == "add")
            let senderKey = json["senderPublicKey"] as? String ?? ""
            let senderName = json["senderName"] as? String ?? ""

            return ReactionPayload(
                messageId: messageId,
                emoji: emoji,
                senderPublicKey: senderKey,
                senderName: senderName,
                timestamp: Date(),
                action: isAdding ? .add : .remove
            )
        }

        return nil
    }

    /// Check if content is a reaction message (from either platform)
    static func isReactionMessage(_ string: String) -> Bool {
        return string.hasPrefix(messagePrefix) || string.hasPrefix(androidPrefix)
    }
}

// MARK: - Reaction Display Data (for UI - avoids tuple type inference issues)

struct ReactionDisplayData: Identifiable {
    let id: String
    let emoji: ReactionEmoji
    let count: Int
    let reactors: [String]
    
    init(emoji: ReactionEmoji, count: Int, reactors: [String]) {
        self.id = emoji.rawValue
        self.emoji = emoji
        self.count = count
        self.reactors = reactors
    }
}

// MARK: - Reaction Manager

class MessageReactionManager: ObservableObject {
    static let shared = MessageReactionManager()
    
    // messageId -> [reactions]
    @Published var reactions: [String: [MessageReaction]] = [:]
    
    private let saveKey = "oshi_message_reactions_v2"
    
    init() {
        loadReactions()
    }
    
    // MARK: - Add/Remove Reactions
    
    func addReaction(to messageId: String, emoji: ReactionEmoji, senderPublicKey: String, senderName: String, broadcast: Bool = true) {
        // Explicitly notify observers before change
        objectWillChange.send()
        
        // Check if user already reacted with this emoji - toggle off
        if let existing = reactions[messageId]?.first(where: { $0.emoji == emoji && $0.senderPublicKey == senderPublicKey }) {
            removeReaction(existing, broadcast: broadcast)
            // Same light tap as adding: taking a reaction back is an action
            // too, and silence read as "the tap did not register". Only for
            // the user's own tap (`broadcast`), never a programmatic add.
            if broadcast { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
            return
        }
        
        // Remove any other reaction from this user on this message (WhatsApp style: one reaction per user)
        if let existingReactions = reactions[messageId] {
            for existingReaction in existingReactions where existingReaction.senderPublicKey == senderPublicKey {
                removeReaction(existingReaction, broadcast: false)
            }
        }
        
        // Add new reaction
        let reaction = MessageReaction(
            messageId: messageId,
            emoji: emoji,
            senderPublicKey: senderPublicKey,
            senderName: senderName
        )
        
        if reactions[messageId] == nil {
            reactions[messageId] = []
        }
        reactions[messageId]?.append(reaction)
        
        saveReactions()
        
        if broadcast {
            broadcastReaction(reaction, action: .add)
        }
        
        // Haptic feedback — the user's own tap only (see above).
        if broadcast { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
        
        OshiLog.messaging.info("✅ Reaction: Added \(emoji.rawValue) to message \(messageId.prefix(8))... by \(senderName)")
        
        // Post notification for UI update (backup)
        NotificationCenter.default.post(
            name: NSNotification.Name("MessageReactionUpdated"),
            object: nil,
            userInfo: ["messageId": messageId]
        )
    }
    
    func removeReaction(_ reaction: MessageReaction, broadcast: Bool = true) {
        // Explicitly notify observers before change
        objectWillChange.send()
        
        let messageId = reaction.messageId
        reactions[messageId]?.removeAll { $0.id == reaction.id }
        
        // Clean up empty arrays
        if reactions[messageId]?.isEmpty == true {
            reactions.removeValue(forKey: messageId)
        }
        
        saveReactions()
        
        if broadcast {
            broadcastReaction(reaction, action: .remove)
        }
        
        OshiLog.messaging.info("❌ Reaction: Removed \(reaction.emoji.rawValue) from message \(messageId.prefix(8))...")
        
        // Post notification for UI update (backup)
        NotificationCenter.default.post(
            name: NSNotification.Name("MessageReactionUpdated"),
            object: nil,
            userInfo: ["messageId": messageId]
        )
    }
    
    func toggleReaction(to messageId: String, emoji: ReactionEmoji, senderPublicKey: String, senderName: String) {
        addReaction(to: messageId, emoji: emoji, senderPublicKey: senderPublicKey, senderName: senderName)
    }
    
    // MARK: - Get Reactions
    
    func getReactions(for messageId: String) -> [MessageReaction] {
        return reactions[messageId] ?? []
    }
    
    func getReactionCounts(for messageId: String) -> [ReactionDisplayData] {
        guard let messageReactions = reactions[messageId] else { return [] }
        
        var grouped: [ReactionEmoji: (count: Int, reactors: [String])] = [:]
        for reaction in messageReactions {
            if grouped[reaction.emoji] == nil {
                grouped[reaction.emoji] = (0, [])
            }
            grouped[reaction.emoji]!.count += 1
            grouped[reaction.emoji]!.reactors.append(reaction.senderName.isEmpty ? "Someone" : reaction.senderName)
        }
        
        return grouped.map { ReactionDisplayData(emoji: $0.key, count: $0.value.count, reactors: $0.value.reactors) }
            .sorted { $0.count > $1.count }
    }
    
    func hasUserReacted(messageId: String, senderPublicKey: String, emoji: ReactionEmoji? = nil) -> Bool {
        guard let messageReactions = reactions[messageId] else { return false }
        
        if let emoji = emoji {
            return messageReactions.contains { $0.senderPublicKey == senderPublicKey && $0.emoji == emoji }
        } else {
            return messageReactions.contains { $0.senderPublicKey == senderPublicKey }
        }
    }
    
    func getUserReaction(messageId: String, senderPublicKey: String) -> ReactionEmoji? {
        return reactions[messageId]?.first { $0.senderPublicKey == senderPublicKey }?.emoji
    }
    
    // MARK: - Process Incoming Reaction (from other user)
    
    func processIncomingReaction(_ payload: ReactionPayload) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            // Explicitly notify observers before change
            self.objectWillChange.send()
            
            switch payload.action {
            case .add:
                // Check for duplicates
                if self.reactions[payload.messageId]?.contains(where: {
                    $0.emoji == payload.emoji && $0.senderPublicKey == payload.senderPublicKey
                }) == true {
                    OshiLog.messaging.info("📥 Reaction: Duplicate, ignoring")
                    return
                }
                
                // Remove any existing reaction from this user
                if let existingReactions = self.reactions[payload.messageId] {
                    for reaction in existingReactions where reaction.senderPublicKey == payload.senderPublicKey {
                        self.reactions[payload.messageId]?.removeAll { $0.id == reaction.id }
                    }
                }
                
                let reaction = MessageReaction(
                    messageId: payload.messageId,
                    emoji: payload.emoji,
                    senderPublicKey: payload.senderPublicKey,
                    senderName: payload.senderName
                )
                
                if self.reactions[payload.messageId] == nil {
                    self.reactions[payload.messageId] = []
                }
                self.reactions[payload.messageId]?.append(reaction)
                OshiLog.messaging.info("📥 Reaction: Received \(payload.emoji.rawValue) from \(payload.senderName) for message \(payload.messageId.prefix(8))...")
                
            case .remove:
                self.reactions[payload.messageId]?.removeAll {
                    $0.emoji == payload.emoji && $0.senderPublicKey == payload.senderPublicKey
                }
                if self.reactions[payload.messageId]?.isEmpty == true {
                    self.reactions.removeValue(forKey: payload.messageId)
                }
                OshiLog.messaging.info("📥 Reaction: Removed \(payload.emoji.rawValue) from \(payload.senderName)")
            }
            
            self.saveReactions()

            // __NO_HAPTIC_ON_RECEIVED_REACTION_2026_11_16__ No haptic here:
            // this runs for every reaction that arrives, and a catch-up sync
            // delivers dozens at once — the phone buzzed in the pocket for
            // reactions to messages days old. Only the user's own tap buzzes
            // (`addReaction` with `broadcast == true`).
            
            // Post notification for UI update (backup mechanism)
            NotificationCenter.default.post(
                name: NSNotification.Name("MessageReactionUpdated"),
                object: nil,
                userInfo: ["messageId": payload.messageId]
            )
        }
    }
    
    // MARK: - Broadcast Reaction (send to other user)
    
    private func broadcastReaction(_ reaction: MessageReaction, action: ReactionPayload.ReactionAction) {
        let payload = ReactionPayload(
            messageId: reaction.messageId,
            emoji: reaction.emoji,
            senderPublicKey: reaction.senderPublicKey,
            senderName: reaction.senderName,
            timestamp: reaction.timestamp,
            action: action
        )
        
        // Post notification for ChatView to send
        NotificationCenter.default.post(
            name: NSNotification.Name("SendReaction"),
            object: nil,
            userInfo: ["payload": payload]
        )
        
        OshiLog.messaging.info("📤 Reaction: Broadcasting \(action.rawValue) \(reaction.emoji.rawValue)")
    }
    
    // MARK: - Persistence
    
    private func saveReactions() {
        if let data = try? JSONEncoder().encode(reactions) {
            UserDefaults.standard.set(data, forKey: saveKey)
        }
    }
    
    private func loadReactions() {
        if let data = UserDefaults.standard.data(forKey: saveKey),
           let decoded = try? JSONDecoder().decode([String: [MessageReaction]].self, from: data) {
            reactions = decoded
        }
    }
    
    // MARK: - Cleanup
    
    func removeReactionsForMessage(_ messageId: String) {
        reactions.removeValue(forKey: messageId)
        saveReactions()
    }
    
    func clearAllReactions() {
        reactions.removeAll()
        saveReactions()
    }
}

// MARK: - WhatsApp-Style Reaction Picker View

struct WhatsAppReactionPicker: View {
    let messageId: String
    let senderPublicKey: String
    let senderName: String
    let onReact: (ReactionEmoji) -> Void
    let onDismiss: () -> Void
    let onShowMore: () -> Void
    /// __GROUP_INFO_SETTINGS_2026_11_20__ group "some reactions" (§2.3). nil = default set.
    var choices: [ReactionEmoji]? = nil
    
    @ObservedObject private var reactionManager = MessageReactionManager.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false

    private var quick: [ReactionEmoji] { choices.map { Array($0.prefix(6)) } ?? ReactionEmoji.quickReactions }

    private var userCurrentReaction: ReactionEmoji? {
        reactionManager.getUserReaction(messageId: messageId, senderPublicKey: senderPublicKey)
    }

    var body: some View {
        HStack(spacing: QuickBarMetrics.spacing) {
            // Quick reaction emojis
            ForEach(quick, id: \.self) { emoji in
                ReactionEmojiButton(
                    emoji: emoji,
                    isSelected: userCurrentReaction == emoji,
                    delay: reduceMotion ? 0 : Double(quick.firstIndex(of: emoji) ?? 0) * 0.05
                ) {
                    onReact(emoji)
                    onDismiss()
                }
            }

            // More button
            if (choices?.count ?? Int.max) > 6 {
            Button {
                onShowMore()
            } label: {
                Image(systemName: "plus.circle.fill")
                    .font(.system(size: 24))
                    .foregroundColor(.gray)
                    .frame(width: QuickBarMetrics.button, height: QuickBarMetrics.button)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("reaction.more".localized)
            .accessibilityHint("reaction.more.hint".localized)
            .scaleEffect(reduceMotion || appeared ? 1 : 0)
            .opacity(appeared ? 1 : 0)
            .animation(reduceMotion ? .easeOut(duration: 0.2) : .spring(response: 0.3, dampingFraction: 0.6).delay(0.3), value: appeared)
            }
        }
        .padding(QuickBarMetrics.padding)
        .background(
            Capsule()
                .fill(ReactionSurface.bar)
                .shadow(color: .black.opacity(0.15), radius: 12, x: 0, y: 4)
        )
        .oledGlassEdge(Capsule())  // __OLED_GLASS_2026_11_16__
        // Reduce Motion: a fade, never a zoom.
        .scaleEffect(reduceMotion || appeared ? 1 : 0.3)
        .opacity(appeared ? 1 : 0)
        .onAppear {
            withAnimation(reduceMotion ? .easeOut(duration: 0.2) : .spring(response: 0.35, dampingFraction: 0.7)) {
                appeared = true
            }
        }
    }
}

// MARK: - __REACTION_BAR_FIT_2026_11_15__ Quick bar geometry

/// The long-press bar's geometry, in one place so the test can prove it fits.
///
/// Eight 44 pt targets (seven reactions + "+") with NO gap between them — the
/// 44 pt frames already separate the glyphs — and 4 pt of capsule padding:
/// 8 × 44 + 2 × 4 = 360 pt. With the overlay's 6 pt screen inset on each side
/// that is 372 pt, inside the narrowest supported phone (375 pt, iPhone SE /
/// mini). The old 8 pt spacing + 12 pt padding + 20 pt inset needed 448 pt.
enum QuickBarMetrics {
    static let button: CGFloat = 44
    static let spacing: CGFloat = 0
    static let padding: CGFloat = 4
    static let screenInset: CGFloat = 6

    /// Width of the bar (reactions + the "+" button), capsule included.
    static func barWidth(reactionCount: Int) -> CGFloat {
        let n = CGFloat(reactionCount + 1)
        return n * button + (n - 1) * spacing + 2 * padding
    }

    /// Screen width the bar needs, insets included.
    static func requiredScreenWidth(reactionCount: Int) -> CGFloat {
        barWidth(reactionCount: reactionCount) + 2 * screenInset
    }
}

/// Surfaces of the floating bars and the "+" card. Light: white. Dark: the
/// raised grey (`tertiarySystemBackground`, #2C2C2E) — pure black disappeared
/// into the dimmed chat behind it.
enum ReactionSurface {
    static let bar = Color(uiColor: .tertiarySystemBackground)
}

/// Height of the overlay's content, so it can be kept on screen.
private struct ReactionOverlayContentHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

// MARK: - Reaction Emoji Button (with bounce animation)

struct ReactionEmojiButton: View {
    let emoji: ReactionEmoji
    let isSelected: Bool
    let delay: Double
    let onTap: () -> Void
    
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false
    @State private var isPressed = false

    private let brandColor = Theme.brand

    var body: some View {
        Button {
            if reduceMotion {
                onTap()
            } else {
                withAnimation(.spring(response: 0.2, dampingFraction: 0.5)) {
                    isPressed = true
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                    isPressed = false
                    onTap()
                }
            }
        } label: {
            // Fixed size: inside a 44 pt target a Dynamic-Type-scaled glyph
            // overflowed into its neighbours at the accessibility sizes.
            ReactionGlyph(emoji: emoji, size: 28, scalesWithText: false, motion: .loop)
                .scaleEffect(isPressed ? 1.4 : (isSelected ? 1.15 : 1.0))
                .frame(width: QuickBarMetrics.button, height: QuickBarMetrics.button)
                .background(
                    Circle()
                        .fill(isSelected ? brandColor.opacity(0.2) : Color.clear)
                )
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(emoji.accessibilityLabel)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .scaleEffect(reduceMotion || appeared ? 1 : 0)
        .opacity(appeared ? 1 : 0)
        .animation(reduceMotion ? .easeOut(duration: 0.2) : .spring(response: 0.3, dampingFraction: 0.6).delay(delay), value: appeared)
        .onAppear {
            appeared = true
        }
    }
}

// MARK: - Full Reaction Picker (all emojis)

struct FullReactionPicker: View {
    let messageId: String
    let senderPublicKey: String
    let senderName: String
    let onReact: (ReactionEmoji) -> Void
    let onDismiss: () -> Void
    var choices: [ReactionEmoji]? = nil
    
    @ObservedObject private var reactionManager = MessageReactionManager.shared
    
    /// Adaptive, never narrower than a 44 pt target: six fixed columns in a
    /// card squeezed by a 375 pt screen came out at ~38 pt each.
    private let columns = [GridItem(.adaptive(minimum: QuickBarMetrics.button), spacing: 4)]
    
    private var userCurrentReaction: ReactionEmoji? {
        reactionManager.getUserReaction(messageId: messageId, senderPublicKey: senderPublicKey)
    }
    
    private func grid(_ items: [ReactionEmoji]) -> some View {
        LazyVGrid(columns: columns, spacing: 12) {
            ForEach(items, id: \.self) { emoji in
                ReactionEmojiButton(
                    emoji: emoji,
                    isSelected: userCurrentReaction == emoji,
                    delay: 0
                ) {
                    onReact(emoji)
                    onDismiss()
                }
            }
        }
        .padding(.horizontal)
    }

    var body: some View {
        VStack(spacing: 16) {
            // Header
            HStack {
                Text("reaction.choose".localized)
                    .font(.headline)
                Spacer()
                Button {
                    onDismiss()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title2)
                        .foregroundColor(.secondary)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("common.close".localized)
            }
            .padding(.horizontal)

            // Emoji grid
            // __GROUP_INFO_SETTINGS_2026_11_20__ a group's "some reactions" list replaces both grids.
            grid(choices ?? ReactionEmoji.pickerReactions)

            // __MASCOT_SAFE_WIRE_2026_11_16__ Mascots old iPhones show as 👍.
            let newOnes = choices == nil ? ReactionEmoji.pickerNewReactions : []
            if !newOnes.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("reaction.new_hint".localized)
                        .font(.footnote)
                        .foregroundColor(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal)
                    grid(newOnes)
                }
            }
        }
        .padding(.vertical)
        .background(
            RoundedRectangle(cornerRadius: 20)
                .fill(ReactionSurface.bar)
                .shadow(color: .black.opacity(0.15), radius: 12, x: 0, y: 4)
        )
        .oledGlassEdge(RoundedRectangle(cornerRadius: 20))  // __OLED_GLASS_2026_11_16__
        // Cap width so the picker stays a readable card on iPad / landscape
        .frame(maxWidth: 420)
        .padding(.horizontal, 16)
        .frame(maxWidth: .infinity)
    }
}

// MARK: - WhatsApp-Style Reaction Display (under message bubble)

struct MessageReactionsView: View {
    let messageId: String
    let isFromCurrentUser: Bool
    @ObservedObject private var reactionManager = MessageReactionManager.shared
    @State private var showReactorsList = false
    @State private var refreshTrigger: Bool = false
    
    var body: some View {
        ReactionBadgeContent(
            messageId: messageId,
            isFromCurrentUser: isFromCurrentUser,
            reactionManager: reactionManager,
            showReactorsList: $showReactorsList,
            refreshTrigger: refreshTrigger
        )
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("MessageReactionUpdated"))) { notification in
            if let updatedMessageId = notification.userInfo?["messageId"] as? String,
               updatedMessageId == messageId {
                refreshTrigger.toggle()
            }
        }
    }
}

// Separate struct to isolate type complexity
private struct ReactionBadgeContent: View {
    let messageId: String
    let isFromCurrentUser: Bool
    let reactionManager: MessageReactionManager
    @Binding var showReactorsList: Bool
    let refreshTrigger: Bool
    
    private var reactions: [ReactionDisplayData] {
        reactionManager.getReactionCounts(for: messageId)
    }

    private var reactionsAccessibilityLabel: String {
        reactions
            .map { "\($0.count) \($0.emoji.accessibilityLabel)" }
            .joined(separator: ", ")
    }

    var body: some View {
        if reactions.isEmpty {
            EmptyView()
        } else {
            Button {
                showReactorsList = true
            } label: {
                ReactionBadgeLabel(reactions: reactions)
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(reactionsAccessibilityLabel)
            .accessibilityHint("reaction.see_who.hint".localized)
            .offset(y: -6)
            .frame(maxWidth: .infinity, alignment: isFromCurrentUser ? .trailing : .leading)
            .padding(.horizontal, isFromCurrentUser ? 16 : 56)
            .sheet(isPresented: $showReactorsList) {
                ReactorsListView(reactionData: reactions)
                    .presentationDetents([.medium, .large])
            }
        }
    }
}

// Further isolation for the badge label
private struct ReactionBadgeLabel: View {
    let reactions: [ReactionDisplayData]

    var body: some View {
        HStack(spacing: 6) {
            ForEach(reactions) { data in
                HStack(spacing: 2) {
                    ReactionGlyph(emoji: data.emoji, size: 14)
                    Text("\(data.count)")
                        .font(.caption2)
                        .fontWeight(.semibold)
                        .foregroundColor(.secondary)
                }
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(
            Capsule()
                .fill(Color(.secondarySystemBackground))
                .shadow(color: .black.opacity(0.08), radius: 2, x: 0, y: 1)
        )
        .oledGlassEdge(Capsule())  // __OLED_GLASS_2026_11_16__
    }
}

// MARK: - Reactors List View (shows who reacted)

struct ReactorsListView: View {
    let reactionData: [ReactionDisplayData]
    @Environment(\.dismiss) private var dismiss
    
    var body: some View {
        NavigationView {
            List {
                ForEach(reactionData) { data in
                    Section {
                        ForEach(data.reactors, id: \.self) { reactor in
                            HStack {
                                // Avatar placeholder
                                Circle()
                                    .fill(Color(.systemGray4))
                                    .frame(width: 36, height: 36)
                                    .overlay(
                                        Text(String(reactor.prefix(1)).uppercased())
                                            .font(.caption)
                                            .fontWeight(.semibold)
                                            .foregroundColor(.secondary)
                                    )
                                
                                Text(reactor)
                                    .font(.body)
                                
                                Spacer()
                            }
                        }
                    } header: {
                        HStack {
                            ReactionGlyph(emoji: data.emoji, size: 22)
                            Text("\(data.count)")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("reaction.who_reacted".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("common.done".localized) {
                        dismiss()
                    }
                }
            }
        }
    }
}

// MARK: - Reaction Overlay (full screen with dimmed background)

struct ReactionOverlay: View {
    let messageId: String
    let senderPublicKey: String
    let senderName: String
    let isPresented: Binding<Bool>
    let messageFrame: CGRect
    let isFromCurrentUser: Bool
    let onReact: (ReactionEmoji) -> Void

    /// Opt-in, off by default. See `MessagePrivacy` for what it does and does
    /// not promise. @AppStorage so flipping it in Settings takes effect on the
    /// next long-press without restarting the app.
    @AppStorage(MessagePrivacy.blockForwardAndCopyKey) private var blockForwardAndCopy = false

    // Optional message action callbacks
    var messageContent: String? = nil
    var onForward: (() -> Void)? = nil
    var onCopy: (() -> Void)? = nil
    var onReply: (() -> Void)? = nil
    var onDelete: (() -> Void)? = nil
    var onTranslate: (() -> Void)? = nil
    var onReplyPrivate: (() -> Void)? = nil
    var onPin: (() -> Void)? = nil
    var pinLabel: String = "Pin"
    var isPinnedMessage: Bool = false
    var onEdit: (() -> Void)? = nil
    /// __GROUP_RECEIPTS_2026_11_02__ (N8) "see who has read it and who has
    /// received it" — offered only for your OWN message in a GROUP, because
    /// that is the only case where the answer exists: receipts are addressed
    /// to the sender, so this device knows nothing about anyone else's message.
    var onInfo: (() -> Void)? = nil
    /// __GROUP_INFO_SETTINGS_2026_11_20__ nil = every reaction; empty = picker hidden.
    var reactionChoices: [ReactionEmoji]? = nil

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var showFullPicker = false
    @State private var backgroundOpacity: Double = 0
    @State private var actionsAppeared = false
    /// Measured height of whatever the overlay currently shows (bars or the
    /// "+" card), so it can be clamped inside the safe area.
    @State private var contentHeight: CGFloat = 0

    private let brandColor = Theme.brand

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .top) {
                // Dimmed background
                Color.black.opacity(backgroundOpacity)
                    .ignoresSafeArea()
                    .onTapGesture {
                        dismissOverlay()
                    }

                overlayContent
                    .background(
                        GeometryReader { inner in
                            Color.clear.preference(key: ReactionOverlayContentHeightKey.self,
                                                   value: inner.size.height)
                        }
                    )
                    .padding(.top, clampedTop(in: geo))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            }
        }
        .onPreferenceChange(ReactionOverlayContentHeightKey.self) { contentHeight = $0 }
        .onAppear {
            withAnimation(.easeOut(duration: 0.2)) {
                backgroundOpacity = 0.3
            }
            withAnimation(reduceMotion ? .easeOut(duration: 0.2) : .spring(response: 0.4, dampingFraction: 0.7).delay(0.15)) {
                actionsAppeared = true
            }
        }
    }

    /// __REACTION_BAR_FIT_2026_11_15__ Where the content's top goes, in the
    /// overlay's own (safe-area) coordinates: just above the pressed bubble,
    /// but never above the safe area and never so low that the bars or the
    /// "+" card run off the bottom — a long-press on the last message used to
    /// push the action bar under the home indicator.
    private func clampedTop(in geo: GeometryProxy) -> CGFloat {
        let originY = geo.frame(in: .global).minY
        let margin: CGFloat = 8
        let quickBarHeight = QuickBarMetrics.button + 2 * QuickBarMetrics.padding
        let desired = messageFrame.minY - originY - quickBarHeight - 12
        return Self.clampTop(desired: desired,
                             contentHeight: contentHeight,
                             availableHeight: geo.size.height,
                             margin: margin)
    }

    /// Pure clamp, shared with the tests.
    static func clampTop(desired: CGFloat, contentHeight: CGFloat,
                         availableHeight: CGFloat, margin: CGFloat) -> CGFloat {
        let maxTop = max(margin, availableHeight - contentHeight - margin)
        return min(max(desired, margin), maxTop)
    }

    @ViewBuilder
    private var overlayContent: some View {
            VStack(spacing: 12) {
                // Position picker near the message
                if !showFullPicker {
                    if reactionChoices?.isEmpty != true {
                    WhatsAppReactionPicker(
                        messageId: messageId,
                        senderPublicKey: senderPublicKey,
                        senderName: senderName,
                        onReact: { emoji in
                            onReact(emoji)
                        },
                        onDismiss: {
                            dismissOverlay()
                        },
                        onShowMore: {
                            withAnimation(reduceMotion ? .easeOut(duration: 0.2) : .spring(response: 0.3)) {
                                showFullPicker = true
                            }
                        },
                        choices: reactionChoices
                    )
                    .padding(.horizontal, QuickBarMetrics.screenInset)
                    .frame(maxWidth: .infinity, alignment: isFromCurrentUser ? .trailing : .leading)
                    }

                    // Message Action Buttons (Forward, Copy, Reply, Delete)
                    if hasAnyAction {
                        messageActionsBar
                            .padding(.horizontal, QuickBarMetrics.screenInset)
                            .frame(maxWidth: .infinity, alignment: isFromCurrentUser ? .trailing : .leading)
                    }
                } else {
                    FullReactionPicker(
                        messageId: messageId,
                        senderPublicKey: senderPublicKey,
                        senderName: senderName,
                        onReact: { emoji in
                            onReact(emoji)
                        },
                        onDismiss: {
                            dismissOverlay()
                        },
                        choices: reactionChoices
                    )
                    // Reduce Motion: fade in place instead of zooming.
                    .transition(reduceMotion ? .opacity : .scale.combined(with: .opacity))
                }
            }
    }

    private var hasAnyAction: Bool {
        // Forward and Copy only count while they are actually offered. Without
        // this, a message whose only actions are those two would still open an
        // action bar, and the bar would be empty.
        let forwardOrCopy = !blockForwardAndCopy && (onForward != nil || onCopy != nil)
        return forwardOrCopy || onReply != nil || onDelete != nil || onTranslate != nil || onReplyPrivate != nil || onPin != nil || onEdit != nil || onInfo != nil
    }

    // MARK: - Message Actions Bar
    /// The action capsule. Up to nine 60 pt buttons is ~560 pt — wider than
    /// any phone — so when it does not fit it becomes a horizontal scroller
    /// inside the same capsule instead of being squeezed or clipped.
    private var messageActionsBar: some View {
        ViewThatFits(in: .horizontal) {
            actionButtons
                .padding(.horizontal, 8)
                .padding(.vertical, 6)
            ScrollView(.horizontal, showsIndicators: false) {
                actionButtons
                    .padding(.horizontal, 8)
                    .padding(.vertical, 6)
            }
            .clipShape(Capsule())
        }
        .background(
            Capsule()
                .fill(ReactionSurface.bar)
                .shadow(color: .black.opacity(0.15), radius: 12, x: 0, y: 4)
        )
        .oledGlassEdge(Capsule())  // __OLED_GLASS_2026_11_16__
        .scaleEffect(reduceMotion || actionsAppeared ? 1 : 0.3)
        .opacity(actionsAppeared ? 1 : 0)
    }

    @ViewBuilder
    private var actionButtons: some View {
        HStack(spacing: 4) {
            // Reply in Private (only for other users' messages)
            if let onReplyPrivate = onReplyPrivate, !isFromCurrentUser {
                MessageActionButton(
                    icon: "envelope.fill",
                    label: "message.action.reply_private".localized,
                    color: brandColor,
                    action: {
                        onReplyPrivate()
                        dismissOverlay()
                    }
                )
            }

            // __GROUP_RECEIPTS_2026_11_02__ (N8) Who received it, who read it.
            if let onInfo = onInfo {
                MessageActionButton(
                    icon: "checkmark.circle",
                    label: "group.receipts.action".localized,
                    action: {
                        onInfo()
                        dismissOverlay()
                    }
                )
            }

            // Reply
            if let onReply = onReply {
                MessageActionButton(
                    icon: "arrowshape.turn.up.left.fill",
                    label: "message.action.reply".localized,
                    action: {
                        onReply()
                        dismissOverlay()
                    }
                )
            }

            // Forward — hidden entirely when the user has blocked forwarding.
            // Hidden rather than disabled: a greyed-out button advertises a
            // capability the user deliberately turned off.
            if let onForward = onForward, !blockForwardAndCopy {
                MessageActionButton(
                    icon: "arrowshape.turn.up.right.fill",
                    label: "message.action.forward".localized,
                    action: {
                        onForward()
                        dismissOverlay()
                    }
                )
            }

            // Copy — same switch. This is the single menu both the 1:1 and the
            // group thread build, so gating here covers both.
            if let onCopy = onCopy, !blockForwardAndCopy {
                MessageActionButton(
                    icon: "doc.on.doc.fill",
                    label: "message.action.copy".localized,
                    action: {
                        onCopy()
                        dismissOverlay()
                    }
                )
            }

            // Translate (only for received messages)
            if let onTranslate = onTranslate, !isFromCurrentUser {
                MessageActionButton(
                    icon: "globe",
                    label: "message.action.translate".localized,
                    color: brandColor,
                    action: {
                        onTranslate()
                        dismissOverlay()
                    }
                )
            }

            // Pin/Unpin
            if let onPin = onPin {
                MessageActionButton(
                    icon: isPinnedMessage ? "pin.slash" : "pin.fill",
                    label: pinLabel,
                    color: brandColor,
                    action: {
                        onPin()
                        dismissOverlay()
                    }
                )
            }

            // Edit (only for user's own messages within time window)
            if let onEdit = onEdit, isFromCurrentUser {
                MessageActionButton(
                    icon: "pencil",
                    label: "message.action.edit".localized,
                    color: brandColor,
                    action: {
                        onEdit()
                        dismissOverlay()
                    }
                )
            }

            // Delete (only for user's own messages)
            // __DELETE_WAS_GATED_TWICE_2026_10_20__ The `isFromCurrentUser`
            // test used to be here too, and it meant a RECEIVED message could
            // not be deleted from the long-press sheet at all — even though the
            // dialog it opens offers "Delete for me" for any message and only
            // adds "Delete for everyone" when the message is yours. The gate was
            // not protecting anything; it was hiding the half of the feature
            // that works.
            //
            // Safe for groups, which already decide at the call site:
            // `GroupViews` passes `onDelete: mine ? { … } : nil`, so `onDelete`
            // is nil for somebody else's group message and this button does not
            // appear. The authority stays where the decision belongs — the
            // caller, and the dialog.
            if let onDelete = onDelete {
                MessageActionButton(
                    icon: "trash.fill",
                    label: "message.action.delete".localized,
                    color: .red,
                    action: {
                        onDelete()
                        dismissOverlay()
                    }
                )
            }
        }
    }

    private func dismissOverlay() {
        withAnimation(.easeIn(duration: 0.15)) {
            backgroundOpacity = 0
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
            isPresented.wrappedValue = false
        }
    }
}

// MARK: - Message Action Button
struct MessageActionButton: View {
    let icon: String
    let label: String
    var color: Color = .primary
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.scaled(18))
                    .foregroundColor(color)
                    .accessibilityHidden(true)
                Text(label)
                    .font(.caption2)
                    .foregroundColor(color)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            .frame(width: 60, height: 50)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

// MARK: - Double Tap Quick Reaction

struct DoubleTapReactionModifier: ViewModifier {
    let messageId: String
    let senderPublicKey: String
    let senderName: String
    let onReact: (ReactionEmoji) -> Void
    
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var showLikeAnimation = false

    func body(content: Content) -> some View {
        content
            .overlay(
                // Thumbs up animation overlay (double tap reaction)
                Text("👍")
                    .font(.scaled(60))
                    .scaleEffect(showLikeAnimation ? 1 : 0)
                    .opacity(showLikeAnimation ? 1 : 0)
                    .accessibilityHidden(true)
            )
            .onTapGesture(count: 2) {
                // Double tap to add thumbs up reaction (quick like)
                withAnimation(reduceMotion ? .easeInOut(duration: 0.15) : .spring(response: 0.3, dampingFraction: 0.5)) {
                    showLikeAnimation = true
                }

                onReact(.thumbsUp)

                // Haptic
                #if !targetEnvironment(macCatalyst)
                let generator = UIImpactFeedbackGenerator(style: .medium)
                generator.impactOccurred()
                #endif

                // Hide animation after delay
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
                    withAnimation(.easeOut(duration: 0.2)) {
                        showLikeAnimation = false
                    }
                }
            }
    }
}

extension View {
    func doubleTapToReact(
        messageId: String,
        senderPublicKey: String,
        senderName: String,
        onReact: @escaping (ReactionEmoji) -> Void
    ) -> some View {
        modifier(DoubleTapReactionModifier(
            messageId: messageId,
            senderPublicKey: senderPublicKey,
            senderName: senderName,
            onReact: onReact
        ))
    }
}

// MARK: - Clear Background for Transparent Overlay

struct ClearBackgroundView: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        DispatchQueue.main.async {
            view.superview?.superview?.backgroundColor = .clear
        }
        return view
    }
    
    func updateUIView(_ uiView: UIView, context: Context) {}
}

// MARK: - Preview

#Preview {
    VStack(spacing: 30) {
        WhatsAppReactionPicker(
            messageId: "test",
            senderPublicKey: "key",
            senderName: "Hugo",
            onReact: { _ in },
            onDismiss: { },
            onShowMore: { }
        )
        
        MessageReactionsView(
            messageId: "test",
            isFromCurrentUser: true
        )
    }
    .padding()
}

// MARK: - __OSHI_SMILE_REACTION_2026_11_09__ The OSHI mark, smiling

/// OSHI's mark — the app icon's thick ring with its speech-bubble tail at the
/// lower left — with a smile inside. Drawn as geometry (same proportions as
/// `LoRaFirmware/tools/oshi_boot_logo.py`: ring r 13, hole r 7, tail wedge),
/// never a bitmap of the icon, so it stays crisp from a 14 pt reaction chip
/// to a 28 pt picker button.
///
/// Canvas units: a 34 × 34 square. Ring centre (17, 14), outer r 13, inner
/// r 7; tail (7, 21) → (14, 24) → (9, 34). The hole is filled white so the
/// face reads on a dark chip as well as a light one.
struct OSHISmileMark: View {
    var size: CGFloat = 20
    var tint: Color = Theme.brand

    var body: some View {
        Canvas { ctx, canvas in
            let u = min(canvas.width, canvas.height) / 34
            func pt(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x * u, y: y * u) }
            func disc(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat) -> CGRect {
                CGRect(x: (cx - r) * u, y: (cy - r) * u, width: 2 * r * u, height: 2 * r * u)
            }
            // Face.
            ctx.fill(Path(ellipseIn: disc(17, 14, 7.2)), with: .color(.white))
            // Ring (even-odd: outer disc minus the hole) + tail.
            var ring = Path()
            ring.addEllipse(in: disc(17, 14, 13))
            ring.addEllipse(in: disc(17, 14, 7))
            ctx.fill(ring, with: .color(tint), style: FillStyle(eoFill: true))
            var tail = Path()
            tail.move(to: pt(7, 21))
            tail.addLine(to: pt(14, 24))
            tail.addLine(to: pt(9, 34))
            tail.closeSubpath()
            ctx.fill(tail, with: .color(tint))
            // Eyes and smile.
            ctx.fill(Path(ellipseIn: disc(14.7, 12.0, 1.15)), with: .color(tint))
            ctx.fill(Path(ellipseIn: disc(19.3, 12.0, 1.15)), with: .color(tint))
            var smile = Path()
            smile.move(to: pt(13.6, 15.2))
            smile.addQuadCurve(to: pt(20.4, 15.2), control: pt(17, 19.4))
            ctx.stroke(smile, with: .color(tint),
                       style: StrokeStyle(lineWidth: 1.6 * u, lineCap: .round))
        }
        .frame(width: size, height: size)
        .accessibilityElement()
        .accessibilityLabel(ReactionEmoji.oshiSmile.accessibilityLabel)
    }
}

/// How every reaction is drawn: the smiling mark for `.oshiSmile`, the
/// `ReactionOSHIO` asset for `.oshiO`, the emoji text for everything else.
/// One place, so no surface shows "⭕" / "⭕😊" as text while another shows
/// the mark.
struct ReactionGlyph: View {
    let emoji: ReactionEmoji
    /// Point size of the emoji; the marks are drawn at the same box size.
    var size: CGFloat
    /// true (chips, lists): grows with Dynamic Type like the text beside it.
    /// false (pickers): fixed, because the 44 pt targets do not grow and a
    /// scaled glyph overlapped its neighbours at the accessibility sizes.
    var scalesWithText: Bool = true
    /// How a mascot moves here: `.loop` in pickers, `.once` in chips.
    var motion: MascotMotion = .once

    /// __O_BUBBLE_MASCOT_2026_11_16__ "Animated OSHI reactions" (default on).
    @AppStorage(MascotReactionPreference.key) private var animatedMascots = MascotReactionPreference.defaultEnabled

    /// Re-renders when the user changes the text size.
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    /// The point size actually used — the same scaling `Font.scaled` applies,
    /// so the drawn marks track the emoji text exactly.
    private var effectiveSize: CGFloat {
        guard scalesWithText else { return size }
        return UIFontMetrics(forTextStyle: Font.uiTextStyle(for: size)).scaledValue(for: size)
    }

    var body: some View {
        if emoji == .oshiSmile {
            OSHISmileMark(size: effectiveSize * 1.15)
        } else if emoji == .oshiO {
            // __OSHI_O_REACTION_2026_11_15__ vector asset (light #5616EC,
            // dark #A98BFF), ring + tail traced from AppIcon-1024.
            Image("ReactionOSHIO")
                .resizable()
                .interpolation(.high)
                .scaledToFit()
                .frame(width: effectiveSize * 1.1, height: effectiveSize * 1.1)
                .accessibilityLabel(emoji.accessibilityLabel)
        } else if let mascot = MascotReactionPreference.mascot(for: emoji, enabled: animatedMascots) {
            // The artwork fills ~75 % of its canvas: 1.35x the emoji size
            // gives a body the size of the emoji it replaces (bar, picker);
            // chips get 1.5x so the face still reads at 14 pt.
            MascotGlyph(mascot: mascot, side: (effectiveSize * (scalesWithText ? 1.5 : 1.35)).rounded(), motion: motion)
                .accessibilityElement()
                .accessibilityLabel(emoji.accessibilityLabel)
        } else {
            Text(emoji.displayEmoji)
                .font(scalesWithText ? .scaled(size) : .system(size: size))
        }
    }
}

/// __REACTION_MAC_MENU_2026_11_15__ The "React" submenu of the Mac
/// right-click menu, shared by the 1:1 and group bubbles. A Mac has no
/// long-press, so without it reacting needed a click-and-hold nobody finds.
/// Menus cannot host custom views, so the O uses its asset as the item image
/// and the others their emoji as text.
struct ReactionContextMenu: View {
    let onReact: (ReactionEmoji) -> Void

    var body: some View {
        Menu {
            ForEach(ReactionEmoji.allReactions, id: \.self) { emoji in
                Button {
                    onReact(emoji)
                } label: {
                    if emoji == .oshiO {
                        Label(emoji.accessibilityLabel, image: "ReactionOSHIO")
                    } else {
                        Text("\(emoji.displayEmoji)  \(emoji.accessibilityLabel)")
                    }
                }
            }
        } label: {
            Label("reaction.choose".localized, systemImage: "face.smiling")
        }
    }
}

// MARK: - __BUBBLE_GESTURES_2026_11_09__ Long-press + double-tap on a bubble

/// The two bubble-level gestures, shared by the 1:1 bubble and the live group
/// bubble so they cannot drift apart.
///
/// WHY `.simultaneousGesture` (reported: "long-pressing a message takes real
/// effort — on some messages I have to hunt for a spot that works"). The
/// long-press used to be `.onLongPressGesture` on the row. In SwiftUI a
/// child's own gesture takes PRECEDENCE over a plain gesture on its parent:
/// wherever the finger landed on something with a gesture of its own — the
/// link-preview card's `Button`, the reply quote's `onTapGesture`, a photo or
/// video tile, a Cashu card, a tappable chip — that child won the touch and
/// the bubble's long-press never ran. Only the bare text/padding worked,
/// hence "find a spot". Its default `maximumDistance` of 10 pt also cancelled
/// on the small drift of a thumb resting in a scroll view.
///
/// As simultaneous gestures they are recognised alongside whatever the child
/// does, so a press anywhere on the bubble opens the actions, and the child's
/// SINGLE tap is never delayed waiting to see whether a second tap follows —
/// links, media and chips still open on one tap. Same 0.5 s as before (the
/// iOS standard); 24 pt of slack instead of 10.
struct BubbleReactionGestures: ViewModifier {
    let onDoubleTap: () -> Void
    let onLongPress: () -> Void

    func body(content: Content) -> some View {
        content
            .simultaneousGesture(
                TapGesture(count: 2).onEnded {
                    #if !targetEnvironment(macCatalyst)
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    #endif
                    onDoubleTap()
                }
            )
            .simultaneousGesture(
                LongPressGesture(minimumDuration: 0.5, maximumDistance: 24)
                    .onEnded { _ in onLongPress() }
            )
            // VoiceOver has no long-press on a bubble it reads as one
            // element: "React" in the actions rotor opens the same overlay.
            .accessibilityAction(named: Text("reaction.choose".localized)) {
                onLongPress()
            }
    }
}
