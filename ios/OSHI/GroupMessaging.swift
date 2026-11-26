//
//  GroupMessaging.swift
//  ✅ CLEAN VERSION - NO FileStorage class included
//  Group messaging with Double Ratchet encryption
//  🖼️ Group picture support added
//

import Foundation
import SwiftUI
import CryptoKit
import UIKit

// MARK: - Brand Color
private let appBrandColor = Theme.brand

// MARK: - Group mesh cleartext broadcast (security R1)

/// 🔧 FIX (security R1): kill switch for the legacy mesh branch of group send,
/// which shipped message bodies to nearby peers with NO encryption of any kind.
///
/// Default OFF, and "off" is the safe value: nothing leaves the device in clear.
/// The cost is offline (no-internet) group delivery over the local mesh — see the
/// long note at the removed call site in `sendMessage`. The send AND receive sides
/// are both gated on this single flag, so flipping it does not leave one half of
/// the path live: with the flag off the app neither emits nor accepts cleartext
/// group traffic on the mesh.
///
/// `UserDefaults.bool(forKey:)` yields false when the key was never written, so an
/// upgrading device is safe without any migration step.
enum GroupMeshPlaintextBroadcast {
    static let defaultsKey = "oshi_group_mesh_cleartext_broadcast_enabled"
    static var enabled: Bool { UserDefaults.standard.bool(forKey: defaultsKey) }
}

// MARK: - Group Types

enum GroupType: String, Codable, CaseIterable {
    case adminOnly = "admin_only"
    case collaborative = "collaborative"
    case publicGroup = "public"
    
    var icon: String {
        switch self {
        case .adminOnly: return "lock.shield.fill"
        case .collaborative: return "person.3.fill"
        case .publicGroup: return "globe"
        }
    }
    
    var color: Color {
        switch self {
        case .adminOnly: return .red
        case .collaborative: return appBrandColor
        case .publicGroup: return .green
        }
    }
    
    /// Short display name for the group type (localized)
    var displayName: String {
        switch self {
        case .adminOnly: return "group_type_admin_only".localized
        case .collaborative: return "group_type_collaborative".localized
        case .publicGroup: return "group_type_public".localized
        }
    }
    
    /// Full description of the group type (localized)
    var description: String {
        switch self {
        case .adminOnly: return "group_type_admin_only_description".localized
        case .collaborative: return "group_type_collaborative_description".localized
        case .publicGroup: return "group_type_public_description".localized
        }
    }
}

// MARK: - Group Models

struct MessageGroup: Identifiable, Codable, Hashable {
    let id: UUID
    var name: String
    var type: GroupType
    var adminPublicKey: String
    var members: [GroupMember]
    var createdAt: Date
    var lastActivity: Date
    
    // 🖼️ NEW: Group picture support
    var groupPictureData: Data?  // Base64 encoded image data
    var groupPictureUpdatedAt: Date?
    var groupPictureUpdatedBy: String?  // Public key of who set it

    // 🎨 NEW: Per-group wallpaper synced across all members
    // (overrides per-user wallpaper when set; nil → fall back to per-user setting)
    var groupWallpaperData: Data?           // Compressed image (≤ 500 KB)
    var groupWallpaperUpdatedAt: Date?
    var groupWallpaperUpdatedBy: String?    // Public key of who set it

    // 🔇 Mute support — silences local notifications for this group
    var isMuted: Bool

    // 📌 Pinned message support
    var pinnedMessageId: UUID?
    var pinnedBy: String?  // public key of who pinned it

    // __GROUP_STATE_VERSION_2026_08_16__
    // Monotonic per-group counter, bumped by `broadcastFullGroupUpdate` — the one
    // place the definition leaves this device. Compared on receipt so a replayed
    // older definition cannot undo the current state.
    //
    // OPTIONAL, and that is the whole compatibility story: Android and every
    // shipped iOS build encode no such key, so they decode to `nil`, NOT to 0. A
    // non-optional `Int` defaulting to 0 would have made every legacy peer look
    // permanently stale the moment our counter passed zero — the group would stop
    // updating across platforms, silently. The comparison therefore runs only when
    // BOTH sides carry a version.
    var stateVersion: Int?

    // __GROUP_EVICTION_TOMBSTONE_2026_09_10__ (F3) Clés des membres RETIRÉS.
    //
    // Ferme le trou d'auto-ajout: `receiveGroupMessage` ré-ajoutait en silence
    // tout expéditeur inconnu « puisqu'il a la clé du groupe ». Un membre retiré
    // qui continuait d'émettre se voyait donc ré-accepté chez les autres, qui
    // recommençaient à lui envelopper les clés de contenu ⇒ il lisait toujours.
    //
    // Une pierre tombale ne RÉTRÉCIT jamais: `applyGroupUpdate` prend l'UNION des
    // ensembles local et entrant, pour qu'une définition périmée ou hostile ne
    // puisse pas « dé-évincer » quelqu'un. Ré-inviter un membre réellement retiré
    // est une action explicite (`addMember`/`joinGroupFromInvite`) qui le retire
    // de cet ensemble — jamais du trafic entrant.
    //
    // Optionnel comme `stateVersion`: nil sur toute build/pair qui l'ignore, et
    // l'union avec nil = l'autre ensemble, donc aucun peer n'est cassé.
    //
    // CE N'EST PAS l'appartenance signée par admin (le correctif complet). C'est
    // la fermeture chirurgicale du ré-ajout silencieux, testable et non cassante.
    var evictedMemberKeys: [String]?

    // __GROUP_E2E_V2_2026_09_23__ §5.1 `description` (Android/Desktop set it; iOS has no editor
    // yet). Kept and re-emitted so an iPhone never erases it from a definition it forwards.
    var groupDescription: String?

    // __GROUP_INFO_SETTINGS_2026_11_20__ GROUP_INFO_SETTINGS_SPEC §2.1. nil = this
    // device never held a `settings` object for the group, so the key is omitted
    // and the definition stays byte-identical to today's. Once set, it is emitted
    // in every definition we send (sender rule).
    var settings: GroupSettings?

    // Computed properties for UI
    var groupType: GroupType { type }
    var lastMessageAt: Date { lastActivity }
    var unreadCount: Int { 0 } // Placeholder - implement with actual logic
    var avatar: String?  // Emoji avatar (fallback when no picture)

    // Permission helpers — collaborative/public: any member; adminOnly: admin only
    private var isCurrentUserAdmin: Bool {
        members.first(where: { $0.publicKey == currentUserPublicKey })?.isAdmin == true
    }
    var canAddMembers: Bool {
        type == .collaborative || type == .publicGroup || isCurrentUserAdmin
    }
    var canRemoveMembers: Bool { canAddMembers }
    var canChangeName: Bool { canAddMembers }
    var canChangeGroupPicture: Bool { canAddMembers }
    var canChangeWallpaper: Bool { canAddMembers }
    var canPinMessages: Bool { canAddMembers }
    var memberPublicKeys: [String] { members.map { $0.publicKey } }
    var memberCount: Int { members.count }

    // MARK: - Invite Links

    /// Universal-link form of the invite (preferred — opens via Safari fallback if app missing).
    /// Format: https://oshi-messenger.com/group?gid=<UUID>&name=<urlencoded>&inviter=<pubkey>
    func inviteLink(inviter: String) -> String {
        var components = URLComponents()
        components.scheme = "https"
        components.host = "oshi-messenger.com"
        components.path = "/group"
        components.queryItems = [
            URLQueryItem(name: "gid", value: id.uuidString),
            URLQueryItem(name: "name", value: name),
            URLQueryItem(name: "inviter", value: inviter)
        ]
        // __GROUP_INFO_SETTINGS_2026_11_20__ §3.2 `t` once an admin reset the link.
        if let t = settings?.inviteToken {
            components.queryItems?.append(URLQueryItem(name: "t", value: t))
        }
        return components.url?.absoluteString ?? "https://oshi-messenger.com/group?gid=\(id.uuidString)"
    }

    /// Custom-scheme form of the invite (back-compat / direct-app open).
    /// Format: oshi://group/join?gid=<UUID>&name=<urlencoded>&inviter=<pubkey>
    func inviteLinkCustomScheme(inviter: String) -> String {
        var components = URLComponents()
        components.scheme = "oshi"
        components.host = "group"
        components.path = "/join"
        components.queryItems = [
            URLQueryItem(name: "gid", value: id.uuidString),
            URLQueryItem(name: "name", value: name),
            URLQueryItem(name: "inviter", value: inviter)
        ]
        // __GROUP_INFO_SETTINGS_2026_11_20__ §3.2 `t` once an admin reset the link.
        if let t = settings?.inviteToken {
            components.queryItems?.append(URLQueryItem(name: "t", value: t))
        }
        return components.url?.absoluteString ?? "oshi://group/join?gid=\(id.uuidString)"
    }
    
    // Store current user key for permission checks
    private var currentUserPublicKey: String = ""
    
    // Custom initializer for creating new groups
    init(id: UUID, name: String, type: GroupType, adminPublicKey: String, members: [GroupMember], createdAt: Date, lastActivity: Date, groupPictureData: Data? = nil, isMuted: Bool = false, avatar: String? = nil) {
        self.id = id
        self.name = name
        self.type = type
        self.adminPublicKey = adminPublicKey
        self.members = members
        self.createdAt = createdAt
        self.lastActivity = lastActivity
        self.groupPictureData = groupPictureData
        self.isMuted = isMuted
        self.avatar = avatar
        self.currentUserPublicKey = ""
    }
    
    // Custom decoder to handle old format without members
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        
        id = try container.decode(UUID.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        type = try container.decode(GroupType.self, forKey: .type)
        adminPublicKey = try container.decode(String.self, forKey: .adminPublicKey)
        // 🔧 FIX: Handle both ISO 8601 strings and double timestamps for dates
        if let date = try? container.decode(Date.self, forKey: .createdAt) {
            createdAt = date
        } else if let ts = try? container.decode(Double.self, forKey: .createdAt) {
            createdAt = Date(timeIntervalSinceReferenceDate: ts)
        } else {
            createdAt = Date()
        }
        if let date = try? container.decode(Date.self, forKey: .lastActivity) {
            lastActivity = date
        } else if let ts = try? container.decode(Double.self, forKey: .lastActivity) {
            lastActivity = Date(timeIntervalSinceReferenceDate: ts)
        } else {
            lastActivity = Date()
        }
        
        // 🖼️ Decode group picture data (optional)
        groupPictureData = try container.decodeIfPresent(Data.self, forKey: .groupPictureData)
        groupPictureUpdatedAt = try container.decodeIfPresent(Date.self, forKey: .groupPictureUpdatedAt)
        groupPictureUpdatedBy = try container.decodeIfPresent(String.self, forKey: .groupPictureUpdatedBy)

        // 🎨 Decode group wallpaper data (optional, nil for old groups)
        groupWallpaperData = try container.decodeIfPresent(Data.self, forKey: .groupWallpaperData)
        groupWallpaperUpdatedAt = try container.decodeIfPresent(Date.self, forKey: .groupWallpaperUpdatedAt)
        groupWallpaperUpdatedBy = try container.decodeIfPresent(String.self, forKey: .groupWallpaperUpdatedBy)
        
        // 🔇 Decode mute setting (default false for existing groups)
        isMuted = (try? container.decodeIfPresent(Bool.self, forKey: .isMuted)) ?? false

        // 📌 Decode pinned message (optional, nil for old groups)
        pinnedMessageId = try? container.decodeIfPresent(UUID.self, forKey: .pinnedMessageId)
        pinnedBy = try? container.decodeIfPresent(String.self, forKey: .pinnedBy)

        // __GROUP_STATE_VERSION_2026_08_16__ nil for anything written before this
        // build and for every Android peer — see the property's note.
        stateVersion = try? container.decodeIfPresent(Int.self, forKey: .stateVersion)

        // __GROUP_EVICTION_TOMBSTONE_2026_09_10__ nil pour toute build/pair antérieur.
        evictedMemberKeys = try? container.decodeIfPresent([String].self, forKey: .evictedMemberKeys)
        groupDescription = try? container.decodeIfPresent(String.self, forKey: .groupDescription)
        // Never throws: GroupSettings sanitises every subkey (§2.1). A non-object
        // value decodes to the defaults rather than failing the whole definition.
        settings = (try? container.decodeIfPresent(GroupSettings.self, forKey: .settings)) ?? nil

        // 🎨 Decode emoji avatar (optional)
        avatar = try? container.decodeIfPresent(String.self, forKey: .avatar)
        
        // 🔧 FIX: Robust member decoding — decode each member individually
        // so one bad member doesn't drop the entire list (was using try? on whole array)
        if let decodedMembers = try? container.decode([GroupMember].self, forKey: .members),
           !decodedMembers.isEmpty {
            members = decodedMembers
            OshiLog.messaging.info("✅ Decoded group '\(name)' with \(decodedMembers.count) members")
        } else if let rawArray = try? container.decode([[String: AnyCodableValue]].self, forKey: .members) {
            // Per-member fallback: decode each member individually
            var parsedMembers: [GroupMember] = []
            for raw in rawArray {
                if let pk = raw["publicKey"]?.stringValue {
                    let alias = raw["alias"]?.stringValue
                    let isAdmin = raw["isAdmin"]?.boolValue ?? false
                    let joinedAt = raw["joinedAt"]?.dateValue ?? createdAt
                    parsedMembers.append(GroupMember(publicKey: pk, alias: alias, joinedAt: joinedAt, isAdmin: isAdmin))
                }
            }
            if !parsedMembers.isEmpty {
                members = parsedMembers
                OshiLog.messaging.info("⚠️ Decoded group '\(name)' with per-member fallback: \(parsedMembers.count) members")
            } else {
                members = [GroupMember(publicKey: adminPublicKey, alias: nil, joinedAt: createdAt, isAdmin: true)]
                OshiLog.messaging.info("⚠️ Migrated old group '\(name)' - created admin member from adminPublicKey")
            }
        } else {
            // Old format: create admin member from adminPublicKey
            members = [GroupMember(publicKey: adminPublicKey, alias: nil, joinedAt: createdAt, isAdmin: true)]
            OshiLog.messaging.info("⚠️ Migrated old group '\(name)' - created admin member from adminPublicKey")
        }
        
        currentUserPublicKey = ""
    }
    
    enum CodingKeys: String, CodingKey {
        case id, name, type, adminPublicKey, members, createdAt, lastActivity
        case groupPictureData, groupPictureUpdatedAt, groupPictureUpdatedBy
        case groupWallpaperData, groupWallpaperUpdatedAt, groupWallpaperUpdatedBy
        case isMuted, pinnedMessageId, pinnedBy, avatar
        case stateVersion
        case evictedMemberKeys
        case groupDescription = "description"
        case settings
    }
    
    mutating func setCurrentUser(_ publicKey: String) {
        currentUserPublicKey = publicKey
    }
    
    func hash(into hasher: inout Hasher) {
        hasher.combine(id)
    }
    
    static func == (lhs: MessageGroup, rhs: MessageGroup) -> Bool {
        lhs.id == rhs.id
    }
}

/// __GROUP_UPDATE_AUTHORIZATION_2026_08_16__ What an inbound group definition is
/// allowed to change. `apply == false` means ignore it entirely; the `keep*` flags
/// name the fields taken from OUR copy instead of the sender's.
struct GroupUpdateDecision: Equatable {
    var apply: Bool
    var keepAdminSet: Bool
    var keepMembership: Bool
    var keepName: Bool
    /// Short machine-readable tag, logged and asserted in tests.
    var reason: String
}

// __MEMBER_COULD_ONLY_BE_REPORTED_AS_A_ROOM_2026_11_06__ `Identifiable` so a
// member can be the subject of a `.sheet(item:)` — the report sheet reached from
// the member row in `GroupInfoSheet`. `publicKey` is already the `ForEach` id
// there, so this adds no new notion of identity.
extension GroupMember: Identifiable {
    var id: String { publicKey }
}

struct GroupMember: Codable, Hashable {
    let publicKey: String
    let alias: String?
    let joinedAt: Date
    var isAdmin: Bool

    enum CodingKeys: String, CodingKey {
        case publicKey, alias, joinedAt, isAdmin
    }

    // 🔧 FIX: Handle both ISO 8601 strings and double timestamps for joinedAt
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        publicKey = try container.decode(String.self, forKey: .publicKey)
        alias = try container.decodeIfPresent(String.self, forKey: .alias)
        isAdmin = (try? container.decode(Bool.self, forKey: .isAdmin)) ?? false
        if let date = try? container.decode(Date.self, forKey: .joinedAt) {
            joinedAt = date
        } else if let ts = try? container.decode(Double.self, forKey: .joinedAt) {
            joinedAt = Date(timeIntervalSinceReferenceDate: ts)
        } else {
            joinedAt = Date()
        }
    }

    init(publicKey: String, alias: String?, joinedAt: Date, isAdmin: Bool) {
        self.publicKey = publicKey
        self.alias = alias
        self.joinedAt = joinedAt
        self.isAdmin = isAdmin
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(publicKey)
    }
}

struct GroupMessage: Identifiable, Codable {
    let id: UUID
    let groupId: UUID
    let senderPublicKey: String
    var encryptedContent: Data   // 📍 var for live location updates
    var timestamp: Date          // 📍 var for live location updates
    var isRead: Bool
    let messageChainIndex: Int
    
    // Media support
    var mediaType: MediaType?
    var mediaData: Data?  // Not persisted - loaded from disk
    var mediaFileName: String?
    var plaintextContent: String?  // For sent messages to show immediately
    
    // 📢 System message support
    var systemMessageType: GroupSystemMessageType?
    var systemMessageData: GroupSystemMessageData?

    // ✏️ Edit support
    var editedAt: Date? = nil
    var editedContent: String? = nil

    // 📮 Delivery status for OUR OWN outgoing group messages (pending → sent →
    // failed). Optional for backward compatibility: legacy persisted messages
    // decode as nil and are treated as `.sent` at display time. Reuses the same
    // `DeliveryStatus` enum as 1:1 so the two chat types read consistently.
    // Groups have no per-recipient delivered/read receipts, so `.delivered` is
    // not used here — `.sent` is the terminal success state.
    var deliveryStatus: DeliveryStatus? = nil

    // __VIDEO_NOTE_2026_09_24__ Round video message (docs/VIDEO_NOTE_SPEC.md §2.B/§2.D).
    // Optional, nil = not encoded: every other GroupMessage JSON is unchanged.
    var videoNote: Bool? = nil
    var durationMs: Int? = nil

    // __BOT_IN_THE_PIPELINE_2026_11_09__ Name of the LOCAL bot that wrote this
    // reply, or nil for a human message. Wire key `botAuthor`, top level of the
    // GroupMessage JSON, absent when nil — byte-compatible with Android
    // `GroupManager.BOT_AUTHOR_FIELD`. It is ONLY a loop guard: a message carrying
    // it never fires a bot trigger (`BotManager.shouldEvaluateTriggers`). It
    // grants nothing — the author is still the envelope's authenticated sender
    // (`GroupWireV2.check`), which for a bot reply is the bot OWNER's key.
    var botAuthor: String? = nil

    // __GROUP_COMMENTS_2026_11_21__ GROUP_INFO_SETTINGS_SPEC §2.12.2. The id of the
    // channel POST this message comments on (UPPERCASE UUID on emit, compared
    // case-insensitively, ≤ 64 chars). Absent for every ordinary message, so
    // their JSON is unchanged. Old builds ignore the key (explicit CodingKeys on
    // 1.0.43) and show the comment as the reply bubble its body already is.
    var commentOn: String? = nil

    var isSystemMessage: Bool {
        senderPublicKey == "SYSTEM" && systemMessageType != nil
    }

    // Custom coding keys to exclude mediaData from persistence
    enum CodingKeys: String, CodingKey {
        case id, groupId, senderPublicKey, encryptedContent, timestamp, isRead, messageChainIndex
        case mediaType, mediaFileName, plaintextContent
        case systemMessageType, systemMessageData
        case editedAt, editedContent
        case deliveryStatus
        case videoNote, durationMs
        case botAuthor
        case commentOn
        // mediaData is excluded - stored separately on disk
    }

    // Standard initializer
    init(id: UUID, groupId: UUID, senderPublicKey: String, encryptedContent: Data, timestamp: Date, isRead: Bool = false, messageChainIndex: Int = 0, mediaType: MediaType? = nil, mediaFileName: String? = nil, plaintextContent: String? = nil, systemMessageType: GroupSystemMessageType? = nil, systemMessageData: GroupSystemMessageData? = nil, editedAt: Date? = nil, editedContent: String? = nil, deliveryStatus: DeliveryStatus? = nil) {
        self.id = id
        self.groupId = groupId
        self.senderPublicKey = senderPublicKey
        self.encryptedContent = encryptedContent
        self.timestamp = timestamp
        self.isRead = isRead
        self.messageChainIndex = messageChainIndex
        self.mediaType = mediaType
        self.mediaFileName = mediaFileName
        self.plaintextContent = plaintextContent
        self.systemMessageType = systemMessageType
        self.systemMessageData = systemMessageData
        self.editedAt = editedAt
        self.editedContent = editedContent
        self.deliveryStatus = deliveryStatus
    }
}

extension GroupMessage {
    /// __VIDEO_NOTE_2026_09_24__ Written out only so that `videoNote` /
    /// `durationMs` decode TOLERANTLY: with the synthesized decoder a peer that
    /// sent `"videoNote":"yes"` would throw away the whole group message. Every
    /// other key decodes exactly as the synthesized code did (same required
    /// keys, same `decodeIfPresent` for the optionals).
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        groupId = try c.decode(UUID.self, forKey: .groupId)
        senderPublicKey = try c.decode(String.self, forKey: .senderPublicKey)
        encryptedContent = try c.decode(Data.self, forKey: .encryptedContent)
        timestamp = try c.decode(Date.self, forKey: .timestamp)
        isRead = try c.decode(Bool.self, forKey: .isRead)
        messageChainIndex = try c.decode(Int.self, forKey: .messageChainIndex)
        mediaType = try c.decodeIfPresent(MediaType.self, forKey: .mediaType)
        mediaData = nil
        mediaFileName = try c.decodeIfPresent(String.self, forKey: .mediaFileName)
        plaintextContent = try c.decodeIfPresent(String.self, forKey: .plaintextContent)
        systemMessageType = try c.decodeIfPresent(GroupSystemMessageType.self, forKey: .systemMessageType)
        systemMessageData = try c.decodeIfPresent(GroupSystemMessageData.self, forKey: .systemMessageData)
        editedAt = try c.decodeIfPresent(Date.self, forKey: .editedAt)
        editedContent = try c.decodeIfPresent(String.self, forKey: .editedContent)
        deliveryStatus = try c.decodeIfPresent(DeliveryStatus.self, forKey: .deliveryStatus)
        videoNote = (try? c.decodeIfPresent(Bool.self, forKey: .videoNote)) ?? nil
        durationMs = VideoNoteMetadata.sanitize((try? c.decodeIfPresent(Int.self, forKey: .durationMs)) ?? nil)
        // Tolerant like `videoNote`: a malformed flag must not cost the message.
        botAuthor = (try? c.decodeIfPresent(String.self, forKey: .botAuthor)) ?? nil
        // __GROUP_COMMENTS_2026_11_21__ tolerant too: a malformed `commentOn` makes
        // the message an ordinary one (then filtered by §2.4), never a lost decode.
        commentOn = GroupCommentPolicy.normalizedCommentOn((try? c.decodeIfPresent(String.self, forKey: .commentOn)) ?? nil)
    }

    /// __BOT_IN_THE_PIPELINE_2026_11_09__ Was this written by a bot (wire flag)?
    /// Same test as Android (`optString(BOT_AUTHOR_FIELD, "").isNotEmpty()`).
    var isBotAuthored: Bool { !(botAuthor ?? "").isEmpty }
}

enum MediaType: String, Codable {
    case photo
    case video
    case audio
    case document
    case contact

    // __GROUP_E2E_V2_2026_09_23__ §2.2: `image` is accepted as an alias of `photo` (Android).
    init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        if raw == "image" { self = .photo; return }
        guard let v = MediaType(rawValue: raw) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath,
                                                    debugDescription: "unknown group mediaType \(raw)"))
        }
        self = v
    }
}

// MARK: - Sender Key (for group encryption)

struct SenderKey: Codable {
    let chainKey: Data
    let messageKeys: [Int: Data]
    var chainIndex: Int
}

// MARK: - Message Group Manager

@MainActor
class MessageGroupManager: ObservableObject {
    
