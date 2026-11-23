//
//  LoRaNodeIdentityMapTests.swift
//  OSHITests
//
//  A raw Meshtastic text packet carries a node number and a string — no key, no
//  identity, nothing. Whether it lands in a contact's conversation or in a
//  "lora!<nodehex>" one is therefore decided entirely by the learned map these
//  tests cover, which makes this the whole of the routing logic.
//
//  Two failure modes are being guarded, and they are not symmetrical:
//   • Routing to "lora!" when the identity is known is a nuisance.
//   • Routing to a contact when the identity is NOT known is a message shown
//     under the wrong person's name in an encrypted messenger.
//  So every ambiguous case below asserts the FALLBACK, never a best guess.
//

import XCTest
@testable import OSHI

final class LoRaNodeIdentityMapTests: XCTestCase {

    /// A real-shaped OSHI identity key (base64 Curve25519, 44 chars).
    private let alice = "eAbMQUdW6UtufTtaHqDJtkC24fKa3c1nhaGN/muFE3A="
    private let bob   = "Qm9iS2V5QWJzb2x1dGVseURpZmZlcmVudEZyb21BbGljZQ=="

    private var suiteName: String!
    private var defaults: UserDefaults!
    private var map: LoRaNodeIdentityMap!

    override func setUpWithError() throws {
        suiteName = "test.lora.identity.\(UUID().uuidString)"
        defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        map = LoRaNodeIdentityMap(defaults: defaults)
    }

    override func tearDownWithError() throws {
        defaults.removePersistentDomain(forName: suiteName)
        map = nil; defaults = nil; suiteName = nil
    }

    // MARK: The default: unknown node → the conversation it has always used

    /// THE regression guard for "do not invent an attribution".
    func testUnknownNodeHasNoIdentity() {
        XCTAssertNil(map.publicKey(forNode: 0x1a2b3c4d))
        XCTAssertNil(map.binding(forNode: 0x1a2b3c4d))
    }

    // MARK: The routing decision `handleInteropText` actually makes

    /// The whole point, stated as the function the radio path calls: a raw
    /// Meshtastic text is filed under the proved identity, or under the
    /// historical "lora!" key when there is none.
    func testConversationKeyIsTheIdentityWhenProvedAndTheFallbackOtherwise() {
        // Raw-text adoption is OFF by default and this test is about the routing
        // RULE, not the switch. See `testRawTextIsNotAdoptedWhileTheSwitchIsOff`
        // for the shipped default.
        let previous = LoRaNodeIdentityMap.adoptRawTextIntoContactThread
        LoRaNodeIdentityMap.adoptRawTextIntoContactThread = true
        defer { LoRaNodeIdentityMap.adoptRawTextIntoContactThread = previous }

        XCTAssertEqual(map.conversationKey(forNode: 0x1a2b3c4d), "lora!1a2b3c4d",
                       "an unproved node must not be attributed to anyone")

        map.learn(node: 0x1a2b3c4d, publicKey: alice)
        XCTAssertEqual(map.conversationKey(forNode: 0x1a2b3c4d), alice)

        // A second identity on the same radio → straight back to the fallback.
        map.learn(node: 0x1a2b3c4d, publicKey: bob)
        XCTAssertEqual(map.conversationKey(forNode: 0x1a2b3c4d), "lora!1a2b3c4d")

        // A neighbouring node number is unaffected either way.
        XCTAssertEqual(map.conversationKey(forNode: 0x1a2b3c4e), "lora!1a2b3c4e")
    }

    /// Existing users: a node that has never sent an OSHI message keeps landing
    /// in exactly the conversation it landed in before this change existed.
    func testExistingLoraConversationsKeepReceiving() {
        for node: UInt32 in [0, 1, 0xff, 0x1a2b3c4d, UInt32.max] {
            XCTAssertEqual(map.conversationKey(forNode: node),
                           LoRaNodeIdentityMap.fallbackConversationKey(node))
        }
    }

