//
//  ClickableTextView.swift
//  OSHI - Clickable URLs in Messages
//
//  Automatically detects URLs and makes them clickable with Safari redirect
//

import SwiftUI

// MARK: - Clickable Text View

/// __LONG_MESSAGE_RERENDER_2026_11_09__ `Equatable`, and every call site wraps
/// the instance in `.equatable()`.
///
/// REPORTED: a chat with one very long text message reopens scrolled onto
/// THAT message, even though several short messages were sent after it — the
/// view never reaches the true bottom. The open-settle in `SharedUIComponents`
/// (`settleScrollToBottomOnOpen` / `landAtBottom`) already re-issues `scrollTo`
/// for up to 2.5 s specifically to outlast slow-to-settle rows (it was built
/// for an async-decoding image or map). That budget is plenty for a bubble
/// whose body is cheap to rebuild — which every bubble was assumed to be.
///
/// This one is not. `attributedText` below is a plain computed property: it
/// re-parses the WHOLE message on every call — markdown markers, `@TICKER`
/// spans, two `NSDataDetector` passes for links — and it reran on every
/// single body evaluation of this view, not once per message. During open,
/// this view's ancestor (`ChatView`) re-evaluates its body repeatedly in the
/// first couple of seconds for reasons that have nothing to do with THIS row:
/// `bottomAnchorSighted` flips as the list's tail anchor appears/disappears
/// while rows materialize, and every corrective `scrollTo` the settle loop
/// fires forces another layout pass. None of that changes `text`,
/// `textColor`, `linkColor` or `weight` — but without `Equatable`, SwiftUI has
/// no way to know that, so it rebuilt `attributedText` from scratch on every
/// one of those passes. For an ordinary message that cost is invisible; for a
/// message thousands of characters long, repeated ~30 times in under 2.5 s, it
/// is enough CPU to make the real content height arrive AFTER the settle loop
/// has already given up — so the scroll parks wherever layout happened to be
/// when the clock ran out, which is on or near the long bubble, not past it.
///
/// `.equatable()` lets SwiftUI skip calling `body` (and therefore
/// `attributedText`) again when none of the four stored properties changed —
/// which is every one of those unrelated re-renders. It does NOT touch the
/// settle timing or the scroll logic itself: this was never a "retry harder"
/// problem, it was a "stop paying for work nobody asked for twice" problem.
struct ClickableTextView: View, Equatable {
    let text: String
    let textColor: Color
    let linkColor: Color
    /// Message text weight. Defaults to `.regular` so every existing call site
    /// is unchanged; the chat passes the user's choice from BubbleStyleManager.
    let weight: Font.Weight

    init(text: String, textColor: Color = .primary, linkColor: Color = .blue,
         weight: Font.Weight = .regular) {
        self.text = text
        self.textColor = textColor
        self.linkColor = linkColor
        self.weight = weight
    }
    
    var body: some View {
        // __SELECTION_ATE_THE_LONG_PRESS_2026_10_21__ La sélection de texte ne
        // vit plus que sur le Mac, et c'est tout le sujet.
        //
        // `.textSelection(.enabled)` installe sur iOS une interaction de
        // sélection UIKit, et celle-ci GAGNE l'arène des gestes contre le
        // `.onLongPressGesture` de la bulle. Conséquence rapportée sur iPhone:
        // l'appui long sur un message n'ouvrait plus NOTRE feuille (Répondre,
        // Transférer, Copier, Traduire, Supprimer) mais la barre du système,
        // qui ne propose que Copier et Partager. Et ce « Partager » est la
        // feuille du système: elle fait SORTIR le message de l'app vers
        // n'importe quelle destination, alors que « Transférer » chez nous
        // (`ForwardPicker`) est une liste de contacts OSHI et rien d'autre.
        //
        // C'est le même piège que le `.contextMenu` du 19/10, qui avait fait
        // disparaître Traduire de l'iPhone: toute interaction UIKit posée sur
        // la bulle prend l'appui long. Et le besoin d'origine ne concernait que
        // le Mac — à la souris il n'y a pas d'appui long, donc sélectionner EST
        // la façon de copier. L'iPhone a déjà sa feuille.
        //
        // Deux bulles sont concernées par une seule ligne: le 1:1 de ChatView
        // et le message de groupe de GroupViews, qui passent tous les deux par
        // cette vue.
        #if targetEnvironment(macCatalyst)
        // `.enabled` and `.disabled` are DIFFERENT types, so the modifier
        // cannot be chosen with a ternary — the branch has to be on the view.
        if MessagePrivacy.blockForwardAndCopy {
            core
        } else {
            core.textSelection(.enabled)
        }
        #else
        // iPhone et iPad: aucune sélection, donc l'appui long arrive jusqu'à
        // nous. Copier reste accessible — par notre propre entrée, qui passe
        // par `SecureClipboard` (expiration 60 s) au lieu du presse-papiers nu.
        core
        #endif
    }

