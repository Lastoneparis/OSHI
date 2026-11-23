//
//  EchoSuppressorGateTests.swift
//  OSHITests
//
//  The echo suppressor is timing logic, and timing logic that has never been
//  measured is timing logic nobody has checked. The version this replaces read
//  correctly, compiled, ran, and attenuated 11 276 frames on a real call — while
//  gating the WRONG 150 milliseconds, because it stamped the far end's level at
//  the moment a buffer was SCHEDULED rather than the moment it would be HEARD.
//  Nothing in the code could have told you that. These tests can.
//
//  Every test injects its own clock. No wall-clock, no sleeps: CoreSimulator
//  timing on this Mac is documented as flaky, and a suppressor test that depends
//  on it would be measuring the machine.
//

import XCTest
@testable import OSHI

final class EchoSuppressorGateTests: XCTestCase {

    /// Comfortably above `farEndFloorRms` (300) — "the speaker is talking".
    private let loudFarEnd: Double = 6000
    /// Leakage: an order of magnitude below what produced it.
    private let leakageMic: Double = 600
    private let frame: TimeInterval = 0.020

    /// __NOISE_FLOOR_CLAMP_2026_08_27__ Établit un bruit de fond BAS avant de
    /// présenter de la fuite.
    ///
    /// Sans ça la fixture est dégénérée: un seul niveau constant EST le bruit de
    /// fond, et la porte a raison de ne rien atténuer — il n'y a rien
    /// au-dessus du fond à retirer. Un vrai appel a des moments calmes; ces
    /// tests doivent en avoir aussi, sinon ils décrivent une pièce qui n'existe
    /// pas.
    private func primeQuietFloor(_ gate: inout EchoSuppressorGate,
                                 at t: TimeInterval,
                                 level: Double = 60) {
        for i in 0..<120 {
            _ = gate.targetGain(micRms: level, route: .loudspeaker,
                                now: t - Double(120 - i) * frame)
        }
    }

    // MARK: - The defect this type exists to fix

    /// THE REGRESSION TEST. A 700 ms player backlog is not hypothetical: it was
    /// measured on 1.0.39 and is what the jitter-buffer decay fix addressed.
    ///
    /// With that backlog, audio scheduled at t=0 is not audible until t=0.7.
    /// The old gate opened at t=0 and closed at t=0.15 — so it attenuated the
    /// microphone through 150 ms of SILENCE, and left it wide open for the whole
    /// stretch during which the speaker was actually emitting. Both halves are
    /// asserted here, because fixing only one would still leave the echo.
    func testGateFollowsPlayoutNotScheduling() {
        var gate = EchoSuppressorGate()
        let queued: TimeInterval = 0.700
        let t0: TimeInterval = 1_000.0
        primeQuietFloor(&gate, at: t0)

        gate.noteFarEndBuffer(rms: loudFarEnd, isConcealed: false,
                              queuedAheadSeconds: queued, durationSeconds: frame, now: t0)

        // At scheduling time nothing has reached the speaker yet. The old code
        // suppressed here; suppressing silence is what made the mic drop words.
        XCTAssertEqual(gate.targetGain(micRms: leakageMic, route: .loudspeaker, now: t0), 1.0,
                       "the buffer is still queued — the speaker is silent, so nothing may be attenuated")

        // Mid-backlog: still nothing out of the speaker.
        XCTAssertEqual(gate.targetGain(micRms: leakageMic, route: .loudspeaker, now: t0 + 0.35), 1.0,
                       "still queued 350 ms in — the old 150 ms hold had already expired here too")

        // Now it is actually playing. THIS is the frame that echoes.
        XCTAssertLessThan(gate.targetGain(micRms: leakageMic, route: .loudspeaker, now: t0 + queued), 1.0,
                          "the buffer is leaving the speaker now — this is the frame that leaks back")
    }

