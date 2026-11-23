//
//  WatchBridgeService.swift
//  OSHI (iPhone side)
//
//  __APPLE_WATCH_2027__ The phone half of the Apple Watch bridge.
//
//  The watch asks (`WatchRequest`, see WatchBridgeProtocol.swift), this answers
//  from the phone's own stores and, for SEND and REACT, goes through exactly the
//  code paths the chat screens use:
//
//    * 1:1 text      MessageManager.sendMessage(content:recipientAddress:identityManager:meshManager:)
//                    (+ `WatchConnectivityManager.contentWithWatchReply`, the one
//                    reply envelope, when the watch quotes a bubble)
//    * group text    MessageGroupManager.sendMessage(to:content:senderPublicKey:)
//    * 1:1 reaction  ChatView.sendReaction  — toggleReaction + ReactionPayload → sendMessage
//    * group react.  GroupChatView.reactToGroupMessage — same, → MessageGroupManager.sendMessage
//
//  Nothing here encrypts, signs or speaks to a server itself.
//
//  GATES, in this order, before ANY store is read:
//    1. "Smartwatch" (Settings › Linked devices) is OFF  → `.disabled`  (the default)
//    2. OSHI's app lock is engaged                       → `.locked`
//    3. no identity / managers yet (cold background wake) → `.unavailable`
//  Disabled is checked first on purpose: while the user has not opted in, the
//  watch must not even learn whether the app is locked.
//

import Foundation
import WatchConnectivity

// MARK: - Settings

/// The opt-in. OFF by default — same model as Android's Wear bridge.
enum WatchBridgeSettings {
    static let enabledKey = "smartwatchBridgeEnabled"
    static let defaultEnabled = false

    static var isEnabled: Bool {
        UserDefaults.standard.object(forKey: enabledKey) as? Bool ?? defaultEnabled
    }

    static func setEnabled(_ on: Bool) {
        UserDefaults.standard.set(on, forKey: enabledKey)
        WatchBridgeService.requestSnapshotPush()
    }
}

/// OSHI's own app lock (Face ID / passcode screen), readable outside the
/// SwiftUI `App` that owns it. The `App` reports every change of its
/// `isUnlocked` state here (SecureWeb3MessengerApp).
///
/// A process launched in the background by WatchConnectivity starts LOCKED
/// when the lock is enabled: `isUnlocked` is false until someone passes the
/// lock screen in that process — exactly the phone's own behaviour.
enum AppLockState {
    static let biometricKey = "biometricEnabled"

    private(set) static var isUnlocked = false

    static func setUnlocked(_ unlocked: Bool) {
        guard unlocked != isUnlocked else { return }
        isUnlocked = unlocked
        WatchBridgeService.requestSnapshotPush()
    }

    static var isLockEnabled: Bool { UserDefaults.standard.bool(forKey: biometricKey) }

    static var isLocked: Bool { isLockEnabled && !isUnlocked }
}

// MARK: - Service

@MainActor
final class WatchBridgeService {
    static let shared = WatchBridgeService()
    private init() {}

    /// Pure gate, unit-tested. nil = go ahead.
    nonisolated static func gate(enabled: Bool, locked: Bool, ready: Bool) -> WatchStatus? {
        if !enabled { return .disabled }
        if locked { return .locked }
        if !ready { return .unavailable }
        return nil
    }

    /// Entry point from `WatchConnectivityManager`. Returns false when the
    /// dictionary is not a bridge message (legacy `type` messages).
    nonisolated func handle(_ message: [String: Any], reply: (([String: Any]) -> Void)?) -> Bool {
        guard message[WatchBridge.payloadKey] != nil else { return false }
        // WCSession calls on its own queue; the stores live on main.
        DispatchQueue.main.async {
            MainActor.assumeIsolated {
                let response = WatchBridgeService.shared.respond(to: message)
                reply?(response.encode())
            }
        }
        return true
    }

