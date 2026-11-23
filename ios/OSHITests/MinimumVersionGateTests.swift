//
//  MinimumVersionGateTests.swift
//  OSHI — prouver qu'un portillon de version ne peut pas murer qui que ce soit.
//
//  Ce fichier ne teste pas d'abord une fonctionnalite, il teste une PROMESSE:
//  « si tu mets un controle de version, qu'il ECHOUE OUVERT quand le serveur
//  est injoignable, et qu'il NE BLOQUE JAMAIS la messagerie hors ligne. »
//
//  Les assertions les plus importantes du fichier sont donc negatives: aucune
//  panne ne produit d'etat bloquant, et aucun etat, quel qu'il soit, ne rend
//  `blocksMessaging == true`.
//

import XCTest
@testable import OSHI

@MainActor
final class MinimumVersionGateTests: XCTestCase {

    // MARK: - ECHOUER OUVERT

    /// Serveur injoignable. Le cas le plus frequent sur une app qui se veut
    /// utilisable dans le metro.
    func testUnreachableServerProducesNoGate() async {
        let gate = makeGate(current: "1.0.9") { throw URLError(.notConnectedToInternet) }
        await gate.refresh()
        XCTAssertEqual(gate.state, .noGate)
        XCTAssertFalse(gate.shouldShowPrompt)
    }

    func testTimeoutProducesNoGate() async {
        let gate = makeGate(current: "1.0.9") { throw URLError(.timedOut) }
        await gate.refresh()
        XCTAssertEqual(gate.state, .noGate)
    }

    /// 500 — `V2Client.fetchClientConfig` leve sur tout code non-2xx, donc le
    /// portillon voit une erreur, exactement comme une coupure reseau.
    func testHTTP500ProducesNoGate() async {
        let gate = makeGate(current: "1.0.9") {
            throw V2.ClientError.http(status: 500, reason: "config fetch")
        }
        await gate.refresh()
        XCTAssertEqual(gate.state, .noGate)
    }

    func testHTTP404ProducesNoGate() async {
        let gate = makeGate(current: "1.0.9") {
            throw V2.ClientError.http(status: 404, reason: "config fetch")
        }
        await gate.refresh()
        XCTAssertEqual(gate.state, .noGate)
    }

    /// Corps illisible sous toutes ses formes.
    func testGarbageBodyProducesNoGate() async {
        let bodies = [
            "not json at all",
            "",
            "<html><body>502 Bad Gateway</body></html>",
            "[1,2,3]",
            #"{"min_supported_version": 44}"#,              // mauvais type
            #"{"min_supported_version": {"a": 1}}"#,
            #"{"min_supported_version": "latest"}"#,        // present mais illisible
            #"{"min_supported_version": "v1.0.44"}"#,
            #"{"min_supported_version": ""}"#,
            #"{"truncated": "#,
        ]
        for body in bodies {
            let gate = makeGate(current: "1.0.9") { Data(body.utf8) }
            await gate.refresh()
            XCTAssertEqual(gate.state, .noGate, "corps: \(body)")
            XCTAssertFalse(gate.shouldShowPrompt, "corps: \(body)")
        }
    }

