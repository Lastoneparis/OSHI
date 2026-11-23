//
//  TransportRatchetSyncTests.swift
//  OSHITests
//
//  DOES THE RATCHET STAY IN SYNC OVER MESH, LoRa AND THE INTERNET?
//
//  The ratchet is transport-agnostic by design — the same `DoubleRatchetMessage`
//  bytes ride MultipeerConnectivity, a LoRa radio, or IPFS. So the question is
//  not "does each transport have its own crypto" (it does not); it is whether the
//  ratchet survives what each transport DOES to a stream of messages:
//
//    * internet / IPFS — mostly ordered, can duplicate on retry
//    * mesh            — floods, so duplicates and reordering are normal
//    * LoRa            — lossy, slow, reorders, and above all TINY: 180 bytes a
//                        frame, 12 frames a message, so 2160 bytes total
//
//  The size limit is the one that bites silently. A message that exceeds it is
//  refused by `MeshtasticManager` before it is ever sent, so the failure is not a
//  crypto error — it is a group whose messages simply never leave the radio. The
//  first tests here are therefore measurements against the real constants, not
//  behaviour assertions.
//
//  Nothing here touches production: no server, no radio, no relay. Two real
//  ratchet sessions in memory, and the transports are modelled by what they do to
//  the ORDER and MULTIPLICITY of messages, which is the only thing the ratchet
//  can actually observe.
//

import XCTest
import CryptoKit
@testable import OSHI

final class TransportRatchetSyncTests: XCTestCase {

    private let sharedSecret = Data(repeating: 0x6D, count: 32)
    private let alice = "ALICEtransportALICEtransportALICEtrans1a="
    private let bob   = "BOBtransportBOBtransportBOBtransportbo2b="

    /// The real budget a message must fit to travel by radio:
    /// `MeshtasticManager.loRaChunk (180) * 12`.
    private let loRaMessageCeiling = 180 * 12

