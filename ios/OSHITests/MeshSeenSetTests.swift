//
//  MeshSeenSetTests.swift
//  OSHITests
//
//  __SEEN_SET_EVICTS_OLDEST_2026_09_12__ Le garde-fou anti-doublon du maillage
//  inter-plateformes.
//
//  Ce que ces tests protègent: `CrossPlatformMesh` ne livre un message qu'une
//  fois parce qu'il retient l'id des messages déjà vus. Ce cache est BORNÉ, donc
//  il faut en jeter — et le SEUL tri acceptable est « les plus vieux d'abord ».
//  Le code d'avant faisait `seenMessageIds = Set(seenMessageIds.suffix(2500))`:
//  un `Set` n'a pas d'ordre, `suffix` gardait donc 2500 ids ARBITRAIRES. Passé
//  ~5000 ids, un message encore en vol pouvait voir son id jeté, repasser le
//  `contains` et être affiché DEUX FOIS à l'utilisateur.
//
//  Aucun de ces tests n'ouvre de socket, n'allume le BLE ni ne publie de service
//  Bonjour: `init()` ne démarre rien (tout est dans `start()`, jamais appelé ici).
//

import XCTest
@testable import OSHI

final class MeshSeenSetTests: XCTestCase {

    // MARK: - Outils

    /// Une instance à soi, jamais démarrée: aucune radio, aucun réseau.
    private func makeMesh() -> CrossPlatformMesh { CrossPlatformMesh() }

    private func id(_ n: Int) -> String { String(format: "msg-%06d", n) }

    /// Toutes les écritures passent par la file série qui garde l'état, comme en
    /// production — le contrat de `noteSeenMessageId` l'exige.
    private func note(_ mesh: CrossPlatformMesh, _ ids: [String]) {
        mesh.stateQueue.sync {
            for one in ids { mesh.noteSeenMessageId(one) }
        }
    }

    /// Lecture sur la même file: pas de course, et l'ordre FIFO garantit qu'on
    /// lit APRÈS tout ce qui a déjà été posté dessus (y compris par `stop()`).
    private func seen(_ mesh: CrossPlatformMesh) -> (set: Set<String>, order: [String]) {
        mesh.stateQueue.sync { (set: mesh.seenMessageIds, order: mesh.seenMessageOrder) }
    }

    // MARK: - LE test: ce qui est jeté, ce sont les PLUS VIEUX

    /// 🔴 La régression. On dépasse le plafond d'un seul id, puis on exige que
    /// les survivants soient EXACTEMENT les plus récents. Avec l'ancien
    /// `Set(...suffix(2500))`, les survivants étaient tirés au hasard dans le
    /// tas: ce test échouait presque à coup sûr, et surtout il échouait sur les
    /// ids fraîchement vus — ceux dont l'oubli produit un doublon à l'écran.
    func testEvictionDropsTheOldestIdsAndKeepsTheMostRecent() {
        let mesh = makeMesh()
        let cap = mesh.seenMessageIdsCap
        let keep = mesh.seenMessageIdsKeep
        XCTAssertGreaterThan(cap, keep, "un plafond sous le seuil de conservation ne jetterait rien")

        note(mesh, (0...cap).map(id))   // cap + 1 ids distincts ⇒ une éviction

        let state = seen(mesh)
        let expected = ((cap + 1 - keep)...cap).map(id)
        XCTAssertEqual(state.order, expected,
                       "le journal doit garder la fin de la séquence, dans l'ordre d'arrivée")
        XCTAssertEqual(state.set, Set(expected),
                       "l'ensemble doit contenir les ids RÉCENTS, pas 2500 ids tirés au hasard")
        XCTAssertTrue(state.set.contains(id(cap)),
                      "le tout dernier id vu est celui qui a le plus de chances de revenir")
        XCTAssertFalse(state.set.contains(id(0)),
                       "le plus ancien est justement celui qu'on avait le droit de jeter")
    }