    /// Main thread. Separate from `handle` so tests can drive the gates.
    func respond(to message: [String: Any]) -> WatchResponse {
        guard let req = WatchRequest.decode(message) else {
            return .status("", .invalid)
        }
        let ready = MessageManager.shared != nil
            && MessageManager.sharedIdentityManager != nil
            && MessageManager.sharedMeshManager != nil
        if let refused = Self.gate(enabled: WatchBridgeSettings.isEnabled,
                                   locked: AppLockState.isLocked,
                                   ready: ready) {
            return .status(req.rid, refused)
        }
        guard req.isWellFormed else { return .status(req.rid, .invalid) }

        switch req.op {
        case .list: return list(req)
        case .thread: return thread(req)
        case .send: return send(req)
        case .react: return react(req)
        case .markRead: return markRead(req)
        }
    }

    // MARK: List

    private var myKey: String { MessageManager.sharedIdentityManager?.publicKey ?? "" }

    private func same(_ a: String, _ b: String) -> Bool {
        a == b || MessageManager.canonicalIdentity(a) == MessageManager.canonicalIdentity(b)
    }

    private func isWelcome(_ key: String) -> Bool {
        same(key, WelcomeMessageManager.systemSenderKey)
    }

    /// Every conversation, 1:1 and groups, newest first.
    func allConversations() -> [WatchConversation] {
        guard let manager = MessageManager.shared else { return [] }
        let me = myKey
        var out: [WatchConversation] = []

        for conv in manager.conversations {
            let pk = conv.participantPublicKey
            let last = conv.lastMessage
            let (text, media, vo) = Self.render(last)
            var c = WatchConversation(
                kind: .direct, cid: pk,
                name: MessageManager.lookupContactDisplayName(pk) ?? (String(pk.prefix(8)) + "…"),
                preview: text, unread: conv.unreadCount,
                ts: last.timestamp.timeIntervalSince1970, akey: pk)
            c.mine = !me.isEmpty && same(last.senderPublicKey, me)
            c.ro = isWelcome(pk)
            c.pmedia = media
            c.pvo = vo
            out.append(c)
        }

        let groups = MessageGroupManager.shared
        let gme = groups.currentUserPublicKey ?? me
        for g in groups.groups {
            let messages = (groups.groupMessages[g.id] ?? []).filter { $0.systemMessageType == nil }
            let last = messages.max(by: { $0.timestamp < $1.timestamp })
            var preview = ""
            var media: WatchMedia? = nil
            var mine = false
            if let last {
                let r = Self.render(last)
                preview = r.text
                media = r.media
                mine = same(last.senderPublicKey, gme)
                if !mine {
                    let who = Self.memberName(last.senderPublicKey, in: g)
                    preview = preview.isEmpty ? preview : "\(who): \(preview)"
                    if preview.isEmpty, media != nil { preview = "\(who): " }
                }
            }
            var c = WatchConversation(
                kind: .group, cid: g.id.uuidString,
                name: g.name, preview: preview,
                unread: groups.getUnreadCount(for: g.id),
                ts: (last?.timestamp ?? g.lastActivity).timeIntervalSince1970,
                akey: g.id.uuidString)
            c.mine = mine
            c.pmedia = media
            out.append(c)
        }
        return out.sorted { $0.ts > $1.ts }
    }

    private func list(_ req: WatchRequest) -> WatchResponse {
        var r = WatchResponse(rid: req.rid, status: .ok)
        r.convs = Array(allConversations().prefix(WatchBridge.maxConversations))
        return r
    }

    // MARK: Thread

