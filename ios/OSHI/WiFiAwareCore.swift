//
//  WiFiAwareCore.swift
//  OSHI
//
//  __WIFI_AWARE_2026_11_25__ Apple Wi-Fi Aware (iOS 26 `WiFiAware` framework)
//  as one MORE nearby link for contacts the user has explicitly paired with.
//  Owner approval: GitHub issue Lastoneparis/OSHI#4.
//
//  This file holds everything that does NOT need the framework, so it compiles
//  on every platform (iOS 17.5+, Mac Catalyst) and is unit-tested there:
//    • `WiFiAwareFrame` / `WiFiAwareFrameDecoder` — the byte framing on the
//      Wi-Fi Aware TCP stream.
//    • `WiFiAwarePairingStore` — which OSHI contact is bound to which
//      Apple-paired device (`WAPairedDevice.ID`, a UInt64).
//    • `WiFiAwareLinkState` + `WiFiAwareLinkReducer` — the per-contact state
//      machine (unsupported → available → paired → connecting → connected,
//      and `fallback` whenever anything goes wrong).
//    • `WiFiAwareBridge` — the ONLY thing the rest of the app calls. On a
//      platform / OS / device / build without Wi-Fi Aware it answers "not
//      connected" and every send returns false, instantly.
//
//  The framework-backed half lives in WiFiAwareLink.swift and
//  WiFiAwarePairingView.swift, both compiled only for iOS (not Catalyst).
//
//  ── DOES NOT BREAK CURRENT CONNECTIONS (owner requirement) ──────────────────
//  Purely additive. Nothing here stops, restarts or reconfigures Multipeer,
//  the iOS↔Android LAN/BLE bridge, LoRa or the relay, and nothing touches the
//  device's Wi-Fi network: Wi-Fi Aware runs beside the infrastructure network,
//  and only OSHI's own Wi-Fi Aware socket uses that path. A message goes over
//  Wi-Fi Aware only if (a) this contact is paired, (b) a link to them is up
//  and its peer PROVED it holds the contact's identity key, and (c) the peer
//  acknowledged the frame. Any "no" falls through, silently, to exactly the
//  routing that existed before.
//
//  ── HOW THE FEATURE IS SWITCHED ON (nothing changes for current builds) ─────
//  The capability needs the `com.apple.developer.wifi-aware` entitlement, and a
//  provisioning profile without it fails signing. So the main
//  OSHI/OSHI.entitlements is NOT changed. The feature is dormant unless BOTH:
//    1. the iOS build is signed with OSHI/OSHI-WiFiAware.entitlements
//       (= OSHI.entitlements + the Wi-Fi Aware key), and
//    2. the build setting `OSHI_WIFI_AWARE = YES` is defined on the OSHI
//       target; it reaches the app as Info.plist `OSHIWiFiAwareEnabled`
//       (an undefined setting expands to "" → disabled).
//  See OSHI-WiFiAware.entitlements for the exact portal steps.
//

import Foundation
import Combine

// MARK: - Framing

/// One unit on the Wi-Fi Aware stream:
///
///     [version u8 = 1][kind u8][length u32 big-endian][payload: length bytes]
///
/// Own framing rather than Network.framework's TLV so the exact bytes are
/// pinned by unit tests and a malformed or oversized header is rejected before
/// a single payload byte is buffered.
struct WiFiAwareFrame: Equatable {
    enum Kind: UInt8 {
        /// payload = UTF-8 OSHI public key ‖ 0x00 ‖ 32-byte challenge nonce
        case hello = 1
        /// payload = 32-byte nonce (echoed) ‖ 32-byte HMAC tag
        case proof = 2
        /// payload = JSON of a `SecureMessage.meshWireSafe()` envelope
        case data = 3
        /// payload = UTF-8 message id that was received intact
        case ack = 4
    }

    static let version: UInt8 = 1
    static let headerLength = 6
    /// A 13 MB photo is ~73 MB once wrapped (see the 5.62× note in
    /// MessageManager's media send). Anything larger than this is not an OSHI
    /// envelope, and refusing the HEADER means we never allocate for it.
    static let maxPayloadLength = 96 * 1024 * 1024

    let kind: Kind
    let payload: Data

