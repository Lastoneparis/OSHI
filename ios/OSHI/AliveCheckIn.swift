import Foundation
import UserNotifications

/// __ALIVE_CHECK_IN_2026_11_04__ "I'm alive." A recurring, per-conversation
/// liveness confirmation.
///
/// Port of `OSHI-Android/.../data/model/AliveCheckIn.kt`, where the reasoning is
/// written out in full. The parts that have to stay identical on both platforms:
///
/// ## What this is, and what it is NOT
///
/// Not the existing check-in (`CheckInMessage`, `🛡️CHECK_IN🛡️`), which
/// accompanies ONE journey: an ETA, a route, a location, and an emergency
/// payload if the ETA passes. That answers "did she get home?".
///
/// This answers a different question, repeatedly and with no location at all:
/// "is he still answering?". A person commits to confirming every N hours; the
/// other side sees a countdown and learns when the deadline passes unanswered.
/// Sharing nothing but a timestamp is the design — a liveness signal that also
/// broadcast a position would be a tracker, and the people who need this most
/// are the ones who can least afford to be tracked.
///
/// ## A pair of one-way commitments, not a shared state
///
/// Each side owns its own schedule, so a conversation holds TWO independent
/// states (``AliveCheckInStore/Entry``). A single shared interval would let one
/// person set a deadline the other has to meet, which is a leash rather than a
/// safety feature.
///
/// ## Wire
///
/// `💓ALIVE💓{"v":1,"t":"ok","at":1762300123.456,"iv":86400}`
///
/// `at` is Unix **seconds**, fractional — seconds because this JSON crosses to
/// Kotlin and back and `Date`'s own encoding is seconds-based, fractional
/// because those milliseconds are what make two events in the same second
/// distinguishable, which ``AliveCheckInPolicy/accepts(eventAt:lastAppliedAt:)``
/// needs in order to drop a duplicate without dropping a real second event.
///
/// `iv` is the sender's interval in seconds, restated on EVERY event rather
/// than only when it changes: a peer that was offline when the schedule was set
/// would otherwise show a confirmation with no deadline attached to it, which
/// is the one display state that reassures without meaning anything.
struct AliveCheckInEvent: Equatable {

    enum Kind: String {
        /// Starting (or re-timing) a schedule. Counts as a confirmation too.
        case enabled = "on"
        /// "I'm alive", at ``atUnixSeconds``.
        case confirmed = "ok"
        /// Stopping. The other side goes back to showing nothing.
        case disabled = "off"
    }

    static let currentVersion = 1

    var version: Int = AliveCheckInEvent.currentVersion
    var kind: Kind
    /// The SENDER's clock — see ``AliveCheckInPolicy/confirmedAt(declared:received:)``.
    var atUnixSeconds: Double
    /// Zero on ``Kind/disabled``, where there is no longer a deadline to describe.
    var intervalSeconds: Int

    /// True when this event means "I was alive at ``atUnixSeconds``".
    var isConfirmation: Bool { kind == .enabled || kind == .confirmed }

    static func create(kind: Kind, intervalSeconds: Int, at date: Date = Date()) -> AliveCheckInEvent {
        AliveCheckInEvent(kind: kind,
                          atUnixSeconds: date.timeIntervalSince1970,
                          intervalSeconds: kind == .disabled ? 0 : intervalSeconds)
    }

    var json: String {
        let obj: [String: Any] = ["v": version, "t": kind.rawValue,
                                  "at": atUnixSeconds, "iv": intervalSeconds]
        guard let data = try? JSONSerialization.data(withJSONObject: obj),
              let s = String(data: data, encoding: .utf8) else { return "{}" }
        return s
    }

    var messageString: String { OSHIControlPayload.aliveCheckInPrefix + json }

    /// Does this text carry the alive-check-in sentinel at all?
    static func isEvent(_ text: String) -> Bool {
        OSHIControlPayload.body(of: text, expecting: OSHIControlPayload.aliveCheckInPrefix) != nil
    }

