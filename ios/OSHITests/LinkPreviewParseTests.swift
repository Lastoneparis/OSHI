//
//  LinkPreviewParseTests.swift
//  OSHITests
//
//  __OG_ATTRIBUTE_ORDER_2026_08_26__ Pourquoi un lien d'actualite n'affichait
//  AUCUNE image alors que Telegram montrait la carte complete.
//
//  Les anciens motifs exigeaient un ordre d'attributs strict. Le balisage reel
//  de `independent.co.uk` — react-helmet, `data-rh="true"` en tete de CHAQUE
//  tag du `<head>` — le violait, et les sept motifs echouaient d'un coup:
//  titre, description, image, nom du site, favicon. L'apercu etait vide.
//
//  Les fixtures ci-dessous sont copiees de la page telle qu'elle etait servie
//  ce jour-la, pas inventees.
//

import XCTest
@testable import OSHI

final class LinkPreviewParseTests: XCTestCase {

    private let fetcher = LinkPreviewFetcher()
    private let pageURL = "https://www.independent.co.uk/news/world/europe/ukraine-russia-war-live.html"

    /// Le balisage EXACT qui cassait tout, `data-rh` compris.
    private let realWorldHead = """
    <html><head>
    <title data-rh="true">Ukraine war live | The Independent</title>
    <meta data-rh="true" name="description" content="Russia-Ukraine war updates">
    <meta data-rh="true" property="og:site_name" content="The Independent">
    <meta data-rh="true" property="og:title" content="Ukraine war live: Mystery as military plane leaves Moscow">
    <meta data-rh="true" property="og:description" content="Flightradar 24 tracked the plane">
    <meta data-rh="true" property="og:image" content="https://static.independent.co.uk/2026/08/25/nrtegfwrbf.jpeg?width=1200">
    <link data-rh="true" rel="icon" type="image/png" href="/img/shortcut-icons/icon-192x192.png" sizes="192x192">
    </head><body></body></html>
    """

    /// LE test du defaut signale: une image, sur la vraie page.
    func testOgImageIsFoundDespiteALeadingDataAttribute() {
        let p = fetcher.parseHTML(realWorldHead, url: pageURL)
        XCTAssertEqual(p.imageURL,
                       "https://static.independent.co.uk/2026/08/25/nrtegfwrbf.jpeg?width=1200")
    }

    /// Ce n'etait pas QUE l'image: tout le reste tombait avec elle.
    func testEveryFieldSurvivesTheLeadingDataAttribute() {
        let p = fetcher.parseHTML(realWorldHead, url: pageURL)
        XCTAssertEqual(p.title, "Ukraine war live: Mystery as military plane leaves Moscow")
        XCTAssertEqual(p.description, "Flightradar 24 tracked the plane")
        XCTAssertEqual(p.siteName, "The Independent")
        XCTAssertEqual(p.favicon,
                       "https://www.independent.co.uk/img/shortcut-icons/icon-192x192.png",
                       "le href doit etre rendu absolu")
    }

    /// Le repli `<title>` refusait tout attribut. Sans og:title, il est seul.
    func testTitleTagWithAttributesIsStillRead() {
        let html = "<html><head><title data-rh=\"true\">Just A Title</title></head></html>"
        XCTAssertEqual(fetcher.parseHTML(html, url: pageURL).title, "Just A Title")
    }

    /// L'ordre ne doit plus rien decider: `content` peut preceder `property`.
    func testReversedAttributeOrder() {
        let html = "<meta content=\"https://ex.com/a.jpg\" property=\"og:image\">"
        XCTAssertEqual(fetcher.parseHTML(html, url: pageURL).imageURL, "https://ex.com/a.jpg")
    }

    /// Les guillemets simples sont du HTML valide.
    func testSingleQuotedAttributes() {
        let html = "<meta property='og:image' content='https://ex.com/b.jpg'>"
        XCTAssertEqual(fetcher.parseHTML(html, url: pageURL).imageURL, "https://ex.com/b.jpg")
    }

    /// La casse d'un nom de tag ou d'attribut ne doit pas decider non plus.
    func testUppercaseTagsAndAttributes() {
        let html = "<META PROPERTY=\"OG:IMAGE\" CONTENT=\"https://ex.com/c.jpg\">"
        XCTAssertEqual(fetcher.parseHTML(html, url: pageURL).imageURL, "https://ex.com/c.jpg")
    }

    /// `&amp;` dans une URL d'image: sans decodage, la requete part deformee et
    /// le CDN rend une erreur — une image « cassee » au lieu d'absente.
    func testAmpersandEntityInImageURLIsDecoded() {
        let html = "<meta property=\"og:image\" content=\"https://ex.com/d.jpg?w=1200&amp;h=800\">"
        XCTAssertEqual(fetcher.parseHTML(html, url: pageURL).imageURL,
                       "https://ex.com/d.jpg?w=1200&h=800")
    }

    /// Certains sites ne posent que la variante Twitter, ou que la securisee.
    func testFallbackChainForImages() {
        let secure = "<meta property=\"og:image:secure_url\" content=\"https://ex.com/s.jpg\">"
        XCTAssertEqual(fetcher.parseHTML(secure, url: pageURL).imageURL, "https://ex.com/s.jpg")

        let twitter = "<meta name=\"twitter:image\" content=\"https://ex.com/t.jpg\">"
        XCTAssertEqual(fetcher.parseHTML(twitter, url: pageURL).imageURL, "https://ex.com/t.jpg")
    }

    /// Une page sans la moindre metadonnee ne doit pas inventer une image.
    func testNoMetadataYieldsNoImage() {
        let p = fetcher.parseHTML("<html><body>rien</body></html>", url: pageURL)
        XCTAssertNil(p.imageURL)
        XCTAssertNil(p.title)
        // Le nom du site retombe sur le domaine.
        XCTAssertEqual(p.siteName, "Independent")
    }

    /// 🔴 __TWO_LABEL_TLD_2026_08_26__ Un `.co.uk` s'annoncait comme « Co ».
    func testTwoLabelTLDsDoNotBecomeCo() {
        func site(_ u: String) -> String? {
            fetcher.parseHTML("<html></html>", url: u).siteName
        }
        XCTAssertEqual(site("https://www.independent.co.uk/a"), "Independent")
        XCTAssertEqual(site("https://www.bbc.co.uk/news"), "Bbc")
        XCTAssertEqual(site("https://www.abc.net.au/news"), "Abc")
        XCTAssertEqual(site("https://asahi.co.jp/x"), "Asahi")
        // Le cas simple ne doit pas regresser.
        XCTAssertEqual(site("https://www.example.com/x"), "Example")
        XCTAssertEqual(site("https://news.ycombinator.com/x"), "Ycombinator")
    }

    /// Un `content` vide ne doit pas gagner contre un repli utile.
    func testEmptyContentIsIgnored() {
        let html = """
        <meta property="og:image" content="">
        <meta name="twitter:image" content="https://ex.com/real.jpg">
        """
        XCTAssertEqual(fetcher.parseHTML(html, url: pageURL).imageURL, "https://ex.com/real.jpg")
    }

    /// `rel="shortcut icon"` porte deux mots, et `href` n'est pas colle a `rel`.
    func testShortcutIconWithExtraAttributes() {
        let html = "<link rel=\"shortcut icon\" type=\"image/x-icon\" href=\"/fav.ico\">"
        XCTAssertEqual(fetcher.parseHTML(html, url: pageURL).favicon,
                       "https://www.independent.co.uk/fav.ico")
    }
}
