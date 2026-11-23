//
//  V2UndecryptableRetryTests.swift
//  OSHITests
//
//  Guards the fix for messages that produced a notification and never appeared.
//
//  A push says "you have a message" and carries no content; the app then pulls
//  the envelope and decrypts it. When the Double Ratchet is briefly out of step,
//  decryption fails — and that case used to log a line and let the ack advance,
//  so the relay deleted a message the user had already been notified about.
//
//  The fix has to hold two opposing properties at once, which is exactly why it
//  needs tests rather than a careful read:
//
//    * hold the batch back so a recoverable envelope gets more attempts;
//    * and eventually LET GO, or one permanently-undecryptable envelope freezes
//      the cursor and blocks every message behind it for the rest of the
//      install. That failure would be far worse than the one being fixed.
//

import XCTest
@testable import OSHI

final class V2UndecryptableRetryTests: XCTestCase {

    private func freshId() -> String { "msg_" + UUID().uuidString }

    private func reset() {
        UserDefaults.standard.removeObject(forKey: "oshi_v2_undecryptable_retries")
        UserDefaults.standard.removeObject(forKey: V2SyncState.firstFailureKey)
        // Sinon un test qui arme l'horloge de blocage fait lâcher les acks du
        // test suivant, et l'ordre d'exécution devient porteur.
        UserDefaults.standard.removeObject(forKey: V2SyncState.cursorStalledSinceKey)
    }

    override func setUp() { super.setUp(); reset() }
    override func tearDown() { reset(); super.tearDown() }

    // MARK: Hold, then let go

    /// The recoverable case: a ratchet that resynchronises within a few ticks
    /// must still get its message through.
    func testEnvelopeIsHeldWhileBudgetRemains() {
        let id = freshId()
        for attempt in 1...V2SyncState.maxRetries {
            XCTAssertTrue(V2SyncState.shouldRetryUndecryptable(id),
                          "attempt \(attempt) should still be retried")
        }
    }

    /// THE safety property. Without this the fix trades a lost message for a
    /// dead mailbox.
    func testEnvelopeIsReleasedOnceBudgetIsSpent() {
        let id = freshId()
        for _ in 1...V2SyncState.maxRetries {
            _ = V2SyncState.shouldRetryUndecryptable(id)
        }
        XCTAssertFalse(V2SyncState.shouldRetryUndecryptable(id),
                       "budget spent — the ack must be allowed through or the queue never drains")
        // And it stays released; it must not recover budget by being asked again.
        XCTAssertFalse(V2SyncState.shouldRetryUndecryptable(id))
        XCTAssertFalse(V2SyncState.shouldRetryUndecryptable(id))
    }

    /// Budgets are per envelope: one poisoned message must not consume the
    /// allowance of the next one behind it.
    func testBudgetsAreIndependentPerEnvelope() {
        let bad = freshId(), good = freshId()
        for _ in 1...(V2SyncState.maxRetries + 3) {
            _ = V2SyncState.shouldRetryUndecryptable(bad)
        }
        XCTAssertFalse(V2SyncState.shouldRetryUndecryptable(bad))
        XCTAssertTrue(V2SyncState.shouldRetryUndecryptable(good),
                      "a fresh envelope inherited the exhausted budget of another")
    }

    // MARK: Le plafond de tics ne suffit pas — mesuré en production

    /// __ACK_HOLD_SURVIVES_EVICTION_2026_10_20__ Le compteur vit dans un
    /// dictionnaire plafonné à `seenCap`, et l'expulsion s'écrivait
    /// `Array(dict).suffix(seenCap)` — or un Dictionary Swift n'a PAS d'ordre,
    /// donc elle gardait 500 entrées arbitraires. Avec un arriéré plus long que
    /// le plafond, le compteur de l'enveloppe qui retient l'ack était jeté à
    /// chaque tic, son budget repartait de 1, et `testEnvelopeIsReleasedOnce
    /// BudgetIsSpent` juste au-dessus devenait un mensonge en conditions
    /// réelles: la boîte ne se vidait plus JAMAIS.
    ///
    /// Mesuré sur un vrai compte le 20/10: 7438 enveloppes en attente sur le
    /// relais, la plus vieille de 13 jours, curseur gelé, 3,8 Mo re-téléchargés
    /// à chaque sondage — et toutes les conversations bloquées, pas seulement
    /// celle du pair cassé.
    /// ⚠️ **Ne PAS ré-affirmer ici « une enveloppe expulsée ne récupère pas son
    /// budget ».** C'est l'assertion que j'avais écrite d'abord, et elle est
    /// infaisable: l'état est plafonné, donc une entrée expulsée revient
    /// forcément avec un compteur neuf — un horodatage par enveloppe est
    /// remboursé exactement comme le compteur. La propriété qu'on veut
    /// n'appartient pas à l'enveloppe, elle appartient au CURSEUR, et c'est le
    /// test suivant qui la tient.
    ///
    /// Ce que ce test garde: l'expulsion ne doit pas être ARBITRAIRE. Avec
    /// `Array(dict).suffix(cap)` sur un Dictionary Swift (non ordonné), elle
    /// l'était, et le compteur de l'enveloppe du tic courant pouvait partir au
    /// lieu d'un vieux — donc ce compteur n'avançait jamais.
    func testEvictionKeepsTheRecentFailuresNotArbitraryOnes() {
        for _ in 1...(V2SyncState.seenCap + 50) { _ = V2SyncState.shouldRetryUndecryptable(freshId()) }
        let id = freshId()
        for attempt in 1...V2SyncState.maxRetries {
            XCTAssertTrue(V2SyncState.shouldRetryUndecryptable(id), "tic \(attempt)")
            // Du bruit après chaque tic: c'est la forme du vrai arriéré.
            for _ in 1...50 { _ = V2SyncState.shouldRetryUndecryptable(freshId()) }
        }
        XCTAssertFalse(V2SyncState.shouldRetryUndecryptable(id),
                       "le compteur n'a pas avancé malgré 8 tics — il se faisait expulser")
    }