    @ViewBuilder
    private var core: some View {
        Text(attributedText)
            .fontWeight(weight)
            // __CANNOT_COPY_ON_THE_MAC_2026_10_19__ Message text was a plain
            // `Text` with selection never enabled, so on the Mac you could not
            // select a word of it — drag did nothing and there is no long-press
            // to fall back on. On iOS the long-press menu hides the gap; on a
            // desktop, selecting text with the mouse IS the way to copy, and
            // the app simply had no answer. `.textSelection(.enabled)` is
            // already used in the profile, mail and AI views — the chat bubble,
            // the one place people most want to copy from, was the omission.
            //
            // Gated on the same privacy switch as every other copy route
            // (`MessagePrivacy.blockForwardAndCopy`), so turning "block forward
            // and copy" ON still prevents selection rather than leaving a
            // loophole the other guards close.
            .tint(linkColor) // Ensure links use the link color
            .environment(\.openURL, OpenURLAction { url in
                // Places (street addresses, Apple/Google Maps links, geo:)
                // follow Settings › Maps — see MapOpener. Everything else
                // opens in Safari as before.
                if MapOpener.handleLink(url) { return .handled }
                OshiLog.messaging.info("🔗 Opening URL: \(url.absoluteString)")
                // Force open in Safari
                UIApplication.shared.open(url)
                return .handled
            })
            // 🔧 FIX (2026-08-06, "two link menus instead of the message
            // menu"): a `.contextMenu` here covered the ENTIRE Text frame, not
            // the link ranges. Long-pressing anywhere on a message — including
            // the plain words — opened Open/Copy/Share, and with two links it
            // opened two visually identical sections with no way to tell which
            // URL each acted on. Worse, a UIContextMenuInteraction wins the
            // gesture arena against the bubble's own `.onLongPressGesture`, so
            // the MESSAGE menu (copy, reply, forward, react) could never appear
            // on any message containing a link.
            //
            // Link actions belong in the message menu, next to the other things
            // you can do with that message. Tapping a link is unaffected: the
            // `.link` attribute plus the `openURL` action above still handle it.
    }
    
    private var detectedURLs: [String] {
        var urls: [String] = []
        // Same rule as `attributedText`: detect against the text WITHOUT the
        // formatting markers, so the context menu offers the same links the
        // bubble shows.
        let plain = InlineMarkup.parse(text).0
        let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
        let nsRange = NSRange(plain.startIndex..., in: plain)
        let matches = detector?.matches(in: plain, options: [], range: nsRange)

        matches?.forEach { match in
            guard let range = Range(match.range, in: plain) else { return }
            let urlString = String(plain[range])
            
            // Create proper URL string
            var finalURLString = urlString
            if let directURL = URL(string: urlString), directURL.scheme != nil {
                finalURLString = urlString
            } else if urlString.hasPrefix("www.") {
                finalURLString = "https://\(urlString)"
            } else if !urlString.contains("://") {
                finalURLString = "https://\(urlString)"
            }
            urls.append(finalURLString)
        }
        
        return urls
    }
    
    private func shareURL(_ urlString: String) {
        guard let url = URL(string: urlString) else { return }
        
        let activityVC = UIActivityViewController(
            activityItems: [url],
            applicationActivities: nil
        )
        
        if let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
           let window = windowScene.windows.first,
           let rootVC = window.rootViewController {
            
            // For iPad: configure popover
            if let popover = activityVC.popoverPresentationController {
                popover.sourceView = window
                popover.sourceRect = CGRect(x: window.bounds.midX, y: window.bounds.midY, width: 0, height: 0)
                popover.permittedArrowDirections = []
            }
            
            rootVC.present(activityVC, animated: true)
        }
    }
    
