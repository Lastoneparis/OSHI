//
//  CrossPlatformMesh.swift
//  OSHI - Cross-Platform Mesh Network (iOS ↔ Android)
//
//  Uses BLE + Bonjour + TCP to enable direct mesh communication
//  between iOS and Android devices WITHOUT internet.
//
//  Protocol:
//  1. BLE advertising for presence detection
//  2. Bonjour/mDNS for service discovery
//  3. TCP sockets for data transfer
//  4. Same message format as internal mesh
//

import Foundation
import CoreBluetooth
import Network

// MARK: - Reconnect Policy

/// When a peer we can SEE but are not TALKING to should be dialled again.
///
/// The twin of `OSHI-Android/.../network/mesh/MeshReconnectPolicy.kt`, down to
/// the numbers — `MeshReconnectParityTest` on the Android side reads both files
/// and fails if they drift. Pure, so every branch is reachable from a unit test
/// instead of needing two phones in one room.
///
/// ───────────────────── the defect this type exists for ─────────────────────
///
/// Found on Android on 2026-11-07 from a user screenshot (0 connected, 1
/// nearby, a Connect button that did nothing) and then looked for here. iOS had
/// the same hole and one more on top:
///
///   * `connectToPeer` is called from the BLE characteristic read and from
///     `netServiceDidResolveAddress`, and from nowhere else. A `NetServiceBrowser`
///     reports a service once; a peer already on the network when browsing
///     starts is never reported again. So an `NWConnection` that goes `.failed`
///     is that peer's only attempt, for the life of the process.
///   * `.failed` calls `cleanupConnection(for:)`, which looks the connection up
///     in `tcpConnections` — where it was never put, because that only happens
///     on `.ready`. The failure path was therefore a complete no-op.
///   * And the row had no button at all. On 2026-08-18 the Connect button was
///     REMOVED from cross-platform peer rows, with the reasoning written into
///     `CompactPeerRow`: "`connectToPeer` and `cleanupConnection` are private in
///     `CrossPlatformMesh`, which connects by itself on discovery. There is no
///     user action to offer on this transport." The premise was wrong — it
///     connects by itself exactly once — and removing a dead control left the
///     user with no control, which is the same mistake as relabelling one.
///     PeersView has no Refresh either, and its three toggles all drive
///     `MeshNetworkManager` (MultipeerConnectivity), not this class. An iPhone
///     looking at a stranded Android peer had **zero** affordances.
enum MeshReconnectPolicy {

    /// What to do about one discovered-but-unconnected peer, right now.
    enum Attempt {
        /// There is a live connection for this key. Nothing to do.
        case alreadyConnected
        /// Open a TCP connection to the peer's announced ip/port.
        case dial
        /// We hold no address for this peer — only a fresh browse can supply one.
        case rediscover
        /// A dial is already in flight, or the backoff has not elapsed.
        case wait
    }

    /// How often the automatic sweep runs.
    static let sweepIntervalMs: Int = 15_000

    /// Floor on automatic discovery restarts — a browse restart re-resolves
    /// every service on the network.
    static let rediscoverMinIntervalMs: Int = 60_000

    /// Backoff ladder indexed by consecutive failures. The first retry is quick
    /// on purpose: the common cause is a peer mid-handshake or briefly
    /// backgrounded. It tops out rather than giving up — a peer still being
    /// announced on the LAN is still worth a try.
    static let backoffMs: [Int] = [0, 3_000, 10_000, 30_000, 60_000, 120_000]

    /// Delay owed before the next dial after `failures` consecutive failures.
    static func backoff(failures: Int) -> Int {
        backoffMs[min(max(failures, 0), backoffMs.count - 1)]
    }

    /// - Parameters:
    ///   - connected: a live connection already exists for this peer's key
    ///   - hasAddress: the peer announced both an ip and a port
    ///   - dialInFlight: a connect attempt for this key has not returned yet
    ///   - failures: consecutive failed dials for this key
    ///   - msSinceLastDial: elapsed time since the last dial was started
    ///   - userAsked: the user pressed Connect on this very peer
    static func decide(
        connected: Bool,
        hasAddress: Bool,
        dialInFlight: Bool,
        failures: Int,
        msSinceLastDial: Int,
        userAsked: Bool
    ) -> Attempt {
        if connected { return .alreadyConnected }
        if dialInFlight { return .wait }
        if !hasAddress { return .rediscover }
        // A press is a fact the backoff does not have: the user is holding the
        // phone next to the other one. Honouring a two-minute ladder at that
        // moment is what makes a button feel dead.
        if userAsked { return .dial }
        return msSinceLastDial >= backoff(failures: failures) ? .dial : .wait
    }

    /// The address to dial when a resolve hands back several.
    ///
    /// Bonjour returns every address a peer published. The old code took the
    /// first IPv4 and, when there was none, gave up without connecting at all.
    /// Preference: a routable IPv4; then an IPv6 that is not loopback and not an
    /// unscoped link-local; then a 169.254 IPv4, which is what two phones
    /// self-assign on a Wi-Fi network with no DHCP server — a last resort, never
    /// a choice, exactly as `lanIPv4Address()` treats it on the announce side.
    static func pickDialAddress(_ candidates: [String]) -> String? {
        let list = candidates.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        let v4 = list.filter { !$0.contains(":") }
            .filter { !$0.hasPrefix("127.") && $0 != "0.0.0.0" }
        let v6 = list.filter { $0.contains(":") }
            .filter { $0 != "::1" && $0 != "::" }
            // A link-local v6 is dialable only with a zone index ("%en0").
            .filter { !$0.lowercased().hasPrefix("fe80:") || $0.contains("%") }

        return v4.first(where: { !$0.hasPrefix("169.254.") }) ?? v6.first ?? v4.first
    }
}

/// The outcome of a user asking, in so many words, to connect to one peer.
enum MeshConnectRequest {
    /// A connection is already up — the UI should be showing this peer connected.
    case alreadyConnected
    /// A TCP connection to the peer's announced address is being opened.
    case dialing
    /// No address for this peer yet; browsing has been restarted to find one.
    case searching
    /// We have never seen this key. Browsing has been restarted anyway.
    case notDiscovered
}

// MARK: - Cross-Platform Mesh Manager

class CrossPlatformMesh: NSObject, ObservableObject {
    static let shared = CrossPlatformMesh()

    // Published state
    @Published var isRunning = false
    @Published var crossPlatformPeers: [CrossPlatformPeer] = []
    @Published var connectedPeers: [CrossPlatformPeer] = []
    @Published var diagnostics: String = ""

    // Diagnostic counters
    private var totalBLEScanResults = 0
    private var oshiBLEMatches = 0

    // BLE (created lazily in start() to avoid permission prompt at launch)
    private var centralManager: CBCentralManager?
    private var peripheralManager: CBPeripheralManager?
    private var discoveredPeripherals: [UUID: CBPeripheral] = [:]

    // Bonjour
    /// __ONE_READ_MUST_SEE_ONE_DOCUMENT_2026_10_23__ The announce as it was when
    /// the central asked for offset 0, so the rest of a long read is the rest of
    /// THAT document. Mirrors Android's `gattPayloadSnapshot`.
    private var gattPayloadSnapshot = Data()

    private var netService: NetService?
    private var netServiceBrowser: NetServiceBrowser?
    private var resolvedServices: [NetService] = []
    private var bonjourServiceToKey: [String: String] = [:]  // service.name → publicKey (for removal)

    // TCP Server
    private var tcpListener: NWListener?
    private var tcpConnections: [String: NWConnection] = [:]  // publicKey -> connection

    // __NOTHING_EVER_DIALLED_A_PEER_TWICE_2026_11_07__ — see MeshReconnectPolicy.
    // All four are owned by `stateQueue`, like `tcpConnections` above.

    /// Consecutive failed dials per peer key. Cleared on a successful connect.
    private var dialFailures: [String: Int] = [:]

    /// When the last dial for a peer key was STARTED, not when it finished.
    private var lastDialAt: [String: TimeInterval] = [:]

    /// Peer keys with a connect attempt still in flight.
    private var dialsInFlight = Set<String>()

    /// Last Bonjour browse restart, for the sweep's floor.
    private var lastDiscoveryRestartAt: TimeInterval = 0

    // Identity
    private var myPublicKey: String = ""
    private var myDisplayName: String = ""
    private var myPort: UInt16 = 0

    // Constants - MUST match Android
    // CRITICAL: Use 16-bit UUID form for BLE advertising!
    // iOS 128-bit UUIDs in advertising packets are often INVISIBLE to Android scanners
    // (pushed to iOS-only "overflow area" or not parsed by Android's ScanRecord).
    // 16-bit UUIDs (4 bytes in ad packet vs 18 for 128-bit) work reliably on ALL platforms.
    // CoreBluetooth treats CBUUID("0541") == CBUUID("00000541-0000-1000-8000-00805F9B34FB")
    static let SERVICE_UUID = CBUUID(string: "0541")
    static let CHAR_UUID = CBUUID(string: "0542")
    static let BONJOUR_TYPE = "_oshi-mesh._tcp."  // Trailing dot required for Bonjour
    static let BONJOUR_DOMAIN = "local."

    // Periodic scan restart
    private var scanRestartTimer: DispatchSourceTimer?
    private var reconnectSweepTimer: DispatchSourceTimer?
    private var diagnosticsTimer: DispatchSourceTimer?
    private var bleRetryTimer: DispatchSourceTimer?
    private var routeCleanupTimer: DispatchSourceTimer?
    private var identityAnnounceTimer: DispatchSourceTimer?

    // GATT service add → advertising sequencing
    private var pendingAdvertising = false

    // Relay settings
    private let maxHops = 50
    // `internal` (not `private`) only so `@testable import OSHI` can assert the
    // eviction below — see OSHITests/MeshSeenSetTests.swift. No app code outside
    // this file touches it.
    var seenMessageIds = Set<String>()

    /// __SEEN_SET_EVICTS_OLDEST_2026_09_12__ Insertion order for `seenMessageIds`.
    ///
    /// The cleanup here used to be `seenMessageIds = Set(seenMessageIds.suffix(2500))`.
    /// `Set` is UNORDERED, so `suffix` kept 2500 ARBITRARY ids and discarded arbitrary
    /// ones — including ids still actively in flight. Once a session had seen more than
    /// 5000 ids (ordinary over a long session, and forceable by a peer emitting junk
    /// ids), a genuinely re-arriving TEXT_MESSAGE/MEDIA_MESSAGE could pass the
    /// `contains` check a second time and be delivered to the UI twice.
    ///
    /// MeshNetworkManager hit this exact defect and fixed it with `noteSeenMessageID`;
    /// the fix was never ported to this file. Same shape, same reasoning.
    var seenMessageOrder: [String] = []

    let seenMessageIdsCap = 5_000
    let seenMessageIdsKeep = 2_500

    // MARK: - Public-key spelling (__MESH_RECIPIENT_SPELLING_2026_10_12__)

