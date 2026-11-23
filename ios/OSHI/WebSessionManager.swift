//
//  WebSessionManager.swift
//  OSHI
//
//  Manages web client sessions like WhatsApp Web
//

import Foundation
import UIKit
import SwiftUI
import AVFoundation
import Combine
import CryptoKit

// MARK: - Web Session Manager

class WebSessionManager: ObservableObject {
    static let shared = WebSessionManager()
    
    @Published var activeSessions: [WebSession] = []

    /// What the last session check amounted to — see [LinkedDeviceWatch].
    ///
    /// `.linked` is the case a user must be shown without opening any screen: a device was
    /// added to this account since the previous check. It is published rather than fired as
    /// a one-shot so a view that appears after the fact still sees it.
    @Published var lastLinkCheck: LinkedDeviceWatch.SeedOutcome = .unchanged
    @Published var isLoading = false
    @Published var lastRefresh: Date?
    @Published var isConnected = false
    
    var identityManager: IdentityManager?
    var messageManager: MessageManager?
    var meshManager: MeshNetworkManager?
    
    private let vpsBaseURL: String = WebSessionManager.debugOverride("OSHI_WEB_BASE_URL") ?? "https://oshi-messenger.com"
    private let wsURL: String = WebSessionManager.debugOverride("OSHI_WEB_WS_URL") ?? "wss://oshi-messenger.com/ws"

    /// DEBUG-only endpoint override (simulator runs against the local test harness in
    /// `ServerPatches/websession_e2e/test`). Always nil in Release.
    static func debugOverride(_ name: String) -> String? {
        #if DEBUG
        if let v = ProcessInfo.processInfo.environment[name], !v.isEmpty { return v }
        #endif
        return nil
    }

    // __WEBSESSION_E2E_2026_09_23__ v2 (end-to-end) state. One channel per paired browser
    // (`sid`), keys in the Keychain. The socket is declared v2 (`wsv:2`) as soon as this
    // phone holds one v2 pairing; a v2 socket never sends v1 plaintext (the server drops it).
    private let e2eLock = NSLock()
    private var e2eChannels: [String: WebE2E.Channel] = [:]
    private var socketIsV2 = false
    /// `ep:seq` of media chunks sent and not yet reported by `e2e_status`, per sid.
    private var e2eMediaInFlight: [String: Set<String>] = [:]
    /// Browsers reported offline (`e2e_status queued`): stop streaming media to them.
    private var e2eStopped: Set<String> = []
    /// Files the browser is uploading to us in `send_file_chunk` frames.
    private var e2eUploads: [String: WebUpload] = [:]
    private var v1SessionsRevokedAfterV2 = false
    /// SAS of the last v2 pairing, shown on the scanner's result screen.
    @Published var lastPairingSAS: String?

    struct WebUpload {
        let recipient: String
        let kind: String
        let fileName: String
        let mime: String
        let size: Int
        var data: Data
    }
    
    private var webSocket: URLSessionWebSocketTask?
    private var webSocketSession: URLSession?
    private var reconnectTimer: Timer?
    private var pingTimer: Timer?
    private var backgroundSyncCompletion: ((Bool) -> Void)?
    private var backgroundSyncTimer: Timer?
    private var isBackgroundSync = false

    struct WebSession: Identifiable, Codable, Equatable {
        let sessionId: String
        let deviceName: String
        let userAgent: String
        let createdAt: String
        let lastActiveAt: String
        var isActive: Bool
        /// __WEBSESSION_E2E_2026_09_23__ 2 = end-to-end session, 1/nil = legacy plaintext
        /// relay. Optional so an older server response still decodes.
        var wsv: Int? = nil
        /// Whether the browser tab is connected right now (server ≥ 2026-09-23).
        var online: Bool? = nil

        var id: String { sessionId }
        var isEndToEnd: Bool { wsv == 2 }
        /// The 6-digit code the browser shows for this session (v2 only, local keys).
        var sas: String? { isEndToEnd ? WebE2E.loadKeys(sid: sessionId)?.sas : nil }
        
        var browserName: String {
            if userAgent.contains("Chrome") && !userAgent.contains("Edge") { return "Chrome" }
            if userAgent.contains("Safari") && !userAgent.contains("Chrome") { return "Safari" }
            if userAgent.contains("Firefox") { return "Firefox" }
            if userAgent.contains("Edge") { return "Edge" }
            return "Web Browser"
        }
        
        var platformName: String {
            if userAgent.contains("Windows") { return "Windows" }
            if userAgent.contains("Mac") { return "macOS" }
            if userAgent.contains("Linux") { return "Linux" }
            return "Unknown"
        }
        
        var browserIcon: String {
            switch browserName {
            case "Chrome": return "globe"
            case "Safari": return "safari"
            case "Firefox": return "flame"
            case "Edge": return "square.stack.3d.up"
            default: return "desktopcomputer"
            }
        }
        
        var createdDate: Date? { ISO8601DateFormatter().date(from: createdAt) }
        var lastActiveDate: Date? { ISO8601DateFormatter().date(from: lastActiveAt) }
        static func == (lhs: WebSession, rhs: WebSession) -> Bool { lhs.sessionId == rhs.sessionId }
    }
    
    private init() {
        let config = URLSessionConfiguration.default
        config.waitsForConnectivity = true
        webSocketSession = URLSession(configuration: config)
        // __WEBSESSION_E2E_2026_09_23__ restore the v2 channels of browsers paired earlier.
        for sid in WebE2E.storedSids() {
            if let keys = WebE2E.loadKeys(sid: sid) { e2eChannels[sid] = WebE2E.Channel(sid: sid, keys: keys) }
        }

        Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            Task { @MainActor in await self?.fetchActiveSessions() }
        }

