//
//  TickerLinkTests.swift
//  OSHITests
//
//  `@AAPL` becomes a TradingView link. The whole risk of this feature is FALSE
//  POSITIVES: it runs on every message, and every word it wrongly claims is a
//  ticker turns something a person typed into a link to a stock chart.
//
//  So most of this file is about what must NOT match.
//

import XCTest
@testable import OSHI

final class TickerLinkTests: XCTestCase {

    private func symbols(_ s: String) -> [String] {
        TickerLinks.matches(in: s).map(\.symbol)
    }

    // MARK: What must match

    func testPlainTicker() {
        XCTAssertEqual(symbols("look at @AAPL today"), ["AAPL"])
    }

    func testSeveralInOneMessage() {
        XCTAssertEqual(symbols("@AAPL vs @TSLA vs @NVDA"), ["AAPL", "TSLA", "NVDA"])
    }

    func testExchangePrefixIsCaptured() {
        let m = TickerLinks.matches(in: "@NASDAQ:TSLA").first
        XCTAssertEqual(m?.symbol, "TSLA")
        XCTAssertEqual(m?.exchange, "NASDAQ")
    }

    func testClassShareDotIsKept() {
        XCTAssertEqual(symbols("@BRK.B"), ["BRK.B"])
    }

    func testAtStartAndEndOfMessage() {
        XCTAssertEqual(symbols("@SPCX"), ["SPCX"])
        XCTAssertEqual(symbols("buy @SPCX"), ["SPCX"])
        XCTAssertEqual(symbols("@SPCX now"), ["SPCX"])
    }

    // MARK: What must NOT match — the reason the sigil exists

    /// An email address contains an `@` followed by letters. Matching there
    /// would turn part of someone's address into a stock link.
    func testEmailAddressIsNotATicker() {
        XCTAssertEqual(symbols("write to hugo@ACME.com"), [])
        XCTAssertEqual(symbols("hugo@icloud.com"), [])
    }

    /// Bare uppercase words are everywhere in ordinary chat. Without the sigil
    /// every one of these would become a link — which is why detection is
    /// opt-in and these must stay plain.
    func testBareUppercaseWordsAreNeverTickers() {
        for word in ["OK", "ASAP", "USA", "AI", "PDF", "TV", "OSHI", "LORA", "GPS"] {
            XCTAssertEqual(symbols("that is \(word) fine"), [],
                           "\(word) was treated as a ticker")
        }
    }

    /// Lower-case after the sigil is a person-handle, not a symbol.
    func testLowercaseHandleIsNotATicker() {
        XCTAssertEqual(symbols("@hugo said so"), [])
        XCTAssertEqual(symbols("@Hugo"), [])
    }

    /// A long shout is not a symbol. Without an upper bound this would link to
    /// a company that cannot exist.
    func testOverlongTokenIsRejected() {
        XCTAssertEqual(symbols("@INCROYABLE"), [])
        XCTAssertEqual(symbols("@ABCDEFG"), [])
    }

    func testSigilAloneOrWithDigits() {
        XCTAssertEqual(symbols("@ alone"), [])
        XCTAssertEqual(symbols("@123"), [])
        XCTAssertEqual(symbols("@A1B2"), [])
    }

    /// __TICKER_SUFFIX_GATE_2026_08_26__ Les places étrangères numérotent leurs
    /// symboles. Ces quatre-là ont été interrogés sur Yahoo le 2026-08-25 et
    /// rendent une cotation; sans eux la pastille ne couvre que les États-Unis.
    ///
    /// Ils vivent à côté de `testSigilAloneOrWithDigits` volontairement: c'est
    /// la paire qui dit la règle. Un chiffre est un symbole QUAND IL PORTE UN
    /// SUFFIXE de place, et du texte sinon. Élargir sans cette porte faisait
    /// passer `@123` pour une valeur cotée.
    func testSuffixedInternationalSymbols() {
        XCTAssertEqual(symbols("@7203.T"), ["7203.T"])     // Toyota, Tokyo
        XCTAssertEqual(symbols("@0700.HK"), ["0700.HK"])   // Tencent, Hong Kong
        XCTAssertEqual(symbols("@GAZP.ME"), ["GAZP.ME"])   // Gazprom, Moscou
        XCTAssertEqual(symbols("@NESN.SW"), ["NESN.SW"])   // Nestlé, Zurich
    }

