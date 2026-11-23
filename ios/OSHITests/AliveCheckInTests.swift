//
//  AliveCheckInTests.swift
//  OSHITests
//
//  __ALIVE_CHECK_IN_2026_11_04__ The recurring liveness check-in.
//
//  Mirror of `OSHI-Android/app/src/test/.../AliveCheckInTest.kt`, assertion for
//  assertion where the behaviour is shared. The point of writing it twice is
//  the wire: this payload crosses between the two platforms, and a divergence
//  in how `at` is encoded or in which events are accepted is invisible on both
//  screens and only shows up as a conversation that is green on one phone and
//  red on the other.
//
//  Covers the five scenarios the feature has to survive — normal, overdue,
//  recovery, offline, and a duplicate arriving over a second transport — plus
//  the two failures that are invisible on a screen: a peer's wrong clock, and a
//  deadline computed against a local calendar.
//

import XCTest
@testable import OSHI

final class AliveCheckInTests: XCTestCase {

    private let day = AliveCheckInInterval.day
    /// A fixed instant, so nothing here reads the clock.
    private let t0 = Date(timeIntervalSince1970: 1_762_300_000)

    // MARK: - Wire format

    func testEventRoundTripsThroughItsOwnJSON() throws {
        let sent = AliveCheckInEvent.create(kind: .confirmed, intervalSeconds: day, at: t0)
        let back = try XCTUnwrap(AliveCheckInEvent.fromMessageString(sent.messageString))
        XCTAssertEqual(back.kind, .confirmed)
        XCTAssertEqual(back.intervalSeconds, day)
        XCTAssertEqual(back.atUnixSeconds, t0.timeIntervalSince1970, accuracy: 0.0005)
    }

    func testTheWireShapeIsTheOneAndroidParses() throws {
        let wire = AliveCheckInEvent.create(kind: .enabled, intervalSeconds: day, at: t0).messageString
        XCTAssertTrue(wire.hasPrefix(OSHIControlPayload.aliveCheckInPrefix))
        let body = try XCTUnwrap(OSHIControlPayload.body(of: wire,
                                                         expecting: OSHIControlPayload.aliveCheckInPrefix))
        let o = try XCTUnwrap(try JSONSerialization.jsonObject(with: XCTUnwrap(body.data(using: .utf8)))
                              as? [String: Any])
        XCTAssertEqual(o["v"] as? Int, 1)
        XCTAssertEqual(o["t"] as? String, "on")
        XCTAssertEqual(o["iv"] as? Int, day)
        // SECONDS, not millis. Kotlin writes `at = millis / 1000.0`; a millis
        // value here would place every check-in in the year 57000 on the other
        // side, and the peer would read as permanently confirmed.
        XCTAssertEqual(o["at"] as? Double ?? 0, t0.timeIntervalSince1970, accuracy: 0.0005)
    }

    func testTheSentinelIsTheSameTwoCodepointsAsAndroids() {
        // Written out by hand rather than compared to the constant: the point is
        // that this exact string is what the other platform matches on, and a
        // test that reads the constant cannot notice it changing.
        XCTAssertEqual(OSHIControlPayload.aliveCheckInPrefix, "💓ALIVE💓")
        // No U+FE0F anywhere in it. The variation-selector skew that forced
        // `OSHIControlPayload`'s whole variant table exists because `🛡️` and `⌨️`
        // carry one and half the wire omits it; `💓` has none, so this sentinel
        // has exactly one spelling on both platforms.
        XCTAssertFalse(OSHIControlPayload.aliveCheckInPrefix.unicodeScalars
                        .contains(where: { $0.value == 0xFE0F }))
    }

    func testADisabledEventCarriesNoInterval() {
        let off = AliveCheckInEvent.create(kind: .disabled, intervalSeconds: day, at: t0)
        XCTAssertEqual(off.intervalSeconds, 0)
        XCTAssertFalse(off.isConfirmation)
    }

