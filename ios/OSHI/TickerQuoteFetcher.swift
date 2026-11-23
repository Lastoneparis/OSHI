//
//  TickerQuoteFetcher.swift
//  OSHI — price + sparkline for a `@TICKER`, fetched ONLY when asked.
//
//  ON DEMAND, DELIBERATELY. Loading a quote automatically would mean that every
//  time a message containing a ticker is rendered, this phone tells a third
//  party which symbol someone is discussing and when. On an app whose whole
//  argument is "no phone number, no metadata", that is a metadata leak
//  introduced by a convenience feature — and the user would never know it
//  happened. Tapping the chip is the consent.
//
//  NOT CCXT: that is a JavaScript/Python library with no maintained Swift port,
//  and it covers crypto only — `@AAPL` would need a second provider anyway.
//  Yahoo's chart endpoint answers for equities AND crypto, needs no API key, and
//  returns the close series and the previous close in one call, which is exactly
//  a price and a sparkline.
//

import Foundation

@MainActor
final class TickerQuoteFetcher: ObservableObject {

    struct Quote: Equatable {
        let symbol: String
        let price: Double
        let previousClose: Double
        let currency: String
        /// Intraday closes, oldest first — enough to draw a sparkline.
        let series: [Double]

        var change: Double { price - previousClose }
        var changePercent: Double {
            guard previousClose != 0 else { return 0 }
            return change / previousClose * 100
        }
        var isUp: Bool { change >= 0 }
    }

    enum State: Equatable {
        case idle
        case loading
        /// Carries a human-readable reason. A silent failure on a tap the user
        /// made deliberately reads as the app being broken.
        case failed(String)
        case loaded(Quote)
    }

    @Published private(set) var state: State = .idle

    /// Quotes are shared process-wide and short-lived: the same ticker often
    /// appears in several messages, and re-fetching per bubble would multiply
    /// both the latency and the number of times we tell Yahoo what is being
    /// discussed.
    private static var cache: [String: (Quote, Date)] = [:]
    private static let ttl: TimeInterval = 60

    private var task: Task<Void, Never>?

    deinit { task?.cancel() }

    /// __TICKER_STICKY_2026_08_26__ La pastille revenait VIDE en rouvrant la
    /// conversation.
    ///
    /// `TickerChipView` porte un `@StateObject`: quitter la conversation détruit
    /// la vue, y revenir en reconstruit une neuve, et son état repartait à
    /// `.idle`. Le prix était pourtant toujours dans le cache de processus —
    /// personne ne le lisait au moment de la construction. D’où le symptôme
    /// rapporté: « ça marche, puis ça disparaît quand on revient ».
    ///
    /// Le cache est délibérément lu SANS regarder son âge: un prix d’il y a
    /// deux minutes affiché pendant qu’on le rafraîchit vaut mieux qu’une
    /// pastille nue. La fraîcheur, c’est `load` qui s’en charge.
    init(symbol: String? = nil) {
        if let symbol, let (q, _) = Self.cache[symbol] {
            state = .loaded(q)
        }
    }

    /// Fetch `symbol`, unless a fresh copy is already cached.
    ///
    /// Safe to call repeatedly: an in-flight request is cancelled and replaced,
    /// so a double tap does not produce two round trips.
    ///
    /// __TICKER_STICKY_2026_08_26__ Stale-while-revalidate. Deux règles, et
    /// elles disent la même chose: **un prix déjà affiché ne disparaît jamais.**
    ///   • on n’affiche `.loading` que s’il n’y a RIEN à montrer — sinon un
    ///     rafraîchissement de routine remplacerait le cours par un spinner;
    ///   • un rafraîchissement qui échoue laisse le dernier cours connu en place
    ///     au lieu de le remplacer par « indisponible ». Le réseau qui tombe
    ///     trois secondes ne doit pas effacer un chiffre correct.
    func load(_ symbol: String) {
        let cached = Self.cache[symbol]
        if let (q, at) = cached {
            state = .loaded(q)
            if Date().timeIntervalSince(at) < Self.ttl { return }   // encore frais
        }
        task?.cancel()
        if cached == nil { state = .loading }
        task = Task { [weak self] in
            do {
                let q = try await Self.fetch(symbol)
                guard !Task.isCancelled else { return }
                Self.cache[symbol] = (q, Date())
                self?.state = .loaded(q)
            } catch is CancellationError {
                // Replaced by a newer request — leave the state to that one.
            } catch {
                guard !Task.isCancelled else { return }
                if cached == nil { self?.state = .failed("quote.unavailable".localized) }
            }
        }
    }

    // MARK: - Network

