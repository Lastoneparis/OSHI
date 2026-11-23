//
//  JitterBufferDecayTests.swift
//  OSHITests
//
//  __JITTER_RATCHET_2026_08_25__
//
//  The buffer grew 201 → 381 → 589 ms during a real 15-minute call and never
//  came back down, leaving the listener ~0.7 s behind on a 26 ms link. Nothing
//  crashed and nothing was logged as wrong — the only symptom was a person
//  saying the sound felt late.
//
//  The decay path existed. Its gate was `networkJitterMs < 10.0`, and the
//  measured median jitter on that call was 25.3 ms, so the gate never opened.
//  These tests drive the buffer at a REALISTIC jitter level rather than an
//  idealised one, which is the only way this class of defect shows up.
//
//  The fix does NOT loosen that gate — two pre-existing pins proved that wrong,
//  see the note in `maybeAdaptDepth`. It adds a release that only opens when the
//  depth floats far above its own burst floor, which is what the bad call did
//  and what a healthy call never does.
//

import XCTest
@testable import OSHI

final class JitterBufferDecayTests: XCTestCase {

    /// Feeds packets on a 20 ms cadence with `jitterMs` of arrival wobble,
    /// driving a virtual clock so a 15-minute call runs in milliseconds.
    private func drive(_ buffer: AdvancedJitterBuffer,
                       seconds: Int,
                       jitterMs: Double,
                       startingSeq: UInt64 = 0) -> UInt64 {
        var nowMs = 0.0
        buffer.clock = { nowMs }
        var seq = startingSeq
        let packets = seconds * 50            // 20 ms cadence
        for i in 0..<packets {
            // Deterministic wobble: alternating ± around the cadence, so the
            // RFC 3550 estimator settles near `jitterMs` without any randomness
            // (a flaky perf test is worse than none).
            let wobble = (i % 2 == 0) ? jitterMs : -jitterMs
            nowMs += 20.0 + wobble
            _ = buffer.addPacket(sequenceNumber: seq, audioData: Data(repeating: 0, count: 8))
            seq += 1
        }
        return seq
    }

    /// THE regression. A link with ordinary jitter must be able to come back
    /// down after a bad patch. Before the fix the depth stayed where the spike
    /// put it, for the rest of the call.
    func testDepthComesBackDownOnAnOrdinaryLink() {
        let buffer = AdvancedJitterBuffer()
        var nowMs = 0.0
        buffer.clock = { nowMs }

        // Inflate the depth the way a bunching link does — and it takes TWO
        // stalls, not one.
        //
        // My first version used a single 400 ms stall and asserted the depth had
        // grown. It had not: it read 180, unmoved, and the test failed. Two
        // reasons, both worth writing down. `recordLateArrival` only fires when
        // the CONSUMER has advanced past a sequence, and this driver never
        // drains the buffer, so nothing is ever "late". And the burst path
        // requires `peakMinCount` = 2 peaks before it will move anything — one
        // stall is explicitly "not evidence of a bursty link".
        var seq: UInt64 = 0
        for i in 0..<80 {
            nowMs += (i % 25 == 10) ? 400.0 : 20.0     // trois décrochages
            _ = buffer.addPacket(sequenceNumber: seq, audioData: Data([0]))
            seq += 1
        }
        let inflated = buffer.targetDepthMs
        XCTAssertGreaterThan(inflated, 180, "le pic aurait dû faire monter la profondeur")

        // Now 120 s of a perfectly ordinary link — the jitter the real call
        // actually measured (median 25.3 ms), NOT an idealised calm one.
        for i in 0..<(120 * 50) {
            nowMs += 20.0 + ((i % 2 == 0) ? 25.0 : -25.0)
            _ = buffer.addPacket(sequenceNumber: seq, audioData: Data([0]))
            seq += 1
        }

        XCTAssertLessThan(buffer.targetDepthMs, inflated,
                          "la profondeur doit redescendre quand le lien se calme — c'est le cliquet qui a coûté 0,7 s de retard")
    }