    /// Fold the two spellings an OSHI public key can arrive in into one:
    /// base64url (`-`, `_`) to standard base64 (`+`, `/`), `=` padding dropped.
    ///
    /// `MessageManager.canonicalIdentity` does this for everything STORED on this
    /// device. Nothing did it for the keys that arrive on a mesh frame, which is
    /// where the two platforms meet.
    static func normalizedMeshKey(_ key: String) -> String {
        return key.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Do these two strings name the same OSHI identity?
    ///
    /// Compares the folded spellings, then falls back to the first-32-character
    /// prefix match this file has always had, for any skew the fold misses.
    static func meshKeysMatch(_ a: String, _ b: String) -> Bool {
        let x = normalizedMeshKey(a)
        let y = normalizedMeshKey(b)
        guard !x.isEmpty, !y.isEmpty else { return false }
        if x == y { return true }
        let prefixLen = min(x.count, y.count, 32)
        guard prefixLen >= 16 else { return false }
        return x.prefix(prefixLen).lowercased() == y.prefix(prefixLen).lowercased()
    }

    /// Is a mesh frame carrying `recipientPublicKey` addressed to us?
    ///
    /// THIS IS THE BUG Anatole reported on 2026-10-12 ("mesh messaging works from
    /// iOS to Android but not from Android to iOS"). Answering `false` does not
    /// park the frame: `handleContentMessage` hands it to `relayToRecipient`,
    /// which holds no route to a key that is in fact OUR OWN, and the message is
    /// DROPPED IN SILENCE — one `OshiLog` line and nothing in the thread.
    ///
    /// The old test was `recipient.trimmed.lowercased() == mine.trimmed.lowercased()`
    /// with the same prefix fallback, and neither half reconciles base64url with
    /// standard base64: `lowercased()` leaves `-` as `-` and `_` as `_`. A 43-char
    /// key carries a `+` or a `/` in its first 32 characters about two times in
    /// three, so for most contacts BOTH halves failed.
    ///
    /// And it failed in one direction only, which is exactly how it was reported.
    /// We address an Android peer with a key `canonicalIdentity` has already folded
    /// to standard base64, and Android's own key is standard base64 — so iOS to
    /// Android matched. Android has no equivalent funnel: its contact rows come
    /// from a QR scan, the v2 relay and the VPS in either spelling (its own code
    /// records contacts "stored under two slightly-different keys (whitespace,
    /// base64 vs base64url)"), and whichever spelling the row held went out in the
    /// frame. So every Android to iPhone mesh message met this check wearing the
    /// wrong spelling and died here.
    ///
    /// An empty recipient is a broadcast and is for everyone (unchanged).
    static func isAddressedToUs(_ recipientPublicKey: String, myPublicKey: String) -> Bool {
        if recipientPublicKey.isEmpty { return true }
        return meshKeysMatch(recipientPublicKey, myPublicKey)
    }

    /// Single entry point so no call site can insert without recording order.
    /// Callers must already be on `stateQueue` — every one of them is.
    func noteSeenMessageId(_ id: String) {
        guard seenMessageIds.insert(id).inserted else { return }
        seenMessageOrder.append(id)
        guard seenMessageOrder.count > seenMessageIdsCap else { return }
        let drop = seenMessageOrder.count - seenMessageIdsKeep
        for old in seenMessageOrder.prefix(drop) { seenMessageIds.remove(old) }
        seenMessageOrder.removeFirst(drop)
    }
    private var routingTable: [String: RouteInfo] = [:]  // publicKey -> route
    // FREEZE FIX: throttle the "No direct/routed path" log per recipient.
    private var lastNoRouteLogAt: [String: Date] = [:]

    // Track connections we've already sent identity to (prevent infinite exchange loop)
    private var sentIdentityTo = Set<String>()  // publicKey set

    // 🔐 PEER AUTH (proof-of-possession, additive/advisory — see MeshPeerAuth.swift).
    // Cross-platform (iOS↔Android) DH challenge-response over the JSON/TCP wire.
    // `verifiedPeerKeys` is a SIGNAL only; routing/delivery never consult it, so
    // an unverified or older/Android peer is served exactly as before.
    @Published private(set) var verifiedPeerKeys: Set<String> = []
    // Nonces we sent and are awaiting a response for, keyed by peer public key.
    private var pendingChallengeNonces: [String: Data] = [:]  // guarded by stateQueue

    // 🔧 FIX: Serial queue to protect mutable state from concurrent access
    // NWConnection/BLE callbacks run on background queues — all shared state mutations must be serialized
    // `internal` so a test can hop onto the very queue that guards this state
    // instead of racing it (the reset in `stop()` lands here asynchronously).
    let stateQueue = DispatchQueue(label: "com.oshi.mesh.state", qos: .userInitiated)

    // Callbacks
    private var messageCallbacks: [(CrossPlatformMessage) -> Void] = []

    // MARK: - Tolerant state lookups
    //
    // Android hardened every one of ITS lookups for this skew long ago
    // (`findStreamForRecipient`, `isPeerReachable`, `isPeerDiscovered`,
    // `findConnectedPeer` — "without this, the sendTextMessage routing falls
    // through to VPS even when the peer is connected"). iOS kept plain subscripts
    // and `==`, so the mirror of the reported bug sat here waiting: an Android key
    // spelled the other way is a live TCP connection we cannot find, and the send
    // silently prefers the relay. Same rule, same place, both directions.

    /// The live connection for `recipientPublicKey`, tolerant of key spelling.
    /// MUST be called on `stateQueue`.
    private func connectionForRecipient(_ recipientPublicKey: String) -> (key: String, connection: NWConnection)? {
        if let direct = tcpConnections[recipientPublicKey] {
            return (recipientPublicKey, direct)
        }
        if let hit = tcpConnections.first(where: { CrossPlatformMesh.meshKeysMatch($0.key, recipientPublicKey) }) {
            return (hit.key, hit.value)
        }
        return nil
    }

    /// The route for `recipientPublicKey`, tolerant of key spelling.
    /// MUST be called on `stateQueue`.
    private func routeForRecipient(_ recipientPublicKey: String) -> RouteInfo? {
        if let direct = routingTable[recipientPublicKey] { return direct }
        return routingTable.first(where: { CrossPlatformMesh.meshKeysMatch($0.key, recipientPublicKey) })?.value
    }

    // MARK: - Centralized Peer Dedup

    /// Add a discovered peer, deduplicating by publicKey AND trimmed displayName.
    /// Must be called on main thread.
    private func addDiscoveredPeerOnMain(_ peer: CrossPlatformPeer) {
        let trimmedName = peer.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        let isDuplicate = crossPlatformPeers.contains {
            (!peer.publicKey.isEmpty && $0.publicKey == peer.publicKey) ||
            $0.displayName.trimmingCharacters(in: .whitespacesAndNewlines) == trimmedName
        }
        if !isDuplicate {
            var p = peer
            p.displayName = trimmedName
            crossPlatformPeers.append(p)
        } else if let idx = crossPlatformPeers.firstIndex(where: {
            (!peer.publicKey.isEmpty && $0.publicKey == peer.publicKey) ||
            $0.displayName.trimmingCharacters(in: .whitespacesAndNewlines) == trimmedName
        }) {
            // Update existing with fresh data
            if let ip = peer.ipAddress { crossPlatformPeers[idx].ipAddress = ip }
            if let port = peer.port { crossPlatformPeers[idx].port = port }
            if !peer.publicKey.isEmpty { crossPlatformPeers[idx].publicKey = peer.publicKey }
            crossPlatformPeers[idx].displayName = trimmedName
        }
    }

    /// Add a connected peer, deduplicating by publicKey AND trimmed displayName.
    /// Must be called on main thread.
    private func addConnectedPeerOnMain(_ peer: CrossPlatformPeer) {
        let trimmedName = peer.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        let isDuplicate = connectedPeers.contains {
            (!peer.publicKey.isEmpty && $0.publicKey == peer.publicKey) ||
            $0.displayName.trimmingCharacters(in: .whitespacesAndNewlines) == trimmedName
        }
        if !isDuplicate {
            var p = peer
            p.displayName = trimmedName
            connectedPeers.append(p)
            // Notify GroupMessaging so it can push groups this peer is a member of
            if !p.publicKey.isEmpty {
                NotificationCenter.default.post(
                    name: NSNotification.Name("CrossPlatformPeerConnected"),
                    object: nil,
                    userInfo: ["peerPublicKey": p.publicKey]
                )
            }
        }
    }

    // MARK: - Initialization
    // BLE managers are created lazily in start() to avoid triggering
    // the Bluetooth permission dialog at app launch.

    // `internal` (like MeshNetworkManager's) so a test can hold an instance of
    // its own rather than mutating `shared`. This init starts NOTHING: no BLE,
    // no Bonjour, no socket — all of that happens in `start()`.
    override init() {
        super.init()
        // Do NOT create CBCentralManager/CBPeripheralManager here.
        // Creating them triggers the Bluetooth permission dialog immediately.
        // They are initialized in start() when mesh features are actually used.
    }

    // MARK: - Public API

    func start(publicKey: String, displayName: String) {
        // Prevent double-start (called from both initializeApp AND MeshNetworkManager.startAll)
        guard !isRunning else {
            OshiLog.mesh.info("[CrossPlatformMesh] ⚠️ Already running, skipping duplicate start()")
            return
        }
        guard !publicKey.isEmpty else {
            OshiLog.mesh.info("[CrossPlatformMesh] ⚠️ Cannot start: empty public key")
            return
        }

        myPublicKey = publicKey
        myDisplayName = displayName

        OshiLog.mesh.info("[CrossPlatformMesh] 🚀 Starting...")
        OshiLog.mesh.info("   Public key: \(publicKey.prefix(16))...")
        OshiLog.mesh.info("   Display name: \(displayName)")

        // Lazily create BLE managers on first start() call
        // (not in init(), to avoid triggering Bluetooth permission at app launch)
        if centralManager == nil {
            centralManager = CBCentralManager(delegate: self, queue: DispatchQueue.global(qos: .userInitiated))
        }
        if peripheralManager == nil {
            peripheralManager = CBPeripheralManager(delegate: self, queue: DispatchQueue.global(qos: .userInitiated))
        }

        // Start TCP server first to get port
        startTCPServer()

        // Start BLE advertising/scanning (may need retry if BT not yet ready)
        startBLEAdvertising()
        startBLEScanning()

        // Schedule BLE retry in case BT wasn't ready at start time
        // (e.g., Bluetooth permission dialog still showing)
        scheduleBLERetry()

        // Start Bonjour — MUST run on main thread for run loop
        DispatchQueue.main.async { [weak self] in
            self?.startBonjourAdvertising()
            self?.startBonjourBrowsing()
        }

        // Periodic BLE scan restart for robustness (every 30s)
        scheduleScanRestart()

        // Periodic diagnostics update (every 5s)
        scheduleDiagnosticsUpdate()

        // Periodic stale route cleanup (every 60s, removes entries older than 5 minutes)
        scheduleRouteCleanup()

        // Periodic 2-hop identity gossip (every 30s)
        scheduleIdentityAnnounce()

        // __NOTHING_EVER_DIALLED_A_PEER_TWICE_2026_11_07__ Retry peers that are
        // discovered but not connected (every 15s).
        scheduleReconnectSweep()

        DispatchQueue.main.async {
            self.isRunning = true
        }
    }

    /// Tear down and restart with a new identity. Required after the user
    /// rotates / regenerates their public key — otherwise the stale `myPublicKey`
    /// keeps being stamped on every outgoing CrossPlatformMessage and the peer
    /// (Android) attributes the message to the OLD conversation instead of
    /// creating a fresh one for the new identity.
    func reconfigureIdentity(publicKey: String, displayName: String) {
        guard !publicKey.isEmpty else {
            OshiLog.mesh.info("[CrossPlatformMesh] ⚠️ reconfigureIdentity called with empty public key — ignoring")
            return
        }
        OshiLog.mesh.info("[CrossPlatformMesh] 🔄 Reconfiguring identity \(myPublicKey.prefix(12))… → \(publicKey.prefix(12))…")
        stop()
        // start() short-circuits when isRunning, but stop() flips it to false
        // on the main queue. Defer restart so the flag has actually settled.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
            self?.start(publicKey: publicKey, displayName: displayName)
        }
    }

    func stop() {
        OshiLog.mesh.info("[CrossPlatformMesh] Stopping...")

        scanRestartTimer?.cancel()
        scanRestartTimer = nil
        diagnosticsTimer?.cancel()
        diagnosticsTimer = nil
        bleRetryTimer?.cancel()
        bleRetryTimer = nil
        routeCleanupTimer?.cancel()
        routeCleanupTimer = nil
        identityAnnounceTimer?.cancel()
        identityAnnounceTimer = nil
        reconnectSweepTimer?.cancel()
        reconnectSweepTimer = nil

        stopBLEAdvertising()
        stopBLEScanning()
        stopBonjourAdvertising()
        stopBonjourBrowsing()
        stopTCPServer()

        stateQueue.async { [weak self] in
            self?.sentIdentityTo.removeAll()
            self?.routingTable.removeAll()
            self?.seenMessageIds.removeAll()
            self?.seenMessageOrder.removeAll()   // keep the order log in step with the set
            // A peer that failed five times before the user toggled the mesh
            // off must not come back owing two minutes of backoff.
            self?.dialFailures.removeAll()
            self?.lastDialAt.removeAll()
            self?.dialsInFlight.removeAll()
        }
        lastDiscoveryRestartAt = 0

        DispatchQueue.main.async {
            self.isRunning = false
            self.crossPlatformPeers.removeAll()
            self.connectedPeers.removeAll()
        }
    }

