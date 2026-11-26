//
//  ChatOpenPerformanceTests.swift
//  OSHITests
//
//  __CHAT_OPEN_PERF_2026_11_14__ Reproducible benchmark for "opening a long,
//  media-heavy conversation stutters" (1:1 and groups), plus the guarantees
//  that the faster code produces the same rows.
//
//  Fixture: a 1:1 thread of 3000 messages (300 photos, 30 videos, 60
//  documents, 90 voice notes, 100 hidden control payloads, a few late
//  arrivals) inside a global store that also holds 6000 messages of six other
//  contacts; and a group window of 50 rows, 20 of them media whose bytes are
//  encrypted on disk exactly as the app stores them.
//
//  Each benchmark times the code path that used to run on the MAIN thread
//  ("legacy", copied verbatim from the previous implementation below) against
//  what runs on the main thread now. Results are printed as `PERF|` lines.
//  Timings are medians; absolute numbers depend on the machine and on the
//  Debug (-Onone) build the test target uses, so the asserts only check
//  direction, with generous slack.
//

import XCTest
import AVFoundation
@testable import OSHI

@MainActor
final class ChatOpenPerformanceTests: XCTestCase {

    // MARK: - Fixture

    private static func key(_ seed: UInt8) -> String {
        Data((0..<32).map { UInt8(truncatingIfNeeded: Int($0) &* 7 &+ Int(seed)) }).base64EncodedString()
    }
    private let me = key(1)
    private let peer = key(2)
    private let others = (3...8).map { key(UInt8($0)) }

    private static var jpegs: [Data] = []
    private static var smallJPEG = Data()
    private static var voiceNote = Data()

    override class func setUp() {
        super.setUp()
        jpegs = (0..<6).map { makeJPEG(seed: $0, size: CGSize(width: 1600, height: 1200)) }
        smallJPEG = makeJPEG(seed: 9, size: CGSize(width: 400, height: 300))
        voiceNote = makeVoiceNote(seconds: 6)
    }

