//
//  ChatThreadPipeline.swift
//  OSHI
//
//  __CHAT_OPEN_PERF_2026_11_14__ The derivation `ChatView` runs on the MAIN
//  thread every time `MessageManager.messages` publishes: once when a thread
//  opens, then on every arriving message, delivery tick and read flip, for ANY
//  contact (the store is global).
//
//  It used to live as private methods of the view, which made it impossible to
//  measure. It is the same algorithm, moved here so `ChatOpenPerformanceTests`
//  can time it and prove the output is unchanged, with four costs removed:
//
//   1. Membership canonicalised BOTH keys of EVERY message in the global store
//      (`canonicalIdentity` is ~3 string scans). The raw spelling of the two
//      keys is now compared first and the canonical form of any other spelling
//      is memoised for the pass — a store has a handful of distinct keys.
//   2. The thread was re-sorted on every publish although it arrives in order
//      in the common case. An O(n) "already sorted?" check skips the sort.
//   3. Without a search or a date filter only the newest `limit` rows are drawn,
//      but the control-payload test (`isHiddenFromThread`, a prefix catalogue)
//      ran over the whole history. It now runs backwards from the newest
//      message until the window is full.
//   4. Date grouping hashed every message into a dictionary keyed by a
//      formatted string (one `DateFormatter` call per message) and sorted the
//      groups. The input is sorted, so a day is a contiguous run: one label per
//      DAY, no dictionary, no sort.
//
//  Output is identical: `ChatOpenPerformanceTests` compares it, row by row and
//  header by header, with a verbatim copy of the previous implementation.
//

import Foundation

enum ChatThreadPipeline {

    struct Filter {
        var searchText: String = ""
        var singleDate: Date? = nil
        var rangeStart: Date? = nil
        var rangeEnd: Date? = nil
        /// Date formatters the search also matches against (ChatView's
        /// `cachedSearchDateFormatters`).
        var searchDateFormatters: [DateFormatter] = []

        var isActive: Bool {
            !searchText.isEmpty || singleDate != nil || (rangeStart != nil && rangeEnd != nil)
        }
    }

    struct Output {
        /// Every message of the thread, oldest first (control payloads included).
        var loaded: [SecureMessage]
        /// With a filter: the matching messages. Without one: the rendered
        /// window (only search code reads this, and only while a filter is on).
        var filtered: [SecureMessage]
        /// The rendered rows, grouped by day, oldest day first.
        var byDate: [(String, [SecureMessage])]
        /// True when older displayable messages exist above the window.
        var hasEarlier: Bool
    }

    // MARK: - Membership

    /// Messages of the conversation between `myKey` and `peerKey`, oldest first.
    /// Same membership rule as before: both keys compared in CANONICAL spelling
    /// (__IDENTITY_CANONICAL_2026_08_15__).
    static func threadMessages(from source: [SecureMessage],
                               peerKey: String,
                               myKey: String) -> [SecureMessage] {
        let peer = MessageManager.canonicalIdentity(peerKey)
        let me = MessageManager.canonicalIdentity(myKey)
        var memo: [String: String] = [:]
        func canonical(_ key: String) -> String {
            // Raw spellings of the two keys of this thread: by far the commonest
            // case, answered by one string compare.
            if key == peerKey { return peer }
            if key == myKey { return me }
            if let hit = memo[key] { return hit }
            let c = MessageManager.canonicalIdentity(key)
            memo[key] = c
            return c
        }
        var out: [SecureMessage] = []
        out.reserveCapacity(min(source.count, 4096))
        // __MESHCORE_INTEROP_2026_11_25__ A radio channel (LoRa room, MeshCore channel) is a room: its rows
        // are stored with the CHANNEL as recipient and whoever spoke as sender, so the two-party rule below
        // kept only our own lines and every other speaker vanished from the open thread (the list and
        // `MessageManager.getMessages` showed them). In a room, the recipient decides.
        if LoRaChannelThread.isChannelKey(peerKey) {
            for message in source where canonical(message.recipientPublicKey) == peer { out.append(message) }
            return sortedByTimestamp(out)
        }
        for message in source {
            let sender = canonical(message.senderPublicKey)
            if sender != peer && sender != me { continue }
            let recipient = canonical(message.recipientPublicKey)
            if (recipient == peer && sender == me) || (sender == peer && recipient == me) {
                out.append(message)
            }
        }
        return sortedByTimestamp(out)
    }