        // 🆕 Listen for group / profile / wallpaper change notifications and fan out to web sessions.
        //
        // 🔴 WATCHDOG (0x8BADF00D): these are posted from background threads (group decode,
        // call signalling, sync). With `queue: .main`, NSNotificationCenter parks the POSTING
        // thread on `-[NSOperation waitUntilFinished]` until the main runloop drains — which
        // deadlocks outright whenever the main thread is itself waiting on that thread's serial
        // queue or lock. addMainThreadObserver hops to main without blocking the poster.
        // See NotificationCenter+MainThread in NotificationManager.swift.
        let nc = NotificationCenter.default
        nc.addMainThreadObserver(forName: NSNotification.Name("OSHIGroupCreated")) { [weak self] _ in
            self?.syncGroupsToWeb()
        }
        nc.addMainThreadObserver(forName: NSNotification.Name("OSHIGroupUpdated")) { [weak self] note in
            self?.syncGroupsToWeb()
            if let g = note.userInfo?["group"] as? MessageGroup {
                self?.pushGroupPicture(group: g)
                self?.pushGroupWallpaper(group: g)
            }
        }
        nc.addMainThreadObserver(forName: NSNotification.Name("OSHIGroupMessageReceived")) { [weak self] note in
            if let m = note.userInfo?["message"] as? GroupMessage {
                self?.notifyNewGroupMessage(m)
            }
        }
        nc.addMainThreadObserver(forName: NSNotification.Name("OSHIGroupMessageSent")) { [weak self] note in
            if let m = note.userInfo?["message"] as? GroupMessage {
                self?.notifyNewGroupMessage(m)
            }
        }
        nc.addMainThreadObserver(forName: NSNotification.Name("OSHIProfilePictureChanged")) { [weak self] _ in
            self?.pushProfilePicture()
        }
        nc.addMainThreadObserver(forName: NSNotification.Name("OSHIWallpaperChanged")) { [weak self] note in
            if let key = note.userInfo?["conversationKey"] as? String {
                self?.pushWallpaper(conversationKey: key)
            }
        }
        nc.addMainThreadObserver(forName: NSNotification.Name("OSHICallStateChanged")) { [weak self] note in
            guard let info = note.userInfo else { return }
            self?.pushCallState(
                callId: info["callId"] as? String ?? "",
                state: info["state"] as? String ?? "unknown",
                peerPublicKey: info["peerPublicKey"] as? String ?? "",
                peerName: info["peerName"] as? String,
                isVideo: info["isVideo"] as? Bool ?? false,
                direction: info["direction"] as? String ?? "incoming",
                errorMessage: info["error"] as? String
            )
        }
    }
    
    func configure(identityManager: IdentityManager, messageManager: MessageManager, meshManager: MeshNetworkManager) {
        self.identityManager = identityManager
        self.messageManager = messageManager
        self.meshManager = meshManager
        // Once identity is wired up, immediately fetch the linked web sessions and open the
        // relay WebSocket if any exist. Without this the WebSocket only opened after a fresh QR
        // scan or a background push wake, so notifyNewMessage(_:) silently no-op'd in normal use
        // and the web client never received messages typed on the phone.
        Task { @MainActor in await fetchActiveSessions() }
    }
    
    private func getPublicKey() -> String? {
        if let pk = identityManager?.publicKey, !pk.isEmpty { return pk }
        if let pk = MessageManager.sharedIdentityManager?.publicKey, !pk.isEmpty { return pk }
        return nil
    }
    
    func validateQRCode(_ qrData: String) async -> (success: Bool, message: String) {
        // __WEBSESSION_E2E_2026_09_23__ A v2 page puts its X25519 key and a secret in the QR.
        // A QR without `v=2` comes from an old page (or a browser without WebCrypto): it is
        // still paired the v1 way while the server accepts v1 (owner decision, 2026-09-23);
        // once ops turns v1 off the server answers 426 and we ask for a reload instead.
        guard let qr = WebE2E.parseQR(qrData) else {
            return (false, "Invalid QR code format")
        }
        if qr.version == 2 { return await validateQRTokenV2(qr) }
        // __LEAK_FIX_2026_09_25__ v1 (old page) pairing relays conversations, history and
        // media IN CLEAR through the server (cached 15 min). Refuse it outright — never
        // fall back — and send the user to the current page, which shows a v2 QR.
        OshiLog.web.info("🔒 WebSession: refused a v1 (plaintext) QR — asking for a reload")
        return (false, "settings.webSessions.outdatedPage".localized)
    }

    /// v2 pairing: fresh ephemeral X25519 key, keys derived with the QR secret, confirm tag
    /// sent to the browser (through the server, which cannot forge it without `s`).
    func validateQRTokenV2(_ qr: WebE2E.QR) async -> (success: Bool, message: String) {
        let identityData: (publicKey: String, deviceName: String)? = await MainActor.run {
            guard let pk = getPublicKey(), !pk.isEmpty else { return nil }
            return (pk, UIDevice.current.name)
        }
        guard let data = identityData else { return (false, "Identity not initialized") }
        let pairing: WebE2E.Pairing
        do { pairing = try WebE2E.pair(qr) } catch { return (false, "Invalid QR code format") }
        do {
            let url = URL(string: "\(vpsBaseURL)/api/web/qr-validate")!
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.timeoutInterval = 15
            let payload: [String: Any] = [
                "qrToken": qr.token, "publicKey": data.publicKey, "deviceName": data.deviceName,
                "wsv": 2,
                "phoneKey": pairing.phoneKey.base64EncodedString(),
                "confirm": pairing.confirm.base64EncodedString(),
            ]
            let bodyData = try JSONSerialization.data(withJSONObject: payload)
            request.httpBody = bodyData
            signRequest(&request, path: "/api/web/qr-validate", body: bodyData)
            let (responseData, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else { return (false, "Invalid response") }
            if http.statusCode == 200,
               let result = try? JSONDecoder().decode(QRValidateResponse.self, from: responseData),
               result.success, let sid = result.sessionId, !sid.isEmpty {
                // The server must confirm v2; a response without it is an old server that
                // would relay our frames nowhere — treat as failure rather than pair in clear.
                guard result.wsv == 2 else {
                    _ = await revokeSession(sid)
                    return (false, "settings.webSessions.reloadPage".localized)
                }
                WebE2E.storeKeys(pairing.derived.keys, sid: sid)
                e2eLock.lock(); e2eChannels[sid] = WebE2E.Channel(sid: sid, keys: pairing.derived.keys); e2eLock.unlock()
                await MainActor.run { self.lastPairingSAS = pairing.sas }
                await fetchActiveSessions()
                // A socket opened earlier as v1 must be re-opened declaring wsv:2.
                if !socketIsV2 { disconnectWebSocket() }
                connectWebSocket()
                let code = String(pairing.sas.prefix(3)) + " " + String(pairing.sas.suffix(3))
                return (true, String(format: "settings.webSessions.e2eLinked".localized, code))
            }
            if let errorResult = try? JSONDecoder().decode(ErrorResponse.self, from: responseData) {
                return (false, errorResult.error)
            }
            return (false, "QR code expired or invalid")
        } catch { return (false, "Network error") }
    }

    func validateQRToken(_ token: String) async -> (success: Bool, message: String) {
        // __LEAK_FIX_2026_09_25__ v1 pairing is refused (plaintext relay); kept only so any
        // caller still compiles. Nothing is sent to the server.
        return (false, "settings.webSessions.outdatedPage".localized)
    }

    /// Attach the Ed25519 auth headers that prove control of this account's key.
    ///
    /// The server's pairing gate (on `qr-validate`) and owner gate (on the session routes)
    /// verify these; without them a caller can bind a device to, or read the linked devices
    /// of, any account it merely names — the covert companion-device attack. Canonical scheme
    /// matches `IdentityManager.signHTTPRequest`: `METHOD\nPATH\nSHA256(body)\nTIMESTAMP`, and
    /// `path` MUST be the path WITHOUT the query string (that is what the server signs).
    private func signRequest(_ request: inout URLRequest, path: String, body: Data) {
        let mgr = identityManager ?? MessageManager.sharedIdentityManager
        guard let sig = mgr?.signHTTPRequest(method: request.httpMethod ?? "GET", path: path, bodyData: body) else { return }
        request.setValue(sig.signature, forHTTPHeaderField: "x-oshi-signature")
        request.setValue(sig.timestamp, forHTTPHeaderField: "x-oshi-timestamp")
        request.setValue(sig.signingPublicKey, forHTTPHeaderField: "x-oshi-signing-pubkey")
    }

    @MainActor
    func fetchActiveSessions() async {
        guard let publicKey = getPublicKey(), !publicKey.isEmpty else { return }
        isLoading = true
        defer { isLoading = false }

        do {
            let encodedKey = publicKey.addingPercentEncoding(withAllowedCharacters: CharacterSet.urlQueryAllowed) ?? publicKey
            let url = URL(string: "\(vpsBaseURL)/api/web/sessions?publicKey=\(encodedKey)")!
            var request = URLRequest(url: url)
            request.httpMethod = "GET"
            signRequest(&request, path: "/api/web/sessions", body: Data())
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else { return }
            let result = try JSONDecoder().decode(SessionsResponse.self, from: data)
            activeSessions = result.sessions
            // __WEBSESSION_E2E_2026_09_23__ A session the server no longer lists (revoked,
            // logged out or expired while we were away) takes its keys with it.
            let listed = Set(result.sessions.map { $0.sessionId })
            for sid in WebE2E.storedSids() where !listed.contains(sid) { dropE2ESession(sid) }
            // __LEAK_FIX_2026_09_25__ v1 (plaintext relay) sessions are no longer served:
            // revoke them so the server stops holding a session that expects plaintext.
            // A session counts as v1 only when the server does not say wsv 2 AND this phone
            // holds no v2 keys for it (an older server response lacks `wsv`).
            let v1Sessions = result.sessions.filter { !$0.isEndToEnd && WebE2E.loadKeys(sid: $0.sessionId) == nil }
            if !v1Sessions.isEmpty {
                for s in v1Sessions {
                    OshiLog.web.info("🔒 WebSession: revoking a v1 (plaintext) web session")
                    _ = await revokeSessionOnly(s.sessionId)
                }
                activeSessions.removeAll { s in v1Sessions.contains(where: { $0.sessionId == s.sessionId }) }
            }
            // A device must not be able to join this account silently. The list has always
            // been here; what was missing is being TOLD, which is the whole of the
            // linked-device attack (see LinkedDeviceWatch for the documents behind it).
            let ids = result.sessions.map { $0.sessionId }
            lastLinkCheck = LinkedDeviceWatch.classify(fetched: ids, known: LinkedDeviceWatch.loadKnown())
            LinkedDeviceWatch.saveKnown(ids)
            if case .linked(let new) = lastLinkCheck {
                OshiLog.web.info("🔔 WebSession: \(new.count) device(s) linked since the last check")
                // Detection alone is not a defence — the user must actually be TOLD, or the
                // covert companion-device method works unopposed. Surface it as a loud,
                // time-sensitive local notification (fires even when this fetch ran from a
                // background pairing-push wake). See NotificationManager.sendLinkedDeviceAlert.
                NotificationManager.shared.sendLinkedDeviceAlert(count: new.count)
            }
            lastRefresh = Date()
            // Keep the relay WebSocket open whenever the user has at least one linked web
            // session, so notifyNewMessage(_:) can actually fan out to the browser.
            if !activeSessions.isEmpty && !isConnected {
                connectWebSocket()
            } else if activeSessions.isEmpty && isConnected {
                disconnectWebSocket()
            }
        } catch { OshiLog.web.info("❌ Fetch sessions error: \(error)") }
    }
    
    /// __LEAK_FIX_2026_09_25__ revoke without re-fetching (called from inside the fetch).
    private func revokeSessionOnly(_ sessionId: String) async -> Bool {
        guard let publicKey = getPublicKey() else { return false }
        do {
            var request = URLRequest(url: URL(string: "\(vpsBaseURL)/api/web/sessions/revoke")!)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            let bodyData = try JSONSerialization.data(withJSONObject: ["sessionId": sessionId, "publicKey": publicKey])
            request.httpBody = bodyData
            signRequest(&request, path: "/api/web/sessions/revoke", body: bodyData)
            let (_, response) = try await URLSession.shared.data(for: request)
            return (response as? HTTPURLResponse)?.statusCode == 200
        } catch { return false }
    }

    func revokeSession(_ sessionId: String) async -> Bool {
        guard let publicKey = getPublicKey() else { return false }
        do {
            let url = URL(string: "\(vpsBaseURL)/api/web/sessions/revoke")!
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            let bodyData = try JSONSerialization.data(withJSONObject: ["sessionId": sessionId, "publicKey": publicKey])
            request.httpBody = bodyData
            signRequest(&request, path: "/api/web/sessions/revoke", body: bodyData)
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else { return false }
            let result = try JSONDecoder().decode(SuccessResponse.self, from: data)
            if result.success { await fetchActiveSessions() }
            return result.success
        } catch { return false }
    }
    
    func revokeAllSessions() async -> Int {
        guard let publicKey = getPublicKey() else { return 0 }
        do {
            let url = URL(string: "\(vpsBaseURL)/api/web/sessions/revoke-all")!
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            let bodyData = try JSONSerialization.data(withJSONObject: ["publicKey": publicKey])
            request.httpBody = bodyData
            signRequest(&request, path: "/api/web/sessions/revoke-all", body: bodyData)
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else { return 0 }
            let result = try JSONDecoder().decode(RevokeAllResponse.self, from: data)
            if result.success { await fetchActiveSessions(); disconnectWebSocket() }
            return result.revokedCount
        } catch { return 0 }
    }
    
    func connectWebSocket() {
        guard let publicKey = getPublicKey(), webSocket == nil, let url = URL(string: wsURL) else { return }
        // __LEAK_FIX_2026_09_25__ Only v2 (end-to-end) browsers are served. Without a v2
        // pairing the socket could only be a v1 plaintext relay — do not open it at all.
        guard hasAnyE2EChannel else {
            OshiLog.web.info("🔒 WebSession: no end-to-end pairing — relay socket not opened")
            return
        }
        webSocket = webSocketSession?.webSocketTask(with: url)
        webSocket?.resume()
        // The auth proof binds this socket, this account identity, a fresh nonce and the
        // exact endpoint. Legacy servers ignore the additional fields during rollout.
        let timestamp = String(Int(Date().timeIntervalSince1970 * 1000))
        let nonce = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        let canonical = "OSHI-MOBILE-AUTH-V1\\n1\\n\(publicKey)\\n\(timestamp)\\n\(nonce)\\n/ws"
        let manager = identityManager ?? MessageManager.sharedIdentityManager
        guard let signingKey = manager?.signingPrivateKey,
              let canonicalData = canonical.data(using: .utf8),
              let signature = try? signingKey.signature(for: canonicalData) else {
            webSocket?.cancel(with: .goingAway, reason: nil); webSocket = nil
            return
        }
        var authMessage: [String: Any] = [
            "type": "mobile_auth", "protocolVersion": 1, "publicKey": publicKey,
            "timestamp": timestamp, "nonce": nonce, "path": "/ws",
            "signingPublicKey": signingKey.publicKey.rawRepresentation.base64EncodedString(),
            "signature": signature.base64EncodedString()
        ]
        // __WEBSESSION_E2E_2026_09_23__ Declare v2 as soon as we hold one v2 pairing. The
        // canonical string above is unchanged; a fresh `ep` per connection (§3).
        e2eLock.lock()
        socketIsV2 = !e2eChannels.isEmpty
        for ch in e2eChannels.values { ch.newEpoch() }
        e2eMediaInFlight.removeAll(); e2eStopped.removeAll()
        e2eLock.unlock()
        if socketIsV2 { authMessage["wsv"] = 2 }
        if let data = try? JSONSerialization.data(withJSONObject: authMessage), let string = String(data: data, encoding: .utf8) {
            webSocket?.send(.string(string)) { _ in }
        }
        receiveMessage()
        startPingTimer()
        DispatchQueue.main.async { self.isConnected = true }
    }

    /// Connect WebSocket specifically for a background push-wake sync.
    func connectForBackgroundSync(completion: @escaping (Bool) -> Void) {
        if webSocket != nil && isConnected {
            OshiLog.web.info("🌐 Already connected - performing sync directly")
            Task { @MainActor in self.performInitialSync() }
            completion(true)
            return
        }

        // __LEAK_FIX_2026_09_25__ nothing to sync without a v2 pairing (v1 is refused).
        guard hasAnyE2EChannel else { completion(false); return }

        isBackgroundSync = true
        backgroundSyncCompletion = completion

        backgroundSyncTimer?.invalidate()
        backgroundSyncTimer = Timer.scheduledTimer(withTimeInterval: 25.0, repeats: false) { [weak self] _ in
            OshiLog.web.info("⏰ Background sync timeout - completing")
            self?.completeBackgroundSync(success: false)
        }

        connectWebSocket()
    }

    private func completeBackgroundSync(success: Bool) {
        guard isBackgroundSync else { return }
        isBackgroundSync = false
        backgroundSyncTimer?.invalidate()
        backgroundSyncTimer = nil

        let completion = backgroundSyncCompletion
        backgroundSyncCompletion = nil
        completion?(success)
    }

    func disconnectWebSocket() {
        pingTimer?.invalidate(); pingTimer = nil
        reconnectTimer?.invalidate(); reconnectTimer = nil
        webSocket?.cancel(with: .goingAway, reason: nil); webSocket = nil
        DispatchQueue.main.async { self.isConnected = false }
    }
    
    private func receiveMessage() {
        webSocket?.receive { [weak self] result in
            switch result {
            case .success(let message):
                if case .string(let text) = message { self?.handleWebSocketMessage(text) }
                self?.receiveMessage()
            case .failure: self?.handleWebSocketDisconnect()
            }
        }
    }
    
    private func handleWebSocketMessage(_ text: String) {
        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String else { return }
        handleIncoming(json, type: type, e2eSid: nil)
    }

    /// Commands that carry or request content. On a v2 socket they are honoured ONLY from
    /// inside an authenticated channel (`e2eSid != nil`); a cleartext copy is ignored.
    private static let contentCommandTypes: Set<String> = [
        "request_conversations", "request_messages", "message_from_web", "web_message",
        "request_groups", "request_group_messages", "web_create_group", "web_add_group_member",
        "web_call_initiate", "web_call_accept", "web_call_reject", "request_media",
        "send_message", "send_file_chunk", "media_get",
    ]

    /// One dispatcher for v1 frames and for the inner messages of v2 frames (§4.3: "dispatch
    /// `inner.t` through the existing v1 switch"). `e2eSid` is the browser that sent it, and
    /// every answer goes back to that browser only.
    private func handleIncoming(_ json: [String: Any], type: String, e2eSid: String?) {
        // __LEAK_FIX_2026_09_25__ content commands are honoured ONLY inside a v2 channel,
        // whatever the socket version (v1 = plaintext relay, refused).
        if e2eSid == nil, Self.contentCommandTypes.contains(type) {
            OshiLog.web.info("🔒 WebSession: cleartext '\(type)' ignored on an end-to-end socket")
            return
        }
        switch type {
        // __WEBSESSION_E2E_2026_09_23__ ------------------------------------------------
        case "e2e":
            handleE2EFrame(json)

        case "e2e_peers":
            // The v2 browsers connected right now: each gets its own snapshot.
            for sid in (json["sids"] as? [String]) ?? [] where hasChannel(sid) {
                Task { @MainActor in self.performInitialSync(target: sid) }
            }

        case "e2e_peer":
            if let sid = json["sid"] as? String, hasChannel(sid) {
                if (json["online"] as? Bool) == true {
                    e2eLock.lock(); e2eStopped.remove(sid); e2eLock.unlock()
                    Task { @MainActor in self.performInitialSync(target: sid) }
                } else {
                    e2eLock.lock(); e2eStopped.insert(sid); e2eMediaInFlight[sid] = nil; e2eLock.unlock()
                }
            }

        case "e2e_session_ended":
            if let sid = json["sid"] as? String {
                OshiLog.web.info("🔒 WebSession: v2 session ended (\(json["reason"] as? String ?? "?"))")
                dropE2ESession(sid)
                Task { @MainActor in await self.fetchActiveSessions() }
            }

        case "e2e_status":
            handleE2EStatus(json)

        case "send_message":
            // v2 equivalent of `message_from_web`, answered with `send_result`.
            let content = json["content"] as? String ?? ""
            let recipient = json["recipientPublicKey"] as? String ?? ""
            let clientMsgId = json["clientMsgId"] as? String ?? ""
            Task { @MainActor in
                var ok = false
                if !content.isEmpty, !recipient.isEmpty { ok = self.handleWebMessage(content: content, recipient: recipient) }
                if let sid = e2eSid {
                    self.sendToWebSocket(["type": "send_result", "clientMsgId": clientMsgId, "status": ok ? "sent" : "failed"], target: sid)
                }
            }

        case "send_file_chunk":
            if let sid = e2eSid { handleFileChunk(json, sid: sid) }

        case "media_get":
            if let sid = e2eSid {
                handleMediaGet(messageId: json["messageId"] as? String ?? "",
                               scope: json["scope"] as? String ?? "private", sid: sid)
            }

        case "mobile_auth_replaced":
            OshiLog.web.info("🔁 WebSession: socket replaced by a newer one")

        // ------------------------------------------------------------------------------
        case "mobile_auth_success":
            OshiLog.web.info("✅ WebSession: Mobile auth successful, syncing data...")
            // A v2 socket syncs per browser on `e2e_peers`/`e2e_peer` (never a broadcast);
            // a v1 socket keeps today's behaviour.
            if (json["wsv"] as? Int) == 2 {
                revokeV1SessionsAfterV2Connect()
            } else {
                if socketIsV2 {
                    // The server did not accept our v2 declaration: never fall back to
                    // plaintext for a v2 pairing.
                    OshiLog.web.info("⚠️ WebSession: server answered v1 to a v2 socket — not syncing")
                } else {
                    // __LEAK_FIX_2026_09_25__ a v1 socket never gets a plaintext sync.
                    OshiLog.web.info("🔒 WebSession: v1 socket — plaintext sync refused")
                }
            }

            // If this is a background sync wake, complete after delay for messages to send
            if isBackgroundSync {
                DispatchQueue.main.asyncAfter(deadline: .now() + 5.0) { [weak self] in
                    self?.completeBackgroundSync(success: true)
                }
            }

        case "request_conversations":
            syncConversationsToWeb(target: e2eSid)

        case "request_messages":
            // Handle both "contact" and "contactPublicKey" for compatibility
            if let contact = json["contactPublicKey"] as? String ?? json["contact"] as? String {
                syncMessagesToWeb(for: contact, target: e2eSid)
            }

        case "message_from_web":
            // Handle message sent from web client
            if let payload = json["payload"] as? [String: Any],
               let content = payload["content"] as? String,
               let recipient = payload["recipientPublicKey"] as? String {
                handleWebMessage(content: content, recipient: recipient)
            }

        case "web_message":
            // Legacy format support
            if let content = json["content"] as? String, let recipient = json["recipientPublicKey"] as? String {
                handleWebMessage(content: content, recipient: recipient)
            }

        case "session_revoked":
            Task { @MainActor in await self.fetchActiveSessions() }

        case "pending_delivered":
            if let count = json["count"] as? Int {
                OshiLog.web.info("📤 WebSession: Server delivered \(count) pending messages")
            }

        case "mobile_status":
            // Server informing us of status (no-op on mobile, this is for web clients)
            break

        // 🆕 Group requests from web
        case "request_groups":
            syncGroupsToWeb(target: e2eSid)
        case "request_group_messages":
            if let gidStr = json["groupId"] as? String, let gid = UUID(uuidString: gidStr) {
                syncGroupMessagesToWeb(groupId: gid, target: e2eSid)
            }

        // 🆕 Web wants to create a new group
        case "web_create_group":
            let name = (json["name"] as? String) ?? ""
            // Use `groupType` (envelope `type` is already taken by "web_create_group")
            let typeRaw = (json["groupType"] as? String) ?? "collaborative"
            let memberKeys = (json["members"] as? [String]) ?? []
            guard !name.isEmpty else {
                OshiLog.web.info("⚠️ WebSession: web_create_group missing name")
                break
            }
            Task { @MainActor [weak self] in
                guard let self = self else { return }
                guard let myKey = self.getPublicKey(), !myKey.isEmpty else {
                    OshiLog.web.info("⚠️ WebSession: web_create_group — no identity")
                    return
                }
                let gType = GroupType(rawValue: typeRaw) ?? .collaborative
                let group = MessageGroupManager.shared.createGroup(
                    name: name,
                    type: gType,
                    adminPublicKey: myKey
                )
                // Add the additional members the web picked. createGroup only seeds
                // the admin; addMember handles dedupe and the system message.
                for pk in memberKeys where pk != myKey && !pk.isEmpty {
                    MessageGroupManager.shared.addMember(to: group.id, publicKey: pk, alias: nil)
                }
                // Echo the new group list back to the web immediately
                self.syncGroupsToWeb()
            }

        // 🆕 Web wants to add a member to an existing group
        case "web_add_group_member":
            let gidStr = (json["groupId"] as? String) ?? ""
            let memberKey = (json["memberPublicKey"] as? String) ?? ""
            let alias = json["alias"] as? String
            guard let gid = UUID(uuidString: gidStr), !memberKey.isEmpty else {
                OshiLog.web.info("⚠️ WebSession: web_add_group_member bad payload")
                break
            }
            Task { @MainActor [weak self] in
                guard let self = self else { return }
                MessageGroupManager.shared.addMember(to: gid, publicKey: memberKey, alias: alias)
                self.syncGroupsToWeb()
            }

        // 🆕 Call control from web
        case "web_call_initiate":
            let payload: [String: Any] = (json["payload"] as? [String: Any]) ?? json
            if let recipient = (payload["peerPublicKey"] as? String) ?? (json["peerPublicKey"] as? String) {
                let isVideo = (payload["isVideo"] as? Bool) ?? (json["isVideo"] as? Bool) ?? false
                let peerName = (payload["peerName"] as? String) ?? (json["peerName"] as? String) ?? recipient
                Task { @MainActor in
                    do {
                        try await VoiceCallManager.shared.startCall(to: recipient, peerName: peerName, isVideoCall: isVideo)
                        self.pushCallState(callId: recipient, state: "connecting",
                                           peerPublicKey: recipient, peerName: peerName,
                                           isVideo: isVideo, direction: "outgoing")
                    } catch {
                        self.pushCallState(callId: recipient, state: "failed",
                                           peerPublicKey: recipient, peerName: peerName,
                                           isVideo: isVideo, direction: "outgoing",
                                           errorMessage: error.localizedDescription)
                    }
                }
            }

        case "web_call_accept":
            Task { @MainActor in
                // 🔧 CRITICAL GUARD: Only proceed if there's a REAL incoming call ringing
                // on this device. Without this guard, a stale/replayed `web_call_accept`
                // can fire while we're the CALLER waiting for the callee to answer
                // (state=.ringing but isOutgoingCall=true, incomingCall=nil), or while
                // we're idle. Letting `acceptCall()` run in those cases either throws
                // (caller side: no incomingCall) or — worse — transitions a foreign
                // device's UI into .inCall with no session key, which presents as Bug A
                // (caller never sees acceptance) and Bug B (callee in-call but no audio).
                let vcm = VoiceCallManager.shared
                guard vcm.callState == .ringing,
                      vcm.incomingCall != nil,
                      vcm.isOutgoingCall == false else {
                    OshiLog.web.info("⚠️ WebSession: Ignoring web_call_accept — no live incoming call (state=\(vcm.callState), hasIncoming=\(vcm.incomingCall != nil), isOutgoing=\(vcm.isOutgoingCall))")
                    return
                }
                do {
                    try await vcm.acceptCall()
                    self.pushCallState(callId: "", state: "connected",
                                       peerPublicKey: "", isVideo: false, direction: "incoming")
                } catch {
                    self.pushCallState(callId: "", state: "failed",
                                       peerPublicKey: "", isVideo: false, direction: "incoming",
                                       errorMessage: error.localizedDescription)
                }
            }

        case "web_call_reject":
            DispatchQueue.main.async {
                // 🔧 CRITICAL GUARD: Only end an actual ringing/in-progress call.
                // Without this, a stale `web_call_reject` from a leftover web session
                // can hang up a healthy call when the user didn't ask for it.
                let vcm = VoiceCallManager.shared
                guard vcm.callState == .ringing || vcm.callState == .connecting || vcm.callState == .inCall else {
                    OshiLog.web.info("⚠️ WebSession: Ignoring web_call_reject — no live call (state=\(vcm.callState))")
                    return
                }
                vcm.endCall(reason: .hungUp)
                self.pushCallState(callId: "", state: "ended",
                                   peerPublicKey: "", isVideo: false, direction: "incoming")
            }

        // 🆕 Server is asking us to retransmit a >5 MB media payload that was sent
        // out-of-band. Look up the message, base64-encode the full attachment, send back.
        case "request_media":
            handleRequestMedia(messageId: json["messageId"] as? String ?? "",
                               scope: json["scope"] as? String ?? "private")

        default:
            OshiLog.web.info("📨 WebSession: Unknown message type: \(type)")
        }
    }

    /// Handles a `request_media` from the relay. Resolves the message either in the 1-1
    /// store (`scope = "private"`) or the group store (`scope = "group"`), encodes the
    /// attachment, and emits `media_response` with the same `messageId`.
    private func handleRequestMedia(messageId: String, scope: String) {
        guard !messageId.isEmpty else { return }
        Task { @MainActor in
            var contentType: String = "application/octet-stream"
            var fileName: String? = nil
            var data: Data? = nil

            if scope == "group" {
                if let gid = UUID(uuidString: messageId) {
                    if let m = MessageGroupManager.shared.groupMessages.values.flatMap({ $0 }).first(where: { $0.id == gid }) {
                        data = m.mediaData
                        fileName = m.mediaFileName
                        contentType = mapGroupMediaTypeToMime(m.mediaType)
                    }
                }
            } else {
                if let m = MessageManager.shared?.messages.first(where: { $0.id == messageId }) {
                    data = m.mediaAttachment
                    fileName = m.mediaFileName
                    contentType = mapMediaTypeToMime(m.mediaType)
                }
            }

            if let buf = data, !buf.isEmpty {
                var payload: [String: Any] = [
                    "type": "media_response",
                    "messageId": messageId,
                    "scope": scope,
                    "contentType": contentType,
                    "data": buf.base64EncodedString()
                ]
                if let fn = fileName { payload["fileName"] = fn }
                sendToWebSocket(payload)
            } else {
                sendToWebSocket([
                    "type": "media_response",
                    "messageId": messageId,
                    "scope": scope,
                    "error": "not_found"
                ])
            }
        }
    }

    private func mapMediaTypeToMime(_ type: MediaManager.MediaType?) -> String {
        guard let t = type else { return "application/octet-stream" }
        switch t {
        case .image: return "image/jpeg"
        // __GIF_2026_09_12__ Le vrai type MIME, pas `image/jpeg`: un navigateur
        // n'anime un GIF que s'il le reçoit comme `image/gif`. Le servir en jpeg
        // donnerait une image fixe côté web, c'est-à-dire précisément ce que
        // l'ajout du type `.gif` cherche à éviter.
        case .gif: return "image/gif"
        case .video: return "video/mp4"
        case .audio: return "audio/m4a"
        case .document: return "application/octet-stream"
        case .contact: return "text/vcard"
        }
    }

    private func mapGroupMediaTypeToMime(_ type: MediaType?) -> String {
        guard let t = type else { return "application/octet-stream" }
        switch t {
        case .photo: return "image/jpeg"
        case .video: return "video/mp4"
        case .audio: return "audio/m4a"
        case .document: return "application/octet-stream"
        case .contact: return "text/vcard"
        }
    }
    
    private func handleWebSocketDisconnect() {
        webSocket = nil
        DispatchQueue.main.async { self.isConnected = false }
        if !activeSessions.isEmpty {
            reconnectTimer?.invalidate()
            reconnectTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: false) { [weak self] _ in self?.connectWebSocket() }
        }
    }
    
    private func startPingTimer() {
        pingTimer?.invalidate()
        pingTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            self?.webSocket?.sendPing { _ in }
        }
    }
    
    /// Performs initial sync when mobile auth succeeds - sends conversations, groups, recent messages,
    /// profile picture, wallpapers.
    /// - Parameter target: v2 — the one browser (`sid`) this snapshot is for; nil = every
    ///   linked browser (v1 socket, or a v2 push to all).
    @MainActor
    func performInitialSync(target: String? = nil) {
        syncConversationsToWeb(target: target)
        syncGroupsToWeb(target: target)
        pushProfilePicture(target: target)

        guard let mgr = messageManager ?? MessageManager.shared else { return }
        let recentConversations = Array(mgr.conversations.prefix(5))

        for conversation in recentConversations {
            syncMessagesToWeb(for: conversation.participantPublicKey, target: target)
            pushWallpaper(conversationKey: conversation.participantPublicKey, target: target)
        }

        // Sync top 5 groups + their messages + their pic/wallpaper
        let recentGroups = MessageGroupManager.shared.groups.prefix(5)
        for g in recentGroups {
            syncGroupMessagesToWeb(groupId: g.id, target: target)
            pushGroupPicture(group: g, target: target)
            pushGroupWallpaper(group: g, target: target)
        }

        OshiLog.web.info("📤 WebSession: Initial sync completed (\(recentConversations.count) chats, \(recentGroups.count) groups)")
    }

    /// Message timestamps for the web companion.
    ///
    /// A bare `ISO8601DateFormatter()` emits `…T14:32:07Z` — TRUNCATED TO THE
    /// SECOND. Several messages sent in the same second (which is exactly what
    /// splitting a long paste produces) all serialize to the same string, and
    /// the web client has nothing left to order them by. `.withFractionalSeconds`
    /// keeps the milliseconds, and the result still sorts correctly as a plain
    /// string because the field is fixed width. Hoisted because building an
    /// ISO8601DateFormatter is expensive and this runs per message.
    private static let webTimestampFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    /// __WEB_IDENTITY_2026_10_10__ Who the user is, for the page's own header.
    ///
    /// The page had no way to know: it showed
    /// `session.publicKey.substring(0, 2)` as the avatar and a truncated key as
    /// the name, because that is all it had ever been told. So the person's own
    /// row read as two characters of base64 and a key fragment, next to the
    /// nickname and photo they had set on the phone.
    ///
    /// The photo is sent as a JPEG data URL so the page can use it directly in
    /// an `<img src>`; it is re-encoded small because this crosses a WebSocket
    /// on every reconnect and the stored original can be hundreds of KB.
    func syncSelfIdentityToWeb(target: String? = nil) {
        let name = UserDefaults.standard.string(forKey: "userName")?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        var payload: [String: Any] = ["type": "self_identity", "displayName": name]

        if let data = (identityManager ?? MessageManager.sharedIdentityManager)?.profileImageData
            ?? UserDefaults.standard.data(forKey: "profileImageData"),
           let image = UIImage(data: data) {
            // 96 pt is twice the 48 px the page draws the avatar at, so it is
            // crisp on a retina display and still a few KB on the wire.
            let side: CGFloat = 96
            let format = UIGraphicsImageRendererFormat.default()
            format.scale = 1
            let thumb = UIGraphicsImageRenderer(size: CGSize(width: side, height: side),
                                                format: format).image { _ in
                image.draw(in: CGRect(x: 0, y: 0, width: side, height: side))
            }
            if let jpeg = thumb.jpegData(compressionQuality: 0.8) {
                payload["avatar"] = "data:image/jpeg;base64,\(jpeg.base64EncodedString())"
            }
        }
        sendToWebSocket(payload, target: target)
        OshiLog.web.info("📤 WebSession: synced self identity (name=\(name.isEmpty ? "unset" : "set"), photo=\(payload["avatar"] != nil))")
    }

    func syncConversationsToWeb(target: String? = nil) {
        guard let mgr = messageManager ?? MessageManager.shared else { return }
        let conversations = mgr.conversations.map { conv -> [String: Any] in
            // __WEB_IDENTITY_2026_10_10__ Send the name the USER chose, not a
            // key fragment.
            //
            // Both `contactName` and `alias` were set to
            // `conv.participantAddress` — the truncated public key. So every
            // name in the web client was a base64 fragment like "Yk8vQmx2",
            // for contacts the user had carefully named on their phone. The
            // resolver that every other surface uses was right there.
            let name = MessageManager.resolveContactAlias(conv.participantPublicKey)
            var convData: [String: Any] = [
                "publicKey": conv.participantPublicKey,
                "contactName": name,
                "lastMessage": Self.conversationPreview(conv.lastMessage.plaintextContent),
                "lastMessageTime": Self.webTimestampFormatter.string(from: conv.lastMessage.timestamp),
                "unreadCount": conv.unreadCount
            ]
            // Kept for older pages that read `alias`; same value now.
            convData["alias"] = name
            // (N21) `sync_groups` has sent `groupPictureBase64` since that
            // channel existed; this one never sent the 1:1 equivalent — only
            // `pushProfilePicture` did, and that is SELF-identity-only (it
            // keys on `getPublicKey()`, never on a contact's key) and only
            // fires on a change, never on the initial sync. web.html already
            // reads `profilePictures[c.publicKey]` when it draws this list;
            // it was only ever missing a writer for a contact's existing photo.
            if let avatar = ContactPresenceManager.shared.presenceMap[conv.participantPublicKey]?.profileImageData {
                convData["profilePictureBase64"] = avatar.base64EncodedString()
            }
            return convData
        }
        sendToWebSocket(["type": "sync_conversations", "conversations": conversations], target: target)
        // The page draws its own header from whatever it last heard; send it
        // with the conversations so a reconnect repaints both together.
        syncSelfIdentityToWeb(target: target)
        OshiLog.web.info("📤 WebSession: Synced \(conversations.count) conversations to web")
    }

    func syncMessagesToWeb(for contactKey: String, target: String? = nil) {
        guard let mgr = messageManager ?? MessageManager.shared else { return }
        let myPublicKey = getPublicKey() ?? ""
        let messages: [[String: Any]] = mgr.getMessages(for: contactKey).map { msg in
            var msgData: [String: Any] = [
                "id": msg.id,
                "content": msg.plaintextContent ?? "",
                "timestamp": Self.webTimestampFormatter.string(from: msg.timestamp),
                "isFromMe": msg.senderPublicKey == myPublicKey,
                "senderPublicKey": msg.senderPublicKey,
                "recipientPublicKey": msg.recipientPublicKey,
                "status": mapDeliveryStatus(msg.deliveryStatus, isRead: msg.isRead)
            ]

            // Add full media payload if present
            if let mediaType = msg.mediaType {
                msgData["mediaType"] = mapMediaType(mediaType)
                if let fileName = msg.mediaFileName {
                    msgData["fileName"] = fileName
                }
                if let attachment = msg.mediaAttachment {
                    msgData["mediaSize"] = attachment.count
                    if attachment.count <= inlineMediaLimit {
                        // ≤ 5 MB (v1) / 256 KiB (v2): send full base64 payload (image/video/audio/doc)
                        msgData["mediaData"] = attachment.base64EncodedString()
                        // Backward-compat: keep small image thumbnail
                        if mediaType == .image, attachment.count < 100_000 {
                            msgData["thumbnailData"] = attachment.base64EncodedString()
                        }
                    } else {
                        // > 5 MB: out-of-band fetch (web client downloads via /api/web/media)
                        msgData["mediaUrl"] = "/api/web/media/\(msg.id)"
                    }
                }
            }

            return msgData
        }
        sendToWebSocket(["type": "sync_messages", "contactPublicKey": contactKey, "messages": messages], target: target)
        OshiLog.web.info("📤 WebSession: Synced \(messages.count) messages for contact to web")
    }

    // ============================================
    // 🆕 GROUP SYNC
    // ============================================

    /// Sync all groups (id, name, members, picture, wallpaper, …) to web sessions.
    /// `MessageGroupManager` is `@MainActor`-isolated; always jump on-main via Task.
    func syncGroupsToWeb(target: String? = nil) {
        Task { @MainActor [weak self] in self?.performGroupsSyncOnMain(target: target) }
    }

    @MainActor
    private func performGroupsSyncOnMain(target: String? = nil) {
        let groups = MessageGroupManager.shared.groups
        let payload: [[String: Any]] = groups.map { g -> [String: Any] in
            var d: [String: Any] = [
                "id": g.id.uuidString,
                "name": g.name,
                "groupType": g.type.rawValue,
                "adminPublicKey": g.adminPublicKey,
                "members": g.members.map { m -> [String: Any] in
                    return [
                        "publicKey": m.publicKey,
                        "alias": m.alias ?? "",
                        "isAdmin": m.isAdmin
                    ]
                },
                "lastMessageTime": Self.webTimestampFormatter.string(from: g.lastActivity),
                "unreadCount": MessageGroupManager.shared.getUnreadCount(for: g.id),
                "isMuted": g.isMuted
            ]
            if let pic = g.groupPictureData {
                d["groupPictureBase64"] = pic.base64EncodedString()
            }
            if let wp = g.groupWallpaperData {
                d["groupWallpaperBase64"] = wp.base64EncodedString()
            }
            // Last message preview
            if let last = MessageGroupManager.shared.groupMessages[g.id]?.last {
                d["lastMessage"] = Self.conversationPreview(last.plaintextContent)
            }
            return d
        }
        sendToWebSocket(["type": "sync_groups", "groups": payload], target: target)
        OshiLog.web.info("📤 WebSession: Synced \(groups.count) groups to web")
    }

    /// Sync messages of a specific group to web. Hop to the main actor where
    /// `MessageGroupManager.groupMessages` is safe to read.
    func syncGroupMessagesToWeb(groupId: UUID, target: String? = nil) {
        Task { @MainActor [weak self] in self?.performGroupMessagesSyncOnMain(groupId: groupId, target: target) }
    }

    @MainActor
    private func performGroupMessagesSyncOnMain(groupId: UUID, target: String? = nil) {
        let myPublicKey = getPublicKey() ?? ""
        let msgs = MessageGroupManager.shared.groupMessages[groupId] ?? []
        let payload: [[String: Any]] = msgs.map { m in
            var d: [String: Any] = [
                "id": m.id.uuidString,
                "groupId": m.groupId.uuidString,
                "content": m.plaintextContent ?? "",
                "timestamp": Self.webTimestampFormatter.string(from: m.timestamp),
                "senderPublicKey": m.senderPublicKey,
                "isFromMe": m.senderPublicKey == myPublicKey,
                "isRead": m.isRead
            ]
            if let mt = m.mediaType {
                d["mediaType"] = mapGroupMediaType(mt)
                if let fn = m.mediaFileName { d["fileName"] = fn }
                if let media = m.mediaData {
                    d["mediaSize"] = media.count
                    if media.count <= inlineMediaLimit {
                        d["mediaData"] = media.base64EncodedString()
                    } else {
                        d["mediaUrl"] = "/api/web/group-media/\(m.id.uuidString)"
                    }
                }
            }
            return d
        }
        sendToWebSocket([
            "type": "sync_group_messages",
            "groupId": groupId.uuidString,
            "messages": payload
        ], target: target)
        OshiLog.web.info("📤 WebSession: Synced \(msgs.count) group messages for \(groupId.uuidString)")
    }

    /// Notify web of a single new group message.
    func notifyNewGroupMessage(_ message: GroupMessage) {
        guard isConnected else { return }
        let myPublicKey = getPublicKey() ?? ""
        var d: [String: Any] = [
            "id": message.id.uuidString,
            "groupId": message.groupId.uuidString,
            "content": message.plaintextContent ?? "",
            "timestamp": Self.webTimestampFormatter.string(from: message.timestamp),
            "senderPublicKey": message.senderPublicKey,
            "isFromMe": message.senderPublicKey == myPublicKey,
            "isRead": message.isRead
        ]
        if let mt = message.mediaType {
            d["mediaType"] = mapGroupMediaType(mt)
            if let fn = message.mediaFileName { d["fileName"] = fn }
            if let media = message.mediaData {
                d["mediaSize"] = media.count
                if media.count <= inlineMediaLimit {
                    d["mediaData"] = media.base64EncodedString()
                } else {
                    d["mediaUrl"] = "/api/web/group-media/\(message.id.uuidString)"
                }
            }
        }
        sendToWebSocket([
            "type": "new_group_message",
            "groupId": message.groupId.uuidString,
            "message": d
        ])
    }

    /// Map iOS-internal `MediaType` (the one from GroupMessaging.swift) to web-friendly string.
    private func mapGroupMediaType(_ type: MediaType) -> String {
        switch type {
        case .photo: return "image"
        case .video: return "video"
        case .audio: return "audio"
        case .document: return "file"
        case .contact: return "contact"
        }
    }

    // ============================================
    // 🆕 PROFILE PICTURE / WALLPAPER PUSH
    // ============================================

    func pushProfilePicture(target: String? = nil) {
        let pk = getPublicKey() ?? ""
        guard !pk.isEmpty else { return }
        let data = identityManager?.profileImageData
        var msg: [String: Any] = [
            "type": "profile_picture_update",
            "publicKey": pk
        ]
        msg["imageData"] = data?.base64EncodedString() ?? ""
        sendToWebSocket(msg, target: target)
    }

    func pushWallpaper(conversationKey: String, target: String? = nil) {
        let wp = ChatWallpaperManager.shared.getWallpaper(for: conversationKey)
        var msg: [String: Any] = [
            "type": "wallpaper_update",
            "conversationKey": conversationKey
        ]
        var wpData = ""
        var wpType = "color"
        switch wp {
        case .none:
            wpType = "none"
        case .gradientPreset, .gradient:
            wpType = "gradient"
            if let enc = try? JSONEncoder().encode(wp) {
                wpData = enc.base64EncodedString()
            }
        case .emojiPattern:
            wpType = "emoji"
            if let enc = try? JSONEncoder().encode(wp) {
                wpData = enc.base64EncodedString()
            }
        case .customImage(let filename):
            wpType = "image"
            // Resolve to base64
            if let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
                let url = docs.appendingPathComponent(filename)
                // Wallpapers are sealed at rest (`ChatWallpaperManager.saveCustomImage` →
                // MediaCipher): send the PLAINTEXT JPEG, never the ciphertext on disk.
                if let imgData = try? MediaCipher.read(contentsOf: url) {
                    wpData = imgData.base64EncodedString()
                }
            }
        default:
            wpType = "color"
            if let enc = try? JSONEncoder().encode(wp) {
                wpData = enc.base64EncodedString()
            }
        }
        msg["wallpaperData"] = wpData
        msg["wallpaperType"] = wpType
        sendToWebSocket(msg, target: target)
    }

    func pushGroupPicture(group: MessageGroup, target: String? = nil) {
        var msg: [String: Any] = [
            "type": "group_picture_update",
            "groupId": group.id.uuidString
        ]
        msg["imageData"] = group.groupPictureData?.base64EncodedString() ?? ""
        sendToWebSocket(msg, target: target)
    }

    func pushGroupWallpaper(group: MessageGroup, target: String? = nil) {
        var msg: [String: Any] = [
            "type": "group_wallpaper_update",
            "groupId": group.id.uuidString
        ]
        msg["wallpaperData"] = group.groupWallpaperData?.base64EncodedString() ?? ""
        msg["wallpaperType"] = "image"
        sendToWebSocket(msg, target: target)
    }

    // ============================================
    // 🆕 CALL STATE PUSH (mobile → web)
    // ============================================

    /// Push current call state to all linked web sessions.
    /// Called by VoiceCallManager / VideoCallManager whenever the call state transitions.
    func pushCallState(callId: String,
                       state: String,        // "ringing"|"connecting"|"connected"|"ended"|"failed"|"declined"
                       peerPublicKey: String,
                       peerName: String? = nil,
                       isVideo: Bool = false,
                       direction: String = "incoming",  // "incoming"|"outgoing"
                       errorMessage: String? = nil) {
        var msg: [String: Any] = [
            "type": "call_state_update",
            "callId": callId,
            "state": state,
            "peerPublicKey": peerPublicKey,
            "isVideo": isVideo,
            "direction": direction,
            "timestamp": ISO8601DateFormatter().string(from: Date())
        ]
        if let n = peerName { msg["peerName"] = n }
        if let e = errorMessage { msg["error"] = e }
        sendToWebSocket(msg)
        OshiLog.web.info("📤 WebSession: Pushed call state '\(state)' (\(direction))")
    }

    private func mapDeliveryStatus(_ status: DeliveryStatus, isRead: Bool) -> String {
        if isRead { return "read" }
        switch status {
        case .pending: return "sending"
        case .sent: return "sent"
        case .delivered: return "delivered"
        case .failed: return "failed"
        @unknown default: return "sending"
        }
    }

    private func mapMediaType(_ type: MediaManager.MediaType) -> String {
        switch type {
        case .image: return "image"
        // __GIF_2026_09_12__ Volontairement « image » et non « gif ». Cette
        // chaîne est l'étiquette lue par le client WEB, qui ne connaît pas de
        // catégorie « gif »: la ranger là ferait tomber la bulle dans la branche
        // « type inconnu » et elle ne s'afficherait pas du tout. En « image »
        // elle passe par le rendu <img>, et comme le type MIME juste au-dessus
        // dit bien `image/gif`, le navigateur l'anime tout seul.
        case .gif: return "image"
        case .video: return "video"
        case .audio: return "audio"
        case .document: return "file"
        case .contact: return "contact"
        }
    }
    
    @discardableResult
    func handleWebMessage(content: String, recipient: String,
                          mediaAttachment: Data? = nil, mediaType: MediaManager.MediaType? = nil,
                          mediaFileName: String? = nil) -> Bool {
        guard let mgr = messageManager ?? MessageManager.shared,
              let identity = identityManager ?? MessageManager.sharedIdentityManager,
              let mesh = meshManager ?? MessageManager.sharedMeshManager else { return false }
        return mgr.sendMessage(content: content, recipientAddress: recipient, identityManager: identity,
                               meshManager: mesh, mediaAttachment: mediaAttachment, mediaType: mediaType,
                               mediaFileName: mediaFileName).didSend
    }
    
    func notifyNewMessage(_ message: SecureMessage) {
        guard isConnected else { return }
        let myPublicKey = getPublicKey() ?? ""

        // Determine the contact public key (the other party)
        let contactPublicKey = message.senderPublicKey == myPublicKey
            ? message.recipientPublicKey
            : message.senderPublicKey

        var msgData: [String: Any] = [
            "id": message.id,
            "content": message.plaintextContent ?? "",
            "timestamp": Self.webTimestampFormatter.string(from: message.timestamp),
            "isFromMe": message.senderPublicKey == myPublicKey,
            "senderPublicKey": message.senderPublicKey,
            "recipientPublicKey": message.recipientPublicKey,
            "status": mapDeliveryStatus(message.deliveryStatus, isRead: message.isRead)
        ]

        // Add media metadata + payload if present
        if let mediaType = message.mediaType {
            msgData["mediaType"] = mapMediaType(mediaType)
            if let fileName = message.mediaFileName {
                msgData["fileName"] = fileName
            }
            if let attachment = message.mediaAttachment {
                msgData["mediaSize"] = attachment.count
                if attachment.count <= inlineMediaLimit {
                    msgData["mediaData"] = attachment.base64EncodedString()
                    if mediaType == .image, attachment.count < 100_000 {
                        msgData["thumbnailData"] = attachment.base64EncodedString()
                    }
                } else {
                    msgData["mediaUrl"] = "/api/web/media/\(message.id)"
                }
            }
        }

        sendToWebSocket([
            "type": "new_message",
            "contactPublicKey": contactPublicKey,
            "message": msgData
        ])
        OshiLog.web.info("📤 WebSession: Sent new message notification to web")
    }
    
    /// ≤ 5 MB inline on a v1 socket (unchanged); ≤ 256 KiB on a v2 one (§4.4), larger
    /// media travel as a `mediaUrl` the page resolves through `media_get`.
    private var inlineMediaLimit: Int { socketIsV2 ? WebE2E.inlineMediaMax : 5_000_000 }

    /// Every phone → browser message goes through here.
    ///   • v1 socket: the plaintext frame, exactly as before.
    ///   • v2 socket: sealed once per live v2 browser (or only for `target`), NEVER in clear.
    private func sendToWebSocket(_ data: [String: Any], target: String? = nil) {
        if socketIsV2 {
            sendE2E(data, target: target)
            return
        }
        // __LEAK_FIX_2026_09_25__ v1 = plaintext through the server: never sent.
    }

    /// __LEAK_FIX_2026_09_25__
    private var hasAnyE2EChannel: Bool {
        e2eLock.lock(); defer { e2eLock.unlock() }
        return !e2eChannels.isEmpty
    }

    // MARK: - v2 channel plumbing (__WEBSESSION_E2E_2026_09_23__)

    private func hasChannel(_ sid: String) -> Bool {
        e2eLock.lock(); defer { e2eLock.unlock() }
        return e2eChannels[sid] != nil
    }

    private func dropE2ESession(_ sid: String) {
        e2eLock.lock()
        e2eChannels[sid] = nil
        e2eMediaInFlight[sid] = nil
        e2eStopped.remove(sid)
        e2eUploads = e2eUploads.filter { !$0.key.hasPrefix(sid + "|") }
        e2eLock.unlock()
        WebE2E.deleteKeys(sid: sid)
    }

    /// Seal `data` for each target browser and send. Oversized lists are split, oversized
    /// pictures are dropped from the frame (the server refuses a frame above 512 KiB).
    @discardableResult
    private func sendE2E(_ data: [String: Any], target: String?, trackMedia: Bool = false) -> [(sid: String, ep: String, seq: Int)] {
        var inner = data
        let t = WebE2E.innerType(forV1: inner.removeValue(forKey: "type") as? String ?? "")
        guard !t.isEmpty else { return [] }
        inner["t"] = t
        e2eLock.lock()
        let channels = target.map { sid in e2eChannels[sid].map { [$0] } ?? [] } ?? Array(e2eChannels.values)
        e2eLock.unlock()
        guard !channels.isEmpty else { return [] }

        var pieces: [[String: Any]] = [inner]
        if let pt = try? JSONSerialization.data(withJSONObject: inner), pt.count > WebE2E.maxFramePlaintext {
            pieces = Self.splitForFrames(inner)
        }
        var sent: [(String, String, Int)] = []
        for ch in channels {
            for piece in pieces {
                guard let frame = try? ch.seal(piece),
                      let json = try? JSONSerialization.data(withJSONObject: frame),
                      let str = String(data: json, encoding: .utf8) else { continue }
                if let ep = frame["ep"] as? String, let seq = frame["seq"] as? Int {
                    sent.append((ch.sid, ep, seq))
                    if trackMedia {
                        e2eLock.lock(); e2eMediaInFlight[ch.sid, default: []].insert("\(ep):\(seq)"); e2eLock.unlock()
                    }
                }
                webSocket?.send(.string(str)) { _ in }
            }
        }
        return sent
    }

    /// Split a too-large inner message: `messages` lists are cut into several frames of the
    /// same type; big inline pictures are removed (the page keeps its previous one).
    /// (N20) How much of a last message travels as a conversation preview.
    ///
    /// The browser draws it in `.convo-preview`, which is
    /// `white-space:nowrap; overflow:hidden; text-overflow:ellipsis` — one
    /// clipped line, about 40 characters of it on a 375 px sidebar. The rest
    /// was sent and never seen.
    ///
    /// It is also the only UNBOUNDED term in `conversations_sync`, a frame that
    /// is only ever split by `splitForFrames` — which, for an inner with no
    /// `messages` list, strips the photos and then returns `[]` if it still
    /// does not fit, i.e. drops the WHOLE list. The browser is told nothing, so
    /// what the user sees is an empty sidebar beside a chat pane that cannot be
    /// opened, under a green "Phone connected": Anatole's N20. Same constant
    /// and same reasoning as Android's CONVERSATION_PREVIEW_MAX_CHARS.
    static let conversationPreviewMaxChars = 160

    static func conversationPreview(_ text: String?) -> String {
        let t = text ?? ""
        return t.count <= conversationPreviewMaxChars
            ? t
            : String(t.prefix(conversationPreviewMaxChars))
    }

    static func splitForFrames(_ inner: [String: Any]) -> [[String: Any]] {
        func size(_ o: Any) -> Int { (try? JSONSerialization.data(withJSONObject: o))?.count ?? Int.max }
        var base = inner
        for key in ["groupPictureBase64", "groupWallpaperBase64", "imageData", "wallpaperData"] {
            if let v = base[key] as? String, v.count > WebE2E.maxFramePlaintext / 2 { base[key] = "" }
        }
        if var groups = base["groups"] as? [[String: Any]] {
            for i in groups.indices {
                groups[i]["groupPictureBase64"] = nil; groups[i]["groupWallpaperBase64"] = nil
            }
            base["groups"] = groups
        }
        // (N21) conversations now carries a per-contact profilePictureBase64
        // too — same move as groups just above: drop the photos first, keep
        // every conversation's text fields, rather than dropping the whole
        // sync because a contact-heavy account's avatars pushed it over budget.
        if var conversations = base["conversations"] as? [[String: Any]] {
            for i in conversations.indices { conversations[i]["profilePictureBase64"] = nil }
            base["conversations"] = conversations
        }
        guard let list = base["messages"] as? [[String: Any]] else {
            return size(base) <= WebE2E.maxFramePlaintext ? [base] : []
        }
        var out: [[String: Any]] = []
        var current: [[String: Any]] = []
        var currentSize = 0
        let budget = WebE2E.maxFramePlaintext - 2048
        for var m in list {
            var s = size(m)
            if s > budget { m["thumbnailData"] = nil; m["mediaData"] = nil; s = size(m) }
            if s > budget { continue }
            if currentSize + s > budget, !current.isEmpty {
                var piece = base; piece["messages"] = current; out.append(piece)
                current = []; currentSize = 0
            }
            current.append(m); currentSize += s + 1
        }
        var last = base; last["messages"] = current; out.append(last)
        return out
    }

    private func handleE2EFrame(_ frame: [String: Any]) {
        guard frame["dir"] as? String == "b2p", let sid = frame["sid"] as? String else { return }
        e2eLock.lock(); let ch = e2eChannels[sid]; e2eLock.unlock()
        guard let ch else { return }
        let inner: [String: Any]
        do { inner = try ch.open(frame) } catch {
            // Dropped, never displayed (§3 receiver rules).
            OshiLog.web.info("🔒 WebSession: e2e frame refused (\(String(describing: error)))")
            return
        }
        guard let t = inner["t"] as? String, t != "e2e" else { return }
        var json = inner
        json.removeValue(forKey: "t"); json.removeValue(forKey: "_ts")
        json["type"] = t
        handleIncoming(json, type: t, e2eSid: sid)
    }

    private func handleE2EStatus(_ json: [String: Any]) {
        guard let sid = json["sid"] as? String, let ep = json["ep"] as? String,
              let seq = json["seq"] as? Int, let state = json["state"] as? String else { return }
        e2eLock.lock(); defer { e2eLock.unlock() }
        e2eMediaInFlight[sid]?.remove("\(ep):\(seq)")
        if state == "queued" { e2eStopped.insert(sid); e2eMediaInFlight[sid] = nil }
        if state == "relayed" { e2eStopped.remove(sid) }
    }

    /// `media_get` → `media_chunk` frames of 192 KiB (`off`, `last`), at most 16 awaiting
    /// `e2e_status`; stops when the browser goes away (`queued`).
    private func handleMediaGet(messageId: String, scope: String, sid: String) {
        guard !messageId.isEmpty else { return }
        Task { @MainActor in
            let (data, fileName, mime) = self.lookupMedia(messageId: messageId, scope: scope)
            guard let buf = data, !buf.isEmpty else {
                self.sendE2E(["type": "media_error", "messageId": messageId, "scope": scope, "error": "not_found"], target: sid)
                return
            }
            Task.detached { [weak self] in
                guard let self else { return }
                var off = 0
                while off < buf.count {
                    var waited = 0
                    while true {
                        let (stopped, inflight) = self.mediaGate(sid)
                        if stopped { return }
                        if inflight < 16 { break }
                        waited += 1
                        if waited > 600 { return }     // 30 s without progress: let the page ask again
                        try? await Task.sleep(nanoseconds: 50_000_000)
                    }
                    let end = min(off + WebE2E.mediaChunk, buf.count)
                    var chunk: [String: Any] = ["type": "media_chunk", "messageId": messageId, "scope": scope,
                                                "off": off, "last": end >= buf.count, "mime": mime,
                                                "data": buf.subdata(in: off..<end).base64EncodedString()]
                    if off == 0, let fileName { chunk["fileName"] = fileName }
                    self.sendE2E(chunk, target: sid, trackMedia: true)
                    off = end
                }
            }
        }
    }

    /// (browser gone, chunks awaiting `e2e_status`) — synchronous, so the lock is never held
    /// across a suspension point.
    nonisolated private func mediaGate(_ sid: String) -> (Bool, Int) {
        e2eLock.lock(); defer { e2eLock.unlock() }
        return (e2eStopped.contains(sid), e2eMediaInFlight[sid]?.count ?? 0)
    }

    @MainActor
    private func lookupMedia(messageId: String, scope: String) -> (Data?, String?, String) {
        if scope == "group" {
            if let gid = UUID(uuidString: messageId),
               let m = MessageGroupManager.shared.groupMessages.values.flatMap({ $0 }).first(where: { $0.id == gid }) {
                return (m.mediaData, m.mediaFileName, mapGroupMediaTypeToMime(m.mediaType))
            }
            return (nil, nil, "application/octet-stream")
        }
        if let m = (messageManager ?? MessageManager.shared)?.messages.first(where: { $0.id == messageId }) {
            return (m.mediaAttachment, m.mediaFileName, mapMediaTypeToMime(m.mediaType))
        }
        return (nil, nil, "application/octet-stream")
    }

    /// `send_file_chunk` from the page: reassemble in order, then send as a normal media
    /// message (answered with `send_result` keyed by the upload id).
    private func handleFileChunk(_ json: [String: Any], sid: String) {
        guard let uploadId = json["uploadId"] as? String, !uploadId.isEmpty,
              let recipient = json["recipientPublicKey"] as? String, !recipient.isEmpty,
              let off = json["off"] as? Int, let b64 = json["data"] as? String,
              let bytes = Data(base64Encoded: b64) else { return }
        let key = sid + "|" + uploadId
        let size = json["size"] as? Int ?? 0
        guard size > 0, size <= V2.maxPlaintextBytes else { return }
        e2eLock.lock()
        var up = e2eUploads[key] ?? WebUpload(recipient: recipient, kind: json["kind"] as? String ?? "file",
                                              fileName: json["fileName"] as? String ?? "",
                                              mime: json["mime"] as? String ?? "application/octet-stream",
                                              size: size, data: Data())
        guard off == up.data.count, up.recipient == recipient, up.data.count + bytes.count <= up.size else {
            e2eUploads[key] = nil
            e2eLock.unlock()
            sendE2E(["type": "send_result", "clientMsgId": uploadId, "status": "failed"], target: sid)
            return
        }
        up.data.append(bytes)
        let last = (json["last"] as? Bool) == true
        if last { e2eUploads[key] = nil } else { e2eUploads[key] = up }
        e2eLock.unlock()
        guard last else { return }
        let mediaType: MediaManager.MediaType
        switch up.kind {
        case "image": mediaType = up.mime == "image/gif" ? .gif : .image
        case "video": mediaType = .video
        case "audio": mediaType = .audio
        default: mediaType = .document
        }
        let complete = up
        Task { @MainActor in
            let ok = complete.data.count == complete.size
                && self.handleWebMessage(content: "", recipient: complete.recipient,
                                         mediaAttachment: complete.data, mediaType: mediaType,
                                         mediaFileName: complete.fileName.isEmpty ? nil : complete.fileName)
            self.sendE2E(["type": "send_result", "clientMsgId": uploadId, "status": ok ? "sent" : "failed"], target: sid)
        }
    }

    /// §5: the first v2 connect revokes this account's remaining v1 (plaintext) sessions —
    /// a v2 socket cannot serve them anyway (the server drops v1 frames on it).
    private func revokeV1SessionsAfterV2Connect() {
        guard !v1SessionsRevokedAfterV2 else { return }
        v1SessionsRevokedAfterV2 = true
        Task { @MainActor in
            await self.fetchActiveSessions()
            for s in self.activeSessions where !s.isEndToEnd {
                OshiLog.web.info("🔒 WebSession: revoking a v1 (plaintext) session after the v2 upgrade")
                _ = await self.revokeSession(s.sessionId)
            }
        }
    }
    
    private struct QRValidateResponse: Codable { let success: Bool; let sessionId: String?; var wsv: Int? = nil }
    private struct SessionsResponse: Codable { let sessions: [WebSession] }
    private struct SuccessResponse: Codable { let success: Bool }
    private struct RevokeAllResponse: Codable { let success: Bool; let revokedCount: Int }
    private struct ErrorResponse: Codable { let error: String }
}

