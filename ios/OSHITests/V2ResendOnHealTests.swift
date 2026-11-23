//
//  V2ResendOnHealTests.swift
//  OSHI — l'accusé de réparation ne répare plus seulement le CANAL: il
//  redemande les messages déjà détruits.
//
//  __RATCHET_RESEND_2026_08_28__ Refaire X3DH remet la conversation en marche
//  pour la SUITE. Ça ne rend pas ce qui a été jeté: MESURÉ sur les deux
//  téléphones de l'utilisateur, 176 enveloppes abandonnées après les 8
//  tentatives — 176 pushs suivis de rien. Le pair est le seul à les détenir
//  encore, donc l'accusé doit pouvoir les LUI NOMMER.
//
//  Trois choses peuvent mal tourner, et chacune est pire que le bug corrigé:
//
//    1. On livre à un pair le contenu d'un TIERS (il devine ou rejoue un id).
//    2. Le renvoi BOUCLE: renvoi → échec → abandon → accusé → mêmes ids.
//    3. Le contenu d'un message fuit dans un journal EXPORTÉ.
//

import XCTest
@testable import OSHI

final class V2ResendOnHealTests: XCTestCase {

    private let me = "ME_KEY"
    private let peer = "PEER_KEY"
    private let stranger = "STRANGER_KEY"

    private func reset() {
        UserDefaults.standard.removeObject(forKey: V2SyncState.unreadableKey)
        UserDefaults.standard.removeObject(forKey: V2SyncState.resentCountsKey)
        UserDefaults.standard.removeObject(forKey: V2SyncState.resendBudgetKey)
    }

    override func setUp() { super.setUp(); reset() }
    override func tearDown() { reset(); super.tearDown() }

    private func message(id: String,
                         from: String,
                         to: String,
                         text: String? = "coucou",
                         age: TimeInterval = 0,
                         deleted: Bool = false,
                         mediaType: MediaManager.MediaType? = nil) -> SecureMessage {
        let ts = Date().addingTimeInterval(-age)
        return SecureMessage(
            id: id,
            senderAddress: from,
            recipientAddress: to,
            encryptedContent: EncryptedMessage(ciphertext: "", signature: "",
                                               senderPublicKey: from, timestamp: ts),
            timestamp: ts,
            isRead: false,
            deliveryStatus: .sent,
            senderPublicKey: from,
            recipientPublicKey: to,
            plaintextContent: text,
            mediaType: mediaType,
            isDeletedForEveryone: deleted)
    }

    private func decide(_ m: SecureMessage?,
                        attempts: Int = 0,
                        budget: Int = 100,
                        now: Date = Date()) -> MessageManager.V2ResendDecision {
        MessageManager.v2ResendDecision(message: m, me: me, peer: peer, now: now,
                                        attemptsSoFar: attempts, peerBudgetLeft: budget)
    }

    // MARK: 1. La garde de SÉCURITÉ

    /// 🔴 LE test qui compte. Sans cette garde, un pair qui devine ou rejoue un
    /// identifiant se fait réémettre un message adressé à quelqu'un d'autre.
    func testAMessageAddressedToAThirdPartyIsNeverResent() {
        let m = message(id: "x1", from: me, to: stranger)
        XCTAssertEqual(decide(m), .notmine)
    }

    /// Et symétriquement: un message REÇU du pair n'est pas à nous de renvoyer.
    func testAnInboundMessageIsNeverResent() {
        let m = message(id: "x2", from: peer, to: me)
        XCTAssertEqual(decide(m), .notmine)
    }

    func testOurOwnMessageToThisPeerIsResent() {
        XCTAssertEqual(decide(message(id: "x3", from: me, to: peer)), .send)
    }

    /// La normalisation base64url/padding est la même que partout ailleurs
    /// (`receiveMessage`, `handleIncomingDeliveryReceipt`): une clé écrite dans
    /// l'autre orthographe ne doit pas faire échouer la garde… ni l'ouvrir.
    func testIdentityComparisonIsCanonical() {
        let canonical = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXowMTIzNDU2Nzg5YWI="
        let urlSafe = canonical.replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        // Sans ça le test serait VIDE: deux orthographes identiques ne
        // prouveraient rien sur la normalisation.
        XCTAssertNotEqual(urlSafe, canonical)
        let m = message(id: "x4", from: me, to: urlSafe)
        XCTAssertEqual(MessageManager.v2ResendDecision(message: m, me: me, peer: canonical,
                                                       now: Date(), attemptsSoFar: 0,
                                                       peerBudgetLeft: 100), .send)
    }

    // MARK: 2. Les cas NORMAUX qui ne sont pas des erreurs

