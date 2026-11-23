//
//  AdaptiveThreadLayout.swift
//  OSHI
//
//  __FOLDABLE_LAYOUT_2026_11_16__ One conversation, whatever the width.
//
//  iPhone Duo (iOS 27.1) reports compact width when closed and REGULAR width
//  and height when open, and the app is resized live as the device is opened or
//  closed (Apple, "Prepare your app for iPhone Duo"). The same happens on iPad
//  in Split View / Stage Manager, and on an iPhone Pro Max turned sideways.
//
//  Before this file, the Chats tab was one `NavigationStack` per list, with
//  `NavigationLink(destination:)` rows. That is fine on a phone and wrong on an
//  8" canvas (one list stretched across the whole width, the chat a push away),
//  and it hides WHICH thread is open inside UIKit, so nothing could carry it
//  from one layout to the other.
//
//  Now the open thread is a value — `ThreadSelection.route` — owned by the
//  Chats tab:
//    • narrow (compact, or regular but too narrow to share): the lists' stacks
//      derive their path from it, so the phone push/pop is unchanged;
//    • wide: the list stays in a sidebar and the same route is drawn in a
//      detail pane next to it.
//  Folding or unfolding swaps which of the two draws the route; the route
//  itself does not move, so the same thread stays open.
//
//  A thread view's `@State` cannot survive moving between a pushed stack and a
//  detail pane (different places in the hierarchy). What the reader would lose
//  — the text being typed, the keyboard, the place in the history — is handed
//  across by `ThreadHandoff`, in memory only, for a few seconds.
//

import SwiftUI

// MARK: - Route

/// Which thread is open in the Chats tab.
enum ThreadRoute: Hashable {
    /// A 1:1 conversation, by `MessageManager.normalizeAliasKey(participantPublicKey)`.
    case conversation(String)
    /// A group, by id.
    case group(UUID)

    enum Kind { case conversation, group }

    var kind: Kind {
        switch self {
        case .conversation: return .conversation
        case .group: return .group
        }
    }

    static func conversation(for conversation: Conversation) -> ThreadRoute {
        .conversation(MessageManager.normalizeAliasKey(conversation.participantPublicKey))
    }

    /// Key shared by the draft / focus / scroll hand-off.
    var handoffKey: String {
        switch self {
        case .conversation(let key): return "c:" + key
        case .group(let id): return "g:" + id.uuidString
        }
    }
}

// MARK: - Selection

/// The open thread of one Chats tab (one per window: iPhone Duo and iPad can
/// show two windows of the app side by side, each with its own selection).
/// Main thread only (SwiftUI).
final class ThreadSelection: ObservableObject {
    @Published var route: ThreadRoute?

    /// The last values seen for each route, so a thread that is momentarily
    /// missing from the manager (a list republish, a conversation created a
    /// beat before it is inserted) still resolves instead of blanking the pane.
    private(set) var conversationSnapshots: [String: Conversation] = [:]
    private(set) var groupSnapshots: [UUID: MessageGroup] = [:]

    func open(_ conversation: Conversation) {
        let route = ThreadRoute.conversation(for: conversation)
        if case .conversation(let key) = route { conversationSnapshots[key] = conversation }
        self.route = route
    }

    func open(_ group: MessageGroup) {
        groupSnapshots[group.id] = group
        route = .group(group.id)
    }

    func remember(_ conversation: Conversation) {
        conversationSnapshots[MessageManager.normalizeAliasKey(conversation.participantPublicKey)] = conversation
    }

    func remember(_ group: MessageGroup) { groupSnapshots[group.id] = group }

    func isOpen(_ conversation: Conversation) -> Bool {
        route == .conversation(for: conversation)
    }

    /// The path a list's `NavigationStack` shows for `kind`.
    ///
    /// Narrow: the open thread of that kind is pushed, and popping it (back
    /// button, edge swipe) closes it. Wide: the stack stays at its root — the
    /// thread is in the detail pane — and a tap on a row only selects it.
    func path(for kind: ThreadRoute.Kind, split: Bool) -> Binding<[ThreadRoute]> {
        Binding(
            get: { [weak self] in
                guard !split, let route = self?.route, route.kind == kind else { return [] }
                return [route]
            },
            set: { [weak self] newPath in
                guard let self else { return }
                if let last = newPath.last {
                    if self.route != last { self.route = last }
                } else if !split, self.route?.kind == kind {
                    self.route = nil
                }
            }
        )
    }

