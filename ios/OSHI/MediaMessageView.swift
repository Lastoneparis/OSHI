//
//  MediaMessageView.swift
//  ✅ FULLY LOCALIZED - All user-facing strings use .localized
//  ✅ FIXED - Works with enhanced MediaManager
//

import SwiftUI
import AVKit
import AVFoundation
import CryptoKit

// MARK: - Brand Color
private let appBrandColor = Theme.brand

// MARK: - Main Media Message View

struct MediaMessageView: View {
    let attachment: MediaManager.MediaAttachment
    let isFromCurrentUser: Bool
    var messageId: String? = nil  // ✅ For marking view-once as viewed
    /// __VOICE_MINIPLAYER_2026_10_09__ Who to name in the floating voice-note
    /// player once the user has navigated away from this conversation.
    var senderName: String? = nil
    var initialHasBeenViewed: Bool = false  // ✅ Initial state from message
    var onViewOnceViewed: ((String) -> Void)? = nil  // ✅ Callback when viewed
    /// TIMED REVEAL — nil for every ordinary photo, so all existing call sites
    /// compile and behave exactly as before. Non-nil means "this bubble is
    /// showing layer N of a photo that sharpens on a schedule" (TimedReveal.swift).
    var reveal: TimedRevealPresentation? = nil
    /// __BUBBLE_MEDIA_2026_11_21__ Display size from `BubbleMediaLayout`, set
    /// by `MediaChatBubble` for photos, GIFs and videos: the picture fills it
    /// edge to edge (no inner rounding — the bubble clips the corners). nil =
    /// the old fixed square.
    var displaySize: CGSize? = nil
    /// 🛡 __ADVANCED_CHAT_PRIVACY_2026_11_26__ handed on explicitly to the viewer.
    @Environment(\.advancedChatPrivacy) private var advancedChatPrivacy

    @State private var showFullScreen = false
    @State private var isLoading = false
    @State private var hasBeenViewed = false  // ✅ Track if view-once was viewed

    // __DUO_POLISH_2026_11_18__ The viewer was a plain `.sheet`: a page sheet on
    // a phone (edge to edge, fine), but at regular width (open iPhone Duo, iPad)
    // a ~540 pt form-sheet card with the photo letterboxed in black inside it,
    // floating over the chat. Regular width now gets a full-screen cover, the
    // photo on the whole display. The style is chosen while the viewer is
    // closed, so folding WITH the viewer open does not swap (= close) it.
    @Environment(\.horizontalSizeClass) private var mediaSizeClass
    @State private var viewerIsCover = false

    private var viewerAsSheet: Binding<Bool> {
        Binding(get: { showFullScreen && !viewerIsCover }, set: { showFullScreen = $0 })
    }

    private var viewerAsCover: Binding<Bool> {
        Binding(get: { showFullScreen && viewerIsCover }, set: { showFullScreen = $0 })
    }

    var body: some View {
        VStack(alignment: isFromCurrentUser ? .trailing : .leading, spacing: 5) {
            // TIMED REVEAL first: while a reveal is still running the bubble
            // shows the sharpest layer that has ARRIVED, not the full picture.
            // Once the final layer is in (`isFinished`) this branch drops out on
            // its own and the bubble becomes an ordinary photo bubble again —
            // that is also the degrade path if the feature is never populated.
            if let reveal = reveal, !reveal.isFinished {
                timedRevealMediaView(reveal)
            } else if attachment.isViewOnce {
                // ✅ View-once media - different behavior for sender vs receiver
                if isFromCurrentUser {
                    // 🔧 SENDER: Show full picture with "View Once" badge
                    // Add "Opened" badge when receiver has viewed it
                    viewOnceSenderView
                } else {
                    // 🔧 RECEIVER: Blurred preview until viewed
                    if hasBeenViewed || initialHasBeenViewed {
                        // Already viewed - show placeholder
                        viewOnceViewedIndicator
                    } else {
                        // Not yet viewed - show tap to view
                        viewOnceMediaButton
                    }
                }
            } else if attachment.type == .contact {
                // 🔧 2026-06-09: Contact cards do NOT use the fullscreen-image
                // sheet — tapping anywhere on the card should start a chat with
                // the shared contact (same action as the inner "Start chat"
                // button). The previous code wrapped contact cards in the
                // image-fullscreen Button, so taps outside the inner button
                // opened a fullscreen viewer that showed the same card on a
                // black background — looked like "an image opened".
                if let contactCard = try? MediaManager().decodeContactCard(from: attachment) {
                    Button {
                        NotificationCenter.default.post(
                            name: NSNotification.Name("StartChatWithContact"),
                            object: nil,
                            userInfo: [
                                "publicKey": contactCard.publicKey,
                                "alias": contactCard.alias ?? ""
                            ]
                        )
                    } label: {
                        ContactCardView(
                            contactCard: contactCard,
                            onStartChat: {
                                NotificationCenter.default.post(
                                    name: NSNotification.Name("StartChatWithContact"),
                                    object: nil,
                                    userInfo: [
                                        "publicKey": contactCard.publicKey,
                                        "alias": contactCard.alias ?? ""
                                    ]
                                )
                            }
                        )
                    }
                    .buttonStyle(.plain)
                } else {
                    // __CONTACT_CARD_COMPAT_2026_09_27__ Never a dead tile: a card
                    // no parser understands is still a file the user can open,
                    // save or pass on (the full-screen sheet falls back to the
                    // document view for it).
                    unreadableContactFallback
                }
            } else if attachment.renderType == .audio {
                // __VOICE_SCRUB_2026_10_26__ Reported: « quand on joue un message
                // audio et qu'on clique sur l'onde, ça ouvre un lecteur plein
                // écran mais le message continue, et il faut attendre la fin
                // pour que le plein écran joue ; et on ne peut pas cliquer au
                // milieu de l'onde ».
                //
                // Both halves were one mistake: a voice note fell into the photo
                // branch below, whose `onTapGesture { showFullScreen = true }`
                // made a tap mean "open a viewer" instead of "seek there". That
                // viewer then built a SECOND, independent player from a temp file
                // (`fullScreenAudio` → `AudioPlayerView(url:)`) starting at zero,
                // while `VoiceNotePlayback` kept playing the bubble's copy — so
                // the user heard the old one to the end.
                //
                // Telegram has no full-screen voice-note player: the note plays in
                // the bubble and the waveform IS the scrubber (`InlineVoiceNoteView`
                // below). Keeping audio out of the sheet also stops `saveToTemp()`
                // writing a plaintext copy of every voice note to disk — the leak
                // __PLAINTEXT_LEFTOVERS_2026_09_24__ closed on the bubble path and
                // this one quietly reopened.
                mediaPreview
            } else {
                // ✅ Normal media
                //
                // __PHOTO_OPENS_ON_SCROLL_2026_08_24__ Deliberately a tap
                // GESTURE, not a `Button`.
                //
                // Reported: "when I slide up or down over a photo it opens on
                // the way past — it should open on a click and otherwise let the
                // conversation scroll."
                //
                // `MessageBubble` (ChatView.swift:3661) carries a
                // `.gesture(DragGesture(minimumDistance: 14))` for the
                // swipe-left timestamp reveal, and this bubble renders INSIDE it
                // (ChatView.swift:4054). That drag competes with the scroll view's
                // pan, and a `.buttonStyle(.plain)` button caught in that
                // arbitration still fires its action on release — so scrolling
                // past a photo opened it.
                //
                // A `TapGesture` cannot do that: it FAILS as soon as the finger
                // travels, so any scroll cancels it, while a real tap still
                // opens the viewer. `.contentShape` keeps the whole preview
                // tappable (a plain image only hits on opaque pixels), and the
                // button accessibility trait is restored explicitly — that is the
                // one thing dropping `Button` would otherwise cost VoiceOver.
                mediaPreview
                    .overlay(alignment: .bottomTrailing) {
                        // The time pill owns that corner on a sized picture.
                        if displaySize == nil { fileSizeBadge }
                    }
                    .contentShape(Rectangle())
                    .onTapGesture {
                        showFullScreen = true
                    }
                    .accessibilityAddTraits(.isButton)
                    .accessibilityLabel(mediaAccessibilityLabel)
                    .accessibilityHint("media.open_fullscreen.hint".localized)
            }
        }
        .onAppear {
            hasBeenViewed = initialHasBeenViewed
            viewerIsCover = mediaSizeClass == .regular
        }
        .onChange(of: mediaSizeClass) { _, sizeClass in
            if !showFullScreen { viewerIsCover = sizeClass == .regular }
        }
        .sheet(isPresented: viewerAsSheet) { fullScreenViewer }
        .fullScreenCover(isPresented: viewerAsCover) { fullScreenViewer }
    }

    @ViewBuilder
    private var fullScreenViewer: some View {
            if attachment.isViewOnce {
                ViewOnceFullScreenView(
                    attachment: attachment,
                    isFromCurrentUser: isFromCurrentUser,
                    onDismiss: {
                        hasBeenViewed = true
                        if let id = messageId {
                            onViewOnceViewed?(id)
                        }
                    }
                )
            } else {
                // Fullscreen must show the SAME bytes the bubble is showing.
                // Handing it `attachment` while a reveal is running would open
                // a sharper picture than the one on screen.
                MediaFullScreenView(attachment: displayAttachment, isFromCurrentUser: isFromCurrentUser)
                    .environment(\.advancedChatPrivacy, advancedChatPrivacy)
            }
        }

    // MARK: - Timed reveal

    /// The attachment as currently displayed: identical to `attachment` unless a
    /// reveal is running and the store still holds the current layer's bytes.
    private var displayAttachment: MediaManager.MediaAttachment {
        guard let reveal = reveal, !reveal.isFinished, let bytes = reveal.imageData else {
            return attachment
        }
        return MediaManager.MediaAttachment(
            id: attachment.id,
            type: attachment.type,
            data: bytes,
            thumbnailData: nil,   // a thumbnail from the full photo would leak detail the layer does not have
            fileName: attachment.fileName,
            fileSize: bytes.count,
            timestamp: attachment.timestamp,
            originalSize: attachment.originalSize,
            compressionRatio: attachment.compressionRatio,
            isCompressed: attachment.isCompressed,
            isViewOnce: attachment.isViewOnce
        )
    }

    /// Decoded bytes of the layer on screen. `nil` here is not an error — it
    /// simply means we cannot draw a reveal, and the caller falls back to the
    /// ordinary preview rather than to a broken bubble.
    private func revealImage(_ reveal: TimedRevealPresentation) -> UIImage? {
        guard attachment.type == .image else { return nil }
        let bytes = reveal.imageData ?? attachment.data
        guard !bytes.isEmpty, bytes.count < 50_000_000 else { return nil }
        let key = "\(reveal.messageId)#\(reveal.layerIndex)#\(bytes.count)"
        guard let image = TimedRevealImageCache.shared.image(for: key, data: bytes),
              image.size.width > 0, image.size.height > 0,
              image.size.width.isFinite, image.size.height.isFinite else { return nil }
        return image
    }

