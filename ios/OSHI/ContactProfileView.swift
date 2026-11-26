//
//  ContactProfileView.swift
//  OSHI — the profile card for the person you are talking to.
//
//  Reached from a 1:1 chat's ⋯ menu. Before this existed, the three things a
//  user needs about a contact were scattered: their picture only ever appeared
//  as a 32pt circle in the nav bar with no way to see it larger, their public
//  key was a "Copy public key" menu item that showed nothing (you had to paste
//  it somewhere to find out what you copied), and their profile links lived in
//  a pinned bar that can be dismissed and then only reappears via a different
//  menu item.
//
//  DELIBERATELY reuses what already exists rather than restating it:
//    • `ContactPresenceManager.getProfileImage` — the same synced picture the
//      group member list draws (GroupViews.swift:7078).
//    • `MessageManager.resolveContactAlias` — THE app-wide naming rule.
//    • `ProfileLinksManager.getVisibleLinks` + `ProfileLinks.open` + the shared
//      `FaviconLoader` — the same link model and the same tap behaviour as the
//      pinned bar, so a `waypath://` handle still opens the app and still falls
//      back to the App Store.
//    • `FullScreenImageViewer` (MessagesListView.swift:1806) — pinch, pan,
//      double-tap, swipe-to-dismiss, already written.
//
//  Every string here already ships in all 34 `.lproj` files; one key was added
//  on 2026-09-22 (`contact.shared_nickname`, also in all 34).
//

import SwiftUI
import CoreImage.CIFilterBuiltins

struct ContactProfileView: View {
    let publicKey: String
    /// The alias the user set for this chat, when there is one. `nil` falls back
    /// to the app-wide rule so this view can never disagree with the rest of
    /// the app about what someone is called.
    var alias: String? = nil

    /// (N16) "Avoir le bouton message, appel ou visio sur la fiche de contact."
    /// Voice/video straight from the card; `nil` hides the row, so a caller
    /// with no call path shows no affordance for one. No "message" button:
    /// this card is only ever reached FROM the conversation with this person
    /// (ChatView.swift:996), so it would be a button back to the screen
    /// underneath it.
    var onCall: ((_ video: Bool) -> Void)? = nil
    /// __ANATOLE_1311_IOS__ (N9) Rename, offered with the call buttons ABOVE
    /// the key and QR code. nil hides it (no alias editor behind this card).
    var onRename: (() -> Void)? = nil

    @Environment(\.dismiss) private var dismiss
    /// __CONTACT_CARD_CARRIES_THE_CODE_2026_10_27__ nil until the code is drawn.
    @State private var qrImage: UIImage?
    @ObservedObject private var presenceManager = ContactPresenceManager.shared
    @ObservedObject private var linksManager = ProfileLinksManager.shared

    @State private var showFullScreenPhoto = false
    @State private var didCopyKey = false
    /// Non-nil while the shared-media gallery is open, and carries which category
    /// the user tapped so the gallery lands on it instead of always on Photos.
    @State private var galleryCategory: MediaGalleryCategory?

    private let brandColor = Theme.brand

    private var displayName: String {
        if let alias, !alias.isEmpty { return alias }
        return MessageManager.resolveContactAlias(publicKey)
    }

    /// __SHARED_NICKNAME_2026_09_22__ observe pour se redessiner a l'arrivee
    /// d'un pseudo.
    @AppStorage(SharedNickname.defaultsKey) private var sharedNicknamesData: Data = Data()

    private var sharedNickname: String? {
        SharedNickname.table(sharedNicknamesData).nickname(for: publicKey)
    }

    private var profileImage: UIImage? {
        presenceManager.getProfileImage(publicKey: publicKey)
    }

    private var links: ProfileLinks? {
        linksManager.getVisibleLinks(for: publicKey)
    }