    /// Two genuine peers — each names the other, so roles and chains line up the
    /// way two devices' would.
    private func pair() -> (a: DoubleRatchetSession, b: DoubleRatchetSession) {
        let m1 = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); m1.resetAllSessions()
        let m2 = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); m2.resetAllSessions()
        return (m1.getOrCreateSession(with: bob, ourPublicKey: alice, sharedSecret: sharedSecret),
                m2.getOrCreateSession(with: alice, ourPublicKey: bob, sharedSecret: sharedSecret))
    }

    private func wire(_ m: DoubleRatchetMessage) throws -> Data {
        try JSONEncoder().encode(m)
    }


    // MARK: - Will it even fit on the radio?

    /// A 1:1 message must fit in one LoRa message, or direct messaging over the
    /// radio is dead. This is the baseline the group format is judged against.
    func testAOneToOneRatchetMessageFitsOnLoRa() throws {
        let (a, _) = pair()
        let bytes = try wire(a.encrypt("Rendez-vous à 18h près du pont."))
        XCTAssertLessThan(bytes.count, loRaMessageCeiling,
                          "un message 1:1 ne tient plus dans 12×180 octets: la radio le refusera avant tout chiffrement")
        // Recorded so a regression shows up as a number, not a guess.
        print("ℹ️ 1:1 ratchet message on the wire: \(bytes.count) bytes (LoRa ceiling \(loRaMessageCeiling))")
    }

    /// THE size question for CV-002: a v3 group envelope carries one wrap per
    /// member, so it grows with the group. This measures where it stops fitting
    /// on a radio — a number the group code must respect rather than discover in
    /// the field.
    func testGroupV3EnvelopeSizeAgainstTheLoRaCeiling() throws {
        let payload = Data("Un message de groupe de longueur ordinaire.".utf8)

        var lastFitting = 0
        for memberCount in [1, 2, 3, 5, 8, 12, 20] {
            var sessions: [String: DoubleRatchetSession] = [:]
            var members: [String] = []
            for i in 0..<memberCount {
                let member = "MEMBER\(i)aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa="
                members.append(member)
                let m1 = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); m1.resetAllSessions()
                sessions[member] = m1.getOrCreateSession(with: member, ourPublicKey: alice,
                                                         sharedSecret: sharedSecret)
            }
            let sealed = try GroupEnvelopeV3.seal(payload: payload, members: members) { member, key in
                try JSONEncoder().encode(try sessions[member]!.encrypt(key))
            }
            let bytes = try JSONEncoder().encode(sealed).count
            let fits = bytes < loRaMessageCeiling
            print("ℹ️ group v3, \(memberCount) member(s): \(bytes) bytes — \(fits ? "fits LoRa" : "TOO BIG for LoRa")")
            if fits { lastFitting = memberCount }
        }

        // Not an arbitrary threshold: the point is to record the real boundary so
        // the group code can refuse the radio above it instead of failing mutely.
        XCTAssertGreaterThanOrEqual(lastFitting, 1,
                                    "même un groupe à UN membre ne tient pas sur LoRa: le format v3 est inutilisable par radio")
        print("ℹ️ largest group that still fits one LoRa message: \(lastFitting) member(s)")
    }

    // MARK: - Internet: ordered, occasionally duplicated

    func testOrderedDeliveryKeepsBothSidesInSync() throws {
        let (a, b) = pair()
        for i in 0..<25 {
            let m = try a.encrypt("message \(i)")
            XCTAssertEqual(try b.decrypt(m), "message \(i)")
        }
    }

    /// An IPFS retry, or a mesh flood, delivers the same bytes twice. The second
    /// copy must be REJECTED, not decrypted again — a ratchet that accepted a
    /// replay would let an observer re-inject old traffic.
    func testADuplicateIsRejectedAndDoesNotCorruptTheSession() throws {
        let (a, b) = pair()
        let first = try a.encrypt("une seule fois")
        XCTAssertEqual(try b.decrypt(first), "une seule fois")

        XCTAssertThrowsError(try b.decrypt(first),
                             "un rejeu a été accepté une seconde fois")

        // And the session must still be usable afterwards — a rejected replay
        // that poisoned the state would be just as bad as accepting it.
        let next = try a.encrypt("et la suite")
        XCTAssertEqual(try b.decrypt(next), "et la suite")
    }

    // MARK: - Mesh: reordering and flooding

    func testHeavilyReorderedDeliveryStillDecryptsEverything() throws {
        let (a, b) = pair()
        var sent: [(Int, DoubleRatchetMessage)] = []
        for i in 0..<12 { sent.append((i, try a.encrypt("m\(i)"))) }

        // Deterministic shuffle so a failure is reproducible.
        let order = [7, 0, 11, 3, 1, 9, 2, 10, 4, 8, 5, 6]
        var seen = Set<Int>()
        for idx in order {
            let (i, msg) = sent[idx]
            XCTAssertEqual(try b.decrypt(msg), "m\(i)", "message \(i) perdu après réordonnancement")
            seen.insert(i)
        }
        XCTAssertEqual(seen.count, 12)
    }

    func testFloodedDuplicatesInterleavedWithNewMessages() throws {
        let (a, b) = pair()
        let m0 = try a.encrypt("zéro")
        let m1 = try a.encrypt("un")
        let m2 = try a.encrypt("deux")

        XCTAssertEqual(try b.decrypt(m0), "zéro")
        XCTAssertThrowsError(try b.decrypt(m0))          // flood echo
        XCTAssertEqual(try b.decrypt(m1), "un")
        XCTAssertThrowsError(try b.decrypt(m1))          // flood echo
        XCTAssertEqual(try b.decrypt(m2), "deux")
    }

    // MARK: - LoRa: lossy

    /// Messages that never arrive must not stall the ones that do. This is the
    /// skipped-key machinery, exercised the way a radio actually loses frames.
    func testLostMessagesDoNotStallTheChain() throws {
        let (a, b) = pair()
        var msgs: [DoubleRatchetMessage] = []
        for i in 0..<10 { msgs.append(try a.encrypt("l\(i)")) }

        // 0, 3, 4 and 7 never arrive.
        for i in [1, 2, 5, 6, 8, 9] {
            XCTAssertEqual(try b.decrypt(msgs[i]), "l\(i)", "message \(i) illisible après pertes")
        }
        // And a straggler that turns up very late is still readable.
        XCTAssertEqual(try b.decrypt(msgs[0]), "l0", "le retardataire a été perdu définitivement")
    }

    // MARK: - Both directions, which is where the DH ratchet actually turns

    /// One-way traffic never rotates the DH ratchet. A real conversation does,
    /// and that is the step most likely to desynchronise — so alternate, and
    /// alternate across a simulated transport switch.
    func testAlternatingConversationKeepsRootKeysAgreeing() throws {
        let (a, b) = pair()
        for round in 0..<10 {
            let up = try a.encrypt("A→B \(round)")
            XCTAssertEqual(try b.decrypt(up), "A→B \(round)")
            let down = try b.encrypt("B→A \(round)")
            XCTAssertEqual(try a.decrypt(down), "B→A \(round)")
        }
        XCTAssertEqual(a.rootKey, b.rootKey,
                       "les deux côtés ont divergé après 10 allers-retours")
    }

    /// The transport can change mid-conversation — internet, then radio, then
    /// mesh. The ratchet must not notice. Modelled as what actually differs:
    /// each leg reorders differently and one drops.
    func testASessionSurvivesSwitchingTransportsMidConversation() throws {
        let (a, b) = pair()

        // Leg 1 — internet: in order.
        for i in 0..<3 {
            XCTAssertEqual(try b.decrypt(try a.encrypt("net\(i)")), "net\(i)")
        }
        // Reply, so the DH ratchet turns between legs.
        XCTAssertEqual(try a.decrypt(try b.encrypt("ack")), "ack")

        // Leg 2 — LoRa: one lost, the rest late and out of order.
        let radio = try (0..<4).map { try a.encrypt("lora\($0)") }
        XCTAssertEqual(try b.decrypt(radio[3]), "lora3")
        XCTAssertEqual(try b.decrypt(radio[1]), "lora1")
        // radio[2] never arrives.
        XCTAssertEqual(try b.decrypt(radio[0]), "lora0")

        // Leg 3 — mesh: duplicated flood, then normal traffic.
        let mesh = try a.encrypt("mesh0")
        XCTAssertEqual(try b.decrypt(mesh), "mesh0")
        XCTAssertThrowsError(try b.decrypt(mesh))

        // Still a working conversation in both directions.
        XCTAssertEqual(try a.decrypt(try b.encrypt("toujours là")), "toujours là")
        XCTAssertEqual(try b.decrypt(try a.encrypt("moi aussi")), "moi aussi")
    }

    // MARK: - Resynchronisation

    /// A reinstall, a wiped session, a heal — on a path with no server to
    /// re-fetch a bundle from.
    ///
    /// MEASURED, not assumed: a rebuilt session CAN pick the stream back up
    /// mid-chain. An earlier version of this test asserted the opposite and was
    /// wrong. That is not a defect — it is the property that makes offline
    /// resynchronisation possible at all here, because the initial chain is
    /// `HKDF(static X25519 secret)` and both sides can always recompute it.
    ///
    /// Its cost is stated in the next test.
    func testALostSessionRebuildsAndPicksTheStreamBackUp() throws {
        let (a, b) = pair()
        XCTAssertEqual(try b.decrypt(try a.encrypt("avant la casse")), "avant la casse")

        // B loses everything and rebuilds from the static identity secret alone.
        let m = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); m.resetAllSessions()
        let bFresh = m.getOrCreateSession(with: alice, ourPublicKey: bob, sharedSecret: sharedSecret)

        // It catches up with the sender still mid-chain — no heal round trip.
        XCTAssertEqual(try bFresh.decrypt(try a.encrypt("après la casse")), "après la casse")
        XCTAssertEqual(try bFresh.decrypt(try a.encrypt("et la suite")), "et la suite")

        // And the reply direction works, which is what closes the resync.
        XCTAssertEqual(try a.decrypt(try bFresh.encrypt("bien reçu")), "bien reçu")
    }

    /// MEASURED — and the answer is worse than expected, so this test records it
    /// rather than asserting a property the code does not have.
    ///
    /// `performDHRatchet` only runs when the peer's ratchet public key CHANGES
    /// (`_decrypt`), and a side's own ratchet key pair is only replaced INSIDE
    /// `performDHRatchet`. On the first message from a peer the code takes the
    /// "store their key, no DH ratchet" branch. So neither side ever rotates,
    /// neither side ever observes a change, and the DH ratchet never turns.
    ///
    /// Consequence: on this path the whole stream stays derivable from the
    /// long-term X25519 identity secret. Anyone who obtains it can rebuild every
    /// message — there is no forward secrecy to lose, because none was reached.
    ///
    /// This is exactly what makes the offline resync in the previous test work,
    /// so the two are the same coin. Recorded here so the number is not a
    /// surprise, and so the day the ratchet is fixed this test FAILS and has to
    /// be updated deliberately.
    func testTheDHRatchetNeverTurnsSoTheChainStaysDerivableFromIdentityKeys() throws {
        let (a, b) = pair()
        let initialRoot = a.rootKey

        for round in 0..<10 {
            XCTAssertEqual(try b.decrypt(try a.encrypt("A\(round)")), "A\(round)")
            XCTAssertEqual(try a.decrypt(try b.encrypt("B\(round)")), "B\(round)")
        }

        // If a DH ratchet had turned even once, the root key would have moved.
        XCTAssertEqual(a.rootKey, initialRoot,
                       "la racine a bougé — le ratchet DH tourne maintenant, ce test doit être mis à jour")
        XCTAssertEqual(b.rootKey, initialRoot)

        // And the practical consequence: a session rebuilt from the static
        // identity secret alone still reads brand-new traffic.
        let later = try a.encrypt("dix allers-retours plus tard")
        let m = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); m.resetAllSessions()
        let rebuilt = m.getOrCreateSession(with: alice, ourPublicKey: bob, sharedSecret: sharedSecret)
        XCTAssertNoThrow(try rebuilt.decrypt(later),
                         "si ceci échoue, la forward secrecy existe et la note d'audit doit être corrigée")
    }

    /// Both sides rebuilding from the same static secret must land on the same
    /// root key — this is what makes recovery possible at all on the mesh path,
    /// where there is no server to re-fetch a bundle from.
    func testBothSidesRebuildTheSameRootFromStaticIdentities() {
        let (a1, b1) = pair()
        let (a2, b2) = pair()
        XCTAssertEqual(a1.rootKey, a2.rootKey)
        XCTAssertEqual(b1.rootKey, b2.rootKey)
        XCTAssertEqual(a1.rootKey, b1.rootKey,
                       "les deux côtés ne dérivent pas la même racine: aucune resynchro hors-ligne n'est possible")
    }

    // MARK: - Group wraps ride the same ratchet

    /// CV-002's wraps go through the 1:1 ratchet, so group traffic now advances
    /// the SAME chain as direct messages. Prove the two interleave without
    /// desynchronising — this is the coupling the new format introduces.
    func testGroupWrapsAndDirectMessagesShareOneChainWithoutDrift() throws {
        let (a, b) = pair()

        let direct1 = try a.encrypt("message direct 1")
        let wrap1 = try a.encrypt("Y29udGVudEtleTE=")     // a group wrap
        let direct2 = try a.encrypt("message direct 2")
        let wrap2 = try a.encrypt("Y29udGVudEtleTI=")

        // Delivered out of order, as a mesh would.
        XCTAssertEqual(try b.decrypt(wrap2), "Y29udGVudEtleTI=")
        XCTAssertEqual(try b.decrypt(direct1), "message direct 1")
        XCTAssertEqual(try b.decrypt(wrap1), "Y29udGVudEtleTE=")
        XCTAssertEqual(try b.decrypt(direct2), "message direct 2")

        // The conversation continues normally afterwards.
        XCTAssertEqual(try a.decrypt(try b.encrypt("ok")), "ok")
    }

    // MARK: - v2 no longer encrypts on the world constant

    /// The v2 responder used to send its first message on 32 zero bytes — the
    /// public constant d663c614…. This proves the constant is gone from the v2
    /// CONSTRUCTION: neither fresh v2 session carries a zero sending chain, and a
    /// v2 responder that speaks first is still read by the initiator, so both
    /// sides agree on the derived chain. [Audit 2026-09-09]
    func testV2NoLongerUsesTheWorldConstantAndStillRoundTrips() throws {
        let (a, b) = pair()   // v2 sessions (this class does not force ratcheting)
        let zero = Data(repeating: 0, count: 32)
        XCTAssertNotEqual(a.sendingChainKey, zero, "a v2 sending chain is the constant")
        XCTAssertNotEqual(b.sendingChainKey, zero, "the other v2 sending chain is the constant")

        // Whichever of the two is the v2 responder, its first send must be read.
        XCTAssertEqual(try a.decrypt(try b.encrypt("v2 sans constante")), "v2 sans constante")
        XCTAssertEqual(try b.decrypt(try a.encrypt("et retour")), "et retour")
    }
}

