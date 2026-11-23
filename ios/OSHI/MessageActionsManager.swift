//
//  MessageActionsManager.swift
//  OSHI - Message Forwarding & Reply/Quote
//
//  Enables WhatsApp-style message actions:
//  - Forward messages to other contacts
//  - Reply to specific messages with quote
//

import Foundation
import SwiftUI

// MARK: - Message Privacy

/// One switch, read from everywhere that can put message text somewhere the
/// sender did not choose: the Forward and Copy actions, and the Copy button on
/// the translate result.
///
/// What it is: a restriction on THIS device. It removes the paths the app
/// offers for taking a message out of its conversation.
///
/// What it is NOT: a guarantee about the person you are talking to. They hold
/// the plaintext once it is delivered — they can screenshot it, photograph the
/// screen, or retype it, and no messenger can prevent that. The setting is
/// worded to promise only what it can keep.
///
/// Off by default: it removes familiar actions, so it is opt-in, never a
/// surprise. `UserDefaults.bool(forKey:)` returns false when the key was never
/// written, which is exactly the default we want and needs no registration.
enum MessagePrivacy {
    static let blockForwardAndCopyKey = "blockForwardAndCopy"

    static var blockForwardAndCopy: Bool {
        UserDefaults.standard.bool(forKey: blockForwardAndCopyKey)
    }
}

// MARK: - Reply/Quote Message

struct ReplyMessage: Codable, Equatable {
    let originalMessageId: String
    let originalSenderKey: String
    let originalText: String
    let originalTimestamp: Date
    let originalMediaType: String?  // "image", "video", "audio", "document", "location"
    
    // Truncate long messages for preview
    //
    // 🔧 RAW-JSON FIX: this property is the choke point for BOTH reply surfaces —
    // the banner above the composer (`ReplyPreviewView`) and the quote block
    // rendered inside the sent bubble (`QuotedMessageView`). It used to return
    // `originalText` verbatim, so swiping to reply on a call summary, a
    // location, a reaction or another reply quoted the payload's raw JSON.
    //
    // Sanitising happens BOTH here (render time — covers messages already on the
    // wire and payloads from the Android client) and at construction time in
    // ChatView/GroupViews (so the JSON never travels inside the reply envelope
    // to the other device in the first place).
    /// Short label for the KIND of media this quote points at, or nil when the
    /// quoted message is plain text.
    ///
    /// 🔧 FIX (2026-08-12, "a reply to a bot post with a link + photo says
    /// «Fichier»"): there are TWO `MediaType` enums in this project and they
    /// disagree on the spelling of a picture — `MediaManager.MediaType.image`
    /// (1:1) versus `MediaType.photo` (groups, GroupMessaging.swift:376). This
    /// switch only knew `"image"`, so EVERY quoted group photo — which is what
    /// a bot post is — missed every case and fell through to the `default`
    /// branch, printing "📎 File". Both spellings are matched now; neither enum
    /// is renamed, because both are persisted on disk and on the wire.
    var mediaLabel: String? {
        guard let mediaType = originalMediaType else { return nil }
        switch mediaType {
        case "image", "photo": return "📷 " + "media_image".localized
        case "video":          return "🎥 " + "media_video".localized
        case "audio":          return "🎤 " + "media_audio".localized
        case "document":       return "📄 " + "media_document".localized
        case "location":       return "📍 " + "location.shared_location".localized
        default:               return "📎 " + "media_file".localized
        }
    }

    /// True when this quote points at something a thumbnail can be drawn for.
    var quotesAPicture: Bool {
        originalMediaType == "image" || originalMediaType == "photo"
    }

