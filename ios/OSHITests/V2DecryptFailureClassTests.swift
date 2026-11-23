//
//  V2DecryptFailureClassTests.swift
//  OSHI — réagir à une session morte doit se décider sur une PREUVE, pas sur
//  un compteur de tics.
//
//  __V2_FAILURE_IS_TYPED_2026_08_28__ Le budget uniforme de 8 ne mesurait pas
//  ce qu'il croyait: le ratchet gère DÉJÀ le hors-séquence (MKSKIPPED persisté,
//  `skipMessageKeys` jusqu'à `maxSkip`), donc réessayer les MÊMES octets contre
//  le MÊME état est un calcul DÉTERMINISTE. 8 tics n'apportent aucune
//  information de plus que 1 — ils mesurent des tics (1 Hz en avant-plan), pas
//  des preuves.
//
//  Deux propriétés opposées doivent tenir ENSEMBLE, et c'est pour ça qu'elles
//  ont besoin de tests plutôt que d'une lecture attentive:
//
//    * réagir VITE là où l'effacement ne peut rien détruire;
//    * et ne JAMAIS réagir sur une signature ambiguë — un effacement à tort
//      détruit tout ce que le pair écrit sous l'ancienne session jusqu'à ce
//      qu'il ait tiré notre accusé.
//

import XCTest
@testable import OSHI

final class V2DecryptFailureClassTests: XCTestCase {

    private let peer = "PEER_A"
    private let other = "PEER_B"

    private func reset() {
        UserDefaults.standard.removeObject(forKey: V2SyncState.breakEvidenceKey)
        UserDefaults.standard.removeObject(forKey: V2SyncState.retryKey)
        UserDefaults.standard.removeObject(forKey: V2SyncState.cursorKey)
    }

    override func setUp() { super.setUp(); reset() }
    override func tearDown() { reset(); super.tearDown() }

    // MARK: 1. La taxonomie se calcule là où l'Error TYPÉE existe

    /// Un cas d'assertion par cas DÉCLARÉ de `RouterError`. La liste se
    /// périmerait sinon à la première erreur ajoutée — et le `switch` du
    /// classifieur est exhaustif SANS `default` précisément pour que le
    /// compilateur refuse un cas non classé.
    func testEveryDeclaredRouterErrorIsClassified() {
        XCTAssertEqual(V2DecryptFailureKind.classify(
            V2MessageRouter.RouterError.noSessionForDecrypt(peer: peer)), .noSession)
        XCTAssertEqual(V2DecryptFailureKind.classify(
            V2MessageRouter.RouterError.missingX3DHForNewSession(peer: peer)), .noSession)
        XCTAssertEqual(V2DecryptFailureKind.classify(
            V2MessageRouter.RouterError.ratchetBreak(peer: peer, underlying: "AEAD")), .ratchetBreak)
        XCTAssertEqual(V2DecryptFailureKind.classify(
            V2MessageRouter.RouterError.unknownOneTimePreKey(peer: peer, keyId: "7")), .unusablePreKey)
        XCTAssertEqual(V2DecryptFailureKind.classify(
            V2MessageRouter.RouterError.bundleIdentityMismatch(peer: peer)), .spoofedIdentity)
        XCTAssertEqual(V2DecryptFailureKind.classify(
            V2MessageRouter.RouterError.badSignedPreKeySignature(peer: peer)), .spoofedIdentity)
        XCTAssertEqual(V2DecryptFailureKind.classify(
            V2MessageRouter.RouterError.malformedHeader), .malformedEnvelope)
        XCTAssertEqual(V2DecryptFailureKind.classify(
            V2MessageRouter.RouterError.malformedFileKey), .malformedEnvelope)
        XCTAssertEqual(V2DecryptFailureKind.classify(
            V2MessageRouter.RouterError.malformedBundleKey), .malformedEnvelope)

        // Ces deux-là sont levées par le chemin d'ÉMISSION et ne peuvent pas
        // remonter en `.failed`. `.transient` est la seule réponse honnête si
        // elles y arrivaient un jour: surtout pas un effacement de session.
        XCTAssertEqual(V2DecryptFailureKind.classify(
            V2MessageRouter.RouterError.healWithLiveSession(peer: peer)), .transient)
        XCTAssertEqual(V2DecryptFailureKind.classify(
            V2MessageRouter.RouterError.sessionVanishedUnderLock(peer: peer)), .transient)
    }

