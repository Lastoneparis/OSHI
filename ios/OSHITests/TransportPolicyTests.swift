//
//  TransportPolicyTests.swift
//  OSHITests
//
//  The order messages travel in, and the one promise about it that has to hold.
//
//  __PREFERENCE_RANK_WAS_A_LIE_2026_10_20__ These tests exist because the thing
//  that LOOKED like the routing order was not it. `TransportKind.preferenceRank`
//  ranked the transports 0…3 and said so in its own doc comment:
//
//      The order is DESCRIPTIVE … Changing a case here does not change routing.
//
//  So the ordering was a comment, and the behaviour was spread over three
//  branches of `MessageManager.fallbackToIPFS` plus a `meshAvailableNow` ladder
//  1,200 lines away. Nothing could be tested, because nothing was decided in
//  one place. `TransportPolicy.order(_:)` is that place; the last test here is
//  the one that keeps it honest.
//

import XCTest
@testable import OSHI

final class TransportPolicyTests: XCTestCase {

    private var savedInternetDisabled = false
    private var savedPreferRadio = false

    override func setUp() {
        super.setUp()
        // These are the USER's real preferences. Save and restore them, or a
        // test run quietly reconfigures the app it is testing.
        savedInternetDisabled = UserDefaults.standard.bool(forKey: TransportPolicy.internetSendingDisabledKey)
        savedPreferRadio = UserDefaults.standard.bool(forKey: TransportPolicy.preferRadioOverRelayKey)
        TransportPolicy.internetAllowedForSending = true
        TransportPolicy.preferRadioOverRelay = false
    }

    override func tearDown() {
        UserDefaults.standard.set(savedInternetDisabled, forKey: TransportPolicy.internetSendingDisabledKey)
        UserDefaults.standard.set(savedPreferRadio, forKey: TransportPolicy.preferRadioOverRelayKey)
        super.tearDown()
    }

    /// The ordinary case, unchanged: online, peer reachable, no radio paired.
    private func online(media: Bool = false, radio: Bool = false,
                        peerOnMesh: Bool = false, peerOnline: Bool = true) -> TransportPolicy.Conditions {
        .init(physicalPath: true, peerOnMesh: peerOnMesh, radioReady: radio,
              peerLikelyOnline: peerOnline, carriesMedia: media)
    }

    // MARK: (a) The guarantee

    /// THE property. Not "the relay is deprioritised" — the relay is not an
    /// option at all. Checked across every combination of the other inputs,
    /// because a guarantee that holds in the case you thought of is not one.
    func testNothingGoesOverTheInternetWhenTheUserForbadeIt() {
        TransportPolicy.internetAllowedForSending = false
        for path in [true, false] {
            for mesh in [true, false] {
                for radio in [true, false] {
                    for peerOnline in [true, false] {
                        for media in [true, false] {
                            let c = TransportPolicy.Conditions(
                                physicalPath: path, peerOnMesh: mesh, radioReady: radio,
                                peerLikelyOnline: peerOnline, carriesMedia: media)
                            XCTAssertFalse(TransportPolicy.order(c).contains(.internetRelay),
                                           "relay offered with internet sending off: \(c)")
                        }
                    }
                }
            }
        }
    }

    /// And the honest consequence: with the internet forbidden and no offline
    /// route, there is NOWHERE to send. An empty list is the right answer —
    /// better than a silent relay send, and the caller keeps the message
    /// `.pending` rather than marking it failed.
    func testForbiddingTheInternetWithNoRadioLeavesNoRouteAtAll() {
        TransportPolicy.internetAllowedForSending = false
        XCTAssertEqual(TransportPolicy.order(online()), [],
                       "a message must not quietly find a route the user forbade")
    }

    /// Switching it back on restores exactly the previous behaviour.
    func testAllowingTheInternetAgainRestoresTheRelay() {
        TransportPolicy.internetAllowedForSending = false
        XCTAssertEqual(TransportPolicy.order(online()), [])
        TransportPolicy.internetAllowedForSending = true
        XCTAssertEqual(TransportPolicy.order(online()), [.internetRelay])
    }

    // MARK: (b) The radio, when the user wants it first

    func testPreferringTheRadioPutsTextOnItBeforeTheRelay() {
        TransportPolicy.preferRadioOverRelay = true
        XCTAssertEqual(TransportPolicy.order(online(radio: true)), [.loRa, .internetRelay],
                       "the preference did not reach the order")
    }