    /// The words the quoted message actually carried — its caption, or its
    /// body when it was plain text. Empty for media sent with no caption.
    ///
    /// Sanitised through the one control-payload catalog, and truncated here so
    /// a quote of a 4000-character message never grows a bubble.
    var bodyText: String {
        let readable = OSHIControlPayload.previewText(for: originalText)
            .trimmingCharacters(in: .whitespacesAndNewlines)

        // __REPLY_QUOTE_NO_RAW_JSON_2026_08_17__ Dernier filet, pour la CITATION
        // seulement. Le catalogue résume tout ce qu'il connaît — payloads de
        // contrôle, media JSON, enveloppes — et rend la prose telle quelle. Un
        // objet JSON qu'il ne connaît PAS (un futur payload de bot, un client
        // tiers, un schéma qui a bougé) retombe donc sur `?? text`, et la
        // citation affiche des accolades.
        //
        // Ici, et pas dans le catalogue: une citation est un aperçu d'une ligne,
        // où « Message chiffré » vaut mieux qu'un mur de JSON. Dans la BULLE, au
        // contraire, quelqu'un qui colle volontairement du JSON doit voir son
        // message — l'effacer là serait une régression.
        if readable.hasPrefix("{"), readable.hasSuffix("}") {
            return mediaLabel ?? "notification.encrypted_message".localized
        }

        if readable.count > 100 {
            return String(readable.prefix(100)) + "..."
        }
        return readable
    }

    /// One line for surfaces that show NO thumbnail.
    ///
    /// 🔧 Same fix: the media branch used to `return` immediately and throw
    /// `originalText` away, so quoting a photo that was sent WITH a caption (a
    /// bot post is a picture plus a link) lost the caption entirely — the user
    /// saw a bare file label where the link should have been. The caption is
    /// kept alongside the label now.
    var previewText: String {
        guard let label = mediaLabel else { return bodyText }
        let body = bodyText
        // `body == label` happens when the caller already ran the raw payload
        // through the catalog before building the quote (both 1:1 reply paths
        // do); without this the quote would read "📷 Image 📷 Image".
        if body.isEmpty || body == label { return label }
        return "\(label) \(body)"
    }
}

// MARK: - Message with Reply

struct MessageWithReply: Codable {
    static let replyPrefix = "💬REPLY💬"
    
    let content: String
    let replyTo: ReplyMessage?
    
    func toMessageString() -> String {
        guard replyTo != nil else {
            return content
        }
        
        guard let data = try? JSONEncoder().encode(self),
              let json = String(data: data, encoding: .utf8) else {
            return content
        }
        return "\(Self.replyPrefix)\(json)"
    }
    
    static func fromMessageString(_ string: String) -> MessageWithReply? {
        guard string.hasPrefix(replyPrefix) else {
            // Plain message without reply
            return MessageWithReply(content: string, replyTo: nil)
        }
        
        let jsonString = String(string.dropFirst(replyPrefix.count))
        guard let data = jsonString.data(using: .utf8),
              let message = try? JSONDecoder().decode(MessageWithReply.self, from: data) else {
            return nil
        }
        return message
    }
}

// MARK: - Forward Message Info

struct ForwardedMessage: Codable {
    static let forwardPrefix = "➡️FORWARDED➡️"
    
    let originalSenderName: String?
    let content: String
    let isForwarded: Bool
    let forwardCount: Int  // How many times forwarded
    
    func toMessageString() -> String {
        guard let data = try? JSONEncoder().encode(self),
              let json = String(data: data, encoding: .utf8) else {
            return content
        }
        return "\(Self.forwardPrefix)\(json)"
    }
    
    static func fromMessageString(_ string: String) -> ForwardedMessage? {
        guard string.hasPrefix(forwardPrefix) else {
            return nil
        }
        
        let jsonString = String(string.dropFirst(forwardPrefix.count))
        guard let data = jsonString.data(using: .utf8),
              let message = try? JSONDecoder().decode(ForwardedMessage.self, from: data) else {
            return nil
        }
        return message
    }
}

// MARK: - Message Actions Manager

class MessageActionsManager: ObservableObject {
    static let shared = MessageActionsManager()
    
    // Currently selected message for reply
    @Published var replyingToMessage: ReplyMessage?
    
    // Messages to forward
    @Published var messagesToForward: [String] = []  // Message IDs
    @Published var isSelectingForForward = false
    
    private init() {
        OshiLog.messaging.info("💬 MessageActionsManager initialized")
    }
    
    // MARK: - Reply Actions
    
    /// Start replying to a message
    func startReply(to message: ReplyMessage) {
        DispatchQueue.main.async {
            self.replyingToMessage = message
        }
        
        // Haptic feedback
        let generator = UIImpactFeedbackGenerator(style: .light)
        generator.impactOccurred()
        
        OshiLog.messaging.info("💬 Replying to message: \(message.originalMessageId.prefix(8))...")
    }
    