    // MARK: - Debug File Logger
    private func debugLog(_ message: String) {
        // 🔒 PRIVACY (2026-07-12): never persist group/sender metadata to a plaintext
        // file on a shipping build. The `group_debug.log` write was un-gated and left
        // group ids + sender-key prefixes readable on disk in Release. Keep the console
        // trace for development only; no disk write ever in Release.
        #if DEBUG
        let timestamp = ISO8601DateFormatter().string(from: Date())
        let line = "[\(timestamp)] \(message)\n"
        NSLog("🔔 [OSHI-Group] %@", message)
        if let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            let logFile = docs.appendingPathComponent("group_debug.log")
            if let data = line.data(using: .utf8) {
                if FileManager.default.fileExists(atPath: logFile.path) {
                    if let handle = try? FileHandle(forWritingTo: logFile) {
                        handle.seekToEndOfFile()
                        handle.write(data)
                        handle.closeFile()
                    }
                } else {
                    try? data.write(to: logFile)
                }
            }
        }
        #endif
    }
    @Published var groups: [MessageGroup] = []
    @Published var groupMessages: [UUID: [GroupMessage]] = [:]

    /// 🔔 UNREAD FIX: the group whose chat view is currently on screen, if any.
    /// Set by GroupChatView.onAppear and cleared on .onDisappear. When a message
    /// arrives for this group while it is open, receiveGroupMessage marks it read
    /// immediately so the conversation list badge does NOT flip to unread.
    /// Mirrors the 1:1 path which checks the visible ChatView before incrementing.
    @Published var activeGroupId: UUID?
    /// __GROUP_E2E_V2_2026_09_23__ §6 members with no v2 bundle (old builds), per group, as of
    /// our last send. They receive nothing; the group screen lists them.
    @Published var v2UnreachableMembers: [UUID: [String]] = [:]

    private var senderKeys: [String: [UUID: SenderKey]] = [:]  // [publicKey: [groupId: SenderKey]]
    
    private let storageKey = "messageGroups_v2"
    private let messagesKey = "groupMessages_v2"
    private let senderKeysKey = "senderKeys_v2"
    
    // Singleton instance
    static let shared = MessageGroupManager()
    
    // 🔥 Current user public key for reactions
    var currentUserPublicKey: String? {
        UserDefaults.standard.string(forKey: "publicKey")
    }

    // 🔧 Base64URL encoding for URL-safe public keys
    private func base64urlEncode(_ base64String: String) -> String {
        return base64String
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Derive envelope encryption key from group ID (matches IPFSGroupUploader & MessageManager)
    /// Always normalizes to uppercase to match Android's deriveGroupKey (which calls .uppercase()).
    private func deriveGroupKey(groupId: String) -> Data {
        let groupIdData = Data(groupId.uppercased().utf8)
        let salt = Data("oshi-group-envelope-key".utf8)
        var combined = Data()
        combined.append(groupIdData)
        combined.append(salt)
        let hash = SHA256.hash(data: combined)
        return hash.withUnsafeBytes { Data($0) }
    }

    private init() {
        // Instrumented: this init was one of the two launch-path blind spots — it
        // decodes groupMessages_v2.json (which used to carry inlined media) on the
        // main thread. Now visible in launch_timing.log.
        LaunchTimingLogger.mark("MessageGroupManager.init.start")
        defer { LaunchTimingLogger.mark("MessageGroupManager.init.end") }
        migrateToFileStorage()
        loadGroups()
        LaunchTimingLogger.measure("MessageGroupManager.loadMessages") { loadMessages() }
        loadSenderKeys()
        setupBroadcastListener()
        setupIncomingMessageListener()
        startPeriodicPublicGroupBroadcast()
        startPeriodicGroupSync()
        startPendingDeliveryLoop()   // __GROUP_RESEND_2026_11_25__
        // __GROUP_INFO_SETTINGS_2026_11_20__ §4.3 failed DELETEs retried at launch;
        // §4.2 public handles re-PUT at most weekly so the 60-day sweeper keeps them.
        // Deferred: never on the launch path, and never in unit tests.
        if !Self.isRunningUnitTests, !ProcessInfo.processInfo.arguments.contains("-OSHIScreens") {
            // __CHANNEL_WEB_2026_11_26__ resume the public-web outbox (durable across launches).
            DispatchQueue.main.asyncAfter(deadline: .now() + 6) { ChannelWebPublisher.shared.start() }
            DispatchQueue.main.asyncAfter(deadline: .now() + 20) { [weak self] in
                GroupHandleRegistry.retryPendingUnregisters()
                for g in self?.groups ?? [] { GroupHandleRegistry.refreshIfDue(g, me: self?.currentUserPublicKey) }
            }
        }
        #if DEBUG
        GroupE2EDriver.startIfRequested()   // __GROUP_E2E_V2_2026_09_23__ test-only
        #endif

        // Retry any pending group envelopes after groups are loaded from storage
        // (handles identity restore scenario where IPFS messages arrived before groups loaded)
        if !groups.isEmpty {
            DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) {
                MessageManager.shared?.retryPendingGroupEnvelopes()
            }
        }
    }
    
    // MARK: - Broadcast Listener
    
    private func setupBroadcastListener() {
        NotificationCenter.default.addObserver(
            forName: NSNotification.Name("ShouldBroadcastPublicGroups"),
            object: nil,
            queue: .main
        ) { [weak self] _ in
            OshiLog.messaging.info("📡 Received ShouldBroadcastPublicGroups notification")
            Task { @MainActor in
                self?.broadcastAllPublicGroups()
            }
        }
        OshiLog.messaging.info("✅ MessageGroupManager: Listening for ShouldBroadcastPublicGroups")
    }
    
    // MARK: - Incoming Message Listener
    
    private func setupIncomingMessageListener() {
        debugLog("setupIncomingMessageListener called - wiring observers")
        // Listen for group messages from mesh network
        NotificationCenter.default.addObserver(
            forName: NSNotification.Name("NewGroupMessage"),
            object: nil,
            queue: .main
        ) { [weak self] notification in
            // Handle raw data from mesh network
            if let messageData = notification.userInfo?["groupMessage"] as? Data {
                Task { @MainActor in
                    self?.handleIncomingGroupMessage(data: messageData)
                }
            }
        }
        
        // Listen for group updates from mesh network
        NotificationCenter.default.addObserver(
            forName: NSNotification.Name("GroupUpdateReceived"),
            object: nil,
            queue: .main
        ) { [weak self] notification in
            if let groupData = notification.userInfo?["groupData"] as? Data {
                // __GROUP_UPDATE_AUTHORIZATION_2026_08_16__ `updatedBy` carries the
                // authenticated sender when the transport knows one (the 1:1
                // encrypted funnel does). Absent from transports that do not carry
                // it yet — the authorizer treats that as unauthenticated rather
                // than as trusted.
                let sender = notification.userInfo?["updatedBy"] as? String
                Task { @MainActor in
                    self?.handleIncomingGroupUpdate(data: groupData, from: sender)
                }
            }
        }
        
        // ✅ FIX: Listen for IPFS encrypted envelope group messages (was dead — nobody observed this)
        NotificationCenter.default.addObserver(
            forName: NSNotification.Name("ReceivedGroupMessageFromIPFS"),
            object: nil,
            queue: .main
        ) { [weak self] notification in
            if let message = notification.userInfo?["groupMessage"] as? GroupMessage {
                Task { @MainActor in
                    self?.receiveGroupMessage(message)
                }
            }
        }

        // ✅ FIX: Listen for CloudGroupService messages (was dead — nobody observed this)
        NotificationCenter.default.addObserver(
            forName: NSNotification.Name("CloudGroupMessageReceived"),
            object: nil,
            queue: .main
        ) { [weak self] notification in
            if let messageData = notification.userInfo?["messageData"] as? Data {
                Task { @MainActor in
                    self?.handleIncomingGroupMessage(data: messageData)
                }
            }
        }

        // ✅ FIX: Listen for CloudGroupService updates (was dead — nobody observed this)
        NotificationCenter.default.addObserver(
            forName: NSNotification.Name("CloudGroupUpdateReceived"),
            object: nil,
            queue: .main
        ) { [weak self] notification in
            if let groupData = notification.userInfo?["groupData"] as? Data {
                // __GROUP_UPDATE_AUTHORIZATION_2026_08_16__ `updatedBy` carries the
                // authenticated sender when the transport knows one (the 1:1
                // encrypted funnel does). Absent from transports that do not carry
                // it yet — the authorizer treats that as unauthenticated rather
                // than as trusted.
                let sender = notification.userInfo?["updatedBy"] as? String
                Task { @MainActor in
                    self?.handleIncomingGroupUpdate(data: groupData, from: sender)
                }
            }
        }

        // Push groups to new CrossPlatformMesh peers automatically
        NotificationCenter.default.addObserver(
            forName: NSNotification.Name("CrossPlatformPeerConnected"),
            object: nil,
            queue: .main
        ) { [weak self] notification in
            // __GROUP_E2E_V2_2026_09_23__ This used to push every group the peer belonged to
            // (name, roster, picture) over the cleartext mesh the moment it connected. Group
            // state now travels only as v2 pairwise messages (spec rule zero); a peer that
            // needs it asks with a v2 `sync_request`.
            _ = notification
            _ = self
        }

        // Handle encrypted group message envelopes received via CrossPlatformMesh from Android
        NotificationCenter.default.addObserver(
            forName: NSNotification.Name("MeshGroupEnvelopeReceived"),
            object: nil,
            queue: .main
        ) { [weak self] notification in
            if let envelopeData = notification.userInfo?["envelopeData"] as? Data,
               let senderPublicKey = notification.userInfo?["senderPublicKey"] as? String {
                Task { @MainActor in
                    self?.handleMeshGroupEnvelope(envelopeData: envelopeData, senderPublicKey: senderPublicKey)
                }
            }
        }

        OshiLog.messaging.info("✅ MessageGroupManager: Listening for incoming group messages (mesh + IPFS + cloud)")
    }

    // MARK: - Incoming Message Handling

    /// The part of mesh-envelope handling that is the same whether the envelope
    /// was v2 (group key) or v3 (per-member wraps): a decrypted `MessagePayload`
    /// becomes a `GroupMessage` and reaches the UI.
    ///
    /// Extracted rather than duplicated so the two formats cannot drift — the
    /// Android-shape tolerance below is exactly the fix that stopped every
    /// Android group message from vanishing, and it must apply to both.
    @MainActor
    private func deliverGroupPayload(_ payload: MessagePayload, senderPublicKey: String) {
        guard payload.type == "group_message" else {
            OshiLog.messaging.info("📡 MeshGroupEnvelope: unexpected payload type '\(payload.type)'")
            return
        }
        guard let contentData = Data(base64Encoded: payload.content) else {
            OshiLog.messaging.info("❌ MeshGroupEnvelope: payload.content not valid base64")
            return
        }
        guard let groupMessage = Self.parseCrossPlatformGroupMessage(data: contentData) else {
            OshiLog.messaging.info("❌ MeshGroupEnvelope: failed to decode inner GroupMessage")
            return
        }
        if let myPublicKey = UserDefaults.standard.string(forKey: "publicKey"),
           groupMessage.senderPublicKey == myPublicKey {
            return
        }
        OshiLog.messaging.info("📡 MeshGroupEnvelope: ✅ delivered from \(senderPublicKey.prefix(8))...")
        receiveGroupMessage(groupMessage)
    }

    /// Handle an encrypted group envelope delivered directly via CrossPlatformMesh (from Android).
    /// The envelope format is identical to IPFS delivery — try decryption against all known groups.
    @MainActor
    private func handleMeshGroupEnvelope(envelopeData: Data, senderPublicKey: String) {
        // CV-002 — a v3 envelope has a different SHAPE (no `envelope` field), so it
        // is recognised before the legacy decode. It also needs no per-group trial
        // decryption: we find our own wrap by hint, and the group id travels
        // inside the payload we then open.
        if let sealed = GroupEnvelopeV3.fromPinned(envelopeData), sealed.v == GroupEnvelopeV3.version,
           let identity = MessageManager.sharedIdentityManager {
            do {
                let payloadData = try GroupEnvelopeV3.open(
                    sealed,
                    asMember: identity.publicKey,
                    decryptWrap: GroupEnvelopeV3.liveDecryptor(senderPublicKey: senderPublicKey,
                                                               ourPublicKey: identity.publicKey,
                                                               identity: identity))
                let payload = try JSONDecoder().decode(MessagePayload.self, from: payloadData)
                deliverGroupPayload(payload, senderPublicKey: senderPublicKey)
            } catch GroupEnvelopeV3.Failure.noWrapForUs {
                // Normal: a group we are not in, or a message sent before we joined.
                OshiLog.messaging.info("📡 group v3: not addressed to us")
            } catch GroupEnvelopeV3.Failure.wrapUndecryptable {
                // The 1:1 ratchet with this sender has drifted.
                //
                // Deliberately NOT healing from here. An undecryptable wrap is
                // attacker-supplied — anyone could pin a malformed envelope — so
                // tearing down a session on it would be a free denial of service
                // against any member. The existing 1:1 heal path already runs on
                // direct-message failure with its own rate limiting, and that is
                // the one place a reset should be decided.
                OshiLog.messaging.info("⚠️ group v3: ratchet desync with \(senderPublicKey.prefix(8))… — 1:1 heal will resolve")
            } catch {
                OshiLog.messaging.info("❌ group v3: \(String(describing: error))")
            }
            return
        }

        guard let envelope = try? JSONDecoder().decode(EncryptedEnvelope.self, from: envelopeData) else {
            OshiLog.messaging.info("❌ MeshGroupEnvelope: failed to decode EncryptedEnvelope JSON")
            return
        }
        guard envelope.v == EncryptedEnvelope.currentVersion else {
            OshiLog.messaging.info("⚠️ MeshGroupEnvelope: unsupported version \(envelope.v), skipping")
            return
        }

        for group in groups {
            let groupKey = deriveGroupKey(groupId: group.id.uuidString)
            guard let payload = try? EnvelopeCrypto.decryptGroup(envelope: envelope, groupKey: groupKey) else {
                continue
            }
            // Decryption succeeded — this envelope belongs to `group`
            OshiLog.messaging.info("📡 MeshGroupEnvelope: matched group '\(group.name)'")
            deliverGroupPayload(payload, senderPublicKey: senderPublicKey)
            return
        }
        OshiLog.messaging.info("⚠️ MeshGroupEnvelope: no group key matched — group not yet known (will retry via IPFS)")
        // __GROUP_SELF_HEAL_2026_10_01__ Can't identify the group (key derives from a groupId we
        // no longer have), but we know the sender — ask them for every group we share.
        requestAllGroupsSyncFromSender(senderPublicKey)
    }

    /// Tolerant decoder for group-message JSON arriving from either iOS
    /// (Codable shape) or Android (cross-platform shape).
    ///
    /// iOS shape:    {id:UUID, groupId:UUID, encryptedContent:base64,
    ///                timestamp:ISO/seconds, isRead:bool, messageChainIndex:int, …}
    /// Android shape:{id:string, groupId:string, content:string,
    ///                senderName:string, timestamp:long-millis, isFromMe:bool,
    ///                mediaType:string?, mediaData:base64?}
    ///
    /// Tries Codable first; on failure falls back to manual JSONSerialization
    /// + field-by-field mapping. The result always uses the iOS-native types
    /// (UUID, Date, Data) so downstream code is unaware of the source.
    /// __STABLE_PARSED_ID_2026_10_19__ A deterministic UUID for a wire identity,
    /// so the same message parsed twice is the same message. First 16 bytes of
    /// SHA-256, stamped as RFC-4122 v5 — collision risk is not a consideration at
    /// this width, and the value is local-only (it is never put back on the wire).
    static func stableUUID(from key: String) -> UUID {
        var b = Array(SHA256.hash(data: Data(key.utf8)).prefix(16))
        b[6] = (b[6] & 0x0F) | 0x50
        b[8] = (b[8] & 0x3F) | 0x80
        return UUID(uuid: (b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7],
                           b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]))
    }

    static func parseCrossPlatformGroupMessage(data: Data) -> GroupMessage? {
        // Path 1: iOS Codable. Works for iOS-to-iOS.
        if let message = try? JSONDecoder().decode(GroupMessage.self, from: data) {
            return message
        }

        // Path 2: Android cross-platform JSON. Hand-parse and synthesize.
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        // ID (Android sends string; sometimes "messageId", sometimes "id").
        //
        // __STABLE_PARSED_ID_2026_10_19__ The id must be a FUNCTION of the wire,
        // never freshly minted.
        //
        // This read `UUID(uuidString: idStr) ?? UUID()`, with `UUID().uuidString`
        // as the default for a missing id — so any message whose wire id is not a
        // UUID (Android ids are not always one), or that carries no id at all, got
        // a BRAND-NEW id on every parse. `receiveGroupMessage` dedupes by `id`, so
        // the same message parsed twice was two different messages: it was appended
        // again and the thread went unread again, seconds after being read.
        //
        // That second parse is routine, not exotic — a group message rides the mesh
        // AND the relay (__GROUP_MESH_IS_A_TRANSPORT_2026_10_16__), and the relay
        // re-serves until every device acks. Hashing instead of minting makes the
        // two copies collide on the same id, which is what the dedupe needs.
        let wireId = ((obj["id"] as? String) ?? (obj["messageId"] as? String))
            .flatMap { $0.isEmpty ? nil : $0 }
        // Group id — both platforms send a UUID string.
        guard let groupIdStr = obj["groupId"] as? String,
              let groupId = UUID(uuidString: groupIdStr) else {
            return nil
        }
        // Sender pubkey is mandatory.
        guard let senderPublicKey = obj["senderPublicKey"] as? String, !senderPublicKey.isEmpty else {
            return nil
        }
        // Content body — Android uses `content`; iOS legacy uses `plaintextContent`
        // or base64-encoded `encryptedContent`. We store the raw bytes in
        // `encryptedContent` (Data) and stash the readable text in
        // `plaintextContent` so the UI shows it without an extra decode step.
        let plaintext: String? = (obj["content"] as? String)
            ?? (obj["plaintextContent"] as? String)
        var encryptedContent = Data()
        if let plaintext = plaintext {
            encryptedContent = plaintext.data(using: .utf8) ?? Data()
        } else if let encB64 = obj["encryptedContent"] as? String,
                  let dec = Data(base64Encoded: encB64) {
            encryptedContent = dec
        }
        // Timestamp — Android sends Long millis since 1970; iOS Codable
        // default is seconds since 2001 (Apple epoch). Detect by magnitude.
        let timestamp: Date
        if let ts = obj["timestamp"] as? Double, ts > 0 {
            // > 1e12 → millis; > 1e9 but < 1e12 → seconds since 1970;
            // smaller → Apple epoch seconds.
            if ts > 1e12 {
                timestamp = Date(timeIntervalSince1970: ts / 1000.0)
            } else if ts > 1e9 {
                timestamp = Date(timeIntervalSince1970: ts)
            } else {
                timestamp = Date(timeIntervalSinceReferenceDate: ts)
            }
        } else if let isoStr = obj["timestamp"] as? String,
                  let parsed = ISO8601DateFormatter().date(from: isoStr) {
            timestamp = parsed
        } else {
            timestamp = Date()
        }
        // messageChainIndex — Android doesn't send it; default 0.
        let chainIdx = obj["messageChainIndex"] as? Int ?? 0
        // Media — Android sends base64 mediaData + mediaType string.
        var mediaType: MediaType? = nil
        if let mtStr = obj["mediaType"] as? String, !mtStr.isEmpty {
            mediaType = MediaType(rawValue: mtStr.lowercased())
        }
        let mediaFileName = obj["mediaFileName"] as? String
        // Resolved here, where the identifying fields exist. A wire id that is a
        // UUID is used as-is; any other non-empty id is hashed; with no id at all
        // we hash what actually identifies the message. Never `UUID()`.
        let id: UUID = {
            if let wireId, let exact = UUID(uuidString: wireId) { return exact }
            if let wireId { return Self.stableUUID(from: "gid:\(groupId.uuidString)|wire:\(wireId)") }
            let bodyKey = plaintext ?? encryptedContent.base64EncodedString()
            return Self.stableUUID(from: "gid:\(groupId.uuidString)|from:\(senderPublicKey)"
                                   + "|ts:\(Int(timestamp.timeIntervalSince1970 * 1000))|body:\(bodyKey)")
        }()
        var parsed = GroupMessage(
            id: id,
            groupId: groupId,
            senderPublicKey: senderPublicKey,
            encryptedContent: encryptedContent,
            timestamp: timestamp,
            isRead: obj["isRead"] as? Bool ?? false,
            messageChainIndex: chainIdx,
            mediaType: mediaType,
            mediaFileName: mediaFileName,
            plaintextContent: plaintext
        )
        // __VIDEO_NOTE_2026_09_24__ spec §2.D — optional, tolerant.
        parsed.applyVideoNote(VideoNoteMetadata.parse(obj, isVideo: mediaType == .video))
        return parsed
    }

    @MainActor private func handleIncomingGroupMessage(data: Data) {
        debugLog("handleIncomingGroupMessage called, data size=\(data.count)")
        do {
            // Try the tolerant parser first so Android-shaped JSON arriving
            // through any of the mesh/IPFS/cloud receive paths is handled
            // even when our standard Codable decoder would reject it.
            if let message = Self.parseCrossPlatformGroupMessage(data: data) {
                debugLog("Decoded GroupMessage (cross-platform tolerant): group=\(message.groupId.uuidString.prefix(8)), sender=\(message.senderPublicKey.prefix(8))")
                if let myPublicKey = UserDefaults.standard.string(forKey: "publicKey"),
                   message.senderPublicKey == myPublicKey {
                    return
                }
                receiveGroupMessage(message)
                return
            }
            // Fallback to strict Codable decode (legacy iOS-to-iOS payloads
            // that happen to fail manual mapping for any reason — keeps the
            // original behavior).
            let message = try JSONDecoder().decode(GroupMessage.self, from: data)
            debugLog("Decoded GroupMessage OK: group=\(message.groupId.uuidString.prefix(8)), sender=\(message.senderPublicKey.prefix(8))")
            OshiLog.messaging.info("📥 Received group message for group: \(message.groupId)")
            
            // Skip if it's our own message
            if let myPublicKey = UserDefaults.standard.string(forKey: "publicKey"),
               message.senderPublicKey == myPublicKey {
                OshiLog.messaging.info("⏭️ Skipping own message")
                return
            }
            
            // Delegate to receiveGroupMessage (handles dedup, storage, notification, UI post)
            receiveGroupMessage(message)
            
        } catch {
            debugLog("DECODE FAILED: \(error.localizedDescription)")
            OshiLog.messaging.info("❌ Failed to decode group message: \(error)")
        }
    }
    
    /// __GROUP_UPDATE_AUTHORIZATION_2026_08_16__
    ///
    /// Decide what an inbound group definition is allowed to change. Pure, so the
    /// rules are exercised directly in `GroupUpdateAuthorizationTests` rather than
    /// inferred from behaviour.
    ///
    /// Before this, `handleIncomingGroupUpdate` replaced the stored group WHOLESALE
    /// — `groups[index] = updatedGroup` — with no check of any kind. `isAdmin` is a
    /// plain boolean decoded from the received JSON, so any member (indeed anyone
    /// who could get a definition to us) could promote themselves, rewrite the
    /// member list, or rename the group. Every admin-gated feature, present and
    /// future, was therefore gated on a flag the requester could grant themselves.
    ///
    /// Three rules, in the order they are cheapest to check:
    ///
    ///   1. VERSION — a definition older than ours is a replay; ignore it. Only
    ///      compared when both sides carry a version (see `stateVersion`).
    ///   2. STANDING — an update from someone who is not currently a member of our
    ///      copy is ignored outright.
    ///   3. AUTHORITY — the admin set (`adminPublicKey` + every `isAdmin`) is only
    ///      ever taken from a sender who is ALREADY an admin here. Membership and
    ///      name follow the group's own policy: any member may in a collaborative
    ///      or public group, admins only in an admin-only group.
    ///
    /// A failed check downgrades what the update may touch — it does not throw the
    /// whole thing away. That matters: cosmetic fields (picture, wallpaper, pin)
    /// legitimately arrive from ordinary members, and a hard reject would have
    /// traded a privilege-escalation hole for a sync outage.
    ///
    /// `sender == nil` is the legacy-transport case (mesh and the cloud paths do
    /// not carry an authenticated sender yet). It keeps today's permissive
    /// behaviour for everything EXCEPT the admin set, which is never taken from an
    /// unauthenticated update. That is the smallest change that closes escalation
    /// without breaking a working transport.
    nonisolated static func authorizeGroupUpdate(current: MessageGroup,
                                     incoming: MessageGroup,
                                     sender: String?) -> GroupUpdateDecision {
        // 1. Replay of an older definition.
        if let incomingVersion = incoming.stateVersion,
           let currentVersion = current.stateVersion,
           incomingVersion < currentVersion {
            return GroupUpdateDecision(apply: false, keepAdminSet: true, keepMembership: true,
                                       keepName: true, reason: "stale-version")
        }

        // __UNAUTHENTICATED_GROUP_UPDATE_2026_11_20__ No sender means nobody
        // proved anything: the LAN bridge `GROUP_UPDATE` frame, the
        // MultipeerConnectivity JSON branch and the IPFS `group_update` all
        // accept bytes from whoever knows the group's UUID (it is in every
        // invite link). Letting such an update rewrite the ROSTER let that
        // person add their own key — after which every member wraps each new
        // message key to them and they read the group — or drop members. Only
        // cosmetic fields (picture, wallpaper, pin) still flow; roster and name
        // need the authenticated 1:1 / v2 channel, which every build since
        // 23/09 uses for group state and which older builds also use
        // (📢GROUP_UPDATE📢 inside a ratchet message carries `updatedBy`).
        guard let sender, !sender.isEmpty else {
            return GroupUpdateDecision(apply: true, keepAdminSet: true, keepMembership: true,
                                       keepName: true, reason: "unauthenticated-transport")
        }

        let senderKey = MessageManager.canonicalIdentity(sender)
        let senderMember = current.members.first {
            MessageManager.canonicalIdentity($0.publicKey) == senderKey
        }

        // 2. Not one of us.
        guard let senderMember else {
            return GroupUpdateDecision(apply: false, keepAdminSet: true, keepMembership: true,
                                       keepName: true, reason: "sender-not-a-member")
        }

        // 3. Authority.
        if senderMember.isAdmin {
            return GroupUpdateDecision(apply: true, keepAdminSet: false, keepMembership: false,
                                       keepName: false, reason: "admin")
        }
        switch current.type {
        case .collaborative, .publicGroup:
            // The group's own policy already lets any member add or rename
            // (`canAddMembers`); only the admin set stays out of reach.
            return GroupUpdateDecision(apply: true, keepAdminSet: true, keepMembership: false,
                                       keepName: false, reason: "member-of-open-group")
        case .adminOnly:
            return GroupUpdateDecision(apply: true, keepAdminSet: true, keepMembership: true,
                                       keepName: true, reason: "member-of-admin-only-group")
        }
    }

    /// Fold `incoming` into `current` according to `decision`, keeping every field
    /// the decision protects. Pure — the caller stores the result.
    nonisolated static let adminReason = "admin"
    /// __GROUP_VERSION_FREEZE_2026_11_20__ Largest forward jump one update may make.
    nonisolated static let maxVersionStep = 1024

    nonisolated static func boundedVersion(current: Int?, incoming: Int?, mayAdvance: Bool) -> Int? {
        guard mayAdvance, let incoming else { return current }
        let base = current ?? 0
        let limit = base > Int.max - maxVersionStep ? Int.max : base + maxVersionStep
        let capped = min(incoming, limit)
        return max(current ?? capped, capped)
    }

    nonisolated static func applyGroupUpdate(current: MessageGroup,
                                 incoming: MessageGroup,
                                 decision: GroupUpdateDecision) -> MessageGroup {
        var merged = incoming
        if decision.keepAdminSet {
            merged.adminPublicKey = current.adminPublicKey
            // Re-stamp the admin flags from OUR copy, keyed by member. A member the
            // incoming update introduces cannot arrive pre-promoted.
            let admins = Set(current.members.filter { $0.isAdmin }
                                            .map { MessageManager.canonicalIdentity($0.publicKey) })
            merged.members = merged.members.map { member in
                GroupMember(publicKey: member.publicKey,
                            alias: member.alias,
                            joinedAt: member.joinedAt,
                            isAdmin: admins.contains(MessageManager.canonicalIdentity(member.publicKey)))
            }
        }
        if decision.keepMembership { merged.members = current.members }
        if decision.keepName { merged.name = current.name }

        let fromAdmin = decision.reason == Self.adminReason
        // __GROUP_TYPE_TAKEOVER_2026_11_20__ The TYPE decides who may change
        // everything else, so only an admin may change it. `merged = incoming`
        // carried a member's `type` through: one `collaborative` from a member of
        // an admin-only group, and every later update of theirs passed the
        // open-group rule (add/remove anyone, rename, drop the admin).
        if !fromAdmin { merged.type = current.type }
        if !fromAdmin && !decision.keepMembership {
            // __GROUP_ADMIN_KICK_2026_11_20__ An open-group member may add and
            // drop members, but not drop an admin nor bring back an evicted key.
            let have = Set(merged.members.map { MessageManager.canonicalIdentity($0.publicKey) })
            let keptAdmins = current.members.filter {
                $0.isAdmin && !have.contains(MessageManager.canonicalIdentity($0.publicKey))
            }
            let evicted = Set((current.evictedMemberKeys ?? []).map { MessageManager.canonicalIdentity($0) })
            let currentKeys = Set(current.members.map { MessageManager.canonicalIdentity($0.publicKey) })
            merged.members = merged.members.filter {
                let k = MessageManager.canonicalIdentity($0.publicKey)
                return !evicted.contains(k) || currentKeys.contains(k)
            } + keptAdmins
        }
        // In an admin-only group a member changes nothing about the group
        // itself: picture, wallpaper, avatar, pin and description stay the admin's.
        if decision.reason == "member-of-admin-only-group" {
            merged.groupPictureData = current.groupPictureData
            merged.groupPictureUpdatedAt = current.groupPictureUpdatedAt
            merged.groupPictureUpdatedBy = current.groupPictureUpdatedBy
            merged.groupWallpaperData = current.groupWallpaperData
            merged.groupWallpaperUpdatedAt = current.groupWallpaperUpdatedAt
            merged.groupWallpaperUpdatedBy = current.groupWallpaperUpdatedBy
            merged.avatar = current.avatar
            merged.pinnedMessageId = current.pinnedMessageId
            merged.pinnedBy = current.pinnedBy
            merged.groupDescription = current.groupDescription
        }
        // __GROUP_VERSION_FREEZE_2026_11_20__ The incoming version is sender-
        // chosen; one definition at Int.max made every later admin update
        // "stale-version" forever. Adopted only from a sender who may change the
        // roster, and then forward by at most `maxVersionStep`.
        merged.stateVersion = Self.boundedVersion(current: current.stateVersion,
                                                  incoming: incoming.stateVersion,
                                                  mayAdvance: !decision.keepMembership)
        // __GROUP_EVICTION_TOMBSTONE_2026_09_10__ (F3) Les pierres tombales ne
        // rétrécissent jamais: on prend l'UNION, jamais la valeur entrante seule.
        // Ainsi une définition périmée/hostile qui « oublie » une éviction ne peut
        // pas ré-ouvrir la porte, et une session parallèle qui n'a pas encore vu
        // le retrait ne l'annule pas. Et on retire des MEMBRES tout évincé, au cas
        // où l'entrant l'aurait (ré)introduit.
        var union = current.evictedMemberKeys ?? []
        let unionCanon = Set(union.map { MessageManager.canonicalIdentity($0) })
        // __GROUP_ADMIN_KICK_2026_11_20__ Only an admin adds tombstones: a member's
        // tombstone list could otherwise evict the admin (and anyone) for good.
        for k in (fromAdmin ? (incoming.evictedMemberKeys ?? []) : [])
            where !unionCanon.contains(MessageManager.canonicalIdentity(k)) {
            union.append(k)
        }
        merged.evictedMemberKeys = union.isEmpty ? nil : union
        if !union.isEmpty {
            let evictedCanon = Set(union.map { MessageManager.canonicalIdentity($0) })
            merged.members = merged.members.filter {
                !evictedCanon.contains(MessageManager.canonicalIdentity($0.publicKey))
            }
        }
        // __GROUP_INFO_SETTINGS_2026_11_20__ §2.1 / §2.2. `settings` only from an
        // admin of OUR copy (a collaborative-group member renames, never sets the
        // type of group it is); absent keeps ours (an old build re-emits the
        // definition without the key); `dissolved` never goes back to false.
        merged.settings = GroupSettingsPolicy.merge(current: current.settings,
                                                    incoming: incoming.settings,
                                                    fromAdmin: fromAdmin)
        if incoming.settings != nil && !fromAdmin {
            OshiLog.messaging.info("🔒 settings-not-admin: group settings from a non-admin ignored (\(decision.reason))")
        }
        merged.groupDescription = GroupSettingsPolicy.mergeDescription(current: current.groupDescription,
                                                                       incoming: merged.groupDescription)
        return merged
    }

    /// - Parameter authenticated: __GROUP_E2E_V2_2026_09_23__ true ONLY on the v2 state channel,
    ///   where `sender` is the ratchet that opened the envelope. Everything else (legacy IPFS,
    ///   legacy 1:1, mesh) is READ ONLY: it may update local state as before, but it never
    ///   triggers a send (spec §0) and never creates admin rights.
    private func handleIncomingGroupUpdate(data: Data, from sender: String? = nil, authenticated: Bool = false) {
        // First try to decode as a full MessageGroup (iOS-to-iOS updates)
        do {
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601  // Match broadcast encoder format
            // `var`: the authorizer below may fold this into our stored copy rather
            // than take it wholesale (__GROUP_UPDATE_AUTHORIZATION_2026_08_16__).
            var updatedGroup = try decoder.decode(MessageGroup.self, from: data)
            OshiLog.messaging.info("📥 Received group update for: \(updatedGroup.name)")

            if let index = groups.firstIndex(where: { $0.id == updatedGroup.id }),
               GroupLocalFlags.shared.isPendingStub(updatedGroup.id),
               let sender, !sender.isEmpty,
               let myKey = currentUserPublicKey, !myKey.isEmpty,
               updatedGroup.members.contains(where: { MessageManager.canonicalIdentity($0.publicKey) == MessageManager.canonicalIdentity(sender) }),
               updatedGroup.members.contains(where: { MessageManager.canonicalIdentity($0.publicKey) == MessageManager.canonicalIdentity(myKey) }) {
                // __GROUP_INFO_SETTINGS_2026_11_20__ (audit) The invite stub no longer
                // makes the URL's unauthenticated `inviter` an admin, so OUR copy
                // has no admin to authorize the first real definition. That first
                // authenticated definition listing both its sender and us is taken
                // like the unknown-group path takes it (§5.3 rule 2) — no more
                // trust than materialising a group we never heard of.
                var g = updatedGroup
                g.isMuted = groups[index].isMuted
                g.setCurrentUser(myKey)
                g.groupDescription = GroupSettingsPolicy.mergeDescription(current: nil, incoming: g.groupDescription)
                groups[index] = g
                GroupLocalFlags.shared.setPendingStub(g.id, false)
                saveGroups()
                showWelcomeIfNeeded(g)
                DispatchQueue.main.async {
                    NotificationCenter.default.post(name: NSNotification.Name("GroupUpdated"), object: nil, userInfo: ["groupId": g.id.uuidString])
                }
                OshiLog.messaging.info("✅ Invite stub replaced by the authenticated definition of '\(g.name)'")
                return
            }
            if let index = groups.firstIndex(where: { $0.id == updatedGroup.id }) {
                let oldGroup = groups[index]

                // __GROUP_UPDATE_AUTHORIZATION_2026_08_16__ The wholesale
                // `groups[index] = updatedGroup` that used to sit here is what made
                // `isAdmin` self-serve. Everything now passes the authorizer first.
                let decision = MessageGroupManager.authorizeGroupUpdate(
                    current: oldGroup, incoming: updatedGroup, sender: sender)
                guard decision.apply else {
                    OshiLog.messaging.info("🛑 Group update ignored (\(decision.reason)) for '\(oldGroup.name)' from \(sender?.prefix(8) ?? "unknown")")
                    return
                }
                if decision.keepAdminSet || decision.keepMembership || decision.keepName {
                    OshiLog.messaging.info("🔒 Group update downgraded (\(decision.reason)) for '\(oldGroup.name)': admin=\(decision.keepAdminSet ? "kept" : "taken") members=\(decision.keepMembership ? "kept" : "taken") name=\(decision.keepName ? "kept" : "taken")")
                }
                updatedGroup = MessageGroupManager.applyGroupUpdate(
                    current: oldGroup, incoming: updatedGroup, decision: decision)
                // __GROUP_E2E_V2_2026_09_23__ §5.3 local = max(local, incoming); an older
                // sender that does not know `description` must not erase it.
                if let a = oldGroup.stateVersion, let b = updatedGroup.stateVersion {
                    updatedGroup.stateVersion = max(a, b)
                } else if updatedGroup.stateVersion == nil {
                    updatedGroup.stateVersion = oldGroup.stateVersion
                }
                if updatedGroup.groupDescription == nil { updatedGroup.groupDescription = oldGroup.groupDescription }
                groups[index] = updatedGroup
                // Preserve local picture if incoming update stripped it (lightweight broadcast)
                if updatedGroup.groupPictureData == nil && oldGroup.groupPictureData != nil {
                    groups[index].groupPictureData = oldGroup.groupPictureData
                    groups[index].groupPictureUpdatedAt = oldGroup.groupPictureUpdatedAt
                    groups[index].groupPictureUpdatedBy = oldGroup.groupPictureUpdatedBy
                }
                // Preserve local wallpaper if incoming update stripped it (lightweight broadcast)
                if updatedGroup.groupWallpaperData == nil && oldGroup.groupWallpaperData != nil {
                    groups[index].groupWallpaperData = oldGroup.groupWallpaperData
                    groups[index].groupWallpaperUpdatedAt = oldGroup.groupWallpaperUpdatedAt
                    groups[index].groupWallpaperUpdatedBy = oldGroup.groupWallpaperUpdatedBy
                }
                // Preserve local mute setting (personal preference, not synced)
                groups[index].isMuted = oldGroup.isMuted
                // Set current user on updated group for permission checks
                if let currentKey = currentUserPublicKey {
                    groups[index].setCurrentUser(currentKey)
                }
                saveGroups()

                // __GROUP_INFO_SETTINGS_2026_11_20__ §2.8 recent events (authenticated
                // actor only), §2.9 dissolved banner row, §4.3 re-PUT on admin change.
                if authenticated { recordAdminLog(old: oldGroup, new: groups[index], actor: sender) }
                if oldGroup.settings?.dissolved != true, groups[index].settings?.dissolved == true {
                    addLocalInfoRow(to: updatedGroup.id, text: "gs_dissolved_banner".localized)
                }
                if Set(oldGroup.members.filter(\.isAdmin).map(\.publicKey)) != Set(groups[index].members.filter(\.isAdmin).map(\.publicKey)) {
                    refreshHandleRegistration(updatedGroup.id)
                }

                // Diff and create system messages for changes
                let oldMemberKeys = Set(oldGroup.members.map { $0.publicKey })
                let newMemberKeys = Set(updatedGroup.members.map { $0.publicKey })

                for newKey in newMemberKeys.subtracting(oldMemberKeys) {
                    addSystemMessage(to: updatedGroup.id, type: .memberJoined, byPublicKey: newKey)
                }
                for removedKey in oldMemberKeys.subtracting(newMemberKeys) {
                    addSystemMessage(to: updatedGroup.id, type: .memberRemoved, byPublicKey: removedKey)
                }
                if oldGroup.name != updatedGroup.name {
                    addSystemMessage(to: updatedGroup.id, type: .groupNameChanged, byPublicKey: updatedGroup.adminPublicKey)
                }
                // Only diff picture if incoming update actually carries picture data
                // (lightweight broadcasts strip picture to save bandwidth)
                if updatedGroup.groupPictureData != nil && oldGroup.groupPictureData != updatedGroup.groupPictureData {
                    addSystemMessage(to: updatedGroup.id, type: .groupPictureChanged, byPublicKey: updatedGroup.groupPictureUpdatedBy ?? updatedGroup.adminPublicKey)
                }
                // Diff wallpaper (only when full update carries it)
                if updatedGroup.groupWallpaperData != nil && oldGroup.groupWallpaperData != updatedGroup.groupWallpaperData {
                    addSystemMessage(to: updatedGroup.id, type: .wallpaperChanged, byPublicKey: updatedGroup.groupWallpaperUpdatedBy ?? updatedGroup.adminPublicKey)
                }
                // Pin/unpin changes
                if oldGroup.pinnedMessageId != updatedGroup.pinnedMessageId {
                    if updatedGroup.pinnedMessageId != nil {
                        addSystemMessage(to: updatedGroup.id, type: .messagePinned, byPublicKey: updatedGroup.pinnedBy ?? updatedGroup.adminPublicKey)
                    } else {
                        addSystemMessage(to: updatedGroup.id, type: .messageUnpinned, byPublicKey: oldGroup.pinnedBy ?? updatedGroup.adminPublicKey)
                    }
                }

                // Re-sync bots when member list OR name changes — the bot registry
                // on the server caches both, so a stale snapshot causes the bot to
                // push notifications with the OLD name (or miss new members).
                // 🔧 FIX (1.0.10 v6): added the name-change condition. Previously only
                // member changes triggered re-sync.
                if oldMemberKeys != newMemberKeys || oldGroup.name != updatedGroup.name {
                    BotManager.shared.syncBotsForGroup(updatedGroup.id)
                }

                // 🔧 SELF-HEALING: If we have this group locally but our current key
                // is missing from the incoming member list, re-add ourselves and notify.
                // This fixes the case where user rejoined with a new key but the admin
                // never received the member_added notification.
                // __GROUP_E2E_V2_2026_09_23__ An authenticated definition that no longer lists
                // us and names us as evicted is a REMOVAL: drop the group locally (spec §5.2).
                // The public-group self-heal below is kept for the non-evicted case only, and
                // only on the authenticated channel (it SENDS).
                if authenticated, let myKey = currentUserPublicKey, !myKey.isEmpty,
                   !updatedGroup.members.contains(where: { MessageManager.canonicalIdentity($0.publicKey) == MessageManager.canonicalIdentity(myKey) }),
                   updatedGroup.type != .publicGroup
                    || (updatedGroup.evictedMemberKeys ?? []).contains(where: { MessageManager.canonicalIdentity($0) == MessageManager.canonicalIdentity(myKey) }) {
                    removeGroupLocallyAfterRemoval(updatedGroup.id, by: sender)
                    return
                }
                if authenticated,
                   let myKey = currentUserPublicKey,
                   !myKey.isEmpty,
                   !updatedGroup.members.contains(where: { $0.publicKey == myKey }),
                   updatedGroup.type == .publicGroup {
                    OshiLog.messaging.info("🔧 Self-healing: our key \(myKey.prefix(8))... missing from group '\(updatedGroup.name)' — re-adding")
                    let selfMember = GroupMember(publicKey: myKey, alias: nil, joinedAt: Date(), isAdmin: false)
                    groups[index].members.append(selfMember)
                    saveGroups()
                    // Re-broadcast so other members learn about us
                    broadcastMemberJoinedNotification(groupId: updatedGroup.id, memberPublicKey: myKey)
                    // Notify server to add our key to bots for this group
                    BotManager.shared.notifyServerMemberJoined(groupId: updatedGroup.id, memberPublicKey: myKey)
                }

                // Notify UI to refresh
                DispatchQueue.main.async {
                    NotificationCenter.default.post(name: NSNotification.Name("GroupUpdated"), object: nil, userInfo: ["groupId": updatedGroup.id.uuidString])
                }

                OshiLog.messaging.info("✅ Updated group: \(updatedGroup.name)")
            } else {
                // ✅ FIX: Only add group if we're actually a member — prevents re-adding after leave
                let myKey = currentUserPublicKey ?? ""
                guard !myKey.isEmpty, updatedGroup.members.contains(where: { $0.publicKey == myKey }) else {
                    OshiLog.messaging.info("⏭️ Ignoring group update — not a member of '\(updatedGroup.name)'")
                    return
                }
                // __UNAUTHENTICATED_GROUP_UPDATE_2026_11_20__ …and never from nobody:
                // an unauthenticated definition would plant a group whose roster the
                // sender chose, attacker included.
                guard let sender, !sender.isEmpty else {
                    OshiLog.messaging.info("⏭️ Ignoring unknown group definition from an unauthenticated transport")
                    return
                }
                // __GROUP_E2E_V2_2026_09_23__ §5.3 rule 2: an unknown group is created only
                // from a sender who is in its own roster, and never after we LEFT it (C.16).
                if
                   !updatedGroup.members.contains(where: { MessageManager.canonicalIdentity($0.publicKey) == MessageManager.canonicalIdentity(sender) }) {
                    OshiLog.messaging.info("⏭️ Ignoring unknown group definition — sender is not in its roster")
                    return
                }
                if Self.hasLeftTombstone(updatedGroup.id) {
                    OshiLog.messaging.info("⏭️ Ignoring definition of a group this account left (not rejoined)")
                    return
                }

                // New group — we were just added as a member
                groups.append(updatedGroup)
                replayOrphanedMessages(for: updatedGroup.id)   // __ORPHAN_GROUP_QUEUE_2026_08_14__
                // Set current user on new group for permission checks
                groups[groups.count - 1].setCurrentUser(myKey)
                groupMessages[updatedGroup.id] = []
                saveGroups()
                addSystemMessage(to: updatedGroup.id, type: .groupCreated, byPublicKey: updatedGroup.adminPublicKey)
                // __GROUP_INFO_SETTINGS_2026_11_20__ §2.5 we just joined: welcome row.
                showWelcomeIfNeeded(updatedGroup)

                // Notify UI to refresh
                DispatchQueue.main.async {
                    NotificationCenter.default.post(name: NSNotification.Name("GroupUpdated"), object: nil, userInfo: ["groupId": updatedGroup.id.uuidString])
                }

                OshiLog.messaging.info("✅ Added NEW group from broadcast: \(updatedGroup.name)")
            }
            return
        } catch {
            // Not a full MessageGroup — try Android's minimal JSON format
        }

        // Try Android's minimal JSON: {"type": "member_added", "groupId": "...", "memberPublicKey": "..."}
        do {
            guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let updateType = json["type"] as? String else {
                OshiLog.messaging.info("❌ Failed to decode group update: not a valid JSON object")
                return
            }

            switch updateType {
            case "member_added":
                guard let groupIdStr = json["groupId"] as? String,
                      let groupId = UUID(uuidString: groupIdStr),
                      let memberPublicKey = json["memberPublicKey"] as? String else {
                    OshiLog.messaging.info("❌ member_added missing groupId or memberPublicKey")
                    return
                }

                guard let index = groups.firstIndex(where: { $0.id == groupId }) else {
                    OshiLog.messaging.info("⚠️ member_added for unknown group: \(groupIdStr.prefix(8))")
                    return
                }

                // Skip if member already exists
                if groups[index].members.contains(where: { $0.publicKey == memberPublicKey }) {
                    OshiLog.messaging.info("⏭️ Member already in group: \(memberPublicKey.prefix(8))")
                    return
                }

                // __GROUP_E2E_V2_2026_09_23__ Applied LOCALLY: `addMember` is the local user's
                // action and re-broadcasts; receiving must never send (spec §0).
                // __UNAUTHENTICATED_GROUP_UPDATE_2026_11_20__ the authorizer refuses a
                // roster change from nobody (keepMembership), so this is no longer skipped.
                do {
                    let d = MessageGroupManager.authorizeGroupUpdate(current: groups[index], incoming: groups[index], sender: sender)
                    guard d.apply, !d.keepMembership else {
                        OshiLog.messaging.info("🛑 member_added refused (\(d.reason))")
                        return
                    }
                }
                if (groups[index].evictedMemberKeys ?? []).contains(where: { MessageManager.canonicalIdentity($0) == MessageManager.canonicalIdentity(memberPublicKey) }) {
                    OshiLog.messaging.info("🛑 member_added refused: target was removed (tombstone)")
                    return
                }
                groups[index].members.append(GroupMember(publicKey: memberPublicKey, alias: nil, joinedAt: Date(), isAdmin: false))
                groups[index].lastActivity = Date()
                saveGroups()
                addSystemMessage(to: groupId, type: .memberJoined, byPublicKey: memberPublicKey)
                NotificationCenter.default.post(name: NSNotification.Name("GroupUpdated"), object: nil, userInfo: ["groupId": groupId.uuidString])
                OshiLog.messaging.info("✅ Added member \(memberPublicKey.prefix(8))... to group \(groups[index].name)")

            case "member_sync_request":
                // Another device is requesting the full member list — respond with broadcastFullGroupUpdate
                guard let groupIdStr = json["groupId"] as? String,
                      let groupId = UUID(uuidString: groupIdStr),
                      let requesterKey = json["requesterPublicKey"] as? String else {
                    OshiLog.messaging.info("❌ member_sync_request missing fields")
                    return
                }
                // __GROUP_E2E_V2_2026_09_23__ §5.3: answered ONLY on the v2 channel, where the
                // requester is authenticated, and only for the requester itself. A legacy
                // (IPFS/mesh) request is read and dropped — replying would be a legacy send.
                guard authenticated, let sender, !sender.isEmpty,
                      MessageManager.canonicalIdentity(sender) == MessageManager.canonicalIdentity(requesterKey) else {
                    OshiLog.messaging.info("⏭️ member_sync_request ignored (not on the authenticated v2 channel)")
                    return
                }
                if let group = groups.first(where: { $0.id == groupId }) {
                    let requesterCanon = MessageManager.canonicalIdentity(requesterKey)
                    if (group.evictedMemberKeys ?? []).contains(where: { MessageManager.canonicalIdentity($0) == requesterCanon }) {
                        OshiLog.messaging.info("🛑 member_sync_request from a removed member — ignored")
                        return
                    }
                    if !group.members.contains(where: { MessageManager.canonicalIdentity($0.publicKey) == requesterCanon }) {
                        // v1 invite completion (spec R1): the requester is added only by a
                        // member allowed to add in this group; `addMember` sends the new
                        // definition to the whole new roster, requester included.
                        var check = group
                        if let me = currentUserPublicKey { check.setCurrentUser(me) }
                        // __GROUP_INFO_SETTINGS_2026_11_20__ §3.3 rules 4–6: dissolved,
                        // and the reset-link token once the group has one.
                        let verdict = GroupJoinAuthorizer.authorizeSyncRequest(
                            sender: sender, requester: requesterKey,
                            token: json["inviteToken"] as? String,
                            group: group, mayAddMembers: check.canAddMembers)
                        guard verdict == .accept else {
                            OshiLog.messaging.info("⏭️ member_sync_request refused (\(String(describing: verdict)))")
                            return
                        }
                        addMember(to: groupId, publicKey: requesterKey, alias: nil)
                        OshiLog.messaging.info("🔧 Added sync requester \(requesterKey.prefix(8))... to group")
                        return
                    }
                    // Broadcast full group with all members back.
                    //
                    // 🔕 __GROUP_HEARTBEAT_PHANTOM_2026_08_26__ `notifyMembers: false`,
                    // et c'est le cas le plus important des trois: on est DANS
                    // `handleIncomingGroupUpdate`. Notifier ici veut dire que
                    // RECEVOIR une mise a jour en pousse une chez tous les autres,
                    // qui la recoivent, qui poussent a leur tour. Avec le battement
                    // de 30 min sur chaque appareil, la boucle s'auto-alimente.
                    // Repondre a une synchronisation est de la convergence, pas un
                    // evenement.
                    // §5.2 answer: our definition, UNCHANGED version, to the requester only.
                    if let updatedGroup = groups.first(where: { $0.id == groupId }) {
                        broadcastFullGroupUpdate(group: updatedGroup, notifyMembers: false,
                                                 bumpVersion: false, onlyTo: [requesterKey])
                        OshiLog.messaging.info("📡 Responded to member sync request for group '\(updatedGroup.name)' — sent \(updatedGroup.members.count) members")
                    }
                }

            case "member_removed":
                guard let groupIdStr = json["groupId"] as? String,
                      let groupId = UUID(uuidString: groupIdStr),
                      let memberPublicKey = json["memberPublicKey"] as? String else {
                    OshiLog.messaging.info("❌ member_removed missing fields")
                    return
                }
                applyRemoteMemberRemoved(groupId: groupId, member: memberPublicKey, sender: sender)

            case "group_renamed":
                guard let groupIdStr = json["groupId"] as? String,
                      let groupId = UUID(uuidString: groupIdStr),
                      let newName = json["name"] as? String else {
                    OshiLog.messaging.info("❌ group_renamed missing fields")
                    return
                }
                if let index = groups.firstIndex(where: { $0.id == groupId }) {
                    // __UNAUTHENTICATED_GROUP_UPDATE_2026_11_20__ checked for nobody too.
                    do {
                        let d = MessageGroupManager.authorizeGroupUpdate(current: groups[index], incoming: groups[index], sender: sender)
                        guard d.apply, !d.keepName else {
                            OshiLog.messaging.info("🛑 group_renamed refused (\(d.reason))")
                            return
                        }
                    }
                    guard groups[index].name != newName else { return }
                    groups[index].name = newName
                    saveGroups()
                    NotificationCenter.default.post(name: NSNotification.Name("GroupUpdated"), object: nil, userInfo: ["groupId": groupId.uuidString])
                    OshiLog.messaging.info("✅ Group renamed to: \(newName)")
                }

            case "sync_request":
                // __SYNC_REQUEST_FIELD_NAME_2026_10_19__ Accept the legacy
                // `requesterPublicKey` spelling, and fall back to the
                // authenticated sender, so a peer still running a build that
                // mis-spells the field is answered instead of ignored. The
                // identity check below is unchanged and still decides.
                let requesterKey = (json["requesterKey"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                    ?? (json["requesterPublicKey"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                    ?? sender
                guard let requesterKey, !requesterKey.isEmpty else {
                    OshiLog.messaging.info("❌ sync_request has no requester and no sender")
                    return
                }
                // __GROUP_E2E_V2_2026_09_23__ v2 channel only, requester == authenticated sender.
                guard authenticated, let sender,
                      MessageManager.canonicalIdentity(sender) == MessageManager.canonicalIdentity(requesterKey) else {
                    OshiLog.messaging.info("⏭️ sync_request ignored (not on the authenticated v2 channel)")
                    return
                }
                handleGroupSyncRequest(requesterKey: requesterKey)

            case "join_request":
                // __GROUP_INFO_SETTINGS_2026_11_20__ §3.1 step 4 / §3.3.
                handleJoinRequest(json, sender: sender, authenticated: authenticated)

            case "deleted":
                // __GROUP_INFO_SETTINGS_2026_11_20__ §2.9 Android-compatible companion.
                if let gs = json["groupId"] as? String, let gid = UUID(uuidString: gs) {
                    handleGroupDeletedCompanion(groupId: gid, sender: sender, authenticated: authenticated)
                }

            case "read_receipt_batch", "created":
                // `read_receipt_batch`: optional, ignored on iOS (spec §5.2). `created`: the
                // Android companion of a create; the definition that precedes it is authoritative.
                break

            default:
                OshiLog.messaging.info("⚠️ Unknown group update type: \(updateType)")
            }
        } catch {
            OshiLog.messaging.info("❌ Failed to decode group update JSON: \(error)")
        }
    }
    
    // MARK: - Migration
    
    private func migrateToFileStorage() {
        OshiLog.messaging.info("🔄 migration_checking".localized)
        FileStorage.shared.migrateFromUserDefaults(key: storageKey, arrayType: [MessageGroup].self)
        FileStorage.shared.migrateFromUserDefaults(key: messagesKey, dictionaryType: [String: [GroupMessage]].self)
        FileStorage.shared.migrateFromUserDefaults(key: senderKeysKey, dictionaryType: [String: [String: SenderKey]].self)
    }
    
    deinit {
        groupSyncTimer?.invalidate()
    }
    
    // MARK: - Group Operations
    
    func createGroup(name: String, type: GroupType, adminPublicKey: String, avatar: String? = nil) -> MessageGroup {
        let group = MessageGroup(
            id: UUID(),
            name: name,
            type: type,
            adminPublicKey: adminPublicKey,
            members: [GroupMember(
                publicKey: adminPublicKey,
                alias: nil,
                joinedAt: Date(),
                isAdmin: true
            )],
            createdAt: Date(),
            lastActivity: Date(),
            avatar: avatar
        )
        
        groups.append(group)
        replayOrphanedMessages(for: group.id)   // __ORPHAN_GROUP_QUEUE_2026_08_14__
        // Set current user on new group for permission checks
        if let currentKey = currentUserPublicKey {
            groups[groups.count - 1].setCurrentUser(currentKey)
        }
        groupMessages[group.id] = []
        saveGroups()
        NotificationCenter.default.post(
            name: .init("OSHIGroupCreated"),
            object: nil,
            userInfo: ["groupId": group.id.uuidString]
        )

        OshiLog.messaging.info("✅ Created group: \(name)")

        // ✅ FIX: Broadcast public groups to mesh network
        if type == .publicGroup {
            broadcastPublicGroup(group)
        }

        return group
    }


    /// Rename group (admin only) — preserves UUID
    func renameGroup(groupId: UUID, newName: String, renamedBy: String) -> Bool {
        guard let index = groups.firstIndex(where: { $0.id == groupId }) else {
            OshiLog.messaging.info("❌ Group not found: \(groupId)")
            return false
        }

        // Permission check (admin for adminOnly, any member for collaborative/public)
        var checkGroup = groups[index]
        checkGroup.setCurrentUser(renamedBy)
        guard checkGroup.canChangeName else {
            OshiLog.messaging.info("❌ No permission to rename group")
            return false
        }

        let oldName = groups[index].name
        groups[index].name = newName
        groups[index].lastActivity = Date()
        saveGroups()
        NotificationCenter.default.post(
            name: .init("OSHIGroupUpdated"),
            object: nil,
            userInfo: ["groupId": groups[index].id.uuidString]
        )

        OshiLog.messaging.info("✏️ Renamed group from '\(oldName)' to '\(newName)'")

        addSystemMessage(to: groupId, type: .groupNameChanged, byPublicKey: renamedBy)
        broadcastFullGroupUpdate(group: groups[index],
                                 companions: GroupWireV2.groupRenamed(groupId: groupId, name: newName)
                                    .map { [($0, groups[index].members.map { $0.publicKey })] } ?? [])

        // 🔧 FIX (1.0.10 v6): Sync the new group name to the bot registry on the
        // VPS. Without this, every bot registered for this group keeps pushing
        // notifications with the OLD group name in the title (the bot's local
        // group snapshot was taken at registration time and never refreshed).
        // syncBotsForGroup re-registers all bots that have access to this group,
        // which propagates the current group state (name + members) to the server.
        Task { @MainActor in
            BotManager.shared.syncBotsForGroup(groupId)
        }

        return true
    }

    // MARK: - Pin/Unpin Messages

    func pinMessage(in groupId: UUID, messageId: UUID, by publicKey: String) -> Bool {
        guard let index = groups.firstIndex(where: { $0.id == groupId }) else { return false }
        var checkGroup = groups[index]
        checkGroup.setCurrentUser(publicKey)
        guard checkGroup.canPinMessages else {
            OshiLog.messaging.info("❌ No permission to pin messages")
            return false
        }

        groups[index].pinnedMessageId = messageId
        groups[index].pinnedBy = publicKey
        groups[index].lastActivity = Date()
        saveGroups()

        addSystemMessage(to: groupId, type: .messagePinned, byPublicKey: publicKey)
        broadcastFullGroupUpdate(group: groups[index])
        return true
    }

    func unpinMessage(in groupId: UUID, by publicKey: String) -> Bool {
        guard let index = groups.firstIndex(where: { $0.id == groupId }) else { return false }
        var checkGroup = groups[index]
        checkGroup.setCurrentUser(publicKey)
        guard checkGroup.canPinMessages else {
            OshiLog.messaging.info("❌ No permission to unpin messages")
            return false
        }

        groups[index].pinnedMessageId = nil
        groups[index].pinnedBy = nil
        groups[index].lastActivity = Date()
        saveGroups()

        addSystemMessage(to: groupId, type: .messageUnpinned, byPublicKey: publicKey)
        broadcastFullGroupUpdate(group: groups[index])
        return true
    }

    // MARK: - A bot changing its own post (__BOTS_PHASE1_2026_11_24__)

    /// Apply a bot's `editMessageText` / `editMessageReplyMarkup` /
    /// `deleteMessage` / `pinChatMessage` to the post it made, in place. Ignored unless the stored
    /// post is that bot's (`BotPostUpdate.isAuthor`) — a member cannot rewrite
    /// a bot's post, and a bot cannot touch anyone else's. No 10-minute window:
    /// a live vote count is edited for as long as the vote runs.
    @MainActor
    @discardableResult
    func applyBotPostUpdate(_ update: BotPostUpdate) -> Bool {
        let localId = BotPostUpdate.localId(forServerId: update.messageId)
        guard var messages = groupMessages[update.groupId],
              let index = messages.firstIndex(where: { $0.id == localId }) else {
            OshiLog.messaging.info("🤖 bot post update for an unknown post — ignored")
            return false
        }
        let original = messages[index]
        let record = BotManager.shared.replyMarkup(forMessageId: localId.uuidString)
        let author = BotPostAuthors.botId(for: localId) ?? record?.botId
        guard BotPostUpdate.isAuthor(senderKey: original.senderPublicKey,
                                     recordedBotId: author, update: update) else {
            OshiLog.messaging.info("🤖 bot post update from another author — ignored")
            return false
        }
        switch update.kind {
        case .delete:
            messages.remove(at: index)
            BotManager.shared.removeReplyMarkup(forMessageId: localId.uuidString)
            if let g = groups.firstIndex(where: { $0.id == update.groupId }), groups[g].pinnedMessageId == localId {
                groups[g].pinnedMessageId = nil
                groups[g].pinnedBy = nil
                saveGroups()
            }
        case .pin, .unpin:
            // Every member receives the action: applied locally, never re-broadcast.
            guard let g = groups.firstIndex(where: { $0.id == update.groupId }) else { return false }
            if update.kind == .pin {
                groups[g].pinnedMessageId = localId
                groups[g].pinnedBy = original.senderPublicKey
            } else if groups[g].pinnedMessageId == localId {
                groups[g].pinnedMessageId = nil
                groups[g].pinnedBy = nil
            }
            saveGroups()
        case .edit(let text, let keyboard):
            if let text {
                // Keep the "[Bot name] " prefix the bubble reads the name from.
                var name = "Bot"
                if let plain = original.plaintextContent, plain.hasPrefix("["), let end = plain.firstIndex(of: "]") {
                    name = String(plain[plain.index(after: plain.startIndex)..<end])
                }
                messages[index].encryptedContent = Data(text.utf8)
                messages[index].plaintextContent = "[\(name)] \(text)"
                messages[index].editedAt = Date()
            }
            if let keyboard {
                if let keyboard, !keyboard.isEmpty {
                    BotManager.shared.storeReplyMarkup(
                        BotReplyMarkupRecord(keyboard: keyboard,
                                             sourceMessageId: record?.sourceMessageId ?? update.messageId,
                                             groupId: update.groupId.uuidString,
                                             botTokenPrefix: record?.botTokenPrefix,
                                             botName: record?.botName,
                                             botId: update.botId),
                        forMessageId: localId.uuidString)
                } else {
                    BotManager.shared.removeReplyMarkup(forMessageId: localId.uuidString)
                }
            }
        }
        groupMessages[update.groupId] = messages
        saveMessages()
        objectWillChange.send()
        return true
    }

    // MARK: - Edit Group Message

    func editGroupMessage(groupId: UUID, messageId: UUID, newContent: String, senderPublicKey: String) {
        guard var messages = groupMessages[groupId] else { return }
        guard let index = messages.firstIndex(where: { $0.id == messageId && $0.senderPublicKey == senderPublicKey }) else { return }

        // Check 10-minute window
        guard Date().timeIntervalSince(messages[index].timestamp) <= 600 else {
            OshiLog.messaging.info("❌ Cannot edit: message is older than 10 minutes")
            return
        }

        messages[index].editedAt = Date()
        messages[index].editedContent = newContent
        messages[index].plaintextContent = newContent
        groupMessages[groupId] = messages
        saveMessages()
        objectWillChange.send()
        OshiLog.messaging.info("✏️ Edited group message locally: \(messageId)")
    }

    func editGroupMessageForEveryone(groupId: UUID, messageId: UUID, newContent: String, senderPublicKey: String) {
        // 1. Edit locally
        editGroupMessage(groupId: groupId, messageId: messageId, newContent: newContent, senderPublicKey: senderPublicKey)

        // 2. Create a system message carrying the edited message ID and new content
        let systemMessage = GroupMessage(
            id: UUID(),
            groupId: groupId,
            senderPublicKey: "SYSTEM",
            encryptedContent: Data(),
            timestamp: Date(),
            isRead: true,
            messageChainIndex: 0,
            systemMessageType: .messageEdited,
            systemMessageData: GroupSystemMessageData(
                // §2.4: the actor is US (== the envelope `from` the receiver checks).
                actorPublicKey: currentUserPublicKey ?? senderPublicKey,
                targetPublicKey: nil,
                deletedMessageId: nil,
                editedMessageId: messageId.uuidString,
                editedContent: newContent
            )
        )

        // 3. __GROUP_E2E_V2_2026_09_23__ v2 content channel (it used to go to the mesh only).
        if let group = groups.first(where: { $0.id == groupId }), let me = currentUserPublicKey {
            Task { await broadcastGroupMessageViaRelay(systemMessage, to: group, senderKey: me) }
        }
        // __CHANNEL_WEB_2026_11_26__ an edited web post is re-published (same n).
        ChannelWebPublisher.shared.didEdit(groupId: groupId, messageId: messageId,
                                           group: groups.first(where: { $0.id == groupId }))
        OshiLog.messaging.info("✏️ Broadcasted edit-for-everyone for message \(messageId)")
    }

    // MARK: - Public Group Broadcasting
    
    /// Broadcasts a public group announcement to the mesh network
    private func broadcastPublicGroup(_ group: MessageGroup) {
        let ad = PublicGroupAd(
            groupId: group.id,
            groupName: group.name,
            adminPublicKey: group.adminPublicKey,
            memberCount: group.members.count,
            avatar: nil,
            timestamp: Date()
        )
        
        do {
            let adData = try JSONEncoder().encode(ad)
            
            // Post notification to MeshNetworkManager
            NotificationCenter.default.post(
                name: NSNotification.Name("BroadcastPublicGroupAd"),
                object: nil,
                userInfo: ["adData": adData]  // ✅ Key must match MeshNetworkManager expectation
            )
            
            OshiLog.messaging.info("📡 Broadcasting public group: \(group.name)")
            OshiLog.messaging.info("   Group ID: \(group.id)")
            OshiLog.messaging.info("   Admin: \(group.adminPublicKey.prefix(16))...")
            OshiLog.messaging.info("   Members: \(group.members.count)")

            // 🔧 CROSS-PLATFORM: Also broadcast via CrossPlatformMesh to Android peers
            if let adJson = String(data: adData, encoding: .utf8) {
                CrossPlatformMesh.shared.broadcastToAllPeers(content: adJson, type: "PUBLIC_GROUP_AD")
            }
        } catch {
            OshiLog.messaging.info("❌ Failed to broadcast public group: \(error)")
        }
    }
    
    /// Re-broadcasts all public groups (for periodic discovery)
    func broadcastAllPublicGroups() {
        let publicGroups = groups.filter { $0.type == .publicGroup }
        
        guard !publicGroups.isEmpty else {
            OshiLog.messaging.info("ℹ️ No public groups to broadcast")
            return
        }
        
        OshiLog.messaging.info("📡 Re-broadcasting \(publicGroups.count) public group(s)...")
        
        for group in publicGroups {
            broadcastPublicGroup(group)
        }
    }
    
    /// Starts periodic broadcasting of public groups (every 30 seconds)
    func startPeriodicPublicGroupBroadcast() {
        // Initial broadcast with slight delay to allow connections
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 2_000_000_000) // 2 second delay
            broadcastAllPublicGroups()
        }
        
        // Setup periodic broadcast (every 30 seconds like old version)
        Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.broadcastAllPublicGroups()
            }
        }
        
        OshiLog.messaging.info("⏰ Started periodic public group broadcast (every 30 seconds)")
    }
    
    func addMember(to groupId: UUID, publicKey: String, alias: String?) {
        guard let index = groups.firstIndex(where: { $0.id == groupId }) else { return }

        // Skip if member already exists
        // __ROSTER_COMPARED_RAW_KEYS_2026_11_07__ The duplicate guard has to use
        // the same notion of identity as everything else, or it admits a second
        // copy of a member already present under the other base64 spelling and
        // the member count moves while nobody touches the group.
        let addingCanon = MessageManager.canonicalIdentity(publicKey)
        if groups[index].members.contains(where: {
            MessageManager.canonicalIdentity($0.publicKey) == addingCanon
        }) {
            OshiLog.messaging.info("⏭️ Member already in group: \(publicKey.prefix(8))")
            return
        }

        let member = GroupMember(
            publicKey: publicKey,
            alias: alias,
            joinedAt: Date(),
            isAdmin: false
        )

        groups[index].members.append(member)
        groups[index].lastActivity = Date()
        saveGroups()

        // System message
        addSystemMessage(to: groupId, type: .memberJoined, byPublicKey: publicKey)

        // Broadcast updated group to ALL members. `notifyMembers: false`: le
        // `notifyMembersOfGroupChange` explicite plus bas porte le VRAI motif
        // du changement — sans ca chaque ajout envoyait DEUX bannieres.
        // __GROUP_E2E_V2_2026_09_23__ §5.2 add: definition to the NEW roster + companion.
        let addRoster = groups[index].members.map { $0.publicKey }
        broadcastFullGroupUpdate(group: groups[index], notifyMembers: false,
                                 companions: GroupWireV2.memberAdded(groupId: groupId, member: publicKey)
                                    .map { [($0, addRoster)] } ?? [])

        // Re-register bots so new member receives bot messages
        Task { @MainActor in
            BotManager.shared.syncBotsForGroup(groupId)
        }

        // Push fallback: notify all members of the change
        notifyMembersOfGroupChange(group: groups[index], changeType: "member_added")

        OshiLog.messaging.info("✅ Added member to group and broadcasted update")
    }
    
    func removeMember(from groupId: UUID, publicKey: String) {
        guard let index = groups.firstIndex(where: { $0.id == groupId }) else { return }

        // __ROSTER_COMPARED_RAW_KEYS_2026_11_07__ Canonical, like the tombstone
        // four lines below — which is the point.
        //
        // This was `$0.publicKey == publicKey`, a RAW comparison, while the
        // eviction record immediately underneath already compared through
        // `canonicalIdentity`. Two lines apart, two different ideas of who this
        // person is. A key spelled base64url in the roster and base64 in the
        // request (or the reverse — iOS emits standard base64, Android
        // canonicalises) matched the TOMBSTONE and missed the REMOVAL: the
        // member was recorded as evicted and left sitting in the roster.
        //
        // Found verifying Anatole's N9 across platforms after fixing the same
        // class of defect on Android. Neither platform was a copy of the other's
        // bug; both grew it independently, which is what happens when the
        // canonical form is a convention rather than the only way to compare.
        let removingCanon = MessageManager.canonicalIdentity(publicKey)
        groups[index].members.removeAll {
            MessageManager.canonicalIdentity($0.publicKey) == removingCanon
        }
        // __GROUP_EVICTION_TOMBSTONE_2026_09_10__ (F3) Poser la pierre tombale
        // AVANT de sauver: `receiveGroupMessage` refusera désormais de ré-ajouter
        // ce membre, et l'union dans `applyGroupUpdate` la propage aux autres.
        let evictedCanon = MessageManager.canonicalIdentity(publicKey)
        var evicted = groups[index].evictedMemberKeys ?? []
        if !evicted.contains(where: { MessageManager.canonicalIdentity($0) == evictedCanon }) {
            evicted.append(publicKey)
            groups[index].evictedMemberKeys = evicted
        }
        groups[index].lastActivity = Date()
        saveGroups()

        // System message
        addSystemMessage(to: groupId, type: .memberRemoved, byPublicKey: publicKey)

        // `notifyMembers: false`: la notification explicite suit, avec son motif.
        // __GROUP_E2E_V2_2026_09_23__ §5.2 remove: the definition goes to the remaining members
        // AND the removed one; the `member_removed` companion is REQUIRED for the removed one.
        let removeTo = groups[index].members.map { $0.publicKey } + [publicKey]
        broadcastFullGroupUpdate(group: groups[index], notifyMembers: false, alsoTo: [publicKey],
                                 companions: GroupWireV2.memberRemoved(groupId: groupId, member: publicKey)
                                    .map { [($0, removeTo)] } ?? [])

        // Re-register bots so removed member stops receiving bot messages
        Task { @MainActor in
            BotManager.shared.syncBotsForGroup(groupId)
        }

        // Push fallback: notify remaining members of the change
        notifyMembersOfGroupChange(group: groups[index], changeType: "member_removed")

        OshiLog.messaging.info("🗑️ Removed member from group and broadcasted update")
    }

    // MARK: - Group Invite (QR / Universal Link Join)

    /// Join a group via QR / deep-link invite payload.
    ///
    /// Behavior:
    /// 1. If we already have the group locally and are a member → no-op (success).
    /// 2. If we have the group but aren't a member → re-add ourselves and broadcast.
    /// 3. If we don't have the group → create a stub MessageGroup, add ourselves
    ///    as the only member (admin = inviter), then send a `member_sync_request`
    ///    to the inviter so they reply with the full member list (existing flow).
    ///
    /// - Parameters:
    ///   - gid: Group UUID encoded in the invite link
    ///   - inviterKey: Public key of the inviter (becomes admin in the stub group)
    ///   - name: Optional group name from the invite (used for display until full sync)
    /// - Returns: true on success, false if we couldn't determine our public key
    /// Pure: the stub a link join creates. No admin — see joinGroupFromInvite.
    nonisolated static func inviteStub(gid: UUID, inviterKey: String?, name: String?, myKey: String) -> MessageGroup {
        let inviter = (inviterKey?.isEmpty == false) ? inviterKey! : nil
        var stubMembers = [GroupMember(publicKey: myKey, alias: nil, joinedAt: Date(), isAdmin: false)]
        if let inviter, MessageManager.canonicalIdentity(inviter) != MessageManager.canonicalIdentity(myKey) {
            stubMembers.insert(GroupMember(publicKey: inviter, alias: nil, joinedAt: Date(), isAdmin: false), at: 0)
        }
        return MessageGroup(
            id: gid,
            name: name ?? "group_invite_default_name".localized,
            type: .adminOnly,                     // Nobody may edit a pending stub
            adminPublicKey: inviter ?? "",
            members: stubMembers,
            createdAt: Date(),
            lastActivity: Date()
        )
    }

    @discardableResult
    func joinGroupFromInvite(gid: UUID, inviterKey: String?, name: String?, inviteToken: String? = nil) -> Bool {
        guard let myKey = currentUserPublicKey, !myKey.isEmpty else {
            OshiLog.messaging.info("❌ joinGroupFromInvite: no current user public key")
            return false
        }

        // Case 1 & 2: group already exists locally
        if let index = groups.firstIndex(where: { $0.id == gid }) {
            if groups[index].members.contains(where: { $0.publicKey == myKey }) {
                OshiLog.messaging.info("✅ joinGroupFromInvite: already a member of '\(groups[index].name)'")
                return true
            }
            // Re-add ourselves
            DevSyncIOSStore.recordLocalJoin(gid)   // __DEVSYNC_REJOIN_2026_09_23__ GROUPS.joinedAt (C.16)
            let selfMember = GroupMember(publicKey: myKey, alias: nil, joinedAt: Date(), isAdmin: false)
            groups[index].members.append(selfMember)
            groups[index].lastActivity = Date()
            saveGroups()
            addSystemMessage(to: gid, type: .memberJoined, byPublicKey: myKey)
            broadcastFullGroupUpdate(group: groups[index])
            broadcastMemberJoinedNotification(groupId: gid, memberPublicKey: myKey)
            // __GROUP_E2E_V2_2026_09_23__ the others no longer list us, so they ignore what we
            // send; the admin re-adds us from an authenticated member_sync_request (§5.3).
            requestMemberSync(groupId: gid, inviteToken: inviteToken)
            OshiLog.messaging.info("✅ joinGroupFromInvite: rejoined '\(groups[index].name)'")
            return true
        }

        // Case 3: create stub group from invite metadata
        DevSyncIOSStore.recordLocalJoin(gid)   // __DEVSYNC_REJOIN_2026_09_23__ a (re)join lifts a synced leave (C.16)
        // __GROUP_INFO_SETTINGS_2026_11_20__ (audit) The URL's `inviter` is NOT
        // authenticated — anyone can write any key into a link. It used to become
        // the stub's ADMIN, so a forged link showed (and authorised) the forger as
        // admin until a real definition arrived — and a real admin not in the stub
        // was then refused as "sender-not-a-member". The inviter is now a plain
        // member and the stub is flagged pending: the first AUTHENTICATED
        // definition listing its sender and us replaces it wholesale
        // (handleIncomingGroupUpdate), exactly like an unknown group.
        let stub = Self.inviteStub(gid: gid, inviterKey: inviterKey, name: name, myKey: myKey)
        GroupLocalFlags.shared.setPendingStub(gid, true)
        groups.append(stub)
        replayOrphanedMessages(for: stub.id)   // __ORPHAN_GROUP_QUEUE_2026_08_14__
        groupMessages[gid] = []
        saveGroups()
        addSystemMessage(to: gid, type: .memberJoined, byPublicKey: myKey)

        // Tell inviter we joined (mesh + IPFS) — they'll reply with the full member list
        broadcastMemberJoinedNotification(groupId: gid, memberPublicKey: myKey)

        // Also push a sync request so we get the canonical group state
        // __GROUP_E2E_V2_2026_09_23__ v2 only: the inviter authenticates us by our ratchet and
        // answers with the definition (spec §5.3, R1). No mesh, no IPFS.
        if let inviter = inviterKey, !inviter.isEmpty,
           let framed = GroupWireV2.memberSyncRequest(groupId: gid, requester: myKey, inviteToken: inviteToken) {
            Task {
                let reached = await MessageManager.shared?.v2SendGroupState(framed, to: [inviter]) ?? []
                // __LEGACY_COMPAT_2026_09_23__ an inviter on an old build reads the legacy request.
                // __LEAK_FIX_2026_09_25__ (audit B) only when v2 could not do it (see stillNeedLegacy).
                let rest = GroupLegacyCompat.stillNeedLegacy([inviter], v2Reached: reached)
                if !rest.isEmpty { self.legacyCompatSendSyncRequest(groupId: gid, requester: myKey, to: rest) }
            }
        }

        DispatchQueue.main.async {
            NotificationCenter.default.post(
                name: NSNotification.Name("GroupUpdated"),
                object: nil,
                userInfo: ["groupId": gid.uuidString, "joined": true]
            )
        }

        OshiLog.messaging.info("✅ joinGroupFromInvite: created stub for '\(stub.name)' and sent join notifications")
        return true
    }

    // MARK: - Group typing broadcast (ephemeral, not persisted)

    /// Broadcast a TypingStatus-prefixed envelope to all peers in the group via the existing mesh/IPFS transport.
    /// Receivers short-circuit at the top of `receiveGroupMessage` so nothing is ever stored.
    func sendGroupTypingStatus(_ groupId: UUID, isTyping: Bool, senderName: String?) {
        guard let myKey = currentUserPublicKey,
              groups.contains(where: { $0.id == groupId }) else { return }
        let status = TypingStatus(
            senderPublicKey: myKey,
            isTyping: isTyping,
            timestamp: Date(),
            groupId: groupId.uuidString,
            senderName: senderName
        )
        let payload = status.toMessageString()
        guard let data = payload.data(using: .utf8) else { return }

        let envelope = GroupMessage(
            id: UUID(),
            groupId: groupId,
            senderPublicKey: myKey,
            encryptedContent: data,
            timestamp: Date(),
            isRead: true,
            messageChainIndex: 0,
            plaintextContent: payload
        )

        // __GROUP_E2E_V2_2026_09_23__ §2.3/§2.5 typing rides the v2 content channel: members
        // only, ratchet-encrypted, no push, no self copy, skipped above 20 members. It used to
        // go to EVERY connected mesh peer, member or not, with the sender's name in clear.
        guard let group = groups.first(where: { $0.id == groupId }),
              group.members.count <= GroupWireV2.typingMaxMembers,
              let text = GroupWireV2.encodeContent(envelope) else { return }
        let me = MessageManager.canonicalIdentity(myKey)
        let to = group.members.map { $0.publicKey }.filter { MessageManager.canonicalIdentity($0) != me }
        guard !to.isEmpty else { return }
        Task {
            _ = await MessageManager.shared?.v2TrySendGroup(groupId: groupId.uuidString, memberKeys: to,
                                                            senderKey: myKey, text: text,
                                                            messageId: envelope.id.uuidString, push: false)
        }
    }

    // MARK: - Admin role changes (Android-parity: GroupManager.kt promoteToAdmin/demoteFromAdmin)

    /// Promote an existing member to admin. No-op if not a member or already admin.
    /// Caller must already be an admin (UI enforces this).
    func promoteToAdmin(in groupId: UUID, publicKey: String) {
        guard let index = groups.firstIndex(where: { $0.id == groupId }) else { return }
        guard let memberIndex = groups[index].members.firstIndex(where: { $0.publicKey == publicKey }) else {
            OshiLog.messaging.info("⏭️ promoteToAdmin: member not in group")
            return
        }
        if groups[index].members[memberIndex].isAdmin { return }
        groups[index].members[memberIndex].isAdmin = true
        groups[index].lastActivity = Date()
        saveGroups()

        addSystemMessage(to: groupId, type: .adminChanged, byPublicKey: currentUserPublicKey ?? "", targetPublicKey: publicKey)
        broadcastFullGroupUpdate(group: groups[index], notifyMembers: false)
        notifyMembersOfGroupChange(group: groups[index], changeType: "admin_promoted")
        refreshHandleRegistration(groupId)   // __GROUP_INFO_SETTINGS_2026_11_20__ §4.3
        OshiLog.messaging.info("👑 Promoted \(publicKey.prefix(8))... to admin")
    }

    /// Demote an admin back to a regular member. No-op for the group creator (adminPublicKey).
    func demoteFromAdmin(in groupId: UUID, publicKey: String) {
        guard let index = groups.firstIndex(where: { $0.id == groupId }) else { return }
        // Never demote the group creator — mirrors Android safeguard.
        if groups[index].adminPublicKey == publicKey {
            OshiLog.messaging.info("⏭️ demoteFromAdmin: cannot demote group creator")
            return
        }
        guard let memberIndex = groups[index].members.firstIndex(where: { $0.publicKey == publicKey }) else { return }
        if !groups[index].members[memberIndex].isAdmin { return }
        groups[index].members[memberIndex].isAdmin = false
        groups[index].lastActivity = Date()
        saveGroups()

        addSystemMessage(to: groupId, type: .adminChanged, byPublicKey: currentUserPublicKey ?? "", targetPublicKey: publicKey)
        broadcastFullGroupUpdate(group: groups[index], notifyMembers: false)
        notifyMembersOfGroupChange(group: groups[index], changeType: "admin_demoted")
        refreshHandleRegistration(groupId)   // __GROUP_INFO_SETTINGS_2026_11_20__ §4.3
        OshiLog.messaging.info("👑 Demoted \(publicKey.prefix(8))... from admin")
    }

    // MARK: - Identity Migration

    /// Replace old public key with new key in all groups when user changes identity.
    /// Broadcasts updated member lists to all group members and re-syncs bots.
    func migrateGroupsToNewKey(oldKey: String, newKey: String) {
        // When user changes identity, leave all groups cleanly.
        // The user can rejoin public groups with their new identity.
        var leftCount = 0

        // Collect group IDs first to avoid mutating while iterating
        let groupIds = groups.map { $0.id }

        for groupId in groupIds {
            guard let index = groups.firstIndex(where: { $0.id == groupId }) else { continue }
            if groups[index].members.contains(where: { $0.publicKey == oldKey }) {
                // Remove old key from member list
                groups[index].members.removeAll { $0.publicKey == oldKey }
                groups[index].lastActivity = Date()

                // Add system message that member left
                addSystemMessage(to: groupId, type: .memberLeft, byPublicKey: oldKey)

                // Broadcast updated group to remaining members.
                // `notifyMembers: false`: une rotation de cle est un evenement de
                // PROTOCOLE. Personne n'a rien fait qui merite une banniere.
                broadcastFullGroupUpdate(group: groups[index], notifyMembers: false)

                // Re-sync bots
                Task { @MainActor in
                    BotManager.shared.syncBotsForGroup(groupId)
                }

                leftCount += 1
                OshiLog.messaging.info("👋 Left group '\(groups[index].name)' due to identity change")
            }
        }

        // Remove all groups and messages locally (clean slate for new identity)
        groups.removeAll()
        groupMessages.removeAll()
        saveGroups()
        saveMessages()
        objectWillChange.send()
        OshiLog.messaging.info("✅ Left \(leftCount) group(s) due to identity change. Local groups cleared.")

    }

    // MARK: - Group Change Push Notifications

    /// Send push notification to group members about a membership change.
    /// This is a fallback for IPFS/mesh broadcasts — ensures all devices get notified.
    private func notifyMembersOfGroupChange(group: MessageGroup, changeType: String) {
        // __GROUP_E2E_V2_2026_09_23__ Retired: the v2 state send already wakes each member with
        // a silent push. This one went to `:8084` (loopback-only on the VPS, never delivered)
        // and carried the group id + change type in clear for the push relay.
        _ = (group, changeType)
        return
    }

    /// Fire-and-forget push to notify members of group changes
    private func sendGroupChangePush(payload: [String: Any]) {
        guard let url = URL(string: "https://oshi-messenger.com:8084/send-push") else { return }
        guard let jsonData = try? JSONSerialization.data(withJSONObject: payload) else { return }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = jsonData
        // __CALL_PUSH_AUTH_IOS_2026_09_23__ signed like every push_service request (actor =
        // data.senderPublicKey). NOTE: `:8084` is bound to 127.0.0.1 on the VPS, so from a
        // device this URL times out and the push has never been delivered; the URL is left
        // as is on purpose (switching it to /push/send-push would START a banner per group
        // change — a product decision, see __GROUP_HEARTBEAT_PHANTOM_2026_08_26__).
        CallPushSigner.sign(&request)
        URLSession.shared.dataTask(with: request).resume()
    }

    // MARK: - Periodic Group Sync

    private var groupSyncTimer: Timer?

    /// Start periodic re-broadcast of all groups to keep member lists consistent
    nonisolated static var isRunningUnitTests: Bool {
        ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
    }

    func startPeriodicGroupSync() {
        groupSyncTimer?.invalidate()
        groupSyncTimer = Timer.scheduledTimer(withTimeInterval: 300, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.syncAllGroups()
            }
        }
    }

    /// __SYNC_ONLY_ON_CHANGE_2026_08_14__ Signature of what a broadcast actually
    /// carries. Re-sending an identical group definition tells every member
    /// something they already know, and the server queues one copy PER MEMBER
    /// whether they ever read it or not.
    ///
    /// Measured on the live queue before this change: 681 messages in one hour,
    /// in bursts every ~3 minutes (two devices on a 300 s timer, offset), 792
    /// queued for a single user in six hours, none of them fetched — and demo
    /// identities that never poll refilled to ~50 messages each within thirty
    /// minutes of being purged. Real messages were arriving BEHIND that backlog,
    /// which is why a group reply "did not appear" while its push notification did.
    private var lastBroadcastSignature: [UUID: String] = [:]
    private var syncTickCount = 0

    private func syncSignature(_ g: MessageGroup) -> String {
        // Only the fields a full update actually propagates. Sorted so member
        // reordering alone never counts as a change.
        g.name + "|" + g.members.map { $0.publicKey }.sorted().joined(separator: ",")
    }

    /// Re-broadcast groups whose definition CHANGED since the last tick.
    func syncAllGroups() {
        guard !groups.isEmpty else { return }
        syncTickCount += 1
        // Every 6th tick (~30 min) re-sends everything regardless, so a member
        // who was offline for the change still converges. Without this the
        // change-only rule would strand anyone who missed the one broadcast.
        let heartbeat = (syncTickCount % 6 == 0)
        var sent = 0
        // __GROUP_E2E_V2_2026_09_23__ No wire traffic from the timer any more. Every local
        // change is sent at its mutation point over v2, and the v2 relay KEEPS a message until
        // the member fetches it — the 30-min re-broadcast existed because the IPFS path lost
        // them. Re-sending every group on every launch/heartbeat would also bump every
        // group's version on every device. A member who needs state asks (`sync_request`).
        for group in groups {
            lastBroadcastSignature[group.id] = syncSignature(group)
        }
        _ = heartbeat
        OshiLog.messaging.info("🔄 Periodic group sync: \(sent)/\(groups.count) group(s) broadcast\(heartbeat ? " (heartbeat)" : "")")
    }

    // MARK: - Group Sync (cross-device / cross-platform)

    /// Respond to an Android device's sync_request by sending back all groups where it's a member.
    /// Uses GROUP_UPDATE_PREFIX + "created" JSON — the exact format Android's handleGroupCreated() expects.
    /// __GROUP_E2E_V2_2026_09_23__ Answer an authenticated v2 `sync_request`: our definition of
    /// every group the requester is ALREADY a member of, unchanged version, to the requester only
    /// (spec §5.2). The old answer went over the cleartext mesh AND auto-added any known contact
    /// to EVERY group we hold — both removed.
    private func handleGroupSyncRequest(requesterKey: String) {
        let req = MessageManager.canonicalIdentity(requesterKey)
        let mine = groups.filter { g in g.members.contains { MessageManager.canonicalIdentity($0.publicKey) == req } }
        guard !mine.isEmpty else {
            OshiLog.messaging.info("⚠️ [GroupSync] \(requesterKey.prefix(8))... is in none of our groups — no answer")
            return
        }
        for g in mine {
            broadcastFullGroupUpdate(group: g, includePicture: g.groupPictureData != nil,
                                     notifyMembers: false, bumpVersion: false, onlyTo: [requesterKey])
        }
        OshiLog.messaging.info("🔄 [GroupSync] answered \(mine.count) group(s) to \(requesterKey.prefix(8))...")
    }

    // MARK: - Member Sync Request

    /// Request full member list from ALL known members (not just admin).
    /// Any member who has the full list responds with broadcastFullGroupUpdate.
    func requestMemberSync(groupId: UUID, inviteToken: String? = nil) {
        // __GROUP_E2E_V2_2026_09_23__ §5.2 "ask for state": ONE member (an admin preferred),
        // over v2. It used to go in clear to the mesh, to IPFS and to /api/queue for everyone.
        guard let group = groups.first(where: { $0.id == groupId }) else { return }
        guard let myKey = currentUserPublicKey else { return }
        let me = MessageManager.canonicalIdentity(myKey)
        let others = group.members.filter { MessageManager.canonicalIdentity($0.publicKey) != me }
        guard let target = (others.first(where: { $0.isAdmin }) ?? others.first)?.publicKey,
              let framed = GroupWireV2.memberSyncRequest(groupId: groupId, requester: myKey, inviteToken: inviteToken) else { return }
        OshiLog.messaging.info("📡 [V2] member_sync_request for '\(group.name)' → \(target.prefix(8))…")
        let everyone = others.map { $0.publicKey }
        Task {
            let reached = await MessageManager.shared?.v2SendGroupState(framed, to: [target]) ?? []
            // __LEGACY_COMPAT_2026_09_23__ old builds only read the legacy request (to every member).
            // __LEAK_FIX_2026_09_25__ (audit B) …unless the v2 request reached a target that
            // answers it (build with v2 group state): one answer is all a sync needs, and the
            // plaintext request names the group AND the requester.
            if !reached.isEmpty, GroupLegacyCompat.stillNeedLegacy(Array(reached), v2Reached: reached).isEmpty { return }
            self.legacyCompatSendSyncRequest(groupId: groupId, requester: myKey, to: everyone)
        }
    }

    /// __LEGACY_COMPAT_2026_09_23__ The pre-v2 `member_sync_request` JSON, to legacy members only.
    private func legacyCompatSendSyncRequest(groupId: UUID, requester: String, to members: [String]) {
        let legacy = GroupLegacyCompat.legacyTargets(members)
        guard !legacy.isEmpty,
              let data = try? JSONSerialization.data(withJSONObject: [
                "type": "member_sync_request", "groupId": groupId.uuidString, "requesterPublicKey": requester])
        else { return }
        legacyCompatSendGroupState(data, groupId: groupId, legacyMembers: legacy)
    }

    // MARK: - Lightweight Member Notifications

    /// Send a minimal "member_added" notification instead of a full group broadcast.
    /// Used by joinPublicGroup so the joiner doesn't overwrite admin's full member list.
    /// The admin processes this via the Android path, calls addMember on their side,
    /// and broadcasts the correct full member list back to everyone.
    func broadcastMemberJoinedNotification(groupId: UUID, memberPublicKey: String) {
        // __GROUP_E2E_V2_2026_09_23__ `member_added` companion over v2 only. The mesh broadcast,
        // the IPFS pin and the `queueMemberJoinedViaVPS` pin (a bare plaintext JSON) are gone.
        guard let group = groups.first(where: { $0.id == groupId }),
              let myKey = currentUserPublicKey,
              let framed = GroupWireV2.memberAdded(groupId: groupId, member: memberPublicKey) else { return }
        let me = MessageManager.canonicalIdentity(myKey)
        let to = group.members.map { $0.publicKey }.filter { MessageManager.canonicalIdentity($0) != me }
        guard !to.isEmpty else { return }
        // __LEGACY_COMPAT_2026_09_23__ the pre-v2 `member_added` JSON for old builds.
        let legacy = GroupLegacyCompat.legacyTargets(to)
        let data = legacy.isEmpty ? nil : try? JSONSerialization.data(withJSONObject: [
            "type": "member_added", "groupId": groupId.uuidString, "memberPublicKey": memberPublicKey])
        Task {
            let reached = await MessageManager.shared?.v2SendGroupState(framed, to: to) ?? []
            // __LEAK_FIX_2026_09_25__ (audit B) not to a member v2 reached on a build that reads it.
            let rest = GroupLegacyCompat.stillNeedLegacy(legacy, v2Reached: reached)
            if let data, !rest.isEmpty { self.legacyCompatSendGroupState(data, groupId: groupId, legacyMembers: rest) }
        }
    }

    // MARK: - Group Picture Management
    
    /// Update group picture (admin only)
    func updateGroupPicture(groupId: UUID, imageData: Data, updatedBy: String) -> Bool {
        guard let index = groups.firstIndex(where: { $0.id == groupId }) else {
            OshiLog.messaging.info("❌ Group not found: \(groupId)")
            return false
        }
        
        // Permission check
        var checkGroup = groups[index]
        checkGroup.setCurrentUser(updatedBy)
        guard checkGroup.canChangeGroupPicture else {
            OshiLog.messaging.info("❌ No permission to update group picture")
            return false
        }

        // Compress image if needed (max 500KB)
        let compressedData = compressImageData(imageData, maxSizeKB: 500)
        
        groups[index].groupPictureData = compressedData
        groups[index].groupPictureUpdatedAt = Date()
        groups[index].groupPictureUpdatedBy = updatedBy
        groups[index].lastActivity = Date()
        saveGroups()
        NotificationCenter.default.post(
            name: NSNotification.Name("OSHIGroupUpdated"),
            object: nil,
            userInfo: ["groupId": groupId.uuidString, "kind": "picture"]
        )

        OshiLog.messaging.info("🖼️ Updated group picture for: \(groups[index].name)")
        
        // Add system message about picture change
        addSystemMessage(
            to: groupId,
            type: .groupPictureChanged,
            byPublicKey: updatedBy
        )
        
        // Broadcast picture update to all members
        broadcastGroupPictureUpdate(group: groups[index])
        
        return true
    }
    
    /// Remove group picture (admin only)
    func removeGroupPicture(groupId: UUID, removedBy: String) -> Bool {
        guard let index = groups.firstIndex(where: { $0.id == groupId }) else {
            return false
        }
        
        // Permission check
        var checkGroup = groups[index]
        checkGroup.setCurrentUser(removedBy)
        guard checkGroup.canChangeGroupPicture else {
            OshiLog.messaging.info("❌ No permission to remove group picture")
            return false
        }

        groups[index].groupPictureData = nil
        groups[index].groupPictureUpdatedAt = Date()
        groups[index].groupPictureUpdatedBy = removedBy
        groups[index].lastActivity = Date()
        saveGroups()
        
        OshiLog.messaging.info("🖼️ Removed group picture for: \(groups[index].name)")
        
        // Add system message about picture removal
        addSystemMessage(
            to: groupId,
            type: .groupPictureRemoved,
            byPublicKey: removedBy
        )
        
        // Broadcast picture removal to all members
        broadcastGroupPictureUpdate(group: groups[index])
        
        return true
    }
    
    /// Update group wallpaper (admin OR member, mirrors picture permissions).
    /// Compresses image to ≤ 500 KB, broadcasts to all members via mesh + IPFS.
    @discardableResult
    func updateGroupWallpaper(groupId: UUID, imageData: Data?, updatedBy: String) -> Bool {
        guard let index = groups.firstIndex(where: { $0.id == groupId }) else {
            OshiLog.messaging.info("❌ updateGroupWallpaper: group not found: \(groupId)")
            return false
        }

        // Permission check (mirrors picture rules: collaborative/public = any member; admin_only = admin)
        var checkGroup = groups[index]
        checkGroup.setCurrentUser(updatedBy)
        guard checkGroup.canChangeWallpaper else {
            OshiLog.messaging.info("❌ updateGroupWallpaper: no permission")
            return false
        }

        if let raw = imageData {
            groups[index].groupWallpaperData = compressImageData(raw, maxSizeKB: 500)
        } else {
            groups[index].groupWallpaperData = nil
        }
        groups[index].groupWallpaperUpdatedAt = Date()
        groups[index].groupWallpaperUpdatedBy = updatedBy
        groups[index].lastActivity = Date()
        saveGroups()
        NotificationCenter.default.post(
            name: NSNotification.Name("OSHIGroupUpdated"),
            object: nil,
            userInfo: ["groupId": groupId.uuidString, "kind": "wallpaper"]
        )

        addSystemMessage(to: groupId, type: .wallpaperChanged, byPublicKey: updatedBy)

        // Broadcast as a full group update with wallpaper included
        broadcastFullGroupUpdate(group: groups[index], includeWallpaper: true)
        OshiLog.messaging.info("🎨 Group wallpaper updated and broadcast for: \(groups[index].name)")
        return true
    }

    /// Compress image data to target size
    private func compressImageData(_ data: Data, maxSizeKB: Int) -> Data {
        guard let image = UIImage(data: data) else { return data }
        
        let maxBytes = maxSizeKB * 1024
        var compression: CGFloat = 1.0
        var compressedData = data
        
        while compressedData.count > maxBytes && compression > 0.1 {
            compression -= 0.1
            if let compressed = image.jpegData(compressionQuality: compression) {
                compressedData = compressed
            }
        }
        
        OshiLog.messaging.info("🖼️ Compressed image: \(data.count / 1024)KB → \(compressedData.count / 1024)KB")
        return compressedData
    }
    
    /// Broadcast group picture update to all members
    /// Uses broadcastFullGroupUpdate since groupPictureData is embedded in MessageGroup
    private func broadcastGroupPictureUpdate(group: MessageGroup) {
        broadcastFullGroupUpdate(group: group, includePicture: true)
        OshiLog.messaging.info("📡 Broadcasting group picture update for: \(group.name)")
    }
    
    // MARK: - Full Group Broadcast

    /// __GROUP_E2E_V2_2026_09_23__ Send the group definition (spec §5.1) to the members as a
    /// v2 pairwise `📢GROUP_UPDATE📢` message — and ONLY that way. The mesh broadcast, the
    /// `SendGroupUpdateViaCloud` IPFS pin (base64 plaintext, audit L2) and the
    /// `queueMemberJoinedViaVPS` pin that used to run here are gone.
    /// - Parameters:
    ///   - includePicture / includeWallpaper: embed that ONE blob (re-encoded ≤ 512 px / 96 KB).
    ///   - notifyMembers: kept for call-site compatibility; the v2 send always wakes silently.
    ///   - alsoTo: extra recipients (a removed member must learn its removal, §5.2).
    ///   - bumpVersion: false only for a sync ANSWER (§5.2 "unchanged version").
    ///   - onlyTo: send to exactly these recipients (sync answer).
    ///   - companions: minimal Android forms sent AFTER the definition, each to its recipients.
    func broadcastFullGroupUpdate(group: MessageGroup,
                                  includePicture: Bool = false,
                                  includeWallpaper: Bool = false,
                                  notifyMembers: Bool = true,
                                  alsoTo: [String] = [],
                                  bumpVersion: Bool = true,
                                  onlyTo: [String]? = nil,
                                  companions: [(framed: String, to: [String])] = []) {
        guard let myKey = currentUserPublicKey else {
            OshiLog.messaging.info("❌ No current user key for group broadcast")
            return
        }
        var wire = group
        if includePicture {
            wire.groupPictureData = Self.wireImage(group.groupPictureData)
            if wire.groupPictureData == nil { wire.groupPictureUpdatedAt = nil; wire.groupPictureUpdatedBy = nil }
        } else {
            wire.groupPictureData = nil; wire.groupPictureUpdatedAt = nil; wire.groupPictureUpdatedBy = nil
        }
        if includeWallpaper && !includePicture {
            wire.groupWallpaperData = Self.wireImage(group.groupWallpaperData)
            if wire.groupWallpaperData == nil { wire.groupWallpaperUpdatedAt = nil; wire.groupWallpaperUpdatedBy = nil }
        } else {
            wire.groupWallpaperData = nil; wire.groupWallpaperUpdatedAt = nil; wire.groupWallpaperUpdatedBy = nil
        }

        // __GROUP_STATE_VERSION_2026_08_16__ Bump here, and only here: this is the single
        // point where a definition leaves the device. Stored back on our own copy first.
        if bumpVersion {
            let nextVersion = (group.stateVersion ?? 0) + 1
            wire.stateVersion = nextVersion
            if let idx = groups.firstIndex(where: { $0.id == group.id }) {
                groups[idx].stateVersion = nextVersion
                saveGroups()
            }
        } else if wire.stateVersion == nil {
            wire.stateVersion = 0
        }

        guard let framed = GroupWireV2.framedDefinition(wire) else {
            OshiLog.messaging.info("❌ Failed to encode group for broadcast")
            return
        }
        let me = MessageManager.canonicalIdentity(myKey)
        var seen = Set<String>()
        let recipients = (onlyTo ?? (group.members.map { $0.publicKey } + alsoTo)).filter {
            let c = MessageManager.canonicalIdentity($0)
            return c != me && seen.insert(c).inserted
        }
        guard !recipients.isEmpty else {
            OshiLog.messaging.info("⏭️ No recipients for group update broadcast")
            return
        }
        OshiLog.messaging.info("📡 [V2] group definition v\(wire.stateVersion ?? 0) (\(framed.utf8.count) B) → \(recipients.count) member(s)")
        _ = notifyMembers
        // __LEGACY_COMPAT_2026_09_23__ also the pre-v2 `group_update` path (the bare iOS
        // MessageGroup JSON, .iso8601 dates, as before) for members not proven v2-capable.
        let legacyState = GroupLegacyCompat.legacyTargets(recipients)
        let legacyData: Data? = legacyState.isEmpty ? nil : {
            let enc = JSONEncoder()
            enc.dateEncodingStrategy = .iso8601
            return try? enc.encode(wire)
        }()
        Task {
            let reached = await MessageManager.shared?.v2SendGroupState(framed, to: recipients) ?? []
            // __LEAK_FIX_2026_09_25__ (audit B) the plaintext `group_update` pin only for the
            // members v2 did not reach or whose build may not route v2 state (pre-2026-09-04);
            // it used to go to every member not proven v2-group-capable — iOS 1.0.43 included,
            // which reads the v2 definition (MessageManager plaintext `📢GROUP_UPDATE📢` branch).
            if let data = legacyData {
                let rest = GroupLegacyCompat.stillNeedLegacy(legacyState, v2Reached: reached)
                if !rest.isEmpty { self.legacyCompatSendGroupState(data, groupId: group.id, legacyMembers: rest) }
            }
            for c in companions {
                let to = c.to.filter { MessageManager.canonicalIdentity($0) != me }
                if !to.isEmpty { await MessageManager.shared?.v2SendGroupState(c.framed, to: to, wake: false) }
            }
        }
    }

    /// Handle incoming group picture update
    func handleIncomingGroupPictureUpdate(data: Data) {
        do {
            let update = try JSONDecoder().decode(GroupPictureUpdate.self, from: data)
            
            guard let index = groups.firstIndex(where: { $0.id == update.groupId }) else {
                OshiLog.messaging.info("⚠️ Received picture update for unknown group: \(update.groupId)")
                return
            }
            
            // Update local group
            groups[index].groupPictureData = update.pictureData
            groups[index].groupPictureUpdatedAt = update.updatedAt
            groups[index].groupPictureUpdatedBy = update.updatedBy
            saveGroups()
            
            OshiLog.messaging.info("📥 Received group picture update for: \(groups[index].name)")
            
            // Post notification for UI update
            DispatchQueue.main.async {
                NotificationCenter.default.post(
                    name: NSNotification.Name("GroupPictureUpdated"),
                    object: nil,
                    userInfo: ["groupId": update.groupId.uuidString]
                )
            }
        } catch {
            OshiLog.messaging.info("❌ Failed to decode group picture update: \(error)")
        }
    }
    
    /// Add system message (for picture changes, member joins, etc.)
    func addSystemMessage(to groupId: UUID, type: GroupSystemMessageType, byPublicKey: String, targetPublicKey: String? = nil) {
        // Check for duplicate system message (same type + same actor within 5 seconds)
        if let existing = groupMessages[groupId],
           existing.contains(where: {
               $0.systemMessageType == type &&
               $0.senderPublicKey == "SYSTEM" &&
               $0.systemMessageData?.actorPublicKey == byPublicKey &&
               abs($0.timestamp.timeIntervalSinceNow) < 5
           }) {
            return // Already have this system message
        }

        // Mark as read if this is our own action (no badge for own actions)
        let isOwnAction = byPublicKey == currentUserPublicKey

        let message = GroupMessage(
            id: UUID(),
            groupId: groupId,
            senderPublicKey: "SYSTEM",
            encryptedContent: Data(),
            timestamp: Date(),
            isRead: isOwnAction,
            systemMessageType: type,
            systemMessageData: GroupSystemMessageData(
                actorPublicKey: byPublicKey,
                targetPublicKey: targetPublicKey
            )
        )

        if groupMessages[groupId] == nil {
            groupMessages[groupId] = []
        }
        groupMessages[groupId]?.append(message)
        NotificationCenter.default.post(
            name: NSNotification.Name("OSHIGroupMessageReceived"),
            object: nil,
            userInfo: [
                "groupId": groupId.uuidString,
                "messageId": message.id.uuidString
            ]
        )

        // Update group last activity
        if let index = groups.firstIndex(where: { $0.id == groupId }) {
            groups[index].lastActivity = Date()
            saveGroups()
        }

        saveMessages()

        // Notify UI of new group message
        DispatchQueue.main.async {
            NotificationCenter.default.post(
                name: NSNotification.Name("NewGroupMessage"),
                object: message
            )
        }

        OshiLog.messaging.info("📢 Added system message: \(type.rawValue) (isRead: \(isOwnAction))")
    }
    
    func deleteGroup(_ groupId: UUID) {
        pendingDeliveryStore.removeGroup(groupId.uuidString)   // __GROUP_RESEND_2026_11_25__
        publishPendingDeliveries()
        groups.removeAll { $0.id == groupId }
        groupMessages.removeValue(forKey: groupId)
        GroupCommentStore.shared.forget(groupId)   // __GROUP_COMMENTS_2026_11_21__
        saveGroups()
        saveMessages()
        
        OshiLog.messaging.info("🗑️ Deleted group")
    }
    
    // MARK: - Delete Message

    /// Delete a message locally only ("for me")
    func deleteGroupMessage(groupId: UUID, messageId: UUID) {
        guard var messages = groupMessages[groupId] else { return }
        messages.removeAll { $0.id == messageId }
        groupMessages[groupId] = messages
        saveMessages()
        objectWillChange.send()
        OshiLog.messaging.info("🗑️ Deleted group message locally: \(messageId)")
    }

    /// Delete a message for everyone — removes locally and broadcasts deletion to all members
    func deleteGroupMessageForEveryone(groupId: UUID, messageId: UUID, senderPublicKey: String) {
        // 1. Delete locally
        deleteGroupMessage(groupId: groupId, messageId: messageId)
        // __CHANNEL_WEB_2026_11_26__ …and from the web, if it was published there.
        ChannelWebPublisher.shared.didDelete(groupId: groupId, messageId: messageId)

        // 2. Create a system message carrying the deleted message ID
        let systemMessage = GroupMessage(
            id: UUID(),
            groupId: groupId,
            senderPublicKey: "SYSTEM",
            encryptedContent: Data(),
            timestamp: Date(),
            isRead: true,
            messageChainIndex: 0,
            systemMessageType: .messageDeleted,
            systemMessageData: GroupSystemMessageData(
                // __GROUP_E2E_V2_2026_09_23__ §2.4: the actor is US (author or admin), not
                // the message's author — an admin deleting someone else's message was
                // otherwise refused by every receiver that checks actor == envelope `from`.
                actorPublicKey: currentUserPublicKey ?? senderPublicKey,
                targetPublicKey: nil,
                deletedMessageId: messageId.uuidString
            )
        )

        // 3. Store the system message locally
        if groupMessages[groupId] != nil {
            groupMessages[groupId]?.append(systemMessage)
        } else {
            groupMessages[groupId] = [systemMessage]
        }
        saveMessages()

        // 4. __GROUP_E2E_V2_2026_09_23__ v2 content channel only (the cleartext mesh copy
        // is gone). `senderKey` = us: the fan-out excludes the SENDER, not the author.
        if let group = groups.first(where: { $0.id == groupId }), let me = currentUserPublicKey {
            Task {
                await broadcastGroupMessageViaRelay(systemMessage, to: group, senderKey: me)
            }
        }

        OshiLog.messaging.info("🗑️ Deleted group message for everyone: \(messageId)")
    }

    // MARK: - Message Operations

    /// Splitting + rate-limiting gate for group sends. Mirror of the 1:1 gate in
    /// `MessageManager.sendMessage` — see MessageSendPolicy.swift for why the
    /// limit is what it is, why split parts cost one rate-limit token rather
    /// than N, and why control payloads are exempt from both rules.
    ///
    /// `@discardableResult`, so every existing call site is unaffected; the
    /// group composer reads the value to restore the user's text on a refusal.
    @discardableResult
    func sendMessage(to groupId: UUID, content: String, senderPublicKey: String, mediaData: Data? = nil, mediaType: MediaType? = nil, mediaFileName: String? = nil, videoNote: VideoNoteMetadata? = nil) -> MessageSendOutcome {
        guard groups.contains(where: { $0.id == groupId }) else { return .sent }

        // __LONG_TEXT_ONE_MESSAGE_2026_09_28__ same line-break normalisation as
        // the 1:1 gate (MessageManager.sendMessage).
        let content = mediaData == nil ? MessageTextSplitter.normalizedForSend(content) : content
        let isUserAction = mediaData != nil || MessageTextSplitter.isUserProse(content)

        // Split decision first, rate limit second — see the 1:1 gate in
        // MessageManager for why a refused paste must not cost a token.
        var plan: MessageTextSplitter.SplitOutcome = .single
        if mediaData == nil {
            // __LONG_TEXT_ONE_MESSAGE_2026_09_28__ Group messages never ride
            // LoRa (no Meshtastic call anywhere in the group path), so the
            // 500 B radio budget never applied to them: standard budget always.
            let limit = MessageTextSplitter.maxUTF8Bytes(loRaPaired: false)
            plan = MessageTextSplitter.splitForSend(content, maxUTF8Bytes: limit)
            if case .tooLong(let needed) = plan {
                OshiLog.messaging.info("⛔️ group send refused: \(needed) parts needed, cap is \(MessageTextSplitter.maxParts)")
                return .tooLong
            }
        }

        if isUserAction, case .denied(let retryAfter) = MessageSendRateLimiter.shared.consume() {
            OshiLog.messaging.info("⛔️ group send refused: rate limit (retry in \(retryAfter)s)")
            return .rateLimited(retryAfterSeconds: retryAfter)
        }

        if case .parts(let parts) = plan {
            OshiLog.messaging.info("✂️ splitting group message: \(content.utf8.count) B into \(parts.count)")
            for part in parts {
                sendSingleMessage(to: groupId, content: part, senderPublicKey: senderPublicKey)
            }
            return .sentAsParts(parts.count)
        }

        sendSingleMessage(to: groupId, content: content, senderPublicKey: senderPublicKey,
                          mediaData: mediaData, mediaType: mediaType, mediaFileName: mediaFileName,
                          videoNote: mediaType == .video ? videoNote : nil)

        // __BOT_RUNTIME_OWNS_DELIVERY_2026_08_16__ (étape 2) The outbound half,
        // moved down from `GroupViews.sendGroupMessage`. Same behaviour for the
        // user, minus the requirement that the chat be on screen — and a bot's
        // own reply comes back through here, which is exactly why
        // `shouldEvaluateTriggers` refuses a `bot:` sender.
        BotManager.shared.processMessage(content: content, groupId: groupId, senderKey: senderPublicKey)
        return .sent
    }

    /// __BOT_IN_THE_PIPELINE_2026_11_09__ The wire copy of a local bot's reply.
    ///
    /// Pure, so the two facts that make the reply deliverable are asserted:
    /// the author is the OWNER's real key (the only sender `GroupWireV2.check`
    /// and Android `v2ContentAuthentic` accept from this device's envelope), and
    /// `botAuthor` names the bot so no receiver's bot answers it. Same id, body
    /// and timestamp as the local row, so delivery status lands on that row.
    nonisolated static func botReplyWireMessage(_ local: GroupMessage, ownerKey: String, botName: String) -> GroupMessage {
        var wire = GroupMessage(
            id: local.id,
            groupId: local.groupId,
            senderPublicKey: ownerKey,
            encryptedContent: local.encryptedContent,
            timestamp: local.timestamp,
            isRead: local.isRead,
            messageChainIndex: 0,
            plaintextContent: local.plaintextContent
        )
        let name = botName.trimmingCharacters(in: .whitespacesAndNewlines)
        wire.botAuthor = name.isEmpty ? nil : botName
        return wire
    }

    /// __BOT_IN_THE_PIPELINE_2026_11_09__ A LOCAL bot's reply, from its owner's
    /// device (the only caller is `BotManager.emit`). Mirror of Android
    /// `GroupManager.sendBotMessage`.
    ///
    /// It used to go through `sendMessage(senderPublicKey: "bot:<id8>")`, which
    /// put `bot:<id8>` in the GroupMessage JSON while the ratchet envelope was
    /// sealed by THIS device's identity — so every receiver, iOS
    /// (`GroupWireV2.check`) and Android (`v2ContentAuthentic`), dropped it as
    /// `sender-mismatch`. The reply was only ever seen on the owner's phone.
    ///
    /// Now: locally the row stays `bot:<id8>` (dark bot bubble, `[Name]` prefix
    /// stripped, as before); on the wire it is an ordinary message from the
    /// owner, body `[Name] text`, plus `botAuthor` as a loop guard. The
    /// receive-side check is NOT loosened: a `bot:` sender unlocks inline
    /// keyboards and `botId` dispatch, and must not be claimable by a member.
    @discardableResult
    func sendBotMessage(to groupId: UUID, botSenderKey: String, botName: String, body: String) -> Bool {
        guard let group = groups.first(where: { $0.id == groupId }),
              let me = currentUserPublicKey, !me.isEmpty,
              let bytes = body.data(using: .utf8) else { return false }

        let local = GroupMessage(
            id: UUID(),
            groupId: groupId,
            senderPublicKey: botSenderKey,
            encryptedContent: bytes,
            timestamp: MessageSendClock.next(),
            isRead: true,
            messageChainIndex: 0,
            plaintextContent: body.isEmpty ? nil : body,
            deliveryStatus: .pending
        )
        var messages = groupMessages[groupId] ?? []
        messages.append(local)
        groupMessages[groupId] = messages
        if let index = groups.firstIndex(where: { $0.id == groupId }) {
            groups[index].lastActivity = Date()
        }
        saveMessages()
        saveGroups()
        objectWillChange.send()
        NotificationCenter.default.post(name: NSNotification.Name("NewGroupMessage"), object: local)
        NotificationCenter.default.post(
            name: NSNotification.Name("OSHIGroupMessageSent"),
            object: nil,
            userInfo: ["groupId": groupId.uuidString, "messageId": local.id.uuidString]
        )

        let wire = Self.botReplyWireMessage(local, ownerKey: me, botName: botName)
        Task {
            await broadcastGroupMessageViaRelay(wire, to: group, senderKey: me)
        }
        return true
    }

    /// The original, unchanged group send pipeline — one call per message that
    /// actually goes out.
    private func sendSingleMessage(to groupId: UUID, content: String, senderPublicKey: String, mediaData: Data? = nil, mediaType: MediaType? = nil, mediaFileName: String? = nil, videoNote: VideoNoteMetadata? = nil) {
        guard let _ = groups.first(where: { $0.id == groupId }) else { return }

        // 📍 Check if this is a live location UPDATE - update existing instead of creating new
        if content.hasPrefix(LocationMessage.locationPrefix),
           let locationMessage = LocationMessage.fromMessageString(content),
           locationMessage.isUpdate,
           let sessionId = locationMessage.sessionId {
            
            // Try to update existing message
            if updateGroupLocationMessage(groupId: groupId, sessionId: sessionId, newContent: content, senderPublicKey: senderPublicKey) {
                OshiLog.messaging.info("📍 Updated existing group live location (sessionId: \(sessionId.prefix(8))...)")
                return
            }
            // If no existing message found, fall through to add as new
        }
        
        // Prepare content - if media, encode as JSON
        var finalContent = content
        if let media = mediaData, let type = mediaType {
            // Create media JSON structure similar to private messages
            var mediaJson: [String: Any] = [
                "type": "media",
                "mediaType": type.rawValue,
                "content": media.base64EncodedString(),
                "size": media.count
            ]
            if let fileName = mediaFileName {
                mediaJson["fileName"] = fileName
            }
            // __VIDEO_NOTE_2026_09_24__ spec §2.D (inline legacy form).
            videoNote?.apply(to: &mediaJson)
            
            if let jsonData = try? JSONSerialization.data(withJSONObject: mediaJson),
               let jsonString = String(data: jsonData, encoding: .utf8) {
                finalContent = jsonString
            }
        }
        
        // 🔧 FIX (security R1): this line used to be labelled "Encrypt content".
        // It does not encrypt anything and never did — it is a UTF-8 byte
        // conversion. `encryptedContent` is a raw plaintext buffer at this point.
        //
        // That is SAFE for the two transports that survive below, because each
        // seals this buffer itself before it leaves the device:
        //   * v2 relay  → one Double Ratchet envelope per member (`v2TrySendGroup`)
        //   * legacy    → AES-256-GCM under the derived group key (`EnvelopeCrypto`)
        // It was NOT safe for the mesh branch, which shipped this struct as bare
        // JSON to every connected peer. That branch is gone (see below).
        //
        // Do not "fix" the name by encrypting here: both surviving transports
        // would then double-encrypt and the receive side would decode garbage.
        guard let plaintextBody = finalContent.data(using: .utf8) else { return }
        
        var built = GroupMessage(
            id: UUID(),
            groupId: groupId,
            senderPublicKey: senderPublicKey,
            encryptedContent: plaintextBody,
            // Was `Date()`. Split parts are issued in one synchronous loop, and
            // the group list sorts with `sorted(by:)` (not stable) on a
            // timestamp that only survives at millisecond resolution over the
            // V2 relay and to Android. See MessageSendClock.
            timestamp: MessageSendClock.next(),
            isRead: true,  // Own messages are always read
            messageChainIndex: 0,
            mediaType: mediaType,
            mediaFileName: mediaFileName,
            plaintextContent: content.isEmpty ? nil : content,
            deliveryStatus: .pending   // → .sent when the relay broadcast lands, → .failed if it can't
        )
        built.applyVideoNote(videoNote)  // __VIDEO_NOTE_2026_09_24__ rides the GroupMessage JSON (§2.B/§2.D)
        let message = built
        
        // Use full dictionary reassignment to guarantee @Published fires
        var messages = groupMessages[groupId] ?? []
        messages.append(message)
        groupMessages[groupId] = messages

        // Update group activity
        if let index = groups.firstIndex(where: { $0.id == groupId }) {
            groups[index].lastActivity = Date()
        }

        // Save media to disk
        if let media = mediaData, let type = mediaType {
            _ = saveGroupMediaAttachment(media, for: message.id.uuidString, type: type)
        }

        saveMessages()
        saveGroups()

        // Force SwiftUI refresh
        objectWillChange.send()

        // Post notification for UI update
        NotificationCenter.default.post(
            name: NSNotification.Name("NewGroupMessage"),
            object: message
        )
        // 🆕 Web sessions need their own bridge so the GroupMessageSent observer sees it.
        NotificationCenter.default.post(
            name: NSNotification.Name("OSHIGroupMessageSent"),
            object: nil,
            userInfo: [
                "groupId": groupId.uuidString,
                "messageId": message.id.uuidString
            ]
        )

        OshiLog.messaging.info("📤 Sent message to group (media: \(mediaType?.rawValue ?? "none"))")

        // __CHANNEL_WEB_2026_11_26__ public web preview: queue the cleartext copy of a
        // NEW channel post (no-op unless the preview is on and we are its admin). After
        // the E2E send above, on its own durable queue — never in its way.
        if let g = groups.first(where: { $0.id == groupId }) {
            ChannelWebPublisher.shared.didSend(message, in: g, me: currentUserPublicKey)
        }
        
        // 🔧 FIX (security R1): the mesh branch no longer carries group message
        // bodies. It used to JSON-encode `message` — whose `encryptedContent` is
        // raw UTF-8 (above) and whose `plaintextContent` is the literal string,
        // BOTH inside `CodingKeys` (`:326`) — and hand it to
        // `session.send(toPeers: session.connectedPeers)`. With
        // `autoConnectEnabled = true` (MeshNetworkManager `:82`) and invitations
        // auto-accepted with no identity or membership check (`:1753`), every
        // nearby OSHI device — member or not — received the body in clear. Inline
        // media rode the same JSON as base64.
        //
        // WHY REMOVED RATHER THAN ENCRYPTED. The obvious repair is to reuse the
        // per-member Double Ratchet. It is not reachable on this branch:
        // `encrypt1to1` → `loadOrEstablish` → `establishInitiatorSession` →
        // `client.fetchBundle` (V2MessageRouter `:319`) is a SERVER round-trip
        // for any member we have no session with. So:
        //   * server reachable → `v2TrySendGroup` below already delivered to that
        //     member; a ratcheted mesh copy would be pure duplication.
        //   * server unreachable → which is the ONLY reason this branch existed
        //     ("offline/protest scenarios") → no bundle, no session, no ciphertext.
        // Encrypting here would therefore work only when it is not needed and
        // fail exactly when it is. That is a half-encrypted path, so it is not
        // shipped; the branch is deleted instead.
        //
        // DELIBERATE FEATURE REMOVAL: offline (no-internet) group delivery over
        // the local mesh. 1:1 mesh messaging is untouched. Restoring the old
        // cleartext behaviour is possible but must be an explicit, informed act —
        // hence the flag, which defaults to OFF (= not leaking).
        // __GROUP_E2E_V2_2026_09_23__ The `GroupMeshPlaintextBroadcast` escape hatch is no
        // longer honoured on send: spec rule zero forbids cleartext mesh for group traffic.

        // ✅ BROADCAST TO ALL MEMBERS VIA IPFS RELAY (requires internet)
        // This ensures all members (even 50+) receive the message
        if let group = groups.first(where: { $0.id == groupId }) {
            Task {
                await broadcastGroupMessageViaRelay(message, to: group, senderKey: senderPublicKey)
            }
        }
    }
    
    // MARK: - IPFS Relay Broadcasting for Group Messages
    
    /// Update the delivery status of one of OUR outgoing group messages, then
    /// persist + refresh the UI. Drives pending → sent (broadcast landed) or
    /// pending → failed (nothing could be delivered). No-op if unchanged.
    func updateGroupMessageStatus(groupId: UUID, messageId: UUID, to status: DeliveryStatus) {
        // __GROUP_COMMENTS_2026_11_21__ a comment's status lives in the comment store.
        if groupMessages[groupId]?.contains(where: { $0.id == messageId }) != true,
           let c = GroupCommentStore.shared.comment(groupId: groupId, id: messageId.uuidString) {
            if c.deliveryStatus != status {
                GroupCommentStore.shared.update(groupId: groupId, id: messageId) { $0.deliveryStatus = status }
            }
            return
        }
        guard var messages = groupMessages[groupId],
              let idx = messages.firstIndex(where: { $0.id == messageId }),
              messages[idx].deliveryStatus != status else { return }
        messages[idx].deliveryStatus = status
        groupMessages[groupId] = messages
        saveMessages()
        objectWillChange.send()
    }

    /// Retry a failed outgoing group message: reset to pending and re-broadcast.
    /// Re-broadcasting the SAME message id is idempotent on receivers (dedup by
    /// id). Reloads media from disk since mediaData isn't persisted on the struct.
    func retryGroupMessage(groupId: UUID, messageId: UUID) {
        // __GROUP_RESEND_2026_11_25__ members already reached are not sent it again.
        if pendingDeliveryStore.get(messageId.uuidString) != nil {
            retryPendingDeliveryNow(messageId: messageId)
            return
        }
        guard let group = groups.first(where: { $0.id == groupId }),
              let msg = resendableCopy(groupId: groupId, messageId: messageId) else { return }
        updateGroupMessageStatus(groupId: groupId, messageId: messageId, to: .pending)
        let retryMsg = msg
        Task { await broadcastGroupMessageViaRelay(retryMsg, to: group, senderKey: retryMsg.senderPublicKey) }
    }

    /// The stored row, ready to go out again: media reloaded from disk, the inlined media JSON
    /// rebuilt when the disk compaction trimmed it.
    func resendableCopy(groupId: UUID, messageId: UUID) -> GroupMessage? {
        guard var msg = groupMessages[groupId]?.first(where: { $0.id == messageId }) else { return nil }
        if let type = msg.mediaType, msg.mediaData == nil {
            msg.mediaData = loadGroupMediaAttachment(for: messageId.uuidString, type: type)
        }
        // The wire payload for group media IS `encryptedContent` (the inlined media
        // JSON) — `mediaData` is excluded from Codable. After a relaunch that field
        // is empty for messages the disk compaction trimmed, so rebuild it from the
        // blob before re-broadcasting; otherwise the retry would ship an empty body.
        if let type = msg.mediaType, msg.encryptedContent.isEmpty, let media = msg.mediaData {
            var mediaJson: [String: Any] = [
                "type": "media",
                "mediaType": type.rawValue,
                "content": media.base64EncodedString(),
                "size": media.count
            ]
            if let fileName = msg.mediaFileName { mediaJson["fileName"] = fileName }
            if let jsonData = try? JSONSerialization.data(withJSONObject: mediaJson) {
                msg.encryptedContent = jsonData
            }
        }
        return msg
    }

    /// MIME for a group media attachment. Mirrors `MessageManager.v2Mime(for:)`,
    /// but over the GROUP `MediaType` enum (`photo` where the 1:1 enum says
    /// `image`) — the two are separate types and must not be bridged by rawValue.
    nonisolated static func v2Mime(for type: MediaType) -> String {
        switch type {
        case .photo: return "image/jpeg"
        case .video: return "video/mp4"
        case .audio: return "audio/m4a"
        case .contact: return "text/vcard"
        case .document: return "application/octet-stream"
        }
    }

    /// Fallback filename when the message carries none. Only cosmetic — the
    /// receiver renders from `GroupMessage.mediaType`, not from the extension.
    nonisolated static func v2DefaultFileName(for type: MediaType) -> String {
        switch type {
        case .photo: return "photo.jpg"
        case .video: return "video.mp4"
        case .audio: return "audio.m4a"
        case .contact: return "contact.vcf"
        case .document: return "file.dat"
        }
    }

    /// Broadcasts a group message to all members via IPFS relay
    /// This ensures all members receive messages even if they're not nearby
    /// __GROUP_E2E_V2_2026_09_23__ Fan a group message out over v2 ONLY (spec §2, §4, §6).
    ///
    /// The Pinata/IPFS branch that used to follow — a groupId-keyed envelope anyone holding the
    /// group id could open (audit L1), the v3 wrapped pin, the per-member `/api/queue`, the
    /// cleartext mesh relay of that envelope and its legacy push — is deleted. A member with no
    /// v2 bundle receives nothing and is listed in `v2UnreachableMembers` so the group screen
    /// can tell the user to have them update.
    /// __GROUP_RESEND_2026_11_25__ `onlyMembers`: an automatic / manual resend to the members a
    /// pending-delivery entry still lists (nil = the first send, to everybody).
    private func broadcastGroupMessageViaRelay(_ message: GroupMessage, to group: MessageGroup, senderKey: String,
                                               onlyMembers: Set<String>? = nil) async {
        let me = MessageManager.canonicalIdentity(senderKey)
        let wanted = onlyMembers.map { Set($0.map { MessageManager.canonicalIdentity($0) }) }
        // 🛡 __ADVANCED_CHAT_PRIVACY_2026_11_26__ The switch goes only to members
        // that read it: iOS 1.0.43 and every older build would print the raw
        // sentinel as a message. They are simply not sent it (they could not have
        // enforced it anyway); see `AdvancedChatPrivacyPolicy.peerReads`.
        let isPrivacySwitch = message.mediaType == nil && AdvancedPrivacyChange.isPayload(
            message.plaintextContent ?? String(data: message.encryptedContent, encoding: .utf8) ?? "")
        let members = group.members.filter {
            let k = MessageManager.canonicalIdentity($0.publicKey)
            return k != me && (wanted?.contains(k) ?? true)
                && (!isPrivacySwitch || AdvancedChatPrivacyPolicy.peerReadsNow($0.publicKey))
        }
        guard !members.isEmpty else {
            // Solo group (or everyone left) — nothing to deliver, so it's "sent".
            if onlyMembers != nil { pendingDeliveryStore.remove(message.id.uuidString); publishPendingDeliveries() }
            updateGroupMessageStatus(groupId: group.id, messageId: message.id, to: .sent)
            return
        }
        let groupIdString = group.id.uuidString
        let recipientKeys = members.map { $0.publicKey }
        let kind = GroupWireV2.kind(of: message)
        var handled: Set<String> = []

        // __GROUP_RESEND_2026_11_25__ a USER message that is a row of this thread is tracked until
        // every recipient has it (typing, reactions, votes, system commands are not).
        let msgKey = message.id.uuidString
        let tracked = kind == .user && message.senderPublicKey != "SYSTEM"
            && groupMessages[group.id]?.contains(where: { $0.id == message.id }) == true
        if tracked {
            if onlyMembers == nil {
                pendingDeliveryStore.begin(msgId: msgKey, groupId: groupIdString, recipients: recipientKeys,
                                           botAuthor: message.botAuthor)
                publishPendingDeliveries()
            }
            groupSendsInFlight.insert(msgKey)
        }

        // 📡 LOCAL MESH FIRST — a transport, not a consolation prize.
        //
        // __GROUP_MESH_IS_A_TRANSPORT_2026_10_16__ A reviewer sat in a room of
        // nine members, every phone within a metre of the next, and watched every
        // message leave over the internet. On this branch that was not a bug in
        // the mesh code — there was no mesh code: group send is `v2TrySendGroup`
        // plus the legacy pin, and `legacyCompatSendGroupMessage` says "no mesh"
        // in its own docstring. The 2026-09-23 removal was right about the
        // CLEARTEXT broadcast it deleted and wrong to leave nothing behind it, so
        // a group with no internet had no delivery at all.
        //
        // The receive half already exists on BOTH platforms and is exercised by
        // the IPFS path every day:
        //   * iOS     `handleMeshGroupEnvelope` → GroupEnvelopeV3.open → MessagePayload
        //   * Android `processGroupEnvelopeFromMesh` → decryptIPFSEnvelope, which
        //             branches on `v == GroupEnvelopeV3.VERSION && has("wraps")`
        // so only the SEND half was missing. This adds exactly that, in the one
        // format both sides already decode, and nothing else changes.
        //
        // Safe with no server in reach, which is the whole point: `liveEncryptor`
        // is a LOCAL X25519 ECDH plus a local ratchet session — no bundle fetch.
        // That is what GroupEnvelopeV3 was written for; see its file header.
        //
        // NOT the cleartext broadcast that was removed: a member we cannot wrap is
        // skipped, and a non-member on the same mesh gets ciphertext addressed to
        // nobody it can open.
        let meshReached = meshSendGroupEnvelope(message, group: group, senderKey: senderKey,
                                                allRecipients: recipientKeys)
        if !meshReached.isEmpty {
            handled.formUnion(meshReached)
            // A message the radio delivered IS sent, even if every server below
            // fails. Otherwise an offline send sits on a failure tick forever.
            updateGroupMessageStatus(groupId: group.id, messageId: message.id, to: .sent)
        }

        let mediaBytes: Data? = message.mediaType.flatMap { type in
            // `mediaData` is not persisted, so on the retry path it comes off disk.
            if let inMemory = message.mediaData, !inMemory.isEmpty { return inMemory }
            return loadGroupMediaAttachment(for: message.id.uuidString, type: type)
        }
        if let mediaType = message.mediaType, let bytes = mediaBytes, !bytes.isEmpty {
            // §4: bytes to the blob store (one reservation per member), the stripped
            // GroupMessage rides inside each member's `v2file` key message.
            var stripped = message
            stripped.encryptedContent = Data()
            stripped.mediaData = nil
            if let strippedB64 = GroupWireV2.encodeContent(stripped) {
                // __MESH_DELIVERY_COUNTS_2026_11_02__ formUnion, NOT `=`. These two
                // assignments used to overwrite `handled`, discarding every member
                // `meshSendGroupEnvelope` had just delivered to over the radio a few
                // lines above. Two consequences, both reported from the field: a
                // message the mesh carried perfectly was marked `.failed` whenever
                // the relay reached nobody, and §6 below then listed those same
                // members as needing to update the app.
                handled.formUnion(await MessageManager.shared?.v2TrySendGroupFile(
                    groupId: groupIdString,
                    memberKeys: recipientKeys,
                    senderKey: message.senderPublicKey,
                    data: bytes,
                    filename: message.mediaFileName ?? Self.v2DefaultFileName(for: mediaType),
                    mime: Self.v2Mime(for: mediaType),
                    mediaType: mediaType.rawValue,
                    groupMessageB64: strippedB64,
                    msgId: message.id.uuidString,
                    videoNote: message.videoNoteMetadata) ?? [])
            }
        } else if let text = GroupWireV2.encodeContent(message) {
            handled.formUnion(await MessageManager.shared?.v2TrySendGroup(
                groupId: groupIdString,
                memberKeys: recipientKeys,
                senderKey: senderKey,
                text: text,
                messageId: message.id.uuidString,
                // __ADVANCED_CHAT_PRIVACY_2026_11_26__ the privacy switch never wakes anyone.
                push: kind == .user && !isPrivacySwitch,
                sentAt: message.timestamp,
                selfCopy: kind == .user,
                // __GROUP_COMMENTS_2026_11_21__ §2.12.3: a comment wakes only the
                // author of the comment it answers; everyone else gets it silently.
                pushOnly: message.commentOn == nil ? nil : commentPushTargets(message, group: group, me: senderKey)) ?? [])
        }

        // __LEGACY_COMPAT_2026_09_23__ transition window: the pre-v2 path as well, for the
        // members not proven v2-group-capable (empty list when compat is off ⇒ no-op).
        // __LEAK_FIX_2026_09_25__ (audit B) not to a member v2 already reached whose build reads
        // v2 groups (advertises group v3): the legacy copy was a readable duplicate for it.
        // __GROUP_FANOUT_2026_11_24__ a member the relay rate-limited is NOT a legacy target: the
        // legacy envelope (groupId-derived key) is weaker, and a 429 says nothing about the member.
        let legacyTargets = GroupLegacyCompat.withoutRateLimited(
            GroupLegacyCompat.stillNeedLegacy(GroupLegacyCompat.legacyTargets(recipientKeys), v2Reached: handled),
            rateLimited: GroupRateLimitLedger.shared.take(messageId: message.id.uuidString))
        var legacyReached: Set<String> = []
        if !legacyTargets.isEmpty {
            legacyReached = await legacyCompatSendGroupMessage(message, group: group, senderKey: senderKey,
                                                               allRecipients: recipientKeys,
                                                               legacyMembers: legacyTargets,
                                                               wake: kind == .user && message.commentOn == nil && !isPrivacySwitch)
            handled.formUnion(legacyReached)
        }

        // §6: whoever v2 did not reach and has no bundle at all must update OSHI.
        let missing = recipientKeys.filter { !handled.contains($0) }
        let unavailable = missing.isEmpty ? [] : await (MessageManager.shared?.v2UnavailableMembers(missing) ?? [])
        let listed = recipientKeys.filter { unavailable.contains($0) }
        if (v2UnreachableMembers[group.id] ?? []) != listed {
            v2UnreachableMembers[group.id] = listed.isEmpty ? nil : listed
        }
        if !missing.isEmpty {
            OshiLog.messaging.info("📡 [V2] group message: \(handled.count)/\(members.count) reached, \(listed.count) member(s) on a build without v2 (nothing sent to them)")
        }
        if tracked {
            // __GROUP_RESEND_2026_11_25__ reached members and members with no v2 bundle at all
            // ("must update", §6) leave the list; the rest are retried 1, 5, 30 min, then 3-hourly.
            groupSendsInFlight.remove(msgKey)
            let left = pendingDeliveryStore.settle(msgId: msgKey, reached: Array(handled), noBundle: listed)
            publishPendingDeliveries()
            if let left {
                OshiLog.messaging.info("↻ [V2] group message \(msgKey.prefix(8)): \(left.pending.count)/\(left.total) member(s) pending, next attempt in \(Int(left.nextAt.timeIntervalSinceNow)) s")
            }
        }
        // A resend that reached nobody leaves the bubble as it was (the first send's status).
        if onlyMembers != nil && handled.isEmpty { return }
        updateGroupMessageStatus(groupId: group.id, messageId: message.id,
                                 to: handled.isEmpty ? .failed : .sent)
    }

    /// Hand this group message to every mesh-reachable member, sealed per member.
    ///
    /// Returns the members a live link accepted it for. Synchronous and cheap: the
    /// seal is local crypto and `CrossPlatformMesh.sendMessage` hands off to its
    /// own queue, so this costs nothing when no peer is nearby (the loop body
    /// never runs) and must not be made async — it has to complete BEFORE the
    /// server attempt so that the offline case is covered at all.
    ///
    /// Only members that advertise group v3 are included. A reachable member on an
    /// older build is left to the server path: wrapping the body under the
    /// groupId-derived key instead would hand a stranger on the same mesh a key it
    /// can derive from a public group id, which is CV-002 exactly.
    private func meshSendGroupEnvelope(_ message: GroupMessage, group: MessageGroup,
                                       senderKey: String, allRecipients: [String]) -> Set<String> {
        let me = MessageManager.canonicalIdentity(senderKey)
        let caps = PeerCapabilityStore.shared
        let targets = allRecipients.filter {
            MessageManager.canonicalIdentity($0) != me
                && CrossPlatformMesh.shared.isPeerReachable($0)
                && caps.supportsGroupV3($0)
        }
        guard !targets.isEmpty, let identity = MessageManager.sharedIdentityManager else { return [] }

        // Inline media rides `encryptedContent` on this path, so a photo would put
        // megabytes of base64 on a local TCP link that also carries live calls.
        // Above the cap the relay and the blob store keep the message; the radio
        // carries text, which is what an offline group needs first. Raising this is
        // a deliberate decision to make once mesh media has been measured.
        let meshInlineCap = 2 * 1024 * 1024

        do {
            let groupMessageBase64 = try JSONEncoder().encode(message).base64EncodedString()
            let payload = MessagePayload.forGroupMessage(
                id: message.id.uuidString,
                groupId: group.id.uuidString,
                senderKey: message.senderPublicKey,
                recipientKeys: allRecipients,
                content: groupMessageBase64,
                chainIndex: message.messageChainIndex,
                hasMedia: message.mediaData != nil,
                mediaType: message.mediaType?.rawValue
            )
            let payloadData = try JSONEncoder().encode(payload)
            guard payloadData.count <= meshInlineCap else {
                OshiLog.messaging.info("📡 group mesh: \(payloadData.count) B over the \(meshInlineCap) B inline cap — server only")
                return []
            }
            let sealed = try GroupEnvelopeV3.seal(
                payload: payloadData,
                members: targets,
                encryptWrap: GroupEnvelopeV3.liveEncryptor(ourPublicKey: identity.publicKey,
                                                           identity: identity))
            guard !sealed.wraps.isEmpty,
                  let envelopeData = try? JSONSerialization.data(withJSONObject: sealed.asPinnedDictionary),
                  let envelopeJson = String(data: envelopeData, encoding: .utf8) else { return [] }

            var reached: Set<String> = []
            for member in targets where CrossPlatformMesh.shared.sendMessage(
                to: member, content: envelopeJson, type: "GROUP_MESSAGE") {
                reached.insert(member)
            }
            if !reached.isEmpty {
                OshiLog.messaging.info("📡 group message delivered over the local mesh to \(reached.count)/\(targets.count) member(s) — before any server")
            }
            return reached
        } catch {
            // Never fatal: the server paths below are untouched by this failing.
            OshiLog.messaging.info("📡 group mesh seal failed: \(error)")
            return []
        }
    }

    /// __LEGACY_COMPAT_2026_09_23__ The pre-v2 content path, restored verbatim from the
    /// release before 48e60b83 for the transition window ONLY (see `GroupLegacyCompat`):
    /// the whole GroupMessage (inline media included, as old builds expect it in
    /// `encryptedContent`) in the groupId-keyed v2-format envelope `{v,envelope,hint,nonce}`,
    /// pinned once, queued per legacy member on `/api/queue`. No v3 variant, no mesh.
    /// Push = the generic wake (no group name, no content). Returns the members queued.
    private func legacyCompatSendGroupMessage(_ message: GroupMessage, group: MessageGroup, senderKey: String,
                                              allRecipients: [String], legacyMembers: [String],
                                              wake: Bool) async -> Set<String> {
        let groupIdString = group.id.uuidString
        let envelopeDict: [String: Any]
        do {
            let groupMessageBase64 = try JSONEncoder().encode(message).base64EncodedString()
            let payload = MessagePayload.forGroupMessage(
                id: message.id.uuidString,
                groupId: groupIdString,
                senderKey: message.senderPublicKey,
                recipientKeys: allRecipients,
                content: groupMessageBase64,
                chainIndex: message.messageChainIndex,
                hasMedia: message.mediaData != nil,
                mediaType: message.mediaType?.rawValue
            )
            // __LEAK_FIX_2026_09_25__ (audit B) no group key when every legacy target reads the
            // v4 wrapped envelope (every build since 2026-09-04, iOS 1.0.43 included — it sent
            // this very format itself): one body, the content key wrapped per target over the
            // 1:1 ratchet. The groupId-keyed form stays, unchanged, for an older target only.
            if GroupEnvelopeV3.canUse(members: legacyMembers),
               let identity = MessageManager.sharedIdentityManager {
                let sealed = try GroupEnvelopeV3.seal(
                    payload: try JSONEncoder().encode(payload),
                    members: legacyMembers,
                    encryptWrap: GroupEnvelopeV3.liveEncryptor(ourPublicKey: identity.publicKey,
                                                               identity: identity))
                guard !sealed.wraps.isEmpty else {
                    OshiLog.messaging.info("❌ [LEGACY-COMPAT] v4: no legacy member could be wrapped — not pinning")
                    return []
                }
                envelopeDict = sealed.asPinnedDictionary
            } else {
                let envelope = try EnvelopeCrypto.encryptForGroup(payload: payload,
                                                                  groupKey: deriveGroupKey(groupId: groupIdString))
                envelopeDict = ["v": envelope.v, "envelope": envelope.envelope,
                                "hint": envelope.hint ?? "", "nonce": envelope.nonce]
            }
        } catch {
            OshiLog.messaging.info("❌ [LEGACY-COMPAT] group envelope encode failed: \(error)")
            return []
        }

        var ipfsHash: String?
        for attempt in 1...3 {
            do {
                ipfsHash = try await PinataService.shared.pinJSON(envelopeDict, name: "msg_\(Date().timeIntervalSince1970)")
                break
            } catch {
                OshiLog.messaging.info("⚠️ [LEGACY-COMPAT] IPFS upload \(attempt)/3 failed: \(error.localizedDescription)")
                if attempt < 3 { try? await Task.sleep(nanoseconds: UInt64(pow(2.0, Double(attempt))) * 1_000_000_000) }
            }
        }
        guard let ipfsHash else { return [] }
        PinataCleanupService.shared.trackSentMessage(ipfsHash: ipfsHash, messageId: message.id.uuidString,
                                                     recipientPublicKey: groupIdString, isGroupMessage: true)

        var reached: Set<String> = []
        for member in legacyMembers {
            for attempt in 1...2 {
                do {
                    try await legacyCompatQueue(ipfsHash, recipientKey: member, nopush: false)
                    reached.insert(member)
                    break
                } catch {
                    if attempt < 2 { try? await Task.sleep(nanoseconds: 500_000_000) }
                }
            }
        }
        OshiLog.messaging.info("📡 [LEGACY-COMPAT] group message queued for \(reached.count)/\(legacyMembers.count) legacy member(s)")

        if wake {
            let me = MessageManager.canonicalIdentity(senderKey)
            let pushTo = reached.filter { MessageManager.canonicalIdentity($0) != me }
            await withTaskGroup(of: Void.self) { tg in
                for memberKey in pushTo {
                    tg.addTask {
                        // No-plaintext notification rule: generic title/body, never the group
                        // name or the text. groupId/messageId/ipfsHash are opaque routing ids.
                        try? await VoIPPushManager.requestPushNotification(
                            to: memberKey, type: "group_message", title: "OSHI",
                            body: "You have a new group message",
                            additionalData: ["groupId": groupIdString, "senderPublicKey": senderKey,
                                             "messageId": message.id.uuidString, "ipfsHash": ipfsHash])
                    }
                }
            }
        }
        for member in reached {
            DeliveryReceiptManager.shared.trackOutgoingMessage(messageId: message.id.uuidString, ipfsHash: ipfsHash,
                                                               recipientKey: member, senderKey: message.senderPublicKey)
        }
        return reached
    }

    /// __LEGACY_COMPAT_2026_09_23__ `POST /api/queue/<base64url recipient>/<cid>` (pre-v2 queue).
    private func legacyCompatQueue(_ hash: String, recipientKey: String, nopush: Bool) async throws {
        let s = "https://oshi-messenger.com/api/queue/\(base64urlEncode(recipientKey))/\(hash)\(nopush ? "?nopush=1" : "")"
        guard let url = URL(string: s) else {
            throw NSError(domain: "GroupMessaging", code: -2, userInfo: [NSLocalizedDescriptionKey: "Invalid URL"])
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 10
        let (_, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, http.statusCode != 200 {
            throw NSError(domain: "GroupMessaging", code: http.statusCode,
                          userInfo: [NSLocalizedDescriptionKey: "Server returned \(http.statusCode)"])
        }
    }

    /// __LEGACY_COMPAT_2026_09_23__ The pre-v2 STATE path for the transition window: the
    /// `SendGroupUpdateViaCloud` pin (IPFSGroupUploader, gated by `legacyGroupUploadsAllowed`)
    /// plus the per-member `?nopush=1` queue of the same JSON (`queueMemberJoinedViaVPS`), as
    /// before 48e60b83. Only to `legacyMembers`; no mesh. No-op when the list is empty.
    func legacyCompatSendGroupState(_ data: Data, groupId: UUID, legacyMembers: [String]) {
        guard !legacyMembers.isEmpty, GroupLegacyCompat.active else { return }
        OshiLog.messaging.info("📡 [LEGACY-COMPAT] group state (\(data.count) B) → \(legacyMembers.count) legacy member(s)")
        NotificationCenter.default.post(
            name: NSNotification.Name("SendGroupUpdateViaCloud"),
            object: nil,
            userInfo: ["data": data, "recipients": legacyMembers, "groupId": groupId.uuidString]
        )
        Task {
            guard let jsonDict = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return }
            do {
                let cid = try await PinataService.shared.pinJSON(jsonDict, name: "member_join_\(Date().timeIntervalSince1970)")
                for key in legacyMembers {
                    try? await self.legacyCompatQueue(cid, recipientKey: key, nopush: true)
                }
            } catch {
                OshiLog.messaging.info("⚠️ [LEGACY-COMPAT] group state pin failed: \(error.localizedDescription)")
            }
        }
    }

    /// 📍 Update existing live location message in a group
    private func updateGroupLocationMessage(groupId: UUID, sessionId: String, newContent: String, senderPublicKey: String) -> Bool {
        guard var messages = groupMessages[groupId] else { return false }
        
        // Find existing message with matching sessionId
        guard let index = messages.firstIndex(where: { message in
            guard message.senderPublicKey == senderPublicKey else { return false }
            
            // Check if plaintextContent has matching sessionId
            if let content = message.plaintextContent,
               content.hasPrefix(LocationMessage.locationPrefix),
               let existingLocation = LocationMessage.fromMessageString(content),
               existingLocation.sessionId == sessionId {
                return true
            }
            return false
        }) else {
            return false
        }
        
        // Update the message
        var updatedMessage = messages[index]
        updatedMessage.timestamp = Date()
        updatedMessage.plaintextContent = newContent
        // Update encrypted content too
        if let encryptedData = newContent.data(using: .utf8) {
            updatedMessage.encryptedContent = encryptedData
        }
        
        messages[index] = updatedMessage
        groupMessages[groupId] = messages
        
        saveMessages()
        
        // Check if this is a "stopped" message
        let isStopped = LocationMessage.fromMessageString(newContent)?.isStopped ?? false
        
        // Post notification for UI update
        if isStopped {
            NotificationCenter.default.post(
                name: NSNotification.Name("LiveLocationStopped"),
                object: nil,
                userInfo: ["sessionId": sessionId, "groupId": groupId.uuidString]
            )
        } else {
            NotificationCenter.default.post(
                name: NSNotification.Name("LiveLocationUpdated"),
                object: nil,
                userInfo: ["sessionId": sessionId, "groupId": groupId.uuidString]
            )
        }
        
        return true
    }
    
    // MARK: - Receive Group Message from IPFS Relay
    
    /// Called when a group message is received via IPFS relay
    /// Messages that arrived before their group definition did, keyed by group.
    /// Memory-only on purpose: a definition that never arrives means the device
    /// was never really in that group, and persisting would resurrect it forever.
    @MainActor private static var orphanedMessages: [UUID: [GroupMessage]] = [:]

    // __GROUP_SELF_HEAL_2026_10_01__ Per-group throttle for the definition request
    // fired from the orphan path: at most one request per group per 45 s.
    @MainActor private static var lastSelfHealRequest: [UUID: Date] = [:]
    @MainActor static func shouldSelfHealRequest(_ groupId: UUID) -> Bool {
        let now = Date()
        if let last = lastSelfHealRequest[groupId], now.timeIntervalSince(last) < 45 { return false }
        lastSelfHealRequest[groupId] = now
        return true
    }

    // __GROUP_SELF_HEAL_2026_10_01__ Ask ONE peer for the definition of EVERY group we share.
    // Recovers a wiped device's groups: identity + 1:1 ratchets survive, so any sharing peer
    // can answer. Global 30s throttle.
    @MainActor private static var lastAllGroupsSync: Date = .distantPast
    @MainActor func requestAllGroupsSyncFromSender(_ senderPublicKey: String) {
        guard !senderPublicKey.isEmpty, senderPublicKey != "SYSTEM", !senderPublicKey.hasPrefix("bot:"),
              let myKey = currentUserPublicKey else { return }
        let now = Date()
        if now.timeIntervalSince(Self.lastAllGroupsSync) < 30 { return }
        Self.lastAllGroupsSync = now
        guard let framed = GroupWireV2.syncRequest(requester: myKey) else { return }
        OshiLog.messaging.info("🩹 [self-heal] asking \(senderPublicKey.prefix(8))… for ALL my groups (group not known locally)")
        Task { _ = await MessageManager.shared?.v2SendGroupState(framed, to: [senderPublicKey]) }
    }

    /// __ASK_DONT_WAIT_2026_10_19__ Ask every known contact for the groups we
    /// share, once per launch.
    ///
    /// `requestAllGroupsSyncFromSender` already does this, but only REACTIVELY:
    /// it fires when a group envelope arrives that we cannot match. A device
    /// that was added to a group while it could not receive — offline, or (as
    /// here) advertising `caps: 0` so senders built the roster without it —
    /// never gets such an envelope, so the self-heal never triggers and the
    /// group stays invisible forever. Re-adding the member on the other device
    /// is the only cure, and nothing tells the user that.
    ///
    /// Measured: a Mac added to four groups from an iPhone showed none of them,
    /// because the roster broadcast happened while it was incapable and a
    /// broadcast is not replayed.
    ///
    /// One framed request, fanned out in a single call, once per process. The
    /// peer answers with the groups we are actually a member of, so this
    /// discloses nothing the sender does not already know about us.
    @MainActor func requestGroupSyncFromAllContacts() {
        guard !Self.askedEveryoneThisLaunch else { return }
        Self.askedEveryoneThisLaunch = true
        guard let myKey = currentUserPublicKey,
              let framed = GroupWireV2.syncRequest(requester: myKey) else { return }
        let peers = (MessageManager.shared?.conversations ?? [])
            .map(\.participantPublicKey)
            .filter { !$0.isEmpty && $0 != "SYSTEM" && !$0.hasPrefix("bot:") && !$0.hasPrefix("lora!") && !MeshCoreInterop.isInteropKey($0)
                      && MessageManager.canonicalIdentity($0) != MessageManager.canonicalIdentity(myKey) }
        let unique = Array(Set(peers))
        guard !unique.isEmpty else { return }
        OshiLog.messaging.info("🩹 [self-heal] asking \(unique.count) contact(s) for every group we share")
        // `wake: true` deliberately. The peer has to be RUNNING to answer a
        // sync request, and a contact whose app is closed would otherwise never
        // reply — leaving the group invisible until they happen to open OSHI.
        // The group_update wake is a silent data push (`title: ""`, `body: ""`,
        // and the receiver's `presentBody` drops silent control payloads before
        // any banner), so nobody is notified; it only gives their app the
        // moment of runtime it needs to respond.
        Task { _ = await MessageManager.shared?.v2SendGroupState(framed, to: unique, wake: true) }
    }

    private static var askedEveryoneThisLaunch = false

    /// Replay anything held for `groupId`. Call right after a group is created,
    /// joined, or synced in — that is the moment the race resolves.
    @MainActor func replayOrphanedMessages(for groupId: UUID) {
        guard let held = Self.orphanedMessages.removeValue(forKey: groupId), !held.isEmpty else { return }
        OshiLog.messaging.info("♻️ Replaying \(held.count) queued message(s) for group \(groupId)")
        for m in held.sorted(by: { $0.timestamp < $1.timestamp }) {
            receiveGroupMessage(m)
        }
    }

    @MainActor func receiveGroupMessage(_ message: GroupMessage) {
        // __GROUP_COMMENTS_2026_11_21__ `var`: §2.12.4 rules 2/3 strip an ignored `commentOn`.
        var message = message
        debugLog("receiveGroupMessage CALLED for group=\(message.groupId.uuidString.prefix(8)), sender=\(message.senderPublicKey.prefix(12))")
        // Check if we have this group
        guard let group = groups.first(where: { $0.id == message.groupId }) else {
            // __ORPHAN_GROUP_QUEUE_2026_08_14__ This used to `return` and the
            // message was gone for good — the single most likely explanation for
            // "I get a notification for the group but the message never shows".
            // The generic push banner is fired by the SENDER (v2TrySendGroup), so
            // it lands whether or not the receiver knows the group yet; the rich
            // banner and the message itself only come from here. Group
            // definitions arrive over their own GROUP_UPDATE path and can lose
            // the race — a member added seconds ago, an invite still in flight,
            // or a bot channel the device has not synced. Hold the message and
            // replay it when the definition lands.
            debugLog("ORPHAN: unknown group \(message.groupId.uuidString.prefix(8)) — queued")
            OshiLog.messaging.info("📥 Group message for unknown group \(message.groupId) — queued for replay")
            var q = Self.orphanedMessages[message.groupId] ?? []
            // Bounded, and never the same message twice: a repeating relay pull
            // must not grow this without limit.
            if !q.contains(where: { $0.id == message.id }) {
                q.append(message)
                if q.count > 200 { q.removeFirst(q.count - 200) }
                Self.orphanedMessages[message.groupId] = q
            }
            // __GROUP_SELF_HEAL_2026_10_01__ We can DECRYPT this message (the content
            // key was unwrapped over our 1:1 ratchet, which lives in the Keychain and
            // survives an app-data wipe) but we have no local definition for the group —
            // so, like a 1:1 chat that rebuilds from an incoming message, ask the ONE
            // member we can identify (the sender) for the group definition. Their answer
            // (member_sync_request responder → full GROUP_UPDATE) recreates the group and
            // replayOrphanedMessages() then shows this backlog. Throttled per group so a
            // relay re-pull can't spam it. This is also how a device recovers its groups
            // after a restore/wipe while still a member server-side.
            if !Self.hasLeftTombstone(message.groupId),
               message.senderPublicKey != "SYSTEM", !message.senderPublicKey.hasPrefix("bot:"),
               Self.shouldSelfHealRequest(message.groupId),
               let myKey = currentUserPublicKey,
               let framed = GroupWireV2.memberSyncRequest(groupId: message.groupId, requester: myKey) {
                let sender = message.senderPublicKey
                OshiLog.messaging.info("🩹 [self-heal] unknown group \(message.groupId.uuidString.prefix(8)) — requesting definition from sender \(sender.prefix(8))…")
                Task { _ = await MessageManager.shared?.v2SendGroupState(framed, to: [sender]) }
            }
            return
        }

        // 🪦 Stale identity gate — drop group messages from a sender whose
        // public key is in the stale set. Bypass for SYSTEM and bot: senders
        // (those keys are not user identities). Critically, this runs BEFORE
        // the auto-add-as-member block below — otherwise a rotated old key
        // would get re-attached to the group's member list and silently
        // restore delivery for the old key.
        if !message.senderPublicKey.hasPrefix("bot:") && message.senderPublicKey != "SYSTEM" {
            if StaleIdentityManager.shared.isStale(message.senderPublicKey) {
                debugLog("SKIP: stale identity sender=\(message.senderPublicKey.prefix(12))")
                OshiLog.messaging.info("🪦 Group message rejected: sender key is stale (rotated identity)")
                return
            }
        }

        // __BLOCKED_MEMBER_DROP_2026_09_24__ Owner decision: a member I blocked is not
        // shown in groups (WhatsApp-like), on all three platforms. Chosen form: DROP at
        // receive — not stored, so no timeline row, no unread, no preview, no banner, and
        // no typing ping — exactly what Desktop already did. Simpler and sturdier than
        // storing + filtering every reader (timeline, unread, preview, search, export,
        // devsync). Cost: unblocking does not bring back what was said meanwhile.
        // SYSTEM rows (joins/leaves, generated locally) and bots are not members' words
        // and stay; group control (GROUP_UPDATE, keys, roster) never comes through here.
        if !message.senderPublicKey.hasPrefix("bot:") && message.senderPublicKey != "SYSTEM",
           BlockedContactsManager.shared.isBlocked(message.senderPublicKey) {
            debugLog("SKIP: blocked member sender=\(message.senderPublicKey.prefix(12))")
            return
        }

        // Ephemeral typing ping — route to TypingIndicatorManager and never persist as a message.
        if let plain = message.plaintextContent ?? String(data: message.encryptedContent, encoding: .utf8),
           plain.hasPrefix(TypingStatus.messagePrefix),
           let status = TypingStatus.fromMessageString(plain) {
            // Trust the status's groupId if present, else fall back to the envelope's.
            var s = status
            if s.groupId == nil { s.groupId = message.groupId.uuidString }
            TypingIndicatorManager.shared.receiveGroupTypingStatus(s)
            return
        }

        // Skip own messages
        if let myKey = UserDefaults.standard.string(forKey: "publicKey"),
           message.senderPublicKey == myKey {
            debugLog("SKIP: own message")
            OshiLog.messaging.info("⏭️ Skipping own group message")
            return
        }

        // ✅ FIX: Auto-add unknown senders as members for ALL group types.
        // The fact that they have the group key (to encrypt the envelope) proves legitimacy.
        // Previously, private groups rejected non-members, causing messages to be lost when
        // the local member list was out of sync (member added but sync not yet received).
        // The push notification still showed (server has correct list), creating a discrepancy.
        let isBot = message.senderPublicKey.hasPrefix("bot:")
        let isSystem = message.senderPublicKey == "SYSTEM"
        let isMember = group.members.contains(where: { $0.publicKey == message.senderPublicKey })
        // __GROUP_EVICTION_TOMBSTONE_2026_09_10__ (F3) Un membre RETIRÉ qui continue
        // d'émettre ne doit être ni ré-ajouté, ni lu. On laisse tomber son message
        // au lieu de le ré-accepter « puisqu'il a la clé » — il avait la clé quand
        // il était membre; le retrait est justement la décision de ne plus l'être.
        // (Un vrai ré-ajout passe par une ré-invitation explicite, qui le sort de
        // l'ensemble évincé.)
        let senderCanon = MessageManager.canonicalIdentity(message.senderPublicKey)
        let isEvicted = (group.evictedMemberKeys ?? []).contains {
            MessageManager.canonicalIdentity($0) == senderCanon
        }
        if isEvicted && !isBot && !isSystem {
            OshiLog.messaging.info("🚫 Group message dropped: sender \(message.senderPublicKey.prefix(8))… was removed from '\(group.name)' (eviction tombstone)")
            return
        }
        // __GROUP_INFO_SETTINGS_2026_11_20__ §2.4 channel mode: a content message from
        // a non-admin of OUR copy is dropped (not stored, no notification). Reactions
        // and ballots are not posts (§2.3 decides reactions); typing returned above.
        // __GROUP_COMMENTS_2026_11_21__ §2.12.4 runs HERE, at the place of the §2.4
        // filter: a message carrying `commentOn` is decided by the comment rule
        // (accept → comment store, hold → orphan, drop), and only a message the
        // rule calls ordinary falls through to the post filter and the timeline.
        // 🛡 __ADVANCED_CHAT_PRIVACY_2026_11_26__ Any member may turn advanced chat
        // privacy on or off (WhatsApp rule), so this is applied BEFORE the §2.4
        // admins-only post filter and is exempt from it. The row is kept as the
        // centred notice, already read: a settings change is not unread mail.
        var isAdvancedPrivacyNotice = false
        if !isBot && !isSystem, message.mediaType == nil {
            let body = message.plaintextContent ?? String(data: message.encryptedContent, encoding: .utf8) ?? ""
            if applyIncomingAdvancedPrivacy(body, from: message.senderPublicKey, groupId: message.groupId) {
                isAdvancedPrivacyNotice = true
                message.isRead = true
                if message.plaintextContent == nil { message.plaintextContent = body }
            }
        }
        if !isBot && !isSystem && !isAdvancedPrivacyNotice {
            let body = message.plaintextContent ?? (message.mediaType == nil ? String(data: message.encryptedContent, encoding: .utf8) : nil) ?? ""
            if !ReactionPayload.isReactionMessage(body) && !PollVotePayload.isVoteMessage(body) {
                switch commentVerdict(message, sender: message.senderPublicKey, group: group) {
                case .notAComment:
                    break
                case .ordinary:
                    message.commentOn = nil
                case .accept:
                    acceptComment(message, group: group)
                    return
                case .hold:
                    GroupCommentStore.shared.orphans.hold(message, sender: message.senderPublicKey)
                    OshiLog.messaging.info("💬 comment held (orphan): post \(message.commentOn?.prefix(8) ?? "?") not stored yet")
                    return
                case .drop(let why):
                    OshiLog.messaging.info("💬 comment dropped (\(why)) from \(message.senderPublicKey.prefix(8))…")
                    return
                }
                if !GroupSettingsPolicy.mayPost(sender: message.senderPublicKey, group: group) {
                    OshiLog.messaging.info("🔇 Group post dropped: '\(group.name)' is admins-only and \(message.senderPublicKey.prefix(8))… is not an admin")
                    return
                }
            }
        }
        if !isMember && !isBot && !isSystem {
            // Auto-add sender as member — they have the group key so they're legitimate
            OshiLog.messaging.info("🔧 Auto-adding sender \(message.senderPublicKey.prefix(8))... to group '\(group.name)' (member sync pending)")
            if let index = groups.firstIndex(where: { $0.id == message.groupId }) {
                let newMember = GroupMember(publicKey: message.senderPublicKey, alias: nil, joinedAt: Date(), isAdmin: false)
                groups[index].members.append(newMember)
                saveGroups()
                // Notify UI about member list change
                DispatchQueue.main.async {
                    NotificationCenter.default.post(name: NSNotification.Name("GroupUpdated"), object: nil, userInfo: ["groupId": message.groupId.uuidString])
                }
            }
        }

        // Check for duplicate by ID
        if let existingMessages = groupMessages[message.groupId],
           existingMessages.contains(where: { $0.id == message.id }) {
            debugLog("SKIP: duplicate \(message.id.uuidString.prefix(8))")
            OshiLog.messaging.info("⏭️ Group message already exists: \(message.id)")
            return
        }

        // Check for duplicate by content (bot messages may arrive via both VPS poll and push with different IDs)
        if message.senderPublicKey.hasPrefix("bot:"),
           var existingMessages = groupMessages[message.groupId],
           let existingIndex = existingMessages.firstIndex(where: {
               $0.senderPublicKey == message.senderPublicKey &&
               $0.plaintextContent == message.plaintextContent &&
               abs($0.timestamp.timeIntervalSince(message.timestamp)) < 60
           }) {
            // ✅ FIX: If the new version has media but the existing one doesn't,
            // upgrade the existing message (push creates text-only, poll has media).
            let existing = existingMessages[existingIndex]
            if message.mediaType != nil && existing.mediaType == nil {
                debugLog("UPGRADE: replacing text-only bot message with media version")
                OshiLog.messaging.info("🔄 Upgrading bot message with media: \(message.mediaType?.rawValue ?? "?")")
                existingMessages[existingIndex] = GroupMessage(
                    id: existing.id,
                    groupId: existing.groupId,
                    senderPublicKey: existing.senderPublicKey,
                    encryptedContent: message.encryptedContent,
                    timestamp: existing.timestamp,
                    isRead: existing.isRead,
                    messageChainIndex: existing.messageChainIndex,
                    mediaType: message.mediaType,
                    mediaFileName: message.mediaFileName,
                    plaintextContent: existing.plaintextContent
                )
                groupMessages[message.groupId] = existingMessages
                // Save media to disk
                if let mediaType = message.mediaType, let mediaData = message.mediaData, !mediaData.isEmpty {
                    let saved = saveGroupMediaAttachment(mediaData, for: existing.id.uuidString, type: mediaType)
                    OshiLog.messaging.info("💾 Saved upgraded media: \(mediaType.rawValue) (\(mediaData.count / 1024) KB), saved=\(saved)")
                } else if let mediaType = message.mediaType {
                    // Try encryptedContent fallback paths
                    if let contentString = String(data: message.encryptedContent, encoding: .utf8),
                       contentString.contains("\"type\":\"media\""),
                       let jsonData = contentString.data(using: .utf8),
                       let mediaJson = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
                       let base64Content = mediaJson["content"] as? String,
                       let mediaData = Data(base64Encoded: base64Content) {
                        let saved = saveGroupMediaAttachment(mediaData, for: existing.id.uuidString, type: mediaType)
                        OshiLog.messaging.info("💾 Saved upgraded media (JSON extract): \(mediaType.rawValue) (\(mediaData.count / 1024) KB), saved=\(saved)")
                    }
                }
                saveMessages()
                // 🔧 FIX: Notify UI on main thread + post notification so GroupChatView reloads
                let upgradedMessage = existingMessages[existingIndex]
                if Thread.isMainThread {
                    objectWillChange.send()
                    NotificationCenter.default.post(name: NSNotification.Name("NewGroupMessage"), object: upgradedMessage)
                } else {
                    DispatchQueue.main.async {
                        self.objectWillChange.send()
                        NotificationCenter.default.post(name: NSNotification.Name("NewGroupMessage"), object: upgradedMessage)
                    }
                }
                return
            }
            debugLog("SKIP: duplicate bot message by content")
            OshiLog.messaging.info("⏭️ Bot message already exists (content match)")
            return
        }
        
        // 📊 Bulletins de sondage arrivés par le chemin de compatibilité.
        //
        // Ici l'expéditeur vient du JSON et non d'un ratchet ouvert — même
        // niveau de confiance que la réaction juste en dessous, qui lit déjà
        // `payload.senderPublicKey`. Le chemin authentifié est `v2` (§2.3 bis);
        // celui-ci existe pour qu'un bulletin venu d'un client plus ancien ne
        // disparaisse pas en silence.
        let ballotPlaintext = message.plaintextContent ?? String(data: message.encryptedContent, encoding: .utf8) ?? ""
        if PollVotePayload.isVoteMessage(ballotPlaintext) {
            if let vote = PollVotePayload.fromMessageString(ballotPlaintext) {
                let voter = message.senderPublicKey
                Task { @MainActor in PollStore.shared.record(vote: vote, from: voter) }
            }
            return  // Never store as a visible message
        }

        // 🔥 Handle cross-platform reaction messages (from Android or other iOS devices)
        // Format: "🔥REACTION🔥{JSON}" — route to MessageReactionManager, don't store as visible message
        let reactionPlaintext = message.plaintextContent ?? String(data: message.encryptedContent, encoding: .utf8) ?? ""
        if ReactionPayload.isReactionMessage(reactionPlaintext) {
            if let payload = ReactionPayload.fromMessageString(reactionPlaintext),
               GroupSettingsPolicy.acceptsIncomingReaction(isRemove: payload.action == .remove,
                                                           emoji: payload.emoji.rawValue, group: group) {
                MessageReactionManager.shared.processIncomingReaction(payload)
                debugLog("🔥 Processed incoming reaction '\(payload.emoji.rawValue)' on message \(payload.messageId.prefix(8))")
            }
            return  // Never store as a visible message
        }

        // 🗑️ Handle "delete for everyone" system messages
        if message.systemMessageType == .messageDeleted,
           let deletedIdString = message.systemMessageData?.deletedMessageId,
           let deletedId = UUID(uuidString: deletedIdString) {
            // __GROUP_COMMENTS_2026_11_21__ §2.12.4 D: a COMMENT is deleted by its
            // author or a current admin of OUR copy — on this (legacy) leg too.
            if let comment = GroupCommentStore.shared.comment(groupId: message.groupId, id: deletedIdString) {
                if GroupCommentPolicy.mayDelete(actor: message.systemMessageData?.actorPublicKey, comment: comment, group: group) {
                    GroupCommentStore.shared.remove(groupId: message.groupId, id: comment.id)
                    OshiLog.messaging.info("🗑️ comment \(deletedIdString.prefix(8)) deleted for everyone")
                } else {
                    OshiLog.messaging.info("🛑 comment delete refused (not author/admin)")
                }
                return
            }
            GroupCommentStore.shared.markDeleted(groupId: message.groupId, id: deletedIdString)
            // Remove the referenced message from local storage
            if var msgs = groupMessages[message.groupId] {
                let before = msgs.count
                msgs.removeAll { $0.id == deletedId }
                if msgs.count < before {
                    groupMessages[message.groupId] = msgs
                    saveMessages()
                    objectWillChange.send()
                    OshiLog.messaging.info("🗑️ Remote delete-for-everyone: removed message \(deletedIdString.prefix(8))")
                }
            }
            // Don't store the delete system message itself — it's a command, not a visible message
            return
        }

        // ✏️ Handle "edit for everyone" system messages
        if message.systemMessageType == .messageEdited,
           let editedIdString = message.systemMessageData?.editedMessageId,
           let editedId = UUID(uuidString: editedIdString),
           let newContent = message.systemMessageData?.editedContent {
            // __GROUP_COMMENTS_2026_11_21__ edits of a comment: author only.
            if let comment = GroupCommentStore.shared.comment(groupId: message.groupId, id: editedIdString) {
                if let actor = message.systemMessageData?.actorPublicKey,
                   MessageManager.canonicalIdentity(actor) == MessageManager.canonicalIdentity(comment.senderPublicKey) {
                    GroupCommentStore.shared.update(groupId: message.groupId, id: comment.id) {
                        $0.editedAt = message.timestamp
                        $0.editedContent = GroupSettings.truncateUTF16(newContent, max: GroupCommentPolicy.maxCommentUnits)
                    }
                }
                return
            }
            if var msgs = groupMessages[message.groupId],
               let idx = msgs.firstIndex(where: { $0.id == editedId }) {
                msgs[idx].editedAt = message.timestamp
                msgs[idx].editedContent = newContent
                msgs[idx].plaintextContent = newContent
                groupMessages[message.groupId] = msgs
                saveMessages()
                objectWillChange.send()
                OshiLog.messaging.info("✏️ Remote edit-for-everyone: updated message \(editedIdString.prefix(8))")
            }
            return
        }

        // 🔇 Skip bot messages that are just relaying system payloads (links visibility, typing, sync)
        if message.senderPublicKey.hasPrefix("bot:"),
           let plain = message.plaintextContent ?? String(data: message.encryptedContent, encoding: .utf8),
           !plain.isEmpty {
            // Strip bot prefix [BotName] to check actual content
            var contentToCheck = plain
            if plain.hasPrefix("["), let close = plain.firstIndex(of: "]") {
                contentToCheck = String(plain[plain.index(after: close)...].drop(while: { $0 == " " }))
            }
            if contentToCheck.hasPrefix("🔗LINKS_VISIBILITY🔗") ||
               contentToCheck.hasPrefix("🔧ACTION🔧") ||
               contentToCheck.hasPrefix("⌨️TYPING⌨️") ||
               contentToCheck.hasPrefix("{\"type\":\"member_sync") ||
               contentToCheck.hasPrefix("{\"type\":\"sync_request") ||
               contentToCheck.hasPrefix("{\"type\":\"group_update") {
                debugLog("SKIP: bot relaying system payload")
                return
            }
        }

        // 🔔 FIX: Force isRead based on whether this group's chat is open right now.
        // The sender encodes isRead=true (their own copy); the receiver must treat
        // it as unread UNLESS they're currently looking at this group — in which
        // case marking it read here keeps the conversation-list badge from flipping
        // to "unread" for a message the user is actively viewing. (Previously this
        // was an unconditional `isRead = false`, so an open group still showed
        // unread until a later .onChange handler raced to clear it.)
        var incomingMessage = message
        let isThisGroupOpen = (activeGroupId == message.groupId)
        incomingMessage.isRead = isThisGroupOpen

        // Add message — use full dictionary reassignment to guarantee @Published fires
        var messages = groupMessages[message.groupId] ?? []
        messages.append(incomingMessage)
        groupMessages[message.groupId] = messages
        // __GROUP_COMMENTS_2026_11_21__ comments that overtook this post.
        replayHeldComments(groupId: message.groupId, postId: message.id)

        // 📑 __GROUP_RECEIPTS_2026_11_02__ (N8) Tell the SENDER, and only the
        // sender, that their message landed here. Sent at the point the message
        // is actually stored, not when it was decrypted, so "received" means
        // the user can see it rather than that bytes arrived.
        //
        // If the thread is already open the message counts as read in the same
        // breath (`isRead` above says so), and sending both is honest: they are
        // two different facts and the sheet shows them separately.
        if !incomingMessage.isSystemMessage {
            GroupReceipts.send(kind: .received, groupId: incomingMessage.groupId,
                               msgId: incomingMessage.id.uuidString,
                               to: incomingMessage.senderPublicKey)
            if isThisGroupOpen {
                GroupReceipts.send(kind: .read, groupId: incomingMessage.groupId,
                                   msgId: incomingMessage.id.uuidString,
                                   to: incomingMessage.senderPublicKey)
            }
        }

        // __BOT_RUNTIME_OWNS_DELIVERY_2026_08_16__ (étape 2) THE missing half.
        // Bots had exactly one caller — `GroupViews.sendGroupMessage` — so they
        // only ever saw the message their owner had just typed, on a screen that
        // had to be open. Nobody else's `/help` reached a trigger, ever. This is
        // the inbound side; `sendMessage` covers the outbound one. Both live in
        // the manager, which is alive whether or not a view is.
        // `shouldEvaluateTriggers` drops bot output and control payloads, so
        // adding inbound traffic cannot turn two bots into a feedback loop.
        if !incomingMessage.isSystemMessage,
           let botInput = incomingMessage.plaintextContent
               ?? String(data: incomingMessage.encryptedContent, encoding: .utf8) {
            BotManager.shared.processMessage(
                content: botInput,
                groupId: incomingMessage.groupId,
                senderKey: incomingMessage.senderPublicKey,
                // __BOT_IN_THE_PIPELINE_2026_11_09__ a bot reply now arrives under
                // its owner's real key, so `bot:` no longer identifies it here.
                botAuthored: incomingMessage.isBotAuthored
            )
        }
        
        // Update group activity
        if let index = groups.firstIndex(where: { $0.id == message.groupId }) {
            groups[index].lastActivity = message.timestamp
        }
        
        // 🔊 FIX: Save media to disk — check in-memory mediaData first (bot envelope),
        // then try embedded JSON in encryptedContent, then raw encryptedContent as fallback
        if let mediaType = message.mediaType {
            if let directMedia = message.mediaData, !directMedia.isEmpty {
                // Direct media data (e.g., from bot envelope processBotEnvelope()) — save to disk
                let saved = saveGroupMediaAttachment(directMedia, for: message.id.uuidString, type: mediaType)
                OshiLog.messaging.info("💾 Saved direct mediaData: \(mediaType.rawValue) (\(directMedia.count / 1024) KB), saved=\(saved)")
            } else if let contentString = String(data: message.encryptedContent, encoding: .utf8),
               contentString.contains("\"type\":\"media\""),
               let jsonData = contentString.data(using: .utf8),
               let mediaJson = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
               let base64Content = mediaJson["content"] as? String,
               let mediaData = Data(base64Encoded: base64Content) {
                let saved = saveGroupMediaAttachment(mediaData, for: message.id.uuidString, type: mediaType)
                OshiLog.messaging.info("💾 Extracted and saved received media: \(mediaType.rawValue) (\(mediaData.count / 1024) KB), saved=\(saved)")
            } else {
                // Media type set but no embedded JSON — save raw encryptedContent as media
                let saved = saveGroupMediaAttachment(message.encryptedContent, for: message.id.uuidString, type: mediaType)
                OshiLog.messaging.info("💾 Saved raw encryptedContent as media: \(mediaType.rawValue) (\(message.encryptedContent.count / 1024) KB), saved=\(saved)")
            }
        }
        
        OshiLog.messaging.info("✅ Received group message from \(message.senderPublicKey.prefix(8))... in group \(message.groupId)")

        // @MainActor guarantees we're on main thread — fire synchronously
        // so SwiftUI picks up the change immediately
        self.objectWillChange.send()
        NotificationCenter.default.post(
            name: NSNotification.Name("NewGroupMessage"),
            object: incomingMessage
        )

        // Defer disk persistence to avoid blocking UI updates
        // The in-memory groupMessages dict is already updated above (line 2308),
        // so the UI will show the message immediately. Persist asynchronously.
        DispatchQueue.main.async { [self] in
            saveMessages()
            saveGroups()
        }
        
        // 🔔 Trigger local notification (unless group is muted or it's a system message)
        debugLog("Notification check: isSystem=\(message.isSystemMessage), groupFound=\(groups.first(where: { $0.id == message.groupId }) != nil), isMuted=\(groups.first(where: { $0.id == message.groupId })?.isMuted ?? false)")
        // __GROUP_MUTE_BLOCK_IN_NSE_2026_09_23__ Also silent for a BLOCKED group
        // and for a member I blocked — the extension already drops their APNs
        // push (blocked sender key), so without this the local banner was the
        // one path that still rang for them. (Since __BLOCKED_MEMBER_DROP_2026_09_24__
        // a blocked member's message is dropped before this point; the check stays as
        // a second guard.)
        if !message.isSystemMessage,
           // __ADVANCED_CHAT_PRIVACY_2026_11_26__ a quiet notice never rings.
           !OSHIControlPayload.isQuietNotice(message.plaintextContent ?? ""),
           let group = groups.first(where: { $0.id == message.groupId }),
           !group.isMuted,
           !BlockedContactsManager.shared.isGroupBlocked(group.id),
           !BlockedContactsManager.shared.isBlocked(message.senderPublicKey) {
            // Resolve sender display name from group member alias
            let senderName: String
            if let member = group.members.first(where: { $0.publicKey == message.senderPublicKey }),
               let alias = member.alias, !alias.isEmpty {
                senderName = alias
            } else if message.senderPublicKey.hasPrefix("bot:") {
                // __ONE_BANNER_PER_MESSAGE_2026_09_23__ Bot: the key is `bot:<token
                // prefix>` — show the bot's NAME (stored as the "[name] " prefix of
                // the plaintext by processBotEnvelope), never the token.
                if let p = message.plaintextContent, p.hasPrefix("["),
                   let close = p.firstIndex(of: "]"), p.distance(from: p.startIndex, to: close) > 1 {
                    senderName = String(p[p.index(after: p.startIndex)..<close])
                } else {
                    senderName = "Bot"
                }
            } else {
                senderName = String(message.senderPublicKey.prefix(8)) + "..."
            }
            
            // Determine message text for notification
            let messageText: String
            if let plaintext = message.plaintextContent, !plaintext.isEmpty {
                messageText = plaintext
            } else if let mediaType = message.mediaType {
                switch mediaType {
                case .photo: messageText = "📷 Photo"
                case .video: messageText = "🎥 Video"
                case .audio: messageText = "🎤 Audio"
                case .document: messageText = "📎 Document"
                case .contact: messageText = "👤 Contact"
                }
            } else {
                messageText = String(data: message.encryptedContent, encoding: .utf8) ?? "New message"
            }
            
            debugLog("SENDING NOTIFICATION: group=\(group.id.uuidString.prefix(8)) id=\(message.id.uuidString.prefix(8))")
            NotificationManager.shared.sendGroupMessageNotification(
                groupName: group.name,
                senderName: senderName,
                messageText: messageText,
                groupId: group.id.uuidString,
                messageId: message.id.uuidString
            )

            // __ONE_NOTICE_IN_FOREGROUND_2026_11_09__ The foreground notice for a
            // group message. Groups had NO in-app banner: in the foreground the
            // only notice was the system banner, which the delegate now keeps
            // off the screen while the app is active (see
            // `ForegroundNoticePolicy`). Same banner as 1:1, skipped when this
            // group is the one open.
            let groupId = group.id
            let groupName = group.name
            let preview = OSHIControlPayload.previewText(for: messageText)
            DispatchQueue.main.async { [weak self] in
                let openKey = self?.activeGroupId.map { "g:" + $0.uuidString }
                guard ForegroundNoticePolicy.showsInAppBanner(
                    conversationKey: "g:" + groupId.uuidString,
                    openConversationKey: openKey,
                    appActive: UIApplication.shared.applicationState == .active) else { return }
                #if DEBUG
                if ProcessInfo.processInfo.arguments.contains("-OSHIScreens") { return }
                #endif
                DynamicIslandNotifier.shared.showIncoming(sender: groupName,
                                                          preview: senderName + ": " + preview)
            }
        }
    }
    
    // MARK: - Mute / Unmute
    
    func toggleMuteGroup(_ groupId: UUID) {
        guard let index = groups.firstIndex(where: { $0.id == groupId }) else { return }
        // __GROUP_MUTE_SYNC_2026_09_24__ the moment of the user's choice travels to the
        // account's other devices (devsync GROUPS muted/mutedAt, later wins).
        DevSyncIOSStore.recordLocalMuteChange(groupId)
        groups[index].isMuted.toggle()
        saveGroups()
        let state = groups[index].isMuted ? "muted" : "unmuted"
        OshiLog.messaging.info("🔇 Group \(groups[index].name) is now \(state)")
    }
    
    func isGroupMuted(_ groupId: UUID) -> Bool {
        return groups.first(where: { $0.id == groupId })?.isMuted ?? false
    }

    // MARK: - Media Persistence
    
    private func saveGroupMediaAttachment(_ mediaData: Data, for messageId: String, type: MediaType) -> Bool {
        Self.writeGroupMediaFile(mediaData, for: messageId, type: type)
    }

    /// __CHAT_OPEN_PERF_2026_11_14__ The body of `saveGroupMediaAttachment`,
    /// `nonisolated` because it touches no manager state (FileManager +
    /// `MediaCipher`), so `GroupMediaStore` can persist off the main thread.
    nonisolated static func writeGroupMediaFile(_ mediaData: Data, for messageId: String, type: MediaType) -> Bool {
        guard let mediaFile = groupMediaFileURL(for: messageId, type: type) else { return false }
        let mediaDirectory = mediaFile.deletingLastPathComponent()
        let fileManager = FileManager.default
        if !fileManager.fileExists(atPath: mediaDirectory.path) {
            try? fileManager.createDirectory(at: mediaDirectory, withIntermediateDirectories: true)
        }
        do {
            // __MEDIA_AT_REST_2026_09_22__ Chiffré au repos (`MediaCipher`, FileStorage.swift).
            try MediaCipher.write(mediaData, to: mediaFile)
            OshiLog.messaging.info("💾 Saved group media for message: \(messageId) (\(mediaData.count / 1024) KB)")
            return true
        } catch {
            OshiLog.messaging.info("❌ Failed to save group media: \(error)")
            return false
        }
    }
    
    /// Public wrapper for saving media from UI (e.g., extracting embedded base64 from encryptedContent)
    func saveGroupMediaAttachmentPublic(_ mediaData: Data, for messageId: String, type: MediaType) -> Bool {
        return saveGroupMediaAttachment(mediaData, for: messageId, type: type)
    }

    /// URL a group media blob lives at. Single source of truth for the
    /// `<id>.<ext>` naming used by save/load/exists.
    nonisolated static func groupMediaFileURL(for messageId: String, type: MediaType) -> URL? {
        guard let documentsURL = FileManager.default
            .urls(for: .documentDirectory, in: .userDomainMask).first else { return nil }
        let fileExtension: String
        switch type {
        case .photo: fileExtension = "jpg"
        case .video: fileExtension = "mp4"
        case .audio: fileExtension = "m4a"
        case .document: fileExtension = "dat"
        case .contact: fileExtension = "vcf"
        }
        return documentsURL
            .appendingPathComponent("GroupMedia", isDirectory: true)
            .appendingPathComponent("\(messageId).\(fileExtension)")
    }

    /// Is this message's media already on disk? The precondition for trimming the
    /// inlined base64 copy out of `encryptedContent` — we never drop bytes we
    /// cannot get back.
    nonisolated static func groupMediaFileExists(for messageId: String, type: MediaType) -> Bool {
        guard let url = groupMediaFileURL(for: messageId, type: type) else { return false }
        return FileManager.default.fileExists(atPath: url.path)
    }

    /// nonisolated: pure disk read (FileManager + Data(contentsOf:)). No
    /// MainActor state touched, so safe to call from a background queue —
    /// which `GroupMessageBubble.onAppear` does to keep the disk read off the
    /// main thread (was the dominant open-group lag with media-heavy chats).
    nonisolated func loadGroupMediaAttachment(for messageId: String, type: MediaType) -> Data? {
        Self.readGroupMediaFile(for: messageId, type: type)
    }

    /// Static form of `loadGroupMediaAttachment` (it never used the instance).
    nonisolated static func readGroupMediaFile(for messageId: String, type: MediaType) -> Data? {
        let fileManager = FileManager.default
        guard let mediaFile = groupMediaFileURL(for: messageId, type: type) else {
            return nil
        }

        guard fileManager.fileExists(atPath: mediaFile.path) else {
            return nil
        }
        
        do {
            let mediaData = try MediaCipher.read(contentsOf: mediaFile)  // clair hérité accepté
            OshiLog.messaging.info("✅ Loaded group media for message: \(messageId) (\(mediaData.count / 1024) KB)")
            return mediaData
        } catch {
            OshiLog.messaging.info("❌ Failed to load group media: \(error)")
            return nil
        }
    }
    
    func markMessagesAsRead(for groupId: UUID) {
        guard var messages = groupMessages[groupId] else { return }

        // ✅ PERF: short-circuit when nothing is unread. Avoids O(n) copy + Published mutation
        // + saveMessages() (which JSON-encodes EVERY group's messages on the main thread).
        // Re-entry via .onAppear and .onChange used to fire 3-4× per chat open with hundreds
        // of messages — a 200-300 ms hitch each time on large groups.
        var changed = false
        // 📑 __GROUP_RECEIPTS_2026_11_02__ (N8) Collected while walking the list
        // and sent AFTER the guard below, so a no-op open sends nothing. One
        // receipt per message that just flipped, addressed to its own sender.
        var newlyRead: [(msgId: String, sender: String)] = []
        for i in 0..<messages.count {
            if !messages[i].isRead {
                messages[i].isRead = true
                changed = true
                if !messages[i].isSystemMessage {
                    newlyRead.append((messages[i].id.uuidString, messages[i].senderPublicKey))
                }
            }
        }
        guard changed else { return }

        for item in newlyRead {
            GroupReceipts.send(kind: .read, groupId: groupId, msgId: item.msgId, to: item.sender)
        }

        groupMessages[groupId] = messages

        // ✅ PERF: offload the full-map JSON serialization to a background queue.
        // saveMessages() encodes ALL groups (not just this one) — on a large account
        // with thousands of total messages this can block the main thread 100+ ms.
        // The in-memory @Published value is already updated synchronously, so the UI
        // reflects "read" immediately; persistence catches up asynchronously.
        // Snapshot on main (cheap — array values are COW), then encode+write off main.
        // Calling saveMessages() from a global queue is illegal under Swift concurrency
        // (the type is main-actor isolated), so we inline the body with a captured snapshot.
        let snapshot = Dictionary(uniqueKeysWithValues: groupMessages.map { ($0.key.uuidString, $0.value) })
        let key = messagesKey
        DispatchQueue.global(qos: .utility).async {
            FileStorage.shared.save(snapshot, forKey: key)
        }
    }
    
    func getUnreadCount(for groupId: UUID) -> Int {
        return groupMessages[groupId]?.filter { !$0.isRead }.count ?? 0
    }
    
    func getTotalUnreadCount() -> Int {
        return groupMessages.values.flatMap { $0 }.filter { !$0.isRead }.count
    }
    
    /// Clear all groups and messages (used on disconnect/logout)
    func clearAllData() {
        groups.removeAll()
        groupMessages.removeAll()
        GroupCommentStore.shared.clearAll()   // __GROUP_COMMENTS_2026_11_21__
        senderKeys.removeAll()
        saveGroups()
        saveMessages()
        saveSenderKeys()
        OshiLog.messaging.info("🧹 MessageGroupManager: Cleared all groups, messages, and sender keys")
    }

    // MARK: - Identity-Scoped Group Archive

    /// Archive current group data for a specific identity (call before clearing)
    func archiveDataForIdentity(_ publicKey: String) {
        guard !publicKey.isEmpty else { return }
        let safeKey = publicKey.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "+", with: "-")

        if !groups.isEmpty {
            FileStorage.shared.save(groups, forKey: "groups_identity_\(safeKey)")
        }
        if !groupMessages.isEmpty {
            let stringKeyed = Dictionary(uniqueKeysWithValues: groupMessages.map { key, value in
                (key.uuidString, value)
            })
            FileStorage.shared.save(stringKeyed, forKey: "groupMsgs_identity_\(safeKey)")
        }
        if !senderKeys.isEmpty {
            let stringKeyed = Dictionary(uniqueKeysWithValues: senderKeys.map { pk, innerDict in
                let stringInner = Dictionary(uniqueKeysWithValues: innerDict.map { k, v in (k.uuidString, v) })
                return (pk, stringInner)
            })
            FileStorage.shared.save(stringKeyed, forKey: "senderKeys_identity_\(safeKey)")
        }
        OshiLog.messaging.info("📦 Archived \(groups.count) groups for identity \(publicKey.prefix(12))...")
    }

    /// Restore archived group data for a specific identity (call after switching)
    func restoreDataForIdentity(_ publicKey: String) {
        guard !publicKey.isEmpty else { return }
        let safeKey = publicKey.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "+", with: "-")

        if let restoredGroups: [MessageGroup] = FileStorage.shared.load(forKey: "groups_identity_\(safeKey)") {
            groups = restoredGroups
            if let currentKey = currentUserPublicKey {
                for i in 0..<groups.count {
                    groups[i].setCurrentUser(currentKey)
                }
            }
            saveGroups()
        }
        if let stringKeyed: [String: [GroupMessage]] = FileStorage.shared.load(forKey: "groupMsgs_identity_\(safeKey)") {
            groupMessages = Dictionary(uniqueKeysWithValues: stringKeyed.compactMap { key, value -> (UUID, [GroupMessage])? in
                guard let uuid = UUID(uuidString: key) else { return nil }
                return (uuid, value)
            })
            saveMessages()
        }
        if let stringKeyed: [String: [String: SenderKey]] = FileStorage.shared.load(forKey: "senderKeys_identity_\(safeKey)") {
            senderKeys = Dictionary(uniqueKeysWithValues: stringKeyed.map { pk, innerDict -> (String, [UUID: SenderKey]) in
                let uuidDict: [UUID: SenderKey] = Dictionary(uniqueKeysWithValues: innerDict.compactMap { key, value -> (UUID, SenderKey)? in
                    guard let uuid = UUID(uuidString: key) else { return nil }
                    return (uuid, value)
                })
                return (pk, uuidDict)
            })
            saveSenderKeys()
        }
        OshiLog.messaging.info("📥 Restored \(groups.count) groups for identity \(publicKey.prefix(12))...")
    }

    // MARK: - Storage

    func saveGroups() {
        FileStorage.shared.save(groups, forKey: storageKey)
        // 📱 MULTI-DEVICE: Upload groups for other devices to pull
        MultiDeviceSyncManager.shared.uploadGroupsForSync()
        // 📱 MULTI-DEVICE: Retry pending envelopes now that groups may have changed
        MessageManager.shared?.retryPendingGroupEnvelopes()
        publishGroupsSnapshotForNSE()
    }

    /// __GROUP_NAME_IN_NSE_2026_09_23__ The notification extension titles a
    /// group push with the group name looked up by `groupId` in the App Group
    /// (the push carries no name: it must not reach Apple). See
    /// `AppGroupBridge.writeGroupsSnapshot`.
    private func publishGroupsSnapshotForNSE() {
        AppGroupBridge.writeGroupsSnapshot(groups.map { (id: $0.id.uuidString, name: $0.name, muted: $0.isMuted) })
    }
    
    private func loadGroups() {
        // Try to load from FileStorage
        if let loadedGroups: [MessageGroup] = FileStorage.shared.load(forKey: storageKey) {
            self.groups = loadedGroups

            // Set current user on all loaded groups for permission checks
            if let currentKey = currentUserPublicKey {
                for i in 0..<self.groups.count {
                    self.groups[i].setCurrentUser(currentKey)
                }
            }

            OshiLog.messaging.info("✅ Loaded \(loadedGroups.count) group(s)")
            for group in loadedGroups {
                OshiLog.messaging.info("   📁 Group: '\(group.name)' with \(group.members.count) members")
            }
        } else {
            OshiLog.messaging.info("📂 No existing groups found, starting fresh")
            self.groups = []
        }
        publishGroupsSnapshotForNSE()  // __GROUP_NAME_IN_NSE_2026_09_23__ existing installs fill it at launch
    }
    
    private func saveMessages() {
        // Convert UUID keys to String keys for storage
        let stringKeyed = Dictionary(uniqueKeysWithValues: groupMessages.map { key, value in
            (key.uuidString, value.map(Self.compactedForDisk))
        })
        FileStorage.shared.save(stringKeyed, forKey: messagesKey)
    }

    /// 🔧 LAUNCH-FREEZE FIX: a group media message carries the whole file inlined as
    /// base64 inside `encryptedContent`, and `encryptedContent` IS persisted (it is
    /// in CodingKeys). `groupMessages_v2.json` has no cap and no pruning, so it grew
    /// without bound with every photo — and `loadMessages()` reads it with
    /// `Data(contentsOf:)` + `JSONDecoder` from `private init()`, on the main thread,
    /// before the first frame. That is the "hangs worse the more files you exchanged"
    /// report.
    ///
    /// The bytes are already on disk under `GroupMedia/<id>.<ext>` and every display
    /// path reads that file first (`loadGroupMediaAttachment`), falling back to
    /// `encryptedContent` only when the file is missing — so trimming is lossless
    /// PROVIDED the file is actually there. That precondition is checked here; if the
    /// blob is missing the message is persisted untouched.
    ///
    /// Only the PERSISTED copy is trimmed. The in-memory `groupMessages` keeps its
    /// inlined content so the outgoing relay broadcast (which encodes the live
    /// `GroupMessage`) still carries the media.
    private nonisolated static func compactedForDisk(_ message: GroupMessage) -> GroupMessage {
        guard let type = message.mediaType,
              !message.encryptedContent.isEmpty,
              groupMediaFileExists(for: message.id.uuidString, type: type) else { return message }
        var trimmed = message
        trimmed.encryptedContent = Data()
        return trimmed
    }

    /// Public wrapper for multi-device sync to save merged group messages
    func saveGroupMessagesPublic() {
        saveMessages()
    }
    
    private func loadMessages() {
        // Load with String keys, then convert to UUID keys
        guard let stringKeyed: [String: [GroupMessage]] = FileStorage.shared.load(forKey: messagesKey) else {
            return
        }
        
        groupMessages = Dictionary(uniqueKeysWithValues: stringKeyed.compactMap { key, value -> (UUID, [GroupMessage])? in
            guard let uuid = UUID(uuidString: key) else { return nil }
            // Ensure consistent sort order after loading
            return (uuid, value.sorted { $0.timestamp < $1.timestamp })
        })

        // One-shot compaction for stores written before the trim above existed: if
        // anything still carries inlined media, rewrite the file once. Deferred into
        // a Task so the re-encode of every group's messages never runs inside
        // `init()` on the way to the first frame — the whole point of this fix.
        let needsCompaction = groupMessages.values.contains { msgs in
            msgs.contains { msg in
                guard let type = msg.mediaType, !msg.encryptedContent.isEmpty else { return false }
                return Self.groupMediaFileExists(for: msg.id.uuidString, type: type)
            }
        }
        if needsCompaction {
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.saveMessages()
                OshiLog.messaging.info("🗜️ Compacted inlined group media out of \(self.messagesKey)")
            }
        }
    }
    
    private func saveSenderKeys() {
        // Convert nested UUID keys to String keys for storage
        let stringKeyed = Dictionary(uniqueKeysWithValues: senderKeys.map { publicKey, innerDict in
            let stringInnerDict = Dictionary(uniqueKeysWithValues: innerDict.map { key, value in
                (key.uuidString, value)
            })
            return (publicKey, stringInnerDict)
        })
        FileStorage.shared.save(stringKeyed, forKey: senderKeysKey)
    }
    
    private func loadSenderKeys() {
        // Load with String keys, then convert inner UUID keys
        guard let stringKeyed: [String: [String: SenderKey]] = FileStorage.shared.load(forKey: senderKeysKey) else {
            return
        }
        
        senderKeys = Dictionary(uniqueKeysWithValues: stringKeyed.map { publicKey, innerDict -> (String, [UUID: SenderKey]) in
            let uuidDict: [UUID: SenderKey] = Dictionary(uniqueKeysWithValues: innerDict.compactMap { key, value -> (UUID, SenderKey)? in
                guard let uuid = UUID(uuidString: key) else { return nil }
                return (uuid, value)
            })
            return (publicKey, uuidDict)
        })
    }
    
    // MARK: - Helpers
    
    func getLastMessage(for groupId: UUID) -> GroupMessage? {
        return groupMessages[groupId]?.sorted(by: { $0.timestamp > $1.timestamp }).first
    }
    
    func getGroup(byId id: UUID) -> MessageGroup? {
        return groups.first { $0.id == id }
    }
    
    func decryptMessage(_ message: GroupMessage) -> String? {
        // Simple decryption for now - in production use proper encryption
        return String(data: message.encryptedContent, encoding: .utf8)
    }
    
    func leaveGroup(_ groupId: UUID, userPublicKey: String) {
        guard let index = groups.firstIndex(where: { $0.id == groupId }) else { return }
        // __DEVSYNC_LEAVE_2026_09_23__ own-device sync: the leave travels as GROUPS{left, leftAt}
        // with the time the user actually left (DevSyncIOSStore.groups reads it back).
        DevSyncIOSStore.recordLocalLeave(groupId)

        // 1. Remove self from member list
        groups[index].members.removeAll { $0.publicKey == userPublicKey }
        groups[index].lastActivity = Date()
        saveGroups()

        // 2. System message for remaining members (voluntary leave, not kicked)
        addSystemMessage(to: groupId, type: .memberLeft, byPublicKey: userPublicKey)

        // 3. Broadcast updated group to remaining members BEFORE local cleanup.
        //    `notifyMembers: false`: l'etape 5 notifie, avec le motif `member_left`.
        // __GROUP_E2E_V2_2026_09_23__ §5.2 leave: `member_removed` naming OURSELVES to every
        // other member (accepted whatever the group type), after the optional definition.
        broadcastFullGroupUpdate(group: groups[index], notifyMembers: false,
                                 companions: GroupWireV2.memberRemoved(groupId: groupId, member: userPublicKey)
                                    .map { [($0, groups[index].members.map { $0.publicKey })] } ?? [])

        // 4. Re-register bots so leaving member stops receiving bot messages
        Task { @MainActor in
            BotManager.shared.syncBotsForGroup(groupId)
        }

        // 5. Push fallback: notify remaining members
        notifyMembersOfGroupChange(group: groups[index], changeType: "member_left")

        // 6. ✅ FIX: Remove group from local storage — prevents zombie group
        groups.removeAll { $0.id == groupId }
        groupMessages.removeValue(forKey: groupId)
        GroupCommentStore.shared.forget(groupId)   // __GROUP_COMMENTS_2026_11_21__
        saveGroups()
        saveMessages()

        // 7. Notify UI to refresh group list
        DispatchQueue.main.async {
            NotificationCenter.default.post(
                name: NSNotification.Name("GroupUpdated"),
                object: nil,
                userInfo: ["groupId": groupId.uuidString, "deleted": true]
            )
        }

        OshiLog.messaging.info("👋 Left group and removed from local storage")
    }
    
    func markGroupAsRead(_ groupId: UUID) {
        markMessagesAsRead(for: groupId)
        // __ANATOLE_1311_IOS__ (N10) the group's banners go when it is read.
        NotificationManager.shared.clearDeliveredNotifications(forGroup: groupId.uuidString)
        // The icon badge counts groups as well as 1:1 (see
        // MessageManager.syncAppBadge). Without this the number stayed put after
        // reading a group — the count was right, nothing recomputed it.
        DispatchQueue.main.async {
            MessageManager.shared?.syncAppBadge()
        }
    }
}