    /// The hold exists to ride through the gaps between words. It must be
    /// measured from the end of PLAYOUT, not from scheduling.
    func testHoldRunsFromEndOfPlayoutAndThenReleases() {
        var gate = EchoSuppressorGate()
        let queued: TimeInterval = 0.180
        let t0: TimeInterval = 500.0
        primeQuietFloor(&gate, at: t0)
        gate.noteFarEndBuffer(rms: loudFarEnd, isConcealed: false,
                              queuedAheadSeconds: queued, durationSeconds: frame, now: t0)

        let playoutEnd = t0 + queued + frame
        // Inside the hold: still guarding, so a pause between two words does not
        // re-open the mic onto the echo of the word that just played.
        XCTAssertLessThan(gate.targetGain(micRms: leakageMic, route: .loudspeaker,
                                          now: playoutEnd + EchoSuppressorGate.holdSeconds - 0.01), 1.0)
        // Past the hold: the room is quiet again, the mic must be untouched.
        XCTAssertEqual(gate.targetGain(micRms: leakageMic, route: .loudspeaker,
                                       now: playoutEnd + EchoSuppressorGate.holdSeconds + 0.01), 1.0)
    }

    /// THE CONTROL. A regression test proves nothing until you have watched the
    /// fixture discriminate — otherwise it is a test that would pass against the
    /// defect it claims to catch.
    ///
    /// The old decision cannot be called any more (it was deleted with the two
    /// scalars it lived on), so it is reproduced here verbatim, on the SAME
    /// fixture as `testGateFollowsPlayoutNotScheduling`. It must get BOTH
    /// answers backwards. If this test ever fails, the fixture has stopped
    /// exercising the defect and the regression test above has become
    /// decorative.
    func testTheOldSchedulingTimeDecisionGetsThisFixtureBackwards() {
        // Verbatim from VoiceCallManager before __ECHO_PLAYOUT_ALIGN_2026_08_26__:
        //     farEndActive     = now - farEndLastActiveAt < 0.15 && smoothed >= 300
        //     looksLikeLeakage = micRms < smoothed * 0.6
        //     target           = (farEndActive && looksLikeLeakage) ? 0.25 : 1.0
        // `farEndLastActiveAt` was stamped when the buffer was SCHEDULED — which
        // is the whole bug — and `smoothed` is an EWMA that has fully converged
        // after four frames of steady far-end speech.
        func oldTargetGain(micRms: Double, secondsSinceScheduled: TimeInterval) -> Float {
            let smoothed = loudFarEnd
            let farEndActive = secondsSinceScheduled < 0.15 && smoothed >= 300
            let looksLikeLeakage = micRms < smoothed * 0.6
            return (farEndActive && looksLikeLeakage) ? 0.25 : 1.0
        }

        let queued: TimeInterval = 0.700

        // At scheduling time the speaker is still silent — nothing can echo yet.
        // The old code attenuated anyway. That is the mic ducking through silence.
        XCTAssertLessThan(oldTargetGain(micRms: leakageMic, secondsSinceScheduled: 0), 1.0,
                          "control broken: the old decision no longer suppresses at scheduling time")

        // 700 ms later the buffer is actually leaving the speaker — THIS is the
        // frame that leaks back and reaches the far end as an echo. The old
        // 150 ms hold had expired 550 ms earlier, so the mic was wide open.
        XCTAssertEqual(oldTargetGain(micRms: leakageMic, secondsSinceScheduled: queued), 1.0,
                       "control broken: the old decision no longer misses the echoing frame")

        // And the new gate gets both the other way round — the assertions in
        // `testGateFollowsPlayoutNotScheduling`. Restated here so the two
        // verdicts sit side by side and the contrast is the readable part.
        var gate = EchoSuppressorGate()
        let t0: TimeInterval = 1_000.0
        primeQuietFloor(&gate, at: t0)
        gate.noteFarEndBuffer(rms: loudFarEnd, isConcealed: false,
                              queuedAheadSeconds: queued, durationSeconds: frame, now: t0)
        XCTAssertEqual(gate.targetGain(micRms: leakageMic, route: .loudspeaker, now: t0), 1.0)
        XCTAssertLessThan(gate.targetGain(micRms: leakageMic, route: .loudspeaker, now: t0 + queued), 1.0)
    }

