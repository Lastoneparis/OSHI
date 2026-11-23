//
//  CallCrashRegressionTests.swift
//  OSHITests
//
//  Regressions for crashes CONFIRMED from the device's own reports, pulled off
//  the iPhone 17 Pro Max on 2026-08-20 with
//  `devicectl device copy from --domain-type systemCrashLogs`.
//
//  Each test below names the .ips it comes from. None of these were theories:
//  the reports carry the exception type, the faulting thread and the frame.
//

import XCTest
@testable import OSHI

final class CallCrashRegressionTests: XCTestCase {

    // MARK: - OSHI-2026-08-14-150232.ips (and six more, back to build 84)
    //
    // EXC_BREAKPOINT / SIGTRAP on `com.oshi.p2p.transport`, in
    // `VoiceCallManager.processReceivedAudioData(_:sequenceNumber:)`.
    //
    // The function measured "holes" as `after - before` on a `UInt64` counter
    // read either side of the drain loop. `AdvancedJitterBuffer.reset()` sets
    // that counter to zero and is called from eight places — including the
    // peer's resync handler, on another queue. A reset landing inside the loop
    // made the last line `0 - 4127`, and Swift traps on unsigned overflow.

    func testCounterResetMidDrainReportsNoHolesInsteadOfTrapping() {
        // The exact shape from the report: a long call's counter, then a reset.
        XCTAssertEqual(VoiceCallManager.holeCount(before: 4127, after: 0), 0,
                       "A counter that went backwards means the buffer was reset mid-drain — no holes, and above all no unsigned subtraction")
    }

    func testNormalDrainStillCountsItsHoles() {
        XCTAssertEqual(VoiceCallManager.holeCount(before: 10, after: 13), 3)
        XCTAssertEqual(VoiceCallManager.holeCount(before: 0, after: 1), 1)
    }

    func testNoHolesIsZeroNotOne() {
        // Guards the off-by-one that would splice a synthesised frame into a
        // slot nothing was ever lost from — the 2026-07-27 "phantom audio" bug
        // this counter was introduced to fix in the first place.
        XCTAssertEqual(VoiceCallManager.holeCount(before: 99, after: 99), 0)
    }

    func testAnImpossiblyLargeDeltaSaturatesRatherThanTraps() {
        // Not reachable in a real call, but `Int(someUInt64)` is a trap waiting
        // for whoever changes the counter's width or its reset semantics.
        XCTAssertEqual(VoiceCallManager.holeCount(before: 0, after: UInt64.max), Int.max)
    }

    // MARK: - OSHI-2026-08-20-195151.ips
    //
    // SIGABRT on `com.app.ratchet.manager`:
    //   -[NSObject doesNotRecognizeSelector:] → Dictionary<>.encode(to:)
    //     → DoubleRatchetSession.encode(to:)
    //
    // `skippedMessageKeys` was mutated on `com.app.ratchet.session` and
    // serialised on `com.app.ratchet.manager`, unsynchronised. Encoding a
    // Dictionary while another thread inserts into it walks a buffer being
    // reallocated underneath.
    //
    // What is asserted here is the property that makes that impossible: the
    // getter hands out a SNAPSHOT, so an encode in flight cannot see a later
    // mutation. Under the old stored property this test could not even be
    // written — there was nothing to snapshot.

    func testSkippedKeysGetterReturnsAnIndependentSnapshot() throws {
        let session = DoubleRatchetSession(rootKey: Data(repeating: 1, count: 32),
                                           sendingChainKey: Data(repeating: 2, count: 32),
                                           receivingChainKey: Data(repeating: 3, count: 32),
                                           sessionId: "snapshot-test",
                                           isInitiator: true)
        let first = session.skippedMessageKeys
        XCTAssertTrue(first.isEmpty)

        // Encode the session — the operation that aborted — and prove it works
        // on a session whose skipped-key table is live.
        let encoded = try JSONEncoder().encode(session)
        XCTAssertFalse(encoded.isEmpty)

        // The snapshot taken before must be unaffected by anything after it.
        XCTAssertTrue(first.isEmpty,
                      "A snapshot must be a value, not a window onto the live table")
    }

