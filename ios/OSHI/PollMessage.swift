//
//  PollMessage.swift
//  OSHI
//
//  A poll in a conversation: one question, two to twelve answers, and the
//  tally of who chose what. Works the same in a 1:1 chat and in a group.
//
//  Why it rides on a sentinel payload
//  ---------------------------------
//  OSHI has no server to hold a poll, so there is nowhere to "fetch results"
//  from. A poll is therefore just a message — `📊POLL📊` followed by JSON —
//  and a vote is another message, `📊POLL_VOTE📊`, that every participant
//  applies locally. Both are declared in `OSHIControlPayload.catalog`, which
//  is the only place allowed to know a sentinel exists.
//
//  The definition never changes after it is sent. Votes are NOT written back
//  into the poll message: they live in `PollStore`, keyed by poll id. That is
//  deliberate — the message payload is the thing both devices signed and
//  re-render from, and the CheckIn mini-map already taught us what happens
//  when a bubble mutates the payload it was parsed from.
//
//  Who voted is decided by the ratchet, never by the wire
//  -----------------------------------------------------
//  `PollVotePayload` carries no voter field. The voter is the sender of the
//  decrypted envelope, which is authenticated; a voter name inside the JSON
//  would let anyone vote as anyone. The receive site passes
//  `message.senderPublicKey` to `PollStore.record`.
//
//  Android: `data/model/PollMessage.kt`. The two must stay byte-compatible —
//  same JSON keys, same Unix-seconds timestamps.
//

import Foundation

// MARK: - The poll itself

struct PollMessage: Codable, Identifiable, Equatable {

    /// Wire format version. A future field goes with a bump, and an older
    /// client that cannot read it shows the question and the options it does
    /// understand rather than nothing.
    static let currentVersion = 1

    let version: Int
    let id: String
    let question: String
    let options: [String]
    let allowsMultipleAnswers: Bool
    /// Anonymous means "do not show the names", nothing more. A ballot still
    /// travels signed by its sender — it cannot be otherwise on a P2P mesh —
    /// so this is a presentation promise, not a cryptographic one, and the
    /// composer says so in as many words.
    let isAnonymous: Bool
    /// __UNIX_SECONDS_NOT_DATE_2026_10_23__ Not a `Date`. `JSONEncoder` writes
    /// a `Date` as seconds since 2001-01-01, Android reads seconds since
    /// 1970-01-01, and the 978 307 200 s gap between them is exactly the trap
    /// the cross-platform message envelope already had to be fixed for.
    let createdAtUnix: Double
    let closesAtUnix: Double?
    let creatorPublicKey: String

    enum CodingKeys: String, CodingKey {
        case version = "v"
        case id
        case question = "q"
        case options = "opts"
        case allowsMultipleAnswers = "multi"
        case isAnonymous = "anon"
        case createdAtUnix = "at"
        case closesAtUnix = "closes"
        case creatorPublicKey = "by"
    }

    // MARK: Limits
    //
    // Both platforms enforce the same ones, because a poll that one client
    // accepts and the other truncates is a poll that shows two different
    // results on two phones.

    static let maxQuestionLength = 300
    static let maxOptionLength = 100
    static let minOptions = 2
    static let maxOptions = 12

    init(id: String = UUID().uuidString,
         question: String,
         options: [String],
         allowsMultipleAnswers: Bool,
         isAnonymous: Bool,
         closesAt: Date? = nil,
         creatorPublicKey: String,
         createdAt: Date = Date(),
         version: Int = PollMessage.currentVersion) {
        self.version = version
        self.id = id
        self.question = question
        self.options = options
        self.allowsMultipleAnswers = allowsMultipleAnswers
        self.isAnonymous = isAnonymous
        self.createdAtUnix = createdAt.timeIntervalSince1970
        self.closesAtUnix = closesAt?.timeIntervalSince1970
        self.creatorPublicKey = creatorPublicKey
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = (try? c.decode(Int.self, forKey: .version)) ?? 1
        id = try c.decode(String.self, forKey: .id)
        question = try c.decode(String.self, forKey: .question)
        options = try c.decode([String].self, forKey: .options)
        allowsMultipleAnswers = (try? c.decode(Bool.self, forKey: .allowsMultipleAnswers)) ?? false
        isAnonymous = (try? c.decode(Bool.self, forKey: .isAnonymous)) ?? false
        createdAtUnix = (try? c.decode(Double.self, forKey: .createdAtUnix)) ?? Date().timeIntervalSince1970
        closesAtUnix = try? c.decode(Double.self, forKey: .closesAtUnix)
        creatorPublicKey = (try? c.decode(String.self, forKey: .creatorPublicKey)) ?? ""
    }

    // MARK: Derived

    var createdAt: Date { Date(timeIntervalSince1970: createdAtUnix) }
    var closesAt: Date? { closesAtUnix.map { Date(timeIntervalSince1970: $0) } }

    /// A closed poll still shows its results; it just stops taking ballots.
    var isClosed: Bool {
        guard let closesAt else { return false }
        return closesAt <= Date()
    }

    func isValidOptionIndex(_ i: Int) -> Bool { i >= 0 && i < options.count }