    /// `V2IncomingMessage.failed` ne porte pas de `groupId`: un id de GROUPE
    /// entre donc dans le registre et arrive ici. Il est introuvable — le
    /// routeur fabrique un UUID par membre — et c'est NORMAL. Même chose pour
    /// l'extension de partage, qui n'enregistre aucun id applicatif.
    func testAnUnknownIdIsSkippedWithoutError() {
        XCTAssertEqual(decide(nil), .notfound)
    }

    func testAMessageDeletedForEveryoneNeverComesBack() {
        XCTAssertEqual(decide(message(id: "x5", from: me, to: peer, deleted: true)), .deleted)
    }

    /// Média exclu en phase 1, EXPLICITEMENT plutôt que par omission: les
    /// octets sont retirés de l'index à la sauvegarde et rerattachés depuis le
    /// disque au chargement, mais purgés pour de bon sur une vue-unique
    /// consommée. Un renvoi média doit gérer un contenu absent ET une
    /// réservation de blob complète.
    func testMediaIsOutOfScopeForNow() {
        XCTAssertEqual(decide(message(id: "x6", from: me, to: peer, mediaType: .image)), .media)
    }

    func testAMessagePastItsAgeIsNotResent() {
        let old = message(id: "x7", from: me, to: peer,
                          age: RatchetHealSignal.Policy.maxResendAge + 60)
        XCTAssertEqual(decide(old), .age)
    }

    // MARK: 3. Les freins — la seule chose qui puisse boucler

    /// Le cycle: renvoi → échec → abandon → `deleteSession` → accusé → MÊMES
    /// ids. Le délai de 60 s ne le termine pas, il l'espace; ce compteur-ci le
    /// termine.
    func testTheAttemptCeilingBreaksTheLoop() {
        let m = message(id: "x8", from: me, to: peer)
        XCTAssertEqual(decide(m, attempts: RatchetHealSignal.Policy.maxResendAttemptsPerMsgId), .budget)
    }

    func testAnExhaustedPeerBudgetStopsTheResend() {
        XCTAssertEqual(decide(message(id: "x9", from: me, to: peer), budget: 0), .budget)
    }

    /// Le compteur par msgId est PERSISTÉ: un relancement le remettrait à zéro
    /// sinon, et la boucle repartirait exactement là où elle en était.
    func testResendAttemptsArePersistedPerMsgId() {
        XCTAssertEqual(V2SyncState.resendAttempts("p1"), 0)
        V2SyncState.noteResent("p1")
        V2SyncState.noteResent("p1")
        XCTAssertEqual(V2SyncState.resendAttempts("p1"), 2)
        XCTAssertEqual(V2SyncState.resendAttempts("p2"), 0, "le compteur est par message, pas global")
    }

    func testPeerHourlyBudgetDrains() {
        let max = RatchetHealSignal.Policy.maxResendsPerPeerPerHour
        XCTAssertEqual(V2SyncState.peerResendBudgetLeft(peer), max)
        V2SyncState.noteResendSpent(peer)
        XCTAssertEqual(V2SyncState.peerResendBudgetLeft(peer), max - 1)
        // Et la fenêtre est GLISSANTE: une heure plus tard, le budget est plein.
        XCTAssertEqual(V2SyncState.peerResendBudgetLeft(peer, now: Date().addingTimeInterval(3601)), max)
    }

    // MARK: 4. Le registre des enveloppes illisibles

    func testUnreadableIdsAreKeptPerPeer() {
        let t = Date()
        V2SyncState.markUnreadable(msgId: "a", from: peer, envelopeTs: t)
        V2SyncState.markUnreadable(msgId: "b", from: stranger, envelopeTs: t)
        XCTAssertEqual(V2SyncState.drainUnreadable(for: peer), ["a"])
        XCTAssertEqual(V2SyncState.drainUnreadable(for: stranger), ["b"])
    }

    /// Plafonné, et les PLUS RÉCENTS d'abord: ce sont ceux dont le renvoi a
    /// encore une valeur pour l'utilisateur.
    func testDrainReturnsTheMostRecentUpToTheCap() {
        let base = Date()
        let cap = RatchetHealSignal.Policy.maxResendPerRequest
        for i in 0..<(cap + 10) {
            V2SyncState.markUnreadable(msgId: "id-\(i)", from: peer,
                                       envelopeTs: base.addingTimeInterval(Double(i)))
        }
        let drained = V2SyncState.drainUnreadable(for: peer)
        XCTAssertEqual(drained.count, cap)
        XCTAssertEqual(drained.first, "id-\(cap + 9)", "le plus récent d'abord")
        XCTAssertFalse(drained.contains("id-0"), "les plus anciens tombent en premier")
    }