    /// Le plafond est un plafond: tant qu'on est dessous, on ne jette rien.
    func testNothingIsEvictedBelowTheCap() {
        let mesh = makeMesh()
        let cap = mesh.seenMessageIdsCap

        note(mesh, (0..<cap).map(id))

        let state = seen(mesh)
        XCTAssertEqual(state.set.count, cap)
        XCTAssertEqual(state.order.count, cap)
        XCTAssertTrue(state.set.contains(id(0)), "rien n'est encore à jeter au niveau du plafond")
    }

    /// Après une éviction il reste EXACTEMENT `seenMessageIdsKeep` entrées — ni
    /// une purge totale (qui rouvrirait la porte aux doublons), ni un cache qui
    /// n'aurait rien libéré (et grossirait sans fin).
    func testEvictionKeepsExactlyTheKeepCount() {
        let mesh = makeMesh()
        let cap = mesh.seenMessageIdsCap
        let keep = mesh.seenMessageIdsKeep

        note(mesh, (0...cap).map(id))

        let state = seen(mesh)
        XCTAssertEqual(state.set.count, keep)
        XCTAssertEqual(state.order.count, keep)
    }

    // MARK: - Ce que ça vaut à l'arrivée d'un message

    /// La raison d'être du cache: un message qui ré-arrive par un autre chemin
    /// du maillage doit être reconnu comme DÉJÀ VU, donc ignoré — pas livré une
    /// seconde fois. Ici l'id est bien dans la fenêtre de rétention.
    func testARecentIdIsStillKnownAfterAnEvictionSoItIsNotDeliveredTwice() {
        let mesh = makeMesh()
        let cap = mesh.seenMessageIdsCap

        note(mesh, (0...cap).map(id))

        let inFlight = id(cap)          // vu à l'instant
        let slightlyOlder = id(cap - 100)
        let before = seen(mesh)
        XCTAssertTrue(before.set.contains(inFlight),
                      "c'est exactement le doublon que l'ancien code laissait passer")
        XCTAssertTrue(before.set.contains(slightlyOlder))

        // Le message ré-arrive: le nœud le re-signale, rien ne doit bouger.
        note(mesh, [inFlight])
        let after = seen(mesh)
        XCTAssertEqual(after.set, before.set)
        XCTAssertEqual(after.order, before.order,
                       "re-voir un id connu ne change ni le contenu ni l'ordre du cache")
    }

    /// Un id re-signalé n'ajoute PAS une seconde entrée d'ordre: sinon un pair
    /// qui rejoue le même message ferait enfler le journal sans fin, et surtout
    /// il ferait évincer des ids bel et bien uniques.
    func testRenotingAnIdNeverAppendsASecondOrderEntry() {
        let mesh = makeMesh()
        let cap = mesh.seenMessageIdsCap
        let keep = mesh.seenMessageIdsKeep

        note(mesh, Array(repeating: "flood", count: 1_000))
        var state = seen(mesh)
        XCTAssertEqual(state.order, ["flood"], "mille répétitions = une seule entrée")
        XCTAssertEqual(state.set, ["flood"])

        // Des nouveautés, chacune rejouée aussitôt par le pair. On s'arrête juste
        // SOUS le plafond: si les répétitions comptaient, on l'aurait crevé et une
        // éviction aurait déjà eu lieu.
        var stream: [String] = []
        for n in 0..<(cap - 1) {
            stream.append(id(n))
            stream.append(id(n))        // le même message, deux chemins
        }
        note(mesh, stream)

        state = seen(mesh)
        XCTAssertEqual(state.order, ["flood"] + (0..<(cap - 1)).map(id),
                       "seuls les ids DISTINCTS entrent au journal, une fois chacun")
        XCTAssertEqual(state.order.count, cap, "donc rien n'a encore été évincé")
        XCTAssertEqual(Set(state.order), state.set,
                       "le journal d'ordre et l'ensemble ne doivent jamais diverger")

        // Deux ids de plus: on franchit le plafond, l'éviction doit rester saine.
        note(mesh, [id(cap - 1), id(cap)])

        state = seen(mesh)
        XCTAssertEqual(state.order, ((cap - keep)...cap).map(id),
                       "l'éviction jette bien la tête du journal, répétitions ou pas")
        XCTAssertEqual(Set(state.order), state.set)
        XCTAssertFalse(state.set.contains("flood"),
                       "« flood » était le plus ancien: le rejouer mille fois ne le rajeunit pas")
    }