    /// Une paire crypto porte son tiret. `@BTC` seul marche aussi — la table
    /// d'alias du fetcher le résout en `BTC-USD` — mais la forme complète doit
    /// survivre à la détection sans être tronquée à `BTC`.
    func testPairedCryptoSymbolKeepsItsQuoteCurrency() {
        XCTAssertEqual(symbols("@BTC-USD"), ["BTC-USD"])
        XCTAssertEqual(symbols("@ETH-USD rally"), ["ETH-USD"])
    }

    /// Le point final d'une phrase n'est pas un suffixe de place.
    func testTrailingSentencePunctuationIsNotASuffix() {
        XCTAssertEqual(symbols("buy @AAPL."), ["AAPL"])
    }

    /// A URL fragment can carry `@`. It must not be rewritten mid-link.
    func testInsideAURLIsLeftAlone() {
        XCTAssertEqual(symbols("https://x.com/i/@AB/status"), [])
    }

    // MARK: The produced link

    /// The chart id the request originally named (`/chart/dMgrF92p/`) is a
    /// PRIVATE layout — it answers 403 for everyone but its owner. Asserting the
    /// public form keeps that from coming back.
    func testUsesThePublicChartURL() {
        let url = TickerLinks.matches(in: "@AAPL").first?.url?.absoluteString ?? ""
        XCTAssertFalse(url.contains("dMgrF92p"), "private chart id would 403: \(url)")
    }

    func testURLCarriesTheSymbol() {
        let m = TickerLinks.matches(in: "@SPCX").first
        let url = m?.url?.absoluteString ?? ""
        XCTAssertTrue(url.hasPrefix("https://www.tradingview.com/chart/?symbol="), url)
        XCTAssertTrue(url.hasSuffix("SPCX"), url)
    }

    /// With no exchange we send the bare symbol rather than guessing NASDAQ —
    /// guessing sends people to a different company on a different exchange.
    func testNoExchangeIsNotInvented() {
        let url = TickerLinks.matches(in: "@SPCX").first?.url?.absoluteString ?? ""
        XCTAssertFalse(url.contains("NASDAQ"), "an exchange was invented: \(url)")
    }

    func testExchangeIsEncodedWhenGiven() {
        let url = TickerLinks.matches(in: "@NASDAQ:TSLA").first?.url?.absoluteString ?? ""
        XCTAssertTrue(url.contains("NASDAQ"), url)
        XCTAssertTrue(url.contains("TSLA"), url)
        // NOT `url.contains(":")` — "https://" has one. Assert on the symbol
        // value itself, which is the part that must be encoded.
        XCTAssertTrue(url.contains("NASDAQ%3ATSLA"),
                      "the colon between exchange and symbol must be percent-encoded: \(url)")
    }

    // MARK: Rendering

    /// The sigil is an input convention, not something to read. It is replaced
    /// by the `$` convention people already know from finance chat.
    func testRenderedTextDropsTheSigil() {
        let a = TickerLinks.attributed("buy @AAPL now")
        XCTAssertEqual(String(a.characters), "buy $AAPL now")
    }

    /// Text with no ticker must come back untouched — this runs on every
    /// message, so the common case has to be free of surprises.
    func testTextWithoutTickersIsUnchanged() {
        let plain = "no tickers here, just words"
        XCTAssertEqual(String(TickerLinks.attributed(plain).characters), plain)
    }

    func testRenderedTickerIsLinked() {
        let a = TickerLinks.attributed("@AAPL")
        let run = a.runs.first { $0.link != nil }
        XCTAssertNotNil(run, "the ticker was rendered without a link")
        XCTAssertTrue(run?.link?.absoluteString.contains("AAPL") ?? false)
    }

