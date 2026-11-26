//
//  WiFiAwareLink.swift
//  OSHI
//
//  __WIFI_AWARE_2026_11_25__ The framework-backed half of the Wi-Fi Aware link
//  (see WiFiAwareCore.swift for the design, the safety rules and how the
//  feature is switched on). iOS 26+ only; compiled out of Mac Catalyst, where
//  the framework is marked unavailable.
//
//  Shape:
//    • one NetworkListener publishing `_oshi-aware._tcp` to OUR paired devices;
//    • one NetworkBrowser subscribing to the same service on our paired
//      devices, dialling each one it sees that has no live socket;
//    • per socket: HELLO (our key + nonce) both ways, then each side PROVES it
//      holds the key it announced — HMAC(DH(priv, peer_pub), nonce), the exact
//      proof MeshPeerAuth already uses on Multipeer. Unlike Multipeer it is
//      ENFORCED here (both ends are this code), so no envelope moves until the
//      peer is proven to be the contact the user paired with;
//    • DATA frames carry `SecureMessage.meshWireSafe()` JSON (ciphertext only,
//      media inline in the ciphertext as on every other path); the receiver
//      answers ACK(id) once the frame decoded, and only then does the sender
//      call the message delivered over Wi-Fi Aware.
//
//  Every error ends in `.failed` → `fallback` state and a log line. No alert,
//  no retry storm, and no other transport is touched.
//

#if os(iOS) && !targetEnvironment(macCatalyst) && canImport(WiFiAware)
import Foundation
import Network
import WiFiAware

@available(iOS 26.0, *)
final class WiFiAwareLink: WiFiAwareLinkDriver, @unchecked Sendable {

    /// Must match Info.plist `WiFiAwareServices`. Apple: ≤ 15 chars, letters,
    /// digits, hyphen; an invalid name there crashes the app at first use.
    static let serviceName = "_oshi-aware._tcp"

    static var isDeviceSupported: Bool {
        WACapabilities.supportedFeatures.contains(.wifiAware)
    }

    static var publishableService: WAPublishableService? {
        WAPublishableService.allServices[serviceName]
    }

    static var subscribableService: WASubscribableService? {
        WASubscribableService.allServices[serviceName]
    }

    static func install(on bridge: WiFiAwareBridge) {
        bridge.register(driver: WiFiAwareLink(bridge: bridge))
    }

    // MARK: State

    private weak var bridge: WiFiAwareBridge?
    private let lock = NSLock()
    private var myPublicKey = ""
    private var listenerTask: Task<Void, Never>?
    private var browserTask: Task<Void, Never>?
    /// Live sessions by local id.
    private var sessions: [UUID: Session] = [:]
    /// Device ids we are currently dialling or connected to (dial dedupe).
    private var busyDevices: Set<UInt64> = []

    private init(bridge: WiFiAwareBridge) {
        self.bridge = bridge
    }

    private func log(_ s: String) {
        OshiLog.mesh.info("📶 [WiFiAware] \(s)")
    }

    // MARK: WiFiAwareLinkDriver

    func start(myPublicKey: String) {
        let supported = Self.isDeviceSupported
            && Self.publishableService != nil
            && Self.subscribableService != nil
        bridge?.setSupported(supported)
        guard supported else {
            log("not supported on this device/build — staying dormant")
            return
        }
        lock.lock()
        self.myPublicKey = myPublicKey
        lock.unlock()
        pairingsChanged()
    }

    /// (Re)starts discovery when at least one contact is paired, stops it when
    /// none is. Only OUR listener/browser are touched.
    func pairingsChanged() {
        guard let bridge, bridge.isSupported else { return }
        let anyPaired = (!bridge.store.pairedContacts.isEmpty || bridge.hasPendingPairing)
            && !bridge.isPairingScreenVisible
        lock.lock()
        let running = listenerTask != nil
        lock.unlock()
        if anyPaired && !running {
            startDiscovery()
        } else if !anyPaired && running {
            stopDiscovery()
        }
        // Drop sessions whose contact was unpaired.
        lock.lock()
        let stale = sessions.values.filter { s in
            guard let key = s.verifiedKey else { return false }
            return !bridge.store.isPaired(key)
        }
        lock.unlock()
        stale.forEach { $0.close() }
    }