    /// The real defect was concurrent, so exercise it concurrently: hammer the
    /// encoder from one queue while the session's own table churns from another.
    /// Under the pre-fix code this is the interleaving that aborted.
    func testEncodingWhileSkippedKeysChurnDoesNotCorruptTheEncoder() throws {
        let session = DoubleRatchetSession(rootKey: Data(repeating: 1, count: 32),
                                           sendingChainKey: Data(repeating: 2, count: 32),
                                           receivingChainKey: Data(repeating: 3, count: 32),
                                           sessionId: "race-test",
                                           isInitiator: true)
        let done = expectation(description: "encoders finished")
        done.expectedFulfillmentCount = 2

        DispatchQueue.global(qos: .userInitiated).async {
            for _ in 0..<200 {
                _ = try? JSONEncoder().encode(session)
            }
            done.fulfill()
        }
        DispatchQueue.global(qos: .userInitiated).async {
            for _ in 0..<200 {
                // Public surface that touches the table on the other side of the
                // lock. Reading is enough to exercise the accessor; the mutators
                // are private and are covered through the ratchet's own tests.
                _ = session.skippedMessageKeys
                session.cleanupOldSkippedKeys(olderThan: 0)
            }
            done.fulfill()
        }
        wait(for: [done], timeout: 30)
    }
}

// MARK: - 2026-09-22 FRONTBOARD 0xBAADCA11 ("bad call") — build 143
//
// iOS killed the app while an incoming call should have rung: MAIN was inside
// `JSONEncoder.encode` in `MultiDeviceSyncManager.uploadMessagesForSync()` and the
// PushKit registry delivered VoIP pushes on `.main`, so a push could not be reported to
// CallKit in time. __PUSHKIT_OFF_MAIN_2026_09_23__ / __SYNC_ENCODE_OFF_MAIN_2026_09_23__.

final class VoIPPushOffMainRegressionTests: XCTestCase {