    func testCryptoErrorsAreClassified() {
        XCTAssertEqual(V2DecryptFailureKind.classify(
            OSHICryptoV2.CryptoError.tooManySkippedMessages), .gapTooLarge)
        XCTAssertEqual(V2DecryptFailureKind.classify(
            OSHICryptoV2.CryptoError.ciphertextTooShort), .malformedEnvelope)
    }

    /// Une erreur d'un genre non prévu doit retomber en `.transient` — donc
    /// hériter du budget long et de l'ancien filet, jamais déclencher une
    /// réaction immédiate.
    func testUnknownErrorFallsBackToTransient() {
        struct Whatever: Error {}
        XCTAssertEqual(V2DecryptFailureKind.classify(Whatever()), .transient)
        XCTAssertEqual(V2DecryptFailureKind.classify(URLError(.timedOut)), .transient)
    }

    // MARK: 2. La preuve de casse — immédiate seulement là où c'est conclusif

    /// `noSession`: le magasin n'a AUCUN enregistrement, donc `deleteSession`
    /// est un no-op et l'accusé ne peut détruire aucun message en vol.
    /// Attendre n'ajoutera jamais ni session ni en-tête.
    func testConclusiveKindsProveOnTheFirstEnvelope() {
        XCTAssertTrue(V2SyncState.breakEvidence(peer: peer, kind: .noSession, msgId: "m1"))
        reset()
        XCTAssertTrue(V2SyncState.breakEvidence(peer: peer, kind: .gapTooLarge, msgId: "m1"))
        reset()
        XCTAssertTrue(V2SyncState.breakEvidence(peer: peer, kind: .unusablePreKey, msgId: "m1"))
    }

    /// La SEULE classe ambiguë. Une enveloppe isolée peut être corrompue en
    /// propre; il faut une SECONDE, chiffrée indépendamment sous la même
    /// session, pour éliminer cette hypothèse.
    func testRatchetBreakNeedsTwoDistinctEnvelopes() {
        XCTAssertFalse(V2SyncState.breakEvidence(peer: peer, kind: .ratchetBreak, msgId: "A"))
        XCTAssertTrue(V2SyncState.breakEvidence(peer: peer, kind: .ratchetBreak, msgId: "B"))
    }

    /// Le variant temporel: la même enveloppe qui échoue tic après tic. 3 tics
    /// ≈ 3 s en avant-plan (le timer de relève est à 1 s), ce qui couvre avec
    /// marge la seule course réelle restante.
    func testRatchetBreakOnASingleEnvelopeNeedsThreeTicks() {
        XCTAssertFalse(V2SyncState.breakEvidence(peer: peer, kind: .ratchetBreak, msgId: "A"))
        XCTAssertFalse(V2SyncState.breakEvidence(peer: peer, kind: .ratchetBreak, msgId: "A"))
        XCTAssertTrue(V2SyncState.breakEvidence(peer: peer, kind: .ratchetBreak, msgId: "A"))
    }

    /// Un déchiffrement RÉUSSI est la preuve DIRECTE que la session est
    /// vivante: l'enveloppe fautive était un défaut isolé.
    func testASuccessfulDecryptWipesTheEvidence() {
        XCTAssertFalse(V2SyncState.breakEvidence(peer: peer, kind: .ratchetBreak, msgId: "A"))
        V2SyncState.clearBreakEvidence(peer: peer)
        XCTAssertFalse(V2SyncState.breakEvidence(peer: peer, kind: .ratchetBreak, msgId: "B"),
                       "après un message lu, une seule enveloppe cassée ne prouve plus rien")
    }

