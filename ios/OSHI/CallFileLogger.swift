//
//  CallFileLogger.swift
//  OSHI
//
//  Extracted from `VoiceCallManager.swift` on 2026-08-18, unchanged.
//
//  Why it moved: `V2MessageRouter` logs through it, and V2MessageRouter now also
//  builds into OSHIShare — which cannot pull in a 12 000-line call manager to get
//  a file logger. A logger buried inside the call stack was the wrong home for it
//  anyway; every target that logs can now reach it without dragging AVFoundation
//  and the whole call pipeline along.
//
//  __CALL_LOG_AT_REST_2026_09_23__ The log is now encrypted at rest.
//
//  Before: `Documents/call_debug.log` was plain text, and it is the most
//  sensitive plaintext file the app still wrote — `DIAG_MSG_DECRYPT_OK` carries
//  `preview=<first 40 chars of the DECRYPTED message>`, next to peer key
//  prefixes, IPs and push ids. The message stores were sealed on 2026-09-22
//  (`AtRestCipher`); this log sat beside them in clear.
//
//  File format ("OSHILOG1"), append-only:
//
//      "OSHILOG1" (8) | record | record | …
//      record = u32 BE length L | AES-256-GCM combined (nonce 12 ‖ ct ‖ tag 16)
//
//  One record per FLUSH, not per line: lines are buffered in memory and sealed
//  every 250 ms or 4 KB (and on logSync / call end / background), so a DIAG
//  line every 20 ms costs an append to a buffer, never a keychain read, an AES
//  pass or a syscall on the caller's thread. Each record has its own random
//  nonce and the header as AAD. A crash mid-write leaves a torn LAST record,
//  which the reader skips; a failed write is truncated back to the last record
//  boundary so later records stay parseable.
//
//  The key never leaves the device (`CallLogKey`). The user-facing export
//  decrypts, applies the same allow-list redaction as before and hands out the
//  same plain `.txt` — the only plaintext this logger ever puts on disk, in a
//  temporary folder purged at every launch.
//

import Foundation
import CryptoKit
#if canImport(UIKit)
import UIKit
#endif

class CallFileLogger {
    static let shared = CallFileLogger()

    static let currentLogName = "call_debug.log"
    static let previousLogName = "call_debug.prev.log"
    static let exportFileName = "oshi-call-diagnostics.txt"

    private let fileURL: URL
    private let prevURL: URL
    private let exportDirectory: URL
    private let queue = DispatchQueue(label: "callFileLogger", qos: .utility)
    private let keyProvider: () throws -> SymmetricKey

    // Lines waiting to be sealed. Guarded by `bufferLock`: `log()` runs on audio,
    // network and main threads and must only ever pay for an append.
    private let bufferLock = NSLock()
    private var pending = Data()
    private var flushScheduled = false
    private var urgentFlushScheduled = false

    static let flushInterval: TimeInterval = 0.25
    static let flushThresholdBytes = 4096
    /// Before the first unlock since boot neither the key nor the file can be
    /// reached. Lines are kept (never written in clear) up to this bound, then
    /// dropped: diagnostics are disposable, memory on a VoIP wake is not.
    static let maxPendingBytes = 1 << 20

    // Confined to `queue`.
    private var handle: FileHandle?
    private var committedSize: UInt64 = 0