    private func startDiscovery() {
        guard let pub = Self.publishableService, let sub = Self.subscribableService else { return }
        log("starting publish + subscribe on \(Self.serviceName)")

        // The link lives for the whole process (held by the bridge), so the
        // tasks capture it strongly; cancelling them is what stops them.
        let listenerTask = Task { [self] in
            do {
                let listener = try NetworkListener(
                    for: WAPublisherListener.wifiAware(.connecting(to: pub, from: .allPairedDevices)),
                    using: .parameters { TCP() }
                )
                try await listener.run { connection in
                    await self.runSession(connection, initiatedByMe: false, dialledDevice: nil)
                }
            } catch {
                self.log("listener ended: \(error)")
            }
        }

        let browserTask = Task { [self] in
            do {
                let browser = NetworkBrowser(
                    for: WASubscriberBrowser.wifiAware(.connecting(to: .allPairedDevices, from: sub))
                )
                try await browser.run { endpoints in
                    for endpoint in endpoints { self.dialIfNeeded(endpoint) }
                }
            } catch {
                self.log("browser ended: \(error)")
            }
        }

        lock.lock()
        self.listenerTask = listenerTask
        self.browserTask = browserTask
        lock.unlock()
    }

    private func stopDiscovery() {
        log("no paired contact left — stopping publish + subscribe")
        lock.lock()
        let tasks = [listenerTask, browserTask]
        listenerTask = nil
        browserTask = nil
        let all = Array(sessions.values)
        lock.unlock()
        tasks.forEach { $0?.cancel() }
        all.forEach { $0.close() }
    }

    private func dialIfNeeded(_ endpoint: WAEndpoint) {
        let device = endpoint.device.id
        lock.lock()
        let busy = busyDevices.contains(device)
        if !busy { busyDevices.insert(device) }
        lock.unlock()
        guard !busy else { return }
        if let contact = bridge?.store.contact(forDevice: device) {
            bridge?.apply(.connecting, for: contact)
        }
        Task { [self] in
            let connection = NetworkConnection(to: endpoint, using: .parameters { TCP() })
            await self.runSession(connection, initiatedByMe: true, dialledDevice: device)
        }
    }

    /// Synchronous critical sections only (NSLock is not async-safe).
    private func withLock<T>(_ body: () -> T) -> T {
        lock.lock(); defer { lock.unlock() }
        return body()
    }

    private func keptSession(for contact: String) -> Session? {
        withLock { sessions.values.first { $0.verifiedKey == contact && $0.isKept } }
    }

    private func endSession(_ session: Session, dialledDevice: UInt64?) {
        let (key, stillLive): (String?, Bool) = withLock {
            sessions[session.id] = nil
            if let d = dialledDevice ?? session.deviceID { busyDevices.remove(d) }
            let key = session.verifiedKey
            let live = key.map { k in sessions.values.contains { $0.verifiedKey == k && $0.isKept } } ?? false
            return (key, live)
        }
        if let key, !stillLive { bridge?.apply(.disconnected, for: key) }
        session.failAllPending()
    }

    // MARK: Sending

    func send(_ wire: Data, messageId: String, to contact: String) async -> Bool {
        guard let session = keptSession(for: contact) else { return false }
        let ok = await session.sendData(wire, messageId: messageId,
                                        timeout: WiFiAwareAckTimeout.seconds(forPayloadBytes: wire.count))
        log(ok ? "delivered \(messageId.prefix(8))… (\(wire.count) B)"
               : "no ack for \(messageId.prefix(8))… — normal routing takes it")
        return ok
    }

    // MARK: Session