    func encoded() -> Data {
        var out = Data(capacity: Self.headerLength + payload.count)
        out.append(Self.version)
        out.append(kind.rawValue)
        let n = UInt32(payload.count)
        out.append(contentsOf: [UInt8(n >> 24 & 0xFF), UInt8(n >> 16 & 0xFF),
                                UInt8(n >> 8 & 0xFF), UInt8(n & 0xFF)])
        out.append(payload)
        return out
    }

    enum HeaderError: Error, Equatable {
        case badVersion(UInt8)
        case unknownKind(UInt8)
        case tooLarge(Int)
        case truncated
    }

    /// Parses a 6-byte header into (kind, payload length).
    static func parseHeader(_ header: Data) throws -> (Kind, Int) {
        guard header.count >= headerLength else { throw HeaderError.truncated }
        let b = [UInt8](header.prefix(headerLength))
        guard b[0] == version else { throw HeaderError.badVersion(b[0]) }
        guard let kind = Kind(rawValue: b[1]) else { throw HeaderError.unknownKind(b[1]) }
        let len = Int(b[2]) << 24 | Int(b[3]) << 16 | Int(b[4]) << 8 | Int(b[5])
        guard len <= maxPayloadLength else { throw HeaderError.tooLarge(len) }
        return (kind, len)
    }

    // MARK: Payload helpers

    static func hello(publicKey: String, nonce: Data) -> WiFiAwareFrame {
        var p = Data(publicKey.utf8)
        p.append(0)
        p.append(nonce)
        return WiFiAwareFrame(kind: .hello, payload: p)
    }

    /// (publicKey, nonce) from a hello payload, or nil when malformed.
    static func parseHello(_ payload: Data) -> (String, Data)? {
        guard let zero = payload.firstIndex(of: 0) else { return nil }
        let keyBytes = payload[payload.startIndex..<zero]
        let nonce = payload[payload.index(after: zero)...]
        guard !keyBytes.isEmpty, keyBytes.count <= 512,
              nonce.count == MeshPeerAuth.nonceLength,
              let key = String(data: Data(keyBytes), encoding: .utf8) else { return nil }
        return (key, Data(nonce))
    }

    static func proof(nonce: Data, tag: Data) -> WiFiAwareFrame {
        WiFiAwareFrame(kind: .proof, payload: nonce + tag)
    }

    static func parseProof(_ payload: Data) -> (nonce: Data, tag: Data)? {
        guard payload.count == MeshPeerAuth.nonceLength + MeshPeerAuth.tagLength else { return nil }
        return (Data(payload.prefix(MeshPeerAuth.nonceLength)), Data(payload.suffix(MeshPeerAuth.tagLength)))
    }

    static func ack(messageId: String) -> WiFiAwareFrame {
        WiFiAwareFrame(kind: .ack, payload: Data(messageId.utf8))
    }
}

/// Incremental decoder: feed it whatever the socket produced, take out whole
/// frames. Used by the tests to prove that a frame split at ANY byte boundary,
/// or several frames in one read, decode identically.
struct WiFiAwareFrameDecoder {
    private var buffer = Data()

    mutating func feed(_ bytes: Data) throws -> [WiFiAwareFrame] {
        buffer.append(bytes)
        var frames: [WiFiAwareFrame] = []
        while buffer.count >= WiFiAwareFrame.headerLength {
            let (kind, len) = try WiFiAwareFrame.parseHeader(buffer)
            let total = WiFiAwareFrame.headerLength + len
            guard buffer.count >= total else { break }
            let start = buffer.startIndex
            let payload = buffer.subdata(in: (start + WiFiAwareFrame.headerLength)..<(start + total))
            frames.append(WiFiAwareFrame(kind: kind, payload: payload))
            buffer = buffer.subdata(in: (start + total)..<buffer.endIndex)
        }
        return frames
    }

    var bufferedByteCount: Int { buffer.count }
}

// MARK: - Pairing store

/// Which OSHI contact (public key) is bound to which Apple-paired Wi-Fi Aware
/// device. One device ↔ one contact: re-binding a device moves it.
///
/// Apple keeps the pairing itself (there is no public API to delete it); this
/// store is OSHI's decision to USE that device for that contact. "Unpair" in
/// the UI removes the binding, after which nothing is ever sent to that device.
final class WiFiAwarePairingStore {
    static let defaultsKey = "wifiAware.pairings.v1"

    private let defaults: UserDefaults
    private let key: String
    private let lock = NSLock()

    init(defaults: UserDefaults = .standard, key: String = WiFiAwarePairingStore.defaultsKey) {
        self.defaults = defaults
        self.key = key
    }