// MARK: - CV-010 fixed: the DH ratchet now turns

//
//  The measurement above (`testTheDHRatchetNeverTurns…`) describes a v2 session,
//  which is what every existing conversation still is. These describe a v3
//  session — the one built once both sides advertise support — and they are the
//  proof that the fix actually reaches the key schedule rather than just adding
//  a version number.
//

final class RatchetV3ForwardSecrecyTests: XCTestCase {

    private let sharedSecret = Data(repeating: 0x6D, count: 32)
    private let alice = "ALICEtransportALICEtransportALICEtrans1a="
    private let bob   = "BOBtransportBOBtransportBOBtransportbo2b="

    /// Two v3 peers.
    private func pair() -> (a: DoubleRatchetSession, b: DoubleRatchetSession) {
        let m1 = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); m1.resetAllSessions()
        let m2 = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); m2.resetAllSessions()
        return (m1.getOrCreateSession(with: bob, ourPublicKey: alice,
                                      sharedSecret: sharedSecret, ratcheting: true),
                m2.getOrCreateSession(with: alice, ourPublicKey: bob,
                                      sharedSecret: sharedSecret, ratcheting: true))
    }

    private func v2Pair() -> (a: DoubleRatchetSession, b: DoubleRatchetSession) {
        let m1 = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); m1.resetAllSessions()
        let m2 = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); m2.resetAllSessions()
        return (m1.getOrCreateSession(with: bob, ourPublicKey: alice, sharedSecret: sharedSecret),
                m2.getOrCreateSession(with: alice, ourPublicKey: bob, sharedSecret: sharedSecret))
    }

    // MARK: - The responder must not speak under a public constant

    /// `makeRatcheting` leaves the RESPONDER's `sendingChainKey` at 32 zero bytes
    /// and only replaces it on that peer's first RECEIVE. A responder that speaks
    /// FIRST therefore encrypts under a key with no secret input:
    ///   HKDF(00x32, salt: "", info: "MessageKey", 32)
    ///   = d663c61441ce56f9d25db8149a2d3867b92aaaa9cf2ff7025649801d6fe34562
    /// identical on every device, in every conversation.
    ///
    /// It shipped because no test ever has the responder speak first: the role
    /// rule is `ourKey < theirKey`, and "ALICE..." always sorts before "BOB...".
    /// So this test deliberately makes BOB talk before ALICE has said anything.
    ///
    /// Today it must FAIL CLOSED. Once the responder gets a derived initial chain
    /// -- a key-schedule change that has to land on iOS and Android together --
    /// flip this to the commented-out assertions below.
    func testResponderSpeakingFirstNeverUsesThePublicZeroChain() throws {
        let (alice, bob) = pair()   // alice = INITIATOR, bob = RESPONDER

        // Bob speaks before he has heard anything. No existing test did this,
        // because the role rule is `ourKey < theirKey` and "ALICE..." always
        // sorts first, so Alice is the initiator everywhere else. That is why
        // this shipped.
        let msg = try bob.encrypt("the responder speaks first")

        // The attack, reproduced with no session state and no secret: the message
        // key a 32-zero sending chain yields.
        let zeroKey = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: Data(repeating: 0, count: 32)),
            salt: Data(), info: Data("MessageKey".utf8), outputByteCount: 32)

        // AAD exactly as encodeHeaderForAAD builds it.
        var aad = Data()
        aad.append(msg.header.publicKey)
        var n = msg.header.messageNumber.bigEndian
        withUnsafeBytes(of: &n) { aad.append(contentsOf: $0) }
        var pcl = msg.header.previousChainLength.bigEndian
        withUnsafeBytes(of: &pcl) { aad.append(contentsOf: $0) }
        aad.append(msg.header.isDHRatchet ? 1 : 0)
        var v = Int32(msg.header.sessionVersion).bigEndian
        withUnsafeBytes(of: &v) { aad.append(contentsOf: $0) }
        aad.append(Data(msg.header.sessionId.utf8))

        XCTAssertThrowsError(
            try AES.GCM.open(try AES.GCM.SealedBox(combined: msg.ciphertext),
                             using: zeroKey, authenticating: aad),
            "the responder's first message opened under a PUBLIC CONSTANT")

        // and the real peer must still read it, in both directions, across turns
        XCTAssertEqual(try alice.decrypt(msg), "the responder speaks first")
        XCTAssertEqual(try bob.decrypt(try alice.encrypt("reply")), "reply")
        XCTAssertEqual(try alice.decrypt(try bob.encrypt("and again")), "and again")
    }

    /// The cross-platform contract. Kotlin `responderInitialChain` must produce
    /// these exact bytes or the two platforms fail the AEAD in silence.
    ///
    /// __THIS TEST USED TO ASSERT NOTHING__ It printed the vector and checked
    /// only that it was 32 bytes and not all zero. Kotlin pinned the literal;
    /// Swift did not. So the pin was one-directional: `RatchetV3VectorTest`
    /// compares Kotlin against Kotlin's own past output, and any drift on THIS
    /// side — a typo in the info string, a different salt, a truncation — left
    /// both suites green while every cross-platform v3 session where the
    /// responder speaks first died at the AEAD, in silence, in the field.
    ///
    /// The literal below is the same one `RatchetV3VectorTest` holds. Changing
    /// it is changing the wire: both platforms ship together or neither does.
    func testResponderInitialChainMatchesTheCrossPlatformVector() {
        let chain = DoubleRatchetSession.responderInitialChain(sharedSecret: sharedSecret)
        XCTAssertEqual(chain.count, 32)
        XCTAssertNotEqual(chain, Data(repeating: 0, count: 32))
        XCTAssertEqual(chain.base64EncodedString(),
                       "wH4PppcQp/gEmmFvUwzTzC456AvPLO+oC9u72f+Z43w=",
                       "la chaine initiale du repondeur a diverge d'Android — "
                       + "les sessions v3 ou le repondeur parle en premier "
                       + "echoueraient en silence")
    }

    /// Et un HKDF independant doit tomber sur le meme nombre, pour que le
    /// vecteur ne soit pas simplement le code de production qui corrige sa
    /// propre copie. Meme discipline que le test Kotlin frere.
    func testAnIndependentHkdfAgreesWithTheResponderInitialChain() {
        let prk = HMAC<SHA256>.authenticationCode(for: sharedSecret,
                                                  using: SymmetricKey(data: Data(count: 32)))
        var input = Data("ResponderInitialChain-v3".utf8)
        input.append(0x01)
        let okm = HMAC<SHA256>.authenticationCode(for: input,
                                                  using: SymmetricKey(data: Data(prk)))
        XCTAssertEqual(Data(okm).prefix(32),
                       DoubleRatchetSession.responderInitialChain(sharedSecret: sharedSecret))
    }

    /// The v2 responder chain, pinned against Kotlin `responderInitialChainV2`.
    /// v2 held the same 32 zero bytes forever (it never ratchets), so its
    /// responder encrypted every opening message under the world constant. It is
    /// derived now, with a `-v2` info string distinct from v3. [Audit 2026-09-09]
    func testResponderInitialChainV2MatchesTheCrossPlatformVector() {
        let chain = DoubleRatchetSession.responderInitialChainV2(sharedSecret: sharedSecret)
        XCTAssertEqual(chain.count, 32)
        XCTAssertNotEqual(chain, Data(repeating: 0, count: 32))
        XCTAssertEqual(chain.base64EncodedString(),
                       "soNnY0ttNa4vIPhiG1WoIQmv6GL2pHZ+nO6GG0KkTPE=",
                       "la chaîne initiale v2 du répondeur a divergé d'Android")
        // v2 et v3 ne doivent JAMAIS dériver la même valeur.
        XCTAssertNotEqual(chain,
                          DoubleRatchetSession.responderInitialChain(sharedSecret: sharedSecret))
    }

    /// The guard must not touch the INITIATOR, whose sending chain is real from
    /// the first byte. If this breaks, the guard is too wide.
    func testInitiatorSpeakingFirstStillWorks() throws {
        let (alice, bob) = pair()
        let m = try alice.encrypt("hello from the initiator")
        XCTAssertEqual(try bob.decrypt(m), "hello from the initiator")
        XCTAssertEqual(try alice.decrypt(try bob.encrypt("and a reply")), "and a reply")
    }

    // MARK: - Upgrading from a build that shipped the zero chain

    /// THE MIGRATION QUESTION: a user has a live conversation created by the OLD
    /// build, updates, and keeps talking. What happens?
    ///
    /// The old build persisted a v3 session whose responder sending chain was 32
    /// zero bytes. The new build must NOT quietly encrypt on it. It must refuse,
    /// so `MessageManager.handleDoubleRatchetError` forces a resync and the
    /// session is rebuilt with a real derived chain.
    func testASessionPersistedByTheOldBuildRefusesToSendAndCanBeRebuilt() throws {
        let mgr = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)")
        mgr.resetAllSessions()
        let old = mgr.getOrCreateSession(with: alice, ourPublicKey: bob,
                                         sharedSecret: sharedSecret, ratcheting: true)

        // Reproduce exactly what the old build left on disk.
        old.forceSendingChainForTesting(Data(repeating: 0, count: 32))

        XCTAssertThrowsError(try old.encrypt("first message after the update")) { err in
            guard case DoubleRatchetError.uninitializedSendingChain = err else {
                return XCTFail("expected a refusal, got \(err) — a message would have gone out under the public constant")
            }
        }

        // The heal path: drop the session and rebuild it, which is what
        // handleSessionDesync does. The rebuilt one must work.
        mgr.resetAllSessions()
        let (a2, b2) = pair()
        let m = try b2.encrypt("after the resync")
        XCTAssertEqual(try a2.decrypt(m), "after the resync")
    }

    // MARK: - Media through the responder-first path

    /// A photo is not a short string. It goes through the same ratchet, but it
    /// exercises the parts a 17-character test never touches: a payload orders of
    /// magnitude larger, and several of them in a row on the same chain.
    ///
    /// Run from the RESPONDER, because that is the role whose chain changed.
    func testMediaSizedPayloadsSurviveTheResponderFirstPath() throws {
        let (alice, bob) = pair()   // alice = INITIATOR, bob = RESPONDER

        // ~600 KB, the size of a phone photo after compression. Random, so a bug
        // that returns a zeroed or constant buffer cannot pass by accident.
        var photo = Data(count: 600_000)
        photo.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, 600_000, $0.baseAddress!) }
        let asText = photo.base64EncodedString()   // how media rides the wire here

        // The responder sends it FIRST, before it has received anything.
        let m1 = try bob.encrypt(asText)
        XCTAssertEqual(try alice.decrypt(m1), asText, "a 600 KB payload did not survive the responder-first chain")

        // and back the other way, which forces the DH ratchet with media in flight
        let m2 = try alice.encrypt(asText)
        XCTAssertEqual(try bob.decrypt(m2), asText)

        // then several in a row on the same chain, which is what sending an album does
        for i in 0..<5 {
            let chunk = asText + "#\(i)"
            XCTAssertEqual(try alice.decrypt(try bob.encrypt(chunk)), chunk, "album item \(i) failed")
        }
    }

    /// Out-of-order media, which is what a real radio delivers.
    func testMediaSurvivesOutOfOrderDeliveryOnTheResponderChain() throws {
        let (alice, bob) = pair()
        var blobs: [DoubleRatchetMessage] = []
        var texts: [String] = []
        for i in 0..<4 {
            var d = Data(count: 120_000)
            d.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, 120_000, $0.baseAddress!) }
            let t = d.base64EncodedString() + "|\(i)"
            texts.append(t)
            blobs.append(try bob.encrypt(t))   // responder sends all four first
        }
        // deliver 2, 0, 3, 1 — the initiator must bank skipped keys and still read all
        for i in [2, 0, 3, 1] {
            XCTAssertEqual(try alice.decrypt(blobs[i]), texts[i], "out-of-order media \(i) failed")
        }
    }

    /// v2 has the SAME zero chain and a larger installed base. The guard is scoped
    /// to v3 on purpose: widening it would break every live v2 responder's first
    /// send. This test pins that scoping so nobody widens it by accident.
    /// v2's own fix is a separate, coordinated change.
    func testV2ResponderIsDeliberatelyNotGuardedYet() throws {
        let (a2, b2) = v2Pair()
        let m = try b2.encrypt("v2 responder first")   // must NOT throw
        XCTAssertEqual(try a2.decrypt(m), "v2 responder first")
    }

    func testAV3SessionIsMarkedAsSuchAndV2IsUntouched() {
        let (a3, b3) = pair()
        XCTAssertEqual(a3.sessionVersion, 3)
        XCTAssertEqual(b3.sessionVersion, 3)
        let (a2, b2) = v2Pair()
        XCTAssertEqual(a2.sessionVersion, 2, "une session v2 a changé de version — les conversations existantes casseraient")
        XCTAssertEqual(b2.sessionVersion, 2)
    }

    /// The conversation must still work — a ratchet that rotates but cannot talk
    /// is not an improvement.
    func testAV3ConversationWorksInBothDirections() throws {
        let (a, b) = pair()
        for round in 0..<8 {
            XCTAssertEqual(try b.decrypt(try a.encrypt("A\(round)")), "A\(round)")
            XCTAssertEqual(try a.decrypt(try b.encrypt("B\(round)")), "B\(round)")
        }
    }

    /// THE fix. The root key must MOVE, where a v2 session's never did.
    func testTheRootKeyEvolvesOnceTheConversationIsTwoWay() throws {
        let (a, b) = pair()
        let initialRoot = a.rootKey

        XCTAssertEqual(try b.decrypt(try a.encrypt("aller")), "aller")
        XCTAssertEqual(try a.decrypt(try b.encrypt("retour")), "retour")

        XCTAssertNotEqual(a.rootKey, initialRoot,
                          "la racine n'a pas bougé après un aller-retour: le ratchet DH ne tourne toujours pas")
        XCTAssertNotEqual(b.rootKey, initialRoot)
    }

    /// The property the whole fix exists for: once the responder has replied
    /// with a RANDOM key, the stream stops being derivable from the long-term
    /// identity secret. This is the assertion that failed before the fix.
    func testForwardSecrecyHoldsAfterTheFirstRoundTrip() throws {
        let (a, b) = pair()

        XCTAssertEqual(try b.decrypt(try a.encrypt("aller")), "aller")
        XCTAssertEqual(try a.decrypt(try b.encrypt("retour")), "retour")

        let later = try a.encrypt("après le ratchet")

        // Rebuilt from the static identity secret alone — everything an attacker
        // holding the identity keys could reconstruct.
        let m = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); m.resetAllSessions()
        let rebuilt = m.getOrCreateSession(with: alice, ourPublicKey: bob,
                                           sharedSecret: sharedSecret, ratcheting: true)
        XCTAssertThrowsError(try rebuilt.decrypt(later),
                             "le trafic postérieur au premier aller-retour est encore dérivable des clés long terme")

        // The real peer still reads it, so the failure above is secrecy and not
        // a broken session.
        XCTAssertEqual(try b.decrypt(later), "après le ratchet")
    }

    /// Rotation must keep happening, not just once.
    func testTheRootKeepsMovingAcrossManyTurns() throws {
        let (a, b) = pair()
        var roots: Set<Data> = [a.rootKey]
        for round in 0..<6 {
            XCTAssertEqual(try b.decrypt(try a.encrypt("A\(round)")), "A\(round)")
            XCTAssertEqual(try a.decrypt(try b.encrypt("B\(round)")), "B\(round)")
            roots.insert(a.rootKey)
        }
        XCTAssertGreaterThan(roots.count, 3,
                             "la racine ne bouge qu'une fois: le ratchet s'arrête après le premier tour")
    }

    /// v3 must survive what the transports do, exactly as v2 did — a rotating
    /// ratchet that cannot take a reorder would be useless on this mesh.
    func testV3SurvivesReorderingLossAndDuplicates() throws {
        let (a, b) = pair()
        // Turn the ratchet first, so the reordering happens on a rotated chain.
        XCTAssertEqual(try b.decrypt(try a.encrypt("amorce")), "amorce")
        XCTAssertEqual(try a.decrypt(try b.encrypt("réponse")), "réponse")

        let msgs = try (0..<8).map { try a.encrypt("m\($0)") }
        XCTAssertEqual(try b.decrypt(msgs[5]), "m5")     // out of order
        XCTAssertEqual(try b.decrypt(msgs[0]), "m0")
        XCTAssertThrowsError(try b.decrypt(msgs[0]))     // duplicate rejected
        XCTAssertEqual(try b.decrypt(msgs[7]), "m7")
        XCTAssertEqual(try b.decrypt(msgs[2]), "m2")     // 1,3,4,6 lost
    }

    /// Interoperability: v3 and v2 must not silently half-work. The version
    /// guard has to refuse, so the negotiation is what keeps peers apart rather
    /// than luck.
    func testAV3SessionAndAV2SessionRefuseEachOther() throws {
        let (a3, _) = pair()
        let (_, b2) = v2Pair()
        XCTAssertThrowsError(try b2.decrypt(try a3.encrypt("mélange")),
                             "une session v2 a accepté du trafic v3: la négociation n'est pas ce qui protège")
    }

    /// And a v3 session must still be a LoRa-sized message.
    func testAV3MessageStillFitsOnLoRa() throws {
        let (a, _) = pair()
        let bytes = try JSONEncoder().encode(a.encrypt("Rendez-vous à 18h près du pont.")).count
        XCTAssertLessThan(bytes, 180 * 12)
    }

    /// The same vector `RatchetV3VectorTest.kt` pins on Android, produced by a
    /// THIRD implementation (Python HKDF) so neither app can inherit a shared
    /// mistake. If the two platforms derive different responder ratchet keys,
    /// every v3 conversation between an iPhone and an Android silently fails to
    /// decrypt — and nothing else in either suite would notice.
    func testDerivedResponderRatchetKeyMatchesTheCrossPlatformVector() {
        let ss = Data(repeating: 0x6d, count: 32)
        let key = DoubleRatchetSession.derivedResponderRatchetKey(sharedSecret: ss)
        XCTAssertEqual(key.rawRepresentation.base64EncodedString(),
                       "9hNjB4pzO1ChrZruYY1QdYrhcKKGKD3HuCPI91bVIdM=",
                       "la clé de ratchet initiale a divergé d'Android")
        XCTAssertEqual(DoubleRatchetSession.ratchetingSessionVersion, 3)
    }

    /// An initiator cannot guess a peer's wire format. Unknown peers stay v2;
    /// a profile-advertised v3 peer gets the rotating format.
    func testInitiationUsesV3OnlyForAdvertisedCapability() {
        PeerCapabilityStore.shared.removeAllForTesting()
        let m = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)")
        XCTAssertFalse(m.shouldRatchet(peer: bob, incomingHeader: nil),
                       "un pair inconnu doit recevoir le format v2 interopérable")
        PeerCapabilityStore.shared.record(ratchetV3: true, for: bob)
        XCTAssertTrue(m.shouldRatchet(peer: bob, incomingHeader: nil))
        PeerCapabilityStore.shared.removeAllForTesting()

        // La capacité reste APPRISE du profil — juste plus consultée pour la porte.
        XCTAssertFalse(m.wantsRatchetingSession(with: bob))
        PeerCapabilityStore.shared.record(ratchetV3: true, for: bob)
        XCTAssertTrue(m.wantsRatchetingSession(with: bob))
        PeerCapabilityStore.shared.removeAllForTesting()
    }

}

