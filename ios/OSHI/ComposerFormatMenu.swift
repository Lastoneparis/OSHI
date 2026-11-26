//
//  ComposerFormatMenu.swift
//  OSHI — the composer's Bold / Underline actions.
//
//  __TEXT_FORMATTING_2026_11_24__ Users must be able to format without knowing
//  the markers. The composer is a SwiftUI `TextField(axis: .vertical)` inside
//  `ComposerPill`, which exposes no selection on iOS 17, so the actions read
//  and restore the selection through the field's UIKit backing (the first
//  responder) and change the text through the SwiftUI binding — the binding
//  stays the single source of truth for the draft.
//
//  With a selection: the selection is wrapped (`**sel**`) and stays selected.
//  Without one: the empty pair is inserted and the caret lands between.
//  Typing the markers by hand works too — it is the same parser.
//

import SwiftUI
import UIKit

// MARK: - First responder lookup

private final class FirstResponderBox {
    weak var responder: UIResponder?
    static let shared = FirstResponderBox()
}

extension UIResponder {
    @objc fileprivate func oshi_captureFirstResponder(_ sender: Any?) {
        FirstResponderBox.shared.responder = self
    }
}

@MainActor
enum ComposerFormatBridge {

    /// The focused text input, if any.
    static func focusedTextInput() -> (UIResponder & UITextInput)? {
        FirstResponderBox.shared.responder = nil
        UIApplication.shared.sendAction(#selector(UIResponder.oshi_captureFirstResponder(_:)),
                                        to: nil, from: nil, for: nil)
        return FirstResponderBox.shared.responder as? (UIResponder & UITextInput)
    }

    private static func currentText(_ input: UITextInput) -> String? {
        if let tv = input as? UITextView { return tv.text }
        if let tf = input as? UITextField { return tf.text }
        return nil
    }

    /// UTF-16 selection of `input`.
    private static func selection(of input: UITextInput) -> NSRange? {
        guard let r = input.selectedTextRange else { return nil }
        let loc = input.offset(from: input.beginningOfDocument, to: r.start)
        let len = input.offset(from: r.start, to: r.end)
        return NSRange(location: loc, length: len)
    }

    private static func select(_ range: NSRange, in input: UITextInput) {
        guard let start = input.position(from: input.beginningOfDocument, offset: range.location),
              let end = input.position(from: start, offset: range.length) else { return }
        input.selectedTextRange = input.textRange(from: start, to: end)
    }

    /// Applies `style` to the draft in `text`. Returns true when the field is
    /// not focused (the caller should focus it); the caret is then placed once
    /// the field is first responder again.
    @discardableResult
    static func apply(_ style: ComposerFormatting.Style, text: Binding<String>) -> Bool {
        let draft = text.wrappedValue
        let input = focusedTextInput()
        // Only trust the responder when it IS our composer (same text).
        let live = input.flatMap { i in currentText(i) == draft ? i : nil }
        let sel = live.flatMap { selection(of: $0) }
            ?? NSRange(location: (draft as NSString).length, length: 0)
        let edit = ComposerFormatting.apply(style, to: draft, selection: sel)
        text.wrappedValue = edit.text
        placeSelection(edit.selection, expecting: edit.text, attempts: 4)
        return live == nil
    }

    /// Restores the selection after SwiftUI has pushed the new text into the
    /// field (which moves the caret to the end). Retries briefly while focus
    /// and the text update land.
    private static func placeSelection(_ range: NSRange, expecting text: String, attempts: Int) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
            if let input = focusedTextInput(), currentText(input) == text {
                select(range, in: input)
            } else if attempts > 1 {
                placeSelection(range, expecting: text, attempts: attempts - 1)
            }
        }
    }
}

// MARK: - Composer button

/// The compact "Aa" menu in the composer pill: Bold, Underline.
struct ComposerFormatMenu: View {
    @Binding var text: String
    let tint: Color
    /// Called when the field was not focused, so the keyboard comes back.
    let requestFocus: () -> Void

    var body: some View {
        Menu {
            Button {
                if ComposerFormatBridge.apply(.bold, text: $text) { requestFocus() }
            } label: {
                Label("composer.format.bold".localized, systemImage: "bold")
            }
            Button {
                if ComposerFormatBridge.apply(.underline, text: $text) { requestFocus() }
            } label: {
                Label("composer.format.underline".localized, systemImage: "underline")
            }
        } label: {
            Image(systemName: "textformat")
                .font(.scaled(18, weight: .medium))
                .foregroundColor(tint)
                .frame(width: 36, height: 44)
                .contentShape(Rectangle())
        }
        .accessibilityLabel(Text("composer.format.menu".localized))
        .accessibilityIdentifier("composer.format")
    }
}
