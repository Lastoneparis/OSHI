//
//  TickerQuoteSnapshotTests.swift
//  OSHITests
//
//  Ce qui est gardé ici, c'est la promesse: le cours affiché sous un message
//  est celui du moment où ce message a été écrit, et il ne rebouge plus. Une
//  régression y serait invisible à la lecture du code — elle se verrait des
//  jours plus tard, sur un vieux fil, sous la forme d'un chiffre qui ne
//  correspond à rien de ce qui a été dit.
//

import XCTest
@testable import OSHI

@MainActor
final class TickerQuoteSnapshotTests: XCTestCase {

    private var store: TickerQuoteSnapshots!
    private var url: URL!

    override func setUp() async throws {
        url = FileManager.default.temporaryDirectory
            .appendingPathComponent("snap_\(UUID().uuidString).json")
        store = TickerQuoteSnapshots(storeURL: url)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: url)
    }

    private func quote(_ symbol: String, _ price: Double, prev: Double = 100) -> TickerQuoteFetcher.Quote {
        TickerQuoteFetcher.Quote(symbol: symbol, price: price, previousClose: prev,
                                 currency: "USD", series: [prev, price])
    }

    func testNothingIsFrozenUntilSomethingIsCaptured() {
        XCTAssertNil(store.snapshot(messageId: "m1", symbol: "AAPL"))
    }

    func testACapturedQuoteComesBack() {
        store.capture(messageId: "m1", symbol: "AAPL", quote: quote("AAPL", 228.5))
        let s = store.snapshot(messageId: "m1", symbol: "AAPL")
        XCTAssertEqual(s?.price, 228.5)
        XCTAssertEqual(s?.currency, "USD")
    }

    /// LE test de ce fichier. Un second relevé ne doit RIEN changer: c'est ce
    /// qui distingue « le cours de ce message » de « le cours d'aujourd'hui ».
    func testASecondCaptureIsIgnored() {
        store.capture(messageId: "m1", symbol: "AAPL", quote: quote("AAPL", 228.5))
        store.capture(messageId: "m1", symbol: "AAPL", quote: quote("AAPL", 311.0))
        XCTAssertEqual(store.snapshot(messageId: "m1", symbol: "AAPL")?.price, 228.5)
    }

    /// Deux messages qui parlent du même titre sont deux instants distincts.
    func testEachMessageKeepsItsOwnPrice() {
        store.capture(messageId: "m1", symbol: "AAPL", quote: quote("AAPL", 228.5))
        store.capture(messageId: "m2", symbol: "AAPL", quote: quote("AAPL", 311.0))
        XCTAssertEqual(store.snapshot(messageId: "m1", symbol: "AAPL")?.price, 228.5)
        XCTAssertEqual(store.snapshot(messageId: "m2", symbol: "AAPL")?.price, 311.0)
    }

    /// Un message peut porter deux symboles; ils ne doivent pas s'écraser.
    func testTwoSymbolsInOneMessageDoNotCollide() {
        store.capture(messageId: "m1", symbol: "AAPL", quote: quote("AAPL", 228.5))
        store.capture(messageId: "m1", symbol: "TSLA", quote: quote("TSLA", 402.1))
        XCTAssertEqual(store.snapshot(messageId: "m1", symbol: "AAPL")?.price, 228.5)
        XCTAssertEqual(store.snapshot(messageId: "m1", symbol: "TSLA")?.price, 402.1)
    }

    /// `@aapl` et `@AAPL` sont le même titre: deux clés en feraient deux prix.
    func testSymbolLookupIsCaseInsensitive() {
        store.capture(messageId: "m1", symbol: "aapl", quote: quote("AAPL", 228.5))
        XCTAssertEqual(store.snapshot(messageId: "m1", symbol: "AAPL")?.price, 228.5)
    }

    /// La forme rendue à la vue doit être celle du réseau, sinon les deux
    /// chemins de rendu divergent.
    func testTheSnapshotRebuildsAUsableQuote() {
        store.capture(messageId: "m1", symbol: "AAPL", quote: quote("AAPL", 110, prev: 100))
        let q = store.snapshot(messageId: "m1", symbol: "AAPL")?.quote
        XCTAssertEqual(q?.changePercent ?? 0, 10, accuracy: 0.0001)
        XCTAssertEqual(q?.isUp, true)
    }

    /// Le magasin survit au redémarrage: sinon le gel ne durerait qu'une
    /// session, et le cours repartirait à chaque lancement de l'app.
    func testItSurvivesARestart() async throws {
        store.capture(messageId: "m1", symbol: "AAPL", quote: quote("AAPL", 228.5))
        // L'écriture est groupée (2 s) pour ne pas écrire une fois par bulle au
        // défilement; on attend donc qu'elle ait lieu.
        try await Task.sleep(for: .seconds(3))
        let reopened = TickerQuoteSnapshots(storeURL: url)
        XCTAssertEqual(reopened.snapshot(messageId: "m1", symbol: "AAPL")?.price, 228.5)
    }

    /// Un fichier corrompu doit rendre un magasin VIDE, pas faire tomber l'app.
    /// Au pire on recharge les cours une fois; c'est la leçon du magasin de
    /// messages, où un enregistrement inconnu effaçait tout l'historique.
    func testACorruptFileYieldsAnEmptyStoreRatherThanACrash() throws {
        try Data("pas du json".utf8).write(to: url)
        let reopened = TickerQuoteSnapshots(storeURL: url)
        XCTAssertEqual(reopened.count, 0)
        XCTAssertNil(reopened.snapshot(messageId: "m1", symbol: "AAPL"))
    }
}
