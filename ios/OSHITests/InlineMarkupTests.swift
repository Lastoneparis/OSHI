//
//  InlineMarkupTests.swift
//  OSHITests
//
//  This parser runs on EVERY message anyone sends or receives, including text
//  nobody meant as formatting. The failure that matters is not "bold didn't
//  work" — it is ordinary punctuation silently eating a message: a file name
//  with underscores turning italic, a stray asterisk bolding the rest of a
//  paragraph, or a marker disappearing from text where it was the actual
//  content.
//
//  So most of what is pinned here is what must NOT happen.
//

import XCTest
@testable import OSHI

final class InlineMarkupTests: XCTestCase {

    private func plain(_ s: String) -> String { InlineMarkup.parse(s).0 }
    private func spans(_ s: String) -> [InlineMarkup.Span] { InlineMarkup.parse(s).1 }

    // MARK: It formats

    func testBoldItalicStrikeAndCode() {
        XCTAssertEqual(plain("a *bold* b"), "a bold b")
        XCTAssertEqual(plain("a _it_ b"), "a it b")
        XCTAssertEqual(plain("a ~no~ b"), "a no b")
        XCTAssertEqual(plain("a `code` b"), "a code b")

        XCTAssertEqual(spans("a *bold* b").count, 1)
        XCTAssertEqual(spans("a *bold* b").first?.length, 4)
    }

    /// The marker must be gone from what the user reads — leaving it visible is
    /// the same as not supporting formatting, only messier.
    func testMarkersAreRemovedFromTheVisibleText() {
        for s in ["*x*", "_x_", "~x~", "`x`"] {
            XCTAssertEqual(plain(s), "x", "markers survived in \(s)")
        }
    }

    func testSeveralRunsInOneMessage() {
        let text = "make it *bold* and _italic_ please"
        XCTAssertEqual(plain(text), "make it bold and italic please")
        XCTAssertEqual(spans(text).count, 2)
    }

    /// The offsets must land on the right characters, or the wrong words get
    /// styled — which looks like a rendering bug, not a parser bug.
    func testSpanOffsetsPointAtTheStyledRun() {
        let text = "hello *world* end"
        let out = plain(text)
        let span = try! XCTUnwrap(spans(text).first)
        let ns = out as NSString
        XCTAssertEqual(ns.substring(with: NSRange(location: span.location, length: span.length)),
                       "world")
    }

    // MARK: It leaves ordinary text alone

    /// THE most important case. `my_file_name.txt` must not become italic.
    func testUnderscoresInsideAWordAreNotFormatting() {
        let text = "open my_file_name.txt now"
        XCTAssertEqual(plain(text), text)
        XCTAssertTrue(spans(text).isEmpty)
    }

    /// Arithmetic and shrugs survive.
    func testLoneMarkersAreLeftAsCharacters() {
        for text in ["2 * 3 * 4 = 24", "a_b", "5 ~ 6", "use ` carefully"] {
            XCTAssertEqual(plain(text), text, "text was altered: \(text)")
        }
    }

    /// An unmatched marker must not swallow everything after it — the failure
    /// where one stray asterisk bolds the rest of a long message.
    func testUnmatchedMarkerDoesNotRunAway() {
        let text = "this *is not closed and the message continues for a while"
        XCTAssertEqual(plain(text), text)
        XCTAssertTrue(spans(text).isEmpty)
    }

    /// Formatting does not span lines, so an opener on one line cannot capture
    /// the paragraph below it.
    func testFormattingDoesNotCrossNewlines() {
        let text = "line one *open\nline two* close"
        XCTAssertEqual(plain(text), text)
        XCTAssertTrue(spans(text).isEmpty)
    }

    /// Empty markers are content, not formatting.
    func testEmptyRunIsNotFormatting() {
        XCTAssertEqual(plain("a ** b"), "a ** b")
        XCTAssertTrue(spans("a ** b").isEmpty)
    }

    // MARK: Robustness

    /// The parser must be safe on the text people actually send.
    func testRealisticMessagesAreUnchanged() {
        let samples = [
            "Meet me at 18:30 — bring the keys",
            "https://example.com/path_with_underscores?a=1&b=2",
            "Rendez-vous à l'angle, 2e étage",
            "残り3分で着きます",
            "قابلني عند الجسر",
            "🙂🙃 ok",
            "",
        ]
        for s in samples {
            XCTAssertEqual(plain(s), s, "altered: \(s)")
        }
    }

