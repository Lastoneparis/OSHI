//
//  WatchBridgeProtocolTests.swift
//  OSHITests
//
//  __APPLE_WATCH_2027__ The iPhone <-> Apple Watch wire format
//  (OSHI/WatchBridgeProtocol.swift, compiled into both apps) and the phone's
//  gates (OSHI/WatchBridgeService.swift): opt-in OFF by default, app lock,
//  reaction allow-list, payload size, and nothing but names + a count in the
//  widget snapshot.
//

import XCTest
@testable import OSHI

@MainActor
final class WatchBridgeProtocolTests: XCTestCase {

    // MARK: Requests

    func testRequestRoundTrip() {
        let req = WatchRequest(rid: "r1", op: .send, kind: .group, cid: "6f1c2b9e-3a44-4d1f-9b0e-1d2c3e4f5a6b",
                               text: "On my way", mid: "m-42")
        let decoded = WatchRequest.decode(req.encode())
        XCTAssertEqual(decoded, req)
        XCTAssertTrue(decoded?.isWellFormed ?? false)
    }

    func testDecodeRejectsGarbageAndOtherVersions() {
        XCTAssertNil(WatchRequest.decode([:]))
        XCTAssertNil(WatchRequest.decode(["type": "requestConversations"]))          // legacy message
        XCTAssertNil(WatchRequest.decode([WatchBridge.payloadKey: Data("{not json".utf8)]))
        var future = WatchRequest(rid: "r", op: .list)
        future.v = WatchBridge.version + 1
        XCTAssertNil(WatchRequest.decode(future.encode()))
        XCTAssertNil(WatchRequest.decode([WatchBridge.payloadKey: Data(count: WatchBridge.maxPayloadBytes + 1)]))
    }

    func testUnknownOpIsRejected() {
        let json = #"{"v":1,"rid":"x","op":"wipe"}"#
        XCTAssertNil(WatchRequest.decode([WatchBridge.payloadKey: Data(json.utf8)]))
    }

    func testWellFormedShapes() {
        XCTAssertTrue(WatchRequest(rid: "a", op: .list).isWellFormed)
        XCTAssertFalse(WatchRequest(rid: "", op: .list).isWellFormed)
        XCTAssertFalse(WatchRequest(rid: String(repeating: "x", count: 65), op: .list).isWellFormed)
        XCTAssertFalse(WatchRequest(rid: "a", op: .thread).isWellFormed, "thread needs kind + cid")
        XCTAssertTrue(WatchRequest(rid: "a", op: .thread, kind: .direct, cid: "k").isWellFormed)
        XCTAssertTrue(WatchRequest(rid: "a", op: .markRead, kind: .group, cid: "g").isWellFormed)
        XCTAssertFalse(WatchRequest(rid: "a", op: .send, kind: .direct, cid: "k", text: "   \n").isWellFormed,
                       "blank text is not a message")
        XCTAssertFalse(WatchRequest(rid: "a", op: .send, kind: .direct, cid: "k",
                                    text: String(repeating: "a", count: WatchBridge.maxSendChars + 1)).isWellFormed)
        XCTAssertTrue(WatchRequest(rid: "a", op: .send, kind: .direct, cid: "k",
                                   text: String(repeating: "a", count: WatchBridge.maxSendChars)).isWellFormed)
        XCTAssertFalse(WatchRequest(rid: "a", op: .send, kind: .direct, cid: "", text: "hi").isWellFormed)
    }

    // MARK: Reactions

    func testReactionAllowListIsExactlyTheQuickSet() {
        for e in WatchBridge.quickReactions {
            XCTAssertTrue(WatchRequest(rid: "a", op: .react, kind: .direct, cid: "k", mid: "m", emoji: e).isWellFormed, e)
        }
        // Lookalikes and anything else are refused.
        for e in ["\u{2B55}\u{FE0F}", "\u{2764}", "\u{1F62D}", "\u{1F595}", "😡", "", "👍👍", "hello"] {
            XCTAssertFalse(WatchRequest(rid: "a", op: .react, kind: .direct, cid: "k", mid: "m", emoji: e).isWellFormed, e)
        }
        XCTAssertFalse(WatchRequest(rid: "a", op: .react, kind: .direct, cid: "k", emoji: "👍").isWellFormed,
                       "a reaction needs a message id")
    }

    /// Byte for byte the phone's long-press bar (`[.oshiO] + MascotWirePolicy.quickBar`)
    /// — and therefore Android's `WearProtocol.QUICK_REACTIONS`.
    func testQuickReactionsMatchThePhoneBar() {
        XCTAssertEqual(WatchBridge.quickReactions, ReactionEmoji.quickReactions.map(\.rawValue))
        XCTAssertEqual(WatchBridge.quickReactions.map { Array($0.unicodeScalars).map(\.value) },
                       [[0x2B55], [0x2764, 0xFE0F], [0x1F602], [0x1F622], [0x1F44D], [0x1F62E], [0x1F525]])
        for e in WatchBridge.quickReactions {
            XCTAssertNotNil(ReactionEmoji(rawValue: e), "the phone must be able to send \(e)")
        }
    }

