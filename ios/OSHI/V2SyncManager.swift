//
//  V2SyncManager.swift  →  DevSync iOS layer (own-device DIRECT sync)
//  OSHI — design: docs/OSHI_DEVICE_SYNC_DIRECT.md
//
//  __DEVSYNC_DIRECT_2026_09_23__
//  This file used to hold `V2SyncManager`, the never-instantiated actor of the `/v2/sync`
//  server archive (deleted by the design, §12 phase 1). It now holds the iOS side of
//  DevSync; the protocol core is in V2Client+Sync.swift. See that file's header for why
//  the module lives in these two files and how to split it once the pbxproj is free.
//

import Foundation
import CryptoKit
import Network
import Combine
import SwiftUI
import UIKit
import UserNotifications

// ===DEVSYNC-IOS-BEGIN===
// MARK: - DevSync iOS layer (flag, keys, transports, store adapter, manager, UI)

// MARK: Feature flag (`/v2/config` devsync_enabled, default OFF) + DEBUG override

enum DevSyncFlag {
    /// DEBUG only: "1" forces on, "0" forces off (UserDefaults or launch arg `-DevSyncEnabled 1`).
    static let debugOverrideKey = "devsync.debugOverride"

    private static func remote() -> DSObj {
        guard let d = UserDefaults.standard.data(forKey: V2FeatureFlag.remoteKey), let o = DSJSON.obj(d) else { return [:] }
        return o
    }

    static var enabled: Bool {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-DevSyncEnabled"), i + 1 < args.count { return args[i + 1] == "1" || args[i + 1] == "YES" }
        if let v = UserDefaults.standard.string(forKey: debugOverrideKey) { return v == "1" }
        #endif
        return DSJSON.bool(remote(), "devsync_enabled", false)
    }

    static var relayEnabled: Bool { DSJSON.bool(remote(), "devsync_relay_enabled", true) }
    static var lanEnabled: Bool { DSJSON.bool(remote(), "devsync_lan_enabled", true) }

    /// Design §12 phase 1: while devsync runs, the legacy `/api/sync` mirror is neither
    /// written nor read (`MultiDeviceSyncManager` checks this).
    static var legacySyncSuppressed: Bool { enabled }
}

/// KVO handles on the profile keys (`userName`, `profileImageData`): the property names must be the
/// UserDefaults keys for `publisher(for:)` to observe them.
extension UserDefaults {
    @objc dynamic var userName: String? { string(forKey: "userName") }
    @objc dynamic var profileImageData: Data? { data(forKey: "profileImageData") }
}

// MARK: Per-install device key DK (Keychain, AfterFirstUnlockThisDeviceOnly — never exported)

enum DevSyncIdentity {
    static let deviceKeyKeychainItem = "oshi.devsync.deviceKey.v1"

    /// X25519 device key (raw 32 bytes). Created once per install; also the per-device
    /// mailbox `dk` (ServerPatches/per_device_mailbox/CLIENT_SPEC.md §2).
    static func loadOrCreateDeviceKey() -> Data? {
        if let d = try? KeychainHelper.load(key: deviceKeyKeychainItem), d.count == 32 { return d }
        do {
            _ = try KeychainHelper.load(key: deviceKeyKeychainItem)
        } catch KeychainError.itemNotFound {
            let k = Curve25519.KeyAgreement.PrivateKey().rawRepresentation
            do { try KeychainHelper.save(key: deviceKeyKeychainItem, data: k); return k } catch { return nil }
        } catch { return nil }   // locked: never replace a key we simply cannot read yet
        return nil
    }

    /// `dk` (std base64) and `deviceId` (32 hex) for the per-device mailbox client.
    static func publicIdentity() -> (dk: String, deviceId: String)? {
        guard let priv = loadOrCreateDeviceKey(), let pub = try? DSCrypto.x25519Public(priv) else { return nil }
        return (pub.base64EncodedString(), DSKeys.deviceIdHex(pub))
    }

    /// IK = the raw account X25519 private key exactly as stored (keys.json: NOT clamped).
    static func accountKey() -> Data? {
        guard let d = try? KeychainHelper.load(key: "privateKey"), d.count == 32 else { return nil }
        return d
    }
}

// MARK: Per-device mailbox bridge (design §15.3) — filled by the mailbox client

/// The approval of a new device also signs it into the per-device mailbox. DevSync only
/// CARRIES it: `APPROVAL.mailbox = {by, ts, sig}` (CLIENT_SPEC §3.2). The new device's
/// `dsk` travels in its HELLO as the additive field `dsk`.
enum DevSyncMailboxBridge {
    /// This device's mailbox signing public key (Ed25519, std base64), if it has one.
    static var localDsk: (() -> String?)?
    /// Sign `OSHI-DEVICE-APPROVE/1\n<identity>\n<newDeviceId>\n<newDsk>\n<ts>` → `{by, ts, sig}`.
    static var signApproval: ((_ newDeviceId: String, _ newDsk: String) -> DSObj?)?
    /// A peer's `dsk` from its HELLO (before approval), so a later Allow can sign for it.
    static var onPeerDsk: ((_ peerDeviceId: String, _ dsk: String) -> Void)?
    /// Called on the NEW device with the approver's `mailbox` object (register within 15 min).
    static var onApprovalReceived: ((_ approverDeviceId: String, _ mailbox: DSObj) -> Void)?

    static let lastApprovalKey = "devsync.mailboxApproval"

    /// Also persisted so a mailbox client that starts later still finds it.
    static func received(from approver: String, body: DSObj) {
        guard let mb = body["mailbox"] as? DSObj else { return }
        let rec: DSObj = ["approver": approver, "mailbox": mb, "receivedAt": NSNumber(value: DSTime.nowMs())]
        UserDefaults.standard.set(DSJSON.data(rec), forKey: lastApprovalKey)
        onApprovalReceived?(approver, mb)
    }
}

// MARK: Small sealed JSON files (Application Support/devsync, AtRestCipher)

final class DSFileStateStore: DSStateStore {
    let dir: URL
    private let lock = NSLock()

    init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        dir = base.appendingPathComponent("devsync", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true,
                                                 attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var u = dir
        var rv = URLResourceValues()
        rv.isExcludedFromBackup = true
        try? u.setResourceValues(rv)
    }

    func read(_ name: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        guard let raw = try? Data(contentsOf: dir.appendingPathComponent(name + ".json")) else { return nil }
        guard let plain = try? AtRestCipher.shared.open(raw) else { return nil }
        return String(data: plain, encoding: .utf8)
    }

    func write(_ name: String, _ json: String) {
        lock.lock(); defer { lock.unlock() }
        let data = Data(json.utf8)
        let sealed = (try? AtRestCipher.shared.seal(data)) ?? data
        try? sealed.write(to: dir.appendingPathComponent(name + ".json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    func wipe() {
        lock.lock(); defer { lock.unlock() }
        try? FileManager.default.removeItem(at: dir)
    }
}

// MARK: LAN transport (design §5.1): NWListener + NWBrowser on _oshi-devsync._tcp

final class DSLanLink: DSLink {
    let transport = DSKeys.transportLAN
    let label: String
    private let conn: NWConnection
    private unowned let engine: DSEngine
    private let q: DispatchQueue
    var session: DSSession?
    private var closed = false
    private let initiator: Bool
    /// The connection failed before any session existed (a dial that never reached the peer).
    var onConnectFailed: (() -> Void)?

    init(engine: DSEngine, conn: NWConnection, initiator: Bool, label: String) {
        self.engine = engine
        self.conn = conn
        self.initiator = initiator
        self.label = label
        q = DispatchQueue(label: "oshi.devsync.lan.\(label)")
    }

    func attach() {
        // The handler holds the link strongly until the connection ends (nothing else
        // retains it before its session exists); it is released on failure/cancel.
        conn.stateUpdateHandler = { st in
            switch st {
            case .ready:
                self.engine.post {
                    guard !self.closed else { return }
                    self.session = self.engine.newSession(self, initiator: self.initiator, expectedPeerId: nil)
                    self.readLength()
                }
            case .failed, .cancelled:
                self.conn.stateUpdateHandler = nil
                self.engine.post {
                    if let s = self.session { s.close("eof", silent: true) } else if case .failed = st { self.onConnectFailed?() }
                }
            default: break
            }
        }
        conn.start(queue: q)
    }

    private func readLength() {
        conn.receive(minimumIncompleteLength: 2, maximumLength: 2) { [weak self] data, _, done, err in
            guard let self else { return }
            guard err == nil, let d = data, d.count == 2 else {
                self.engine.post { self.session?.close("eof", silent: true) }
                return
            }
            let len = Int(d[d.startIndex]) << 8 | Int(d[d.startIndex + 1])
            if len == 0 { if done { self.engine.post { self.session?.close("eof", silent: true) } } else { self.readLength() }; return }
            self.conn.receive(minimumIncompleteLength: len, maximumLength: len) { [weak self] body, _, _, err2 in
                guard let self else { return }
                guard err2 == nil, let b = body, b.count == len else {
                    self.engine.post { self.session?.close("eof", silent: true) }
                    return
                }
                let frame = Data(b)
                self.engine.post { self.session?.onFrame(kind: 0, frame) }
                self.readLength()
            }
        }
    }

    func send(kind: Int, _ noise: Data) {
        guard !closed else { return }
        conn.send(content: DSWire.lanFrame(noise), completion: .contentProcessed { _ in })
    }

    func close() {
        guard !closed else { return }
        closed = true
        // Let the last frames (a BYE) flush before the socket goes.
        q.asyncAfter(deadline: .now() + 0.5) { [conn] in conn.cancel() }
    }
}

final class DSLanTransport {
    static let serviceType = "_oshi-devsync._tcp"
    private unowned let engine: DSEngine
    private var listener: NWListener?
    private var browser: NWBrowser?
    /// §5.1: 8 random hex characters PER ADVERTISEMENT — re-drawn at every hourly tag rotation,
    /// otherwise a LAN observer links the device across hours by its instance name.
    private var instanceName = DSCrypto.hex(DSCrypto.random(4))
    /// Every name this object ever advertised (the browser may still list an old one until
    /// its goodbye propagates): never dial ourselves.
    private var ownNames: Set<String> = []
    private let namesLock = NSLock()
    private var currentTag = ""
    private var lastDial: [String: Int64] = [:]
    /// __DEVSYNC_LIVE_UPGRADE_2026_09_23__ §5.3 step 4: endpoints seen (Bonjour or manual dial) and
    /// which device answered there once a session we dialled completed. Re-dialled while a peer is
    /// only on the relay (mDNS reports a service once); dropped after 3 consecutive failed dials.
    /// Engine queue only.
    private struct Endpoint { let endpoint: NWEndpoint; var peerId: String?; var failures: Int }
    private var endpoints: [String: Endpoint] = [:]
    static let redialMs: Int64 = 30_000
    static let maxFailures = 3
    private let q = DispatchQueue(label: "oshi.devsync.lan")
    private(set) var port: UInt16 = 0
    private(set) var lastError: String?

    init(engine: DSEngine) {
        self.engine = engine
        engine.onLanDialIdentified = { [weak self] label, peer in
            guard var e = self?.endpoints[label] else { return }
            e.peerId = peer; e.failures = 0
            self?.endpoints[label] = e
        }
        engine.lanUpgradeProbe = { [weak self] in self?.probeUpgrade() }
    }

    private func tagNow() -> String { DSKeys.mdnsTag(engine.disc, unixSeconds: engine.now() / 1000) }

    private func service() -> NWListener.Service {
        var txt = NWTXTRecord()
        txt["v"] = "1"
        txt["t"] = currentTag
        namesLock.lock(); ownNames.insert(instanceName); namesLock.unlock()
        return NWListener.Service(name: instanceName, type: Self.serviceType, domain: nil, txtRecord: txt)
    }

    func start(fixedPort: UInt16? = nil, advertise: Bool = true) {
        do {
            let params = NWParameters.tcp
            params.allowLocalEndpointReuse = true
            let l: NWListener
            if let fp = fixedPort, let port = NWEndpoint.Port(rawValue: fp) {
                l = try NWListener(using: params, on: port)
            } else {
                l = try NWListener(using: params)
            }
            currentTag = tagNow()
            if advertise { l.service = service() }
            l.newConnectionHandler = { [weak self] c in
                guard let self else { return }
                let label: String
                if case .hostPort(let h, let p) = c.endpoint { label = "\(h):\(p)" } else { label = "\(c.endpoint)" }
                self.engine.post { DSLanLink(engine: self.engine, conn: c, initiator: false, label: label).attach() }
            }
            l.stateUpdateHandler = { [weak self] st in
                if case .ready = st, let p = l.port?.rawValue { self?.port = p; self?.engine.log("LAN listener on port \(p)") }
                if case .failed(let e) = st { self?.lastError = "\(e)"; self?.engine.log("LAN listener failed: \(e)") }
            }
            l.start(queue: q)
            listener = l
        } catch {
            lastError = "\(error)"
            engine.log("LAN listener failed: \(error)")
        }
        guard advertise else { return }
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: Self.serviceType, domain: nil), using: .tcp)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self else { return }
            for r in results {
                guard case .service(let name, _, _, _) = r.endpoint, !self.isOwnName(name) else { continue }
                guard case .bonjour(let txt) = r.metadata, txt["v"] == "1", let tag = txt["t"] else { continue }
                let endpoint = r.endpoint
                self.engine.post {
                    guard DSKeys.acceptedTags(self.engine.disc, unixSeconds: self.engine.now() / 1000).contains(tag) else { return }
                    // Already on LAN with the device behind this endpoint: no duplicate session.
                    if let known = self.endpoints[name]?.peerId, self.engine.activeSession(known)?.link.transport == DSKeys.transportLAN { return }
                    self.dial(endpoint, key: name)
                }
            }
        }
        b.start(queue: q)
        browser = b
    }

    /// Dial a peer (Bonjour endpoint or host:port). Runs on the engine queue.
    func dial(_ endpoint: NWEndpoint, key: String) {
        if endpoints[key] == nil { endpoints[key] = Endpoint(endpoint: endpoint, peerId: nil, failures: 0) }
        let t = engine.now()
        if t - (lastDial[key] ?? 0) < Self.redialMs { return }
        lastDial[key] = t
        let c = NWConnection(to: endpoint, using: .tcp)
        let link = DSLanLink(engine: engine, conn: c, initiator: true, label: key)
        link.onConnectFailed = { [weak self] in
            guard let self, var e = self.endpoints[key] else { return }
            e.failures += 1
            if e.failures >= Self.maxFailures { self.endpoints.removeValue(forKey: key) } else { self.endpoints[key] = e }
        }
        link.attach()
    }

    /// Re-dial the known endpoints of devices NOT on LAN right now (their session is on the relay,
    /// or there is none). A LAN session that completes retires the relay one (BYE{lan}).
    func probeUpgrade() {
        for (key, e) in endpoints {
            if let peer = e.peerId {
                if engine.registry.isRevoked(peer) { continue }
                if engine.activeSession(peer)?.link.transport == DSKeys.transportLAN { continue }
            } else if !engine.registry.linked().contains(where: { engine.activeSession($0.deviceId)?.link.transport != DSKeys.transportLAN }) {
                continue   // every linked device is already on LAN
            }
            dial(e.endpoint, key: key)
        }
    }

    func dialHost(_ host: String, port: UInt16) {
        engine.post {
            guard let p = NWEndpoint.Port(rawValue: port) else { return }
            self.dial(.hostPort(host: NWEndpoint.Host(host), port: p), key: "\(host):\(port)")
        }
    }

    private func isOwnName(_ n: String) -> Bool { namesLock.lock(); defer { namesLock.unlock() }; return ownNames.contains(n) }

    /// Hourly tag rotation (§5.1) = a NEW advertisement: withdraw the old service (mDNSResponder
    /// sends the goodbye), then register a fresh random instance name with the new tag. Open
    /// sessions are untouched (the listener and its connections stay up). The host label is
    /// the system's own (iOS mDNSResponder), not ours to rotate. Mirrors DesktopDevSync.kt.
    func maintain() {
        let t = tagNow()
        guard t != currentTag, let l = listener, l.service != nil else { return }
        currentTag = t
        l.service = nil
        instanceName = DSCrypto.hex(DSCrypto.random(4))
        l.service = service()
        engine.log("LAN re-advertised (tag rotation)")
    }

    func rediscover() { engine.post { self.lastDial.removeAll() } }

    func stop() {
        browser?.cancel(); browser = nil
        listener?.cancel(); listener = nil
    }
}