    convenience init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        self.init(directory: docs,
                  exportDirectory: FileManager.default.temporaryDirectory
                      .appendingPathComponent("CallDiagnosticsExport", isDirectory: true),
                  keyProvider: { try CallLogKey.shared.key() },
                  observeLifecycle: true)
    }

    /// Designated init — `directory`, `exportDirectory` and `keyProvider` are
    /// injectable so tests can run against a scratch folder and a known key.
    init(directory: URL, exportDirectory: URL,
         keyProvider: @escaping () throws -> SymmetricKey,
         observeLifecycle: Bool = false) {
        fileURL = directory.appendingPathComponent(Self.currentLogName)
        prevURL = directory.appendingPathComponent(Self.previousLogName)
        self.exportDirectory = exportDirectory
        self.keyProvider = keyProvider
        // 🔧 FIX (2026-07-26): this used to TRUNCATE the log on every launch. A test
        // call that ends with the app relaunching (or a crash + relaunch before the
        // log is pulled) therefore left NO evidence anywhere — which is exactly what
        // happened to the 2026-07-24 call. Rotate instead: keep the previous run as
        // call_debug.prev.log, and start the new run's file with a marker.
        //
        // The rename is format-agnostic (sealed or legacy plaintext), so it stays
        // synchronous and cheap; everything that needs the key runs on `queue`,
        // queued BEFORE the first line so no write can overtake it.
        let fm = FileManager.default
        if fm.fileExists(atPath: fileURL.path) {
            try? fm.removeItem(at: prevURL)
            try? fm.moveItem(at: fileURL, to: prevURL)
        }
        queue.async { [self] in
            purgeExports()
            securePreviousRun()
        }
        enqueue("===== OSHI call log — run started \(Self.stamp.string(from: Date())) (previous run: call_debug.prev.log) =====\n")
        #if canImport(UIKit)
        if observeLifecycle {
            // Backgrounding is where iOS may suspend or kill us without another
            // chance: seal what is buffered now, not 250 ms later.
            for name in [UIApplication.didEnterBackgroundNotification, UIApplication.willTerminateNotification] {
                NotificationCenter.default.addObserver(forName: name, object: nil, queue: nil) { [weak self] _ in
                    self?.flush()
                }
            }
        }
        #endif
    }

    // MARK: - Export

    /// Only call-path prefixes survive the export. See `exportRedactedCallLog()`.
    static let exportAllowList: [String] = [
        "DIAG_PATH", "DIAG_JITTER", "DIAG_JITTER_JUMP", "DIAG_PLAYBACK_RATE",
        "DIAG_SNAP", "DIAG_CODEC", "DIAG_ENGINE", "DIAG_AUDIO_RX_PCM",
        "DIAG_AUDIO_TX_PCM", "DIAG_AUDIO_TRACK_CFG", "DIAG_RX_AUDIO_FIRST",
        "DIAG_TX_AUDIO_FIRST", "DIAG_TX_PRIMARY", "DIAG_ICE_TX", "DIAG_ICE_RX",
        "DIAG_NET_PATH", "DIAG_TRANSPORT_BADGE", "DIAG_FRAME_CONTRACT",
        "DIAG_ACCEPT", "DIAG_DECRYPT_FAIL", "🧭 P2P", "🔄 STATE CHANGE",
        // __EXPORT_P2P_STATE_2026_10_09__ The line that says whether the direct
        // path ever came up, and why it did not.
        //
        // Matching here is `hasPrefix`, and no existing entry is a prefix of
        // "DIAG_P2P_STATE" — "DIAG_PATH" is not — so it was being dropped from
        // every exported log. That is the ONE line that carries
        // `new=connected | reason=direct_pair_selected`, which is the only
        // place `.connected` is ever reported, and `.connected` is reachable
        // only through `pairSelectable` ⇒ a 1200/2000 B media probe has
        // round-tripped. In other words it is the proof that the direct path
        // actually carried media, and the export was blind to it.
        //
        // Safe to share: state names, a reason enum, a candidate COUNT and a
        // bool. No addresses, no ports, no identifiers.
        "DIAG_P2P_STATE",
        "===== OSHI call log",
        // __EXPORT_BLIND_TO_ACCEPT_2026_08_25__ Le chemin d'ACCEPTATION.
        //
        // Cet export a été écrit pour diagnostiquer l'audio, et il ne
        // gardait que `DIAG_*`, `🧭 P2P` et `🔄 STATE CHANGE`. Quand un
        // appel a échoué parce que l'appelée appuyait sur Accepter sans
        // rien déclencher, son export a prouvé QUE ça n'avait pas abouti
        // (pas de `DIAG_ACCEPT`, pas de transition vers `inCall`) et n'a pas
        // pu dire POURQUOI: `⚠️ handleCallKitAnswer: fast path failed`,
        // `✅ CALL ACCEPTED` et `📞 acceptCall:` étaient tous écrits dans le
        // fichier et tous jetés par ce filtre.
        //
        // Un diagnostic aveugle à la moitié des pannes qu'il doit couvrir
        // oblige à réclamer le journal brut, donc le mode développeur, donc
        // un redémarrage — à quelqu'un qui voulait juste passer un appel.
        //
        // Ces préfixes ne portent ni message, ni contact, ni contenu de
        // conversation: uniquement des états d'appel et des raisons d'échec.
        // La promesse faite en tête du fichier reste tenue.
        "✅ CALL ACCEPTED", "📞 acceptCall:", "⚠️ acceptCall:",
        "⚠️ handleCallKitAnswer:", "📞 CallKit", "❌ CALL", "📞 CALL ENDED",
        "📞 AUTO-ACCEPT", "⚠️ startCall:", "DIAG_CALLSIG", "DIAG_PUSH_RECV",
        // __EXPORT_BLIND_TO_VIDEO_2026_08_25__ Le même angle mort, côté VIDÉO.
        //
        // Un appel vidéo où l'image du correspondant ne s'affichait jamais:
        // l'export de l'autre partie a pu montrer qu'il avait ACCEPTÉ en
        // vidéo (`DIAG_ACCEPT … isVideo=true`) et que l'audio circulait dans
        // les deux sens (27 435 paquets émis, 27 317 reçus) — mais pas une
        // seule ligne sur la vidéo elle-même, parce qu'aucun `DIAG_VIDEO_*`
        // ne passait le filtre.
        //
        // « A-t-il allumé sa caméra ? » est LA question d'un appel vidéo qui
        // ne montre rien, et l'outil ne pouvait pas y répondre.
        "DIAG_VIDEO", "📹",
        // __EXPORT_BLIND_TO_THE_RATCHET_2026_08_28__ Le MÊME angle mort, une
        // troisième fois, et cette fois sur la panne la plus signalée.
        //
        // « Ils reçoivent la notification mais pas le message » est le
        // symptôme d'un ratchet désynchronisé, et les trois lignes qui le
        // décrivent — `DIAG_V2_PULL`, `DIAG_V2_UNDECRYPTABLE`,
        // `DIAG_V2_GIVEUP` — sont ÉCRITES dans le fichier depuis le
        // 2026-08-16 et JETÉES par ce filtre depuis toujours.
        //
        // `v2PullAndProcess` disait déjà pourquoi ça coûte cher: ce blind
        // spot « a fait que "notification mais rien dans la conversation" a
        // pris des jours au lieu de minutes ». Ces lignes ont été ajoutées
        // au JOURNAL pour ça. Personne ne les a ajoutées à l'EXPORT — et
        // l'export est la seule chose qu'on peut demander à QUELQU'UN
        // D'AUTRE. Quand la panne est chez le pair, on ne peut pas brancher
        // son téléphone.
        //
        // `DIAG_V2_GIVEUP` est la ligne qui compte: le moment précis où un
        // message dont l'utilisateur a DÉJÀ vu la notification est jeté pour
        // de bon.
        //
        // ⚠️ CE QUI N'EST PAS AJOUTÉ, ET POURQUOI: `DIAG_MSG_RX_ENTRY` et
        // `DIAG_MSG_DECRYPT_OK` décriraient la même panne — et portent tous
        // deux un champ `preview=` avec le TEXTE DÉCHIFFRÉ. Les laisser
        // entrer romprait la promesse en tête de ce fichier au moment même
        // où on demande cet export à un tiers. Les `DIAG_V2_*` ne portent
        // que des identifiants tronqués (8 et 12 caractères) et des
        // compteurs; `DIAG_AUTH_FAIL` ne porte que des compteurs.
        "DIAG_V2_", "DIAG_AUTH_FAIL",
        // __EXPORT_AUDIO_ENGINE_2026_09_25__ The 25/09 silent incoming calls showed
        // `DIAG_ENGINE running=false` for the whole call and the export could not say
        // WHY: the engine-start / CallKit-handover / watchdog lines were all dropped.
        // Every one of these prefixes carries only states, counters and error codes
        // (audited line by line), never message content or peer identity.
        "🎙️", "⏱️", "🔁 Audio engine watchdog", "🔧 CallKit audio",
        "✅ AUDIO ENGINE", "❌ AUDIO ENGINE", "✅ Audio engine", "❌ Audio engine",
        "⚠️ startAudioEngine"
    ]

    /// Builds a REDACTED copy of the call logs, safe to hand to someone else.
    ///
    /// 🔧 (2026-08-11) Diagnosing one side of a call needs the OTHER side's log,
    /// and there was no way to get it off a device: `UIFileSharingEnabled` is not
    /// set, so Documents is invisible in Files.app — despite the comment above
    /// telling readers to pair the rotation with it.
    ///
    /// Turning that flag on is NOT the fix. It would expose the whole Documents
    /// directory of an encrypted messenger, and this log in particular carries
    /// `DIAG_MSG_DECRYPT_OK | preview=<first 40 chars of decrypted text>` — real
    /// message content. Shipping a "send me your log" button over that would
    /// leak conversations to whoever asked for it.
    ///
    /// So this is an ALLOW-list, not a block-list: only lines whose prefix is a
    /// known call diagnostic survive. A new diagnostic is excluded until someone
    /// adds it deliberately, which fails safe; a block-list would leak every
    /// future line nobody remembered to filter.
    ///
    /// __CALL_LOG_AT_REST_2026_09_23__ Reads through the decryptor, writes the
    /// plain `.txt` into `exportDirectory` (complete file protection), which the
    /// next launch — or the next export — deletes. Blocks on the logger queue and
    /// decrypts the whole log: call it OFF the main thread (the share button does,
    /// via `CallDiagnosticsExport`).
    ///
    /// - Returns: URL of a temporary redacted file, or nil if there is nothing.
    func exportRedactedCallLog() -> URL? {
        guard let out = redactedExportText() else { return nil }
        let fm = FileManager.default
        try? fm.removeItem(at: exportDirectory)
        guard (try? fm.createDirectory(at: exportDirectory, withIntermediateDirectories: true)) != nil else { return nil }
        let dest = exportDirectory.appendingPathComponent(Self.exportFileName)
        do {
            try Data(out.utf8).write(to: dest, options: [.atomic, .completeFileProtection])
        } catch {
            return nil
        }
        return dest
    }

    /// The redacted export as text, decrypted from both runs (previous first).
    func redactedExportText() -> String? {
        let logs: [(name: String, text: String)] = queue.sync {
            drainOnQueue()
            return [prevURL, fileURL].compactMap { url in
                readPlaintextOnQueue(url).map { (url.lastPathComponent, $0) }
            }
        }
        return Self.redact(logs: logs)
    }

    /// Pure redaction — the exact output format the export always had. Kept
    /// separate so a test can prove the encrypted path yields byte-for-byte what
    /// the plaintext path did.
    static func redact(logs: [(name: String, text: String)]) -> String? {
        var out = "OSHI call diagnostics — redacted export\n"
        out += "Only call-path lines are included. Message content, contacts and\n"
        out += "conversation data are deliberately NOT in this file.\n\n"
        var kept = 0
        for log in logs {
            out += "\n===== \(log.name) =====\n"
            for line in log.text.split(separator: "\n", omittingEmptySubsequences: true) {
                // Timestamps are bracketed at the head, so match on what follows.
                let body = line.drop { $0 != "]" }.dropFirst().trimmingCharacters(in: .whitespaces)
                let candidate = body.isEmpty ? String(line) : body
                if exportAllowList.contains(where: { candidate.hasPrefix($0) }) {
                    out += candidate + "\n"
                    kept += 1
                }
            }
        }
        return kept > 0 ? out : nil
    }

    /// Cheap enough for a SwiftUI body: a stat per file, no decryption.
    var hasDiagnostics: Bool {
        let fm = FileManager.default
        return fm.fileExists(atPath: fileURL.path) || fm.fileExists(atPath: prevURL.path)
            || bufferLock.withLock { !pending.isEmpty }
    }

    /// Decrypted content of both runs, previous first — for tests and any in-app
    /// viewer. Never write the result to disk.
    func decryptedLogs() -> [(name: String, text: String)] {
        queue.sync {
            drainOnQueue()
            return [prevURL, fileURL].compactMap { url in
                readPlaintextOnQueue(url).map { (url.lastPathComponent, $0) }
            }
        }
    }

    // MARK: - Writing

    /// 🔧 FIX (2026-08-11): a bare `ISO8601DateFormatter()` truncates to the
    /// SECOND. Every timestamp in this log was therefore a whole second, which
    /// made it impossible to answer "how long from the server responding to the
    /// message appearing?" — an attempt to measure it from a real capture
    /// returned a median of exactly 4.00 s, which is the granularity talking,
    /// not the app. Sub-second work cannot be measured with a second-resolution
    /// ruler. Built once, not per line: constructing a formatter costs a locale
    /// and an ICU pattern lookup, and this runs on every diagnostic write.
    private static let stamp: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    func log(_ message: String) {
        enqueue("[\(Self.stamp.string(from: Date()))] \(message)\n")
    }

    /// Synchronous log for critical events (endCall, state changes) — guarantees write before returning.
    ///
    /// 🔧 FIX (2026-07-27): NEVER `queue.sync` from the main thread. `callState.didSet`
    /// calls this TWICE (plus `Thread.callStackSymbols`, which symbolicates) and
    /// `endCall` publishes `.ended` inside a SwiftUI `withAnimation` — so every call
    /// state change parked the main thread on a `.utility` serial queue from inside a
    /// SwiftUI transaction. That is precisely the shape that produced the 0x8BADF00D
    /// watchdog kills fixed in 6ec6ca9/16be239: main blocked on `queue.sync` while a
    /// background thread holds/needs something main owns. Diagnostics must never be
    /// able to hang the app; degrade to the async writer on main (ordering on the
    /// logger queue is preserved either way — it is the same serial queue).
    /// Set `oshi.call.logSyncOnMain` = true to restore the old blocking behaviour.
    ///
    /// With buffering, "async" on main now means "flush immediately, off main"
    /// rather than "wait for the 250 ms tick", so critical lines keep their head start.
    func logSync(_ message: String) {
        enqueue("[\(Self.stamp.string(from: Date()))] \(message)\n", schedule: false)
        if Thread.isMainThread && !UserDefaults.standard.bool(forKey: "oshi.call.logSyncOnMain") {
            flush()
            return
        }
        queue.sync { drainOnQueue() }
    }

    /// Seals whatever is buffered, without waiting. Call on call end / background.
    func flush() {
        queue.async { [self] in drainOnQueue() }
    }

    /// Seals whatever is buffered and waits for the write. Not from the main thread.
    func flushAndWait() {
        queue.sync { drainOnQueue() }
    }

    private func enqueue(_ line: String, schedule: Bool = true) {
        bufferLock.lock()
        pending.append(contentsOf: line.utf8)
        var scheduleTick = false, scheduleNow = false
        if schedule {
            if !flushScheduled { flushScheduled = true; scheduleTick = true }
            if pending.count >= Self.flushThresholdBytes && !urgentFlushScheduled {
                urgentFlushScheduled = true; scheduleNow = true
            }
        }
        bufferLock.unlock()
        if scheduleNow {
            queue.async { [self] in drainOnQueue() }
        } else if scheduleTick {
            queue.asyncAfter(deadline: .now() + Self.flushInterval) { [self] in drainOnQueue() }
        }
    }

    /// Seals the buffer as ONE record and appends it. Runs on `queue` only.
    private func drainOnQueue() {
        bufferLock.lock()
        let chunk = pending
        pending = Data()
        flushScheduled = false
        urgentFlushScheduled = false
        bufferLock.unlock()
        guard !chunk.isEmpty else { return }
        do {
            try appendOnQueue(record: CallLogRecords.seal(chunk, key: keyProvider()))
        } catch {
            // Key or file unreachable (before first unlock, disk full): keep the
            // lines for the next flush — never fall back to writing them in clear.
            bufferLock.lock()
            if chunk.count + pending.count <= Self.maxPendingBytes {
                pending = chunk + pending
            }
            bufferLock.unlock()
        }
    }

    private func appendOnQueue(record: Data) throws {
        if handle == nil {
            if !FileManager.default.fileExists(atPath: fileURL.path) {
                // AfterFirstUnlock, not Complete: calls are logged from VoIP
                // wakes with the screen locked.
                try CallLogRecords.magic.write(to: fileURL, options: [.completeFileProtectionUntilFirstUserAuthentication])
            }
            let h = try FileHandle(forWritingTo: fileURL)
            committedSize = try h.seekToEnd()
            handle = h
        }
        do {
            try handle?.write(contentsOf: record)
            committedSize += UInt64(record.count)
        } catch {
            // A partial write would shift every later record off its boundary:
            // cut back to the last whole record before anything else is appended.
            try? handle?.truncate(atOffset: committedSize)
            try? handle?.close()
            handle = nil
            throw error
        }
    }

    // MARK: - Reading, migration, cleanup (all on `queue`)

    /// Decrypted text of one log file, or nil if absent / unreadable. A sealed
    /// file that NO record of opens was written under a key this device no
    /// longer has (restore, keychain reset): it can never be read again, so it
    /// is dropped silently and logging starts fresh.
    private func readPlaintextOnQueue(_ url: URL) -> String? {
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe) else { return nil }
        guard data.starts(with: CallLogRecords.magic) else {
            // Not ours to interpret as records; `securePreviousRun` converts
            // legacy plaintext before any read can get here.
            return String(decoding: data, as: UTF8.self)
        }
        guard let key = try? keyProvider() else { return nil }
        let decoded = CallLogRecords.decode(data, key: key)
        if decoded.isUnreadable {
            if url == fileURL { try? handle?.close(); handle = nil }
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        return String(decoding: decoded.plaintext, as: UTF8.self)
    }

    /// First-launch migration of a plaintext log left by an older version, and
    /// key-loss cleanup of a sealed one. Only the previous run can be legacy:
    /// the current file is always new, and the rotation above moved the old one.
    private func securePreviousRun() {
        let fm = FileManager.default
        guard let data = try? Data(contentsOf: prevURL, options: .mappedIfSafe) else { return }
        if data.starts(with: CallLogRecords.magic) {
            // Locked keychain ⇒ no verdict; a readable key that opens nothing ⇒ lost.
            if let key = try? keyProvider(), CallLogRecords.decode(data, key: key).isUnreadable {
                try? fm.removeItem(at: prevURL)
            }
            return
        }
        // Legacy plaintext. Seal it in chunks (a long call writes megabytes) into
        // a sibling file, then swap it in; if that cannot be done right now, the
        // plaintext is deleted rather than left behind — it is only diagnostics.
        let sealedURL = prevURL.appendingPathExtension("sealing")
        defer { try? fm.removeItem(at: sealedURL) }
        do {
            let key = try keyProvider()
            var out = CallLogRecords.magic
            let chunkSize = 64 * 1024
            var offset = data.startIndex
            while offset < data.endIndex {
                let end = min(offset + chunkSize, data.endIndex)
                out.append(try CallLogRecords.seal(data[offset..<end], key: key))
                offset = end
            }
            try out.write(to: sealedURL, options: [.completeFileProtectionUntilFirstUserAuthentication])
            _ = try fm.replaceItemAt(prevURL, withItemAt: sealedURL)
        } catch {
            try? fm.removeItem(at: prevURL)
        }
    }

    /// The last export (plaintext by design) is only needed while the share sheet
    /// copies it; nothing tells us when that is, so it dies at the next launch.
    /// Also removes where versions ≤ 2026-09 dropped it, straight in tmp/.
    private func purgeExports() {
        let fm = FileManager.default
        try? fm.removeItem(at: exportDirectory)
        try? fm.removeItem(at: fm.temporaryDirectory.appendingPathComponent(Self.exportFileName))
    }
}

