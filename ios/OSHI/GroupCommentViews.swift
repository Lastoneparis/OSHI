//
//  GroupCommentViews.swift
//  OSHI
//
//  __GROUP_COMMENTS_2026_11_21__ GROUP_INFO_SETTINGS_SPEC §2.12.6: the
//  "💬 N comments" bar under each post of an Active channel and the comment
//  thread (post on top, comments, composer with reply-to). Text only in v1:
//  no attach button. The rules are in GroupComments.swift; the send / delete /
//  edit actions in the MessageGroupManager extension at the end of
//  GroupMessaging.swift.
//

import SwiftUI

// MARK: - Bar under a post

struct GroupCommentsBar: View {
    let groupId: UUID
    let postId: UUID
    let isMine: Bool
    var maxWidth: CGFloat = 420
    /// __BUBBLE_MEDIA_2026_11_21__ Drawn INSIDE a channel card, under its
    /// hairline: no surface, no outline, no outer padding of its own.
    var attached: Bool = false

    @ObservedObject private var store = GroupCommentStore.shared

    static func label(count: Int) -> String {
        count == 0 ? "cm_leave_comment".localized : countText(count)
    }

    /// `cm_comments_count` is a plural (Localizable.stringsdict). Read straight
    /// from the bundle: `.localized` may rewrite the string (MacVerbs on
    /// Catalyst), which would drop the plural rules attached to it.
    static func countText(_ count: Int) -> String {
        let fmt = Bundle.main.localizedString(forKey: "cm_comments_count", value: "%d comments", table: nil)
        return String.localizedStringWithFormat(fmt, count)
    }