// MARK: - Web Sessions List View

struct WebSessionsView: View {
    @StateObject private var sessionManager = WebSessionManager.shared
    @State private var showScanner = false
    @State private var showRevokeAllAlert = false
    @State private var sessionToRevoke: WebSessionManager.WebSession?
    
    private let brandColor = Theme.brand
    
    var body: some View {
        List {
            linkComputerSection
            activeSessionsSection
            if !sessionManager.activeSessions.isEmpty { revokeAllSection }
            securityInfoSection
        }
        .navigationTitle("settings.webSessions.title".localized)
        .refreshable { await sessionManager.fetchActiveSessions() }
        .onAppear { Task { await sessionManager.fetchActiveSessions() } }
        .sheet(isPresented: $showScanner) { WebQRScannerView() }
        .alert("settings.webSessions.revokeSession".localized, isPresented: Binding(
            get: { sessionToRevoke != nil },
            set: { if !$0 { sessionToRevoke = nil } }
        )) {
            Button("common.cancel".localized, role: .cancel) { sessionToRevoke = nil }
            Button("settings.webSessions.revoke".localized, role: .destructive) {
                if let session = sessionToRevoke { Task { await sessionManager.revokeSession(session.sessionId) } }
                sessionToRevoke = nil
            }
        } message: {
            if let session = sessionToRevoke {
                Text("settings.webSessions.revokeMessage".localized
                        .replacingOccurrences(of: "%browser%", with: session.browserName)
                        .replacingOccurrences(of: "%platform%", with: session.platformName))
            }
        }
        .alert("settings.webSessions.revokeAllTitle".localized, isPresented: $showRevokeAllAlert) {
            Button("common.cancel".localized, role: .cancel) { }
            Button("settings.webSessions.revokeAll".localized, role: .destructive) {
                Task { await sessionManager.revokeAllSessions() }
            }
        } message: {
            Text("settings.webSessions.revokeAllMessage".localized
                    .replacingOccurrences(of: "%count%", with: "\(sessionManager.activeSessions.count)"))
        }
    }
    