    private struct Record: Codable, Equatable {
        var contact: String
        var device: UInt64
        var pairedAt: Date
    }

    private func load() -> [Record] {
        guard let data = defaults.data(forKey: key),
              let records = try? JSONDecoder().decode([Record].self, from: data) else { return [] }
        return records
    }

    private func save(_ records: [Record]) {
        if records.isEmpty {
            defaults.removeObject(forKey: key)
        } else if let data = try? JSONEncoder().encode(records) {
            defaults.set(data, forKey: key)
        }
    }

    /// Binds `device` to `contact`, replacing any previous binding of either.
    func bind(contact: String, device: UInt64, at date: Date = Date()) {
        guard !contact.isEmpty else { return }
        lock.lock(); defer { lock.unlock() }
        var records = load().filter { $0.contact != contact && $0.device != device }
        records.append(Record(contact: contact, device: device, pairedAt: date))
        save(records)
    }

    func unbind(contact: String) {
        lock.lock(); defer { lock.unlock() }
        save(load().filter { $0.contact != contact })
    }

    func deviceID(for contact: String) -> UInt64? {
        lock.lock(); defer { lock.unlock() }
        return load().first { $0.contact == contact }?.device
    }

    func contact(forDevice device: UInt64) -> String? {
        lock.lock(); defer { lock.unlock() }
        return load().first { $0.device == device }?.contact
    }

    func isPaired(_ contact: String) -> Bool { deviceID(for: contact) != nil }

    var pairedContacts: [String] {
        lock.lock(); defer { lock.unlock() }
        return load().map(\.contact)
    }

    var pairedDeviceIDs: Set<UInt64> {
        lock.lock(); defer { lock.unlock() }
        return Set(load().map(\.device))
    }
}

// MARK: - Link state machine

/// Per-contact link state. `fallback` means "paired, but the Wi-Fi Aware link
/// failed — the other transports carry everything"; it never surfaces as an
/// alert, only as the absence of the "Connected" label.
enum WiFiAwareLinkState: Equatable {
    /// No Wi-Fi Aware here: Mac, iOS < 26, older iPhone, build without the
    /// entitlement. The UI shows nothing at all.
    case unsupported
    /// Supported, but this contact is not paired.
    case available
    /// Paired; not nearby right now (or not yet looked).
    case paired
    case connecting
    /// Link up AND the peer proved possession of the contact's identity key.
    case connected
    case fallback(reason: String)

    var isPaired: Bool {
        switch self {
        case .unsupported, .available: return false
        case .paired, .connecting, .connected, .fallback: return true
        }
    }

    var canCarryMessages: Bool { self == .connected }
}

enum WiFiAwareLinkEvent: Equatable {
    case capability(supported: Bool)
    case paired
    case unpaired
    case connecting
    /// Transport ready and the identity proof verified.
    case verified
    case disconnected
    case failed(reason: String)
}

/// Pure transition function. Rules:
///  • nothing but `capability(true)` leaves `unsupported`;
///  • `unpaired` always wins (back to available);
///  • connection events are ignored unless paired;
///  • `verified` is only accepted from `connecting` (or a stale `paired` /
///    `fallback` when the peer dialled us first) — never from `available`.
enum WiFiAwareLinkReducer {
    static func reduce(_ state: WiFiAwareLinkState, _ event: WiFiAwareLinkEvent) -> WiFiAwareLinkState {
        switch (state, event) {
        case (_, .capability(supported: false)):
            return .unsupported
        case (.unsupported, .capability(supported: true)):
            return .available
        case (.unsupported, _):
            return .unsupported
        case (_, .capability(supported: true)):
            return state
        case (_, .unpaired):
            return .available
        case (.available, .paired):
            return .paired
        case (_, .paired):
            return state
        case (.available, _):
            return .available
        case (.paired, .connecting), (.fallback, .connecting):
            return .connecting
        case (.connecting, .connecting), (.connected, .connecting):
            return state
        case (.connecting, .verified), (.paired, .verified), (.fallback, .verified), (.connected, .verified):
            return .connected
        case (.connected, .disconnected), (.connecting, .disconnected):
            return .paired
        case (.paired, .disconnected), (.fallback, .disconnected):
            return state
        case (_, .failed(let reason)):
            return .fallback(reason: reason)
        }
    }
}