    // MARK: - The double-talk guard, which is what keeps this safe

    /// Real speech is never attenuated, on either route. If this ever fails the
    /// suppressor has become a half-duplex gate, which is the failure mode that
    /// makes cheap speakerphones sound broken.
    func testRealSpeechPassesThroughUntouched() {
        var gate = EchoSuppressorGate()
        let t0: TimeInterval = 42.0
        gate.noteFarEndBuffer(rms: loudFarEnd, isConcealed: false,
                              queuedAheadSeconds: 0, durationSeconds: frame, now: t0)
        // Local at the same order of magnitude as the far end: someone is talking
        // over them, not leaking.
        let speech = loudFarEnd * EchoSuppressorGate.doubleTalkRatio + 1
        for route in [EchoSuppressorGate.Route.loudspeaker, .earpieceOrHeadset] {
            XCTAssertEqual(gate.targetGain(micRms: speech, route: route, now: t0), 1.0,
                           "double-talk must pass at full gain on \(route)")
        }
    }

    /// A far end below the floor cannot echo, so nothing may be attenuated —
    /// otherwise the mic ducks against line noise.
    func testQuietFarEndNeverAttenuates() {
        var gate = EchoSuppressorGate()
        let t0: TimeInterval = 7.0
        gate.noteFarEndBuffer(rms: EchoSuppressorGate.farEndFloorRms - 1, isConcealed: false,
                              queuedAheadSeconds: 0, durationSeconds: frame, now: t0)
        XCTAssertEqual(gate.targetGain(micRms: 10, route: .loudspeaker, now: t0), 1.0)
    }

    // MARK: - Depth follows the route

    /// The reported echo happened with the ANSWERER on speakerphone and went
    /// away when he turned it off. The playback stage puts 6.6× into the
    /// loudspeaker against 1.43× into the earpiece, so one depth cannot serve
    /// both — and the shallow one is what let the echo through.
    /// __ADAPTIVE_COUPLING_2026_08_27__ Les profondeurs de route ne sont plus des
    /// valeurs FIXES appliquées dès qu'on déclenche: ce sont des PLANCHERS que la
    /// soustraction ne peut pas franchir. Ce test garde donc la propriété qui
    /// compte — le haut-parleur peut être coupé plus profond que l'écouteur — sans
    /// prétendre que la profondeur est atteinte à chaque trame.
    func testLoudspeakerFloorGoesDeeperThanEarpieceFloor() {
        XCTAssertLessThan(EchoSuppressorGate.loudspeakerGain,
                          EchoSuppressorGate.earpieceGain,
                          "le haut-parleur fuit davantage: son plancher doit être plus bas")
        XCTAssertGreaterThan(EchoSuppressorGate.loudspeakerGain, 0, "atténuer, pas couper")

        // Et sur une fuite écrasante, chaque route atteint bien SON plancher.
        for (route, floorGain) in [(EchoSuppressorGate.Route.loudspeaker, EchoSuppressorGate.loudspeakerGain),
                                   (.earpieceOrHeadset, EchoSuppressorGate.earpieceGain)] {
            var gate = EchoSuppressorGate()
            let t0: TimeInterval = 7_000.0
            for i in 0..<250 { _ = gate.targetGain(micRms: 10, route: route, now: t0 + Double(i) * frame) }
            settleCoupling(&gate, mic: 800, far: 900, floorLevel: 10, route: route,
                           at: t0 + 6, frames: 900)
            gate.noteFarEndBuffer(rms: 900, isConcealed: false, queuedAheadSeconds: 0,
                                  durationSeconds: frame, now: t0 + 6 + 900 * frame)
            let g = gate.targetGain(micRms: 800, route: route, now: t0 + 6 + 900 * frame)
            XCTAssertGreaterThanOrEqual(g, floorGain, "descendu sous le plancher de \(route)")
        }
    }

    // MARK: - The pumping regression (__NOISE_FLOOR_CLAMP_2026_08_27__)