    private func thread(_ req: WatchRequest) -> WatchResponse {
        guard let cid = req.cid, let kind = req.kind else { return .status(req.rid, .invalid) }
        let reactions = MessageReactionManager.shared
        switch kind {
        case .direct:
            guard let manager = MessageManager.shared else { return .status(req.rid, .unavailable) }
            guard manager.conversations.contains(where: { same($0.participantPublicKey, cid) }) else {
                return .status(req.rid, .notFound)
            }
            let me = myKey
            let msgs = manager.getMessages(for: cid, limit: 60)
                .filter { !$0.isDeletedForEveryone }
                .compactMap { m -> WatchMessage? in
                    let (text, media, vo) = Self.render(m)
                    if text.isEmpty && media == nil && !vo { return nil }
                    var w = WatchMessage(mid: m.id, mine: same(m.senderPublicKey, me),
                                         text: text, ts: m.timestamp.timeIntervalSince1970)
                    w.media = media
                    w.vo = vo
                    w.reacts = Self.distinctReactions(reactions.getReactions(for: m.id).map { $0.emoji.rawValue })
                    w.myr = reactions.getUserReaction(messageId: m.id, senderPublicKey: me)?.rawValue
                    return w
                }
            var r = WatchResponse(rid: req.rid, status: .ok)
            r.msgs = Array(msgs.suffix(WatchBridge.maxThreadMessages))
            r.title = MessageManager.lookupContactDisplayName(cid) ?? (String(cid.prefix(8)) + "…")
            r.ro = isWelcome(cid)
            return r

        case .group:
            let groups = MessageGroupManager.shared
            guard let gid = UUID(uuidString: cid), let g = groups.getGroup(byId: gid) else {
                return .status(req.rid, .notFound)
            }
            let me = groups.currentUserPublicKey ?? myKey
            let all = (groups.groupMessages[gid] ?? [])
                .filter { $0.systemMessageType == nil }
                .sorted { $0.timestamp < $1.timestamp }
            let msgs = all.suffix(60).compactMap { m -> WatchMessage? in
                let (text, media, _) = Self.render(m)
                if text.isEmpty && media == nil { return nil }
                let mine = same(m.senderPublicKey, me)
                let id = m.id.uuidString
                var w = WatchMessage(mid: id, mine: mine, text: text, ts: m.timestamp.timeIntervalSince1970)
                if !mine {
                    w.sender = Self.memberName(m.senderPublicKey, in: g)
                    w.skey = m.senderPublicKey
                }
                w.media = media
                w.reacts = Self.distinctReactions(reactions.getReactions(for: id).map { $0.emoji.rawValue })
                w.myr = reactions.getUserReaction(messageId: id, senderPublicKey: me)?.rawValue
                return w
            }
            var r = WatchResponse(rid: req.rid, status: .ok)
            r.msgs = Array(msgs.suffix(WatchBridge.maxThreadMessages))
            r.title = g.name
            r.ro = false
            return r
        }
    }

    // MARK: Send

    private func send(_ req: WatchRequest) -> WatchResponse {
        guard let cid = req.cid, let kind = req.kind, let raw = req.text else { return .status(req.rid, .invalid) }
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        switch kind {
        case .direct:
            guard let manager = MessageManager.shared,
                  let identity = MessageManager.sharedIdentityManager,
                  let mesh = MessageManager.sharedMeshManager else { return .status(req.rid, .unavailable) }
            if isWelcome(cid) { return .status(req.rid, .readOnly) }
            guard manager.conversations.contains(where: { same($0.participantPublicKey, cid) }) else {
                return .status(req.rid, .notFound)
            }
            // A quoted bubble rides in `mid`: the SAME reply envelope the phone's
            // swipe-to-reply builds, rebuilt from the phone's own copy.
            let content = WatchConnectivityManager.contentWithWatchReply(
                content: text, replyToMessageId: req.mid, recipientKey: cid, manager: manager)
            let outcome = manager.sendMessage(content: content, recipientAddress: cid,
                                              identityManager: identity, meshManager: mesh)
            return .status(req.rid, outcome.didSend ? .ok : .error)

        case .group:
            let groups = MessageGroupManager.shared
            guard let gid = UUID(uuidString: cid), groups.getGroup(byId: gid) != nil else {
                return .status(req.rid, .notFound)
            }
            let me = groups.currentUserPublicKey ?? myKey
            let outcome = groups.sendMessage(to: gid, content: text, senderPublicKey: me)
            return .status(req.rid, outcome.didSend ? .ok : .error)
        }
    }

    // MARK: React