// MARK: Relay socket (design §5.2): signed URLSessionWebSocketTask

final class DSWebSocket: DSRelaySocket {
    private let task: URLSessionWebSocketTask
    private let onOpen: () -> Void
    private let onText: (String) -> Void
    private let onBinary: (Data) -> Void
    private let onClosed: (Int, String) -> Void
    private let q = DispatchQueue(label: "oshi.devsync.ws")
    private var outbox: [Data] = []
    private var sending = false
    private var closed = false
    private var opened = false
    private var pinger: DispatchSourceTimer?

    init(request: URLRequest, session: URLSession, onOpen: @escaping () -> Void, onText: @escaping (String) -> Void,
         onBinary: @escaping (Data) -> Void, onClosed: @escaping (Int, String) -> Void) {
        task = session.webSocketTask(with: request)
        task.maximumMessageSize = 1 << 20
        self.onOpen = onOpen
        self.onText = onText
        self.onBinary = onBinary
        self.onClosed = onClosed
        task.resume()
        receive()
        // The relay answers the upgrade before any frame: a pong proves the socket is up.
        task.sendPing { [weak self] err in
            guard let self else { return }
            self.q.async {
                if err == nil { self.markOpen() } else { self.fail(err) }
            }
        }
        let t = DispatchSource.makeTimerSource(queue: q)
        t.schedule(deadline: .now() + 25, repeating: 25)
        t.setEventHandler { [weak self] in self?.task.sendPing { _ in } }
        t.resume()
        pinger = t
    }

    private func markOpen() {
        guard !opened, !closed else { return }
        opened = true
        onOpen()
    }

    private func receive() {
        task.receive { [weak self] result in
            guard let self else { return }
            self.q.async {
                switch result {
                case .success(let msg):
                    self.markOpen()
                    switch msg {
                    case .data(let d): self.onBinary(d)
                    case .string(let s): self.onText(s)
                    @unknown default: break
                    }
                    if !self.closed { self.receive() }
                case .failure(let e):
                    self.fail(e)
                }
            }
        }
    }

    private func fail(_ e: Error?) {
        guard !closed else { return }
        closed = true
        pinger?.cancel()
        let status = (task.response as? HTTPURLResponse)?.statusCode ?? task.closeCode.rawValue
        onClosed(status, e.map { "\($0.localizedDescription)" } ?? "closed")
        task.cancel(with: .goingAway, reason: nil)
    }

    func sendBinary(_ bytes: Data) {
        q.async {
            guard !self.closed else { return }
            self.outbox.append(bytes)
            self.drain()
        }
    }

    /// One message in flight at a time: order is preserved and a slow socket
    /// back-pressures the outbox instead of URLSession's buffers.
    private func drain() {
        guard !sending, !closed, !outbox.isEmpty else { return }
        sending = true
        let next = outbox.removeFirst()
        task.send(.data(next)) { [weak self] err in
            guard let self else { return }
            self.q.async {
                self.sending = false
                if let err { self.fail(err); return }
                self.drain()
            }
        }
    }

    func close() {
        q.async {
            guard !self.closed else { return }
            self.closed = true
            self.pinger?.cancel()
            self.task.cancel(with: .normalClosure, reason: nil)
        }
    }
}

enum DevSyncRelayAPI {
    static let path = "/v2/devsync"
    static let purgePath = "/v2/devsync/purge-legacy"

    /// DEBUG: `-DevSyncRelayBase ws://127.0.0.1:8099` (or UserDefaults `devsync.relayBase`).
    static var base: URL {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-DevSyncRelayBase"), i + 1 < args.count, let u = URL(string: args[i + 1]) { return u }
        if let s = UserDefaults.standard.string(forKey: "devsync.relayBase"), let u = URL(string: s) { return u }
        #endif
        return URL(string: "wss://oshi-messenger.com")!
    }

    static var isDefaultHost: Bool { base.host == "oshi-messenger.com" }

    static var session: URLSession {
        isDefaultHost ? V2Client.pinnedSession : URLSession(configuration: .ephemeral)
    }

    /// Account-signed headers (x-oshi-user / -signing-pubkey / -signature / -timestamp) over
    /// `METHOD\nPATH\nsha256hex(body)\nts` — the same scheme V2Client uses.
    static func sign(_ req: inout URLRequest, method: String, path: String, body: Data?) -> Bool {
        let crypto = V2KeychainCrypto()
        let user = crypto.userKey
        guard !user.isEmpty else { return false }
        let ts = HTTPRequestSignature.currentTimestampMillis()
        let canonical = HTTPRequestSignature.canonicalMessage(method: method, path: path, bodyData: body, timestamp: ts)
        guard let (sig, pub) = try? crypto.signCanonical(Data(canonical.utf8)) else { return false }
        req.setValue(user, forHTTPHeaderField: "x-oshi-user")
        req.setValue(pub, forHTTPHeaderField: "x-oshi-signing-pubkey")
        req.setValue(sig, forHTTPHeaderField: "x-oshi-signature")
        req.setValue(ts, forHTTPHeaderField: "x-oshi-timestamp")
        return true
    }

    /// __DEVSYNC_DEVICE_AUTH_2026_09_23__ device-bound upgrade (DSRelayAuth, relay_auth.json):
    /// the account signature covers sha256(B) instead of an empty body, the device signing key
    /// `dsk` (the per-device-mailbox key, `V2Device`) signs the same B, fresh nonce per attempt.
    /// `dk` is the SAME Keychain item as the devsync static key, so deviceId == H(dk).
    static func upgradeHeaders(deviceId: String) -> [String: String]? {
        let crypto = V2KeychainCrypto()
        let user = crypto.userKey
        guard !user.isEmpty, let keys = try? V2Device.loadOrCreateKeys() else { return nil }
        let dsk = keys.dsk
        do {
            return try DSRelayAuth.upgradeHeaders(
                identity: user, deviceId: deviceId, dkPub: keys.dk.publicKey.rawRepresentation,
                dskPub: dsk.publicKey.rawRepresentation, dskSign: { try dsk.signature(for: $0) },
                accountSign: { body in
                    let ts = HTTPRequestSignature.currentTimestampMillis()
                    let canonical = HTTPRequestSignature.canonicalMessage(method: "GET", path: path, bodyData: body, timestamp: ts)
                    let (sig, pub) = try crypto.signCanonical(Data(canonical.utf8))
                    return ["x-oshi-user": user, "x-oshi-signing-pubkey": pub, "x-oshi-signature": sig, "x-oshi-timestamp": ts]
                })
        } catch {
            OshiLog.messaging.infoPublic("[devsync] relay upgrade not signed: \(error)")
            return nil
        }
    }

    static func connect(deviceId: String, onOpen: @escaping () -> Void, onText: @escaping (String) -> Void,
                        onBinary: @escaping (Data) -> Void, onClosed: @escaping (Int, String) -> Void) -> DSRelaySocket? {
        var c = URLComponents(url: base, resolvingAgainstBaseURL: false)!
        if c.scheme == "https" { c.scheme = "wss" } else if c.scheme == "http" { c.scheme = "ws" }
        c.path = path
        c.queryItems = [URLQueryItem(name: "device", value: deviceId)]
        guard let url = c.url else { return nil }
        var req = URLRequest(url: url)
        req.timeoutInterval = 20
        guard let headers = upgradeHeaders(deviceId: deviceId) else { return nil }
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        return DSWebSocket(request: req, session: session, onOpen: onOpen, onText: onText, onBinary: onBinary, onClosed: onClosed)
    }

    /// `POST /v2/devsync/purge-legacy` (signed, empty body). Returns the HTTP status.
    static func purgeLegacy() async -> Int {
        var c = URLComponents(url: base, resolvingAgainstBaseURL: false)!
        if c.scheme == "wss" { c.scheme = "https" } else if c.scheme == "ws" { c.scheme = "http" }
        c.path = purgePath
        guard let url = c.url else { return -1 }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 20
        guard sign(&req, method: "POST", path: purgePath, body: nil) else { return -1 }
        do {
            let (_, resp) = try await session.data(for: req)
            return (resp as? HTTPURLResponse)?.statusCode ?? -1
        } catch { return -1 }
    }
}

// MARK: Store adapter over MessageManager / MessageGroupManager (design §7, §8)

/// Reads snapshots on the main thread, maps them off-main, writes through the IMPORT path:
/// no notification, no receipt, no send, no control processing; `read` stored as received.
final class DevSyncIOSStore: DevSyncStore {
    private struct Index {
        var me: String
        var convs: [SyncConversation]
        var byConv: [String: [SyncMessage]]
    }
    private enum MediaKind: String { case direct, group, avatar, photo }
    private var cache: Index?
    private let state: DSStateStore
    /// msgIdLower -> [sha, size]; sha -> [kind, id, type]
    private var mediaById: [String: (String, Int64)] = [:]
    private var mediaBySha: [String: (MediaKind, String, String)] = [:]
    private let metaLock = NSLock()
    /// Ids inserted by sync: the LIVE observer must not mirror them back.
    var onInserted: (([String]) -> Void)?

    init(state: DSStateStore) {
        self.state = state
        if let raw = state.read("devsync-mediaindex"), let o = DSJSON.obj(Data(raw.utf8)) {
            for (id, v) in o { if let a = v as? [Any], a.count >= 5, let sha = a[0] as? String, let size = a[1] as? NSNumber,
                                   let k = (a[2] as? String).flatMap(MediaKind.init(rawValue:)), let rid = a[3] as? String, let t = a[4] as? String {
                mediaById[id] = (sha, size.int64Value)
                mediaBySha[sha] = (k, rid, t)
            } }
        }
    }

    private func onMain<T>(_ f: @MainActor () -> T) -> T {
        if Thread.isMainThread { return MainActor.assumeIsolated(f) }
        return DispatchQueue.main.sync { MainActor.assumeIsolated(f) }
    }

    func invalidate() { cache = nil }

    static func myKey() -> String {
        if let k = MessageManager.sharedIdentityManager?.publicKey, !k.isEmpty { return DSIds.canonicalKey(k) }
        return DSIds.canonicalKey(V2KeychainCrypto().userKey)
    }

    // ---------------------------------------------------------------- native -> sync

    static func mapDirect(_ m: SecureMessage, me: String, reactions: [MessageReaction]?) -> SyncMessage? {
        if MessageExportV2.isControl(m.plaintextContent) { return nil }
        let sender = DSIds.canonicalKey(m.senderPublicKey.isEmpty ? m.senderAddress : m.senderPublicKey)
        let out = sender == me
        let peer = out ? DSIds.canonicalKey(m.recipientPublicKey.isEmpty ? m.recipientAddress : m.recipientPublicKey) : sender
        guard DSIds.isKey(peer), peer != me, !m.id.isEmpty else { return nil }
        var x = SyncMessage(id: m.id, conversation: DSIds.directConversationId(peer), outgoing: out, senderPublicKey: sender,
                            timestampMs: Int64((m.timestamp.timeIntervalSince1970 * 1000).rounded()),
                            text: m.isDeletedForEveryone ? nil : m.plaintextContent, status: m.deliveryStatus.rawValue, read: m.isRead)
        if let e = m.editedContent { x.edited = SyncEdited(text: e, atMs: m.editedAt.map { Int64(($0.timeIntervalSince1970 * 1000).rounded()) }) }
        x.deleted = m.isDeletedForEveryone
        x.replyToId = m.replyToId
        x.viewOnce = m.isViewOnce
        if let t = m.mediaType {
            let type = MessageExportV2.v2Type(t)
            x.media = SyncMedia(type: type, fileName: m.mediaFileName, mime: MessageExportV2.guessMime(type, m.mediaFileName),
                                omitted: m.isViewOnce ? "viewOnce" : nil)
        }
        x.reactions = reactionMap(reactions)
        return x
    }