    private var linkComputerSection: some View {
        Section {
            Button {
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                showScanner = true
            } label: {
                HStack(spacing: 16) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 10)
                            .fill(LinearGradient(colors: [brandColor, .purple], startPoint: .topLeading, endPoint: .bottomTrailing))
                            .frame(width: 44, height: 44)
                        Image(systemName: "qrcode.viewfinder").font(.title2).foregroundColor(.white)
                    }
                    .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("settings.webSessions.linkComputer".localized).font(.headline).foregroundColor(.primary)
                        Text("settings.webSessions.scanQR".localized).font(.caption).foregroundColor(.secondary)
                    }
                    Spacer()
                    Image(systemName: "chevron.right").font(.caption).foregroundColor(.secondary)
                        .accessibilityHidden(true)
                }
                .padding(.vertical, 8)
            }
            .accessibilityElement(children: .combine)
            .accessibilityHint("settings.webSessions.scanQR".localized)
        } header: {
            Text("settings.webSessions.sectionHeader".localized)
        } footer: {
            Text("settings.webSessions.footer".localized)
        }
    }
    
    private var activeSessionsSection: some View {
        Section {
            if sessionManager.isLoading && sessionManager.activeSessions.isEmpty {
                HStack { Spacer(); ProgressView(); Spacer() }.padding(.vertical, 20)
            } else if sessionManager.activeSessions.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "desktopcomputer").font(.largeTitle).foregroundColor(.secondary.opacity(0.5))
                    Text("settings.webSessions.noSessions".localized).font(.subheadline).foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity).padding(.vertical, 30)
            } else {
                ForEach(sessionManager.activeSessions) { session in
                    WebSessionRowView(session: session)
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            Button(role: .destructive) { sessionToRevoke = session } label: {
                                Label("settings.webSessions.revoke".localized, systemImage: "xmark.circle")
                            }
                        }
                }
            }
        } header: {
            activeSessionsHeader
        } footer: {
            activeSessionsFooter
        }
    }
    
    @ViewBuilder
    private var activeSessionsHeader: some View {
        HStack {
            Text("settings.webSessions.activeSessions".localized.uppercased() + " (\(sessionManager.activeSessions.count))")
            Spacer()
            if sessionManager.isLoading { ProgressView().scaleEffect(0.7) }
        }
    }
    
    @ViewBuilder
    private var activeSessionsFooter: some View {
        if let lastRefresh = sessionManager.lastRefresh {
            let formatter = RelativeDateTimeFormatter()
            let _ = formatter.unitsStyle = .abbreviated
            Text("settings.webSessions.lastUpdated".localized + " " + formatter.localizedString(for: lastRefresh, relativeTo: Date()))
        } else {
            EmptyView()
        }
    }
    
    private var revokeAllSection: some View {
        Section {
            Button(role: .destructive) { showRevokeAllAlert = true } label: {
                HStack { Image(systemName: "xmark.circle.fill"); Text("settings.webSessions.revokeAll".localized) }
            }
        } footer: {
            Text("settings.webSessions.revokeAllFooter".localized)
        }
    }
    
    private var securityInfoSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 12) {
                WebSecurityInfoRow(icon: "clock", text: "settings.webSessions.security.expire".localized)
                // __WEBSESSION_E2E_2026_09_23__ The claim comes back ONLY for v2 sessions,
                // which really are end-to-end (keys agreed through the QR, the server relays
                // ciphertext). v1 sessions stay unclaimed, per the C-WEB-1 rule below.
                if sessionManager.activeSessions.contains(where: { $0.isEndToEnd }) {
                    WebSecurityInfoRow(icon: "lock.fill", text: "settings.webSessions.security.e2e".localized)
                }
                // Security (C-WEB-1): the previous "end-to-end encrypted" and "contacts are not
                // shared" rows were FALSE — the web session relays plaintext message content and
                // contact keys to the server. Removed until the web channel is actually E2E.
                // Do not re-add these claims without implementing real end-to-end encryption.
            }
            .padding(.vertical, 8)
        } header: {
            Text("settings.security.title".localized.uppercased())
        }
    }
}

