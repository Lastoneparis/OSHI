import Foundation
import Security
import UserNotifications

/// __PANIC_WIPE_2026_11_03__ The steps of `PanicWipePolicy`, performed.
///
/// Deliberately not `AccountDeletionView`. That flow asks the server first and
/// ABORTS if the request fails, which is right when someone has decided to
/// leave and wrong when someone is about to have their phone taken: it means a
/// wipe that can be defeated by jamming the signal. This path never waits on
/// the network, never asks, and cannot fail into "nothing happened".
///
/// Android mirror: `SettingsViewModel.panicWipe()`.
@MainActor
enum PanicWipe {

    /// A wipe in flight swallows further triggers — there is no confirmation
    /// dialog to absorb a shaking hand, and the fourth and fifth taps of a
    /// frightened triple-tap arrive as fresh requests.
    private static var running = false

    /// Both collaborators are passed in rather than reached for, because
    /// neither has a `shared`: `MeshNetworkManager` and `IdentityManager` are
    /// both `@EnvironmentObject`s created once in `SecureWeb3MessengerApp`.
    /// (`IdentityManager.shared` does appear in the tree — only in stale
    /// `.claude/worktrees/` copies, which is exactly why the compiler is a
    /// better source than grep here.) Taking them as parameters is what lets
    /// the MultipeerConnectivity advertiser actually be stopped and the live
    /// state actually be cleared, instead of either being quietly skipped.
    static func run(mesh: MeshNetworkManager?, identity: IdentityManager) {
        guard PanicWipePolicy.shouldStart(alreadyRunning: running) else { return }
        running = true

        stopRadios(mesh: mesh)
        cancelNotifications()
        wipeMedia()

        // Clears the LIVE state — message caches, ratchet sessions, group data.
        // It matters that this runs before the sweeps below and not after: an
        // in-memory manager still holding messages can flush them back to disk,
        // and a wipe undone by its own app a second later is worse than none.
        //
        // The cost is that `disconnectWallet()` ARCHIVES this identity first,
        // writing its conversations, groups, sessions and aliases into
        // per-identity `UserDefaults` keys so a later switch-back can restore
        // them. Those archives are the identity, written to disk moments after
        // it was "erased", so the defaults sweep below has to run after them —
        // which is the whole reason for the main-queue hop.
        identity.disconnectWallet()
        // __FOLDABLE_LAYOUT_2026_11_16__ In-memory composer drafts kept for a
        // fold / unfold hand-off are plaintext too.
        ThreadHandoff.shared.reset()

        // `disconnectWallet` enqueues some of its own work with
        // `DispatchQueue.main.async` — `MessageGroupManager.archiveDataForIdentity`
        // among it, which WRITES. The main queue is FIFO, so hopping once here
        // puts the sweeps strictly after everything it queued. Sweeping
        // synchronously instead would race those writes and lose: the archives
        // would land after the wipe that was supposed to remove them.
        DispatchQueue.main.async {
            wipeFiles()
            // __PANIC_WIPE_MISSED_APP_GROUP_2026_11_20__
            wipeAppGroup(container: FileManager.default.containerURL(
                            forSecurityApplicationGroupIdentifier: appGroupIdentifier),
                         defaults: UserDefaults(suiteName: appGroupIdentifier))
            wipeKeys()
            // __AT_REST_KEY_SURVIVED_WIPE_2026_11_10__ the keychain item is gone; the
            // in-memory copy must go too, or the next identity's data is sealed with
            // a destroyed key and reads back empty after the next launch.
            AtRestCipher.shared.forgetCachedKey()
            wipePreferences()
            // Dropping the onboarding flags above is what returns the app to
            // first run on its own. That is the only feedback a panic wipe
            // gives, and it is the right one: nothing to screenshot, nothing to
            // dismiss, a device that looks like a fresh install.
            running = false
        }
    }

    // MARK: - steps

    /// First, always: a phone that gets no further has at least gone quiet.
    /// Each teardown is independent — a radio that throws on the way down must
    /// not stop the keychain being emptied.
    private static func stopRadios(mesh: MeshNetworkManager?) {
        mesh?.stopAdvertising()
        mesh?.stopBrowsing()
        CrossPlatformMesh.shared.stop()
        MeshtasticManager.shared.stopScan()
        // forget: true — leaving the paired radio behind would reconnect a
        // wiped phone to the node it was carrying.
        MeshtasticManager.shared.disconnect(forget: true)
    }

