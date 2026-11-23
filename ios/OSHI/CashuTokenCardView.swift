//
//  CashuTokenCardView.swift
//  OSHI
//
//  🪙 The eCash card under a message that carries a Cashu token.
//
//  Swift half of `OSHI-Android/.../ui/components/CashuChip.kt`, and the same
//  scope on purpose: RECOGNISE, PREVIEW, HAND OFF. OSHI reads the token
//  locally and redeeming opens the wallet the user already trusts through the
//  `cashu:` URI. No balance is held here, nothing is signed, no mint is
//  contacted.
//
//  The bearer warning is not decoration. A Cashu token IS the money: whoever
//  reads the string can spend it, including anyone who later sees this
//  conversation. It is the one line of this card that must never be dropped to
//  save space.
//

import SwiftUI

struct CashuTokenCardsForText: View {
    let text: String

    private var tokens: [CashuTokens.Token] {
        // Cheap bail before any parsing, like TickerChipsForText.
        guard text.range(of: "cashu", options: .caseInsensitive) != nil else { return [] }
        var seen = Set<String>()
        // The same token quoted twice is one card.
        return CashuTokens.find(in: text).compactMap { match in
            seen.insert(match.token.raw).inserted ? match.token : nil
        }
    }

    var body: some View {
        let list = tokens
        if !list.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                ForEach(list, id: \.raw) { CashuTokenCard(token: $0) }
            }
        }
    }
}

private struct CashuTokenCard: View {
    let token: CashuTokens.Token
    /// __CASHU_2026_10_25__ Set when nothing on this device claims `cashu:`.
    /// Android said so with a toast and iOS said nothing at all: the button
    /// simply did not respond, which reads as a broken app rather than as a
    /// missing wallet.
    @State private var noWallet = false
    /// __CASHU_SEND_READY_2026_11_09__ Android confirmed a copy with a toast;
    /// iOS copied in silence, so a tap on "Copy token" looked like nothing.
    @State private var copied = false

    /// The mint is WHO owes the money. A token is only worth what its mint will
    /// honour, so it is shown, never hidden behind the word "eCash".
    private var mintLine: String {
        token.mints
            .map { $0.replacingOccurrences(of: "https://", with: "")
                     .replacingOccurrences(of: "http://", with: "") }
            .joined(separator: ", ")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Image(systemName: "bolt.fill")
                    .foregroundColor(.accentColor)
                    .font(.system(size: 14))
                Text(token.displayAmount)
                    .font(.system(size: 18, weight: .bold))
            }
            Text(mintLine)
                .font(.system(size: 12))
                .foregroundColor(.secondary)
            if let memo = token.memo, !memo.trimmingCharacters(in: .whitespaces).isEmpty {
                Text(memo).font(.system(size: 13)).padding(.top, 2)
            }
            Text(NSLocalizedString("cashu_bearer_note", comment: "Cashu tokens are bearer instruments"))
                .font(.system(size: 11))
                .foregroundColor(.secondary)
                .padding(.top, 6)
            // OSHI validates NOTHING — no DLEQ check, no call to the mint. The
            // figure above is the amount the token claims. A card that showed it
            // without saying so would present an already-spent token as a payment
            // received, which is the one thing a bearer instrument must never look
            // like before it is redeemed.
            Text(NSLocalizedString("cashu_not_verified", comment: "OSHI does not verify the token with the mint"))
                .font(.system(size: 11))
                .foregroundColor(.secondary)
                .padding(.top, 4)
            HStack(spacing: 14) {
                Button { redeemInWallet() } label: {
                    Text(NSLocalizedString("cashu_redeem", comment: "Open the token in an external wallet"))
                        .font(.system(size: 14, weight: .semibold))
                }
                Button { copyToken() } label: {
                    Text(NSLocalizedString("cashu_copy_token", comment: "Copy the token to the pasteboard"))
                        .font(.system(size: 14))
                }
                // Separate, and never automatic: putting a bearer token in a URL
                // on someone else's server is the user's decision to take.
                Button { redeemOnWeb() } label: {
                    Text(NSLocalizedString("cashu_redeem_web", comment: "Open the public web redemption page"))
                        .font(.system(size: 14))
                }
            }
            .padding(.top, 2)
            if copied {
                Text(NSLocalizedString("cashu_copied", comment: "The token was copied"))
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
                    .padding(.top, 2)
            }
            if noWallet {
                Text(NSLocalizedString("cashu_no_wallet_ios", comment: "No eCash wallet is installed on this device"))
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
                    .padding(.top, 2)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color.secondary.opacity(0.12)))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.secondary.opacity(0.25), lineWidth: 1))
    }

    /// A wallet installed on this device, and nothing else. No automatic web
    /// fallback: if nothing claims `cashu:`, the token stays where it is.
    private func redeemInWallet() {
        guard let uri = URL(string: token.redeemURI) else { return }
        #if canImport(UIKit)
        UIApplication.shared.open(uri, options: [:]) { opened in
            // Say so rather than doing nothing. Still NO automatic web hand-off:
            // the two explicit buttons beside this one are the way out.
            if !opened { noWallet = true }
        }
        #endif
    }

    /// The token on the pasteboard, so it can be pasted into any wallet by hand.
    private func copyToken() {
        #if canImport(UIKit)
        // __CLIPBOARD_HYGIENE_2026_10_01__ A Cashu token is a BEARER note: whoever
        // pastes it first owns the money. The plain pasteboard kept it forever and
        // synced it to every device on the Apple account (Universal Clipboard),
        // readable by any app. Same policy as the recovery key: this device only,
        // gone after 60 s — long enough to paste into a wallet.
        SecureClipboard.copy(token.raw)
        copied = true
        #endif
    }

    /// The public redemption page, opened only because the user asked for it.
    /// The token travels in the URL, so it reaches that site; OSHI itself still
    /// contacts no mint and no server.
    private func redeemOnWeb() {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
        guard let enc = token.raw.addingPercentEncoding(withAllowedCharacters: allowed),
              let url = URL(string: "https://redeem.cashu.me/?token=\(enc)") else { return }
        #if canImport(UIKit)
        UIApplication.shared.open(url)
        #endif
    }
}