// MARK: - Session Row View

struct WebSessionRowView: View {
    let session: WebSessionManager.WebSession
    
    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle().fill(Color.purple.opacity(0.15)).frame(width: 44, height: 44)
                Image(systemName: session.browserIcon).font(.title3).foregroundColor(.purple)
            }
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Text(session.browserName).font(.headline)
                    if session.isActive {
                        Text("settings.webSessions.active".localized)
                            .font(.caption2).fontWeight(.medium)
                            .padding(.horizontal, 8).padding(.vertical, 3)
                            .background(Color.green.opacity(0.15)).foregroundColor(.green).cornerRadius(6)
                    }
                }
                Text(session.platformName).font(.subheadline).foregroundColor(.secondary)
                // __WEBSESSION_E2E_2026_09_23__ what this session is, stated per row.
                if session.isEndToEnd {
                    HStack(spacing: 4) {
                        Image(systemName: "lock.fill").accessibilityHidden(true)
                        if let sas = session.sas {
                            Text(String(format: "settings.webSessions.codeRow".localized,
                                        String(sas.prefix(3)) + " " + String(sas.suffix(3))))
                        } else {
                            Text("settings.webSessions.e2eBadge".localized)
                        }
                    }
                    .font(.caption).foregroundColor(.green)
                } else {
                    Text("settings.webSessions.notE2E".localized)
                        .font(.caption).foregroundColor(.orange)
                }
                if let lastActive = session.lastActiveDate {
                    Text(lastActiveText(lastActive))
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            Spacer()
        }
        .padding(.vertical, 6)
        .accessibilityElement(children: .combine)
    }
    
    private func lastActiveText(_ date: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return "settings.webSessions.lastActive".localized + " " + formatter.localizedString(for: date, relativeTo: Date())
    }
}

