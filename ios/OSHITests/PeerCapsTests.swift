//
//  PeerCapsTests.swift
//  OSHITests — __CLIENT_CAPS_2026_08_31__
//
//  LE DÉFAUT QUE CES TESTS GARDENT
//  -------------------------------
//  La première version apprenait qu'un pair savait lire une liste de renvoi
//  UNIQUEMENT en recevant un accusé de réparation de sa part. Vérifié dans le
//  code avant correction: `markPeerUnderstandsResendList` n'avait qu'UN seul
//  appelant, dans la branche de réception d'accusé.
//
//  Conséquence, et elle est fatale au rattrapage des messages perdus: pour
//  redemander mes messages, je dois savoir que TU es capable — mais je ne
//  l'apprends que le jour où TA session casse. Si c'est toujours mon côté qui
//  casse, la liste ne part JAMAIS, et mes identifiants expirent au bout de
//  sept jours sans avoir servi.
//
//  La capacité voyage donc maintenant dans le BUNDLE DE PREKEYS — le seul
//  endroit que les deux côtés consultent avant de s'écrire. Le test central
//  ci-dessous est `testCapabilityIsKnownWithoutEverReceivingAHeal`: c'est la
//  propriété qui manquait, et rien d'autre ne la garde.
//

import XCTest
@testable import OSHI

final class PeerCapsTests: XCTestCase {

    private let peer = "test-peer-caps-\(UUID().uuidString)"

    override func tearDown() {
        // Ces entrées vivent dans UserDefaults et survivraient au processus.
        var m = (UserDefaults.standard.dictionary(forKey: "oshi_v2_peer_caps") as? [String: Int]) ?? [:]
        m.removeValue(forKey: peer)
        UserDefaults.standard.set(m, forKey: "oshi_v2_peer_caps")
        var l = UserDefaults.standard.stringArray(forKey: V2SyncState.peerResendCapableKey) ?? []
        l.removeAll { $0 == peer }
        UserDefaults.standard.set(l, forKey: V2SyncState.peerResendCapableKey)
        super.tearDown()
    }

    // MARK: - LA propriété qui manquait

    /// Sans avoir JAMAIS reçu d'accusé de ce pair, on doit savoir qu'il sait
    /// lire une liste — parce que son bundle le dit. C'est exactement le cas où
    /// l'ancienne conception échouait.
    func testCapabilityIsKnownWithoutEverReceivingAHeal() {
        XCTAssertFalse(V2SyncState.peerUnderstandsResendList(peer),
                       "état de départ: rien de connu sur ce pair")

        RatchetHealSignal.Caps.remember(peer: peer, caps: RatchetHealSignal.Caps.resendList)

        XCTAssertTrue(V2SyncState.peerUnderstandsResendList(peer),
                      "le bundle suffit: aucun accusé n'a été reçu de ce pair")
    }

    /// L'ancienne source reste valable: un pair dont le bundle est antérieur à
    /// sa mise à jour (publié sans `caps`, donc lu 0) mais dont l'accusé prouve
    /// qu'il sait lire, doit rester servi.
    func testHealProofStillWorksWhenTheBundleIsStale() {
        RatchetHealSignal.Caps.remember(peer: peer, caps: 0)
        XCTAssertFalse(V2SyncState.peerUnderstandsResendList(peer))

        V2SyncState.markPeerUnderstandsResendList(peer)
        XCTAssertTrue(V2SyncState.peerUnderstandsResendList(peer),
                      "la preuve par accusé reçu doit rester une source valable")
    }

    /// LE faux positif qui coûterait cher: croire capable un pair qui ne l'est
    /// pas lui enverrait du JSON brut en pleine conversation.
    func testAPeerAnnouncingNothingIsNotCapable() {
        RatchetHealSignal.Caps.remember(peer: peer, caps: 0)
        XCTAssertFalse(V2SyncState.peerUnderstandsResendList(peer))
    }

    /// Une capacité FUTURE qu'on ne connaît pas ne doit pas être lue comme
    /// celle-ci. Les drapeaux sont indépendants, sinon ajouter une capacité en
    /// activerait d'autres par accident.
    func testAnUnrelatedFutureFlagDoesNotImplyThisOne() {
        RatchetHealSignal.Caps.remember(peer: peer, caps: 1 << 7)
        XCTAssertFalse(V2SyncState.peerUnderstandsResendList(peer),
                       "un drapeau inconnu ne doit rien activer d'autre")
    }