// MARK: - Record format

/// "OSHILOG1" | (u32 BE length | AES-GCM combined)* — see the file header.
enum CallLogRecords {
    static let magic = Data("OSHILOG1".utf8)
    /// nonce + tag: a record can never be shorter than this.
    static let minRecordLength = 12 + 16
    /// Far above any flush (≤ `maxPendingBytes`); a larger prefix is garbage.
    static let maxRecordLength = 16 << 20

    struct Decoded {
        var plaintext = Data()
        var opened = 0
        var rejected = 0
        /// Trailing bytes too short to be a whole record (crash mid-write).
        var tornTail = false
        /// Records exist and none opens: wrong key, not a torn write.
        var isUnreadable: Bool { opened == 0 && rejected > 0 }
    }

    /// Seals one record with a fresh random nonce (CryptoKit's default).
    static func seal<D: DataProtocol>(_ plaintext: D, key: SymmetricKey) throws -> Data {
        let box = try AES.GCM.seal(plaintext, using: key, authenticating: magic)
        guard let combined = box.combined else { throw CryptoKitError.incorrectParameterSize }
        var record = Data(capacity: 4 + combined.count)
        let length = UInt32(combined.count)
        record.append(contentsOf: [UInt8(length >> 24 & 0xff), UInt8(length >> 16 & 0xff),
                                   UInt8(length >> 8 & 0xff), UInt8(length & 0xff)])
        record.append(combined)
        return record
    }