    /// The reveal bubble.
    ///
    /// Deliberately NOT `viewOnceMediaButton`: that one blurs the full image
    /// (the exact thing this feature exists to avoid), and it crops to a fixed
    /// square, which hard-crops every portrait photo. Here the layer is drawn
    /// `.fit` at the bubble width, so aspect ratio survives and nothing is cut.
    @ViewBuilder
    private func timedRevealMediaView(_ reveal: TimedRevealPresentation) -> some View {
        if let image = revealImage(reveal) {
            Button {
                showFullScreen = true
            } label: {
                ZStack(alignment: .bottomLeading) {
                    Image(uiImage: image)
                        .resizable()
                        // Smooth, not a blocky mosaic. Still honest: there is
                        // genuinely no more detail in these bytes.
                        .interpolation(.medium)
                        .aspectRatio(contentMode: .fit)
                        .frame(maxWidth: MediaChatBubble.bubbleWidth)
                        // `.id` + `.transition` is what makes the new layer
                        // crossfade in; without the id SwiftUI reuses the same
                        // Image and the bitmap snaps.
                        .id(reveal.layerIndex)
                        .transition(.opacity)

                    revealCountdownCapsule(reveal)
                        .padding(10)
                }
                .frame(maxWidth: MediaChatBubble.bubbleWidth)
                .clipShape(RoundedRectangle(cornerRadius: 15))
                .animation(.easeInOut(duration: 0.4), value: reveal.layerIndex)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(mediaAccessibilityLabel)
            .accessibilityValue(TimedRevealFormatter.remaining(reveal.remaining(at: Date())))
        } else {
            // Not an image, or undecodable bytes: behave like any other media.
            Button {
                showFullScreen = true
            } label: {
                mediaPreview
            }
            .buttonStyle(.plain)
            .accessibilityLabel(mediaAccessibilityLabel)
        }
    }

    /// Countdown capsule. Carries NO words: a ring, and a duration formatted by
    /// `DateComponentsFormatter`, which is localized in all 34 languages. When
    /// the clock has run out but a layer is still in flight it becomes an
    /// indeterminate spinner instead of claiming a time it no longer has.
    @ViewBuilder
    private func revealCountdownCapsule(_ reveal: TimedRevealPresentation) -> some View {
        TimelineView(.periodic(from: Date(), by: 1)) { context in
            let remaining = reveal.remaining(at: context.date)
            HStack(spacing: 6) {
                if remaining > 0 {
                    ZStack {
                        Circle()
                            .stroke(Color.white.opacity(0.35), lineWidth: 2)
                        Circle()
                            .trim(from: 0, to: max(0.001, reveal.progress(at: context.date)))
                            .stroke(Color.white, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                    }
                    .frame(width: 12, height: 12)

                    Text(TimedRevealFormatter.remaining(remaining))
                        .font(.caption2)
                        .fontWeight(.medium)
                        .monospacedDigit()
                } else {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .scaleEffect(0.5)
                        .frame(width: 12, height: 12)
                        .tint(.white)
                }
            }
            .foregroundColor(.white)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(Color.black.opacity(0.55))
            .clipShape(Capsule())
        }
    }

    // 🔧 NEW: Sender view for view-once media
    // Shows the full picture with "View Once" badge, and "Opened" badge when recipient viewed
    @ViewBuilder
    private var viewOnceSenderView: some View {
        if attachment.type == .audio {
            // Le lecteur du vocal reste utilisable; le badge passe DESSOUS au lieu
            // de recouvrir ses commandes.
            VStack(alignment: .trailing, spacing: 6) {
                mediaPreview
                HStack(spacing: 6) {
                    Label("media_picker.view_once".localized, systemImage: "1.circle.fill")
                    if hasBeenViewed || initialHasBeenViewed {
                        Label("media.view_once.opened".localized, systemImage: "eye.fill")
                    }
                }
                .font(.caption2.weight(.medium))
                .foregroundColor(.orange)
            }
        } else {
            viewOnceSenderVisual
        }
    }

    private var viewOnceSenderVisual: some View {
        Button {
            showFullScreen = true
        } label: {
            ZStack(alignment: .bottomTrailing) {
                // Show full picture (not blurred) for sender
                mediaPreview

                // Badges overlay
                VStack(alignment: .trailing, spacing: 4) {
                    // "View Once" badge
                    HStack(spacing: 4) {
                        Image(systemName: "1.circle.fill")
                            .font(.caption2)
                        Text("media_picker.view_once".localized)
                            .font(.caption2)
                            .fontWeight(.medium)
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.black.opacity(0.6))
                    .foregroundColor(.white)
                    .cornerRadius(10)

                    // "Opened" badge - shows when recipient has viewed
                    if hasBeenViewed || initialHasBeenViewed {
                        HStack(spacing: 4) {
                            Image(systemName: "eye.fill")
                                .font(.caption2)
                            Text("media.view_once.opened".localized)
                                .font(.caption2)
                                .fontWeight(.medium)
                        }
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(appBrandColor)
                        .foregroundColor(.white)
                        .cornerRadius(10)
                    }
                }
                .padding(8)
            }
        }
        .buttonStyle(.plain)
    }

    // ✅ View-once "already viewed" indicator
    // Shows "Opened" for sender (recipient opened), "Viewed" for receiver
    private var viewOnceViewedIndicator: some View {
        HStack(spacing: 8) {
            Image(systemName: isFromCurrentUser ? "eye.circle.fill" : "eye.slash.circle.fill")
                .foregroundColor(isFromCurrentUser ? appBrandColor : .gray)
            Text(isFromCurrentUser ? "media.view_once.opened".localized : "media.view_once.viewed".localized)
                .font(.caption)
                .foregroundColor(isFromCurrentUser ? appBrandColor : .gray)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background((isFromCurrentUser ? appBrandColor : Color.gray).opacity(0.15))
        .clipShape(RoundedRectangle(cornerRadius: 15))
    }
    
    // ✅ View-once media button (blurred preview)
    @ViewBuilder
    private var viewOnceMediaButton: some View {
        if attachment.type == .audio {
            viewOnceVoicePill
        } else {
            viewOnceVisualButton
        }
    }

    /// __VOICE_PREVIEW_2026_09_29__ Un vocal « vue unique » reçu: une pastille à
    /// la taille d'un vocal, pas un carré de 250 pt fait pour une photo floutée.
    private var viewOnceVoicePill: some View {
        Button {
            showFullScreen = true
        } label: {
            HStack(spacing: 12) {
                ZStack {
                    Circle().fill(LinearGradient(colors: [.orange, .pink],
                                                 startPoint: .topLeading, endPoint: .bottomTrailing))
                    Image(systemName: "1")
                        .font(.scaled(17, weight: .heavy))
                        .foregroundColor(.white)
                }
                .frame(width: 40, height: 40)

                VStack(alignment: .leading, spacing: 2) {
                    Text("audio.voice_message".localized)
                        .font(.subheadline.weight(.semibold))
                    Text("media.view_once.tap_to_view".localized)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                Spacer(minLength: 8)
                Image(systemName: "play.circle.fill")
                    .font(.scaled(26))
                    .foregroundStyle(.orange)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(width: MediaChatBubble.bubbleWidth)
            .background(.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("audio.voice_message".localized + ", " + "media_picker.view_once".localized)
    }

    @ViewBuilder
    private var viewOnceVisualButton: some View {
        Button {
            showFullScreen = true
        } label: {
            ZStack {
                // Blurred background hint (use safe downsampled image)
                if attachment.type == .image, let image = MediaAttachmentCache.shared.decodedImage(from: attachment.data, id: attachment.id, maxDimension: 400) {
                    Image(uiImage: image)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(width: MediaChatBubble.bubbleWidth, height: MediaChatBubble.bubbleWidth)
                        .blur(radius: 30)
                        .clipped()
                        .clipShape(RoundedRectangle(cornerRadius: 15))
                } else {
                    RoundedRectangle(cornerRadius: 15)
                        .fill(
                            LinearGradient(
                                colors: [.orange.opacity(0.3), .pink.opacity(0.3)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: MediaChatBubble.bubbleWidth, height: MediaChatBubble.bubbleWidth)
                }
                
                // Overlay content
                VStack(spacing: 12) {
                    Image(systemName: mediaTypeIcon)
                        .font(.scaled(50))
                        .foregroundColor(.white)
                    
                    Text("media.view_once.tap_to_view".localized)
                        .font(.subheadline)
                        .fontWeight(.medium)
                        .foregroundColor(.white)
                    
                    // Show "view once" badge
                    HStack(spacing: 4) {
                        Image(systemName: "eye.slash")
                            .font(.caption)
                        Text("1")
                            .font(.caption.bold())
                    }
                    .foregroundColor(.white.opacity(0.8))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(Color.black.opacity(0.3))
                    .clipShape(Capsule())
                }
            }
        }
        .buttonStyle(.plain)
    }
    
    private var mediaTypeIcon: String {
        switch attachment.type {
        case .image: return "photo.circle.fill"
        case .gif: return "photo.circle.fill"
        case .video: return "video.circle.fill"
        case .audio: return "waveform.circle.fill"
        case .document: return "doc.circle.fill"
        case .contact: return "person.crop.circle.fill"
        }
    }

    /// VoiceOver description of the attachment for the tappable media preview.
    private var mediaAccessibilityLabel: String {
        switch attachment.type {
        case .image: return "media.a11y.photo".localized
        // Réutilise la clé « photo »: elle existe dans les 34 locales, alors
        // qu'une clé « gif » neuve n'y serait traduite nulle part.
        case .gif: return "media.a11y.photo".localized
        case .video: return "media.a11y.video".localized
        case .audio: return "media.a11y.audio".localized
        case .document: return "media.a11y.document".localized
        case .contact: return "media.a11y.contact".localized
        }
    }
    
    @ViewBuilder
    private var mediaPreview: some View {
        ZStack {
            // `renderType`, pas `type`: un GIF étiqueté `image` par un pair
            // s'afficherait sinon figé sur sa première image.
            switch attachment.renderType {
            case .image:
                imagePreview
            // __GIF_2026_09_12__ Surtout PAS `imagePreview`: il passe par le
            // rééchantillonnage ImageIO, qui aplatit le GIF sur sa première image.
            case .gif:
                // __GIF_BUBBLE_WIDTH_2026_09_13__ « on a une bordure violette à
                // gauche et à droite quand on envoie un GIF ».
                //
                // `GIFBubbleView` plafonnait à sa largeur par défaut de 240 pt à
                // l'intérieur d'une bulle FIXÉE à 250 pt (`bubbleWidth`), donc le
                // GIF était centré et laissait 5 pt de remplissage de marque —
                // le violet — de chaque côté. Exactement le défaut corrigé le
                // 05/08 pour les photos, que le chemin GIF, écrit après, a
                // réintroduit: une image passe par `imagePreview`, qui fait
                // `.frame(width: bubbleWidth, height: bubbleWidth)` et remplit
                // donc la bulle bord à bord.
                //
                // On donne la largeur de la bulle au lieu de la coder en dur ici:
                // si `bubbleWidth` change un jour, la bande ne revient pas.
                GIFBubbleView(data: attachment.data,
                              maxWidth: displaySize?.width ?? MediaChatBubble.bubbleWidth)
                    .frame(width: displaySize?.width, height: displaySize?.height)
                    .clipped()
            case .video:
                videoPreview
            case .audio:
                InlineVoiceNoteView(audioData: attachment.data,
                                    isFromCurrentUser: isFromCurrentUser,
                                    noteID: messageId ?? attachment.id,
                                    noteTitle: senderName ?? "")
            case .document:
                documentPreview
            case .contact:
                contactPreview
            }
        }
    }
    
    @ViewBuilder
    private var imagePreview: some View {
        if attachment.data.count >= 50_000_000 {
            Text("Image too large")
                .foregroundColor(.secondary)
        } else {
            // __CHAT_OPEN_PERF_2026_11_14__ The ImageIO downsample used to run
            // HERE, synchronously, on the first render of every photo row — one
            // full JPEG decode on the main thread per photo scrolled into view.
            // It now runs off main (`ChatAsyncImage`); a cached picture still
            // shows in the first frame, and a miss shows a box of the final size
            // so the row never changes height. Same key as before (`<id>@800`),
            // so the cache is shared with the warm-up and the quote.
            let data = attachment.data
            ChatAsyncImage(id: attachment.id,
                           maxDimension: 800,
                           size: mediaBox,
                           cornerRadius: displaySize == nil ? 15 : 0,
                           produce: { ChatImagePipeline.prepare(data, maxDimension: 800) }) {
                imageDecodeFallback
            }
        }
    }

    /// What `imagePreview` drew when the bytes did not decode: the thumbnail,
    /// else the placeholder.
    @ViewBuilder
    private var imageDecodeFallback: some View {
        if let thumbnailData = attachment.thumbnailData,
                  let thumbnail = UIImage(data: thumbnailData),
                  thumbnail.size.width > 0,
                  thumbnail.size.height > 0,
                  thumbnail.size.width.isFinite,
                  thumbnail.size.height.isFinite {
            Image(uiImage: thumbnail)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(width: mediaBox.width, height: mediaBox.height)
                .clipped()
                .clipShape(RoundedRectangle(cornerRadius: displaySize == nil ? 15 : 0))
                .overlay {
                    if isLoading {
                        ProgressView()
                            .tint(.white)
                    }
                }
        } else {
            placeholderView(icon: "photo")
        }
    }
    
    @ViewBuilder
    private var videoPreview: some View {
        // __CHAT_OPEN_PERF_2026_11_14__ `UIImage(data:)` of the poster ran on
        // every body pass and the JPEG was inflated at draw time, on main. The
        // poster is now decoded once, off main, and cached like a photo.
        if let thumbnailData = attachment.thumbnailData {
            ZStack {
                ChatAsyncImage(id: attachment.id + "#poster",
                               maxDimension: 400,
                               size: mediaBox,
                               cornerRadius: displaySize == nil ? 15 : 0,
                               produce: { ChatImagePipeline.prepare(thumbnailData, maxDimension: 400) }) {
                    placeholderView(icon: "video")
                }
                
                Circle()
                    .fill(Color.black.opacity(0.6))
                    .frame(width: 60, height: 60)
                
                Image(systemName: "play.fill")
                    .font(.title)
                    .foregroundColor(.white)
            }
        } else {
            placeholderView(icon: "video")
        }
    }
    
    // ✅ NEW: Audio preview
    @ViewBuilder
    private var audioPreview: some View {
        HStack(spacing: 12) {
            Image(systemName: "waveform")
                .font(.title2)
                .foregroundColor(isFromCurrentUser ? .white : appBrandColor)
            
            VStack(alignment: .leading, spacing: 4) {
                Text("media.audio_message".localized)
                    .font(.subheadline)
                    .fontWeight(.medium)
                
                Text(attachment.fileSizeFormatted)
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
            
            Spacer()
            
            Image(systemName: "play.circle.fill")
                .font(.title)
                .foregroundColor(isFromCurrentUser ? .white : appBrandColor)
        }
        .padding(12)
        .frame(width: 200)
        .background(isFromCurrentUser ? appBrandColor : Color(.systemGray5))
        .foregroundColor(isFromCurrentUser ? .white : .primary)
        .cornerRadius(15)
    }
    
    // ✅ NEW: Document preview
    @ViewBuilder
    private var documentPreview: some View {
        DocumentMessageView(
            fileName: attachment.fileName,
            fileSize: attachment.fileSize,
            onTap: { showFullScreen = true }
        )
    }
    
    // ✅ NEW: Contact preview
    @ViewBuilder
    private var contactPreview: some View {
        if let contactCard = try? MediaManager().decodeContactCard(from: attachment) {
            ContactCardView(
                contactCard: contactCard,
                onStartChat: {
                    handleContactTap(contactCard)
                }
            )
        } else {
            unreadableContactFallback
        }
    }

    /// A `.contact` attachment whose bytes are neither our JSON card nor a vCard
    /// with an OSHI key: show it as the file it is — name, size, tap to open the
    /// document sheet (share / save to Files).
    private var unreadableContactFallback: some View {
        DocumentMessageView(
            fileName: attachment.fileName,
            fileSize: attachment.fileSize,
            onTap: { showFullScreen = true }
        )
    }
    
    private func handleContactTap(_ contact: ContactCard) {
        // Tapping a shared contact card in the message bubble opens (or creates) a
        // direct chat with that contact. Previously this just showed the full-screen
        // media viewer, which made the contact card look like a photo.
        NotificationCenter.default.post(
            name: NSNotification.Name("StartChatWithContact"),
            object: nil,
            userInfo: [
                "publicKey": contact.publicKey,
                "alias": contact.alias ?? ""
            ]
        )
    }
    
    /// The box a photo / video preview is drawn in.
    private var mediaBox: CGSize {
        displaySize ?? CGSize(width: MediaChatBubble.bubbleWidth, height: MediaChatBubble.bubbleWidth)
    }

    private func placeholderView(icon: String) -> some View {
        Rectangle()
            .fill(Color.gray.opacity(0.3))
            .frame(width: mediaBox.width, height: mediaBox.height)
            .clipShape(RoundedRectangle(cornerRadius: displaySize == nil ? 15 : 0))
            .overlay {
                Image(systemName: icon)
                    .font(.largeTitle)
                    .foregroundColor(.gray)
            }
    }
    
    private var fileSizeBadge: some View {
        Text(attachment.fileSizeFormatted)
            .font(.caption2)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(Color.black.opacity(0.6))
            .foregroundColor(.white)
            .cornerRadius(8)
            .padding(8)
    }
}

// MARK: - Zoomable Image View (Pinch-to-Zoom + Double-Tap)

struct ZoomableImageView: View {
    let image: UIImage
    @State private var scale: CGFloat = 1.0
    @State private var lastScale: CGFloat = 1.0
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    var body: some View {
        GeometryReader { geo in
            Image(uiImage: image)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .scaleEffect(scale)
                .offset(offset)
                .frame(width: geo.size.width, height: geo.size.height)
                .gesture(
                    MagnificationGesture()
                        .onChanged { value in
                            let newScale = lastScale * value
                            scale = min(max(newScale, 1.0), 5.0)
                        }
                        .onEnded { value in
                            lastScale = scale
                            if scale <= 1.0 {
                                withAnimation(.spring(response: 0.3)) {
                                    offset = .zero
                                    lastOffset = .zero
                                }
                            }
                        }
                )
                .simultaneousGesture(
                    DragGesture()
                        .onChanged { value in
                            if scale > 1.0 {
                                offset = CGSize(
                                    width: lastOffset.width + value.translation.width,
                                    height: lastOffset.height + value.translation.height
                                )
                            }
                        }
                        .onEnded { _ in
                            lastOffset = offset
                        }
                )
                .onTapGesture(count: 2) {
                    withAnimation(.spring(response: 0.3)) {
                        if scale > 1.0 {
                            scale = 1.0
                            lastScale = 1.0
                            offset = .zero
                            lastOffset = .zero
                        } else {
                            scale = 2.5
                            lastScale = 2.5
                        }
                    }
                }
        }
    }
}

// MARK: - Full Screen Media View

struct MediaFullScreenView: View {
    let attachment: MediaManager.MediaAttachment
    /// 🛡 __ADVANCED_CHAT_PRIVACY_2026_11_26__ who sent it, when the caller knows;
    /// nil is treated as someone else's media.
    var isFromCurrentUser: Bool? = nil
    @Environment(\.dismiss) var dismiss
    @Environment(\.advancedChatPrivacy) private var privacy
    @State private var showSaveConfirmation = false

    /// Save to Photos / Save to Files: refused by strict mode, and — for other
    /// people's media — by advanced chat privacy.
    private var mayExport: Bool {
        StrictSecurityMode.allowsMediaExport
            && AdvancedChatPrivacyPolicy.allowsSavingMedia(protected: privacy.isOn, isFromMe: isFromCurrentUser)
    }

    var body: some View {
        NavigationView {
            ZStack {
                Color.black.ignoresSafeArea()

                switch attachment.renderType {
                case .image:
                    fullScreenImage
                // __GIF_2026_09_12__ idem en plein écran.
                case .gif:
                    AnimatedImageView(data: attachment.data, contentMode: .scaleAspectFit)
                case .video:
                    fullScreenVideo
                case .audio:
                    fullScreenAudio
                case .document:
                    fullScreenDocument
                case .contact:
                    fullScreenContact
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    // __STRICT_SECURITY_MODE_2026_11_22__ nothing leaves OSHI.
                    if attachment.type == .image && mayExport {
                        Button {
                            saveImageToPhotos()
                        } label: {
                            Image(systemName: "square.and.arrow.down")
                                .foregroundColor(.white)
                        }
                        .accessibilityLabel("media.save_to_photos".localized)
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(.white)
                    }
                    .accessibilityLabel("common.close".localized)
                }
            }
            // __PLAINTEXT_LEFTOVERS_2026_09_24__ La copie en clair de lecture
            // (vidéo, audio) part avec le visualiseur; rouvrir la réécrit.
            .onDisappear { Self.discardTemp(for: attachment.id) }
            .overlay {
                if showSaveConfirmation {
                    VStack {
                        Spacer()
                        Text("Saved to Photos")
                            .font(.subheadline)
                            .fontWeight(.medium)
                            .foregroundColor(.white)
                            .padding(.horizontal, 20)
                            .padding(.vertical, 10)
                            .background(Color.green.opacity(0.85))
                            .cornerRadius(20)
                            .padding(.bottom, 40)
                    }
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
        }
    }

    // MARK: - Full Screen Image with Pinch-to-Zoom
    @ViewBuilder
    private var fullScreenImage: some View {
        if attachment.data.count >= 50_000_000 {
            Text("Image too large")
                .foregroundColor(.secondary)
        } else if let image = MediaAttachmentCache.shared.decodedImage(from: attachment.data, id: attachment.id, maxDimension: 1920) {
            ZoomableImageView(image: image)
        } else {
            VStack(spacing: 12) {
                Image(systemName: "photo.badge.exclamationmark")
                    .font(.scaled(40))
                    .foregroundColor(.secondary)
                Text("Unable to display image")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                Text("\(attachment.data.count) bytes")
                    .font(.caption)
                    .foregroundColor(.secondary.opacity(0.7))
            }
        }
    }

    private func saveImageToPhotos() {
        guard let image = MediaManager.safeUIImage(from: attachment.data, maxDimension: 4096) else { return }
        UIImageWriteToSavedPhotosAlbum(image, nil, nil, nil)
        withAnimation(.spring(response: 0.3)) {
            showSaveConfirmation = true
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            withAnimation { showSaveConfirmation = false }
        }
    }
    
    @ViewBuilder
    private var fullScreenVideo: some View {
        if let tempURL = saveToTemp() {
            // The player is built ONCE and held, rather than re-created inline.
            // `VideoPlayer(player: AVPlayer(url:))` inside a builder allocates a
            // fresh player on every body evaluation, and each one starts its own
            // asset load; the visible effect is playback restarting or stalling
            // when anything else on screen changes.
            //
            // __PLAINTEXT_LEFTOVERS_2026_09_24__ The temp copy IS deleted when
            // the viewer closes (`discardTemp`): a decrypted video must not
            // outlive its viewer. Re-opening pays one rewrite, the cache still
            // prevents a rewrite per body evaluation while it is open.
            FullScreenVideoPlayer(url: tempURL)
        }
    }
    
    // ✅ NEW: Full screen audio player
    @ViewBuilder
    private var fullScreenAudio: some View {
        VStack(spacing: 30) {
            Image(systemName: "waveform.circle.fill")
                .font(.scaled(100))
                .foregroundColor(appBrandColor)
            
            Text("media.audio_message".localized)
                .font(.title2)
                .foregroundColor(.white)
            
            Text(attachment.fileSizeFormatted)
                .font(.subheadline)
                .foregroundColor(.gray)
            
            if let tempURL = saveToTemp() {
                AudioPlayerView(url: tempURL)
            }
        }
        .padding()
    }
    
    // ✅ NEW: Full screen document view
    @ViewBuilder
    private var fullScreenDocument: some View {
        VStack(spacing: 20) {
            Spacer()
            
            DocumentMessageView(
                fileName: attachment.fileName,
                fileSize: attachment.fileSize,
                onTap: { shareDocument() }
            )
            .padding()
            
            if mayExport {  // __STRICT_SECURITY_MODE_2026_11_22__
            Button {
                shareDocument()
            } label: {
                HStack {
                    Image(systemName: "arrow.down.doc")
                    Text("document.save_to_files".localized)
                }
                .foregroundColor(.white)
                .padding()
                .background(appBrandColor)
                .cornerRadius(12)
            }
            }
            
            Spacer()
        }
    }
    
    // ✅ NEW: Full screen contact view
    @ViewBuilder
    private var fullScreenContact: some View {
        VStack(spacing: 20) {
            Spacer()
            
            if let contactCard = try? MediaManager().decodeContactCard(from: attachment) {
                ContactCardView(
                    contactCard: contactCard,
                    onStartChat: {
                        startChatWithContact(contactCard)
                    }
                )
                .padding()
            } else {
                // __CONTACT_CARD_COMPAT_2026_09_27__ Unreadable card: offer the
                // bytes as a file rather than an empty black sheet.
                fullScreenDocument
            }

            Spacer()
        }
    }

    private func shareDocument() {
        guard mayExport else { return }  // __STRICT_SECURITY_MODE_2026_11_22__
        // __PLAINTEXT_LEFTOVERS_2026_09_24__ Copie de PARTAGE (purgée au partage
        // suivant / au lancement), distincte de la copie de lecture: l'autre app
        // peut encore la lire après la fermeture de ce visualiseur.
        guard let tempURL = try? MediaPlaintextTemp.writeForShare(attachment.data, fileName: attachment.fileName) else {
            OshiLog.storage.info("❌ Failed to save document to temp")
            return
        }
        
        OshiLog.storage.info("✅ Saved document to: \(tempURL.path)")
        
        // Present activity view controller directly
        DispatchQueue.main.async { [dismiss] in
            // Create custom OSHI activity
            let oshiActivity = OSHIShareActivity { fileURL in
                OshiLog.storage.info("🎉 User selected OSHI to share document")
                
                // Dismiss current sheet
                dismiss()
                
                // Post notification to open contact picker and share document
                NotificationCenter.default.post(
                    name: NSNotification.Name("ShareDocumentViaOSHI"),
                    object: nil,
                    userInfo: ["fileURL": fileURL]
                )
            }
            
            let activityVC = UIActivityViewController(
                activityItems: [tempURL],
                applicationActivities: [oshiActivity]  // ✅ Add OSHI option
            )
            
            // Get the current window scene and present
            if let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
               let window = windowScene.windows.first {
                
                // Find the topmost view controller
                var topVC = window.rootViewController
                while let presented = topVC?.presentedViewController {
                    topVC = presented
                }
                
                // Configure for iPad
                if let popover = activityVC.popoverPresentationController {
                    popover.sourceView = window
                    popover.sourceRect = CGRect(x: window.bounds.midX, y: window.bounds.midY, width: 0, height: 0)
                    popover.permittedArrowDirections = []
                }
                
                topVC?.present(activityVC, animated: true) {
                    OshiLog.storage.info("✅ Share sheet presented with OSHI option")
                }
            }
        }
    }
    
    
    private func startChatWithContact(_ contact: ContactCard) {
        // Post notification to open chat with this contact
        NotificationCenter.default.post(
            name: NSNotification.Name("StartChatWithContact"),
            object: nil,
            userInfo: [
                "publicKey": contact.publicKey,
                "alias": contact.alias ?? "",
                "autoSendMessage": "Hello OSHI! 👋"
            ]
        )
        dismiss()
    }
    
    /// A file URL for this attachment's bytes, written at most once.
    ///
    /// 🔧 FIX (2026-08-04). This is called from `@ViewBuilder` properties
    /// (`fullScreenVideo`, `fullScreenAudio`), so it ran on EVERY body
    /// evaluation and rewrote the entire payload to disk each time — a whole
    /// video re-serialised on a rotation, a state change, a re-render. It was
    /// survivable only because those builders sit behind a fullscreen cover that
    /// appears once; reuse it under anything that re-renders — an inline player
    /// in a scrolling list, say — and it becomes a disk write per frame.
    ///
    /// It also wrote to `temporaryDirectory/<fileName>` with no disambiguation,
    /// so two attachments both called `video.mp4` — the default OSHI itself
    /// generates for received media — resolved to the SAME path and silently
    /// overwrote each other. Opening one could show the other's contents.
    ///
    /// Now: keyed by attachment id, written once, reused. Each attachment gets
    /// its own subdirectory, so the original filename is preserved (it is what
    /// the share sheet and Files show the user) without ever colliding.
    private static let tempFileCacheLock = NSLock()
    nonisolated(unsafe) private static var tempFileCache: [String: URL] = [:]

    /// Retire du cache ET du disque la copie de lecture de cette pièce jointe.
    static func discardTemp(for id: String) {
        tempFileCacheLock.lock()
        let url = tempFileCache.removeValue(forKey: id)
        tempFileCacheLock.unlock()
        if let url { MediaPlaintextTemp.remove(url) }
    }

    private func saveToTemp() -> URL? {
        let key = attachment.id

        Self.tempFileCacheLock.lock()
        if let cached = Self.tempFileCache[key], FileManager.default.fileExists(atPath: cached.path) {
            Self.tempFileCacheLock.unlock()
            return cached
        }
        Self.tempFileCacheLock.unlock()

        // One directory per attachment: collision-proof, and it keeps the real
        // filename, which is what the user sees when they share or save.
        //
        // __MEDIA_AT_REST_2026_09_22__ Dans `MediaPlaintextTemp` (classe
        // `completeUnlessOpen`, sous-dossier unique, balayé au lancement
        // suivant) au lieu de `tmp/oshi-media/`, sans protection ni nettoyage.
        do {
            let tempURL = try MediaPlaintextTemp.write(attachment.data, fileName: attachment.fileName)
            Self.tempFileCacheLock.lock()
            Self.tempFileCache[key] = tempURL
            Self.tempFileCacheLock.unlock()
            return tempURL
        } catch {
            OshiLog.storage.info("❌ Failed to save temp file: \(error)")
            return nil
        }
    }
}

/// Full-screen video playback that owns its `AVPlayer` for the lifetime of the
/// view instead of minting a new one on every SwiftUI body evaluation.
struct FullScreenVideoPlayer: View {
    let url: URL
    @State private var player: AVPlayer?

    var body: some View {
        VideoPlayer(player: player)
            .onAppear {
                if player == nil { player = AVPlayer(url: url) }
                player?.play()
            }
            .onDisappear {
                player?.pause()
            }
    }
}

// ✅ NEW: Audio Player View
struct AudioPlayerView: View {
    let url: URL
    @State private var player: AVAudioPlayer?
    @State private var isPlaying = false
    @State private var currentTime: TimeInterval = 0
    @State private var duration: TimeInterval = 0
    /// __VOICE_ROUTE_2026_09_12__ Défaut: haut-parleur (c'était l'écouteur).
    /// Voir `VoiceMessageAudioRoute`.
    @State private var useSpeaker = true
    @AppStorage("audioPlaybackSpeed") private var playbackSpeed: Double = 1.0

    private var speedLabel: String {
        // 1.0 → "1x", 1.5 → "1.5x", 2.0 → "2x"
        if playbackSpeed == floor(playbackSpeed) {
            return "\(Int(playbackSpeed))x"
        }
        return String(format: "%.1fx", playbackSpeed)
    }

    var body: some View {
        VStack(spacing: 20) {
            // Progress bar
            if duration > 0 {
                VStack(spacing: 8) {
                    Slider(value: Binding(
                        get: { currentTime },
                        set: { newValue in
                            player?.currentTime = newValue
                            currentTime = newValue
                        }
                    ), in: 0...duration)
                    .tint(appBrandColor)

                    HStack {
                        Text(formatTime(currentTime))
                            .font(.caption)
                            .foregroundColor(.gray)
                        Spacer()
                        Text(formatTime(duration))
                            .font(.caption)
                            .foregroundColor(.gray)
                    }
                }
                .padding(.horizontal)
            }

            HStack(spacing: 30) {
                // Speaker toggle button
                Button {
                    useSpeaker.toggle()
                    configureAudioSession()
                } label: {
                    VStack(spacing: 4) {
                        Image(systemName: useSpeaker ? "speaker.wave.3.fill" : "speaker.fill")
                            .font(.scaled(32))
                            .foregroundColor(useSpeaker ? appBrandColor : .gray)

                        Text(useSpeaker ? "audio.speaker".localized : "audio.earpiece".localized)
                            .font(.caption2)
                            .foregroundColor(useSpeaker ? appBrandColor : .gray)
                    }
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
                }
                .accessibilityLabel("audio.output_route".localized)
                .accessibilityValue(useSpeaker ? "audio.speaker".localized : "audio.earpiece".localized)

                // Play/Pause button
                Button(action: togglePlayback) {
                    Image(systemName: isPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.scaled(64))
                        .foregroundColor(appBrandColor)
                }
                .accessibilityLabel(isPlaying ? "audio.pause".localized : "audio.play".localized)

                // Playback-speed toggle: cycles 1x → 1.5x → 2x → 1x
                Button(action: cyclePlaybackSpeed) {
                    VStack(spacing: 4) {
                        Text(speedLabel)
                            .font(.scaled(18, weight: .semibold))
                            .foregroundColor(appBrandColor)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 4)
                            .background(
                                Capsule().stroke(appBrandColor.opacity(0.6), lineWidth: 1.5)
                            )
                        Text("audio.speed".localized)
                            .font(.caption2)
                            .foregroundColor(.gray)
                    }
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
                }
                .accessibilityLabel("audio.playback_speed".localized)
                .accessibilityValue(speedLabel)
            }
        }
        .sensoryFeedback(.selection, trigger: isPlaying)
        .onAppear {
            setupPlayer()
        }
        .onDisappear {
            player?.stop()
        }
    }
    
    private func setupPlayer() {
        do {
            configureAudioSession()  // Set initial audio routing
            player = try AVAudioPlayer(contentsOf: url)
            player?.enableRate = true
            player?.rate = Float(playbackSpeed)
            player?.prepareToPlay()
            duration = player?.duration ?? 0
            
            Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { timer in
                if let player = player {
                    currentTime = player.currentTime
                    if !player.isPlaying && isPlaying {
                        isPlaying = false
                        timer.invalidate()
                    }
                }
            }
        } catch {
            OshiLog.storage.info("❌ Failed to setup audio player: \(error)")
        }
    }
    
    /// Routage centralisé — voir `VoiceMessageAudioRoute`. Idempotent: appelable
    /// au démarrage de la lecture comme à chaque bascule du bouton.
    private func configureAudioSession() {
        VoiceMessageAudioRoute.shared.setPrefersSpeaker(useSpeaker)
        VoiceMessageAudioRoute.shared.beginPlayback()
    }
    
    private func togglePlayback() {
        if isPlaying {
            player?.pause()
        } else {
            player?.rate = Float(playbackSpeed)
            player?.play()
        }
        isPlaying.toggle()
    }

    private func cyclePlaybackSpeed() {
        switch playbackSpeed {
        case 1.0: playbackSpeed = 1.5
        case 1.5: playbackSpeed = 2.0
        default:  playbackSpeed = 1.0
        }
        player?.rate = Float(playbackSpeed)
    }

    private func formatTime(_ time: TimeInterval) -> String {
        let minutes = Int(time) / 60
        let seconds = Int(time) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

// MARK: - Media Chat Bubble (for ChatView)

struct MediaChatBubble: View {
    /// One width for every media bubble. Fixed rather than flexible so the
    /// picture and its caption always occupy exactly the same box — a caption
    /// wider than the picture is what used to stretch the bubble and expose the
    /// accent fill beside the image.
    static let bubbleWidth: CGFloat = 250

    @ObservedObject private var bubbleStyle = BubbleStyleManager.shared
    /// TIMED REVEAL state, keyed by message id. Empty for every message today,
    /// so `presentation(for:)` returns nil and the bubble draws the plain photo.
    @ObservedObject private var revealStore = TimedRevealStore.shared
    @Environment(\.colorScheme) private var colorScheme

    let message: SecureMessage
    let isFromCurrentUser: Bool

    @EnvironmentObject var identityManager: IdentityManager
    @EnvironmentObject var messageManager: MessageManager

    @State private var attachment: MediaManager.MediaAttachment?
    @State private var isDecrypting = true

    /// Caption text extracted from the message (text sent alongside media)
    private var captionText: String? {
        if let plain = message.plaintextContent, !plain.isEmpty { return plain }
        return nil
    }

    var body: some View {
        if message.isVideoNote {
            videoNoteBody
        } else {
            standardBody
        }
    }

    /// __VIDEO_NOTE_2026_09_24__ A round video message: no bubble, no tail,
    /// just the circle and the time under it (docs/VIDEO_NOTE_SPEC.md §4). The
    /// long-press menu is the row's, exactly as for any other media.
    private var videoNoteBody: some View {
        HStack {
            if isFromCurrentUser { Spacer() }
            VStack(alignment: isFromCurrentUser ? .trailing : .leading, spacing: 5) {
                VideoNoteBubbleView(
                    messageId: message.id,
                    videoData: message.mediaAttachment ?? message.originalMediaData,
                    durationMs: message.durationMs,
                    isOutgoing: isFromCurrentUser
                )
                if let caption = captionText, !caption.isEmpty {
                    Text(caption)
                        .font(.subheadline)
                        .foregroundColor(.primary)
                        .frame(maxWidth: 240, alignment: isFromCurrentUser ? .trailing : .leading)
                }
                // (time, transport and ticks come from the row, as for any media)
            }
            if !isFromCurrentUser { Spacer() }
        }
    }

    // MARK: Media sizing (__BUBBLE_MEDIA_2026_11_21__)

    /// The kind of media this message carries, from the label or the bytes.
    private var visualKind: MediaManager.MediaType? {
        if message.isViewOnce || revealStore.presentation(for: message.id) != nil { return nil }
        let bytes = message.mediaAttachment ?? message.originalMediaData
        let declared = message.mediaType ?? bytes.flatMap {
            MediaManager.inferredType(from: $0, fileName: message.mediaFileName)
        }
        switch declared {
        case .image?:
            if let bytes, MediaManager.isGIFData(bytes) { return .gif }
            return .image
        case .gif?, .video?: return declared
        default: return nil
        }
    }

    /// Display size of the picture: from the image HEADER (known before the
    /// decrypt/decode finishes, so the placeholder already has the final
    /// size), from the poster frame for a video (16:9 until it exists).
    private var mediaDisplaySize: CGSize? {
        guard let kind = visualKind else { return nil }
        let maxW = BubbleMediaLayout.maxWidth(containerWidth: BubbleMediaLayout.screenWidth, channel: false)
        var natural = CGSize(width: 16, height: 9)
        if kind == .video {
            if let poster = attachment?.thumbnailData, let px = BubbleMediaLayout.pixelSize(of: poster) { natural = px }
        } else if let bytes = message.mediaAttachment ?? message.originalMediaData,
                  let px = BubbleMediaLayout.pixelSize(of: bytes) {
            natural = px
        }
        return BubbleMediaLayout.displaySize(natural: natural, maxWidth: maxW).size
    }

    /// Media-only picture: the time and ticks ride on it in a pill, and the row
    /// footer under the bubble is skipped (`ChatView` asks `showsTimePill`).
    static func showsTimePill(_ message: SecureMessage) -> Bool {
        guard !message.isVideoNote, !message.isViewOnce else { return false }
        if let caption = message.plaintextContent, !caption.isEmpty { return false }
        let bytes = message.mediaAttachment ?? message.originalMediaData
        let kind = message.mediaType ?? bytes.flatMap { MediaManager.inferredType(from: $0, fileName: message.mediaFileName) }
        guard let kind, kind == .image || kind == .gif || kind == .video else { return false }
        return message.editedAt == nil
    }

    private var standardBody: some View {
        let size = mediaDisplaySize
        let pill = size != nil && (captionText ?? "").isEmpty && !message.isViewOnce
        return HStack {
            if isFromCurrentUser { Spacer() }

            VStack(alignment: isFromCurrentUser ? .trailing : .leading, spacing: 5) {
                VStack(alignment: .leading, spacing: 0) {
                    mediaContent
                        .overlay(alignment: .bottomTrailing) {
                            if pill, !isDecrypting {
                                MediaTimePill(time: formatTimestamp(message.timestamp),
                                              status: isFromCurrentUser ? message.deliveryStatus : nil)
                            }
                        }
                    // Caption text under media (like group chat)
                    if let caption = captionText, !caption.isEmpty {
                        Text(caption)
                            .font(.subheadline)
                            .foregroundColor(isFromCurrentUser ? bubbleStyle.accent.onAccent : .primary)
                            .multilineTextAlignment(.leading)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                    }
                }
                // 🔧 FIX (2026-08-05): purple bands down both sides of a photo.
                //
                // The media rendered at a FIXED 200pt while the bubble was free
                // to grow to 260 for the caption. Any caption wider than the
                // image stretched the bubble, and the accent fill showed as two
                // vertical strips beside the picture — reading as a coloured
                // frame around it rather than as a bubble behind it.
                //
                // Darkening the strip would only make a mistake less visible.
                // The bubble is now a FIXED width and the media fills it edge to
                // edge, so there is nothing beside the picture to show through —
                // the same shape WhatsApp and Telegram use for a captioned photo.
                // __BUBBLE_MEDIA_2026_11_21__ the picture's own width (was a
                // fixed 250-pt square for every photo); other media keep 250.
                .frame(width: size?.width ?? MediaChatBubble.bubbleWidth)
                .background(isFromCurrentUser ? bubbleStyle.outgoingFill
                                              : bubbleStyle.incomingFill(colorScheme))
                // __BUBBLE_SHAPE_2026_08_18__ Same silhouette as a text bubble —
                // continuous corners, tail on the sender's side — so a photo is
                // not the one circular-cornered thing in a squircle thread.
                // A media bubble is always drawn as its own row, so it is always
                // a tail; it has no run to be in the middle of.
                //
                // `clipShape` rather than `bubbleSurface`: the picture itself has
                // to be cut to the corners, which a background cannot do.
                .clipShape(bubbleStyle.outline(outgoing: isFromCurrentUser, tail: true))
                .overlay(bubbleStyle.outline(outgoing: isFromCurrentUser, tail: true)
                    .stroke(bubbleStyle.rim(outgoing: isFromCurrentUser, colorScheme),
                            lineWidth: 0.5))
                .elevation(Theme.Elevation.low)
                // The row already prints time, transport and ticks under the
                // bubble; a picture no longer repeats "time • size" here.
                if size == nil { messageFooter }
            }

            if !isFromCurrentUser { Spacer() }
        }
        .task {
            // 🔧 FIX (2026-08-10, "media makes the conversation lag when you
            // come back to it"): `.task` re-runs every time LazyVStack
            // re-materializes this row — scrolling past and back, or leaving and
            // returning to the conversation. `loadMedia()` rebuilt the whole
            // attachment from the encrypted blob each time, with `isDecrypting`
            // reset to true, so the bubble flashed its placeholder and the main
            // thread paid for the decode again.
            //
            // A hit is synchronous, so the picture is there in the first frame
            // instead of after a decode.
            if let cached = MediaAttachmentCache.shared.get(for: message.id) {
                attachment = cached
                isDecrypting = false
                return
            }
            await loadMedia()
        }
    }
    
    @ViewBuilder
    private var mediaContent: some View {
        if isDecrypting {
            decryptingView
        } else if let attachment = attachment {
            MediaMessageView(
                attachment: attachment,
                isFromCurrentUser: isFromCurrentUser,
                messageId: message.id,
                senderName: isFromCurrentUser
                    ? "audio.you".localized
                    : MessageManager.resolveContactAlias(message.senderPublicKey),
                initialHasBeenViewed: message.hasBeenViewed,
                onViewOnceViewed: { messageId in
                    messageManager.markViewOnceAsViewed(messageId)
                },
                reveal: revealStore.presentation(for: message.id),
                displaySize: mediaDisplaySize
            )
        } else if message.isViewOnce && message.hasBeenViewed {
            // View-once media that was already viewed (media deleted)
            viewOnceViewedPlaceholder
        } else {
            errorView
        }
    }
    
    private var viewOnceViewedPlaceholder: some View {
        HStack(spacing: 8) {
            Image(systemName: "eye.slash.circle.fill")
                .foregroundColor(.gray)
            Text("media.view_once.viewed".localized)
                .font(.caption)
                .foregroundColor(.gray)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Color.gray.opacity(0.2))
        .clipShape(RoundedRectangle(cornerRadius: 15))
    }
    
    @ViewBuilder
    private var decryptingView: some View {
        if let size = mediaDisplaySize {
            // Same final size as the picture, so the list does not jump.
            Rectangle()
                .fill(Color.primary.opacity(0.06))
                .frame(width: size.width, height: size.height)
                .overlay(ProgressView())
        } else {
            decryptingRow
        }
    }

    private var decryptingRow: some View {
        HStack {
            ProgressView()
                .scaleEffect(0.8)
            Text(isFromCurrentUser ? "media.loading".localized : "media.decrypting".localized)
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding()
        .background(Color(.systemGray5))
        .cornerRadius(15)
    }
    
    private var errorView: some View {
        HStack {
            Image(systemName: "exclamationmark.triangle")
                .foregroundColor(.orange)
            Text("media.failed_load".localized)
                .font(.caption)
        }
        .padding()
        .background(Color(.systemGray6))
        .cornerRadius(15)
    }
    
    private var messageFooter: some View {
        HStack(spacing: 5) {
                Text(formatTimestamp(message.timestamp))
                    .font(.caption2)
                    .foregroundColor(.secondary)
                
                if let attachment = attachment {
                    Text("•")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                    
                    // ✅ Show compression info if available
                    if attachment.originalSize != nil,
                       let compressionRatio = attachment.compressionRatio,
                       attachment.isCompressed {
                        let saved = (1 - compressionRatio) * 100
                        Text(String(format: "Saved %.0f%%", saved))
                            .font(.caption2)
                            .foregroundColor(.green)
                    } else {
                        Text(attachment.fileSizeFormatted)
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                }
            }
    }
    
    private func loadMedia() async {
        OshiLog.storage.info("📱 MediaChatBubble loading media...")
        
        // __GIF_2026_09_12__ Récupérer le type À PARTIR DES OCTETS quand
        // l'étiquette manque.
        //
        // `mediaType` est décodé de façon tolérante depuis le 12/09: une valeur
        // inconnue tombe à nil au lieu de détruire tout l'historique. Excellent
        // pour la survie du magasin — mais ici le `guard` jetait alors une pièce
        // jointe DÉJÀ EN MÉMOIRE parce qu'il lui manquait une chaîne. Un GIF
        // venu d'un pair dont on ne connaît pas la version affichait une bulle
        // vide. Les octets, eux, disent toujours ce qu'ils sont.
        guard let mediaData = message.mediaAttachment,
              let declaredType = message.mediaType
                ?? MediaManager.inferredType(from: mediaData, fileName: message.mediaFileName) else {
            OshiLog.storage.info("❌ No media attachment found")
            await MainActor.run {
                isDecrypting = false
            }
            return
        }
        
        // Un GIF peut arriver étiqueté `image`: le chemin d'envoi LAN d'Android
        // lit son énumération au lieu de renifler les octets. Sans ce
        // redressement, l'export nomme le fichier `.jpg` et tout ce qui se fie
        // à l'extension perd l'animation.
        let mediaType: MediaManager.MediaType =
            (declaredType == .image && MediaManager.isGIFData(mediaData)) ? .gif : declaredType
        OshiLog.storage.info("   Media type: \(mediaType.rawValue)")
        OshiLog.storage.info("   Data size: \(mediaData.count) bytes")
        
        // ✅ Determine filename - use stored fileName or generate default
        let fileName: String
        if let storedFileName = message.mediaFileName, !storedFileName.isEmpty {
            fileName = storedFileName
            OshiLog.storage.info("   Using stored fileName: \(fileName)")
        } else {
            // Generate default filename based on type
            switch mediaType {
            case .audio:
                fileName = "audio_\(message.id.prefix(8)).m4a"
            case .image:
                fileName = "image_\(message.id.prefix(8)).jpg"
            // __GIF_2026_09_12__ Extension .gif, sinon le fichier exporté perd
            // son animation pour tout ce qui se fie à l'extension.
            case .gif:
                fileName = "image_\(message.id.prefix(8)).gif"
            case .video:
                fileName = "video_\(message.id.prefix(8)).mp4"
            case .document:
                // Try to detect document type from data
                let ext = detectDocumentExtension(from: mediaData)
                fileName = "document_\(message.id.prefix(8)).\(ext)"
            case .contact:
                fileName = "contact_\(message.id.prefix(8)).vcard"
            }
            OshiLog.storage.info("   Generated fileName: \(fileName)")
        }
        
        // 🎬 Generate a poster frame for videos so the bubble shows a real preview
        // + play button instead of the grey placeholder icon. Cached after the
        // first run; covers both received videos and re-rendered sent ones.
        var posterData: Data? = nil
        if mediaType == .video {
            posterData = await MediaManager().videoPosterFrame(forMessageId: message.id, videoData: mediaData)
        }

        // ✅ Create MediaAttachment from message data
        let loadedAttachment = MediaManager.MediaAttachment(
            id: message.id,
            type: mediaType,
            data: mediaData,
            thumbnailData: posterData,
            fileName: fileName,
            fileSize: mediaData.count,
            timestamp: message.timestamp,
            originalSize: nil,  // ✅ NEW: Required parameter
            compressionRatio: nil,  // ✅ NEW: Required parameter
            isCompressed: false,  // ✅ NEW: Required parameter
            isViewOnce: message.isViewOnce  // ✅ Pass view-once flag from message
        )
        
        await MainActor.run {
            MediaAttachmentCache.shared.set(loadedAttachment, for: message.id)
            attachment = loadedAttachment
            isDecrypting = false
        }

        OshiLog.storage.info("✅ Media loaded successfully")
    }
    
    /// Detect document extension from data header
    private func detectDocumentExtension(from data: Data) -> String {
        guard data.count > 4 else { return "bin" }
        
        let bytes = [UInt8](data.prefix(8))
        
        // PDF: %PDF
        if bytes[0] == 0x25 && bytes[1] == 0x50 && bytes[2] == 0x44 && bytes[3] == 0x46 {
            return "pdf"
        }
        
        // ZIP/DOCX/XLSX/PPTX: PK
        if bytes[0] == 0x50 && bytes[1] == 0x4B {
            // Check for Office XML formats by looking at file structure
            // Default to docx for now, could be improved
            return "docx"
        }
        
        // Plain text (ASCII/UTF8 check)
        let isText = bytes.allSatisfy { byte in
            (byte >= 0x20 && byte <= 0x7E) || byte == 0x0A || byte == 0x0D || byte == 0x09
        }
        if isText { return "txt" }
        
        return "bin"
    }
    
    private func formatTimestamp(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
    }
}

// MARK: - Missing View Components

/// Document message view for displaying document attachments
struct DocumentMessageView: View {
    let fileName: String
    let fileSize: Int
    let onTap: () -> Void
    
    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                Image(systemName: "doc.fill")
                    .font(.scaled(32))
                    .foregroundColor(appBrandColor)
                
                VStack(alignment: .leading, spacing: 4) {
                    Text(fileName)
                        .font(.subheadline)
                        .fontWeight(.medium)
                        .foregroundColor(.primary)
                        .lineLimit(2)
                    
                    Text(ByteCountFormatter.string(fromByteCount: Int64(fileSize), countStyle: .file))
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                
                Spacer()
                
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .padding()
            .background(Color(.systemGray6))
            .cornerRadius(12)
        }
        .buttonStyle(PlainButtonStyle())
    }
}

/// Contact card view for displaying shared contacts
struct ContactCardView: View {
    let contactCard: ContactCard
    let onStartChat: () -> Void
    
    var body: some View {
        VStack(spacing: 12) {
            // Avatar — show the shared contact's profile photo when available so
            // it's easy to recognise who's being shared; falls back to initials.
            ContactAvatarWithBadge(publicKey: contactCard.publicKey, size: 60, showBadge: false, displayName: contactCard.alias)

            // Contact info
            VStack(spacing: 4) {
                if let alias = contactCard.alias {
                    Text(alias)
                        .font(.headline)
                        .foregroundColor(.primary)
                }
                
                Text(contactCard.publicKey.prefix(12) + "...")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }
            
            // Start chat button
            Button(action: onStartChat) {
                HStack {
                    Image(systemName: "message.fill")
                    Text("contact.start_chat".localized)
                }
                .font(.subheadline)
                .fontWeight(.medium)
                .foregroundColor(.white)
                .padding(.horizontal, 20)
                .padding(.vertical, 10)
                .background(appBrandColor)
                .cornerRadius(20)
            }
        }
        .padding()
        .background(Color(.systemGray6))
        .cornerRadius(16)
    }
}


// MARK: - OSHI Share Activity
class OSHIShareActivity: UIActivity {
    private var fileURL: URL?
    private var onShare: ((URL) -> Void)?
    
    convenience init(onShare: @escaping (URL) -> Void) {
        self.init()
        self.onShare = onShare
    }
    
    override var activityType: UIActivity.ActivityType? {
        return UIActivity.ActivityType("com.oshi.share")
    }
    
    override var activityTitle: String? {
        return "Share via OSHI"
    }
    
    override var activityImage: UIImage? {
        // Use OSHI app icon or a custom image
        return UIImage(systemName: "message.fill")?.withTintColor(UIColor(red: 0.337, green: 0.086, blue: 0.925, alpha: 1.0), renderingMode: .alwaysOriginal)
    }
    
    override func canPerform(withActivityItems activityItems: [Any]) -> Bool {
        // Check if there's a file URL
        return activityItems.contains { $0 is URL }
    }
    
    override func prepare(withActivityItems activityItems: [Any]) {
        // Get the file URL
        fileURL = activityItems.compactMap { $0 as? URL }.first
    }
    
    override func perform() {
        guard let fileURL = fileURL else {
            activityDidFinish(false)
            return
        }
        
        // Call the share handler
        onShare?(fileURL)
        activityDidFinish(true)
    }
}

// MARK: - View Once Full Screen View

struct ViewOnceFullScreenView: View {
    @Environment(\.dismiss) var dismiss
    let attachment: MediaManager.MediaAttachment
    var isFromCurrentUser: Bool = false  // ✅ NEW: Track if sender or receiver
    var onDismiss: (() -> Void)? = nil
    
    @State private var timeRemaining = 5  // ✅ Auto-close after 5 seconds for images
    @State private var showContent = false
    @State private var timer: Timer?
    
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            
            if showContent {
                // Show media content
                VStack {
                    // Timer indicator for images
                    if attachment.type == .image {
                        HStack {
                            Spacer()
                            HStack(spacing: 6) {
                                Image(systemName: "timer")
                                Text("\(timeRemaining)s")
                                    .monospacedDigit()
                            }
                            .font(.headline)
                            .foregroundColor(.white)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                            .background(Color.black.opacity(0.5))
                            .clipShape(Capsule())
                            .padding()
                        }
                    }
                    
                    Spacer()
                    
                    // Media content (safe downsampled loading)
                    if attachment.type == .image, let image = MediaAttachmentCache.shared.decodedImage(from: attachment.data, id: attachment.id, maxDimension: 1920) {
                        Image(uiImage: image)
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else if attachment.type == .video {
                        ViewOnceVideoPlayer(data: attachment.data) {
                            closeAndNotify()
                        }
                    } else if attachment.type == .audio {
                        ViewOnceAudioPlayer(data: attachment.data) {
                            closeAndNotify()
                        }
                    }
                    
                    Spacer()
                    
                    // View once indicator
                    HStack(spacing: 8) {
                        Image(systemName: "eye.slash.circle.fill")
                        Text("media_picker.view_once".localized)
                    }
                    .font(.subheadline)
                    .foregroundColor(.white.opacity(0.7))
                    .padding(.bottom, 30)
                }
            } else {
                // Confirm to view
                VStack(spacing: 24) {
                    Image(systemName: "eye.slash.circle.fill")
                        .font(.scaled(80))
                        .foregroundColor(.orange)
                    
                    Text("media_picker.view_once".localized)
                        .font(.title2)
                        .fontWeight(.bold)
                        .foregroundColor(.white)
                    
                    Text("media_picker.view_once.warning".localized)
                        .font(.subheadline)
                        .foregroundColor(.white.opacity(0.7))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 40)
                    
                    // Show different message for sender vs receiver
                    if isFromCurrentUser {
                        Text("media.view_once.sender_warning".localized)
                            .font(.caption)
                            .foregroundColor(.orange.opacity(0.8))
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 40)
                    }
                    
                    Button {
                        withAnimation {
                            showContent = true
                            startTimer()
                        }
                    } label: {
                        Text("media.view_once.tap_to_view".localized)
                            .font(.headline)
                            .foregroundColor(.black)
                            .padding(.horizontal, 32)
                            .padding(.vertical, 14)
                            .background(Color.orange)
                            .clipShape(Capsule())
                    }
                    .padding(.top, 20)
                }
            }
            
            // Close button
            VStack {
                HStack {
                    Button {
                        closeAndNotify()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title)
                            .foregroundColor(.white.opacity(0.8))
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel("common.close".localized)
                    .padding()
                    Spacer()
                }
                Spacer()
            }
        }
        .onDisappear {
            timer?.invalidate()
            if showContent {
                onDismiss?()
            }
        }
        .interactiveDismissDisabled(showContent)  // Prevent accidental dismiss while viewing
    }
    
    private func startTimer() {
        guard attachment.type == .image else { return }
        
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
            if timeRemaining > 1 {
                timeRemaining -= 1
            } else {
                closeAndNotify()
            }
        }
    }
    
    private func closeAndNotify() {
        timer?.invalidate()
        onDismiss?()
        dismiss()
    }
}

// MARK: - View Once Audio Player

struct ViewOnceAudioPlayer: View {
    let data: Data
    var onFinished: (() -> Void)? = nil

    // __VOICE_PREVIEW_2026_09_29__ Deux défauts corrigés en le refaisant:
    //  • un `AVAudioPlayer` nu, sans session: catégorie par défaut `.soloAmbient`,
    //    donc MUET quand l'iPhone est en silencieux — on écoutait « une fois »
    //    un message qu'on n'entendait pas. Il passe désormais par
    //    `AudioPlayerManager` (route haut-parleur/écouteur de tous les vocaux);
    //  • la fin était guettée par `currentTime >= duration - 0.1`, or
    //    `AVAudioPlayer` remet `currentTime` à 0 en finissant: `onFinished`
    //    n'était jamais appelé. On lit la bascule de `isPlaying`.
    @StateObject private var player = AudioPlayerManager()
    @State private var duration: TimeInterval = 0
    @State private var levels: [Float] = []
    @State private var started = false

    private var progress: Double {
        guard duration > 0 else { return 0 }
        return min(1, player.currentTime / duration)
    }

    var body: some View {
        VStack(spacing: 28) {
            ZStack {
                Circle()
                    .stroke(Color.white.opacity(0.12), lineWidth: 6)
                Circle()
                    .trim(from: 0, to: progress)
                    .stroke(AngularGradient(colors: [.orange, .pink, .orange], center: .center),
                            style: StrokeStyle(lineWidth: 6, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(.linear(duration: 0.1), value: progress)
                Image(systemName: "waveform")
                    .font(.scaled(54, weight: .semibold))
                    .foregroundStyle(.orange)
                    .symbolEffect(.variableColor.iterative, isActive: player.isPlaying)
            }
            .frame(width: 170, height: 170)

            HStack(alignment: .center, spacing: 3) {
                ForEach(Array(levels.enumerated()), id: \.offset) { i, level in
                    Capsule()
                        .fill(Double(i) / Double(max(levels.count, 1)) < progress
                              ? Color.orange : Color.white.opacity(0.25))
                        .frame(width: 3, height: max(4, CGFloat(level) * 36))
                }
            }
            .frame(height: 40)
            .padding(.horizontal, 30)

            Text(formatTime(max(0, duration - player.currentTime)))
                .font(.title3.weight(.semibold))
                .foregroundColor(.white)
                .monospacedDigit()
                .contentTransition(.numericText(countsDown: true))

            Label("media.view_once.listening".localized, systemImage: "1.circle.fill")
                .font(.subheadline)
                .foregroundColor(.white.opacity(0.7))
        }
        .onAppear {
            duration = (try? AVAudioPlayer(data: data))?.duration ?? 0
            levels = AudioRecorderManager.AudioAttachment.generateWaveform(from: data)
            player.play(data)
            started = true
        }
        .onChange(of: player.isPlaying) { _, playing in
            if started && !playing { onFinished?() }
        }
        .onDisappear {
            started = false   // la fermeture n'est pas une « fin d'écoute »
            player.stop()
        }
    }

    private func formatTime(_ time: TimeInterval) -> String {
        let minutes = Int(time) / 60
        let seconds = Int(time) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

// MARK: - View Once Video Player

struct ViewOnceVideoPlayer: View {
    let data: Data
    var onFinished: (() -> Void)? = nil
    
    @State private var player: AVPlayer?
    @State private var tempURL: URL?  // __MEDIA_AT_REST_2026_09_22__

    var body: some View {
        ZStack {
            if let player = player {
                VideoPlayer(player: player)
                    .onAppear {
                        player.play()
                    }
                    .onDisappear {
                        player.pause()
                    }
            } else {
                ProgressView()
                    .tint(.white)
            }
        }
        .onAppear {
            setupPlayer()
        }
        .onDisappear {
            // __MEDIA_AT_REST_2026_09_22__ Un média « vue unique » ne laisse
            // AUCUNE copie en clair derrière lui (avant: tmp/<uuid>.mp4, jamais effacé).
            player?.pause()
            player = nil
            if let url = tempURL { MediaPlaintextTemp.remove(url); tempURL = nil }
        }
    }

    private func setupPlayer() {
        guard player == nil else { return }
        do {
            let tempURL = try MediaPlaintextTemp.write(data, fileName: "video.mp4")
            self.tempURL = tempURL
            let avPlayer = AVPlayer(url: tempURL)
            
            // Observe when video ends
            NotificationCenter.default.addObserver(
                forName: .AVPlayerItemDidPlayToEndTime,
                object: avPlayer.currentItem,
                queue: .main
            ) { _ in
                onFinished?()
            }
            
            player = avPlayer
        } catch {
            OshiLog.storage.info("❌ Error creating video player: \(error)")
        }
    }
}

// MARK: - Inline animated voice note (Telegram/WhatsApp-style)

/// A voice-note bubble that plays INLINE (no full-screen jump), with a waveform
/// that fills as it plays and a live equalizer shimmer while playing — the
/// "background animation for more life". The waveform is synthesised
/// deterministically from the audio bytes, so every note has its own stable
/// shape without needing waveform metadata on the wire.
struct InlineVoiceNoteView: View {
    let audioData: Data
    let isFromCurrentUser: Bool
    /// __VOICE_MINIPLAYER_2026_10_09__ Identity of the note inside the shared
    /// player. Defaulted so any call site that predates the mini player still
    /// compiles; it then falls back to a per-view identity, which only costs
    /// the cross-screen resume.
    var noteID: String = UUID().uuidString
    /// Shown by the floating player once the user has left this conversation.
    var noteTitle: String = ""

    // The player is NOT owned here any more. A voice note has to survive the
    // bubble that started it — leaving the chat used to cut it off mid-sentence
    // because this view did `.onDisappear { player.stop() }` on its own player.
    @ObservedObject private var player = VoiceNotePlayback.shared
    @State private var duration: TimeInterval = 0
    @State private var bars: [CGFloat] = []
    /// __VOICE_SCRUB_2026_10_26__ Where the finger is while dragging the
    /// waveform. The displayed progress follows THIS while it is non-nil, so the
    /// head sticks to the finger instead of being yanked back by the next tick
    /// of the player's 0.1 s ticker.
    @State private var scrubProgress: Double?
    /// nil until the drag has declared itself: true = horizontal, this is a
    /// scrub; false = vertical, the conversation keeps the gesture.
    @State private var isScrubbing: Bool?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private let barCount = 34
    /// The waveform strip is a fixed width, so a touch's x maps straight to a
    /// position without a GeometryReader around the gesture.
    private let waveWidth: CGFloat = 130

    // __BUBBLE_MEDIA_2026_11_21__ violet on the light lavender bubble, white
    // on the dark purple one (and on every other accent).
    private var accent: Color { isFromCurrentUser ? BubbleStyleManager.shared.accent.accentInk : appBrandColor }
    /// True only while THIS note is the one loaded in the shared player.
    private var isMine: Bool { player.isActive(noteID) }
    private var isPlaying: Bool { isMine && player.isPlaying }
    private var progress: Double {
        if let scrubProgress { return scrubProgress }
        guard isMine, duration > 0 else { return 0 }
        return min(1, max(0, player.currentTime / duration))
    }

    var body: some View {
        HStack(spacing: 10) {
            // Play / pause — morphs, presses, and gives a light haptic.
            Button {
                #if os(iOS)
                Haptics.tap(.light)
                #endif
                if isPlaying { player.pause() } else { startPlayback() }
            } label: {
                Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                    .font(.scaled(15, weight: .bold))
                    .foregroundColor(isFromCurrentUser ? (BubbleStyleManager.shared.accent == .oshi ? Color(ChatPalette.outgoingGlyphOnAccentUI) : appBrandColor) : .white)
                    .frame(width: 36, height: 36)
                    .background(Circle().fill(isFromCurrentUser ? accent : appBrandColor))
                    .contentTransition(.opacity)   // iOS 16-safe crossfade
                    .animation(.easeInOut(duration: 0.15), value: isPlaying)
            }
            .buttonStyle(PressScaleButtonStyle())
            .accessibilityLabel(isPlaying ? "audio.pause".localized : "audio.play".localized)

            waveform
                .frame(width: waveWidth, height: 28)
                // A touch anywhere on the strip must count, not only on a bar.
                .contentShape(Rectangle())
                // Tap = "play from here". A SpatialTapGesture gives the location
                // AND fails the moment the finger travels, so scrolling the
                // conversation over a waveform still scrolls it — the lesson of
                // __PHOTO_OPENS_ON_SCROLL_2026_08_24__.
                .gesture(
                    SpatialTapGesture()
                        .onEnded { v in
                            #if os(iOS)
                            Haptics.tap(.light)
                            #endif
                            seek(toX: v.location.x, startIfIdle: true)
                        }
                )
                // Drag = scrub. 8 pt before anything happens, and only if the
                // movement is more horizontal than vertical: a vertical pan is
                // the user scrolling, and it must stay the scroll view's.
                .gesture(
                    DragGesture(minimumDistance: 8)
                        .onChanged { v in
                            if isScrubbing == nil {
                                isScrubbing = abs(v.translation.width) > abs(v.translation.height)
                            }
                            guard isScrubbing == true else { return }
                            let p = clampedProgress(forX: v.location.x)
                            scrubProgress = p
                            // Only moves a note that is already loaded; a scrub
                            // must not seize the audio route on its own.
                            seek(to: p, startIfIdle: false)
                        }
                        .onEnded { v in
                            let wasScrubbing = isScrubbing == true
                            isScrubbing = nil
                            scrubProgress = nil
                            guard wasScrubbing else { return }
                            // Releasing a scrub leaves the note as it was —
                            // paused stays paused, playing keeps playing.
                            seek(toX: v.location.x, startIfIdle: false)
                        }
                )
                .accessibilityElement()
                .accessibilityLabel("audio.voice_message".localized)
                .accessibilityValue(timeLabel)
                .accessibilityAdjustableAction { direction in
                    // VoiceOver swipe up/down: ±5 s, the one way to scrub
                    // without being able to see the strip.
                    guard duration > 0 else { return }
                    let step = 5.0 / duration
                    switch direction {
                    case .increment: seek(to: progress + step, startIfIdle: false)
                    case .decrement: seek(to: progress - step, startIfIdle: false)
                    @unknown default: break
                    }
                }

            Text(timeLabel)
                .font(.caption2)
                .monospacedDigit()
                .foregroundColor(accent.opacity(0.9))
                .frame(minWidth: 34, alignment: .leading)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .task { await prepare() }
        // Tells the shared player this bubble is on screen, so the floating
        // mini player stays hidden while its own controls are reachable — and
        // appears the moment the user scrolls it away or leaves the chat.
        .onAppear { player.ownerAppeared(noteID) }
        .onDisappear { player.ownerDisappeared(noteID) }
    }

    private var waveform: some View {
        // Continuous shimmer only while playing; paused (and Reduce Motion) makes
        // it a static, crisp waveform. TimelineView drives the live oscillation.
        TimelineView(.animation(minimumInterval: 1.0 / 30.0,
                                paused: !isPlaying || reduceMotion)) { timeline in
            let t = timeline.date.timeIntervalSinceReferenceDate
            GeometryReader { geo in
                HStack(alignment: .center, spacing: 2) {
                    ForEach(bars.indices, id: \.self) { i in
                        let frac = bars.count > 1 ? Double(i) / Double(bars.count - 1) : 0
                        let played = frac <= progress
                        let osc: CGFloat = (isPlaying && !reduceMotion)
                            ? 0.82 + 0.18 * CGFloat(abs(sin(t * 3.2 + Double(i) * 0.55)))
                            : 1.0
                        Capsule()
                            .fill(accent.opacity(played ? 1.0 : 0.35))
                            .frame(width: 2.5, height: max(3, bars[i] * geo.size.height * osc))
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
                // __VOICE_SCRUB_2026_10_26__ The grab handle. Without something
                // to aim at, a waveform does not look like it can be moved —
                // which is why nobody tried dragging it. Only drawn once the
                // note is the loaded one, so an untouched bubble stays clean.
                .overlay(alignment: .leading) {
                    if isMine || scrubProgress != nil {
                        Circle()
                            .fill(accent)
                            .frame(width: knobSize, height: knobSize)
                            .shadow(color: .black.opacity(0.25), radius: 1, y: 0.5)
                            .offset(x: CGFloat(progress) * geo.size.width - knobSize / 2)
                            .allowsHitTesting(false)
                    }
                }
            }
        }
        .animation(.easeOut(duration: 0.15), value: progress)
    }

    /// Grows under the finger, the way a slider thumb does.
    private var knobSize: CGFloat { scrubProgress == nil ? 7 : 11 }

    private var timeLabel: String {
        // __VOICE_SCRUB_2026_10_26__ Elapsed while the note is loaded, total when
        // it is not — Telegram's reading, and the only one that makes a scrub
        // legible: a counter that went DOWN while the finger moved right said the
        // opposite of what the finger was doing.
        let shown: TimeInterval
        if let scrubProgress {
            shown = scrubProgress * duration
        } else if isMine, duration > 0 {
            shown = min(duration, player.currentTime)
        } else {
            shown = duration
        }
        let s = Int(shown.rounded())
        return String(format: "%d:%02d", s / 60, s % 60)
    }

    // MARK: - Scrubbing

    /// x inside the strip → 0…1, clamped at both ends.
    private func clampedProgress(forX x: CGFloat) -> Double {
        guard waveWidth > 0 else { return 0 }
        return min(1, max(0, Double(x / waveWidth)))
    }

    private func seek(toX x: CGFloat, startIfIdle: Bool) {
        seek(to: clampedProgress(forX: x), startIfIdle: startIfIdle)
    }

    private func seek(to p: Double, startIfIdle: Bool) {
        player.seek(id: noteID,
                    title: noteTitle.isEmpty ? "audio.voice_message".localized : noteTitle,
                    data: audioData,
                    toProgress: p,
                    startIfIdle: startIfIdle)
    }

    private func startPlayback() {
        player.play(id: noteID,
                    title: noteTitle.isEmpty
                        ? "audio.voice_message".localized
                        : noteTitle,
                    data: audioData)
    }

    private func prepare() async {
        // Duration from the decoded player; bars synthesised from the bytes.
        // __CHAT_OPEN_PERF_2026_11_14__ `AVAudioPlayer(data:)` parses the whole
        // file; it ran on the main actor for every voice-note row that
        // appeared. Now off main, once per note.
        bars = Self.makeBars(from: audioData, count: barCount)
        if let d = await VoiceNoteDurationCache.duration(id: noteID, data: audioData) { duration = d }
    }

    /// Deterministic 0.25…1.0 bar heights from the audio bytes — stable per note,
    /// pleasant distribution, no decoding required.
    static func makeBars(from data: Data, count: Int) -> [CGFloat] {
        guard !data.isEmpty else { return Array(repeating: 0.4, count: count) }
        let stride = max(1, data.count / count)
        var out: [CGFloat] = []
        out.reserveCapacity(count)
        var idx = 0
        for _ in 0..<count {
            let b = data[data.index(data.startIndex, offsetBy: min(idx, data.count - 1))]
            // Map byte → 0.25…1.0 with a gentle curve so bars look organic.
            let n = CGFloat(b) / 255.0
            out.append(0.25 + 0.75 * (0.3 + 0.7 * n))
            idx += stride
        }
        return out
    }
}