    /// `sorted { $0.timestamp < $1.timestamp }` (stable), skipped when already in
    /// order. When a sort is needed it sorts (timestamp, index) pairs — 16 bytes
    /// each — rather than moving the large message structs around; ties keep
    /// their original order, as the stable sort did.
    static func sortedByTimestamp(_ messages: [SecureMessage]) -> [SecureMessage] {
        var inOrder = true
        var i = 1
        while i < messages.count {
            if messages[i].timestamp < messages[i - 1].timestamp { inOrder = false; break }
            i += 1
        }
        if inOrder { return messages }
        var keys = messages.indices.map { (t: messages[$0].timestamp.timeIntervalSinceReferenceDate, i: $0) }
        keys.sort { $0.t < $1.t || ($0.t == $1.t && $0.i < $1.i) }
        return keys.map { messages[$0.i] }
    }

    // MARK: - Visible rows

    static func isDisplayable(_ message: SecureMessage) -> Bool {
        guard let plaintext = message.plaintextContent else { return true }
        return !OSHIControlPayload.isHiddenFromThread(plaintext)
    }

    /// The whole derivation, from the global store to the rendered rows.
    static func build(source: [SecureMessage],
                      peerKey: String,
                      myKey: String,
                      filter: Filter,
                      windowLimit: Int,
                      calendar: Calendar = .current) -> Output {
        let loaded = threadMessages(from: source, peerKey: peerKey, myKey: myKey)
        return derive(loaded: loaded, filter: filter, windowLimit: windowLimit, calendar: calendar)
    }

    /// Everything after membership. Split out so the window can be recomputed
    /// ("load earlier", search) without re-scanning the store.
    static func derive(loaded: [SecureMessage],
                       filter: Filter,
                       windowLimit: Int,
                       calendar: Calendar = .current) -> Output {
        if filter.isActive {
            let filtered = applyFilter(loaded.filter(isDisplayable), filter: filter, calendar: calendar)
            return Output(loaded: loaded, filtered: filtered,
                          byDate: groupByDay(filtered, calendar: calendar),
                          hasEarlier: false)
        }
        // Newest-first walk until the window is full.
        var window: [SecureMessage] = []
        window.reserveCapacity(min(windowLimit, loaded.count))
        var index = loaded.count - 1
        while index >= 0 && window.count < windowLimit {
            if isDisplayable(loaded[index]) { window.append(loaded[index]) }
            index -= 1
        }
        var hasEarlier = false
        while index >= 0 {
            if isDisplayable(loaded[index]) { hasEarlier = true; break }
            index -= 1
        }
        window.reverse()
        return Output(loaded: loaded, filtered: window,
                      byDate: groupByDay(window, calendar: calendar),
                      hasEarlier: hasEarlier)
    }

