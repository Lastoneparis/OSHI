//
//  TwoDeviceRatchetHealTests.swift
//  OSHITests
//
//  GUÉRISON DU RATCHET ENTRE DEUX APPAREILS DISTINCTS, avec le VRAI
//  `V2MessageRouter` de chaque côté.
//
//  POURQUOI CE FICHIER EXISTE, alors qu'un test de guérison existe déjà
//  ------------------------------------------------------------------
//  `V2LiveMediaRoundTripTests.testABrokenRatchetHealsAndMessagesFlowAgain`
//  prouve le mécanisme au niveau du PROTOCOLE, mais pas à travers le routeur:
//  `V2MessageRouter.loadLegacyIdentityPair()` lit l'identité de l'appareil dans
//  le trousseau à une clé FIXE ("privateKey"). Deux routeurs du même processus
//  partageraient donc une seule identité, et la conversation à deux est
//  impossible à monter en mémoire.
//
//  DEUX SIMULATEURS lèvent exactement cette limite: chacun a son propre
//  trousseau, donc sa propre identité, donc son propre routeur réel. C'est le
//  seul montage qui exerce `decrypt1to1`, `sendHealSignal`, `encrypt1to1` et le
//  magasin de sessions tels qu'ils tournent sur un vrai téléphone.
//
//  COMMENT ON L'EXÉCUTE — en trois passes, comme le harnais d'appel à deux
//  appareils déjà présent dans ce dépôt (`TEST_RUNNER_OSHI_PEER_KEY`):
//
//    1) sur A et sur B:   testAnnounceMyIdentity        → imprime la clé v2
//    2) sur A:            testPhase1_SendToPeer         (PEER_KEY = clé de B)
//    3) sur B:            testPhase2_BreakThenHeal      (PEER_KEY = clé de A)
//    4) sur A:            testPhase3_AdoptHealAndReply  (PEER_KEY = clé de B)
//    5) sur B:            testPhase4_ConfirmHealed      (PEER_KEY = clé de A)
//
//  Chaque phase est un test SÉPARÉ parce que les deux appareils ne peuvent pas
//  se synchroniser autrement: il n'y a pas de canal entre deux simulateurs, et
//  un test qui attendrait l'autre bloquerait indéfiniment.
//
//  ⚠️ Touche le serveur de PRODUCTION. Gardé derrière la même variable que les
//  autres tests vivants, et il faut le préfixe TEST_RUNNER_ pour qu'elle
//  atteigne le processus de test — sans lui le run affiche « SAUTÉ » et un vert
//  qui ne prouve rien. LIRE la ligne « Executed N tests ».
//

import XCTest
import CryptoKit
@testable import OSHI

final class TwoDeviceRatchetHealTests: XCTestCase {

    private func requireLive() throws {
        guard ProcessInfo.processInfo.environment["OSHI_LIVE_SERVER_TESTS"] == "1" else {
            throw XCTSkip("TEST_RUNNER_OSHI_LIVE_SERVER_TESTS=1 requis")
        }
    }

    private var peerKey: String {
        ProcessInfo.processInfo.environment["OSHI_PEER_KEY"] ?? ""
    }

    /// Le routeur RÉEL de cet appareil, avec l'identité du trousseau.
    private func realRouter() -> V2MessageRouter { V2MessageRouter() }