    var body: some View {
        let n = store.count(groupId: groupId, postId: postId)
        NavigationLink {
            GroupCommentThreadView(groupId: groupId, postId: postId)
        } label: {
            HStack(spacing: 8) {
                Image(systemName: n == 0 ? "bubble.left" : "bubble.left.and.bubble.right.fill")
                    .font(.footnote)
                Text(Self.label(count: n))
                    .font(.footnote.weight(.semibold))
                    .lineLimit(1)
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
            // Theme-aware accent (light #5616EC / dark #B899FF): the brand purple
            // alone read too dark on the dark received-bubble fill.
            .foregroundStyle(ChatPalette.quoteAccent)
            .padding(.horizontal, attached ? 12 : 14)
            .padding(.vertical, attached ? 10 : 9)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(attached ? Color.clear : BubbleStyleManager.receivedBubbleColour)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(Theme.brand.opacity(attached ? 0 : 0.18), lineWidth: 0.5)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .frame(maxWidth: maxWidth)
        .frame(maxWidth: attached ? nil : .infinity, alignment: isMine ? .trailing : .leading)
        .padding(.horizontal, attached ? 0 : 8)
        .padding(.top, attached ? 0 : -4)
        .accessibilityIdentifier("cm.bar")
    }
}

// MARK: - Thread

struct GroupCommentThreadView: View {
    let groupId: UUID
    let postId: UUID

    @EnvironmentObject var groupManager: MessageGroupManager
    @ObservedObject private var store = GroupCommentStore.shared
    @ObservedObject private var reactions = MessageReactionManager.shared

    @State private var draft = ""
    @State private var replyTo: GroupMessage?
    @State private var notice: String?
    @State private var deleting: GroupMessage?
    @State private var editing: GroupMessage?
    @State private var editText = ""
    @FocusState private var composerFocused: Bool

    private var group: MessageGroup? { groupManager.groups.first { $0.id == groupId } }
    private var post: GroupMessage? { groupManager.commentPost(groupId: groupId, postId: postId) }
    private var me: String { groupManager.currentUserPublicKey ?? "" }
    private var active: Bool {
        GroupCommentPolicy.isActive(group) && group?.settings?.dissolved != true
    }

    var body: some View {
        VStack(spacing: 0) {
            if let post, let group {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 6) {
                            GroupCommentRow(message: post, group: group, me: me, isPost: true,
                                            postId: postId)
                            HStack(spacing: 8) {
                                Rectangle().fill(Color.secondary.opacity(0.25)).frame(height: 0.5)
                                Text(comments.isEmpty ? "cm_thread_title".localized
                                                      : GroupCommentsBar.countText(comments.count))
                                    .font(.caption2.weight(.semibold))
                                    .foregroundStyle(.secondary)
                                    .fixedSize()
                                Rectangle().fill(Color.secondary.opacity(0.25)).frame(height: 0.5)
                            }
                            .padding(.vertical, 8)
                            if comments.isEmpty {
                                Text("cm_empty".localized)
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 24)
                                    .accessibilityIdentifier("cm.empty")
                            }
                            ForEach(comments, id: \.id) { c in
                                GroupCommentRow(message: c, group: group, me: me, isPost: false, postId: postId)
                                    .id(c.id)
                                    .contextMenu { menu(for: c, group: group) }
                            }
                            Color.clear.frame(height: 4).id("cm-bottom")
                        }
                        .padding(.horizontal, 12)
                        .padding(.top, 12)
                    }
                    .scrollDismissesKeyboard(.interactively)
                    .onChange(of: comments.count) { _, _ in
                        withAnimation { proxy.scrollTo("cm-bottom", anchor: .bottom) }
                    }
                }
                composerArea
            } else {
                Spacer()
                Label("cm_post_unavailable".localized, systemImage: "exclamationmark.bubble")
                    .foregroundStyle(.secondary)
                    .padding()
                    .accessibilityIdentifier("cm.unavailable")
                Spacer()
            }
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("cm_thread_title".localized)
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog("cm_delete_comment".localized,
                            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                            titleVisibility: .visible) {
            Button("cm_delete_comment".localized, role: .destructive) {
                if let d = deleting { groupManager.deleteCommentForEveryone(groupId: groupId, commentId: d.id) }
                deleting = nil
            }
            Button("cancel".localized, role: .cancel) { deleting = nil }
        }
        .alert("message.action.edit".localized,
               isPresented: Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })) {
            TextField("cm_composer_hint".localized, text: $editText)
            Button("cancel".localized, role: .cancel) { editing = nil }
            Button("gs_save".localized) {
                if let e = editing { groupManager.editCommentForEveryone(groupId: groupId, commentId: e.id, newText: editText) }
                editing = nil
            }
        }
    }

    private var comments: [GroupMessage] { store.comments(groupId: groupId, postId: postId) }

    // MARK: Composer

    @ViewBuilder
    private var composerArea: some View {
        VStack(spacing: 0) {
            Divider()
            if !active {
                Text("cm_disabled".localized)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(.bar)
                    .accessibilityIdentifier("cm.disabled")
            } else {
                if let r = replyTo {
                    HStack(spacing: 8) {
                        Capsule().fill(ChatPalette.quoteAccent).frame(width: 3, height: 30)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(String(format: "cm_replying_to".localized,
                                        GroupNames.displayName(r.senderPublicKey, in: group, me: me)))
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(ChatPalette.quoteAccent)
                            Text(GroupMentions.render(GroupCommentPolicy.text(of: r)))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        Spacer()
                        Button { replyTo = nil } label: {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("cancel".localized)
                    }
                    .padding(.horizontal, 14)
                    .padding(.top, 8)
                }
                if let notice {
                    Text(notice)
                        .font(.caption)
                        .foregroundStyle(.orange)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.top, 6)
                }
                HStack(alignment: .bottom, spacing: 8) {
                    TextField("cm_composer_hint".localized, text: $draft, axis: .vertical)
                        .lineLimit(1...5)
                        .focused($composerFocused)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 9)
                        .background(
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .fill(Color(.secondarySystemGroupedBackground))
                        )
                        .accessibilityIdentifier("cm.composer")
                    Button(action: send) {
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.system(size: 32))
                            .foregroundStyle(canSend ? Theme.brand : Color.secondary.opacity(0.5))
                    }
                    .buttonStyle(.plain)
                    .disabled(!canSend)
                    .accessibilityLabel("send".localized)
                    .accessibilityIdentifier("cm.send")
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
            }
        }
        .background(.bar)
    }

    private var canSend: Bool { !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    private func send() {
        let r = groupManager.sendComment(groupId: groupId, postId: postId, text: draft, replyTo: replyTo)
        switch r {
        case .ok:
            draft = ""
            replyTo = nil
            notice = nil
        case .tooFast:
            notice = "cm_too_fast".localized
            UINotificationFeedbackGenerator().notificationOccurred(.warning)
        case .inactive:
            notice = "cm_disabled".localized
        case .badPost:
            notice = "cm_post_unavailable".localized
        case .empty:
            break
        }
    }

    // MARK: Long-press actions

    @ViewBuilder
    private func menu(for c: GroupMessage, group: MessageGroup) -> some View {
        if active {
            Button {
                replyTo = c
                composerFocused = true
            } label: { Label("message.action.reply".localized, systemImage: "arrowshape.turn.up.left") }
        }
        let choices = ReactionEmoji.allCases.filter {
            !$0.isOSHIMark && GroupSettingsPolicy.reactionAllowed($0.rawValue, settings: group.settings)
        }
        if !choices.isEmpty {
            Menu {
                ForEach(choices.prefix(12), id: \.self) { e in
                    Button(e.rawValue) { react(c, e) }
                }
            } label: { Label("reaction.choose".localized, systemImage: "face.smiling") }
        }
        if !MessagePrivacy.blockForwardAndCopy {
            Button {
                SecureClipboard.copy(GroupMentions.render(GroupCommentPolicy.text(of: c)))
            } label: { Label("message.action.copy".localized, systemImage: "doc.on.doc") }
        }
        if GroupCommentPolicy.mayEdit(me: me, comment: c) {
            Button {
                editText = GroupCommentPolicy.text(of: c)
                editing = c
            } label: { Label("message.action.edit".localized, systemImage: "pencil") }
        }
        if GroupCommentPolicy.mayDelete(actor: me, comment: c, group: group) {
            Button(role: .destructive) { deleting = c } label: {
                Label("cm_delete_comment".localized, systemImage: "trash")
            }
        }
    }

    /// Same two steps as the timeline (`GroupChatView.reactToGroupMessage`):
    /// toggle locally, then send the add/remove; §2.3 filter on the sender side.
    private func react(_ m: GroupMessage, _ emoji: ReactionEmoji) {
        let rm = MessageReactionManager.shared
        let removing = rm.hasUserReacted(messageId: m.id.uuidString, senderPublicKey: me, emoji: emoji)
        if !removing, !GroupSettingsPolicy.reactionAllowed(emoji.rawValue, settings: group?.settings) { return }
        rm.toggleReaction(to: m.id.uuidString, emoji: emoji, senderPublicKey: me, senderName: "you".localized)
        let payload = ReactionPayload(messageId: m.id.uuidString, emoji: emoji, senderPublicKey: me,
                                      senderName: "you".localized, timestamp: Date(),
                                      action: removing ? .remove : .add)
        groupManager.sendMessage(to: groupId, content: payload.toMessageString(), senderPublicKey: me)
    }
}