    /// Multi-byte characters must not corrupt the offsets — the spans are UTF-16
    /// and the text is not ASCII.
    func testOffsetsSurviveEmojiAndNonLatin() {
        let text = "🙂 *gras* été 日本"
        let out = plain(text)
        XCTAssertEqual(out, "🙂 gras été 日本")
        let span = try! XCTUnwrap(spans(text).first)
        XCTAssertEqual((out as NSString).substring(
            with: NSRange(location: span.location, length: span.length)), "gras")
    }

    /// A pathological input must not hang the chat.
    func testLongInputIsHandledQuickly() {
        let text = String(repeating: "word *bold* and _it_ ", count: 2_000)
        let started = Date()
        _ = InlineMarkup.parse(text)
        XCTAssertLessThan(Date().timeIntervalSince(started), 1.0,
                          "parser is too slow for a message-sized input")
    }

    func testHasFormattingMatchesParse() {
        XCTAssertTrue(InlineMarkup.hasFormatting("a *b* c"))
        XCTAssertFalse(InlineMarkup.hasFormatting("a b c"))
        XCTAssertFalse(InlineMarkup.hasFormatting("my_file.txt"))
    }
}

// MARK: - Is the parser actually WIRED to the group bubble?

//
//  Everything above tests `InlineMarkup.parse` — a pure function over strings.
//  A parser can be perfect and correct in all 13 cases above while no message
//  on screen is ever bold, because nothing calls it: that is exactly how
//  `GroupMessageBubble` (1100 lines, every message action written) sat in this
//  file instantiated NOWHERE for months. A string test cannot see that.
//
//  So these rasterise the REAL views the group thread draws — `cleanBubble` →
//  `messageBubble` → `GroupInlineLinkBubble` → `ClickableTextView` — and compare
//  the pixels of a marked-up message against the SAME visible words with no
//  markers. Same glyphs, same width, same colour: if the two images are
//  identical, the formatting reached the screen as nothing at all.
//

import SwiftUI

final class InlineMarkupRenderingTests: XCTestCase {

    /// Rasterise a view to PNG bytes. Fixed size and scale so the only thing
    /// that can move a pixel is the styling under test.
    @MainActor
    private func raster(_ view: some View) throws -> Data {
        let renderer = ImageRenderer(
            content: view
                .frame(width: 300, alignment: .leading)
                .background(Color.white)
        )
        renderer.scale = 2
        let image = try XCTUnwrap(renderer.uiImage, "the view did not rasterise")
        return try XCTUnwrap(image.pngData(), "no PNG data")
    }

    /// The group thread's live text bubble, exactly as `messageBubble` builds it.
    @MainActor
    private func groupBubble(_ text: String) -> some View {
        GroupInlineLinkBubble(
            text: text,
            isMine: false,
            bubbleColor: .white,
            textColor: .black
        )
    }

    /// `*gras*` and `gras` show the SAME four letters. The images may only
    /// differ if the run was actually emboldened.
    @MainActor
    func testGroupBubbleRendersBoldDifferentlyFromPlainText() throws {
        let marked = try raster(groupBubble("Salut *gras* ici"))
        let bare   = try raster(groupBubble("Salut gras ici"))
        XCTAssertNotEqual(marked, bare,
                          "le message de groupe *gras* est rendu pixel pour pixel comme du texte normal — la mise en forme n'atteint pas l'écran")
    }

    @MainActor
    func testGroupBubbleRendersItalicDifferentlyFromPlainText() throws {
        let marked = try raster(groupBubble("Salut _ital_ ici"))
        let bare   = try raster(groupBubble("Salut ital ici"))
        XCTAssertNotEqual(marked, bare,
                          "le message de groupe _italique_ est rendu comme du texte normal")
    }

    @MainActor
    func testGroupBubbleRendersStrikethroughDifferentlyFromPlainText() throws {
        let marked = try raster(groupBubble("Salut ~barre~ ici"))
        let bare   = try raster(groupBubble("Salut barre ici"))
        XCTAssertNotEqual(marked, bare,
                          "le message de groupe ~barré~ est rendu comme du texte normal")
    }

    @MainActor
    func testGroupBubbleRendersCodeDifferentlyFromPlainText() throws {
        let marked = try raster(groupBubble("Salut `code` ici"))
        let bare   = try raster(groupBubble("Salut code ici"))
        XCTAssertNotEqual(marked, bare,
                          "le message de groupe `code` est rendu comme du texte normal — les backticks disparaissent sans rien changer, ce qui est PIRE que de ne pas les interpréter")
    }