    // MARK: - Remise à zéro

    /// `stop()` vide le cache. Il doit vider les DEUX structures: un journal
    /// d'ordre qui survivrait à l'ensemble ferait évincer, à la prochaine purge,
    /// des ids qui n'y sont plus — et garderait des ids récents hors du compte.
    /// (Rien n'est démarré ici, donc `stop()` n'a ni socket ni radio à fermer.)
    func testStopClearsBothTheSetAndTheOrderLog() {
        let mesh = makeMesh()
        note(mesh, (0..<10).map(id))
        XCTAssertEqual(seen(mesh).order.count, 10)

        mesh.stop()

        let cleared = seen(mesh)        // la file est série: on lit après la purge
        XCTAssertTrue(cleared.set.isEmpty, "l'ensemble doit repartir vide")
        XCTAssertTrue(cleared.order.isEmpty, "et le journal d'ordre avec lui")

        // Et le cache repart sain: pas de désynchronisation héritée.
        note(mesh, [id(100), id(101), id(100)])
        let reborn = seen(mesh)
        XCTAssertEqual(reborn.order, [id(100), id(101)])
        XCTAssertEqual(reborn.set, [id(100), id(101)])
    }
}

// MARK: - __MESH_RECIPIENT_SPELLING_2026_10_12__
//
//  Signalé le 12/10/2026 par un utilisateur: « mesh messaging works from iOS to
//  Android but not from Android to iOS ». `handleContentMessage` décidait « ce
//  message est-il pour moi ? » en comparant `destinataire.trimmed.lowercased()`
//  à `ma_clé.trimmed.lowercased()`, avec un repli sur les 32 premiers caractères.
//
//  Aucune des deux moitiés ne réconcilie base64url avec base64 standard:
//  `lowercased()` laisse `-` en `-` et `_` en `_`. Une clé de 43 caractères porte
//  un `+` ou un `/` dans ses 32 premiers caractères environ deux fois sur trois,
//  donc pour la plupart des contacts l'égalité ÉCHOUAIT, le repli échouait avec
//  elle, et `relayToRecipient` — qui n'a aucune route vers notre PROPRE clé —
//  jetait le message SANS RIEN DIRE.
//
//  Et l'asymétrie est exactement celle rapportée: iOS appose la graphie canonique
//  sur toute identité qu'il stocke (`MessageManager.canonicalIdentity`), donc
//  iOS→Android tombait juste; Android n'a pas d'entonnoir équivalent (QR, relais
//  v2, VPS livrent les deux graphies) donc Android→iOS mourait sur ce test.
//
//  Ces tests n'ouvrent rien: les règles sont des fonctions `static` pures.

final class MeshRecipientSpellingTests: XCTestCase {

    /// Une vraie clé X25519, dans les trois graphies où elle circule.
    /// Son 23e caractère est `/` en standard et `_` en base64url — c'est
    /// précisément ce que l'ancienne comparaison ne rattrapait pas.
    private let keyStandard = "VFNouvPEe2Ca9UIsXPYcLM/Ti7kEmnrorgDeLDhKnw8="
    private let keyUrlPadded = "VFNouvPEe2Ca9UIsXPYcLM_Ti7kEmnrorgDeLDhKnw8="
    private let keyUrlBare = "VFNouvPEe2Ca9UIsXPYcLM_Ti7kEmnrorgDeLDhKnw8"

    /// Une clé DIFFÉRENTE: le correctif ne doit pas être « dire oui à tout ».
    private let otherKey = "cyNYM+01CFBfwJuryISNkTsWE3jwXBi90bVTx+lZvuc="