    /// Reponse parfaitement valide qui ne parle pas de version minimale — la
    /// forme que le serveur renvoie AUJOURD'HUI.
    func testTodaysServerPayloadProducesNoGate() async {
        let gate = makeGate(current: "1.0.9") {
            Data(#"{"v2_enabled":true,"rollout_percent":100,"min_build":0}"#.utf8)
        }
        await gate.refresh()
        XCTAssertEqual(gate.state, .noGate)
    }

    // MARK: - LE PORTILLON NE BLOQUE JAMAIS

    /// La promesse, verifiee sur tous les etats que le type peut prendre.
    func testNoStateEverBlocksSending() {
        let states: [MinimumVersionGateState] = [
            .noGate,
            .updateAvailable(minimum: "1.0.44"),
            .updateAvailable(minimum: "99.0.0"),
        ]
        for state in states {
            XCTAssertFalse(state.blocksMessaging,
                           "aucun etat de ce portillon ne doit empecher d'envoyer: \(state)")
        }
    }

    /// Et verifiee AUSSI sur ce que produisent les chemins reels, pas seulement
    /// sur des valeurs ecrites a la main.
    func testEveryOutcomeOfEveryPathIsNonBlocking() async {
        let scenarios: [() async throws -> Data] = [
            { throw URLError(.notConnectedToInternet) },
            { throw V2.ClientError.http(status: 500, reason: "x") },
            { Data("garbage".utf8) },
            { Data(#"{"min_supported_version":"1.0.44"}"#.utf8) },
            { Data(#"{"min_supported_version":"0.0.1"}"#.utf8) },
            { Data(#"{}"#.utf8) },
        ]
        for scenario in scenarios {
            let gate = makeGate(current: "1.0.9", fetch: scenario)
            await gate.refresh()
            XCTAssertFalse(gate.state.blocksMessaging)
        }
    }

    // MARK: - Le comportement attendu quand tout marche

    func testOlderBuildIsPrompted() async {
        let gate = makeGate(current: "1.0.9") {
            Data(#"{"min_supported_version":"1.0.44"}"#.utf8)
        }
        await gate.refresh()
        XCTAssertEqual(gate.state, .updateAvailable(minimum: "1.0.44"))
        XCTAssertTrue(gate.shouldShowPrompt)
        XCTAssertFalse(gate.state.blocksMessaging, "on propose, on n'impose pas")
    }

    /// La comparaison est NUMERIQUE jusque dans le portillon: en chaines,
    /// "1.0.9" >= "1.0.44" et personne ne serait jamais prevenu.
    func testTheGateComparesNumerically() {
        XCTAssertEqual(MinimumVersionGate.decide(currentVersion: "1.0.9", minimumSupported: "1.0.43"),
                       .updateAvailable(minimum: "1.0.43"))
        XCTAssertEqual(MinimumVersionGate.decide(currentVersion: "1.0.43", minimumSupported: "1.0.9"),
                       .noGate)
    }

    func testTheRequiredVersionItselfIsNotPrompted() {
        XCTAssertEqual(MinimumVersionGate.decide(currentVersion: "1.0.44", minimumSupported: "1.0.44"),
                       .noGate, "obeir doit faire disparaitre le rappel")
        XCTAssertEqual(MinimumVersionGate.decide(currentVersion: "1.0.45", minimumSupported: "1.0.44"),
                       .noGate)
    }

    func testUnreadableVersionsOnEitherSideMeanNoGate() {
        XCTAssertEqual(MinimumVersionGate.decide(currentVersion: "", minimumSupported: "1.0.44"), .noGate)
        XCTAssertEqual(MinimumVersionGate.decide(currentVersion: "1.0.9", minimumSupported: "soon"), .noGate)
        XCTAssertEqual(MinimumVersionGate.decide(currentVersion: "1.0.9", minimumSupported: nil), .noGate)
        XCTAssertEqual(MinimumVersionGate.decide(currentVersion: "1.0.9", minimumSupported: "  "), .noGate)
    }

    // MARK: - Un levier par plateforme

    /// Le piege concret: iOS numerote en 1.0.x, Android en 1.6.x. Un
    /// `min_supported_version` unique regle sur la valeur Android dirait a
    /// CHAQUE appareil iOS qu'il est en retard, pour toujours.
    func testIOSKeyWinsOverTheSharedOne() async {
        let gate = makeGate(current: "1.0.43") {
            Data(#"{"min_supported_version":"1.6.20","ios_min_supported_version":"1.0.44"}"#.utf8)
        }
        await gate.refresh()
        XCTAssertEqual(gate.state, .updateAvailable(minimum: "1.0.44"),
                       "le seuil iOS doit gagner, pas le numero Android")
    }

    func testSharedKeyIsUsedWhenThereIsNoIOSKey() async {
        let gate = makeGate(current: "1.0.43") {
            Data(#"{"min_supported_version":"1.0.44"}"#.utf8)
        }
        await gate.refresh()
        XCTAssertEqual(gate.state, .updateAvailable(minimum: "1.0.44"))
    }

    /// Une cle iOS presente mais cassee ne doit pas se faire remplacer en
    /// silence par un reglage prevu pour l'autre plateforme.
    func testABrokenIOSKeyDoesNotFallBackToTheSharedOne() async {
        let gate = makeGate(current: "1.0.43") {
            Data(#"{"min_supported_version":"1.6.20","ios_min_supported_version":"soon"}"#.utf8)
        }
        await gate.refresh()
        XCTAssertEqual(gate.state, .noGate)
    }

    /// Et le cas qui motive tout ca: un serveur regle pour Android seul ne doit
    /// pas murer iOS de rappels. Il en produit un ici — 1.0.43 EST inferieure a
    /// 1.6.20 — ce qui est precisement pourquoi la cle specifique existe.
    /// L'assertion qui compte est la derniere: meme dans ce cas, rien ne bloque.
    func testAnAndroidOnlyNumberStillCannotBlockIOS() async {
        let gate = makeGate(current: "1.0.43") {
            Data(#"{"min_supported_version":"1.6.20"}"#.utf8)
        }
        await gate.refresh()
        XCTAssertFalse(gate.state.blocksMessaging)
    }

    // MARK: - Le cache

    /// « Un lancement hors ligne se comporte comme le dernier lancement en
    /// ligne » — mais sans jamais bloquer davantage.
    func testLastGoodAnswerSurvivesAnOfflineRelaunch() async {
        let defaults = freshDefaults(#function)

        let online = MinimumVersionGate(defaults: defaults, currentVersion: "1.0.9") {
            Data(#"{"min_supported_version":"1.0.44"}"#.utf8)
        }
        await online.refresh()
        XCTAssertEqual(online.state, .updateAvailable(minimum: "1.0.44"))

        // Relancement sans reseau: l'etat de depart vient du cache.
        let offline = MinimumVersionGate(defaults: defaults, currentVersion: "1.0.9") {
            throw URLError(.notConnectedToInternet)
        }
        XCTAssertEqual(offline.state, .updateAvailable(minimum: "1.0.44"))
        XCTAssertFalse(offline.state.blocksMessaging)
        await offline.refresh()
        XCTAssertEqual(offline.state, .updateAvailable(minimum: "1.0.44"),
                       "une panne ne doit ni creer ni effacer un rappel")
    }

    /// Un corps illisible ne doit pas empoisonner un cache valide.
    func testGarbageDoesNotPoisonAGoodCache() async {
        let defaults = freshDefaults(#function)
        let gate = MinimumVersionGate(defaults: defaults, currentVersion: "1.0.9") {
            Data(#"{"min_supported_version":"1.0.44"}"#.utf8)
        }
        await gate.refresh()

        let poisoned = MinimumVersionGate(defaults: defaults, currentVersion: "1.0.9") {
            Data("<html>502</html>".utf8)
        }
        await poisoned.refresh()
        XCTAssertEqual(poisoned.state, .updateAvailable(minimum: "1.0.44"))
        XCTAssertEqual(defaults.string(forKey: MinimumVersionGate.cacheKey), "1.0.44")
    }

    /// En revanche, une bonne reponse qui dit « plus rien a exiger » DOIT
    /// effacer le cache — sinon on ne peut plus jamais retirer le rappel sans
    /// une livraison App Store.
    func testAServerThatDropsTheRequirementClearsTheCache() async {
        let defaults = freshDefaults(#function)
        let gate = MinimumVersionGate(defaults: defaults, currentVersion: "1.0.9") {
            Data(#"{"min_supported_version":"1.0.44"}"#.utf8)
        }
        await gate.refresh()
        XCTAssertTrue(gate.state.shouldPrompt)

        let relaxed = MinimumVersionGate(defaults: defaults, currentVersion: "1.0.9") {
            Data(#"{"v2_enabled":true}"#.utf8)
        }
        await relaxed.refresh()
        XCTAssertEqual(relaxed.state, .noGate)
        XCTAssertNil(defaults.string(forKey: MinimumVersionGate.cacheKey))
    }

    // MARK: - L'ecart

    func testDismissSilencesThisMinimumOnly() async {
        let defaults = freshDefaults(#function)
        let gate = MinimumVersionGate(defaults: defaults, currentVersion: "1.0.9") {
            Data(#"{"min_supported_version":"1.0.44"}"#.utf8)
        }
        await gate.refresh()
        XCTAssertTrue(gate.shouldShowPrompt)

        gate.dismissPrompt()
        XCTAssertFalse(gate.shouldShowPrompt)
        XCTAssertEqual(gate.state, .updateAvailable(minimum: "1.0.44"),
                       "ecarter la banniere ne change pas le fait")
        XCTAssertFalse(gate.state.blocksMessaging)

        // Un minimum plus recent est un fait NOUVEAU: il se redit une fois.
        let later = MinimumVersionGate(defaults: defaults, currentVersion: "1.0.9") {
            Data(#"{"min_supported_version":"1.0.50"}"#.utf8)
        }
        await later.refresh()
        XCTAssertTrue(later.shouldShowPrompt)
    }

    // MARK: - L'etranglement

    func testRefreshIfNeededIsThrottled() async {
        let counter = FetchCounter()
        let gate = MinimumVersionGate(defaults: freshDefaults(#function), currentVersion: "1.0.9") {
            await counter.bump()
            return Data(#"{}"#.utf8)
        }
        let start = Date()
        await gate.refreshIfNeeded(now: start)
        await gate.refreshIfNeeded(now: start.addingTimeInterval(5))
        await gate.refreshIfNeeded(now: start.addingTimeInterval(30))
        let after = await counter.count
        XCTAssertEqual(after, 1, "un va-et-vient entre apps ne doit pas marteler l'endpoint")

        await gate.refreshIfNeeded(now: start.addingTimeInterval(120))
        let later = await counter.count
        XCTAssertEqual(later, 2, "passe le delai, on redemande")
    }

    // MARK: - Outils

    private actor FetchCounter {
        private(set) var count = 0
        func bump() { count += 1 }
    }

    private func makeGate(current: String,
                          fetch: @escaping () async throws -> Data) -> MinimumVersionGate {
        MinimumVersionGate(defaults: freshDefaults(UUID().uuidString),
                           currentVersion: current,
                           fetch: fetch)
    }

    private func freshDefaults(_ name: String) -> UserDefaults {
        let suite = "MinimumVersionGateTests.\(name).\(UUID().uuidString)"
        UserDefaults().removePersistentDomain(forName: suite)
        return UserDefaults(suiteName: suite)!
    }
}