// MARK: - Security Info Row

struct WebSecurityInfoRow: View {
    let icon: String
    let text: String
    
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon).font(.subheadline).foregroundColor(.green).frame(width: 20)
                .accessibilityHidden(true)
            Text(text).font(.subheadline).foregroundColor(.secondary)
        }
    }
}

// MARK: - QR Scanner View

struct WebQRScannerView: View {
    @Environment(\.dismiss) var dismiss
    @StateObject private var sessionManager = WebSessionManager.shared
    @State private var isScanning = true
    @State private var showResult = false
    @State private var resultSuccess = false
    @State private var resultMessage = ""
    @State private var isProcessing = false
    
    private let brandColor = Theme.brand
    
    var body: some View {
        NavigationView {
            ZStack {
                WebQRCodeScannerView(isScanning: $isScanning, onCodeScanned: handleQRCode).ignoresSafeArea()
                
                GeometryReader { geometry in
                    let scanSize: CGFloat = 280
                    let centerX = (geometry.size.width - scanSize) / 2
                    let centerY = (geometry.size.height - scanSize) / 2 - 50
                    let scanRect = CGRect(x: centerX, y: centerY, width: scanSize, height: scanSize)
                    Path { path in
                        path.addRect(CGRect(origin: .zero, size: geometry.size))
                        path.addRoundedRect(in: scanRect, cornerSize: CGSize(width: 20, height: 20))
                    }
                    .fill(Color.black.opacity(0.6), style: FillStyle(eoFill: true))
                }
                .ignoresSafeArea()
                
                VStack(spacing: 24) {
                    Spacer()
                    scanFrameView
                    instructionsView
                    securityBadgeView
                    Spacer()
                    Spacer()
                }
            }
            .navigationTitle("settings.webSessions.linkComputer".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel".localized) { dismiss() }.foregroundColor(.white)
                }
            }
            .toolbarBackground(.hidden, for: .navigationBar)
            .alert(resultSuccess ? "alert.success".localized : "alert.error".localized, isPresented: $showResult) {
                Button("common.ok".localized) {
                    if resultSuccess { dismiss() } else { isScanning = true; isProcessing = false }
                }
            } message: { Text(resultMessage) }
        }
    }
    
    private var scanFrameView: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 20)
                .stroke(LinearGradient(colors: [brandColor, .purple], startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 3)
                .frame(width: 280, height: 280)
            ForEach(0..<4, id: \.self) { corner in WebCornerAccent(corner: corner, color: brandColor) }
            if isProcessing {
                RoundedRectangle(cornerRadius: 20).fill(Color.black.opacity(0.8)).frame(width: 280, height: 280)
                VStack(spacing: 16) {
                    ProgressView().progressViewStyle(CircularProgressViewStyle(tint: .white)).scaleEffect(1.5)
                    Text("settings.webSessions.linking".localized).foregroundColor(.white).font(.headline)
                }
            }
        }
        .offset(y: -50)
    }
    
    private var instructionsView: some View {
        VStack(spacing: 12) {
            Text("settings.webSessions.scanTitle".localized).font(.title2).fontWeight(.bold).foregroundColor(.white)
            Text("settings.webSessions.scanInstructions".localized).font(.subheadline).foregroundColor(.white.opacity(0.8)).multilineTextAlignment(.center).padding(.horizontal, 40)
        }
    }
    
    private var securityBadgeView: some View {
        HStack(spacing: 8) {
            Image(systemName: "lock.shield.fill").foregroundColor(.green)
            Text("info.encryption_title".localized).font(.caption).foregroundColor(.white.opacity(0.7))
        }
        .padding(.horizontal, 16).padding(.vertical, 10).background(Color.white.opacity(0.1)).cornerRadius(20)
    }
    
    private func handleQRCode(_ code: String) {
        guard !isProcessing else { return }
        isScanning = false
        isProcessing = true
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        Task {
            let result = await sessionManager.validateQRCode(code)
            await MainActor.run {
                isProcessing = false
                resultSuccess = result.success
                resultMessage = result.message
                UINotificationFeedbackGenerator().notificationOccurred(result.success ? .success : .error)
                showResult = true
            }
        }
    }
}

// MARK: - Corner Accent

struct WebCornerAccent: View {
    let corner: Int
    let color: Color
    
