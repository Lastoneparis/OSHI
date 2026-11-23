//
//  BlockedContactsManager.swift
//  OSHI - Blocked Contacts & Groups Management
//

import Foundation
import Combine
import UserNotifications
#if canImport(UIKit)
import UIKit
#endif

// MARK: - Blocked Contact Model

struct BlockedContact: Codable, Identifiable {
    let id: UUID
    let publicKey: String
    let displayName: String?
    let blockedAt: Date
    let reason: BlockReason?
    
    init(publicKey: String, displayName: String? = nil, reason: BlockReason? = nil) {
        self.id = UUID()
        self.publicKey = publicKey
        self.displayName = displayName
        self.blockedAt = Date()
        self.reason = reason
    }
}

// MARK: - Blocked Group Model

struct BlockedGroup: Codable, Identifiable {
    let id: UUID
    let groupId: UUID
    let groupName: String?
    let blockedAt: Date
    let reason: BlockReason?
    
    init(groupId: UUID, groupName: String? = nil, reason: BlockReason? = nil) {
        self.id = UUID()
        self.groupId = groupId
        self.groupName = groupName
        self.blockedAt = Date()
        self.reason = reason
    }
}

enum BlockReason: String, Codable, CaseIterable {
    case spam = "spam"
    case harassment = "harassment"
    case inappropriate = "inappropriate"
    case other = "other"
    
    var localizedDescription: String {
        switch self {
        case .spam:
            return "block_reason_spam".localized
        case .harassment:
            return "block_reason_harassment".localized
        case .inappropriate:
            return "block_reason_inappropriate".localized
        case .other:
            return "block_reason_other".localized
        }
    }
}

// MARK: - Blocked Contacts Manager

class BlockedContactsManager: ObservableObject {
    static let shared = BlockedContactsManager()
    
    @Published private(set) var blockedContacts: [BlockedContact] = []
    @Published private(set) var blockedGroups: [BlockedGroup] = []
    
    private let contactsStorageKey = "blockedContacts"
    private let groupsStorageKey = "blockedGroups"
    private let fileManager = FileManager.default
    