    /// **LA garantie, et la seule qui débloque une boîte déjà gelée.** Quel que
    /// soit l'arriéré, quel que soit l'état des compteurs: si le curseur n'a pas
    /// avancé depuis `maxAckHoldSeconds`, plus rien ne retient l'ack — même une
    /// enveloppe toute neuve avec un budget intact.
    ///
    /// Mesuré le 20/10 sur un vrai compte: 7453 enveloppes en attente, la plus
    /// vieille de 13 jours, curseur gelé, 3,8 Mo re-téléchargés à chaque
    /// sondage, et TOUTES les conversations bloquées. Sans cette borne, rien
    /// dans le code ne pouvait en sortir.
    func testNothingHoldsTheAckOnceTheCursorHasBeenStalledTooLong() {
        let t0 = Date()
        V2SyncState.cursorStalledSince = t0.timeIntervalSince1970
        let fresh = freshId()
        XCTAssertTrue(V2SyncState.shouldRetryUndecryptable(fresh, clock: t0),
                      "curseur qui vient de bouger: la générosité normale s'applique")

        let late = t0.addingTimeInterval(V2SyncState.maxAckHoldSeconds + 1)
        XCTAssertFalse(V2SyncState.shouldRetryUndecryptable(freshId(), clock: late),
                       "curseur bloqué trop longtemps: même une enveloppe neuve ne doit plus rien retenir")
    }

    /// Et l'horloge repart dès que la boîte se vide, sinon la borne se
    /// déclencherait une fois puis resterait armée pour toujours.
    func testAdvancingTheCursorRestartsTheStallClock() {
        V2SyncState.cursorStalledSince = Date().timeIntervalSince1970 - (V2SyncState.maxAckHoldSeconds + 60)
        XCTAssertFalse(V2SyncState.shouldRetryUndecryptable(freshId()), "bloqué: on lâche")
        V2SyncState.cursor = V2SyncState.cursor + 1   // un ack a passé
        XCTAssertTrue(V2SyncState.shouldRetryUndecryptable(freshId()),
                      "le curseur a avancé, la retenue normale doit revenir")
    }

    /// Une install neuve n'a pas d'horloge enregistrée: elle ne doit pas se
    /// croire bloquée depuis 1970 et lâcher tous ses acks au premier échec.
    func testAFreshInstallIsNotConsideredStalled() {
        UserDefaults.standard.removeObject(forKey: V2SyncState.cursorStalledSinceKey)
        XCTAssertTrue(V2SyncState.shouldRetryUndecryptable(freshId()),
                      "install neuve traitée comme bloquée depuis l'epoch")
    }

    // MARK: Recovery

    /// When an envelope finally decodes, its failure count is dropped so a later
    /// unrelated failure starts from a full budget.
    func testSuccessClearsTheCount() {
        let id = freshId()
        for _ in 1...(V2SyncState.maxRetries - 1) {
            _ = V2SyncState.shouldRetryUndecryptable(id)
        }
        V2SyncState.clearRetries(id)
        for attempt in 1...V2SyncState.maxRetries {
            XCTAssertTrue(V2SyncState.shouldRetryUndecryptable(id),
                          "after a success the budget should be full again (attempt \(attempt))")
        }
    }

    /// Clearing something that was never counted is a no-op, not a crash — the
    /// success path calls it for every message, most of which never failed.
    func testClearingAnUncountedEnvelopeIsHarmless() {
        V2SyncState.clearRetries(freshId())
        let id = freshId()
        XCTAssertTrue(V2SyncState.shouldRetryUndecryptable(id))
    }

    // MARK: Bookkeeping

    /// The counters live in UserDefaults, not a database. A long-lived install
    /// that sees many failures must not grow an entry per failure forever.
    func testCounterStoreStaysBounded() {
        for _ in 0..<700 { _ = V2SyncState.shouldRetryUndecryptable(freshId()) }
        XCTAssertLessThanOrEqual((UserDefaults.standard.dictionary(forKey: "oshi_v2_undecryptable_retries")?.count ?? 0), 500,
                                 "retry bookkeeping grew unbounded in UserDefaults")
    }

    /// A budget of one would make the fix pointless, and a huge one would keep a
    /// dead envelope in front of the queue for a long time.
    func testBudgetIsInAUsefulRange() {
        XCTAssertGreaterThan(V2SyncState.maxRetries, 2)
        XCTAssertLessThanOrEqual(V2SyncState.maxRetries, 20)
    }
}