    /// A photo-like JPEG (noise + shapes), so the decoder does real work.
    private static func makeJPEG(seed: Int, size: CGSize) -> Data {
        var rng = SeededRNG(seed: UInt64(seed + 1))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            for _ in 0..<600 {
                UIColor(red: .random(in: 0...1, using: &rng), green: .random(in: 0...1, using: &rng),
                        blue: .random(in: 0...1, using: &rng), alpha: 1).setFill()
                let w = CGFloat.random(in: 10...300, using: &rng), h = CGFloat.random(in: 10...300, using: &rng)
                ctx.fill(CGRect(x: .random(in: 0...size.width, using: &rng),
                                y: .random(in: 0...size.height, using: &rng), width: w, height: h))
            }
        }
        return image.jpegData(compressionQuality: 0.8)!
    }

    /// A real AAC voice note, as the recorder produces.
    private static func makeVoiceNote(seconds: Double) -> Data {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("perf-voice-\(UUID().uuidString).m4a")
        defer { try? FileManager.default.removeItem(at: url) }
        let rate = 16_000.0
        do {
            let file = try AVAudioFile(forWriting: url, settings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: rate, AVNumberOfChannelsKey: 1,
            ])
            let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1)!
            let frames = AVAudioFrameCount(rate * seconds)
            let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames)!
            buffer.frameLength = frames
            let samples = buffer.floatChannelData![0]
            for i in 0..<Int(frames) { samples[i] = Float(sin(Double(i) * 0.05)) * 0.3 }
            try file.write(from: buffer)
        } catch {
            return Data()
        }
        return (try? Data(contentsOf: url)) ?? Data()
    }

    private func message(id: String, from: String, to: String, at t: Date, text: String? = nil,
                         media: Data? = nil, type: MediaManager.MediaType? = nil,
                         isRead: Bool = true) -> SecureMessage {
        SecureMessage(id: id, senderAddress: from, recipientAddress: to,
                      encryptedContent: EncryptedMessage(ciphertext: "", signature: "", senderPublicKey: from, timestamp: t),
                      timestamp: t, isRead: isRead, deliveryStatus: .read,
                      senderPublicKey: from, recipientPublicKey: to,
                      plaintextContent: text, mediaAttachment: media, mediaType: type)
    }

    /// The global 1:1 store. `run` makes every id unique, so no cache is warm.
    private func makeStore(run: Int = 0) -> [SecureMessage] {
        var rng = SeededRNG(seed: 42)
        let start = Date().addingTimeInterval(-120 * 86_400)
        var store: [SecureMessage] = []
        store.reserveCapacity(9_000)
        // Other contacts, interleaved over the same period.
        for i in 0..<6_000 {
            let other = others[i % others.count]
            let t = start.addingTimeInterval(Double(i) * 1_700)
            let incoming = i % 2 == 0
            store.append(message(id: "o\(run)-\(i)", from: incoming ? other : me, to: incoming ? me : other,
                                 at: t, text: "Other thread message \(i) with some words in it"))
        }
        // The thread being opened.
        for i in 0..<3_000 {
            var t = start.addingTimeInterval(Double(i) * 3_400 + 17)
            // A few messages arrive late (delivery delayed): the store is not in order.
            if i % 211 == 0 && i > 0 { t = t.addingTimeInterval(-7_200) }
            let incoming = Bool.random(using: &rng)
            // Every 25th incoming peer key uses the base64url spelling.
            let peerSpelling = i % 25 == 0
                ? peer.replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
                    .replacingOccurrences(of: "=", with: "")
                : peer
            let from = incoming ? peerSpelling : me
            let to = incoming ? me : peerSpelling
            let id = "t\(run)-\(i)"
            let isTail = i >= 2_960   // newest 40: an album, voice notes, a video
            let m: SecureMessage
            if (isTail && i % 3 == 0) || (!isTail && i % 10 == 3) {
                m = message(id: id, from: from, to: to, at: t, media: Self.jpegs[i % Self.jpegs.count], type: .image)
            } else if (isTail && i % 13 == 1) || (!isTail && i % 100 == 7) {
                m = message(id: id, from: from, to: to, at: t, media: Data(count: 2_000_000), type: .video)
            } else if !isTail && i % 50 == 11 {
                m = message(id: id, from: from, to: to, at: t, media: Data(count: 400_000), type: .document)
            } else if (isTail && i % 7 == 2) || (!isTail && i % 33 == 5) {
                m = message(id: id, from: from, to: to, at: t, media: Self.voiceNote, type: .audio)
            } else if i % 30 == 29 {
                m = message(id: id, from: from, to: to, at: t, text: OSHIControlPayload.profileUpdatePrefix + "{}")
            } else {
                m = message(id: id, from: from, to: to, at: t,
                            text: "Message \(i): on se retrouve où ? https://example.org/\(i)",
                            isRead: !(incoming && i > 2_990))
            }
            store.append(m)
        }
        // Arrival order: by timestamp, except the late ones appended at the end.
        let late = store.filter { $0.id.hasPrefix("t\(run)-") && (Int($0.id.split(separator: "-").last!)! % 211 == 0) }
        let lateIds = Set(late.map(\.id))
        var arrival = store.filter { !lateIds.contains($0.id) }.sorted { $0.timestamp < $1.timestamp }
        arrival.append(contentsOf: late)
        return arrival
    }

    // MARK: - Timing

    private func median(_ runs: Int = 5, _ body: () -> Void) -> Double {
        var samples: [Double] = []
        for _ in 0..<runs {
            let t0 = DispatchTime.now().uptimeNanoseconds
            body()
            samples.append(Double(DispatchTime.now().uptimeNanoseconds - t0) / 1_000_000)
        }
        return samples.sorted()[samples.count / 2]
    }

    private func report(_ name: String, before: Double, after: Double, note: String = "") {
        let ratio = after > 0 ? before / after : .infinity
        print(String(format: "PERF| %@ | before %.2f ms | after %.2f ms | x%.1f %@", name, before, after, ratio, note))
    }

    // MARK: - 1:1 thread derivation (main thread, every publish of the store)

    func testThreadDerivationMatchesLegacyAndIsFaster() {
        let store = makeStore()
        let legacy = LegacyChatPipeline(peer: peer, me: me)

        // Same rows, same headers, same "earlier" flag — default window and a
        // grown one ("load earlier").
        for limit in [200, 500, 5_000] {
            let old = legacy.build(source: store, filter: .init(), limit: limit)
            let new = ChatThreadPipeline.build(source: store, peerKey: peer, myKey: me,
                                               filter: .init(), windowLimit: limit)
            XCTAssertEqual(old.loaded.map(\.id), new.loaded.map(\.id), "loaded @\(limit)")
            XCTAssertEqual(old.byDate.map(\.0), new.byDate.map(\.0), "headers @\(limit)")
            XCTAssertEqual(old.byDate.map { $0.1.map(\.id) }, new.byDate.map { $0.1.map(\.id) }, "rows @\(limit)")
            XCTAssertEqual(old.hasEarlier, new.hasEarlier, "hasEarlier @\(limit)")
        }
        XCTAssertEqual(ChatThreadPipeline.build(source: store, peerKey: peer, myKey: me,
                                                filter: .init(), windowLimit: 200).loaded.count, 3_000,
                       "base64url spellings must stay in the thread")

        // Search and date filters: whole thread, same result.
        let day = store.first { $0.id == "t0-1500" }!.timestamp
        for filter in [LegacyChatPipeline.Filter(search: "retrouve où ? https://example.org/27"),
                       LegacyChatPipeline.Filter(search: "zzz-no-match"),
                       LegacyChatPipeline.Filter(singleDate: day),
                       LegacyChatPipeline.Filter(rangeStart: day.addingTimeInterval(-3 * 86_400), rangeEnd: day)] {
            let old = legacy.build(source: store, filter: filter, limit: 200)
            let new = ChatThreadPipeline.build(source: store, peerKey: peer, myKey: me,
                                               filter: filter.pipeline, windowLimit: 200)
            XCTAssertEqual(old.filtered.map(\.id), new.filtered.map(\.id))
            XCTAssertEqual(old.byDate.map(\.0), new.byDate.map(\.0))
            XCTAssertEqual(old.byDate.map { $0.1.map(\.id) }, new.byDate.map { $0.1.map(\.id) })
        }

        let before = median { _ = legacy.build(source: store, filter: .init(), limit: 200) }
        let after = median {
            _ = ChatThreadPipeline.build(source: store, peerKey: peer, myKey: me, filter: .init(), windowLimit: 200)
        }
        report("1:1 derivation per publish (9000-msg store, 3000-msg thread)", before: before, after: after)
        XCTAssertLessThan(after, before * 1.2)
    }

    func testUnreadAnchorIsARenderedRow() {
        var store = makeStore()
        // 400 unread messages from the peer: more than the 200-row window.
        for i in store.indices.suffix(900) where store[i].senderPublicKey == peer && store[i].plaintextContent != nil {
            store[i].isRead = false
        }
        let out = ChatThreadPipeline.build(source: store, peerKey: peer, myKey: me, filter: .init(), windowLimit: 200)
        let rendered = Set(out.byDate.flatMap { $0.1.map(\.id) })
        let anchor = ChatThreadPipeline.firstUnreadIncoming(in: out.byDate, myKey: me)
        XCTAssertNotNil(anchor)
        XCTAssertTrue(rendered.contains(anchor!), "the divider must sit on a row that exists")
    }

    func testReplyTargetAboveTheWindowCanBeRevealed() {
        let store = makeStore()
        let out = ChatThreadPipeline.build(source: store, peerKey: peer, myKey: me, filter: .init(), windowLimit: 200)
        let target = out.loaded[100]   // far above the window
        XCTAssertFalse(out.byDate.contains { $0.1.contains { $0.id == target.id } })
        let needed = ChatThreadPipeline.windowLimitToInclude(target.id, in: out.loaded)!
        let grown = ChatThreadPipeline.derive(loaded: out.loaded, filter: .init(), windowLimit: needed)
        XCTAssertTrue(grown.byDate.contains { $0.1.contains { $0.id == target.id } })
        // A hidden control payload has no row to reveal.
        let hidden = out.loaded.first { !ChatThreadPipeline.isDisplayable($0) }!
        XCTAssertNil(ChatThreadPipeline.windowLimitToInclude(hidden.id, in: out.loaded))
    }

    // MARK: - 1:1 first screen media (main thread at open)

    /// The newest 40 rows of the thread hold an album of photos, voice notes
    /// and a video, as a real media-heavy chat does. Legacy: what the bubbles
    /// did on main as they appeared. Now: what still runs on main (cache
    /// lookups and scheduling); the decodes run on background tasks.
    func testFirstScreenMediaLeavesTheMainThread() async {
        var legacySamples: [Double] = []
        var mainSamples: [Double] = []
        var readySamples: [Double] = []
        for run in 1...3 {
            // Unique ids per run, so every cache starts cold.
            let rows = ChatThreadPipeline.build(source: makeStore(run: run), peerKey: peer, myKey: me,
                                                filter: .init(), windowLimit: 200)
                .byDate.flatMap(\.1).suffix(40)
            let media = rows.filter { $0.mediaType != nil }
            XCTAssertGreaterThanOrEqual(media.filter { $0.mediaType == .image }.count, 10)

            // Legacy, on main.
            let t0 = DispatchTime.now().uptimeNanoseconds
            for m in media {
                switch m.mediaType {
                case .image:
                    _ = MediaAttachmentCache.shared.decodedImage(from: m.mediaAttachment!, id: "L\(m.id)", maxDimension: 800)
                case .video:
                    _ = UIImage(data: Self.smallJPEG)?.preparingForDisplay()   // poster inflated at draw
                case .audio:
                    _ = try? AVAudioPlayer(data: m.mediaAttachment!)
                    _ = InlineVoiceNoteView.makeBars(from: m.mediaAttachment!, count: 34)
                default: break
                }
            }
            legacySamples.append(Double(DispatchTime.now().uptimeNanoseconds - t0) / 1_000_000)

            // Now: the synchronous part each row runs on main, then the wait
            // (off main) until every picture and duration is ready.
            let t1 = DispatchTime.now().uptimeNanoseconds
            var pending: [Task<Void, Never>] = []
            for m in media {
                switch m.mediaType {
                case .image:
                    if ChatImageLoader.shared.cached(id: m.id, maxDimension: 800) == nil {
                        let data = m.mediaAttachment!
                        let id = m.id
                        pending.append(Task { _ = await ChatImageLoader.shared.load(id: id, data: data, maxDimension: 800) })
                    }
                case .video:
                    let id = m.id + "#poster"
                    if ChatImageLoader.shared.cached(id: id, maxDimension: 400) == nil {
                        let poster = Self.smallJPEG
                        pending.append(Task { _ = await ChatImageLoader.shared.load(id: id, data: poster, maxDimension: 400) })
                    }
                case .audio:
                    _ = InlineVoiceNoteView.makeBars(from: m.mediaAttachment!, count: 34)
                    if VoiceNoteDurationCache.cached(m.id) == nil {
                        let data = m.mediaAttachment!
                        let id = m.id
                        pending.append(Task { _ = await VoiceNoteDurationCache.duration(id: id, data: data) })
                    }
                default: break
                }
            }
            let t2 = DispatchTime.now().uptimeNanoseconds
            for task in pending { await task.value }
            let t3 = DispatchTime.now().uptimeNanoseconds
            mainSamples.append(Double(t2 - t1) / 1_000_000)
            readySamples.append(Double(t3 - t1) / 1_000_000)

            // Every photo is now a cache hit at its final size.
            for m in media where m.mediaType == .image {
                let image = ChatImageLoader.shared.cached(id: m.id, maxDimension: 800)
                XCTAssertNotNil(image)
                XCTAssertLessThanOrEqual(max(image!.size.width, image!.size.height) * image!.scale, 800.5)
            }
        }
        let before = legacySamples.sorted()[1], after = mainSamples.sorted()[1]
        report("1:1 first screen media, main thread (40 newest rows)", before: before, after: after,
               note: String(format: "| all ready off main after %.2f ms", readySamples.sorted()[1]))
        XCTAssertLessThan(after, before)
    }

    // MARK: - Group media rows (main thread, every body pass)

    private func makeGroupWindow(inMemory: Bool) throws -> (rows: [GroupMessage], files: [URL]) {
        let groupId = UUID()
        var rows: [GroupMessage] = []
        var files: [URL] = []
        let start = Date().addingTimeInterval(-3_600)
        for i in 0..<50 {
            let id = UUID()
            let t = start.addingTimeInterval(Double(i) * 60)
            var type: MediaType? = nil
            var bytes = Data()
            switch i % 5 {
            case 0, 2: type = .photo; bytes = Self.jpegs[i % Self.jpegs.count]
            case 4 where i % 3 == 0: type = .video; bytes = Data(count: 2_000_000)
            case 4: type = .document; bytes = Data(count: 500_000)
            default: break
            }
            var m = GroupMessage(id: id, groupId: groupId, senderPublicKey: i % 2 == 0 ? peer : me,
                                 encryptedContent: Data("Group message \(i)".utf8), timestamp: t,
                                 isRead: true, messageChainIndex: i)
            if let type {
                m.mediaType = type
                m.encryptedContent = Data()   // trimmed at rest (compactedForDisk)
                if inMemory {
                    m.mediaData = bytes
                } else {
                    XCTAssertTrue(MessageGroupManager.writeGroupMediaFile(bytes, for: id.uuidString, type: type))
                }
                if let url = MessageGroupManager.groupMediaFileURL(for: id.uuidString, type: type) { files.append(url) }
            } else {
                m.plaintextContent = "Group message \(i)"
            }
            rows.append(m)
        }
        return (rows, files)
    }

    /// Legacy `GroupChatView.loadGroupMediaData`, as it ran in the body.
    private func legacyLoadGroupMediaData(_ message: GroupMessage) -> Data? {
        guard let mediaType = message.mediaType else { return nil }
        if let inMemory = message.mediaData, !inMemory.isEmpty {
            _ = MessageGroupManager.writeGroupMediaFile(inMemory, for: message.id.uuidString, type: mediaType)
            return inMemory
        }
        if let data = MessageGroupManager.readGroupMediaFile(for: message.id.uuidString, type: mediaType) {
            return data
        }
        if message.encryptedContent.count > 100 { return message.encryptedContent }
        return nil
    }

    func testGroupMediaRowsNoLongerReadDiskOnMain() async throws {
        for inMemory in [false, true] {
            let (rows, files) = try makeGroupWindow(inMemory: inMemory)
            defer { for f in files { try? FileManager.default.removeItem(at: f) } }
            let media = rows.filter { $0.mediaType != nil }
            XCTAssertEqual(media.count, 30)

            // Legacy: EVERY body pass of the group view re-ran this for every
            // media row of the (eager) window.
            let before = median(3) { for m in media { _ = legacyLoadGroupMediaData(m) } }

            // Now, first open: the body only asks the store; loads run off main.
            let t0 = DispatchTime.now().uptimeNanoseconds
            var pending: [Task<Data?, Never>] = []
            for m in media where GroupMediaStore.shared.cachedData(for: m) == nil {
                pending.append(Task { await GroupMediaStore.shared.load(m) })
            }
            let firstPassMain = Double(DispatchTime.now().uptimeNanoseconds - t0) / 1_000_000
            var loaded = 0
            for task in pending where await task.value != nil { loaded += 1 }
            let ready = Double(DispatchTime.now().uptimeNanoseconds - t0) / 1_000_000
            if !inMemory { XCTAssertEqual(loaded, media.count) }

            // Same bytes as the legacy path.
            for m in media {
                XCTAssertEqual(GroupMediaStore.shared.cachedData(for: m), legacyLoadGroupMediaData(m))
            }
            // Every later body pass: memory only.
            let later = median(3) { for m in media { _ = GroupMediaStore.shared.cachedData(for: m) } }

            let label = inMemory ? "fresh, bytes in memory" : "at rest, encrypted on disk"
            report("group body pass, 30 media rows (\(label)) — first pass", before: before, after: firstPassMain,
                   note: String(format: "| all loaded off main after %.2f ms", ready))
            report("group body pass, 30 media rows (\(label)) — every later pass", before: before, after: later)
            XCTAssertLessThan(later, before)
            for m in media { GroupMediaStore.shared.remove(m.id) }
        }
    }

    func testGroupWindowSkipsTheSortWhenInOrder() {
        let start = Date()
        let inOrder = (0..<2_000).map { i in
            GroupMessage(id: UUID(), groupId: UUID(), senderPublicKey: peer, encryptedContent: Data(),
                         timestamp: start.addingTimeInterval(Double(i)), isRead: true, messageChainIndex: i)
        }
        XCTAssertTrue(GroupChatView.isChronological(inOrder))
        var shuffled = inOrder
        shuffled.swapAt(10, 1_500)
        XCTAssertFalse(GroupChatView.isChronological(shuffled))
        XCTAssertTrue(GroupChatView.isChronological([]))
    }
}