    /// __CRYPTO_TICKER_WRONG_PRICE_2026_08_25__ Ce que l'humain veut dire.
    ///
    /// Sur Yahoo, `BTC` tout court n'est PAS Bitcoin: c'est le Grayscale Bitcoin
    /// Mini Trust, coté au NYSE Arca. Mesuré le 2026-08-25, `@BTC` rendait
    /// **34,90 $** pendant que Bitcoin valait **78 918 $**; `@ETH` rendait
    /// 23,50 $ au lieu de 2 461 $. La monnaie s'écrit `BTC-USD`.
    ///
    /// Un chiffre FAUX présenté avec assurance est pire que pas de chiffre: la
    /// pastille a l'air de fonctionner, et l'écart est tel que personne ne
    /// vérifie. Quand quelqu'un écrit `@BTC` dans une conversation, il parle de
    /// Bitcoin, pas d'un tracker qui porte le même sigle.
    ///
    /// Les douze paires ont été interrogées une par une avant d'entrer ici.
    /// Aucune n'est supposée: ajouter une ligne sans la vérifier ferait
    /// exactement le contraire de ce que cette table corrige.
    nonisolated private static let cryptoAliases: [String: String] = [
        "BTC": "BTC-USD",   "ETH": "ETH-USD",   "SOL": "SOL-USD",
        "DOGE": "DOGE-USD", "XRP": "XRP-USD",   "ADA": "ADA-USD",
        "BNB": "BNB-USD",   "LTC": "LTC-USD",   "DOT": "DOT-USD",
        "AVAX": "AVAX-USD", "LINK": "LINK-USD", "XMR": "XMR-USD",
        // Matieres premieres. Sur Yahoo elles se cotent en contrats a terme,
        // suffixe `=F`. `XAUUSD=X` et `XAGUSD=X` — la forme forex, l'evidente —
        // repondent 404: teste, pas suppose.
        "GOLD": "GC=F",     "XAU": "GC=F",      "OR": "GC=F",
        "SILVER": "SI=F",   "XAG": "SI=F",      "ARGENT": "SI=F",
        "OIL": "CL=F",      "WTI": "CL=F",      "PETROL": "CL=F",
        "BRENT": "BZ=F",    "GAS": "NG=F",
        "COPPER": "HG=F",   "CUIVRE": "HG=F",
        "PLAT": "PL=F",     "PLATINE": "PL=F"
    ]

    /// Le symbole tel qu'il part au réseau.
    nonisolated static func resolvedSymbol(for symbol: String) -> String {
        let up = symbol.uppercased()
        if up.contains("-") { return up }   // déjà explicite: `@BTC-USD`
        return cryptoAliases[up] ?? up
    }

    /// __LOWERCASE_STOCKS_2026_08_26__ Les ACTIONS en minuscules: `@aapl`.
    ///
    /// Meme probleme que `@gold`, autre solution. Une action n'a pas besoin
    /// d'etre traduite (`AAPL` part tel quel chez Yahoo), il faut seulement
    /// AUTORISER la minuscule — et c'est justement la qu'est le danger.
    ///
    /// Pourquoi une LISTE et pas « toute suite de 2 a 5 lettres »: `TickerLinks`
    /// pose que « quelqu'un appele Max transforme en lien vers un graphique
    /// boursier est un echec bien pire qu'un ticker rendu en texte ». Ouvrir la
    /// minuscule en grand ferait exactement ca — `@marie` dans une conversation
    /// A DEUX, ou il n'y a aucune mention a resoudre, deviendrait `$MARIE`. Une
    /// liste blanche rend ce cas impossible par construction: un prenom n'y est
    /// pas.
    ///
    /// Deux regles pour y entrer, et la seconde coute des entrees:
    ///   1. le symbole a ete INTERROGE et rend une cotation (fait le 2026-08-26);
    ///   2. il ne doit pas etre un MOT courant en anglais ou en francais.
    /// C'est (2) qui exclut `COIN` (Coinbase, mais « coin » en francais),
    /// `DIS` (Disney, mais « dis ! »), `SNAP`, `SHOP`, `META`, `NOW`, `ARM`,
    /// `KEY`, `GAP`, `CAR`. Ces valeurs restent accessibles EN MAJUSCULES —
    /// `@META` marche, `@meta` reste du texte. Le cout est un mot a mettre en
    /// capitales; le cout de l'inverse est un prenom transforme en lien.
    nonisolated private static let lowercaseStocks: Set<String> = [
        "AAPL", "TSLA", "MSFT", "NVDA", "GOOGL", "GOOG", "AMZN", "NFLX",
        "AMD",  "INTC", "MSTR", "PLTR", "SPY",   "QQQ",  "JPM",  "BAC",
        "BABA", "RIVN", "PYPL", "ABNB", "ROKU",  "ORCL", "UBER", "NIO",
        "TSM",  "ASML", "SMCI", "QCOM", "CSCO",  "ADBE", "IBM",  "GME",
        "AMC",  "VOO",  "VTI",  "LCID", "SOFI",  "HOOD", "RDDT", "AVGO"
    ]

