//
//  PushRetryQueueTests.swift
//  OSHITests
//
//  __PUSH_RETRY_SEAM_2026_09_12__ La file des poussées ratées.
//
//  Cette classe n'avait AUCUN test de comportement: la seule chose qui la citait
//  lisait son code source au grep. Or c'est un objet dont tout l'intérêt est
//  invisible à l'écran — le destinataire ne voit rien du tout quand elle se
//  trompe, et c'est précisément la panne qu'elle existe pour éviter.
//
//  Le test central est `testAPushEnqueuedDuringADrainSurvivesIt`: `drain()` est
//  `@MainActor` et `await` le réseau, donc le main actor est LIBRE pendant le vol
//  et `enqueue(...)` (appelé par chaque catch de MessageManager) peut ajouter une
//  poussée en pleine purge. La queue se réécrivait alors à plat depuis
//  l'instantané du début de boucle, et cette poussée-là disparaissait en silence.
//

import XCTest
@testable import OSHI

@MainActor
final class PushRetryQueueTests: XCTestCase {

    private var dir: URL!
    private var store: URL { dir.appendingPathComponent("push_retry_queue.json") }

    override func setUp() async throws {
        // Un dossier NEUF par test: jamais le vrai store d'Application Support,
        // et aucun test ne peut hériter de la file d'un autre.
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("push-retry-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        if let dir { try? FileManager.default.removeItem(at: dir) }
    }

    // MARK: - Outillage

    private enum SendFailure: Error { case offline }

    /// Boîte de référence: la fermeture injectée et le corps du test doivent voir
    /// la MÊME valeur (l'horloge qu'on avance, le compteur d'envois).
    private final class Box<T> {
        var value: T
        init(_ value: T) { self.value = value }
    }

    @discardableResult
    private func enqueue(_ queue: PushRetryQueue, id: String, recipient: String = "peer") -> String {
        queue.enqueue(
            recipientPublicKey: recipient,
            type: "message",
            title: "t",
            body: "b",
            additionalData: ["messageId": id]
        )
        return "\(recipient)|message|\(id)"
    }

    // MARK: - LA régression: ce qui arrive pendant l'await

    /// 🔴 Le test de cette classe. Une poussée mise en file PENDANT la suspension
    /// d'un drain doit être encore là après.
    ///
    /// Échoue contre l'ancien `entries = newEntries`: l'instantané du début de
    /// boucle ne contient que « m1 », qui part avec succès, donc la file finit
    /// VIDE et « m2 » n'est jamais renvoyée — le destinataire n'est jamais
    /// réveillé pour ce message-là.
    func testAPushEnqueuedDuringADrainSurvivesIt() async {
        let gate = Box<CheckedContinuation<Void, Never>?>(nil)
        let sent = Box<[String]>([])
        let inFlight = expectation(description: "l'envoi de m1 est en vol")

        let queue = PushRetryQueue(storeURL: store, sender: { entry in
            sent.value.append(entry.dedupKey)
            inFlight.fulfill()
            await withCheckedContinuation { gate.value = $0 }
        })
        let m1 = enqueue(queue, id: "m1")

        let drain = Task { await queue.drain() }
        await fulfillment(of: [inFlight], timeout: 5)

        // Ici le drain est suspendu et le main actor est libre: c'est très
        // exactement la fenêtre dans laquelle un catch de MessageManager appelle
        // enqueue pour une AUTRE poussée qui vient d'échouer.
        let m2 = enqueue(queue, id: "m2")
        XCTAssertEqual(queue.pending.count, 2,
                       "m1 est encore en file (le drain ne l'a pas encore soldée) et m2 vient de s'ajouter")

        guard let resume = gate.value else {
            return XCTFail("la continuation n'a pas été capturée — le harnais est faux, pas le code")
        }
        resume.resume()
        await drain.value

        XCTAssertEqual(sent.value, [m1], "m2 n'appartient pas à ce drain: elle ne doit pas partir maintenant")
        XCTAssertEqual(queue.pending.map(\.dedupKey), [m2],
                       "m1 est partie donc elle sort; m2 est arrivée pendant l'await et doit SURVIVRE")
        XCTAssertEqual(queue.pending.first?.attempts, 0,
                       "et elle survit intacte: son essai n'a pas été consommé par un drain qu'elle n'a pas vécu")

        let reborn = PushRetryQueue(storeURL: store, sender: { _ in })
        XCTAssertEqual(reborn.pending.map(\.dedupKey), [m2],
                       "la survivante doit aussi être sur le disque, sinon elle meurt à la fermeture")
    }

    // MARK: - Dédup

    /// La même poussée demandée deux fois reste UNE poussée: sinon le
    /// destinataire reçoit deux bannières pour un seul message.
    func testEnqueueIsIdempotentOnDedupKey() async {
        let queue = PushRetryQueue(storeURL: store, sender: { _ in throw SendFailure.offline })
        enqueue(queue, id: "m1")
        enqueue(queue, id: "m1")
        XCTAssertEqual(queue.pendingCount, 1)

        // Un messageId différent est une AUTRE poussée: la dédup ne doit pas
        // avaler tout ce qui va au même pair.
        enqueue(queue, id: "m2")
        XCTAssertEqual(queue.pendingCount, 2)

        // Et un doublon ne remet pas le compteur d'essais à zéro — sinon un
        // envoi qui rate en boucle ne franchirait jamais la borne des 5.
        await queue.drain()
        XCTAssertEqual(queue.pending.map(\.attempts), [1, 1])
        enqueue(queue, id: "m1")
        XCTAssertEqual(queue.pending.first?.attempts, 1)
    }

    // MARK: - Succès / échec

    /// Une poussée passée sort de la file: la garder ferait sonner le téléphone
    /// d'en face une deuxième fois au prochain premier plan.
    func testASuccessfulSendRemovesTheEntry() async {
        let sent = Box<[String]>([])
        let queue = PushRetryQueue(storeURL: store, sender: { sent.value.append($0.dedupKey) })
        let key = enqueue(queue, id: "m1")

        await queue.drain()

        XCTAssertEqual(sent.value, [key])
        XCTAssertTrue(queue.pending.isEmpty)
        let reborn = PushRetryQueue(storeURL: store, sender: { _ in })
        XCTAssertTrue(reborn.pending.isEmpty, "et le disque est à jour: pas de résurrection au relancement")
    }

    /// Une poussée ratée reste en file — c'est toute la raison d'être de l'objet —
    /// et l'essai est COMPTÉ, sinon la borne des 5 ne tombe jamais.
    func testAFailedSendKeepsTheEntryAndCountsTheAttempt() async {
        let queue = PushRetryQueue(storeURL: store, sender: { _ in throw SendFailure.offline })
        let key = enqueue(queue, id: "m1")

        await queue.drain()

        XCTAssertEqual(queue.pending.map(\.dedupKey), [key])
        XCTAssertEqual(queue.pending.first?.attempts, 1)
        XCTAssertNotNil(queue.pending.first?.lastAttemptAt, "sans horodatage, le backoff n'a rien à comparer")
    }

    // MARK: - Backoff

    /// Deux premiers plans en dix secondes ne doivent pas donner deux appels
    /// réseau: le backoff est la seule chose qui empêche la file de marteler un
    /// serveur déjà en peine.
    func testASecondDrainInsideTheBackoffWindowDoesNotResend() async {
        let clock = Box(Date(timeIntervalSince1970: 1_000_000))
        let calls = Box(0)
        let queue = PushRetryQueue(
            storeURL: store,
            now: { clock.value },
            sender: { _ in calls.value += 1; throw SendFailure.offline }
        )
        enqueue(queue, id: "m1")

        await queue.drain()
        XCTAssertEqual(calls.value, 1)

        clock.value = clock.value.addingTimeInterval(29)
        await queue.drain()
        XCTAssertEqual(calls.value, 1, "29 s < 30 s: rien ne repart")
        XCTAssertEqual(queue.pending.first?.attempts, 1, "et l'essai n'est pas décompté pour rien")

        clock.value = clock.value.addingTimeInterval(2)     // 31 s au total
        await queue.drain()
        XCTAssertEqual(calls.value, 2, "passé 30 s, la file réessaie")
    }

    // MARK: - Les deux bornes: 5 essais, 10 minutes

    /// La file abandonne après 5 essais. Sans cette borne, une poussée vers un
    /// pair définitivement injoignable est rejouée à chaque premier plan, pour
    /// toujours.
    func testTheEntryIsDroppedAfterFiveFailedAttempts() async {
        let clock = Box(Date(timeIntervalSince1970: 1_000_000))
        let calls = Box(0)
        let queue = PushRetryQueue(
            storeURL: store,
            now: { clock.value },
            sender: { _ in calls.value += 1; throw SendFailure.offline }
        )
        enqueue(queue, id: "m1")

        for _ in 0..<5 {
            await queue.drain()
            clock.value = clock.value.addingTimeInterval(31)   // passe le backoff
        }
        XCTAssertEqual(calls.value, 5)
        XCTAssertEqual(queue.pending.first?.attempts, 5, "encore en file, mais à bout d'essais")

        await queue.drain()
        XCTAssertEqual(calls.value, 5, "le 6e drain ne rappelle pas le réseau")
        XCTAssertTrue(queue.pending.isEmpty, "et l'entrée est jetée, pas gardée à vie")
    }

    /// Une poussée dit « réveille-toi, il y a du neuf ». Passé 10 minutes le
    /// message n'est plus une nouvelle: la bannière sonnerait pour quelque chose
    /// que le destinataire a déjà lu. On la jette MÊME s'il lui reste des essais.
    func testAPushOlderThanTenMinutesIsDroppedEvenWithEveryAttemptLeft() async {
        let clock = Box(Date(timeIntervalSince1970: 2_000_000))
        let calls = Box(0)
        let queue = PushRetryQueue(
            storeURL: store,
            now: { clock.value },
            sender: { _ in calls.value += 1 }
        )
        enqueue(queue, id: "vieux")
        XCTAssertEqual(queue.pending.first?.attempts, 0, "aucun essai consommé: seul l'âge doit la tuer")

        clock.value = clock.value.addingTimeInterval(601)
        await queue.drain()

        XCTAssertEqual(calls.value, 0, "rien ne part pour un message qui n'est plus une nouvelle")
        XCTAssertTrue(queue.pending.isEmpty)
    }

    /// Le contrôle positif de la règle précédente: juste en dessous de la borne,
    /// la poussée part. Sans lui, « tout jeter » passerait le test du haut.
    func testAPushJustUnderTenMinutesIsStillSent() async {
        let clock = Box(Date(timeIntervalSince1970: 2_000_000))
        let calls = Box(0)
        let queue = PushRetryQueue(
            storeURL: store,
            now: { clock.value },
            sender: { _ in calls.value += 1 }
        )
        enqueue(queue, id: "frais")

        clock.value = clock.value.addingTimeInterval(599)
        await queue.drain()

        XCTAssertEqual(calls.value, 1)
        XCTAssertTrue(queue.pending.isEmpty, "envoyée avec succès, donc soldée")
    }

    // MARK: - Survivre à la fermeture de l'app

    /// La file sert au cas où l'envoi rate; un ratage arrive souvent juste avant
    /// que l'app soit tuée. Ce qui n'est pas sur le disque n'existe pas.
    func testTheQueueAndItsAttemptCountSurviveAFreshInstance() async throws {
        let clock = Box(Date(timeIntervalSince1970: 3_000_000))
        var keys: [String] = []
        do {
            let queue = PushRetryQueue(
                storeURL: store,
                now: { clock.value },
                sender: { _ in throw SendFailure.offline }
            )
            keys = [enqueue(queue, id: "m1"), enqueue(queue, id: "m2", recipient: "peer-B")]
            await queue.drain()
            XCTAssertEqual(queue.pending.count, 2)
        }

        // Instance NEUVE sur le même fichier: c'est ce que fait un relancement.
        let reborn = PushRetryQueue(storeURL: store, now: { clock.value }, sender: { _ in })
        XCTAssertEqual(reborn.pending.map(\.dedupKey), keys)
        XCTAssertEqual(reborn.pending.map(\.attempts), [1, 1],
                       "le compte d'essais doit traverser: sinon la borne des 5 repart de zéro à chaque lancement")
        let first = try XCTUnwrap(reborn.pending.first)
        XCTAssertEqual(first.title, "t")
        XCTAssertEqual(first.body, "b")
        XCTAssertEqual(first.additionalData["messageId"], "m1",
                       "la charge utile doit être intacte, c'est elle qu'on renvoie")
        XCTAssertEqual(first.firstQueuedAt.timeIntervalSince1970, 3_000_000, accuracy: 1,
                       "l'âge est une date ABSOLUE — relative, elle repartirait de zéro et la borne des 10 min ne tomberait jamais")

        // Et la file relue est bien vivante: elle renvoie ce qu'elle a rechargé.
        clock.value = clock.value.addingTimeInterval(31)
        await reborn.drain()
        XCTAssertTrue(reborn.pending.isEmpty, "les deux entrées relues du disque sont reparties et ont abouti")
    }
}