    /// MEASURED ON DEVICE, and it was a regression I shipped. Real call,
    /// 2026-08-27, loudspeaker: 477 of 1175 mic frames attenuated — 41 % of the
    /// time — at −24 dB. Reported as "un petit soufflement derrière".
    ///
    /// The depth was not the problem. Putting it on the ROOM TONE was. Dropping
    /// the background 24 dB and releasing it, 41 % of the time, in step with the
    /// far end's speech, is pumping: the room breathes to someone else's rhythm.
    /// The ramp cannot fix that — it removes the click, not the modulation.
    ///
    /// So: a microphone sitting AT its own noise floor has nothing to remove and
    /// must be left alone, however loud the far end is.
    func testAMicAtItsOwnNoiseFloorIsNotDucked() {
        var gate = EchoSuppressorGate()
        let t0: TimeInterval = 200.0
        let roomTone: Double = 260

        // Establish the floor: a stretch of nothing but room tone.
        for i in 0..<200 {
            _ = gate.targetGain(micRms: roomTone, route: .loudspeaker,
                                now: t0 + Double(i) * frame)
        }
        // Now the far end starts talking, loudly, while the mic still hears only
        // the room.
        gate.noteFarEndBuffer(rms: loudFarEnd, isConcealed: false,
                              queuedAheadSeconds: 0, durationSeconds: frame, now: t0 + 5)
        let g = gate.targetGain(micRms: roomTone, route: .loudspeaker, now: t0 + 5)
        XCTAssertEqual(g, 1.0, accuracy: 0.02,
                       "ducked the room tone itself — that is the pumping the owner heard")
    }

    /// The other half: a mic well ABOVE its noise floor is real leakage and must
    /// still be cut hard, or the clamp would have simply disabled the fix.
    func testRealLeakageWellAboveTheFloorIsStillCutHard() {
        var gate = EchoSuppressorGate()
        let t0: TimeInterval = 300.0
        let roomTone: Double = 60

        for i in 0..<200 {
            _ = gate.targetGain(micRms: roomTone, route: .loudspeaker,
                                now: t0 + Double(i) * frame)
        }
        // __ADAPTIVE_COUPLING_2026_08_27__ Le couplage doit d'abord être OBSERVÉ:
        // la porte part de zéro et n'atténue rien tant qu'elle n'a rien mesuré.
        // C'est le prix de ne plus rien deviner, et il se paye une fois par appel.
        settleCoupling(&gate, mic: 1200, far: loudFarEnd, floorLevel: roomTone,
                       route: .loudspeaker, at: t0 + 5)
        let tEnd = t0 + 5 + 600 * frame
        gate.noteFarEndBuffer(rms: loudFarEnd, isConcealed: false,
                              queuedAheadSeconds: 0, durationSeconds: frame, now: tEnd)
        // Leakage an order of magnitude over the room tone.
        let g = gate.targetGain(micRms: 1200, route: .loudspeaker, now: tEnd)
        XCTAssertLessThanOrEqual(g, 0.10, "loud leakage must still be attenuated")
        XCTAssertGreaterThanOrEqual(g, EchoSuppressorGate.loudspeakerGain,
                                    "the route depth is the floor — never deeper")
    }

    /// The clamp must never AMPLIFY. Right after a sharp drop in level the
    /// tracked floor can momentarily sit above the mic; the gain has to stay at
    /// unity, not go over it.
    func testClampNeverProducesGainAboveUnity() {
        var gate = EchoSuppressorGate()
        let t0: TimeInterval = 400.0
        for i in 0..<50 {
            _ = gate.targetGain(micRms: 5000, route: .loudspeaker, now: t0 + Double(i) * frame)
        }
        gate.noteFarEndBuffer(rms: loudFarEnd, isConcealed: false,
                              queuedAheadSeconds: 0, durationSeconds: frame, now: t0 + 2)
        for mic in [1.0, 10.0, 50.0, 200.0] {
            let g = gate.targetGain(micRms: mic, route: .loudspeaker, now: t0 + 2)
            XCTAssertLessThanOrEqual(g, 1.0, "gain above unity at micRms=\(mic)")
            XCTAssertGreaterThan(g, 0)
        }
    }