    /// Create reply from SecureMessage
    func createReply(from messageId: String, senderKey: String, text: String, timestamp: Date, mediaType: String? = nil) -> ReplyMessage {
        return ReplyMessage(
            originalMessageId: messageId,
            originalSenderKey: senderKey,
            originalText: text,
            originalTimestamp: timestamp,
            originalMediaType: mediaType
        )
    }
    
    /// Cancel current reply
    func cancelReply() {
        replyingToMessage = nil
    }
    
    /// Build message content with reply
    func buildMessageWithReply(content: String) -> String {
        guard let reply = replyingToMessage else {
            return content
        }
        
        let messageWithReply = MessageWithReply(content: content, replyTo: reply)
        let result = messageWithReply.toMessageString()
        
        // Clear reply after building
        cancelReply()
        
        return result
    }
    
    // MARK: - Forward Actions
    
    /// Start forward selection mode
    func startForwardSelection() {
        isSelectingForForward = true
        messagesToForward.removeAll()
    }
    
    /// Toggle message selection for forward
    func toggleMessageForForward(_ messageId: String) {
        if messagesToForward.contains(messageId) {
            messagesToForward.removeAll { $0 == messageId }
        } else {
            messagesToForward.append(messageId)
        }
    }
    
    /// Cancel forward selection
    func cancelForward() {
        isSelectingForForward = false
        messagesToForward.removeAll()
    }
    
    /// Create forwarded message content
    /// - Note: `originalContent` is sanitised through `OSHIControlPayload` by the
    ///   call sites, so forwarding a call summary forwards "Outgoing call • 1:23"
    ///   and not the sentinel payload, which the recipient's client would then
    ///   try to route as a control message.
    func createForwardedMessage(originalContent: String, originalSenderName: String?, forwardCount: Int = 1) -> String {
        // Check if already forwarded
        if let existing = ForwardedMessage.fromMessageString(originalContent) {
            // Increment forward count
            let updated = ForwardedMessage(
                originalSenderName: existing.originalSenderName,
                content: existing.content,
                isForwarded: true,
                forwardCount: existing.forwardCount + 1
            )
            return updated.toMessageString()
        }
        
        // New forward
        let forwarded = ForwardedMessage(
            originalSenderName: originalSenderName,
            content: originalContent,
            isForwarded: true,
            forwardCount: forwardCount
        )
        return forwarded.toMessageString()
    }
    
    /// Check if message is forwarded
    func isForwarded(_ content: String) -> Bool {
        return ForwardedMessage.fromMessageString(content) != nil
    }
    
    /// Get forward info
    func getForwardInfo(_ content: String) -> ForwardedMessage? {
        return ForwardedMessage.fromMessageString(content)
    }
}

// MARK: - Reply Preview View (shown above text input)

struct ReplyPreviewView: View {
    let reply: ReplyMessage
    let contactAlias: String?
    let onCancel: () -> Void
    
    private let appBrandColor = Theme.brand
    