    private func runSession(_ connection: NetworkConnection<TCP>,
                            initiatedByMe: Bool,
                            dialledDevice: UInt64?) async {
        let myKey = withLock { myPublicKey }
        let session = Session(connection: connection, initiatedByMe: initiatedByMe, myKey: myKey)
        withLock { sessions[session.id] = session }

        defer { endSession(session, dialledDevice: dialledDevice) }

        // Handshake must finish quickly or the socket is dropped.
        let watchdog = Task { [weak session] in
            try? await Task.sleep(nanoseconds: 15_000_000_000)
            if let session, session.verifiedKey == nil { session.close() }
        }
        defer { watchdog.cancel() }

        do {
            try await session.sendFrame(.hello(publicKey: myKey, nonce: session.myNonce))
            let path = connection.currentPath
            let awarePath = try? await path?.wifiAware
            session.deviceID = awarePath?.endpoint.device.id ?? dialledDevice
            try await session.receiveLoop { [self] frame in
                try await self.handle(frame, in: session)
            }
        } catch {
            if !session.isClosed {
                log("session ended: \(error)")
                if let key = session.verifiedKey ?? session.deviceID.flatMap({ bridge?.store.contact(forDevice: $0) }) {
                    bridge?.apply(.failed(reason: "\(error)"), for: key)
                }
            }
        }
    }

    private func handle(_ frame: WiFiAwareFrame, in session: Session) async throws {
        switch frame.kind {
        case .hello:
            guard let (peerKey, peerNonce) = WiFiAwareFrame.parseHello(frame.payload) else {
                throw LinkError.malformed
            }
            session.announcedKey = peerKey
            guard let secret = try? MessageManager.sharedIdentityManager?.computeSharedSecret(with: peerKey) else {
                throw LinkError.noSharedSecret
            }
            let tag = MeshPeerAuth.tag(sharedSecret: secret, nonce: peerNonce)
            try await session.sendFrame(.proof(nonce: peerNonce, tag: tag))

        case .proof:
            guard let (nonce, tag) = WiFiAwareFrame.parseProof(frame.payload),
                  MeshPeerAuth.constantTimeEquals(nonce, session.myNonce),
                  let peerKey = session.announcedKey,
                  let secret = try? MessageManager.sharedIdentityManager?.computeSharedSecret(with: peerKey),
                  MeshPeerAuth.verify(tag: tag, sharedSecret: secret, nonce: nonce) else {
                throw LinkError.proofFailed
            }
            try acceptVerified(peerKey: peerKey, in: session)

        case .data:
            guard let peerKey = session.verifiedKey else { throw LinkError.notVerified }
            guard let message = try? JSONDecoder().decode(SecureMessage.self, from: frame.payload) else {
                return // undecodable envelope: drop it, no ack → sender falls back
            }
            // The socket is proven to be `peerKey`; an envelope claiming
            // another author on it is not delivered (and not acked).
            guard MessageManager.canonicalIdentity(message.senderPublicKey) == MessageManager.canonicalIdentity(peerKey) else {
                log("dropping envelope whose sender is not the verified peer")
                return
            }
            let safe = message.meshInboundSafe()
            DispatchQueue.main.async {
                NotificationCenter.default.post(
                    name: NSNotification.Name("MeshMessageReceived"),
                    object: nil,
                    userInfo: ["message": safe]
                )
            }
            try await session.sendFrame(.ack(messageId: message.id))

        case .ack:
            if let id = String(data: frame.payload, encoding: .utf8) {
                session.resolveAck(id)
            }
        }
    }

    /// The peer proved it holds `peerKey`. Decide whether that key is the
    /// contact this DEVICE is paired with, then whether this socket is the one
    /// both sides keep.
    private func acceptVerified(peerKey: String, in session: Session) throws {
        guard let bridge else { throw LinkError.notVerified }
        let device = session.deviceID
        let boundContact = device.flatMap { bridge.store.contact(forDevice: $0) }

        if let boundContact {
            guard boundContact == peerKey else { throw LinkError.wrongIdentity }
        } else if let device, bridge.consumePendingPairing(for: peerKey) {
            // The user is on this contact's pairing screen right now and a
            // freshly paired device answered as that contact: bind it.
            bridge.didPair(contact: peerKey, device: device)
        } else if !bridge.store.isPaired(peerKey) {
            throw LinkError.notPaired
        } else if let device, bridge.store.deviceID(for: peerKey) != device {
            throw LinkError.wrongIdentity
        }

        session.verifiedKey = peerKey
        let initiator = session.initiatedByMe ? session.myKey : peerKey
        session.isKept = WiFiAwareDuplicateRule.keep(initiatorKey: initiator, myKey: session.myKey, peerKey: peerKey)

        // Close the twin socket the rule says to drop (if both are verified).
        lock.lock()
        let twins = sessions.values.filter { $0 !== session && $0.verifiedKey == peerKey }
        lock.unlock()
        if session.isKept {
            twins.filter { !$0.isKept }.forEach { $0.close() }
        } else if twins.contains(where: { $0.isKept }) {
            session.close()
            return
        } else {
            // No twin exists: this socket is the only path, keep it.
            session.isKept = true
        }
        log("verified \(peerKey.prefix(10))… on device \(device.map(String.init) ?? "?")")
        bridge.apply(.verified, for: peerKey)
    }