    // MARK: Responses

    func testResponseRoundTripAndUnknownStatus() {
        var r = WatchResponse(rid: "r", status: .ok)
        var c = WatchConversation(kind: .direct, cid: "k", name: "Léa", preview: "salut", unread: 2, ts: 10, akey: "k")
        c.pmedia = .photo
        r.convs = [c]
        var m = WatchMessage(mid: "1", mine: false, text: "", ts: 3)
        m.vo = true
        m.reacts = ["👍"]
        r.msgs = [m]
        XCTAssertEqual(WatchResponse.decode(r.encode()), r)

        let unknown = #"{"v":1,"rid":"r","status":"hacked","convs":[],"msgs":[]}"#
        XCTAssertEqual(WatchResponse.decode([WatchBridge.payloadKey: Data(unknown.utf8)])?.status, .error,
                       "an unknown status must never read as ok")
    }

    func testResponseFitsTheWCSessionLimitAndKeepsTheNewest() {
        var r = WatchResponse(rid: "r", status: .ok)
        r.msgs = (0..<200).map { i in
            WatchMessage(mid: "m\(i)", mine: i % 2 == 0, text: String(repeating: "é😀", count: 2_000), ts: Double(i))
        }
        let encoded = r.encode()
        let data = encoded[WatchBridge.payloadKey] as? Data
        XCTAssertNotNil(data)
        XCTAssertLessThanOrEqual(data!.count, WatchBridge.maxPayloadBytes)
        let back = WatchResponse.decode(encoded)!
        XCTAssertLessThanOrEqual(back.msgs.count, WatchBridge.maxThreadMessages)
        XCTAssertEqual(back.msgs.last?.mid, "m199", "the newest bubble survives trimming")
        XCTAssertLessThanOrEqual(back.msgs.last!.text.count, WatchBridge.maxBubbleChars)
    }

    func testClipNeverSplitsAnEmoji() {
        let s = String(repeating: "👨‍👩‍👧", count: 10)
        let clipped = WatchBridge.clip(s, 5)
        XCTAssertEqual(clipped.count, 5)
        XCTAssertTrue(clipped.hasSuffix("…"))
        XCTAssertEqual(WatchBridge.clip("short", 64), "short")
    }

    // MARK: Snapshot (widget)

    func testSnapshotCarriesNamesOnlyWhenOk() {
        let ok = WatchBridgeSnapshot.make(status: .ok, unread: 9, names: ["A", "B", "C", "D"])
        XCTAssertEqual(ok.names, ["A", "B", "C"])
        XCTAssertEqual(ok.unread, 9)
        for s in [WatchStatus.disabled, .locked, .unavailable, .error] {
            let snap = WatchBridgeSnapshot.make(status: s, unread: 9, names: ["A"])
            XCTAssertTrue(snap.names.isEmpty, "\(s) must not carry names")
            XCTAssertEqual(snap.unread, 0, "\(s) must not carry a count")
        }
        // Decoding re-applies the rule, whatever the wire said.
        let forged = #"{"v":1,"status":"locked","unread":5,"names":["Léa"],"at":1}"#
        let back = WatchBridgeSnapshot.decode([WatchBridge.snapshotKey: Data(forged.utf8)])
        XCTAssertEqual(back?.status, .locked)
        XCTAssertEqual(back?.names, [])
        XCTAssertEqual(back?.unread, 0)
        XCTAssertEqual(WatchBridgeSnapshot.decode(ok.encode()), ok)
    }

    // MARK: Gates

    func testGateOrder() {
        XCTAssertEqual(WatchBridgeService.gate(enabled: false, locked: true, ready: false), .disabled,
                       "while not opted in, the watch must not even learn the lock state")
        XCTAssertEqual(WatchBridgeService.gate(enabled: true, locked: true, ready: true), .locked)
        XCTAssertEqual(WatchBridgeService.gate(enabled: true, locked: false, ready: false), .unavailable)
        XCTAssertNil(WatchBridgeService.gate(enabled: true, locked: false, ready: true))
    }

    func testBridgeIsOffByDefault() {
        let d = UserDefaults.standard
        let saved = d.object(forKey: WatchBridgeSettings.enabledKey)
        defer { d.set(saved, forKey: WatchBridgeSettings.enabledKey) }
        d.removeObject(forKey: WatchBridgeSettings.enabledKey)
        XCTAssertFalse(WatchBridgeSettings.isEnabled)

        let r = WatchBridgeService.shared.respond(to: WatchRequest(rid: "r", op: .list).encode())
        XCTAssertEqual(r.status, .disabled)
        XCTAssertTrue(r.convs.isEmpty)
        // Even a well-formed send is refused before anything is read.
        let s = WatchBridgeService.shared.respond(
            to: WatchRequest(rid: "s", op: .send, kind: .direct, cid: "k", text: "hi").encode())
        XCTAssertEqual(s.status, .disabled)
    }

