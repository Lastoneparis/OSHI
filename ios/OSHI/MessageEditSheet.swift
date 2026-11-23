//
//  MessageEditSheet.swift
//  Editing a message you already sent — extracted out of ChatView.swift, which
//  is ~6k lines and already slow to type-check.
//  ✅ FULLY LOCALIZED: reuses keys that already exist in all 34 .lproj files.
//

import SwiftUI

// MARK: - Brand Color
private let appBrandColor = Theme.brand

/// 🔧 FIX (2026-09-12, user report: "when I modify a text I send, the box to
/// modify my text is not user friendly and complicated to change well when the
/// text is a bit long").
///
/// Editing used to live in a `.alert` carrying a bare `TextField` — see the
/// block in ChatView.swift that now presents this view. That container is the
/// whole bug, and it could not be fixed in place:
///
///   • an alert's text field is a UIAlertController text field. It is ONE line,
///     roughly 200pt wide, and it does not wrap. A message of any real length
///     scrolled sideways through a slit, so you could neither read what you
///     were correcting nor see where the caret was.
///   • `axis: .vertical` and `.lineLimit(_:)` are silently ignored inside an
///     alert, so "just make it multi-line" was not available.
///   • the alert dismissed on Save whatever happened. The old
///     `guard !editingText.isEmpty else { return }` therefore swallowed an
///     empty edit in silence: the sheet closed, nothing was sent, and the user
///     was told nothing.
///
/// So the container had to go. This is the same edit on a full-height sheet:
/// the field is multi-line, it grows with the text, the whole thing scrolls,
/// and Cancel/Save sit in the navigation bar where the keyboard can never
/// cover them.
///
/// This view is UI only. It hands the finished string back through `onSave`
/// and knows nothing about how the edit reaches the peer.
struct MessageEditSheet: View {

    /// The message as it currently reads. Kept so Save can stay disabled until
    /// something has actually changed — re-sending an identical body would burn
    /// a mesh round-trip and stamp a misleading "edited" marker on the bubble.
    let originalText: String

    /// Receives the trimmed, non-empty replacement body. Called at most once.
    let onSave: (String) -> Void

    @State private var draft: String
    @FocusState private var isEditorFocused: Bool
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme

    init(originalText: String, onSave: @escaping (String) -> Void) {
        self.originalText = originalText
        self.onSave = onSave
        _draft = State(initialValue: originalText)
    }

    /// Same pair the composer uses (ChatView's `inputBackground`), so an edit
    /// box looks like the box the message was typed in.
    private var editorBackground: Color {
        colorScheme == .dark ? Color(.systemGray6) : Color(.systemGray5)
    }

    /// The multi-line field can now produce trailing newlines, which the old
    /// single-line alert never could. Trim once, here, so the comparison below
    /// and the value handed to `onSave` agree.
    private var trimmedDraft: String {
        draft.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var canSave: Bool {
        !trimmedDraft.isEmpty
            && trimmedDraft != originalText.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                // __SHOW_WHAT_IS_BEING_EDITED_2026_11_05__ The message as it
                // stands, above the field.
                //
                // Asked for directly: "review the UX to modify a message to see
                // the previous message and what to modify". The sheet already
                // HELD `originalText` — it was used only to decide whether Save
                // should be enabled, and never shown. So the box opened
                // pre-filled with no statement of which message it belonged to,
                // and once the keyboard covered the thread there was nothing on
                // screen to compare against. On a long message that is the whole
                // difficulty: you are editing from memory.
                //
                // Shown as a quote rather than a diff. A character diff of prose
                // the user is still typing flickers on every keystroke and is
                // unreadable; the stable "before" next to the live "after" is
                // what actually lets someone check their change.
                //
                // It collapses once the draft is identical again, so an untouched
                // sheet is not two copies of the same sentence.
                if trimmedDraft != originalText.trimmingCharacters(in: .whitespacesAndNewlines) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("edit.original_label".localized)
                            .font(.caption.weight(.semibold))
                            .foregroundColor(.secondary)
                        Text(originalText)
                            .font(.scaled(15))
                            .foregroundColor(.secondary)
                            // No line cap: the point is to re-read a LONG
                            // message, which is the case this exists for.
                            .fixedSize(horizontal: false, vertical: true)
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(10)
                            .background(
                                RoundedRectangle(cornerRadius: 12)
                                    .fill(editorBackground.opacity(0.6))
                            )
                            .overlay(alignment: .leading) {
                                // A quote bar, so it reads as "the previous
                                // version" and not as a second editable field.
                                RoundedRectangle(cornerRadius: 2)
                                    .fill(Color.secondary.opacity(0.5))
                                    .frame(width: 3)
                                    .padding(.vertical, 6)
                            }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 16)
                    .transition(.opacity.combined(with: .move(edge: .top)))
                    .accessibilityElement(children: .combine)
                }

                // `.lineLimit(6...)` = at least six lines tall even when the
                // message is short (a generous tap target, and room to work),
                // and NO upper bound: the field grows to fit everything and the
                // ScrollView around it does the scrolling. Capping it instead
                // (e.g. `6...8`) makes the field scroll INSIDE a scroll view,
                // and the two fight over the drag — the caret then jumps while
                // you try to reach a word, which is exactly the complaint.
                TextField("message.edit_placeholder".localized, text: $draft, axis: .vertical)
                    .privateTypingComposer()  // __KEYBOARD_PRIVACY_2026_10_01__ no autocorrect learning when Private typing is on
                    .textFieldStyle(.plain)
                    .font(.scaled(17))
                    .lineLimit(6...)
                    .padding(12)
                    .background(editorBackground)
                    .cornerRadius(16)
                    // Focusing programmatically (below) drops the insertion
                    // point at the END of the existing text, which is where you
                    // want it when you are fixing a typo you just spotted.
                    .focused($isEditorFocused)
                    .padding(.horizontal, 16)
                    .padding(.top, 16)
                    .padding(.bottom, 24)
            }
            // Long message + keyboard up leaves very little window. Let the
            // user flick the keyboard away to re-read, without losing focus or
            // the draft.
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle("edit.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // Navigation-bar placement is deliberate: bar buttons are
                // pinned above the keyboard, so Save is reachable at any text
                // length. Buttons laid out under the field would sit behind the
                // keyboard for exactly the long messages this fix is about.
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel".localized) {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.save".localized) {
                        onSave(trimmedDraft)
                        dismiss()
                    }
                    .fontWeight(.semibold)
                    // Replaces the old silent `guard`: an empty or unchanged
                    // edit is now visibly refused instead of quietly dropped.
                    .disabled(!canSave)
                }
            }
            .task {
                // A `@FocusState` flipped in the same runloop turn as the
                // sheet's first layout is discarded — the field appears without
                // a keyboard and the user has to tap it. Waiting out the
                // presentation animation makes it stick.
                try? await Task.sleep(for: .milliseconds(350))
                isEditorFocused = true
            }
        }
        // `.large` only, and on purpose. A `.medium` detent with the keyboard
        // up re-creates the cramped box this replaces.
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }
}