    /// The limit that keeps the app usable. A LoRa frame carries ~180–240 bytes
    /// at seconds per frame, so a photo over the radio is minutes on the air
    /// while a working network path sits unused. Preferring the radio is about
    /// your messages, not your media.
    func testPreferringTheRadioStillSendsMediaOverTheNetwork() {
        TransportPolicy.preferRadioOverRelay = true
        XCTAssertEqual(TransportPolicy.order(online(media: true, radio: true)), [.internetRelay],
                       "media was put on the radio while a network path existed")
    }

    /// Off-grid, media HAS no better option, so the radio is allowed to carry
    /// it. This is the pre-existing rule, kept.
    func testOffGridMediaMayUseTheRadio() {
        let c = TransportPolicy.Conditions(physicalPath: false, peerOnMesh: false, radioReady: true,
                                           peerLikelyOnline: true, carriesMedia: true)
        XCTAssertEqual(TransportPolicy.order(c), [.loRa])
    }

    /// Nothing changes for the overwhelming majority: no radio paired, peer
    /// reachable, preference off.
    func testTheDefaultOrderIsUnchangedForSomeoneWithoutARadio() {
        XCTAssertEqual(TransportPolicy.order(online()), [.internetRelay])
        XCTAssertEqual(TransportPolicy.order(online(peerOnMesh: true)), [.bluetoothMesh, .internetRelay])
    }

    /// And the two cases that already sent over LoRa keep doing so without any
    /// preference being set.
    func testRadioStillComesFirstWhenThePeerLooksUnreachable() {
        XCTAssertEqual(TransportPolicy.order(online(radio: true, peerOnline: false)),
                       [.loRa, .internetRelay])
    }

    func testRadioStillComesFirstOffGrid() {
        let c = TransportPolicy.Conditions(physicalPath: false, peerOnMesh: false, radioReady: true,
                                           peerLikelyOnline: true, carriesMedia: false)
        XCTAssertEqual(TransportPolicy.order(c), [.loRa])
    }

    /// With a radio paired and the peer reachable, the radio stays BEHIND the
    /// relay unless asked for — it is a fallback, not a downgrade imposed on
    /// everyone who ever paired a node.
    func testRadioStaysBehindTheRelayWhenNobodyAskedForIt() {
        XCTAssertEqual(TransportPolicy.order(online(radio: true)), [.internetRelay, .loRa])
    }

    // MARK: (b2) __LORA_THEN_INTERNET_2026_10_27__ the second half of the order

    /// THE property the user asked for, stated as a test: with the radio
    /// preferred, the relay is still in the order BEHIND it. "Prefer my radio"
    /// has to mean "try the radio first", not "give up the internet".
    func testPreferringTheRadioLeavesTheRelayBehindItRatherThanRemovingIt() {
        TransportPolicy.preferRadioOverRelay = true
        let o = TransportPolicy.order(online(radio: true))
        XCTAssertEqual(o, [.loRa, .internetRelay])
        XCTAssertTrue(o.contains(.internetRelay),
                      "preferring the radio must not take the internet out of the order")
    }

    /// And the step that was missing: the radio did not reach the peer, so the
    /// relay picks the message up. This is the behaviour that made the second
    /// entry in the order above reachable at all — handing bytes to a radio
    /// used to count as delivery, so nothing ever fell through to it.
    func testTheRelayPicksUpWhatTheRadioCouldNotDeliver() {
        XCTAssertTrue(TransportPolicy.internetPicksUpAfterRadio(
            hasPhysicalPath: true, internetAllowedHere: true, alreadyDelivered: false))
    }

    /// Off-grid there is nothing to escalate TO. The message waits; it is not
    /// failed, and it is certainly not sent over a link that does not exist.
    func testNothingEscalatesWithoutAPhysicalPath() {
        XCTAssertFalse(TransportPolicy.internetPicksUpAfterRadio(
            hasPhysicalPath: false, internetAllowedHere: true, alreadyDelivered: false))
    }

    /// The guarantee survives the new escalation. `__NEVER_OVER_THE_INTERNET__`
    /// would be worth nothing if a radio coming up short were a licence to use
    /// the relay after all — that is precisely the quiet re-routing it forbids.
    func testTheRadioFailingIsNotALicenceToUseAForbiddenRelay() {
        XCTAssertFalse(TransportPolicy.internetPicksUpAfterRadio(
            hasPhysicalPath: true, internetAllowedHere: false, alreadyDelivered: false))
    }

