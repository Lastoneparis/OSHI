//
//  PollStore.swift
//  OSHI
//
//  Where the ballots live.
//
//  A poll's DEFINITION is the message (`PollMessage`, parsed from the bubble's
//  own payload every time it draws). Its BALLOTS are here, keyed by poll id,
//  because they arrive later and from other people — writing them back into
//  the message payload would mean rewriting a message that both devices
//  already hold, which is the mistake the CheckIn mini-map paid for.
//
//  One ballot per voter per poll. A later ballot replaces an earlier one, and
//  "later" is decided by the ballot's own timestamp, not by arrival order:
//  mesh delivery is not ordered, so a vote relayed through a third phone can
//  land after the correction that superseded it.
//
//  At rest the file is sealed with `AtRestFile` (a Keychain-held key), for the
//  same reason `CheckInManager` stopped using UserDefaults: who voted what in
//  a group is user content, and UserDefaults rides along in iCloud and Finder
//  backups in cleartext.
//
//  Android: `service/PollStore.kt`.
//

import Foundation
import Combine

@MainActor
final class PollStore: ObservableObject {

    static let shared = PollStore()

    /// pollId -> voter public key -> their current ballot.
    @Published private(set) var ballots: [String: [String: PollBallot]] = [:]

    /// Storage hygiene, not a product rule: a device that has been in a lot of
    /// group polls should not grow this file without bound. Nothing in the
    /// product depends on a poll surviving past this point — the bubble still
    /// renders, it just shows no tally — so the cap is allowed to forget, and
    /// it forgets the least recently touched poll first.
    private static let maxPollsKept = 1000

    private var lastTouched: [String: Double] = [:]
    private var saveTask: Task<Void, Never>?

    private init() {
        load()
    }

    // MARK: - Reading

    /// __ONE_VOTER_IS_ONE_KEY_2026_10_23__ The same identity reaches this
    /// device spelled more than one way — base64 and base64url, padded and
    /// unpadded — depending on which platform and which transport the envelope
    /// came through. Keyed by the raw spelling, one person would hold two
    /// ballots and be counted twice, which is a wrong RESULT and not a
    /// cosmetic bug. Everything stored or compared here goes through
    /// `MessageManager.canonicalIdentity`, the same funnel the conversation
    /// list and the message filter already use.
    private static func canonical(_ key: String) -> String {
        MessageManager.canonicalIdentity(key)
    }

    /// Everything the bubble needs, computed against the poll definition it was
    /// drawn from. Out-of-range picks are dropped HERE rather than at record
    /// time: a ballot can legitimately arrive before the poll message it
    /// belongs to (different relay paths), and at that moment there is nothing
    /// to validate it against.
    func results(for poll: PollMessage, myPublicKey: String) -> PollResults {
        var counts = [Int](repeating: 0, count: poll.options.count)
        var voters = [[String]](repeating: [], count: poll.options.count)
        var voterCount = 0
        var mine: Set<Int> = []

        let me = Self.canonical(myPublicKey)
        let table = ballots[poll.id] ?? [:]
        // Deterministic order so two devices list the same voters in the same
        // order: by ballot time, then by key.
        let ordered = table.sorted { a, b in
            a.value.atUnix == b.value.atUnix ? a.key < b.key : a.value.atUnix < b.value.atUnix
        }

        for (voter, ballot) in ordered {
            let picks = ballot.picks.filter { poll.isValidOptionIndex($0) }
            let unique = Array(Set(picks)).sorted()
            // A single-answer poll keeps the FIRST pick of a ballot that
            // somehow carries several, rather than counting the voter twice.
            let effective = poll.allowsMultipleAnswers ? unique : Array(unique.prefix(1))
            guard !effective.isEmpty else { continue }   // a retraction
            voterCount += 1
            for i in effective {
                counts[i] += 1
                if !poll.isAnonymous { voters[i].append(voter) }
            }
            if voter == me { mine = Set(effective) }
        }

        return PollResults(counts: counts,
                           voterCount: voterCount,
                           votersByOption: poll.isAnonymous
                               ? [[String]](repeating: [], count: poll.options.count)
                               : voters,
                           myPicks: mine)
    }

    // MARK: - Writing

    /// Record a ballot. Returns true when something actually changed, so a
    /// caller can avoid redrawing or re-broadcasting for a duplicate — mesh
    /// delivers the same vote more than once routinely.
    @discardableResult
    func record(pollId: String, voter: String, ballot: PollBallot) -> Bool {
        guard !pollId.isEmpty, !voter.isEmpty else { return false }
        let voter = Self.canonical(voter)
        var table = ballots[pollId] ?? [:]
        if let existing = table[voter] {
            // Strictly later wins. Equal timestamps keep what we have: two
            // ballots at the same instant from the same voter is a duplicate,
            // and flapping between them would make the bars jitter.
            // Strictly later, so an equal timestamp is already refused above
            // and there is nothing left for an equality check to catch.
            if ballot.atUnix <= existing.atUnix { return false }
        }
        table[voter] = ballot
        ballots[pollId] = table
        lastTouched[pollId] = Date().timeIntervalSince1970
        enforceCap()
        scheduleSave()
        return true
    }

    func record(vote: PollVotePayload, from voter: String) -> Bool {
        record(pollId: vote.pollId, voter: voter, ballot: vote.ballot)
    }

    private func enforceCap() {
        guard ballots.count > Self.maxPollsKept else { return }
        let victims = lastTouched
            .sorted { $0.value < $1.value }
            .prefix(ballots.count - Self.maxPollsKept)
            .map(\.key)
        for id in victims {
            ballots.removeValue(forKey: id)
            lastTouched.removeValue(forKey: id)
        }
    }

    // MARK: - Persistence

    private struct Disk: Codable {
        var ballots: [String: [String: PollBallot]]
        var lastTouched: [String: Double]
    }

    private static var fileURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("poll_ballots.json")
    }

    /// Coalesced: a group poll can land twenty ballots in a second and each one
    /// would otherwise rewrite the whole file.
    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard !Task.isCancelled else { return }
            self?.save()
        }
    }

    private func save() {
        let snapshot = Disk(ballots: ballots, lastTouched: lastTouched)
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        do {
            try AtRestFile.write(data, to: Self.fileURL)
        } catch {
            OshiLog.storage.info("[PollStore] could not seal the ballots: \(error)")
        }
    }

    private func load() {
        guard let data = try? AtRestFile.read(Self.fileURL),
              let disk = try? JSONDecoder().decode(Disk.self, from: data) else { return }
        ballots = disk.ballots
        lastTouched = disk.lastTouched
        OshiLog.storage.infoPublic("[PollStore] loaded \(disk.ballots.count) poll(s)")
    }
}