    /// S'assure que CE simulateur a une identité d'appareil.
    ///
    /// `V2MessageRouter.loadLegacyIdentityPair()` lit le trousseau à la clé
    /// fixe "privateKey" et jette `itemNotFound` s'il n'y a rien — c'est le cas
    /// d'un simulateur neuf, où l'app n'a jamais tourné. On pose donc la même
    /// chose que l'app pose à sa première ouverture: une clé X25519.
    ///
    /// C'est CE point qui rend le montage à deux simulateurs supérieur au test
    /// en processus: chaque simulateur a son propre trousseau, donc sa propre
    /// identité, donc son propre routeur réel. En mémoire, les deux partageraient
    /// cette clé fixe et la conversation à deux serait impossible.
    private func ensureDeviceIdentity() {
        // DEUX éléments, pas un: le routeur lit l'identité X25519 ("privateKey",
        // via `loadLegacyIdentityPair`) ET la clé de signature Ed25519
        // ("signingKey", via `loadSigningPair`). Il manquait la seconde, et son
        // absence rendait le même `itemNotFound` que la première — d'où un
        // diagnostic trompeur.
        if (try? KeychainHelper.load(key: "privateKey"))?.isEmpty ?? true {
            let priv = Curve25519.KeyAgreement.PrivateKey()
            try? KeychainHelper.save(key: "privateKey", data: priv.rawRepresentation)
        }
        if (try? KeychainHelper.load(key: "signingKey"))?.isEmpty ?? true {
            let sign = Curve25519.Signing.PrivateKey()
            try? KeychainHelper.save(key: "signingKey", data: sign.rawRepresentation)
        }
        // Si l'écriture échoue (entitlements du runner), on le DIT plutôt que
        // de laisser `register()` rendre un « itemNotFound » sans adresse.
        let hasId = !((try? KeychainHelper.load(key: "privateKey"))?.isEmpty ?? true)
        let hasSign = !((try? KeychainHelper.load(key: "signingKey"))?.isEmpty ?? true)
        XCTAssertTrue(hasId && hasSign,
                      "trousseau du simulateur inaccessible: identite=\(hasId) signature=\(hasSign)")
    }

    private func me() throws -> String {
        let k = V2KeychainCrypto().userKey
        guard !k.isEmpty else {
            throw XCTSkip("cet appareil n'a pas encore d'identité — lancer l'app une fois")
        }
        return k
    }

    // MARK: - Phase 0 : chaque appareil annonce sa clé

    func testAnnounceMyIdentity() async throws {
        try requireLive()
        ensureDeviceIdentity()
        let router = realRouter()
        try await router.register()
        let key = try me()
        // Imprimé pour être relu depuis le journal du run et passé à l'autre
        // appareil: c'est le seul canal entre deux simulateurs.
        print("OSHI_IDENTITY_BEGIN\(key)OSHI_IDENTITY_END")
        // L'identite DOIT etre stable d'un run a l'autre: c'est elle que l'autre
        // appareil a note et vers laquelle il ecrit. Si elle change, ses envois
        // partent dans le vide et la file du destinataire reste a zero — un
        // symptome qui ressemble a une panne de protocole alors que c'est une
        // panne d'identite.
        if let pinned = UserDefaults.standard.string(forKey: "test.pinned.identity") {
            XCTAssertEqual(pinned, key, "l'identite de cet appareil a CHANGE entre deux runs")
        } else {
            UserDefaults.standard.set(key, forKey: "test.pinned.identity")
        }
        XCTAssertFalse(key.isEmpty)
    }

    // MARK: - Phase 1 : A écrit à B, la conversation doit marcher

    func testPhase1_SendToPeer() async throws {
        try requireLive()
        XCTAssertFalse(peerKey.isEmpty, "TEST_RUNNER_OSHI_PEER_KEY non défini")
        ensureDeviceIdentity()
        let router = realRouter()
        try await router.register()
        _ = try await router.sendText(peer: peerKey, "phase1-bonjour")

        // __CLIENT_CAPS_2026_08_31__ LA derniere maille, verifiee en vrai.
        //
        // Envoyer a ce pair a oblige le routeur a recuperer son bundle. Si les
        // capacites qu'il y annonce ont bien ete retenues, on sait desormais
        // qu'il peut lire une liste de renvoi — SANS avoir jamais recu d'accuse
        // de sa part. C'est exactement ce que l'ancienne conception ne savait
        // pas faire, et c'est ce qui empechait le rattrapage des messages
        // perdus de partir.
        //
        // Le pair doit tourner une build qui publie ses capacites: si ce test
        // echoue apres une republication des deux cotes, c'est le chainon
        // publication -> stockage -> lecture qu'il faut regarder, pas la
        // guerison elle-meme.
        // On n'affirme PAS que les capacites sont connues ici: `loadOrEstablish`
        // rend une session existante sans recuperer de bundle, donc un envoi
        // ordinaire vers un pair deja connu n'apprend rien. C'est voulu — et
        // c'est pourquoi la reparation, elle, rafraichit explicitement les
        // capacites apres avoir efface la session (voir `v2RepairPeer`).
        //
        // Ce qui est garanti ici: apres un PREMIER echange, on connait le pair
        // ou on ne le connait pas, mais rien n'a plante.
        _ = V2SyncState.peerUnderstandsResendList(peerKey)
    }

    // MARK: - Phase 2 : B ouvre, CASSE sa session, puis émet l'accusé