    func testHasTickerAgreesWithMatches() {
        XCTAssertTrue(TickerLinks.hasTicker(in: "hold @NVDA"))
        XCTAssertFalse(TickerLinks.hasTicker(in: "hold on"))
        XCTAssertFalse(TickerLinks.hasTicker(in: "mail me at a@b.com"))
    }
}

// MARK: - Quote parsing
//
// Parsing is tested against a FIXTURE, not the network: a test that reaches
// Yahoo fails on a plane and passes for the wrong reason on a cached response.
// The fixture's shape was verified against a live call before being frozen here.

@MainActor
final class TickerQuoteParseTests: XCTestCase {

    private func payload(price: Double?, prevChart: Double?, prevDaily: Double?,
                         closes: [Any]) -> Data {
        var meta: [String: Any] = ["symbol": "AAPL", "currency": "USD"]
        if let price { meta["regularMarketPrice"] = price }
        if let prevChart { meta["chartPreviousClose"] = prevChart }
        if let prevDaily { meta["previousClose"] = prevDaily }
        let root: [String: Any] = ["chart": ["result": [[
            "meta": meta,
            "indicators": ["quote": [["close": closes]]],
        ]]]]
        return try! JSONSerialization.data(withJSONObject: root)
    }

    // MARK: - __QUOTES_MOVE_SERVER_SIDE_2026_11_02__ `/api/quote`

    /// The flat body the OSHI quote endpoint returns for a CRYPTO symbol.
    /// No `series`: an exchange ticker has no intraday close list, and the
    /// chip must still price itself without one.
    func testParsesOshiCryptoBodyWithNoSeries() throws {
        let json = "{\"symbol\":\"BTC\",\"pair\":\"BTC/USDT\",\"price\":77749.77,"
            + "\"previousClose\":77935.91,\"currency\":\"USD\",\"source\":\"binance\",\"kind\":\"crypto\"}"
        let q = try TickerQuoteFetcher.parseOshi(Data(json.utf8), symbol: "BTC")
        XCTAssertEqual(q.symbol, "BTC")
        XCTAssertEqual(q.price, 77749.77, accuracy: 0.001)
        XCTAssertEqual(q.previousClose, 77935.91, accuracy: 0.001)
        XCTAssertEqual(q.currency, "USD")
        XCTAssertTrue(q.series.isEmpty)
        XCTAssertFalse(q.isUp)   // 24h open above last
    }

    /// An EQUITY body, which does carry a series (server-side Yahoo).
    func testParsesOshiEquityBodyWithSeries() throws {
        let json = "{\"symbol\":\"AAPL\",\"price\":344.63,\"previousClose\":348.91,"
            + "\"currency\":\"USD\",\"series\":[348.01,347.89,344.63],\"kind\":\"equity\"}"
        let q = try TickerQuoteFetcher.parseOshi(Data(json.utf8), symbol: "AAPL")
        XCTAssertEqual(q.price, 344.63, accuracy: 0.001)
        XCTAssertEqual(q.series.count, 3)
        XCTAssertFalse(q.isUp)
    }

    /// A non-USD pair must keep its currency, or a EUR price renders with a
    /// dollar sign — the bug the crypto alias table was written to avoid.
    func testOshiBodyKeepsNonUsdCurrency() throws {
        let json = "{\"symbol\":\"ETH-EUR\",\"pair\":\"ETH/EUR\",\"price\":2034.26,"
            + "\"previousClose\":2016.6,\"currency\":\"EUR\",\"kind\":\"crypto\"}"
        let q = try TickerQuoteFetcher.parseOshi(Data(json.utf8), symbol: "ETH-EUR")
        XCTAssertEqual(q.currency, "EUR")
        XCTAssertTrue(q.isUp)
    }

    /// The endpoint's own error shape must not become a zero-priced chip.
    func testOshiErrorBodyThrows() {
        let json = "{\"error\":\"unknown-symbol\",\"symbol\":\"NOTACOIN\"}"
        XCTAssertThrowsError(try TickerQuoteFetcher.parseOshi(Data(json.utf8), symbol: "NOTACOIN"))
    }

