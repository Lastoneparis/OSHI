//
//  AppGroupBridge.swift
//  OSHI
//
//  Writes snapshots into the shared App Group container so the
//  OSHINotificationService extension can read them when each APNs push
//  arrives. The NSE runs as a separate process and cannot read the main
//  app's normal sandbox files; the App Group is the only shared surface.
//
//  Snapshots written:
//    - contacts_snapshot.json   — [{publicKey, alias, displayName, firstName,
//                                  lastMessageAt, avatarFile}] used by NSE to
//                                  decorate the banner with the sender's name
//                                  instead of "OSHI", and by OSHIShare to draw
//                                  the "Send to…" list.
//    - share_avatars/<hash>.jpg — 96pt profile thumbnails for that list. The
//                                  extension cannot read `contact_presence.json`
//                                  (main-app sandbox), so the faces have to be
//                                  copied here or the picker is a wall of grey
//                                  glyphs.
//    - identity_snapshot.json   — {publicKey} so NSE can hit
//                                  /api/pending/<myKey> to update fetchedBy.
//    - device_id.txt            — current deviceId for the same call.
//    - groups_snapshot.json     — [{groupId, name, muted?}] so NSE titles a
//                                  group push with the group's name, resolved
//                                  locally (the push itself carries only the
//                                  groupId), and silences a muted group.
//    - blocked_snapshot.json    — [normalized blocked contact keys].
//    - blocked_groups_snapshot.json — [blocked group ids].
//
//  Reads (handled in MessageManager startup):
//    - nse_prefetch/<hash>.dat  — encrypted IPFS blobs the NSE
//                                  pre-downloaded; main app uses these
//                                  instead of re-fetching.
//

import Foundation
import UIKit
import CryptoKit
import os.log

enum AppGroupBridge {
    static let appGroupID = "group.Moriceau-lab.Genesis"