    var body: some View {
        Path { path in
            let length: CGFloat = 30
            let offset: CGFloat = 140
            switch corner {
            case 0:
                path.move(to: CGPoint(x: -offset, y: -offset + length))
                path.addLine(to: CGPoint(x: -offset, y: -offset))
                path.addLine(to: CGPoint(x: -offset + length, y: -offset))
            case 1:
                path.move(to: CGPoint(x: offset - length, y: -offset))
                path.addLine(to: CGPoint(x: offset, y: -offset))
                path.addLine(to: CGPoint(x: offset, y: -offset + length))
            case 2:
                path.move(to: CGPoint(x: offset, y: offset - length))
                path.addLine(to: CGPoint(x: offset, y: offset))
                path.addLine(to: CGPoint(x: offset - length, y: offset))
            case 3:
                path.move(to: CGPoint(x: -offset + length, y: offset))
                path.addLine(to: CGPoint(x: -offset, y: offset))
                path.addLine(to: CGPoint(x: -offset, y: offset - length))
            default: break
            }
        }
        .stroke(color, style: StrokeStyle(lineWidth: 4, lineCap: .round))
    }
}

// MARK: - QR Scanner UIKit

struct WebQRCodeScannerView: UIViewControllerRepresentable {
    @Binding var isScanning: Bool
    let onCodeScanned: (String) -> Void
    
    func makeUIViewController(context: Context) -> WebQRScannerViewController {
        let vc = WebQRScannerViewController()
        vc.onCodeScanned = onCodeScanned
        return vc
    }
    
    func updateUIViewController(_ uiViewController: WebQRScannerViewController, context: Context) {
        if isScanning { uiViewController.startScanning() } else { uiViewController.stopScanning() }
    }
}

class WebQRScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var captureSession: AVCaptureSession?
    var previewLayer: AVCaptureVideoPreviewLayer?
    var onCodeScanned: ((String) -> Void)?
    private var hasScanned = false
    
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        setupCamera()
    }
    
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        startScanning()
    }
    
    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        stopScanning()
    }
    
    private func setupCamera() {
        guard let videoCaptureDevice = AVCaptureDevice.default(for: .video) else {
            showCameraError()
            return
        }
        
        let videoInput: AVCaptureDeviceInput
        do {
            videoInput = try AVCaptureDeviceInput(device: videoCaptureDevice)
        } catch {
            showCameraError()
            return
        }
        
        captureSession = AVCaptureSession()
        
        guard captureSession!.canAddInput(videoInput) else {
            showCameraError()
            return
        }
        captureSession!.addInput(videoInput)
        
        let metadataOutput = AVCaptureMetadataOutput()
        
        guard captureSession!.canAddOutput(metadataOutput) else {
            showCameraError()
            return
        }
        captureSession!.addOutput(metadataOutput)
        metadataOutput.setMetadataObjectsDelegate(self, queue: .main)
        metadataOutput.metadataObjectTypes = [.qr]
        
        previewLayer = AVCaptureVideoPreviewLayer(session: captureSession!)
        previewLayer!.frame = view.layer.bounds
        previewLayer!.videoGravity = .resizeAspectFill
        view.layer.addSublayer(previewLayer!)
    }
    
    private func showCameraError() {
        let label = UILabel()
        label.text = "alert.camera_permission".localized
        label.textColor = .white
        label.textAlignment = .center
        label.frame = view.bounds
        view.addSubview(label)
    }
    
    func startScanning() {
        hasScanned = false
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            if self?.captureSession?.isRunning == false {
                self?.captureSession?.startRunning()
            }
        }
    }
    
    func stopScanning() {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            if self?.captureSession?.isRunning == true {
                self?.captureSession?.stopRunning()
            }
        }
    }
    
    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !hasScanned,
              let metadataObject = metadataObjects.first,
              let readableObject = metadataObject as? AVMetadataMachineReadableCodeObject,
              let stringValue = readableObject.stringValue else { return }
        
        let isOshiQR = stringValue.starts(with: "oshi://") ||
                       stringValue.starts(with: "oshi:web:") ||
                       (stringValue.count >= 32 && stringValue.count <= 128)
        
        guard isOshiQR else { return }
        
        hasScanned = true
        stopScanning()
        onCodeScanned?(stringValue)
    }
    
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.layer.bounds
    }
}

// MARK: - WebE2E (websession v2, end-to-end) — __WEBSESSION_E2E_2026_09_23__
//
// Phone side of `ServerPatches/websession_e2e/CLIENT_SPEC.md` §2–§3. The page builds a QR
// carrying its X25519 key `k` and a 16-byte secret `s` that never reaches the server; the
// phone answers with an EPHEMERAL X25519 key and a confirm tag, and from then on the server
// only relays opaque AES-256-GCM frames. Normative references: `test/phone_ref.js` (phone),
// `oshi/files/oshi-websession-v2.js` (page), byte vectors `test/vectors.json`, reproduced by
// `WebE2ESelfTest` (DEBUG, `-OSHISelfTestMultiDevice`).
//
// Lives in this file (not its own) because the app target is not folder-synced and the
// project file is shared with other work in progress.

nonisolated enum WebE2E {
    static let labelTH = "OSHI-WS2/th"
    static let labelB2P = "OSHI-WS2 b2p"
    static let labelP2B = "OSHI-WS2 p2b"
    static let labelConfirm = "OSHI-WS2 confirm"
    static let labelSAS = "OSHI-WS2 sas"
    static let labelPhoneConfirm = "OSHI-WS2 phone-confirm"
    /// Freshness window for an `ep` seen for the first time (§3 receiver rules).
    static let freshnessMs: Int64 = 5 * 60 * 1000
    /// Plaintext budget per frame. The server refuses `ct` above 512 KiB of base64
    /// (≈ 384 KiB of bytes); 360 KB leaves room for the GCM tag and JSON growth.
    static let maxFramePlaintext = 360 * 1024
    /// Media chunk size inside `media_chunk` frames (§4.4).
    static let mediaChunk = 192 * 1024
    /// Attachments above this travel as a `mediaUrl` placeholder, fetched via `media_get`.
    static let inlineMediaMax = 256 * 1024

    enum Failure: Error, Equatable {
        case invalidQR, badFrame, wrongSession, wrongDirection, replay, stale, auth, badEpoch
    }

    /// A parsed `oshi://web-auth?…` QR. `version == 1` means the page is an old build (or a
    /// browser without WebCrypto): token only, no keys.
    struct QR: Equatable {
        let token: String
        let version: Int
        let browserKey: Data?
        let secret: Data?
    }

    /// Standard base64 or base64url, padding optional.
    static func decodeB64Any(_ s: String) -> Data? {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        return Data(base64Encoded: t)
    }

    static func b64url(_ d: Data) -> String {
        d.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    /// Parse any QR the scanner hands us. Returns nil for an unusable code; a v2 QR whose
    /// `k`/`s` are not exactly 32/16 bytes is REJECTED (never silently treated as v1).
    static func parseQR(_ raw: String) -> QR? {
        let str = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if str.hasPrefix("oshi://web-auth") {
            guard let comps = URLComponents(string: str) else { return nil }
            let items = comps.queryItems ?? []
            func q(_ n: String) -> String? { items.first(where: { $0.name == n })?.value }
            guard let token = q("token"), !token.isEmpty else { return nil }
            guard q("v") == "2" else { return QR(token: token, version: 1, browserKey: nil, secret: nil) }
            guard let k = q("k").flatMap(decodeB64Any), k.count == 32,
                  let s = q("s").flatMap(decodeB64Any), s.count == 16 else { return nil }
            return QR(token: token, version: 2, browserKey: k, secret: s)
        }
        if str.hasPrefix("oshi:web:") {
            let t = String(str.dropFirst("oshi:web:".count))
            return t.isEmpty ? nil : QR(token: t, version: 1, browserKey: nil, secret: nil)
        }
        if str.count == 64 || str.count == 32 { return QR(token: str, version: 1, browserKey: nil, secret: nil) }
        return nil
    }

    /// Session keys for one paired browser. Stored per `sid` in the Keychain
    /// (AfterFirstUnlockThisDeviceOnly via `KeychainHelper`).
    struct Keys: Codable, Equatable {
        let b2p: Data
        let p2b: Data
        let sas: String
    }

    struct Derived: Equatable {
        let th: Data
        let keys: Keys
        let confirmKey: Data
        let confirm: Data
    }

    struct Pairing {
        let token: String
        let phoneKey: Data
        let confirm: Data
        let derived: Derived
        var sas: String { derived.keys.sas }
    }

    static func transcriptHash(token: String, browserKey: Data, phoneKey: Data) -> Data {
        var d = Data(labelTH.utf8); d.append(0); d.append(Data(token.utf8)); d.append(0)
        d.append(browserKey); d.append(phoneKey)
        return Data(SHA256.hash(data: d))
    }

    static func hkdf(_ shared: Data, salt: Data, label: String, th: Data, count: Int) -> Data {
        var info = Data(label.utf8); info.append(th)
        let key = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: shared), salt: salt,
                                         info: info, outputByteCount: count)
        return key.withUnsafeBytes { Data($0) }
    }

    static func derive(shared: Data, secret: Data, token: String, browserKey: Data, phoneKey: Data) -> Derived {
        let th = transcriptHash(token: token, browserKey: browserKey, phoneKey: phoneKey)
        let confirmKey = hkdf(shared, salt: secret, label: labelConfirm, th: th, count: 32)
        let sasBytes = [UInt8](hkdf(shared, salt: secret, label: labelSAS, th: th, count: 4))
        let n = (UInt32(sasBytes[0]) << 24) | (UInt32(sasBytes[1]) << 16) | (UInt32(sasBytes[2]) << 8) | UInt32(sasBytes[3])
        let sas = String(format: "%06u", n % 1_000_000)
        let mac = HMAC<SHA256>.authenticationCode(for: Data(labelPhoneConfirm.utf8), using: SymmetricKey(data: confirmKey))
        return Derived(th: th,
                       keys: Keys(b2p: hkdf(shared, salt: secret, label: labelB2P, th: th, count: 32),
                                  p2b: hkdf(shared, salt: secret, label: labelP2B, th: th, count: 32),
                                  sas: sas),
                       confirmKey: confirmKey,
                       confirm: Data(mac))
    }

    /// The phone scanning a v2 QR: fresh ephemeral X25519 key (never the identity key),
    /// derive, prove. `phonePrivate` is injectable for the vectors only.
    static func pair(_ qr: QR, phonePrivate: Curve25519.KeyAgreement.PrivateKey = .init()) throws -> Pairing {
        guard qr.version == 2, let b = qr.browserKey, let s = qr.secret else { throw Failure.invalidQR }
        let browserPub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: b)
        let secret = try phonePrivate.sharedSecretFromKeyAgreement(with: browserPub)
        let shared = secret.withUnsafeBytes { Data($0) }
        let p = phonePrivate.publicKey.rawRepresentation
        let d = derive(shared: shared, secret: s, token: qr.token, browserKey: b, phoneKey: p)
        return Pairing(token: qr.token, phoneKey: p, confirm: d.confirm, derived: d)
    }

    static func nonce(ep: Data, seq: UInt32) -> Data {
        var n = ep
        n.append(UInt8((seq >> 24) & 0xff)); n.append(UInt8((seq >> 16) & 0xff))
        n.append(UInt8((seq >> 8) & 0xff)); n.append(UInt8(seq & 0xff))
        return n
    }

    static func aad(sid: String, dir: String, ep: String, seq: UInt32) -> Data {
        Data("OSHI-WS2|\(sid)|\(dir)|\(ep)|\(seq)".utf8)
    }

    /// AES-256-GCM, ciphertext ‖ tag16 (NOT `.combined`, which prefixes the nonce).
    static func sealRaw(key: Data, sid: String, dir: String, ep: Data, seq: UInt32, plaintext: Data) throws -> Data {
        let box = try AES.GCM.seal(plaintext, using: SymmetricKey(data: key),
                                   nonce: AES.GCM.Nonce(data: nonce(ep: ep, seq: seq)),
                                   authenticating: aad(sid: sid, dir: dir, ep: ep.base64EncodedString(), seq: seq))
        return box.ciphertext + box.tag
    }

    static func openRaw(key: Data, sid: String, dir: String, ep: Data, seq: UInt32, ct: Data) throws -> Data {
        guard ct.count >= 16 else { throw Failure.auth }
        let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce(ep: ep, seq: seq)),
                                        ciphertext: ct.prefix(ct.count - 16), tag: ct.suffix(16))
        do {
            return try AES.GCM.open(box, using: SymmetricKey(data: key),
                                    authenticating: aad(sid: sid, dir: dir, ep: ep.base64EncodedString(), seq: seq))
        } catch { throw Failure.auth }
    }

    /// One paired browser, phone side: seals `p2b`, opens `b2p`. Thread-safe (the socket
    /// callback, the main thread and media tasks all send).
    nonisolated final class Channel: @unchecked Sendable {
        let sid: String
        let keys: Keys
        private let lock = NSLock()
        private var ep: Data
        private var seq: UInt32 = 0
        /// Highest accepted `seq` per browser `ep`, LRU-ordered (oldest first), ≤ 64 entries.
        private var recv: [(ep: String, seq: UInt32)] = []
        private let now: () -> Int64

        init(sid: String, keys: Keys, ep: Data? = nil, now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
            self.sid = sid
            self.keys = keys
            self.ep = ep ?? WebE2E.randomBytes(8)
            self.now = now
        }

        /// New sender instance (per socket connection, §3 `ep`).
        func newEpoch(_ ep: Data? = nil) {
            lock.lock(); defer { lock.unlock() }
            self.ep = ep ?? WebE2E.randomBytes(8); seq = 0
        }

        /// Seal already-serialised inner JSON bytes. Returns the frame as a JSON dictionary.
        func sealPlaintext(_ pt: Data) throws -> [String: Any] {
            lock.lock(); defer { lock.unlock() }
            if seq == UInt32.max - 1 { ep = WebE2E.randomBytes(8); seq = 0 }
            seq += 1
            let ct = try WebE2E.sealRaw(key: keys.p2b, sid: sid, dir: "p2b", ep: ep, seq: seq, plaintext: pt)
            return ["type": "e2e", "v": 2, "sid": sid, "dir": "p2b", "ep": ep.base64EncodedString(),
                    "seq": Int(seq), "ct": ct.base64EncodedString()]
        }

        /// Seal `{t, …fields, _ts}`.
        func seal(_ inner: [String: Any], ts: Int64? = nil) throws -> [String: Any] {
            var obj = inner
            obj["_ts"] = ts ?? now()
            let pt = try JSONSerialization.data(withJSONObject: obj, options: [.withoutEscapingSlashes])
            return try sealPlaintext(pt)
        }

        /// Open a `b2p` frame per the §3 receiver rules. Throws — and the caller drops the
        /// frame without displaying anything — on any failure.
        func open(_ f: [String: Any]) throws -> [String: Any] {
            guard f["type"] as? String == "e2e", (f["v"] as? Int) == 2 else { throw Failure.badFrame }
            guard f["sid"] as? String == sid else { throw Failure.wrongSession }
            guard f["dir"] as? String == "b2p" else { throw Failure.wrongDirection }
            guard let epStr = f["ep"] as? String, let seqNum = f["seq"] as? Int, seqNum >= 1, seqNum <= Int(UInt32.max),
                  let ctStr = f["ct"] as? String, let ct = Data(base64Encoded: ctStr) else { throw Failure.badFrame }
            guard let ep = Data(base64Encoded: epStr), ep.count == 8 else { throw Failure.badEpoch }
            let seq = UInt32(seqNum)
            lock.lock(); defer { lock.unlock() }
            let last = recv.first(where: { $0.ep == epStr })?.seq
            if let last, seq <= last { throw Failure.replay }
            let pt = try WebE2E.openRaw(key: keys.b2p, sid: sid, dir: "b2p", ep: ep, seq: seq, ct: ct)
            guard let inner = try JSONSerialization.jsonObject(with: pt) as? [String: Any] else { throw Failure.badFrame }
            if last == nil {
                let ts = (inner["_ts"] as? NSNumber)?.int64Value ?? 0
                guard abs(now() - ts) <= WebE2E.freshnessMs else { throw Failure.stale }
            }
            recv.removeAll { $0.ep == epStr }
            recv.append((epStr, seq))
            if recv.count > 64 { recv.removeFirst(recv.count - 64) }
            return inner
        }
    }

    static func randomBytes(_ n: Int) -> Data {
        var b = [UInt8](repeating: 0, count: n)
        _ = SecRandomCopyBytes(kSecRandomDefault, n, &b)
        return Data(b)
    }

    // MARK: Key storage (per sid, Keychain, this device only)

    private static let indexKey = "oshi.ws2.sids"
    private static func account(_ sid: String) -> String { "oshi.ws2.keys." + sid }

    static func storeKeys(_ keys: Keys, sid: String) {
        guard let data = try? JSONEncoder().encode(keys) else { return }
        try? KeychainHelper.save(key: account(sid), data: data)
        var ids = storedSids()
        if !ids.contains(sid) { ids.append(sid) }
        UserDefaults.standard.set(ids, forKey: indexKey)
    }

    static func loadKeys(sid: String) -> Keys? {
        guard let data = try? KeychainHelper.load(key: account(sid)) else { return nil }
        return try? JSONDecoder().decode(Keys.self, from: data)
    }

    static func deleteKeys(sid: String) {
        try? KeychainHelper.delete(key: account(sid))
        UserDefaults.standard.set(storedSids().filter { $0 != sid }, forKey: indexKey)
    }

    static func storedSids() -> [String] {
        UserDefaults.standard.stringArray(forKey: indexKey) ?? []
    }

    /// v1 plaintext type names → v2 inner names (§4.4). Everything else keeps its name.
    static func innerType(forV1 t: String) -> String {
        switch t {
        case "sync_conversations": return "conversations_sync"
        case "sync_messages": return "messages_sync"
        case "sync_groups": return "groups_sync"
        case "sync_group_messages": return "group_messages_sync"
        default: return t
        }
    }
}

