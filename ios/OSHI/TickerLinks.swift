//
//  TickerLinks.swift
//  OSHI — turn `@AAPL` in a message into a TradingView chart link.
//
//  THE SIGIL IS THE WHOLE DESIGN. Detecting bare uppercase words as tickers is
//  unusable in a messenger: "OK", "ASAP", "USA", "AI", "PDF", "TV", "OSHI"
//  itself — ordinary conversation is full of them, and every false positive
//  turns a word someone typed into a link to a stock chart. Requiring `@` means
//  the sender opts in, once, per ticker, and the false-positive rate is zero by
//  construction.
//
//  Everything here is pure text → ranges. No network, no state, so it is safe to
//  run on every message as it renders.
//

import Foundation
import SwiftUI

enum TickerLinks {

    /// A ticker found in a message, and where it sits in the ORIGINAL string.
    struct Match: Equatable {
        /// The symbol without the sigil, upper-cased. "AAPL"
        let symbol: String
        /// Exchange prefix if the author gave one ("NASDAQ" in `@NASDAQ:TSLA`).
        let exchange: String?
        /// Range of the whole token INCLUDING the `@`, in the original string.
        let range: Range<String.Index>

        /// TradingView URL for this match.
        ///
        /// TradingView needs an exchange to resolve a symbol. When the author
        /// did not give one we omit the prefix entirely rather than guessing:
        /// TradingView then resolves the symbol itself, which is right more
        /// often than defaulting every ticker to NASDAQ and sending someone to
        /// a chart for a different company on a different exchange.
        var url: URL? {
            let raw = exchange.map { "\($0):\(symbol)" } ?? symbol
            guard let encoded = raw.addingPercentEncoding(
                withAllowedCharacters: .alphanumerics) else { return nil }
            // NOT `/chart/dMgrF92p/` — that layout id is PRIVATE and returns
            // HTTP 403 "Chart Not Found" for anyone who is not its owner.
            // Verified by request: dMgrF92p → 403, `/chart/?symbol=` → 200.
            // Shipping the private id would have sent every recipient of every
            // ticker to an error page.
            return URL(string: "https://www.tradingview.com/chart/?symbol=\(encoded)")
        }

        /// What is shown in the bubble: the symbol, no sigil, no exchange.
        var display: String { "$\(symbol)" }
    }

    // MARK: - Detection

    /// Deux formes, et une seule admet les chiffres:
    ///   • NUE       `@AAPL`, `@BRK` — 1–6 LETTRES, rien d'autre;
    ///   • SUFFIXÉE  `@BRK.B`, `@7203.T`, `@0700.HK`, `@BTC-USD` — 1–8 lettres
    ///     ou chiffres, puis `.` ou `-`, puis 1–3 lettres de place/paire.
    /// Les deux acceptent un préfixe de place optionnel (`@NASDAQ:TSLA`).
    ///
    /// Bounded lengths on purpose. Without an upper bound `@` followed by a long
    /// shout ("@INCROYABLE") becomes a link to a symbol that cannot exist. Et le
    /// chiffre est réservé à la forme suffixée parce que `@123` ne nomme rien —
    /// voir la note sur le motif.
    private static let pattern = try! NSRegularExpression(
        // __INTL_TICKERS_2026_08_25__ Chiffres, symboles plus longs, suffixe de
        // place ou de paire.
        //
        // Le motif ne prenait que 1 a 6 LETTRES avec un suffixe `.XX`. Il
        // laissait donc de cote, verifie le 2026-08-25 sur Yahoo:
        //   `@7203.T`   Toyota (JPY)      — chiffres refuses
        //   `@0700.HK`  Tencent (HKD)     — idem
        //   `@BTC-USD`  Bitcoin           — tiret refuse
        //   `@GAZP.ME`  Gazprom (RUB)     — 4 lettres + `.ME`, passait deja
        //
        // Un symbole inconnu ne rend aucune cotation, donc aucune pastille:
        // elargir ne cree pas de faux positif VISIBLE, ca coute au pire une
        // requete qui ne montre rien.
        //
        // __TICKER_SUFFIX_GATE_2026_08_26__ Les chiffres sont admis SEULEMENT
        // dans un symbole SUFFIXE. Elargir a `[A-Z0-9]{1,8}` tout court cassait
        // trois contrats que des tests tenaient deja, et ils avaient raison:
        //   `@123`  et `@A1B2`     ne nomment aucune valeur, c'est du texte;
        //   `@ABCDEFG` (7 lettres) est un cri, pas un symbole — aucune action
        //                          cotee ne depasse 5 lettres hors suffixe.
        // Or tout ce qu'on voulait ajouter porte justement un suffixe: `7203.T`,
        // `0700.HK`, `BTC-USD`, `GAZP.ME`. La forme NUE reste donc l'ancienne,
        // `[A-Z]{1,6}`, et la forme SUFFIXEE seule gagne les chiffres et les
        // 8 caracteres. Mesure sur 22 cas: la version large en ratait 3, l'ancienne
        // en ratait 3 autres, celle-ci les passe tous.
        //
        // L'ordre de l'alternance compte: la forme suffixee d'abord, sinon
        // `@BRK.B` s'arreterait a `BRK`.
        pattern: "(?<![A-Za-z0-9_/.@])@(?:([A-Z]{2,8}):)?([A-Z0-9]{1,8}[.-][A-Z]{1,3}|[A-Z]{1,6})(?![A-Za-z0-9_/])"
    )

