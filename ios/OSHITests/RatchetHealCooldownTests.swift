//
//  RatchetHealCooldownTests.swift
//  OSHI — le délai brise une boucle DÉCALÉE. Il ne brise pas une boucle
//  SYMÉTRIQUE, et la réaction immédiate rend celle-là probable.
//
//  LE SCÉNARIO, PRÉCISÉMENT
//  A et B cassent ensemble. Chacun efface sa session et envoie son accusé.
//  A reçoit celui de B: sa session ne l'ouvre pas, l'en-tête X3DH est là, donc
//  `establishResponderSession` REMPLACE la session de A par celle que B a
//  créée. Symétriquement chez B. Les deux écrivent alors sur une session que
//  l'autre n'a plus — re-cassés — et le délai de 60 s ne fait que répéter la
//  scène à l'identique.
//
//  Tant que la réaction demandait 8 tics, la simultanéité était rare: la gigue
//  des compteurs suffisait à décaler les deux côtés. Dès que la réaction est
//  immédiate, les deux répondent au MÊME lot cassé dans la MÊME seconde.
//
//  Le commentaire d'origine de `Policy` décrivait déjà le remède qui manquait —
//  « en laissant à l'un des deux le temps d'aboutir ». Le délai ne DÉSIGNE
//  personne; il fallait une fonction qui le fasse, sans horloge, sans réseau et
//  sans négociation.
//

import XCTest
@testable import OSHI

final class RatchetHealCooldownTests: XCTestCase {

    // MARK: Le départage

    /// La propriété qui compte: EXACTEMENT un des deux côtés est initiateur,
    /// pour toute paire, et les deux côtés calculent la même réponse sans
    /// échanger un octet.
    func testExactlyOneSideOfAPairIsInitiator() {
        let a = "aaa", z = "zzz"
        XCTAssertTrue(RatchetHealSignal.Policy.isHealInitiator(me: a, peer: z))
        XCTAssertFalse(RatchetHealSignal.Policy.isHealInitiator(me: z, peer: a))
    }

    /// Et sur des identités réalistes — des clés publiques en base64 — pas
    /// seulement sur trois lettres choisies pour que ça marche.
    func testTieBreakIsTotalOverRealisticIdentities() {
        let keys = (0..<64).map { _ in
            Data((0..<32).map { _ in UInt8.random(in: 0...255) }).base64EncodedString()
        }
        for i in keys.indices {
            for j in keys.indices where i != j {
                let one = RatchetHealSignal.Policy.isHealInitiator(me: keys[i], peer: keys[j])
                let other = RatchetHealSignal.Policy.isHealInitiator(me: keys[j], peer: keys[i])
                XCTAssertNotEqual(one, other,
                                  "les deux côtés doivent conclure des choses OPPOSÉES, sinon soit personne n'émet, soit les deux émettent")
            }
        }
    }

    /// Le cas dégénéré: se soigner soi-même. Personne n'est initiateur, ce qui
    /// est la bonne réponse — il n'y a pas de boucle à départager.
    func testAPeerIsNotItsOwnInitiator() {
        XCTAssertFalse(RatchetHealSignal.Policy.isHealInitiator(me: "k", peer: "k"))
    }

    // MARK: Le délai, bornes comprises

    /// La borne est INCLUSIVE et doit le rester: un test qui ne regarde que
    /// « bien avant » et « bien après » laisse passer une inversion de signe.
    func testCooldownBoundaryIsInclusive() {
        let t0 = Date(timeIntervalSince1970: 1_700_000_000)
        XCTAssertFalse(RatchetHealSignal.Policy.maySend(
            lastSentAt: t0, now: t0.addingTimeInterval(RatchetHealSignal.Policy.cooldown - 1)))
        XCTAssertTrue(RatchetHealSignal.Policy.maySend(
            lastSentAt: t0, now: t0.addingTimeInterval(RatchetHealSignal.Policy.cooldown)))
    }

    func testFirstHealIsNeverDelayed() {
        XCTAssertTrue(RatchetHealSignal.Policy.maySend(lastSentAt: nil, now: Date()))
    }

    // MARK: La passe unique de réparation
    //
    // Le défaut qu'elle corrige était ACTUEL, pas théorique: le délai était
    // testé APRÈS `deleteSession`. Sur un lot de k ≥ 2 enveloppes cassées du
    // même pair — le cas NORMAL d'une session morte — l'item #1 effaçait et
    // envoyait l'accusé, ce qui CRÉE ET PERSISTE une session initiateur; puis
    // l'item #2 rappelait `deleteSession` et la DÉTRUISAIT, pendant que le
    // délai bloquait tout nouvel accusé pendant 60 s.
    //
    // La forme corrigée n'a pas de point d'observation pur — elle touche le
    // magasin de sessions et le réseau. Ce qui reste falsifiable, c'est la
    // FORME: qu'aucun effacement ni aucun envoi ne subsiste DANS la boucle.

    private func pullSource() throws -> String {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("OSHI")
            .appendingPathComponent("MessageManager+V2.swift")
        return try String(contentsOf: url, encoding: .utf8)
    }

