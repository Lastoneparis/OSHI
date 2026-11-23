//
//  LocalGifLibrary.swift
//  OSHI
//
//  __LOCAL_GIF_LIBRARY_2026_09_13__ La bibliothèque de GIFs et d'autocollants
//  livrée AVEC l'app, lue depuis le paquet, sans réseau et sans clé d'API.
//
//  POURQUOI PAS TENOR / GIPHY PAR DÉFAUT. Les deux exigent une clé, imposent une
//  mention d'attribution, et surtout font PARTIR chaque recherche de l'appareil.
//  Pour un messager sans compte dont le troisième marché est la Russie, suivie de
//  la Turquie, de l'Arabie saoudite et de l'Irak, envoyer la liste de ce que les
//  gens tapent à un tiers est un mauvais défaut. Et c'est incohérent: un sélecteur
//  de GIFs qui exige Internet ne marche pas pendant la coupure réseau pour
//  laquelle cette app existe.
//
//  `TenorClient` reste dans le projet, inutilisé, pour le jour où un flux « en
//  tendance » relayé par le serveur OSHI sera branché — son point d'accès tient
//  en deux constantes exactement pour ça.
//
//  LES DEUX DOSSIERS SONT DES RÉFÉRENCES DE DOSSIER (« blue folders ») dans le
//  projet: tout le répertoire est copié dans le paquet tel quel. Ajouter un GIF
//  demande donc de déposer un fichier et une ligne de manifeste — jamais de
//  toucher au projet Xcode.
//

import Foundation

/// Un élément de la bibliothèque locale.
struct LocalGifItem: Identifiable, Hashable {
    let id: String
    let fileName: String
    let category: String
    let tags: [String]
    let kind: LocalPackKind
    /// Code ISO à deux lettres de la langue du texte dessiné dans l'image.
    ///
    /// Le paquet est multilingue et les images sont PRÉ-RENDUES: « СПАСИБО » est
    /// des pixels, pas une chaîne que le téléphone pourrait traduire. Savoir de
    /// quelle langue relève chaque vignette est donc la seule façon de montrer
    /// la sienne à quelqu'un en premier.
    let lang: String

    /// Où le fichier vit dans le paquet.
    var bundleURL: URL? {
        Bundle.main.url(forResource: (fileName as NSString).deletingPathExtension,
                        withExtension: "gif",
                        subdirectory: kind.folderName)
    }
}

enum LocalPackKind: String, CaseIterable, Hashable {
    case gif
    case sticker

    var folderName: String { self == .gif ? "GifPack" : "StickerPack" }

    var title: String {
        self == .gif ? "gif.tab.gifs".localizedOrFallback("GIFs")
                     : "gif.tab.stickers".localizedOrFallback("Stickers")
    }
}

extension String {
    /// Les clés de cette fonctionnalité ne sont pas encore traduites dans les 34
    /// locales. Renvoyer l'anglais est correct; renvoyer la CLÉ BRUTE afficherait
    /// `gif.tab.gifs` à l'écran dans 33 langues sur 34.
    func localizedOrFallback(_ fallback: String) -> String {
        let v = localized
        return v == self ? fallback : v
    }
}

@MainActor
final class LocalGifLibrary: ObservableObject {
    static let shared = LocalGifLibrary()

    @Published private(set) var items: [LocalPackKind: [LocalGifItem]] = [:]

    private init() {
        for kind in LocalPackKind.allCases {
            items[kind] = Self.load(kind)
        }
    }

    // MARK: - Lecture du manifeste

    private struct Manifest: Decodable {
        struct Entry: Decodable {
            let id: String
            let file: String
            let category: String
            let tags: [String]
            /// Absent des 286 entrées d'origine, qui sont toutes anglophones.
            let lang: String?
        }
        let gifs: [Entry]
    }

    private static func load(_ kind: LocalPackKind) -> [LocalGifItem] {
        guard let url = Bundle.main.url(forResource: "manifest", withExtension: "json",
                                        subdirectory: kind.folderName),
              let data = try? Data(contentsOf: url) else {
            OshiLog.storage.info("🎬 LocalGifLibrary: no manifest for \(kind.folderName)")
            return []
        }
        // Décodage TOLÉRANT, par élément: une entrée mal formée ne doit pas
        // emporter la bibliothèque entière. C'est la même leçon que le magasin de
        // messages, où un seul enregistrement inconnu effaçait tout l'historique.
        guard let manifest = try? JSONDecoder().decode(Manifest.self, from: data) else {
            OshiLog.storage.info("🎬 LocalGifLibrary: manifest unreadable for \(kind.folderName)")
            return []
        }
        let items = manifest.gifs.map {
            LocalGifItem(id: $0.id, fileName: $0.file, category: $0.category,
                         tags: $0.tags, kind: kind, lang: $0.lang ?? "en")
        }
        // Ne garder que ce qui est RÉELLEMENT dans le paquet: un manifeste qui
        // promet un fichier absent donnerait une case vide et silencieuse.
        let present = items.filter { $0.bundleURL != nil }
        if present.count != items.count {
            OshiLog.storage.info("🎬 LocalGifLibrary: \(items.count - present.count) entr(y/ies) in \(kind.folderName) have no file")
        }
        return localeFirst(present)
    }

