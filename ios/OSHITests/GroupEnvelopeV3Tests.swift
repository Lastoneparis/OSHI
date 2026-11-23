//
//  GroupEnvelopeV3Tests.swift
//  OSHITests
//
//  CV-002 — the group key that contained no secret.
//
//  The old scheme derived the group key as `SHA256(groupId + constant)`, so
//  these tests are as much about what must NO LONGER be possible as about the
//  new format working: a stranger who knows the group id must get nothing.
//
//  The wrap seams are driven by REAL `DoubleRatchetSession` objects rather than
//  a stub. That matters for the case that will actually happen in the field —
//  a member whose 1:1 ratchet has drifted — because only the real ratchet
//  produces the desync this has to survive.
//

import XCTest
import CryptoKit
@testable import OSHI

final class GroupEnvelopeV3Tests: XCTestCase {

    private let sharedSecret = Data(repeating: 0x3B, count: 32)
    private let alice = "ALICEaliceALICEaliceALICEaliceALICEalice1a="
    private let bob   = "BOBbobBOBbobBOBbobBOBbobBOBbobBOBbobBOBbo2b="
    private let carol = "CAROLcarolCAROLcarolCAROLcarolCAROLcarol3c="
    private let mallory = "MALLORYmalloryMALLORYmalloryMALLORYmall4m="

    override func setUp() {
        super.setUp()
        PeerCapabilityStore.shared.removeAllForTesting()
    }
    override func tearDown() {
        PeerCapabilityStore.shared.removeAllForTesting()
        super.tearDown()
    }

    // MARK: - A two-ended ratchet pair per member