    private static var containerURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupID)
    }

    // MARK: - Writes (called from main app)

    /// Writes a compact `[{publicKey, alias, displayName, firstName,
    /// lastMessageAt, avatarFile}]` array.
    /// Call from `updateConversations()` and anywhere contacts mutate.
    /// Throttled by caller — internal write is cheap (<1ms for typical
    /// contact lists) so no debounce here.
    ///
    /// ⚠️ EVERY value stays a `String`, including `lastMessageAt`. OSHIShare
    /// parses this file as `[[String: String]]`, and a single non-string value
    /// makes that cast return nil for the WHOLE array — the picker would show
    /// zero contacts, not one contact missing a field.
    static func writeContactsSnapshot(_ contacts: [(publicKey: String, alias: String?, displayName: String?, firstName: String?, lastMessageAt: Date?, avatar: Data?)]) {
        guard let url = containerURL?.appendingPathComponent("contacts_snapshot.json") else { return }
        let avatarFiles = writeAvatarThumbnails(contacts)
        let payload: [[String: String]] = contacts.map { c in
            var d: [String: String] = ["publicKey": c.publicKey]
            if let a = c.alias { d["alias"] = a }
            if let n = c.displayName { d["displayName"] = n }
            if let f = c.firstName { d["firstName"] = f }
            if let t = c.lastMessageAt { d["lastMessageAt"] = String(t.timeIntervalSince1970) }
            if let file = avatarFiles[c.publicKey] { d["avatarFile"] = file }
            return d
        }
        do {
            let data = try JSONSerialization.data(withJSONObject: payload, options: [])
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            // Contacts (keys + aliases) must not ride into an iCloud/iTunes backup.
            var u = url
            var rv = URLResourceValues()
            rv.isExcludedFromBackup = true
            try? u.setResourceValues(rv)
        } catch {
            os_log("⚠️ writeContactsSnapshot err: %{public}@", log: .default, type: .info, error.localizedDescription)
        }
    }

    /// Copies each contact's profile photo into the App Group as a small JPEG
    /// so OSHIShare can draw a real face in the "Send to…" list.
    ///
    /// The file name carries a hash of the BYTES, not just of the key
    /// (`<keyHash>_<dataHash>.jpg`). That is what makes this cheap enough to
    /// call from `updateConversations()`: when a photo has not changed the file
    /// already exists and we skip the encode entirely, and when it HAS changed
    /// the name changes too, so no reader can be handed a stale image under a
    /// name it already cached.
    ///
    /// Returns publicKey → file name for the entries that have a photo.
    private static func writeAvatarThumbnails(_ contacts: [(publicKey: String, alias: String?, displayName: String?, firstName: String?, lastMessageAt: Date?, avatar: Data?)]) -> [String: String] {
        guard let dir = containerURL?.appendingPathComponent("share_avatars", isDirectory: true) else { return [:] }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        var result: [String: String] = [:]
        var keep = Set<String>()

        for c in contacts {
            guard let source = c.avatar, !source.isEmpty else { continue }
            let name = "\(shortHash(Data(c.publicKey.utf8)))_\(shortHash(source)).jpg"
            keep.insert(name)
            result[c.publicKey] = name

            let dest = dir.appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: dest.path) { continue }
            guard let thumb = thumbnailJPEG(source) else {
                result.removeValue(forKey: c.publicKey)
                keep.remove(name)
                continue
            }
            try? thumb.write(to: dest, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])  // __PLAINTEXT_LEFTOVERS_2026_09_24__
        }

        // Drop files no contact points at any more — a renamed photo would
        // otherwise leave its predecessor behind on every single change.
        if let existing = try? FileManager.default.contentsOfDirectory(atPath: dir.path) {
            for file in existing where !keep.contains(file) {
                try? FileManager.default.removeItem(at: dir.appendingPathComponent(file))
            }
        }
        return result
    }

    /// 96pt square, aspect-fill, JPEG. Small enough that a 50-contact list costs
    /// a couple hundred KB in the shared container.
    private static func thumbnailJPEG(_ data: Data, side: CGFloat = 96) -> Data? {
        guard let image = UIImage(data: data) else { return nil }
        let scale: CGFloat = 2
        let size = CGSize(width: side, height: side)
        let format = UIGraphicsImageRendererFormat()
        format.scale = scale
        format.opaque = false
        let rendered = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            // Aspect-FILL: a portrait photo squeezed into a square avatar reads
            // as a different person. Crop instead of distort.
            let ratio = max(size.width / image.size.width, size.height / image.size.height)
            let drawn = CGSize(width: image.size.width * ratio, height: image.size.height * ratio)
            image.draw(in: CGRect(x: (size.width - drawn.width) / 2,
                                  y: (size.height - drawn.height) / 2,
                                  width: drawn.width, height: drawn.height))
        }
        return rendered.jpegData(compressionQuality: 0.7)
    }

    private static func shortHash(_ data: Data) -> String {
        SHA256.hash(data: data).prefix(8).map { String(format: "%02x", $0) }.joined()
    }

    /// Persists the local user's public key so the NSE can construct the
    /// /api/pending URL. Called once after identity load.
    static func writeIdentitySnapshot(publicKey: String) {
        guard let url = containerURL?.appendingPathComponent("identity_snapshot.json") else { return }
        let payload: [String: String] = ["publicKey": publicKey]
        if let data = try? JSONSerialization.data(withJSONObject: payload) {
            try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }

    /// Publishes the blocked public keys so the Notification Service Extension
    /// can drop a push before it is ever shown.
    ///
    /// WHY THIS EXISTS: `BlockedContactsManager` persists to the app's Documents
    /// directory. The NSE runs in its OWN process with its OWN container and
    /// cannot read that file, so it had no way to know a sender was blocked —
    /// which is why blocking someone stopped their messages appearing in the app
    /// but did NOT stop their notifications arriving. The app checks were all
    /// present and correct; they simply were not reachable from the extension.
    ///
    /// Keys are stored pre-normalized (base64url → base64, padding dropped) so
    /// the extension compares canonical forms and a differently-spelled key
    /// cannot walk past the block.
    static func writeBlockedSnapshot(_ normalizedKeys: [String]) {
        guard let url = containerURL?.appendingPathComponent("blocked_snapshot.json") else { return }
        if let data = try? JSONSerialization.data(withJSONObject: normalizedKeys) {
            try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }

    /// __ANATOLE_1311_IOS__ (N8) Muted 1:1 contacts for the NSE, canonical
    /// keys like `writeBlockedSnapshot`. Without it a muted contact would only be
    /// silenced while the app itself posts the banner, and would still ring
    /// through APNs whenever the app is in the background.
    static func writeMutedContactsSnapshot(_ normalizedKeys: [String]) {
        guard let url = containerURL?.appendingPathComponent("muted_contacts_snapshot.json") else { return }
        if let data = try? JSONSerialization.data(withJSONObject: normalizedKeys) {
            try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }

    /// __GROUP_MUTE_BLOCK_IN_NSE_2026_09_23__ Blocked group ids for the NSE —
    /// same reason as `writeBlockedSnapshot`: `BlockedContactsManager` lives in
    /// the app's Documents, which the extension cannot read, so a blocked
    /// group's pushes kept ringing.
    static func writeBlockedGroupsSnapshot(_ groupIds: [String]) {
        guard let url = containerURL?.appendingPathComponent("blocked_groups_snapshot.json") else { return }
        if let data = try? JSONSerialization.data(withJSONObject: groupIds) {
            try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }

    /// __PHANTOM_BANNER_2026_10_11__ Ids des messages GARES en attente de
    /// déchiffrement, pour que l'extension taise une poussée RÉPÉTÉE sur un
    /// message dont la bannière a déjà sonné sans que rien puisse s'afficher.
    ///
    /// WHY: l'extension ne déchiffre pas (délibérément — déchiffrer avancerait le
    /// ratchet et désynchroniserait l'app), donc elle ne peut pas savoir seule
    /// qu'il n'y a rien derrière la poussée. Seule l'app le sait, et seulement
    /// après avoir essayé. Ce fichier est ce qu'elle en rapporte.
    ///
    /// Écrit en entier à chaque fois: un id qui finit par se déchiffrer disparaît
    /// de lui-même. Borné à 200 ids — au-delà c'est un désync massif, et la
    /// première poussée de chaque message a de toute façon déjà sonné.
    static func writeUndecryptableSnapshot(_ messageIds: [String]) {
        guard let url = containerURL?.appendingPathComponent("undecryptable_snapshot.json") else { return }
        let capped = Array(messageIds.filter { !$0.isEmpty }.suffix(200))
        if let data = try? JSONSerialization.data(withJSONObject: capped) {
            try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }

    /// __GROUP_NAME_IN_NSE_2026_09_23__ Publishes `[{groupId, name}]` so the
    /// Notification Service Extension can title a group push with the group's
    /// name, resolved ON THE DEVICE from the opaque `groupId` the push carries.
    ///
    /// WHY: since the server forces a generic banner on every non-call push
    /// (`ServerPatches/plaintext_leaks`, 2026-09-23), a group push reads
    /// "OSHI — You have a new encrypted message". The name must not go back to
    /// Apple, and the extension cannot read the app's `groups` file (separate
    /// process, separate sandbox), so the name has to be copied here.
    ///
    /// Same posture as `contacts_snapshot.json`: local file, excluded from
    /// backup, readable after first unlock. Skips the write when nothing the
    /// extension reads has changed (`saveGroups` runs on every group mutation).
    nonisolated(unsafe) private static var lastGroupsSnapshotSignature: String?  // written from @MainActor GroupMessaging only
    static func writeGroupsSnapshot(_ groups: [(id: String, name: String, muted: Bool)]) {
        guard let url = containerURL?.appendingPathComponent("groups_snapshot.json") else { return }
        // __GROUP_MUTE_BLOCK_IN_NSE_2026_09_23__ `muted` travels too, as the
        // STRING "1" (every value stays a String, same rule as the contacts
        // snapshot): the extension must be able to silence a muted group's push.
        let payload: [[String: String]] = groups.map { g in
            var d = ["groupId": g.id, "name": g.name]
            if g.muted { d["muted"] = "1" }
            return d
        }
        guard let data = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]) else { return }
        let signature = data.base64EncodedString()
        if signature == lastGroupsSnapshotSignature, FileManager.default.fileExists(atPath: url.path) { return }
        do {
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            var u = url
            var rv = URLResourceValues()
            rv.isExcludedFromBackup = true
            try? u.setResourceValues(rv)
            lastGroupsSnapshotSignature = signature
        } catch {
            os_log("⚠️ writeGroupsSnapshot err: %{public}@", log: .default, type: .info, error.localizedDescription)
        }
    }

    /// Saves the current deviceId so NSE's /api/pending call uses the
    /// same deviceId as the main app (otherwise the server treats NSE as
    /// a separate device and fetchedBy isn't correctly tracked).
    static func writeDeviceId(_ deviceId: String) {
        guard let url = containerURL?.appendingPathComponent("device_id.txt") else { return }
        if let data = deviceId.data(using: .utf8) {
            try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }

    // MARK: - Reads (main app picks up NSE pre-fetches)

    /// Returns the encrypted IPFS blob the NSE may have already
    /// downloaded for this hash, or nil if not prefetched. Once
    /// consumed, the cached file is deleted so the cache doesn't grow.
    static func consumePrefetchedBlob(ipfsHash: String) -> Data? {
        guard let cacheDir = containerURL?.appendingPathComponent("nse_prefetch", isDirectory: true) else { return nil }
        let file = cacheDir.appendingPathComponent("\(ipfsHash).dat")
        guard FileManager.default.fileExists(atPath: file.path),
              let data = try? Data(contentsOf: file) else { return nil }
        try? FileManager.default.removeItem(at: file)
        os_log("✅ Consumed NSE prefetch for %{public}@ (%d bytes)",
               log: .default, type: .info, ipfsHash.prefix(12) as CVarArg, data.count)
        return data
    }

    /// Best-effort age-based cleanup so abandoned NSE pre-fetches
    /// (hash never consumed, e.g. user deleted the conversation) don't
    /// accumulate forever. Call from app launch.
    static func purgeStalePrefetches(olderThan: TimeInterval = 7 * 24 * 3600) {
        guard let cacheDir = containerURL?.appendingPathComponent("nse_prefetch", isDirectory: true) else { return }
        let now = Date()
        guard let entries = try? FileManager.default.contentsOfDirectory(at: cacheDir,
                                                                         includingPropertiesForKeys: [.contentModificationDateKey]) else { return }
        for url in entries {
            guard let attrs = try? url.resourceValues(forKeys: [.contentModificationDateKey]),
                  let mtime = attrs.contentModificationDate else { continue }
            if now.timeIntervalSince(mtime) > olderThan {
                try? FileManager.default.removeItem(at: url)
            }
        }
    }
}