    func testParsesPriceChangeAndSeries() throws {
        let q = try TickerQuoteFetcher.parse(
            payload(price: 312.41, prevChart: 311.0, prevDaily: 310.0,
                    closes: [310.0, 311.5, 312.41]), symbol: "AAPL")
        XCTAssertEqual(q.price, 312.41, accuracy: 0.001)
        XCTAssertEqual(q.previousClose, 311.0, accuracy: 0.001)   // chart wins over daily
        XCTAssertEqual(q.series.count, 3)
        XCTAssertTrue(q.isUp)
        XCTAssertEqual(q.changePercent, (312.41 - 311.0) / 311.0 * 100, accuracy: 0.001)
    }

    /// Yahoo emits null inside the close series for gaps. Dropping them is
    /// right for a sparkline — interpolating would draw a price never traded.
    func testNullsInTheSeriesAreDroppedNotInterpolated() throws {
        let q = try TickerQuoteFetcher.parse(
            payload(price: 10, prevChart: 10, prevDaily: nil,
                    closes: [9.0, NSNull(), 11.0, NSNull()]), symbol: "X")
        XCTAssertEqual(q.series, [9.0, 11.0])
    }

    func testFallsBackToDailyPreviousClose() throws {
        let q = try TickerQuoteFetcher.parse(
            payload(price: 50, prevChart: nil, prevDaily: 48, closes: []), symbol: "X")
        XCTAssertEqual(q.previousClose, 48, accuracy: 0.001)
    }

    /// A flat series must not divide by zero when the sparkline scales it.
    func testFlatSeriesDoesNotProduceNaN() throws {
        let q = try TickerQuoteFetcher.parse(
            payload(price: 7, prevChart: 7, prevDaily: nil, closes: [7.0, 7.0, 7.0]), symbol: "X")
        XCTAssertEqual(q.changePercent, 0, accuracy: 0.0001)
        XCTAssertFalse(q.changePercent.isNaN)
    }

    /// A malformed body must throw, not return a zero price that would render
    /// as a real quote of 0.00.
    func testGarbageThrows() {
        XCTAssertThrowsError(try TickerQuoteFetcher.parse(Data("{}".utf8), symbol: "X"))
        XCTAssertThrowsError(try TickerQuoteFetcher.parse(Data("not json".utf8), symbol: "X"))
    }

    func testMissingPriceThrowsRatherThanShowingZero() {
        let d = payload(price: nil, prevChart: nil, prevDaily: nil, closes: [])
        XCTAssertThrowsError(try TickerQuoteFetcher.parse(d, symbol: "X"))
    }
}

// MARK: - __LOWERCASE_ALIAS_2026_08_26__ `@gold` ne faisait rien
//
// Le motif principal n'accepte que des MAJUSCULES, et il doit le rester: la
// casse est ce qui separe `@AAPL` (ticker) de `@hugo` (personne). Ces tests
// tiennent les DEUX cotes du compromis — les mots de la table d'alias passent
// en minuscules, tout le reste du minuscule reste du texte.

final class TickerLowercaseAliasTests: XCTestCase {

    private func syms(_ s: String) -> [String] {
        TickerLinks.matches(in: s).map(\.symbol)
    }

    /// Le defaut signale: on ecrit `@gold`, il ne se passe rien.
    func testLowercaseGoldIsATicker() {
        XCTAssertEqual(syms("@gold"), ["GOLD"])
        XCTAssertEqual(syms("regarde @gold ce soir"), ["GOLD"])
    }

    /// `Match.symbol` est documente comme majuscule: la casse saisie ne doit pas
    /// fuir dans l'URL du graphique ni dans le `$SYMBOLE` affiche.
    func testLowercaseIsNormalisedToUppercase() {
        XCTAssertEqual(syms("@Gold"), ["GOLD"])
        XCTAssertEqual(syms("@gOlD"), ["GOLD"])
    }

    /// Les autres mots de la table, ceux que la demande appelait « and so on ».
    func testTheOtherAliasWordsAlsoWorkInLowercase() {
        XCTAssertEqual(syms("@silver"), ["SILVER"])
        XCTAssertEqual(syms("@oil"), ["OIL"])
        XCTAssertEqual(syms("@btc et @eth"), ["BTC", "ETH"])
        XCTAssertEqual(syms("@argent"), ["ARGENT"])
    }