// MARK: - Group Picture Update Payload

struct GroupPictureUpdate: Codable {
    let groupId: UUID
    let pictureData: Data?
    let updatedAt: Date
    let updatedBy: String
}

// MARK: - AnyCodableValue (for robust per-member fallback decoding)

/// Lightweight wrapper to decode heterogeneous JSON values for fallback member parsing
struct AnyCodableValue: Codable {
    let value: Any

    var stringValue: String? { value as? String }
    var boolValue: Bool? { value as? Bool }
    var doubleValue: Double? { value as? Double }
    var dateValue: Date? {
        // Try double (default JSONEncoder date format)
        if let d = value as? Double { return Date(timeIntervalSinceReferenceDate: d) }
        // Try ISO 8601 string (FileStorage format)
        if let s = value as? String {
            let f = ISO8601DateFormatter()
            return f.date(from: s)
        }
        return nil
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let s = try? container.decode(String.self) { value = s }
        else if let b = try? container.decode(Bool.self) { value = b }
        else if let d = try? container.decode(Double.self) { value = d }
        else if let i = try? container.decode(Int.self) { value = i }
        else { value = "" }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        if let s = value as? String { try container.encode(s) }
        else if let b = value as? Bool { try container.encode(b) }
        else if let d = value as? Double { try container.encode(d) }
        else if let i = value as? Int { try container.encode(i) }
        else { try container.encode("") }
    }
}