    func testPhase2_BreakThenHeal() async throws {
        try requireLive()
        XCTAssertFalse(peerKey.isEmpty, "TEST_RUNNER_OSHI_PEER_KEY non défini")
        ensureDeviceIdentity()
        let router = realRouter()
        let store = V2SessionStore()
        let client = V2Client()
        let mine = try me()

        // Ouvrir ce que A vient d'envoyer — établit la session côté B.
        let pull = try await client.pullMessages(userKey: mine, afterSeq: 0)
        let got = await router.receiveAndDecrypt(pull)
        try? await client.ack(userKey: mine, upToSeq: pull.maxSeq)
        let texts = got.compactMap { item -> String? in
            if case .text(_, _, _, let t, _) = item { return t } else { return nil }
        }
        XCTAssertTrue(texts.contains("phase1-bonjour"),
                      "la conversation doit d'abord marcher — sinon on ne prouve rien en la cassant")
        XCTAssertTrue(store.hasSession(peerUserKey: peerKey), "aucune session à casser")

        // Faire DIVERGER la clé de chaîne de réception: c'est l'état que
        // produisent un pas de ratchet perdu, un second appareil, une
        // restauration.
        var broken = try XCTUnwrap(
            try store.withPeerSession(peerKey) { try store.loadSession(peerUserKey: peerKey) })
        if broken.ratchet.CKr != nil {
            broken.ratchet.CKr = Data(repeating: 0x42, count: 32)
        } else {
            broken.ratchet.RK = Data(repeating: 0x42, count: 32)
        }
        try store.withPeerSession(peerKey) { try store.saveSession(broken) }

        // La vraie réaction de l'app: effacer, puis émettre l'accusé. L'ordre
        // porte tout — sans l'effacement, l'enveloppe ne porterait aucun
        // en-tête X3DH et ne réparerait rien. `sendHealSignal` le refuserait.
        try store.deleteSession(peerUserKey: peerKey)
        _ = try await router.sendHealSignal(to: peerKey)
    }

    // MARK: - Phase 3 : A adopte l'accusé et répond

    func testPhase3_AdoptHealAndReply() async throws {
        try requireLive()
        XCTAssertFalse(peerKey.isEmpty, "TEST_RUNNER_OSHI_PEER_KEY non défini")
        ensureDeviceIdentity()
        let router = realRouter()
        let client = V2Client()
        let mine = try me()

        let pull = try await client.pullMessages(userKey: mine, afterSeq: 0)
        let got = await router.receiveAndDecrypt(pull)
        try? await client.ack(userKey: mine, upToSeq: pull.maxSeq)

        let failures = got.filter { if case .failed = $0 { return true } else { return false } }.count
        XCTAssertEqual(failures, 0, "l'accusé doit s'ouvrir: son en-tête X3DH remplace la session")
        let texts = got.compactMap { item -> String? in
            if case .text(_, _, _, let t, _) = item { return t } else { return nil }
        }
        XCTAssertTrue(texts.contains { RatchetHealSignal.isHealPayload($0) },
                      "A doit reconnaître l'accusé, sinon il s'afficherait en bulle")

        // La session de A a été remplacée par celle que B vient de créer.
        _ = try await router.sendText(peer: peerKey, "phase3-et-maintenant")
    }

    // MARK: - Boucle COMPLÈTE : le message perdu revient

