//
//  GiphyClient.swift
//  OSHI
//
//  __GIPHY_2026_09_13__ La source EN LIGNE qui allonge le paquet local.
//
//  Il n'y a PAS d'onglet « GIPHY » dans le sélecteur: ces résultats sont ajoutés
//  À LA SUITE du paquet local, dans les onglets GIFs et Autocollants. La personne
//  qui s'en sert voit une bibliothèque, pas deux fournisseurs.
//
//  POURQUOI PAR L'API ET PAS EN TÉLÉCHARGEANT LE CONTENU. Les GIFs de GIPHY
//  appartiennent à ceux qui les ont mis en ligne; les étiquettes « public domain »
//  qu'on y lit sont des MOTS-CLÉS saisis par ces mêmes personnes, pas une licence.
//  Les conditions de GIPHY autorisent l'affichage VIA leur API, pas la
//  redistribution des fichiers dans un binaire. On les affiche donc en direct, on
//  n'en embarque aucun, et la mention « Powered by GIPHY » est affichée — c'est
//  ce que leur licence demande en échange.
//
//  ⚠️ LA CLÉ BÊTA PLAFONNE À 100 APPELS PAR HEURE, TOUS UTILISATEURS CONFONDUS.
//  Ce n'est pas un quota par téléphone: c'est le total de l'application. Quelques
//  personnes qui ouvrent le sélecteur en même temps l'épuisent. D'où trois
//  précautions, qui sont la seule raison pour laquelle cet onglet est utilisable:
//    1. une frappe ne déclenche PAS un appel — 500 ms de silence d'abord;
//    2. tout résultat est mis en cache en mémoire, donc rouvrir un terme déjà vu
//       ne coûte rien;
//    3. sur 429 (ou toute panne), la grille ne dit RIEN et redevient simplement
//       le paquet local — une erreur pour un GIF indisponible serait du bruit.
//  Une clé de production lèvera ce plafond; rien d'autre n'a à changer.
//
//  RIEN NE PART DE L'APPAREIL TANT QUE LE SÉLECTEUR N'EST PAS OUVERT. Le paquet local
//  reste le défaut, et c'est délibéré: une recherche envoie à Google ce que la
//  personne tape, ce qui n'est pas un défaut souhaitable pour un messager sans
//  compte dont le troisième marché est la Russie, suivie de la Turquie, de
//  l'Arabie saoudite et de l'Irak.
//

import Foundation

enum GiphyKind: String {
    case gif  = "gifs"
    case sticker = "stickers"
}

struct GiphyItem: Identifiable, Hashable {
    let id: String
    let title: String
    /// Petite rendition pour la grille — jamais l'originale, qui peut peser
    /// plusieurs mégaoctets par vignette.
    let previewURL: URL
    /// Rendition choisie pour l'envoi, bornée en taille.
    let sendURL: URL
}

enum GiphyError: LocalizedError {
    case notConfigured
    case rateLimited
    case transport(String)

    var errorDescription: String? {
        switch self {
        case .notConfigured: return "GIPHY is not configured"
        case .rateLimited:   return "GIPHY hourly limit reached — the offline pack still works"
        case .transport(let m): return m
        }
    }
}

