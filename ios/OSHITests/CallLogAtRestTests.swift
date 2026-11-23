//
//  CallLogAtRestTests.swift
//  OSHITests
//
//  __CALL_LOG_AT_REST_2026_09_23__ `call_debug.log` is sealed on disk
//  (OSHILOG1 records) yet the "share call diagnostics" export stays the same
//  readable, redacted `.txt`. Each logger here runs against its own scratch
//  folder and an injected key, so nothing touches the real log or keychain.
//

import XCTest
import CryptoKit
@testable import OSHI

final class CallLogAtRestTests: XCTestCase {

    private var dir: URL!
    private var exportDir: URL!
    private let key = SymmetricKey(size: .bits256)

    override func setUpWithError() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("CallLogAtRestTests-\(UUID().uuidString)", isDirectory: true)
        dir = root.appendingPathComponent("docs", isDirectory: true)
        exportDir = root.appendingPathComponent("export", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir.deletingLastPathComponent())
    }

    private func makeLogger(key: SymmetricKey? = nil) -> CallFileLogger {
        let k = key ?? self.key
        return CallFileLogger(directory: dir, exportDirectory: exportDir, keyProvider: { k })
    }

    private var currentURL: URL { dir.appendingPathComponent(CallFileLogger.currentLogName) }
    private var previousURL: URL { dir.appendingPathComponent(CallFileLogger.previousLogName) }

    private func contains(_ data: Data, _ needle: String) -> Bool {
        data.range(of: Data(needle.utf8)) != nil
    }

    // MARK: -

    func testRoundTripNeverWritesPlaintext() throws {
        let logger = makeLogger()
        for i in 0..<250 { logger.log("DIAG_JITTER | depth=\(i) | secret-marker") }
        logger.flushAndWait()
        for i in 250..<500 { logger.log("DIAG_JITTER | depth=\(i) | secret-marker") }
        logger.log("DIAG_MSG_DECRYPT_OK | preview=bonjour-en-clair")
        logger.flushAndWait()

        let raw = try Data(contentsOf: currentURL)
        XCTAssertTrue(raw.starts(with: CallLogRecords.magic))
        XCTAssertFalse(contains(raw, "secret-marker"))
        XCTAssertFalse(contains(raw, "bonjour-en-clair"))
        XCTAssertFalse(contains(raw, "OSHI call log"))

        let decoded = CallLogRecords.decode(raw, key: key)
        XCTAssertGreaterThan(decoded.opened, 1, "two flushes ⇒ at least two records, read back as one stream")
        XCTAssertEqual(decoded.rejected, 0)
        XCTAssertFalse(decoded.tornTail)
        let text = String(decoding: decoded.plaintext, as: UTF8.self)
        let lines = text.split(separator: "\n")
        XCTAssertTrue(lines.first?.hasPrefix("===== OSHI call log — run started") == true)
        XCTAssertEqual(lines.count, 502)
        XCTAssertTrue(lines[1].hasSuffix("] DIAG_JITTER | depth=0 | secret-marker"))
        XCTAssertTrue(lines[500].hasSuffix("] DIAG_JITTER | depth=499 | secret-marker"))
    }

    func testEachRecordHasAFreshNonce() throws {
        let a = try CallLogRecords.seal(Data("same".utf8), key: key)
        let b = try CallLogRecords.seal(Data("same".utf8), key: key)
        XCTAssertNotEqual(a.subdata(in: 4..<16), b.subdata(in: 4..<16))
    }

    func testTornLastRecordIsSkipped() throws {
        let logger = makeLogger()
        logger.log("DIAG_PATH | first")
        logger.flushAndWait()
        logger.log("DIAG_PATH | second")
        logger.flushAndWait()

        // Simulate a crash mid-write: half of a third record.
        let partial = try CallLogRecords.seal(Data("DIAG_PATH | third\n".utf8), key: key)
        let handle = try FileHandle(forWritingTo: currentURL)
        try handle.seekToEnd()
        try handle.write(contentsOf: partial.prefix(partial.count / 2))
        try handle.close()

        let decoded = CallLogRecords.decode(try Data(contentsOf: currentURL), key: key)
        XCTAssertTrue(decoded.tornTail)
        XCTAssertEqual(decoded.rejected, 0)
        let text = String(decoding: decoded.plaintext, as: UTF8.self)
        XCTAssertTrue(text.contains("DIAG_PATH | first"))
        XCTAssertTrue(text.contains("DIAG_PATH | second"))
        XCTAssertFalse(text.contains("third"))

        // A 3-byte stub (not even a whole length prefix) is torn too.
        var stub = CallLogRecords.magic
        stub.append(try CallLogRecords.seal(Data("ok\n".utf8), key: key))
        stub.append(contentsOf: [0, 0, 1])
        let d2 = CallLogRecords.decode(stub, key: key)
        XCTAssertEqual(d2.opened, 1)
        XCTAssertTrue(d2.tornTail)
    }

    func testRotationKeepsPreviousRunReadable() throws {
        let first = makeLogger()
        first.log("DIAG_PATH | run-one")
        first.flushAndWait()

        let second = makeLogger()
        second.log("DIAG_PATH | run-two")
        let logs = second.decryptedLogs()
        XCTAssertEqual(logs.map(\.name), [CallFileLogger.previousLogName, CallFileLogger.currentLogName])
        XCTAssertTrue(logs[0].text.contains("run-one"))
        XCTAssertFalse(logs[0].text.contains("run-two"))
        XCTAssertTrue(logs[1].text.contains("run-two"))
        XCTAssertTrue(try Data(contentsOf: previousURL).starts(with: CallLogRecords.magic))
    }

    func testLostKeyDropsOldLogsSilently() throws {
        let before = makeLogger(key: SymmetricKey(size: .bits256))
        before.log("DIAG_PATH | written-with-the-old-key")
        before.flushAndWait()

        // Restore onto a new device: file came along, key did not.
        let after = makeLogger()
        after.log("DIAG_PATH | fresh")
        after.flushAndWait()   // queue is serial: launch cleanup has run
        XCTAssertFalse(FileManager.default.fileExists(atPath: previousURL.path),
                       "an unreadable previous run must be deleted, not kept forever")
        let export = try XCTUnwrap(after.redactedExportText())
        XCTAssertFalse(export.contains("written-with-the-old-key"))
        XCTAssertTrue(export.contains("DIAG_PATH | fresh"))
    }

    func testLockedKeychainNeverWritesPlaintextAndRecovers() throws {
        var available = false
        let k = key
        let logger = CallFileLogger(directory: dir, exportDirectory: exportDir, keyProvider: {
            guard available else { throw KeychainError.locked }
            return k
        })
        logger.log("DIAG_PATH | before-first-unlock")
        logger.flushAndWait()
        XCTAssertFalse(FileManager.default.fileExists(atPath: currentURL.path),
                       "no key ⇒ nothing on disk, and certainly not in clear")

        available = true
        logger.log("DIAG_PATH | after-unlock")
        logger.flushAndWait()
        let text = String(decoding: CallLogRecords.decode(try Data(contentsOf: currentURL), key: key).plaintext,
                          as: UTF8.self)
        XCTAssertTrue(text.contains("before-first-unlock"), "buffered lines are kept until the key is back")
        XCTAssertTrue(text.contains("after-unlock"))
    }

    func testLegacyPlaintextIsMigratedAndRemoved() throws {
        let legacyCurrent = """
        ===== OSHI call log — run started 2026-09-20T10:00:00.000Z (previous run: call_debug.prev.log) =====
        [2026-09-20T10:00:01.000Z] DIAG_PATH | legacy-current
        [2026-09-20T10:00:02.000Z] DIAG_MSG_DECRYPT_OK | preview=legacy-secret

        """
        // Big enough to span several 64 KB migration records.
        let filler = String(repeating: "[2026-09-20T10:00:03.000Z] DIAG_JITTER | depth=1\n", count: 3000)
        try Data((legacyCurrent + filler).utf8).write(to: currentURL)
        try Data("[2026-09-19T00:00:00.000Z] DIAG_PATH | legacy-prev\n".utf8).write(to: previousURL)

        let logger = makeLogger()
        logger.flushAndWait()

        let raw = try Data(contentsOf: previousURL)
        XCTAssertTrue(raw.starts(with: CallLogRecords.magic))
        XCTAssertFalse(contains(raw, "legacy-secret"))
        XCTAssertFalse(contains(raw, "legacy-current"))
        XCTAssertFalse(FileManager.default.fileExists(atPath: previousURL.path + ".sealing"))
        let decoded = CallLogRecords.decode(raw, key: key)
        XCTAssertGreaterThan(decoded.opened, 1)
        XCTAssertEqual(String(decoding: decoded.plaintext, as: UTF8.self), legacyCurrent + filler,
                       "migration must be lossless")
        // The older previous run is rotated out, exactly as before.
        XCTAssertFalse(logger.decryptedLogs().contains { $0.text.contains("legacy-prev") })
        // Nothing in the folder is plaintext any more.
        for name in try FileManager.default.contentsOfDirectory(atPath: dir.path) {
            let data = try Data(contentsOf: dir.appendingPathComponent(name))
            XCTAssertTrue(data.starts(with: CallLogRecords.magic), "\(name) is not sealed")
        }
    }

    func testExportEqualsRedactedPlaintext() throws {
        let legacy = """
        ===== OSHI call log — run started 2026-09-20T10:00:00.000Z (previous run: call_debug.prev.log) =====
        [2026-09-20T10:00:01.000Z] DIAG_PATH | route=relay
        [2026-09-20T10:00:02.000Z] DIAG_MSG_DECRYPT_OK | preview=never-exported
        [2026-09-20T10:00:03.000Z] 🔄 STATE CHANGE: ringing → inCall
        [2026-09-20T10:00:04.000Z] MSGMATRIX_SEND | to=abcdef
        [2026-09-20T10:00:05.000Z] DIAG_V2_GIVEUP | mid=12345678

        """
        try Data(legacy.utf8).write(to: currentURL)
        let logger = makeLogger()
        logger.log("DIAG_JITTER | depth=3")
        logger.log("DIAG_MSG_RX_ENTRY | preview=also-never-exported")

        let export = try XCTUnwrap(logger.redactedExportText())
        let plainLogs = logger.decryptedLogs()
        XCTAssertEqual(plainLogs.first?.text, legacy)
        XCTAssertEqual(export, CallFileLogger.redact(logs: plainLogs),
                       "the sealed path must export exactly what the plaintext path did")
        XCTAssertEqual(export, CallFileLogger.redact(logs: [(CallFileLogger.previousLogName, legacy),
                                                            (CallFileLogger.currentLogName, plainLogs[1].text)]))

        // Same format as the plaintext-era export, pinned for the previous run.
        let expectedHead = """
        OSHI call diagnostics — redacted export
        Only call-path lines are included. Message content, contacts and
        conversation data are deliberately NOT in this file.


        ===== call_debug.prev.log =====
        ===== OSHI call log — run started 2026-09-20T10:00:00.000Z (previous run: call_debug.prev.log) =====
        DIAG_PATH | route=relay
        🔄 STATE CHANGE: ringing → inCall
        DIAG_V2_GIVEUP | mid=12345678

        ===== call_debug.log =====
        ===== OSHI call log — run started
        """
        XCTAssertTrue(export.hasPrefix(expectedHead), export)
        XCTAssertTrue(export.hasSuffix("DIAG_JITTER | depth=3\n"))
        XCTAssertFalse(export.contains("never-exported"))
        XCTAssertFalse(export.contains("MSGMATRIX"))

        // The shared file is plain UTF-8 text with that content, and is gone at the next launch.
        let url = try XCTUnwrap(logger.exportRedactedCallLog())
        XCTAssertEqual(url.pathExtension, "txt")
        XCTAssertEqual(try String(contentsOf: url, encoding: .utf8), export)
        _ = makeLogger().decryptedLogs()
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    }

    func testBatchingDoesNotBlockTheCaller() {
        let logger = makeLogger()
        let start = Date()
        for i in 0..<5_000 { logger.log("DIAG_JITTER | depth=\(i)") }
        // 5 000 lines (4 min of 20 ms DIAG) must cost the caller appends only.
        XCTAssertLessThan(Date().timeIntervalSince(start), 1.0)
        logger.flushAndWait()
        let decoded = CallLogRecords.decode((try? Data(contentsOf: currentURL)) ?? Data(), key: key)
        XCTAssertEqual(String(decoding: decoded.plaintext, as: UTF8.self)
                        .split(separator: "\n").count, 5_001)
        XCTAssertLessThan(decoded.opened, 5_000 / 10, "lines must be batched, not sealed one by one")
    }

    /// The real key: stable across calls, 256-bit, per-bundle account name.
    /// Unsigned test runs have no keychain entitlement (-34018): skipped there.
    func testKeychainKeyIsStableAndPerBundle() throws {
        let first: SymmetricKey
        do {
            first = try CallLogKey.shared.key()
        } catch KeychainError.unhandledError(let status) where status == -34018 {
            throw XCTSkip("no keychain entitlement in this (unsigned) test host")
        }
        XCTAssertEqual(first.bitCount, 256)
        XCTAssertEqual(try CallLogKey.shared.key(), first)
        XCTAssertTrue(CallLogKey.shared.account.hasSuffix(Bundle.main.bundleIdentifier ?? "unknown"))
    }
}

