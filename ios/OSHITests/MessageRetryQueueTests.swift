//
//  MessageRetryQueueTests.swift
//  OSHITests
//
//  __RETRY_DRAIN_MAIN_ACTOR_2026_09_12__ / __RETRY_CAP_IS_PER_MESSAGE_2026_09_12__
//
//  Ce que ces tests protègent: la file de renvoi est le dernier filet sous un
//  message non parti. Elle se vide exactement au pire moment — un retour de
//  réseau, quand plusieurs messages sont en souffrance et que les confirmations
//  de livraison retirent des entrées EN MÊME TEMPS que la boucle les traite.
//
//  Deux défauts vivaient là:
//
//  1. La boucle tournait dans un `Task { }` nu d'une classe non isolée (donc HORS
//     du main actor) et parcourait la file PAR INDICE, la plage calculée une seule
//     fois d'avance. Le tableau, lui, rétrécit pendant les `await` — la boucle en
//     retire elle-même, et `removeFromRetryQueue` en retire à la livraison. Un
//     indice périmé lisait alors hors bornes: « Index out of range », en plein
//     drain de reconnexion.
//
//  2. `maxRetryAttempts` était un plafond par CYCLE, pas par message: l'entrée
//     épuisée quittait la file mais le message restait `.failed`, donc le
//     balayage suivant (`enqueueFailedOutgoingForRetry`) la remettait en file avec
//     un compteur remis à ZÉRO — trois tentatives de plus à chaque passage au
//     premier plan, pendant 24 h.
//
//  COMMENT CES TESTS PILOTENT LE DRAIN SANS RÉSEAU. `retrySendViaCloud` sort tout
//  seul, sans transport, dans deux cas et deux seulement:
//    • l'id n'est plus dans `messages`  ⇒ renvoie `true`  (« succès », entrée retirée)
//    • le message est `.pending` et a moins de 60 s ⇒ renvoie `false` (« échec »,
//      entrée conservée, compteur incrémenté)
//  Tout est donc du VRAI `retryFailedMessages()`, pas une imitation.
//

import XCTest
@testable import OSHI

@MainActor
final class MessageRetryQueueTests: XCTestCase {

    private var manager: MessageManager!
    private var previousShared: MessageManager?
    private var previousIdentity: IdentityManager?

    /// Clé propre à ce fichier: `enqueueFailedOutgoingForRetry` balaie TOUT
    /// `messages`, y compris ce que l'hôte de test aurait déjà sur disque. Une
    /// clé unique garantit qu'aucun message étranger n'entre dans nos assertions.
    private let myKey = "retry-queue-tests-sender-\(UUID().uuidString)"
    private let recipientKey = "retry-queue-tests-recipient"

    override func setUp() async throws {
        previousShared = MessageManager.shared
        previousIdentity = MessageManager.sharedIdentityManager

        manager = MessageManager()

        // `init` relit la file persistée HORS du main actor (`loadDeferredQueues`)
        // puis la republie. On laisse cet atterrissage se produire AVANT de partir
        // d'une file vide: sinon un reliquat de disque remplacerait, en plein test,
        // ce que le test vient d'empiler.
        try? await Task.sleep(nanoseconds: 300_000_000)
        emptyTheQueue()
        manager.permanentlyFailedMessageIds.removeAll()

        // Le drain refuse de partir sans réseau déclaré. On le pose ici et on le
        // revérifie dans chaque test: un drain qui ne part pas rendrait toutes les
        // assertions suivantes creuses.
        manager.isNetworkAvailable = true
    }

    override func tearDown() async throws {
        if manager != nil {
            emptyTheQueue()
            manager.messages.removeAll { $0.senderPublicKey == myKey }
            manager = nil
        }
        MessageManager.shared = previousShared
        MessageManager.sharedIdentityManager = previousIdentity
    }

    // MARK: - Outillage

    /// Vide la file PAR le retrait public, pour que le fichier persisté soit
    /// réécrit vide lui aussi — sans quoi le `MessageManager` du test suivant le
    /// rechargerait.
    private func emptyTheQueue() {
        for entry in manager.messageRetryQueue {
            manager.removeFromRetryQueue(messageId: entry.messageId)
        }
    }

    private func queuedIds() -> [String] {
        manager.messageRetryQueue.map { $0.messageId }
    }