    /// Delivered notifications are a copy of the message list that no store
    /// wipe reaches. Leave them and the previews are still readable on a locked
    /// screen — the surface the person panicking is most likely to be holding
    /// up to somebody.
    private static func cancelNotifications() {
        let centre = UNUserNotificationCenter.current()
        centre.removeAllDeliveredNotifications()
        centre.removeAllPendingNotificationRequests()
        if #available(iOS 16.0, *) {
            centre.setBadgeCount(0, withCompletionHandler: nil)
        }
    }

    /// Decrypted bytes, before the keys they no longer need. A file whose key
    /// is gone but which was never deleted is still recoverable by a tool that
    /// does not need our key at all.
    private static func wipeMedia() {
        clearContents(of: FileManager.default.temporaryDirectory)
        for url in FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask) {
            clearContents(of: url)
        }
    }

    /// Messages, contacts, call records, group media — the file-backed stores
    /// under Documents and Application Support.
    private static func wipeFiles() {
        for directory in [FileManager.SearchPathDirectory.documentDirectory,
                          .applicationSupportDirectory] {
            for url in FileManager.default.urls(for: directory, in: .userDomainMask) {
                clearContents(of: url)
            }
        }
    }

    /// Exhaustive by construction rather than by list, which is the same reason
    /// Android enumerates `shared_prefs` instead of naming eight files: a
    /// hand-written list of keychain accounts rots the first time someone adds
    /// a ninth, and the two that rotted last time were both private key
    /// material. Deleting by CLASS cannot miss one.
    private static func wipeKeys() {
        let classes: [CFString] = [
            kSecClassGenericPassword,
            kSecClassInternetPassword,
            kSecClassCertificate,
            kSecClassKey,
            kSecClassIdentity,
        ]
        for secClass in classes {
            // The status is deliberately dropped: `errSecItemNotFound` for a
            // class this app never used is the normal case, and there is
            // nothing a panic wipe could usefully do about a failure anyway.
            _ = SecItemDelete([kSecClass as String: secClass] as CFDictionary)
        }
    }

    /// Every default this app owns, the archives `disconnectWallet()` just
    /// wrote included, and the vault entry that would otherwise still offer
    /// this identity on the switch screen.
    private static func wipePreferences() {
        let defaults = UserDefaults.standard
        for key in defaults.dictionaryRepresentation().keys {
            defaults.removeObject(forKey: key)
        }
        // Belt as well as braces: the per-key loop is what reliably clears the
        // in-process cache, `removePersistentDomain` is what clears the plist.
        if let bundleID = Bundle.main.bundleIdentifier {
            defaults.removePersistentDomain(forName: bundleID)
        }
        for stored in IdentityVaultManager.shared.loadVault() {
            IdentityVaultManager.shared.deleteIdentity(id: stored.id)
        }
    }

    /// __PANIC_WIPE_MISSED_APP_GROUP_2026_11_20__ The App Group container is
    /// NOT under Documents / Application Support / Caches, so the sweeps above
    /// never reached it — and it is where the app keeps, for its extensions,
    /// `contacts_snapshot.json` (every contact's name + public key),
    /// `groups_snapshot.json` (group names), `identity_snapshot.json`,
    /// `share_avatars/` (contact photos), NSE prefetches and items shared in
    /// but not yet sent. After a "wipe" those files still listed exactly who
    /// the person talks to. Its UserDefaults suite goes too.
    static let appGroupIdentifier = "group.Moriceau-lab.Genesis"

    nonisolated static func wipeAppGroup(container: URL?, defaults: UserDefaults?) {
        if let container {
            let manager = FileManager.default
            let entries = (try? manager.contentsOfDirectory(at: container,
                                                            includingPropertiesForKeys: nil,
                                                            options: [])) ?? []
            for entry in entries {
                // `Library/Preferences` holds the suite's plist, which belongs
                // to cfprefsd: the defaults loop below empties it. Everything
                // else under `Library/` (Caches…) goes.
                if entry.lastPathComponent == "Library" {
                    let inner = (try? manager.contentsOfDirectory(at: entry,
                                                                  includingPropertiesForKeys: nil,
                                                                  options: [])) ?? []
                    for item in inner where item.lastPathComponent != "Preferences" {
                        try? manager.removeItem(at: item)
                    }
                    continue
                }
                try? manager.removeItem(at: entry)
            }
        }
        if let defaults {
            for key in defaults.dictionaryRepresentation().keys {
                defaults.removeObject(forKey: key)
            }
        }
    }

    // MARK: - helpers

    /// Empty a directory without removing the directory itself: iOS recreates
    /// some of these lazily and some not at all, and an app whose Documents
    /// directory has vanished behaves worse than one whose Documents directory
    /// is empty.
    private static func clearContents(of directory: URL) {
        let manager = FileManager.default
        guard let entries = try? manager.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil,
            options: []
        ) else { return }
        for entry in entries {
            try? manager.removeItem(at: entry)
        }
    }
}