    static func mapGroup(_ m: GroupMessage, text: String?, me: String, reactions: [MessageReaction]?) -> SyncMessage? {
        if m.senderPublicKey == "SYSTEM" || m.isSystemMessage || MessageExportV2.isControl(text) { return nil }
        let sender = DSIds.canonicalKey(m.senderPublicKey)
        let out = sender == me
        var x = SyncMessage(id: m.id.uuidString, conversation: DSIds.groupConversationId(m.groupId.uuidString), outgoing: out,
                            senderPublicKey: sender, timestampMs: Int64((m.timestamp.timeIntervalSince1970 * 1000).rounded()),
                            text: text, status: m.deliveryStatus?.rawValue ?? (out ? "sent" : "delivered"), read: m.isRead)
        if let e = m.editedContent { x.edited = SyncEdited(text: e, atMs: m.editedAt.map { Int64(($0.timeIntervalSince1970 * 1000).rounded()) }) }
        if let t = m.mediaType {
            let type = MessageExportV2.v2Type(t)
            x.media = SyncMedia(type: type, fileName: m.mediaFileName, mime: MessageExportV2.guessMime(type, m.mediaFileName))
        }
        x.reactions = reactionMap(reactions)
        return x
    }

    private static func reactionMap(_ rs: [MessageReaction]?) -> [String: [String]] {
        var out: [String: [String]] = [:]
        for r in rs ?? [] { out[r.emoji.rawValue, default: []].append(DSIds.canonicalKey(r.senderPublicKey)) }
        return out.mapValues { Array(Set($0)).sorted(by: CJ.utf8Less) }
    }

    private func index() -> Index {
        if let c = cache { return c }
        struct Snap { var direct: [SecureMessage]; var groups: [(MessageGroup, [GroupMessage])]; var reactions: [String: [MessageReaction]]; var me: String }
        let snap: Snap = onMain {
            let gm = MessageGroupManager.shared
            return Snap(direct: MessageManager.shared?.messages ?? [],
                        groups: gm.groups.map { ($0, gm.groupMessages[$0.id] ?? []) },
                        reactions: MessageReactionManager.shared.reactions, me: Self.myKey())
        }
        var byConv: [String: [SyncMessage]] = [:]
        var convs: [String: SyncConversation] = [:]
        var order: [String] = []
        for m in snap.direct {
            guard let x = Self.mapDirect(m, me: snap.me, reactions: snap.reactions[m.id]) else { continue }
            if convs[x.conversation] == nil {
                convs[x.conversation] = SyncConversation.direct(String(x.conversation.dropFirst("direct:".count)))
                order.append(x.conversation)
            }
            byConv[x.conversation, default: []].append(x)
        }
        for (g, list) in snap.groups {
            let conv = SyncConversation.group(g.id.uuidString, name: g.name)
            for m in list {
                // A group media message carries its bytes base64 in encryptedContent: never read that as a caption.
                let text = m.mediaType != nil ? m.plaintextContent : (m.plaintextContent ?? String(data: m.encryptedContent, encoding: .utf8))
                guard let x = Self.mapGroup(m, text: text, me: snap.me, reactions: snap.reactions[m.id.uuidString]) else { continue }
                if convs[conv.id] == nil { convs[conv.id] = conv; order.append(conv.id) }
                byConv[conv.id, default: []].append(x)
            }
        }
        // Case-insensitive duplicates would make the digest disagree with every peer: keep the first.
        for (k, list) in byConv {
            var seen = Set<String>()
            byConv[k] = list.filter { seen.insert($0.idKey).inserted }
        }
        let idx = Index(me: snap.me, convs: order.compactMap { convs[$0] }, byConv: byConv)
        cache = idx
        return idx
    }

    func conversations() -> [SyncConversation] { index().convs }
    func messages(_ conversationId: String) -> [SyncMessage] { index().byConv[conversationId] ?? [] }

    func messagesByIds(_ conversationId: String, _ ids: [String]) -> [SyncMessage] {
        let want = Set(ids.map(DSIds.asciiLower))
        var out = messages(conversationId).filter { want.contains($0.idKey) }
        var dirty = false
        for i in out.indices {
            guard var md = out[i].media, md.omitted == nil, !out[i].deleted, !out[i].viewOnce else { continue }
            if let (sha, size) = metaGet(out[i].idKey) { md.sha256 = sha; md.size = size; out[i].media = md; continue }
            // First time this message's media leaves the device: hash the plaintext once.
            guard let (bytes, kind, type) = loadMedia(conversationId, out[i]) else { md.omitted = "unavailable"; out[i].media = md; continue }
            let sha = DSCrypto.hex(DSCrypto.sha256(bytes))
            md.sha256 = sha
            md.size = Int64(bytes.count)
            out[i].media = md
            metaPut(out[i].idKey, sha, Int64(bytes.count), kind, out[i].id, type)
            dirty = true
        }
        if dirty { persistMediaIndex() }
        return out
    }

    private func metaGet(_ idKey: String) -> (String, Int64)? { metaLock.lock(); defer { metaLock.unlock() }; return mediaById[idKey] }
    private func metaPut(_ idKey: String, _ sha: String, _ size: Int64, _ kind: MediaKind, _ id: String, _ type: String) {
        metaLock.lock(); mediaById[idKey] = (sha, size); mediaBySha[sha] = (kind, id, type); metaLock.unlock()
    }

    private func persistMediaIndex() {
        metaLock.lock()
        var o: DSObj = [:]
        for (id, v) in mediaById {
            guard let r = mediaBySha[v.0] else { continue }
            o[id] = [v.0, NSNumber(value: v.1), r.0.rawValue, r.1, r.2]
        }
        metaLock.unlock()
        state.write("devsync-mediaindex", String(decoding: DSJSON.data(o), as: UTF8.self))
    }

    /// Plaintext bytes through the SAME paths as the UI (decrypted if encrypted at rest).
    private func loadMedia(_ conv: String, _ m: SyncMessage) -> (Data, MediaKind, String)? {
        guard let md = m.media else { return nil }
        if conv.hasPrefix("group:") {
            guard let t = MessageExportV2.iosGroupType(md.type), let uuid = UUID(uuidString: m.id) else { return nil }
            let gm: MessageGroupManager = onMain { MessageGroupManager.shared }
            guard let d = gm.loadGroupMediaAttachment(for: uuid.uuidString, type: t) else { return nil }
            return (d, .group, t.rawValue)
        }
        let native: SecureMessage? = onMain { MessageManager.shared?.messages.first { $0.id.lowercased() == m.id.lowercased() } }
        guard let n = native, let mm: MessageManager = onMain({ MessageManager.shared }) else { return nil }
        guard let d = n.mediaAttachment ?? n.originalMediaData ?? mm.exportMediaBytes(for: n) else { return nil }
        return (d, .direct, n.mediaType?.rawValue ?? "")
    }

    func openMedia(_ sha256: String) -> Data? {
        metaLock.lock(); let ref = mediaBySha[sha256]; metaLock.unlock()
        guard let ref else { return nil }
        var bytes: Data?
        switch ref.0 {
        case .group:
            if let t = MediaType(rawValue: ref.2) {
                let gm: MessageGroupManager = onMain { MessageGroupManager.shared }
                bytes = gm.loadGroupMediaAttachment(for: ref.1, type: t)
            }
        case .direct:
            let native: SecureMessage? = onMain { MessageManager.shared?.messages.first { $0.id.lowercased() == ref.1.lowercased() } }
            if let n = native, let mm: MessageManager = onMain({ MessageManager.shared }) {
                bytes = n.mediaAttachment ?? n.originalMediaData ?? mm.exportMediaBytes(for: n)
            }
        case .avatar:
            bytes = onMain { MessageGroupManager.shared.groups.first { $0.id.uuidString.uppercased() == ref.1.uppercased() }?.groupPictureData }
        case .photo:
            bytes = Self.localPhoto()
        }
        guard let b = bytes, DSCrypto.hex(DSCrypto.sha256(b)) == sha256 else { return nil }
        return b
    }

    // ---------------------------------------------------------------- sync -> native

    func apply(_ conversation: SyncConversation, inserts: [SyncMessage], updates: [SyncMessage]) -> DSApplyResult {
        defer { cache = nil }
        onInserted?(inserts.map(\.idKey))
        let me = index().me
        if conversation.kind == SyncConversation.kindGroup { return applyGroup(conversation, inserts, updates, me: me) }
        guard conversation.kind == SyncConversation.kindDirect, let peerRaw = conversation.peerPublicKey else { return DSApplyResult(failed: inserts.count + updates.count) }
        let peer = DSIds.canonicalKey(peerRaw)
        var natives: [SecureMessage] = []
        for m in inserts {
            let ts = Date(timeIntervalSince1970: Double(m.timestampMs) / 1000)
            let sender = m.outgoing ? me : DSIds.canonicalKey(m.senderPublicKey)
            let recipient = m.outgoing ? peer : me
            let type = m.media.flatMap { MessageExportV2.iosType($0.type) }
            var text = m.deleted ? nil : m.text
            if text == nil, !m.deleted, m.media != nil, type == nil { text = m.media?.fileName }
            natives.append(SecureMessage(
                id: m.id, senderAddress: sender, recipientAddress: recipient,
                encryptedContent: EncryptedMessage(ciphertext: "", signature: "", senderPublicKey: sender, timestamp: ts),
                timestamp: ts, isRead: m.read,
                deliveryStatus: DeliveryStatus(rawValue: m.status) ?? (m.outgoing ? .sent : .delivered),
                senderPublicKey: sender, recipientPublicKey: recipient, plaintextContent: text,
                mediaType: type, mediaFileName: m.media?.fileName,
                isViewOnce: m.viewOnce, hasBeenViewed: m.viewOnce,
                editedAt: m.edited?.atMs.map { Date(timeIntervalSince1970: Double($0) / 1000) }, editedContent: m.edited?.text,
                isDeletedForEveryone: m.deleted, replyToId: m.replyToId))
        }
        return onMain {
            var r = DSApplyResult()
            guard let mm = MessageManager.shared else { r.failed = inserts.count + updates.count; return r }
            if !natives.isEmpty { r.inserted = mm.importMessages(natives) }
            if !updates.isEmpty {
                var arr = mm.messages
                var pos: [String: Int] = [:]
                for (i, x) in arr.enumerated() { pos[x.id.lowercased()] = i }
                for u in updates {
                    guard let i = pos[u.id.lowercased()] else { r.failed += 1; continue }
                    arr[i].deliveryStatus = DeliveryStatus(rawValue: u.status) ?? arr[i].deliveryStatus
                    arr[i].isRead = u.read
                    if u.deleted {
                        arr[i].isDeletedForEveryone = true
                        arr[i].plaintextContent = nil
                        arr[i].editedContent = nil
                        arr[i].mediaAttachment = nil
                        arr[i].originalMediaData = nil
                    } else if let e = u.edited {
                        arr[i].editedContent = e.text
                        arr[i].editedAt = e.atMs.map { Date(timeIntervalSince1970: Double($0) / 1000) }
                    }
                    r.updated += 1
                }
                mm.messages = arr
                mm.saveMessages()
            }
            Self.writeReactions(inserts + updates)
            return r
        }
    }

    @MainActor private static func writeReactions(_ ms: [SyncMessage]) {
        let rm = MessageReactionManager.shared
        var changed = false
        for m in ms where !m.reactions.isEmpty || m.deleted {
            var list = rm.reactions[m.id] ?? []
            if m.deleted { if !list.isEmpty { rm.reactions.removeValue(forKey: m.id); changed = true }; continue }
            for (emoji, keys) in m.reactions {
                guard let e = ReactionEmoji(wire: emoji) else { continue }  // unknown emoji kept as itself; junk skipped
                for k in keys where !list.contains(where: { $0.emoji == e && DSIds.canonicalKey($0.senderPublicKey) == k }) {
                    list.append(MessageReaction(messageId: m.id, emoji: e, senderPublicKey: k))
                    changed = true
                }
            }
            rm.reactions[m.id] = list
        }
        if changed, let data = try? JSONEncoder().encode(rm.reactions) {
            UserDefaults.standard.set(data, forKey: "oshi_message_reactions_v2")
        }
    }