    private init() {
        loadBlockedContacts()
        loadBlockedGroups()
        // __BLOCKED_NOTIF_2026_09_23__ sweep on every return to the foreground: the
        // extension cannot hide a push outright (no filtering entitlement), so it
        // leaves a passive, marked line behind — and older builds left full banners.
        #if canImport(UIKit)
        NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification,
                                               object: nil, queue: .main) { [weak self] _ in
            self?.withdrawDeliveredNotifications()
        }
        #endif
        withdrawDeliveredNotifications()
    }

    /// __BLOCKED_NOTIF_2026_09_23__ Remove, from Notification Centre, every
    /// notification whose sender is blocked (message, call, reaction…) and every one
    /// the extension downgraded. Rule in `MessageBannerPolicy.deliveredToWithdraw`.
    func withdrawDeliveredNotifications() {
        let blocked = blockedContacts.map { $0.publicKey }
        let center = UNUserNotificationCenter.current()
        center.getDeliveredNotifications { delivered in
            let ids = MessageBannerPolicy.deliveredToWithdraw(
                delivered.map { MessageBannerPolicy.Delivered(identifier: $0.request.identifier,
                                                              userInfo: $0.request.content.userInfo,
                                                              date: $0.date) },
                blockedKeys: blocked)
            if !ids.isEmpty { center.removeDeliveredNotifications(withIdentifiers: ids) }
        }
    }
    
    // MARK: - Contact Methods
    
    /// Normalize a public key for tolerant comparison: strip whitespace,
    /// fold base64url variants (`_`,`-`) to the standard alphabet (`/`,`+`),
    /// and drop trailing `=` padding. The same key may be stored across
    /// platforms or transports in slightly different formats — without this,
    /// a contact blocked as `eAbMQUdW…cyc=` would NOT match an incoming call
    /// signaling `eAbMQUdW…cyc` (no padding) and the call would ring.
    private func normalizeKey(_ key: String) -> String {
        return key.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
            .trimmingCharacters(in: CharacterSet(charactersIn: "="))
    }

    /// Mirrors the blocked keys into the App Group so the Notification Service
    /// Extension can suppress a push before it is displayed. The extension is a
    /// separate process and cannot read this class's Documents-directory file.
    private func publishBlockedSnapshot() {
        AppGroupBridge.writeBlockedSnapshot(blockedContacts.map { normalizeKey($0.publicKey) })
    }

    /// Same bridge for blocked GROUPS (__GROUP_MUTE_BLOCK_IN_NSE_2026_09_23__):
    /// without it a blocked group's pushes kept being shown by the extension.
    private func publishBlockedGroupsSnapshot() {
        AppGroupBridge.writeBlockedGroupsSnapshot(blockedGroups.map { $0.groupId.uuidString })
    }

    /// Check if a contact is blocked. Comparison is normalized so that
    /// base64 vs base64url and `=` padding skew don't accidentally let a
    /// blocked or stale identity ring through.
    func isBlocked(_ publicKey: String) -> Bool {
        let target = normalizeKey(publicKey)
        return blockedContacts.contains { normalizeKey($0.publicKey) == target }
    }
    
    /// Block a contact
    func blockContact(publicKey: String, displayName: String? = nil, reason: BlockReason? = nil) {
        // Don't block if already blocked
        guard !isBlocked(publicKey) else {
            OshiLog.messaging.info("⚠️ Contact already blocked: \(publicKey)")
            return
        }
        
        let blocked = BlockedContact(publicKey: publicKey, displayName: displayName, reason: reason)
        blockedContacts.append(blocked)
        saveBlockedContacts()
        withdrawDeliveredNotifications()   // __BLOCKED_NOTIF_2026_09_23__
        
        OshiLog.messaging.info("🚫 Blocked contact: \(displayName ?? publicKey)")
        OshiLog.messaging.info("   Reason: \(reason?.rawValue ?? "none")")
        OshiLog.messaging.info("   Total blocked: \(blockedContacts.count)")
        
        // Post notification
        NotificationCenter.default.post(
            name: .contactBlocked,
            object: nil,
            userInfo: ["publicKey": publicKey]
        )
    }
    
    /// Unblock a contact
    func unblockContact(publicKey: String) {
        // Normalized, like isBlocked. An exact-match lookup here meant a key
        // presented in a different spelling than it was stored in could be
        // reported as blocked yet refuse to unblock.
        let target = normalizeKey(publicKey)
        guard let index = blockedContacts.firstIndex(where: { normalizeKey($0.publicKey) == target }) else {
            OshiLog.messaging.info("⚠️ Contact not found in blocked list: \(publicKey)")
            return
        }
        
        let contact = blockedContacts[index]
        blockedContacts.remove(at: index)
        saveBlockedContacts()
        BlockedContactsManager.recordUnblock(publicKey)   // __BLOCK_SYNC_LWW_2026_09_24__
        
        OshiLog.messaging.info("✅ Unblocked contact: \(contact.displayName ?? publicKey)")
        OshiLog.messaging.info("   Total blocked: \(blockedContacts.count)")
        
        // Post notification
        NotificationCenter.default.post(
            name: .contactUnblocked,
            object: nil,
            userInfo: ["publicKey": publicKey]
        )
    }
    
    /// __BLOCK_SYNC_LWW_2026_09_24__ When the user unblocked a key on THIS device (ms), so
    /// devsync exports the unblock as `blocked:false` + the real `blockedAt` (a block's own
    /// time is `BlockedContact.blockedAt`). Keyed by the devsync canonical key.
    static let unblockedAtDefaultsKey = "devsync.unblockedAt"

    static func recordUnblock(_ publicKey: String, at: Date = Date()) {
        var m = UserDefaults.standard.dictionary(forKey: unblockedAtDefaultsKey) as? [String: Double] ?? [:]
        m[DSIds.canonicalKey(publicKey)] = (at.timeIntervalSince1970 * 1000).rounded()
        UserDefaults.standard.set(m, forKey: unblockedAtDefaultsKey)
    }

    static func unblockedAtMs(_ canonicalKey: String) -> Int64? {
        (UserDefaults.standard.dictionary(forKey: unblockedAtDefaultsKey) as? [String: Double])?[canonicalKey].map { Int64($0) }
    }

    /// Get blocked contact by public key
    func getBlockedContact(_ publicKey: String) -> BlockedContact? {
        return blockedContacts.first { $0.publicKey == publicKey }
    }
    
    /// Get all blocked contacts
    func getAllBlockedContacts() -> [BlockedContact] {
        return blockedContacts.sorted { $0.blockedAt > $1.blockedAt }
    }
    
    // MARK: - Group Methods
    
    /// Check if a group is blocked
    func isGroupBlocked(_ groupId: UUID) -> Bool {
        return blockedGroups.contains { $0.groupId == groupId }
    }
    
    /// Block a group
    func blockGroup(groupId: UUID, groupName: String? = nil, reason: BlockReason? = nil) {
        // Don't block if already blocked
        guard !isGroupBlocked(groupId) else {
            OshiLog.messaging.info("⚠️ Group already blocked: \(groupId)")
            return
        }
        
        let blocked = BlockedGroup(groupId: groupId, groupName: groupName, reason: reason)
        blockedGroups.append(blocked)
        saveBlockedGroups()
        
        OshiLog.messaging.info("🚫 Blocked group: \(groupName ?? groupId.uuidString)")
        OshiLog.messaging.info("   Reason: \(reason?.rawValue ?? "none")")
        OshiLog.messaging.info("   Total blocked groups: \(blockedGroups.count)")
        
        // Post notification
        NotificationCenter.default.post(
            name: .groupBlocked,
            object: nil,
            userInfo: ["groupId": groupId]
        )
    }
    
    /// Unblock a group
    func unblockGroup(groupId: UUID) {
        guard let index = blockedGroups.firstIndex(where: { $0.groupId == groupId }) else {
            OshiLog.messaging.info("⚠️ Group not found in blocked list: \(groupId)")
            return
        }
        
        let group = blockedGroups[index]
        blockedGroups.remove(at: index)
        saveBlockedGroups()
        
        OshiLog.messaging.info("✅ Unblocked group: \(group.groupName ?? groupId.uuidString)")
        OshiLog.messaging.info("   Total blocked groups: \(blockedGroups.count)")
        
        // Post notification
        NotificationCenter.default.post(
            name: .groupUnblocked,
            object: nil,
            userInfo: ["groupId": groupId]
        )
    }
    
    /// Get blocked group by ID
    func getBlockedGroup(_ groupId: UUID) -> BlockedGroup? {
        return blockedGroups.first { $0.groupId == groupId }
    }
    
    /// Get all blocked groups
    func getAllBlockedGroups() -> [BlockedGroup] {
        return blockedGroups.sorted { $0.blockedAt > $1.blockedAt }
    }
    
    // MARK: - Clear Methods
    
    /// Clear all blocked contacts (for testing/reset)
    func clearAllContacts() {
        blockedContacts.removeAll()
        saveBlockedContacts()
        OshiLog.messaging.info("🗑 Cleared all blocked contacts")
    }
    
    /// Clear all blocked groups (for testing/reset)
    func clearAllGroups() {
        blockedGroups.removeAll()
        saveBlockedGroups()
        OshiLog.messaging.info("🗑 Cleared all blocked groups")
    }
    
    /// Clear everything
    func clearAll() {
        clearAllContacts()
        clearAllGroups()
    }
    
    // MARK: - Private Methods - Contacts
    
    private func loadBlockedContacts() {
        guard let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else {
            OshiLog.messaging.info("❌ Cannot access documents directory")
            return
        }
        
        let fileURL = documentsURL.appendingPathComponent("\(contactsStorageKey).json")
        
        guard fileManager.fileExists(atPath: fileURL.path) else {
            OshiLog.messaging.info("ℹ️ No blocked contacts file found (fresh install)")
            return
        }
        
        do {
            let data = try Data(contentsOf: fileURL)
            let contacts = try JSONDecoder().decode([BlockedContact].self, from: data)
            blockedContacts = contacts
            OshiLog.messaging.info("✅ Loaded \(contacts.count) blocked contacts")
            // Republish on load too: an install that blocked someone BEFORE this
            // bridge existed has an empty snapshot, and would keep getting their
            // notifications until the next block/unblock.
            publishBlockedSnapshot()
        } catch {
            OshiLog.messaging.info("❌ Error loading blocked contacts: \(error)")
        }
    }
    
    private func saveBlockedContacts() {
        guard let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else {
            OshiLog.messaging.info("❌ Cannot access documents directory")
            return
        }
        
        let fileURL = documentsURL.appendingPathComponent("\(contactsStorageKey).json")
        
        do {
            let data = try JSONEncoder().encode(blockedContacts)
            try data.write(to: fileURL, options: .atomic)
            OshiLog.messaging.info("💾 Saved \(blockedContacts.count) blocked contacts")
            publishBlockedSnapshot()
        } catch {
            OshiLog.messaging.info("❌ Error saving blocked contacts: \(error)")
        }
    }
    
    // MARK: - Private Methods - Groups
    
    private func loadBlockedGroups() {
        guard let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else {
            OshiLog.messaging.info("❌ Cannot access documents directory")
            return
        }
        
        let fileURL = documentsURL.appendingPathComponent("\(groupsStorageKey).json")
        
        guard fileManager.fileExists(atPath: fileURL.path) else {
            OshiLog.messaging.info("ℹ️ No blocked groups file found (fresh install)")
            return
        }
        
        do {
            let data = try Data(contentsOf: fileURL)
            let groups = try JSONDecoder().decode([BlockedGroup].self, from: data)
            blockedGroups = groups
            OshiLog.messaging.info("✅ Loaded \(groups.count) blocked groups")
            publishBlockedGroupsSnapshot()
        } catch {
            OshiLog.messaging.info("❌ Error loading blocked groups: \(error)")
        }
    }
    
    private func saveBlockedGroups() {
        guard let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else {
            OshiLog.messaging.info("❌ Cannot access documents directory")
            return
        }
        
        let fileURL = documentsURL.appendingPathComponent("\(groupsStorageKey).json")
        
        do {
            let data = try JSONEncoder().encode(blockedGroups)
            try data.write(to: fileURL, options: .atomic)
            OshiLog.messaging.info("💾 Saved \(blockedGroups.count) blocked groups")
            publishBlockedGroupsSnapshot()
        } catch {
            OshiLog.messaging.info("❌ Error saving blocked groups: \(error)")
        }
    }
}