    /// The decay must stop where the link justifies, not run to the floor:
    /// shrinking past what the jitter warrants just re-introduces chop.
    func testDecayStopsAtWhatTheLinkJustifies() {
        let buffer = AdvancedJitterBuffer()
        var nowMs = 0.0
        buffer.clock = { nowMs }
        var seq: UInt64 = 0
        for i in 0..<50 {
            nowMs += (i == 25) ? 500.0 : 20.0
            _ = buffer.addPacket(sequenceNumber: seq, audioData: Data([0]))
            seq += 1
        }
        for i in 0..<(300 * 50) {
            nowMs += 20.0 + ((i % 2 == 0) ? 25.0 : -25.0)
            _ = buffer.addPacket(sequenceNumber: seq, audioData: Data([0]))
            seq += 1
        }
        XCTAssertGreaterThanOrEqual(buffer.targetDepthMs, 80,
                                    "ne doit jamais descendre sous le plancher minimum")
    }

    /// The buffer must still EXPAND — a decay fix that quietly disabled growth
    /// would trade a late-sounding call for a chopping one.
    func testExpansionStillWorks() {
        let buffer = AdvancedJitterBuffer()
        var nowMs = 0.0
        buffer.clock = { nowMs }
        let before = buffer.targetDepthMs
        var seq: UInt64 = 0
        for i in 0..<60 {
            nowMs += (i % 20 == 0) ? 350.0 : 20.0
            _ = buffer.addPacket(sequenceNumber: seq, audioData: Data([0]))
            seq += 1
        }
        XCTAssertGreaterThan(buffer.targetDepthMs, before,
                             "un lien qui décroche doit toujours faire monter la profondeur")
    }

    /// A new check proves nothing until it has been watched to fail.
    ///
    /// This states the PRE-FIX behaviour — depth stuck at its inflated value on
    /// a 25 ms link — as a known failure. If someone reinstates an absolute
    /// jitter gate below the normal floor, this stops failing and XCTest reports
    /// an unexpected pass.
    func testTheOldRatchetWouldStillBeCaught() {
        XCTExpectFailure("comportement d'avant: la profondeur restait bloquée en haut") {
            let buffer = AdvancedJitterBuffer()
            var nowMs = 0.0
            buffer.clock = { nowMs }
            var seq: UInt64 = 0
            for i in 0..<80 {
                nowMs += (i % 25 == 10) ? 400.0 : 20.0
                _ = buffer.addPacket(sequenceNumber: seq, audioData: Data([0]))
                seq += 1
            }
            let inflated = buffer.targetDepthMs
            for i in 0..<(120 * 50) {
                nowMs += 20.0 + ((i % 2 == 0) ? 25.0 : -25.0)
                _ = buffer.addPacket(sequenceNumber: seq, audioData: Data([0]))
                seq += 1
            }
            XCTAssertEqual(buffer.targetDepthMs, inflated,
                           "avant le correctif la profondeur ne bougeait plus")
        }
    }
}

// ============================================================================
// __CALM_EXPIRES_THE_EVIDENCE_2026_08_27__
// ============================================================================

/// MESURÉ sur l'appel du 27 août, après un trou réseau d'environ 4 s: le lien
/// était redevenu sain en DEUX secondes (iaP95 de 255 à 96 ms) et la profondeur
/// est restée à 600 ms pendant VINGT. C'est ce qu'on entend comme un appel qui
/// « met du temps à redevenir normal » après une coupure.
///
/// Une première tentative — fusionner les pics d'un même incident — a été
/// attrapée par `testExpansionStillWorks` ci-dessus, et à juste titre: un lien
/// qui stalle trois fois en une seconde et demie est vraiment mauvais, et
/// compter moins de pics cassait la protection contre la famine.
///
/// Ce n'est donc pas le comptage qu'il fallait changer, c'est l'EXPIRATION.
final class JitterCalmExpiryTests: XCTestCase {