// MARK: - One row (the post on top, or a comment)

struct GroupCommentRow: View {
    let message: GroupMessage
    let group: MessageGroup
    let me: String
    let isPost: Bool
    let postId: UUID

    @EnvironmentObject var groupManager: MessageGroupManager

    private var isMine: Bool {
        MessageManager.canonicalIdentity(message.senderPublicKey) == MessageManager.canonicalIdentity(me)
    }

    private var bodyText: String {
        if isPost {
            if message.mediaType != nil {
                let caption = message.plaintextContent ?? ""
                return [mediaLabel, caption].filter { !$0.isEmpty }.joined(separator: " ")
            }
            let t = GroupCommentPolicy.text(of: message)
            return GroupMentions.render(OSHIControlPayload.previewText(for: t))
        }
        return GroupMentions.render(GroupCommentPolicy.text(of: message))
    }

    private var mediaLabel: String {
        switch message.mediaType {
        case .photo?: return "📷 " + "media_image".localized
        case .video?: return "🎥 " + "media_video".localized
        case .audio?: return "🎤 " + "media_audio".localized
        case .document?: return "📄 " + "media_document".localized
        case .contact?: return "👤"
        case nil: return ""
        }
    }

    /// The quote of a reply to another comment. A top-level comment quotes the
    /// post itself (`replyTo == commentOn`) and shows no quote in the thread.
    private var quote: ReplyMessage? {
        guard !isPost else { return nil }
        let b = GroupCommentPolicy.body(of: message)
        guard let r = GroupCommentPolicy.envelope(of: b)?.replyTo,
              !GroupCommentPolicy.sameId(r.originalMessageId, message.commentOn) else { return nil }
        return r
    }

