import SwiftUI

/// Merged Messages + Groups tab with a segmented picker at the top.
struct ChatsTabView: View {
    @State private var selectedSegment = 0
    @Binding var pendingConversationId: String?
    @Binding var pendingGroupId: String?
    @Binding var pendingMailMessageId: String?

    @EnvironmentObject var messageManager: MessageManager
    @EnvironmentObject var messageGroupManager: MessageGroupManager
    @EnvironmentObject var identityManager: IdentityManager

    /// Shared with MailView — observed here so the Email-segment badge tracks the
    /// unread Inbox count live (and updates the moment a message is read).
    @StateObject private var mail = OSHIMailClient.shared

    private let brandColor = Theme.brand

    /// __MERGED_CHATS_2026_11_18__ « Fusionner Messages et Groupes » (Settings ›
    /// Appearance). On: two segments, "Chats" (people + groups, tag 0) and
    /// "Email" (tag 2). Off: the three segments, untouched.
    @AppStorage(MergedChatsSetting.storageKey) private var mergedChats = false

    private var groupUnreadCount: Int {
        messageGroupManager.getTotalUnreadCount()
    }

    private var messagesUnreadCount: Int {
        messageManager.conversations.reduce(0) { $0 + $1.unreadCount }
    }

    private var mailUnreadCount: Int {
        mail.unreadInboxCount
    }

    /// 🔴 __IPAD_TABBAR_OVERLAP_2026_08_19__ App Review, guideline 4:
    /// "the screen was cropped", reviewed on iPhone 17 Pro Max AND iPad Air 11".
    ///
    /// On iPad, iOS 26 puts the `TabView` bar along the TOP of the window, not the
    /// bottom — and it floats OVER the tab's content. This screen opens with its
    /// own segmented control at the very top, so the two landed on the same pixels:
    /// reproduced on an iPad Air 11-inch (M3) simulator, where "Messages | Groupes"
    /// rendered underneath the tab bar and read as "Mes…pes". That is the crop the
    /// reviewer saw, and it is why the iPhone looked fine to us for a whole day.
    ///
    /// A regular horizontal size class is the signal, not `UIDevice.isPad`: the
    /// same collision appears in a wide split view, and the same absence of it
    /// applies to a compact-width iPad window.
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    ///
    /// __FOLDABLE_LAYOUT_2026_11_16__ Not in the two-pane layout: there the
    /// system DOES inset the content below the floating bar (measured on an iPad
    /// mini simulator: with the clearance the segment sat 56 pt lower than the
    /// bar's own bottom, an empty band across both panes).
    private var topBarClearance: CGFloat {
        horizontalSizeClass == .regular && !splitActive ? 56 : 0
    }

    // __FOLDABLE_LAYOUT_2026_11_16__ see AdaptiveThreadLayout.swift.
    @StateObject private var threadSelection = ThreadSelection()
    @State private var containerWidth: CGFloat = 0

    /// Wide enough for the list and the open thread side by side (an open
    /// iPhone Duo, an iPad, a Mac window, a Pro Max on its side).
    private var layoutIsWide: Bool {
        ThreadSplitMetrics.isWide(sizeClass: horizontalSizeClass, width: containerWidth)
    }

    /// __DUO_POLISH_2026_11_18__ Email splits too now (inbox | message), with
    /// its own selection — except while there is no mailbox yet: the claim-an-
    /// address screen is a single page and gets the full width.
    @StateObject private var mailSelection = MailSelection()

    private var splitActive: Bool {
        layoutIsWide && (selectedSegment != 2 || mail.account != nil)
    }

    var body: some View {
        VStack(spacing: 0) {
            Color.clear
                .frame(height: topBarClearance)
                .accessibilityHidden(true)

            // The list column keeps the SAME position in the hierarchy in both
            // layouts (first child of this HStack, only its width changes), so
            // folding does not rebuild the list, its search text or scroll.
            HStack(spacing: 0) {
                listColumn
                    .frame(width: splitActive ? ThreadSplitMetrics.sidebarWidth(for: containerWidth) : nil)

                if splitActive {
                    Divider()
                        .ignoresSafeArea(edges: .bottom)
                    if selectedSegment == 2 {
                        MailDetailPane(selection: mailSelection)
                            .frame(maxWidth: .infinity)
                    } else {
                        ThreadDetailPane()
                            .frame(maxWidth: .infinity)
                    }
                }
            }
        }
        .measureWidth(into: $containerWidth)
        .environmentObject(threadSelection)
        .environment(\.threadSplitActive, splitActive)
        .environment(\.mailSelection, mailSelection)
        .onChange(of: layoutIsWide) { _, wide in
            // Folding with a thread open: the phone layout can only show the
            // thread from the list that owns it, so bring that list forward.
            // (Email stays where it is — the thread is kept for the next unfold.)
            guard !wide, selectedSegment != 2, let route = threadSelection.route else { return }
            selectedSegment = ChatsSegments.tag(for: route.kind, merged: mergedChats)
        }
        .onChange(of: selectedSegment) { _, segment in
            // Phone behaviour: switching segment drops what was pushed in the
            // other list, as it did when each list owned its stack. Wide, the
            // open thread stays in the detail pane.
            guard !layoutIsWide, let route = threadSelection.route else { return }
            let owner = ChatsSegments.tag(for: route.kind, merged: mergedChats)
            if segment != owner { threadSelection.route = nil }
        }
        // Turning the option on while "Groups" is selected: that segment is gone,
        // its groups are in the merged list.
        .onChange(of: mergedChats) { _, merged in
            if merged, selectedSegment == 1 { selectedSegment = 0 }
        }
    }