    func testDrainDiscardsEnvelopesPastTheResendAge() {
        let now = Date()
        V2SyncState.markUnreadable(msgId: "frais", from: peer, envelopeTs: now)
        V2SyncState.markUnreadable(msgId: "vieux", from: peer,
                                   envelopeTs: now.addingTimeInterval(-RatchetHealSignal.Policy.maxResendAge - 60))
        XCTAssertEqual(V2SyncState.drainUnreadable(for: peer, now: now), ["frais"])
    }

    /// Un message finalement LU ne doit plus être redemandé — d'où l'appel au
    /// même endroit que `clearRetries`.
    func testClearingRemovesTheIdFromTheRegistry() {
        V2SyncState.markUnreadable(msgId: "a", from: peer, envelopeTs: Date())
        V2SyncState.markUnreadable(msgId: "b", from: peer, envelopeTs: Date())
        V2SyncState.clearUnreadable("a")
        XCTAssertEqual(V2SyncState.drainUnreadable(for: peer), ["b"])
    }

    /// Le registre NE SE PURGE PAS à la lecture: un accusé qui échoue
    /// emporterait la liste avec lui, et l'utilisateur perdrait à la fois les
    /// messages et le déclencheur.
    func testDrainIsNotDestructive() {
        V2SyncState.markUnreadable(msgId: "a", from: peer, envelopeTs: Date())
        XCTAssertEqual(V2SyncState.drainUnreadable(for: peer), ["a"])
        XCTAssertEqual(V2SyncState.drainUnreadable(for: peer), ["a"])
    }

    /// Borné comme ses voisins: UserDefaults n'est pas une base de données.
    func testRegistryStaysBoundedPerPeer() {
        let base = Date()
        for i in 0..<(V2SyncState.unreadablePerPeerCap * 3) {
            V2SyncState.markUnreadable(msgId: "id-\(i)", from: peer,
                                       envelopeTs: base.addingTimeInterval(Double(i)))
        }
        // Le drain est déjà plafonné plus bas; on vérifie le STOCKAGE en
        // demandant plus que le plafond de requête ne peut rendre.
        let stored = UserDefaults.standard.dictionary(forKey: V2SyncState.unreadableKey) as? [String: [String: Double]]
        XCTAssertEqual(stored?[peer]?.count, V2SyncState.unreadablePerPeerCap)
    }

    // MARK: 5. Aucun contenu, aucun identifiant entier, dans un journal EXPORTÉ
    //
    // `CallFileLogger` est exportable, et `receiveMessage` y écrit déjà
    // `preview=` — les 24 premiers caractères du texte. Ces gardes-là se
    // vérifient sur le TEXTE de la source, comme
    // `testNotificationExtensionSuppressesHealSignals` le fait pour la NSE:
    // c'est la seule forme qui reste falsifiable sans faire tourner le réseau.

    private func source(_ name: String) throws -> String {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("OSHI")
            .appendingPathComponent(name)
        return try String(contentsOf: url, encoding: .utf8)
    }

    func testResendLogsCarryNoContentAndNoWholeIdentifier() throws {
        let src = try source("MessageManager+V2.swift")
        for line in src.split(separator: "\n") where line.contains("DIAG_V2_RESEND") || line.contains("DIAG_V2_HEAL") {
            XCTAssertFalse(line.contains("plaintextContent"),
                           "un journal exporté ne doit jamais porter le contenu d'un message: \(line)")
            // Un msgId est l'identifiant APPLICATIF du message; une clé
            // publique est une identité. Ni l'un ni l'autre en entier.
            XCTAssertFalse(line.contains("\\(id)") || line.contains("\\(msgId)"),
                           "identifiant entier dans un journal exporté: \(line)")
            XCTAssertFalse(line.contains("\\(peer)") || line.contains("\\(from)") || line.contains("\\(to)"),
                           "clé publique entière dans un journal exporté: \(line)")
        }
    }

    /// 🔴 LA propriété de vie privée la plus fragile du chemin, et elle tient à
    /// un seul `continue`. Sans lui, la charge de l'accusé — c'est-à-dire une
    /// liste d'identifiants de messages — descend dans `v2Deliver` →
    /// `receiveMessage`, dont la toute PREMIÈRE ligne écrit `preview=` dans le
    /// journal exportable.
    func testHealPayloadNeverReachesTheDeliveryFunnel() throws {
        let src = try source("MessageManager+V2.swift")
        guard let branch = src.range(of: "if groupId == nil, let heal = RatchetHealSignal.parse(text)") else {
            return XCTFail("la branche de réception de l'accusé a changé de forme — revérifier le `continue`")
        }
        let tail = src[branch.upperBound...]
        guard let stop = tail.range(of: "\n                }") else {
            return XCTFail("branche de l'accusé illisible")
        }
        let body = tail[..<stop.lowerBound]
        XCTAssertTrue(body.contains("continue"),
                      "l'accusé DOIT sortir avant v2Deliver: son JSON atteindrait sinon le preview= de DIAG_MSG_RX_ENTRY, qui est exporté.")
        XCTAssertFalse(body.contains("v2Deliver"))
    }