// MARK: - Legacy 1:1 derivation (verbatim from ChatView before 2026-11-14)

private struct LegacyChatPipeline {
    let peer: String
    let me: String

    struct Filter {
        var search = ""
        var singleDate: Date? = nil
        var rangeStart: Date? = nil
        var rangeEnd: Date? = nil
        var isFiltering: Bool { !search.isEmpty || singleDate != nil || (rangeStart != nil && rangeEnd != nil) }
        var pipeline: ChatThreadPipeline.Filter {
            .init(searchText: search, singleDate: singleDate, rangeStart: rangeStart, rangeEnd: rangeEnd,
                  searchDateFormatters: LegacyChatPipeline.formatters)
        }
    }

    static let formatters: [DateFormatter] = [
        "MMM d, yyyy", "MMMM d, yyyy", "MMM d", "MMMM d",
        "MMM yyyy", "MMMM yyyy", "MMM", "MMMM", "yyyy",
        "dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd", "EEEE",
    ].map { fmt in let f = DateFormatter(); f.dateFormat = fmt; return f }

    struct Output {
        var loaded: [SecureMessage]
        var filtered: [SecureMessage]
        var byDate: [(String, [SecureMessage])]
        var hasEarlier: Bool
    }

    func build(source: [SecureMessage], filter: Filter, limit: Int) -> Output {
        let loaded = computeLoadedMessages(from: source)
        let filtered = computeFilteredMessages(from: loaded, filter: filter)
        let windowed = filter.isFiltering ? filtered : Array(filtered.suffix(limit))
        return Output(loaded: loaded, filtered: filtered,
                      byDate: computeMessagesByDate(from: windowed),
                      hasEarlier: windowed.count < filtered.count)
    }

