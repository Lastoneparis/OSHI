import XCTest

/// __FROSTED_COMPOSER_2026_11_07__
///
/// Anatole asked (N10) for the message bar to carry a blur, "sauf dans la case
/// de texte". On Android that already shipped as N12's frosted bars on 02/11.
/// Checking the platform he does NOT use found iOS had only half of it: the
/// top bar was frosted, the composer sat on an opaque `systemBackground`, and
/// the wallpaper stopped dead at the bottom of the thread.
///
/// The pair is what matters and why this is a test rather than a one-line
/// change left to stand on its own: the BAR must be translucent and the FIELD
/// inside it must not. Make the field translucent too and the caret sits on a
/// moving wallpaper, which is the thing he was careful to exclude.
final class FrostedComposerTests: XCTestCase {

    private func chatView() throws -> String {
        let here = URL(fileURLWithPath: #filePath)
        let root = here.deletingLastPathComponent().deletingLastPathComponent()
        return try String(contentsOf: root.appendingPathComponent("OSHI/ChatView.swift"), encoding: .utf8)
    }

    /// THE test: the bar is frosted, so the wallpaper runs under it.
    func testComposerBarIsTranslucent() throws {
        let src = try chatView()
        // __OLED_BLACK_2026_11_16__ `oledAwareBackground` IS the frosted
        // material, except under OLED black where the user asked for black.
        XCTAssertTrue(
            src.contains(".background(.ultraThinMaterial)\n        .animation(chromeAnimation, value: isEmpty)")
                || src.contains(".oledAwareBackground(.ultraThinMaterial)\n        .animation(chromeAnimation, value: isEmpty)"),
            "the composer bar is back on an opaque background — the wallpaper will stop at the bar again"
        )
        XCTAssertFalse(
            src.contains(".background(Color(.systemBackground))\n        .animation(chromeAnimation, value: isEmpty)"),
            "the opaque systemBackground returned on the composer bar"
        )
    }

    /// And the other half: the field keeps an OPAQUE ground of its own.
    /// __COMPOSER_PILL_2026_11_23__ that ground now belongs to the Google-style
    /// pill the field sits in (`ComposerPill`, SharedUIComponents.swift), and
    /// the field itself is bare inside it.
    func testTheTextFieldItselfStaysOpaque() throws {
        let src = try chatView()
        let here = URL(fileURLWithPath: #filePath)
        let root = here.deletingLastPathComponent().deletingLastPathComponent()
        let shared = try String(contentsOf: root.appendingPathComponent("OSHI/SharedUIComponents.swift"), encoding: .utf8)
        XCTAssertTrue(
            src.contains("ComposerPill(focused: isComposerFocused"),
            "the 1:1 field is no longer inside the composer pill; it may have lost its opaque ground"
        )
        XCTAssertTrue(
            shared.contains("colorScheme == .dark ? Color(.systemGray6) : Color(.systemGray5)"),
            "the pill's ground is no longer a solid colour — typing would happen over the wallpaper"
        )
        XCTAssertTrue(
            shared.contains(".background(ComposerPillFill().clipShape(shape))"),
            "the pill no longer paints its own ground"
        )
    }
}