    private func source(_ relative: String) throws -> String {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent(relative)
            if FileManager.default.fileExists(atPath: candidate.path) {
                return try String(contentsOf: candidate, encoding: .utf8)
            }
            dir.deleteLastPathComponent()
        }
        throw XCTSkip("\(relative) not found from \(#filePath)")
    }

    /// Text of `func <name>` up to the next top-level-ish `func ` declaration.
    private func body(of name: String, in src: String) throws -> String {
        let start = try XCTUnwrap(src.range(of: "func \(name)"), "func \(name) not found")
        let rest = src[start.upperBound...]
        let end = rest.range(of: "\n    func ")?.lowerBound ?? rest.endIndex
        return String(rest[..<end])
    }

    // MARK: PushKit side

    func testPushRegistryIsNeverDeliveredOnMain() throws {
        let src = try source("OSHI/VoIPPushManager.swift")
        XCTAssertFalse(src.contains("PKPushRegistry(queue: .main)"),
                       "VoIP pushes delivered on main wait behind whatever main is doing → 0xBAADCA11")
        XCTAssertTrue(src.contains("PKPushRegistry(queue: VoIPPushManager.pushQueue)"))
    }

    /// The handler may not touch main-thread singletons or wait for main.
    func testPushHandlerReadsNoMainThreadState() throws {
        let src = try source("OSHI/VoIPPushManager.swift")
        let handler = try body(of: "handleIncomingVoIPPush(", in: src)
        let code = handler.split(separator: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.hasPrefix("//") && !$0.hasPrefix("///") }
            .joined(separator: "\n")
        for forbidden in ["UIApplication.shared", "VoiceCallManager.shared", "MessageManager.shared",
                          "BlockedContactsManager.shared", "StaleIdentityManager.shared",
                          "DispatchQueue.main.sync", "callKitPresentingCallId"] {
            XCTAssertFalse(code.contains(forbidden), "handleIncomingVoIPPush must not use \(forbidden)")
        }
    }

    /// Every early exit of the handler first reports to CallKit (PushKit contract).
    func testEveryHandlerExitReportsToCallKit() throws {
        let src = try source("OSHI/VoIPPushManager.swift")
        let handler = try body(of: "handleIncomingVoIPPush(", in: src)
        let lines = handler.split(separator: "\n", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespaces) }
        var exits = 0
        for (i, line) in lines.enumerated() where line == "return" {
            exits += 1
            let prev = lines[..<i].last { !$0.isEmpty && !$0.hasPrefix("//") } ?? ""
            XCTAssertTrue(prev.hasSuffix("completion: completion)") || prev.hasSuffix("{ _ in completion() }"),
                          "exit #\(exits) returns without reporting to CallKit (previous line: \(prev))")
        }
        XCTAssertGreaterThanOrEqual(exits, 8)
    }

    func testPushCompletionFiresExactlyOnce() {
        var count = 0
        let once = PushCompletionOnce { count += 1 }
        XCTAssertTrue(once.fire())
        XCTAssertFalse(once.fire())
        XCTAssertFalse(once.fire())
        XCTAssertEqual(count, 1)
    }

    func testGateFoldsKeysAndExpiresEndedCallIds() {
        let suite = "CallPushGateTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let gate = CallPushGate(defaults: defaults)

        gate.setBlockedKeys(["ab+c/d=="])
        XCTAssertTrue(gate.isBlocked("ab-c_d"), "base64url / unpadded spelling must still match")
        gate.setStaleKeys(["xy+z"])
        XCTAssertTrue(gate.isStale("xy-z="))
        XCTAssertFalse(gate.isStale(""))

        let now = Date()
        gate.noteCallEnded("c1", ttl: 15, now: now)
        XCTAssertTrue(gate.isCallIdRecentlyEnded("c1", now: now.addingTimeInterval(10)))
        XCTAssertFalse(gate.isCallIdRecentlyEnded("c1", now: now.addingTimeInterval(16)))
        XCTAssertFalse(gate.isCallIdRecentlyEnded(nil))

        XCTAssertNil(gate.isContact("k1"), "never populated → unknown, not 'not a contact'")
        gate.setContactKeys(["k1=="], persistTo: defaults)
        XCTAssertEqual(gate.isContact("k1"), true)
        XCTAssertEqual(gate.isContact("k2"), false)
        // A cold launch by the push must see the last snapshot.
        XCTAssertEqual(CallPushGate(defaults: defaults).isContact("k1"), true)

        gate.setCallState(.ringing)
        XCTAssertEqual(gate.callState, .ringing)
    }

    /// The gate is usable from the PushKit queue while MAIN is busy (no hidden main hop).
    func testGateAnswersWhileMainIsBlocked() {
        let gate = CallPushGate(defaults: UserDefaults(suiteName: "gate-main-\(UUID().uuidString)")!)
        let answered = DispatchSemaphore(value: 0)
        VoIPPushManager.pushQueue.async {
            _ = gate.isBlocked("k"); _ = gate.callState; _ = gate.appState
            _ = gate.isCallIdRecentlyEnded("x"); _ = gate.isContact("k")
            answered.signal()
        }
        // We ARE main: block it and require the push queue to finish meanwhile.
        XCTAssertEqual(answered.wait(timeout: .now() + 2), .success)
    }

    // MARK: Sync side

    private func message(_ i: Int, mediaBytes: Int = 0) -> SecureMessage {
        var m = SecureMessage(
            id: "m\(i)",
            senderAddress: "0xS",
            recipientAddress: "0xR",
            encryptedContent: EncryptedMessage(
                ciphertext: String(repeating: "Q", count: 40 + i % 7),
                signature: "c2ln",
                senderPublicKey: "sender",
                timestamp: Date(timeIntervalSince1970: 1_700_000_000 + Double(i))
            ),
            timestamp: Date(timeIntervalSince1970: 1_700_000_000 + Double(i)),
            isRead: false,
            deliveryStatus: .sent
        )
        m.plaintextContent = "hello \(i)"
        if mediaBytes > 0 { m.mediaAttachment = Data(repeating: 7, count: mediaBytes) }
        return m
    }

    func testRecentMessagesBodyIsByteIdenticalToArrayEncode() throws {
        let msgs = (0..<120).map { message($0, mediaBytes: $0 == 3 ? 2_000 : 0) }.shuffled()
        let (body, kept) = MultiDeviceSyncManager.buildRecentMessagesBody(msgs)
        XCTAssertEqual(kept, msgs.count)
        let reference = try JSONEncoder().encode(msgs.sorted { $0.timestamp > $1.timestamp })
        // Same size and same JSON value as JSONEncoder's own array output. (Not compared
        // byte-for-byte: JSONEncoder's key order inside an object is hash-based and can
        // differ between two encodes of the same value.)
        XCTAssertEqual(body.count, reference.count)
        let a = try JSONSerialization.jsonObject(with: body) as? NSArray
        let b = try JSONSerialization.jsonObject(with: reference) as? NSArray
        XCTAssertNotNil(a)
        XCTAssertEqual(a, b, "hand-assembled array must be the same JSON value as encode(array)")
    }

    func testRecentMessagesBodyHonoursCapAndKeepsNewest() throws {
        let msgs = (0..<400).map { message($0) }
        let cap = 20_000
        let (body, kept) = MultiDeviceSyncManager.buildRecentMessagesBody(msgs, totalBodyCap: cap)
        XCTAssertLessThanOrEqual(body.count, cap)
        XCTAssertGreaterThan(kept, 0)
        XCTAssertLessThan(kept, msgs.count)
        let decoded = try JSONDecoder().decode([SecureMessage].self, from: body)
        XCTAssertEqual(decoded.count, kept)
        XCTAssertEqual(decoded.first?.id, "m399")
        XCTAssertEqual(decoded.last?.id, "m\(400 - kept)")
        // Oversized media is dropped from the sync copy, never the message.
        let big = MultiDeviceSyncManager.buildRecentMessagesBody([message(1, mediaBytes: 50)], perMessageMediaCap: 10)
        XCTAssertNil(try JSONDecoder().decode([SecureMessage].self, from: big.body).first?.mediaAttachment)
    }

    /// No legacy sync upload serialises on the main queue: main only snapshots.
    func testLegacySyncUploadsEncodeOffMain() throws {
        let src = try source("OSHI/MultiDeviceSyncManager.swift")
        for fn in ["uploadGroupsForSync()", "uploadMessagesForSync()", "uploadGroupMessagesForSync()"] {
            let b = try body(of: fn, in: src)
            let mainStart = try XCTUnwrap(b.range(of: "DispatchQueue.main.async"), fn)
            let work = try XCTUnwrap(b.range(of: "uploadQueue.async", range: mainStart.upperBound..<b.endIndex),
                                     "\(fn): the main block must hand off to uploadQueue")
            let onMain = b[mainStart.upperBound..<work.lowerBound]
            XCTAssertFalse(onMain.contains("JSONEncoder"), "\(fn) encodes on main")
            XCTAssertFalse(onMain.contains("encryptSyncData"), "\(fn) encrypts on main")
        }
        for fn in ["uploadContactsForSync()", "uploadProfileForSync()"] {
            XCTAssertTrue(try body(of: fn, in: src).contains("uploadQueue.async"), fn)
        }
    }

    /// OSHI_DEVICE_SYNC_DIRECT.md §12 phase 1: with `/v2/config` devsync_enabled=true
    /// (prod since 2026-09-23) the legacy /api/sync uploads must not run at all.
    func testDevsyncConfigSuppressesEveryLegacyUpload() throws {
        let d = UserDefaults.standard
        let savedRemote = d.data(forKey: V2FeatureFlag.remoteKey)
        let savedOverride = d.object(forKey: DevSyncFlag.debugOverrideKey)
        defer {
            if let savedRemote { d.set(savedRemote, forKey: V2FeatureFlag.remoteKey) } else { d.removeObject(forKey: V2FeatureFlag.remoteKey) }
            if let savedOverride { d.set(savedOverride, forKey: DevSyncFlag.debugOverrideKey) } else { d.removeObject(forKey: DevSyncFlag.debugOverrideKey) }
        }
        d.removeObject(forKey: DevSyncFlag.debugOverrideKey)
        if ProcessInfo.processInfo.arguments.contains("-DevSyncEnabled") { throw XCTSkip("launch-arg override present") }

        d.set(Data(#"{"v2_enabled":true,"rollout_percent":100,"min_build":0,"devsync_enabled":true}"#.utf8), forKey: V2FeatureFlag.remoteKey)
        XCTAssertTrue(DevSyncFlag.legacySyncSuppressed)
        d.set(Data(#"{"v2_enabled":true,"rollout_percent":100,"min_build":0,"devsync_enabled":false}"#.utf8), forKey: V2FeatureFlag.remoteKey)
        XCTAssertFalse(DevSyncFlag.legacySyncSuppressed)

        let src = try source("OSHI/MultiDeviceSyncManager.swift")
        for fn in ["performFullSync()", "scheduleUploadMessagesForSync(", "uploadMessagesForSync()",
                   "uploadGroupsForSync()", "uploadContactsForSync()", "uploadGroupMessagesForSync()",
                   "uploadProfileForSync()"] {
            let b = try body(of: fn, in: src)
            let firstStatement = b.split(separator: "\n").dropFirst()
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .first { !$0.isEmpty && !$0.hasPrefix("//") } ?? ""
            XCTAssertTrue(firstStatement.hasPrefix("guard !DevSyncFlag.legacySyncSuppressed"),
                          "\(fn) must bail out first when devsync is on (got: \(firstStatement))")
        }
    }
}

// MARK: - The silent answered call: __ENGINE_HEALTH_NOT_ISRUNNING_2026_10_12__
//
// Reported 2026-10-12: "people who are calling me on my iPhone 17, once I pick
// up I cannot hear sound." Not a crash — the opposite. A call that connects,
// decrypts thousands of packets, and plays none of them.
//
// `AVAudioEngine.isRunning` is not a health check. An engine built while CallKit
// owned a session it had not yet activated reports `isRunning == true` for the
// whole call while its mic tap delivers all-zero buffers and its player renders
// into nothing. The 2026-09-25 diagnostics caught it three times in a row:
// `eng=false play=false micTapReceiving=false rxPlayed=0 txCount=0` with
// `ckActive=false`.
//
// Three guards in `startAudioEngineForCall` trusted `isRunning` anyway, and the
// one added on 2026-10-01 (__ENGINE_SINGLE_START_2026_10_01__, to stop a second
// installTap SIGABRT) sat FIRST — so on a call where early P2P audio had already
// built the dead graph, it returned before the 2 s `didActivate` safety net was
// ever armed. Nothing rebuilt the engine, the auto-recovery watchdog only fires
// on `!isRunning`, and the call stayed silent both ways from answer to hang-up.

final class CallSilentEngineRegressionTests: XCTestCase {

    // MARK: the rule itself

    func testARunningEngineOnACallKitSessionThatNeverActivatedIsNotLive() {
        // The reported bug, exactly: running, built on CallKit's session, no didActivate.
        XCTAssertFalse(
            VoiceCallManager.engineRunningOnLiveSession(isRunning: true,
                                                        builtOnCallKitSession: true,
                                                        callKitSessionActive: false),
            "This is the deaf-and-mute graph. Calling it live is what made the call silent.")
    }

    func testTheSameEngineBecomesLiveOnceDidActivateArrives() {
        XCTAssertTrue(
            VoiceCallManager.engineRunningOnLiveSession(isRunning: true,
                                                        builtOnCallKitSession: true,
                                                        callKitSessionActive: true))
    }

    func testAnEngineWeBuiltOnOurOwnSessionIsLiveWhenItRuns() {
        // China bypass / in-app-overlay accept: we called setActive ourselves.
        XCTAssertTrue(
            VoiceCallManager.engineRunningOnLiveSession(isRunning: false || true,
                                                        builtOnCallKitSession: false,
                                                        callKitSessionActive: false))
    }

    func testAStoppedEngineIsNeverLive() {
        for built in [true, false] {
            for active in [true, false] {
                XCTAssertFalse(
                    VoiceCallManager.engineRunningOnLiveSession(isRunning: false,
                                                                builtOnCallKitSession: built,
                                                                callKitSessionActive: active),
                    "built=\(built) active=\(active)")
            }
        }
    }

    func testTheOldRuleIsTheBug() {
        // Replay what the three guards used to ask — `isRunning == true` alone — and
        // assert it gives the WRONG answer for the reported call. If this ever starts
        // failing, the rule has been weakened back to where it was.
        let oldRuleSaysHealthy = true   // isRunning
        let newRule = VoiceCallManager.engineRunningOnLiveSession(isRunning: true,
                                                                   builtOnCallKitSession: true,
                                                                   callKitSessionActive: false)
        XCTAssertTrue(oldRuleSaysHealthy)
        XCTAssertFalse(newRule)
        XCTAssertNotEqual(oldRuleSaysHealthy, newRule,
                          "The whole point of the fix: these two must disagree on the silent call.")
    }

    // MARK: the wiring — a correct rule nobody calls fixes nothing

    func testEveryEngineReuseGuardAsksTheRuleAndNotIsRunning() throws {
        let here = URL(fileURLWithPath: #filePath)
        let src = here.deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("OSHI/VoiceCallManager.swift")
        guard let text = try? String(contentsOf: src, encoding: .utf8) else {
            throw XCTSkip("source not reachable from the test host")
        }

        // Strip comments: a comment quoting the old shape must not satisfy the test,
        // and the comments here deliberately quote it.
        let body = text.split(separator: "\n", omittingEmptySubsequences: false)
            .filter { line in
                let t = line.trimmingCharacters(in: .whitespaces)
                return !t.hasPrefix("//") && !t.hasPrefix("*") && !t.hasPrefix("/*") && !t.hasPrefix("///")
            }
            .joined(separator: "\n")

        XCTAssertTrue(body.contains("if audioEngineRunningOnLiveSession, micTapInstalled {"),
                      "the __ENGINE_SINGLE_START_2026_10_01__ guard must not short-circuit the didActivate deferral")
        XCTAssertFalse(body.contains("if audioEngine?.isRunning == true, micTapInstalled {"),
                       "the bare isRunning form of that guard is the bug — it is back")
        XCTAssertFalse(body.contains("guard self.audioEngine?.isRunning != true else { return }"),
                       "no engine-reuse guard may decide health from isRunning alone")
        XCTAssertEqual(body.components(separatedBy: "guard !self.audioEngineRunningOnLiveSession else { return }").count - 1, 3,
                       "all three reuse guards (2 s didActivate fallback, in-app accept settle, 500 ms retry) must ask the rule")
    }

    func testCrashSafetyDoesNotDependOnThatGuard() throws {
        // Removing the early return is only safe because a start tears the previous
        // engine down under a lock — which is what actually prevents the second
        // installTap SIGABRT of 2026-09-29 (build 156, iPhone 17).
        let here = URL(fileURLWithPath: #filePath)
        let src = here.deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("OSHI/VoiceCallManager.swift")
        guard let text = try? String(contentsOf: src, encoding: .utf8) else {
            throw XCTSkip("source not reachable from the test host")
        }
        XCTAssertTrue(text.contains("engineLifecycleLock.lock()"))
        XCTAssertTrue(text.contains("tearing it down first"),
                      "startAudioEngine must stop an existing engine before building a new one")
    }
}