    /// __WINDOWED_MINIMUM_2026_08_27__ THE REGRESSION TEST FOR THE INERT CLAMP.
    ///
    /// MEASURED on device: `echoNoiseFloor=20` on BOTH phones, for the whole
    /// call — 20 being the absolute floor. The first version fell INSTANTLY to
    /// the lowest frame ever seen (one muted frame at pickup is enough) and rose
    /// at 1 %/s, i.e. over two minutes to get back to 200. So it sat at the
    /// bottom, `floor/micRms` was tiny, and `max(routeGain, tiny)` always
    /// returned `routeGain`. The clamp clamped nothing.
    ///
    /// A sliding minimum cannot do that: one freak-quiet frame only counts for
    /// the length of the window, then it ages out.
    func testOneSilentFrameDoesNotLatchTheFloorForever() {
        var gate = EchoSuppressorGate()
        let t0: TimeInterval = 500.0
        let room: Double = 400

        // A single dead-silent frame — the pickup artefact that broke it.
        _ = gate.targetGain(micRms: 0, route: .loudspeaker, now: t0)
        XCTAssertEqual(gate.currentNoiseFloorRms, EchoSuppressorGate.minNoiseFloorRms, accuracy: 1)

        // Then ordinary room tone for longer than the whole window.
        let windowFrames = EchoSuppressorGate.noiseSubWindowFrames
            * EchoSuppressorGate.noiseSubWindowCount
        for i in 0..<(windowFrames + 2 * EchoSuppressorGate.noiseSubWindowFrames) {
            _ = gate.targetGain(micRms: room, route: .loudspeaker,
                                now: t0 + Double(i + 1) * frame)
        }
        XCTAssertEqual(gate.currentNoiseFloorRms, room, accuracy: 1,
                       "the silent frame never aged out — the clamp is inert again")
    }

    // MARK: - __ADAPTIVE_COUPLING_2026_08_27__ Les deux cas que rien ne séparait

    /// Aide: fait vivre la porte assez longtemps pour que le couplage converge
    /// vers ce que l'acoustique lui montre.
    /// ALTERNE fuite et silence, comme une vraie conversation.
    ///
    /// Une fixture qui donnerait la fuite EN CONTINU se fait manger par le suivi
    /// du bruit de fond: son minimum glissant finit par prendre le niveau de la
    /// fuite pour le plancher de la pièce, l'excès tombe à zéro et le couplage
    /// mesuré s'effondre. Ce n'est pas un défaut du code — c'est une fixture qui
    /// décrit un haut-parleur qui ne se tait jamais.
    ///
    /// Un correspondant réel fait des pauses, et c'est pendant ces pauses que le
    /// plancher se retrouve. `quietEvery` les reproduit.
    private func settleCoupling(_ gate: inout EchoSuppressorGate,
                                mic: Double, far: Double, floorLevel: Double,
                                route: EchoSuppressorGate.Route,
                                at t: TimeInterval, frames: Int = 600,
                                quietEvery: Int = 5) {
        for i in 0..<frames {
            let now = t + Double(i) * frame
            let quiet = i % quietEvery == 0
            // Pendant une pause du correspondant, rien ne joue et le micro
            // retombe à son plancher.
            if !quiet {
                gate.noteFarEndBuffer(rms: far, isConcealed: false,
                                      queuedAheadSeconds: 0, durationSeconds: frame, now: now)
            }
            _ = gate.targetGain(micRms: quiet ? floorLevel : mic, route: route, now: now)
        }
    }