    private func applyGroup(_ c: SyncConversation, _ inserts: [SyncMessage], _ updates: [SyncMessage], me: String) -> DSApplyResult {
        guard let gidS = c.groupId, let gid = UUID(uuidString: gidS) else { return DSApplyResult(failed: inserts.count + updates.count) }
        return onMain {
            var r = DSApplyResult()
            let gm = MessageGroupManager.shared
            guard gm.groups.contains(where: { $0.id == gid }) else { r.skippedNoGroup = inserts.count + updates.count; return r }
            var list = gm.groupMessages[gid] ?? []
            var pos: [UUID: Int] = [:]
            for (i, x) in list.enumerated() { pos[x.id] = i }
            for m in inserts {
                guard let mid = UUID(uuidString: m.id) else { r.failed += 1; continue }   // iOS group rows are keyed by UUID
                if pos[mid] != nil || m.deleted { continue }
                // __BLOCKED_MEMBER_DROP_2026_09_24__ a blocked member's words do not come back
                // through another of the account's devices (an older build may have stored them).
                if !m.outgoing, BlockedContactsManager.shared.isBlocked(m.senderPublicKey) { continue }
                let type = m.media.flatMap { MessageExportV2.iosGroupType($0.type) }
                let text = m.text ?? (type == nil ? m.media?.fileName : nil)
                list.append(GroupMessage(id: mid, groupId: gid, senderPublicKey: m.outgoing ? me : m.senderPublicKey,
                                         encryptedContent: Data((text ?? "").utf8),
                                         timestamp: Date(timeIntervalSince1970: Double(m.timestampMs) / 1000), isRead: m.read,
                                         mediaType: type, mediaFileName: m.media?.fileName, plaintextContent: text,
                                         editedAt: m.edited?.atMs.map { Date(timeIntervalSince1970: Double($0) / 1000) },
                                         editedContent: m.edited?.text,
                                         deliveryStatus: m.outgoing ? (DeliveryStatus(rawValue: m.status) ?? .sent) : nil))
                r.inserted += 1
            }
            for u in updates {
                guard let mid = UUID(uuidString: u.id), let i = pos[mid] else { r.failed += 1; continue }
                list[i].isRead = u.read
                if u.outgoing { list[i].deliveryStatus = DeliveryStatus(rawValue: u.status) ?? list[i].deliveryStatus }
                if !u.deleted, let e = u.edited {
                    list[i].editedContent = e.text
                    list[i].editedAt = e.atMs.map { Date(timeIntervalSince1970: Double($0) / 1000) }
                }
                r.updated += 1
            }
            list.sort { $0.timestamp < $1.timestamp }
            gm.groupMessages[gid] = list
            gm.saveGroupMessagesPublic()
            Self.writeReactions(inserts + updates)
            return r
        }
    }

    func attachMedia(conversationId: String, messageId: String, sha256: String, file: URL) -> Bool {
        guard let bytes = try? Data(contentsOf: file) else { return false }
        defer { cache = nil }
        if conversationId == DSEngine.profileConv && messageId == DSEngine.profileRef { return adoptPhoto(sha256, bytes) }
        if messageId == DSEngine.avatarRef {
            let gid = String(conversationId.dropFirst("group:".count))
            metaPut("#avatar:" + gid.lowercased(), sha256, Int64(bytes.count), .avatar, gid, "")
            persistMediaIndex()
            return onMain {
                let gm = MessageGroupManager.shared
                guard let i = gm.groups.firstIndex(where: { $0.id.uuidString.uppercased() == gid.uppercased() }) else { return false }
                gm.groups[i].groupPictureData = bytes
                gm.saveGroups()
                return true
            }
        }
        if conversationId.hasPrefix("group:") {
            guard let mid = UUID(uuidString: messageId) else { return false }
            let ok: Bool = onMain {
                let gm = MessageGroupManager.shared
                for (gid, list) in gm.groupMessages {
                    guard let i = list.firstIndex(where: { $0.id == mid }), let t = list[i].mediaType else { continue }
                    _ = gm.saveGroupMediaAttachmentPublic(bytes, for: mid.uuidString, type: t)
                    var l = list
                    l[i].mediaData = bytes
                    gm.groupMessages[gid] = l
                    metaPutFromMain(DSIds.asciiLower(messageId), sha256, Int64(bytes.count), .group, mid.uuidString, t.rawValue)
                    return true
                }
                return false
            }
            if ok { persistMediaIndex() }
            return ok
        }
        let ok: Bool = onMain {
            guard let mm = MessageManager.shared else { return false }
            var arr = mm.messages
            guard let i = arr.firstIndex(where: { $0.id.lowercased() == messageId.lowercased() }), arr[i].mediaType != nil,
                  !arr[i].isViewOnce, !arr[i].isDeletedForEveryone else { return false }
            arr[i].mediaAttachment = bytes
            arr[i].originalMediaData = bytes
            mm.messages = arr
            mm.saveMessages()
            metaPutFromMain(DSIds.asciiLower(messageId), sha256, Int64(bytes.count), .direct, arr[i].id, arr[i].mediaType?.rawValue ?? "")
            return true
        }
        if ok { persistMediaIndex() }
        return ok
    }

    private func metaPutFromMain(_ idKey: String, _ sha: String, _ size: Int64, _ kind: MediaKind, _ id: String, _ type: String) {
        metaPut(idKey, sha, size, kind, id, type)
    }

    // ---------------------------------------------------------------- contacts (aliases + blocks)

    private func meta(_ name: String) -> DSObj { state.read(name).flatMap { DSJSON.obj(Data($0.utf8)) } ?? [:] }
    private func putMeta(_ name: String, _ o: DSObj) { state.write(name, String(decoding: DSJSON.data(o), as: UTF8.self)) }

    private func localAliases() -> [String: String] {
        guard let d = UserDefaults.standard.data(forKey: "contactAliases"), let dict = try? JSONDecoder().decode([String: String].self, from: d) else { return [:] }
        var out: [String: String] = [:]
        for (k, v) in dict where !v.isEmpty { out[DSIds.canonicalKey(k)] = v }
        return out
    }

    /// Local rows carry no timestamps: the first snapshot is "legacy" (gap-fill only, §8.5);
    /// every change observed after it is stamped with the time it was observed.
    func contacts() -> [SyncContact] {
        let aliases = localAliases()
        // __BLOCK_SYNC_LWW_2026_09_24__ with the moment of each block, so a change is stamped
        // with when the USER made it (block: BlockedContact.blockedAt; unblock: recordUnblock),
        // not with when this export happened to run.
        let blockedAtByKey: [String: Int64] = onMain {
            var m: [String: Int64] = [:]
            for b in BlockedContactsManager.shared.blockedContacts {
                m[DSIds.canonicalKey(b.publicKey)] = Int64((b.blockedAt.timeIntervalSince1970 * 1000).rounded())
            }
            return m
        }
        let blocked = Set(blockedAtByKey.keys)
        var m = meta("devsync-contacts")
        let initialized = DSJSON.bool(m, "#init")
        let now = DSTime.nowMs()
        var entries = m["c"] as? DSObj ?? [:]
        var out: [SyncContact] = []
        let keys = Set(aliases.keys).union(blocked).union(entries.keys)
        for pk in keys where DSIds.isKey(pk) {
            var e = entries[pk] as? DSObj ?? [:]
            let alias = aliases[pk]
            let isBlocked = blocked.contains(pk)
            if (e["alias"] as? String) != alias {
                e["alias"] = alias ?? NSNull()
                if initialized { e["aliasAt"] = NSNumber(value: now) }
            }
            if DSJSON.boolOrNil(e, "blocked") != isBlocked {
                // A CHANGE (a value was observed before), or a user action recorded here, is
                // stamped. The first sight of a never-blocked contact is not: stamping it
                // `blocked:false @now` would let a device that merely met the contact late
                // undo a block made earlier on another device.
                let hadValue = e["blocked"] != nil
                e["blocked"] = isBlocked
                let userAt = isBlocked ? blockedAtByKey[pk] : BlockedContactsManager.unblockedAtMs(pk)
                if (initialized && (hadValue || isBlocked)) || userAt != nil || e["blockedAt"] != nil {
                    // Never older than what is already recorded: a restamp must win LWW.
                    let prev = DSJSON.int64(e, "blockedAt") ?? 0
                    e["blockedAt"] = NSNumber(value: max(userAt ?? now, prev + 1))
                }
            }
            entries[pk] = e
            out.append(SyncContact(publicKey: pk, alias: alias, aliasAtMs: DSJSON.int64(e, "aliasAt"),
                                   blocked: (isBlocked || e["blockedAt"] != nil) ? isBlocked : nil, blockedAtMs: DSJSON.int64(e, "blockedAt"),
                                   verified: DSJSON.boolOrNil(e, "verified"), verifiedAtMs: DSJSON.int64(e, "verifiedAt")))
        }
        m["c"] = entries
        m["#init"] = true
        putMeta("devsync-contacts", m)
        return out
    }

    func putContact(_ c: SyncContact) {
        var m = meta("devsync-contacts")
        var entries = m["c"] as? DSObj ?? [:]
        var e = entries[c.publicKey] as? DSObj ?? [:]
        var aliases = localAliases()
        if let a = c.alias, !a.isEmpty, aliases[c.publicKey] != a {
            aliases[c.publicKey] = a
            if let d = try? JSONEncoder().encode(aliases) { UserDefaults.standard.set(d, forKey: "contactAliases") }
        }
        e["alias"] = c.alias ?? NSNull()
        if let t = c.aliasAtMs { e["aliasAt"] = NSNumber(value: t) }
        if let b = c.blocked {
            onMain {
                let bm = BlockedContactsManager.shared
                if b && !bm.isBlocked(c.publicKey) { bm.blockContact(publicKey: c.publicKey) }
                if !b && bm.isBlocked(c.publicKey) { bm.unblockContact(publicKey: c.publicKey) }
            }
            e["blocked"] = b
            if let t = c.blockedAtMs { e["blockedAt"] = NSNumber(value: t) }
            // A synced unblock keeps its own time (not "now") for this device's next export.
            if !b, let t = c.blockedAtMs {
                BlockedContactsManager.recordUnblock(c.publicKey, at: Date(timeIntervalSince1970: Double(t) / 1000))
            }
        }
        if let v = c.verified { e["verified"] = v }
        if let t = c.verifiedAtMs { e["verifiedAt"] = NSNumber(value: t) }
        entries[c.publicKey] = e
        m["c"] = entries
        putMeta("devsync-contacts", m)
    }

    // ---------------------------------------------------------------- groups (members + keys, D11)

    func groups() -> [SyncGroup] {
        struct G { var id: UUID; var name: String; var type: String; var admin: String; var members: [GroupMember]; var stateVersion: Int?
            var picture: Data?; var lastActivity: Date; var emoji: String?; var pinned: UUID?; var pinnedBy: String?; var muted: Bool }
        let local: [G] = onMain {
            MessageGroupManager.shared.groups.map { G(id: $0.id, name: $0.name, type: $0.type.rawValue, admin: $0.adminPublicKey, members: $0.members,
                                                      stateVersion: $0.stateVersion, picture: $0.groupPictureData, lastActivity: $0.lastActivity,
                                                      emoji: $0.avatar, pinned: $0.pinnedMessageId, pinnedBy: $0.pinnedBy, muted: $0.isMuted) }
        }
        var m = meta("devsync-groups")
        let initialized = DSJSON.bool(m, "#init")
        let now = DSTime.nowMs()
        var entries = m["g"] as? DSObj ?? [:]
        var out: [SyncGroup] = []
        var present = Set<String>()
        var indexDirty = false
        for g in local {
            let gid = g.id.uuidString.uppercased()
            present.insert(gid)
            var e = entries[gid] as? DSObj ?? [:]
            if (e["name"] as? String) != g.name { e["name"] = g.name; if initialized || e["seen"] != nil { e["nameAt"] = NSNumber(value: now) } }
            var avatar: SyncAvatar? = nil
            if let p = g.picture, !p.isEmpty {
                let sha = DSCrypto.hex(DSCrypto.sha256(p))
                avatar = SyncAvatar(sha256: sha, size: Int64(p.count), mime: "image/jpeg")
                if metaGet("#avatar:" + gid.lowercased())?.0 != sha {
                    metaPut("#avatar:" + gid.lowercased(), sha, Int64(p.count), .avatar, gid, "")
                    indexDirty = true
                }
            }
            if (e["avatar"] as? String) != avatar?.sha256 {
                e["avatar"] = avatar?.sha256 ?? NSNull()
                if initialized || e["seen"] != nil { e["avatarAt"] = NSNumber(value: now) }
            }
            // __GROUP_MUTE_SYNC_2026_09_24__ mute = the account's choice: stamped with the moment
            // the user toggled it here (recordLocalMuteChange), else when first observed.
            if DSJSON.boolOrNil(e, "muted") != g.muted {
                let hadValue = e["muted"] != nil
                e["muted"] = g.muted
                // Same rule as blocks: a first observation of "not muted" is not a fact to push.
                if hadValue || Self.localMuteAt(gid) != nil || (g.muted && (initialized || e["seen"] != nil)) {
                    let prev = DSJSON.int64(e, "mutedAt") ?? 0
                    e["mutedAt"] = NSNumber(value: max(Self.localMuteAt(gid) ?? now, prev + 1))
                }
            }
            e["seen"] = true
            e.removeValue(forKey: "left")
            e.removeValue(forKey: "leftSrc")
            // __DEVSYNC_REJOIN_2026_09_23__ C.16: the latest deliberate (re)join known here.
            if let j = [DSJSON.int64(e, "joinedAt"), Self.localJoinAt(gid)].compactMap({ $0 }).max() { e["joinedAt"] = NSNumber(value: j) }
            entries[gid] = e
            let members = g.members.map { DSIds.canonicalKey($0.publicKey) }
            var admins = g.members.filter(\.isAdmin).map { DSIds.canonicalKey($0.publicKey) }
            if DSIds.isKey(g.admin) { admins.append(DSIds.canonicalKey(g.admin)) }
            let keys = DSJSON.objs(e, "keys").compactMap { k -> SyncGroupKey? in
                guard let key = DSJSON.str(k, "key") else { return nil }
                return SyncGroupKey(kind: DSJSON.str(k, "kind") ?? SyncGroupKey.kindLegacyAes, version: DSJSON.int64(k, "version") ?? 0, key: key)
            }
            out.append(SyncGroup(groupId: gid, name: g.name, nameAtMs: DSJSON.int64(e, "nameAt"), avatar: avatar,
                                 avatarAtMs: DSJSON.int64(e, "avatarAt"), members: members, admins: admins,
                                 epoch: Int64(g.stateVersion ?? 0), joinedAtMs: DSJSON.int64(e, "joinedAt"), keys: keys,
                                 updatedAtMs: Int64((g.lastActivity.timeIntervalSince1970 * 1000).rounded()),
                                 type: g.type, creator: DSIds.isKey(g.admin) ? DSIds.canonicalKey(g.admin) : nil,
                                 avatarEmoji: g.emoji, pinnedMessageId: g.pinned?.uuidString, pinnedBy: g.pinnedBy,
                                 muted: (g.muted || e["mutedAt"] != nil) ? g.muted : nil, mutedAtMs: DSJSON.int64(e, "mutedAt")))
        }
        // A group this device knew and no longer has was LEFT here: sticky, propagated (§8.4).
        // __DEVSYNC_LEAVE_2026_09_23__ leftAt = when the user tapped Leave (recorded by leaveGroup), else now.
        for (gid, v) in entries where !present.contains(gid) {
            guard var e = v as? DSObj, e["seen"] != nil else { continue }
            if !DSJSON.bool(e, "left") {
                e["left"] = true
                let userLeave = Self.localLeaveAt(gid)
                e["leftAt"] = NSNumber(value: userLeave ?? now)
                // "user" = the user tapped Leave here; "absent" = gone for another reason (removed by
                // an admin, deleted): the latter is never lifted by a synced join (C.16).
                e["leftSrc"] = userLeave != nil ? "user" : "absent"
                entries[gid] = e
            }
            out.append(SyncGroup(groupId: gid, name: DSJSON.string(e, "name"), nameAtMs: DSJSON.int64(e, "nameAt"), left: true,
                                 leftAtMs: DSJSON.int64(e, "leftAt"), joinedAtMs: DSJSON.int64(e, "joinedAt"),
                                 keys: DSJSON.objs(e, "keys").compactMap { k in DSJSON.str(k, "key").map { SyncGroupKey(kind: DSJSON.str(k, "kind") ?? "legacy-aes", version: DSJSON.int64(k, "version") ?? 0, key: $0) } },
                                 updatedAtMs: DSJSON.int64(e, "leftAt") ?? now))
        }
        m["g"] = entries
        m["#init"] = true
        putMeta("devsync-groups", m)
        if indexDirty { persistMediaIndex() }
        return out
    }