    // 🎨 __REPLY_STRIP_2026_08_18__ Third attempt, and the first two were wrong in
    // the same way: they kept treating this as a PANEL.
    //
    //   1. `systemGray6` full-bleed band, square corners — a grey rectangle.
    //   2. A bordered `.ultraThinMaterial` card — still a box, just a prettier one.
    //
    // What a 2026 messenger actually shows is not a panel at all: it is a short
    // strip that belongs to the COMPOSER, sharing its insets and its corner
    // language, so the eye reads "the field grew a header" rather than "a dialog
    // appeared". Concretely, versus attempt 2:
    //
    //   • ONE line of quoted text, not two — this alone halves the height, and two
    //     lines was the single biggest reason it read as a box;
    //   • no border and no material — a brand-tinted fill, so it looks like part of
    //     the input rather than a floating surface with an edge;
    //   • the same horizontal inset as the input row, and a 2pt gap to it, so the
    //     two shapes read as one stack;
    //   • 32pt thumbnail, tighter padding, smaller close button.
    var body: some View {
        HStack(spacing: 10) {
            Capsule(style: .continuous)
                .fill(appBrandColor)
                .frame(width: 3)

            ReplyQuoteBody(
                reply: reply,
                title: contactAlias ?? formatAddress(reply.originalSenderKey),
                // __REPLY_QUOTE_ON_THE_WALLPAPER_2026_11_09__ dynamic quote
                // tokens (raw brand violet is ~2:1 on a dark composer).
                titleColor: ChatPalette.quoteAccent,
                textColor: ChatPalette.quoteBody,
                font: .caption2,
                bodyLines: 1,
                thumbnailSide: 32
            )
            .frame(maxWidth: .infinity, alignment: .leading)

            Button(action: onCancel) {
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(.secondary)
                    .frame(width: 22, height: 22)
                    .background(Circle().fill(Color.primary.opacity(0.07)))
                    // Small on screen, 44pt to the finger.
                    .contentShape(Rectangle())
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("common.cancel".localized)
        }
        // 🔴 __STRIP_HUGS_ITS_CONTENT_2026_08_19__ THE ACTUAL BUG, finally seen on
        // a screenshot rather than reasoned about in a diff.
        //
        // The strip was rendering about a THIRD OF THE SCREEN tall, with the two
        // lines of text marooned in the middle of it. Every previous pass changed
        // its colour, its radius and its border while it was twelve times too
        // tall — which is why "it is still a big rectangle" kept being the right
        // answer no matter what I did to the fill.
        //
        // Cause: the accent `Capsule` is constrained on WIDTH only, so it grows to
        // whatever height the HStack is offered, and the composer's layout offers
        // it a lot. `.fixedSize(vertical:)` makes the row hug its content, so the
        // capsule is exactly as tall as the text beside it.
        // __STRIP_IS_THE_COMPOSER_HAT_2026_08_19__ Sixth pass. Hugo has now said
        // three times that this reads as "a big rectangle above the keyboard", and
        // the height fix alone did not settle it — because a rounded card FLOATING
        // above the input is still a separate object hovering over the keyboard.
        //
        // A 2026 messenger does not float it: the strip is the composer's HAT. It
        // shares the input row's full width, rounds only its TOP corners, and sits
        // flush on top of the field with a hairline between them. The eye then
        // reads one control that grew a header — which is what it IS — instead of
        // a panel that appeared.
        .fixedSize(horizontal: false, vertical: true)
        .padding(.leading, 12)
        .padding(.trailing, 0)
        .padding(.vertical, 7)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(alignment: .bottom) {
            ZStack(alignment: .bottom) {
                UnevenRoundedRectangle(topLeadingRadius: 16, bottomLeadingRadius: 0,
                                       bottomTrailingRadius: 0, topTrailingRadius: 16,
                                       style: .continuous)
                    .fill(appBrandColor.opacity(0.09))
                // Hairline, not a border: it separates the hat from the field
                // without drawing a box around either.
                Rectangle()
                    .fill(appBrandColor.opacity(0.18))
                    .frame(height: 0.5)
            }
        }
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    private func formatAddress(_ key: String) -> String {
        if key.count > 12 {
            return String(key.prefix(6)) + "..." + String(key.suffix(4))
        }
        return key
    }
}

// MARK: - Reply Quote Thumbnails

/// Small, downscaled previews of the picture a reply quotes, keyed by the id of
/// the ORIGINAL message.
///
/// Nothing new travels on the wire: `ReplyMessage` still carries only a media
/// TYPE, and the picture is resolved from the copy of the original that both
/// sides already hold locally. A reply is exactly the same size it has always
/// been — which matters here, because these go out over mesh and LoRa.
///
/// NSCache rather than a dictionary, for the reason spelled out in
/// `MediaAttachmentCache`: a long thread can quote hundreds of pictures and the
/// system has to be able to take the memory back.
enum ReplyQuoteThumbnail {
    /// NSCache is itself thread-safe, so the store needs no isolation of its
    /// own — only `load` does, because it reads MainActor-bound managers.
    private static let cache: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>()
        c.countLimit = 120
        return c
    }()

    /// Side of the square drawn in the quote, in points at 1× — small on
    /// purpose, it is a 40pt tile.
    private static let side: CGFloat = 120

    static func cached(for messageId: String) -> UIImage? {
        cache.object(forKey: messageId as NSString)
    }

    /// Resolve the thumbnail for `reply`, calling back on the main thread.
    ///
    /// The disk read and the decode run on a background queue — the exact
    /// pattern the group media bubble uses (GroupViews.swift ~4930), and for
    /// the same reason: doing this synchronously in `onAppear` for every quote
    /// on screen is what stalls opening a media-heavy conversation.
    @MainActor
    static func load(for reply: ReplyMessage, completion: @escaping (UIImage?) -> Void) {
        guard reply.quotesAPicture else { return completion(nil) }
        let id = reply.originalMessageId
        if let hit = cached(for: id) { return completion(hit) }

        // 1:1 — already decrypted in RAM for any bubble that has been on
        // screen, and carried on the message itself otherwise.
        var inMemory: Data? = MediaAttachmentCache.shared.get(for: id)
            .map { $0.thumbnailData ?? $0.data }
        if inMemory == nil {
            inMemory = MessageManager.shared?.messages
                .first(where: { $0.id == id })?.mediaAttachment
        }

        // Hoisted out of the background closure on purpose: `shared` is
        // MainActor-isolated, `loadGroupMediaAttachment` is not.
        let groupManager = MessageGroupManager.shared
        DispatchQueue.global(qos: .userInitiated).async { [weak groupManager] in
            // Groups keep media on disk, never on the message.
            let data = inMemory
                ?? groupManager?.loadGroupMediaAttachment(for: id, type: .photo)
            let image = data.flatMap { UIImage(data: $0) }.map { downscaled($0) }
            DispatchQueue.main.async {
                if let image { cache.setObject(image, forKey: id as NSString) }
                completion(image)
            }
        }
    }

    /// Square, aspect-filled, at thumbnail size. Holding the full-resolution
    /// image for a 40pt tile is how a scrolling thread runs out of memory.
    private static func downscaled(_ image: UIImage) -> UIImage {
        let target = CGSize(width: side, height: side)
        let scale = max(target.width / image.size.width, target.height / image.size.height)
        let drawn = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let origin = CGPoint(x: (target.width - drawn.width) / 2,
                             y: (target.height - drawn.height) / 2)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: origin, size: drawn))
        }
    }
}