// MARK: - __LEAK_FIX_2026_09_25__ migrations & leak guards
//
// Lives in this file (at-rest topic) so no project-file change is needed.

final class LeakFixMigrationTests: XCTestCase {

    private var dir: URL!
    private var suiteName: String!
    private var defaults: UserDefaults!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("LeakFixMigrationTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        suiteName = "LeakFixMigrationTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
        defaults.removePersistentDomain(forName: suiteName)
    }

    // #2 AI assistant history: UserDefaults (backed up, cleartext) → sealed file.
    func testAIChatHistoryMigratesOutOfUserDefaultsSealed() throws {
        let legacy = Data(#"[{"id":"c1","title":"secret-ai-title","messages":[]}]"#.utf8)
        defaults.set(legacy, forKey: AIChatPersistence.legacyDefaultsKey)
        let url = dir.appendingPathComponent("ai_chats.json")

        XCTAssertEqual(AIChatPersistence.load(url: url, defaults: defaults), .data(legacy))
        XCTAssertNil(defaults.object(forKey: AIChatPersistence.legacyDefaultsKey), "cleartext copy must be gone")
        let raw = try Data(contentsOf: url)
        XCTAssertTrue(raw.starts(with: Data("OSHIENC1".utf8)))
        XCTAssertNil(raw.range(of: Data("secret-ai-title".utf8)))
        // Second launch reads the sealed file and gets the same history back.
        XCTAssertEqual(AIChatPersistence.load(url: url, defaults: defaults), .data(legacy))
    }

    func testAIChatHistorySaveNeverWritesPlaintext() throws {
        let url = dir.appendingPathComponent("ai_chats.json")
        AIChatPersistence.save(Data("hello-plain".utf8), url: url)
        let raw = try Data(contentsOf: url)
        XCTAssertNil(raw.range(of: Data("hello-plain".utf8)))
        XCTAssertEqual(AIChatPersistence.load(url: url, defaults: defaults), .data(Data("hello-plain".utf8)))
    }

    func testAIChatHistoryEmptyWhenNothingStored() {
        XCTAssertEqual(AIChatPersistence.load(url: dir.appendingPathComponent("none.json"), defaults: defaults), .empty)
    }

    // #4 attachment keys: UserDefaults → Keychain, transparently.
    func testSecretBlobMigratesFromUserDefaultsToKeychain() throws {
        let account = "oshi.test.leakfix.\(UUID().uuidString)"
        defer { try? KeychainHelper.delete(key: account) }
        let legacyKey = "legacy.\(UUID().uuidString)"
        let value = ["m1": "fileKey-AAAA"]
        defaults.set(try JSONEncoder().encode(value), forKey: legacyKey)

        let loaded = V2SecretBlobStore.load([String: String].self, account: account,
                                            legacyDefaultsKey: legacyKey, defaults: defaults)
        XCTAssertEqual(loaded, value)
        XCTAssertNil(defaults.object(forKey: legacyKey), "the key material must leave UserDefaults")
        XCTAssertEqual(try KeychainHelper.load(key: account), try JSONEncoder().encode(value))

        // Save/load round trip, and empty deletes the item.
        V2SecretBlobStore.save(["m2": "k"], isEmpty: false, account: account, legacyDefaultsKey: legacyKey, defaults: defaults)
        XCTAssertEqual(V2SecretBlobStore.load([String: String].self, account: account,
                                              legacyDefaultsKey: legacyKey, defaults: defaults), ["m2": "k"])
        XCTAssertNil(defaults.object(forKey: legacyKey))
        V2SecretBlobStore.save([String: String](), isEmpty: true, account: account, legacyDefaultsKey: legacyKey, defaults: defaults)
        XCTAssertNil(V2SecretBlobStore.load([String: String].self, account: account,
                                            legacyDefaultsKey: legacyKey, defaults: defaults))
    }

    // #5 a received link is never fetched.
    func testIncomingLinkPreviewDoesNotStartANetworkFetch() {
        let f = LinkPreviewFetcher()
        f.fetchPreview(for: "https://leakfix-\(UUID().uuidString).invalid/pixel")
        XCTAssertFalse(f.isLoading)
        XCTAssertNil(f.previewData)
    }

    // #6 the push no longer carries a name; the callee resolves it locally.
    func testCallerNameResolvedLocallyFromKey() {
        let key = "LEAKFIXTESTKEY" + UUID().uuidString.replacingOccurrences(of: "-", with: "")
        let fromSentinel = VoIPPushManager.resolveCallerDisplayName(callerKey: key, pushName: VoIPPushManager.neutralCallerName)
        let fromNil = VoIPPushManager.resolveCallerDisplayName(callerKey: key, pushName: nil)
        XCTAssertEqual(fromSentinel, fromNil)
        XCTAssertTrue(fromNil.hasSuffix("..."))
        XCTAssertFalse(fromNil.isEmpty)
        // An old sender that still ships a real name keeps working.
        XCTAssertEqual(VoIPPushManager.resolveCallerDisplayName(callerKey: key, pushName: "Alice"), "Alice")
    }

    // #1 an old-format (v1) web QR is refused without any network call.
    func testV1WebQRIsRefused() async {
        let token = String(repeating: "ab", count: 32)
        let result = await WebSessionManager.shared.validateQRCode("oshi://web-auth?token=\(token)")
        XCTAssertFalse(result.success)
        XCTAssertEqual(result.message, "settings.webSessions.outdatedPage".localized)
    }
}