    /// Send message to a peer (direct or via relay)
    /// Returns true only if the payload was handed to a live TCP connection.
    /// `@discardableResult` keeps every fire-and-forget caller source-compatible.
    @discardableResult
    func sendMessage(to recipientPublicKey: String, content: String, type: String = "TEXT_MESSAGE") -> Bool {
        let message = CrossPlatformMessage(
            id: UUID().uuidString,
            type: type,
            senderPublicKey: myPublicKey,
            senderName: myDisplayName,
            recipientPublicKey: recipientPublicKey,
            payload: content,
            timestamp: Date().timeIntervalSince1970 * 1000,  // Milliseconds to match Android
            hopCount: 0,
            maxHops: maxHops,
            seenBy: [myPublicKey],
            platform: "ios"
        )

        return sendOrRelay(message)
    }

    /// Broadcast a message to ALL connected cross-platform peers (for group ads, etc.)
    func broadcastToAllPeers(content: String, type: String) {
        // 🔧 FIX: Serialize tcpConnections access
        stateQueue.async { [weak self] in
            guard let self = self, !self.tcpConnections.isEmpty else { return }
            let message = CrossPlatformMessage(
                id: UUID().uuidString,
                type: type,
                senderPublicKey: self.myPublicKey,
                senderName: self.myDisplayName,
                recipientPublicKey: "",
                payload: content,
                timestamp: Date().timeIntervalSince1970 * 1000,
                hopCount: 0,
                maxHops: self.maxHops,
                seenBy: [self.myPublicKey],
                platform: "ios"
            )
            let data = message.toJSON()
            OshiLog.mesh.info("[CrossPlatformMesh] 📡 Broadcasting \(type) to \(self.tcpConnections.count) cross-platform peer(s)")
            for (_, connection) in self.tcpConnections {
                self.sendData(data, to: connection)
            }
        }
    }

    /// Send a relay message (for MeshNetworkManager integration)
    @discardableResult
    func relayMessage(_ originalMessage: Data, to recipientPublicKey: String) -> Bool {
        guard let content = String(data: originalMessage, encoding: .utf8) else { return false }
        return sendMessage(to: recipientPublicKey, content: content, type: "RELAY")
    }

    /// Add callback for received messages
    func addMessageCallback(_ callback: @escaping (CrossPlatformMessage) -> Void) {
        messageCallbacks.append(callback)
    }

    /// Check if peer is reachable.
    ///
    /// Thread-safety: the routing-table read now goes through `stateQueue.sync`,
    /// matching `isPeerLiveConnected` below — the previous bare subscript raced
    /// every mutation of `routingTable`. Same contract as that one: never call
    /// this from inside a `stateQueue` block. Every caller today
    /// (VoiceCallManager, MessageManager) is off-queue.
    func isPeerReachable(_ publicKey: String) -> Bool {
        if connectedPeers.contains(where: { CrossPlatformMesh.meshKeysMatch($0.publicKey, publicKey) }) {
            return true
        }
        if let route = stateQueue.sync(execute: { routeForRecipient(publicKey) }),
           Date().timeIntervalSince(route.lastSeen) < 90 {
            return true
        }
        return false
    }

    /// True ONLY if we can hand a payload to a live TCP connection *right now* —
    /// the recipient is directly connected, or we hold a route whose nextHop is
    /// directly connected. This is the SAME condition `sendOrRelay` uses to
    /// deliver, so callers that gate IPFS fallback on it are never lied to
    /// (unlike `isPeerReachable`, which trusts a routing entry up to 90 s stale).
    ///
    /// Thread-safety: reads tcpConnections/routingTable on stateQueue via `sync`.
    /// SAFE because all callers (MeshNetworkManager.sendMessage, MessageManager
    /// send path) run OFF stateQueue. Never call this from inside a stateQueue block.
    func isPeerLiveConnected(_ publicKey: String) -> Bool {
        return stateQueue.sync {
            if connectionForRecipient(publicKey) != nil { return true }
            if let route = routeForRecipient(publicKey), connectionForRecipient(route.nextHop) != nil {
                return true
            }
            return false
        }
    }

    /// Check if peer is locally discoverable (BLE/Bonjour seen, TCP handshake may still be in progress).
    /// Used by the routing layer to wait briefly for a fresh connection before falling back to IPFS.
    func isPeerDiscovered(_ publicKey: String) -> Bool {
        return crossPlatformPeers.contains { CrossPlatformMesh.meshKeysMatch($0.publicKey, publicKey) }
    }

    /// Get Android peers
    func getAndroidPeers() -> [CrossPlatformPeer] {
        return crossPlatformPeers.filter { $0.platform == "android" }
    }

    /// Send call signal to a peer (for voice/video calls)
    func sendCallSignal(to recipientPublicKey: String, signalData: Data) {
        let base64Signal = signalData.base64EncodedString()
        sendMessage(to: recipientPublicKey, content: base64Signal, type: "CALL_SIGNAL")
    }

    /// Send call audio via cross-platform mesh (for direct P2P calls)
    func sendCallAudio(to recipientPublicKey: String, audioData: Data) {
        let base64Audio = audioData.base64EncodedString()
        sendMessage(to: recipientPublicKey, content: base64Audio, type: "CALL_AUDIO")
    }

    // __MEDIA_IN_THE_CLEAR_ON_THE_LAN_2026_11_07__
    //
    // `sendLocation`, `sendMedia` and `sendDocument` USED TO LIVE HERE and are
    // gone. Each one base64'd a payload into `{data, mediaType/mimeType,
    // fileName, fileSize}` and handed it to `sendMessage`, which writes JSON to
    // an `NWParameters.tcp` connection — no TLS. A photo, a GPS fix, a PDF and
    // its filename were readable by anyone on the same Wi-Fi.
    //
    // Android deleted its three counterparts on 25/09 (`__LEAK_FIX_2026_09_25__`).
    // These survived because that commit's comment said it "mirrors iOS", which
    // nobody checked: on iOS only the location CALL SITE had been rerouted, and
    // the media one still `continue`d past the encrypted branch.
    //
    // NOTHING REPLACES THEM, because nothing needed to. Media, location and
    // documents are already inside the ratchet ciphertext before this layer is
    // reached: `MessageManager.sendMessage` encodes an attachment as
    // `{"type":"media",…,"content":"<base64>"}` and encrypts THAT, and a location
    // is a normal message whose plaintext is `📍LOCATION📍{json}`. Every cross-
    // platform send now goes out through `relayMessage(meshWireSafe())` as
    // ciphertext, which is what the direct live-connected bridge has done since
    // 10/09.
    //
    // DELETED RATHER THAN DEPRECATED on purpose. Android could keep its version
    // as a harmless pure envelope builder; these three WRITE TO THE SOCKET, so
    // leaving them callable keeps the leak one line away. Receiving the legacy
    // raw `LOCATION_MESSAGE` / `MEDIA_MESSAGE` / `DOCUMENT_MESSAGE` frames is
    // untouched on both platforms, so peers on older builds still work.
    //
    // See `MeshNetworkManager.sendViaCrossPlatformMesh` for the call site this
    // was removed from, and `OSHI_Mesh_Protocol_Reference.md` for the wire shape.

    // MARK: - TCP Server

    private func startTCPServer() {
        do {
            // Use dynamic port
            let parameters = NWParameters.tcp
            parameters.allowLocalEndpointReuse = true

            tcpListener = try NWListener(using: parameters)

            tcpListener?.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    if let port = self?.tcpListener?.port?.rawValue {
                        self?.myPort = port
                        OshiLog.mesh.info("[CrossPlatformMesh] TCP server ready on port \(port)")
                    }
                case .failed(let error):
                    OshiLog.mesh.info("[CrossPlatformMesh] TCP server failed: \(error)")
                default:
                    break
                }
            }

            tcpListener?.newConnectionHandler = { [weak self] connection in
                self?.handleIncomingConnection(connection)
            }