    /// La preuve est PAR PAIR. Deux pairs qui cassent une fois chacun ne font
    /// pas une casse.
    func testEvidenceIsPerPeer() {
        XCTAssertFalse(V2SyncState.breakEvidence(peer: peer, kind: .ratchetBreak, msgId: "A"))
        XCTAssertFalse(V2SyncState.breakEvidence(peer: other, kind: .ratchetBreak, msgId: "B"))
    }

    /// L'attaque qu'on ne veut pas offrir: des octets abîmés, ou une identité
    /// qui ne colle pas, ne prouvent RIEN sur la session — et effacer sur
    /// cette base donnerait à n'importe qui un moyen de couper une
    /// conversation.
    func testMalformedAndSpoofedNeverProveABreak() {
        for _ in 0..<10 {
            XCTAssertFalse(V2SyncState.breakEvidence(peer: peer, kind: .malformedEnvelope, msgId: "A"))
            XCTAssertFalse(V2SyncState.breakEvidence(peer: peer, kind: .malformedEnvelope, msgId: UUID().uuidString))
            XCTAssertFalse(V2SyncState.breakEvidence(peer: peer, kind: .spoofedIdentity, msgId: UUID().uuidString))
        }
    }

    /// `.transient` n'apporte aucune preuve: une casse d'un genre non prévu
    /// reste rattrapée par l'ancien filet (budget épuisé), pas par une
    /// réaction immédiate.
    func testTransientNeverProvesABreak() {
        for _ in 0..<10 {
            XCTAssertFalse(V2SyncState.breakEvidence(peer: peer, kind: .transient, msgId: UUID().uuidString))
        }
    }

    // MARK: 3. Le budget d'ACK reste généreux — sauf là où l'octet ne bougera pas

    /// Retenir l'ack ne retarde AUCUNE livraison et le relais garde 30 jours:
    /// il n'y a aucune raison d'échanger cette sûreté contre de la vitesse.
    func testAckBudgetIsUnchangedForOrdinaryFailures() {
        let id = UUID().uuidString
        for _ in 1...V2SyncState.maxRetries {
            XCTAssertTrue(V2SyncState.shouldRetryUndecryptable(id, kind: .ratchetBreak))
        }
        XCTAssertFalse(V2SyncState.shouldRetryUndecryptable(id, kind: .ratchetBreak))
    }

    /// Les deux exceptions: des octets malformés ou une identité usurpée ne
    /// changeront pas au re-pull, et les retenir ne fait que geler le curseur.
    func testAckBudgetIsOneForBytesThatWillNotChange() {
        let a = UUID().uuidString
        XCTAssertTrue(V2SyncState.shouldRetryUndecryptable(a, kind: .malformedEnvelope))
        XCTAssertFalse(V2SyncState.shouldRetryUndecryptable(a, kind: .malformedEnvelope))

        let b = UUID().uuidString
        XCTAssertTrue(V2SyncState.shouldRetryUndecryptable(b, kind: .spoofedIdentity))
        XCTAssertFalse(V2SyncState.shouldRetryUndecryptable(b, kind: .spoofedIdentity))
    }

    // MARK: 4. Le hors-séquence n'est PAS une casse
    //
    // C'est le socle de tout le raisonnement: si le ratchet ne gérait pas déjà
    // le désordre, réagir vite serait dangereux.