// MARK: - Group System Message Types

enum GroupSystemMessageType: String, Codable {
    case memberJoined = "member_joined"
    case memberLeft = "member_left"
    case memberRemoved = "member_removed"
    case groupCreated = "group_created"
    case groupNameChanged = "group_name_changed"
    case groupPictureChanged = "group_picture_changed"
    case groupPictureRemoved = "group_picture_removed"
    case adminChanged = "admin_changed"
    case messagePinned = "message_pinned"
    case messageUnpinned = "message_unpinned"
    case wallpaperChanged = "wallpaper_changed"
    case messageDeleted = "message_deleted"
    case messageEdited = "message_edited"
    /// __GROUP_INFO_SETTINGS_2026_11_20__ A LOCAL informational row (welcome
    /// message §2.5, "an admin deleted this group" §2.9). Never sent: its text is
    /// in `plaintextContent`, which is what the live system bubble draws.
    case groupInfo = "group_info"

    var localizedDescription: String {
        switch self {
        case .memberJoined: return "group.system.member_joined".localized
        case .memberLeft: return "group.system.member_left".localized
        case .memberRemoved: return "group.system.member_removed".localized
        case .groupCreated: return "group.system.created".localized
        case .groupNameChanged: return "group.system.name_changed".localized
        case .groupPictureChanged: return "group.system.picture_changed".localized
        case .groupPictureRemoved: return "group.system.picture_removed".localized
        case .adminChanged: return "group.system.admin_changed".localized
        case .messagePinned: return "group.system.message_pinned".localized
        case .messageUnpinned: return "group.system.message_unpinned".localized
        case .wallpaperChanged: return "group.system.wallpaper_changed".localized
        case .messageDeleted: return "group.system.message_deleted".localized
        case .messageEdited: return "group.system.message_edited".localized
        case .groupInfo: return ""
        }
    }

