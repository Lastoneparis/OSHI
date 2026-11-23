//
//  KeychainHelper.swift
//  OSHI
//
//  Created by MORICEAU on 01/11/2025.
//

//
//  KeychainHelper.swift
//  Secure keychain storage utilities - LOCALIZED
//

import Foundation
import Security

class KeychainHelper {

    // MARK: - Access groups
    //
    // 🔑 2026-08-18 — OSHIShare has to read the same ratchet sessions and identity
    // the app writes, or it can never send anything itself; it lives in a separate
    // process with a separate keychain. A shared access group is the only way in.
    //
    // ⚠️ THE ORDER OF `keychain-access-groups` IN THE ENTITLEMENTS IS LOAD-BEARING.
    // Once that key exists, the FIRST entry becomes the default group for every
    // write that does not name one. The app's own group is listed first precisely
    // so that adding this entitlement changes NOTHING for the 30-odd call sites
    // that don't ask for a group. Only the items named in `sharedKeys` move, and
    // only through `migrateSharedItems()`.
    //
    // The simulator does not enforce access groups at all — every app shares one
    // keychain there. Passing the attribute is harmless but proves nothing, so the
    // simulator can verify the MIGRATION and the LOCK, never the entitlement.

    /// Suffix is arbitrary; the `$(AppIdentifierPrefix)` prefix is what the OS
    /// checks. Must match the string in BOTH entitlement files exactly.
    static let sharedAccessGroup = "X3T3KRUQU8.Moriceau-lab.Genesis.shared"

    /// Le groupe PROPRE à l'app — la première entrée de `keychain-access-groups`.
    /// Dérivé de `sharedAccessGroup` pour que les deux ne puissent pas diverger.
    static let privateAccessGroup = String(sharedAccessGroup.dropLast(".shared".count))

    /// 🔴 __GROUPLESS_DELETE_2026_08_22__ Retirer une copie devenue inutile du
    /// groupe PROPRE de l'app, jamais du groupe partagé.
    ///
    /// `delete(key:)` sans `accessGroup` ne vise pas « le groupe par défaut »: une
    /// requête trousseau sans `kSecAttrAccessGroup` porte sur TOUS les groupes
    /// accessibles, et `SecItemDelete` supprime TOUT ce qui correspond. Appelée
    /// juste après une écriture dans le groupe partagé, elle efface donc ce qu'on
    /// vient d'écrire. `save()` documente ce piège trois lignes plus haut; deux
    /// appelants le reproduisaient à la ligne suivante.
    ///
    /// Sur le Simulateur il n'y a AUCUN groupe (voir `applyAccessGroup`), donc pas
    /// de copie privée distincte: il n'y a rien à retirer, et supprimer y
    /// détruirait l'unique exemplaire. C'est un non-événement, pas une suppression.
    static func deletePrivateCopy(key: String) {
        #if targetEnvironment(simulator)
        return
        #else
        try? delete(key: key, accessGroup: privateAccessGroup)
        #endif
    }

    /// The FIXED keychain items OSHIShare needs to send without waking the app.
    ///
    /// 🔴 These are the real account strings, verified against their writers. The
    /// first version of this list contained `ratchet_sessions_v1`, a name that
    /// exists nowhere in the app — invented rather than looked up. The migration
    /// would have reported it `absent`, forever, and the extension would have had
    /// no sessions with no error anywhere. A key name is not a guess.
    ///
    ///   privateKey                 — identity, `IdentityManager`
    ///   signingKey                 — Ed25519 signing key
    ///   oshi.v2.ownkeys            — `V2SessionStore.ownMaterialKey`
    ///   doubleRatchetSessions_v4   — `DoubleRatchet.storageKey` (legacy, one blob)
    ///
    /// Deliberately short: everything else stays private to the app, so a
    /// compromised extension reads the minimum.
    ///
    /// ⚠️ The v2 PER-PEER sessions (`oshi.v2.session.<peerUserKey>`) are NOT here
    /// and must not be. There is one per contact, they are created and rewritten
    /// constantly, and enumerating them at launch would be both slow and racy.
    /// `V2SessionStore` migrates each one lazily, on first access — see
    /// `sessionAccessGroup` there.
    /// Single source of truth for the account strings. The writers below now read
    /// these constants instead of holding their own copy, so the list cannot drift
    /// away from reality again — a rename breaks the build rather than silently
    /// emptying the extension's keychain.
    enum Account {
        static let identity = "privateKey"
        static let signing = "signingKey"
        /// `V2SessionStore.ownMaterialKey`
        static let v2OwnKeys = "oshi.v2.ownkeys"
        /// `DoubleRatchet.storageKey`
        static let legacyRatchetSessions = "doubleRatchetSessions_v4"
        /// Prefix of the v2 per-peer sessions — see the warning above.
        static let v2SessionPrefix = "oshi.v2.session."
    }