            tcpListener?.start(queue: DispatchQueue.global(qos: .userInitiated))

        } catch {
            OshiLog.mesh.info("[CrossPlatformMesh] Failed to create TCP listener: \(error)")
        }
    }

    private func stopTCPServer() {
        tcpListener?.cancel()
        tcpListener = nil

        // 🔧 FIX: Serialize tcpConnections access
        stateQueue.async { [weak self] in
            guard let self = self else { return }
            for (_, connection) in self.tcpConnections {
                connection.cancel()
            }
            self.tcpConnections.removeAll()
        }
    }

    private func handleIncomingConnection(_ connection: NWConnection) {
        OshiLog.mesh.info("[CrossPlatformMesh] Incoming TCP connection")

        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                OshiLog.mesh.info("[CrossPlatformMesh] Connection ready")
                self?.receiveData(from: connection)
            case .failed(let error):
                OshiLog.mesh.info("[CrossPlatformMesh] Connection failed: \(error)")
                self?.cleanupConnection(for: connection)
            case .cancelled:
                OshiLog.mesh.info("[CrossPlatformMesh] Connection cancelled")
                self?.cleanupConnection(for: connection)
            default:
                break
            }
        }

        connection.start(queue: DispatchQueue.global(qos: .userInitiated))
    }

    private func connectToPeer(_ peer: CrossPlatformPeer) {
        guard let host = peer.ipAddress, let port = peer.port else {
            OshiLog.mesh.info("[CrossPlatformMesh] No address for peer")
            return
        }
        guard !peer.publicKey.isEmpty else { return }

        // 🔧 FIX: Serialize duplicate check on stateQueue (tcpConnections accessed from multiple threads)
        stateQueue.async { [weak self] in
            guard let self = self else { return }

            // Don't create duplicate TCP connections
            if let existing = self.tcpConnections[peer.publicKey] {
                let state = existing.state
                if state == .ready || state == .preparing {
                    OshiLog.mesh.info("[CrossPlatformMesh] Already connected to \(peer.displayName), skipping")
                    return
                }
            }

            // __NOTHING_EVER_DIALLED_A_PEER_TWICE_2026_11_07__ One dial per peer
            // at a time, however many callers ask: the sweep, the two discovery
            // callbacks and the Connect button all arrive here.
            if self.dialsInFlight.contains(peer.publicKey) {
                OshiLog.mesh.info("[CrossPlatformMesh] Dial already in flight for \(peer.displayName)")
                return
            }
            self.dialsInFlight.insert(peer.publicKey)
            self.lastDialAt[peer.publicKey] = Date().timeIntervalSince1970

            let endpoint = NWEndpoint.hostPort(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!)
            let connection = NWConnection(to: endpoint, using: .tcp)

        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                OshiLog.mesh.info("[CrossPlatformMesh] Connected to \(peer.displayName)")
                // 🔧 FIX: Serialize state mutations
                self?.stateQueue.async {
                    self?.tcpConnections[peer.publicKey] = connection
                    self?.sentIdentityTo.insert(peer.publicKey)
                }
                self?.noteDialFinished(peer.publicKey, failed: false)

                DispatchQueue.main.async {
                    self?.addConnectedPeerOnMain(peer)
                }

                // Send identity
                self?.sendIdentity(to: connection)
                // 2-hop gossip: announce ourselves so the new peer can route to us
                self?.sendIdentityAnnounce(to: connection)
                self?.receiveData(from: connection)

            case .failed(let error):
                OshiLog.mesh.info("[CrossPlatformMesh] Connection to \(peer.displayName) failed: \(error)")
                // This used to be the end of the road. `cleanupConnection` looks
                // the connection up in `tcpConnections`, where a dial that never
                // reached .ready was never put — so the whole failure path was a
                // no-op and the peer was simply never dialled again.
                self?.noteDialFinished(peer.publicKey, failed: true)
                self?.cleanupConnection(for: connection)

            case .cancelled:
                OshiLog.mesh.info("[CrossPlatformMesh] Connection to \(peer.displayName) cancelled")
                self?.noteDialFinished(peer.publicKey, failed: true)
                self?.cleanupConnection(for: connection)

            default:
                break
            }
        }

            connection.start(queue: DispatchQueue.global(qos: .userInitiated))
        } // end stateQueue.async
    }

    // MARK: - Reconnect (see MeshReconnectPolicy)

    /// Record the outcome of a dial. Only the attempt that is actually in
    /// flight reports one: a connection that went `.ready` and is cancelled
    /// minutes later is a disconnect, not a failed attempt.
    private func noteDialFinished(_ key: String, failed: Bool) {
        stateQueue.async { [weak self] in
            guard let self = self else { return }
            guard self.dialsInFlight.remove(key) != nil else { return }
            if failed {
                let n = (self.dialFailures[key] ?? 0) + 1
                self.dialFailures[key] = n
                OshiLog.mesh.info("[CrossPlatformMesh] dial failed for \(key.prefix(12))… — attempt \(n), next in \(MeshReconnectPolicy.backoff(failures: n))ms")
            } else {
                self.dialFailures.removeValue(forKey: key)
            }
        }
    }

    /// Is there a live connection for this peer key?
    ///
    /// Normalized, for the same reason `meshKeysMatch` is: an outgoing dial
    /// keys the map on the spelling the peer ANNOUNCED, an incoming connection
    /// on the spelling it stamped on its IDENTITY_EXCHANGE, and those differ
    /// whenever one side uses base64url. Must be called on `stateQueue`.
    private func hasLiveConnectionLocked(_ publicKey: String) -> Bool {
        let want = CrossPlatformMesh.normalizedMeshKey(publicKey)
        guard let connection = tcpConnections.first(where: {
            CrossPlatformMesh.normalizedMeshKey($0.key) == want
        })?.value else { return false }
        return connection.state == .ready || connection.state == .preparing
    }

    /// Connect to one discovered peer, now, because the user asked.
    ///
    /// The Connect button on a cross-platform row was removed on 2026-08-18
    /// because there was nothing public to call. This is the thing to call.
    @discardableResult
    func connectNow(publicKey: String) -> MeshConnectRequest {
        guard !publicKey.isEmpty else { return .notDiscovered }

        let want = CrossPlatformMesh.normalizedMeshKey(publicKey)
        let peer = crossPlatformPeers.first {
            CrossPlatformMesh.normalizedMeshKey($0.publicKey) == want
        } ?? connectedPeers.first {
            CrossPlatformMesh.normalizedMeshKey($0.publicKey) == want
        }

        guard let peer = peer else {
            restartDiscovery(force: true)
            return .notDiscovered
        }

        let connected = stateQueue.sync { hasLiveConnectionLocked(peer.publicKey) }
        if connected { return .alreadyConnected }

        let inFlight = stateQueue.sync { dialsInFlight.contains(peer.publicKey) }
        // The user pressing the button forgives every earlier failure: they can
        // see the other phone, and the backoff cannot.
        stateQueue.async { [weak self] in self?.dialFailures.removeValue(forKey: peer.publicKey) }

        switch MeshReconnectPolicy.decide(
            connected: false,
            hasAddress: peer.ipAddress != nil && peer.port != nil,
            dialInFlight: inFlight,
            failures: 0,
            msSinceLastDial: Int.max,
            userAsked: true
        ) {
        case .dial:
            connectToPeer(peer)
            return .dialing
        case .rediscover:
            restartDiscovery(force: true)
            return .searching
        // A dial is already running for this key — say so, do not open a second.
        case .wait:
            return .dialing
        case .alreadyConnected:
            return .alreadyConnected
        }
    }

    /// Tear down Bonjour browsing and start it again, and re-arm the BLE scan.
    ///
    /// The only way to make a peer that is ALREADY published announce itself to
    /// us a second time: `NetServiceBrowser` reports a service once per browse.
    /// PeersView has no Refresh button today; this is what one would call.
    ///
    /// - Parameter force: skip the `rediscoverMinIntervalMs` floor. True for a
    ///   user action, false for the automatic sweep.
    func restartDiscovery(force: Bool = false) {
        guard isRunning else { return }
        let now = Date().timeIntervalSince1970
        if !force,
           (now - lastDiscoveryRestartAt) * 1000 < Double(MeshReconnectPolicy.rediscoverMinIntervalMs) {
            return
        }
        lastDiscoveryRestartAt = now

        OshiLog.mesh.info("[CrossPlatformMesh] 🔄 Restarting discovery (force=\(force), discovered=\(crossPlatformPeers.count), connected=\(connectedPeers.count))")

        // Let BLE read announces again from peripherals we have already seen.
        discoveredPeripherals.removeAll()
        stopBLEScanning()
        startBLEScanning()

        DispatchQueue.main.async { [weak self] in
            guard let self = self, self.isRunning else { return }
            self.stopBonjourBrowsing()
            // NetServiceBrowser does not like a search that races its own stop.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                guard let self = self, self.isRunning else { return }
                self.startBonjourBrowsing()
            }
        }
    }

    /// Dial every discovered peer that is not connected and whose backoff has
    /// elapsed. Runs every `MeshReconnectPolicy.sweepIntervalMs`.
    private func sweepDiscoveredPeers() {
        let peers = crossPlatformPeers
        guard !peers.isEmpty else { return }
        let now = Date().timeIntervalSince1970

        var toDial: [CrossPlatformPeer] = []
        var addressless = 0

        stateQueue.sync {
            for peer in peers where !peer.publicKey.isEmpty {
                let sinceMs: Int
                if let last = lastDialAt[peer.publicKey] {
                    sinceMs = Int((now - last) * 1000)
                } else {
                    sinceMs = Int.max
                }
                switch MeshReconnectPolicy.decide(
                    connected: hasLiveConnectionLocked(peer.publicKey),
                    hasAddress: peer.ipAddress != nil && peer.port != nil,
                    dialInFlight: dialsInFlight.contains(peer.publicKey),
                    failures: dialFailures[peer.publicKey] ?? 0,
                    msSinceLastDial: sinceMs,
                    userAsked: false
                ) {
                case .dial: toDial.append(peer)
                case .rediscover: addressless += 1
                case .wait, .alreadyConnected: break
                }
            }
        }

        for peer in toDial {
            OshiLog.mesh.info("[CrossPlatformMesh] 🔁 Reconnect sweep: dialling \(peer.displayName)")
            connectToPeer(peer)
        }
        if addressless > 0 {
            OshiLog.mesh.info("[CrossPlatformMesh] 🔁 Reconnect sweep: \(addressless) peer(s) with no address — asking discovery again")
            restartDiscovery()
        }
    }

    private func scheduleReconnectSweep() {
        reconnectSweepTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        let seconds = Double(MeshReconnectPolicy.sweepIntervalMs) / 1000.0
        timer.schedule(deadline: .now() + seconds, repeating: seconds)
        timer.setEventHandler { [weak self] in
            guard let self = self, self.isRunning else { return }
            self.sweepDiscoveredPeers()
        }
        timer.resume()
        reconnectSweepTimer = timer
    }

    private func sendIdentity(to connection: NWConnection) {
        let identity = CrossPlatformMessage(
            id: UUID().uuidString,
            type: "IDENTITY_EXCHANGE",
            senderPublicKey: myPublicKey,
            senderName: myDisplayName,
            recipientPublicKey: "",
            payload: "{\"publicKey\":\"\(myPublicKey)\",\"name\":\"\(myDisplayName)\",\"platform\":\"ios\"}",
            timestamp: Date().timeIntervalSince1970 * 1000,  // Milliseconds to match Android
            hopCount: 0,
            maxHops: 1,
            seenBy: [],
            platform: "ios"
        )

        sendData(identity.toJSON(), to: connection)
    }

    private func sendData(_ data: Data, to connection: NWConnection) {
        // Prepend length header (4 bytes, big endian)
        var length = UInt32(data.count).bigEndian
        var packet = Data(bytes: &length, count: 4)
        packet.append(data)

        connection.send(content: packet, completion: .contentProcessed { [weak self] error in
            if let error = error {
                OshiLog.mesh.info("[CrossPlatformMesh] Send error: \(error)")
                self?.cleanupConnection(for: connection)
            }
        })
    }

    // 🔧 CRITICAL: Clean up all state when a TCP connection dies
    // This ensures isPeerReachable() returns false and routing falls back to IPFS immediately
    // Also allows BLE scan to reconnect to the peer
    private func cleanupConnection(for connection: NWConnection) {
        // 🔧 FIX: Serialize all mutable state access to prevent concurrent mutation crash
        stateQueue.async { [weak self] in
            guard let self = self else { return }

            // Find the public key associated with this connection
            guard let peerKey = self.tcpConnections.first(where: { $0.value === connection })?.key else {
                return
            }

            OshiLog.mesh.info("[CrossPlatformMesh] 🧹 Cleaning up dead connection for \(peerKey.prefix(16))...")

            self.tcpConnections.removeValue(forKey: peerKey)
            self.routingTable.removeValue(forKey: peerKey)
            self.sentIdentityTo.remove(peerKey)

            // Clear discoveredPeripherals to allow BLE re-discovery and reconnection
            self.discoveredPeripherals.removeAll()

            // Remove from connectedPeers on main thread
            DispatchQueue.main.async { [weak self] in
                self?.connectedPeers.removeAll { $0.publicKey == peerKey }
                OshiLog.mesh.info("[CrossPlatformMesh] 🧹 Peer removed from connectedPeers (now: \(self?.connectedPeers.count ?? 0))")
            }

            // Cancel the dead connection
            connection.cancel()
        }
    }

    private func receiveData(from connection: NWConnection) {
        // Read length header first (4 bytes)
        connection.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] data, _, isComplete, error in
            if let error = error {
                OshiLog.mesh.info("[CrossPlatformMesh] Receive error: \(error)")
                self?.cleanupConnection(for: connection)
                return
            }

            guard let data = data, data.count == 4 else {
                if isComplete {
                    OshiLog.mesh.info("[CrossPlatformMesh] Connection completed (header read)")
                    self?.cleanupConnection(for: connection)
                } else {
                    self?.receiveData(from: connection)
                }
                return
            }

            // loadUnaligned: the 4 received bytes are not guaranteed to be word-aligned (MED-11).
            let length = data.withUnsafeBytes { $0.loadUnaligned(as: UInt32.self).bigEndian }

            // Security: bound the length-prefixed allocation. An unauthenticated peer could send
            // a 0xFFFFFFFF header to force a ~4 GB buffer (remote OOM, H-MSG-1). Reject frames
            // outside (0, maxFrameSize]. 100 MB comfortably covers the 50 MB media limit + base64.
            let maxFrameSize: UInt32 = 100 * 1024 * 1024
            guard length > 0 && length <= maxFrameSize else {
                OshiLog.mesh.info("[CrossPlatformMesh] Rejecting frame with invalid length \(length); closing connection")
                self?.cleanupConnection(for: connection)
                return
            }

            // Now read the message
            connection.receive(minimumIncompleteLength: Int(length), maximumLength: Int(length)) { [weak self] messageData, _, isComplete, error in
                if let error = error {
                    OshiLog.mesh.info("[CrossPlatformMesh] Receive error (body): \(error)")
                    self?.cleanupConnection(for: connection)
                    return
                }

                if let messageData = messageData {
                    self?.handleReceivedData(messageData, from: connection)
                }

                if isComplete {
                    OshiLog.mesh.info("[CrossPlatformMesh] Connection completed (body read)")
                    self?.cleanupConnection(for: connection)
                } else {
                    self?.receiveData(from: connection)
                }
            }
        }
    }

    private func handleReceivedData(_ data: Data, from connection: NWConnection) {
        guard let message = CrossPlatformMessage.fromJSON(data) else {
            OshiLog.mesh.info("[CrossPlatformMesh] Failed to parse message")
            return
        }

        OshiLog.mesh.info("[CrossPlatformMesh] Received \(message.type) from \(message.senderName) (\(message.platform))")

        switch message.type {
        case "IDENTITY_EXCHANGE":
            handleIdentityExchange(message, connection: connection)

        case "IDENTITY_ANNOUNCE":
            handleIdentityAnnounce(message, from: connection)

        // 🔐 PEER AUTH proof-of-possession (additive/advisory, see MeshPeerAuth.swift).
        // Older peers on either platform never send these and, on the `default`
        // branch below, simply log-and-ignore an unknown type — safe one-sided.
        case "PEER_CHALLENGE":
            handlePeerChallenge(message, connection: connection)

        case "PEER_CHALLENGE_RESPONSE":
            handlePeerChallengeResponse(message)

        // 👥 GROUP_UPDATE / GROUP_MESSAGE / PUBLIC_GROUP_AD must be forwarded too.
        // MeshNetworkManager.swift already has working handlers for all three
        // (see its `case "PUBLIC_GROUP_AD"` / `"GROUP_UPDATE"` / `"GROUP_MESSAGE"`),
        // but before this line they never reached it: this transport-level switch
        // dropped them into `default:` as "Unknown message type". Android sends
        // GROUP_UPDATE from ~10 call sites in GroupManager.kt and broadcasts
        // PUBLIC_GROUP_AD (GroupManager.kt:1199), so every Android→iOS group join,
        // member sync and public-group discovery was being silently discarded.
        // Android↔Android and iOS↔iOS (MultipeerConnectivity) were never affected,
        // which is why unit tests on either platform alone stayed green.
        // Verify with a real cross-device test, not a unit test.
        case "TEXT_MESSAGE", "MEDIA_MESSAGE", "CALL_SIGNAL", "CALL_AUDIO", "RELAY",
             "LOCATION_MESSAGE", "DOCUMENT_MESSAGE",
             "GROUP_UPDATE", "GROUP_MESSAGE", "PUBLIC_GROUP_AD":
            handleContentMessage(message, from: connection)

        default:
            OshiLog.mesh.info("[CrossPlatformMesh] Unknown message type: \(message.type)")
        }
    }

    private func handleIdentityExchange(_ message: CrossPlatformMessage, connection: NWConnection) {
        // Skip iOS peers — MultipeerConnectivity handles iOS-to-iOS mesh
        if message.platform == "ios" {
            OshiLog.mesh.info("[CrossPlatformMesh] Skipping iOS identity from \(message.senderName) — handled by MultipeerConnectivity")
            return
        }

        // 🔧 FIX: Serialize all mutable state access
        stateQueue.async { [weak self] in
            guard let self = self else { return }

            // Don't replace existing live connection — prevents RST on duplicate connections
            if let existing = self.tcpConnections[message.senderPublicKey],
               existing !== connection,
               existing.state == .ready {
                OshiLog.mesh.info("[CrossPlatformMesh] Already have live connection for \(message.senderName), keeping existing")
            } else {
                self.tcpConnections[message.senderPublicKey] = connection
            }

            // Update routing table
            self.routingTable[message.senderPublicKey] = RouteInfo(
                nextHop: message.senderPublicKey,
                hopCount: 1,
                lastSeen: Date()
            )

            // Send our identity back if not already sent
            let shouldSendIdentity = !self.sentIdentityTo.contains(message.senderPublicKey)
            if shouldSendIdentity {
                self.sentIdentityTo.insert(message.senderPublicKey)
            }

            // Update peer info on main thread
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                let trimmedName = message.senderName.trimmingCharacters(in: .whitespacesAndNewlines)
                // Update any discovered peer that matches (by publicKey or name)
                for i in self.crossPlatformPeers.indices {
                    let p = self.crossPlatformPeers[i]
                    if p.publicKey == message.senderPublicKey ||
                       p.publicKey.isEmpty && p.displayName.trimmingCharacters(in: .whitespacesAndNewlines) == trimmedName {
                        self.crossPlatformPeers[i].publicKey = message.senderPublicKey
                        self.crossPlatformPeers[i].displayName = trimmedName
                        self.crossPlatformPeers[i].platform = message.platform
                        break
                    }
                }
                let peer = CrossPlatformPeer(
                    publicKey: message.senderPublicKey,
                    displayName: trimmedName,
                    platform: message.platform,
                    ipAddress: nil,
                    port: nil
                )
                self.addConnectedPeerOnMain(peer)
            }

            if shouldSendIdentity {
                OshiLog.mesh.info("[CrossPlatformMesh] Identity exchange complete: \(message.senderName) (\(message.platform)) — sending our identity back")
                self.sendIdentity(to: connection)
            } else {
                OshiLog.mesh.info("[CrossPlatformMesh] Identity exchange complete: \(message.senderName) (\(message.platform)) — already sent our identity")
            }

            // 2-hop gossip: tell the new peer our identity (and let them re-broadcast)
            self.sendIdentityAnnounce(to: connection)

            // 🔐 PEER AUTH: challenge the peer to prove possession of the key it
            // announced (additive/advisory). Only reached for non-iOS peers here,
            // since iOS↔iOS identity is handled by MultipeerConnectivity above.
            self.sendPeerChallenge(to: message.senderPublicKey, connection: connection)
        }
    }

    // MARK: - Peer authentication (proof-of-possession, see MeshPeerAuth.swift)

    // Wire format (JSON payload on the existing CrossPlatformMessage envelope):
    //   PEER_CHALLENGE           payload = {"nonce":"<base64(32)>"}
    //   PEER_CHALLENGE_RESPONSE  payload = {"nonce":"<base64(32)>","tag":"<base64(32)>"}
    // The challenger's identity is message.senderPublicKey; the responder computes
    // tag = HMAC-SHA256(DH(their_private, challenger_public), nonce). Symmetry lets
    // the challenger verify with DH(challenger_private, responder_public).

    /// Send a fresh DH challenge to a peer whose announced key we just learned.
    /// Must be called on `stateQueue` (mutates `pendingChallengeNonces`).
    private func sendPeerChallenge(to peerKey: String, connection: NWConnection) {
        guard !peerKey.isEmpty else { return }
        let nonce = MeshPeerAuth.makeNonce()
        pendingChallengeNonces[peerKey] = nonce
        let payload = "{\"nonce\":\"\(nonce.base64EncodedString())\"}"
        let msg = CrossPlatformMessage(
            id: UUID().uuidString,
            type: "PEER_CHALLENGE",
            senderPublicKey: myPublicKey,
            senderName: myDisplayName,
            recipientPublicKey: peerKey,
            payload: payload,
            timestamp: Date().timeIntervalSince1970 * 1000,
            hopCount: 0,
            maxHops: 1,
            seenBy: [],
            platform: "ios"
        )
        sendData(msg.toJSON(), to: connection)
        OshiLog.mesh.info("[CrossPlatformMesh] 🔐 Sent peer-auth challenge to \(peerKey.prefix(16))…")
    }

    /// A peer challenged us: return HMAC(DH(our_private, their_public), nonce).
    /// On any failure (no identity / bad payload) we stay silent — advisory only.
    private func handlePeerChallenge(_ message: CrossPlatformMessage, connection: NWConnection) {
        guard let payload = message.payload.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: payload) as? [String: Any],
              let nonceB64 = obj["nonce"] as? String,
              let nonce = Data(base64Encoded: nonceB64), nonce.count == MeshPeerAuth.nonceLength,
              let sharedSecret = try? MessageManager.sharedIdentityManager?.computeSharedSecret(with: message.senderPublicKey) else {
            OshiLog.mesh.info("[CrossPlatformMesh] 🔐 Cannot answer peer-auth challenge — skipping (advisory)")
            return
        }
        let tag = MeshPeerAuth.tag(sharedSecret: sharedSecret, nonce: nonce)
        let responsePayload = "{\"nonce\":\"\(nonce.base64EncodedString())\",\"tag\":\"\(tag.base64EncodedString())\"}"
        let msg = CrossPlatformMessage(
            id: UUID().uuidString,
            type: "PEER_CHALLENGE_RESPONSE",
            senderPublicKey: myPublicKey,
            senderName: myDisplayName,
            recipientPublicKey: message.senderPublicKey,
            payload: responsePayload,
            timestamp: Date().timeIntervalSince1970 * 1000,
            hopCount: 0,
            maxHops: 1,
            seenBy: [],
            platform: "ios"
        )
        sendData(msg.toJSON(), to: connection)
        OshiLog.mesh.info("[CrossPlatformMesh] 🔐 Answered peer-auth challenge from \(message.senderPublicKey.prefix(16))…")
    }

    /// A peer answered our challenge. Verify the tag (constant-time) against the
    /// nonce we sent and mark the peer verified on match. A mismatch never drops
    /// the peer — verification is advisory.
    private func handlePeerChallengeResponse(_ message: CrossPlatformMessage) {
        stateQueue.async { [weak self] in
            guard let self = self else { return }
            guard let payload = message.payload.data(using: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: payload) as? [String: Any],
                  let nonceB64 = obj["nonce"] as? String, let nonce = Data(base64Encoded: nonceB64),
                  let tagB64 = obj["tag"] as? String, let tag = Data(base64Encoded: tagB64) else {
                return
            }
            guard let pending = self.pendingChallengeNonces[message.senderPublicKey],
                  MeshPeerAuth.constantTimeEquals(nonce, pending) else {
                OshiLog.mesh.info("[CrossPlatformMesh] 🔐 Peer-auth response nonce mismatch — ignoring")
                return
            }
            guard let sharedSecret = try? MessageManager.sharedIdentityManager?.computeSharedSecret(with: message.senderPublicKey) else {
                return
            }
            if MeshPeerAuth.verify(tag: tag, sharedSecret: sharedSecret, nonce: nonce) {
                self.pendingChallengeNonces.removeValue(forKey: message.senderPublicKey)
                DispatchQueue.main.async { [weak self] in
                    self?.verifiedPeerKeys.insert(message.senderPublicKey)
                    OshiLog.mesh.info("[CrossPlatformMesh] ✅ Peer VERIFIED possession of \(message.senderPublicKey.prefix(16))…")
                }
            } else {
                OshiLog.mesh.info("[CrossPlatformMesh] 🔐 Peer-auth tag mismatch — staying unverified (advisory, delivery unaffected)")
            }
        }
    }

    /// Advisory: has this peer (by public key) proven possession of its key?
    func isPeerVerified(_ publicKey: String) -> Bool {
        return verifiedPeerKeys.contains(publicKey)
    }

    private func handleContentMessage(_ message: CrossPlatformMessage, from connection: NWConnection) {
        // 🔧 FIX: Serialize mutable state access
        stateQueue.async { [weak self] in
            guard let self = self else { return }

            // Check if already seen (duplicate prevention)
            if self.seenMessageIds.contains(message.id) {
                return
            }
            // Records insertion order and evicts OLDEST-first; see noteSeenMessageId.
            self.noteSeenMessageId(message.id)

            // Update routing table (learn route back to sender)
            self.routingTable[message.senderPublicKey] = RouteInfo(
                nextHop: message.senderPublicKey,
                hopCount: message.hopCount,
                lastSeen: Date()
            )

            // Is the message for us? __MESH_RECIPIENT_SPELLING_2026_10_12__ —
            // base64url and standard base64 are the same key. See
            // `isAddressedToUs` for why a wrong answer here LOSES the message.
            let isForUs = CrossPlatformMesh.isAddressedToUs(
                message.recipientPublicKey,
                myPublicKey: self.myPublicKey
            )

            if isForUs {
                OshiLog.mesh.info("[CrossPlatformMesh] Message is for us!")

                // Notify callbacks
                DispatchQueue.main.async { [weak self] in
                    self?.messageCallbacks.forEach { $0(message) }
                }

                // Also notify main mesh manager
                NotificationCenter.default.post(
                    name: NSNotification.Name("CrossPlatformMessageReceived"),
                    object: nil,
                    userInfo: ["message": message]
                )
            } else {
                OshiLog.mesh.info("[CrossPlatformMesh] ⚠️ Recipient key mismatch — relaying. mine=\(CrossPlatformMesh.normalizedMeshKey(self.myPublicKey).prefix(24))... msg=\(CrossPlatformMesh.normalizedMeshKey(message.recipientPublicKey).prefix(24))...")
                // Relay to intended recipient
                self.relayToRecipient(message)
            }
        }
    }

    // MARK: - Send/Relay Logic

    /// Delivers `message` to a live TCP connection and returns whether it could.
    ///
    /// The verdict (which connection, if any) is resolved SYNCHRONOUSLY on
    /// stateQueue so callers get the truth; the socket write itself stays async.
    /// Returning `false` lets callers (MeshNetworkManager → MessageManager)
    /// engage the IPFS fallback instead of falsely reporting mesh success.
    ///
    /// Strict mesh delivery — DO NOT broadcast. The CrossPlatformMessage envelope
    /// carries sender/recipient public keys in PLAINTEXT; broadcasting an
    /// unroutable message would leak the sender↔recipient relationship to every
    /// connected peer. When we can't deliver, we say so and IPFS takes over.
    @discardableResult
    private func sendOrRelay(_ message: CrossPlatformMessage) -> Bool {
        // Resolve the target connection synchronously so we can report the truth.
        // Also handles the throttled "no route" log inside the same critical
        // section (lastNoRouteLogAt is stateQueue-confined).
        let target: NWConnection? = stateQueue.sync {
            // Tolerant of base64url/padding skew — see `meshKeysMatch`. A strict
            // subscript here is how a live link gets passed over for the relay.
            if let direct = connectionForRecipient(message.recipientPublicKey) {
                OshiLog.mesh.info("[CrossPlatformMesh] Direct send to \(message.recipientPublicKey.prefix(16))... (matched \(direct.key.prefix(16))...)")
                return direct.connection
            }
            if let route = routeForRecipient(message.recipientPublicKey),
               let hop = connectionForRecipient(route.nextHop) {
                OshiLog.mesh.info("[CrossPlatformMesh] Routed send via \(route.nextHop.prefix(16))...")
                return hop.connection
            }
            // 🔧 FREEZE FIX: voice calls invoke this 50×/sec via sendCallAudio.
            // Throttle the no-route log to one per recipient per 5 s.
            let now = Date()
            let lastAt = lastNoRouteLogAt[message.recipientPublicKey] ?? .distantPast
            if now.timeIntervalSince(lastAt) > 5.0 {
                lastNoRouteLogAt[message.recipientPublicKey] = now
                OshiLog.mesh.info("[CrossPlatformMesh] No live path for \(message.recipientPublicKey.prefix(16))…, NOT delivered (peers=\(tcpConnections.count))")
            }
            return nil
        }

        guard let connection = target else {
            return false   // honest: NOT delivered — caller should fall back to IPFS
        }

        // Deliver on stateQueue (unchanged behavior); verdict already returned.
        stateQueue.async { [weak self] in
            self?.sendData(message.toJSON(), to: connection)
        }
        return true
    }

    private func relayToRecipient(_ message: CrossPlatformMessage) {
        // Check hop count.
        //
        // [Audit F-mesh#4 2026-09-10] Clamp the attacker-controlled `maxHops` to
        // our LOCAL ceiling — `min(message.maxHops, maxHops)` — mirroring the MPC
        // relay (MeshNetworkManager.swift:1766). Taking the ceiling straight from
        // the wire let a hostile sender set it arbitrarily large and turn every
        // node into a re-flooder (`seenMessageIds` bounds it to network size, but
        // the intended cap was ignored). Legitimate traffic hops far below 50, so
        // real delivery is unchanged.
        guard message.hopCount < min(message.maxHops, maxHops) else {
            OshiLog.mesh.info("[CrossPlatformMesh] Max hops reached, dropping message")
            return
        }

        // Check if we've already relayed
        guard !message.seenBy.contains(myPublicKey) else {
            return
        }

        // Update message for relay
        var relayMessage = message
        relayMessage.hopCount += 1
        relayMessage.seenBy.append(myPublicKey)

        // Try direct route first
        if let route = routingTable[message.recipientPublicKey],
           let connection = tcpConnections[route.nextHop] {
            OshiLog.mesh.info("[CrossPlatformMesh] Relaying to \(message.recipientPublicKey.prefix(16))... via \(route.nextHop.prefix(16))...")
            sendData(relayMessage.toJSON(), to: connection)
            return
        }

        // Broadcast to all except sender
        OshiLog.mesh.info("[CrossPlatformMesh] Broadcast relay (hop \(relayMessage.hopCount))")
        for (publicKey, connection) in tcpConnections {
            if !message.seenBy.contains(publicKey) {
                sendData(relayMessage.toJSON(), to: connection)
            }
        }
    }

    // MARK: - BLE Advertising

    private func startBLEAdvertising() {
        guard let peripheralManager = peripheralManager, peripheralManager.state == .poweredOn else { return }

        // Stop existing advertising/services to avoid duplicates
        peripheralManager.stopAdvertising()
        peripheralManager.removeAllServices()

        // Create service
        let service = CBMutableService(type: CrossPlatformMesh.SERVICE_UUID, primary: true)

        // Create characteristic WITHOUT cached value (nil)
        // This ensures didReceiveRead delegate is called with fresh data (including current port/IP)
        let characteristic = CBMutableCharacteristic(
            type: CrossPlatformMesh.CHAR_UUID,
            properties: [.read],
            value: nil,
            permissions: [.readable]
        )
        service.characteristics = [characteristic]

        // Mark that we want to start advertising after service is added
        // peripheralManager.add() is ASYNC — advertising must wait for didAdd callback
        // Otherwise Android connects via BLE but can't find the GATT service
        pendingAdvertising = true
        peripheralManager.add(service)

        OshiLog.mesh.info("[CrossPlatformMesh] BLE GATT service adding... (advertising will start after)")
    }

    private func stopBLEAdvertising() {
        peripheralManager?.stopAdvertising()
        peripheralManager?.removeAllServices()
    }

    private func createAdvertisementData() -> Data {
        let info: [String: Any] = [
            "pk": String(myPublicKey.prefix(32)),  // Shortened for BLE
            "name": myDisplayName,
            "port": myPort,
            "platform": "ios",
            "ip": getLocalIPAddress() ?? ""
        ]
        return (try? JSONSerialization.data(withJSONObject: info)) ?? Data()
    }

    /// __BLE_DIAL_ANY_IP_2026_11_20__ True only for an IPv4 literal on a local
    /// network: RFC 1918 (10/8, 172.16/12 — iPhone hotspot is 172.20.10/28 —,
    /// 192.168/16), link-local 169.254/16, and CGNAT 100.64/10 (some Android
    /// hotspots). Loopback, multicast, public and malformed addresses are refused.
    static func isDialableMeshIPv4(_ ip: String) -> Bool {
        let parts = ip.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return false }
        var o: [Int] = []
        for p in parts {
            guard !p.isEmpty, p.count <= 3, p.allSatisfy({ $0.isASCII && $0.isNumber }),
                  let v = Int(p), v <= 255 else { return false }
            o.append(v)
        }
        switch (o[0], o[1]) {
        case (10, _): return true
        case (172, 16...31): return true
        case (192, 168): return true
        case (169, 254): return true
        case (100, 64...127): return true
        default: return false
        }
    }

    /// __THE_IP_WE_ANNOUNCE_MUST_BE_THE_LAN_ONE_2026_10_22__
    ///
    /// The address that goes into the BLE characteristic and the Bonjour record is
    /// the one the Android phone dials. There is no BLE data path on either side
    /// (both characteristics are READ-only, one JSON blob), so if that TCP
    /// connection never comes up, iOS ↔ Android mesh does not happen at all.
    ///
    /// The old rule was `name == "en0" || name == "en1"`. Safe, and incomplete in
    /// the one configuration people reach for when there is no router: with
    /// Personal Hotspot ON, the iPhone's LAN address lives on `bridge100`, never
    /// on `en0` — so we announced an EMPTY ip to the very peer that had just
    /// joined our own network. Android then logs "no IP/port" and waits on
    /// Bonjour. The `en0`/`en1` pair also leaves out `en2`+ (a wired adapter on
    /// iPad, and the Mac under Catalyst, where Wi-Fi is not always `en0`).
    ///
    /// Pure and `static` so the ranking is testable; `getifaddrs` needs a device.
    /// Mirror of Android's `pickLanIPv4` — keep the two in step.
    ///
    /// - Parameter candidates: (interface name, IPv4 literal), any order.
    static func preferredLanIPv4(_ candidates: [(name: String, ip: String)]) -> String? {
        // Up, carrying an IPv4, and useless to a peer on the local network:
        // cellular is carrier NAT, utun/ipsec is a VPN, awdl/llw is the Apple
        // peer-to-peer link MultipeerConnectivity already owns, anpi/XHC are
        // internal.
        let unusable = ["pdp_ip", "utun", "ipsec", "awdl", "llw", "lo",
                        "anpi", "XHC", "ap1", "gif", "stf", "pktap"]
        // Best first: a station or wired link, then a network we are HOSTING.
        let tiers = [["en"], ["bridge"]]

        func rank(_ name: String) -> Int {
            if unusable.contains(where: { name.hasPrefix($0) }) { return -1 }
            for (i, prefixes) in tiers.enumerated() where prefixes.contains(where: { name.hasPrefix($0) }) {
                return i
            }
            return tiers.count          // unknown but not excluded: last resort
        }

        // 169.254.x.x means DHCP never completed: a fallback, never a choice.
        let usable = candidates.compactMap { c -> (Int, String)? in
            let r = rank(c.name)
            guard r >= 0, !c.ip.isEmpty, c.ip != "0.0.0.0" else { return nil }
            return (r, c.ip)
        }
        return usable
            .sorted { lhs, rhs in
                let l = (lhs.1.hasPrefix("169.254.") ? 1 : 0, lhs.0)
                let r = (rhs.1.hasPrefix("169.254.") ? 1 : 0, rhs.0)
                return l < r
            }
            .first?.1
    }

    private func getLocalIPAddress() -> String? {
        var candidates: [(name: String, ip: String)] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0 else { return nil }
        defer { freeifaddrs(ifaddr) }

        var ptr = ifaddr
        while ptr != nil {
            defer { ptr = ptr?.pointee.ifa_next }
            guard let interface = ptr?.pointee else { continue }
            guard interface.ifa_addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            // Skip an interface the system reports as down: it has an address and
            // no path, which is the same lie as announcing the cellular one.
            guard (interface.ifa_flags & UInt32(IFF_UP)) != 0 else { continue }
            var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            getnameinfo(interface.ifa_addr, socklen_t(interface.ifa_addr.pointee.sa_len),
                        &hostname, socklen_t(hostname.count), nil, 0, NI_NUMERICHOST)
            candidates.append((String(cString: interface.ifa_name), String(cString: hostname)))
        }
        let picked = CrossPlatformMesh.preferredLanIPv4(candidates)
        if picked == nil && !candidates.isEmpty {
            OshiLog.mesh.info("[CrossPlatformMesh] no LAN IPv4 to announce; saw \(candidates.map { $0.name })")
        }
        return picked
    }

    // MARK: - BLE Scanning

    private func startBLEScanning() {
        guard let centralManager = centralManager, centralManager.state == .poweredOn else {
            OshiLog.mesh.info("[CrossPlatformMesh] BLE scanning skipped: central state=\(centralManager?.state.rawValue ?? -1)")
            return
        }

        // CRITICAL: Use broad scan (nil services) for cross-platform discovery!
        // UUID-filtered scan only sees devices advertising that EXACT UUID format,
        // but Android's 128-bit UUID ads may not match iOS's 16-bit UUID filter.
        // Broad scan + manual filtering (same as Android approach) is more reliable.
        centralManager.scanForPeripherals(
            withServices: nil,
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )

        OshiLog.mesh.info("[CrossPlatformMesh] BLE scanning started (broad scan, manual filtering)")
    }

    private func stopBLEScanning() {
        centralManager?.stopScan()
    }

    private func scheduleDiagnosticsUpdate() {
        diagnosticsTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .userInitiated))
        timer.schedule(deadline: .now() + 2, repeating: 5)
        timer.setEventHandler { [weak self] in
            guard let self = self else { return }
            let centralState = self.centralManager?.state == .poweredOn ? "ON" : "s\(self.centralManager?.state.rawValue ?? -1)"
            let periphState = self.peripheralManager?.state == .poweredOn ? "ON" : "s\(self.peripheralManager?.state.rawValue ?? -1)"
            let ip = self.getLocalIPAddress() ?? "none"
            let port = self.myPort
            let bleTotal = self.totalBLEScanResults
            let bleOshi = self.oshiBLEMatches
            let discovered = self.crossPlatformPeers.count
            let connected = self.connectedPeers.count
            let tcp = self.tcpConnections.count

            let diag = "v2 C:\(centralState) P:\(periphState) IP:\(ip) TCP:\(port) BLE[\(bleTotal)/\(bleOshi)] Peers:\(discovered)/\(connected)/\(tcp)"
            DispatchQueue.main.async {
                self.diagnostics = diag
            }
        }
        timer.resume()
        diagnosticsTimer = timer
    }

    private func scheduleScanRestart() {
        scanRestartTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .userInitiated))
        timer.schedule(deadline: .now() + 30, repeating: 30)
        timer.setEventHandler { [weak self] in
            guard let self = self, !self.myPublicKey.isEmpty else { return }

            // Restart BLE scanning
            if self.centralManager?.state == .poweredOn {
                self.centralManager?.stopScan()
                self.startBLEScanning()
                OshiLog.mesh.info("[CrossPlatformMesh] Periodic BLE scan restart (connected: \(self.connectedPeers.count))")
            }
        }
        timer.resume()
        scanRestartTimer = timer
    }

    /// Retry BLE advertising/scanning if Bluetooth wasn't ready at start time.
    /// Polls every 3s for up to 30s until both central and peripheral are powered on.
    private func scheduleBLERetry() {
        bleRetryTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .userInitiated))
        timer.schedule(deadline: .now() + 3, repeating: 3)
        var attempts = 0
        timer.setEventHandler { [weak self] in
            guard let self = self, !self.myPublicKey.isEmpty else { return }
            attempts += 1

            // Try advertising if peripheral is ready but we're not advertising yet
            if let pm = self.peripheralManager, pm.state == .poweredOn && !pm.isAdvertising {
                OshiLog.mesh.info("[CrossPlatformMesh] BLE retry #\(attempts): starting advertising")
                self.startBLEAdvertising()
            }

            // Try scanning if central is ready
            if self.centralManager?.state == .poweredOn {
                OshiLog.mesh.info("[CrossPlatformMesh] BLE retry #\(attempts): restarting scan")
                self.startBLEScanning()
            }

            // Stop retrying after 10 attempts (30s) or when both are running
            let advertisingOk = self.peripheralManager?.isAdvertising ?? false
            let scanningOk = self.centralManager?.state == .poweredOn
            if (advertisingOk && scanningOk) || attempts >= 10 {
                OshiLog.mesh.info("[CrossPlatformMesh] BLE retry done (adv:\(advertisingOk) scan:\(scanningOk) attempts:\(attempts))")
                self.bleRetryTimer?.cancel()
                self.bleRetryTimer = nil
            }
        }
        timer.resume()
        bleRetryTimer = timer
    }

    /// Periodically removes stale entries from the routing table.
    /// Runs every 60 seconds, evicting routes whose lastSeen is older than 5 minutes.
    private func scheduleRouteCleanup() {
        routeCleanupTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now() + 60, repeating: 60)
        timer.setEventHandler { [weak self] in
            guard let self = self else { return }
            self.stateQueue.async {
                let cutoff = Date().addingTimeInterval(-300) // 5 minutes ago
                let staleKeys = self.routingTable.filter { $0.value.lastSeen < cutoff }.map { $0.key }
                for key in staleKeys {
                    self.routingTable.removeValue(forKey: key)
                }
                if !staleKeys.isEmpty {
                    OshiLog.mesh.info("[CrossPlatformMesh] Route cleanup: removed \(staleKeys.count) stale route(s)")
                }
            }
        }
        timer.resume()
        routeCleanupTimer = timer
    }

    // MARK: - 2-Hop Identity Gossip (IDENTITY_ANNOUNCE)

    /// Build an IDENTITY_ANNOUNCE message describing the originator at the given hop count.
    /// Wire keys (originator, displayName, platform, hops, ttl) are kept identical with Android.
    private func buildIdentityAnnounce(originatorPublicKey: String,
                                       originatorDisplayName: String,
                                       originatorPlatform: String,
                                       hopCount: Int,
                                       ttl: Int) -> CrossPlatformMessage {
        let payloadDict: [String: Any] = [
            "originator": originatorPublicKey,
            "displayName": originatorDisplayName,
            "platform": originatorPlatform,
            "hops": hopCount,
            "ttl": ttl
        ]
        let payloadString: String
        if let data = try? JSONSerialization.data(withJSONObject: payloadDict),
           let s = String(data: data, encoding: .utf8) {
            payloadString = s
        } else {
            payloadString = "{}"
        }
        return CrossPlatformMessage(
            id: UUID().uuidString,
            type: "IDENTITY_ANNOUNCE",
            senderPublicKey: myPublicKey,
            senderName: myDisplayName,
            recipientPublicKey: "",
            payload: payloadString,
            timestamp: Date().timeIntervalSince1970 * 1000,
            hopCount: hopCount,
            maxHops: ttl,
            seenBy: [],
            platform: "ios"
        )
    }

    /// Broadcast our own IDENTITY_ANNOUNCE to all connected peers (hopCount=0, ttl=2).
    private func broadcastOwnIdentityAnnounce() {
        stateQueue.async { [weak self] in
            guard let self = self, !self.myPublicKey.isEmpty, !self.tcpConnections.isEmpty else { return }
            let msg = self.buildIdentityAnnounce(
                originatorPublicKey: self.myPublicKey,
                originatorDisplayName: self.myDisplayName,
                originatorPlatform: "ios",
                hopCount: 0,
                ttl: 2
            )
            self.noteSeenMessageId(msg.id)
            let data = msg.toJSON()
            for (_, connection) in self.tcpConnections {
                self.sendData(data, to: connection)
            }
            OshiLog.mesh.info("[CrossPlatformMesh] 📢 IDENTITY_ANNOUNCE broadcast (peers=\(self.tcpConnections.count))")
        }
    }

    /// Send a fresh IDENTITY_ANNOUNCE for ourselves to a single connection.
    /// Used when a peer just connected so they can immediately learn about us.
    private func sendIdentityAnnounce(to connection: NWConnection) {
        stateQueue.async { [weak self] in
            guard let self = self, !self.myPublicKey.isEmpty else { return }
            let msg = self.buildIdentityAnnounce(
                originatorPublicKey: self.myPublicKey,
                originatorDisplayName: self.myDisplayName,
                originatorPlatform: "ios",
                hopCount: 0,
                ttl: 2
            )
            self.noteSeenMessageId(msg.id)
            self.sendData(msg.toJSON(), to: connection)
        }
    }

    /// Schedule a 30s recurring IDENTITY_ANNOUNCE broadcast.
    private func scheduleIdentityAnnounce() {
        identityAnnounceTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        // Fire first announce shortly after start (5s), then every 30s
        timer.schedule(deadline: .now() + 5, repeating: 30)
        timer.setEventHandler { [weak self] in
            self?.broadcastOwnIdentityAnnounce()
        }
        timer.resume()
        identityAnnounceTimer = timer
    }

    /// Handle an inbound IDENTITY_ANNOUNCE: dedupe, learn route, optionally re-broadcast.
    private func handleIdentityAnnounce(_ message: CrossPlatformMessage, from connection: NWConnection) {
        stateQueue.async { [weak self] in
            guard let self = self else { return }

            // Dedupe by message id (also covers our own echoes)
            if self.seenMessageIds.contains(message.id) { return }
            self.noteSeenMessageId(message.id)

            // Parse payload
            guard let data = message.payload.data(using: .utf8),
                  let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
                return
            }
            let originator = obj["originator"] as? String ?? ""
            let displayName = obj["displayName"] as? String ?? ""
            let platform = obj["platform"] as? String ?? "unknown"
            let hopsIn = obj["hops"] as? Int ?? 0
            let ttl = obj["ttl"] as? Int ?? 2

            if originator.isEmpty { return }
            if originator == self.myPublicKey { return } // echo

            // Find the link this came in on (publicKey of direct neighbor)
            let nextHopKey = self.tcpConnections.first(where: { $0.value === connection })?.key
                ?? message.senderPublicKey

            let newHopCount = hopsIn + 1
            let now = Date()

            // Update routing table only if this is a strictly better path,
            // or refresh lastSeen if hop counts match.
            if let existing = self.routingTable[originator] {
                if newHopCount < existing.hopCount ||
                   (newHopCount == existing.hopCount && now.timeIntervalSince(existing.lastSeen) > 0) {
                    self.routingTable[originator] = RouteInfo(
                        nextHop: nextHopKey,
                        hopCount: newHopCount,
                        lastSeen: now
                    )
                }
            } else {
                self.routingTable[originator] = RouteInfo(
                    nextHop: nextHopKey,
                    hopCount: newHopCount,
                    lastSeen: now
                )
            }

            OshiLog.mesh.info("[CrossPlatformMesh] 📢 IDENTITY_ANNOUNCE \(displayName) (\(platform)) hops=\(newHopCount) via \(nextHopKey.prefix(12))…")

            // Re-broadcast if we haven't reached TTL
            if newHopCount < ttl {
                let relay = self.buildIdentityAnnounce(
                    originatorPublicKey: originator,
                    originatorDisplayName: displayName,
                    originatorPlatform: platform,
                    hopCount: newHopCount,
                    ttl: ttl
                )
                // Reuse the same id so dedupe upstream still works (prevent loops)
                let relayWithSameId = CrossPlatformMessage(
                    id: message.id,
                    type: relay.type,
                    senderPublicKey: relay.senderPublicKey,
                    senderName: relay.senderName,
                    recipientPublicKey: relay.recipientPublicKey,
                    payload: relay.payload,
                    timestamp: relay.timestamp,
                    hopCount: relay.hopCount,
                    maxHops: relay.maxHops,
                    seenBy: relay.seenBy,
                    platform: relay.platform
                )
                let relayData = relayWithSameId.toJSON()
                for (peerKey, peerConn) in self.tcpConnections {
                    if peerConn === connection { continue }    // skip the link it came from
                    if peerKey == originator { continue }      // skip the originator itself
                    self.sendData(relayData, to: peerConn)
                }
            }
        }
    }

    // MARK: - Bonjour Advertising

    private func startBonjourAdvertising() {
        guard myPort > 0 else {
            // Retry after TCP server is ready
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
                self?.startBonjourAdvertising()
            }
            return
        }

        let serviceName = "OSHI-\(myPublicKey.prefix(8))"
        netService = NetService(
            domain: CrossPlatformMesh.BONJOUR_DOMAIN,
            type: CrossPlatformMesh.BONJOUR_TYPE,
            name: serviceName,
            port: Int32(myPort)
        )

        // Set TXT record with our info
        let txtData: [String: Data] = [
            "pk": myPublicKey.data(using: .utf8)!,
            "name": myDisplayName.data(using: .utf8)!,
            "platform": "ios".data(using: .utf8)!
        ]
        netService?.setTXTRecord(NetService.data(fromTXTRecord: txtData))

        netService?.delegate = self
        netService?.publish()

        OshiLog.mesh.info("[CrossPlatformMesh] Bonjour advertising: \(serviceName) on port \(myPort)")
    }

    private func stopBonjourAdvertising() {
        netService?.stop()
        netService = nil
    }

    // MARK: - Bonjour Browsing

    private func startBonjourBrowsing() {
        netServiceBrowser = NetServiceBrowser()
        netServiceBrowser?.delegate = self
        netServiceBrowser?.searchForServices(
            ofType: CrossPlatformMesh.BONJOUR_TYPE,
            inDomain: CrossPlatformMesh.BONJOUR_DOMAIN
        )

        OshiLog.mesh.info("[CrossPlatformMesh] Bonjour browsing started")
    }

    private func stopBonjourBrowsing() {
        netServiceBrowser?.stop()
        netServiceBrowser = nil
        resolvedServices.removeAll()
    }
}