    /// Opens every whole record in order. A record that fails authentication is
    /// skipped (its length still locates the next); a torn tail ends the walk.
    static func decode(_ file: Data, key: SymmetricKey) -> Decoded {
        var result = Decoded()
        guard file.starts(with: magic) else { return result }
        let bytes = [UInt8](file)
        var i = magic.count
        while i < bytes.count {
            guard bytes.count - i >= 4 else { result.tornTail = true; break }
            let length = Int(bytes[i]) << 24 | Int(bytes[i + 1]) << 16 | Int(bytes[i + 2]) << 8 | Int(bytes[i + 3])
            let start = i + 4
            guard length >= minRecordLength, length <= maxRecordLength,
                  bytes.count - start >= length else {
                result.tornTail = true
                break
            }
            let body = Data(bytes[start..<start + length])
            if let box = try? AES.GCM.SealedBox(combined: body),
               let plain = try? AES.GCM.open(box, using: key, authenticating: magic) {
                result.plaintext.append(plain)
                result.opened += 1
            } else {
                result.rejected += 1
            }
            i = start + length
        }
        return result
    }
}

// MARK: - Key

/// __CALL_LOG_AT_REST_2026_09_23__ 32 random bytes in the keychain via
/// `KeychainHelper` (AfterFirstUnlockThisDeviceOnly: never backed up, never
/// migrated, readable on a locked-screen VoIP wake once the phone has been
/// unlocked since boot).
///
/// Its own key rather than `AtRestCipher`'s: this file also builds into
/// OSHIShare, which cannot reach the app's private keychain group, and a
/// throwaway diagnostic must not share a key with the message store.
final class CallLogKey {
    static let shared = CallLogKey()

    /// Per-bundle account name. OSHIShare's DEFAULT keychain group is the shared
    /// one, and the app's unscoped loads search every group it can reach: one
    /// account name for both would let either process pick up the other's key
    /// and silently lose its own logs.
    let account = "oshi.calllog.atRestKey.v1." + (Bundle.main.bundleIdentifier ?? "unknown")

    private let lock = NSLock()
    private var cached: SymmetricKey?

    func key() throws -> SymmetricKey {
        lock.lock(); defer { lock.unlock() }
        if let k = cached { return k }
        do {
            let raw = try KeychainHelper.load(key: account)
            guard raw.count == 32 else { throw KeychainError.unexpectedData }
            cached = SymmetricKey(data: raw)
        } catch KeychainError.itemNotFound {
            // Only a MISSING key is replaced. A locked keychain throws and the
            // caller retries later: replacing it would orphan every sealed log.
            let fresh = SymmetricKey(size: .bits256)
            try KeychainHelper.save(key: account, data: fresh.withUnsafeBytes { Data($0) })
            cached = fresh
        }
        return cached!
    }
}