    /// LE contrat qui protege la fonctionnalite d'a cote: un mot en minuscules
    /// qui n'est PAS dans la table reste du texte. Sans cette garde, `@hugo`
    /// deviendrait un lien vers un graphique boursier.
    func testLowercaseNonAliasWordsAreNotTickers() {
        XCTAssertEqual(syms("@hugo"), [])
        XCTAssertEqual(syms("@marie"), [])
        XCTAssertEqual(syms("@here"), [])
        XCTAssertEqual(syms("@all"), [])
        XCTAssertEqual(syms("@everyone"), [])
    }

    /// La passe minuscule ne doit pas rouvrir ce que la look-behind ferme.
    func testLowercasePassStillRefusesEmailsAndUrls() {
        XCTAssertEqual(syms("ecris a hugo@gold.com"), [])
        XCTAssertEqual(syms("https://x.com/i/@gold/status"), [])
    }

    /// Un symbole deja couvert par le motif principal ne doit pas etre compte
    /// deux fois par la seconde passe.
    func testUppercaseAliasIsNotDuplicated() {
        XCTAssertEqual(syms("@GOLD"), ["GOLD"])
        XCTAssertEqual(syms("@BTC-USD"), ["BTC-USD"])
    }

    /// `hasTicker` decide si on paie le rendu enrichi: il doit voir exactement
    /// ce que `matches` voit, alias minuscules compris.
    func testHasTickerAgreesWithMatches() {
        XCTAssertTrue(TickerLinks.hasTicker(in: "@gold"))
        XCTAssertFalse(TickerLinks.hasTicker(in: "@hugo"))
    }

    // MARK: - __LOWERCASE_STOCKS_2026_08_26__ Les actions

    /// La demande: `@aapl` « et ainsi de suite ».
    func testLowercaseStockTickersResolve() {
        XCTAssertEqual(syms("@aapl"), ["AAPL"])
        XCTAssertEqual(syms("@tsla"), ["TSLA"])
        XCTAssertEqual(syms("@nvda"), ["NVDA"])
        XCTAssertEqual(syms("@googl"), ["GOOGL"])
        XCTAssertEqual(syms("j'ai achete @msft hier"), ["MSFT"])
    }

    /// Crypto et action dans la meme phrase, toutes deux en minuscules.
    func testLowercaseStocksAndCryptoMix() {
        XCTAssertEqual(syms("@aapl vs @btc vs @gold"), ["AAPL", "BTC", "GOLD"])
    }

    /// LA garde qui justifie la liste blanche: un mot courant qui se trouve
    /// AUSSI etre un symbole reste du texte en minuscules. Sinon « dis-moi »
    /// et « au coin » deviendraient Disney et Coinbase.
    func testAmbiguousWordsStayTextInLowercase() {
        XCTAssertEqual(syms("@coin"), [])
        XCTAssertEqual(syms("@dis"), [])
        XCTAssertEqual(syms("@meta"), [])
        XCTAssertEqual(syms("@snap"), [])
        XCTAssertEqual(syms("@now"), [])
    }

    /// Et ils restent joignables en MAJUSCULES: la liste blanche ne retire
    /// rien, elle n'ouvre que la minuscule.
    func testAmbiguousWordsStillWorkInUppercase() {
        XCTAssertEqual(syms("@META"), ["META"])
        XCTAssertEqual(syms("@COIN"), ["COIN"])
        XCTAssertEqual(syms("@DIS"), ["DIS"])
    }

    /// Un prenom ne doit JAMAIS devenir un lien boursier, y compris dans une
    /// conversation a deux ou aucune mention n'est resolue en amont.
    func testNamesNeverBecomeStockLinks() {
        for name in ["@marie", "@thomas", "@sophie", "@max", "@leo", "@ana"] {
            XCTAssertEqual(syms(name), [], "\(name) doit rester du texte")
        }
    }
}