// MARK: - CBCentralManagerDelegate

extension CrossPlatformMesh: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        // Use myPublicKey instead of isRunning to avoid race condition:
        // isRunning is set async on main queue, but this delegate fires on BLE queue
        if central.state == .poweredOn && !myPublicKey.isEmpty {
            startBLEScanning()
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String : Any], rssi RSSI: NSNumber) {
        totalBLEScanResults += 1
        let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name ?? ""

        // Manual filtering since we use broad scan (nil services):
        // Check 1: Service UUID in advertisement data
        var hasOshiUuid = false
        if let serviceUUIDs = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] {
            // CBUUID("0541") == CBUUID("00000541-0000-1000-8000-00805F9B34FB")
            hasOshiUuid = serviceUUIDs.contains(where: {
                $0 == CrossPlatformMesh.SERVICE_UUID ||
                $0.uuidString == "00000541-0000-1000-8000-00805F9B34FB"
            })
        }

        // Check 2: Name starts with "OSHI"
        let hasOshiName = name.hasPrefix("OSHI")

        // Check 3: Manufacturer data contains "OSHI" bytes (Android sends this)
        var hasOshiMfg = false
        if let mfgData = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data, mfgData.count >= 6 {
            // First 2 bytes = manufacturer ID (little-endian 0xFFFF), rest = "OSHI"
            let payload = mfgData.subdata(in: 2..<mfgData.count)
            if String(data: payload, encoding: .utf8)?.hasPrefix("OSHI") == true {
                hasOshiMfg = true
            }
        }

        if !hasOshiUuid && !hasOshiName && !hasOshiMfg { return }

        oshiBLEMatches += 1
        OshiLog.mesh.info("[CrossPlatformMesh] BLE discovered OSHI: \(name) (RSSI: \(RSSI), uuid:\(hasOshiUuid), name:\(hasOshiName), mfg:\(hasOshiMfg))")

        // Avoid duplicate connections
        if discoveredPeripherals[peripheral.identifier] != nil { return }

        // Store and connect to read characteristics
        discoveredPeripherals[peripheral.identifier] = peripheral
        peripheral.delegate = self
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        OshiLog.mesh.info("[CrossPlatformMesh] BLE connected: \(peripheral.name ?? "Unknown")")
        peripheral.discoverServices([CrossPlatformMesh.SERVICE_UUID])
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        discoveredPeripherals.removeValue(forKey: peripheral.identifier)
    }
}