    private func react(_ req: WatchRequest) -> WatchResponse {
        guard let cid = req.cid, let kind = req.kind, let mid = req.mid,
              let raw = req.emoji, WatchBridge.quickReactions.contains(raw),
              let emoji = ReactionEmoji(rawValue: raw) else { return .status(req.rid, .invalid) }
        let rm = MessageReactionManager.shared

        switch kind {
        case .direct:
            guard let manager = MessageManager.shared,
                  let identity = MessageManager.sharedIdentityManager,
                  let mesh = MessageManager.sharedMeshManager else { return .status(req.rid, .unavailable) }
            if isWelcome(cid) { return .status(req.rid, .readOnly) }
            guard let message = manager.getMessages(for: cid, limit: 500).first(where: { $0.id == mid }) else {
                return .status(req.rid, .notFound)
            }
            // ChatView.sendReaction, step for step.
            let me = identity.publicKey
            let isRemoving = rm.hasUserReacted(messageId: message.id, senderPublicKey: me, emoji: emoji)
            rm.toggleReaction(to: message.id, emoji: emoji, senderPublicKey: me, senderName: "You")
            let partnerKey = same(message.senderPublicKey, me) ? message.recipientPublicKey : message.senderPublicKey
            let payload = ReactionPayload(messageId: message.id, emoji: emoji, senderPublicKey: me,
                                          senderName: "You", timestamp: Date(),
                                          action: isRemoving ? .remove : .add)
            let outcome = manager.sendMessage(content: payload.toMessageString(), recipientAddress: partnerKey,
                                              identityManager: identity, meshManager: mesh)
            return .status(req.rid, outcome.didSend ? .ok : .error)

        case .group:
            let groups = MessageGroupManager.shared
            guard let gid = UUID(uuidString: cid), let g = groups.getGroup(byId: gid),
                  (groups.groupMessages[gid] ?? []).contains(where: { $0.id.uuidString == mid }) else {
                return .status(req.rid, .notFound)
            }
            // GroupChatView.reactToGroupMessage, step for step.
            let me = groups.currentUserPublicKey ?? myKey
            let isRemoving = rm.hasUserReacted(messageId: mid, senderPublicKey: me, emoji: emoji)
            rm.toggleReaction(to: mid, emoji: emoji, senderPublicKey: me, senderName: "you".localized)
            let payload = ReactionPayload(messageId: mid, emoji: emoji, senderPublicKey: me,
                                          senderName: "you".localized, timestamp: Date(),
                                          action: isRemoving ? .remove : .add)
            let outcome = groups.sendMessage(to: g.id, content: payload.toMessageString(), senderPublicKey: me)
            return .status(req.rid, outcome.didSend ? .ok : .error)
        }
    }

    // MARK: Read

    private func markRead(_ req: WatchRequest) -> WatchResponse {
        guard let cid = req.cid, let kind = req.kind else { return .status(req.rid, .invalid) }
        switch kind {
        case .direct:
            MessageManager.shared?.markConversationAsRead(participantPublicKey: cid)
        case .group:
            guard let gid = UUID(uuidString: cid) else { return .status(req.rid, .notFound) }
            MessageGroupManager.shared.markGroupAsRead(gid)
        }
        return .status(req.rid, .ok)
    }

    // MARK: Rendering (what crosses — never media bytes, never view-once content)

    static func media(for type: MediaManager.MediaType?, hasAttachment: Bool) -> WatchMedia? {
        switch type {
        case .image?: return .photo
        case .video?: return .video
        case .audio?: return .voice
        case .document?: return .file
        case .contact?: return .contact
        case .gif?: return .gif
        case nil: return hasAttachment ? .file : nil
        }
    }

    static func media(for type: MediaType?) -> WatchMedia? {
        switch type {
        case .photo?: return .photo
        case .video?: return .video
        case .audio?: return .voice
        case .document?: return .file
        case .contact?: return .contact
        case nil: return nil
        }
    }

