//
//  InviteLink.swift
//  OSHI
//
//  __FIRST_CONTACT_REMOTE_2026_10_10__ Adding your first contact when the other
//  person is NOT in the room with you.
//
//  Reported: older users get lost adding their first contact when they are not
//  together. Looking at what the app actually offered, that is not a wording
//  problem — the path did not exist.
//
//  The empty state offered exactly two actions, "Show my code" and "Scan a
//  friend's code", and BOTH require the other phone to be physically present.
//  "Show my code" opens a QR, your truncated address, and a "Copy address"
//  button that puts a ~44-character base64 public key on the clipboard. So for
//  two people in different places, the only available route was: copy a base64
//  blob, paste it into some other app, and hope the other person pastes it into
//  the right field. That is the whole reported failure.
//
//  The mechanism to do this properly was already in the app and simply never
//  pointed at your own identity: `MessagesListView.shareContact()` builds
//  `https://oshi-messenger.org/add?key=…` to pass SOMEBODY ELSE'S contact on,
//  and both `oshi-messenger.com` and `oshi-messenger.org` are in the
//  `applinks:` entitlement, so such a link opens the app. This type is that
//  same link, for you, plus the sentence that has to travel with it.
//
//  Pure and string-only so the link format is testable without a share sheet,
//  and so iOS and Android can be checked against the same shape.
//

import Foundation

enum InviteLink {

    /// __THE_INVITE_LINK_POINTED_AT_THE_UNVERIFIABLE_HOST_2026_11_07__
    /// (Anatole N5: "the invite link does not work when it is sent in a
    /// conversation".)
    ///
    /// This was `oshi-messenger.org`, and that host CANNOT be verified by
    /// either platform. Measured on 2026-11-07:
    ///
    ///     https://oshi-messenger.org/.well-known/assetlinks.json
    ///         -> HTTP 301 -> https://oshi-messenger.com/.well-known/...
    ///     https://oshi-messenger.org/.well-known/apple-app-site-association
    ///         -> HTTP 301
    ///
    /// Android App Links verification does NOT follow redirects, and neither
    /// does Apple's CDN when it fetches the association file. A 301 is a
    /// verification failure, so the OS never binds `.org` to the app and the
    /// link opens a browser instead — which is exactly the report. The `.com`
    /// host serves both files directly with HTTP 200 and verifies fine, and it
    /// is the host the GROUP invite already uses (`GroupMessaging`), which is
    /// why group links worked while contact links did not.
    ///
    /// Only what we EMIT changes. Both hosts stay in the entitlement and in
    /// the Android manifest, and every receiver still ACCEPTS `.org`, because
    /// links already sent are in people's chat histories.
    ///
    /// The complementary fix is server-side: serve those two files on `.org`
    /// directly instead of redirecting. Until that happens, old `.org` links
    /// keep opening a browser.
    static let host = "oshi-messenger.com"

    /// The link that adds *me*.
    ///
    /// A universal link, deliberately, NOT the `oshi://` scheme: a custom
    /// scheme does nothing at all on a phone that does not have OSHI yet, and
    /// for a FIRST contact that is the likely case. An https link at least
    /// lands somewhere that can explain what to install.
    /// `myName`, when non-blank, is carried as `&name=` — the same shape a
    /// group invite already uses — so the receiver's add-contact prompt (N1)
    /// can default to it instead of the raw key. Optional and additive: a
    /// link without it (older app, or no name set) still parses exactly as
    /// before on both platforms.
    static func url(forMyPublicKey key: String, myName: String? = nil) -> URL? {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        // `urlQueryAllowed` leaves `+`, `/` and `=` alone, and base64 keys
        // contain all three. `+` in a query is read as a SPACE by most
        // receivers, which would corrupt the key silently — so the base64
        // characters are escaped explicitly.
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "+/=&?#")
        guard let encoded = trimmed.addingPercentEncoding(withAllowedCharacters: allowed) else {
            return nil
        }
        var urlString = "https://\(host)/add?key=\(encoded)"
        let name = myName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !name.isEmpty, let encodedName = name.addingPercentEncoding(withAllowedCharacters: allowed) {
            urlString += "&name=\(encodedName)"
        }
        return URL(string: urlString)
    }

    /// What gets sent, as one block of text.
    ///
    /// Written to be read by someone who has never heard of OSHI, because that
    /// is who receives a first invite. It says who it is from, what to do, and
    /// what happens if they do not have the app — in that order, because that
    /// is the order the questions arrive in.
    ///
    /// `senderName` is optional: a brand-new user may not have set a nickname
    /// yet, and the invite must not read "– OSHI" with a blank in front of it.
    static func message(forMyPublicKey key: String, senderName: String?) -> String? {
        guard let url = url(forMyPublicKey: key, myName: senderName) else { return nil }
        let name = senderName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let opener = name.isEmpty
            ? "invite.body.opener.anonymous".localized
            : String(format: "invite.body.opener".localized, name)
        return """
        \(opener)

        \(url.absoluteString)

        \("invite.body.hint".localized)
        """
    }

    /// Everything the share sheet should carry. The text already contains the
    /// link; handing the sheet a separate `URL` item as well makes some targets
    /// (Mail, Notes) attach a link preview and drop the sentence, which is the
    /// part that does the explaining.
    static func shareItems(forMyPublicKey key: String, senderName: String?) -> [Any] {
        guard let text = message(forMyPublicKey: key, senderName: senderName) else { return [] }
        return [text]
    }
}