    /// Un message n+5 puis un message n+1 passent tous les deux, via MKSKIPPED
    /// — donc aucun des deux ne produit de `.failed`, donc aucune preuve de
    /// casse. Et l'état survit à l'aller-retour de sérialisation, sinon la
    /// propriété serait perdue au premier relancement.
    func testOutOfOrderDecryptionRaisesNothing() throws {
        let (alice, bob) = try Self.pairedRatchets()
        let ad = Data("ad".utf8)

        let (h1, c1) = try OSHICryptoV2.ratchetEncrypt(alice, plaintext: Data("un".utf8), associatedData: ad)
        let (h2, c2) = try OSHICryptoV2.ratchetEncrypt(alice, plaintext: Data("deux".utf8), associatedData: ad)
        let (h3, c3) = try OSHICryptoV2.ratchetEncrypt(alice, plaintext: Data("trois".utf8), associatedData: ad)

        // Le dernier arrive en premier.
        XCTAssertEqual(try OSHICryptoV2.ratchetDecrypt(bob, header: h3, ciphertext: c3, associatedData: ad),
                       Data("trois".utf8))

        // MKSKIPPED doit survivre à la persistance.
        let round = V2SessionStore.SerializedRatchet(bob).toRatchetState()

        XCTAssertEqual(try OSHICryptoV2.ratchetDecrypt(round, header: h1, ciphertext: c1, associatedData: ad),
                       Data("un".utf8))
        XCTAssertEqual(try OSHICryptoV2.ratchetDecrypt(round, header: h2, ciphertext: c2, associatedData: ad),
                       Data("deux".utf8))
    }

    /// `gapTooLarge` est classé CONCLUSIF: rien ne se répare avec le temps.
    /// Encore faut-il que l'échec laisse l'état INTACT, sinon une réaction
    /// immédiate agirait sur un ratchet qu'on vient soi-même d'abîmer.
    /// (`__RATCHET_STATE_IS_TRANSACTIONAL_2026_08_18__`)
    func testAGapPastMaxSkipThrowsAndLeavesTheStateUntouched() throws {
        let (alice, bob) = try Self.pairedRatchets()
        let ad = Data("ad".utf8)

        // Un premier message ordinaire, pour que Bob ait une chaîne de
        // réception: sans ça `skipMessageKeys` sort avant même de compter.
        let (h0, c0) = try OSHICryptoV2.ratchetEncrypt(alice, plaintext: Data("zéro".utf8), associatedData: ad)
        _ = try OSHICryptoV2.ratchetDecrypt(bob, header: h0, ciphertext: c0, associatedData: ad)

        for _ in 0...OSHICryptoV2.maxSkip {
            _ = try OSHICryptoV2.ratchetEncrypt(alice, plaintext: Data("saut".utf8), associatedData: ad)
        }
        let (hFar, cFar) = try OSHICryptoV2.ratchetEncrypt(alice, plaintext: Data("loin".utf8), associatedData: ad)

        let before = V2SessionStore.SerializedRatchet(bob)
        XCTAssertThrowsError(try OSHICryptoV2.ratchetDecrypt(bob, header: hFar, ciphertext: cFar, associatedData: ad)) { error in
            XCTAssertEqual(V2DecryptFailureKind.classify(error), .gapTooLarge)
        }
        XCTAssertEqual(V2SessionStore.SerializedRatchet(bob), before,
                       "un échec ne doit RIEN laisser derrière lui: Nr, CKr et MKSKIPPED identiques avant/après")
    }

    // MARK: __REPLAY_IS_NOT_A_DESYNC_2026_10_14__
    //
    // Le MÊME principe que ce fichier défend pour la v2 — « ne JAMAIS réagir sur
    // une signature ambiguë » — appliqué au ratchet hérité, qui ne l'avait pas.
    //
    // Signalé le 14/10 (Huawei nova 5T ↔ iPad 9): « la distribution des messages
    // marche une fois sur deux ». Android→iOS perdait tout sauf le message qui
    // ouvrait une chaîne. Un doublon — banal sur un maillage qui inonde, et
    // invisible aux deux dédoublonnages puisque chaque envoi tire un identifiant
    // de trame NEUF — arrivait jusqu'au ratchet, qui le refusait correctement;
    // c'est la couche au-dessus qui en concluait « session morte » et l'effaçait.