    /// La langue du téléphone d'abord, puis l'anglais, puis le reste.
    ///
    /// Le paquet couvre quatorze langues. Présenté dans l'ordre du manifeste, un
    /// utilisateur russe ferait défiler des centaines de vignettes anglaises,
    /// françaises et arabes avant d'atteindre les siennes — et conclurait, à
    /// raison, que ce paquet n'est pas pour lui. C'est la seule chose qui rend
    /// une bibliothèque multilingue utilisable plutôt que seulement grande.
    ///
    /// Le tri est STABLE à l'intérieur de chaque groupe: l'ordre du manifeste
    /// est celui du contenu, et le contenu récent y est placé en tête.
    private static func localeFirst(_ items: [LocalGifItem]) -> [LocalGifItem] {
        let mine = Locale.current.language.languageCode?.identifier ?? "en"
        func rank(_ lang: String) -> Int {
            // « any » = un motif DESSINÉ, sans texte. Il passe devant tout le
            // monde: une image se lit d'un coup d'œil et dans toutes les
            // langues, là où un mot demande d'être lu.
            if lang == "any" { return 0 }
            if lang == mine { return 1 }
            if lang == "en" { return 2 }
            return 3
        }
        return items.enumerated()
            .sorted { a, b in
                let ra = rank(a.element.lang), rb = rank(b.element.lang)
                return ra == rb ? a.offset < b.offset : ra < rb
            }
            .map(\.element)
    }

    // MARK: - Parcours

    func all(_ kind: LocalPackKind) -> [LocalGifItem] { items[kind] ?? [] }

    /// Catégories présentes, dans un ordre stable et lisible plutôt qu'alphabétique
    /// pur: on veut « Reactions » avant « Urgent » dans une rangée de puces.
    func categories(_ kind: LocalPackKind) -> [String] {
        // L'ordre des puces, décidé et pas alphabétique: on veut « Reactions »
        // avant « Urgent ». Toute catégorie qui apparaîtrait dans le manifeste
        // sans être listée ici reste proposée — à la fin, triée — parce qu'une
        // vignette qu'aucune puce n'atteint n'est trouvable que par hasard.
        let preferred = ["Reactions", "Yes / No", "Greetings", "Thanks", "Sorry",
                         "Love", "Celebrate", "Funny", "Mood", "Urgent",
                         "Work", "Question", "Food", "Night", "Animals", "OSHI"]
        let present = Set(all(kind).map(\.category))
        return preferred.filter(present.contains) + present.subtracting(preferred).sorted()
    }

    /// Recherche sur l'identifiant ET les étiquettes, insensible à la casse et aux
    /// accents — « felicitations » doit trouver « félicitations ».
    func search(_ query: String, kind: LocalPackKind, category: String? = nil) -> [LocalGifItem] {
        var pool = all(kind)
        if let category { pool = pool.filter { $0.category == category } }

        let q = query.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return pool }

        return pool.filter { item in
            let hay = ([item.id] + item.tags).joined(separator: " ")
                .folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil)
            return hay.contains(q)
        }
    }

    /// Les octets d'une vignette, mis en cache.
    ///
    /// SwiftUI réévalue le corps d'une cellule bien plus souvent qu'elle
    /// n'apparaît, et cette fonction est appelée depuis ce corps, sur le fil
    /// principal. À 286 éléments relire le fichier à chaque fois passait
    /// inaperçu; à près de deux mille, chaque défilement devient une rafale de
    /// lectures disque synchrones et la grille saccade.
    ///
    /// `NSCache` plutôt qu'un dictionnaire: il se vide tout seul sous pression
    /// mémoire, ce qui est exactement ce qu'on veut d'un cache d'images qu'on
    /// peut toujours relire depuis le paquet.
    func data(for item: LocalGifItem) -> Data? {
        if let hit = cache.object(forKey: item.fileName as NSString) { return hit as Data }
        guard let url = item.bundleURL, let data = try? Data(contentsOf: url) else { return nil }
        cache.setObject(data as NSData, forKey: item.fileName as NSString, cost: data.count)
        return data
    }

    private let cache: NSCache<NSString, NSData> = {
        let c = NSCache<NSString, NSData>()
        c.countLimit = 400
        c.totalCostLimit = 16 * 1024 * 1024
        return c
    }()
}