    /// __LOWERCASE_ALIAS_2026_08_26__ Ce mot nomme-t-il une valeur connue ?
    ///
    /// Les deux tables ci-dessus sont les SEULES listes blanches qui autorisent
    /// une écriture en minuscules. `TickerLinks` s'en sert pour accepter
    /// `@gold` et `@aapl` sans accepter `@here`, `@all` ni le prénom de qui que
    /// ce soit : un mot qui n'est dans aucune des deux reste du texte.
    nonisolated static func isKnownAlias(_ symbol: String) -> Bool {
        let up = symbol.uppercased()
        return cryptoAliases[up] != nil || lowercaseStocks.contains(up)
    }

    /// __QUOTES_MOVE_SERVER_SIDE_2026_11_02__ The phone asks OSHI, not Yahoo.
    ///
    /// This used to call `query1.finance.yahoo.com` DIRECTLY FROM THE DEVICE.
    /// The header of this file has always been honest about what that costs:
    /// "this phone tells a third party which symbol someone is discussing and
    /// when ... a metadata leak introduced by a convenience feature". The
    /// mitigation was to fetch only on a tap, which is real, and is also why a
    /// price could never simply appear as the user typed.
    ///
    /// `/api/quote` removes the third party from the device's view — the same
    /// argument, and the same nginx shape, as the GIPHY proxy already in front
    /// of this server. Crypto resolves through ccxt against real exchanges,
    /// which also retires the hardcoded twelve-symbol table below: `@PEPE` and
    /// `@ARB` now answer, and they never did.
    ///
    /// Equities still come from Yahoo, but SERVER-SIDE, so the device is out of
    /// it either way.
    private static func fetch(_ rawSymbol: String) async throws -> Quote {
        let symbol = resolvedSymbol(for: rawSymbol)
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-."))
        guard let encoded = symbol.addingPercentEncoding(withAllowedCharacters: allowed),
              let url = URL(string: "https://oshi-messenger.com/api/quote?symbol=" + encoded) else {
            throw URLError(.badURL)
        }
        var req = URLRequest(url: url)
        req.timeoutInterval = 12
        req.setValue(HTTPRequestSignature.userAgent, forHTTPHeaderField: "User-Agent")
        // Never let a quote sit in a shared cache with message content.
        req.cachePolicy = .reloadIgnoringLocalCacheData

        let (data, response) = try await URLSession.shared.data(for: req)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw URLError(.badServerResponse)
        }
        return try parseOshi(data, symbol: symbol)
    }

    /// The `/api/quote` body. Flat on purpose — the server has already done the
    /// shape-wrangling that [parse] does for Yahoo's nested chart payload.
    ///
    /// [parse] is kept, and still tested, because the server speaks Yahoo's
    /// dialect for equities and a future caller may need to read it directly.
    static func parseOshi(_ data: Data, symbol: String) throws -> Quote {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let price = root["price"] as? Double else {
            throw URLError(.cannotParseResponse)
        }
        let prev = (root["previousClose"] as? Double) ?? price
        let series = (root["series"] as? [Any])?.compactMap { $0 as? Double } ?? []
        return Quote(symbol: (root["symbol"] as? String) ?? symbol,
                     price: price,
                     previousClose: prev,
                     currency: (root["currency"] as? String) ?? "USD",
                     series: series)
    }

    /// Split out so it can be tested without a network.
    static func parse(_ data: Data, symbol: String) throws -> Quote {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let chart = root["chart"] as? [String: Any],
              let results = chart["result"] as? [[String: Any]],
              let first = results.first,
              let meta = first["meta"] as? [String: Any] else {
            throw URLError(.cannotParseResponse)
        }

        let price = (meta["regularMarketPrice"] as? Double)
            ?? (meta["previousClose"] as? Double)
        guard let price else { throw URLError(.cannotParseResponse) }

        // `chartPreviousClose` is the one that matches the requested range;
        // `previousClose` is the daily figure. Prefer the former, fall back.
        let prev = (meta["chartPreviousClose"] as? Double)
            ?? (meta["previousClose"] as? Double)
            ?? price

        var series: [Double] = []
        if let ind = first["indicators"] as? [String: Any],
           let quotes = ind["quote"] as? [[String: Any]],
           let closes = quotes.first?["close"] as? [Any] {
            // Yahoo emits null for gaps in the series; dropping them is right
            // for a sparkline — interpolating would draw a price that was never
            // traded.
            series = closes.compactMap { $0 as? Double }
        }

        return Quote(symbol: (meta["symbol"] as? String) ?? symbol,
                     price: price,
                     previousClose: prev,
                     currency: (meta["currency"] as? String) ?? "USD",
                     series: series)
    }
}
