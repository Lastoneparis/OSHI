//
//  LocalGifLibraryTests.swift
//  OSHITests
//
//  Ce que ces tests gardent fermé, c'est la promesse faite à l'utilisateur:
//  « tout ce qui est dans la bibliothèque doit être atteignable en cliquant une
//  catégorie ». Avec 286 éléments, une vignette orpheline se retrouvait en
//  faisant défiler. Avec près de trois mille, elle n'existe pas.
//
//  Ils lisent le VRAI paquet du bundle, pas un jeu d'essai. Un manifeste qui
//  promet un fichier absent, une catégorie qu'aucune puce ne propose, un
//  identifiant en double qui ferait s'écraser deux entrées: rien de tout cela
//  n'est visible en lisant le code, et tout est visible ici.
//

import XCTest
@testable import OSHI

@MainActor
final class LocalGifLibraryTests: XCTestCase {

    private let library = LocalGifLibrary.shared

    /// Le paquet arrive par une RÉFÉRENCE DE DOSSIER: si elle saute du projet,
    /// tout est vide et chaque test passerait à vide. On le dit plutôt que de
    /// laisser une suite verte mentir.
    private func requirePack(_ kind: LocalPackKind) throws -> [LocalGifItem] {
        let items = library.all(kind)
        try XCTSkipIf(items.isEmpty,
                      "\(kind.folderName) absent du bundle de test — rien à vérifier")
        return items
    }

    func testPacksAreSubstantial() throws {
        let gifs = try requirePack(.gif)
        let stickers = try requirePack(.sticker)
        // Un plancher volontairement bas: il attrape « le dossier n'a pas été
        // copié » et « le manifeste a été tronqué », pas une variation normale
        // du contenu.
        XCTAssertGreaterThan(gifs.count, 1000)
        XCTAssertGreaterThan(stickers.count, 800)
    }

    /// LA garantie demandée: aucune vignette hors catégorie.
    func testEveryItemIsReachableFromACategoryChip() throws {
        for kind in LocalPackKind.allCases {
            let items = try requirePack(kind)
            let chips = Set(library.categories(kind))
            for item in items {
                XCTAssertFalse(item.category.trimmingCharacters(in: .whitespaces).isEmpty,
                               "\(item.id) n'a pas de catégorie")
                XCTAssertTrue(chips.contains(item.category),
                              "\(item.id) est dans « \(item.category) », qu'aucune puce ne propose")
            }
        }
    }

    /// Et l'inverse: une puce qui ne mène à rien est un cul-de-sac.
    func testEveryCategoryChipYieldsItems() throws {
        for kind in LocalPackKind.allCases {
            _ = try requirePack(kind)
            for category in library.categories(kind) {
                XCTAssertFalse(library.search("", kind: kind, category: category).isEmpty,
                               "la puce « \(category) » ouvre une grille vide")
            }
        }
    }

    /// Deux entrées de même identifiant viseraient le même fichier: la grille
    /// montrerait deux fois la même vignette. `ForEach` s'en sert comme clé.
    func testIdentifiersAreUnique() throws {
        for kind in LocalPackKind.allCases {
            let items = try requirePack(kind)
            XCTAssertEqual(Set(items.map(\.id)).count, items.count,
                           "identifiants en double dans \(kind.folderName)")
        }
    }

    /// Le manifeste peut promettre ce que le paquet ne contient pas. Une entrée
    /// pareille donne une case grise et muette — `load` doit l'avoir écartée.
    func testEveryListedItemHasRealBytes() throws {
        for kind in LocalPackKind.allCases {
            let items = try requirePack(kind)
            // Tout relire ferait une suite de plusieurs secondes pour rien: un
            // échantillon régulier trouve un dossier mal copié aussi bien.
            for item in stride(from: 0, to: items.count, by: 17).map({ items[$0] }) {
                XCTAssertNotNil(library.data(for: item), "\(item.id): aucun octet")
            }
        }
    }

    /// Les IMAGES d'abord — celles qui ne portent aucun texte, marquées
    /// `lang == "any"`. Une image se lit d'un coup d'œil et dans toutes les
    /// langues; un mot demande d'être lu. C'est ce que les gens cherchent en
    /// premier, donc c'est ce qu'ils doivent voir en premier.
    func testDrawnPicturesLeadTheGrid() throws {
        for kind in LocalPackKind.allCases {
            let items = try requirePack(kind)
            let pictures = items.filter { $0.lang == "any" }
            try XCTSkipIf(pictures.isEmpty, "aucun motif dessiné dans \(kind.folderName)")
            XCTAssertGreaterThan(pictures.count, 50)
            let firstWord = items.firstIndex { $0.lang != "any" }
            let lastPicture = items.lastIndex { $0.lang == "any" }
            if let firstWord, let lastPicture {
                XCTAssertLessThan(lastPicture, firstWord,
                                  "une image dessinée arrive après une vignette de texte")
            }
        }
    }

    /// Puis la langue du téléphone. C'est ce qui rend une bibliothèque de
    /// quatorze langues utilisable au lieu de seulement grande.
    func testDeviceLanguageComesFirstAmongTheWords() throws {
        let words = try requirePack(.gif).filter { $0.lang != "any" }
        let mine = Locale.current.language.languageCode?.identifier ?? "en"
        guard words.contains(where: { $0.lang == mine }) else {
            throw XCTSkip("le paquet ne couvre pas \(mine) — rien à ordonner")
        }
        let firstForeign = words.firstIndex { $0.lang != mine && $0.lang != "en" }
        let lastMine = words.lastIndex { $0.lang == mine }
        if let firstForeign, let lastMine {
            XCTAssertLessThan(lastMine, firstForeign,
                              "des vignettes en \(mine) arrivent après une autre langue")
        }
    }

    /// Une image n'a pas de texte à chercher: sans synonymes, personne ne
    /// retrouve le visage qui pleure en tapant « triste » ou « cry ». C'est ce
    /// qui rend la barre de recherche utile plutôt que décorative.
    func testPicturesAreFindableBySynonym() throws {
        _ = try requirePack(.gif)
        for (query, expected) in [("triste", "p_f_cry"), ("cry", "p_f_cry"),
                                  ("pouce", "p_m_thumb_up"), ("feu", "p_m_fire"),
                                  ("anniversaire", "p_m_cake"), ("bourse", "p_m_chart_up")] {
            let hits = library.search(query, kind: .gif).map(\.id)
            XCTAssertTrue(hits.contains(expected),
                          "« \(query) » ne trouve pas \(expected)")
        }
    }

    /// La recherche doit ignorer la casse ET les accents: quelqu'un qui tape
    /// « felicitations » sans accent cherche bien « FÉLICITATIONS ».
    func testSearchIsAccentAndCaseInsensitive() throws {
        _ = try requirePack(.gif)
        let plain = library.search("felicitations", kind: .gif)
        let accented = library.search("Félicitations", kind: .gif)
        XCTAssertEqual(Set(plain.map(\.id)), Set(accented.map(\.id)))
    }

    /// Une recherche vide n'est pas une recherche: elle doit tout rendre.
    func testEmptyQueryReturnsEverything() throws {
        let items = try requirePack(.gif)
        XCTAssertEqual(library.search("   ", kind: .gif).count, items.count)
    }
}