    /// LE CAS DU SOUFFLEMENT. Haut-parleur qui hurle (6000), micro qui reste à
    /// son bruit de fond (55 pour un plancher de 20). Le couplage réel est
    /// dérisoire — la pièce ne renvoie rien — donc il n'y a RIEN à retirer et le
    /// fond ne doit pas bouger.
    ///
    /// Aucun seuil ne pouvait obtenir ça: ce cas et celui du dessous ont le même
    /// rapport micro/plancher, 2,75 contre 2,74.
    func testALoudSpeakerThatTheMicDoesNotHearLeavesTheRoomAlone() {
        var gate = EchoSuppressorGate()
        let t0: TimeInterval = 3_000.0
        // « Le micro n'entend pas le haut-parleur » veut dire quelque chose de
        // précis: le micro reste À SON PLANCHER pendant que le correspondant
        // hurle. Une fixture où il monterait au-dessus décrirait un micro qui
        // l'entend un peu — et l'atténuer serait alors juste.
        let roomTone: Double = 55
        for i in 0..<250 {
            _ = gate.targetGain(micRms: roomTone, route: .loudspeaker, now: t0 + Double(i) * frame)
        }
        settleCoupling(&gate, mic: roomTone, far: 6000, floorLevel: roomTone,
                       route: .loudspeaker, at: t0 + 6)
        gate.noteFarEndBuffer(rms: 6000, isConcealed: false, queuedAheadSeconds: 0,
                              durationSeconds: frame, now: t0 + 6 + 600 * frame)
        let g = gate.targetGain(micRms: roomTone, route: .loudspeaker, now: t0 + 6 + 600 * frame)
        XCTAssertGreaterThan(g, 0.95,
            "le fond de la pièce est écrasé alors que le micro n'entend pas le haut-parleur")
    }

    /// LA FUITE RÉELLE, fixture de l'iPhone 14 mesurée: plancher 53, micro 145,
    /// lecture 265. Rapport micro/plancher 2,74 — le MÊME qu'au-dessus — mais le
    /// couplage, lui, vaut 0,55 contre 0,009. C'est ce qui les sépare, et c'est
    /// la seule chose qui le pouvait.
    func testRealLeakageOnTheSameRatioIsStillRemoved() {
        var gate = EchoSuppressorGate()
        let t0: TimeInterval = 4_000.0
        for i in 0..<250 {
            _ = gate.targetGain(micRms: 53, route: .loudspeaker, now: t0 + Double(i) * frame)
        }
        settleCoupling(&gate, mic: 145, far: 265, floorLevel: 53, route: .loudspeaker, at: t0 + 6)
        gate.noteFarEndBuffer(rms: 265, isConcealed: false, queuedAheadSeconds: 0,
                              durationSeconds: frame, now: t0 + 6 + 600 * frame)
        let g = gate.targetGain(micRms: 145, route: .loudspeaker, now: t0 + 6 + 600 * frame)
        XCTAssertLessThan(g, 0.75,
            "la fuite mesurée de l'iPhone 14 n'est pas retirée — le suppresseur est désarmé sur ce micro")
        XCTAssertGreaterThanOrEqual(g, EchoSuppressorGate.loudspeakerGain,
            "jamais plus bas que la profondeur de route")
    }

    /// Le couplage est une propriété de la pièce, pas du volume. Doubler le
    /// niveau joué double l'écho prédit: le résidu doit rester comparable, pas
    /// s'effondrer.
    func testEstimateFollowsTheRoomNotTheVolume() {
        var gate = EchoSuppressorGate()
        let t0: TimeInterval = 5_000.0
        for i in 0..<250 {
            _ = gate.targetGain(micRms: 53, route: .loudspeaker, now: t0 + Double(i) * frame)
        }
        settleCoupling(&gate, mic: 145, far: 265, floorLevel: 53, route: .loudspeaker, at: t0 + 6)
        let c = gate.currentCoupling
        XCTAssertGreaterThan(c, 0.2, "le couplage mesuré n'a pas convergé")
        XCTAssertLessThanOrEqual(c, EchoSuppressorGate.maxCoupling)
    }