    /// __MERGED_CHATS_2026_11_18__ The path of the merged chat list, which owns
    /// threads of BOTH kinds: whatever is open is pushed (narrow) or drawn in
    /// the detail pane (wide), exactly like `path(for:split:)` does per kind.
    func pathForAnyKind(split: Bool) -> Binding<[ThreadRoute]> {
        Binding(
            get: { [weak self] in
                guard !split, let route = self?.route else { return [] }
                return [route]
            },
            set: { [weak self] newPath in
                guard let self else { return }
                if let last = newPath.last {
                    if self.route != last { self.route = last }
                } else if !split, self.route != nil {
                    self.route = nil
                }
            }
        )
    }
}

// MARK: - Layout metrics

enum ThreadSplitMetrics {
    /// Below this width a regular-width window keeps the phone layout.
    ///
    /// iPhone Duo's inner display: 2007 × 2853 px @3x = 669 × 951 pt (the
    /// simulator's device profile). Apple says it reports a regular width
    /// there and that it has room for a sidebar, so the split is on at 669.
    /// __DUO_POLISH_2026_11_18__ Raised from 600: with a 300 pt sidebar, a
    /// 600 pt window left a 299 pt chat — narrower than any iPhone. At 640
    /// the chat keeps ~340 pt; a narrower regular window (Stage Manager, an
    /// iPad Split View third) is a phone layout.
    static let minimumSplitWidth: CGFloat = 640

    /// Sidebar: about 40 % of the window, never under 300 pt nor over 380 pt
    /// (the row has nothing more to show, the space belongs to the chat).
    ///
    /// __DUO_POLISH_2026_11_18__ The floor was 280: on the open iPhone Duo held
    /// upright (669 pt) that is what it got, and a conversation row's preview
    /// wrapped after two or three words beside its 52 pt avatar. 300 | 368
    /// leaves the chat an iPhone mini's width. Tried and dropped: halving the
    /// window there (334 | 334, divider on the centre fold) — the group
    /// composer's text field shrank to ~50 pt beside its six buttons.
    /// Landscape (867 pt beside the rail) gets 40 % = 347 pt.
    static func sidebarWidth(for width: CGFloat) -> CGFloat {
        guard width > 0 else { return 320 }
        return min(max((width * 0.40).rounded(), 300), 380)
    }

    static func isWide(sizeClass: UserInterfaceSizeClass?, width: CGFloat) -> Bool {
        guard sizeClass == .regular else { return false }
        // Width not measured yet (first frame): trust the size class, so an
        // iPad or an open iPhone Duo does not start in the phone layout and
        // then jump.
        return width == 0 || width >= minimumSplitWidth
    }
}

// MARK: - Environment

private struct ThreadSplitActiveKey: EnvironmentKey {
    static let defaultValue = false
}

/// The width the thread's message column has. 0 = not measured yet.
private struct ChatColumnWidthKey: EnvironmentKey {
    static let defaultValue: CGFloat = 0
}

extension EnvironmentValues {
    /// True while the Chats tab shows the list and the open thread side by side.
    var threadSplitActive: Bool {
        get { self[ThreadSplitActiveKey.self] }
        set { self[ThreadSplitActiveKey.self] = newValue }
    }

    /// Width of the chat the bubbles are laid out in — NOT the screen. On iPhone
    /// Duo "the main screen" is ambiguous (two displays; Apple: avoid it), and
    /// in a detail pane, a Split View or a Mac window the screen is wider than
    /// the thread. Bubble caps are a fraction of this.
    var chatColumnWidth: CGFloat {
        get { self[ChatColumnWidthKey.self] }
        set { self[ChatColumnWidthKey.self] = newValue }
    }
}