    /// Text / media kind / view-once for a 1:1 message. A view-once message
    /// sends NOTHING of its content, not even its caption.
    static func render(_ m: SecureMessage) -> (text: String, media: WatchMedia?, vo: Bool) {
        let media = media(for: m.mediaType, hasAttachment: m.mediaAttachment != nil)
        if m.isViewOnce { return ("", media, true) }
        let raw = m.editedContent ?? m.plaintextContent ?? ""
        let text = raw.isEmpty ? "" : OSHIControlPayload.previewText(for: raw)
        return (text.trimmingCharacters(in: .whitespacesAndNewlines), media, false)
    }

    static func render(_ m: GroupMessage) -> (text: String, media: WatchMedia?, vo: Bool) {
        let media = media(for: m.mediaType)
        var raw = m.editedContent ?? m.plaintextContent ?? ""
        if raw.isEmpty, media == nil { raw = String(data: m.encryptedContent, encoding: .utf8) ?? "" }
        let text = raw.isEmpty ? "" : OSHIControlPayload.previewText(for: raw)
        return (text.trimmingCharacters(in: .whitespacesAndNewlines), media, false)
    }

    static func distinctReactions(_ emojis: [String]) -> [String] {
        var counts: [String: Int] = [:]
        var order: [String] = []
        for e in emojis {
            if counts[e] == nil { order.append(e) }
            counts[e, default: 0] += 1
        }
        return Array(order.sorted { counts[$0]! > counts[$1]! }.prefix(3))
    }

    static func memberName(_ key: String, in group: MessageGroup) -> String {
        if let local = MessageManager.lookupContactDisplayName(key), !local.isEmpty { return local }
        if let alias = group.members.first(where: { $0.publicKey == key })?.alias, !alias.isEmpty { return alias }
        return String(key.prefix(6)) + "…"
    }

    // MARK: - Snapshot for the watch widget (count + three NAMES, never text)

    private var pendingPush: DispatchWorkItem?
    private var lastPushed: WatchBridgeSnapshot?

    /// Debounced: a burst of incoming messages costs one context update.
    /// Callable from any thread.
    nonisolated static func scheduleSnapshotPush() {
        DispatchQueue.main.async {
            MainActor.assumeIsolated {
                let me = WatchBridgeService.shared
                me.pendingPush?.cancel()
                let work = DispatchWorkItem {
                    MainActor.assumeIsolated { WatchBridgeService.shared.pushSnapshotNow() }
                }
                me.pendingPush = work
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.0, execute: work)
            }
        }
    }

    /// Immediate (settings / lock changes, session activation). Any thread.
    nonisolated static func requestSnapshotPush() {
        DispatchQueue.main.async {
            MainActor.assumeIsolated { WatchBridgeService.shared.pushSnapshotNow() }
        }
    }

    func currentSnapshot() -> WatchBridgeSnapshot {
        let ready = MessageManager.shared != nil && MessageManager.sharedIdentityManager != nil
        if let refused = Self.gate(enabled: WatchBridgeSettings.isEnabled,
                                   locked: AppLockState.isLocked, ready: ready) {
            return .make(status: refused, unread: 0, names: [])
        }
        let convs = allConversations()
        let unread = convs.reduce(0) { $0 + $1.unread }
        let names = convs
            .sorted { ($0.unread > 0 ? 1 : 0, $0.ts) > ($1.unread > 0 ? 1 : 0, $1.ts) }
            .prefix(WatchBridge.maxSnapshotNames)
            .map { $0.name }
        return .make(status: .ok, unread: unread, names: Array(names))
    }

    func pushSnapshotNow() {
        guard WCSession.isSupported() else { return }
        let session = WCSession.default
        guard session.activationState == .activated, session.isPaired, session.isWatchAppInstalled else { return }
        let snap = currentSnapshot()
        if let last = lastPushed, last.status == snap.status, last.unread == snap.unread, last.names == snap.names {
            return
        }
        do {
            try session.updateApplicationContext(snap.encode())
            lastPushed = snap
        } catch {
            OshiLog.ui.info("⌚ WatchBridge: snapshot not pushed: \(error.localizedDescription)")
        }
    }
}
