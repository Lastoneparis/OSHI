//
//  TickerQuoteSnapshots.swift
//  OSHI — le cours d'un `@TICKER` est FIGÉ au message qui le porte.
//
//  __TICKER_FROZEN_2026_09_13__ Jusqu'ici la pastille rechargeait le cours à
//  chaque fois que la conversation s'ouvrait. Deux choses n'allaient pas, et la
//  seconde est la vraie:
//
//  1. Un message d'il y a trois jours affichait le cours d'AUJOURD'HUI. La
//     bulle disait « regarde @TSLA » avec un chiffre qui n'a jamais été celui
//     dont on parlait — et le fil de discussion devenait incompréhensible à la
//     relecture. Un message est un instantané; son cours doit l'être aussi.
//
//  2. Chaque réouverture redisait à Yahoo QUEL symbole est discuté et QUAND.
//     Pour une app dont tout l'argument est « pas de numéro, pas de métadonnée »,
//     ça transformait une conversation ancienne en balise qui se rallume à
//     chaque visite. Geler le cours supprime la répétition: une requête par
//     message, pour toujours.
//
//  LA RÈGLE: le premier affichage d'un message capture le cours et l'écrit ici.
//  Tous les affichages suivants lisent cette copie et ne touchent pas au réseau.
//  Pour un message qu'on vient d'envoyer, « premier affichage » veut dire « au
//  moment de l'envoi », ce qui est exactement le cours qu'on avait sous les yeux
//  en écrivant. Qui veut un cours frais envoie un nouveau message.
//
//  CE N'EST PAS UN CHANGEMENT DE FORMAT DE FIL. Rien ne part sur le réseau: le
//  message reste le texte `@AAPL`, et chaque appareil garde SON instantané, pris
//  au moment où il a vu le message. Embarquer le prix dans le message aurait
//  demandé un nouveau format que les anciens clients n'auraient pas lu.
//

import Foundation

@MainActor
final class TickerQuoteSnapshots {

    static let shared = TickerQuoteSnapshots()

    struct Snapshot: Codable, Equatable {
        let symbol: String
        let price: Double
        let previousClose: Double
        let currency: String
        let series: [Double]
        /// Quand ce cours a été relevé — pas quand le message a été écrit. Les
        /// deux coïncident pour un message qu'on envoie, et diffèrent pour un
        /// message reçu hors ligne puis affiché plus tard. Distinguer les deux
        /// est ce qui permettra un jour de dire « cours d'il y a 3 jours »
        /// plutôt que de laisser croire qu'il est frais.
        let capturedAt: Date
    }

    /// Le paquet est borné: une conversation active peut porter des milliers de
    /// messages, et un instantané par message et par symbole finirait par peser.
    /// Au-delà, on jette les plus anciens — ce qui les fait simplement
    /// recharger une fois, jamais afficher un chiffre faux.
    private static let maxEntries = 3000

    private var entries: [String: Snapshot] = [:]
    private let storeURL: URL
    private var saveScheduled = false

    private init() {
        let dir = (try? FileManager.default
            .url(for: .applicationSupportDirectory, in: .userDomainMask,
                 appropriateFor: nil, create: true))
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        storeURL = dir.appendingPathComponent("ticker_quote_snapshots.json")
        load()
    }

    /// Pour les tests: un magasin isolé, sans toucher au fichier de l'app.
    init(storeURL: URL) {
        self.storeURL = storeURL
        load()
    }

    // MARK: - Lecture / écriture

    private func key(_ messageId: String, _ symbol: String) -> String {
        "\(messageId)|\(symbol.uppercased())"
    }

    func snapshot(messageId: String, symbol: String) -> Snapshot? {
        entries[key(messageId, symbol)]
    }

    /// Écrit UNE fois. Un deuxième appel pour le même message est ignoré: c'est
    /// ce qui garantit que le chiffre ne bouge plus, même si deux bulles à
    /// l'écran déclenchent la capture en même temps.
    func capture(messageId: String, symbol: String, quote: TickerQuoteFetcher.Quote) {
        let k = key(messageId, symbol)
        guard entries[k] == nil else { return }
        entries[k] = Snapshot(symbol: quote.symbol, price: quote.price,
                              previousClose: quote.previousClose, currency: quote.currency,
                              series: quote.series, capturedAt: Date())
        evictIfNeeded()
        scheduleSave()
    }

    private func evictIfNeeded() {
        guard entries.count > Self.maxEntries else { return }
        let doomed = entries.sorted { $0.value.capturedAt < $1.value.capturedAt }
            .prefix(entries.count - Self.maxEntries)
        for (k, _) in doomed { entries.removeValue(forKey: k) }
    }

    // MARK: - Disque

    /// Écrire à chaque capture ferait une écriture disque par bulle au
    /// défilement. On regroupe: la perte, en cas de mort brutale, est une
    /// poignée d'instantanés qui se rechargeront une fois.
    private func scheduleSave() {
        guard !saveScheduled else { return }
        saveScheduled = true
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(2))
            self?.saveScheduled = false
            self?.save()
        }
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(entries) else { return }
        try? data.write(to: storeURL, options: .atomic)
    }

    private func load() {
        // Décodage TOLÉRANT: un fichier illisible redonne un magasin VIDE, ce
        // qui coûte un rechargement par message. La leçon vient du magasin de
        // messages, où un `try` nu sur un enregistrement inconnu faisait perdre
        // tout l'historique — ici, au pire, on perd des chiffres reconstituables.
        guard let data = try? Data(contentsOf: storeURL),
              let decoded = try? JSONDecoder().decode([String: Snapshot].self, from: data)
        else { return }
        entries = decoded
    }

    /// Pour les tests.
    func removeAll() {
        entries.removeAll()
        try? FileManager.default.removeItem(at: storeURL)
    }

    var count: Int { entries.count }
}

extension TickerQuoteSnapshots.Snapshot {
    /// La même forme que ce que le réseau rend, pour que la vue n'ait qu'UN
    /// chemin de rendu. Deux chemins divergeraient à la première retouche.
    var quote: TickerQuoteFetcher.Quote {
        TickerQuoteFetcher.Quote(symbol: symbol, price: price,
                                 previousClose: previousClose,
                                 currency: currency, series: series)
    }
}