    /// L'horloge virtuelle vit dans une CLASSE, pas dans une variable locale.
    ///
    /// Une première version passait `nowMs` en `inout` à une fonction d'aide
    /// pendant que la closure d'horloge le capturait: Swift a levé « Fatal access
    /// conflict detected » et fait tomber toute la suite. Deux accès simultanés à
    /// la même variable, dont un exclusif — la vérification d'accès exclusif a
    /// raison, et la boîte est la façon correcte de partager une horloge.
    private final class Clock { var ms: Double = 0 }

    private func drive(_ b: AdvancedJitterBuffer, _ c: Clock, _ seq: inout UInt64,
                       count: Int, gapEvery: Int?, gapMs: Double) {
        for i in 0..<count {
            if let g = gapEvery, i % g == 10 { c.ms += gapMs } else { c.ms += 20.0 }
            _ = b.addPacket(sequenceNumber: seq, audioData: Data([0]))
            seq += 1
        }
    }

    /// LE TEST QUI TIENT LA MESURE. Après la coupure, dix secondes de lien sain
    /// doivent avoir rendu de la latence — pas vingt.
    func testDepthIsGivenBackWithinSecondsOfRecovery() {
        let c = Clock(); var seq: UInt64 = 0
        let b = AdvancedJitterBuffer()
        b.clock = { c.ms }

        // UNE coupure, pas huit. La fixture précédente en produisait huit
        // (`i % 25` sur 200 paquets) et décrivait donc un lien franchement
        // mauvais, pas le hoquet isolé qu'on veut voir se pardonner.
        drive(b, c, &seq, count: 80, gapEvery: 30, gapMs: 500.0)
        XCTAssertGreaterThan(b.targetDepthMs, 180, "la coupure doit faire monter la profondeur")
        XCTAssertGreaterThan(b.gapBasedTargetMs, 0, "les pics doivent imposer un plancher")

        // Dix secondes d'un lien parfaitement sain — bien moins que les 20 s de
        // la fenêtre des pics.
        drive(b, c, &seq, count: 10 * 50, gapEvery: nil, gapMs: 0)

        // CE QUE CE CORRECTIF LIVRE, exactement: le PLANCHER est rendu. Tant
        // qu'il tenait, la décroissance normale ne pouvait rien reprendre —
        // `gapBasedTargetMs` est un minimum sous `currentDepthMs`, et il restait
        // en place 20 s quoi qu'il arrive.
        XCTAssertEqual(b.gapBasedTargetMs, 0,
            "le plancher imposé par la coupure tient encore alors que le lien est sain depuis 10 s")

        // ⚠️ CE QU'IL NE LIVRE PAS: la profondeur elle-même redescend par le
        // chemin lent et hystérétique existant, qui prend des dizaines de
        // secondes — c'est DÉLIBÉRÉ, une décroissance rapide s'entendrait comme
        // une oscillation. Voir `testDepthComesBackDownOnAnOrdinaryLink`, qui lui
        // laisse 120 s. Ce correctif ne l'accélère pas: il enlève le verrou qui
        // l'empêchait de commencer.
    }

    /// LE CONTRÔLE. Un lien qui reste mauvais n'accumule aucune arrivée calme et
    /// garde sa profondeur. Sans lui, « expirer plus vite » passerait aussi bien
    /// en expirant TOUT LE TEMPS — et on aurait rendu la famine au lieu de la
    /// latence.
    func testAStillBadLinkKeepsItsDepth() {
        let c = Clock(); var seq: UInt64 = 0
        let b = AdvancedJitterBuffer()
        b.clock = { c.ms }

        drive(b, c, &seq, count: 200, gapEvery: 25, gapMs: 500.0)
        let afterOutage = b.targetDepthMs

        drive(b, c, &seq, count: 10 * 50, gapEvery: 25, gapMs: 500.0)

        XCTAssertGreaterThanOrEqual(b.targetDepthMs, afterOutage,
            "un lien toujours mauvais a perdu sa profondeur — c'est la famine qui revient")
    }
}