    func testFallbackKeyIsTheHistoricalLoraFormat() {
        XCTAssertEqual(LoRaNodeIdentityMap.fallbackConversationKey(0x1a2b3c4d), "lora!1a2b3c4d")
        // Low node numbers must stay zero-padded to 8 hex digits, or an old
        // conversation created before this change stops being addressed.
        XCTAssertEqual(LoRaNodeIdentityMap.fallbackConversationKey(0xff), "lora!000000ff")
        XCTAssertEqual(LoRaNodeIdentityMap.fallbackConversationKey(UInt32.max), "lora!ffffffff")
        XCTAssertTrue(LoRaNodeIdentityMap.isFallbackConversationKey("lora!1a2b3c4d"))
        XCTAssertFalse(LoRaNodeIdentityMap.isFallbackConversationKey(alice))
    }

    // MARK: Learning

    func testLearningBindsTheNode() {
        XCTAssertEqual(map.learn(node: 0x1a2b3c4d, publicKey: alice), .bound)
        XCTAssertEqual(map.publicKey(forNode: 0x1a2b3c4d), alice)
        XCTAssertEqual(map.node(forPublicKey: alice), 0x1a2b3c4d)
    }

    /// The key must come back byte-for-byte, because it is compared against
    /// contact and conversation keys elsewhere with `==`.
    func testStoredKeyIsNotRewritten() {
        map.learn(node: 7, publicKey: alice)
        XCTAssertEqual(map.publicKey(forNode: 7), alice, "key was normalized on the way in")
    }

    func testRelearningTheSameIdentityIsANoOp() {
        XCTAssertEqual(map.learn(node: 7, publicKey: alice), .bound)
        XCTAssertEqual(map.learn(node: 7, publicKey: alice), .unchanged)
        XCTAssertEqual(map.publicKey(forNode: 7), alice)
    }

    /// base64url vs base64, padded vs not — the same key spelled differently
    /// must not read as a second identity and lock the node out of routing.
    func testBase64URLVariantOfTheSameKeyIsNotAConflict() {
        map.learn(node: 7, publicKey: alice)
        let variant = alice
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        XCTAssertNotEqual(variant, alice, "test key must actually contain +/ or = to be meaningful")
        XCTAssertEqual(map.learn(node: 7, publicKey: variant), .unchanged)
        XCTAssertEqual(map.publicKey(forNode: 7), alice)
    }

    func testSurroundingWhitespaceIsTrimmed() {
        XCTAssertEqual(map.learn(node: 7, publicKey: "  \(alice)\n"), .bound)
        XCTAssertEqual(map.publicKey(forNode: 7), alice)
    }

    // MARK: Rejections — things that must never become an identity

    func testSyntheticLoraKeyIsRejected() {
        XCTAssertEqual(map.learn(node: 7, publicKey: "lora!1a2b3c4d"), .rejected)
        XCTAssertNil(map.publicKey(forNode: 7))
    }

    func testEmptyAndShortKeysAreRejected() {
        XCTAssertEqual(map.learn(node: 7, publicKey: ""), .rejected)
        XCTAssertEqual(map.learn(node: 7, publicKey: "   "), .rejected)
        XCTAssertEqual(map.learn(node: 7, publicKey: "tooshort"), .rejected)
        XCTAssertNil(map.publicKey(forNode: 7))
        XCTAssertEqual(map.count, 0)
    }

    // MARK: Conflict — two identities on one radio

    /// One radio, two people (or a rotated key). There is no correct answer, so
    /// the node stops routing and raw text goes back to "lora!". Silence beats a
    /// coin flip when the coin decides whose conversation a message enters.
    func testSecondIdentityMakesTheNodeAmbiguousAndStopsRouting() {
        XCTAssertEqual(map.learn(node: 7, publicKey: alice), .bound)
        XCTAssertEqual(map.learn(node: 7, publicKey: bob), .conflict)

        XCTAssertNil(map.publicKey(forNode: 7), "an ambiguous node must not route")
        XCTAssertEqual(map.binding(forNode: 7)?.ambiguous, true)
        XCTAssertNil(map.node(forPublicKey: alice))
        XCTAssertNil(map.node(forPublicKey: bob))
    }