    var body: some View {
        HStack(alignment: .bottom) {
            if isMine && !isPost { Spacer(minLength: 40) }
            VStack(alignment: isMine && !isPost ? .trailing : .leading, spacing: 4) {
                CommentBubbleLayout(spacing: 5) {
                    if !isMine || isPost {
                        Text(GroupNames.displayName(message.senderPublicKey, in: group, me: me))
                            .font(.caption.weight(.semibold))
                            .foregroundColor(AvatarIdentity.nameColor(for: message.senderPublicKey))
                    }
                    if let q = quote {
                        HStack(spacing: 7) {
                            Capsule().fill(ChatPalette.quoteAccent).frame(width: 3)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(GroupNames.displayName(q.originalSenderKey, in: group, me: me))
                                    .font(.caption2.weight(.semibold))
                                    .foregroundStyle(ChatPalette.quoteAccent)
                                Text(q.previewText)
                                    .font(.caption)
                                    .foregroundStyle(ChatPalette.quoteBody)
                                    .lineLimit(2)
                            }
                        }
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.vertical, 4)
                        .padding(.horizontal, 6)
                        .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(ChatPalette.quoteSurface))
                    }
                    Text(bodyText)
                        .font(isPost ? .body : .callout)
                        .foregroundColor(isMine && !isPost ? .white : .primary)
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: 4) {
                        if message.editedAt != nil {
                            Text("message.edited".localized).italic()
                        }
                        Text(message.timestamp, style: .time)
                        if isMine && !isPost { statusIcon }
                    }
                    .font(.caption2)
                    .foregroundColor(isMine && !isPost ? .white.opacity(0.8) : .secondary)
                }
                .frame(maxWidth: isPost ? .infinity : nil, alignment: .leading)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(isMine && !isPost ? BubbleStyleManager.sentBubbleColour(Theme.brand)
                                                : BubbleStyleManager.receivedBubbleColour)
                )
                .frame(maxWidth: isPost ? .infinity : 520, alignment: isMine && !isPost ? .trailing : .leading)
                MessageReactionsView(messageId: message.id.uuidString, isFromCurrentUser: isMine && !isPost)
            }
            if !isMine && !isPost { Spacer(minLength: 40) }
        }
        .accessibilityIdentifier(isPost ? "cm.post" : "cm.comment")
    }

    @ViewBuilder
    private var statusIcon: some View {
        switch message.deliveryStatus {
        case .pending?:
            Image(systemName: "clock")
        case .failed?:
            Button {
                groupManager.retryComment(groupId: group.id, commentId: message.id)
            } label: {
                Image(systemName: "exclamationmark.circle.fill").foregroundColor(.red)
            }
            .buttonStyle(.plain)
        default:
            Image(systemName: "checkmark")
        }
    }
}