    func testNothingIsDeletedOrHealedInsideThePerEnvelopeLoop() throws {
        let src = try pullSource()
        guard let start = src.range(of: "for item in incoming {"),
              let end = src.range(of: "PASSE UNIQUE DE RÉPARATION", range: start.upperBound..<src.endIndex) else {
            return XCTFail("la boucle de traitement a changé de forme — ce test se périmerait en silence")
        }
        let loop = src[start.upperBound..<end.lowerBound]
        XCTAssertFalse(loop.contains("deleteSession"),
                       "effacer DANS la boucle détruit la session neuve créée par l'accusé de l'item précédent")
        XCTAssertFalse(loop.contains("sendHealSignal"),
                       "émettre DANS la boucle fait que seul le PREMIER abandon d'un tic part, avec un seul id")
        XCTAssertFalse(loop.contains("sendSessionResetNotification"),
                       "le signal de compat 1.0.39 partait par item, sans aucun délai — il doit être hissé dans la passe unique")
    }

    /// __RESEND_HAD_ONE_VEHICLE_2026_10_28__ La demande de renvoi doit avoir un SECOND
    /// véhicule, et il doit être hors de la boucle.
    ///
    /// Jusqu'ici `sendHealSignal` n'avait qu'UN appelant: la passe de réparation, qui ne
    /// s'exécute que sur une session PROUVÉE morte. La liste était donc l'otage d'une
    /// coïncidence — il fallait que le pair soit DÉJÀ connu capable à cet instant précis.
    /// Sinon la liste était retenue, la session guérissait, aucune autre casse ne venait,
    /// et les ids expiraient sept jours plus tard sans avoir jamais été demandés.
    ///
    /// Vérifié sur la FORME, comme le test au-dessus et pour la même raison: le chemin
    /// touche le réseau et le magasin de sessions, mais « où » l'appel se trouve est
    /// falsifiable.
    func testThePendingResendRequestLivesOutsideThePerEnvelopeLoop() throws {
        let src = try pullSource()
        XCTAssertTrue(src.contains("func requestPendingResends"),
                      "le second véhicule a disparu — une liste de renvoi redevient l'otage d'une casse")

        guard let start = src.range(of: "for item in incoming {"),
              let end = src.range(of: "PASSE UNIQUE DE RÉPARATION", range: start.upperBound..<src.endIndex) else {
            return XCTFail("la boucle de traitement a changé de forme — ce test se périmerait en silence")
        }
        let loop = src[start.upperBound..<end.lowerBound]
        XCTAssertFalse(loop.contains("requestPendingResends("),
                       "demander DANS la boucle, c'est un envoi par lot avec le premier id venu — exactement ce que la passe unique corrige")
        XCTAssertTrue(loop.contains("newlyResendCapable.insert"),
                      "la boucle doit se contenter de NOTER la transition vers « capable »")

        let afterLoop = src[end.lowerBound...]
        XCTAssertTrue(afterLoop.contains("requestPendingResends(from: peer)")
                      || src[start.upperBound...].contains("await Self.requestPendingResends(from: peer)"),
                      "la demande doit partir dans la passe, une fois par pair")
    }

    /// Un id n'est oublié qu'APRÈS un envoi réussi.
    ///
    /// `drainUnreadable` ne retire rien malgré son nom — c'est `clearUnreadable` qui retire.
    /// Oublier avant l'envoi perdrait à la fois le message et la seule trace permettant de
    /// le redemander, et personne ne le saurait.
    func testPendingResendIdsAreForgottenOnlyAfterTheSendSucceeds() throws {
        let src = try pullSource()
        guard let fn = src.range(of: "static func requestPendingResends") else {
            return XCTFail("la fonction a disparu")
        }
        let body = src[fn.lowerBound...].prefix(2_000)
        guard let send = body.range(of: "sendHealSignal"),
              let clear = body.range(of: "clearUnreadable") else {
            return XCTFail("l'envoi ou l'oubli a changé de nom — ce test se périmerait en silence")
        }
        XCTAssertTrue(send.lowerBound < clear.lowerBound,
                      "les ids sont oubliés AVANT l'envoi: un échec perdrait le message et le moyen de le redemander")
    }

    /// Et l'ordre dans la passe: le délai et le départage se vérifient AVANT
    /// l'effacement, jamais après. C'est l'inversion exacte qui produisait le
    /// défaut.
    func testThePolicyIsCheckedBeforeTheSessionIsDeleted() throws {
        let src = try pullSource()
        guard let start = src.range(of: "func v2RepairPeer("),
              let end = src.range(of: "func v2HasRelationship(", range: start.upperBound..<src.endIndex) else {
            return XCTFail("v2RepairPeer introuvable")
        }
        let body = String(src[start.upperBound..<end.lowerBound])
        guard let policy = body.range(of: "Policy.maySend"),
              let delete = body.range(of: "deleteSession") else {
            return XCTFail("la passe de réparation ne consulte plus le délai, ou n'efface plus")
        }
        XCTAssertLessThan(policy.lowerBound, delete.lowerBound,
                          "le délai doit être consulté AVANT l'effacement: l'inverse efface puis se refuse à émettre, et laisse la conversation morte une minute entière")
        guard let tieBreak = body.range(of: "isHealInitiator") else {
            return XCTFail("le départage symétrique a disparu de la passe de réparation")
        }
        XCTAssertLessThan(tieBreak.lowerBound, delete.lowerBound)
    }
}