    func putGroup(_ g: SyncGroup) {
        var m = meta("devsync-groups")
        var entries = m["g"] as? DSObj ?? [:]
        var e = entries[g.groupId] as? DSObj ?? [:]
        e["name"] = g.name
        if let t = g.nameAtMs { e["nameAt"] = NSNumber(value: t) }
        if let t = g.avatarAtMs { e["avatarAt"] = NSNumber(value: t) }
        // Keys are never dropped (older messages stay decryptable). iOS groups encrypt per
        // member (GroupEnvelopeV3), so other platforms' keys are kept and forwarded as-is.
        e["keys"] = g.keys.map { $0.json() }
        e["seen"] = true
        if let j = [DSJSON.int64(e, "joinedAt"), g.joinedAtMs].compactMap({ $0 }).max() { e["joinedAt"] = NSNumber(value: j) }
        // __GROUP_MUTE_SYNC_2026_09_24__ the merged mute is applied below; remember it (and its
        // time) so the next export does not restamp a synced value as a local change.
        if let mu = g.muted {
            e["muted"] = mu
            if let t = g.mutedAtMs { e["mutedAt"] = NSNumber(value: t) }
        }
        if g.left {
            e["left"] = true
            if let t = g.leftAtMs { e["leftAt"] = NSNumber(value: t) } else { e.removeValue(forKey: "leftAt") }
            e["leftSrc"] = "sync"
        } else if DSJSON.bool(e, "left") {
            // __DEVSYNC_REJOIN_2026_09_23__ C.16: the merged record is no longer left because of a
            // NEWER join on another device: lift the synced leave and re-create the group here. A
            // leave with no synced/user origin (admin removal, deletion) is never lifted from sync.
            let leftAt = DSJSON.int64(e, "leftAt")
            let liftable = DSJSON.string(e, "leftSrc") != "absent"
                && g.joinedAtMs != nil && (leftAt == nil || g.joinedAtMs! > leftAt!)
            guard liftable else { return }
            e.removeValue(forKey: "left"); e.removeValue(forKey: "leftAt"); e.removeValue(forKey: "leftSrc")
            UserDefaults.standard.removeObject(forKey: "devsync.leftAt." + g.groupId.uppercased())
        }
        guard let uuid = UUID(uuidString: g.groupId) else { return }
        let myKey = index().me
        let applied: Bool = onMain {
            let gm = MessageGroupManager.shared
            let idx = gm.groups.firstIndex { $0.id == uuid }
            if g.left {
                // Left on another of my devices: remove locally, silently (no broadcast from here).
                guard let i = idx else { return true }
                gm.groups.remove(at: i)
                gm.groupMessages.removeValue(forKey: uuid)
                gm.saveGroups()
                gm.saveGroupMessagesPublic()
                return true
            }
            let existing = idx.map { gm.groups[$0] }
            let admins = Set(g.admins)
            let members = g.members.map { pk -> GroupMember in
                let old = existing?.members.first { DSIds.canonicalKey($0.publicKey) == pk }
                return GroupMember(publicKey: old?.publicKey ?? pk, alias: old?.alias, joinedAt: old?.joinedAt ?? Date(), isAdmin: admins.contains(pk))
            }
            if let i = idx {
                var cur = gm.groups[i]
                cur.name = g.name
                cur.members = members.isEmpty ? cur.members : members
                if g.epoch > 0 { cur.stateVersion = Int(g.epoch) }
                if let t = g.type.flatMap(GroupType.init(rawValue:)) { cur.type = t }
                if let p = g.pinnedMessageId { cur.pinnedMessageId = UUID(uuidString: p) }
                if let p = g.pinnedBy { cur.pinnedBy = p }
                if let em = g.avatarEmoji { cur.avatar = em }
                if let mu = g.muted { cur.isMuted = mu }
                gm.groups[i] = cur
            } else {
                guard members.contains(where: { $0.publicKey == myKey }) || members.isEmpty == false else { return false }
                var ng = MessageGroup(id: uuid, name: g.name, type: g.type.flatMap(GroupType.init(rawValue:)) ?? .collaborative,
                                      adminPublicKey: g.creator ?? g.admins.first ?? myKey, members: members,
                                      createdAt: Date(timeIntervalSince1970: Double(g.updatedAtMs) / 1000),
                                      lastActivity: Date(timeIntervalSince1970: Double(g.updatedAtMs) / 1000), avatar: g.avatarEmoji)
                if g.epoch > 0 { ng.stateVersion = Int(g.epoch) }
                if let mu = g.muted { ng.isMuted = mu }
                gm.groups.append(ng)
            }
            gm.saveGroups()
            return true
        }
        if applied { entries[g.groupId] = e; m["g"] = entries; putMeta("devsync-groups", m) }
        cache = nil
    }

    /// __GROUP_MUTE_SYNC_2026_09_24__ The moment the user muted/unmuted a group on THIS device (ms).
    static func recordLocalMuteChange(_ groupId: UUID) {
        UserDefaults.standard.set(NSNumber(value: DSTime.nowMs()), forKey: "devsync.mutedAt." + groupId.uuidString.uppercased())
    }

    static func localMuteAt(_ gid: String) -> Int64? {
        (UserDefaults.standard.object(forKey: "devsync.mutedAt." + gid.uppercased()) as? NSNumber)?.int64Value
    }

    /// __DEVSYNC_LEAVE_2026_09_23__ The moment the user left a group on THIS device (ms).
    static func recordLocalLeave(_ groupId: UUID) {
        UserDefaults.standard.set(NSNumber(value: DSTime.nowMs()), forKey: "devsync.leftAt." + groupId.uuidString.uppercased())
    }

    /// __DEVSYNC_REJOIN_2026_09_23__ The moment the user deliberately joined / rejoined / created a
    /// group on THIS device (ms): exported as GROUPS.joinedAt (C.16).
    static func recordLocalJoin(_ groupId: UUID) {
        UserDefaults.standard.set(NSNumber(value: DSTime.nowMs()), forKey: "devsync.joinedAt." + groupId.uuidString.uppercased())
    }

    static func localJoinAt(_ gid: String) -> Int64? {
        (UserDefaults.standard.object(forKey: "devsync.joinedAt." + gid.uppercased()) as? NSNumber)?.int64Value
    }

    static func localLeaveAt(_ gid: String) -> Int64? {
        (UserDefaults.standard.object(forKey: "devsync.leftAt." + gid.uppercased()) as? NSNumber)?.int64Value
    }

    // ---------------------------------------------------------------- profile

    // __DEVSYNC_PROFILE_2026_09_23__ PROFILE = {name, photo:{sha256,size,mime}|null, …, updatedAt}
    // (§8.6, same shape as Android). Last writer wins on updatedAt, stamped when a local edit is
    // noticed (fingerprint of name + photo sha). The first snapshot is legacy (0). The photo travels by
    // reference: the core fetches it with MEDIA_GET and `attachMedia(profile, #photo)` adopts the
    // bytes unchanged. `pendingPhoto` = a newer device's picture adopted but not fetched yet
    // (announced instead, so this device never reverts it). Nothing is broadcast to contacts.

    static func localPhoto() -> Data? {
        guard let d = UserDefaults.standard.data(forKey: "profileImageData"), !d.isEmpty else { return nil }
        return d
    }

    private func profileFingerprint(_ name: String, _ photo: Data?) -> String {
        name + "\u{0}" + (photo.map { DSCrypto.hex(DSCrypto.sha256($0)) } ?? "")
    }

    func profile() -> SyncProfile? {
        let name = UserDefaults.standard.string(forKey: "userName") ?? ""
        let photo = Self.localPhoto()
        var m = meta("devsync-profile")
        let fp = profileFingerprint(name, photo)
        if (m["fp"] as? String) != fp {
            // First snapshot (also after an upgrade from the name-only fingerprint): legacy, keep
            // what was stamped before or 0, so any stamped peer value wins.
            let first = m["fp"] == nil
            m["fp"] = fp
            m["name"] = name
            if !first { m["updatedAt"] = NSNumber(value: DSTime.nowMs()) } else if m["updatedAt"] == nil { m["updatedAt"] = NSNumber(value: 0) }
            if !first { m.removeValue(forKey: "pendingPhoto") }   // a local edit supersedes a pending adoption
            putMeta("devsync-profile", m)
        }
        var extra = m["extra"] as? DSObj ?? [:]
        if let pending = m["pendingPhoto"] as? DSObj {
            extra["photo"] = pending
        } else if let p = photo {
            let sha = DSCrypto.hex(DSCrypto.sha256(p))
            if metaGet("#photo")?.0 != sha { metaPut("#photo", sha, Int64(p.count), .photo, "#photo", ""); persistMediaIndex() }
            extra["photo"] = ["sha256": sha, "size": NSNumber(value: p.count), "mime": "image/jpeg"] as DSObj
        } else {
            extra["photo"] = NSNull()
        }
        guard !name.isEmpty || photo != nil || m["pendingPhoto"] != nil || extra.count > 1 else { return nil }
        let at = DSJSON.int64(m, "updatedAt") ?? 0
        return SyncProfile(name: name.isEmpty ? nil : name, updatedAtMs: at, extra: extra)
    }

    /// Called by the core only when the other device's profile is strictly newer (§8.6).
    func putProfile(_ p: SyncProfile) {
        var m = meta("devsync-profile")
        if let n = p.name?.trimmingCharacters(in: .whitespacesAndNewlines), !n.isEmpty { UserDefaults.standard.set(String(n.prefix(48)), forKey: "userName") }
        var rest = p.extra
        m.removeValue(forKey: "pendingPhoto")
        if rest.keys.contains("photo") {
            if let ph = rest["photo"] as? DSObj {
                let want = DSJSON.str(ph, "sha256")?.lowercased() ?? ""
                let have = Self.localPhoto().map { DSCrypto.hex(DSCrypto.sha256($0)) }
                if !want.isEmpty && want != have { m["pendingPhoto"] = ph }   // fetched by the core, adopted in attachMedia
            } else if Self.localPhoto() != nil {
                Self.adoptPhotoBytes(nil)
            }
        }
        rest.removeValue(forKey: "photo")
        m["extra"] = rest   // fields this client does not model (links, …) pass through
        m["updatedAt"] = NSNumber(value: p.updatedAtMs)
        // What the app now stores is the ADOPTED state, not a local edit.
        m["name"] = UserDefaults.standard.string(forKey: "userName") ?? ""
        m["fp"] = profileFingerprint(UserDefaults.standard.string(forKey: "userName") ?? "", Self.localPhoto())
        putMeta("devsync-profile", m)
    }

    private func adoptPhoto(_ sha256: String, _ bytes: Data) -> Bool {
        var m = meta("devsync-profile")
        // Superseded (a newer local edit, or a newer synced picture): drop it.
        guard let pending = m["pendingPhoto"] as? DSObj, DSJSON.string(pending, "sha256").lowercased() == sha256.lowercased() else { return true }
        guard bytes.count <= 8 * 1024 * 1024 else { return false }
        Self.adoptPhotoBytes(bytes)
        metaPut("#photo", sha256.lowercased(), Int64(bytes.count), .photo, "#photo", "")
        persistMediaIndex()
        m.removeValue(forKey: "pendingPhoto")
        m["fp"] = profileFingerprint(UserDefaults.standard.string(forKey: "userName") ?? "", bytes)
        putMeta("devsync-profile", m)
        return true
    }