// MARK: - DEBUG self-test: websession v2 vectors + live pairing (__WEBSESSION_E2E_2026_09_23__)
#if DEBUG
/// Run from `MultiDeviceSelfTest` (`-OSHISelfTestMultiDevice`). Reproduces EVERY output of
/// `ServerPatches/websession_e2e/test/vectors.json` with CryptoKit.
enum WebE2ESelfTest {
    static func vectors(_ log: MultiDeviceSelfTest.Log) {
        let hex = MultiDeviceSelfTest.hex, unhex = MultiDeviceSelfTest.unhex
        guard let bPriv = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: unhex("a8abababababababababababababababababababababababababababababab6b")),
              let pPriv = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: unhex("c8cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd4d")) else {
            log.check("ws2.vector.keys", false); return
        }
        let s = unhex("000102030405060708090a0b0c0d0e0f")
        let token = "5f1c0e9d3b2a41786655443322110099aabbccddeeff00112233445566778899"
        let sid = "0123456789abcdef0123456789abcdef0123456789abcdef"
        let B = bPriv.publicKey.rawRepresentation, P = pPriv.publicKey.rawRepresentation
        log.check("ws2.vector.browser_x25519_pub", hex(B) == "e3712d851a0e5d79b831c5e34ab22b41a198171de209b8b8faca23a11c624859")
        log.check("ws2.vector.phone_x25519_pub", hex(P) == "b5bea823d9c9ff576091c54b7c596c0ae296884f0e150290e88455d7fba6126f")
        let qrString = "oshi://web-auth?token=\(token)&v=2&k=\(WebE2E.b64url(B))&s=\(WebE2E.b64url(s))"
        log.check("ws2.vector.qr_string", qrString == "oshi://web-auth?token=5f1c0e9d3b2a41786655443322110099aabbccddeeff00112233445566778899&v=2&k=43EthRoOXXm4McXjSrIrQaGYFx3iCbi4-sojoRxiSFk&s=AAECAwQFBgcICQoLDA0ODw")
        guard let qr = WebE2E.parseQR(qrString), qr.version == 2, qr.browserKey == B, qr.secret == s, qr.token == token else {
            log.check("ws2.vector.parseQR", false); return
        }
        log.check("ws2.vector.parseQR", true)
        let shared = (try? pPriv.sharedSecretFromKeyAgreement(with: bPriv.publicKey))?.withUnsafeBytes { Data($0) } ?? Data()
        log.check("ws2.vector.shared_x25519", hex(shared) == "235101b705734aae8d4c2d9d0f1baf90bbb2a8c233d831a80d43815bb47ead10")
        guard let pairing = try? WebE2E.pair(qr, phonePrivate: pPriv) else { log.check("ws2.vector.pair", false); return }
        let d = pairing.derived
        log.check("ws2.vector.transcript_hash_th", hex(d.th) == "39523158113e5c066f93cd7bb75029000349d532475a767680e63825beec3d67")
        log.check("ws2.vector.key_b2p", hex(d.keys.b2p) == "8c3e8087ed1b6d7d1c1a58b65f951275d5db71495b629681486dee60319f5ef8")
        log.check("ws2.vector.key_p2b", hex(d.keys.p2b) == "00a61460459156f234ce24dde653940e1473ee16cedbb1d6bad9a1e9d88ee7aa")
        log.check("ws2.vector.key_confirm", hex(d.confirmKey) == "31c3e5b134ece5ada3d222669e1429c1fbc89e3c0fa91046d0656329a8e98433")
        log.check("ws2.vector.confirm_tag", hex(d.confirm) == "de82a8ac49b83ef91227b99ddc52f9a65f0873fe17850a4c323266cd7aad3a88")
        log.check("ws2.vector.confirm_b64", pairing.confirm.base64EncodedString() == "3oKorEm4PvkSJ7md3FL5pl8Ic/4XhQpMMjJmzXqtOog=")
        log.check("ws2.vector.sas", pairing.sas == "035469")
        let ep = unhex("1112131415161718")
        log.check("ws2.vector.frame_nonce", hex(WebE2E.nonce(ep: ep, seq: 1)) == "111213141516171800000001")
        log.check("ws2.vector.frame_aad_utf8", String(decoding: WebE2E.aad(sid: sid, dir: "p2b", ep: ep.base64EncodedString(), seq: 1), as: UTF8.self)
                  == "OSHI-WS2|0123456789abcdef0123456789abcdef0123456789abcdef|p2b|ERITFBUWFxg=|1")
        let pt = Data("{\"t\":\"new_message\",\"contactPublicKey\":\"QUJD\",\"message\":{\"id\":\"m1\",\"content\":\"Bonjour\"},\"_ts\":1790000000000}".utf8)
        let ch = WebE2E.Channel(sid: sid, keys: d.keys, ep: ep, now: { 1790000000000 })
        let frame = try? ch.sealPlaintext(pt)
        log.check("ws2.vector.frame_p2b_seq1.ct", (frame?["ct"] as? String) == "3PsamvMnbOkenTlj7cyyjtcapQXtUFK399S/A5SoF/W3hBdzy75e2AEqEM1Dj0FVmHMiXneHqGbrRbWVtu9QlkKxm4o5qkIcA6LrPCRPLumUXvkleODIIcGTQNv8nlKuiri9ZN3PJermg7x3Rhby5qXLjQW+viiTROp3"
                  && (frame?["ep"] as? String) == "ERITFBUWFxg=" && (frame?["seq"] as? Int) == 1 && (frame?["dir"] as? String) == "p2b")
        // Receiver rules (b2p), with the browser's key: open, replay, wrong dir/sid, stale, tamper.
        func browserFrame(seq: UInt32, ts: Int64, epB: Data = unhex("0102030405060708"), sidOverride: String? = nil, dir: String = "b2p") -> [String: Any] {
            let body = try! JSONSerialization.data(withJSONObject: ["t": "send_message", "content": "réponse 🌍", "_ts": ts])
            let ct = try! WebE2E.sealRaw(key: d.keys.b2p, sid: sid, dir: "b2p", ep: epB, seq: seq, plaintext: body)
            return ["type": "e2e", "v": 2, "sid": sidOverride ?? sid, "dir": dir, "ep": epB.base64EncodedString(), "seq": Int(seq), "ct": ct.base64EncodedString()]
        }
        let f1 = browserFrame(seq: 1, ts: 1790000000000)
        log.check("ws2.channel.open", ((try? ch.open(f1))?["content"] as? String) == "réponse 🌍")
        log.check("ws2.channel.replayRefused", (try? ch.open(f1)) == nil)
        log.check("ws2.channel.wrongSessionRefused", (try? ch.open(browserFrame(seq: 2, ts: 1790000000000, sidOverride: "x"))) == nil)
        log.check("ws2.channel.wrongDirectionRefused", (try? ch.open(browserFrame(seq: 3, ts: 1790000000000, dir: "p2b"))) == nil)
        log.check("ws2.channel.staleNewEpochRefused", (try? ch.open(browserFrame(seq: 1, ts: 1790000000000 - 6 * 60 * 1000, epB: unhex("0909090909090909")))) == nil)
        var tampered = browserFrame(seq: 4, ts: 1790000000000)
        if var ct = Data(base64Encoded: tampered["ct"] as! String) { ct[0] ^= 1; tampered["ct"] = ct.base64EncodedString() }
        log.check("ws2.channel.tamperRefused", (try? ch.open(tampered)) == nil)
        log.check("ws2.channel.laterSeqAccepted", (try? ch.open(browserFrame(seq: 5, ts: 1790000000000))) != nil)
        // QR parsing rules.
        log.check("ws2.qr.v1PageIsV1", WebE2E.parseQR("oshi://web-auth?token=\(token)")?.version == 1)
        log.check("ws2.qr.badKeyLengthRejected", WebE2E.parseQR("oshi://web-auth?token=\(token)&v=2&k=AAAA&s=\(WebE2E.b64url(s))") == nil)
        log.check("ws2.split.messagesFitFrames", {
            let big = String(repeating: "x", count: 100_000)
            let inner: [String: Any] = ["t": "messages_sync", "contactPublicKey": "K", "messages": (0..<10).map { ["id": "\($0)", "content": big] }]
            let parts = WebSessionManager.splitForFrames(inner)
            let sizes = parts.map { (try? JSONSerialization.data(withJSONObject: $0))?.count ?? .max }
            let total = parts.reduce(0) { $0 + (($1["messages"] as? [Any])?.count ?? 0) }
            return parts.count > 1 && total == 10 && sizes.allSatisfy { $0 <= WebE2E.maxFramePlaintext }
        }())
    }

    /// Pair the app with the browser whose QR the harness passed, then stay up and serve it.
    /// The harness (node, the real page module) asserts the browser side; here we check that
    /// the pairing succeeded and that the browser's message reached the conversation.
    static func live(_ log: MultiDeviceSelfTest.Log, qr: String) async {
        let r = await WebSessionManager.shared.validateQRCode(qr)
        let sas = await MainActor.run { WebSessionManager.shared.lastPairingSAS ?? "" }
        log.check("ws2.live.pair", r.success, r.message)
        log.add("INFO ws2.live.sas=\(sas)")
        let expect = ProcessInfo.processInfo.environment["OSHI_SELFTEST_WS_EXPECT"] ?? ""
        guard !expect.isEmpty else { return }
        for _ in 0..<90 {
            let found = await MainActor.run { MessageManager.shared?.messages.contains(where: { ($0.plaintextContent ?? "") == expect }) ?? false }
            if found { log.check("ws2.live.webMessageSentFromPhone", true); return }
            try? await Task.sleep(nanoseconds: 1_000_000_000)
        }
        log.check("ws2.live.webMessageSentFromPhone", false)
    }
}
#endif