// MARK: - Reply Quote Body (thumbnail + text)

/// The inside of a reply quote: who is quoted, what they said, and — when the
/// quoted message was a picture — the picture itself.
///
/// 🔧 FIX (2026-08-12): every reply-quote surface used to be text only, so a
/// reply to a photo showed a media label where WhatsApp shows the photo. The
/// callers keep their own accent bar and background (they differ between the
/// composer banner, the 1:1 bubble and the group bubble); only the contents are
/// shared, so there is one place that decides how a quoted photo reads.
struct ReplyQuoteBody: View {
    let reply: ReplyMessage
    /// Already-resolved display name for whoever is being quoted.
    let title: String
    let titleColor: Color
    let textColor: Color
    /// `.caption` in the bubbles, `.caption` in the banner — passed in so each
    /// surface keeps its own scale. No fixed point sizes: Dynamic Type applies.
    var font: Font = .caption
    /// One line above the keyboard, two inside a bubble. Two lines is what made
    /// the composer strip tall enough to read as a box.
    var bodyLines: Int = 2
    var thumbnailSide: CGFloat = 40

    @State private var thumbnail: UIImage? = nil

    /// With a picture on screen the media label is redundant — the caption (a
    /// bot post's link, for instance) is what the user needs to read. Without
    /// one, the label is the only thing saying "this was a photo".
    private var line: String {
        // `InlineMarkup.stripped`: this box cannot style a run — it is one
        // `.caption` line with its own tap gesture — but it must not show the
        // markers either. Quoting `*gras*` used to print the asterisks, which is
        // the formatting feature failing in the most visible way it can.
        guard thumbnail != nil else { return InlineMarkup.stripped(reply.previewText) }
        let body = reply.bodyText
        return InlineMarkup.stripped(body.isEmpty ? (reply.mediaLabel ?? body) : body)
    }

    var body: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(font)
                    .fontWeight(.semibold)
                    .foregroundColor(titleColor)
                    .lineLimit(1)

                Text(line)
                    .font(font)
                    .foregroundColor(textColor)
                    .lineLimit(bodyLines)
            }

            if let thumbnail {
                Spacer(minLength: 4)
                Image(uiImage: thumbnail)
                    .resizable()
                    .scaledToFill()
                    .frame(width: thumbnailSide, height: thumbnailSide)
                    .clipShape(RoundedRectangle(cornerRadius: thumbnailSide * 0.28, style: .continuous))
                    .accessibilityLabel(reply.mediaLabel ?? "")
            }
        }
        .onAppear {
            guard thumbnail == nil else { return }
            ReplyQuoteThumbnail.load(for: reply) { thumbnail = $0 }
        }
    }
}