    /// __LOWERCASE_ALIAS_2026_08_26__ `@gold` ne faisait RIEN.
    ///
    /// Le motif ci-dessus n'accepte que des MAJUSCULES, et c'est deliberé: la
    /// casse est ce qui separe un ticker d'une mention de personne (`@hugo`).
    /// Rendre le motif insensible a la casse transformerait chaque prenom en
    /// lien vers un graphique boursier — exactement le desastre que le fichier
    /// dit vouloir eviter.
    ///
    /// Mais « or », « gold », « silver », « oil » sont des MOTS: personne ne les
    /// crie en capitales dans une conversation. D'ou une seconde passe, etroite:
    /// un mot en minuscules ne devient un ticker que s'il figure dans la table
    /// d'alias VERIFIEE de `TickerQuoteFetcher`. `@gold` passe, `@here`, `@all`
    /// et `@marie` ne passent pas — ils ne sont dans aucune table.
    private static let aliasPattern = try! NSRegularExpression(
        pattern: "(?<![A-Za-z0-9_/.@])@([A-Za-z]{2,8})(?![A-Za-z0-9_/])"
    )

    /// Every ticker in `text`, in order of appearance.
    ///
    /// The negative look-behind carries the whole safety of this feature:
    /// `[A-Za-z0-9_/.@]` before the sigil means an email local part
    /// (`hugo@ACME`), a URL path segment (`x.com/i/@AB/status`) and a repeated
    /// sigil are all left alone. The `/` in the look-AHEAD matters for the same
    /// reason — a ticker is never followed by a path separator.
    ///
    /// The URL case was caught by a test, not by reading: the first pattern
    /// happily turned `@AB` inside a link into a stock chart.
    static func matches(in text: String) -> [Match] {
        guard text.contains("@") else { return [] }   // cheap bail for the common case
        let ns = NSRange(text.startIndex..., in: text)
        var found = pattern.matches(in: text, range: ns).compactMap { m -> Match? in
            guard let whole = Range(m.range, in: text),
                  let symRange = Range(m.range(at: 2), in: text) else { return nil }
            let exchange = Range(m.range(at: 1), in: text).map { String(text[$0]) }
            return Match(symbol: String(text[symRange]), exchange: exchange, range: whole)
        }

        // Les mots en minuscules de la liste blanche, s'ils ne sont pas deja
        // couverts par le motif principal.
        for m in aliasPattern.matches(in: text, range: ns) {
            guard let whole = Range(m.range, in: text),
                  let symRange = Range(m.range(at: 1), in: text) else { continue }
            let word = String(text[symRange])
            guard TickerQuoteFetcher.isKnownAlias(word) else { continue }
            guard !found.contains(where: { $0.range.overlaps(whole) }) else { continue }
            // `Match.symbol` est documente comme MAJUSCULE: `@gold` s'affiche
            // `$GOLD` et part au reseau par la meme table que `@GOLD`.
            found.append(Match(symbol: word.uppercased(), exchange: nil, range: whole))
        }

        return found.sorted { $0.range.lowerBound < $1.range.lowerBound }
    }

    /// True when the text carries at least one ticker. Cheaper than building
    /// every match when the caller only needs to decide whether to do more work.
    static func hasTicker(in text: String) -> Bool {
        guard text.contains("@") else { return false }
        // Doit rester d'accord avec `matches(in:)`, alias minuscules compris —
        // sinon la pastille de `@gold` depend de qui pose la question.
        return !matches(in: text).isEmpty
    }

    // MARK: - Rendering

    /// `text` with every `@TICKER` replaced by a tappable `$TICKER`.
    ///
    /// Returns the input unchanged when there is no ticker, so a caller can use
    /// this unconditionally without paying for an AttributedString round-trip on
    /// ordinary messages.
    static func attributed(_ text: String,
                           tint: Color? = nil) -> AttributedString {
        let found = matches(in: text)
        guard !found.isEmpty else { return AttributedString(text) }

        var out = AttributedString()
        var cursor = text.startIndex
        for m in found {
            if cursor < m.range.lowerBound {
                out.append(AttributedString(String(text[cursor..<m.range.lowerBound])))
            }
            var chip = AttributedString(m.display)
            if let url = m.url { chip.link = url }
            if let tint { chip.foregroundColor = tint }
            chip.inlinePresentationIntent = .stronglyEmphasized
            out.append(chip)
            cursor = m.range.upperBound
        }
        if cursor < text.endIndex {
            out.append(AttributedString(String(text[cursor...])))
        }
        return out
    }
}