// MARK: - __BLOCKED_BY_PEER_2026_09_24__ "this peer told me I am blocked"

/// The caller-side half of "a blocked contact leaves no missed-call trace" (owner decision
/// 2026-09-24). The callee's device answers anything a blocked peer sends with the 🚫BLOCKED🚫
/// notice (`MessageManager.noticeBlockedSender`, once a day). The caller's device records that
/// here and, from then on, never places a call to that peer: no call offer, no VoIP push, so the
/// blocked side's iPhone is never woken into the forced CallKit report (which is what could leave
/// a line in its Recents). The caller sees "unavailable" locally.
///
/// The record is lifted by the first sign that the peer takes our traffic again: any message
/// from them that is not mechanics (`OSHIControlPayload.suppressesPush` false — a text, a call
/// attempt…), or a delivery/read receipt (a device that still blocks us never acks). Nothing is
/// told to the server: who blocks whom stays between the two devices.
///
/// Same rules and the same evidence list on Android (`BlockedByPeerStore.kt`) and Desktop
/// (`com.oshi.desktop.block.BlockedByPeerStore`).
final class BlockedByPeerStore {
    static let shared = BlockedByPeerStore()
    static let defaultsKey = "oshi.blockedByPeer"

    private let defaults: UserDefaults
    private let lock = NSLock()
    private var map: [String: Double]

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.map = defaults.dictionary(forKey: BlockedByPeerStore.defaultsKey) as? [String: Double] ?? [:]
    }

    /// Canonical spelling (standard alphabet, padded) so every transport's spelling matches.
    static func fold(_ key: String) -> String {
        var s = key.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while s.hasSuffix("=") { s.removeLast() }
        while s.count % 4 != 0 { s.append("=") }
        return s
    }

    /// What an inbound 1:1 plaintext from `peer` says about "peer blocks me".
    enum Evidence: Equatable { case blocked, unblocked, none }

    static func evidence(of plaintext: String?) -> Evidence {
        guard let pt = plaintext else { return .none }
        if pt.hasPrefix(OSHIControlPayload.blockedNoticePrefix) { return .blocked }
        if pt.hasPrefix("📬DELIVERY_RECEIPT📬") || pt.hasPrefix("📖READ_RECEIPT📖") { return .unblocked }
        return OSHIControlPayload.suppressesPush(pt) ? .none : .unblocked
    }

    /// Feed EVERY decrypted inbound 1:1 plaintext here (sender = the peer). Cheap when nothing
    /// is recorded: only a notice writes.
    func observeInbound(from peer: String, plaintext: String?, now: Date = Date()) {
        guard !peer.isEmpty else { return }
        switch BlockedByPeerStore.evidence(of: plaintext) {
        case .blocked: set(peer, now.timeIntervalSince1970)
        case .unblocked: set(peer, nil)
        case .none: break
        }
    }

    /// For a row that is about to be STORED in the 1:1 thread (every control payload has been
    /// consumed before that point): the notice records, anything else — text, media (nil
    /// plaintext), a call summary — is a peer that talks to us again.
    func observeStoredInbound(from peer: String, plaintext: String?, now: Date = Date()) {
        guard !peer.isEmpty else { return }
        if let pt = plaintext, pt.hasPrefix(OSHIControlPayload.blockedNoticePrefix) {
            set(peer, now.timeIntervalSince1970)
        } else if plaintext.map({ !OSHIControlPayload.suppressesPush($0) }) ?? true {
            set(peer, nil)
        }
    }

    func isBlockedBy(_ peer: String) -> Bool {
        let k = BlockedByPeerStore.fold(peer)
        lock.lock(); defer { lock.unlock() }
        return map[k] != nil
    }

    func clear(_ peer: String) { set(peer, nil) }

    private func set(_ peer: String, _ value: Double?) {
        let k = BlockedByPeerStore.fold(peer)
        lock.lock()
        let changed = (map[k] != nil) != (value != nil)
        if let value { if map[k] == nil { map[k] = value } } else { map.removeValue(forKey: k) }
        let snapshot = map
        lock.unlock()
        guard changed else { return }
        defaults.set(snapshot, forKey: BlockedByPeerStore.defaultsKey)
        OshiLog.messaging.info("🚫 blockedByPeer \(value != nil ? "set" : "cleared") for \(k.prefix(8))…")
    }
}

// MARK: - Notification Names

extension Notification.Name {
    static let contactBlocked = Notification.Name("contactBlocked")
    static let contactUnblocked = Notification.Name("contactUnblocked")
    static let groupBlocked = Notification.Name("groupBlocked")
    static let groupUnblocked = Notification.Name("groupUnblocked")
}

// Note: String.localized extension is defined in String+Localization.swift