/// A bubble that HUGS its content: as wide as its widest child (capped by the
/// proposal), children leading-aligned except the last one (the time row),
/// which sits at the trailing edge — the Telegram bubble without stretching
/// every comment to full width.
struct CommentBubbleLayout: Layout {
    var spacing: CGFloat = 5

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let p = ProposedViewSize(width: proposal.width, height: nil)
        let sizes = subviews.map { $0.sizeThatFits(p) }
        let w = sizes.map(\.width).max() ?? 0
        let h = sizes.map(\.height).reduce(0, +) + spacing * CGFloat(max(0, sizes.count - 1))
        return CGSize(width: min(w, proposal.width ?? w), height: h)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        let p = ProposedViewSize(width: bounds.width, height: nil)
        for (i, v) in subviews.enumerated() {
            let sz = v.sizeThatFits(p)
            let x = i == subviews.count - 1 ? bounds.maxX - sz.width : bounds.minX
            v.place(at: CGPoint(x: x, y: y), anchor: .topLeading, proposal: ProposedViewSize(width: min(sz.width, bounds.width), height: sz.height))
            y += sz.height + spacing
        }
    }
}

// MARK: - DEBUG screenshot host

#if DEBUG
/// `-OSHIScreens -OSHICommentsPreview chat|thread|settings [-OSHIGroupInfoRole member]`
/// Seeds one Active channel with a post, comments and a reply, then shows the
/// real chat, the real thread, or the real settings page. Never in Release;
/// never touches the network (nothing is sent: the seed writes the stores).
struct GroupCommentsPreviewHost: View {
    @EnvironmentObject var groupManager: MessageGroupManager
    @EnvironmentObject var identityManager: IdentityManager
    @State private var ready = false

    static let demoId = UUID(uuidString: "6E5E7700-0000-4000-8000-0000000C0C0C")!
    static let postId = UUID(uuidString: "6E5E7700-0000-4000-8000-0000000C0001")!
    static let post2Id = UUID(uuidString: "6E5E7700-0000-4000-8000-0000000C0002")!

    private func arg(_ name: String) -> String? {
        let a = ProcessInfo.processInfo.arguments
        guard let i = a.firstIndex(of: name), i + 1 < a.count else { return nil }
        return a[i + 1]
    }

    var body: some View {
        SwiftUI.Group {
            if ready, let g = groupManager.groups.first(where: { $0.id == Self.demoId }) {
                switch arg("-OSHICommentsPreview") ?? "chat" {
                case "thread":
                    NavigationStack { GroupCommentThreadView(groupId: Self.demoId, postId: Self.postId) }
                case "settings":
                    NavigationStack {
                        List {
                            GroupSettingsSections(groupId: Self.demoId, onMessageAdmin: { _ in })
                        }
                        .navigationTitle("gs_title".localized)
                    }
                default:
                    NavigationStack { GroupChatView(group: g) }
                }
            } else {
                ProgressView()
            }
        }
        .onAppear { seed() }
    }

    private func seed() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
            let me = identityManager.publicKey.isEmpty ? (groupManager.currentUserPublicKey ?? "ME") : identityManager.publicKey
            let asAdmin = arg("-OSHIGroupInfoRole") != "member"
            let fr = Locale.current.language.languageCode?.identifier == "fr"
            let alice = DemoDataManager.aliceKey, bob = DemoDataManager.bobKey, charlie = DemoDataManager.charlieKey
            let t0 = Date().addingTimeInterval(-86400 * 30)
            let admin = asAdmin ? me : alice
            var g = MessageGroup(
                id: Self.demoId, name: "Swiss Quant", type: .adminOnly, adminPublicKey: admin,
                members: [
                    GroupMember(publicKey: admin, alias: asAdmin ? nil : "Alice", joinedAt: t0, isAdmin: true),
                    GroupMember(publicKey: asAdmin ? alice : me, alias: asAdmin ? "Alice" : nil, joinedAt: t0, isAdmin: false),
                    GroupMember(publicKey: bob, alias: "Bob", joinedAt: t0, isAdmin: false),
                    GroupMember(publicKey: charlie, alias: "Charlie", joinedAt: t0, isAdmin: false)
                ],
                createdAt: t0, lastActivity: Date())
            var s = GroupSettings()
            s.postPolicy = .admins
            s.comments = true
            s.reactions = .some
            s.allowedReactions = ["👍", "❤️", "🔥"]
            g.settings = s
            g.stateVersion = 8
            g.setCurrentUser(me)
            groupManager.groups.removeAll { $0.id == Self.demoId }
            groupManager.groups.append(g)
            GroupCommentStore.shared.forget(Self.demoId)