extension View {
    /// __DUO_POLISH_2026_11_18__ For the root of a list column's
    /// NavigationStack while the window is split.
    ///
    /// Measured on the iPhone Duo simulator (iOS 27.2, open, landscape): the
    /// window's trailing safe area (84 pt, the side rail for the status bar
    /// and toolbar items) reached the hosting view INSIDE the list column's
    /// NavigationStack even though the column (x 0…347) is nowhere near that
    /// edge — the stack's own container reported 0. Every row then kept an
    /// empty 84 pt band on its right: a 347 pt sidebar showed 263 pt of list.
    /// The column never touches the trailing edge when split, so there is no
    /// real inset to keep. Phone layout (one column up to the edge): untouched.
    func sidebarColumnContent(_ split: Bool) -> some View {
        ignoresSafeArea(.container, edges: split ? .trailing : [])
    }

    /// Measures this view's width into `width` (iOS 17-compatible), only
    /// writing when it changes by at least a point.
    func measureWidth(into width: Binding<CGFloat>) -> some View {
        background(
            GeometryReader { geo in
                Color.clear
                    .onAppear { if abs(width.wrappedValue - geo.size.width) >= 1 { width.wrappedValue = geo.size.width } }
                    .onChange(of: geo.size.width) { _, new in
                        if abs(width.wrappedValue - new) >= 1 { width.wrappedValue = new }
                    }
            }
        )
    }

    /// Measures the thread's width and hands it to the bubbles below.
    func providesChatColumnWidth(_ width: Binding<CGFloat>) -> some View {
        measureWidth(into: width)
            .environment(\.chatColumnWidth, width.wrappedValue)
    }
}

// MARK: - Destination

/// The thread for a route: the pushed view on a phone, the detail pane when wide.
struct ThreadDestinationView: View {
    let route: ThreadRoute
    @EnvironmentObject private var selection: ThreadSelection
    @EnvironmentObject private var messageManager: MessageManager
    @EnvironmentObject private var messageGroupManager: MessageGroupManager

    var body: some View {
        switch route {
        case .conversation(let key):
            if let conversation = resolveConversation(key) {
                ChatView(conversation: conversation)
            } else {
                ThreadPlaceholderView()
            }
        case .group(let id):
            if let group = messageGroupManager.groups.first(where: { $0.id == id }) {
                GroupChatView(group: group)
            } else if messageGroupManager.groups.isEmpty, let group = selection.groupSnapshots[id] {
                // Groups not loaded yet (cold start from a notification).
                GroupChatView(group: group)
            } else {
                // Left or deleted while it was open.
                ThreadPlaceholderView()
            }
        }
    }

    private func resolveConversation(_ key: String) -> Conversation? {
        if let live = messageManager.conversations.first(where: {
            MessageManager.normalizeAliasKey($0.participantPublicKey) == key
        }) {
            return live
        }
        return selection.conversationSnapshots[key]
    }
}

/// The right-hand pane of the wide layout.
struct ThreadDetailPane: View {
    @EnvironmentObject private var selection: ThreadSelection

    var body: some View {
        // A fresh stack per thread: whatever was pushed inside the previous
        // thread (a contact card, a media list) must not stay on top of the next.
        NavigationStack {
            if let route = selection.route {
                ThreadDestinationView(route: route)
            } else {
                ThreadPlaceholderView()
            }
        }
        .id(selection.route)
        .accessibilityIdentifier("thread.detail")
    }
}

/// Shown in the detail pane when no thread is open.
struct ThreadPlaceholderView: View {
    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "lock.shield")
                .font(.system(size: 44, weight: .regular))
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            Text("nav_title_messages".localized)
                .font(.title3.weight(.semibold))
            // Both keys ship in all 34 locales.
            Text("chat.encryption_enabled".localized)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemGroupedBackground))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("thread.placeholder")
    }
}

// MARK: - Hand-off

/// What a thread view hands to the view that replaces it when the layout
/// changes (fold, unfold, a Split View resize): the composer text, whether the
/// keyboard was up, and where the reader was in the history.
///
/// In memory only, never written anywhere: a draft is plaintext. An entry is
/// carried only while its thread is on screen or for `window` seconds after it
/// left — i.e. across a re-layout, not across "closed the chat and came back
/// later", which behaves as before (an empty composer). Cleared by a panic wipe.
/// Main thread only.
final class ThreadHandoff {
    static let shared = ThreadHandoff()

