//
//  TickerChipView.swift
//  OSHI — the tappable `$AAPL` chip under a message.
//
//  __TICKER_AUTOLOAD_2026_08_26__ Le cours se charge TOUT SEUL, et un tap ouvre
//  le graphique. C'est un changement de doctrine demandé explicitement, et il
//  faut l'écrire ici plutôt que de laisser l'ancien commentaire mentir.
//
//  Ce que ça coûte, en clair: la pastille interroge Yahoo au moment où le
//  message s'affiche, donc un tiers apprend QUEL symbole est discuté et QUAND,
//  sans que personne ait rien touché. C'était précisément ce que le premier tap
//  achetait. Ce qui limite la fuite, et rien d'autre:
//    • aucun contenu de message, aucun contact, aucune clé ne part — le seul
//      élément transmis est le symbole lui-même;
//    • le cache est à l'échelle du PROCESSUS et vaut 60 s, donc le même symbole
//      présent dans dix bulles ne produit qu'UNE requête.
//
//  Si la fuite redevient inacceptable, le retour en arrière tient en une ligne:
//  supprimer le `.task` plus bas. Tout le reste (cache, collant, décimales)
//  fonctionne à l'identique dans les deux régimes.
//

import SwiftUI

struct TickerChipView: View {
    let match: TickerLinks.Match
    var tint: Color = Theme.brand
    /// Le message qui porte ce symbole. Il sert à FIGER le cours: une fois
    /// relevé pour ce message, il ne rebouge plus. `nil` (aperçu, composition)
    /// retombe sur l'ancien comportement, cours vivant.
    var messageId: String?
    /// __TICKER_RECEIVED_NO_AUTOLOAD_2026_11_20__ Load the price without a tap?
    /// Only for the user's OWN messages (same rule as link previews,
    /// `allowNetwork: isFromCurrentUser`). For a RECEIVED message the automatic
    /// fetch told the quote server — with the reader's IP — which symbol was
    /// being read and the second it was displayed: a sender could plant
    /// `@XYZ` to learn when, and from where, the message was read. A received
    /// chip now fetches on the first tap; a price already frozen on the
    /// message is still shown without any request.
    var autoLoad: Bool = false

    @StateObject private var fetcher: TickerQuoteFetcher

    /// L'init existe pour UNE raison: passer le symbole au fetcher AVANT le
    /// premier rendu, pour qu'il lise le cache de processus tout de suite. Sans
    /// ça, rouvrir une conversation redessinait une pastille nue le temps d'un
    /// aller-retour réseau — le « ça disparaît quand on revient » signalé.
    init(match: TickerLinks.Match, tint: Color = Theme.brand, messageId: String? = nil,
         autoLoad: Bool = false) {
        self.match = match
        self.tint = tint
        self.messageId = messageId
        self.autoLoad = autoLoad
        _fetcher = StateObject(wrappedValue: TickerQuoteFetcher(symbol: match.symbol))
    }

    /// Le cours figé de CE message, s'il en existe un.
    private var frozen: TickerQuoteFetcher.Quote? {
        guard let messageId else { return nil }
        return TickerQuoteSnapshots.shared
            .snapshot(messageId: messageId, symbol: match.symbol)?.quote
    }