    func testLockedPhoneAnswersLocked() {
        let d = UserDefaults.standard
        let savedOn = d.object(forKey: WatchBridgeSettings.enabledKey)
        let savedLock = d.object(forKey: AppLockState.biometricKey)
        let wasUnlocked = AppLockState.isUnlocked
        defer {
            d.set(savedOn, forKey: WatchBridgeSettings.enabledKey)
            d.set(savedLock, forKey: AppLockState.biometricKey)
            AppLockState.setUnlocked(wasUnlocked)
        }
        d.set(true, forKey: WatchBridgeSettings.enabledKey)
        d.set(true, forKey: AppLockState.biometricKey)
        AppLockState.setUnlocked(false)
        XCTAssertTrue(AppLockState.isLocked)

        for op in [WatchRequest(rid: "1", op: .list),
                   WatchRequest(rid: "2", op: .thread, kind: .direct, cid: "k"),
                   WatchRequest(rid: "3", op: .send, kind: .direct, cid: "k", text: "hi"),
                   WatchRequest(rid: "4", op: .react, kind: .direct, cid: "k", mid: "m", emoji: "👍")] {
            let r = WatchBridgeService.shared.respond(to: op.encode())
            XCTAssertEqual(r.status, .locked, "\(op.op)")
            XCTAssertEqual(r.rid, op.rid)
            XCTAssertTrue(r.convs.isEmpty && r.msgs.isEmpty)
        }
        XCTAssertEqual(WatchBridgeService.shared.currentSnapshot().names, [])

        // No lock configured → never "locked", whatever isUnlocked says.
        d.set(false, forKey: AppLockState.biometricKey)
        XCTAssertFalse(AppLockState.isLocked)
    }

    func testMalformedRequestIsInvalidOnceAllowed() {
        let d = UserDefaults.standard
        let savedOn = d.object(forKey: WatchBridgeSettings.enabledKey)
        let savedLock = d.object(forKey: AppLockState.biometricKey)
        defer {
            d.set(savedOn, forKey: WatchBridgeSettings.enabledKey)
            d.set(savedLock, forKey: AppLockState.biometricKey)
        }
        d.set(true, forKey: WatchBridgeSettings.enabledKey)
        d.set(false, forKey: AppLockState.biometricKey)
        XCTAssertEqual(WatchBridgeService.shared.respond(to: ["garbage": 1]).status, .invalid)
        let r = WatchBridgeService.shared.respond(
            to: WatchRequest(rid: "x", op: .react, kind: .direct, cid: "k", mid: "m", emoji: "🖕").encode())
        // `.unavailable` when the test host has no identity loaded, `.invalid`
        // when it has: either way the reaction is never sent.
        XCTAssertTrue([.invalid, .unavailable].contains(r.status), "\(r.status)")
    }

    // MARK: Rendering helpers

    func testMediaKindsAndReactionSummary() {
        XCTAssertEqual(WatchBridgeService.media(for: MediaManager.MediaType.image, hasAttachment: true), .photo)
        XCTAssertEqual(WatchBridgeService.media(for: MediaManager.MediaType.gif, hasAttachment: true), .gif)
        XCTAssertEqual(WatchBridgeService.media(for: MediaManager.MediaType.audio, hasAttachment: true), .voice)
        XCTAssertEqual(WatchBridgeService.media(for: nil as MediaManager.MediaType?, hasAttachment: true), .file)
        XCTAssertNil(WatchBridgeService.media(for: nil as MediaManager.MediaType?, hasAttachment: false))
        XCTAssertEqual(WatchBridgeService.media(for: MediaType.photo), .photo)
        XCTAssertEqual(WatchBridgeService.distinctReactions(["👍", "❤️", "👍", "😂", "🔥", "👍"]),
                       ["👍", "❤️", "😂"])
    }

    /// The watch draws avatars with THIS file (AvatarIdentity.swift is compiled
    /// into the watch target), so the spec vectors that `AvatarIdentityTests`
    /// checks are the watch's too. Spot-check one here so a watch-only change
    /// to the selection rule would show up in this suite as well.
    func testAvatarSlotFollowsTheSharedSpec() throws {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("docs/avatar-palette.json")
        guard let data = try? Data(contentsOf: url),
              let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let vectors = (json["vectors"] ?? json["testVectors"]) as? [[String: Any]] else {
            throw XCTSkip("docs/avatar-palette.json not reachable from the test bundle")
        }
        var checked = 0
        for v in vectors {
            guard let id = (v["id"] ?? v["identifier"]) as? String, let slot = v["slot"] as? Int else { continue }
            XCTAssertEqual(AvatarIdentity.slot(for: id), slot, id)
            checked += 1
        }
        XCTAssertGreaterThan(checked, 0)
    }
}