    static let sharedKeys: [String] = [
        Account.identity,
        Account.signing,
        Account.v2OwnKeys,
        Account.legacyRatchetSessions,
    ]

    // Items stay on this device only (never iCloud-synced) and become accessible
    // once the user has unlocked the device at least once since the most recent
    // reboot. This is the standard accessibility for apps with background work
    // (VoIP pushes, BGTask, silent notifications): the previous
    // `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` made the keychain unreadable
    // any time the screen was locked, which silently poisoned the IdentityManager
    // singleton on background launch and forced the user to re-onboard the next
    // time they opened the app.
    private static let accessibility = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

    static func save(key: String, data: Data, accessGroup: String? = nil) throws {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecValueData as String: data,
            kSecAttrAccessible as String: accessibility
        ]
        applyAccessGroup(accessGroup, to: &query)

        // Delete any existing item. Scoped to the SAME group as the add: deleting
        // without the attribute would reach across every accessible group and
        // could take out the copy we are about to migrate from.
        SecItemDelete(query as CFDictionary)

        // Add the new item
        let status = SecItemAdd(query as CFDictionary, nil)

        guard status == errSecSuccess else {
            throw KeychainError.unhandledError(status: status)
        }
    }

    /// `accessGroup: nil` searches every group this process can reach, which is
    /// what almost every caller wants — and what makes the migration invisible to
    /// them: the item is found whether it has moved yet or not.
    static func load(key: String, accessGroup: String? = nil) throws -> Data {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        applyAccessGroup(accessGroup, to: &query)

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        // CRITICAL: callers must distinguish "no item exists" (legitimate first
        // launch — show onboarding) from "item exists but locked" (transient —
        // do NOT clear identity, retry once the device is unlocked).
        if status == errSecItemNotFound {
            throw KeychainError.itemNotFound
        }
        if status == errSecInteractionNotAllowed {
            throw KeychainError.locked
        }
        guard status == errSecSuccess else {
            throw KeychainError.unhandledError(status: status)
        }

        guard let data = result as? Data else {
            throw KeychainError.unexpectedData
        }

        return data
    }
    
    static func delete(key: String, accessGroup: String? = nil) throws {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key
        ]
        applyAccessGroup(accessGroup, to: &query)
        
        let status = SecItemDelete(query as CFDictionary)
        
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError.unhandledError(status: status)
        }
    }
    
    static func update(key: String, data: Data) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key
        ]
        
        let attributes: [String: Any] = [
            kSecValueData as String: data
        ]
        
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        
        guard status == errSecSuccess else {
            throw KeychainError.unhandledError(status: status)
        }
    }
}

// MARK: - Access group plumbing

extension KeychainHelper {

    /// Adds `kSecAttrAccessGroup` unless we are on the Simulator.
    ///
    /// The Simulator has ONE keychain shared by every app and does not enforce
    /// entitlements. Passing a group there is at best ignored and has historically
    /// returned `errSecMissingEntitlement` (-34018) instead, which would make the
    /// migration below fail on exactly the platform we test it on — and look like
    /// a logic bug rather than a platform quirk.
    fileprivate static func applyAccessGroup(_ group: String?, to query: inout [String: Any]) {
        guard let group else { return }
        #if !targetEnvironment(simulator)
        query[kSecAttrAccessGroup as String] = group
        #endif
    }