    var icon: String {
        switch self {
        case .memberJoined: return "person.badge.plus"
        case .memberLeft: return "person.badge.minus"
        case .memberRemoved: return "person.fill.xmark"
        case .groupCreated: return "sparkles"
        case .groupNameChanged: return "pencil"
        case .groupPictureChanged: return "photo"
        case .groupPictureRemoved: return "photo.badge.minus"
        case .adminChanged: return "crown"
        case .messagePinned: return "pin.fill"
        case .messageUnpinned: return "pin.slash"
        case .wallpaperChanged: return "paintbrush.fill"
        case .messageDeleted: return "trash"
        case .messageEdited: return "pencil"
        case .groupInfo: return "info.circle"
        }
    }
}

struct GroupSystemMessageData: Codable {
    let actorPublicKey: String  // Who performed the action
    let targetPublicKey: String?  // Who was affected (for member actions)
    var deletedMessageId: String?  // For messageDeleted: the ID of the deleted message
    var editedMessageId: String?  // For messageEdited: the ID of the edited message
    var editedContent: String?    // For messageEdited: new content
}

// MARK: - __GROUP_E2E_V2_2026_09_23__ Wire codec (docs/GROUP_E2E_V2_SPEC.md)

/// The pure half of the group v2 protocol: what goes into a ratchet plaintext, and what an
/// inbound one is allowed to be. No I/O, no state — the unit tests run it against
/// `docs/fixtures/group_e2e_v2/vectors.json`.
///
/// Rule zero of the spec: a group byte leaves the device ONLY inside a pairwise v2 envelope.
/// Content (text, reactions, typing, edit/delete, media) rides `type:"group"` envelopes as
/// `base64(GroupMessage JSON)`; state rides `type:"1to1"` envelopes as `📢GROUP_UPDATE📢`+JSON.
///
/// __LEGACY_COMPAT_2026_09_23__ Transition window (owner decision 2026-09-23): until
/// 2026-10-14 09:00 Paris, members on OLD builds (iOS ≤ 143, Android ≤ 1.6.25) must keep
/// receiving group traffic. While `/v2/config.group_legacy_compat == true`, the sender ALSO
/// uses the pre-v2 path (groupId-keyed envelope on IPFS + `/api/queue`, `group_update` pins)
/// for the members NOT proven v2-group-capable. v2 sends are unchanged. Absent / false ⇒ v2
/// only. After `sunset` the flag is IGNORED (the config is unsigned: a server that turned it
/// back on later must not be able to downgrade anyone).
enum GroupLegacyCompat {

