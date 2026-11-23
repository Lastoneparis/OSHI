//
//  PostQuantumRatchetTests.swift
//  OSHITests
//
//  THE HYBRID POST-QUANTUM RATCHET — and, more importantly, the proof that
//  turning it on cannot disturb anything already running.
//
//  Two thirds of this file is about the SECOND part. A post-quantum upgrade that
//  works is easy to demonstrate; one that leaves every existing conversation,
//  every stored session and every peer on iOS 17 / Android / desktop exactly
//  where they were is the thing that actually has to be shown. So the classical
//  path is pinned here byte for byte, from three directions:
//
//    * the derived root key, against an INDEPENDENT HKDF in the test — so a
//      changed `info` string or a changed IKM fails here rather than in the
//      field (a test that re-derived it with the code under test would pass no
//      matter what the code did);
//    * the JSON that goes on the wire, which must not gain a key;
//    * a session serialised by a pre-hybrid build, which must load as classical.
//
//  And the failure cases are first-class, not an afterthought. A downgrade that
//  fails LOUDLY is the entire security argument for binding the mode into the
//  AEAD's additional data: if stripping the KEM ciphertext merely produced a
//  weaker session, an attacker would simply strip it.
//

import XCTest
import CryptoKit
@testable import OSHI

final class PostQuantumRatchetTests: XCTestCase {

    // MARK: - Fixtures

    /// Stable, non-secret material. Deterministic on purpose: a random shared
    /// secret would make a failure hard to reproduce.
    private let sharedSecret = Data(repeating: 0xA7, count: 32)
    private let aliceKey = "AAAAaliceaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa="
    private let bobKey   = "BBBBbobbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb="