            let now = Date()
            func msg(_ id: UUID, _ from: String, _ text: String, _ at: Date) -> GroupMessage {
                GroupMessage(id: id, groupId: Self.demoId, senderPublicKey: from, encryptedContent: Data(text.utf8),
                             timestamp: at, isRead: true, messageChainIndex: 0, plaintextContent: text,
                             deliveryStatus: .sent)
            }
            let p1Text = fr ? "Note du jour : position hebdo BTC 40 % long. Volatilité en baisse, on garde la cible."
                            : "Daily note: BTC weekly position 40% long. Volatility is falling, target unchanged."
            let p2Text = fr ? "Rappel : la réunion mensuelle est jeudi à 18 h." : "Reminder: the monthly call is on Thursday at 6 pm."
            let p1 = msg(Self.postId, admin, p1Text, now.addingTimeInterval(-3600))
            let p2 = msg(Self.post2Id, admin, p2Text, now.addingTimeInterval(-1800))
            groupManager.groupMessages[Self.demoId] = [p1, p2]

            let q1 = fr ? "Pourquoi 40 et pas 50 ?" : "Why 40 and not 50?"
            let a1 = fr ? "Le ciblage de volatilité plafonne à 40." : "Vol targeting caps it at 40."
            let c3 = fr ? "Merci pour la note 🙏" : "Thanks for the note 🙏"
            let c1 = GroupCommentPolicy.buildComment(groupId: Self.demoId, me: bob, text: q1, post: p1, postText: p1Text,
                                                     replyTo: nil, replyText: nil, at: now.addingTimeInterval(-3000))
            var c2 = GroupCommentPolicy.buildComment(groupId: Self.demoId, me: admin, text: a1, post: p1, postText: p1Text,
                                                     replyTo: c1, replyText: q1, at: now.addingTimeInterval(-2900))
            var c3m = GroupCommentPolicy.buildComment(groupId: Self.demoId, me: charlie, text: c3, post: p1, postText: p1Text,
                                                      replyTo: nil, replyText: nil, at: now.addingTimeInterval(-2000))
            var c1s = c1; c1s.deliveryStatus = .sent
            c2.deliveryStatus = .sent
            c3m.deliveryStatus = .sent
            for c in [c1s, c2, c3m] { GroupCommentStore.shared.insert(c) }
            let rm = MessageReactionManager.shared
            if !rm.hasUserReacted(messageId: Self.postId.uuidString, senderPublicKey: bob, emoji: .fire) {
                rm.toggleReaction(to: Self.postId.uuidString, emoji: .fire, senderPublicKey: bob, senderName: "Bob")
            }
            if !rm.hasUserReacted(messageId: c2.id.uuidString, senderPublicKey: bob, emoji: .thumbsUp) {
                rm.toggleReaction(to: c2.id.uuidString, emoji: .thumbsUp, senderPublicKey: bob, senderName: "Bob")
            }
            ready = true
        }
    }
}
/// __BUBBLE_MEDIA_2026_11_21__ `-OSHIScreens -OSHIBubblePreview group|group2|channel|direct`
/// Seeds a throwaway group / channel / 1:1 thread with GENERATED test pictures
/// (landscape, portrait, small, captioned) so the media bubble can be shot
/// before/after in every theme. DEBUG only, never sends anything.
struct BubbleMediaPreviewHost: View {
    @EnvironmentObject var groupManager: MessageGroupManager
    @EnvironmentObject var identityManager: IdentityManager
    @State private var ready = false
    @State private var conversation: Conversation?

    static let groupId = UUID(uuidString: "B0B0B0B0-0000-4000-8000-00000000B0B1")!
    static let channelId = UUID(uuidString: "B0B0B0B0-0000-4000-8000-00000000B0C1")!