    /// 2026-10-14T07:00:00Z = 09:00 Europe/Paris, the server-side flip.
    static let sunset = Date(timeIntervalSince1970: 1_791_961_200)

    /// Pure decision: flag strictly `true` AND before the sunset.
    static func isActive(remote: Bool?, now: Date = Date()) -> Bool {
        remote == true && now < sunset
    }

    /// Live value from the cached `/v2/config` (no cache ⇒ key absent ⇒ false).
    static var active: Bool { isActive(remote: V2FeatureFlag.cachedRemote().group_legacy_compat) }

    // MARK: Capability proof

    private static let proofKey = "oshi_group_v2_capable_peers"
    private static let proofCap = 1000

    /// Peers that sent us an authenticated v2 group DEFINITION (`📢GROUP_UPDATE📢` over a v2
    /// 1:1 ratchet, carrying `stateVersion`). Old builds never did: iOS ≤ 143 sent definitions
    /// over IPFS only (its v2 `sync_request` answer is the stateVersion-less `created` form),
    /// Android ≤ 1.6.25 over the legacy 1:1 IPFS envelope.
    static func provenPeers(_ defaults: UserDefaults = .standard) -> Set<String> {
        Set((defaults.array(forKey: proofKey) as? [String]) ?? [])
    }

    static func recordV2StateProof(peer: String, json: String, defaults: UserDefaults = .standard) {
        guard !peer.isEmpty, let d = json.data(using: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any],
              obj["stateVersion"] != nil else { return }
        let c = MessageManager.canonicalIdentity(peer)
        var list = (defaults.array(forKey: proofKey) as? [String]) ?? []
        guard !list.contains(c) else { return }
        list.append(c)
        if list.count > proofCap { list.removeFirst(list.count - proofCap) }
        defaults.set(list, forKey: proofKey)
    }