// MARK: - Quoted Message View (shown in message bubble)

// MARK: - Quoted Message View (REMOVED 2026-08-19)
//
// `QuotedMessageView` lived here, was redesigned four times, and was rendered
// NOWHERE — its only reference in the whole repo was its own `#Preview`. The
// quote you actually see is drawn inline by `ChatView` (1:1) and `GroupViews`
// (groups), both through the shared `ReplyQuoteBody` above.
//
// It is deleted rather than wired up, because the two live call sites have title
// logic this view never had: 1:1 says "You" when you quote yourself, groups
// resolve the member's display name. Adopting it would have LOST that.
//
// Deleted because four passes were spent redesigning a view nobody displays, and
// leaving it would guarantee a fifth. If a shared chrome is wanted later, the
// thing to extract is the bar-and-wash around `ReplyQuoteBody`, from the two real
// call sites — not this.

// MARK: - Forwarded Badge View

struct ForwardedBadgeView: View {
    let forwardCount: Int
    let isFromCurrentUser: Bool
    
    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "arrowshape.turn.up.right.fill")
                .font(.scaled(10))
            
            if forwardCount > 1 {
                Text("message.forwarded_many".localized)
                    .font(.caption2)
                    .italic()
            } else {
                Text("message.forwarded".localized)
                    .font(.caption2)
                    .italic()
            }
        }
        .foregroundColor(isFromCurrentUser ? .white.opacity(0.7) : .secondary)
        .padding(.bottom, 4)
    }
}

// MARK: - Contact Picker for Forwarding

struct ForwardContactPickerView: View {
    @Binding var isPresented: Bool
    let messageContent: String
    let onForward: (String) -> Void  // recipientPublicKey

    /// __FORWARD_SEARCH_2026_11_05__ The query typed in the search field.
    ///
    /// There was no search here at all: the body was a plain `List { ForEach }`
    /// with no predicate anywhere in the file, so reaching a contact meant
    /// scrolling the whole list.
    @State private var searchQuery = ""

    // Get conversations from MessageManager
    private var allConversations: [Conversation] {
        MessageManager.shared?.conversations ?? []
    }

    /// Matched against alias AND public key, then ranked most-forwarded first.
    ///
    /// See `ForwardTargetSearch` for why the alias matches case-insensitively
    /// and the key does not. The ranking is a re-rank over the existing
    /// last-message order, so with no forwarding history the list looks exactly
    /// as it did before.
    private var conversations: [Conversation] {
        let matched = allConversations.filter {
            ForwardTargetSearch.matches(
                query: searchQuery,
                name: MessageManager.resolveContactAlias($0.participantPublicKey),
                publicKey: $0.participantPublicKey
            )
        }
        return ForwardAffinityStore.rank(matched) { $0.participantPublicKey }
    }
    
    var body: some View {
        NavigationView {
            List {
                if conversations.isEmpty {
                    // Distinguishes "you have no contacts" from "your search
                    // matched nobody" — the same empty list meant both before,
                    // which reads as data loss when it is a typo.
                    Text(searchQuery.isEmpty
                         ? "contacts.empty".localized
                         : "forward.no_match".localized)
                        .foregroundColor(.secondary)
                        .frame(maxWidth: .infinity)
                        .padding()
                } else {
                    ForEach(conversations) { conversation in
                        Button {
                            ForwardAffinityStore.record(conversation.participantPublicKey)
                            onForward(conversation.participantPublicKey)
                            isPresented = false
                        } label: {
                            ForwardContactRow(publicKey: conversation.participantPublicKey)
                        }
                    }
                }
            }
            .searchable(
                text: $searchQuery,
                placement: .navigationBarDrawer(displayMode: .always),
                prompt: "forward.search_prompt".localized
            )
            // A pasted key must not be "corrected": autocapitalisation would
            // change the first character of base64 key material, and
            // autocorrect would mangle the rest. Case is significant in a key.
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled(true)
            .navigationTitle("message.forward_to".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("button_cancel".localized) {
                        isPresented = false
                    }
                }
            }
        }
    }
}