    static func fromMessageString(_ text: String) -> AliveCheckInEvent? {
        guard let body = OSHIControlPayload.body(of: text, expecting: OSHIControlPayload.aliveCheckInPrefix),
              let data = body.data(using: .utf8),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        let v = (o["v"] as? Int) ?? 0
        guard v > 0, v <= currentVersion else { return nil }
        guard let raw = o["t"] as? String, let kind = Kind(rawValue: raw) else { return nil }
        // A payload with no timestamp cannot be placed in time at all, and "now"
        // would be a fabrication that reads as a fresh confirmation.
        let at = (o["at"] as? Double) ?? 0
        guard at > 0 else { return nil }
        let iv = max(0, (o["iv"] as? Int) ?? 0)
        return AliveCheckInEvent(version: v, kind: kind, atUnixSeconds: at, intervalSeconds: iv)
    }
}

/// __ALIVE_CHECK_IN_2026_11_04__ The intervals offered in the UI.
enum AliveCheckInInterval {
    static let hour = 3_600
    static let day = 24 * hour

    static let every24h = day
    static let every48h = 2 * day
    static let every72h = 3 * day

    static let presets = [every24h, every48h, every72h]
    static let `default` = every24h

    /// The floor is an hour: anything shorter turns a safety feature into a
    /// notification every few minutes, and the first thing a user does with
    /// that is mute the channel the overdue alert travels on. The ceiling is
    /// thirty days, past which "overdue" stops describing anything.
    static let minSeconds = hour
    static let maxSeconds = 30 * day

    static func clamp(_ seconds: Int) -> Int { min(max(seconds, minSeconds), maxSeconds) }
    static func isPreset(_ seconds: Int) -> Bool { presets.contains(seconds) }
}

/// __ALIVE_CHECK_IN_2026_11_04__ Every decision this feature makes, with no
/// store, no notification centre and no clock of its own.
///
/// Pure for a reason stronger than tidiness: the three things that go wrong here
/// — a deadline computed in the wrong time zone, a duplicate counted twice, a
/// peer's broken clock reading as a fresh confirmation — are all invisible on a
/// screen and all trivial to state as a test. Mirrors `AliveCheckInPolicy` in
/// Kotlin, assertion for assertion.
enum AliveCheckInPolicy {

    enum Status {
        /// Nobody configured anything. Draw nothing.
        case neutral
        /// Confirmed, and the next deadline is still ahead. Green.
        case confirmed
        /// The deadline passed with no confirmation. Red — and see ``overdueIsNotProof``.
        case overdue
    }

    /// The sentence this whole feature has to keep saying.
    ///
    /// A missed check-in means a notification did not arrive. That is all it can
    /// ever mean: the phone may be off, out of coverage, out of battery, on a
    /// plane, or simply in a pocket. Every string this feature shows is written
    /// to say "no response" and never "in danger", and this constant exists so a
    /// future caller looking for the right words finds the rule instead of
    /// inventing them.
    static let overdueIsNotProof = "no_response_not_proof_of_harm"

    /// When the next confirmation is expected.
    ///
    /// Absolute instants, both sides, which is the entire answer to time zones
    /// and daylight saving: a deadline is an instant on the shared timeline and
    /// no local calendar is consulted to compute or compare one. A traveller who
    /// crosses six zones between two check-ins sees the same countdown as the
    /// person watching them.
    static func dueAt(lastConfirmedAt: Date, intervalSeconds: Int) -> Date {
        lastConfirmedAt.addingTimeInterval(TimeInterval(intervalSeconds))
    }

    static func status(enabled: Bool,
                       lastConfirmedAt: Date?,
                       intervalSeconds: Int,
                       now: Date) -> Status {
        guard enabled, let last = lastConfirmedAt, intervalSeconds > 0 else { return .neutral }
        return now >= dueAt(lastConfirmedAt: last, intervalSeconds: intervalSeconds) ? .overdue : .confirmed
    }

    /// Seconds left before the deadline (positive) or past it (negative).
    static func remaining(lastConfirmedAt: Date, intervalSeconds: Int, now: Date) -> TimeInterval {
        dueAt(lastConfirmedAt: lastConfirmedAt, intervalSeconds: intervalSeconds)
            .timeIntervalSince(now)
    }

    /// A clock further back than this tells us nothing about time.
    static let maxBackdate: TimeInterval = 30 * 24 * 3_600