    /// Store the picture exactly like `IdentityManager.setProfileImage`, minus the web-session
    /// notification; `ProfileImageSyncedFromDevice` refreshes the live IdentityManager. No broadcast.
    private static func adoptPhotoBytes(_ data: Data?) {
        let pk = V2KeychainCrypto().userKey
        if let data {
            UserDefaults.standard.set(data, forKey: "profileImageData")
            if !pk.isEmpty { UserDefaults.standard.set(data, forKey: "profileImageData_\(pk)") }
        } else {
            UserDefaults.standard.removeObject(forKey: "profileImageData")
            if !pk.isEmpty { UserDefaults.standard.removeObject(forKey: "profileImageData_\(pk)") }
        }
        DispatchQueue.main.async {
            if let data { NotificationCenter.default.post(name: NSNotification.Name("ProfileImageSyncedFromDevice"), object: data) }
            else { MessageManager.sharedIdentityManager?.profileImageData = nil }
        }
    }

    // ---------------------------------------------------------------- read state (never emits receipts)

    // __DEVSYNC_READ_STATE_2026_09_23__ READ_STATE{conv, readUpTo, at}, one per conversation (§8.7).
    // Export: readUpTo = the newest INCOMING message shown as read here (or a higher value adopted from
    // another device); `at` moves when readUpTo does. Apply: max of both; incoming rows at or before it
    // become read (unread badges clear). Never a receipt: the device that read them already sent it,
    // and opening the conversation afterwards finds nothing unread.

    private func readMarks() -> DSObj { meta("devsync-read")["r"] as? DSObj ?? [:] }

    private func putReadMarks(_ marks: DSObj) {
        var m = meta("devsync-read")
        m["r"] = marks
        putMeta("devsync-read", m)
    }

    func readStates() -> [SyncReadState] {
        var marks = readMarks()
        let now = DSTime.nowMs()
        var dirty = false
        for (conv, list) in index().byConv where conv.hasPrefix("direct:") || conv.hasPrefix("group:") {
            guard let t = list.filter({ !$0.outgoing && $0.read }).map(\.timestampMs).max(), t > 0 else { continue }
            let prev = (marks[conv] as? DSObj).flatMap { DSJSON.int64($0, "r") } ?? 0
            if t > prev { marks[conv] = ["r": NSNumber(value: t), "a": NSNumber(value: now)] as DSObj; dirty = true }
        }
        if dirty { putReadMarks(marks) }
        return marks.compactMap { conv, v -> SyncReadState? in
            guard let o = v as? DSObj, let r = DSJSON.int64(o, "r") else { return nil }
            return SyncReadState(conv: conv, readUpToMs: r, atMs: DSJSON.int64(o, "a") ?? 0)
        }
    }

    func putReadState(_ r: SyncReadState) {
        var marks = readMarks()
        let prev = marks[r.conv] as? DSObj
        let upTo = max(prev.flatMap { DSJSON.int64($0, "r") } ?? 0, r.readUpToMs)
        let at = max(prev.flatMap { DSJSON.int64($0, "a") } ?? 0, r.atMs)
        marks[r.conv] = ["r": NSNumber(value: upTo), "a": NSNumber(value: at)] as DSObj
        putReadMarks(marks)
        if r.conv.hasPrefix("direct:") {
            let peer = DSIds.canonicalKey(String(r.conv.dropFirst("direct:".count)))
            onMain {
                guard let mm = MessageManager.shared else { return }
                var arr = mm.messages
                var changed = false
                for i in arr.indices where !arr[i].isRead
                    && DSIds.canonicalKey(arr[i].senderPublicKey) == peer
                    && Int64((arr[i].timestamp.timeIntervalSince1970 * 1000).rounded()) <= upTo {
                    arr[i].isRead = true
                    changed = true
                }
                if changed { mm.messages = arr; mm.saveMessages() }
            }
        } else if r.conv.hasPrefix("group:"), let uuid = UUID(uuidString: String(r.conv.dropFirst("group:".count))) {
            let me = index().me
            onMain {
                let gm = MessageGroupManager.shared
                guard var list = gm.groupMessages[uuid] else { return }
                var changed = false
                for i in list.indices where !list[i].isRead
                    && DSIds.canonicalKey(list[i].senderPublicKey) != me
                    && Int64((list[i].timestamp.timeIntervalSince1970 * 1000).rounded()) <= upTo {
                    list[i].isRead = true
                    changed = true
                }
                if changed { gm.groupMessages[uuid] = list; gm.saveGroupMessagesPublic() }
            }
        }
        cache = nil
    }
}

// MARK: Manager (main actor): lifecycle, policy, UI state, alerts, LIVE mirror

struct DevSyncDeviceRow: Identifiable, Equatable {
    var id: String
    var name: String
    var platform: String
    var lastSyncAtMs: Int64?
    var online: Bool
    var transport: String?
    var syncing: Bool
}

@MainActor
final class DevSyncManager: ObservableObject {
    static let shared = DevSyncManager()

    @Published private(set) var devices: [DevSyncDeviceRow] = []
    @Published private(set) var pending: [DSPairingRequest] = []
    @Published private(set) var running = false
    @Published private(set) var thisDeviceId = ""
    @Published private(set) var status = ""
    @Published var mediaEnabled: Bool = UserDefaults.standard.object(forKey: "devsync.media") as? Bool ?? true {
        didSet { UserDefaults.standard.set(mediaEnabled, forKey: "devsync.media") }
    }

    private var engine: DSEngine?
    private var store: DevSyncIOSStore?
    private var stateStore: DSFileStateStore?
    private var lan: DSLanTransport?
    private var relay: DSRelayHub?
    private var accountTag = Data()
    private var sessionInfos: [String: DSSessionInfo] = [:]
    private var bridge: Bridge?
    private let path = NWPathMonitor()
    private let pathState = DSPathState()
    private var cancellables: [AnyCancellable] = []
    private let known = DSKnownIds()
    private var shownAlertFor: String?
    private var bgTask: UIBackgroundTaskIdentifier = .invalid