    /// v2-group-capable iff the prekey bundle announced `Caps.groupV2`, or a proof was seen.
    /// Unknown ⇒ NOT capable (gets the legacy copy; new receivers dedup it by message id).
    static func isCapable(_ member: String,
                          capsOf: (String) -> Int = { RatchetHealSignal.Caps.of(peer: $0) },
                          proven: Set<String>) -> Bool {
        let c = MessageManager.canonicalIdentity(member)
        let caps = capsOf(member) | (c == member ? 0 : capsOf(c))
        return RatchetHealSignal.Caps.has(caps, RatchetHealSignal.Caps.groupV2) || proven.contains(c)
    }

    /// Pure: the members that must also get the legacy path.
    static func legacyTargets(_ members: [String], active: Bool,
                              capsOf: (String) -> Int = { RatchetHealSignal.Caps.of(peer: $0) },
                              proven: Set<String>) -> [String] {
        guard active else { return [] }
        return members.filter { !isCapable($0, capsOf: capsOf, proven: proven) }
    }

    /// Live: `[]` whenever compat is off (⇒ today's v2-only behaviour, byte for byte).
    static func legacyTargets(_ members: [String]) -> [String] {
        legacyTargets(members, active: active, proven: provenPeers())
    }

    // MARK: __LEAK_FIX_2026_09_25__ audit B — no readable duplicate for a member v2 reached

    /// Every build that advertises `groupEnvelopeV3` in its profile (iOS ≥ 1.0.43, Android
    /// ≥ 1.6.1x — all from 2026-09-04) also reads v2 `type:"group"` envelopes (iOS
    /// `v2DeliverGroup` since 2026-07-05, Android `dispatchV2GroupText` since 1.6.3) and
    /// `📢GROUP_UPDATE📢` over a v2 1:1 (measured in git 4befbea0 = 1.0.43 and in 48e60b83^).
    /// Unknown ⇒ false ⇒ the member keeps its legacy copy.
    static func readsV2Groups(_ member: String) -> Bool {
        let store = PeerCapabilityStore.shared
        return store.supportsGroupV3(member) || store.supportsGroupV3(MessageManager.canonicalIdentity(member))
    }

    /// __GROUP_FANOUT_2026_11_24__ Pure: drop the members the v2 send left out only because the relay
    /// rate-limited us. They read v2 groups; the message is simply not out to them yet.
    static func withoutRateLimited(_ legacy: [String], rateLimited: Set<String>) -> [String] {
        guard !rateLimited.isEmpty, !legacy.isEmpty else { return legacy }
        let limited = Set(rateLimited.map { MessageManager.canonicalIdentity($0) })
        return legacy.filter { !limited.contains(MessageManager.canonicalIdentity($0)) }
    }

    /// Pure: which `candidates` still need a legacy copy once the v2 send reached `v2Reached`.
    static func stillNeedLegacy(_ candidates: [String], v2Reached: Set<String>,
                                readsV2Groups: (String) -> Bool = GroupLegacyCompat.readsV2Groups) -> [String] {
        guard !candidates.isEmpty else { return [] }
        let reached = Set(v2Reached.map { MessageManager.canonicalIdentity($0) })
        var seen = Set<String>()
        return candidates.filter { k in
            let c = MessageManager.canonicalIdentity(k)
            guard seen.insert(c).inserted else { return false }
            return !(reached.contains(c) && readsV2Groups(k))
        }
    }
}

enum GroupWireV2 {

    static let groupUpdatePrefix = "\u{1F4E2}GROUP_UPDATE\u{1F4E2}"
    /// Legacy IPFS group uploaders (`CloudGroupService`, `IPFSGroupUploader`) stay compiled for
    /// their RECEIVE side only; their send handlers check this and refuse.
    /// __LEGACY_COMPAT_2026_09_23__ …except during the transition window: open only while
    /// `GroupLegacyCompat.active` (server flag AND before the compiled-in sunset).
    static var legacyGroupUploadsAllowed: Bool { GroupLegacyCompat.active }
    /// §5.1: the framed state plaintext must stay under this (relay cap 256 KiB of base64 ciphertext).
    static let maxStateBytes = 180_000
    /// §2.5: typing is skipped for groups larger than this.
    static let typingMaxMembers = 20

    // MARK: Content channel (§2)

    /// `ballot` est un bulletin de sondage: comme une réaction, il ne réveille
    /// personne, ne fait pas de copie vers nos autres appareils et n'est pas une
    /// ligne du fil — mais il n'est pas une réaction, et le nommer ainsi ferait
    /// mentir les trois tests qui lisent ce type.
    enum BodyKind: Equatable { case user, reaction, typing, command, ballot }

    /// §2.5: which kinds wake the recipient and get a self copy.
    static func kind(of message: GroupMessage) -> BodyKind {
        if message.senderPublicKey == "SYSTEM" { return .command }
        let body = message.plaintextContent ?? String(data: message.encryptedContent, encoding: .utf8) ?? ""
        if body.hasPrefix(TypingStatus.messagePrefix) { return .typing }
        if ReactionPayload.isReactionMessage(body) { return .reaction }
        if PollVotePayload.isVoteMessage(body) { return .ballot }
        return .user
    }

    /// §2.2: the ratchet plaintext of a content envelope — base64 of the GroupMessage JSON with
    /// the Android compatibility keys and without the sender-local ones.
    static func encodeContent(_ message: GroupMessage) -> String? {
        guard let data = try? JSONEncoder().encode(message),
              var obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        obj.removeValue(forKey: "deliveryStatus")
        obj.removeValue(forKey: "editedAt")
        obj.removeValue(forKey: "editedContent")
        obj["id"] = message.id.uuidString
        obj["groupId"] = message.groupId.uuidString
        let isSystem = message.senderPublicKey == "SYSTEM"
        obj["isRead"] = isSystem
        obj["messageChainIndex"] = 0
        if !isSystem {
            obj["messageId"] = message.id.uuidString
            obj["content"] = message.mediaType == nil
                ? (String(data: message.encryptedContent, encoding: .utf8) ?? "")
                : ""
            obj["senderName"] = ""
            // §2.2: `plaintextContent` is the caption, media only.
            if message.mediaType == nil { obj.removeValue(forKey: "plaintextContent") }
        } else {
            obj.removeValue(forKey: "plaintextContent")
        }
        guard let out = try? JSONSerialization.data(withJSONObject: obj, options: [.withoutEscapingSlashes]) else { return nil }
        return out.base64EncodedString()
    }

    enum ContentDecision: Equatable {
        case accept
        case drop(String)
    }

    /// §3 steps 2-4 (+ the reactor rule of §2.3) against an already decoded message.
    static func check(_ message: GroupMessage, envelopeFrom: String, envelopeGroupId: String) -> ContentDecision {
        guard message.groupId.uuidString.caseInsensitiveCompare(envelopeGroupId) == .orderedSame else {
            return .drop("groupId-mismatch")
        }
        let from = MessageManager.canonicalIdentity(envelopeFrom)
        if message.senderPublicKey == "SYSTEM" {
            guard let type = message.systemMessageType, type == .messageEdited || type == .messageDeleted else {
                return .drop("system-kind-not-a-command")
            }
            guard let actor = message.systemMessageData?.actorPublicKey,
                  MessageManager.canonicalIdentity(actor) == from else {
                return .drop("system-actor-mismatch")
            }
            return .accept
        }
        guard MessageManager.canonicalIdentity(message.senderPublicKey) == from else {
            return .drop("sender-mismatch")
        }
        // Reactions: the reactor is the envelope `from`, never the JSON field — the receiver
        // substitutes it (`MessageGroupManager.receiveV2GroupMessage`), so no check here.
        let body = message.plaintextContent ?? String(data: message.encryptedContent, encoding: .utf8) ?? ""
        if body.hasPrefix(TypingStatus.messagePrefix),
           let t = TypingStatus.fromMessageString(body),
           MessageManager.canonicalIdentity(t.senderPublicKey) != from {
            return .drop("typist-mismatch")
        }
        return .accept
    }

    /// §3 step 2: base64 → GroupMessage, anything else is dropped (never rendered).
    static func decodeContent(_ plaintext: String) -> GroupMessage? {
        guard let data = Data(base64Encoded: plaintext) else { return nil }
        return try? JSONDecoder().decode(GroupMessage.self, from: data)
    }

    /// §2.4: may `actor` apply an edit/delete of `target` in `group`?
    static func commandAllowed(_ message: GroupMessage, target: GroupMessage?, group: MessageGroup) -> Bool {
        guard let actor = message.systemMessageData?.actorPublicKey else { return false }
        let a = MessageManager.canonicalIdentity(actor)
        let isAuthor = target.map { MessageManager.canonicalIdentity($0.senderPublicKey) == a } ?? false
        switch message.systemMessageType {
        case .messageEdited?:
            return isAuthor
        case .messageDeleted?:
            let isAdmin = group.members.contains { MessageManager.canonicalIdentity($0.publicKey) == a && $0.isAdmin }
            return isAuthor || isAdmin
        default:
            return false
        }
    }

    // MARK: State channel (§5)

    /// §5.3: nil on either side ⇒ no version check; otherwise incoming must not be older.
    static func versionAccepts(local: Int?, incoming: Int?) -> Bool {
        guard let local, let incoming else { return true }
        return incoming >= local
    }

    /// §5.1: the framed definition. Blobs are the caller's choice (at most one); if the result
    /// is over `maxStateBytes` it is re-encoded without any blob rather than failing.
    static func framedDefinition(_ group: MessageGroup) -> String? {
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .iso8601
        var g = group
        g.isMuted = false
        guard let data = try? enc.encode(g), let json = String(data: data, encoding: .utf8) else { return nil }
        let framed = groupUpdatePrefix + json
        if framed.utf8.count <= maxStateBytes { return framed }
        g.groupPictureData = nil; g.groupPictureUpdatedAt = nil; g.groupPictureUpdatedBy = nil
        g.groupWallpaperData = nil; g.groupWallpaperUpdatedAt = nil; g.groupWallpaperUpdatedBy = nil
        guard let small = try? enc.encode(g), let smallJson = String(data: small, encoding: .utf8) else { return nil }
        return groupUpdatePrefix + smallJson
    }

    /// §5.2 companions (minimal Android form).
    static func framedCompanion(_ obj: [String: Any]) -> String? {
        guard let data = try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys, .withoutEscapingSlashes]),
              let json = String(data: data, encoding: .utf8) else { return nil }
        return groupUpdatePrefix + json
    }

    static func memberAdded(groupId: UUID, member: String) -> String? {
        framedCompanion(["type": "member_added", "groupId": groupId.uuidString, "memberPublicKey": member])
    }
    static func memberRemoved(groupId: UUID, member: String) -> String? {
        framedCompanion(["type": "member_removed", "groupId": groupId.uuidString, "memberPublicKey": member])
    }
    static func groupRenamed(groupId: UUID, name: String) -> String? {
        framedCompanion(["type": "group_renamed", "groupId": groupId.uuidString, "name": name])
    }
    static func memberSyncRequest(groupId: UUID, requester: String, inviteToken: String? = nil) -> String? {
        var o: [String: Any] = ["type": "member_sync_request", "groupId": groupId.uuidString, "requesterPublicKey": requester]
        // __GROUP_INFO_SETTINGS_2026_11_20__ §3.2: the private link's `t`, when it had one.
        if let t = GroupSettings.validInviteToken(inviteToken) { o["inviteToken"] = t }
        return framedCompanion(o)
    }

    // __GROUP_SELF_HEAL_2026_10_01__ "send me EVERY group I am a member of" — no groupId needed.
    //
    // __SYNC_REQUEST_FIELD_NAME_2026_10_19__ The field is `requesterKey`.
    // It was written as `requesterPublicKey` — the spelling `member_sync_request`
    // uses — and NOTHING reads that on this message: the iOS handler wants
    // `requesterKey` (and bailed: "sync_request missing requesterKey"), and so
    // does Android's `handleSyncRequest`. So self-heal was dropped on arrival by
    // every iOS/macOS peer since the day it was added; Android only coped because
    // its reader defaults to the authenticated sender.
    //
    // Measured: a Mac added to four groups from an iPhone had NO relay queue at
    // all, and asking for a resync could not fix it because the ask was discarded.
    // Do not "align" this with member_sync_request — the two messages genuinely
    // use different spellings on the wire, and the readers are what define them.
    static func syncRequest(requester: String) -> String? {
        framedCompanion(["type": "sync_request", "requesterKey": requester])
    }

    // MARK: Self copies (§7.2)

    static func groupSentCopyPayload(groupId: UUID, message: String, msgId: String, sentAt: Date) -> Data? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        let obj: [String: Any] = [
            "kind": "sent-copy", "v": 1,
            "conversation": ["type": "group", "groupId": groupId.uuidString],
            "message": message, "msgId": msgId,
            "sentAt": f.string(from: sentAt),
        ]
        return try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys, .withoutEscapingSlashes])
    }

    struct GroupSentCopy: Equatable {
        let groupId: String
        let message: String
        let msgId: String
    }

    static func parseGroupSentCopy(_ text: String) -> GroupSentCopy? {
        guard text.hasPrefix("{"), text.contains("\"sent-copy\""),
              let obj = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              obj["kind"] as? String == "sent-copy",
              let conv = obj["conversation"] as? [String: Any], conv["type"] as? String == "group",
              let gid = conv["groupId"] as? String, !gid.isEmpty,
              let message = obj["message"] as? String,
              let msgId = obj["msgId"] as? String, !msgId.isEmpty else { return nil }
        return GroupSentCopy(groupId: gid, message: message, msgId: msgId)
    }
}


// MARK: - __GROUP_E2E_V2_2026_09_23__ v2 receive side (spec §3, §5, §7.2)