    func testAnUnparseablePayloadIsRefusedRatherThanGuessed() {
        let p = OSHIControlPayload.aliveCheckInPrefix
        // No timestamp: the event cannot be placed in time, and defaulting to
        // "now" would fabricate a fresh confirmation out of a corrupt payload.
        XCTAssertNil(AliveCheckInEvent.fromMessageString(p + #"{"v":1,"t":"ok","iv":86400}"#))
        // A version from the future: a field we do not understand could change
        // what "ok" means.
        XCTAssertNil(AliveCheckInEvent.fromMessageString(p + #"{"v":2,"t":"ok","at":1,"iv":86400}"#))
        XCTAssertNil(AliveCheckInEvent.fromMessageString(p + #"{"v":1,"t":"maybe","at":1,"iv":1}"#))
        XCTAssertNil(AliveCheckInEvent.fromMessageString("hello"))
        XCTAssertFalse(AliveCheckInEvent.isEvent("hello"))
    }

    func testASentinelInTheMiddleOfProseIsProse() {
        let typed = "we should add a \(OSHIControlPayload.aliveCheckInPrefix) marker"
        XCTAssertFalse(AliveCheckInEvent.isEvent(typed))
        XCTAssertEqual(OSHIControlPayload.previewText(for: typed), typed)
    }

    // MARK: - The catalog

    func testTheCatalogClaimsTheSentinelOnceAsRendered() {
        let hits = OSHIControlPayload.catalog.filter { $0.prefix == OSHIControlPayload.aliveCheckInPrefix }
        XCTAssertEqual(hits.count, 1)
        XCTAssertEqual(hits.first?.kind, .rendered)
    }

    func testACheckInIsNeitherSilentNorPushSuppressed() {
        let wire = AliveCheckInEvent.create(kind: .confirmed, intervalSeconds: day, at: t0).messageString
        // Rendered: it is a line in the thread. A confirmation the reader cannot
        // find afterwards is one they have to take on trust from a coloured dot.
        XCTAssertFalse(OSHIControlPayload.isSilent(wire))
        // And it wakes the peer. This is the one message whose ABSENCE is the
        // alarm, so suppressing its push would be suppressing the feature.
        XCTAssertFalse(OSHIControlPayload.suppressesPush(wire))
    }

    // MARK: - Status, and the time zones

    func testNothingIsShownUntilSomethingIsConfigured() {
        XCTAssertEqual(AliveCheckInPolicy.status(enabled: false, lastConfirmedAt: t0,
                                                 intervalSeconds: day, now: t0), .neutral)
        XCTAssertEqual(AliveCheckInPolicy.status(enabled: true, lastConfirmedAt: nil,
                                                 intervalSeconds: day, now: t0), .neutral)
        XCTAssertEqual(AliveCheckInPolicy.status(enabled: true, lastConfirmedAt: t0,
                                                 intervalSeconds: 0, now: t0), .neutral)
    }

    func testConfirmedBeforeTheDeadlineOverdueAtItAndAfterIt() {
        let due = AliveCheckInPolicy.dueAt(lastConfirmedAt: t0, intervalSeconds: day)
        XCTAssertEqual(AliveCheckInPolicy.status(enabled: true, lastConfirmedAt: t0,
                                                 intervalSeconds: day,
                                                 now: due.addingTimeInterval(-1)), .confirmed)
        // At the deadline, not one tick after: "due in 0" is not a state anyone
        // can act on, and a half-open boundary is the one a test can pin.
        XCTAssertEqual(AliveCheckInPolicy.status(enabled: true, lastConfirmedAt: t0,
                                                 intervalSeconds: day, now: due), .overdue)
        XCTAssertEqual(AliveCheckInPolicy.status(enabled: true, lastConfirmedAt: t0,
                                                 intervalSeconds: day,
                                                 now: due.addingTimeInterval(86_400)), .overdue)
    }

    func testADeadlineIsTheSameInstantInEveryTimeZone() {
        let original = TimeZone.current
        defer { NSTimeZone.default = original }
        var answers = Set<String>()
        for zone in ["UTC", "Pacific/Kiritimati", "Pacific/Niue", "Asia/Kathmandu"] {
            NSTimeZone.default = TimeZone(identifier: zone)!
            let due = AliveCheckInPolicy.dueAt(lastConfirmedAt: t0, intervalSeconds: day)
            let left = AliveCheckInPolicy.remaining(lastConfirmedAt: t0, intervalSeconds: day, now: t0)
            answers.insert("\(due.timeIntervalSince1970)|\(left)")
        }
        // One distinct answer across a +14, a -11 and a :45 offset. Absolute
        // instants throughout is the reason: no local calendar is consulted to
        // compute or compare a deadline, so daylight saving and a traveller
        // crossing six zones cannot move one.
        XCTAssertEqual(answers.count, 1)
    }

    // MARK: - Another device's clock

    func testAClockFromTheFutureCannotKeepAConversationGreen() {
        let declared = t0.addingTimeInterval(7 * 24 * 3_600)
        // Clamped to receipt. This is the dangerous direction: honoured raw, the
        // deadline would move a week out and the indicator would stay green long
        // past the point where it should have gone red.
        XCTAssertEqual(AliveCheckInPolicy.confirmedAt(declared: declared, received: t0), t0)
    }

    func testAClockYearsBehindTellsUsNothingAboutTime() {
        let declared = t0.addingTimeInterval(-400 * 24 * 3_600)
        XCTAssertEqual(AliveCheckInPolicy.confirmedAt(declared: declared, received: t0), t0)
    }

    func testAnOrdinaryQueueDelayIsHonouredBecauseThatLatenessIsThePoint() {
        let declared = t0.addingTimeInterval(-6 * 3_600)
        // Dating this to delivery would hide exactly what the feature exists to
        // show: the confirmation is six hours old, and the countdown must say so.
        XCTAssertEqual(AliveCheckInPolicy.confirmedAt(declared: declared, received: t0), declared)
    }

    func testAMissingTimestampFallsBackToTheReceiveInstant() {
        XCTAssertEqual(AliveCheckInPolicy.confirmedAt(declared: nil, received: t0), t0)
        XCTAssertEqual(AliveCheckInPolicy.confirmedAt(declared: Date(timeIntervalSince1970: 0),
                                                      received: t0), t0)
    }

    // MARK: - Duplicates and ordering

    func testAReplayOfTheEventWeAlreadyHoldChangesNothing() {
        XCTAssertFalse(AliveCheckInPolicy.accepts(eventAt: 100, lastAppliedAt: 100))
        XCTAssertFalse(AliveCheckInPolicy.accepts(eventAt: 99.9, lastAppliedAt: 100))
        XCTAssertTrue(AliveCheckInPolicy.accepts(eventAt: 100.1, lastAppliedAt: 100))
        XCTAssertTrue(AliveCheckInPolicy.accepts(eventAt: 1, lastAppliedAt: 0))
    }

    // MARK: - Recovery, as a pure transition

    func testAnsweringAfterTheDeadlineIsARecoveryAnsweringOnTimeIsNot() {
        XCTAssertTrue(AliveCheckInPolicy.isRecovery(previous: .overdue, next: .confirmed))
        XCTAssertFalse(AliveCheckInPolicy.isRecovery(previous: .confirmed, next: .confirmed))
        XCTAssertFalse(AliveCheckInPolicy.isRecovery(previous: .overdue, next: .overdue))
        XCTAssertFalse(AliveCheckInPolicy.isRecovery(previous: .neutral, next: .confirmed))
    }

    // MARK: - What one row shows, out of two directions

    func testASilenceOnEitherSideWinsTheRow() {
        var both = AliveCheckInStore.Entry()
        both.mineEnabled = true
        both.mineIntervalSeconds = day
        both.mineLastConfirmedAt = t0
        both.theirsEnabled = true
        both.theirsIntervalSeconds = day
        both.theirsLastConfirmedAt = t0
        XCTAssertEqual(both.rowStatus(now: t0.addingTimeInterval(1)), .confirmed)

        // Our own deadline passes while theirs holds. Showing their green would
        // hide the only half the user can still do something about.
        let myDeadline = t0.addingTimeInterval(TimeInterval(day) + 1)
        var theirsFresher = both
        theirsFresher.theirsLastConfirmedAt = myDeadline
        XCTAssertEqual(theirsFresher.rowStatus(now: myDeadline), .overdue)

        var mineFresher = both
        mineFresher.mineLastConfirmedAt = myDeadline
        XCTAssertEqual(mineFresher.rowStatus(now: myDeadline), .overdue)
    }

    func testTheRowCountsDownToTheNearestDeadlineOfTheTwo() {
        var e = AliveCheckInStore.Entry()
        e.mineEnabled = true
        e.mineIntervalSeconds = 3 * day
        e.mineLastConfirmedAt = t0
        e.theirsEnabled = true
        e.theirsIntervalSeconds = day
        e.theirsLastConfirmedAt = t0
        XCTAssertEqual(e.rowDueAt(), AliveCheckInPolicy.dueAt(lastConfirmedAt: t0, intervalSeconds: day))

        // Only one side configured: that side's deadline, not nil.
        var mineOnly = e
        mineOnly.theirsEnabled = false
        XCTAssertEqual(mineOnly.rowDueAt(),
                       AliveCheckInPolicy.dueAt(lastConfirmedAt: t0, intervalSeconds: 3 * day))
        XCTAssertNil(AliveCheckInStore.Entry().rowDueAt())
    }

    // MARK: - Intervals

    func testAnIntervalIsClampedToSomethingAHumanCanMeet() {
        XCTAssertEqual(AliveCheckInInterval.clamp(0), AliveCheckInInterval.minSeconds)
        XCTAssertEqual(AliveCheckInInterval.clamp(-5), AliveCheckInInterval.minSeconds)
        XCTAssertEqual(AliveCheckInInterval.clamp(10_000 * day), AliveCheckInInterval.maxSeconds)
        XCTAssertEqual(AliveCheckInInterval.clamp(day), day)
        XCTAssertTrue(AliveCheckInInterval.isPreset(AliveCheckInInterval.every72h))
        XCTAssertFalse(AliveCheckInInterval.isPreset(8 * AliveCheckInInterval.hour))
    }

    func testTheThreePresetsAreTheSameNumbersAsAndroids() {
        XCTAssertEqual(AliveCheckInInterval.every24h, 86_400)
        XCTAssertEqual(AliveCheckInInterval.every48h, 172_800)
        XCTAssertEqual(AliveCheckInInterval.every72h, 259_200)
        XCTAssertEqual(AliveCheckInInterval.default, 86_400)
    }

    // MARK: - The countdown's words

    func testACountdownShowsTheTwoLargestUnitsAndStopsThere() {
        func b(_ s: TimeInterval) -> AliveCheckInPolicy.Breakdown { AliveCheckInPolicy.breakdown(s) }
        XCTAssertEqual(b(TimeInterval(2 * 86_400 + 7 * 3_600 + 14 * 60 + 3)),
                       AliveCheckInPolicy.Breakdown(days: 2, hours: 7, minutes: 0))
        XCTAssertEqual(b(TimeInterval(5 * 3_600 + 30 * 60 + 59)),
                       AliveCheckInPolicy.Breakdown(days: 0, hours: 5, minutes: 30))
        XCTAssertEqual(b(45 * 60), AliveCheckInPolicy.Breakdown(days: 0, hours: 0, minutes: 45))
        XCTAssertTrue(b(30).isImminent)
        // A time PAST the deadline is described by the same two numbers: the sign
        // lives in the sentence ("due in" / "no response for"), not in the units.
        XCTAssertEqual(b(5 * 3_600), b(-5 * 3_600))
    }

    func testRemainingIsNegativeExactlyAsFarPastTheDeadlineAsItIs() {
        let due = AliveCheckInPolicy.dueAt(lastConfirmedAt: t0, intervalSeconds: day)
        XCTAssertEqual(AliveCheckInPolicy.remaining(lastConfirmedAt: t0, intervalSeconds: day,
                                                    now: due), 0, accuracy: 0.001)
        XCTAssertEqual(AliveCheckInPolicy.remaining(lastConfirmedAt: t0, intervalSeconds: day,
                                                    now: due.addingTimeInterval(3_600)),
                       -3_600, accuracy: 0.001)
    }
}
