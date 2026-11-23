//
//  MeshCustodyStore.swift
//  OSHI
//
//  __MESH_CUSTODY_2026_10_10__ Store-and-forward AT A RELAY NODE.
//
//  The owner's ask, verbatim: "Nobody has Internet. OSHI → BLE → BLE → BLE →
//  store-and-forward → recipient."
//
//  The multi-hop half of that already worked. The store-and-forward half did
//  not exist at all: `MeshNetworkManager.forwardRelayMessage` forwards a
//  message NOW or drops it. Specifically, when every session's
//  `connectedPeers.filter { $0 != sourcePeer }` comes back empty, each session
//  `continue`s, `didRelay` stays false, and the packet is discarded in silence.
//  So the chain only ever worked when EVERY hop was awake simultaneously —
//  walk out of range for thirty seconds and the message was gone.
//
//  This is the missing middle term: a node that cannot pass a message on right
//  now takes custody of it and re-offers it when a peer appears.
//
//  NOT `MeshRelayManager` (MeshRelay.swift), which looks like this and is dead:
//  it has zero call sites anywhere in the target, and its dedup can never fire
//  because it mints `UUID()` and then asks whether the cache contains it. This
//  is built on `forwardRelayMessage`, the path that actually runs.
//
//  WHAT IT IS CARRYING, and the limits that follow. Held payloads are other
//  people's traffic. They are already ciphertext — `forwardRelayMessage` calls
//  `meshWireSafe()` before this ever sees them, so no cleartext is stored — and
//  this node cannot read them. But it is still somebody else's data on the
//  user's phone, costing their storage, so every bound here is deliberate:
//  bounded count, bounded bytes, a TTL, oldest-first eviction, and release the
//  moment it has been handed on.
//
//  It is store-and-FORWARD, not store-and-confirm. Custody ends when the
//  message has been passed to a peer that had not already seen it. There is no
//  app-level ack on the mesh to wait for, and inventing one here would mean
//  holding strangers' data indefinitely on the strength of a guess.
//

import Foundation

final class MeshCustodyStore {

    /// Kill switch. Default ON: relaying for other people is what the mesh IS,
    /// and holding a message for six hours is an extension of the forwarding
    /// this app already does in real time and already gamifies
    /// (`MeshBoosterManager`). Set `oshi_mesh_custody` to `false` in
    /// UserDefaults to restore the previous forward-now-or-drop behaviour
    /// exactly.
    ///
    /// A user-visible toggle is a product decision, not one to invent here.
    static let defaultsKey = "oshi_mesh_custody"
    static var enabled: Bool {
        if UserDefaults.standard.object(forKey: defaultsKey) == nil { return true }
        return UserDefaults.standard.bool(forKey: defaultsKey)
    }

    // MARK: - Limits

    /// How long a node will carry someone else's message.
    ///
    /// Six hours, not the ten minutes the dead `MeshRelayManager` used: ten
    /// minutes does not survive the case this exists for — a courier who walks
    /// out of range and comes back. And not a day: a phone is not a mail
    /// server, and the longer the window the more of other people's data sits
    /// on it. The relay's own store-and-forward (30 days) is the right place
    /// for a long wait; this is for the off-grid gap.
    static let maxAge: TimeInterval = 6 * 60 * 60

    /// Bounds, enforced together. Oldest is evicted first.
    static let maxCount = 200
    static let maxBytes = 4 * 1024 * 1024

    struct Held: Codable {
        /// Stored EXACTLY as `forwardRelayMessage` built it — `hopCount`
        /// already incremented and this node already appended to `seenBy`.
        ///
        /// That matters: re-originating a held message at hop 0 would hand it a
        /// fresh hop budget and let a deferred packet outlive the ceiling,
        /// which is the same class of bug the 2026-09-08 audit fixed when it
        /// made the hop ceiling OURS rather than the packet's.
        let relay: RelayMessage
        let takenAt: Date
        /// Encoded size at custody time, so the byte bound does not require
        /// re-encoding every record on every check.
        let byteCount: Int
    }

    private var held: [String: Held] = [:]   // keyed by message id
    private let lock = NSLock()

    init() {
        held = Self.loadFromDisk()
        prune()
    }

    // MARK: - Pure rules (testable without a radio or a disk)