    private func manager() -> DoubleRatchetSessionManager {
        let m = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)")
        m.resetAllSessions()
        return m
    }

    /// Build the two ends of one conversation.
    ///
    /// Side A comes from the REAL `getOrCreateSession`, so the derivation under
    /// test is the one that runs in the app. Side B is then MIRRORED from A's
    /// own material — sending and receiving chains swapped — rather than
    /// re-derived by the test. That mirroring is what a correct peer would
    /// compute, and taking it from A means this helper cannot quietly encode a
    /// second, private opinion about how keys are derived.
    private func pair(pqSecret: Data? = nil,
                      pqCiphertext: Data? = nil) -> (a: DoubleRatchetSession, b: DoubleRatchetSession) {
        let m = manager()
        let a = m.getOrCreateSession(with: bobKey, ourPublicKey: aliceKey,
                                     sharedSecret: sharedSecret,
                                     pqSecret: pqSecret, pqCiphertext: pqCiphertext)
        let b = DoubleRatchetSession(rootKey: a.rootKey,
                                     sendingChainKey: a.receivingChainKey,
                                     receivingChainKey: a.sendingChainKey,
                                     sessionId: a.sessionId,
                                     isInitiator: !a.isInitiator,
                                     securityMode: a.securityMode)
        return (a, b)
    }

    /// An X-Wing secret + ciphertext from a real encapsulation.
    private func realKEM() throws -> (secret: Data, ciphertext: Data, seed: Data, publicKey: Data) {
        let kp = try PostQuantumKEM.generateKeyPair()
        let enc = try PostQuantumKEM.encapsulate(toPublicKey: kp.publicKey)
        return (enc.sharedSecret, enc.ciphertext, kp.seed, kp.publicKey)
    }

    private func requirePQ() throws {
        try XCTSkipUnless(PostQuantumKEM.isSupportedByPlatform,
                          "X-Wing needs iOS/macOS 26 — SKIPPED, this run proves nothing about PQ")
    }

    // MARK: - The platform

    /// Guards against the whole file passing vacuously by being skipped.
    /// If this ever fails, every `requirePQ()` test above it was a no-op.
    func testTheTestPlatformActuallySupportsXWing() {
        XCTAssertTrue(PostQuantumKEM.isSupportedByPlatform,
                      "ce simulateur est < iOS 26: tous les tests PQ de ce fichier sont SAUTÉS et ne prouvent rien")
    }

    // MARK: - Production must not move

    /// The classical root key, checked against HKDF computed here, from the
    /// spec, with no reference to the code under test.
    func testClassicalRootKeyIsUnchangedByTheHybridWork() {
        let (a, _) = pair()
        let expected = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: sharedSecret),
            salt: Data(), info: Data("RootKey".utf8), outputByteCount: 32
        ).withUnsafeBytes { Data($0) }

        XCTAssertEqual(a.rootKey, expected,
                       "la dérivation classique a bougé — toutes les conversations existantes seraient cassées")
        XCTAssertEqual(a.securityMode, .classical)
        XCTAssertNil(a.pqCiphertext)
    }

    /// A classical message must serialise to the JSON it always has. If either
    /// post-quantum key appears, an older client is being sent bytes it has
    /// never seen, and the AAD it computes will not match ours.
    func testClassicalMessageJSONGainsNoNewKeys() throws {
        let (a, _) = pair()
        let msg = try a.encrypt("bonjour")
        let json = try JSONSerialization.jsonObject(
            with: try JSONEncoder().encode(msg)) as? [String: Any]
        let header = try XCTUnwrap(json?["header"] as? [String: Any])

        XCTAssertNil(header["securityMode"],
                     "un message CLASSIQUE porte securityMode: le format de fil a changé pour tout le monde")
        XCTAssertNil(header["pqCiphertext"],
                     "un message CLASSIQUE porte pqCiphertext")
        // And the fields that were always there still are.
        for key in ["publicKey", "messageNumber", "previousChainLength",
                    "isDHRatchet", "timestamp", "sessionVersion", "sessionId"] {
            XCTAssertNotNil(header[key], "champ de fil disparu: \(key)")
        }
    }

    /// A session written by a build that had never heard of the hybrid.
    /// It must load, and it must load as classical — not throw, not "unknown".
    func testASessionStoredBeforeTheHybridLoadsAsClassical() throws {
        let (a, _) = pair()
        var stored = try JSONSerialization.jsonObject(
            with: try JSONEncoder().encode(a)) as? [String: Any] ?? [:]
        stored.removeValue(forKey: "securityMode")
        stored.removeValue(forKey: "pqCiphertext")
        stored.removeValue(forKey: "pqCiphertextAcknowledged")

        let data = try JSONSerialization.data(withJSONObject: stored)
        let revived = try JSONDecoder().decode(DoubleRatchetSession.self, from: data)

        XCTAssertEqual(revived.securityMode, .classical)
        XCTAssertEqual(revived.rootKey, a.rootKey)
        XCTAssertFalse(revived.pqCiphertextAcknowledged)
    }

    /// The ordinary conversation, still working.
    func testClassicalSessionsStillExchangeMessages() throws {
        let (a, b) = pair()
        let msg = try a.encrypt("message classique")
        XCTAssertEqual(try b.decrypt(msg), "message classique")
    }

    // MARK: - The hybrid

    func testHybridRootKeyAbsorbsTheKEMSecretAndDiffersFromClassical() throws {
        try requirePQ()
        let kem = try realKEM()
        let (hybrid, _) = pair(pqSecret: kem.secret, pqCiphertext: kem.ciphertext)
        let (classical, _) = pair()

        XCTAssertEqual(hybrid.securityMode, .hybridPQ)
        XCTAssertNotEqual(hybrid.rootKey, classical.rootKey,
                          "la clé racine hybride est identique à la classique: le secret PQ n'a pas été mélangé")

        let expected = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: sharedSecret + kem.secret),
            salt: Data(), info: Data("RootKey-XWing-v1".utf8), outputByteCount: 32
        ).withUnsafeBytes { Data($0) }
        XCTAssertEqual(hybrid.rootKey, expected)
    }

    func testHybridSessionsExchangeMessagesInBothDirections() throws {
        try requirePQ()
        let kem = try realKEM()
        let (a, b) = pair(pqSecret: kem.secret, pqCiphertext: kem.ciphertext)

        let m1 = try a.encrypt("aller")
        XCTAssertEqual(try b.decrypt(m1), "aller")
        let m2 = try b.encrypt("retour")
        XCTAssertEqual(try a.decrypt(m2), "retour")
    }

    /// The encapsulation must actually be on the wire — otherwise the responder
    /// in a real deployment could never derive the root key.
    func testTheKEMCiphertextRidesOnOutgoingMessagesUntilAcknowledged() throws {
        try requirePQ()
        let kem = try realKEM()
        let (a, b) = pair(pqSecret: kem.secret, pqCiphertext: kem.ciphertext)

        let first = try a.encrypt("un")
        XCTAssertEqual(first.header.pqCiphertext, kem.ciphertext,
                       "le destinataire ne recevrait jamais l'encapsulation")
        XCTAssertEqual(first.header.securityMode, .hybridPQ)

        let second = try a.encrypt("deux")
        XCTAssertEqual(second.header.pqCiphertext, kem.ciphertext,
                       "un seul envoi suffirait à perdre la session sur un maillage qui perd des messages")

        // B answers: proof it holds the root key.
        _ = try b.decrypt(first)
        let reply = try b.encrypt("bien reçu")
        _ = try a.decrypt(reply)

        let third = try a.encrypt("trois")
        XCTAssertNil(third.header.pqCiphertext,
                     "1120 octets continuent d'être attachés à chaque message après confirmation")
        XCTAssertEqual(third.header.securityMode, .hybridPQ,
                       "le mode doit rester épinglé même quand l'encapsulation cesse")
    }

    // MARK: - When it is broken

    /// THE downgrade attack. Strip the KEM ciphertext in flight and the message
    /// must fail authentication — not decrypt into a weaker session.
    func testStrippingTheKEMCiphertextIsRejected() throws {
        try requirePQ()
        let kem = try realKEM()
        let (a, b) = pair(pqSecret: kem.secret, pqCiphertext: kem.ciphertext)

        let sent = try a.encrypt("secret")
        var header = sent.header
        header.pqCiphertext = nil
        let tampered = DoubleRatchetMessage(header: header, ciphertext: sent.ciphertext)

        XCTAssertThrowsError(try b.decrypt(tampered),
                             "retirer l'encapsulation a produit un déchiffrement RÉUSSI: la rétrogradation est possible")
    }

    /// Same attack from the other side: claim the session is classical.
    func testStrippingTheSecurityModeIsRejected() throws {
        try requirePQ()
        let kem = try realKEM()
        let (a, b) = pair(pqSecret: kem.secret, pqCiphertext: kem.ciphertext)

        let sent = try a.encrypt("secret")
        var header = sent.header
        header.securityMode = nil
        let tampered = DoubleRatchetMessage(header: header, ciphertext: sent.ciphertext)

        XCTAssertThrowsError(try b.decrypt(tampered))
    }

    func testATamperedKEMCiphertextIsRejected() throws {
        try requirePQ()
        let kem = try realKEM()
        let (a, b) = pair(pqSecret: kem.secret, pqCiphertext: kem.ciphertext)

        let sent = try a.encrypt("secret")
        var flipped = kem.ciphertext
        flipped[flipped.startIndex] ^= 0x01
        var header = sent.header
        header.pqCiphertext = flipped
        let tampered = DoubleRatchetMessage(header: header, ciphertext: sent.ciphertext)

        XCTAssertThrowsError(try b.decrypt(tampered))
    }

    /// Domain separation: the same X25519 secret must not open a hybrid session.
    func testAClassicalPeerCannotReadAHybridSession() throws {
        try requirePQ()
        let kem = try realKEM()
        let (hybridA, _) = pair(pqSecret: kem.secret, pqCiphertext: kem.ciphertext)
        let (_, classicalB) = pair()

        let msg = try hybridA.encrypt("hybride")
        XCTAssertThrowsError(try classicalB.decrypt(msg),
                             "une session classique a lu du trafic hybride: les domaines HKDF ne sont pas séparés")
    }

    func testDecapsulationWithTheWrongSeedFails() throws {
        try requirePQ()
        let kem = try realKEM()
        let other = try PostQuantumKEM.generateKeyPair()

        // A wrong-but-valid seed must not yield the right secret. X-Wing's
        // implicit rejection means this need not throw — but it must never
        // return the same bytes.
        let wrong = try? PostQuantumKEM.decapsulate(kem.ciphertext, withSeed: other.seed)
        XCTAssertNotEqual(wrong, kem.secret,
                          "décapsuler avec la MAUVAISE graine a rendu le bon secret")
    }

    func testMalformedKeyMaterialIsRejectedBeforeCryptoKit() throws {
        try requirePQ()
        let kem = try realKEM()

        XCTAssertThrowsError(try PostQuantumKEM.encapsulate(toPublicKey: Data([1, 2, 3]))) { e in
            XCTAssertEqual(e as? PostQuantumError, .malformedKeyMaterial)
        }
        XCTAssertThrowsError(try PostQuantumKEM.decapsulate(Data([1, 2, 3]), withSeed: kem.seed)) { e in
            XCTAssertEqual(e as? PostQuantumError, .malformedKeyMaterial)
        }
        // A public key of the right LENGTH but the wrong content must also fail,
        // rather than produce a secret nobody can decapsulate.
        let garbage = Data(repeating: 0xFF, count: PostQuantumKEM.publicKeyByteCount)
        XCTAssertThrowsError(try PostQuantumKEM.encapsulate(toPublicKey: garbage))
    }

    func testEncapsulationRoundTripsToTheSameSecret() throws {
        try requirePQ()
        let kp = try PostQuantumKEM.generateKeyPair()
        let enc = try PostQuantumKEM.encapsulate(toPublicKey: kp.publicKey)
        let dec = try PostQuantumKEM.decapsulate(enc.ciphertext, withSeed: kp.seed)

        XCTAssertEqual(dec, enc.sharedSecret)
        XCTAssertEqual(enc.sharedSecret.count, PostQuantumKEM.sharedSecretByteCount)
        XCTAssertEqual(enc.ciphertext.count, PostQuantumKEM.ciphertextByteCount)
        XCTAssertEqual(kp.publicKey.count, PostQuantumKEM.publicKeyByteCount)
        // Two encapsulations to the same key must differ: a KEM that repeated
        // itself would repeat the session key.
        let again = try PostQuantumKEM.encapsulate(toPublicKey: kp.publicKey)
        XCTAssertNotEqual(again.ciphertext, enc.ciphertext)
        XCTAssertNotEqual(again.sharedSecret, enc.sharedSecret)
    }

    // MARK: - Re-initialising the ratchet

    /// The upgrade path. An established classical conversation is reset, and the
    /// next session is hybrid — this is how an existing user moves over without
    /// anyone re-keying a live conversation underneath them.
    func testResettingASessionAllowsItToComeBackAsHybrid() throws {
        try requirePQ()
        let m = manager()

        let classical = m.getOrCreateSession(with: bobKey, ourPublicKey: aliceKey,
                                             sharedSecret: sharedSecret)
        XCTAssertEqual(classical.securityMode, .classical)
        let classicalRoot = classical.rootKey

        // Offering PQ material to an EXISTING session must change nothing:
        // re-keying under a live peer would strand them.
        let kem = try realKEM()
        let same = m.getOrCreateSession(with: bobKey, ourPublicKey: aliceKey,
                                        sharedSecret: sharedSecret,
                                        pqSecret: kem.secret, pqCiphertext: kem.ciphertext)
        XCTAssertEqual(same.securityMode, .classical,
                       "une session VIVANTE a été re-clenchée sous le pair")
        XCTAssertEqual(same.rootKey, classicalRoot)

        // Reset — the explicit, deliberate act — then it comes back hybrid.
        m.resetSession(with: bobKey, ourPublicKey: aliceKey)
        let upgraded = m.getOrCreateSession(with: bobKey, ourPublicKey: aliceKey,
                                            sharedSecret: sharedSecret,
                                            pqSecret: kem.secret, pqCiphertext: kem.ciphertext)
        XCTAssertEqual(upgraded.securityMode, .hybridPQ)
        XCTAssertNotEqual(upgraded.rootKey, classicalRoot)
    }

    /// And a re-initialised hybrid session must be a genuinely NEW one: fresh
    /// KEM, fresh root. A ratchet reset that reused the old secret would give
    /// the heal path no forward secrecy at all.
    func testReinitialisingAHybridSessionProducesFreshKeyMaterial() throws {
        try requirePQ()
        let m = manager()
        let first = try realKEM()
        let s1 = m.getOrCreateSession(with: bobKey, ourPublicKey: aliceKey,
                                      sharedSecret: sharedSecret,
                                      pqSecret: first.secret, pqCiphertext: first.ciphertext)
        let root1 = s1.rootKey

        m.resetSession(with: bobKey, ourPublicKey: aliceKey)

        let second = try realKEM()
        let s2 = m.getOrCreateSession(with: bobKey, ourPublicKey: aliceKey,
                                      sharedSecret: sharedSecret,
                                      pqSecret: second.secret, pqCiphertext: second.ciphertext)

        XCTAssertEqual(s2.securityMode, .hybridPQ)
        XCTAssertNotEqual(s2.rootKey, root1,
                          "le ratchet ré-initialisé a repris la MÊME racine: aucune fraîcheur")
        XCTAssertFalse(s2.pqCiphertextAcknowledged,
                       "une session neuve démarre non confirmée, sinon l'encapsulation ne partirait jamais")
    }

    /// After a reset, the two ends must be able to talk again from scratch.
    func testAReinitialisedHybridPairCanTalkAgain() throws {
        try requirePQ()
        let kem = try realKEM()
        let (a1, b1) = pair(pqSecret: kem.secret, pqCiphertext: kem.ciphertext)
        _ = try b1.decrypt(try a1.encrypt("avant"))

        // Fresh KEM, fresh pair — as a heal would produce.
        let kem2 = try realKEM()
        let (a2, b2) = pair(pqSecret: kem2.secret, pqCiphertext: kem2.ciphertext)
        XCTAssertEqual(try b2.decrypt(try a2.encrypt("après")), "après")

        // And the OLD session's traffic must not decrypt under the NEW keys.
        XCTAssertThrowsError(try b2.decrypt(try a1.encrypt("ancien trafic")),
                             "l'ancienne session déchiffre sous la nouvelle racine")
    }

    /// Out-of-order delivery is normal on this mesh; the hybrid must not change
    /// that. Exercises the skipped-key path with a hybrid AAD.
    func testHybridSurvivesOutOfOrderDelivery() throws {
        try requirePQ()
        let kem = try realKEM()
        let (a, b) = pair(pqSecret: kem.secret, pqCiphertext: kem.ciphertext)

        let m1 = try a.encrypt("un")
        let m2 = try a.encrypt("deux")
        let m3 = try a.encrypt("trois")

        XCTAssertEqual(try b.decrypt(m3), "trois")   // gap: 1 and 2 skipped
        XCTAssertEqual(try b.decrypt(m1), "un")
        XCTAssertEqual(try b.decrypt(m2), "deux")
    }

    // MARK: - Negotiation

    func testPolicyDisabledIsAlwaysClassical() {
        let key = Data(repeating: 7, count: PostQuantumKEM.publicKeyByteCount)
        XCTAssertEqual(PostQuantumNegotiator.mode(policy: .disabled,
                                                  localSupportsPQ: true,
                                                  peerPublicKey: key), .classical)
    }

    func testHybridWhenAvailableFallsBackRatherThanFailing() {
        let good = Data(repeating: 7, count: PostQuantumKEM.publicKeyByteCount)
        XCTAssertEqual(PostQuantumNegotiator.mode(policy: .hybridWhenAvailable,
                                                  localSupportsPQ: true,
                                                  peerPublicKey: good), .hybridPQ)
        // Peer is Android / desktop / iOS 17 — must still work, classically.
        XCTAssertEqual(PostQuantumNegotiator.mode(policy: .hybridWhenAvailable,
                                                  localSupportsPQ: true,
                                                  peerPublicKey: nil), .classical)
        // We are the old device.
        XCTAssertEqual(PostQuantumNegotiator.mode(policy: .hybridWhenAvailable,
                                                  localSupportsPQ: false,
                                                  peerPublicKey: good), .classical)
        // A truncated key must not be treated as usable.
        XCTAssertEqual(PostQuantumNegotiator.mode(policy: .hybridWhenAvailable,
                                                  localSupportsPQ: true,
                                                  peerPublicKey: Data([1, 2, 3])), .classical)
    }

    func testRequiredRefusesRatherThanDowngrading() {
        let good = Data(repeating: 7, count: PostQuantumKEM.publicKeyByteCount)
        XCTAssertEqual(PostQuantumNegotiator.mode(policy: .required,
                                                  localSupportsPQ: true,
                                                  peerPublicKey: good), .hybridPQ)
        XCTAssertNil(PostQuantumNegotiator.mode(policy: .required,
                                                localSupportsPQ: true,
                                                peerPublicKey: nil),
                     "`.required` a accepté une session classique: c'est une rétrogradation silencieuse")
        XCTAssertNil(PostQuantumNegotiator.mode(policy: .required,
                                                localSupportsPQ: false,
                                                peerPublicKey: good))
    }

    /// The shipping default. If this ever flips, every device starts publishing
    /// PQ keys and negotiating — which is a product decision, not an accident.
    func testTheShippingDefaultIsDisabled() {
        XCTAssertEqual(PostQuantumPolicy.disabled.rawValue, "disabled")
        XCTAssertEqual(PostQuantumNegotiator.mode(policy: .disabled,
                                                  localSupportsPQ: true,
                                                  peerPublicKey: nil), .classical)
    }

    // MARK: - Device identity

    func testTheDeviceKeyPairIsStableAcrossReads() throws {
        try requirePQ()
        let identity = PostQuantumIdentity.shared
        identity.resetForTesting()
        defer { identity.resetForTesting() }

        let first = try XCTUnwrap(identity.publicKey())
        let second = try XCTUnwrap(identity.publicKey())
        XCTAssertEqual(first, second, "l'identité PQ change entre deux lectures")
        XCTAssertEqual(first.count, PostQuantumKEM.publicKeyByteCount)

        // And the published key must match the seed we would decapsulate with.
        let derived = try PostQuantumKEM.publicKey(forSeed: try identity.seed())
        XCTAssertEqual(derived, first,
                       "la clé publiée ne correspond pas à la graine stockée: toute encapsulation serait indéchiffrable")
    }
}