    /// Sender-side and receiver-side sessions for one member, built as two real
    /// peers (each names the other), so their chains line up the way two devices'
    /// would.
    private func ratchetPair(for member: String) -> (send: DoubleRatchetSession, recv: DoubleRatchetSession) {
        let m1 = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); m1.resetAllSessions()
        let m2 = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); m2.resetAllSessions()
        let send = m1.getOrCreateSession(with: member, ourPublicKey: alice, sharedSecret: sharedSecret)
        let recv = m2.getOrCreateSession(with: alice, ourPublicKey: member, sharedSecret: sharedSecret)
        return (send, recv)
    }

    private func encryptor(_ sessions: [String: DoubleRatchetSession]) -> GroupEnvelopeV3.WrapEncryptor {
        { member, contentKeyB64 in
            guard let s = sessions[member] else { throw GroupEnvelopeV3.Failure.malformed }
            return try JSONEncoder().encode(try s.encrypt(contentKeyB64))
        }
    }

    private func decryptor(_ session: DoubleRatchetSession) -> GroupEnvelopeV3.WrapDecryptor {
        { data in
            let msg = try JSONDecoder().decode(DoubleRatchetMessage.self, from: data)
            return try session.decrypt(msg)
        }
    }

    // MARK: - Cross-platform

    /// The same vector `GroupEnvelopeV3Test.kt` pins on Android, produced by a
    /// THIRD implementation (Python's hashlib) so neither app can inherit a
    /// shared mistake.
    ///
    /// If iOS and Android ever drift on the input encoding, the salt order or the
    /// truncation length, it fails here — rather than as an Android member who
    /// silently stops receiving group messages.
    func testHintMatchesTheCrossPlatformVector() {
        let member = "MEMBER_TEST_KEY_aaaaaaaaaaaaaaaaaaaaaaaa="
        let salt = Data(repeating: 0x2a, count: 16)
        XCTAssertEqual(GroupEnvelopeV3.hint(forMember: member, salt: salt),
                       "+7zbjg1FmAn8coemimzAzg==",
                       "l'indice de destinataire a divergé d'Android")
    }

    /// The wire version must NOT be 3.
    ///
    /// `v: 3` is already the 1:1 IPFS envelope — `{v, envelope, nonce, hint,
    /// from, senderPublicKey}` in `MessageManager.uploadToIPFS` — and both
    /// shapes share one `v` namespace. They are told apart today only because
    /// every decoder happens to be strict, which stops being true the moment
    /// someone adds a field or decodes leniently.
    func testTheWireVersionDoesNotCollideWithTheOneToOneEnvelope() throws {
        XCTAssertEqual(GroupEnvelopeV3.version, 4)
        let b = ratchetPair(for: bob)
        let sealed = try GroupEnvelopeV3.seal(payload: Data("x".utf8), members: [bob],
                                              encryptWrap: encryptor([bob: b.send]))
        XCTAssertEqual(sealed.v, 4)
    }

    /// A 1:1 v3 envelope must never decode as a group one.
    func testAOneToOneV3EnvelopeIsNotSeenAsAGroupEnvelope() throws {
        let oneToOne = try JSONSerialization.data(withJSONObject: [
            "v": 3, "envelope": "abc", "nonce": "def",
            "hint": "ABCD", "from": "12345678", "senderPublicKey": "AAAA"
        ])
        XCTAssertNil(GroupEnvelopeV3.fromPinned(oneToOne),
                     "une enveloppe 1:1 v3 est prise pour une enveloppe de groupe")
    }

    // MARK: - It works

    func testAMemberCanReadTheMessageAndTheBodyIsSentOnlyOnce() throws {
        let b = ratchetPair(for: bob)
        let c = ratchetPair(for: carol)
        let payload = Data("le message du groupe".utf8)

        let sealed = try GroupEnvelopeV3.seal(payload: payload,
                                              members: [bob, carol],
                                              encryptWrap: encryptor([bob: b.send, carol: c.send]))

        XCTAssertEqual(sealed.v, GroupEnvelopeV3.version)
        XCTAssertEqual(sealed.wraps.count, 2)

        XCTAssertEqual(try GroupEnvelopeV3.open(sealed, asMember: bob,
                                                decryptWrap: decryptor(b.recv)), payload)
        XCTAssertEqual(try GroupEnvelopeV3.open(sealed, asMember: carol,
                                                decryptWrap: decryptor(c.recv)), payload)
    }

    /// The cost argument for this design, pinned so it cannot silently regress
    /// into per-member fan-out: the body is stored once, and adding a member adds
    /// only a wrap.
    func testAddingAMemberAddsAWrapNotACopyOfTheBody() throws {
        let big = Data(repeating: 0x41, count: 4096)
        let b = ratchetPair(for: bob), c = ratchetPair(for: carol)

        let one = try GroupEnvelopeV3.seal(payload: big, members: [bob],
                                           encryptWrap: encryptor([bob: b.send]))
        let two = try GroupEnvelopeV3.seal(payload: big, members: [bob, carol],
                                           encryptWrap: encryptor([bob: b.send, carol: c.send]))

        XCTAssertEqual(one.body.count, two.body.count,
                       "le corps est recopié par membre — on est revenu au fan-out")
        XCTAssertLessThan(two.wraps[0].k.count, 1200,
                          "une enveloppe par membre plutôt qu'une clé enveloppée")
    }

    // MARK: - What must no longer be possible

    /// The heart of CV-002. Knowing the group id used to BE the key. It must now
    /// buy nothing at all.
    func testKnowingTheGroupIdRevealsNothing() throws {
        let groupId = UUID().uuidString
        let b = ratchetPair(for: bob)
        let payload = Data("secret".utf8)
        let sealed = try GroupEnvelopeV3.seal(payload: payload, members: [bob],
                                              encryptWrap: encryptor([bob: b.send]))

        // The old key, derived exactly as the legacy path did.
        let legacyKey = Data(SHA256.hash(
            data: Data(groupId.uppercased().utf8) + Data("oshi-group-envelope-key".utf8)))
        let combined = try XCTUnwrap(Data(base64Encoded: sealed.body))
        let nonce = try AES.GCM.Nonce(data: try XCTUnwrap(Data(base64Encoded: sealed.nonce)))
        let box = try AES.GCM.SealedBox(nonce: nonce,
                                        ciphertext: combined.prefix(combined.count - 16),
                                        tag: combined.suffix(16))
        XCTAssertThrowsError(try AES.GCM.open(box, using: SymmetricKey(data: legacyKey)),
                             "l'ancienne clé dérivée du groupId ouvre encore le message")

        // And the envelope must not carry the group id, nor a prefix of it.
        let json = String(data: try JSONEncoder().encode(sealed), encoding: .utf8) ?? ""
        XCTAssertFalse(json.contains(groupId), "le groupId voyage en clair")
        XCTAssertFalse(json.lowercased().contains(groupId.prefix(4).lowercased()),
                       "l'indice de 4 caractères du groupId est toujours là")
    }

    func testANonMemberFindsNoWrap() throws {
        let b = ratchetPair(for: bob)
        let sealed = try GroupEnvelopeV3.seal(payload: Data("hello".utf8), members: [bob],
                                              encryptWrap: encryptor([bob: b.send]))
        XCTAssertThrowsError(try GroupEnvelopeV3.open(sealed, asMember: mallory,
                                                      decryptWrap: decryptor(b.recv))) { e in
            XCTAssertEqual(e as? GroupEnvelopeV3.Failure, .noWrapForUs)
        }
    }

    /// Hints must not turn the pin into a membership list, and two envelopes to
    /// the same group must not be linkable by them.
    func testRecipientHintsAreUnlinkableAcrossMessages() throws {
        let b = ratchetPair(for: bob)
        let enc = encryptor([bob: b.send])
        let first = try GroupEnvelopeV3.seal(payload: Data("un".utf8), members: [bob], encryptWrap: enc)
        let second = try GroupEnvelopeV3.seal(payload: Data("deux".utf8), members: [bob], encryptWrap: enc)

        XCTAssertNotEqual(first.salt, second.salt)
        XCTAssertNotEqual(first.wraps[0].h, second.wraps[0].h,
                          "le même membre produit le même indice deux fois: les épingles sont liables")
        // The hint must not be the member key itself in any recoverable form.
        XCTAssertFalse(first.wraps[0].h.contains(bob.prefix(6)))
    }

    func testATamperedBodyIsRejected() throws {
        let b = ratchetPair(for: bob)
        let sealed = try GroupEnvelopeV3.seal(payload: Data("intact".utf8), members: [bob],
                                              encryptWrap: encryptor([bob: b.send]))
        var bodyBytes = try XCTUnwrap(Data(base64Encoded: sealed.body))
        bodyBytes[bodyBytes.startIndex] ^= 0x01
        let tampered = GroupEnvelopeV3.Sealed(v: sealed.v, salt: sealed.salt,
                                              body: bodyBytes.base64EncodedString(),
                                              nonce: sealed.nonce, wraps: sealed.wraps)
        XCTAssertThrowsError(try GroupEnvelopeV3.open(tampered, asMember: bob,
                                                      decryptWrap: decryptor(b.recv)))
    }

    // MARK: - Ratchet desync, which is the failure that will actually happen

    /// A member whose 1:1 ratchet has drifted must fail in a way the caller can
    /// act on — a distinct error that means "heal the session", never a silent
    /// empty message.
    func testADesyncedMemberSurfacesAsWrapUndecryptable() throws {
        let b = ratchetPair(for: bob)
        let sealed = try GroupEnvelopeV3.seal(payload: Data("coucou".utf8), members: [bob],
                                              encryptWrap: encryptor([bob: b.send]))

        // A DIFFERENT receiving session: same identities, unrelated chain state —
        // exactly what a reinstall or a lost session leaves behind.
        let stale = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)")
        stale.resetAllSessions()
        let wrongSession = stale.getOrCreateSession(with: alice, ourPublicKey: bob,
                                                    sharedSecret: Data(repeating: 0x99, count: 32))

        XCTAssertThrowsError(try GroupEnvelopeV3.open(sealed, asMember: bob,
                                                      decryptWrap: decryptor(wrongSession))) { e in
            XCTAssertEqual(e as? GroupEnvelopeV3.Failure, .wrapUndecryptable,
                           "un ratchet désynchronisé doit dire « répare la session », pas « malformé »")
        }
    }

    /// Out-of-order group messages must still open — the ratchet's skipped-key
    /// machinery has to carry the wraps, or every reordered mesh delivery would
    /// look like a desync.
    func testOutOfOrderGroupMessagesStillOpen() throws {
        let b = ratchetPair(for: bob)
        let enc = encryptor([bob: b.send])
        let first  = try GroupEnvelopeV3.seal(payload: Data("un".utf8),    members: [bob], encryptWrap: enc)
        let second = try GroupEnvelopeV3.seal(payload: Data("deux".utf8),  members: [bob], encryptWrap: enc)
        let third  = try GroupEnvelopeV3.seal(payload: Data("trois".utf8), members: [bob], encryptWrap: enc)

        let d = decryptor(b.recv)
        XCTAssertEqual(try GroupEnvelopeV3.open(third,  asMember: bob, decryptWrap: d), Data("trois".utf8))
        XCTAssertEqual(try GroupEnvelopeV3.open(first,  asMember: bob, decryptWrap: d), Data("un".utf8))
        XCTAssertEqual(try GroupEnvelopeV3.open(second, asMember: bob, decryptWrap: d), Data("deux".utf8))
    }

    /// One unreachable member must not stop the message for everybody else.
    func testAMemberWithNoSessionIsSkippedRatherThanFailingTheSend() throws {
        let b = ratchetPair(for: bob)
        let sealed = try GroupEnvelopeV3.seal(payload: Data("quand même".utf8),
                                              members: [bob, carol],   // carol has no session
                                              encryptWrap: encryptor([bob: b.send]))
        XCTAssertEqual(sealed.wraps.count, 1)
        XCTAssertEqual(try GroupEnvelopeV3.open(sealed, asMember: bob,
                                                decryptWrap: decryptor(b.recv)), Data("quand même".utf8))
    }

    // MARK: - The interoperability gate

    func testAGroupStaysOnTheOldFormatUntilEveryMemberCanReadV3() {
        PeerCapabilityStore.shared.record(groupV3: true, for: bob)
        XCTAssertFalse(GroupEnvelopeV3.canUse(members: [bob, carol]),
                       "un seul membre non mis à jour doit garder TOUT le groupe sur l'ancien format")

        PeerCapabilityStore.shared.record(groupV3: true, for: carol)
        XCTAssertTrue(GroupEnvelopeV3.canUse(members: [bob, carol]))

        // A member who downgrades / reinstalls takes the group back.
        PeerCapabilityStore.shared.record(groupV3: false, for: carol)
        XCTAssertFalse(GroupEnvelopeV3.canUse(members: [bob, carol]))
    }

    func testAnUnknownMemberIsTreatedAsIncapable() {
        XCTAssertFalse(GroupEnvelopeV3.canUse(members: [bob]),
                       "un pair inconnu est supposé capable: on lui couperait les messages de groupe")
        XCTAssertFalse(GroupEnvelopeV3.canUse(members: []))
    }

    func testAProfileWithoutTheFlagIsReadAsIncapable() throws {
        let legacy = #"{"type":"profile_update","profileImageData":null,"lastSeen":1.0,"version":1}"#
        let payload = try JSONDecoder().decode(ProfileUpdatePayload.self, from: Data(legacy.utf8))
        XCTAssertNil(payload.groupEnvelopeV3)
        XCTAssertNotEqual(payload.groupEnvelopeV3, true)
    }

    func testOurOwnProfileAdvertisesV3() {
        let mine = ContactPresenceManager.shared.createProfileUpdatePayload()
        XCTAssertEqual(mine.groupEnvelopeV3, true,
                       "on n'annonce pas v3: aucun groupe ne migrera jamais")
    }
}