    struct Entry {
        var text = ""
        var composerFocused = false
        /// When the composer last lost focus. The view being replaced resigns
        /// its field a moment BEFORE it disappears (UIKit tears the keyboard
        /// down first), so a loss just before the detach still counts as up.
        fileprivate var focusLostAt: Date?
        /// Width of the thread's scroll view at the last report, and when it
        /// last changed. Rows reflow at a new width (the old view is resized
        /// a beat before it is removed): what is "visible" then says nothing
        /// about where the reader was, so reports are ignored for a moment.
        fileprivate var reportWidth: CGFloat = 0
        fileprivate var widthChangedAt: Date?
        /// A row near the top of the viewport, while the reader is scrolled up.
        var readingAnchor: String?
        var atBottom = true
        fileprivate var live: Set<UUID> = []
        fileprivate var closedAt: Date?
    }

    static let window: TimeInterval = 3
    /// A keyboard dropped this recently before a detach was up at the re-layout.
    static let focusGrace: TimeInterval = 1
    /// Position reports ignored this long after the thread's width changed.
    static let reflowQuiet: TimeInterval = 1

    private func inReflow(_ e: Entry) -> Bool {
        e.widthChangedAt.map { Date().timeIntervalSince($0) < Self.reflowQuiet } ?? false
    }

    /// The thread's scroll view width (reported in the same layout pass as
    /// the visibility change it may cause).
    func noteWidth(_ key: String, _ width: CGFloat) {
        guard entries[key] != nil else { return }
        let old = entries[key]!.reportWidth
        if old > 0, abs(old - width) > 1 { entries[key]!.widthChangedAt = Date() }
        entries[key]!.reportWidth = width
    }

    private var entries: [String: Entry] = [:]

    /// A thread view appeared. Returns the state to restore when another view
    /// of the same thread is still on screen or has just left; nil for a
    /// plain open. `currentText` seeds the entry with what this view already
    /// holds (it may be re-appearing after a tab switch with a draft).
    func attach(_ key: String, token: UUID, currentText: String) -> Entry? {
        let now = Date()
        entries = entries.filter { _, e in
            !e.live.isEmpty || (e.closedAt.map { now.timeIntervalSince($0) < Self.window } ?? true)
        }
        let existing = entries[key]
        let others = existing?.live.subtracting([token]) ?? []
        // Another view of this thread is on screen, or one just left (the
        // prune above dropped anything older than `window`).
        let carried = existing.flatMap { e in (!others.isEmpty || e.closedAt != nil) ? e : nil }
        var entry: Entry
        if let carried {
            entry = carried
        } else if let existing, existing.live == [token] {
            entry = existing          // this same view, appearing again
        } else {
            entry = Entry(text: currentText)
        }
        entry.live.insert(token)
        entry.closedAt = nil
        entries[key] = entry
        return carried
    }

    func detach(_ key: String, token: UUID) {
        guard var entry = entries[key] else { return }
        entry.live.remove(token)
        if let lost = entry.focusLostAt, Date().timeIntervalSince(lost) < Self.focusGrace {
            entry.composerFocused = true
            entry.focusLostAt = nil
        }
        if entry.live.isEmpty { entry.closedAt = Date() }
        entries[key] = entry
    }

    func setText(_ key: String, _ text: String) {
        guard entries[key] != nil, entries[key]!.text != text else { return }
        entries[key]!.text = text
    }

    func setComposerFocused(_ key: String, _ focused: Bool) {
        guard entries[key] != nil else { return }
        if focused {
            entries[key]!.composerFocused = true
            entries[key]!.focusLostAt = nil
        } else if entries[key]!.composerFocused {
            entries[key]!.composerFocused = false
            entries[key]!.focusLostAt = Date()
        }
    }

    func setAtBottom(_ key: String, _ atBottom: Bool) {
        if let e = entries[key], inReflow(e) { return }
        entries[key]?.atBottom = atBottom
    }