    var body: some View {
        Button(action: tap) {
            HStack(spacing: 6) {
                Text(match.display)
                    .font(.scaled(14, weight: .semibold))
                    .monospacedDigit()

                // Un cours figé bat toujours l'état du chargeur: c'est LE
                // chiffre dont ce message parlait, et il ne doit pas se faire
                // remplacer par celui d'aujourd'hui au prochain affichage.
                if let q = frozen {
                    quoteBody(q)
                } else {
                switch fetcher.state {
                case .idle:
                    EmptyView()

                case .loading:
                    ProgressView().controlSize(.mini)

                case .failed(let why):
                    Text(why)
                        .font(.scaled(12))
                        .foregroundColor(.secondary)

                case .loaded(let q):
                    // A sparkline only when there is enough of a series to be a
                    // shape. Two points is a straight line that implies a trend
                    // nobody measured.
                    if q.series.count > 8 {
                        Sparkline(values: q.series, up: q.isUp)
                            .frame(width: 46, height: 16)
                    }
                    Text(price(q))
                        .font(.scaled(14, weight: .semibold))
                        .monospacedDigit()
                    Text(String(format: "%@%.2f%%", q.isUp ? "▲" : "▼", abs(q.changePercent)))
                        .font(.scaled(12, weight: .semibold))
                        .monospacedDigit()
                        .foregroundColor(q.isUp ? .green : .red)
                }
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(tint.opacity(0.20))
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityText)
        .accessibilityHint(isLoaded ? "ticker.open_chart".localized : "ticker.tap_for_price".localized)
        // Le cours sans qu'on ait à le demander — MAIS une seule fois par
        // message. Un instantané déjà pris coupe court: pas de requête, donc
        // rouvrir une vieille conversation ne redit plus à Yahoo quel symbole
        // on relit.
        .task {
            guard autoLoad, frozen == nil else { return }
            fetcher.load(match.symbol)
        }
        // Et dès que le cours arrive, on le grave sur ce message.
        .onChange(of: fetcher.state) { _, state in
            guard let messageId, case .loaded(let q) = state else { return }
            TickerQuoteSnapshots.shared.capture(messageId: messageId,
                                                symbol: match.symbol, quote: q)
        }
    }

    /// Le corps de la pastille quand il y a un cours à montrer. Extrait pour
    /// que le chemin figé et le chemin réseau rendent EXACTEMENT la même chose:
    /// deux copies divergeraient à la première retouche.
    @ViewBuilder
    private func quoteBody(_ q: TickerQuoteFetcher.Quote) -> some View {
        if q.series.count > 8 {
            Sparkline(values: q.series, up: q.isUp)
                .frame(width: 46, height: 16)
        }
        Text(price(q))
            .font(.scaled(14, weight: .semibold))
            .monospacedDigit()
        Text(String(format: "%@%.2f%%", q.isUp ? "▲" : "▼", abs(q.changePercent)))
            .font(.scaled(12, weight: .semibold))
            .monospacedDigit()
            .foregroundColor(q.isUp ? .green : .red)
    }

    private var isLoaded: Bool {
        if frozen != nil { return true }
        if case .loaded = fetcher.state { return true }
        return false
    }

    private func tap() {
        if isLoaded, let url = match.url {
            UIApplication.shared.open(url)
        } else {
            fetcher.load(match.symbol)
        }
    }

    /// Two decimals for ordinary prices, more for sub-dollar instruments where
    /// two would round a real price to 0.00.
    /// __TICKER_CURRENCY_2026_08_25__ Le prix avec sa DEVISE, et dans la langue
    /// de l'utilisateur.
    ///
    /// C'était `String(format: "%.2f", price)`: aucune devise, et un point
    /// décimal quelle que soit la locale. Tolérable tant que tout était en
    /// dollars; faux dès que l'international marche. Vérifié le 2026-08-25:
    /// `@NESN.SW` cote en CHF, `@MC.PA` en EUR, `@7203.T` en JPY, `@GAZP.ME` en
    /// RUB — et `@SHEL.L` en **GBp**, c'est-à-dire en PENCE.
    ///
    /// Ce dernier est le piège: Yahoo rend `3386.5 GBp` pour Shell, soit 33,86 £.
    /// Afficher « 3386,50 £ » serait faux d'un facteur 100. On convertit donc en
    /// livres, ce qui est le montant qu'un humain attend.
    ///
    /// Le yen n'a pas de décimales, la plupart des cryptos en veulent beaucoup:
    /// le nombre de décimales suit le prix, pas une constante.
    private func price(_ q: TickerQuoteFetcher.Quote) -> String {
        var value = q.price
        var code = q.currency.uppercased()
        if q.currency == "GBp" || code == "GBX" {   // pence -> livres
            value /= 100
            code = "GBP"
        }

        let f = NumberFormatter()
        f.numberStyle = .currency
        f.currencyCode = code
        f.locale = .current

        // __TICKER_YEN_DECIMALS_2026_08_25__ Le nombre de décimales appartient
        // à la DEVISE, pas à une constante.
        //
        // La version précédente forçait `min/maxFractionDigits = 2` dès que le
        // prix dépassait 1 — alors que poser `currencyCode` sur un formateur
        // `.currency` règle DÉJÀ les décimales selon la convention de la devise.
        // Mesuré le 2026-08-25: JPY → 0, KRW → 0, CLP → 0, USD/EUR/CHF → 2,
        // BHD → 3, et un code inconnu retombe sur 2. Forcer 2 écrasait cette
        // convention et rendait Toyota « 2 800,00 ¥ » — une somme qui ne peut
        // pas exister, le yen n'ayant aucune subdivision.
        //
        // On ne s'écarte donc du défaut que VERS LE HAUT, et seulement quand ce
        // défaut effacerait le prix: DOGE à 0,0881 $ rendu « 0,09 $ » est une
        // erreur de 2 %, et un jeton à 0,000012 $ deviendrait « 0,00 $ ».
        let digits: Int
        switch value {
        case ..<0.000_001: digits = max(f.maximumFractionDigits, 8)
        case ..<0.01:      digits = max(f.maximumFractionDigits, 6)
        case ..<1:         digits = max(f.maximumFractionDigits, 4)
        default:           digits = f.maximumFractionDigits   // la convention de la devise
        }
        f.minimumFractionDigits = digits
        f.maximumFractionDigits = digits
        if let out = f.string(from: NSNumber(value: value)) { return out }
        // Repli si la devise est inconnue du système: le nombre + le code brut,
        // jamais un nombre nu qu'on prendrait pour des dollars.
        return String(format: "%.\(digits)f %@", value, code)
    }

    private var accessibilityText: String {
        guard case .loaded(let q) = fetcher.state else { return match.display }
        return String(format: "%@ %@ %@%.2f percent",
                      match.display, price(q), q.isUp ? "up" : "down", abs(q.changePercent))
    }
}

/// Minimal intraday line. No axes, no labels — at 46×16 anything else is noise.
private struct Sparkline: View {
    let values: [Double]
    let up: Bool

    var body: some View {
        GeometryReader { geo in
            let lo = values.min() ?? 0
            let hi = values.max() ?? 1
            // A flat series would divide by zero; draw it flat rather than
            // exploding the scale on floating-point noise.
            let span = max(hi - lo, 0.000_001)
            Path { p in
                for (i, v) in values.enumerated() {
                    let x = geo.size.width * CGFloat(i) / CGFloat(max(values.count - 1, 1))
                    let y = geo.size.height * (1 - CGFloat((v - lo) / span))
                    i == 0 ? p.move(to: CGPoint(x: x, y: y)) : p.addLine(to: CGPoint(x: x, y: y))
                }
            }
            .stroke(up ? Color.green : Color.red, lineWidth: 1.4)
        }
        .accessibilityHidden(true)   // the price beside it already says this
    }
}

// MARK: - __TICKER_CHIPS_WIRED_2026_08_25__

/// Every `@TICKER` a message carries, as chips under the bubble.
///
/// The chip and its fetcher were both written, and `TickerChipView` was
/// instantiated NOWHERE — so the price half of this feature has never once run
/// on a device. The text was already turned into a `$AAPL` link to TradingView
/// by `ClickableTextView`, which is why nobody noticed: tickers looked handled.
///
/// The inline link is left exactly as it is. It opens the chart; the chip is
/// what shows the price and the day's move — depuis le 2026-08-26, sans qu'on
/// ait à le demander. Le compromis que ça engage est écrit en tête de fichier,
/// pas ici, pour qu'il n'y ait qu'UN endroit à corriger si on revient dessus.
struct TickerChipsForText: View {
    let text: String
    var tint: Color = Theme.brand
    /// Le message porteur, pour figer le cours. Voir `TickerQuoteSnapshots`.
    var messageId: String?
    /// See `TickerChipView.autoLoad`: true only for the user's own messages.
    var autoLoad: Bool = false

    var body: some View {
        let matches = TickerLinks.matches(in: text)
        if !matches.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(matches, id: \.range) { match in
                        TickerChipView(match: match, tint: tint, messageId: messageId, autoLoad: autoLoad)
                    }
                }
            }
            .scrollBounceBehavior(.basedOnSize)
        }
    }
}