// MARK: - Fitting the radio (the >3 member fix)

extension GroupEnvelopeV3Tests {

    private func manyMembers(_ n: Int) -> ([String], [String: DoubleRatchetSession]) {
        var members: [String] = []
        var sessions: [String: DoubleRatchetSession] = [:]
        for i in 0..<n {
            let member = "LORAMEMBER\(i)aaaaaaaaaaaaaaaaaaaaaaaaaaaa="
            members.append(member)
            let m = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); m.resetAllSessions()
            sessions[member] = m.getOrCreateSession(with: member, ourPublicKey: alice,
                                                    sharedSecret: sharedSecret)
        }
        return (members, sessions)
    }

    /// A small group still travels as ONE envelope — the fix must not make every
    /// group pay for the large-group case.
    func testASmallGroupStillSendsASingleEnvelope() throws {
        let (members, sessions) = manyMembers(3)
        let out = try GroupEnvelopeV3.sealFitting(
            payload: Data("court".utf8), members: members,
            maxBytes: GroupEnvelopeV3.loRaMessageCeiling,
            encryptWrap: encryptor(sessions))
        XCTAssertEqual(out.count, 1, "un groupe de 3 tient en une enveloppe et doit le rester")
        XCTAssertEqual(out[0].wraps.count, 3)
    }

    /// THE fix: above the ceiling, one envelope per recipient, and EVERY one of
    /// them must fit the radio.
    func testALargeGroupSplitsIntoPerRecipientEnvelopesThatEachFit() throws {
        for count in [5, 8, 20] {
            let (members, sessions) = manyMembers(count)
            let out = try GroupEnvelopeV3.sealFitting(
                payload: Data("un message de groupe ordinaire".utf8), members: members,
                maxBytes: GroupEnvelopeV3.loRaMessageCeiling,
                encryptWrap: encryptor(sessions))

            XCTAssertEqual(out.count, count, "chaque membre doit recevoir sa propre enveloppe")
            for env in out {
                XCTAssertEqual(env.wraps.count, 1)
                XCTAssertLessThanOrEqual(GroupEnvelopeV3.encodedSize(env),
                                         GroupEnvelopeV3.loRaMessageCeiling,
                                         "une enveloppe par destinataire dépasse encore le plafond LoRa (\(count) membres)")
            }
        }
    }

    /// Each recipient must actually be able to open the copy addressed to them.
    func testEveryPerRecipientEnvelopeIsReadableByItsOwner() throws {
        let (members, sendSessions) = manyMembers(6)
        // Receiving halves, mirrored as real peers.
        var recvSessions: [String: DoubleRatchetSession] = [:]
        for member in members {
            let m = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); m.resetAllSessions()
            recvSessions[member] = m.getOrCreateSession(with: alice, ourPublicKey: member,
                                                        sharedSecret: sharedSecret)
        }
        let payload = Data("lisible par chacun".utf8)
        let out = try GroupEnvelopeV3.sealFitting(
            payload: payload, members: members,
            maxBytes: GroupEnvelopeV3.loRaMessageCeiling,
            encryptWrap: encryptor(sendSessions))

        for (i, member) in members.enumerated() {
            XCTAssertEqual(try GroupEnvelopeV3.open(out[i], asMember: member,
                                                    decryptWrap: decryptor(recvSessions[member]!)),
                           payload, "le membre \(i) ne peut pas lire son enveloppe")
        }
    }

    /// The copies must not be correlatable by ciphertext: each gets its own
    /// content key, so the bodies differ even though the plaintext is identical.
    func testPerRecipientCopiesDoNotShareACiphertext() throws {
        let (members, sessions) = manyMembers(5)
        let out = try GroupEnvelopeV3.sealFitting(
            payload: Data("même texte".utf8), members: members,
            maxBytes: GroupEnvelopeV3.loRaMessageCeiling,
            encryptWrap: encryptor(sessions))
        XCTAssertEqual(Set(out.map { $0.body }).count, out.count,
                       "deux destinataires partagent le même corps chiffré: les copies sont corrélables")
    }

    /// Off the radio there is no reason to split — one pin, as before.
    func testWithAGenerousBudgetALargeGroupStaysOneEnvelope() throws {
        let (members, sessions) = manyMembers(20)
        let out = try GroupEnvelopeV3.sealFitting(
            payload: Data("via IPFS".utf8), members: members,
            maxBytes: 1_000_000,
            encryptWrap: encryptor(sessions))
        XCTAssertEqual(out.count, 1, "hors radio, on doit garder UNE seule épingle Pinata")
        XCTAssertEqual(out[0].wraps.count, 20)
    }
}