    /// Photos, videos, documents, voice messages and links exchanged with this
    /// contact. Same index the gallery renders from, so a count here always
    /// matches what opens.
    private var mediaIndex: ContactMediaIndex {
        ContactMediaIndex.build(peerPublicKey: publicKey, groupId: nil)
    }

    var body: some View {
        NavigationView {
            List {
                Section { headerRow }
                if let onCall {
                    Section {
                        HStack(spacing: 12) {
                            contactCallButton(
                                symbol: "phone.fill",
                                label: "chat.voice_call".localized,
                                video: false,
                                action: onCall
                            )
                            contactCallButton(
                                symbol: "video.fill",
                                label: "chat.video_call".localized,
                                video: true,
                                action: onCall
                            )
                        }
                        if let onRename {
                            renameButton(onRename)
                        }
                    }
                } else if let onRename {
                    Section { renameButton(onRename) }
                }
                // __WIFI_AWARE_2026_11_25__ Renders nothing unless this
                // iPhone (iOS 26+) and build support Wi-Fi Aware.
                WiFiAwareContactSection(publicKey: publicKey, displayName: displayName)
                Section("profile.public_key".localized) {
                    publicKeyRow
                    // __CONTACT_CARD_CARRIES_THE_CODE_2026_10_27__ Reporter's N9
                    // asked for the contact's QR code on this card. The key was
                    // already here as text; the code is the same key in the one
                    // form another phone can actually take in, so it belongs in
                    // this section rather than a new one.
                    //
                    // Caption reuses `address.scan_to_add`, which already ships
                    // in all 34 .lproj files via ContactAddressSheet — a new key
                    // here would have meant 34 more translations for a sentence
                    // the app already owns.
                    qrRow
                }
                if let links, links.hasAnyLink {
                    Section("profile_links.title".localized) {
                        ContactProfileLinksSection(links: links)
                    }
                }
                if !mediaIndex.isEmpty {
                    Section("profile.shared_media".localized) {
                        ForEach(MediaGalleryCategory.allCases.filter { mediaIndex.count($0) > 0 }) { cat in
                            Button {
                                galleryCategory = cat
                            } label: {
                                HStack {
                                    Label(cat.label, systemImage: cat.systemImage)
                                        .foregroundColor(.primary)
                                    Spacer()
                                    Text("\(mediaIndex.count(cat))")
                                        .foregroundColor(.secondary)
                                    Image(systemName: "chevron.right")
                                        .font(.caption.bold())
                                        .foregroundColor(Color(.tertiaryLabel))
                                        .accessibilityHidden(true)
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("profile.contact_profile".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.done".localized) { dismiss() }
                }
            }
        }
        .fullScreenCover(isPresented: $showFullScreenPhoto) {
            if let profileImage {
                FullScreenImageViewer(image: profileImage)
            }
        }
        .sheet(item: $galleryCategory) { cat in
            MediaGalleryView(peerPublicKey: publicKey, groupId: nil, initialCategory: cat)
        }
    }

    // MARK: - Call buttons (N16)

    /// Dismisses first, then calls. The call UI is NOT presented from here or
    /// from ChatView — ContentView's own ZStack draws it
    /// (__CALL_COVER_HID_VIDEO_2026_09_23__, ChatView.swift:1105) — so there is
    /// no sheet-over-sheet conflict to wait out, and the card getting out of
    /// the way immediately is what makes the tap feel like it started a call.
    /// `action` is the chat's own guarded path (`startHeaderCall`), so a
    /// contact who blocked us is refused here exactly as in the nav bar, with
    /// one notion of "can I call this person" rather than two.
    ///
    /// The ink is NOT `Theme.brand`. I wrote these buttons as
    /// `.foregroundColor(brandColor)` over `brandColor.opacity(0.12)`, which is
    /// verbatim "the recipe that failed three times" that Android's own
    /// `Oshi.ContainerTintAlpha` doc warns about, and which
    /// `BubbleStyleManager.linkColour` already measured here at ~2.1:1 on a
    /// dark surface. Both halves of that fix are needed and neither is enough
    /// alone: raise the container to 0.22 so it is actually visible, AND take
    /// the ink off the brand in dark mode — raising the alpha by itself makes
    /// the ink HARDER to read. Same lightened violet and same 0.22 as the
    /// Android twin, so a 15 pt label clears 4.5:1 in both appearances.
    private var callInk: Color {
        Color(UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(red: 0.72, green: 0.60, blue: 1.0, alpha: 1.0)
                : UIColor(Theme.brand)
        })
    }

    private func contactCallButton(
        symbol: String,
        label: String,
        video: Bool,
        action: @escaping (Bool) -> Void
    ) -> some View {
        Button {
            dismiss()
            action(video)
        } label: {
            HStack(spacing: 8) {
                Image(systemName: symbol)
                Text(label)
            }
            .font(.scaled(15, weight: .medium))
            .frame(maxWidth: .infinity, minHeight: 44)
            .foregroundColor(callInk)
            .background(brandColor.opacity(0.22))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private func renameButton(_ action: @escaping () -> Void) -> some View {
        Button {
            dismiss()
            action()
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "pencil")
                Text("chat.set_alias".localized)
            }
            .font(.scaled(15, weight: .medium))
            .frame(maxWidth: .infinity, minHeight: 44)
            .foregroundColor(callInk)
            .background(brandColor.opacity(0.22))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("contact_profile_rename")
    }

    // MARK: - Header (picture + name)

    private var headerRow: some View {
        VStack(spacing: 12) {
            avatar
            Text(displayName)
                .font(.title3)
                .fontWeight(.semibold)
                .multilineTextAlignment(.center)
            // __SHARED_NICKNAME_2026_09_22__ Le pseudo que le contact s'est
            // choisi, sous le nom affiche quand l'utilisateur l'a renomme (alias)
            // — sinon le titre EST deja ce pseudo et le repeter serait du bruit.
            if let nick = sharedNickname, nick != displayName {
                Text(String(format: "contact.shared_nickname".localized, nick))
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        .listRowBackground(Color.clear)
    }

    @ViewBuilder
    private var avatar: some View {
        if let profileImage {
            Button {
                showFullScreenPhoto = true
            } label: {
                Image(uiImage: profileImage)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 120, height: 120)
                    .clipShape(Circle())
                    .overlay(Circle().strokeBorder(brandColor.opacity(0.35), lineWidth: 2))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("profile.contact_profile".localized)
            .accessibilityHint("media_image".localized)
        } else {
            // No picture shared yet. Not a button: there is nothing to enlarge.
            //
            // __ONE_AVATAR_NOT_THREE_2026_11_07__ The comment here used to say
            // this was "the same fallback the contact list and the group member
            // list draw". It was not — the list drew one of fourteen gradients
            // and this drew a flat brand circle, so the same person was two
            // different colours one tap apart. A comment asserting parity is
            // not parity; this now calls the thing the list calls.
            DefaultAvatar(key: publicKey, name: displayName, size: 120, initialCount: 1)
                .accessibilityHidden(true)
        }
    }

    // MARK: - QR code

    /// __CONTACT_CARD_CARRIES_THE_CODE_2026_10_27__ The contact's public key as
    /// a scannable code.
    ///
    /// Generated the same way `ContactAddressSheet` does (CIQRCodeGenerator,
    /// correction level M, 10x transform) so the two codes in the app are the
    /// same bytes rendered the same way — a contact scanned from here and from
    /// there must add identically.
    ///
    /// Built off the main thread because the filter plus `createCGImage` is tens
    /// of milliseconds, and this view is pushed from a chat the user is reading.
    @ViewBuilder
    private var qrRow: some View {
        HStack {
            Spacer()
            VStack(spacing: 8) {
                if let qrImage {
                    Image(uiImage: qrImage)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 180, height: 180)
                        .padding(8)
                        .background(Color.white)
                        .cornerRadius(12)
                        .accessibilityLabel("address.scan_to_add".localized)
                } else {
                    ProgressView()
                        .frame(width: 180, height: 180)
                        .accessibilityLabel("qr.generating".localized)
                }
                Text("address.scan_to_add".localized)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
            Spacer()
        }
        .padding(.vertical, 4)
        .task(id: publicKey) { await generateQRCode() }
    }

    private func generateQRCode() async {
        let key = publicKey
        let image = await Task.detached(priority: .userInitiated) { () -> UIImage? in
            let context = CIContext()
            let filter = CIFilter.qrCodeGenerator()
            filter.message = Data(key.utf8)
            filter.correctionLevel = "M"
            guard let output = filter.outputImage else { return nil }
            let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
            guard let cg = context.createCGImage(scaled, from: scaled.extent) else { return nil }
            return UIImage(cgImage: cg)
        }.value
        await MainActor.run { self.qrImage = image }
    }

    // MARK: - Public key (readable, and copyable)

    private var publicKeyRow: some View {
        Button {
            UIPasteboard.general.string = publicKey
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            withAnimation { didCopyKey = true }
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                withAnimation { didCopyKey = false }
            }
        } label: {
            HStack(alignment: .top, spacing: 12) {
                Text(publicKey)
                    // Monospaced so the key can actually be read and compared
                    // character by character; `.caption` (not a fixed size) so
                    // it still scales with Dynamic Type.
                    .font(.system(.caption, design: .monospaced))
                    .foregroundColor(.primary)
                    .textSelection(.enabled)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)

                Image(systemName: didCopyKey ? "checkmark.circle.fill" : "doc.on.doc")
                    .foregroundColor(didCopyKey ? .green : brandColor)
                    .accessibilityHidden(true)
            }
            // __SEGMENT_HIT_AREA_2026_10_26__ The row says "tap to copy" but only
            // the key glyphs and the icon were tappable; the space between them
            // was dead. Worse here than elsewhere, because the key is rendered
            // with `textSelection(.enabled)`, which on a pointer-driven Mac
            // prefers to select the text rather than report a tap.
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(didCopyKey ? "common.copied".localized
                                       : "menu_copy_public_key".localized)
        .accessibilityAddTraits(.isButton)
    }
}

// MARK: - Links section

/// The contact's shared links.
///
/// Its own view for the view-builder budget, and because the favicon loader
/// needs a `@StateObject` that must not be re-created every time the parent's
/// copy-confirmation state flips.
private struct ContactProfileLinksSection: View {
    let links: ProfileLinks

    @StateObject private var faviconLoader = FaviconLoader()

    var body: some View {
        ForEach(links.filledLinks, id: \.type) { link in
            Button {
                ProfileLinks.open(link.type, value: link.url)
            } label: {
                HStack(spacing: 12) {
                    icon(for: link)
                        .frame(width: 24)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(link.type.label)
                            .font(.body)
                            .foregroundColor(.primary)
                        Text(link.url)
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "arrow.up.right")
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .accessibilityHidden(true)
                }
            }
            .buttonStyle(.plain)
            .accessibilityHint(link.type == .waypath
                               ? "profile_links.waypath.open.hint".localized
                               : "profile_links.open.hint".localized)
        }
        .onAppear {
            for link in links.filledLinks where link.type == .website {
                faviconLoader.loadFavicon(for: link.url)
            }
        }
    }

    @ViewBuilder
    private func icon(for link: (type: ProfileLinks.LinkType, url: String)) -> some View {
        if link.type == .website, let favicon = faviconLoader.favicons[link.url] {
            Image(uiImage: favicon)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: 20, height: 20)
                .clipShape(RoundedRectangle(cornerRadius: 4))
        } else {
            Image(systemName: link.type.icon)
                .font(.body)
                .foregroundColor(link.type.color)
        }
    }
}