// MARK: - CBPeripheralDelegate

extension CrossPlatformMesh: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }

        for service in services {
            if service.uuid == CrossPlatformMesh.SERVICE_UUID {
                peripheral.discoverCharacteristics([CrossPlatformMesh.CHAR_UUID], for: service)
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let characteristics = service.characteristics else { return }

        for char in characteristics {
            if char.uuid == CrossPlatformMesh.CHAR_UUID {
                peripheral.readValue(for: char)
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value,
              let info = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return
        }

        let pk = info["pk"] as? String ?? ""
        let name = info["name"] as? String ?? "Unknown"
        let port = (info["port"] as? Int).flatMap { UInt16($0) } ?? 0
        let platform = info["platform"] as? String ?? "unknown"
        let ip = info["ip"] as? String ?? ""

        OshiLog.mesh.info("[CrossPlatformMesh] BLE peer info: \(name) (\(platform)) ip=\(ip) port=\(port) pk=\(pk.prefix(16))...")

        // Skip self-discovery
        if pk == myPublicKey {
            OshiLog.mesh.info("[CrossPlatformMesh] Skipping self-discovery via BLE")
            return
        }

        // Skip iOS peers — MultipeerConnectivity handles iOS-to-iOS mesh
        if platform == "ios" {
            OshiLog.mesh.info("[CrossPlatformMesh] Skipping iOS peer \(name) — handled by MultipeerConnectivity")
            return
        }

        // __BLE_DIAL_ANY_IP_2026_11_20__ The address comes from a BLE characteristic
        // anyone in radio range can publish. Dialled as-is, a beacon could make
        // this phone open a TCP connection to an Internet server of its choosing
        // and send IDENTITY_EXCHANGE — our public key and display name, in clear —
        // pinning this identity to the phone's public IP. A mesh peer is on the
        // same local network by definition, so only a private/link-local IPv4
        // is dialled; anything else waits for Bonjour, which resolves locally.
        if !ip.isEmpty && !Self.isDialableMeshIPv4(ip) {
            OshiLog.mesh.info("[CrossPlatformMesh] BLE peer announced a non-LAN address — not dialling it")
        }
        // If we have IP and port from BLE, try TCP connection directly
        if !ip.isEmpty && port > 0 && !pk.isEmpty && Self.isDialableMeshIPv4(ip) {
            let peer = CrossPlatformPeer(
                publicKey: pk,
                displayName: name,
                platform: platform,
                ipAddress: ip,
                port: port
            )

            // Add to discovered peers (centralized dedup)
            DispatchQueue.main.async { [weak self] in
                self?.addDiscoveredPeerOnMain(peer)
            }

            OshiLog.mesh.info("[CrossPlatformMesh] BLE provided IP+port, connecting via TCP to \(name) at \(ip):\(port)")
            connectToPeer(peer)
        }
    }
}

// MARK: - CBPeripheralManagerDelegate

extension CrossPlatformMesh: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn && !myPublicKey.isEmpty {
            startBLEAdvertising()
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error = error {
            OshiLog.mesh.info("[CrossPlatformMesh] Failed to add BLE service: \(error)")
            pendingAdvertising = false
            return
        }

        // GATT service is now in the database — safe to start advertising
        // Android will be able to discover services after connecting
        if pendingAdvertising {
            pendingAdvertising = false
            // Include BOTH Service UUID AND a short name "OSHI"
            // BLE advertising packet is limited to 31 bytes:
            //   - Flags: 3 bytes
            //   - 128-bit UUID: 18 bytes (2 header + 16 UUID)
            //   - Short name "OSHI": 6 bytes (2 header + 4 chars)
            //   = 27 bytes total, fits in 31 bytes!
            // Android can discover via UUID match OR name prefix "OSHI" — dual detection.
            peripheral.startAdvertising([
                CBAdvertisementDataServiceUUIDsKey: [CrossPlatformMesh.SERVICE_UUID],
                CBAdvertisementDataLocalNameKey: "OSHI"
            ])
            OshiLog.mesh.info("[CrossPlatformMesh] BLE advertising started (GATT service ready, name: OSHI)")
        }
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error = error {
            OshiLog.mesh.info("[CrossPlatformMesh] BLE advertising failed: \(error)")
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        if request.characteristic.uuid == CrossPlatformMesh.CHAR_UUID {
            // __ONE_READ_MUST_SEE_ONE_DOCUMENT_2026_10_23__
            //
            // Snapshot at offset 0 and serve the continuation reads of a long read
            // from that same snapshot. The announce is ~115 bytes and the default
            // ATT MTU carries 22, so a long read is the ORDINARY case here: the
            // central asks for 0, then 22, then 44. Recomputing per request embeds
            // a fresh `ip`/`port` each time, so a Wi-Fi handover or a TCP server
            // restart mid-read splices two different JSON documents together —
            // `JSONObject` throws on the Android side, the peer is dropped in
            // silence, and nothing in either log says why.
            //
            // Android has served reads this way since 2026-10-22
            // (`CrossPlatformMesh.kt` gattReadSlice / gattPayloadSnapshot); this is
            // the same rule, and `CrossPlatformMeshParityTest` now pins both.
            let data: Data
            if request.offset <= 0 {
                data = createAdvertisementData()
                gattPayloadSnapshot = data
            } else {
                data = gattPayloadSnapshot
            }
            if request.offset > data.count {
                peripheral.respond(to: request, withResult: .invalidOffset)
                return
            }
            request.value = data.subdata(in: request.offset..<data.count)
            peripheral.respond(to: request, withResult: .success)
            OshiLog.mesh.info("[CrossPlatformMesh] BLE read request served (offset:\(request.offset) of \(data.count)B)")
        } else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
        }
    }
}