/// Two phones that both publish AND subscribe can end up with two sockets to
/// each other. Both sides must keep the SAME one without talking about it:
/// keep the socket dialled by the lexicographically smaller identity key.
enum WiFiAwareDuplicateRule {
    static func keep(initiatorKey: String, myKey: String, peerKey: String) -> Bool {
        initiatorKey == min(myKey, peerKey)
    }
}

/// How long to wait for the peer's ACK before letting the old routing take
/// the message. Scales with size (≥ 1 MB/s is the floor we assume over a
/// Wi-Fi Aware data path), bounded so a stuck link can never hold a send.
enum WiFiAwareAckTimeout {
    static func seconds(forPayloadBytes n: Int) -> Double {
        min(60, 4 + Double(n) / 1_000_000)
    }
}

// MARK: - Bridge (the only API the rest of the app calls)

/// What the framework-backed link registers with the bridge (iOS 26 only).
protocol WiFiAwareLinkDriver: AnyObject {
    func start(myPublicKey: String)
    func pairingsChanged()
    /// Sends one envelope; true only once the peer ACKed it.
    func send(_ wire: Data, messageId: String, to contact: String) async -> Bool
}

final class WiFiAwareBridge: ObservableObject, @unchecked Sendable {
    static let shared = WiFiAwareBridge()

    let store: WiFiAwarePairingStore
    private let lock = NSLock()
    private var _states: [String: WiFiAwareLinkState] = [:]
    private var _supported = false
    private weak var _driver: WiFiAwareLinkDriver?
    private var startedKey: String?
    /// Whether `startIfEnabled` has run with an identity (used by the UI to
    /// start lazily when the identity appeared after launch).
    var hasStarted: Bool {
        lock.lock(); defer { lock.unlock() }
        return startedKey != nil
    }
    /// Strong ref; the driver is created once per process.
    private var driverHolder: AnyObject?

    init(store: WiFiAwarePairingStore = WiFiAwarePairingStore()) {
        self.store = store
    }

    /// The Info.plist gate (`OSHIWiFiAwareEnabled = $(OSHI_WIFI_AWARE)`).
    static var isEnabledInThisBuild: Bool {
        let raw = Bundle.main.object(forInfoDictionaryKey: "OSHIWiFiAwareEnabled")
        if let b = raw as? Bool { return b }
        if let s = raw as? String { return ["yes", "true", "1"].contains(s.lowercased()) }
        return false
    }

    var isSupported: Bool {
        lock.lock(); defer { lock.unlock() }
        return _supported
    }

    func state(for contact: String) -> WiFiAwareLinkState {
        lock.lock(); defer { lock.unlock() }
        guard _supported else { return .unsupported }
        if let s = _states[contact] { return s }
        return store.isPaired(contact) ? .paired : .available
    }

    func isConnected(_ contact: String) -> Bool {
        state(for: contact).canCarryMessages
    }

    /// Feeds one event into a contact's state machine and republishes.
    func apply(_ event: WiFiAwareLinkEvent, for contact: String) {
        lock.lock()
        let current = _supported ? (_states[contact] ?? (store.isPaired(contact) ? .paired : .available)) : .unsupported
        let next = WiFiAwareLinkReducer.reduce(current, event)
        let changed = next != current
        _states[contact] = next
        lock.unlock()
        if changed { publishChange() }
    }

    func setSupported(_ supported: Bool) {
        lock.lock()
        let changed = _supported != supported
        _supported = supported
        if !supported { _states.removeAll() }
        lock.unlock()
        if changed { publishChange() }
    }

    func register(driver: WiFiAwareLinkDriver) {
        lock.lock()
        _driver = driver
        driverHolder = driver
        lock.unlock()
    }

    private var driver: WiFiAwareLinkDriver? {
        lock.lock(); defer { lock.unlock() }
        return _driver
    }

    private func publishChange() {
        if Thread.isMainThread {
            objectWillChange.send()
        } else {
            DispatchQueue.main.async { [weak self] in self?.objectWillChange.send() }
        }
    }

    // MARK: Lifecycle

