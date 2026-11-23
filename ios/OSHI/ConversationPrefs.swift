import Foundation
import Combine

/// __ANATOLE_1311_IOS__ Per-conversation choices the 1:1 list did not have.
///
/// Anatole's 13/11 report (tested on Android, ported here):
///   * N8 — a long press on a conversation offers Pin, Mute and Open contact.
///     iOS had neither a 1:1 pin nor a 1:1 mute (groups have their own
///     `isMuted`); both live here.
///   * N18 — a contact appears in the list the moment it is saved, before any
///     message. `MessageManager.addPendingConversation` already showed it, but
///     only in memory: the row vanished at the next launch until a message was
///     exchanged. The saved set below is what survives a relaunch.
///
/// Keys are stored in the canonical base64 spelling (`MessageBannerPolicy
/// .normalizedKey`) so a key that reached us as base64url on one transport and
/// standard base64 on another is one contact here too — the same rule the
/// notification extension uses for the muted/blocked snapshots.
final class ConversationPrefsStore: ObservableObject {

    static let shared = ConversationPrefsStore()

    static let pinnedDefaultsKey = "oshi.conversations.pinned"
    static let mutedDefaultsKey = "oshi.conversations.muted"
    static let savedDefaultsKey = "oshi.conversations.savedContacts"

    @Published private(set) var pinned: Set<String>
    @Published private(set) var muted: Set<String>
    /// canonical key -> when it was saved (the placeholder row's sort date).
    @Published private(set) var saved: [String: Date]

    private let defaults: UserDefaults
    /// Mirrors the muted set into the App Group for the notification
    /// extension. Injected so tests do not write the real container.
    private let publishMuted: ([String]) -> Void

    init(defaults: UserDefaults = .standard,
         publishMuted: @escaping ([String]) -> Void = { AppGroupBridge.writeMutedContactsSnapshot($0) }) {
        self.defaults = defaults
        self.publishMuted = publishMuted
        pinned = Set(defaults.stringArray(forKey: Self.pinnedDefaultsKey) ?? [])
        muted = Set(defaults.stringArray(forKey: Self.mutedDefaultsKey) ?? [])
        let raw = defaults.dictionary(forKey: Self.savedDefaultsKey) as? [String: Double] ?? [:]
        saved = raw.mapValues { Date(timeIntervalSince1970: $0) }
    }

    static func canonical(_ key: String) -> String {
        MessageBannerPolicy.normalizedKey(key)
    }

    // MARK: Pin (N8)

    func isPinned(_ key: String) -> Bool { pinned.contains(Self.canonical(key)) }

    func togglePinned(_ key: String) {
        let k = Self.canonical(key)
        guard !k.isEmpty else { return }
        if pinned.contains(k) { pinned.remove(k) } else { pinned.insert(k) }
        defaults.set(Array(pinned).sorted(), forKey: Self.pinnedDefaultsKey)
    }

    // MARK: Mute (N8)

    func isMuted(_ key: String) -> Bool { muted.contains(Self.canonical(key)) }

    func toggleMuted(_ key: String) {
        let k = Self.canonical(key)
        guard !k.isEmpty else { return }
        if muted.contains(k) { muted.remove(k) } else { muted.insert(k) }
        defaults.set(Array(muted).sorted(), forKey: Self.mutedDefaultsKey)
        publishMuted(Array(muted).sorted())
    }

    /// Called at launch so an extension that never saw a write (fresh App
    /// Group, restored backup) still learns the current set.
    func republishMuted() { publishMuted(Array(muted).sorted()) }

    // MARK: Saved contacts (N18)

    func isSaved(_ key: String) -> Bool { saved[Self.canonical(key)] != nil }

    /// Records a contact the user deliberately added. Keeps the FIRST date, so
    /// re-scanning someone does not move them to the top of the list.
    func markSaved(_ key: String, at date: Date = Date()) {
        let k = Self.canonical(key)
        guard !k.isEmpty, saved[k] == nil else { return }
        saved[k] = date
        persistSaved()
    }

    /// The user deleted the thread: it stays hidden, as on Android.
    func forgetSaved(_ key: String) {
        let k = Self.canonical(key)
        guard saved.removeValue(forKey: k) != nil else { return }
        persistSaved()
    }

    /// Identity reset / wipe paths.
    func forgetAllSaved() {
        guard !saved.isEmpty else { return }
        saved = [:]
        persistSaved()
    }

    /// Identity switch: the restored identity's own saved contacts.
    func replaceSaved(_ restored: [String: Date]) {
        saved = Dictionary(restored.map { (Self.canonical($0.key), $0.value) },
                           uniquingKeysWith: { min($0, $1) })
        persistSaved()
    }

    private func persistSaved() {
        defaults.set(saved.mapValues { $0.timeIntervalSince1970 }, forKey: Self.savedDefaultsKey)
    }

    // MARK: Ordering (pure, tested)

    /// Pinned conversations first, each half keeping the order it came in
    /// (the list is already newest-first). `key` extracts the conversation key.
    static func pinnedFirst<T>(_ items: [T], pinned: Set<String>, key: (T) -> String) -> [T] {
        guard !pinned.isEmpty else { return items }
        var top: [T] = []
        var rest: [T] = []
        for item in items {
            if pinned.contains(canonical(key(item))) { top.append(item) } else { rest.append(item) }
        }
        return top + rest
    }
}