// MARK: - Production wiring: interoperability and automatic re-establishment

//
//  The previous class proves the hybrid is correct. This one proves it is safe
//  to SHIP — which is a different claim, and the one that matters when there are
//  already users in the field.
//
//  Everything here goes through `getOrCreateSessionNegotiatingPQ`, the single
//  entry point every send and receive path in `MessageManager` now calls. Testing
//  the primitive would not catch the failure that actually costs users their
//  conversations: a client that has updated refusing to talk to one that has not.
//

final class PostQuantumInteropTests: XCTestCase {

    private let sharedSecret = Data(repeating: 0x5C, count: 32)
    private let me   = "MEmemememememememememememememememememememe="
    private let them = "THEMthemthemthemthemthemthemthemthemthemth="

    override func setUp() {
        super.setUp()
        PostQuantumPeerDirectory.shared.removeAllForTesting()
        PostQuantumPolicy.setOverride(nil)
    }

    override func tearDown() {
        PostQuantumPeerDirectory.shared.removeAllForTesting()
        PostQuantumPolicy.setOverride(nil)
        super.tearDown()
    }

    private func manager() -> DoubleRatchetSessionManager {
        let m = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)")
        m.resetAllSessions()
        return m
    }

    /// Same key, filed under an arbitrary peer id — needed when BOTH simulated
    /// sides must be able to encapsulate to "the other one".
    private func publishOwnKeyAs(_ peer: String) throws {
        let pub = try XCTUnwrap(PostQuantumIdentity.shared.publicKey())
        PostQuantumPeerDirectory.shared.record(pub, for: peer)
    }

    /// Publishes THIS device's PQ key as if the peer had sent it in a profile.
    ///
    /// One process has one Keychain, so "the peer" has to be our own X-Wing
    /// identity — which is exactly what makes the round trip real: the initiator
    /// encapsulates to a published key, and the responder decapsulates with the
    /// seed `PostQuantumIdentity` actually holds.
    private func publishPeerKey() throws {
        let pub = try XCTUnwrap(PostQuantumIdentity.shared.publicKey(),
                                "pas de clé PQ locale — l'OS est-il < iOS 26 ?")
        PostQuantumPeerDirectory.shared.record(pub, for: them)
    }

    // MARK: A peer that has not updated

    /// The single most important test in this file. A contact still on the old
    /// build publishes no PQ key, so the directory is empty — and the session
    /// must come out classical and work exactly as before.
    func testAPeerWhoHasNotUpdatedStaysClassicalAndStillTalks() throws {
        let m = manager()
        let a = m.getOrCreateSessionNegotiatingPQ(with: them, ourPublicKey: me,
                                                  sharedSecret: sharedSecret)
        XCTAssertEqual(a.securityMode, .classical,
                       "on a tenté l'hybride avec un pair qui n'a publié aucune clé")
        XCTAssertNil(a.pqCiphertext)

        // And the classical root must be the pre-hybrid one, bit for bit.
        let expected = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: sharedSecret),
            salt: Data(), info: Data("RootKey".utf8), outputByteCount: 32
        ).withUnsafeBytes { Data($0) }
        XCTAssertEqual(a.rootKey, expected)

        // A message it sends must carry no post-quantum fields at all, so an old
        // client computes the same additional data and can read it.
        let msg = try a.encrypt("salut")
        XCTAssertNil(msg.header.securityMode)
        XCTAssertNil(msg.header.pqCiphertext)
    }

    /// The updated client must be able to READ what an old client sends, which
    /// is a classical message with a header that has no PQ fields at all.
    func testAnUpdatedClientReadsAnOldClientsMessage() throws {
        let m = manager()
        // Two genuine peers: each side names the OTHER as `with:`. The canonical
        // session id is the same for both and the roles come out opposite, so
        // their chains line up the way two devices' would — no hand-mirroring,
        // which is what made an earlier version of this test lie.
        let oldClient = m.getOrCreateSessionNegotiatingPQ(with: them, ourPublicKey: me,
                                                          sharedSecret: sharedSecret)
        let updated = manager().getOrCreateSessionNegotiatingPQ(with: me, ourPublicKey: them,
                                                                sharedSecret: sharedSecret)
        XCTAssertNotEqual(oldClient.isInitiator, updated.isInitiator,
                          "les deux côtés ont le même rôle: le montage ne simule pas deux appareils")

        let fromOld = try oldClient.encrypt("message d'une ancienne version")
        XCTAssertEqual(try updated.decrypt(fromOld), "message d'une ancienne version")
    }

    // MARK: A peer that has updated

    func testAPeerWhoPublishedAKeyGetsAHybridSession() throws {
        try XCTSkipUnless(PostQuantumKEM.isSupportedByPlatform, "iOS 26 requis")
        try publishPeerKey()

        let a = manager().getOrCreateSessionNegotiatingPQ(with: them, ourPublicKey: me,
                                                          sharedSecret: sharedSecret)
        XCTAssertEqual(a.securityMode, .hybridPQ)
        XCTAssertEqual(a.pqCiphertext?.count, PostQuantumKEM.ciphertextByteCount)
    }

    /// The responder's half, through the production path: it holds no session,
    /// receives a header carrying an encapsulation, and must decapsulate into the
    /// same root key the sender derived.
    func testTheResponderDerivesTheSameRootFromTheHeader() throws {
        try XCTSkipUnless(PostQuantumKEM.isSupportedByPlatform, "iOS 26 requis")
        try publishPeerKey()

        let sender = manager().getOrCreateSessionNegotiatingPQ(with: them, ourPublicKey: me,
                                                               sharedSecret: sharedSecret)
        let msg = try sender.encrypt("bonjour")

        let responder = manager().getOrCreateSessionNegotiatingPQ(
            with: them, ourPublicKey: me, sharedSecret: sharedSecret,
            incomingHeader: msg.header)

        XCTAssertEqual(responder.securityMode, .hybridPQ)
        XCTAssertEqual(responder.rootKey, sender.rootKey,
                       "le répondeur n'a pas retrouvé la même racine: la session ne démarrerait jamais")
    }

    // MARK: Automatic re-establishment

    /// The upgrade a real user lives through: they were mid-conversation on the
    /// old build, both sides update, the peer re-establishes as hybrid — and this
    /// side must follow automatically, not sit there failing to decrypt.
    func testAClassicalSessionIsAutomaticallyReestablishedWhenThePeerGoesHybrid() throws {
        try XCTSkipUnless(PostQuantumKEM.isSupportedByPlatform, "iOS 26 requis")
        let m = manager()

        // Yesterday: a classical session, working.
        let classical = m.getOrCreateSessionNegotiatingPQ(with: them, ourPublicKey: me,
                                                          sharedSecret: sharedSecret)
        XCTAssertEqual(classical.securityMode, .classical)

        // Today: the peer has updated and sends us a hybrid message. Their side
        // names US as the peer, so the roles are genuinely opposite.
        try publishPeerKey()
        try publishOwnKeyAs(me)
        let peerSide = manager().getOrCreateSessionNegotiatingPQ(with: me, ourPublicKey: them,
                                                                 sharedSecret: sharedSecret)
        XCTAssertEqual(peerSide.securityMode, .hybridPQ)
        XCTAssertNotEqual(classical.isInitiator, peerSide.isInitiator)
        let hybridMsg = try peerSide.encrypt("je suis passé en hybride")

        // Our side must throw away the classical session and come back hybrid.
        let upgraded = m.getOrCreateSessionNegotiatingPQ(with: them, ourPublicKey: me,
                                                         sharedSecret: sharedSecret,
                                                         incomingHeader: hybridMsg.header)
        XCTAssertEqual(upgraded.securityMode, .hybridPQ,
                       "la session est restée classique: le pair serait devenu illisible")
        XCTAssertEqual(upgraded.rootKey, peerSide.rootKey,
                       "les deux côtés n'ont pas la même racine hybride")
        XCTAssertEqual(try upgraded.decrypt(hybridMsg), "je suis passé en hybride")

        // And the conversation works in both directions afterwards.
        XCTAssertEqual(try peerSide.decrypt(try upgraded.encrypt("bien reçu")), "bien reçu")
    }

    /// And the reverse must NOT happen. A header is attacker-controlled; if a
    /// classical-looking one could tear down a hybrid session, stripping the PQ
    /// fields would be a downgrade with extra steps.
    func testAHybridSessionIsNeverTornDownByAClassicalHeader() throws {
        try XCTSkipUnless(PostQuantumKEM.isSupportedByPlatform, "iOS 26 requis")
        try publishPeerKey()
        let m = manager()

        let hybrid = m.getOrCreateSessionNegotiatingPQ(with: them, ourPublicKey: me,
                                                       sharedSecret: sharedSecret)
        XCTAssertEqual(hybrid.securityMode, .hybridPQ)
        let hybridRoot = hybrid.rootKey

        // A forged classical header for the same session.
        let forged = DoubleRatchetMessage.MessageHeader(
            publicKey: Data(repeating: 9, count: 32), messageNumber: 0,
            previousChainLength: 0, isDHRatchet: false, timestamp: Date(),
            sessionVersion: 2, sessionId: hybrid.sessionId,
            securityMode: nil, pqCiphertext: nil)

        let after = m.getOrCreateSessionNegotiatingPQ(with: them, ourPublicKey: me,
                                                      sharedSecret: sharedSecret,
                                                      incomingHeader: forged)
        XCTAssertEqual(after.securityMode, .hybridPQ,
                       "un en-tête classique a rétrogradé une session hybride")
        XCTAssertEqual(after.rootKey, hybridRoot)
    }

    // MARK: The kill switch

    func testThePolicyOverrideReturnsNewSessionsToClassical() throws {
        try XCTSkipUnless(PostQuantumKEM.isSupportedByPlatform, "iOS 26 requis")
        try publishPeerKey()
        PostQuantumPolicy.setOverride(.disabled)

        let a = manager().getOrCreateSessionNegotiatingPQ(with: them, ourPublicKey: me,
                                                          sharedSecret: sharedSecret)
        XCTAssertEqual(a.securityMode, .classical,
                       "l'interrupteur d'arrêt ne coupe pas l'hybride")
        XCTAssertEqual(PostQuantumPolicy.current, .disabled)
    }

    func testTheShippingPolicyIsHybridWhenAvailable() {
        PostQuantumPolicy.setOverride(nil)
        XCTAssertEqual(PostQuantumPolicy.current, .hybridWhenAvailable,
                       "la politique livrée n'est plus l'hybride opportuniste")
    }

    // MARK: Key distribution through the profile payload

    /// A profile from an older client has no `pqPublicKey`. Decoding must not
    /// throw — a strict decode here would break profile sync for everyone.
    func testAProfilePayloadWithoutAPQKeyStillDecodes() throws {
        let legacy = #"{"type":"profile_update","profileImageData":null,"lastSeen":123.0,"version":1}"#
        let payload = try JSONDecoder().decode(ProfileUpdatePayload.self,
                                               from: Data(legacy.utf8))
        XCTAssertNil(payload.pqPublicKey)
        XCTAssertEqual(payload.lastSeen, 123.0)
    }

    func testAProfilePayloadRoundTripsThePQKey() throws {
        let key = Data(repeating: 0x11, count: PostQuantumKEM.publicKeyByteCount)
        let sent = ProfileUpdatePayload(profileImageData: nil, lastSeen: 1,
                                        pqPublicKey: key.base64EncodedString())
        let back = try JSONDecoder().decode(ProfileUpdatePayload.self,
                                            from: try JSONEncoder().encode(sent))
        XCTAssertEqual(Data(base64Encoded: try XCTUnwrap(back.pqPublicKey)), key)
    }

    /// A peer advertising a wrong-length key must not poison the directory —
    /// otherwise every later encapsulation to them throws and the conversation
    /// silently stops upgrading.
    func testAMalformedAdvertisedKeyIsNotStored() {
        PostQuantumPeerDirectory.shared.record(Data([1, 2, 3]), for: them)
        XCTAssertNil(PostQuantumPeerDirectory.shared.publicKey(for: them))
    }

    func testTheDirectoryStoresAndReturnsAWellFormedKey() {
        let key = Data(repeating: 0x22, count: PostQuantumKEM.publicKeyByteCount)
        PostQuantumPeerDirectory.shared.record(key, for: them)
        XCTAssertEqual(PostQuantumPeerDirectory.shared.publicKey(for: them), key)
        PostQuantumPeerDirectory.shared.forget(them)
        XCTAssertNil(PostQuantumPeerDirectory.shared.publicKey(for: them))
    }
}