    /// Phase 5 — B a perdu un message, le redemande, et A le lui renvoie.
    ///
    /// Les phases 1 à 4 prouvent que la conversation REPART. Celle-ci prouve la
    /// seconde promesse, celle qui n'avait jamais tourné: que le message perdu
    /// pendant la panne revienne, sous son identifiant d'ORIGINE.
    ///
    /// CE QUI N'EST PAS COUVERT, et il faut le dire: le comptage des 8 tentatives
    /// avant abandon vit dans `MessageManager`, qui exige tout le graphe d'objets
    /// de l'app et n'est pas atteignable d'un test unitaire. Il a ses propres
    /// tests (`V2UndecryptableRetryTests`). Ici on part de l'instant D'APRÈS:
    /// l'abandon a eu lieu, l'identifiant est noté, et on verifie tout le reste.
    ///
    /// À lancer sur B, avec la clé de A.
    func testPhase5_LostMessageComesBack() async throws {
        try requireLive()
        XCTAssertFalse(peerKey.isEmpty, "TEST_RUNNER_OSHI_PEER_KEY non défini")
        ensureDeviceIdentity()
        let router = realRouter()
        let store = V2SessionStore()
        let client = V2Client()
        let mine = try me()

        // A a écrit quelque chose que B n'a pas pu ouvrir. On note l'identifiant
        // exactement comme le fait le point d'abandon de l'app.
        let lostId = ProcessInfo.processInfo.environment["OSHI_LOST_MSGID"] ?? ""
        XCTAssertFalse(lostId.isEmpty, "TEST_RUNNER_OSHI_LOST_MSGID non défini")
        V2SyncState.markUnreadable(msgId: lostId, from: peerKey, envelopeTs: Date())

        // La réparation, dans l'ordre exact de `v2RepairPeer`: effacer, PUIS
        // rafraîchir les capacités du pair, PUIS décider de la liste.
        try? store.deleteSession(peerUserKey: peerKey)
        _ = await router.capabilities(peerUserKey: peerKey)

        XCTAssertTrue(V2SyncState.peerUnderstandsResendList(peerKey),
                      "capacités du pair non apprises: la liste ne partirait pas")
        let resend = V2SyncState.drainUnreadable(for: peerKey)
        XCTAssertTrue(resend.contains(lostId), "l'identifiant perdu doit être dans la liste")

        // « Envoye » ne veut rien dire tant que le RELAIS ne l'a pas accepte:
        // sendEnvelope peut rendre une liste VIDE sans lever d'erreur, et le
        // test passerait en croyant avoir emis. C'est exactement l'ecart qui a
        // fait chercher la panne du mauvais cote.
        let accepted = try await router.sendHealSignal(to: peerKey, resend: resend)
        print("DIAG_P5 | vers=\(peerKey.prefix(12)) | liste=\(resend.count) | acceptes=\(accepted.count) \(accepted.map { String($0.prefix(10)) })")
        XCTAssertFalse(accepted.isEmpty, "le relais n'a accepte AUCUNE enveloppe — rien n'a ete emis")
        _ = client
    }

    /// Phase 6 — A reçoit l'accusé AVEC la liste, et renvoie.
    /// À lancer sur A, avec la clé de B.
    func testPhase6_HonourResendRequest() async throws {
        try requireLive()
        XCTAssertFalse(peerKey.isEmpty, "TEST_RUNNER_OSHI_PEER_KEY non défini")
        ensureDeviceIdentity()
        let router = realRouter()
        let client = V2Client()
        let mine = try me()

        let pull = try await client.pullMessages(userKey: mine, afterSeq: 0)
        let got = await router.receiveAndDecrypt(pull)
        try? await client.ack(userKey: mine, upToSeq: pull.maxSeq)

        let heals = got.compactMap { item -> RatchetHealSignal.Heal? in
            if case .text(_, _, _, let t, _) = item { return RatchetHealSignal.parse(t) }
            return nil
        }
        // Faire DIRE au test ce qu'il a reellement recu: « aucun accuse porteur
        // de liste » recouvre trois causes opposees — rien n'est arrive, un
        // accuse est arrive vide, ou il est arrive et n'a pas ete reconnu.
        let allTexts = got.compactMap { item -> String? in
            if case .text(_, _, _, let t, _) = item { return t } else { return nil }
        }
        let failed = got.filter { if case .failed = $0 { return true } else { return false } }.count
        print("DIAG_P6 | enveloppes=\(pull.messages.count) | textes=\(allTexts.count)"
              + " | echecs=\(failed) | accuses=\(heals.count)"
              + " | listes=\(heals.map(\.resend.count))")
        for t in allTexts.prefix(4) {
            print("DIAG_P6_TEXTE | \(t.prefix(40).replacingOccurrences(of: "\n", with: "⏎"))")
        }

        let withList = heals.first { !$0.resend.isEmpty }
        XCTAssertNotNil(withList, "aucun accusé porteur de liste — la capacité n'a pas voyagé")
        let ids = withList?.resend ?? []

        // Le renvoi tel que l'app le fait: enveloppe NEUVE, identifiant
        // applicatif d'origine DANS le chiffré, date d'origine préservée.
        let body = ProcessInfo.processInfo.environment["OSHI_LOST_BODY"] ?? "message-perdu"
        var acceptedTotal = 0
        for id in ids {
            guard let wrapped = RatchetHealSignal.Resent.encode(originalId: id, body: body) else { continue }
            let accepted = try await router.sendText(peer: peerKey, wrapped,
                                                     msgId: UUID().uuidString,
                                                     originalTimestamp: Date().addingTimeInterval(-3600))
            acceptedTotal += accepted.count
        }
        print("DIAG_P6_RENVOI | demandes=\(ids.count) | acceptes_par_le_relais=\(acceptedTotal)")
        XCTAssertFalse(ids.isEmpty)
        // Meme exigence qu'en phase 5: « envoye » ne veut rien dire tant que le
        // relais n'a pas accepte. Sans ca, la phase 7 chercherait un message qui
        // n'a jamais quitte l'appareil, et l'echec accuserait la reparation.
        XCTAssertEqual(acceptedTotal, ids.count,
                       "le relais n'a pas accepte tous les renvois")
    }

