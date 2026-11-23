//
//  WatchScreens.swift
//  OSHI Watch
//
//  __APPLE_WATCH_2027__ List → thread → reply, plus the status screens.
//  Pure black ground, glass panes, Digital Crown scrolls every screen (they
//  are all `List` / `ScrollView`), Dynamic Type everywhere (text styles, no
//  fixed point sizes on text), one combined VoiceOver element per row/bubble.
//

import SwiftUI
import WatchKit

// MARK: - Root

struct WatchRootView: View {
    @EnvironmentObject var store: WatchStore
    @State private var path: [WatchConversationRef] = []

    var body: some View {
        #if DEBUG
        if WatchFakeData.showWidgetGallery {
            WidgetGalleryView()
        } else {
            stack
        }
        #else
        stack
        #endif
    }

    private var stack: some View {
        NavigationStack(path: $path) {
            ConversationListView()
                .navigationDestination(for: WatchConversationRef.self) { ref in
                    ThreadView(ref: ref)
                }
        }
        .background(Color.black.ignoresSafeArea())
        .onChange(of: store.pendingOpen) { _, ref in
            guard let ref else { return }
            path = [ref]
            store.pendingOpen = nil
        }
        .onAppear {
            if let ref = store.pendingOpen {
                path = [ref]
                store.pendingOpen = nil
            }
        }
    }
}

// MARK: - Status screen

/// One screen for every "no data" state: a mascot, a title, one sentence, Retry.
struct StatusScreen: View {
    let mascot: WatchMascot
    let title: String
    let message: String
    var retry: (() -> Void)? = nil