// MARK: - Forward Picker Row

/// One contact in the forward picker.
///
/// 🔧 FIX (2026-08-12, "forwarding shows keys, not the names I gave people"):
/// this row used to print `participantPublicKey.prefix(20) + "..."` as the
/// title, a two-hex-character avatar, and the STATIC label
/// `conversation.last_message` as the subtitle — so the one screen where you
/// must recognise a person showed none of the naming the rest of the app uses.
///
/// `MessageManager.resolveContactAlias` is that naming rule, and it is the same
/// call the incoming-message banner (MessageManager.swift:5767) and the CallKit
/// handle (VoIPPushManager.swift:541) already make. Deliberately NOT a second
/// alias lookup written here.
///
/// Extracted as its own view rather than inlined in the `ForEach`: the
/// view-builder type-check budget in this project is real, and a row that reads
/// three managers is exactly the kind of body that blows it.
struct ForwardContactRow: View {
    let publicKey: String

    @ObservedObject private var presenceManager = ContactPresenceManager.shared

    private let appBrandColor = Theme.brand

    private var alias: String { MessageManager.resolveContactAlias(publicKey) }

    /// Short, unambiguous form of the key — the secondary line, so two contacts
    /// that happen to share an alias are still distinguishable before you send.
    private var shortKey: String {
        publicKey.count > 16
            ? String(publicKey.prefix(8)) + "…" + String(publicKey.suffix(6))
            : publicKey
    }

    var body: some View {
        HStack(spacing: 12) {
            avatar
            VStack(alignment: .leading, spacing: 2) {
                Text(alias)
                    .font(.headline)
                    .foregroundColor(.primary)
                    .lineLimit(1)
                Text(shortKey)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            Image(systemName: "chevron.right")
                .foregroundColor(.secondary)
                .accessibilityHidden(true)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(alias)
    }

    @ViewBuilder
    private var avatar: some View {
        // The contact's own synced picture when they shared one — same source
        // the group member list uses — otherwise their initial.
        if let image = presenceManager.getProfileImage(publicKey: publicKey) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: 44, height: 44)
                .clipShape(Circle())
        } else {
            // __ONE_AVATAR_NOT_THREE_2026_11_07__ flat brand circle before —
            // the same blue for every contact in the forward picker, so the
            // column of avatars carried no information at all.
            DefaultAvatar(key: publicKey, name: alias, size: 44, initialCount: 1)
        }
    }
}

// MARK: - Preview

#Preview {
    VStack(spacing: 20) {
        ReplyPreviewView(
            reply: ReplyMessage(
                originalMessageId: "123",
                originalSenderKey: "abc123def456",
                originalText: "Hello, how are you doing today?",
                originalTimestamp: Date(),
                originalMediaType: nil
            ),
            contactAlias: "Alice",
            onCancel: {}
        )
        
        // The in-bubble quote is previewed where it is DRAWN — inline in
        // ChatView / GroupViews. There is deliberately no stand-in for it here:
        // a preview of a view nobody renders is what kept `QuotedMessageView`
        // alive through four redesigns.

        ForwardedBadgeView(forwardCount: 1, isFromCurrentUser: false)
        ForwardedBadgeView(forwardCount: 3, isFromCurrentUser: true)
    }
    .padding()
}

// MARK: - __RESTRICTED_NETWORK_HINT_2026_11_18__ RN-HINT v1 (docs/RESTRICTED_NETWORK_HINT.md)