    static func isExpired(takenAt: Date, now: Date = Date()) -> Bool {
        now.timeIntervalSince(takenAt) >= maxAge
    }

    /// Whether a store holding `count` messages / `bytes` bytes has room for
    /// one more of `incoming` bytes. A single message larger than the whole
    /// budget is refused outright rather than evicting everything for it.
    static func hasRoom(count: Int, bytes: Int, incoming: Int) -> Bool {
        incoming <= maxBytes && count < maxCount && bytes + incoming <= maxBytes
    }

    // MARK: - Custody

    /// Take custody of a message this node could not pass on.
    /// Returns true when it was stored.
    @discardableResult
    func take(_ relay: RelayMessage) -> Bool {
        guard Self.enabled else { return false }
        guard let data = try? JSONEncoder().encode(relay) else { return false }
        let incoming = data.count
        guard incoming <= Self.maxBytes else {
            OshiLog.mesh.info("📦 custody refused \(relay.message.id.prefix(8))… — \(incoming) B exceeds the whole budget")
            return false
        }

        lock.lock()
        // Already carrying it: keep the original custody time so a message that
        // keeps arriving cannot renew its own TTL forever.
        if held[relay.message.id] != nil { lock.unlock(); return false }

        // Make room, oldest first.
        while !Self.hasRoom(count: held.count,
                            bytes: held.values.reduce(0) { $0 + $1.byteCount },
                            incoming: incoming),
              let oldest = held.min(by: { $0.value.takenAt < $1.value.takenAt })?.key {
            held.removeValue(forKey: oldest)
        }
        guard Self.hasRoom(count: held.count,
                           bytes: held.values.reduce(0) { $0 + $1.byteCount },
                           incoming: incoming) else {
            lock.unlock()
            return false
        }

        held[relay.message.id] = Held(relay: relay, takenAt: Date(), byteCount: incoming)
        let snapshot = held
        lock.unlock()
        Self.save(snapshot)
        OshiLog.mesh.info("📦 custody taken for \(relay.message.id.prefix(8))… hop=\(relay.hopCount) (carrying \(snapshot.count))")
        return true
    }

    /// Messages still worth forwarding, oldest first so the longest-waiting
    /// leaves first. Expired records are dropped as a side effect.
    func readyToForward() -> [RelayMessage] {
        prune()
        lock.lock(); defer { lock.unlock() }
        return held.values.sorted { $0.takenAt < $1.takenAt }.map(\.relay)
    }

    /// Custody ends: the message has been handed to a peer that had not seen it.
    func release(_ messageId: String) {
        lock.lock()
        guard held.removeValue(forKey: messageId) != nil else { lock.unlock(); return }
        let snapshot = held
        lock.unlock()
        Self.save(snapshot)
    }

    /// Drop everything past its TTL.
    func prune() {
        lock.lock()
        let now = Date()
        let before = held.count
        held = held.filter { !Self.isExpired(takenAt: $0.value.takenAt, now: now) }
        let dropped = before - held.count
        let snapshot = held
        lock.unlock()
        if dropped > 0 {
            Self.save(snapshot)
            OshiLog.mesh.info("📦 custody expired \(dropped) message(s)")
        }
    }

    var count: Int { lock.lock(); defer { lock.unlock() }; return held.count }

    #if DEBUG
    func removeAllForTesting() {
        lock.lock(); held = [:]; lock.unlock()
        try? FileManager.default.removeItem(at: Self.fileURL)
    }
    #endif

    // MARK: - Persistence

    /// Application Support, not Caches: a message in custody must survive the
    /// disk pressure that Caches is purged under, which is exactly when a
    /// crowded off-grid scene is likely to be happening.
    private static var fileURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("oshi_mesh_custody.json")
    }

    private static func loadFromDisk() -> [String: Held] {
        guard let data = try? Data(contentsOf: fileURL),
              let all = try? JSONDecoder().decode([String: Held].self, from: data) else { return [:] }
        return all
    }

    private static func save(_ all: [String: Held]) {
        guard let data = try? JSONEncoder().encode(all) else { return }
        // Other people's ciphertext: protected at rest, and written atomically
        // so a kill mid-write cannot leave a half record that fails to decode
        // and silently empties the store.
        try? data.write(to: fileURL,
                        options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}