// MARK: - Old build ⇄ new build, in both directions

//
//  The question this file has to answer without hand-waving: can somebody who
//  has NOT updated still talk to somebody who has, and see their messages, and
//  be seen?
//
//  Capability is learned from a profile that arrives asynchronously, so the
//  dangerous case is not old-vs-new — it is NEW vs NEW with ASYMMETRIC
//  knowledge. A knows B supports v3 because B's profile arrived; B has not yet
//  received A's. If A then builds a v3 session and B builds a v2 one, the strict
//  `sessionVersion` check makes every message between two updated clients fail.
//

final class VersionInteropTests: XCTestCase {

    private let sharedSecret = Data(repeating: 0x77, count: 32)
    private let alice = "ALICEinteropALICEinteropALICEinter1a="
    private let bob   = "BOBinteropBOBinteropBOBinteropbob2b="

    override func setUp() {
        super.setUp()
        PeerCapabilityStore.shared.removeAllForTesting()
        PostQuantumPeerDirectory.shared.removeAllForTesting()
    }
    override func tearDown() {
        PeerCapabilityStore.shared.removeAllForTesting()
        PostQuantumPeerDirectory.shared.removeAllForTesting()
        super.tearDown()
    }

    private func mgr() -> DoubleRatchetSessionManager {
        let m = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); m.resetAllSessions(); return m
    }

    /// An old build knows nothing and advertises nothing, so a new build must
    /// produce the old session and the old wire format.
    func testNewClientTalkingToAnOldClientStaysOnTheOldFormat() throws {
        let newSide = mgr().getOrCreateSessionNegotiatingPQ(
            with: bob, ourPublicKey: alice, sharedSecret: sharedSecret)
        XCTAssertEqual(newSide.sessionVersion, 2)
        XCTAssertEqual(newSide.securityMode, .classical)

        let oldSide = mgr().getOrCreateSession(with: alice, ourPublicKey: bob,
                                               sharedSecret: sharedSecret)
        XCTAssertEqual(try oldSide.decrypt(try newSide.encrypt("depuis la nouvelle version")),
                       "depuis la nouvelle version")
        XCTAssertEqual(try newSide.decrypt(try oldSide.encrypt("depuis l'ancienne")),
                       "depuis l'ancienne")
    }

    /// THE case that matters, and the one capability alone does not cover.
    ///
    /// Both sides are updated, but only A has received B's profile. A therefore
    /// wants v3; B, knowing nothing about A yet, would build v2 — and the strict
    /// version check would kill the conversation between two clients that are
    /// both perfectly capable.
    ///
    /// The receiver must take the version from the INCOMING HEADER, not from what
    /// it happens to have learned.
    func testAsymmetricCapabilityKnowledgeDoesNotBreakTwoUpdatedClients() throws {
        // A has B's profile; B has not yet received A's.
        PeerCapabilityStore.shared.record(ratchetV3: true, for: bob)
        let a = mgr().getOrCreateSessionNegotiatingPQ(
            with: bob, ourPublicKey: alice, sharedSecret: sharedSecret)
        XCTAssertEqual(a.sessionVersion, 3, "A devrait vouloir v3: il connaît la capacité de B")

        let first = try a.encrypt("bonjour depuis une session v3")

        // B knows nothing about A, and receives a v3 message.
        PeerCapabilityStore.shared.removeAllForTesting()
        let b = mgr().getOrCreateSessionNegotiatingPQ(
            with: alice, ourPublicKey: bob, sharedSecret: sharedSecret,
            incomingHeader: first.header)

        XCTAssertEqual(b.sessionVersion, 3,
                       "B a construit une session v2 face à un en-tête v3: deux clients à jour ne se parleraient plus")
        XCTAssertEqual(try b.decrypt(first), "bonjour depuis une session v3")
        XCTAssertEqual(try a.decrypt(try b.encrypt("bien reçu")), "bien reçu")
    }

    /// And the reverse must NOT happen: a v2-looking header must never pull an
    /// established v3 session back down. Headers are attacker-controlled.
    func testAV2HeaderCannotDowngradeAnEstablishedV3Session() throws {
        PeerCapabilityStore.shared.record(ratchetV3: true, for: bob)
        let m = mgr()
        let v3 = m.getOrCreateSessionNegotiatingPQ(with: bob, ourPublicKey: alice,
                                                   sharedSecret: sharedSecret)
        XCTAssertEqual(v3.sessionVersion, 3)

        let forged = DoubleRatchetMessage.MessageHeader(
            publicKey: Data(repeating: 4, count: 32), messageNumber: 0,
            previousChainLength: 0, isDHRatchet: false, timestamp: Date(),
            sessionVersion: 2, sessionId: v3.sessionId,
            securityMode: nil, pqCiphertext: nil)

        let after = m.getOrCreateSessionNegotiatingPQ(with: bob, ourPublicKey: alice,
                                                      sharedSecret: sharedSecret,
                                                      incomingHeader: forged)
        XCTAssertEqual(after.sessionVersion, 3,
                       "un en-tête v2 a rétrogradé une session v3 établie")
    }

    /// A brand-new session created from an OLD peer's v2 header must stay v2 —
    /// the header is what decides, in both directions.
    func testAV2HeaderFromAnOldPeerCreatesAV2Session() throws {
        let oldSide = mgr().getOrCreateSession(with: alice, ourPublicKey: bob,
                                               sharedSecret: sharedSecret)
        let fromOld = try oldSide.encrypt("ancienne version")

        let newSide = mgr().getOrCreateSessionNegotiatingPQ(
            with: bob, ourPublicKey: alice, sharedSecret: sharedSecret,
            incomingHeader: fromOld.header)
        XCTAssertEqual(newSide.sessionVersion, 2)
        XCTAssertEqual(try newSide.decrypt(fromOld), "ancienne version")
    }

    /// Media: the file key must cross an old⇄new boundary intact, because that
    /// is what a photo actually is on the wire.
    func testAFileKeyCrossesTheOldNewBoundaryIntact() throws {
        let newSide = mgr().getOrCreateSessionNegotiatingPQ(
            with: bob, ourPublicKey: alice, sharedSecret: sharedSecret)
        let oldSide = mgr().getOrCreateSession(with: alice, ourPublicKey: bob,
                                               sharedSecret: sharedSecret)

        let enc = try OSHICryptoV2.encryptFile(bytes: Data(repeating: 0xAB, count: 30_000),
                                               chunkSize: 8 * 1024)
        let keyB64 = enc.fileKey.base64EncodedString()
        XCTAssertEqual(try oldSide.decrypt(try newSide.encrypt(keyB64)), keyB64)
        XCTAssertEqual(try newSide.decrypt(try oldSide.encrypt(keyB64)), keyB64)
    }
}
