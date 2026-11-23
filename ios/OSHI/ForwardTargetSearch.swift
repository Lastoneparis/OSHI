import Foundation

/// ForwardTargetSearch — matching and ordering for the forward picker.
///
/// Port of `OSHI-Android/.../service/ForwardTargetSearch.kt` and
/// `ForwardAffinityStore.kt`, deliberately sharing their rules so the two
/// platforms behave the same way at the one screen where picking the wrong row
/// sends a private message to a stranger.
///
/// WHY THIS EXISTS
/// ---------------
/// Reported: "when I click transfer, make sure there is a box to search the
/// target, with alias and also public key." iOS had no search box at all — the
/// picker was a plain `List { ForEach(conversations) }` with no filter
/// predicate anywhere in the file, so with many contacts the only way to reach
/// someone was to scroll. Android had a box that matched the display name only.
///
/// WHY ALIAS AND KEY MATCH WITH DIFFERENT CASE RULES
/// -------------------------------------------------
/// - An alias is a human name: case-insensitive substring, so "nat" finds
///   "Anatole".
/// - A public key is base64, where case is SIGNIFICANT. Two distinct people can
///   hold keys differing only in case, so a case-insensitive key match could
///   put the wrong contact under a typed prefix — and the next tap forwards to
///   them. A false negative makes someone paste again; a false positive leaks a
///   message. Keys therefore match case-sensitively, which costs nothing
///   because a key is pasted, never typed from memory.
///
/// Keys are normalised first (base64url → base64, padding dropped): the same
/// key is legitimately spelled several ways across transports, so a key copied
/// from a QR scan must match the same key stored from a mesh packet.
///
/// Key matching is PREFIX rather than substring — a fragment from the middle of
/// a base64 blob is coincidence, and coincidence here is a misdirected message.
enum ForwardTargetSearch {

    /// Below this many characters a query is treated as an alias only: two or
    /// three base64 characters prefix far too many keys to be a useful search.
    static let minKeyQuery = 4

    /// Canonical form for key comparison. Mirrors
    /// `CallAdmissionPolicy.normalizeKey` on Android and
    /// `MessageRepository.normalizeBase64Key`.
    static func normalizeKey(_ key: String) -> String {
        key.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Does this target match `query`?
    ///
    /// - Parameter publicKey: the target's key, or nil for a group.
    static func matches(query: String, name: String, publicKey: String?) -> Bool {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return true }

        // Alias: case-insensitive substring.
        if name.range(of: trimmed, options: .caseInsensitive) != nil { return true }

        // Key: normalised, case-sensitive prefix.
        if let publicKey, trimmed.count >= minKeyQuery {
            let normalizedQuery = normalizeKey(trimmed)
            if !normalizedQuery.isEmpty,
               normalizeKey(publicKey).hasPrefix(normalizedQuery) {
                return true
            }
        }
        return false
    }
}

/// Who this device forwards to most, so they are offered first.
///
/// Requested: "the contact we use or forward the most on top of the view."
/// Nothing tracked it; the picker showed conversations in last-message order,
/// which is the messages-list ordering rather than a forwarding one.
///
/// The score is the count with recency breaking ties. A pure count ossifies —
/// whoever you forwarded to most in week one stays top forever. Pure recency
/// thrashes — one forward to a stranger evicts the person you use daily.
///
/// PRIVACY: a local-only tally of counts and timestamps in this app's own
/// defaults. Never transmitted, holds no message content. Worth stating plainly
/// because a ranked contact list IS a social graph, and that is precisely what
/// this app exists not to leak.
enum ForwardAffinityStore {

    private static let storageKey = "oshi.forward.affinity"
    private static let maxEntries = 200

    struct Affinity: Codable {
        let count: Int
        let lastUsedAt: TimeInterval
    }

    private static var cache: [String: Affinity]?

    private static func load() -> [String: Affinity] {
        if let cache { return cache }
        guard let data = UserDefaults.standard.data(forKey: storageKey),
              let decoded = try? JSONDecoder().decode([String: Affinity].self, from: data) else {
            cache = [:]
            return [:]
        }
        cache = decoded
        return decoded
    }

    private static func persist(_ map: [String: Affinity]) {
        cache = map
        if let data = try? JSONEncoder().encode(map) {
            UserDefaults.standard.set(data, forKey: storageKey)
        }
    }

    /// Note that a message was forwarded to `targetId`.
    ///
    /// Keys are normalised so one contact cannot accumulate two tallies under
    /// two spellings of the same key.
    static func record(_ targetId: String, now: TimeInterval = Date().timeIntervalSince1970) {
        let trimmed = targetId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let key = ForwardTargetSearch.normalizeKey(trimmed)
        var map = load()
        map[key] = Affinity(count: (map[key]?.count ?? 0) + 1, lastUsedAt: now)

        // Bound the tally, dropping the least useful entries — lowest count,
        // then oldest — so a rarely-used contact cannot evict a frequent one
        // merely by being touched later.
        if map.count > maxEntries {
            let kept = map.sorted {
                $0.value.count != $1.value.count
                    ? $0.value.count > $1.value.count
                    : $0.value.lastUsedAt > $1.value.lastUsedAt
            }.prefix(maxEntries)
            map = Dictionary(uniqueKeysWithValues: kept.map { ($0.key, $0.value) })
        }
        persist(map)
    }

    static func score(for targetId: String) -> Affinity? {
        load()[ForwardTargetSearch.normalizeKey(targetId)]
    }

    /// Re-ranks `items` most-forwarded first, preserving the incoming order as
    /// the tie-break. A re-rank rather than a replacement: a user who has never
    /// forwarded anything still sees the list's own sensible order.
    static func rank<T>(_ items: [T], idOf: (T) -> String) -> [T] {
        let map = load()
        guard !map.isEmpty else { return items }
        return items.enumerated().sorted { lhs, rhs in
            let l = map[ForwardTargetSearch.normalizeKey(idOf(lhs.element))]
            let r = map[ForwardTargetSearch.normalizeKey(idOf(rhs.element))]
            if (l?.count ?? 0) != (r?.count ?? 0) { return (l?.count ?? 0) > (r?.count ?? 0) }
            if (l?.lastUsedAt ?? 0) != (r?.lastUsedAt ?? 0) { return (l?.lastUsedAt ?? 0) > (r?.lastUsedAt ?? 0) }
            return lhs.offset < rhs.offset   // stable
        }.map { $0.element }
    }
}