    func setReadingAnchor(_ key: String, _ anchor: String?) {
        // An empty report is the view LEAVING (its rows are torn down a beat
        // before onDisappear), not the reader moving: keeping it would hand
        // the replacement view nil and land it on the newest message. Reading
        // back to the bottom is carried by `atBottom`.
        guard let anchor else { return }
        guard let e = entries[key], !inReflow(e), e.readingAnchor != anchor else { return }
        entries[key]!.readingAnchor = anchor
    }

    /// The row being read, or nil when the reader is at the newest message.
    /// For a view that stays on screen while its width changes (iPad rotation,
    /// Split View resize): the bubbles reflow under a fixed offset, so the
    /// view re-aims at this row.
    func readingAnchorIfScrolledUp(_ key: String) -> String? {
        guard let e = entries[key], !e.atBottom else { return nil }
        return e.readingAnchor
    }

    /// Panic wipe / identity switch.
    func reset() { entries.removeAll() }

    /// Posted with the thread's key as `object`: the composer of that thread
    /// takes the keyboard (it was up in the view this one replaced).
    static let restoreComposerFocus = Notification.Name("OSHIThreadRestoreComposerFocus")

    /// After the new view's composer exists and the layout change has settled.
    ///
    /// __DUO_POLISH_2026_11_18__ Addressed to ONE view (`token`), not to the
    /// thread: the view being replaced is often still on screen when the ask
    /// lands (measured on a 1:1 chat: its detach came 30 ms after the second
    /// ask). Both composers then took the focus, the old one won, and the
    /// keyboard went down with it a moment later — the 1:1 "keyboard lost on
    /// fold" leftover. The group chat only escaped because its old view
    /// happened to leave sooner.
    static func requestComposerFocus(_ key: String, token: UUID) {
        // Twice: the first ask can land while the size transition is still
        // running and be refused; asking a focused field again is a no-op.
        for delay in [0.45, 1.0] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                NotificationCenter.default.post(name: restoreComposerFocus, object: key,
                                                userInfo: ["token": token])
            }
        }
    }

    /// True when `note` asks THIS view (`key` + `token`) for the keyboard.
    static func isComposerFocusRequest(_ note: Notification, key: String?, token: UUID?) -> Bool {
        guard let key, let token, note.object as? String == key else { return false }
        return note.userInfo?["token"] as? UUID == token
    }

    #if DEBUG
    func entry(_ key: String) -> Entry? { entries[key] }
    #endif
}

// MARK: - Reading position

/// Reports the first message row visible at the top of a thread to
/// `ThreadHandoff` (iOS 18+, where the row container is a scroll-target
/// layout — see `ChatMessageStack(scrollTargets:)`). Fires when the set of
/// visible rows changes, not per frame, and writes into a plain class, so it
/// never re-renders the thread. iOS 17: no report, a re-layout lands on the
/// newest message as an open does.
struct ThreadReadingPositionReporter: ViewModifier {
    let handoffKey: String
    var enabled: Bool = true

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *), enabled {
            content
                .onScrollGeometryChange(for: CGFloat.self, of: { $0.containerSize.width }) { _, w in
                    ThreadHandoff.shared.noteWidth(handoffKey, w)
                }
                .onScrollTargetVisibilityChange(idType: String.self, threshold: 0.5) { ids in
                    ThreadHandoff.shared.setReadingAnchor(handoffKey, ids.first)
                }
        } else {
            content
        }
    }
}

/// Same, for a thread whose rows are identified by `UUID` (groups).
struct ThreadReadingPositionReporterUUID: ViewModifier {
    let handoffKey: String
    var enabled: Bool = true

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *), enabled {
            content
                .onScrollGeometryChange(for: CGFloat.self, of: { $0.containerSize.width }) { _, w in
                    ThreadHandoff.shared.noteWidth(handoffKey, w)
                }
                .onScrollTargetVisibilityChange(idType: UUID.self, threshold: 0.5) { ids in
                    ThreadHandoff.shared.setReadingAnchor(handoffKey, ids.first?.uuidString)
                }
        } else {
            content
        }
    }
}