    enum LinkError: Error {
        case malformed, noSharedSecret, proofFailed, notVerified, wrongIdentity, notPaired
    }

    // MARK: - Session object

    final class Session: @unchecked Sendable {
        let id = UUID()
        let connection: NetworkConnection<TCP>
        let initiatedByMe: Bool
        let myKey: String
        let myNonce = MeshPeerAuth.makeNonce()

        private let lock = NSLock()
        private var _announcedKey: String?
        private var _verifiedKey: String?
        private var _deviceID: UInt64?
        private var _isKept = false
        private var _closed = false
        private var pendingAcks: [String: CheckedContinuation<Bool, Never>] = [:]
        private var receiveTask: Task<Void, Error>?

        init(connection: NetworkConnection<TCP>, initiatedByMe: Bool, myKey: String) {
            self.connection = connection
            self.initiatedByMe = initiatedByMe
            self.myKey = myKey
        }

        private func locked<T>(_ body: () -> T) -> T {
            lock.lock(); defer { lock.unlock() }
            return body()
        }

        var announcedKey: String? {
            get { locked { _announcedKey } }
            set { locked { _announcedKey = newValue } }
        }
        var verifiedKey: String? {
            get { locked { _verifiedKey } }
            set { locked { _verifiedKey = newValue } }
        }
        var deviceID: UInt64? {
            get { locked { _deviceID } }
            set { locked { _deviceID = newValue } }
        }
        var isKept: Bool {
            get { locked { _isKept } }
            set { locked { _isKept = newValue } }
        }
        var isClosed: Bool { locked { _closed } }

        func sendFrame(_ frame: WiFiAwareFrame) async throws {
            try await connection.send(frame.encoded())
        }

        /// Reads frames until the socket ends or `close()` cancels the task.
        func receiveLoop(_ onFrame: @escaping @Sendable (WiFiAwareFrame) async throws -> Void) async throws {
            let task = Task {
                while !Task.isCancelled {
                    let header = try await connection.receive(exactly: WiFiAwareFrame.headerLength).content
                    let (kind, length) = try WiFiAwareFrame.parseHeader(header)
                    let payload = length > 0 ? try await connection.receive(exactly: length).content : Data()
                    try await onFrame(WiFiAwareFrame(kind: kind, payload: payload))
                }
            }
            let alreadyClosed: Bool = locked {
                receiveTask = task
                return _closed
            }
            if alreadyClosed { task.cancel() }
            try await task.value
        }

        func sendData(_ wire: Data, messageId: String, timeout: Double) async -> Bool {
            let result: Bool = await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
                let duplicate: Bool = locked {
                    if pendingAcks[messageId] != nil || _closed { return true }
                    pendingAcks[messageId] = cont
                    return false
                }
                if duplicate { cont.resume(returning: false); return }
                Task {
                    do {
                        try await self.sendFrame(WiFiAwareFrame(kind: .data, payload: wire))
                    } catch {
                        self.resolve(messageId, ok: false)
                        return
                    }
                    try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                    self.resolve(messageId, ok: false)
                }
            }
            return result
        }

        func resolveAck(_ messageId: String) { resolve(messageId, ok: true) }

        private func resolve(_ messageId: String, ok: Bool) {
            let cont: CheckedContinuation<Bool, Never>? = locked { pendingAcks.removeValue(forKey: messageId) }
            cont?.resume(returning: ok)
        }

        func failAllPending() {
            let all: [CheckedContinuation<Bool, Never>] = locked {
                let v = Array(pendingAcks.values)
                pendingAcks.removeAll()
                return v
            }
            all.forEach { $0.resume(returning: false) }
        }

        func close() {
            let task: Task<Void, Error>? = locked {
                _closed = true
                return receiveTask
            }
            task?.cancel()
            failAllPending()
        }
    }
}
#endif