    /// Il n'existe pas de gain nul. Quoi que prédise l'estimation, il reste
    /// toujours de quoi entendre quelqu'un qui se met à parler.
    func testNeverMutesHoweverStrongTheEstimate() {
        var gate = EchoSuppressorGate()
        let t0: TimeInterval = 6_000.0
        for i in 0..<250 {
            _ = gate.targetGain(micRms: 10, route: .loudspeaker, now: t0 + Double(i) * frame)
        }
        settleCoupling(&gate, mic: 500, far: 900, floorLevel: 10, route: .loudspeaker,
                       at: t0 + 6, frames: 800)
        gate.noteFarEndBuffer(rms: 900, isConcealed: false, queuedAheadSeconds: 0,
                              durationSeconds: frame, now: t0 + 6 + 800 * frame)
        let g = gate.targetGain(micRms: 500, route: .loudspeaker, now: t0 + 6 + 800 * frame + frame)
        XCTAssertGreaterThanOrEqual(g, EchoSuppressorGate.loudspeakerGain)
        XCTAssertGreaterThan(g, 0)
    }

    /// It is still a FLOOR, not a level follower    /// It is still a FLOOR, not a level follower: sustained speech must not drag
    /// it upward, or the clamp would stop protecting anything.
    func testFloorDoesNotFollowSpeechUpward() {
        var gate = EchoSuppressorGate()
        let t0: TimeInterval = 700.0
        // Establish a quiet room.
        for i in 0..<200 {
            _ = gate.targetGain(micRms: 90, route: .loudspeaker, now: t0 + Double(i) * frame)
        }
        XCTAssertEqual(gate.currentNoiseFloorRms, 90, accuracy: 1)
        // One second of loud speech — shorter than the window, so the quiet
        // sub-windows are still live and must win.
        for i in 0..<50 {
            _ = gate.targetGain(micRms: 9000, route: .loudspeaker,
                                now: t0 + Double(200 + i) * frame)
        }
        XCTAssertEqual(gate.currentNoiseFloorRms, 90, accuracy: 1,
                       "speech dragged the floor up — it is no longer a floor")
    }

    /// And it must RISE when the room genuinely gets louder, or a call that
    /// moves into a noisy place would keep clamping against a floor that no
    /// longer exists.
    func testFloorRisesWhenTheRoomActuallyGetsLouder() {
        var gate = EchoSuppressorGate()
        let t0: TimeInterval = 900.0
        for i in 0..<200 {
            _ = gate.targetGain(micRms: 50, route: .loudspeaker, now: t0 + Double(i) * frame)
        }
        XCTAssertEqual(gate.currentNoiseFloorRms, 50, accuracy: 1)
        // Sustained louder room, longer than the whole window.
        let windowFrames = EchoSuppressorGate.noiseSubWindowFrames
            * EchoSuppressorGate.noiseSubWindowCount
        for i in 0..<(windowFrames + EchoSuppressorGate.noiseSubWindowFrames) {
            _ = gate.targetGain(micRms: 500, route: .loudspeaker,
                                now: t0 + Double(200 + i) * frame)
        }
        XCTAssertEqual(gate.currentNoiseFloorRms, 500, accuracy: 1,
                       "the floor never caught up with a genuinely louder room")
    }

    /// The floor belongs to this microphone in this room. After a route change
    /// or an engine restart it is a different microphone.
    func testResetClearsTheNoiseFloor() {
        var gate = EchoSuppressorGate()
        _ = gate.targetGain(micRms: 700, route: .loudspeaker, now: 600)
        XCTAssertGreaterThan(gate.currentNoiseFloorRms, 0)
        gate.reset()
        XCTAssertEqual(gate.currentNoiseFloorRms, 0)
    }

    // MARK: - Bookkeeping

    /// PLC frames are synthesised. Counting them as far-end speech would hold
    /// the gate open through silence — the behaviour the first version already
    /// had right, and which must survive the rewrite.
    func testConcealedFramesAreNotFarEndSpeech() {
        var gate = EchoSuppressorGate()
        let t0: TimeInterval = 11.0
        gate.noteFarEndBuffer(rms: loudFarEnd, isConcealed: true,
                              queuedAheadSeconds: 0, durationSeconds: frame, now: t0)
        XCTAssertEqual(gate.farEndLevel(now: t0), 0)
        XCTAssertEqual(gate.targetGain(micRms: leakageMic, route: .loudspeaker, now: t0), 1.0)
    }