    /// The markers must not survive into the picture either: a bubble drawn from
    /// `*gras*` must look like one drawn from `gras`, not like one drawn from
    /// the literal string with its asterisks still in it.
    @MainActor
    func testMarkersThemselvesAreNotDrawn() throws {
        let marked  = try raster(groupBubble("Salut *gras* ici"))
        let literal = try raster(groupBubble("Salut ⁎gras⁎ ici"))
        XCTAssertNotEqual(marked, literal,
                          "les astérisques semblent encore dessinés dans la bulle")
    }

    /// The 1:1 thread renders through the same view, but with the bubble style's
    /// weight passed in. `.fontWeight()` sits on the same `Text` as the
    /// attributed run, so it is worth pinning that it does not flatten it.
    @MainActor
    func testExplicitFontWeightDoesNotFlattenBold() throws {
        let marked = try raster(
            ClickableTextView(text: "Salut *gras* ici", textColor: .black,
                              linkColor: .blue, weight: .regular)
        )
        let bare = try raster(
            ClickableTextView(text: "Salut gras ici", textColor: .black,
                              linkColor: .blue, weight: .regular)
        )
        XCTAssertNotEqual(marked, bare,
                          "un poids de police explicite écrase le gras du markup")
    }

    /// Le témoin. Les assertions ci-dessus ne valent que si une bulle NON
    /// branchée rendrait vraiment les mêmes pixels — sinon elles passeraient
    /// au vert quoi qu'il arrive.
    ///
    /// Voici exactement ce que la bulle dessinerait si `ClickableTextView`
    /// cessait d'appliquer les `spans`: le texte débarrassé de ses marqueurs et
    /// rien d'autre. Il doit être pixel pour pixel identique au même texte écrit
    /// sans marqueur — c'est-à-dire que les `XCTAssertNotEqual` du dessus
    /// VIRERAIENT AU ROUGE le jour où le parseur est débranché.
    @MainActor
    func testAnUnwiredBubbleWouldRenderIdenticalPixels() throws {
        let strippedOnly = InlineMarkup.parse("Salut *gras* ici").0
        let unwired = try raster(Text(AttributedString(strippedOnly)).foregroundColor(.black))
        let bare    = try raster(Text(AttributedString("Salut gras ici")).foregroundColor(.black))
        XCTAssertEqual(unwired, bare,
                       "le témoin lui-même est cassé: deux rendus du même texte non stylé diffèrent, donc les comparaisons ci-dessus ne prouvent rien")
    }
}

// MARK: - Les surfaces qui ne PEUVENT pas styler ne doivent pas montrer les marqueurs

//
//  La bulle style le texte. La boîte de citation, la bannière épinglée et la
//  dernière ligne d'une conversation ne le peuvent pas — une seule ligne de
//  `.caption`, sans lien ni gras, avec son propre geste. Elles doivent donc
//  EFFACER les marqueurs plutôt que les imprimer: citer `*gras*` en affichant
//  les astérisques, c'est la mise en forme qui échoue à l'endroit le plus
//  visible qui soit.
//

final class InlineMarkupStrippingTests: XCTestCase {

    func testStrippedRemovesTheMarkersItWouldOtherwisePrint() {
        XCTAssertEqual(InlineMarkup.stripped("Salut *gras* ici"), "Salut gras ici")
        XCTAssertEqual(InlineMarkup.stripped("_ital_ et ~barre~ et `code`"),
                       "ital et barre et code")
    }

    /// Et surtout: ne rien manger d'un message ordinaire. Même règle
    /// conservatrice que le rendu — sinon on supprimerait des caractères d'un
    /// message que personne n'a voulu mettre en forme.
    func testStrippedLeavesOrdinaryTextExactlyAsItIs() {
        for s in ["2 * 3 * 4 = 24", "snake_case_name", "rendez-vous a 8h30",
                  "un * seul", "chemin/vers/le_fichier_final.txt"] {
            XCTAssertEqual(InlineMarkup.stripped(s), s,
                           "stripped() a modifie un message ordinaire: \(s)")
        }
    }

    /// Le témoin: ce que ces surfaces affichaient avant le correctif, c'est la
    /// chaîne BRUTE — et elle diffère bien du résultat attendu, sans quoi
    /// l'assertion ci-dessus ne prouverait rien.
    func testTheRawStringIsWhatUsedToBeShown() {
        let raw = "Salut *gras* ici"
        XCTAssertNotEqual(raw, InlineMarkup.stripped(raw),
                          "brut et nettoye identiques: le test ne prouverait rien")
    }
}