    /// Un renvoi n'est pas un nouvel envoi: faire clignoter la bulle d'un
    /// message vieux d'une semaine serait un mensonge sur ce qui vient de se
    /// passer — et 32 bannières de rattrapage seraient le bug d'à côté.
    func testResendTouchesNoDeliveryStateAndFiresNoPush() throws {
        let src = try source("MessageManager+V2.swift")
        guard let start = src.range(of: "func v2ResendRequested("),
              let end = src.range(of: "// MARK: - Blobs média", range: start.upperBound..<src.endIndex) else {
            return XCTFail("v2ResendRequested introuvable — le test se périmerait en silence")
        }
        let body = src[start.upperBound..<end.lowerBound]
        XCTAssertFalse(body.contains("deliveryStatus"))
        XCTAssertFalse(body.contains("uploadStatus"))
        XCTAssertFalse(body.contains("uploadProgress"))
        XCTAssertFalse(body.contains("VoIPPushManager"))
        // __RESEND_ENVELOPE_2026_08_28__ CES DEUX ASSERTIONS ONT ÉTÉ RETOURNÉES.
        //
        // Elles exigeaient la réémission sous le MÊME msgId, en raisonnant que
        // le dédoublonnage de `receiveMessage` est par id. Le raisonnement était
        // juste, la conclusion coûteuse: `msgId` est un champ EN CLAIR de
        // l'enveloppe relais, conservé 30 jours. Deux enveloppes A→B portant le
        // même identifiant à des semaines d'intervalle sont appariables par le
        // relais — assez pour dater la panne et compter les messages perdus,
        // sans jamais ouvrir un seul contenu.
        //
        // L'identifiant de relais est donc NEUF, et l'identifiant applicatif
        // d'origine voyage DANS le chiffré (`Resent.encode`). Le dédoublonnage
        // reste intact, et l'appariement devient impossible.
        XCTAssertTrue(body.contains("Resent.encode(originalId: id"),
                      "l'id d'origine doit voyager dans le chiffré")
        XCTAssertTrue(body.contains("msgId: UUID().uuidString"),
                      "l'identifiant de relais doit être neuf")
        XCTAssertFalse(body.contains("msgId: id"),
                       "réutiliser le msgId d'origine rend les deux envois appariables par le relais")
    }

    /// Le renvoi ne doit produire AUCUNE bannière: le destinataire a déjà été
    /// notifié quand le message est arrivé la première fois. C'est même cette
    /// notification-là, suivie d'aucun message, qui a fait remonter le problème.
    ///
    /// Le commentaire d'origine jurait qu'aucune bannière ne partait. C'était
    /// vrai du push APNs — `router.sendText` n'en déclenche pas — et FAUX du
    /// chemin local: `receiveMessage` programme sa propre notification.
    func testResendDeliversWithoutFiringALocalNotification() throws {
        let rx = try source("MessageManager+V2.swift")
        guard let start = rx.range(of: "Resent.parse(text)") else {
            return XCTFail("branche de réception du renvoi introuvable")
        }
        // __GROUP_E2E_V2_2026_09_23__ la branche porte maintenant aussi le cas « état de
        // groupe renvoyé » avant la livraison 1:1: on borne la fenêtre à la branche
        // elle-même (jusqu'à la branche suivante, `RatchetHealSignal.parse`) au lieu
        // d'un nombre de caractères fixe.
        let tail = rx[start.upperBound...]
        let end = tail.range(of: "RatchetHealSignal.parse(text)")?.lowerBound ?? rx.endIndex
        let window = rx[start.lowerBound..<end]
        XCTAssertTrue(window.contains("v2Deliver("),
                      "le renvoi 1:1 n'est plus livré par v2Deliver")
        XCTAssertTrue(window.contains("silent: true"),
                      "le renvoi doit être livré sans notification")
        let deliver = try XCTUnwrap(rx.range(of: "func v2Deliver("))
        let deliverBody = rx[deliver.lowerBound..<(rx.index(deliver.lowerBound, offsetBy: 3000, limitedBy: rx.endIndex) ?? rx.endIndex)]
        XCTAssertTrue(deliverBody.contains("suppressNotification: silent"),
                      "v2Deliver ne transmet plus `silent` à receiveMessage: le renvoi refait une bannière")

        let mm = try source("MessageManager.swift")
        XCTAssertTrue(mm.contains("suppressNotification: Bool = false"),
                      "receiveMessage doit accepter la suppression, et par défaut ne rien changer")
        XCTAssertTrue(mm.contains("!suppressNotification"),
                      "le paramètre doit réellement garder la programmation de la notification")
    }
}