    @ViewBuilder
    private var listColumn: some View {
        VStack(spacing: 0) {
            // Custom segmented control with badge support
            HStack(spacing: 0) {
                if mergedChats {
                    segmentButton(
                        title: "chats.merged.segment".localized,
                        badgeCount: messagesUnreadCount + groupUnreadCount,
                        tag: 0
                    )
                    .accessibilityIdentifier("segment_chats")
                    segmentButton(
                        title: "tab_email".localized,
                        badgeCount: mailUnreadCount,
                        tag: 2
                    )
                    .accessibilityIdentifier("segment_email")
                } else {
                segmentButton(
                    title: "tab_messages".localized,
                    badgeCount: messagesUnreadCount,
                    tag: 0
                )
                .accessibilityIdentifier("segment_messages")
                segmentButton(
                    title: "tab_groups".localized,
                    badgeCount: groupUnreadCount,
                    tag: 1
                )
                .accessibilityIdentifier("segment_groups")
                segmentButton(
                    title: "tab_email".localized,
                    badgeCount: mailUnreadCount,
                    tag: 2
                )
                .accessibilityIdentifier("segment_email")
                }
            }
            // __GLASS_ALL_THEMES_2026_11_21__ a glass strip with glass on.
            .oledGlassCard(RoundedRectangle(cornerRadius: 8, style: .continuous),
                           fallback: Color(.systemGray5), blur: true)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)

            // Content
            if selectedSegment == 0 && mergedChats {
                MessagesListView(pendingConversationId: $pendingConversationId,
                                 merged: true,
                                 pendingGroupId: $pendingGroupId)
            } else if selectedSegment == 0 {
                MessagesListView(pendingConversationId: $pendingConversationId)
            } else if selectedSegment == 1 {
                GroupsListView(pendingGroupId: $pendingGroupId)
            } else {
                // Email as a proper view, right after Groups — not buried in a menu.
                MailView(deepLinkMessageId: $pendingMailMessageId)
                    .environmentObject(identityManager)
            }
        }
        .glassAwareGround(Color.clear)  // __GLASS_ALL_THEMES_2026_11_21__
        // Auto-switch to Messages when a conversation needs to open
        .onChange(of: pendingConversationId) { _, newValue in
            if newValue != nil {
                selectedSegment = 0
            }
        }
        // Auto-switch to Groups when a group needs to open
        .onChange(of: pendingGroupId) { _, newValue in
            if newValue != nil {
                selectedSegment = mergedChats ? 0 : 1
            }
        }
        // Auto-switch to Email when a new-email tap needs to open a message. MailView
        // (now mounted) reads the same binding and pushes the message, then clears it.
        .onChange(of: pendingMailMessageId) { _, newValue in
            if newValue != nil {
                selectedSegment = 2
            }
        }
        // Populate the unread-mail count at app open so the Email badge (and the mail
        // slice of the app icon badge, via refreshInbox → messages.didSet) is correct
        // before the user ever taps the Email segment. Quietly no-ops without a mailbox.
        // __MAIL_UNREAD_REFRESH_2026_11_22__ This used to be the ONLY mail fetch outside
        // MailView: once per process, never retried (offline at launch ⇒ 0 for good), never
        // repeated on foreground / network return / mail push. MailUnreadRefresher owns
        // that now; start() is idempotent (the app root usually got there first).
        .task {
            MailUnreadRefresher.shared.start(identity: identityManager)
        }
    }

    // MARK: - Segment Button with Badge

    private func segmentButton(title: String, badgeCount: Int, tag: Int) -> some View {
        Button {
            withAnimation(.easeInOut(duration: 0.15)) {
                selectedSegment = tag
            }
        } label: {
            HStack(spacing: 4) {
                Text(title)
                    .font(.subheadline)
                    .fontWeight(selectedSegment == tag ? .semibold : .regular)
                if badgeCount > 0 {
                    Text("\(badgeCount)")
                        .font(.caption2)
                        .fontWeight(.bold)
                        .foregroundColor(.white)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(Capsule().fill(brandColor))
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 7)
            .background(
                RoundedRectangle(cornerRadius: 7)
                    .fill(selectedSegment == tag ? Color(.systemBackground) : Color.clear)
                    .shadow(color: selectedSegment == tag ? .black.opacity(0.08) : .clear, radius: 1, y: 1)
            )
            .padding(2)
            // __SEGMENT_HIT_AREA_2026_10_26__ Without this the only tappable
            // thing in the segment is the GLYPHS of the title: the `.frame`
            // widens the label but adds no hit-testable view, and the rounded
            // background is `Color.clear` on exactly the segment you need to
            // press — the unselected one. A fingertip hides the bug; a mouse
            // pointer on macOS does not, which is where it was reported.
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundColor(selectedSegment == tag ? .primary : .secondary)
    }
}