    private init() {
        let ps = pathState
        path.pathUpdateHandler = { p in ps.set(expensive: p.isExpensive, constrained: p.isConstrained) }
        // Also driven by UIKit directly: SwiftUI's onChange(of: scenePhase) misses the launch .active.
        NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { DevSyncManager.shared.appDidBecomeActive() }
        }
        path.start(queue: DispatchQueue(label: "oshi.devsync.path"))
    }

    // ---------------------------------------------------------------- lifecycle (D12: foreground only)

    func appDidBecomeActive() {
        #if DEBUG
        DevSyncSelfTest.runIfRequested()
        DevSyncSelfTest.startHarnessIfRequested()
        #endif
        guard DevSyncFlag.enabled else { if running { stop(reason: "flag-off") }; return }
        if engine != nil { observeMessageManager(retries: 0) }
        if bgTask != .invalid { UIApplication.shared.endBackgroundTask(bgTask); bgTask = .invalid }
        start()
        purgeLegacyOnce()
    }

    func appDidEnterBackground() {
        guard running, let e = engine else { return }
        // ~30 s grace to finish the current batch, then drop every socket (no background sync on iPhone).
        bgTask = UIApplication.shared.beginBackgroundTask(withName: "devsync-finish") { [weak self] in
            Task { @MainActor in self?.stop(reason: "background") }
        }
        let deadline = Date().addingTimeInterval(25)
        Task { @MainActor in
            while true {
                let busy: Bool = await withCheckedContinuation { c in
                    e.post { c.resume(returning: e.sessions.contains { $0.state == .sync && (!$0.outboundIdle || !$0.pullComplete) }) }
                }
                guard UIApplication.shared.applicationState == .background else { return }
                if !busy || Date() >= deadline { self.stop(reason: "background"); return }
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }

    private func start() {
        guard let ik = DevSyncIdentity.accountKey(), let dk = DevSyncIdentity.loadOrCreateDeviceKey() else {
            status = "no identity"
            return
        }
        let tag = DSCrypto.sha256(ik)
        if engine != nil, tag != accountTag { stop(reason: "identity-changed"); engine = nil }
        if engine == nil {
            let st = DSFileStateStore()
            let s = DevSyncIOSStore(state: st)
            var cfg = DSConfig(deviceName: Self.deviceName(), platform: "ios",
                               appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "",
                               cacheDir: FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0])
            cfg.historySource = UIDevice.current.userInterfaceIdiom == .phone
            cfg.mediaPolicy = mediaEnabled ? DSHello.mediaAlways : DSHello.mediaNever
            let ps = pathState
            cfg.network = {
                let (exp, con, lowBattery) = ps.get()
                return DSNet(metered: exp, lowPower: ProcessInfo.processInfo.isLowPowerModeEnabled || lowBattery || con)
            }
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-DevSyncFastRelay") { cfg.relayDelayMs = 300 }
            #endif
            guard let e = try? DSEngine(config: cfg, accountPriv: ik, deviceKeyPriv: dk, store: s, state: st) else { status = "engine error"; return }
            e.logger = { line in OshiLog.messaging.infoPublic(line) }
            let b = Bridge(owner: self)
            e.delegate = b
            // __DEVSYNC_DEVICE_AUTH_2026_09_23__ HELLO always carries our dsk (the key the relay upgrade
            // is signed with), even when the mailbox client has not installed its bridge yet.
            e.helloExtras = { (DevSyncMailboxBridge.localDsk?() ?? (try? V2Device.loadOrCreateKeys())?.dskB64).map { ["dsk": $0] } }
            e.onPeerHello = { peer, hello in if let d = DSJSON.str(hello, "dsk") { DevSyncMailboxBridge.onPeerDsk?(peer, d) } }
            e.approvalExtras = { peerId, _, hello in
                guard let dsk = hello?.dsk, let f = DevSyncMailboxBridge.signApproval, let mb = f(peerId, dsk) else { return nil }
                return ["mailbox": mb]
            }
            e.onApprovalReceived = { peer, body in DevSyncMailboxBridge.received(from: peer, body: body) }
            let kn = known
            s.onInserted = { ids in kn.add(ids) }
            engine = e
            store = s
            stateStore = st
            bridge = b
            accountTag = tag
            thisDeviceId = e.deviceId
            UIDevice.current.isBatteryMonitoringEnabled = true
            pathState.refreshBattery()
            NotificationCenter.default.addObserver(forName: UIDevice.batteryLevelDidChangeNotification, object: nil, queue: .main) { [ps = pathState] _ in
                MainActor.assumeIsolated { ps.refreshBattery() }
            }
            observeLocalMessages()
        }
        guard let e = engine, !running else { engine?.syncNow(); return }
        running = true
        e.start()
        var lanOn = DevSyncFlag.lanEnabled, relayOn = DevSyncFlag.relayEnabled
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-DevSyncNoLan") { lanOn = false }
        if ProcessInfo.processInfo.arguments.contains("-DevSyncNoRelay") { relayOn = false }
        #endif
        if lanOn {
            let l = DSLanTransport(engine: e)
            var port: UInt16? = nil
            #if DEBUG
            let args = ProcessInfo.processInfo.arguments
            if let i = args.firstIndex(of: "-DevSyncLanPort"), i + 1 < args.count { port = UInt16(args[i + 1]) }
            #endif
            l.start(fixedPort: port)
            lan = l
            e.maintainHooks = [{ [weak l] in l?.maintain() }]
            #if DEBUG
            if let i = args.firstIndex(of: "-DevSyncLanDial"), i + 1 < args.count {
                let parts = args[i + 1].split(separator: ":")
                if parts.count == 2, let p = UInt16(parts[1]) {
                    let host = String(parts[0])
                    e.schedule(2000) { l.dialHost(host, port: p) }
                }
            }
            #endif
        }
        if relayOn {
            let hub = DSRelayHub(engine: e) { id, open, text, bin, closed in
                DevSyncRelayAPI.connect(deviceId: id, onOpen: open, onText: text, onBinary: bin, onClosed: closed)
            }
            relay = hub
            e.maintainHooks.append { [weak hub] in hub?.maintain() }
            e.sessionClosedHooks = [{ [weak hub] s in hub?.onSessionClosed(s) }]
            e.relayDialSoon = { [weak hub] p in hub?.dialSoon(p) }   // §5.3 step 5
            hub.start()
        }
        status = "running"
        refreshDevices()
    }

    func stop(reason: String) {
        guard let e = engine, running else { return }
        running = false
        let l = lan, r = relay
        lan = nil
        relay = nil
        e.stop {
            r?.stop()
            l?.stop()
            Task { @MainActor in
                if self.bgTask != .invalid { UIApplication.shared.endBackgroundTask(self.bgTask); self.bgTask = .invalid }
            }
        }
        status = "stopped (\(reason))"
        sessionInfos.removeAll()
        refreshDevices()
    }

    static func deviceName() -> String {
        let n = UIDevice.current.name
        return n.isEmpty ? UIDevice.current.model : n
    }

    private func purgeLegacyOnce() {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-DevSyncNoPurge") { return }
        #endif
        let key = "devsync.purgedLegacyAt"
        guard UserDefaults.standard.object(forKey: key) == nil else { return }
        Task.detached {
            let code = await DevSyncRelayAPI.purgeLegacy()
            if code == 200 || code == 207 {
                await MainActor.run { UserDefaults.standard.set(Date(), forKey: key) }
            }
            OshiLog.messaging.infoPublic("[devsync] purge-legacy → \(code)")
        }
    }

    // ---------------------------------------------------------------- UI actions

    func approve(_ peerId: String, allow: Bool) {
        engine?.approve(peerId, allow: allow)
        pending.removeAll { $0.peerId == peerId }
    }

    func revoke(_ peerId: String) {
        engine?.revoke(peerId)
        Task { @MainActor in try? await Task.sleep(nanoseconds: 300_000_000); self.refreshDevices() }
    }

    func syncNow() {
        guard running else { appDidBecomeActive(); return }
        lan?.rediscover()
        engine?.syncNow()
    }

    fileprivate func refreshDevices() {
        guard let e = engine else { devices = []; return }
        let linked = e.registry.linked()
        devices = linked.map { d in
            let s = sessionInfos[d.deviceId]
            return DevSyncDeviceRow(id: d.deviceId, name: d.name, platform: d.platform, lastSyncAtMs: d.lastSyncAtMs,
                                    online: s != nil && s?.state != .closed, transport: s?.transport,
                                    syncing: s?.state == .sync && !(s?.pullComplete ?? false && s?.mediaComplete ?? false))
        }
    }

    // ---------------------------------------------------------------- LIVE mirror (§9, Appendix C.15)

    /// __DEVSYNC_LIVE_UPGRADE_2026_09_23__ Every local write (1:1 and group messages — inserts,
    /// statuses, edits, deletions —, reactions, groups) is reported to the engine, which debounces
    /// (150 ms quiet / 600 ms max) and then, per open session, pushes the messages newer than its
    /// last SUMMARY as LIVE (never echoing what came from that peer), re-sends only the state
    /// records that changed, and re-sends SUMMARY unless identical. Replaces the first cut's own
    /// 800 ms timer + 5 s throttled re-diff (and its direct `onLocalMessages` push, which the
    /// engine's one-hop bookkeeping did not see).
    private func observeLocalMessages() {
        observeMessageManager()
        guard cancellables.isEmpty else { return }
        let changed: (Any) -> Void = { [weak self] _ in Task { @MainActor in self?.engine?.onLocalStateChanged() } }
        let gm = MessageGroupManager.shared
        gm.$groupMessages.dropFirst().sink(receiveValue: changed).store(in: &cancellables)
        gm.$groups.dropFirst().sink(receiveValue: changed).store(in: &cancellables)
        // __BLOCK_SYNC_LWW_2026_09_24__ a block / unblock is mirrored at once, not at the 30 s sweep.
        BlockedContactsManager.shared.$blockedContacts.dropFirst().sink(receiveValue: changed).store(in: &cancellables)
        MessageReactionManager.shared.$reactions.dropFirst().sink(receiveValue: changed).store(in: &cancellables)
        // C.15 (c): a profile edit (name / picture) is mirrored at once, not at the 30 s sweep.
        UserDefaults.standard.publisher(for: \.userName).dropFirst().removeDuplicates().sink(receiveValue: changed).store(in: &cancellables)
        UserDefaults.standard.publisher(for: \.profileImageData).dropFirst().removeDuplicates().sink(receiveValue: changed).store(in: &cancellables)
    }

    /// `MessageManager.shared` is assigned asynchronously after its init, so at engine start it can
    /// still be nil (measured: a 2-simulator run mirrored a new message only at the 30 s sweep).
    /// Subscribe as soon as it exists, and again if the instance is replaced.
    private var messagesObserver: AnyCancellable?
    private weak var observedMM: MessageManager?
    private func observeMessageManager(retries: Int = 30) {
        if let mm = MessageManager.shared {
            guard observedMM !== mm else { return }
            observedMM = mm
            messagesObserver = mm.$messages.dropFirst().sink { [weak self] _ in
                Task { @MainActor in self?.engine?.onLocalStateChanged() }
            }
            return
        }
        guard retries > 0 else { return }
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            self?.observeMessageManager(retries: retries - 1)
        }
    }

    #if DEBUG
    func debugLocalChanged() { engine?.onLocalStateChanged() }
    #endif

    // ---------------------------------------------------------------- engine events (from the engine queue)

    fileprivate final class Bridge: DSEngineDelegate {
        weak var owner: DevSyncManager?
        init(owner: DevSyncManager) { self.owner = owner }
        private func ui(_ f: @escaping @MainActor (DevSyncManager) -> Void) {
            DispatchQueue.main.async { [weak self] in if let o = self?.owner { MainActor.assumeIsolated { f(o) } } }
        }
        func devsyncDevicesChanged() { ui { $0.refreshDevices() } }
        func devsyncPairingRequest(_ r: DSPairingRequest) {
            ui { o in
                o.pending.removeAll { $0.peerId == r.peerId }
                o.pending.append(r)
                #if DEBUG
                if o.autoApprove { o.approve(r.peerId, allow: true); return }
                #endif
                o.presentPairingPrompt(r)
            }
        }
        func devsyncPairingResolved(peerId: String, linked: Bool) {
            ui { o in o.pending.removeAll { $0.peerId == peerId }; o.dismissPrompt(peerId); o.refreshDevices() }
        }
        func devsyncSessionChanged(_ i: DSSessionInfo) {
            ui { o in
                guard let p = i.peerId else { return }
                if i.state == .closed { o.sessionInfos.removeValue(forKey: p) } else { o.sessionInfos[p] = i }
                o.refreshDevices()
            }
        }
        func devsyncApplied(peerId: String, conversationId: String, result: DSApplyResult) {
            #if DEBUG
            // Two-simulator harness: when rows land (live-mirroring latency, Appendix C.15).
            if ProcessInfo.processInfo.arguments.contains("-DevSyncDump"), result.inserted > 0 {
                let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                let line = "applied \(conversationId.prefix(20)) +\(result.inserted) ms=\(DSTime.nowMs())\n"
                if let h = try? FileHandle(forWritingTo: docs.appendingPathComponent("devsync_applied.txt")) {
                    h.seekToEndOfFile(); h.write(Data(line.utf8)); try? h.close()
                } else { try? line.write(to: docs.appendingPathComponent("devsync_applied.txt"), atomically: true, encoding: .utf8) }
            }
            #endif
        }
        func devsyncSynced(peerId: String, stats: DSSessionStats) {
            ui { o in
                o.refreshDevices()
                OshiLog.messaging.infoPublic("[devsync] synced with \(peerId.prefix(8)): +\(stats.inserted) ~\(stats.updated) media=\(stats.mediaFetched)")
            }
        }
        func devsyncAlert(kind: String, deviceName: String) { ui { $0.alert(kind: kind, deviceName: deviceName) } }
    }

    // ---------------------------------------------------------------- alerts + pairing prompt

    private func alert(kind: String, deviceName: String) {
        let body: String
        switch kind {
        case "device-linked": body = String(format: "devsync.alert.linked".localized, deviceName)
        case "unapproved-device": body = String(format: "devsync.alert.unapproved".localized, deviceName)
        case "revoked-by-peer": body = String(format: "devsync.alert.revoked".localized, deviceName)
        default: return
        }
        let c = UNMutableNotificationContent()
        c.title = "devsync.title".localized
        c.body = body
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "devsync.\(kind).\(UUID().uuidString)", content: c, trigger: nil))
    }

    private weak var promptController: UIAlertController?
    private var promptPeer: String?

    fileprivate func presentPairingPrompt(_ r: DSPairingRequest) {
        guard UIApplication.shared.applicationState == .active else { return }
        if promptPeer == r.peerId, promptController != nil { return }
        guard let root = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first?.rootViewController else { return }
        var top = root
        while let p = top.presentedViewController { top = p }
        let code = String(r.code.prefix(3)) + " " + String(r.code.suffix(3))
        let platform = Self.platformLabel(r.platform)
        let msg = r.thisDeviceIsNew && !r.peerApproved
            ? String(format: "devsync.pending.waiting".localized, code)
            : String(format: "devsync.pending.message".localized, r.name, platform, code)
        let ac = UIAlertController(title: "devsync.pending.title".localized, message: msg, preferredStyle: .alert)
        let peer = r.peerId
        ac.addAction(UIAlertAction(title: "devsync.deny".localized, style: .cancel) { [weak self] _ in
            Task { @MainActor in self?.approve(peer, allow: false); self?.promptPeer = nil }
        })
        ac.addAction(UIAlertAction(title: "devsync.allow".localized, style: .default) { [weak self] _ in
            Task { @MainActor in self?.approve(peer, allow: true); self?.promptPeer = nil }
        })
        promptPeer = r.peerId
        promptController = ac
        top.present(ac, animated: true)
    }

    fileprivate func dismissPrompt(_ peerId: String) {
        if promptPeer == peerId { promptController?.dismiss(animated: true); promptPeer = nil }
    }

    static func platformLabel(_ p: String) -> String {
        switch p {
        case "ios": return "iOS"
        case "android": return "Android"
        case "desktop": return "Desktop"
        default: return p
        }
    }

    // ---------------------------------------------------------------- DEBUG harness

    #if DEBUG
    /// `-DevSyncAutoApprove 1`: approve every pairing request (simulator tests only).
    var autoApprove: Bool { ProcessInfo.processInfo.arguments.contains("-DevSyncAutoApprove") }
    #endif
}

final class DSPathState: @unchecked Sendable {
    private let lock = NSLock()
    private var expensive = false, constrained = false, lowBattery = false
    func set(expensive: Bool, constrained: Bool) { lock.lock(); self.expensive = expensive; self.constrained = constrained; lock.unlock() }
    func get() -> (Bool, Bool, Bool) { lock.lock(); defer { lock.unlock() }; return (expensive, constrained, lowBattery) }
    @MainActor func refreshBattery() {
        let d = UIDevice.current
        let low = d.batteryLevel >= 0 && d.batteryLevel < 0.2 && d.batteryState == .unplugged
        lock.lock(); lowBattery = low; lock.unlock()
    }
}

final class DSKnownIds: @unchecked Sendable {
    private let lock = NSLock()
    private var ids = Set<String>()
    func add<S: Sequence>(_ s: S) where S.Element == String { lock.lock(); ids.formUnion(s); lock.unlock() }
    func insertIfNew(_ id: String) -> Bool { lock.lock(); defer { lock.unlock() }; return ids.insert(id).inserted }
}

// MARK: Linked devices screen (Settings)

struct LinkedDevicesView: View {
    @ObservedObject private var mgr = DevSyncManager.shared
    @State private var confirmRemove: DevSyncDeviceRow?
    /// __APPLE_WATCH_2027__ "Smartwatch": OFF by default (WatchBridgeSettings).
    @AppStorage(WatchBridgeSettings.enabledKey) private var smartwatchOn = WatchBridgeSettings.defaultEnabled
    @ObservedObject private var watch = WatchConnectivityManager.shared