// MARK: - NetServiceDelegate

extension CrossPlatformMesh: NetServiceDelegate {
    func netServiceDidPublish(_ sender: NetService) {
        OshiLog.mesh.info("[CrossPlatformMesh] Bonjour service published: \(sender.name)")
    }

    func netService(_ sender: NetService, didNotPublish errorDict: [String : NSNumber]) {
        OshiLog.mesh.info("[CrossPlatformMesh] Bonjour publish failed: \(errorDict)")
    }
}

// MARK: - NetServiceBrowserDelegate

extension CrossPlatformMesh: NetServiceBrowserDelegate {
    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        // Don't connect to ourselves
        if service.name.contains(String(myPublicKey.prefix(8))) {
            return
        }

        OshiLog.mesh.info("[CrossPlatformMesh] Bonjour found: \(service.name)")

        resolvedServices.append(service)
        service.delegate = self
        service.resolve(withTimeout: 10)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        OshiLog.mesh.info("[CrossPlatformMesh] Bonjour lost: \(service.name)")
        resolvedServices.removeAll { $0.name == service.name }

        // Remove from peers using tracked publicKey (service.name ≠ displayName from TXT)
        let trackedKey = bonjourServiceToKey[service.name]
        bonjourServiceToKey.removeValue(forKey: service.name)