    /// Date and search filters, exactly as `ChatView.computeFilteredMessages`
    /// applied them after dropping the hidden payloads.
    static func applyFilter(_ input: [SecureMessage], filter: Filter, calendar: Calendar) -> [SecureMessage] {
        var messages = input
        if let singleDate = filter.singleDate {
            messages = messages.filter { calendar.isDate($0.timestamp, inSameDayAs: singleDate) }
        } else if let start = filter.rangeStart, let end = filter.rangeEnd {
            let startOfDay = calendar.startOfDay(for: start)
            let endOfDay = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: end)) ?? end
            messages = messages.filter { $0.timestamp >= startOfDay && $0.timestamp < endOfDay }
        }
        let searchText = filter.searchText
        if !searchText.isEmpty {
            let searchLower = searchText.lowercased().trimmingCharacters(in: .whitespaces)
            let todayLocalized = "messages.today".localized.lowercased()
            let yesterdayLocalized = "messages.yesterday".localized.lowercased()
            messages = messages.filter { message in
                if let plaintext = message.plaintextContent,
                   plaintext.localizedCaseInsensitiveContains(searchText) {
                    return true
                }
                if searchLower == todayLocalized && calendar.isDateInToday(message.timestamp) {
                    return true
                }
                if searchLower == yesterdayLocalized && calendar.isDateInYesterday(message.timestamp) {
                    return true
                }
                for formatter in filter.searchDateFormatters {
                    if formatter.string(from: message.timestamp).localizedCaseInsensitiveContains(searchText) {
                        return true
                    }
                }
                return false
            }
        }
        return messages
    }

    // MARK: - Filtered result window

    /// __FILTERED_RESULTS_WINDOW_2026_11_16__ A search or date filter can match
    /// the whole thread, and the list draws its rows eagerly on iOS 18+ (a
    /// `LazyVStack` livelocks with accessibility on, see `ChatMessageStack`).
    /// So the filtered list, like the unfiltered one, draws a bounded window of
    /// the matches: `resultWindowStep` at a time, grown by "+N more results".
    static let resultWindowStep = 50

    /// The rendered slice of `count` matches (oldest first), starting at
    /// `start`, at most `size` long. Clamped to the matches.
    static func resultWindow(count: Int, start: Int, size: Int) -> Range<Int> {
        guard count > 0 else { return 0..<0 }
        let lo = min(max(0, start), count - 1)
        return lo..<min(count, lo + max(1, size))
    }

    /// Where the window has to be so that match `index` is drawn (search
    /// navigation, a jump to a match). Unchanged when it already is; otherwise
    /// a fresh `step`-long window with a few matches of context above it.
    static func resultWindow(revealing index: Int, start: Int, size: Int, count: Int,
                             step: Int = resultWindowStep) -> (start: Int, size: Int) {
        let current = resultWindow(count: count, start: start, size: size)
        guard count > 0, index >= 0, index < count, !current.contains(index) else { return (start, size) }
        let lo = max(0, min(index - 10, count - step))
        return (lo, step)
    }

    // MARK: - Day groups

    /// The header a day is shown under — same three cases as before.
    static func dayLabel(for date: Date, calendar: Calendar) -> String {
        if calendar.isDateInToday(date) { return "messages.today".localized }
        if calendar.isDateInYesterday(date) { return "messages.yesterday".localized }
        return OshiRowFormatters.string(date, format: "EEEE, MMMM d, yyyy")
    }

    /// Group sorted messages into consecutive days. One label per day.
    static func groupByDay(_ messages: [SecureMessage],
                           calendar: Calendar) -> [(String, [SecureMessage])] {
        var groups: [(String, [SecureMessage])] = []
        var current: [SecureMessage] = []
        var currentLabel = ""
        var dayStart = Date.distantFuture
        var dayEnd = Date.distantPast
        for message in messages {
            let t = message.timestamp
            if t >= dayStart && t < dayEnd {
                current.append(message)
                continue
            }
            let label: String
            if let interval = calendar.dateInterval(of: .day, for: t) {
                dayStart = interval.start
                dayEnd = interval.end
                label = dayLabel(for: t, calendar: calendar)
            } else {
                dayStart = .distantFuture
                dayEnd = .distantPast
                label = dayLabel(for: t, calendar: calendar)
            }
            if !current.isEmpty && label == currentLabel {
                current.append(message)
                continue
            }
            if !current.isEmpty { groups.append((currentLabel, current)) }
            current = [message]
            currentLabel = label
        }
        if !current.isEmpty { groups.append((currentLabel, current)) }
        return groups
    }

    // MARK: - Open helpers

    /// First unread message from the peer among the RENDERED rows.
    ///
    /// Read off the rows, not the whole thread: a hidden control payload or a
    /// message above the window has no row, so a divider aimed at it is never
    /// drawn and the open scroll has nothing to land on. Same clamp as
    /// `GroupChatView.firstUnreadIncomingGroupId`.
    static func firstUnreadIncoming(in rows: [(String, [SecureMessage])], myKey: String) -> String? {
        let me = MessageManager.canonicalIdentity(myKey)
        for (_, day) in rows {
            for message in day where !message.isRead {
                if message.senderPublicKey == myKey { continue }
                if MessageManager.canonicalIdentity(message.senderPublicKey) != me { return message.id }
            }
        }
        return nil
    }

    /// How large the window must be for `messageId` to be a rendered row, or
    /// nil when the message is not a displayable message of this thread.
    static func windowLimitToInclude(_ messageId: String, in loaded: [SecureMessage]) -> Int? {
        guard let index = loaded.lastIndex(where: { $0.id == messageId }),
              isDisplayable(loaded[index]) else { return nil }
        var count = 0
        for i in index..<loaded.count where isDisplayable(loaded[i]) { count += 1 }
        return count
    }
}