    private func computeLoadedMessages(from source: [SecureMessage]) -> [SecureMessage] {
        let peer = MessageManager.canonicalIdentity(self.peer)
        let me = MessageManager.canonicalIdentity(self.me)
        return source.filter { message in
            let sender = MessageManager.canonicalIdentity(message.senderPublicKey)
            let recipient = MessageManager.canonicalIdentity(message.recipientPublicKey)
            return (recipient == peer && sender == me) || (sender == peer && recipient == me)
        }.sorted { $0.timestamp < $1.timestamp }
    }

    private func computeFilteredMessages(from loaded: [SecureMessage], filter: Filter) -> [SecureMessage] {
        var messages = loaded
        messages = messages.filter { message in
            guard let plaintext = message.plaintextContent else { return true }
            return !OSHIControlPayload.isHiddenFromThread(plaintext)
        }
        let calendar = Calendar.current
        if let singleDate = filter.singleDate {
            messages = messages.filter { message in
                calendar.isDate(message.timestamp, inSameDayAs: singleDate)
            }
        } else if let start = filter.rangeStart, let end = filter.rangeEnd {
            messages = messages.filter { message in
                let startOfDay = calendar.startOfDay(for: start)
                let endOfDay = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: end)) ?? end
                return message.timestamp >= startOfDay && message.timestamp < endOfDay
            }
        }
        let searchText = filter.search
        if !searchText.isEmpty {
            messages = messages.filter { message in
                if let plaintext = message.plaintextContent,
                   plaintext.localizedCaseInsensitiveContains(searchText) {
                    return true
                }
                let searchLower = searchText.lowercased().trimmingCharacters(in: .whitespaces)
                let todayLocalized = "messages.today".localized.lowercased()
                let yesterdayLocalized = "messages.yesterday".localized.lowercased()
                if searchLower == todayLocalized && calendar.isDateInToday(message.timestamp) {
                    return true
                }
                if searchLower == yesterdayLocalized && calendar.isDateInYesterday(message.timestamp) {
                    return true
                }
                for formatter in Self.formatters {
                    let messageDate = formatter.string(from: message.timestamp)
                    if messageDate.localizedCaseInsensitiveContains(searchText) {
                        return true
                    }
                }
                return false
            }
        }
        return messages
    }

    private func computeMessagesByDate(from filtered: [SecureMessage]) -> [(String, [SecureMessage])] {
        let grouped = Dictionary(grouping: filtered) { message -> String in
            let calendar = Calendar.current
            if calendar.isDateInToday(message.timestamp) {
                return "messages.today".localized
            } else if calendar.isDateInYesterday(message.timestamp) {
                return "messages.yesterday".localized
            } else {
                return OshiRowFormatters.string(message.timestamp, format: "EEEE, MMMM d, yyyy")
            }
        }
        return grouped.sorted { first, second in
            guard let firstMsg = first.value.first,
                  let secondMsg = second.value.first else {
                return false
            }
            return firstMsg.timestamp < secondMsg.timestamp
        }
    }
}