    /// A late radio failure for a message another transport already delivered
    /// must not re-send it. The radio's ACK window is 90 s; a relay delivery
    /// inside that window is entirely normal when both are in play.
    func testADeliveredMessageIsNeverReSentWhenTheRadioReportsLate() {
        XCTAssertFalse(TransportPolicy.internetPicksUpAfterRadio(
            hasPhysicalPath: true, internetAllowedHere: true, alreadyDelivered: true))
        XCTAssertFalse(TransportPolicy.internetPicksUpAfterRadio(
            hasPhysicalPath: false, internetAllowedHere: false, alreadyDelivered: true))
    }

    /// The per-conversation switch is what the caller folds in, so this rule
    /// must not read the global one behind its back: a conversation marked
    /// offline-only inside an app that allows the internet everywhere else
    /// still does not escalate.
    func testTheConversationSwitchIsWhatDecidesNotTheGlobalOne() {
        TransportPolicy.internetAllowedForSending = true   // allowed app-wide
        XCTAssertFalse(TransportPolicy.internetPicksUpAfterRadio(
            hasPhysicalPath: true, internetAllowedHere: false, alreadyDelivered: false),
            "the global preference overrode the conversation's own answer")
    }

    // MARK: (c) The anti-drift property

    /// THE test that justifies the refactor. `radioComesBeforeRelay` is what
    /// the send path branches on; `order` is what the policy publishes. If they
    /// could disagree we would be back to a documented order that does not
    /// match the code — the exact bug this replaced.
    ///
    /// So: over every combination of inputs AND both preferences, the branch
    /// must say "radio first" exactly when the published list puts it first.
    func testTheBranchTakenAlwaysAgreesWithThePublishedOrder() {
        for internetAllowed in [true, false] {
            for prefer in [true, false] {
                TransportPolicy.internetAllowedForSending = internetAllowed
                TransportPolicy.preferRadioOverRelay = prefer
                for path in [true, false] {
                    for mesh in [true, false] {
                        for radio in [true, false] {
                            for peerOnline in [true, false] {
                                for media in [true, false] {
                                    let c = TransportPolicy.Conditions(
                                        physicalPath: path, peerOnMesh: mesh, radioReady: radio,
                                        peerLikelyOnline: peerOnline, carriesMedia: media)
                                    let list = TransportPolicy.order(c)
                                    let radioIdx = list.firstIndex(of: .loRa)
                                    let relayIdx = list.firstIndex(of: .internetRelay)
                                    let expected: Bool
                                    switch (radioIdx, relayIdx) {
                                    case (nil, _):            expected = false
                                    case (_, nil):            expected = true
                                    case let (r?, l?):        expected = r < l
                                    }
                                    XCTAssertEqual(TransportPolicy.radioComesBeforeRelay(c), expected,
                                                   "branch and published order disagree for \(c) (list: \(list))")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /// A transport is never offered twice, and the list never contains a
    /// transport whose precondition is false. Cheap, but it is what stops a
    /// future edit to `order` from producing a list the send path walks twice.
    func testTheOrderNeverRepeatsOrInventsATransport() {
        for internetAllowed in [true, false] {
            for prefer in [true, false] {
                TransportPolicy.internetAllowedForSending = internetAllowed
                TransportPolicy.preferRadioOverRelay = prefer
                for path in [true, false] {
                    for mesh in [true, false] {
                        for radio in [true, false] {
                            let c = TransportPolicy.Conditions(
                                physicalPath: path, peerOnMesh: mesh, radioReady: radio,
                                peerLikelyOnline: false, carriesMedia: false)
                            let list = TransportPolicy.order(c)
                            XCTAssertEqual(list.count, Set(list).count, "a transport appears twice: \(list)")
                            if !mesh { XCTAssertFalse(list.contains(.bluetoothMesh)) }
                            if !radio { XCTAssertFalse(list.contains(.loRa)) }
                            if !path { XCTAssertFalse(list.contains(.internetRelay)) }
                        }
                    }
                }
            }
        }
    }

    // MARK: __ANTI_JAMMING_PHASE1_2026_11_13__ A jammed radio is not offered at all
    // (mirrors TransportOrderPolicyTest.kt)

    func testAJammedRadioIsLeftOutAndTheRelayCarriesTheMessage() {
        var c = online(radio: true, peerOnline: false)
        c.radioJammed = true
        XCTAssertEqual(TransportPolicy.order(c), [.internetRelay])
        XCTAssertFalse(TransportPolicy.radioComesBeforeRelay(c))
    }

    func testAJammedRadioOffGridLeavesTheMeshOrNothing() {
        var offGrid = TransportPolicy.Conditions(physicalPath: false, peerOnMesh: true, radioReady: true,
                                                 peerLikelyOnline: false, carriesMedia: false, radioJammed: true)
        XCTAssertEqual(TransportPolicy.order(offGrid), [.bluetoothMesh])
        // Nothing else reachable: an empty list, the message waits (the radio keeps it for later).
        offGrid.peerOnMesh = false
        XCTAssertEqual(TransportPolicy.order(offGrid), [])
    }

    func testJammingNeverReordersAnythingElse() {
        for prefer in [true, false] {
            TransportPolicy.preferRadioOverRelay = prefer
            for path in [true, false] { for mesh in [true, false] { for peerOnline in [true, false] {
                for media in [true, false] {
                    var c = TransportPolicy.Conditions(physicalPath: path, peerOnMesh: mesh, radioReady: true,
                                                       peerLikelyOnline: peerOnline, carriesMedia: media)
                    let free = TransportPolicy.order(c)
                    c.radioJammed = true
                    let jammed = TransportPolicy.order(c)
                    XCTAssertEqual(jammed, free.filter { $0 != .loRa }, "\(c)")
                    XCTAssertFalse(jammed.contains(.loRa))
                }
            } } }
        }
    }

    /// Default is "not jammed", so every existing caller keeps its behaviour.
    func testRadioJammedDefaultsToFalse() {
        XCTAssertFalse(online(radio: true).radioJammed)
    }
}

// MARK: - Who is watching the radio

/// __BROADCAST_SILENCE_IS_ALSO_A_FAILURE_2026_10_27__
///
/// The 18 tests above cover where a message is ALLOWED to go. They all passed
/// while messages to most contacts went nowhere at all, because none of them
/// could see whether anything was watching the radio after the frames left.
///
/// `deliverViaLoRa` unicasts when `LoRaNodeIdentityMap` has a binding for the
/// recipient and broadcasts when it does not — and on a fresh install that map
/// is empty, so broadcast is the path for EVERY contact. The expiry window was
/// armed under `if wantAck && …`, and `wantAck` is false for a broadcast
/// because Meshtastic does not acknowledge broadcasts. Net effect: no pending
/// entry, no expiry, no `.loRaDeliveryFailed`, no escalation — while
/// `deliverViaLoRa` still returned true, marking the message sent and dropping
/// it from the retry queue.
///
/// These tests pin the rule that closes that hole: the window is armed by the
/// LAST FRAME, never by the send mode.
final class RadioDeliveryWindowTests: XCTestCase {

    private func pending(_ at: Date, unicast: Bool = true,
                         id: String = "m") -> MeshtasticManager.PendingRadioSend {
        MeshtasticManager.PendingRadioSend(id: id, at: at, unicast: unicast)
    }

    // MARK: Arming — the regression itself

    /// THE regression test. Before the fix this was false, and that single
    /// `false` is the whole bug: nothing watched, so nothing escalated.
    func testABroadcastArmsTheWindowJustLikeAUnicast() {
        XCTAssertTrue(MeshtasticManager.armsDeliveryWindow(isLastChunk: true, unicast: false))
        XCTAssertTrue(MeshtasticManager.armsDeliveryWindow(isLastChunk: true, unicast: true))
    }

    /// The send mode must not enter into it at all — stated as an equality so
    /// that reintroducing the dependency fails here rather than in the field.
    func testTheSendModeNeverChangesWhetherWeWatch() {
        for lastChunk in [true, false] {
            XCTAssertEqual(
                MeshtasticManager.armsDeliveryWindow(isLastChunk: lastChunk, unicast: true),
                MeshtasticManager.armsDeliveryWindow(isLastChunk: lastChunk, unicast: false),
                "unicast must not decide whether the delivery window is armed")
        }
    }

    /// Only the final frame arms it: the mesh answers per packet, so arming on
    /// an earlier chunk would resolve a multi-frame message before it had
    /// finished leaving.
    func testOnlyTheLastFrameArmsTheWindow() {
        XCTAssertFalse(MeshtasticManager.armsDeliveryWindow(isLastChunk: false, unicast: true))
        XCTAssertFalse(MeshtasticManager.armsDeliveryWindow(isLastChunk: false, unicast: false))
    }

    // MARK: Expiry

    func testNothingExpiresBeforeTheWindowCloses() {
        let now = Date()
        let map: [UInt32: MeshtasticManager.PendingRadioSend] = [
            1: pending(now.addingTimeInterval(-89), unicast: false),
            2: pending(now.addingTimeInterval(-1), unicast: true),
        ]
        XCTAssertEqual(MeshtasticManager.expiredPacketIds(map, now: now, window: 90), [])
    }

    /// A broadcast is the case that can ONLY ever end in silence, so it must be
    /// reported when the window closes — not quietly forgotten.
    func testAnUnconfirmedBroadcastExpires() {
        let now = Date()
        let map: [UInt32: MeshtasticManager.PendingRadioSend] = [
            7: pending(now.addingTimeInterval(-91), unicast: false)
        ]
        XCTAssertEqual(MeshtasticManager.expiredPacketIds(map, now: now, window: 90), [7])
    }

    func testBothKindsOfSilenceExpireTogether() {
        let now = Date()
        let map: [UInt32: MeshtasticManager.PendingRadioSend] = [
            3: pending(now.addingTimeInterval(-120), unicast: true),
            9: pending(now.addingTimeInterval(-120), unicast: false),
            4: pending(now, unicast: false),            // still fresh
        ]
        XCTAssertEqual(MeshtasticManager.expiredPacketIds(map, now: now, window: 90), [3, 9])
    }

    /// The boundary is inclusive, matching the `<=` in the sweep: a send that
    /// has used exactly its whole window has used it up.
    func testTheWindowBoundaryIsInclusive() {
        let now = Date()
        let map: [UInt32: MeshtasticManager.PendingRadioSend] = [
            5: pending(now.addingTimeInterval(-90), unicast: false)
        ]
        XCTAssertEqual(MeshtasticManager.expiredPacketIds(map, now: now, window: 90), [5])
    }

    /// An acknowledged packet is removed from the map by `handleRouting`, so it
    /// can never also be reported as a failure — the two outcomes are exclusive.
    func testAnAcknowledgedPacketCannotAlsoExpire() {
        let now = Date()
        var map: [UInt32: MeshtasticManager.PendingRadioSend] = [
            6: pending(now.addingTimeInterval(-120), unicast: true)
        ]
        map.removeValue(forKey: 6)          // what handleRouting does on an ACK
        XCTAssertEqual(MeshtasticManager.expiredPacketIds(map, now: now, window: 90), [])
    }

    func testAnEmptyMapExpiresNothing() {
        XCTAssertEqual(MeshtasticManager.expiredPacketIds([:], now: Date(), window: 90), [])
    }

    /// The default window is the documented 90 s, so a caller that omits it
    /// gets the same rule the sweep timer applies.
    func testTheDefaultWindowIsTheDocumentedNinetySeconds() {
        XCTAssertEqual(MeshtasticManager.loraAckWindow, 90)
        let now = Date()
        let map: [UInt32: MeshtasticManager.PendingRadioSend] = [
            8: pending(now.addingTimeInterval(-91), unicast: false)
        ]
        XCTAssertEqual(MeshtasticManager.expiredPacketIds(map, now: now), [8])
    }

    // MARK: The other half — expiry has somewhere to go

    /// Closing the loop with the policy half: an expired broadcast is exactly
    /// the case `internetPicksUpAfterRadio` must say yes to, and a message a
    /// receipt has already lifted to delivered is the case it must refuse — so
    /// arming the window for broadcasts cannot produce a double send.
    func testAnExpiredBroadcastEscalatesUnlessAReceiptBeatItThere() {
        XCTAssertTrue(TransportPolicy.internetPicksUpAfterRadio(
            hasPhysicalPath: true, internetAllowedHere: true, alreadyDelivered: false))
        XCTAssertFalse(TransportPolicy.internetPicksUpAfterRadio(
            hasPhysicalPath: true, internetAllowedHere: true, alreadyDelivered: true))
    }
}