    var body: some View {
        List {
            if !DevSyncFlag.enabled {
                Section { Text("devsync.disabled".localized).foregroundColor(.secondary) }
            }
            if !mgr.pending.isEmpty {
                Section(header: Text("devsync.pending.section".localized)) {
                    ForEach(mgr.pending, id: \.peerId) { r in
                        VStack(alignment: .leading, spacing: 6) {
                            Text("\(r.name) · \(DevSyncManager.platformLabel(r.platform))").font(.headline)
                            Text(String(r.code.prefix(3)) + " " + String(r.code.suffix(3)))
                                .font(.system(.title, design: .monospaced).weight(.semibold))
                                .accessibilityLabel(r.code.map(String.init).joined(separator: " "))
                            HStack {
                                Button("devsync.deny".localized, role: .destructive) { mgr.approve(r.peerId, allow: false) }
                                    .buttonStyle(.bordered)
                                Spacer()
                                Button("devsync.allow".localized) { mgr.approve(r.peerId, allow: true) }
                                    .buttonStyle(.borderedProminent)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
            Section(header: Text("devsync.title".localized), footer: Text("devsync.footer".localized)) {
                HStack {
                    Image(systemName: "iphone").foregroundColor(.accentColor)
                    VStack(alignment: .leading) {
                        Text(DevSyncManager.deviceName())
                        Text("devsync.thisDevice".localized).font(.caption).foregroundColor(.secondary)
                    }
                }
                if mgr.devices.isEmpty {
                    Text("devsync.noDevices".localized).font(.callout).foregroundColor(.secondary)
                }
                ForEach(mgr.devices) { d in
                    HStack {
                        Image(systemName: d.platform == "desktop" ? "desktopcomputer" : (d.platform == "android" ? "candybarphone" : "ipad.and.iphone"))
                            .foregroundColor(d.online ? .green : .secondary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(d.name.isEmpty ? DevSyncManager.platformLabel(d.platform) : d.name)
                            Text(subtitle(d)).font(.caption).foregroundColor(.secondary)
                        }
                        Spacer()
                        if d.syncing { ProgressView() }
                    }
                    .swipeActions {
                        Button("devsync.remove".localized, role: .destructive) { confirmRemove = d }
                    }
                }
            }
            Section {
                Button { mgr.syncNow() } label: { Label("devsync.syncNow".localized, systemImage: "arrow.triangle.2.circlepath") }
                    .disabled(!DevSyncFlag.enabled)
                Toggle(isOn: $mgr.mediaEnabled) { Label("devsync.media.toggle".localized, systemImage: "photo.on.rectangle") }
            }
            smartwatchSection
        }
        .navigationTitle("devsync.title".localized)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { if DevSyncFlag.enabled { mgr.appDidBecomeActive() } }
        .alert(item: $confirmRemove) { d in
            Alert(title: Text("devsync.remove".localized),
                  message: Text(String(format: "devsync.removeConfirm".localized, d.name)),
                  primaryButton: .destructive(Text("devsync.remove".localized)) { mgr.revoke(d.id) },
                  secondaryButton: .cancel())
        }
    }

    /// __APPLE_WATCH_2027__ The opt-in for the Apple Watch app — the same
    /// "Settings › Linked devices › Smartwatch" place as on Android (Wear OS).
    /// While it is off the iPhone answers the watch with "off" and nothing else.
    private var smartwatchSection: some View {
        Section(header: Text("smartwatch.title".localized),
                footer: Text("smartwatch.footer".localized)) {
            Toggle(isOn: Binding(get: { smartwatchOn },
                                 set: { on in
                                     smartwatchOn = on
                                     WatchBridgeSettings.setEnabled(on)
                                 })) {
                Label("smartwatch.toggle".localized, systemImage: "applewatch")
            }
            if smartwatchOn {
                HStack(spacing: 8) {
                    Circle()
                        .fill(watch.watchAppInstalled ? (watch.isWatchReachable ? Color.green : Color.orange) : Color.gray)
                        .frame(width: 8, height: 8)
                        .accessibilityHidden(true)
                    Text(smartwatchStatus)
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
            }
        }
    }

    private var smartwatchStatus: String {
        if !watch.isWatchPaired { return "smartwatch.status.not_paired".localized }
        if !watch.watchAppInstalled { return "smartwatch.status.not_installed".localized }
        return watch.isWatchReachable ? "smartwatch.status.connected".localized
                                      : "smartwatch.status.not_reachable".localized
    }

    private func subtitle(_ d: DevSyncDeviceRow) -> String {
        var parts: [String] = [DevSyncManager.platformLabel(d.platform)]
        if d.online, let t = d.transport { parts.append(t == DSKeys.transportLAN ? "devsync.transport.lan".localized : "devsync.transport.relay".localized) }
        if let at = d.lastSyncAtMs {
            let f = RelativeDateTimeFormatter()
            parts.append(String(format: "devsync.lastSync".localized, f.localizedString(for: Date(timeIntervalSince1970: Double(at) / 1000), relativeTo: Date())))
        } else {
            parts.append("devsync.neverSynced".localized)
        }
        return parts.joined(separator: " · ")
    }
}

// MARK: DEBUG self-test (`-OSHISelfTestDevSync`, writes Documents/selftest_devsync.txt)

#if DEBUG
enum DevSyncSelfTest {
    private static var ran = false

    /// Vectors dir: env `OSHI_DEVSYNC_FIXTURES` (use `SIMCTL_CHILD_OSHI_DEVSYNC_FIXTURES=<repo>/docs/fixtures/devsync`)
    /// or `-OSHIDevSyncFixtures <dir>`. The simulator reads the host's disk.
    static func runIfRequested() {
        let args = ProcessInfo.processInfo.arguments
        guard !ran, args.contains("-OSHISelfTestDevSync") else { return }
        ran = true
        var dir = ProcessInfo.processInfo.environment["OSHI_DEVSYNC_FIXTURES"]
        if let i = args.firstIndex(of: "-OSHIDevSyncFixtures"), i + 1 < args.count { dir = args[i + 1] }
        DispatchQueue.global(qos: .userInitiated).async {
            var lines: [String] = []
            if let dir {
                let r = DevSyncVectorCheck.run(dir: URL(fileURLWithPath: dir))
                lines = r.lines
            } else {
                lines = ["FAIL fixtures: set OSHI_DEVSYNC_FIXTURES"]
            }
            lines.append("deviceId \(DevSyncIdentity.publicIdentity()?.deviceId ?? "-")")
            let out = lines.joined(separator: "\n") + "\n"
            let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            try? out.write(to: docs.appendingPathComponent("selftest_devsync.txt"), atomically: true, encoding: .utf8)
            OshiLog.storage.infoPublic("DEVSYNC SELFTEST \(lines.last ?? "")")
        }
    }

    private static var harnessStarted = false
    @MainActor static func startHarnessIfRequested() {
        guard !harnessStarted else { return }
        let args = ProcessInfo.processInfo.arguments
        // `-DevSyncTestIdentity <base64 of X25519 priv ‖ Ed25519 priv>`: two simulators on ONE account.
        if let i = args.firstIndex(of: "-DevSyncTestIdentity"), i + 1 < args.count {
            guard let im = MessageManager.sharedIdentityManager, MessageManager.shared != nil else {
                Task { @MainActor in try? await Task.sleep(nanoseconds: 500_000_000); DevSyncManager.shared.appDidBecomeActive() }
                return
            }
            let key = args[i + 1]
            let want = Data(base64Encoded: key).flatMap { try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: $0.prefix(32)) }?.publicKey.rawRepresentation.base64EncodedString()
            if im.publicKey != want {
                im.connectWallet(seedPhrase: key)
                UserDefaults.standard.set(true, forKey: "hasSeenOnboarding")
                UserDefaults.standard.set(true, forKey: "hasCompletedFirstTimeGuide")
                // connectWallet's wipe of the previous identity is partly async: seed after it.
                harnessStarted = true
                Task { @MainActor in
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                    seedIfRequested()
                    startDump()
                    DevSyncManager.shared.appDidBecomeActive()
                }
                return
            }
        }
        harnessStarted = true
        seedIfRequested()
        startDump()
    }

    /// `-DevSyncLiveInject <prefix>:<seconds>`: <seconds> after start, import ONE new 1:1 message
    /// with the test peer through the import path (a local write while a session is open) and log
    /// when; the other simulator logs when it lands (`devsync_applied.txt`): C.15 latency.
    @MainActor private static func startLiveInject() {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-DevSyncLiveInject"), i + 1 < args.count else { return }
        let parts = args[i + 1].split(separator: ":")
        guard parts.count == 2, let secs = Double(parts[1]) else { return }
        let prefix = String(parts[0])
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(secs * 1_000_000_000))
            guard let mm = MessageManager.shared else { return }
            let me = DevSyncIOSStore.myKey(), peer = "3b6TE6T6dfqOHzhG5WVPCdhuzDHQXrKCFxVoTqnlPUE="
            let ts = Date()
            let m = SecureMessage(id: "\(prefix)-LIVE", senderAddress: me, recipientAddress: peer,
                                  encryptedContent: EncryptedMessage(ciphertext: "", signature: "", senderPublicKey: me, timestamp: ts),
                                  timestamp: ts, isRead: true, deliveryStatus: .delivered,
                                  senderPublicKey: me, recipientPublicKey: peer, plaintextContent: "\(prefix) live")
            let n = mm.importMessages([m])
            let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            try? "injected \(n) ms=\(DSTime.nowMs())\n".write(to: docs.appendingPathComponent("devsync_inject.txt"), atomically: true, encoding: .utf8)
        }
    }

    /// Two-simulator harness for C.16 / profile photo: `-DevSyncSetPhotoAt <s>` (set a new random
    /// profile picture + name, as a local edit), `-DevSyncLeaveAt <s>` / `-DevSyncRejoinAt <s>`
    /// (leave / rejoin the test group). Each logs `devsync_actions.txt`.
    @MainActor private static func startActions() {
        let args = ProcessInfo.processInfo.arguments
        func at(_ flag: String) -> Double? {
            guard let i = args.firstIndex(of: flag), i + 1 < args.count else { return nil }
            return Double(args[i + 1])
        }
        func log(_ l: String) {
            let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            let u = docs.appendingPathComponent("devsync_actions.txt")
            let line = l + " ms=\(DSTime.nowMs())\n"
            if let h = try? FileHandle(forWritingTo: u) { h.seekToEndOfFile(); h.write(Data(line.utf8)); try? h.close() }
            else { try? line.write(to: u, atomically: true, encoding: .utf8) }
        }
        let gid = UUID(uuidString: "5E2A9C1B-7D3F-4B8A-9E6C-0F1D2A3B4C5D")!
        let peer = "3b6TE6T6dfqOHzhG5WVPCdhuzDHQXrKCFxVoTqnlPUE="
        if let t = at("-DevSyncSetPhotoAt") {
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: UInt64(t * 1e9))
                var bytes = Data(count: 120_000)
                bytes.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, 120_000, $0.baseAddress!) }
                UserDefaults.standard.set("Photo \(DSTime.nowMs() % 10000)", forKey: "userName")
                MessageManager.sharedIdentityManager?.setProfileImage(bytes)
                DevSyncManager.shared.debugLocalChanged()
                log("photo-set \(DSCrypto.hex(DSCrypto.sha256(bytes)))")
            }
        }
        if let t = at("-DevSyncLeaveAt") {
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: UInt64(t * 1e9))
                MessageGroupManager.shared.leaveGroup(gid, userPublicKey: DevSyncIOSStore.myKey())
                DevSyncManager.shared.debugLocalChanged()
                log("left")
            }
        }
        if let t = at("-DevSyncRejoinAt") {
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: UInt64(t * 1e9))
                MessageGroupManager.shared.joinGroupFromInvite(gid: gid, inviterKey: peer, name: "DevSync Test Group")
                DevSyncManager.shared.debugLocalChanged()
                log("rejoined")
            }
        }
    }

    @MainActor private static func startDump() {
        startLiveInject()
        startActions()
        if ProcessInfo.processInfo.arguments.contains("-DevSyncDump") {
            Task { @MainActor in
                while true { dumpState(); try? await Task.sleep(nanoseconds: 2_000_000_000) }
            }
        }
    }

    /// `-DevSyncSeed <prefix>:<n>`: add n synthetic 1:1 messages with one fixed test peer
    /// through the import path (two-simulator test only).
    @MainActor static func seedIfRequested() {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-DevSyncSeed"), i + 1 < args.count, let mm = MessageManager.shared else { return }
        let parts = args[i + 1].split(separator: ":")
        guard parts.count == 2, let n = Int(parts[1]) else { return }
        let prefix = String(parts[0])
        let me = DevSyncIOSStore.myKey()
        let peer = "3b6TE6T6dfqOHzhG5WVPCdhuzDHQXrKCFxVoTqnlPUE="
        let base = Date().addingTimeInterval(-86_400)
        var list: [SecureMessage] = []
        for k in 0..<n {
            let out = k % 2 == 0
            let ts = base.addingTimeInterval(Double(k) * 60)
            let s = out ? me : peer, r = out ? peer : me
            list.append(SecureMessage(id: "\(prefix)-\(k)", senderAddress: s, recipientAddress: r,
                                      encryptedContent: EncryptedMessage(ciphertext: "", signature: "", senderPublicKey: s, timestamp: ts),
                                      timestamp: ts, isRead: true, deliveryStatus: out ? .delivered : .delivered,
                                      senderPublicKey: s, recipientPublicKey: r, plaintextContent: "\(prefix) message \(k)"))
        }
        let added = mm.importMessages(list)
        OshiLog.messaging.infoPublic("[devsync] seeded \(added) messages (\(prefix))")
        // `-DevSyncSeedGroup`: one test group (me + the test peer) with 3 messages from this device.
        if args.contains("-DevSyncSeedGroup") {
            let gm = MessageGroupManager.shared
            let gid = UUID(uuidString: "5E2A9C1B-7D3F-4B8A-9E6C-0F1D2A3B4C5D")!
            if !gm.groups.contains(where: { $0.id == gid }) {
                var g = MessageGroup(id: gid, name: "DevSync Test Group", type: .collaborative, adminPublicKey: me,
                                     members: [GroupMember(publicKey: me, alias: nil, joinedAt: base, isAdmin: true),
                                               GroupMember(publicKey: peer, alias: "Bob", joinedAt: base, isAdmin: false)],
                                     createdAt: base, lastActivity: base)
                g.stateVersion = 2
                gm.groups.append(g)
                gm.saveGroups()
            }
            var msgs = gm.groupMessages[gid] ?? []
            for k in 0..<3 {
                let mid = UUID(uuidString: String(format: "7E57%04X-0000-4000-8000-%012X", k, prefix.utf8.reduce(0) { ($0 &* 31 &+ Int($1)) & 0xFFFFFF }))!
                if msgs.contains(where: { $0.id == mid }) { continue }
                msgs.append(GroupMessage(id: mid, groupId: gid, senderPublicKey: me, encryptedContent: Data("group \(prefix) \(k)".utf8),
                                         timestamp: base.addingTimeInterval(Double(k) * 30), isRead: true,
                                         plaintextContent: "group \(prefix) \(k)", deliveryStatus: .sent))
            }
            gm.groupMessages[gid] = msgs
            gm.saveGroupMessagesPublic()
        }
    }

    /// Writes Documents/devsync_state.txt: counts the two-simulator test compares.
    @MainActor static func dumpState() {
        guard ProcessInfo.processInfo.arguments.contains("-DevSyncDump"), let mm = MessageManager.shared else { return }
        let me = DevSyncIOSStore.myKey()
        let peer = "3b6TE6T6dfqOHzhG5WVPCdhuzDHQXrKCFxVoTqnlPUE="
        let conv = mm.messages.compactMap { DevSyncIOSStore.mapDirect($0, me: me, reactions: nil) }.filter { $0.conversation == "direct:" + peer }
        let digest = SyncCodec.digest(conv.map { ($0.id, SyncCodec.rev($0)) })
        let mgr = DevSyncManager.shared
        let gm = MessageGroupManager.shared
        let gid = UUID(uuidString: "5E2A9C1B-7D3F-4B8A-9E6C-0F1D2A3B4C5D")!
        let g = gm.groups.first { $0.id == gid }
        let gmsgs = (gm.groupMessages[gid] ?? []).filter { !$0.isSystemMessage }
        let photo = UserDefaults.standard.data(forKey: "profileImageData").map { DSCrypto.hex(DSCrypto.sha256($0)) } ?? "none"
        let lines = ["deviceId \(mgr.thisDeviceId)", "running \(mgr.running)",
                     "profile \(UserDefaults.standard.string(forKey: "userName") ?? "-") photo=\(photo) live=\(MessageManager.sharedIdentityManager?.profileImageData.map { DSCrypto.hex(DSCrypto.sha256($0)) } ?? "none")",
                     "group \(g.map { "\($0.name) members=\($0.members.count) admins=\($0.members.filter(\.isAdmin).count) v=\($0.stateVersion ?? 0)" } ?? "none") msgs=\(gmsgs.count)",
                     "media \(mm.messages.filter { $0.mediaType != nil }.map { "\($0.id):\($0.mediaAttachment?.count ?? -1)" })", "linked \(mgr.devices.map { "\($0.id.prefix(8)):\($0.online ? ($0.transport ?? "?") : "off")" })",
                     "pending \(mgr.pending.count)", "testConv.count \(conv.count)", "testConv.digest \(digest)",
                     "ids \(conv.map(\.id).sorted().joined(separator: ","))"]
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try? (lines.joined(separator: "\n") + "\n").write(to: docs.appendingPathComponent("devsync_state.txt"), atomically: true, encoding: .utf8)
    }
}
#endif
// ===DEVSYNC-IOS-END===
