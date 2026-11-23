//
//  CashuSendSheet.swift
//  OSHI
//
//  🪙 "Send bitcoin" from the + menu: paste a Cashu token, see what it is,
//  then send it.
//
//  __CASHU_SEND_READY_2026_11_09__ Swift half of
//  `OSHI-Android/.../ui/components/CashuSendSheet.kt`. Two reasons it exists:
//
//  1. `/pay` was the only door, and a payment you have to be told how to type
//     is a payment almost nobody makes.
//  2. Nothing showed the amount BEFORE a token left the device, although the
//     in-app guide promised exactly that. A bearer token cannot be recalled,
//     so the amount and the mint are read back here first.
//
//  Same scope as the card: OSHI reads the token locally and hands it to the
//  conversation. No mint is contacted, nothing is held, nothing is signed, and
//  the parse is `/pay`'s own (`CashuPayCommand.parsePasted`), so this sheet can
//  never send a string the command would refuse.
//

import SwiftUI

/// What opens the sheet: empty from the + menu, prefilled from `/pay <token>`.
struct CashuSendDraft: Identifiable {
    let id = UUID()
    var text: String = ""
}

struct CashuSendSheet: View {
    /// A thread more than one other person can read: the sheet then says that
    /// everyone in it can redeem the token.
    let isGroup: Bool
    /// Sends the bare token through the conversation's normal send path.
    /// Returns the refusal line to show, or nil once it went out.
    let onSend: (String) -> String?

    @Environment(\.dismiss) private var dismiss
    @State private var text: String
    @State private var refusal: String?

    init(draft: CashuSendDraft, isGroup: Bool, onSend: @escaping (String) -> String?) {
        self.isGroup = isGroup
        self.onSend = onSend
        _text = State(initialValue: draft.text)
    }

    private var outcome: CashuPayCommand.Outcome { CashuPayCommand.parsePasted(text) }

    private var token: CashuTokens.Token? {
        if case let .send(token, _) = outcome { return token }
        return nil
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("guide.cashu.sub".localized)
                        .font(.footnote)
                        .foregroundColor(.secondary)

                    // The system paste button: reads the clipboard on a tap
                    // without the "Allow Paste" prompt a custom button triggers.
                    PasteButton(payloadType: String.self) { strings in
                        guard let pasted = strings.first else { return }
                        Task { @MainActor in
                            text = CashuPayCommand.firstToken(in: pasted) ?? pasted
                            refusal = nil
                        }
                    }

                    TextField(NSLocalizedString("cashu_send_field_hint", comment: "Paste a Cashu token"),
                              text: $text, axis: .vertical)
                        .lineLimit(3...6)
                        .font(.system(.footnote, design: .monospaced))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .padding(10)
                        .background(RoundedRectangle(cornerRadius: 10).fill(Color.secondary.opacity(0.1)))
                        .onChange(of: text) { _, _ in refusal = nil }

                    switch outcome {
                    case .send(let token, let summary):
                        preview(token, summary: summary)
                    case .notAToken:
                        errorLine(NSLocalizedString("cashu_pay_not_token", comment: ""))
                    case .invalid:
                        errorLine(NSLocalizedString("cashu_pay_invalid", comment: ""))
                    case .usage, .needsPublicConfirm:
                        EmptyView()
                    }

                    if let refusal { errorLine(refusal) }
                }
                .padding()
            }
            .navigationTitle(NSLocalizedString("cashu_send_title", comment: "Send bitcoin (Cashu)"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel".localized) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(NSLocalizedString("cashu_group_confirm_send", comment: "Send the token")) { send() }
                        .disabled(token == nil)
                }
            }
        }
    }

    /// Everything the receiver's card will say, shown to the sender first —
    /// including that OSHI has not checked the token with its mint.
    private func preview(_ token: CashuTokens.Token, summary: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Image(systemName: "bolt.fill").foregroundColor(.accentColor)
                Text(summary).font(.system(size: 22, weight: .bold))
            }
            Text(token.mints.map { $0.replacingOccurrences(of: "https://", with: "")
                                     .replacingOccurrences(of: "http://", with: "") }
                    .joined(separator: ", "))
                .font(.footnote)
                .foregroundColor(.secondary)
            if let memo = token.memo, !memo.trimmingCharacters(in: .whitespaces).isEmpty {
                Text(memo).font(.subheadline).padding(.top, 2)
            }
            if isGroup {
                Text(String(format: NSLocalizedString("cashu_group_confirm_body", comment: ""), summary))
                    .font(.footnote.weight(.semibold))
                    .foregroundColor(.orange)
                    .padding(.top, 6)
            }
            Text(NSLocalizedString("cashu_bearer_note", comment: ""))
                .font(.caption)
                .foregroundColor(.secondary)
                .padding(.top, 6)
            Text(NSLocalizedString("cashu_not_verified", comment: ""))
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color.secondary.opacity(0.12)))
    }

    private func errorLine(_ line: String) -> some View {
        Text(line).font(.footnote).foregroundColor(.red)
    }

    private func send() {
        guard let token else { return }
        if let line = onSend(token.raw) {
            refusal = line
        } else {
            dismiss()
        }
    }
}

/// Presents `CashuSendSheet` for a non-nil draft. A separate modifier so the
/// chat screens' long `.sheet` chains stay one opaque type each — inlined, the
/// 1:1 chat's chain exceeded the Swift type-checker's time limit.
struct CashuSendSheetModifier: ViewModifier {
    @Binding var draft: CashuSendDraft?
    let isGroup: Bool
    let onSend: (String) -> String?

    func body(content: Content) -> some View {
        content.sheet(item: $draft) { d in
            CashuSendSheet(draft: d, isGroup: isGroup, onSend: onSend)
        }
    }
}