    /// Called once at launch. A no-op unless this build enables the feature,
    /// the OS is iOS 26+, and the device reports Wi-Fi Aware support.
    /// Errors are swallowed by design: Wi-Fi Aware is a bonus link.
    func startIfEnabled(myPublicKey: String) {
        guard !myPublicKey.isEmpty, Self.isEnabledInThisBuild else { return }
        lock.lock(); startedKey = myPublicKey; lock.unlock()
        #if os(iOS) && !targetEnvironment(macCatalyst) && canImport(WiFiAware)
        if #available(iOS 26.0, *) {
            if driver == nil { WiFiAwareLink.install(on: self) }
            driver?.start(myPublicKey: myPublicKey)
        }
        #endif
    }

    // MARK: Pairing (called by the contact screen)

    func didPair(contact: String, device: UInt64) {
        store.bind(contact: contact, device: device)
        lock.lock()
        if pendingPairing?.contact == contact { pendingPairing = nil }
        lock.unlock()
        apply(.paired, for: contact)
        driver?.pairingsChanged()
    }

    /// The device that made itself VISIBLE (Apple's DevicePairingView) gets no
    /// callback naming the device that paired with it. So while the user is on
    /// a contact's pairing screen, the first freshly paired device whose peer
    /// then PROVES it holds that contact's key is bound to that contact.
    private var pendingPairing: (contact: String, until: Date)?
    static let pendingPairingWindow: TimeInterval = 15 * 60

    func beginPairing(contact: String, now: Date = Date()) {
        lock.lock()
        pendingPairing = (contact, now.addingTimeInterval(Self.pendingPairingWindow))
        lock.unlock()
        driver?.pairingsChanged()
    }

    /// While Apple's pairing UI is on screen it publishes/subscribes our
    /// service itself; an app may publish a given service only once per
    /// device, so OUR listener/browser step aside for that time.
    private var _pairingScreenVisible = false
    var isPairingScreenVisible: Bool {
        lock.lock(); defer { lock.unlock() }
        return _pairingScreenVisible
    }

    func setPairingScreenVisible(_ visible: Bool) {
        lock.lock(); _pairingScreenVisible = visible; lock.unlock()
        driver?.pairingsChanged()
    }

    var hasPendingPairing: Bool {
        lock.lock(); defer { lock.unlock() }
        guard let p = pendingPairing else { return false }
        return p.until > Date()
    }

    /// True (once) when `contact` is the one being paired right now.
    func consumePendingPairing(for contact: String, now: Date = Date()) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard let p = pendingPairing, p.contact == contact, p.until > now else { return false }
        pendingPairing = nil
        return true
    }

    func unpair(contact: String) {
        store.unbind(contact: contact)
        apply(.unpaired, for: contact)
        driver?.pairingsChanged()
    }

    // MARK: Sending

    /// Tries the Wi-Fi Aware link for this envelope. Returns true ONLY when the
    /// paired, identity-verified peer acknowledged it; false means "nothing was
    /// decided here, route it exactly as before". Never throws, never alerts.
    func send(_ message: SecureMessage, to contact: String) async -> Bool {
        guard isConnected(contact), let driver else { return false }
        guard let wire = try? JSONEncoder().encode(message.meshWireSafe()) else { return false }
        return await driver.send(wire, messageId: message.id, to: contact)
    }
}

// MARK: - Routing hook (MessageManager's 1:1 send path)

extension MessageManager {
    /// Wi-Fi Aware first for a paired, nearby, verified contact. On success the
    /// bubble is stamped exactly like a mesh delivery (`.mesh` / `.sent`) and the
    /// relay insurance copy follows the SAME affordability rule as the mesh
    /// branch, so the recipient's other devices and a missed hop are covered as
    /// before. On `false` nothing has been changed.
    func trySendViaWiFiAware(message: SecureMessage, messageId: String,
                             recipientPublicKey: String) async -> Bool {
        let bridge = WiFiAwareBridge.shared
        guard bridge.isConnected(recipientPublicKey) else { return false }
        guard await bridge.send(message, to: recipientPublicKey) else { return false }

        await MainActor.run {
            guard let manager = MessageManager.shared,
                  let index = manager.messages.firstIndex(where: { $0.id == messageId }) else { return }
            manager.messages[index].deliveryMethod = .mesh
            manager.messages[index].deliveryStatus = .sent
            manager.saveMessages()
            manager.removeFromRetryQueue(messageId: messageId)
            RateAppManager.shared.recordMeshDeliverySucceeded()
        }

        let net = NetworkReachabilityManager.shared
        if MessageTextSplitter.duplicationAffordable(cellular: net.isCellular,
                                                    expensive: net.isExpensive,
                                                    constrained: net.isConstrained) {
            _ = await self.v2TrySend(message: message, messageId: messageId,
                                     recipientPublicKey: recipientPublicKey)
        }
        return true
    }
}