    /// The instant a received confirmation actually vouches for.
    ///
    /// The timestamp comes off another device's clock, so it cannot be used raw.
    /// Two failures matter, and they pull in opposite directions:
    ///
    ///  * **A clock ahead of ours.** `at` lands in the future, the deadline moves
    ///    with it, and the conversation stays green past the point where it
    ///    should have gone red. That is the dangerous direction — a safety
    ///    indicator reassuring on the strength of a wrong clock. Clamped to our
    ///    receive time: the message did arrive, now, and that is the latest
    ///    moment it can honestly vouch for.
    ///  * **A clock far behind ours.** `at` lands days back and the peer reads as
    ///    overdue the instant they check in. Merely wrong rather than dangerous,
    ///    but wrong in a way that cries wolf, so anything past ``maxBackdate``
    ///    is replaced by the receive instant too.
    ///
    /// Between those limits the declared instant is honoured as it stands, which
    /// is what makes an offline queue work: a confirmation that waited six hours
    /// in a relay queue is still a confirmation from six hours ago, and dating it
    /// to delivery would hide exactly the lateness this feature exists to show.
    static func confirmedAt(declared: Date?, received: Date) -> Date {
        guard let declared, declared.timeIntervalSince1970 > 0 else { return received }
        if declared > received { return received }
        if received.timeIntervalSince(declared) > maxBackdate { return received }
        return declared
    }

    /// Should this event be applied, given the newest one already applied from
    /// the same peer?
    ///
    /// Duplicates are the normal case: one send travels the cross-platform mesh,
    /// the relay and the LoRa radio, the receiver takes whichever arrives first,
    /// and then the others arrive too. Mesh delivery is also unordered, so an
    /// older event can land after a newer one.
    ///
    /// Strictly-greater, so a replay of the event we already hold changes
    /// nothing, and an out-of-order older one cannot pull a confirmation
    /// backwards and manufacture an overdue state.
    static func accepts(eventAt: Double, lastAppliedAt: Double) -> Bool {
        eventAt > lastAppliedAt
    }

    /// Was this confirmation a recovery — someone answering after their deadline
    /// had already passed?
    static func isRecovery(previous: Status, next: Status) -> Bool {
        previous == .overdue && next == .confirmed
    }

    /// A duration split into the largest two units that still describe it.
    ///
    /// Deliberately coarse above an hour. "2 days 7 hours" tells the reader
    /// everything they can act on; "2 days 7 hours 14 minutes 3 seconds" ticks
    /// in their peripheral vision and makes a safety indicator feel like a bomb
    /// timer. Below an hour the minutes matter, so that is where precision
    /// arrives.
    struct Breakdown: Equatable {
        let days: Int
        let hours: Int
        let minutes: Int
        /// Nothing left to show — under a minute.
        var isImminent: Bool { days == 0 && hours == 0 && minutes == 0 }
    }

    static func breakdown(_ duration: TimeInterval) -> Breakdown {
        let total = Int(abs(duration))
        let days = total / 86_400
        let hours = (total % 86_400) / 3_600
        let minutes = (total % 3_600) / 60
        if days > 0 { return Breakdown(days: days, hours: hours, minutes: 0) }
        if hours > 0 { return Breakdown(days: 0, hours: hours, minutes: minutes) }
        return Breakdown(days: 0, hours: 0, minutes: minutes)
    }

    /// A ``Breakdown`` as words. Compact units, because this text sits beside a
    /// name and a timestamp in a conversation row where a full sentence would
    /// push one of the two off the end.
    static func text(_ b: Breakdown) -> String {
        if b.days > 0 {
            return String(format: "alive.duration.days_hours".localized, b.days, b.hours)
        }
        if b.hours > 0 {
            return String(format: "alive.duration.hours_minutes".localized, b.hours, b.minutes)
        }
        if b.minutes > 0 {
            return String(format: "alive.duration.minutes".localized, b.minutes)
        }
        return "alive.duration.now".localized
    }

    static func text(_ duration: TimeInterval) -> String { text(breakdown(duration)) }

    /// One line about one direction: how long is left, or how long it has been.
    ///
    /// Both readings come from the same two numbers, so there is no state in
    /// which the countdown and the colour can disagree.
    static func laneText(lastConfirmedAt: Date?, intervalSeconds: Int, now: Date) -> String {
        guard let last = lastConfirmedAt, intervalSeconds > 0 else {
            return "alive.state.not_set".localized
        }
        let left = remaining(lastConfirmedAt: last, intervalSeconds: intervalSeconds, now: now)
        if left >= 0 {
            return String(format: "alive.state.due_in".localized, text(left))
        }
        return String(format: "alive.state.overdue_by".localized, text(-left))
    }
}