    private func retryCount(of id: String) -> Int? {
        manager.messageRetryQueue.first(where: { $0.messageId == id })?.retryCount
    }

    /// Empile `ids` et vérifie qu'ils y sont, dans cet ordre. La vérification
    /// n'est pas décorative: c'est la précondition de tous les tests d'ordre.
    private func enqueue(_ ids: [String], file: StaticString = #filePath, line: UInt = #line) {
        for id in ids {
            manager.addToRetryQueue(messageId: id, content: "body-\(id)", recipientKey: recipientKey)
        }
        XCTAssertEqual(queuedIds(), ids,
                       "précondition: la file doit contenir exactement \(ids), dans l'ordre d'ajout",
                       file: file, line: line)
    }

    /// Un message à nous, `.pending` et tout frais: `retrySendViaCloud` le
    /// considère comme un envoi encore en vol et renvoie `false` SANS réseau.
    private func youngPendingMessage(id: String) -> SecureMessage {
        message(id: id, status: .pending, age: 0)
    }

    /// Un message à nous, `.failed` et récent: c'est exactement ce que
    /// `enqueueFailedOutgoingForRetry` doit reprendre.
    private func recentlyFailedMessage(id: String) -> SecureMessage {
        message(id: id, status: .failed, age: 60)
    }

    private func message(id: String, status: DeliveryStatus, age: TimeInterval) -> SecureMessage {
        let when = Date().addingTimeInterval(-age)
        return SecureMessage(
            id: id,
            senderAddress: "me",
            recipientAddress: "them",
            encryptedContent: EncryptedMessage(
                ciphertext: "ct-\(id)",
                signature: "sig-\(id)",
                senderPublicKey: myKey,
                timestamp: when
            ),
            timestamp: when,
            isRead: false,
            deliveryStatus: status,
            senderPublicKey: myKey,
            recipientPublicKey: recipientKey,
            plaintextContent: "body-\(id)"
        )
    }

    /// Attend qu'une condition devienne vraie. `Task.sleep` rend le main actor, ce
    /// qui laisse justement tourner le drain — qui est lui aussi sur le main actor.
    @discardableResult
    private func poll(upTo seconds: TimeInterval, until condition: () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            if condition() { return true }
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
        return condition()
    }

    /// Le drain lève `isRetrying` de façon synchrone et le repose dans son `defer`.
    private func waitForDrain(file: StaticString = #filePath, line: UInt = #line) async {
        let finished = await poll(upTo: 20) { !self.manager.isRetrying }
        XCTAssertTrue(finished, "le drain n'est jamais retombé: isRetrying est resté vrai",
                      file: file, line: line)
    }

    /// Démarre le drain et prouve qu'il a bien démarré.
    private func startDrain(file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(manager.isNetworkAvailable,
                      "précondition: sans réseau déclaré, retryFailedMessages() sort tout de suite",
                      file: file, line: line)
        XCTAssertFalse(manager.messageRetryQueue.isEmpty,
                       "précondition: une file vide ne déclenche aucun drain",
                       file: file, line: line)
        manager.retryFailedMessages()
        XCTAssertTrue(manager.isRetrying, "le drain devait démarrer", file: file, line: line)
    }

    // MARK: - 1. Retirer une entrée PENDANT le drain

    /// LE crash. Une confirmation de livraison retire une entrée que la boucle n'a
    /// pas encore atteinte; le tableau rétrécit d'un cran de plus que ce que la
    /// boucle a elle-même retiré.
    ///
    /// Ancienne implémentation: `for i in (0..<count).reversed()` puis
    /// `messageRetryQueue[i]`. Après le retrait externe, l'indice suivant dépasse
    /// la fin du tableau ⇒ « Index out of range » (le processus de test meurt, ce
    /// qui est bien un échec). Nouvelle: copie de valeur + adressage par id.
    ///
    /// Et il ne s'agit pas seulement de ne pas planter: ce qui restait après
    /// l'entrée retirée doit quand même partir.
    func testRemovingAnEntryMidDrainNeitherCrashesNorStrandsTheRest() async {
        // Aucun de ces ids n'est dans `messages` ⇒ chaque tentative « réussit »
        // sans le moindre appel réseau, et l'entrée quitte la file.
        let ids = ["mr-1", "mr-2", "mr-3", "mr-4"]
        XCTAssertTrue(ids.allSatisfy { id in !manager.messages.contains(where: { $0.id == id }) },
                      "précondition: ces ids doivent être absents de `messages` pour court-circuiter le transport")
        enqueue(ids)

        startDrain()

        // La première entrée est partie: le drain dort maintenant ses 0,5 s. C'est
        // le moment où une confirmation de livraison tombe, dans la vraie vie.
        let firstLeft = await poll(upTo: 10) { self.manager.messageRetryQueue.count == 3 }
        XCTAssertTrue(firstLeft, "la première entrée aurait dû quitter la file")

        manager.removeFromRetryQueue(messageId: "mr-2")

        await waitForDrain()

        XCTAssertEqual(queuedIds(), [],
                       "tout devait partir: `mr-2` par le retrait externe, les autres par le drain")
        XCTAssertTrue(manager.permanentlyFailedMessageIds.isEmpty,
                      "aucun message n'a épuisé ses tentatives ici")
    }