/// __FILTERED_RESULTS_WINDOW_2026_11_16__ The window of search / date matches
/// the 1:1 list draws (it is eager on iOS 18+, so it must stay bounded).
final class ChatResultWindowTests: XCTestCase {

    func testWindowIsBoundedAndClamped() {
        let step = ChatThreadPipeline.resultWindowStep
        XCTAssertEqual(ChatThreadPipeline.resultWindow(count: 0, start: 0, size: step), 0..<0)
        XCTAssertEqual(ChatThreadPipeline.resultWindow(count: 10, start: 0, size: step), 0..<10)
        XCTAssertEqual(ChatThreadPipeline.resultWindow(count: 3000, start: 0, size: step), 0..<step)
        XCTAssertEqual(ChatThreadPipeline.resultWindow(count: 3000, start: 2990, size: step), 2990..<3000)
        // A window left over from a larger result set is clamped to the new one.
        XCTAssertEqual(ChatThreadPipeline.resultWindow(count: 5, start: 40, size: step), 4..<5)
        XCTAssertEqual(ChatThreadPipeline.resultWindow(count: 5, start: -3, size: step), 0..<5)
    }

    func testRevealKeepsAWindowThatAlreadyShowsTheMatch() {
        let w = ChatThreadPipeline.resultWindow(revealing: 49, start: 0, size: 50, count: 3000)
        XCTAssertEqual(w.start, 0)
        XCTAssertEqual(w.size, 50)
    }