    // MARK: Validation
    //
    // `sanitized` is what the composer calls before sending: it is the single
    // definition of "this poll is sendable", so the Send button and the wire
    // can never disagree.

    static func sanitize(question: String, options: [String]) -> (question: String, options: [String])? {
        let q = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return nil }
        let opts = options
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .map { String($0.prefix(maxOptionLength)) }
        guard opts.count >= minOptions, opts.count <= maxOptions else { return nil }
        return (String(q.prefix(maxQuestionLength)), opts)
    }

    // MARK: Wire

    static var prefix: String { OSHIControlPayload.pollPrefix }

    static func isPollMessage(_ text: String) -> Bool {
        OSHIControlPayload.matchedPrefix(text) == prefix
    }

    func toMessageString() -> String {
        guard let data = try? JSONEncoder().encode(self),
              let json = String(data: data, encoding: .utf8) else {
            return Self.prefix + "{}"
        }
        return Self.prefix + json
    }

    static func fromMessageString(_ text: String) -> PollMessage? {
        guard let body = OSHIControlPayload.body(of: text, expecting: prefix),
              let data = body.data(using: .utf8),
              let poll = try? JSONDecoder().decode(PollMessage.self, from: data),
              poll.options.count >= minOptions
        else { return nil }
        return poll
    }
}

// MARK: - A ballot

/// One participant's answer. `picks` is a set of option indexes: a single
/// answer poll carries exactly one, a multiple answer poll one or more. An
/// empty `picks` is how a vote is RETRACTED — it is a real ballot, not a
/// missing one, so it has to beat the earlier one on timestamp.
struct PollBallot: Codable, Equatable {
    let picks: [Int]
    let atUnix: Double

    var at: Date { Date(timeIntervalSince1970: atUnix) }

    init(picks: [Int], at: Date = Date()) {
        self.picks = picks
        self.atUnix = at.timeIntervalSince1970
    }

    enum CodingKeys: String, CodingKey {
        case picks
        case atUnix = "at"
    }
}

// MARK: - The vote on the wire

struct PollVotePayload: Codable {
    let version: Int
    let pollId: String
    let picks: [Int]
    let atUnix: Double

    enum CodingKeys: String, CodingKey {
        case version = "v"
        case pollId = "poll"
        case picks
        case atUnix = "at"
    }

    init(pollId: String, picks: [Int], at: Date = Date(), version: Int = PollMessage.currentVersion) {
        self.version = version
        self.pollId = pollId
        self.picks = picks
        self.atUnix = at.timeIntervalSince1970
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = (try? c.decode(Int.self, forKey: .version)) ?? 1
        pollId = try c.decode(String.self, forKey: .pollId)
        picks = (try? c.decode([Int].self, forKey: .picks)) ?? []
        atUnix = (try? c.decode(Double.self, forKey: .atUnix)) ?? Date().timeIntervalSince1970
    }

    var at: Date { Date(timeIntervalSince1970: atUnix) }
    var ballot: PollBallot { PollBallot(picks: picks, at: at) }

    static var prefix: String { OSHIControlPayload.pollVotePrefix }

    static func isVoteMessage(_ text: String) -> Bool {
        OSHIControlPayload.matchedPrefix(text) == prefix
    }

    func toMessageString() -> String {
        guard let data = try? JSONEncoder().encode(self),
              let json = String(data: data, encoding: .utf8) else {
            return Self.prefix + "{}"
        }
        return Self.prefix + json
    }

    static func fromMessageString(_ text: String) -> PollVotePayload? {
        guard let body = OSHIControlPayload.body(of: text, expecting: prefix),
              let data = body.data(using: .utf8),
              let vote = try? JSONDecoder().decode(PollVotePayload.self, from: data),
              !vote.pollId.isEmpty
        else { return nil }
        return vote
    }
}

// MARK: - What the bubble draws

struct PollResults: Equatable {
    /// One count per option, in the poll's own option order.
    let counts: [Int]
    /// Number of distinct people who have a non-empty ballot. In a multiple
    /// answer poll this is LESS than the sum of `counts`, which is why the
    /// footer says "N people" and the bars are drawn against this number.
    let voterCount: Int
    /// Public keys per option, in the order the ballots arrived. Empty for an
    /// anonymous poll: the names are not withheld at draw time, they are never
    /// collected, so a screenshot of the view cannot leak them.
    let votersByOption: [[String]]
    /// What this device's owner chose, so the rows can show it.
    let myPicks: Set<Int>

    func share(ofOption index: Int) -> Double {
        guard voterCount > 0, counts.indices.contains(index) else { return 0 }
        return Double(counts[index]) / Double(voterCount)
    }

    /// Rounded for display, and never 0 % for an option that really has a
    /// voter: a bar with a body and "0 %" written on it reads as a bug.
    func percentLabel(forOption index: Int) -> Int {
        let raw = share(ofOption: index) * 100
        if raw > 0 && raw < 1 { return 1 }
        return Int(raw.rounded())
    }

    static let empty = PollResults(counts: [], voterCount: 0, votersByOption: [], myPicks: [])
}