    /// La propriété: un rejeu ne prouve RIEN sur la session, donc il ne doit
    /// jamais pouvoir l'effacer.
    func testAReplayedEnvelopeNeverJustifiesErasingTheSession() {
        XCTAssertFalse(MessageManager.ratchetFailureProvesSessionIsBroken(.invalidMessageNumber),
                       "un rejeu détruisait une session SAINE: c'est le bug du 14/10")
        XCTAssertFalse(MessageManager.ratchetFailureProvesSessionIsBroken(.oldMessage))
    }

    /// Et l'inverse doit tenir avec: une vraie désynchronisation doit encore
    /// déclencher la reprise, sinon on échange un bug contre l'autre.
    func testARealDesyncStillJustifiesTheReset() {
        for error: DoubleRatchetError in [.sessionMismatch, .versionMismatch,
                                          .tooManySkippedMessages, .uninitializedSendingChain,
                                          .decryptionFailed, .encryptionFailed,
                                          .invalidPublicKey, .staleSession] {
            XCTAssertTrue(MessageManager.ratchetFailureProvesSessionIsBroken(error),
                          "\(error) doit encore réparer la session")
        }
    }

    /// LA preuve de bout en bout, sur de vrais ratchets: une session qui vient de
    /// recevoir un doublon doit continuer à lire la SUITE de la conversation.
    /// C'est exactement ce que le message 3 de la capture ne faisait plus.
    func testASessionKeepsReadingTheConversationAfterADuplicate() throws {
        let secret = Data(repeating: 0x5A, count: 32)
        let a = "ALICEreplayALICEreplayALICEreplayALIC1a="
        let b = "BOBreplayBOBreplayBOBreplayBOBreplaybo2b="
        let ma = DoubleRatchetSessionManager(storageKey: "test-replay-\(UUID().uuidString)"); ma.resetAllSessions()
        let mb = DoubleRatchetSessionManager(storageKey: "test-replay-\(UUID().uuidString)"); mb.resetAllSessions()
        let alice = ma.getOrCreateSession(with: b, ourPublicKey: a, sharedSecret: secret, ratcheting: true)
        let bob = mb.getOrCreateSession(with: a, ourPublicKey: b, sharedSecret: secret, ratcheting: true)

        // Le ping-pong de la capture: 1 arrive, la réponse tourne le ratchet.
        let m1 = try alice.encrypt("Message 1")
        XCTAssertEqual(try bob.decrypt(m1), "Message 1")
        XCTAssertEqual(try alice.decrypt(try bob.encrypt("Message 2")), "Message 2")

        // Le doublon du message 1, que le maillage livre une seconde fois.
        XCTAssertThrowsError(try bob.decrypt(m1)) { error in
            XCTAssertEqual(error as? DoubleRatchetError, .invalidMessageNumber,
                           "un rejeu doit se présenter comme tel, c'est ce qui le rend reconnaissable")
            XCTAssertFalse(MessageManager.ratchetFailureProvesSessionIsBroken(error as! DoubleRatchetError))
        }

        // Et la conversation continue — la session n'a rien perdu.
        XCTAssertEqual(try bob.decrypt(try alice.encrypt("Message 3")), "Message 3")
        XCTAssertEqual(try alice.decrypt(try bob.encrypt("Message 4")), "Message 4")
        XCTAssertEqual(try bob.decrypt(try alice.encrypt("Message 5")), "Message 5")
    }

    /// Deux ratchets appariés, montés à la main comme le fait
    /// `establishResponderSession`: Alice émet, Bob reçoit.
    private static func pairedRatchets() throws -> (OSHICryptoV2.RatchetState, OSHICryptoV2.RatchetState) {
        var sk = Data(count: 32)
        for i in 0..<32 { sk[i] = UInt8(i) }
        let bobPair = OSHICryptoV2.generateX25519()
        let alice = try OSHICryptoV2.ratchetInitAlice(SK: sk, bobRatchetPub: bobPair.pub)
        let bob = OSHICryptoV2.ratchetInitBob(SK: sk, bobRatchetPair: bobPair)
        return (alice, bob)
    }
}