        DispatchQueue.main.async { [weak self] in
            self?.crossPlatformPeers.removeAll {
                (trackedKey != nil && $0.publicKey == trackedKey) || $0.displayName == service.name
            }
        }
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        guard let addresses = sender.addresses, !addresses.isEmpty else {
            OshiLog.mesh.info("[CrossPlatformMesh] No addresses for \(sender.name)")
            return
        }

        // Get IP address.
        //
        // This used to take the first IPv4 and `break`, leaving `ipAddress` nil
        // when a peer published none — and a nil address meant no connection
        // attempt at all, ever, since nothing re-resolved. Collect them all and
        // let `MeshReconnectPolicy.pickDialAddress` rank them, so an IPv6-only
        // peer is reachable and a loopback or unscoped link-local never is.
        var candidates: [String] = []
        for addressData in addresses {
            var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            addressData.withUnsafeBytes { ptr in
                let sockaddr = ptr.bindMemory(to: sockaddr.self).baseAddress!
                getnameinfo(sockaddr, socklen_t(addressData.count), &hostname, socklen_t(hostname.count), nil, 0, NI_NUMERICHOST)
            }
            candidates.append(String(cString: hostname))
        }
        let ipAddress = MeshReconnectPolicy.pickDialAddress(candidates)
        if ipAddress == nil && !candidates.isEmpty {
            OshiLog.mesh.info("[CrossPlatformMesh] Resolved \(sender.name) to \(candidates.count) address(es), none dialable")
        }

        // Get TXT record info
        var publicKey = ""
        var displayName = sender.name
        var platform = "unknown"

        if let txtData = sender.txtRecordData() {
            let txtDict = NetService.dictionary(fromTXTRecord: txtData)
            if let pkData = txtDict["pk"], let pk = String(data: pkData, encoding: .utf8) {
                publicKey = pk
            }
            if let nameData = txtDict["name"], let name = String(data: nameData, encoding: .utf8) {
                displayName = name
            }
            if let platData = txtDict["platform"], let plat = String(data: platData, encoding: .utf8) {
                platform = plat
            }
        }

        OshiLog.mesh.info("[CrossPlatformMesh] Resolved: \(displayName) (\(platform)) at \(ipAddress ?? "?"):\(sender.port)")

        // Skip iOS peers — MultipeerConnectivity handles iOS-to-iOS mesh
        if platform == "ios" {
            OshiLog.mesh.info("[CrossPlatformMesh] Skipping iOS peer \(displayName) — handled by MultipeerConnectivity")
            return
        }

        let peer = CrossPlatformPeer(
            publicKey: publicKey,
            displayName: displayName,
            platform: platform,
            ipAddress: ipAddress,
            port: UInt16(sender.port)
        )

        // Track service.name → publicKey mapping for proper removal
        if !publicKey.isEmpty {
            bonjourServiceToKey[sender.name] = publicKey
        }

        // Add to discovered peers (centralized dedup)
        DispatchQueue.main.async { [weak self] in
            self?.addDiscoveredPeerOnMain(peer)
        }

        // Connect via TCP
        if ipAddress != nil && sender.port > 0 {
            connectToPeer(peer)
        }
    }
}

// MARK: - Data Structures

struct CrossPlatformPeer: Identifiable, Equatable {
    let id = UUID()
    var publicKey: String
    var displayName: String
    var platform: String  // "ios" or "android"
    var ipAddress: String?
    var port: UInt16?

    static func == (lhs: CrossPlatformPeer, rhs: CrossPlatformPeer) -> Bool {
        return lhs.publicKey == rhs.publicKey
    }
}

struct CrossPlatformMessage: Codable {
    let id: String
    let type: String
    let senderPublicKey: String
    let senderName: String
    let recipientPublicKey: String
    let payload: String
    let timestamp: Double
    var hopCount: Int
    let maxHops: Int
    var seenBy: [String]
    let platform: String

    func toJSON() -> Data {
        return (try? JSONEncoder().encode(self)) ?? Data()
    }

    static func fromJSON(_ data: Data) -> CrossPlatformMessage? {
        return try? JSONDecoder().decode(CrossPlatformMessage.self, from: data)
    }
}

struct RouteInfo {
    let nextHop: String
    let hopCount: Int
    let lastSeen: Date
}

// MARK: - Set Extension

extension Set where Element == String {
    mutating func add(_ element: String) {
        self.insert(element)
    }
}