    // MARK: - 2. Les entrées sont adressées par id, pas par position

    /// Retirer une entrée SANS RAPPORT au milieu du drain ne doit ni faire sauter
    /// une autre entrée, ni écrire le compteur de tentatives sur la mauvaise.
    ///
    /// L'ancienne boucle écrivait `messageRetryQueue[i].retryCount = …`: après un
    /// retrait externe, `i` ne désigne plus la même entrée. Le compteur d'un
    /// message atterrissait sur son voisin — un message pouvait ainsi être
    /// abandonné avec deux tentatives réelles, un autre re-tenté six fois.
    ///
    /// Ici chaque envoi ÉCHOUE (message `.pending` de moins de 60 s), donc les
    /// entrées restent en file et leur compteur est observable.
    func testAnUnrelatedRemovalMidDrainDoesNotMisaddressTheOthers() async {
        let ids = ["id-1", "id-2", "id-3", "id-4"]
        manager.messages.append(contentsOf: ids.map { youngPendingMessage(id: $0) })
        enqueue(ids)
        // `?? -1` plutôt qu'un `compactMap`: une entrée manquante doit faire
        // ÉCHOUER la précondition, pas disparaître de la comparaison.
        XCTAssertEqual(ids.map { retryCount(of: $0) ?? -1 }, [0, 0, 0, 0],
                       "précondition: tout le monde part de zéro tentative")

        startDrain()

        // La première entrée a pris sa tentative: le drain est engagé.
        let firstBumped = await poll(upTo: 10) { self.retryCount(of: "id-1") == 1 }
        XCTAssertTrue(firstBumped, "la première entrée aurait dû être comptée")

        manager.removeFromRetryQueue(messageId: "id-2")

        await waitForDrain()

        XCTAssertEqual(queuedIds(), ["id-1", "id-3", "id-4"],
                       "seule l'entrée retirée doit manquer, et les échecs restent en file")
        XCTAssertEqual(retryCount(of: "id-1"), 1, "`id-1` a été tenté une fois, pas deux")
        XCTAssertEqual(retryCount(of: "id-3"), 1, "`id-3` ne doit pas avoir été sauté")
        XCTAssertEqual(retryCount(of: "id-4"), 1, "`id-4` ne doit pas avoir été sauté")
    }

    // MARK: - 3. Le plafond est PAR MESSAGE

    /// Une fois `maxRetryAttempts` brûlé réseau debout, l'id part dans
    /// `permanentlyFailedMessageIds` et le balayage suivant ne le remet PAS en
    /// file avec un compteur neuf. Le message reste `.failed` et reste
    /// re-envoyable à la main — on cesse seulement de le re-piloter tout seul.
    func testTheRetryCapIsPerMessageAndTheNextSweepDoesNotResurrectIt() async {
        let identity = IdentityManager()
        identity.publicKey = myKey
        MessageManager.sharedIdentityManager = identity
        XCTAssertEqual(MessageManager.sharedIdentityManager?.publicKey, myKey,
                       "précondition: sans clé publique, enqueueFailedOutgoingForRetry() sort tout de suite")

        manager.messages.append(recentlyFailedMessage(id: "cap-1"))

        // Précondition, et c'est elle qui rend le test final non creux: le balayage
        // SAIT reprendre ce message tant qu'il n'a pas épuisé ses tentatives.
        manager.enqueueFailedOutgoingForRetry()
        XCTAssertTrue(queuedIds().contains("cap-1"),
                      "précondition: le balayage doit reprendre un `.failed` récent")
        XCTAssertEqual(retryCount(of: "cap-1"), 0)

        // Épuise le compteur. Au drain suivant, `newRetryCount > maxRetryAttempts`
        // ⇒ branche de retrait, sans aucun transport.
        guard let index = manager.messageRetryQueue.firstIndex(where: { $0.messageId == "cap-1" }) else {
            return XCTFail("l'entrée vient d'être empilée, elle doit être là")
        }
        manager.messageRetryQueue[index].retryCount = manager.maxRetryAttempts

        startDrain()
        await waitForDrain()

        XCTAssertFalse(queuedIds().contains("cap-1"), "l'entrée épuisée doit quitter la file")
        XCTAssertTrue(manager.permanentlyFailedMessageIds.contains("cap-1"),
                      "l'id doit être retenu comme définitivement échoué")

        // Le correctif tient ici, et nulle part ailleurs.
        manager.enqueueFailedOutgoingForRetry()
        XCTAssertFalse(queuedIds().contains("cap-1"),
                       "le balayage suivant ne doit pas le remettre en file avec un compteur remis à zéro")
        XCTAssertNil(retryCount(of: "cap-1"))
    }