    /// La panne rapportée, reproduite sur la règle qui l'a causée.
    func testAncienneComparaisonRejetaitNotrePropreCle() {
        let theirs = keyUrlPadded.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let mine = keyStandard.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let prefixLen = min(mine.count, theirs.count, 32)
        let ancienVerdict = theirs == mine ||
            (prefixLen >= 16 && theirs.prefix(prefixLen) == mine.prefix(prefixLen))
        XCTAssertFalse(
            ancienVerdict,
            "l'ancienne règle doit être montrée en échec, sinon cette section ne prouve rien"
        )
    }

    func testUnDestinataireEnBase64urlEstBienNous() {
        XCTAssertTrue(
            CrossPlatformMesh.isAddressedToUs(keyUrlPadded, myPublicKey: keyStandard),
            "un pair Android qui nous adresse en base64url nous adresse BIEN à NOUS"
        )
        XCTAssertTrue(
            CrossPlatformMesh.isAddressedToUs(keyUrlBare, myPublicKey: keyStandard),
            "base64url sans padding est la même clé"
        )
        XCTAssertTrue(
            CrossPlatformMesh.isAddressedToUs(keyStandard, myPublicKey: keyUrlBare),
            "et le repli fonctionne dans les deux sens"
        )
    }

    func testGraphiesIdentiquesContinuentDeCorrespondre() {
        XCTAssertTrue(CrossPlatformMesh.isAddressedToUs(keyStandard, myPublicKey: keyStandard))
        XCTAssertTrue(CrossPlatformMesh.isAddressedToUs(keyUrlBare, myPublicKey: keyUrlBare))
    }

    func testDestinataireVideEstUneDiffusion() {
        XCTAssertTrue(
            CrossPlatformMesh.isAddressedToUs("", myPublicKey: keyStandard),
            "les diffusions (PUBLIC_GROUP_AD, ragot d'identité) ne portent pas de destinataire"
        )
    }

    func testLeMessageDunAutreResteRelaye() {
        XCTAssertFalse(
            CrossPlatformMesh.isAddressedToUs(otherKey, myPublicKey: keyStandard),
            "une trame destinée à un autre pair doit continuer vers le relais"
        )
        let otherUrl = otherKey.replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
        XCTAssertFalse(
            CrossPlatformMesh.isAddressedToUs(otherUrl, myPublicKey: keyStandard),
            "et sa graphie base64url ne doit pas nous correspondre non plus"
        )
    }

    func testOnNeReclameRienAvantDavoirUneIdentite() {
        // `myPublicKey` vaut "" jusqu'à `start(publicKey:)`. Réclamer une trame
        // à ce moment-là livrerait le chiffré d'un inconnu à notre déchiffrement.
        XCTAssertFalse(CrossPlatformMesh.isAddressedToUs(keyStandard, myPublicKey: ""))
    }

    func testNormalisationReplieAlphabetUrlEtPadding() {
        XCTAssertEqual(
            CrossPlatformMesh.normalizedMeshKey(keyStandard),
            CrossPlatformMesh.normalizedMeshKey(keyUrlPadded)
        )
        XCTAssertEqual(
            CrossPlatformMesh.normalizedMeshKey(keyUrlBare),
            CrossPlatformMesh.normalizedMeshKey(keyUrlPadded)
        )
        XCTAssertFalse(
            CrossPlatformMesh.normalizedMeshKey(keyStandard).hasSuffix("="),
            "le padding est retiré, pas conservé"
        )
        XCTAssertEqual(
            CrossPlatformMesh.normalizedMeshKey("  \n" + keyUrlPadded + "\t "),
            CrossPlatformMesh.normalizedMeshKey(keyStandard),
            "les blancs autour sont toujours coupés"
        )
    }

    /// Le CÂBLAGE: les recherches d'envoi partagent la même règle, sinon le
    /// sens iOS→Android retomberait un jour dans le miroir de cette panne.
    func testUneInstanceNonDemarreeNeTrouveAucunPair() {
        let mesh = CrossPlatformMesh()
        XCTAssertFalse(mesh.isPeerReachable(keyStandard))
        XCTAssertFalse(mesh.isPeerLiveConnected(keyUrlBare))
        XCTAssertFalse(mesh.isPeerDiscovered(keyUrlPadded))
    }
}