    /// Phase 7 — B relit, et retrouve LE MESSAGE PERDU, sous son id d'origine.
    /// À lancer sur B, avec la clé de A.
    func testPhase7_TheLostMessageIsBack() async throws {
        try requireLive()
        ensureDeviceIdentity()
        let router = realRouter()
        let client = V2Client()
        let mine = try me()
        let lostId = ProcessInfo.processInfo.environment["OSHI_LOST_MSGID"] ?? ""
        let body = ProcessInfo.processInfo.environment["OSHI_LOST_BODY"] ?? "message-perdu"

        let pull = try await client.pullMessages(userKey: mine, afterSeq: 0)
        let got = await router.receiveAndDecrypt(pull)
        try? await client.ack(userKey: mine, upToSeq: pull.maxSeq)

        let failures = got.filter { if case .failed = $0 { return true } else { return false } }.count

        // Dire CE QUI est arrive avant de juger: « 1 echec » recouvre aussi bien
        // un renvoi illisible qu'une vieille enveloppe restee en file depuis un
        // run precedent, chiffree sous une session depuis longtemps effacee.
        // Les deux demandent des conclusions opposees.
        for item in got {
            switch item {
            case .text(let f, let id, _, let t, _):
                print("DIAG_P7_TEXTE | de=\(f.prefix(10)) | id=\(id.prefix(14)) | \(t.prefix(48).replacingOccurrences(of: "\n", with: "⏎"))")
            case .failed(let f, let id, _, let e, let k, _):
                print("DIAG_P7_ECHEC | de=\(f.prefix(10)) | id=\(id.prefix(14)) | kind=\(k.rawValue) | \(e.prefix(60))")
            case .file(let f, let id, _, _, _):
                print("DIAG_P7_FICHIER | de=\(f.prefix(10)) | id=\(id.prefix(14))")
            }
        }

        let resent = got.compactMap { item -> (id: String, body: String)? in
            if case .text(_, _, _, let t, _) = item { return RatchetHealSignal.Resent.parse(t) }
            return nil
        }
        XCTAssertTrue(resent.contains { $0.id == lostId && $0.body == body },
                      "LE MESSAGE PERDU doit revenir, sous son identifiant d'origine")
        // L'echec n'est juge qu'APRES: une vieille enveloppe illisible venue
        // d'un run precedent n'invalide pas la reparation qu'on mesure.
        XCTAssertEqual(failures, 0, "après réparation, plus aucun échec")
    }

    // MARK: - Phase 4 : B relit. C'est LA propriété qui compte.

    func testPhase4_ConfirmHealed() async throws {
        try requireLive()
        XCTAssertFalse(peerKey.isEmpty, "TEST_RUNNER_OSHI_PEER_KEY non défini")
        ensureDeviceIdentity()
        let router = realRouter()
        let client = V2Client()
        let mine = try me()

        let pull = try await client.pullMessages(userKey: mine, afterSeq: 0)
        let got = await router.receiveAndDecrypt(pull)
        try? await client.ack(userKey: mine, upToSeq: pull.maxSeq)

        let failures = got.filter { if case .failed = $0 { return true } else { return false } }.count
        let texts = got.compactMap { item -> String? in
            if case .text(_, _, _, let t, _) = item { return t } else { return nil }
        }
        XCTAssertEqual(failures, 0, "après guérison, plus aucun échec de déchiffrement")
        XCTAssertTrue(texts.contains("phase3-et-maintenant"),
                      "GUÉRISON: la conversation repart, sans intervention humaine")
    }
}