    /// Further evidence cannot resolve the ambiguity — including evidence that
    /// agrees with the original binding, which would otherwise let an attacker
    /// un-stick a node by replaying one message.
    func testAmbiguityIsSticky() {
        map.learn(node: 7, publicKey: alice)
        map.learn(node: 7, publicKey: bob)
        XCTAssertEqual(map.learn(node: 7, publicKey: alice), .ignoredAmbiguous)
        XCTAssertNil(map.publicKey(forNode: 7))
    }

    func testForgetClearsAmbiguityAndAllowsRebinding() {
        map.learn(node: 7, publicKey: alice)
        map.learn(node: 7, publicKey: bob)
        map.forget(node: 7)
        XCTAssertNil(map.binding(forNode: 7))
        XCTAssertEqual(map.learn(node: 7, publicKey: bob), .bound)
        XCTAssertEqual(map.publicKey(forNode: 7), bob)
    }

    /// This test used to assert the OPPOSITE — that one identity may be bound to
    /// several nodes, on the reasoning "same person, two radios, nothing
    /// ambiguous from the receiving side". That reasoning was wrong, and it was
    /// the vulnerability: from the receiving side there is no way to tell a
    /// contact's second radio from an ATTACKER's radio claiming that contact's
    /// public key, and public keys are shared openly by QR. The old rule made the
    /// attacker's node a second valid route into the contact's conversation.
    ///
    /// Both nodes go ambiguous. Which one is the impostor is not knowable here,
    /// and choosing would be a coin flip on whose messages get attributed to a
    /// real person. Falling back to their own `lora!` threads is exactly the
    /// behaviour that shipped before any of this existed.
    func testASecondNodeClaimingABoundIdentityMakesBothAmbiguous() {
        XCTAssertEqual(map.learn(node: 1, publicKey: alice), .bound)
        XCTAssertEqual(map.learn(node: 2, publicKey: alice), .conflict)

        XCTAssertNil(map.publicKey(forNode: 1), "the genuine node is collateral, and that is the safe direction")
        XCTAssertNil(map.publicKey(forNode: 2))
        XCTAssertEqual(map.binding(forNode: 1)?.ambiguous, true)
        XCTAssertEqual(map.binding(forNode: 2)?.ambiguous, true)

        // Recoverable: the user can say "that isn't them" about the impostor and
        // re-teach the real radio.
        map.forget(node: 2)
        map.forget(node: 1)
        XCTAssertEqual(map.learn(node: 1, publicKey: alice), .bound)
        XCTAssertEqual(map.publicKey(forNode: 1), alice)
    }

    /// The shipped default. Raw Meshtastic text carries an UNAUTHENTICATED `from`
    /// field on a channel whose key is public, so even a perfectly sound binding
    /// cannot make that packet trustworthy — anyone in range can claim a bound
    /// node number. Until interop bubbles say "unverified plaintext" on screen,
    /// raw text stays in its own thread.
    ///
    /// The shipped default flipped to ON in a52531c8 (`.loraPlaintext`, amber
    /// "LoRa · unverified" badge), so this no longer asserts the default — it
    /// pins what the switch still guarantees when a user turns it OFF.
    func testRawTextIsNotAdoptedWhileTheSwitchIsOff() {
        let saved = LoRaNodeIdentityMap.adoptRawTextIntoContactThread
        LoRaNodeIdentityMap.adoptRawTextIntoContactThread = false
        defer { LoRaNodeIdentityMap.adoptRawTextIntoContactThread = saved }

        map.learn(node: 0x1a2b3c4d, publicKey: alice)
        XCTAssertEqual(map.publicKey(forNode: 0x1a2b3c4d), alice,
                       "the binding is still learned — only the routing is gated")
        XCTAssertEqual(map.conversationKey(forNode: 0x1a2b3c4d), "lora!1a2b3c4d",
                       "a contact's encrypted thread must not receive unauthenticated plaintext")
    }