    var body: some View {
        ScrollView {
            VStack(spacing: 5) {
                WatchMascotView(mascot: mascot, size: 52)
                Text(title)
                    .font(.headline)
                    .multilineTextAlignment(.center)
                    .foregroundColor(.white)
                    .fixedSize(horizontal: false, vertical: true)
                Text(message)
                    .font(.footnote)
                    .multilineTextAlignment(.center)
                    .foregroundColor(WatchTheme.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
                if let retry {
                    Button(action: retry) {
                        Text(WS.retry).font(.body.weight(.semibold)).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(WatchTheme.brand)
                    .padding(.top, 4)
                }
            }
            .padding(.horizontal, 4)
            .accessibilityElement(children: .contain)
        }
    }

    static func mascot(for s: WatchStatus) -> WatchMascot {
        switch s {
        case .disabled: return .peek
        case .locked: return .sleep
        default: return .thinking
        }
    }
}

// MARK: - List

struct ConversationListView: View {
    @EnvironmentObject var store: WatchStore

    var body: some View {
        content
            .navigationTitle(WS.chats)
            .containerBackground(Color.black.gradient, for: .navigation)
            .task { await store.refreshList() }
    }

    @ViewBuilder private var content: some View {
        switch store.listState {
        case .loading where store.conversations.isEmpty:
            ProgressView(WS.loading)
        case .status(let s):
            StatusScreen(mascot: StatusScreen.mascot(for: s), title: WS.statusTitle(s),
                         message: WS.statusBody(s)) { Task { await store.refreshList() } }
        case .noPhone where store.conversations.isEmpty:
            StatusScreen(mascot: .thinking, title: WS.noPhoneTitle, message: WS.noPhoneBody) {
                Task { await store.refreshList() }
            }
        default:
            if store.conversations.isEmpty {
                StatusScreen(mascot: .wave, title: WS.emptyTitle, message: WS.emptyBody) {
                    Task { await store.refreshList() }
                }
            } else {
                List {
                    if store.listState == .noPhone {
                        Label(WS.noPhoneTitle, systemImage: "iphone.slash")
                            .font(.footnote)
                            .foregroundColor(.orange)
                            .listRowBackground(Color.clear)
                    }
                    ForEach(store.conversations) { c in
                        NavigationLink(value: WatchConversationRef(c)) {
                            ConversationRow(c: c)
                        }
                        .listRowBackground(
                            RoundedRectangle(cornerRadius: 22, style: .continuous)
                                .fill(Color.clear)
                                .glass(RoundedRectangle(cornerRadius: 22, style: .continuous))
                        )
                    }
                }
                .refreshable { await store.refreshList() }
            }
        }
    }
}

struct ConversationRow: View {
    let c: WatchConversation
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        // At accessibility text sizes a name gets two lines and the avatar
        // shrinks, instead of truncating every name to three letters.
        let big = typeSize.isAccessibilitySize
        HStack(spacing: big ? 6 : 8) {
            WatchAvatar(key: c.akey, name: c.name, isGroup: c.kind == .group, size: big ? 26 : 34)
            VStack(alignment: .leading, spacing: 1) {
                Text(c.name)
                    .font(.headline)
                    .lineLimit(big ? 2 : 1)
                    .foregroundColor(.white)
                Text(previewLine)
                    .font(.footnote)
                    .lineLimit(big ? 2 : 1)
                    .foregroundColor(WatchTheme.secondaryText)
            }
            Spacer(minLength: 2)
            if c.unread > 0 {
                Text(c.unread > 99 ? "99+" : "\(c.unread)")
                    .font(.caption2.weight(.bold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 6)
                    .frame(minWidth: 22, minHeight: 22)
                    .background(Capsule().fill(WatchTheme.brand))
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
    }

    var previewLine: String { Self.preview(c) }

    static func preview(_ c: WatchConversation) -> String {
        var text = c.preview
        if c.pvo {
            text = WS.viewOnceShort
        } else if let m = c.pmedia {
            let label = WS.media(m)
            if text.isEmpty { text = label }
            else if text.hasSuffix(": ") { text += label }
            else { text = label + " · " + text }
        }
        return c.mine ? WS.youPrefix(text) : text
    }

    private var accessibilityText: String {
        var parts = [c.name]
        if c.unread > 0 { parts.append(WS.unreadCount(c.unread)) }
        parts.append(previewLine)
        return parts.joined(separator: ", ")
    }
}

// MARK: - Thread

struct ThreadView: View {
    let ref: WatchConversationRef
    @EnvironmentObject var store: WatchStore
    @EnvironmentObject var callManager: WatchCallManager
    @State private var replySheet: ReplyTarget?

    private var model: ThreadModel { store.threads[ref.id] ?? ThreadModel(title: ref.name) }

    var body: some View {
        content
            .navigationTitle(model.title.isEmpty ? ref.name : model.title)
            .navigationBarTitleDisplayMode(.inline)
            .containerBackground(Color.black.gradient, for: .navigation)
            .toolbar {
                if ref.kind == .direct, !model.readOnly, model.state == .ready {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            callManager.startCall(to: ref.cid, recipientName: model.title)
                        } label: {
                            Image(systemName: "phone.fill")
                        }
                        .tint(.green)
                        .accessibilityLabel(Text(WS.call))
                        .disabled(callManager.isInCall)
                    }
                }
            }
            .task {
                await store.loadThread(ref)
                if WatchFakeData.autoOpenReply, replySheet == nil {
                    replySheet = ReplyTarget(message: lastIncoming, quote: false)
                }
            }
            .sheet(item: $replySheet) { target in
                ReplyView(ref: ref, target: target)
            }
    }

    @ViewBuilder private var content: some View {
        switch model.state {
        case .loading where model.messages.isEmpty:
            ProgressView(WS.loading)
        case .status(let s):
            StatusScreen(mascot: StatusScreen.mascot(for: s), title: WS.statusTitle(s),
                         message: WS.statusBody(s)) { Task { await store.loadThread(ref) } }
        case .noPhone where model.messages.isEmpty:
            StatusScreen(mascot: .thinking, title: WS.noPhoneTitle, message: WS.noPhoneBody) {
                Task { await store.loadThread(ref) }
            }
        default:
            messagesView
        }
    }

    private var lastIncoming: WatchMessage? { model.messages.last(where: { !$0.mine && !$0.vo }) }

    private var messagesView: some View {
        ScrollViewReader { proxy in
            ScrollView {
                // A plain VStack: at most 20 bubbles cross the bridge.
                VStack(spacing: 6) {
                    ForEach(model.messages) { m in
                        BubbleView(m: m, isGroup: ref.kind == .group)
                            .id(m.mid)
                            .onTapGesture {
                                guard !model.readOnly, !m.vo else { return }
                                replySheet = ReplyTarget(message: m)
                            }
                            .accessibilityAddTraits(model.readOnly || m.vo ? [] : .isButton)
                            .accessibilityAction(named: Text(WS.react)) {
                                if !model.readOnly && !m.vo { replySheet = ReplyTarget(message: m) }
                            }
                    }
                    if model.readOnly {
                        Text(WS.readOnly)
                            .font(.footnote)
                            .foregroundColor(WatchTheme.secondaryText)
                            .multilineTextAlignment(.center)
                            .padding(.top, 4)
                    } else {
                        Button {
                            WKInterfaceDevice.current().play(.click)
                            replySheet = ReplyTarget(message: lastIncoming, quote: false)
                        } label: {
                            Label(WS.reply, systemImage: "arrowshape.turn.up.left.fill")
                                .font(.body.weight(.semibold))
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(WatchTheme.brand)
                        .padding(.top, 4)
                        .id("reply")
                    }
                }
                .padding(.horizontal, 2)
            }
            .onAppear {
                if WatchFakeData.scroll == "top", let first = model.messages.first?.mid {
                    proxy.scrollTo(first, anchor: .top)
                } else {
                    proxy.scrollTo(model.readOnly ? model.messages.last?.mid : "reply", anchor: .bottom)
                }
            }
            .onChange(of: model.messages.count) { _, _ in
                withAnimation { proxy.scrollTo("reply", anchor: .bottom) }
            }
        }
    }
}

struct BubbleView: View {
    let m: WatchMessage
    let isGroup: Bool

    var body: some View {
        HStack {
            if m.mine { Spacer(minLength: 18) }
            VStack(alignment: m.mine ? .trailing : .leading, spacing: 2) {
                VStack(alignment: .leading, spacing: 3) {
                    if isGroup, !m.mine, let sender = m.sender {
                        Text(sender)
                            .font(.caption2.weight(.semibold))
                            .foregroundColor(AvatarIdentity.nameColorsDark[AvatarIdentity.slot(for: m.skey ?? sender)])
                            .lineLimit(1)
                    }
                    if m.vo {
                        Text(WS.viewOnce)
                            .font(.footnote.italic())
                            .foregroundColor(m.mine ? .white : WatchTheme.secondaryText)
                    } else {
                        if let media = m.media {
                            Text(WS.media(media))
                                .font(.footnote.weight(.medium))
                                .foregroundColor(.white)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .background(Capsule().fill(Color.white.opacity(m.mine ? 0.18 : 0.10)))
                        }
                        if !m.text.isEmpty {
                            Text(m.text)
                                .font(.body)
                                .foregroundColor(.white)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .glass(RoundedRectangle(cornerRadius: 16, style: .continuous),
                       tint: m.mine ? AnyShapeStyle(WatchTheme.mineFill) : nil)

                if !m.reacts.isEmpty {
                    HStack(spacing: 2) {
                        ForEach(m.reacts, id: \.self) { r in
                            ReactionGlyph(wire: r, size: 18)
                        }
                    }
                    .padding(.horizontal, 5)
                    .padding(.vertical, 2)
                    .glass(Capsule())
                    .offset(y: -6)
                    .padding(.bottom, -6)
                }
            }
            if !m.mine { Spacer(minLength: 18) }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
    }

    private var accessibilityText: String {
        var parts: [String] = [m.mine ? WS.you : (m.sender ?? "")].filter { !$0.isEmpty }
        if m.vo { parts.append(WS.viewOnce) }
        if let media = m.media { parts.append(WS.media(media)) }
        if !m.text.isEmpty { parts.append(m.text) }
        if !m.reacts.isEmpty { parts.append(m.reacts.map(WS.reactionName).joined(separator: " ")) }
        let time = Date(timeIntervalSince1970: m.ts).formatted(date: .omitted, time: .shortened)
        parts.append(time)
        return parts.joined(separator: ", ")
    }
}

// MARK: - Reply

struct ReplyTarget: Identifiable {
    let message: WatchMessage?
    /// true = the user tapped THIS bubble: the text reply quotes it.
    var quote = true
    var id: String { (message?.mid ?? "none") + (quote ? ".q" : "") }
}

struct ReplyView: View {
    let ref: WatchConversationRef
    let target: ReplyTarget
    @EnvironmentObject var store: WatchStore
    @Environment(\.dismiss) private var dismiss
    @State private var phase: Phase = .idle
    @State private var animateReaction: String?

    enum Phase: Equatable { case idle, sending, sent, failed(String) }

    private var reactTarget: WatchMessage? { target.message }

    var body: some View {
        ZStack {
            // Opaque: the sheet's default material let the thread (and the
            // green call button) bleed through as coloured blur.
            Color.black.ignoresSafeArea()
            ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 8) {
                    if target.quote, let q = target.message {
                        quoteView(q)
                    }
                    TextFieldLink(prompt: Text(WS.speakOrType)) {
                        Label(WS.speakOrType, systemImage: "mic.fill")
                            .font(.body.weight(.semibold))
                            .frame(maxWidth: .infinity, minHeight: 40)
                            .foregroundColor(.white)
                            .background(Capsule().fill(WatchTheme.brand))
                    } onSubmit: { text in
                        send(text)
                    }
                    .buttonStyle(.plain)

                    if let msg = reactTarget {
                        sectionHeader(WS.react)
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 4), spacing: 6) {
                            ForEach(WatchBridge.quickReactions, id: \.self) { r in
                                reactionButton(r, on: msg)
                            }
                        }
                    }

                    sectionHeader(WS.quickReplies)
                        .id("quick")
                    ForEach(WS.quickReplyList, id: \.self) { q in
                        Button { send(q) } label: {
                            Text(q)
                                .font(.body)
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 9)
                                .glass(Capsule())
                        }
                        .buttonStyle(.plain)
                    }

                    Label(WS.endToEnd, systemImage: "lock.fill")
                        .font(.caption2)
                        .foregroundColor(WatchTheme.secondaryText)
                        .multilineTextAlignment(.center)
                        .padding(.top, 4)
                }
                .padding(.horizontal, 2)
                .disabled(phase == .sending)
            }
            .onAppear {
                if WatchFakeData.scroll == "bottom" { proxy.scrollTo("quick", anchor: .top) }
            }
            }
            if phase != .idle { phaseOverlay }
        }
        .containerBackground(Color.black.gradient, for: .navigation)
        .navigationTitle(ref.name)
    }

    private func sectionHeader(_ s: String) -> some View {
        Text(s)
            .font(.footnote.weight(.semibold))
            .foregroundColor(WatchTheme.secondaryText)
            .frame(maxWidth: .infinity)
            .padding(.top, 6)
            .accessibilityAddTraits(.isHeader)
    }

    private func quoteView(_ q: WatchMessage) -> some View {
        HStack(alignment: .top, spacing: 6) {
            RoundedRectangle(cornerRadius: 1).fill(WatchTheme.lilac).frame(width: 2)
            VStack(alignment: .leading, spacing: 1) {
                Text(WS.replyingTo).font(.caption2).foregroundColor(WatchTheme.lilac)
                Text(q.text.isEmpty ? (q.media.map(WS.media) ?? "") : q.text)
                    .font(.footnote)
                    .foregroundColor(WatchTheme.secondaryText)
                    .lineLimit(2)
            }
            Spacer(minLength: 0)
        }
        .fixedSize(horizontal: false, vertical: true)
        .padding(8)
        .glass(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    private func reactionButton(_ r: String, on msg: WatchMessage) -> some View {
        let selected = msg.myr == r
        return Button {
            react(r, on: msg)
        } label: {
            ReactionGlyph(wire: r, size: 30, animated: animateReaction == r)
                .frame(width: 40, height: 40)
                .background(Circle().fill(selected ? WatchTheme.brand.opacity(0.55) : WatchTheme.pane))
                .overlay(Circle().strokeBorder(selected ? WatchTheme.lilac : Color.white.opacity(0.12),
                                               lineWidth: selected ? 1.5 : 0.75))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(WS.reactionName(r)))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    @ViewBuilder private var phaseOverlay: some View {
        VStack(spacing: 6) {
            switch phase {
            case .sending:
                ProgressView()
                Text(WS.sending).font(.footnote)
            case .sent:
                WatchMascotView(mascot: .ok, size: 64)
                Text(WS.sent).font(.headline)
            case .failed(let why):
                WatchMascotView(mascot: .cry, size: 52, animated: false)
                Text(why).font(.footnote).multilineTextAlignment(.center)
                Button(WS.retry) { phase = .idle }
                    .buttonStyle(.borderedProminent)
                    .tint(WatchTheme.brand)
            case .idle:
                EmptyView()
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black.opacity(0.92))
        .accessibilityElement(children: .combine)
    }

    private func explain(_ s: WatchStatus) -> String {
        switch s {
        case .disabled, .locked, .readOnly, .notFound: return WS.statusBody(s)
        default: return WS.sendFailed
        }
    }

    private func send(_ raw: String) {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, phase != .sending else { return }
        phase = .sending
        Task {
            let quote = target.quote ? target.message?.mid : nil
            let failure = await store.send(text, to: ref, replyTo: quote)
            finish(failure)
        }
    }

    private func react(_ r: String, on msg: WatchMessage) {
        guard phase != .sending else { return }
        animateReaction = r
        WKInterfaceDevice.current().play(.click)
        phase = .sending
        Task {
            let failure = await store.react(r, to: msg.mid, in: ref)
            finish(failure)
        }
    }

    @MainActor private func finish(_ failure: WatchStatus?) {
        if let failure {
            WKInterfaceDevice.current().play(.failure)
            phase = .failed(explain(failure))
        } else {
            WKInterfaceDevice.current().play(.success)
            phase = .sent
            Task {
                try? await Task.sleep(nanoseconds: 900_000_000)
                dismiss()
            }
        }
    }
}