    private var attributedText: AttributedString {
        // Inline formatting is stripped from the string BEFORE URL detection, so
        // ranges are computed against the text the user actually sees. Doing it
        // the other way round shifts every link by the number of markers removed.
        let (plain, spans) = InlineMarkup.parse(text)

        var attributedString = AttributedString(plain)

        // Set base text color
        attributedString.foregroundColor = textColor

        // Apply *bold*, _italic_, ~strike~ and `code`.
        for span in spans {
            guard let r = Range(NSRange(location: span.location, length: span.length),
                                in: attributedString) else { continue }
            switch span.kind {
            case .bold:
                attributedString[r].inlinePresentationIntent = .stronglyEmphasized
            case .italic:
                attributedString[r].inlinePresentationIntent = .emphasized
            case .strikethrough:
                attributedString[r].strikethroughStyle = .single
            case .code:
                attributedString[r].inlinePresentationIntent = .code
            }
        }

        // Stock tickers: `@AAPL` → a tappable `$AAPL` pointing at TradingView.
        //
        // Applied LAST-BUT-ONE, and walked in REVERSE, because replacing the
        // token shortens the string by one character (the sigil goes, the `$`
        // arrives): editing back-to-front keeps every earlier range valid. The
        // formatting spans above were already applied, so they move with the
        // text rather than being computed against a string that no longer
        // matches.
        //
        // The sigil is required — see TickerLinks. Detecting bare uppercase
        // words would turn "OK", "USA" and "OSHI" into stock links.
        for ticker in TickerLinks.matches(in: plain).reversed() {
            guard let r = Range(NSRange(ticker.range, in: plain), in: attributedString),
                  let url = ticker.url else { continue }
            var chip = AttributedString(ticker.display)
            chip.link = url
            chip.foregroundColor = linkColor
            chip.inlinePresentationIntent = .stronglyEmphasized
            attributedString.replaceSubrange(r, with: chip)
        }

        // Detect URLs — against `plain`, not the original.
        let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
        let nsRange = NSRange(plain.startIndex..., in: plain)
        let matches = detector?.matches(in: plain, options: [], range: nsRange)

        matches?.forEach { match in
            guard let range = Range(match.range, in: plain) else { return }
            let urlString = String(plain[range])
            
            // Create proper URL
            var url: URL?
            if let directURL = URL(string: urlString), directURL.scheme != nil {
                url = directURL
            } else if urlString.hasPrefix("www.") {
                url = URL(string: "https://\(urlString)")
            } else if !urlString.contains("://") {
                url = URL(string: "https://\(urlString)")
            }
            
            if let finalURL = url, let attrRange = Range(match.range, in: attributedString) {
                // Style the link
                attributedString[attrRange].foregroundColor = linkColor
                attributedString[attrRange].underlineStyle = .single
                attributedString[attrRange].link = finalURL
                
                OshiLog.messaging.info("🔗 Detected URL: \(urlString) → \(finalURL.absoluteString)")
            }
        }
        
        // Street addresses → a tappable link that MapOpener opens with the
        // app chosen in Settings › Maps. Skipped where a URL already sits.
        if let addressDetector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.address.rawValue) {
            for match in addressDetector.matches(in: plain, options: [], range: nsRange) {
                guard let range = Range(match.range, in: plain),
                      let attrRange = Range(match.range, in: attributedString),
                      !attributedString[attrRange].runs.contains(where: { $0.link != nil }) else { continue }
                var comps = URLComponents()
                comps.scheme = MapOpener.addressScheme
                comps.host = "open"
                comps.queryItems = [URLQueryItem(name: "q", value: String(plain[range]))]
                guard let url = comps.url else { continue }
                attributedString[attrRange].foregroundColor = linkColor
                attributedString[attrRange].underlineStyle = .single
                attributedString[attrRange].link = url
            }
        }

        return attributedString
    }
}

// MARK: - Extension for easy use

extension Text {
    /// Creates a Text view with clickable URLs
    static func withClickableLinks(_ text: String, textColor: Color = .primary, linkColor: Color = .blue) -> some View {
        ClickableTextView(text: text, textColor: textColor, linkColor: linkColor)
    }
}

// MARK: - Preview

struct ClickableTextView_Previews: PreviewProvider {
    static var previews: some View {
        VStack(spacing: 20) {
            ClickableTextView(text: "Check out www.styia.com")
            
            ClickableTextView(text: "Visit https://apple.com for more info")
            
            ClickableTextView(text: "Multiple links: www.google.com and https://github.com")
            
            // Dark bubble style
            ClickableTextView(text: "www.example.com", textColor: .white, linkColor: .cyan)
                .padding()
                .background(Color.blue)
                .cornerRadius(16)
        }
        .padding()
    }
}
