//
//  StaleIdentityManager.swift
//  OSHI - Stale (rotated) public-key tracking
//
//  Purpose
//  -------
//  When a peer rotates their identity (regenerates keys), the OLD public key
//  is no longer "them" — but inbound calls / group messages signed by that
//  old key may still arrive (cached signaling, lagging relays, group members
//  with stale view, etc.). Without dropping those, the user's phone rings
//  for a person who doesn't actually own that key anymore, or a group member
//  receives messages addressed to the new identity from the old one.
//
//  This manager holds a per-device set of public keys the user has decided
//  are no longer valid. Stale ≠ blocked: blocked means "I don't want this
//  person at all". Stale means "this key is no longer this person".
//  The drop semantics are similar (silently reject inbound), but stale is
//  surfaced separately in UI and is auto-populated on contact rotation
//  (QR re-scan with same alias, KEY_ROTATION group control).
//

import Foundation
import Combine

// MARK: - Stale Identity Model

struct StaleIdentity: Codable, Identifiable {
    let id: UUID
    let publicKey: String        // stored normalized (see normalizeKey)
    let displayName: String?     // remembered alias at the time of marking
    let markedAt: Date
    let reason: StaleReason
    let replacedByPublicKey: String?  // if known (auto-rotation case)

    init(publicKey: String,
         displayName: String? = nil,
         reason: StaleReason = .manual,
         replacedByPublicKey: String? = nil) {
        self.id = UUID()
        self.publicKey = publicKey
        self.displayName = displayName
        self.markedAt = Date()
        self.reason = reason
        self.replacedByPublicKey = replacedByPublicKey
    }
}

enum StaleReason: String, Codable {
    case manual                  // user tapped "Mark old identity"
    case qrReplacement           // new QR scanned for same alias
    case groupKeyRotation        // KEY_ROTATION control message in a group
}

// MARK: - Stale Identity Manager

class StaleIdentityManager: ObservableObject {
    static let shared = StaleIdentityManager()

    @Published private(set) var staleIdentities: [StaleIdentity] = []

    private let storageKey = "staleIdentities_v1"
    private let fileManager = FileManager.default

    private init() {
        load()
    }

    // MARK: - Key Normalization
    //
    // MUST match BlockedContactsManager.normalizeKey: trim whitespace, fold
    // base64url variants (`-`,`_`) to standard base64 (`+`,`/`), strip `=`
    // padding. The same key may be carried in slightly different shapes
    // across transports (mesh vs VPS vs IPFS group envelope) — without this
    // a stale entry stored as `eAbM…cyc=` would NOT match an inbound key
    // serialized as `eAbM…cyc` and the call/message would slip through.
    private func normalizeKey(_ key: String) -> String {
        return key.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
            .trimmingCharacters(in: CharacterSet(charactersIn: "="))
    }

    // MARK: - Public API

    /// Returns true if the given public key is in the stale set.
    /// Comparison is normalized.
    func isStale(_ publicKey: String) -> Bool {
        let target = normalizeKey(publicKey)
        if target.isEmpty { return false }
        return staleIdentities.contains { $0.publicKey == target }
    }

    /// Mark a public key as stale. Idempotent.
    /// `replacedByPublicKey` is optional — useful for the auto-rotation path
    /// where we know the new key (QR re-scan, KEY_ROTATION control msg).
    func markStale(publicKey: String,
                   displayName: String? = nil,
                   reason: StaleReason = .manual,
                   replacedByPublicKey: String? = nil) {
        let normalized = normalizeKey(publicKey)
        guard !normalized.isEmpty else { return }

        // Don't re-add — keep the original markedAt so the "when" is preserved
        if staleIdentities.contains(where: { $0.publicKey == normalized }) {
            OshiLog.crypto.info("ℹ️ StaleIdentity: already marked stale: \(normalized.prefix(12))…")
            return
        }

        let entry = StaleIdentity(
            publicKey: normalized,
            displayName: displayName,
            reason: reason,
            replacedByPublicKey: replacedByPublicKey.map { normalizeKey($0) }
        )
        staleIdentities.append(entry)
        save()

        OshiLog.crypto.info("🪦 StaleIdentity: marked stale (\(reason.rawValue)) \(displayName ?? normalized.prefix(12).description)…")

        NotificationCenter.default.post(
            name: .identityMarkedStale,
            object: nil,
            userInfo: ["publicKey": normalized, "reason": reason.rawValue]
        )
    }

    /// Remove a key from the stale set (e.g. user undid the action).
    func unmarkStale(publicKey: String) {
        let normalized = normalizeKey(publicKey)
        guard let idx = staleIdentities.firstIndex(where: { $0.publicKey == normalized }) else { return }
        staleIdentities.remove(at: idx)
        save()

        NotificationCenter.default.post(
            name: .identityUnmarkedStale,
            object: nil,
            userInfo: ["publicKey": normalized]
        )
    }

    /// All stale entries, newest first.
    func getAll() -> [StaleIdentity] {
        return staleIdentities.sorted { $0.markedAt > $1.markedAt }
    }

    /// For testing / reset
    func clearAll() {
        staleIdentities.removeAll()
        save()
    }

    // MARK: - Persistence

    private func storageURL() -> URL? {
        guard let dir = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return nil
        }
        return dir.appendingPathComponent("\(storageKey).json")
    }

    private func load() {
        guard let url = storageURL() else { return }
        guard fileManager.fileExists(atPath: url.path) else { return }
        do {
            let data = try AtRestFile.read(url)  // __PLAINTEXT_LEFTOVERS_2026_09_24__
            staleIdentities = try JSONDecoder().decode([StaleIdentity].self, from: data)
            OshiLog.crypto.info("✅ StaleIdentity: loaded \(staleIdentities.count) entries")
        } catch {
            OshiLog.crypto.info("❌ StaleIdentity: load failed: \(error)")
        }
    }

    private func save() {
        guard let url = storageURL() else { return }
        do {
            let data = try JSONEncoder().encode(staleIdentities)
            try AtRestFile.write(data, to: url)  // __PLAINTEXT_LEFTOVERS_2026_09_24__ chiffré au repos (ancien clair lu puis rechiffré)
        } catch {
            OshiLog.crypto.info("❌ StaleIdentity: save failed: \(error)")
        }
    }
}

// MARK: - Notification Names

extension Notification.Name {
    static let identityMarkedStale = Notification.Name("identityMarkedStale")
    static let identityUnmarkedStale = Notification.Name("identityUnmarkedStale")
}