actor GiphyClient {
    static let shared = GiphyClient()

    /// Clé BÊTA. Une clé client est par nature extractible du binaire — c'est le
    /// modèle de GIPHY, pas une négligence. Ne rien y mettre d'autre que ça.
    // __GIPHY_PROXY_2026_09_25__ No API key in the app any more: requests go through the
    // OSHI proxy, which adds the key server-side, rate-limits, and strips the caller's IP
    // and identifiers before talking to GIPHY. (The key that used to live here was
    // extractable from the binary and let anyone drain the shared quota.)
    static let proxyBase = "https://oshi-messenger.com/api/giphy/"
    static var isConfigured: Bool { true }

    static let searchDebounce: Duration = .milliseconds(500)

    /// Au-delà, on ne propose pas: un GIF de plusieurs mégaoctets bascule l'envoi
    /// sur IPFS au lieu de la maille, et c'est l'inverse du but.
    static let maxSendBytes = 5 * 1024 * 1024

    private var cache: [String: [GiphyItem]] = [:]
    private let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral   // pas de cookie, pas de cache disque
        // Six secondes, pas douze. Un sélecteur de GIFs qui réfléchit douze
        // secondes est cassé de toute façon, et pendant ce temps la grille tient
        // des cases vides réservées: mieux vaut abandonner tôt et rendre la
        // place au paquet local.
        c.timeoutIntervalForRequest = 6
        return URLSession(configuration: c)
    }()

    // MARK: - Requêtes

    /// 50 est le maximum que GIPHY sert en un appel. On le prend: ces vignettes
    /// arrivent maintenant EN TÊTE de la grille, et une première page maigre
    /// serait la première chose que l'on voit.
    static let pageSize = 50


    /// Dix pages, soit cinq cents vignettes distantes avant le paquet local.
    ///
    /// ⚠️ C'EST LA CLÉ BÊTA QUI DEVIENT LE MUR, PAS CE NOMBRE. Chaque page est un
    /// appel, et la clé bêta en autorise cent par heure POUR TOUTE
    /// L'APPLICATION — pas par téléphone. Une personne qui déroule jusqu'au bout
    /// en dépense dix; dix personnes en même temps épuisent le quota horaire de
    /// tout le monde, et la grille retombe alors sur le paquet local, en
    /// silence. Une clé de production lève ce plafond et rien d'autre n'a à
    /// changer ici — c'est la seule chose qui manque pour que ce nombre soit
    /// tenable en vrai.
    ///
    /// Ce qui limite les dégâts en attendant: le cache est par terme ET par
    /// page, donc rouvrir le sélecteur sur une recherche déjà vue ne coûte rien.
    static let maxPages = 10

    /// Le flux par défaut, quand personne n'a rien tapé.
    ///
    /// ⚠️ « TENDANCES » NE CONTIENT QUE ~174 ÉLÉMENTS. Mesuré, pas supposé:
    /// `offset=450` rend zéro et l'API annonce `total_count: 174`. Le flux par
    /// défaut plafonnerait donc à trois écrans et demi — alors qu'une RECHERCHE
    /// va jusqu'à 500 par terme.
    ///
    /// D'où ce mélange: la première page est la vraie tendance du jour, et les
    /// suivantes puisent dans une liste de termes larges. Ce n'est pas du
    /// remplissage: ce sont les catégories que les gens cherchent effectivement
    /// dans un sélecteur de GIFs, et ça donne un défaut profond et varié plutôt
    /// qu'une liste qui s'arrête sans raison visible.
    static let feedTerms = ["", "reaction", "funny", "love", "dance",
                            "happy", "wow", "excited", "hello", "thank you"]

    func feed(_ kind: GiphyKind, page: Int) async throws -> [GiphyItem] {
        guard page < Self.feedTerms.count else { return [] }
        let term = Self.feedTerms[page]
        return term.isEmpty
            ? try await trending(kind, offset: page * Self.pageSize)
            : try await search(term, kind: kind, offset: 0)
    }

    func trending(_ kind: GiphyKind, limit: Int = pageSize, offset: Int = 0) async throws -> [GiphyItem] {
        try await fetch(path: "\(kind.rawValue)/trending", query: [],
                        cacheKey: "trend:\(kind.rawValue):\(offset)", limit: limit, offset: offset)
    }

    func search(_ term: String, kind: GiphyKind, limit: Int = pageSize, offset: Int = 0) async throws -> [GiphyItem] {
        let t = term.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return try await trending(kind, limit: limit, offset: offset) }
        return try await fetch(path: "\(kind.rawValue)/search",
                               query: [URLQueryItem(name: "q", value: t)],
                               cacheKey: "q:\(kind.rawValue):\(t.lowercased()):\(offset)",
                               limit: limit, offset: offset)
    }

    private func fetch(path: String, query: [URLQueryItem], cacheKey: String,
                       limit: Int, offset: Int) async throws -> [GiphyItem] {
        guard Self.isConfigured else { throw GiphyError.notConfigured }
        if let hit = cache[cacheKey] { return hit }

        var c = URLComponents(string: Self.proxyBase + path)!
        c.queryItems = [
            URLQueryItem(name: "limit", value: String(limit)),
            URLQueryItem(name: "offset", value: String(offset)),
            // `g` = tout public. Un messager n'a pas à servir autre chose par défaut.
            URLQueryItem(name: "rating", value: "g"),
            // La LANGUE aide la pertinence; le PAYS n'est délibérément pas envoyé.
            URLQueryItem(name: "lang", value: String(Locale.current.identifier.prefix(2)))
        ] + query

        let (data, response) = try await session.data(from: c.url!)
        if let http = response as? HTTPURLResponse {
            if http.statusCode == 429 { throw GiphyError.rateLimited }
            guard (200..<300).contains(http.statusCode) else {
                throw GiphyError.transport("GIPHY HTTP \(http.statusCode)")
            }
        }
        let items = Self.parse(data)
        cache[cacheKey] = items
        return items
    }

    /// Analyse TOLÉRANTE: une entrée dont il manque une rendition est ignorée,
    /// elle n'emporte pas la page. Même règle que partout ailleurs ici.
    private static func parse(_ data: Data) -> [GiphyItem] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let arr = root["data"] as? [[String: Any]] else { return [] }
        return arr.compactMap { entry in
            guard let id = entry["id"] as? String,
                  let images = entry["images"] as? [String: Any] else { return nil }
            func url(_ name: String) -> URL? {
                guard let r = images[name] as? [String: Any],
                      let s = r["url"] as? String else { return nil }
                return URL(string: s)
            }
            func bytes(_ name: String) -> Int {
                guard let r = images[name] as? [String: Any],
                      let s = r["size"] as? String, let v = Int(s) else { return .max }
                return v
            }
            guard let preview = url("fixed_width_small") ?? url("preview_gif") ?? url("fixed_width") else { return nil }
            // Du plus petit au plus grand, on prend la PREMIÈRE qui tient dans le
            // budget: compresser un GIF l'aplatit, donc la seule façon de réduire
            // la taille est de choisir une autre rendition.
            let candidates = ["downsized_small", "fixed_width", "downsized", "original"]
            guard let send = candidates.first(where: { bytes($0) <= maxSendBytes && url($0) != nil })
                    .flatMap({ url($0) }) ?? url("fixed_width") else { return nil }
            return GiphyItem(id: id,
                             title: (entry["title"] as? String) ?? "",
                             previewURL: preview, sendURL: send)
        }
    }

    // MARK: - Octets

    func download(_ url: URL) async throws -> Data {
        let (data, _) = try await session.data(from: url)
        return data
    }
}