    /// Le garde ne doit pas mettre TOUS les envois du même correspondant au
    /// silence: seul l'id épuisé est écarté, son voisin part normalement.
    func testOnlyTheExhaustedIdIsSkippedBySubsequentSweeps() async {
        let identity = IdentityManager()
        identity.publicKey = myKey
        MessageManager.sharedIdentityManager = identity

        manager.messages.append(recentlyFailedMessage(id: "cap-a"))
        manager.messages.append(recentlyFailedMessage(id: "cap-b"))
        manager.permanentlyFailedMessageIds.insert("cap-a")

        manager.enqueueFailedOutgoingForRetry()

        XCTAssertFalse(queuedIds().contains("cap-a"), "l'id épuisé est écarté")
        XCTAssertTrue(queuedIds().contains("cap-b"),
                      "son voisin, lui, doit toujours être repris — sinon le garde est trop large")
    }

    // MARK: - 4. Ordre de vidage: le plus ancien d'abord

    /// L'ancienne boucle parcourait la file À L'ENVERS (`.reversed()`), non par
    /// choix produit mais pour rendre la suppression par indice sûre en une passe:
    /// un lot de renvois arrivait donc au relais dans l'ordre inverse de l'envoi.
    /// L'adressage par id rend ce détour inutile, et l'ordre redevient FIFO.
    func testTheDrainIsOldestFirst() async {
        let ids = ["fifo-1", "fifo-2", "fifo-3"]
        enqueue(ids)   // absents de `messages` ⇒ chaque entrée « réussit » et quitte la file

        startDrain()

        var departures: [String] = []
        let sawThemAll = await poll(upTo: 15) {
            let present = Set(self.queuedIds())
            for id in ids where !present.contains(id) && !departures.contains(id) {
                departures.append(id)
            }
            return departures.count == ids.count
        }

        XCTAssertTrue(sawThemAll, "les trois entrées auraient dû quitter la file; vues: \(departures)")
        XCTAssertEqual(departures, ids,
                       "la file doit se vider du plus ancien au plus récent (l'ancienne version faisait l'inverse)")

        await waitForDrain()
    }

    // MARK: - 5. Le drain ne se marche pas dessus

    /// `isRetrying` est le seul verrou: deux drains simultanés compteraient deux
    /// tentatives pour un seul envoi. L'ancien code reposait le drapeau à la
    /// toute fin du `Task`, ce qui laissait passer les sorties anticipées; il est
    /// maintenant reposé par un `defer`.
    func testASecondDrainIsRefusedWhileOneIsRunning() async {
        let ids = ["lock-1", "lock-2", "lock-3"]
        manager.messages.append(contentsOf: ids.map { youngPendingMessage(id: $0) })
        enqueue(ids)

        startDrain()
        manager.retryFailedMessages()   // doit être un non-événement

        await waitForDrain()

        XCTAssertEqual(retryCount(of: "lock-1"), 1, "un seul drain a tourné: une seule tentative comptée")
        XCTAssertEqual(retryCount(of: "lock-2"), 1)
        XCTAssertEqual(retryCount(of: "lock-3"), 1)
        XCTAssertFalse(manager.isRetrying, "le drapeau doit retomber quand le drain se termine")
    }
}