    private var mode: String {
        let a = ProcessInfo.processInfo.arguments
        guard let i = a.firstIndex(of: "-OSHIBubblePreview"), i + 1 < a.count else { return "group" }
        return a[i + 1]
    }

    /// A test picture: diagonal gradient, a 1-px-per-percent grid, a label and a
    /// thick border so any crop or empty band beside it is obvious on screen.
    static func testImage(_ w: Int, _ h: Int, _ label: String, hue: CGFloat) -> Data {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let size = CGSize(width: w, height: h)
        let img = UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            let c = ctx.cgContext
            let colors = [UIColor(hue: hue, saturation: 0.55, brightness: 0.95, alpha: 1).cgColor,
                          UIColor(hue: hue + 0.12, saturation: 0.75, brightness: 0.55, alpha: 1).cgColor] as CFArray
            let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1])!
            c.drawLinearGradient(g, start: .zero, end: CGPoint(x: size.width, y: size.height), options: [])
            UIColor.white.withAlphaComponent(0.25).setStroke()
            for i in 1..<10 {
                let x = size.width * CGFloat(i) / 10, y = size.height * CGFloat(i) / 10
                c.stroke(CGRect(x: x, y: 0, width: 0.0001, height: size.height).insetBy(dx: -1, dy: 0))
                c.stroke(CGRect(x: 0, y: y, width: size.width, height: 0.0001).insetBy(dx: 0, dy: -1))
            }
            let border = max(4, CGFloat(min(w, h)) / 40)
            UIColor.white.setStroke()
            c.setLineWidth(border)
            c.stroke(CGRect(origin: .zero, size: size).insetBy(dx: border / 2, dy: border / 2))
            let fontSize = max(12, CGFloat(min(w, h)) / 9)
            let attrs: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: fontSize, weight: .heavy),
                .foregroundColor: UIColor.white]
            let text = "\(label)\n\(w)×\(h)" as NSString
            let r = text.boundingRect(with: size, options: .usesLineFragmentOrigin, attributes: attrs, context: nil)
            text.draw(at: CGPoint(x: (size.width - r.width) / 2, y: (size.height - r.height) / 2), withAttributes: attrs)
        }
        return img.jpegData(compressionQuality: 0.85) ?? Data()
    }

    var body: some View {
        SwiftUI.Group {
            if ready, mode == "direct", let conversation {
                NavigationStack { ChatView(conversation: conversation) }
            } else if ready, let g = groupManager.groups.first(where: {
                $0.id == (mode == "channel" ? Self.channelId : Self.groupId) }) {
                NavigationStack { GroupChatView(group: g) }
            } else {
                ProgressView()
            }
        }
        .onAppear { seed() }
    }

    private func seed() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
            let me = identityManager.publicKey.isEmpty ? (groupManager.currentUserPublicKey ?? "ME") : identityManager.publicKey
            let alice = DemoDataManager.aliceKey, bob = DemoDataManager.bobKey, charlie = DemoDataManager.charlieKey
            let landscape = Self.testImage(1600, 1000, "LANDSCAPE", hue: 0.55)
            let portrait = Self.testImage(1000, 1600, "PORTRAIT", hue: 0.08)
            let small = Self.testImage(160, 120, "SMALL", hue: 0.33)
            let tall = Self.testImage(700, 2100, "TALL 1:3", hue: 0.75)
            let wide = Self.testImage(2400, 700, "PANORAMA", hue: 0.95)
            let now = Date()

            if mode == "direct" {
                guard let mm = MessageManager.shared else { return }
                let peer = MessageManager.canonicalIdentity(alice)
                func m(_ id: String, _ incoming: Bool, _ text: String?, _ img: Data?, _ ago: TimeInterval) -> SecureMessage {
                    let sender = incoming ? peer : me
                    return SecureMessage(
                        id: id, senderAddress: incoming ? "Alice" : String(me.prefix(10)),
                        recipientAddress: incoming ? String(me.prefix(10)) : "Alice",
                        encryptedContent: EncryptedMessage(ciphertext: "", signature: "demo_signature",
                                                           senderPublicKey: sender, timestamp: now),
                        timestamp: now.addingTimeInterval(-ago), isRead: true, deliveryStatus: .read,
                        senderPublicKey: sender, recipientPublicKey: incoming ? me : peer,
                        plaintextContent: text, mediaAttachment: img, deliveryMethod: .ipfs,
                        mediaType: img == nil ? nil : .image, originalMediaData: img,
                        mediaFileName: img == nil ? nil : "\(id).jpg")
                }
                mm.messages.removeAll { $0.id.hasPrefix("bubble-demo-") }
                mm.messages.append(contentsOf: [
                    m("bubble-demo-1", true, "Photos from the weekend 👇", nil, 600),
                    m("bubble-demo-2", true, nil, landscape, 540),
                    m("bubble-demo-3", false, "Love this one — the light at the top of the ridge was unreal", portrait, 480),
                    m("bubble-demo-4", true, nil, small, 420),
                    m("bubble-demo-5", false, "Gorgeous!", nil, 360),
                ])
                mm.refreshConversations()
                conversation = mm.conversations.first {
                    MessageManager.canonicalIdentity($0.participantPublicKey) == peer
                }
                ready = true
                return
            }

            let channel = mode == "channel"
            let gid = channel ? Self.channelId : Self.groupId
            let t0 = now.addingTimeInterval(-86400 * 30)
            var g = MessageGroup(
                id: gid, name: channel ? "OSHI Photo Club" : "Weekend Hikers",
                type: channel ? .adminOnly : .collaborative, adminPublicKey: alice,
                members: [
                    GroupMember(publicKey: alice, alias: "Alice", joinedAt: t0, isAdmin: true),
                    GroupMember(publicKey: me, alias: nil, joinedAt: t0, isAdmin: false),
                    GroupMember(publicKey: bob, alias: "Bob", joinedAt: t0, isAdmin: false),
                    GroupMember(publicKey: charlie, alias: "Charlie", joinedAt: t0, isAdmin: false)
                ],
                createdAt: t0, lastActivity: now)
            if channel {
                var s = GroupSettings()
                s.postPolicy = .admins
                s.comments = true
                g.settings = s
                g.stateVersion = 3
            }
            g.setCurrentUser(me)
            groupManager.groups.removeAll { $0.id == gid }
            groupManager.groups.append(g)

            var idx = 0
            func gm(_ from: String, _ text: String?, _ img: Data?, _ ago: TimeInterval) -> GroupMessage {
                idx += 1
                var msg = GroupMessage(
                    id: UUID(), groupId: gid, senderPublicKey: from,
                    encryptedContent: img == nil ? Data((text ?? "").utf8) : Data(),
                    timestamp: now.addingTimeInterval(-ago), isRead: true, messageChainIndex: idx,
                    mediaType: img == nil ? nil : .photo, mediaFileName: img == nil ? nil : "photo\(idx).jpg",
                    plaintextContent: text, deliveryStatus: .sent)
                msg.mediaData = img
                return msg
            }
            let msgs: [GroupMessage]
            switch mode {
            case "channel":
                msgs = [
                    gm(alice, "This week's theme: mountains at golden hour. Post yours in the comments!", landscape, 7200),
                    gm(alice, "Winner of last week: a tall frame shot straight up the cliff 🧗", portrait, 3600),
                    gm(alice, nil, wide, 1800),
                ]
            case "group2":
                msgs = [
                    gm(bob, nil, portrait, 900),
                    gm(me, "Here's mine from the summit, the clouds came in right after", landscape, 600),
                    gm(charlie, nil, tall, 400),
                    gm(me, "Next hike on Saturday?", nil, 200),
                ]
            default:
                msgs = [
                    gm(alice, "Photos from the trail 👇", nil, 1200),
                    gm(alice, nil, landscape, 1100),
                    gm(charlie, nil, small, 1000),
                    gm(bob, "The view from the top was worth every step, even with the wind", portrait, 900),
                ]
            }
            groupManager.groupMessages[gid] = msgs
            ready = true
        }
    }
}
#endif