/// "Sent through a restricted network": an invisible suffix (U+2063 U+2064 U+2063)
/// at the end of a 1:1 text, or of a reply envelope's decoded `content`, inside the
/// end-to-end encrypted plaintext. Old clients render the three default-ignorable
/// format characters as nothing (iOS 1.0.43 stores the decrypted text verbatim,
/// `MessageWithReply.fromMessageString` keeps `content` as is).
nonisolated enum RestrictedNetworkHint {
    static let marker = "\u{2063}\u{2064}\u{2063}"
    /// Sender setting, default ON (Settings › Privacy disguise).
    static let settingKey = "restrictedNetworkHintEnabled"

    static func senderAllows(_ defaults: UserDefaults = .standard) -> Bool {
        defaults.object(forKey: settingKey) as? Bool ?? true
    }

    /// Marker appended to `text` (at most one, at the very end).
    static func tag(_ text: String) -> String {
        hasMarker(text) ? text : text + marker
    }

    /// Unicode-scalar suffix test: Swift `String.hasSuffix` compares grapheme
    /// clusters, and these Cf scalars join the previous cluster.
    static func hasMarker(_ text: String) -> Bool {
        let m = Array(marker.unicodeScalars), s = text.unicodeScalars
        guard s.count >= m.count else { return false }
        return Array(s.suffix(m.count)) == m
    }

    static func strip(_ text: String) -> (text: String, flagged: Bool) {
        guard hasMarker(text) else { return (text, false) }
        var scalars = String.UnicodeScalarView(text.unicodeScalars)
        scalars.removeLast(marker.unicodeScalars.count)
        return (String(scalars), true)
    }

    /// The outgoing plaintext of a user-typed 1:1 text: the marker goes on the text,
    /// or on a reply's `content` BEFORE the envelope is encoded (never after the `}`).
    static func outgoing(text: String, replyTo: ReplyMessage?, coverOn: Bool, allowed: Bool) -> String {
        let flag = coverOn && allowed
        let content = flag ? tag(text) : text
        guard let replyTo else { return content }
        return MessageWithReply(content: content, replyTo: replyTo).toMessageString()
    }

    /// Ingestion: plain text or `💬REPLY💬` envelope → the same plaintext without the
    /// marker, and whether it carried one. Reads the DECODED `content` (an Android
    /// envelope spells the marker as `⁣…` JSON escapes).
    static func ingest(_ plaintext: String) -> (plaintext: String, flagged: Bool) {
        if plaintext.hasPrefix(MessageWithReply.replyPrefix) {
            guard let parsed = MessageWithReply.fromMessageString(plaintext), parsed.replyTo != nil else {
                return (plaintext, false)
            }
            let (clean, flagged) = strip(parsed.content)
            guard flagged else { return (plaintext, false) }
            return (MessageWithReply(content: clean, replyTo: parsed.replyTo).toMessageString(), true)
        }
        let r = strip(plaintext)
        return (r.text, r.flagged)
    }
}

/// Which received messages carried the hint (local, per device; bounded).
enum RestrictedNetworkHintStore {
    private static let key = "restrictedNetworkHintIds"
    private static let cap = 2000
    static func insert(_ id: String, defaults: UserDefaults = .standard) {
        var ids = defaults.stringArray(forKey: key) ?? []
        guard !ids.contains(id) else { return }
        ids.append(id)
        if ids.count > cap { ids.removeFirst(ids.count - cap) }
        defaults.set(ids, forKey: key)
        cache = Set(ids)
    }
    private static var cache: Set<String>?
    static func contains(_ id: String, defaults: UserDefaults = .standard) -> Bool {
        if cache == nil { cache = Set(defaults.stringArray(forKey: key) ?? []) }
        return cache?.contains(id) ?? false
    }
    static func resetCacheForTests() { cache = nil }
}

/// The discreet chip under an incoming bubble (and on a revealed "Disguise as
/// photo" message): eye-slash + "Sent through a restricted network"; tap shows
/// what it means, hover shows it too on the Mac.
struct RestrictedNetworkChip: View {
    @State private var explaining = false

    var body: some View {
        Button { explaining = true } label: {
            HStack(spacing: 4) {
                Image(systemName: "eye.slash")
                    .font(.caption2.weight(.semibold))
                Text("rn_hint.chip".localized)
                    .font(.caption2)
                    .lineLimit(1)
            }
            .foregroundColor(.secondary)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Capsule().fill(Color(.tertiarySystemFill)))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .help("rn_hint.explain".localized)
        .accessibilityLabel("rn_hint.chip".localized)
        .accessibilityHint("rn_hint.explain".localized)
        .accessibilityIdentifier("rn_hint.chip")
        .popover(isPresented: $explaining) {
            VStack(alignment: .leading, spacing: 8) {
                Label("rn_hint.chip".localized, systemImage: "eye.slash").font(.headline)
                Text("rn_hint.explain".localized)
                    .font(.callout)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(16)
            .frame(idealWidth: 320, maxWidth: 360)
            .presentationCompactAdaptation(.popover)
            .accessibilityIdentifier("rn_hint.explanation")
        }
    }
}