    /// Every account name in this process's reachable keychain that starts with
    /// `prefix`.
    ///
    /// Needed because per-peer ratchet sessions are stored under DYNAMIC account
    /// names (`oshi.v2.session.<peerKey>`), so `migrateSharedItems`, which walks a
    /// fixed list, can never reach them. Without an enumeration the migration is
    /// lazy — it happens the next time the APP loads that particular peer's
    /// session — and until then OSHIShare still reports "no session" and falls
    /// back to opening the app. Which is exactly the symptom that whole change
    /// set was meant to remove.
    ///
    /// Returns [] on any error: this feeds a best-effort migration, and a locked
    /// keychain must degrade to "migrate nothing now", never to a thrown launch.
    static func allAccounts(prefix: String, accessGroup: String? = nil) -> [String] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecMatchLimit as String: kSecMatchLimitAll,
            kSecReturnAttributes as String: true
        ]
        applyAccessGroup(accessGroup, to: &query)
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let items = result as? [[String: Any]] else { return [] }
        return items.compactMap { $0[kSecAttrAccount as String] as? String }
                    .filter { $0.hasPrefix(prefix) }
    }

    /// Result of one migration attempt, so a caller can log something truthful
    /// instead of "done".
    struct MigrationReport: CustomStringConvertible {
        var alreadyShared: [String] = []
        var moved: [String] = []
        var absent: [String] = []
        var failed: [(key: String, reason: String)] = []

        var description: String {
            "moved=\(moved) alreadyShared=\(alreadyShared) absent=\(absent) "
            + "failed=\(failed.map { "\($0.key):\($0.reason)" })"
        }
    }

    /// Copies `sharedKeys` into the shared access group so OSHIShare can read them.
    ///
    /// 🔴 THE PRIVATE COPY IS NEVER DELETED, and that is deliberate.
    ///
    /// This is a COPY, not a move. Both copies live in the keychain under the same
    /// protection class, so duplicating buys no attacker anything — while deleting
    /// buys a catastrophe: an interrupted migration, a locked keychain, or a build
    /// that later drops the entitlement would leave the app unable to read its own
    /// ratchet sessions and identity. On this app that means every conversation
    /// stops decrypting and the user is shown onboarding as if they were new.
    ///
    /// This is the opposite call from the bot-token migration, and for a reason:
    /// there the old copy was plaintext in a preferences plist, so leaving it was
    /// the whole bug. Here the old copy is already in the keychain.
    ///
    /// The write is still verified by reading it back and comparing bytes before
    /// the key is reported as migrated — a write that "succeeded" but produced an
    /// unreadable item must not be counted as done.
    ///
    /// Idempotent: safe to call on every launch. A key already readable from the
    /// shared group is left alone.
    /// The three keychain operations the migration needs, injectable so the
    /// ALGORITHM can be tested where real access groups do not exist.
    ///
    /// This seam is not decoration. The Simulator has one keychain and ignores
    /// access groups, so with the real operations `migrateSharedItems()` reports
    /// "already shared" for everything and proves nothing at all. Tests substitute
    /// two genuinely separate stores and exercise the same code path.
    struct MigrationStore {
        var readShared: (String) throws -> Data
        var readPrivate: (String) throws -> Data
        var writeShared: (String, Data) throws -> Void

        static let keychain = MigrationStore(
            readShared: { try load(key: $0, accessGroup: sharedAccessGroup) },
            // `nil` group searches every reachable group, so this finds the
            // private item wherever it currently lives.
            readPrivate: { try load(key: $0) },
            writeShared: { try save(key: $0, data: $1, accessGroup: sharedAccessGroup) }
        )
    }

    @discardableResult
    static func migrateSharedItems(keys: [String] = sharedKeys,
                                   store: MigrationStore = .keychain) -> MigrationReport {
        var report = MigrationReport()

        for key in keys {
            // Already there? Nothing to do — this is what makes it safe to call
            // on every launch.
            if (try? store.readShared(key)) != nil {
                report.alreadyShared.append(key)
                continue
            }

            let existing: Data
            do {
                existing = try store.readPrivate(key)
            } catch KeychainError.itemNotFound {
                report.absent.append(key)
                continue
            } catch {
                // Locked keychain, or anything else — do NOT touch the old copy.
                report.failed.append((key, "\(error)"))
                continue
            }

            do {
                try store.writeShared(key, existing)
            } catch {
                report.failed.append((key, "write: \(error)"))
                continue
            }

            // A write that "succeeded" but produced an unreadable or different
            // item must not be counted as done.
            guard let verified = try? store.readShared(key), verified == existing else {
                report.failed.append((key, "read-back mismatch — private copy kept"))
                continue
            }

            report.moved.append(key)
        }

        return report
    }
}

enum KeychainError: Error {
    case unhandledError(status: OSStatus)
    case unexpectedData
    case itemNotFound
    /// errSecInteractionNotAllowed — item exists but the device hasn't been
    /// unlocked yet since boot. Caller must NOT treat this as "no identity".
    case locked

    var localizedDescription: String {
        switch self {
        case .unhandledError(let status):
            return NSLocalizedString("keychain_error_unhandled", comment: "") + ": \(status)"
        case .unexpectedData:
            return NSLocalizedString("keychain_error_unexpected_data", comment: "")
        case .itemNotFound:
            return NSLocalizedString("keychain_error_not_found", comment: "")
        case .locked:
            return "Keychain locked (device not yet unlocked since boot)"
        }
    }
}