extension MessageGroupManager {

    /// C.16 tombstone: this account LEFT the group (on this or another own device) and has not
    /// rejoined since. An incoming definition must not walk us back in.
    nonisolated static func hasLeftTombstone(_ groupId: UUID) -> Bool {
        guard let left = DevSyncIOSStore.localLeaveAt(groupId.uuidString) else { return false }
        return (DevSyncIOSStore.localJoinAt(groupId.uuidString) ?? 0) < left
    }

    /// §3 — a `type:"group"` content envelope, already base64+JSON decoded.
    func receiveV2GroupMessage(_ message: GroupMessage, from: String, envelopeGroupId: String) {
        if case .drop(let why) = GroupWireV2.check(message, envelopeFrom: from, envelopeGroupId: envelopeGroupId) {
            CallFileLogger.shared.log("DIAG_GROUPV2_DROP | why=\(why) | from=\(from.prefix(12))")
            OshiLog.messaging.info("🛑 [V2] group content dropped (\(why)) from \(from.prefix(8))…")
            return
        }
        let fromCanon = MessageManager.canonicalIdentity(from)
        let group = groups.first(where: { $0.id == message.groupId })
        if let group, (group.evictedMemberKeys ?? []).contains(where: { MessageManager.canonicalIdentity($0) == fromCanon }) {
            OshiLog.messaging.info("🚫 [V2] group content from a removed member dropped")
            return
        }
        // §2.3 bis — bulletins de sondage: le votant est le `from` de
        // l'enveloppe, exactement comme le réacteur ci-dessous. C'est le chemin
        // AUTHENTIFIÉ: `from` est la clé qui a ouvert le ratchet, donc un
        // membre ne peut pas voter au nom d'un autre.
        if !message.isSystemMessage {
            let body = message.plaintextContent ?? String(data: message.encryptedContent, encoding: .utf8) ?? ""
            if PollVotePayload.isVoteMessage(body) {
                guard group != nil, let vote = PollVotePayload.fromMessageString(body) else { return }
                Task { @MainActor in PollStore.shared.record(vote: vote, from: from) }
                return
            }
        }

        // §2.3 reactions: the reactor is the envelope `from`, whatever the JSON says.
        if !message.isSystemMessage {
            let body = message.plaintextContent ?? String(data: message.encryptedContent, encoding: .utf8) ?? ""
            if ReactionPayload.isReactionMessage(body) {
                guard group != nil, let r = ReactionPayload.fromMessageString(body) else { return }
                // __GROUP_INFO_SETTINGS_2026_11_20__ §2.3 reactions allowed (OUR copy).
                guard GroupSettingsPolicy.acceptsIncomingReaction(isRemove: r.action == .remove,
                                                                  emoji: r.emoji.rawValue, group: group) else {
                    OshiLog.messaging.info("🚫 [V2] reaction \(r.emoji.rawValue) not allowed in this group — dropped")
                    return
                }
                MessageReactionManager.shared.processIncomingReaction(ReactionPayload(
                    messageId: r.messageId, emoji: r.emoji, senderPublicKey: from,
                    senderName: r.senderName, timestamp: r.timestamp, action: r.action))
                return
            }
        }
        // §2.4 commands: author (edit) or author/admin (delete), and only for a group we hold.
        if message.isSystemMessage {
            guard let group else { return }
            let targetId = message.systemMessageData?.editedMessageId ?? message.systemMessageData?.deletedMessageId ?? ""
            // __GROUP_COMMENTS_2026_11_21__ the target may be a comment (rule D).
            let target = groupMessages[group.id]?.first { $0.id.uuidString.caseInsensitiveCompare(targetId) == .orderedSame }
                ?? GroupCommentStore.shared.comment(groupId: group.id, id: targetId)
            guard GroupWireV2.commandAllowed(message, target: target, group: group) else {
                OshiLog.messaging.info("🛑 [V2] group command refused (not author/admin)")
                return
            }
        }
        // __GROUP_SELF_HEAL_2026_10_01__ A USER message for a group we do NOT hold
        // locally (reaction/system cases already returned above). Ask the AUTHENTICATED
        // envelope sender `from` — the routable v2 user key — for the group definition,
        // so the group is recreated (handleIncomingGroupUpdate) and this message, which
        // receiveGroupMessage is about to orphan-queue, then replays. This is the real
        // recovery path after a wipe: the device is still a member server-side, so each
        // group's next message triggers one definition request. The orphan-branch
        // self-heal in receiveGroupMessage used message.senderPublicKey, which on the v2
        // path is not routable — `from` is. Throttled per group (shared with that branch).
        if group == nil, !from.isEmpty,
           !Self.hasLeftTombstone(message.groupId),
           Self.shouldSelfHealRequest(message.groupId),
           let myKey = currentUserPublicKey,
           let framed = GroupWireV2.memberSyncRequest(groupId: message.groupId, requester: myKey) {
            OshiLog.messaging.info("🩹 [self-heal v2] unknown group \(message.groupId.uuidString.prefix(8)) — asking \(from.prefix(8))… for the definition")
            Task { _ = await MessageManager.shared?.v2SendGroupState(framed, to: [from]) }
        }
        receiveGroupMessage(message)
    }

    /// §7.2 — our own group message sent from another of our devices: inserted as OUTGOING.
    func receiveV2GroupSentCopy(_ copy: GroupWireV2.GroupSentCopy, me: String) {
        guard let decoded = GroupWireV2.decodeContent(copy.message) else { return }
        // __GROUP_COMMENTS_2026_11_21__ `var`: an ignored `commentOn` is stripped below.
        var m = decoded
        guard m.groupId.uuidString.caseInsensitiveCompare(copy.groupId) == .orderedSame,
              MessageManager.canonicalIdentity(m.senderPublicKey) == MessageManager.canonicalIdentity(me),
              GroupWireV2.kind(of: m) == .user,
              let gi = groups.firstIndex(where: { $0.id == m.groupId }) else { return }
        if groupMessages[m.groupId]?.contains(where: { $0.id == m.id }) == true { return }
        // __GROUP_COMMENTS_2026_11_21__ my comment sent from my other device (§2.12.3 self copy).
        if m.commentOn != nil {
            var own = m
            own.isRead = true
            own.deliveryStatus = .sent
            if own.plaintextContent == nil { own.plaintextContent = String(data: own.encryptedContent, encoding: .utf8) }
            switch commentVerdict(own, sender: me, group: groups[gi], countFlood: false) {
            case .accept:
                GroupCommentStore.shared.insert(GroupCommentPolicy.truncatedBody(own))
                return
            case .hold:
                GroupCommentStore.shared.orphans.hold(own, sender: me)
                return
            case .drop:
                return
            case .ordinary, .notAComment:
                m.commentOn = nil
            }
        }
        var out = m
        out.isRead = true
        out.deliveryStatus = .sent
        if out.mediaType == nil, out.plaintextContent == nil {
            out.plaintextContent = String(data: out.encryptedContent, encoding: .utf8)
        }
        // 🛡 __ADVANCED_CHAT_PRIVACY_2026_11_26__ my own switch, flipped on my other device.
        if out.mediaType == nil, let text = out.plaintextContent {
            applyIncomingAdvancedPrivacy(text, from: me, groupId: m.groupId)
        }
        var list = groupMessages[m.groupId] ?? []
        list.append(out)
        groupMessages[m.groupId] = list
        replayHeldComments(groupId: m.groupId, postId: m.id)   // __GROUP_COMMENTS_2026_11_21__
        groups[gi].lastActivity = Date()
        saveMessages()
        saveGroups()
        objectWillChange.send()
        NotificationCenter.default.post(name: NSNotification.Name("NewGroupMessage"), object: out)
    }

    /// §5 — a `📢GROUP_UPDATE📢` body from the v2 state channel (prefix already stripped).
    func receiveV2GroupState(_ json: String, from: String) {
        guard let data = json.data(using: .utf8) else { return }
        CallFileLogger.shared.log("DIAG_GROUPV2_STATE_RX | from=\(from.prefix(12)) | bytes=\(data.count)")
        // __LEGACY_COMPAT_2026_09_23__ a versioned definition over v2 proves a v2-group build.
        GroupLegacyCompat.recordV2StateProof(peer: from, json: json)
        handleIncomingGroupUpdate(data: data, from: from, authenticated: true)
    }

    /// `member_removed` (companion or leave), applied locally — never re-broadcast.
    /// §5.3 rule 4: naming the sender itself (a leave) is always accepted.
    func applyRemoteMemberRemoved(groupId: UUID, member: String, sender: String?) {
        guard let index = groups.firstIndex(where: { $0.id == groupId }) else { return }
        let target = MessageManager.canonicalIdentity(member)
        let isLeave = sender.map { MessageManager.canonicalIdentity($0) == target } ?? false
        // __UNAUTHENTICATED_GROUP_UPDATE_2026_11_20__ A removal from nobody used to
        // skip the check entirely — anyone who knew the group UUID could evict any
        // member, tombstone them for good, or (targeting us) delete the group here.
        if !isLeave {
            let d = MessageGroupManager.authorizeGroupUpdate(current: groups[index], incoming: groups[index], sender: sender)
            guard d.apply, !d.keepMembership else {
                OshiLog.messaging.info("🛑 member_removed refused (\(d.reason))")
                return
            }
            // __GROUP_ADMIN_KICK_2026_11_20__ an open-group member may remove
            // members, but not an admin: that is how "anyone may edit" became
            // "anyone may take over".
            if d.reason != MessageGroupManager.adminReason,
               groups[index].members.contains(where: { $0.isAdmin && MessageManager.canonicalIdentity($0.publicKey) == target }) {
                OshiLog.messaging.info("🛑 member_removed refused: only an admin removes an admin")
                return
            }
        }
        if let me = currentUserPublicKey, MessageManager.canonicalIdentity(me) == target {
            if !isLeave { removeGroupLocallyAfterRemoval(groupId, by: sender) }
            return
        }
        let before = groups[index].members.count
        groups[index].members.removeAll { MessageManager.canonicalIdentity($0.publicKey) == target }
        guard groups[index].members.count < before else { return }
        if !isLeave {
            var ev = groups[index].evictedMemberKeys ?? []
            if !ev.contains(where: { MessageManager.canonicalIdentity($0) == target }) { ev.append(member) }
            groups[index].evictedMemberKeys = ev
        }
        groups[index].lastActivity = Date()
        saveGroups()
        addSystemMessage(to: groupId, type: isLeave ? .memberLeft : .memberRemoved, byPublicKey: member)
        NotificationCenter.default.post(name: NSNotification.Name("GroupUpdated"), object: nil, userInfo: ["groupId": groupId.uuidString])
        OshiLog.messaging.info("✅ Removed member \(member.prefix(8))... from group (\(isLeave ? "left" : "removed"))")
    }

    /// We were removed by an admin: forget the group (no leave is sent — we were not asked).
    func removeGroupLocallyAfterRemoval(_ groupId: UUID, by sender: String?) {
        guard groups.contains(where: { $0.id == groupId }) else { return }
        groups.removeAll { $0.id == groupId }
        groupMessages.removeValue(forKey: groupId)
        v2UnreachableMembers.removeValue(forKey: groupId)
        saveGroups()
        saveMessages()
        DispatchQueue.main.async {
            NotificationCenter.default.post(name: NSNotification.Name("GroupUpdated"), object: nil,
                                            userInfo: ["groupId": groupId.uuidString, "deleted": true])
        }
        OshiLog.messaging.info("👋 Removed from group by \(sender?.prefix(8) ?? "?")… — group dropped locally")
    }

    /// §5.1 picture/wallpaper on the wire: ≤ 512 px, JPEG, ≤ 96 KB. nil = cannot be made to fit.
    nonisolated static func wireImage(_ data: Data?) -> Data? {
        guard let data, let image = UIImage(data: data) else { return nil }
        let maxSide: CGFloat = 512
        let size = image.size
        let scale = min(1, maxSide / max(size.width, size.height, 1))
        let target = CGSize(width: max(1, floor(size.width * scale)), height: max(1, floor(size.height * scale)))
        let fmt = UIGraphicsImageRendererFormat()
        fmt.scale = 1
        let resized = UIGraphicsImageRenderer(size: target, format: fmt).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
        var q: CGFloat = 0.8
        while q >= 0.2 {
            if let jpg = resized.jpegData(compressionQuality: q), jpg.count <= 96 * 1024 { return jpg }
            q -= 0.15
        }
        return nil
    }
}


#if DEBUG
// MARK: - __GROUP_E2E_V2_2026_09_23__ Two-simulator group driver (DEBUG only)

/// `-OSHIGroupDriver`: drives the SHIPPING group API from a command file so a multi-device
/// group test can run against the production relay without tapping through the UI.
///   in : `Documents/group_cmds.jsonl`  one JSON object per line, `{"seq":N,"op":…}`
///   out: `Documents/group_state.json`  my key + groups + messages + reactions + unreachable
/// Compiled out of release builds.
@MainActor
enum GroupE2EDriver {
    private static var done = Set<Int>()
    private static var typingSeen = Set<String>()
    private static var docs: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0] }

    static func startIfRequested() {
        guard ProcessInfo.processInfo.arguments.contains("-OSHIGroupDriver") else { return }
        Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { _ in
            Task { @MainActor in tick() }
        }
    }

    private static func tick() {
        let gm = MessageGroupManager.shared
        guard let me = gm.currentUserPublicKey, !me.isEmpty else { return }
        if let text = try? String(contentsOf: docs.appendingPathComponent("group_cmds.jsonl"), encoding: .utf8) {
            for line in text.split(separator: "\n") {
                guard let obj = try? JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any],
                      let seq = obj["seq"] as? Int, !done.contains(seq) else { continue }
                done.insert(seq)
                run(obj, gm: gm, me: me)
            }
        }
        dump(gm: gm, me: me)
    }

    private static func gid(_ o: [String: Any], _ gm: MessageGroupManager) -> UUID? {
        if let s = o["gid"] as? String, let u = UUID(uuidString: s) { return u }
        if let n = o["group"] as? String { return gm.groups.first { $0.name == n }?.id }
        return nil
    }

    private static func mid(_ o: [String: Any], _ gm: MessageGroupManager, _ g: UUID) -> UUID? {
        if let s = o["mid"] as? String, let u = UUID(uuidString: s) { return u }
        if let t = o["text"] as? String {
            return gm.groupMessages[g]?.last { ($0.plaintextContent ?? String(data: $0.encryptedContent, encoding: .utf8)) == t }?.id
        }
        return nil
    }

    private static func run(_ o: [String: Any], gm: MessageGroupManager, me: String) {
        let op = o["op"] as? String ?? ""
        NSLog("GROUPDRIVER op=\(op)")
        switch op {
        case "create":
            let type: GroupType = (o["type"] as? String) == "admin_only" ? .adminOnly : .collaborative
            let g = gm.createGroup(name: o["name"] as? String ?? "G", type: type, adminPublicKey: me)
            for k in o["members"] as? [String] ?? [] { gm.addMember(to: g.id, publicKey: k, alias: nil) }
        case "text":
            if let g = gid(o, gm) { gm.sendMessage(to: g, content: o["body"] as? String ?? "", senderPublicKey: me) }
        case "photo":
            if let g = gid(o, gm) {
                let size = CGSize(width: 320, height: 240)
                let img = UIGraphicsImageRenderer(size: size).image { ctx in
                    UIColor(hue: CGFloat.random(in: 0...1), saturation: 0.8, brightness: 0.9, alpha: 1).setFill()
                    ctx.fill(CGRect(origin: .zero, size: size))
                    ("GROUP-E2E " + (o["caption"] as? String ?? "")).draw(at: CGPoint(x: 10, y: 100),
                        withAttributes: [.font: UIFont.boldSystemFont(ofSize: 24), .foregroundColor: UIColor.white])
                }
                if let jpg = img.jpegData(compressionQuality: 0.9) {
                    gm.sendMessage(to: g, content: o["caption"] as? String ?? "", senderPublicKey: me,
                                   mediaData: jpg, mediaType: .photo, mediaFileName: "photo.jpg")
                }
            }
        case "react":
            if let g = gid(o, gm), let m = mid(o, gm, g) {
                let emoji = ReactionEmoji(wire: o["emoji"] as? String ?? "👍") ?? .thumbsUp
                MessageReactionManager.shared.toggleReaction(to: m.uuidString, emoji: emoji, senderPublicKey: me, senderName: "")
                let payload = ReactionPayload(messageId: m.uuidString, emoji: emoji, senderPublicKey: me,
                                              senderName: "", timestamp: Date(), action: .add)
                gm.sendMessage(to: g, content: payload.toMessageString(), senderPublicKey: me)
            }
        case "rename":
            if let g = gid(o, gm) { _ = gm.renameGroup(groupId: g, newName: o["name"] as? String ?? "", renamedBy: me) }
        case "add":
            if let g = gid(o, gm), let k = o["key"] as? String { gm.addMember(to: g, publicKey: k, alias: nil) }
        case "remove":
            if let g = gid(o, gm), let k = o["key"] as? String { gm.removeMember(from: g, publicKey: k) }
        case "leave":
            if let g = gid(o, gm) { gm.leaveGroup(g, userPublicKey: me) }
        case "edit":
            if let g = gid(o, gm), let m = mid(o, gm, g) {
                gm.editGroupMessageForEveryone(groupId: g, messageId: m, newContent: o["body"] as? String ?? "", senderPublicKey: me)
            }
        case "delete":
            if let g = gid(o, gm), let m = mid(o, gm, g) {
                gm.deleteGroupMessageForEveryone(groupId: g, messageId: m, senderPublicKey: me)
            }
        case "typing":
            if let g = gid(o, gm) { gm.sendGroupTypingStatus(g, isTyping: true, senderName: "driver") }
        case "sync":
            if let g = gid(o, gm) { gm.requestMemberSync(groupId: g) }
        case "promote":
            if let g = gid(o, gm), let k = o["key"] as? String { gm.promoteToAdmin(in: g, publicKey: k) }
        case "picture":
            if let g = gid(o, gm) {
                let img = UIGraphicsImageRenderer(size: CGSize(width: 900, height: 900)).image { ctx in
                    UIColor.systemTeal.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 900, height: 900))
                }
                if let jpg = img.jpegData(compressionQuality: 0.9) { _ = gm.updateGroupPicture(groupId: g, imageData: jpg, updatedBy: me) }
            }
        default:
            NSLog("GROUPDRIVER unknown op \(op)")
        }
    }

    private static func dump(gm: MessageGroupManager, me: String) {
        for (g, users) in TypingIndicatorManager.shared.groupTypingUsers {
            for (k, _) in users { typingSeen.insert(g + "|" + String(k.prefix(8))) }
        }
        let reactions = MessageReactionManager.shared.reactions
        let groups: [[String: Any]] = gm.groups.map { g in
            let msgs: [[String: Any]] = (gm.groupMessages[g.id] ?? []).map { m in
                var d: [String: Any] = [
                    "id": m.id.uuidString, "sender": m.senderPublicKey,
                    "body": m.plaintextContent ?? String(data: m.encryptedContent, encoding: .utf8) ?? "",
                    "status": m.deliveryStatus?.rawValue ?? "",
                ]
                if let t = m.systemMessageType { d["system"] = t.rawValue }
                if let e = m.editedContent { d["edited"] = e }
                if let mt = m.mediaType {
                    d["mediaType"] = mt.rawValue
                    d["mediaBytes"] = gm.loadGroupMediaAttachment(for: m.id.uuidString, type: mt)?.count ?? 0
                }
                let rs = reactions[m.id.uuidString] ?? []
                if !rs.isEmpty { d["reactions"] = rs.map { $0.emoji.rawValue + "@" + String($0.senderPublicKey.prefix(8)) } }
                return d
            }
            return [
                "id": g.id.uuidString, "name": g.name, "type": g.type.rawValue,
                "stateVersion": g.stateVersion ?? -1,
                "members": g.members.map { ["key": $0.publicKey, "admin": $0.isAdmin] },
                "evicted": g.evictedMemberKeys ?? [],
                "hasPicture": g.groupPictureData != nil,
                "unreachable": gm.v2UnreachableMembers[g.id] ?? [],
                "messages": msgs,
            ]
        }
        let out: [String: Any] = ["me": me, "at": Date().timeIntervalSince1970, "groups": groups,
                                  "typingSeen": Array(typingSeen)]
        if let data = try? JSONSerialization.data(withJSONObject: out, options: [.prettyPrinted, .sortedKeys]) {
            try? data.write(to: docs.appendingPathComponent("group_state.json"), options: .atomic)
        }
    }
}
#endif

// MARK: - __GROUP_COMMENTS_2026_11_21__ Comments under channel posts (GROUP_INFO_SETTINGS_SPEC §2.12)
//
// In this file (not GroupComments.swift) because the send path is `private`.
// The rules are the pure functions of `GroupCommentPolicy`; this is the wiring.

extension MessageGroupManager {

    /// What we know about the message `commentOn` names: a timeline message, a
    /// stored comment, or a known tombstone. nil = unknown (orphan).
    func commentParent(groupId: UUID, id: String) -> CommentParentInfo? {
        if let post = groupMessages[groupId]?.first(where: { GroupCommentPolicy.sameId($0.id.uuidString, id) }) {
            return CommentParentInfo(authorKey: post.senderPublicKey,
                                     isComment: post.commentOn != nil || post.isSystemMessage,
                                     isDeleted: false)
        }
        let store = GroupCommentStore.shared
        if let c = store.comment(groupId: groupId, id: id) {
            return CommentParentInfo(authorKey: c.senderPublicKey, isComment: true, isDeleted: false)
        }
        if store.isDeleted(groupId: groupId, id: id) {
            return CommentParentInfo(authorKey: "", isComment: false, isDeleted: true)
        }
        return nil
    }

    /// §2.12.4 with the device state filled in. `countFlood: false` for our own
    /// self copies (the receiver flood counter is about other people).
    func commentVerdict(_ m: GroupMessage, sender: String, group: MessageGroup, countFlood: Bool = true) -> CommentReceiveVerdict {
        guard let postId = m.commentOn else { return .notAComment }
        let key = group.id.uuidString + "|" + MessageManager.canonicalIdentity(sender)
        let recent = countFlood ? GroupCommentStore.shared.receiveFlood.count(key) : 0
        return GroupCommentPolicy.decide(m, sender: sender, group: group,
                                         parent: commentParent(groupId: group.id, id: postId),
                                         recentFromSender: recent)
    }

    /// Rule 4e: store as a comment (never a timeline row) and notify only for a
    /// reply to one of my comments or a mention of me (§2.12.5).
    func acceptComment(_ message: GroupMessage, group: MessageGroup) {
        let store = GroupCommentStore.shared
        guard !store.contains(groupId: group.id, id: message.id) else { return }
        var c = GroupCommentPolicy.truncatedBody(message)
        c.isRead = true
        if c.plaintextContent == nil { c.plaintextContent = String(data: c.encryptedContent, encoding: .utf8) }
        let me = currentUserPublicKey
        // Read BEFORE inserting, so a comment never counts as "mine" for itself.
        let mine = store.myCommentIds(groupId: group.id, me: me)
        store.insert(c)
        store.receiveFlood.record(group.id.uuidString + "|" + MessageManager.canonicalIdentity(c.senderPublicKey))
        OshiLog.messaging.info("💬 comment \(c.id.uuidString.prefix(8)) stored under post \(c.commentOn?.prefix(8) ?? "?")")
        NotificationCenter.default.post(name: NSNotification.Name("GroupCommentsChanged"), object: nil,
                                        userInfo: ["groupId": group.id.uuidString])

        guard GroupCommentPolicy.notifies(c, me: me, myCommentIds: mine),
              !group.isMuted,
              !BlockedContactsManager.shared.isGroupBlocked(group.id),
              !BlockedContactsManager.shared.isBlocked(c.senderPublicKey) else { return }
        let name = GroupNames.displayName(c.senderPublicKey, in: group, me: me)
        NotificationManager.shared.sendGroupMessageNotification(
            groupName: group.name,
            senderName: String(format: "cm_notif_reply".localized, name),
            messageText: GroupMentions.render(GroupCommentPolicy.text(of: c)),
            groupId: group.id.uuidString,
            messageId: c.id.uuidString)
    }

    /// Orphans: when a message is stored, the held comments naming it are re-run
    /// through rule 4 (§2.12.4).
    func replayHeldComments(groupId: UUID, postId: UUID) {
        let store = GroupCommentStore.shared
        guard store.orphans.count(groupId) > 0 else { return }
        let ready = store.orphans.take(groupId: groupId, postId: postId.uuidString)
        guard !ready.isEmpty, let group = groups.first(where: { $0.id == groupId }) else { return }
        for h in ready.sorted(by: { $0.message.timestamp < $1.message.timestamp }) {
            let mine = currentUserPublicKey.map { MessageManager.canonicalIdentity($0) == MessageManager.canonicalIdentity(h.sender) } ?? false
            switch commentVerdict(h.message, sender: h.sender, group: group, countFlood: !mine) {
            case .accept:
                if mine {
                    GroupCommentStore.shared.insert(GroupCommentPolicy.truncatedBody(h.message))
                } else {
                    acceptComment(h.message, group: group)
                }
            case .drop(let why):
                OshiLog.messaging.info("💬 held comment dropped on replay (\(why))")
            case .hold, .ordinary, .notAComment:
                break
            }
        }
    }

    /// §2.12.3 who a comment wakes: the author of the comment it answers.
    func commentPushTargets(_ m: GroupMessage, group: MessageGroup, me: String) -> [String] {
        guard let target = GroupCommentPolicy.replyTarget(of: m),
              !GroupCommentPolicy.sameId(target, m.commentOn),
              let answered = GroupCommentStore.shared.comment(groupId: group.id, id: target) else { return [] }
        return GroupCommentPolicy.pushTargets(replyTargetAuthor: answered.senderPublicKey, me: me)
    }

    /// The post a comment bar / thread is about.
    func commentPost(groupId: UUID, postId: UUID) -> GroupMessage? {
        groupMessages[groupId]?.first { $0.id == postId }
    }

    /// §2.12.3 send. Returns the refusal, `.ok` once queued.
    @discardableResult
    func sendComment(groupId: UUID, postId: UUID, text: String, replyTo: GroupMessage?) -> GroupCommentPolicy.SendCheck {
        guard let me = currentUserPublicKey, !me.isEmpty else { return .inactive }
        let group = groups.first { $0.id == groupId }
        let post = commentPost(groupId: groupId, postId: postId)
        let store = GroupCommentStore.shared
        let floodKey = groupId.uuidString
        let check = GroupCommentPolicy.senderCheck(
            text: text, post: post,
            postDeleted: store.isDeleted(groupId: groupId, id: postId.uuidString),
            group: group, sentInLastMinute: store.sendFlood.count(floodKey))
        guard check == .ok, let group, let post else { return check }
        // A reply targets a stored comment of THIS post; anything else becomes a top-level comment.
        let answered = replyTo.flatMap { r in
            store.comment(groupId: groupId, id: r.id.uuidString).flatMap {
                GroupCommentPolicy.sameId($0.commentOn, postId.uuidString) ? $0 : nil
            }
        }
        let postText = post.mediaType == nil ? GroupCommentPolicy.text(of: post) : (post.plaintextContent ?? "")
        let c = GroupCommentPolicy.buildComment(
            groupId: groupId, me: me, text: MessageTextSplitter.normalizedForSend(text),
            post: post, postText: postText,
            replyTo: answered, replyText: answered.map { GroupCommentPolicy.text(of: $0) },
            at: MessageSendClock.next())
        store.insert(c)
        store.sendFlood.record(floodKey)
        NotificationCenter.default.post(name: NSNotification.Name("GroupCommentsChanged"), object: nil,
                                        userInfo: ["groupId": groupId.uuidString])
        Task { await broadcastGroupMessageViaRelay(c, to: group, senderKey: me) }
        return .ok
    }

    /// Retry a failed comment (same id ⇒ idempotent on receivers).
    func retryComment(groupId: UUID, commentId: UUID) {
        guard let group = groups.first(where: { $0.id == groupId }),
              let c = GroupCommentStore.shared.comment(groupId: groupId, id: commentId.uuidString),
              let me = currentUserPublicKey else { return }
        GroupCommentStore.shared.update(groupId: groupId, id: commentId) { $0.deliveryStatus = .pending }
        Task { await broadcastGroupMessageViaRelay(c, to: group, senderKey: me) }
    }

    /// Delete for everyone: own comment, or ANY comment when we are admin (§2.12.4 D).
    /// No local system row: a comment is not a timeline row.
    @discardableResult
    func deleteCommentForEveryone(groupId: UUID, commentId: UUID) -> Bool {
        guard let group = groups.first(where: { $0.id == groupId }),
              let me = currentUserPublicKey,
              let c = GroupCommentStore.shared.comment(groupId: groupId, id: commentId.uuidString),
              GroupCommentPolicy.mayDelete(actor: me, comment: c, group: group) else { return false }
        GroupCommentStore.shared.remove(groupId: groupId, id: c.id)
        let cmd = GroupMessage(
            id: UUID(), groupId: groupId, senderPublicKey: "SYSTEM", encryptedContent: Data(),
            timestamp: Date(), isRead: true, messageChainIndex: 0,
            systemMessageType: .messageDeleted,
            systemMessageData: GroupSystemMessageData(actorPublicKey: me, targetPublicKey: nil,
                                                      deletedMessageId: c.id.uuidString))
        Task { await broadcastGroupMessageViaRelay(cmd, to: group, senderKey: me) }
        return true
    }

    /// Edit for everyone: own comment, 10-minute window.
    @discardableResult
    func editCommentForEveryone(groupId: UUID, commentId: UUID, newText: String) -> Bool {
        guard let group = groups.first(where: { $0.id == groupId }),
              let me = currentUserPublicKey,
              let c = GroupCommentStore.shared.comment(groupId: groupId, id: commentId.uuidString),
              GroupCommentPolicy.mayEdit(me: me, comment: c) else { return false }
        let t = GroupSettings.truncateUTF16(newText.trimmingCharacters(in: .whitespacesAndNewlines),
                                            max: GroupCommentPolicy.maxCommentUnits)
        guard !t.isEmpty else { return false }
        GroupCommentStore.shared.update(groupId: groupId, id: c.id) {
            $0.editedAt = Date()
            $0.editedContent = t
        }
        let cmd = GroupMessage(
            id: UUID(), groupId: groupId, senderPublicKey: "SYSTEM", encryptedContent: Data(),
            timestamp: Date(), isRead: true, messageChainIndex: 0,
            systemMessageType: .messageEdited,
            systemMessageData: GroupSystemMessageData(actorPublicKey: me, targetPublicKey: nil,
                                                      deletedMessageId: nil,
                                                      editedMessageId: c.id.uuidString,
                                                      editedContent: t))
        Task { await broadcastGroupMessageViaRelay(cmd, to: group, senderKey: me) }
        return true
    }
}


// MARK: - __GROUP_FANOUT_2026_11_24__

/// messageId → members the v2 group send left out because the relay kept answering 429. Written by
/// `v2TrySendGroup` / `v2TrySendGroupFile`, taken once by the legacy-compat step of the same message.
nonisolated final class GroupRateLimitLedger: @unchecked Sendable {
    static let shared = GroupRateLimitLedger()
    private let lock = NSLock()
    private var byMessage: [String: Set<String>] = [:]

    func record(messageId: String, members: [String]) {
        guard !members.isEmpty else { return }
        lock.lock(); defer { lock.unlock() }
        byMessage[messageId, default: []].formUnion(members)
    }

    func take(messageId: String) -> Set<String> {
        lock.lock(); defer { lock.unlock() }
        return byMessage.removeValue(forKey: messageId) ?? []
    }
}


// MARK: - __GROUP_RESEND_2026_11_25__ automatic resend to the members a send missed

extension MessageGroupManager {

    static let sharedPendingDeliveryStore = GroupPendingDeliveryStore(fileURL: GroupPendingDeliveryStore.defaultURL)
    /// A large group's first send can outlast the first retry slot: never run two at once.
    private static var sendsInFlight: Set<String> = []
    private static var resendPassRunning = false
    private static var resendTimer: Timer?

    var pendingDeliveryStore: GroupPendingDeliveryStore { Self.sharedPendingDeliveryStore }

    var groupSendsInFlight: Set<String> {
        get { Self.sendsInFlight }
        set { Self.sendsInFlight = newValue }
    }

    func publishPendingDeliveries() {
        GroupPendingDeliveryCenter.shared.publish(pendingDeliveryStore.all())
    }

    /// Every 30 s, and at once when the network comes back or the user taps "Retry now".
    func startPendingDeliveryLoop() {
        publishPendingDeliveries()
        guard !Self.isRunningUnitTests, !ProcessInfo.processInfo.arguments.contains("-OSHIScreens") else { return }
        NotificationCenter.default.addObserver(forName: .networkBecameReachable, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self, !self.pendingDeliveryStore.all().isEmpty else { return }
                self.pendingDeliveryStore.makeDue()
                self.runDuePendingDeliveries()
            }
        }
        // First pass after the transports came up on a cold start.
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in self?.runDuePendingDeliveries() }
        Self.resendTimer?.invalidate()
        Self.resendTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.runDuePendingDeliveries() }
        }
    }

    /// "Retry now" from the bubble's details.
    func retryPendingDeliveryNow(messageId: UUID) {
        pendingDeliveryStore.makeDue(msgId: messageId.uuidString)
        publishPendingDeliveries()
        runDuePendingDeliveries()
    }

    /// Resends every due entry to its pending members only, through the normal send path
    /// (`broadcastGroupMessageViaRelay`: re-encrypted, re-signed, same message id — receivers
    /// and the relay dedupe on it; a member the v2 path cannot reach gets exactly what the first
    /// send would have given it, never a weaker copy because of the retry).
    func runDuePendingDeliveries() {
        guard !Self.resendPassRunning else { return }
        let due = pendingDeliveryStore.due()
        guard !due.isEmpty else { return }
        Self.resendPassRunning = true
        Task { @MainActor in
            defer { Self.resendPassRunning = false; self.publishPendingDeliveries() }
            for entry in due {
                if self.groupSendsInFlight.contains(entry.msgId) { continue }
                guard let gid = UUID(uuidString: entry.groupId),
                      let group = self.groups.first(where: { $0.id == gid }),
                      let mid = UUID(uuidString: entry.msgId) else {
                    self.pendingDeliveryStore.remove(entry.msgId); continue
                }
                // Members who left are not owed the message.
                self.pendingDeliveryStore.dropDeparted(groupId: entry.groupId,
                                                      members: group.members.map { $0.publicKey })
                guard let current = self.pendingDeliveryStore.get(entry.msgId) else {
                    self.updateGroupMessageStatus(groupId: gid, messageId: mid, to: .sent)
                    continue
                }
                guard let me = self.currentUserPublicKey, !me.isEmpty,
                      let row = self.resendableCopy(groupId: gid, messageId: mid) else {
                    self.pendingDeliveryStore.remove(entry.msgId); continue   // deleted row
                }
                let wire: GroupMessage
                if row.senderPublicKey.hasPrefix("bot:"), let bot = current.botAuthor {
                    wire = Self.botReplyWireMessage(row, ownerKey: me, botName: bot)
                } else if MessageManager.canonicalIdentity(row.senderPublicKey) == MessageManager.canonicalIdentity(me) {
                    wire = row
                } else {
                    self.pendingDeliveryStore.remove(entry.msgId); continue   // not ours to send
                }
                OshiLog.messaging.info("↻ group \(entry.groupId.prefix(8)) msg \(entry.msgId.prefix(8)): resend to \(current.pending.count)/\(current.total) (attempt \(current.attempts + 1))")
                await self.broadcastGroupMessageViaRelay(wire, to: group, senderKey: me,
                                                         onlyMembers: Set(current.pending))
            }
        }
    }
}