    /// `reset()` is called when the player queue is dropped. The recorded
    /// timeline then describes audio that will never be played, and a gate that
    /// keeps believing it would attenuate the microphone into silence.
    func testResetForgetsAQueueThatNoLongerExists() {
        var gate = EchoSuppressorGate()
        let t0: TimeInterval = 90.0
        primeQuietFloor(&gate, at: t0)
        gate.noteFarEndBuffer(rms: loudFarEnd, isConcealed: false,
                              queuedAheadSeconds: 0, durationSeconds: frame, now: t0)
        XCTAssertLessThan(gate.targetGain(micRms: leakageMic, route: .loudspeaker, now: t0), 1.0)
        gate.reset()
        // Re-prime: sinon ce test passerait pour la mauvaise raison — un fond
        // remis à zéro rend 1,0 tout seul, et on ne prouverait plus rien sur la
        // chronologie de lecture, qui est ce que ce test surveille.
        primeQuietFloor(&gate, at: t0)
        XCTAssertEqual(gate.targetGain(micRms: leakageMic, route: .loudspeaker, now: t0), 1.0)
    }

    /// A fresh gate has every slot zeroed. Zero must never be mistaken for a
    /// playout window that overlaps a real clock, or the very first mic frame of
    /// every call would be attenuated.
    func testFreshGateReportsSilence() {
        var gate = EchoSuppressorGate()
        XCTAssertEqual(gate.farEndLevel(now: 0), 0)
        XCTAssertEqual(gate.farEndLevel(now: 1_000_000), 0)
        XCTAssertEqual(gate.targetGain(micRms: 5000, route: .loudspeaker, now: 1_000_000), 1.0)
    }

    /// The ring must hold more playout timeline than the deepest queue the
    /// player is allowed to accumulate, or a lookup falls off the end while the
    /// audio it is looking for is still pending. 128 × 20 ms ≈ 2.5 s.
    func testRingSpansMoreThanTheDeepestQueue() {
        var gate = EchoSuppressorGate()
        let t0: TimeInterval = 0.5
        let queued: TimeInterval = 0.700
        primeQuietFloor(&gate, at: t0)
        // Fill the whole ring with one continuous 20 ms-per-slot stream, exactly
        // as a call does at 50 packets per second.
        for i in 0..<EchoSuppressorGate.traceCapacity {
            gate.noteFarEndBuffer(rms: loudFarEnd, isConcealed: false,
                                  queuedAheadSeconds: queued,
                                  durationSeconds: frame,
                                  now: t0 + Double(i) * frame)
        }
        // The oldest slot's playout window is still in the future relative to
        // the newest slot's scheduling time — i.e. the ring never dropped a
        // buffer that had not yet been heard.
        let oldestPlayoutStart = t0 + queued
        XCTAssertLessThan(gate.targetGain(micRms: leakageMic, route: .loudspeaker,
                                          now: oldestPlayoutStart), 1.0,
                          "the ring wrapped and forgot audio that had not played yet")
    }

    /// The lookup takes the loudest overlapping slot, so a single quiet frame in
    /// the middle of a sentence cannot punch a hole in the guard.
    func testLoudestOverlappingSlotWins() {
        var gate = EchoSuppressorGate()
        let t0: TimeInterval = 60.0
        gate.noteFarEndBuffer(rms: loudFarEnd, isConcealed: false,
                              queuedAheadSeconds: 0, durationSeconds: frame, now: t0)
        gate.noteFarEndBuffer(rms: 1, isConcealed: false,
                              queuedAheadSeconds: 0, durationSeconds: frame, now: t0 + frame)
        XCTAssertEqual(gate.farEndLevel(now: t0 + frame + 0.001), loudFarEnd,
                       "a dip between two words must not release the guard")
    }
}