    // MARK: Persistence

    /// The point of the whole map: it has to survive a relaunch, because the
    /// raw text that needs it will arrive days after the OSHI message that
    /// taught it.
    func testBindingsSurviveARelaunch() {
        map.learn(node: 0x1a2b3c4d, publicKey: alice)

        let reloaded = LoRaNodeIdentityMap(defaults: defaults)
        XCTAssertEqual(reloaded.publicKey(forNode: 0x1a2b3c4d), alice)
    }

    func testAmbiguitySurvivesARelaunch() {
        map.learn(node: 7, publicKey: alice)
        map.learn(node: 7, publicKey: bob)

        let reloaded = LoRaNodeIdentityMap(defaults: defaults)
        XCTAssertNil(reloaded.publicKey(forNode: 7), "ambiguity was lost across launches")
        XCTAssertEqual(reloaded.binding(forNode: 7)?.ambiguous, true)
    }

    func testForgetSurvivesARelaunch() {
        map.learn(node: 7, publicKey: alice)
        map.forget(node: 7)
        XCTAssertNil(LoRaNodeIdentityMap(defaults: defaults).binding(forNode: 7))
    }

    /// ⚠️ The persisted key must NOT be derived from `hashValue`: Swift salts
    /// hashing per process, so a hash-keyed map addresses a different slot after
    /// every relaunch and the mapping quietly evaporates. This asserts the key
    /// is the fixed hex rendering — computable identically in any process.
    func testPersistedKeyIsProcessStableHexNotAHash() throws {
        XCTAssertEqual(LoRaNodeIdentityMap.nodeKey(0x1a2b3c4d), "1a2b3c4d")
        XCTAssertEqual(LoRaNodeIdentityMap.nodeKey(0), "00000000")
        XCTAssertEqual(LoRaNodeIdentityMap.nodeKey(UInt32.max), "ffffffff")

        // And that key is what actually reaches UserDefaults.
        map.learn(node: 0x1a2b3c4d, publicKey: alice)
        let raw = try XCTUnwrap(defaults.data(forKey: "oshi.lora.nodeIdentity.v1"))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: raw) as? [String: Any])
        XCTAssertNotNil(json["1a2b3c4d"], "binding is not stored under the stable hex key")
        XCTAssertEqual(json.count, 1)
    }

    // MARK: The wire type this is fed from

    /// The binding is made from `SecureMessage.senderPublicKey` after a LoRa
    /// envelope decrypts. That field goes through `meshWireSafe()` + JSON on the
    /// way, so bind from what actually comes off the wire, not from a literal.
    func testIdentityLearnedFromARoundTrippedEnvelope() throws {
        var m = SecureMessage(
            id: "lora-identity",
            senderAddress: "0xSENDER",
            recipientAddress: "0xRECIPIENT",
            encryptedContent: EncryptedMessage(
                ciphertext: "AAAA", signature: "c2ln",
                senderPublicKey: alice,
                timestamp: Date(timeIntervalSince1970: 1_700_000_000)),
            timestamp: Date(timeIntervalSince1970: 1_700_000_000),
            isRead: false,
            deliveryStatus: .sent)
        m.senderPublicKey = alice
        m.recipientPublicKey = bob
        m.plaintextContent = "meet at the mill"

        let wire = try JSONEncoder().encode(m.meshWireSafe())
        let decoded = try JSONDecoder().decode(SecureMessage.self, from: wire)

        XCTAssertEqual(map.learn(node: 0x00c0ffee, publicKey: decoded.senderPublicKey), .bound)
        XCTAssertEqual(map.publicKey(forNode: 0x00c0ffee), alice)
        // And a raw text from a DIFFERENT node still has nowhere to go but the
        // fallback conversation.
        XCTAssertNil(map.publicKey(forNode: 0x00c0fffe))
    }

    // MARK: - __LORA_INTEROP_REPLY_2026_10_28__ the node back out of the key

    /// Answering a `lora!` thread means transmitting to a node number, and the
    /// thread key is the only record of which node it was. A wrong answer here
    /// would send someone's reply to a DIFFERENT radio, so the parse is strict
    /// and these tests pin that.

    func testTheNodeNumberRoundTripsThroughTheConversationKey() {
        for num: UInt32 in [0x4b88a86d, 0x766eb396, 0x00000001, 0xffffffff] {
            let key = LoRaNodeIdentityMap.fallbackConversationKey(num)
            XCTAssertEqual(LoRaNodeIdentityMap.nodeNumber(fromFallbackKey: key), num,
                           "round trip failed for \(key)")
        }
    }

    func testAKeyThatIsNotAFullEightDigitNodeIsRefusedRatherThanGuessed() {
        // Short, long, non-hex, and the real conversation keys of actual
        // contacts — none of these may yield a transmit destination.
        let bad = [
            "lora!4b88a86",      // seven digits
            "lora!4b88a86d0",    // nine
            "lora!zzzzzzzz",     // not hex
            "lora!",             // empty
            "lora!        ",     // blank
            "4b88a86d",          // no prefix
            "",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",  // a real identity key
        ]
        for key in bad {
            XCTAssertNil(LoRaNodeIdentityMap.nodeNumber(fromFallbackKey: key),
                         "\(key) must not parse to a node")
        }
    }

    func testUppercaseHexIsAcceptedBecauseOnlyOurOwnSpellingIsLowercase() {
        // `fallbackConversationKey` writes lowercase, but a key that has been
        // through a round of JSON, a paste or an older build may not be. The
        // node number is the same number either way.
        XCTAssertEqual(LoRaNodeIdentityMap.nodeNumber(fromFallbackKey: "lora!4B88A86D"), 0x4b88a86d)
    }

    func testOnlyAFallbackKeyParses_aProvedContactThreadHasNoNodeToAnswer() {
        XCTAssertEqual(map.learn(node: 0x4b88a86d, publicKey: alice), .bound)
        // Once bound, the thread is keyed on Alice's key, and that thread is
        // NOT answered over the interop lane — it is end-to-end encrypted.
        XCTAssertFalse(LoRaNodeIdentityMap.isFallbackConversationKey(alice))
        XCTAssertNil(LoRaNodeIdentityMap.nodeNumber(fromFallbackKey: alice))
    }


    // MARK: - __LORA_MANUAL_LINK_2026_10_28__ a link the user made by hand

    /// A radio that only ever sends stock Meshtastic text can never PROVE an
    /// identity, so "one person, two conversations" is unfixable by evidence.
    /// `link` lets a person vouch instead. These tests pin what a person is and
    /// is not allowed to override.

    func testAManualLinkRoutesEvenWhileAdoptionIsOff() {
        let was = LoRaNodeIdentityMap.adoptRawTextIntoContactThread
        LoRaNodeIdentityMap.adoptRawTextIntoContactThread = false
        defer { LoRaNodeIdentityMap.adoptRawTextIntoContactThread = was }

        // Before: the fallback thread, which is the whole complaint.
        XCTAssertEqual(map.conversationKey(forNode: 0x766eb396), "lora!766eb396")
        XCTAssertEqual(map.link(node: 0x766eb396, toIdentity: alice), .bound)
        XCTAssertEqual(map.conversationKey(forNode: 0x766eb396), alice)
    }

    func testAManualLinkSurvivesAReloadFromDisk() throws {
        XCTAssertEqual(map.link(node: 0x766eb396, toIdentity: alice), .bound)
        let reloaded = LoRaNodeIdentityMap(defaults: defaults)
        XCTAssertEqual(reloaded.manualPublicKey(forNode: 0x766eb396), alice)
        XCTAssertTrue(try XCTUnwrap(reloaded.binding(forNode: 0x766eb396)).isManual)
    }

    func testAPersonMayNotOverwriteWhatTheRatchetProved() {
        XCTAssertEqual(map.learn(node: 0x766eb396, publicKey: alice), .bound)
        // Proof says Alice; the user claims Bob. Proof wins, and the claim is
        // reported as the conflict it is.
        XCTAssertEqual(map.link(node: 0x766eb396, toIdentity: bob), .conflict)
        XCTAssertEqual(map.publicKey(forNode: 0x766eb396), alice)
        // And the binding is still PROVED, not downgraded to asserted.
        XCTAssertEqual(map.binding(forNode: 0x766eb396)?.isManual, false)
    }

    func testAPersonMayCorrectTheirOwnLink() {
        XCTAssertEqual(map.link(node: 0x766eb396, toIdentity: alice), .bound)
        XCTAssertEqual(map.link(node: 0x766eb396, toIdentity: bob), .bound)
        XCTAssertEqual(map.manualPublicKey(forNode: 0x766eb396), bob)
        XCTAssertEqual(map.link(node: 0x766eb396, toIdentity: bob), .unchanged)
    }

    func testAnAmbiguousNodeCannotBeRescuedByVouchingForIt() {
        XCTAssertEqual(map.learn(node: 0x766eb396, publicKey: alice), .bound)
        XCTAssertEqual(map.learn(node: 0x766eb396, publicKey: bob), .conflict)
        // Two identities proved themselves from one node. A person saying "it is
        // really Alice" does not resolve that — the evidence is contradictory
        // and the thread stays on the fallback key.
        XCTAssertEqual(map.link(node: 0x766eb396, toIdentity: alice), .ignoredAmbiguous)
        XCTAssertNil(map.manualPublicKey(forNode: 0x766eb396))
        XCTAssertEqual(map.conversationKey(forNode: 0x766eb396), "lora!766eb396")
    }

    func testASyntheticOrTruncatedKeyIsStillRefusedFromAPerson() {
        for bad in ["lora!766eb396", "", "short", "   "] {
            XCTAssertEqual(map.link(node: 0x766eb396, toIdentity: bad), .rejected,
                           "\(bad) must not become a binding")
        }
        XCTAssertEqual(map.conversationKey(forNode: 0x766eb396), "lora!766eb396")
    }

    func testUnlinkReturnsTheThreadAndNeverDiscardsProof() {
        XCTAssertEqual(map.link(node: 0x766eb396, toIdentity: alice), .bound)
        XCTAssertTrue(map.unlink(node: 0x766eb396))
        XCTAssertEqual(map.conversationKey(forNode: 0x766eb396), "lora!766eb396")
        XCTAssertFalse(map.unlink(node: 0x766eb396))   // nothing left to undo

        // A PROVED binding is evidence and unlink must not touch it.
        XCTAssertEqual(map.learn(node: 0x4b88a86d, publicKey: alice), .bound)
        XCTAssertFalse(map.unlink(node: 0x4b88a86d))
        XCTAssertEqual(map.publicKey(forNode: 0x4b88a86d), alice)
    }

    func testAMapWrittenBeforeTodayDecodesAsProvedNotAsserted() throws {
        // MIGRATION: `manual` is absent from every binding on disk today, and
        // absent must mean proved — reading them as asserted would relabel the
        // whole existing map as something a person had vouched for.
        let legacy = #"{"766eb396":{"publicKey":"\#(alice)","learnedAt":0,"ambiguous":false}}"#
        defaults.set(Data(legacy.utf8), forKey: "oshi.lora.nodeIdentity.v1")
        let reloaded = LoRaNodeIdentityMap(defaults: defaults)
        let b = try XCTUnwrap(reloaded.binding(forNode: 0x766eb396))
        XCTAssertFalse(b.isManual)
        XCTAssertNil(reloaded.manualPublicKey(forNode: 0x766eb396))
    }

}