    func testRevealMovesTheWindowWithoutGrowingIt() {
        let step = ChatThreadPipeline.resultWindowStep
        // Next result just past the window, and a jump far away: the window
        // moves, keeps a little context above, and stays one step long.
        for index in [50, 1234, 2999] {
            let w = ChatThreadPipeline.resultWindow(revealing: index, start: 0, size: step, count: 3000)
            let r = ChatThreadPipeline.resultWindow(count: 3000, start: w.start, size: w.size)
            XCTAssertTrue(r.contains(index), "match \(index) not in \(r)")
            XCTAssertLessThanOrEqual(r.count, step)
        }
        let back = ChatThreadPipeline.resultWindow(revealing: 3, start: 1200, size: step, count: 3000)
        XCTAssertEqual(back.start, 0)
        // Out of range: unchanged.
        let none = ChatThreadPipeline.resultWindow(revealing: 3000, start: 7, size: 9, count: 3000)
        XCTAssertEqual(none.start, 7)
        XCTAssertEqual(none.size, 9)
    }
}

/// Deterministic fixture generator (SplitMix64).
private struct SeededRNG: RandomNumberGenerator {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE5_E9B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}


/// __LORA_ROOM_SPEAKERS_2026_11_25__ An open LoRa room shows every speaker, not only our own lines.
final class LoRaRoomThreadPipelineTests: XCTestCase {
    private func row(_ id: String, from: String, to: String) -> SecureMessage {
        SecureMessage(id: id, senderAddress: from, recipientAddress: to,
                      encryptedContent: EncryptedMessage(ciphertext: "", signature: "", senderPublicKey: from, timestamp: Date()),
                      timestamp: Date(timeIntervalSince1970: Double(id.count)), isRead: true, deliveryStatus: .delivered,
                      senderPublicKey: from, recipientPublicKey: to, plaintextContent: id)
    }

    func testALoRaRoomShowsEverySpeaker() {
        let room = LoRaChannelThread.key(channelIndex: 0, channelName: "LongFast")
        let other = LoRaChannelThread.key(channelIndex: 1, channelName: "Alps")
        let source = [row("a", from: "lora!0a0b0c0d", to: room),
                      row("bb", from: "me", to: room),
                      row("ccc", from: "lora!0a0b0c0d", to: "me"),     // a DM, not the room
                      row("dddd", from: "lora!11223344", to: other)]   // another room
        XCTAssertEqual(ChatThreadPipeline.threadMessages(from: source, peerKey: room, myKey: "me").map(\.id), ["a", "bb"])
        // A one-to-one thread keeps the two-party rule.
        XCTAssertEqual(ChatThreadPipeline.threadMessages(from: source, peerKey: "lora!0a0b0c0d", myKey: "me").map(\.id), ["ccc"])
    }
}