    /// Un pair qui se met à jour doit cesser d'être vu comme ancien.
    func testRememberingNewCapsReplacesTheOldValue() {
        RatchetHealSignal.Caps.remember(peer: peer, caps: 0)
        XCTAssertEqual(RatchetHealSignal.Caps.of(peer: peer), 0)
        RatchetHealSignal.Caps.remember(peer: peer, caps: RatchetHealSignal.Caps.resendList)
        XCTAssertEqual(RatchetHealSignal.Caps.of(peer: peer), RatchetHealSignal.Caps.resendList)
    }

    // MARK: - Rétro-compatibilité du fil

    /// LE test qui protège la messagerie: un bundle publié AVANT ce changement
    /// ne porte pas `caps`. S'il ne se décodait pas, TOUT établissement de
    /// session avec ce pair échouerait — on couperait la messagerie des anciens
    /// clients pour ajouter une optimisation.
    func testABundleWithoutCapsStillDecodes() throws {
        let json = """
        {"identityKey":"aWs=","signingKey":"c2s=",
         "signedPreKey":{"keyId":"7","key":"c3Br","signature":"c2ln"},
         "oneTimePreKey":null}
        """
        let b = try JSONDecoder().decode(V2.FetchedBundle.self, from: Data(json.utf8))
        XCTAssertNil(b.caps, "absent doit se décoder en nil, pas faire échouer le bundle")
        XCTAssertEqual(b.identityKey, "aWs=")
    }

    /// Et un bundle qui le porte doit le rendre.
    func testABundleWithCapsDecodesTheValue() throws {
        let json = """
        {"identityKey":"aWs=","signingKey":"c2s=","caps":1,
         "signedPreKey":{"keyId":"7","key":"c3Br","signature":"c2ln"},
         "oneTimePreKey":null}
        """
        let b = try JSONDecoder().decode(V2.FetchedBundle.self, from: Data(json.utf8))
        XCTAssertEqual(b.caps, RatchetHealSignal.Caps.resendList)
    }

    // MARK: - Ce que subissent les clients DÉJÀ LIVRÉS

    /// LA question à trancher avant de déployer le serveur: un client 1.0.39,
    /// qui ne connaît pas `caps`, survit-il à une réponse qui en contient un ?
    ///
    /// Sa structure est reproduite ici À L'IDENTIQUE de la version livrée
    /// (vérifiée dans `git show 135d093:OSHI/V2ClientModels.swift`): quatre
    /// champs, aucun `caps`, et aucune stratégie de décodage stricte dans tout
    /// son `V2Client`. Si ce décodage échouait, le serveur casserait
    /// l'établissement de session de TOUS les clients déjà installés — Yassine
    /// et Zak compris — pour ajouter une optimisation dont ils ne profitent
    /// même pas.
    private struct FetchedBundle_1_0_39: Codable {
        let identityKey: String
        let signingKey: String
        let signedPreKey: V2.SignedPreKey
        let oneTimePreKey: V2.OneTimePreKey?
    }

    func testAShippedClientSurvivesTheNewServerField() throws {
        // Exactement ce que le serveur modifié renvoie désormais.
        let json = """
        {"identityKey":"aWs=","signingKey":"c2s=","caps":1,
         "signedPreKey":{"keyId":"7","key":"c3Br","signature":"c2ln"},
         "oneTimePreKey":{"keyId":"1","key":"b3Br"}}
        """
        let old = try JSONDecoder().decode(FetchedBundle_1_0_39.self, from: Data(json.utf8))
        XCTAssertEqual(old.identityKey, "aWs=", "un champ inconnu ne doit rien perturber")
        XCTAssertEqual(old.signedPreKey.keyId, "7")
        XCTAssertNotNil(old.oneTimePreKey, "la pré-clé à usage unique doit survivre au champ ajouté")
    }

    /// Cette build doit annoncer ce qu'elle sait réellement faire. Annoncer une
    /// capacité non implémentée ferait envoyer par le pair quelque chose qu'on
    /// ne saurait pas lire.
    func testThisBuildAnnouncesTheResendCapability() {
        XCTAssertTrue(RatchetHealSignal.Caps.has(RatchetHealSignal.Caps.mine,
                                                 RatchetHealSignal.Caps.resendList))
    }
}
