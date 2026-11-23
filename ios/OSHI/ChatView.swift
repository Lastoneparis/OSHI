//
//  ChatView.swift
//  ✅ FIXED: Timer updates in real-time, visual feedback, audio preview
//  ✅ FULLY LOCALIZED: All user-facing strings use .localized
//

import SwiftUI
import UniformTypeIdentifiers

// MARK: - Brand Color
private let appBrandColor = Theme.brand
import AVFoundation  // ✅ REQUIRED for audio playback

// MARK: - Cached DateFormatters (perf: created once, reused across all searches)
private let cachedSearchDateFormatters: [DateFormatter] = {
    let formats = [
        "MMM d, yyyy", "MMMM d, yyyy", "MMM d", "MMMM d",
        "MMM yyyy", "MMMM yyyy", "MMM", "MMMM", "yyyy",
        "dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd", "EEEE"
    ]
    return formats.map { fmt in
        let f = DateFormatter()
        f.dateFormat = fmt
        return f
    }
}()

// MARK: - Row-level DateFormatter cache (perf)

/// 🔑 `DateFormatter()` is NOT a cheap allocation. Its init builds a
/// CFDateFormatter, which pulls the locale's date symbols through ICU — tens to
/// hundreds of microseconds, every time.
///
/// 🔴 Every timestamp label in this app built a fresh one *inside a view body*:
/// `ConversationRow` (MessagesListView `formatTimestamp`), `MessageBubble`
/// (`formatTimestamp` below), and all four group bubble variants plus the group
/// date header (`GroupViews.chatFormatTimestamp` / `groupDateLabel`). SwiftUI
/// re-runs those bodies on every publish of `MessageManager` /
/// `MessageGroupManager` — which is every arriving message, every delivery and
/// read receipt, every typing tick and every IPFS poll — so with ~20 rows on
/// screen the app was paying 20 ICU formatter constructions per frame, on the
/// main thread, inside the 16.6 ms budget of an iPhone 14's 60 Hz display.
/// That is dropped frames for work whose *output* never changes.
///
/// One formatter per format string, built once, reused. Formatters are mutable
/// and not thread-safe, but every caller here is a SwiftUI `body` — i.e. the
/// main actor — so there is no cross-thread sharing. `formatted(_:)` never
/// mutates the cached instance; the format is baked in at creation.
///
/// 🔑 The cache is dropped on locale, calendar and time-zone changes. A cached
/// formatter captures the locale/zone it was built with, so without this a user
/// who flies a timezone (or switches language in Settings) would keep seeing
/// timestamps in the old one until the app was killed.
enum OshiRowFormatters {
    private static var cache: [String: DateFormatter] = [:]
    private static var observersInstalled = false

    /// Format `date` with `format`, reusing the formatter built for that format.
    static func string(_ date: Date, format: String) -> String {
        installObserversIfNeeded()
        if let existing = cache[format] {
            return existing.string(from: date)
        }
        let f = DateFormatter()
        f.dateFormat = format
        cache[format] = f
        return f.string(from: date)
    }

    /// Same cache, for the *style*-based formatters (`.medium` date, `.short`
    /// time…). These are the locale-correct ones — a fixed `dateFormat` string
    /// is not — so several call sites legitimately use them and they were
    /// paying the same per-render `DateFormatter()` cost.
    ///
    /// 🔑 The cache key is prefixed with a control character so a style key can
    /// never collide with a caller-supplied `dateFormat` string.
    static func string(_ date: Date,
                       dateStyle: DateFormatter.Style,
                       timeStyle: DateFormatter.Style) -> String {
        installObserversIfNeeded()
        let key = "\u{1}style-\(dateStyle.rawValue)-\(timeStyle.rawValue)"
        if let existing = cache[key] {
            return existing.string(from: date)
        }
        let f = DateFormatter()
        f.dateStyle = dateStyle
        f.timeStyle = timeStyle
        cache[key] = f
        return f.string(from: date)
    }

    private static func installObserversIfNeeded() {
        guard !observersInstalled else { return }
        observersInstalled = true
        let center = NotificationCenter.default
        for name: Notification.Name in [
            NSLocale.currentLocaleDidChangeNotification,
            .NSSystemTimeZoneDidChange
        ] {
            // `_ =`: addObserver(forName:…) returns the observer token, which we
            // deliberately drop — these two observers live for the whole process
            // and there is nothing to deregister. Without the discard the
            // compiler warns about an unused result.
            _ = center.addObserver(forName: name, object: nil, queue: .main) { _ in
                cache.removeAll()
            }
        }
    }
}

// MARK: - Brand Color

/// A media send waiting on the user's OK because, with no internet at all and a
/// LoRa radio ready, it is about to go out over LoRa — slow, and only small
/// media fits. Carries the airtime estimate shown in the confirmation.
private struct PendingLoRaMedia {
    let attachment: MediaManager.MediaAttachment
    let caption: String
    let estimate: (frames: Int, seconds: Int, fits: Bool)
}

/// The slow-LoRa-send confirmation, isolated in a modifier so ChatView's large
/// body does not have to type-check the dialog's closures inline.
private struct LoRaMediaConfirmModifier: ViewModifier {
    @Binding var pending: PendingLoRaMedia?
    let message: (PendingLoRaMedia) -> String
    let onConfirm: (PendingLoRaMedia) -> Void

    func body(content: Content) -> some View {
        content.confirmationDialog(
            "lora.media.confirm.title".localized,
            isPresented: Binding(get: { pending != nil },
                                 set: { if !$0 { pending = nil } }),
            titleVisibility: .visible,
            presenting: pending
        ) { p in
            Button("lora.media.confirm.send".localized) {
                let confirmed = p
                pending = nil
                onConfirm(confirmed)
            }
            Button("common.cancel".localized, role: .cancel) { pending = nil }
        } message: { p in
            Text(message(p))
        }
    }
}

struct ChatView: View {
    let conversation: Conversation

    @EnvironmentObject var messageManager: MessageManager
    @EnvironmentObject var identityManager: IdentityManager
    @EnvironmentObject var meshManager: MeshNetworkManager
    @Environment(\.colorScheme) var colorScheme
    
    // __PEER_VERSION_2026_09_09__ La version que le correspondant a annoncee
    // dans sa charge de profil. `@ObservedObject` et pas une simple lecture:
    // la charge arrive APRES l'ouverture de l'ecran, il faut redessiner.
    @ObservedObject private var peerVersions = PeerVersionStore.shared
    /// Banniere ecartee pour cette session d'ecran. Volontairement non
    /// persistee: on ne veut ni harceler dans la conversation ouverte, ni
    /// cacher definitivement un fait qui explique des messages manquants.
    @State private var outdatedPeerBannerDismissed = false

    /// __COMPOSER_DRAFT_ISOLATED_2026_11_16__ The composer text lives in a
    /// reference box that this view does NOT observe: only `ComposerDraftReader`
    /// (the field, the `/` bar, the smart-reply bar) re-renders per keystroke.
    /// As `@State` it re-evaluated the whole chat body on every key, and with it
    /// every built row of the message list — all of them with accessibility on,
    /// or on iOS 18+ where the 1:1 stack is eager (`ChatMessageStack`).
    /// Measured (74 simulated keystrokes, 50-row eager window, no
    /// accessibility): 51 `MessageBubble` bodies per key and 9.1-9.4 s of
    /// dropped frames as `@State`; 0.7-1.4 bodies per key and 0.4-0.6 s here.
    /// `messageText` keeps every existing read and write site unchanged; body
    /// code must read the text through a `ComposerDraftReader`, never directly.
    @State private var composerDraft = ComposerDraft()

    // __FOLDABLE_LAYOUT_2026_11_16__ Fold / unfold (and any move between the
    // pushed stack and the wide layout's detail pane) replaces this view with a
    // new one. What the reader would lose is carried by `ThreadHandoff`.
    @State private var handoffToken = UUID()
    @State private var handoffAttached = false
    /// A row the previous view's reader was looking at (scrolled up); the open
    /// lands there instead of on the newest message or the unread line.
    @State private var handoffReadingAnchor: String?
    @State private var handoffAnchorActive = false
    /// The thread's own width — the bubbles' caps are a fraction of it.
    @State private var chatColumnWidth: CGFloat = 0
    private var handoffKey: String { ThreadRoute.conversation(for: conversation).handoffKey }

    private var messageText: String {
        get { composerDraft.text }
        nonmutating set { composerDraft.text = newValue }
    }
    @State private var showingError = false
    @State private var errorMessage = ""
    /// Media over LoRa is opt-in (Settings › LoRa radio). Read here to know
    /// whether an off-grid media send needs the slow-send confirmation below.
    @AppStorage(MeshtasticManager.mediaOverLoRaKey) private var mediaOverLoRaEnabled = false
    /// __SUGGESTIONS_ARE_A_CHOICE_2026_10_27__ Reporter's N1: "possibilite de
    /// desactiver les suggestions lors de l'ecriture d'un message".
    ///
    /// Gates BOTH the bar and `generateSmartReplies`. Hiding the bar alone would
    /// still run the model on every incoming message — the work the person
    /// asked us not to do, done invisibly. Default true: this is a switch for
    /// people who dislike the feature, not a quiet removal of it.
    @AppStorage(SmartReplyPreference.enabledKey) private var smartRepliesEnabled = true
    /// Non-nil while the slow-LoRa-send confirmation is up. [Media-over-LoRa P1]
    @State private var pendingLoRaMedia: PendingLoRaMedia?
    @State private var isConnected = false
    @State private var showingAliasEditor = false
    // 🔴 __PICKER_MODE_RACE_2026_08_22__ C'était deux états séparés:
    // `showMediaPicker` (présenter ou non) et `showMultiSelectOption` (dans quel
    // mode). Le second était copié PAR VALEUR dans `ChatSheetsModifier`, et
    // `.sheet(isPresented:)` capture son contenu au moment de la présentation.
    // Les deux drapeaux étant posés dans la même fermeture, la feuille pouvait
    // s'ouvrir avec la copie d'AVANT le changement de mode — d'où deux entrées de
    // menu qui donnaient exactement le même écran, signalé par Hugo.
    //
    // Un seul état supprime la course: le mode EST la présentation.
    // `.sheet(item:)` ne peut pas présenter sans savoir lequel.
    @State private var mediaPickerMode: MediaPickerMode?
    /// __ATTACH_PANEL_2027_2026_11_17__ The "+" panel, and the caption a photo
    /// sent from it to the markup sheet ("Draw") carries through that sheet.
    @State private var showAttachPanel = false
    @State private var pendingMarkupCaption = ""
    @State private var showWhatsAppCamera = false  // ✅ NEW: WhatsApp-style camera
    @State private var showVideoNoteRecorder = false  // __VIDEO_NOTE_2026_09_24__ round video recorder
    /// Capturing a photo and recording a video note are distinct, explicit actions.
    /// Keeping the choice outside the sheet makes the requested mode its first state.
    @State private var captureCameraMode: WhatsAppMediaCaptureSheet.CameraMode = .photo
    @State private var showMediaActionSheet = false  // ✅ NEW: Quick action sheet
    @State private var showDocumentPicker = false    // ✅ NEW: Document picker
    // __PHOTO_MARKUP_2026_11_08__ the picked photo awaiting its markup sheet.
    // Wrapped because `UIImage` is not `Identifiable` and `.sheet(item:)` needs
    // it to be — the wrapper also makes "no image, no sheet" unrepresentable.
    @State private var pendingMarkupImage: MarkupSubject?
    @State private var pendingMarkupAttachment: MediaManager.MediaAttachment?
    /// __LOCAL_GIF_LIBRARY_2026_09_13__ Sélecteur GIFs/autocollants local.
    @State private var showGifPicker = false
    /// True while a drag is hovering the conversation (Mac drag-and-drop, iPad split-view drag).
    @State private var isDropTargeted = false
    @State private var showContactPicker = false     // ✅ NEW: Contact picker
    @State private var showContactPhotoPicker = false  // ✅ NEW: Contact photo picker
    @State private var showLocationPicker = false    // ✅ NEW: Location sharing
    @State private var showCheckInSheet = false       // 🛡️ Check-In / Accompagnement
    @State private var showDisguiseSheet = false      // STEGO-v1 "Disguise as photo"
    @State private var droppedReveal: RevealOutcomeBox?  // a disguised PNG dropped onto the chat
    @State private var showPollSheet = false          // 📊 Sondage
    @State private var cashuDraft: CashuSendDraft?    // 🪙 __CASHU_SEND_READY_2026_11_09__
    // 🔧 FIX (1.0.10 v4): Computed instead of @State so the body re-evaluates on
    // EVERY messageManager.messages mutation (in-place struct edits like
    // .deliveryStatus changes too — not just count deltas). Previous @State copy
    // was assigned by loadMessages() inside .onReceive, but SwiftUI didn't
    // reliably re-render the bubble row for in-place mutations of an array
    // element when the count was unchanged. Reading directly from the @Published
    // source means @ObservedObject auto-tracking forces a re-render on every
    // emit. Filter is O(n) per render — fine for typical chats, and SwiftUI
    // diffing avoids re-rendering rows whose identity hasn't changed.
    // 🔧 PERF (1.0.15): These three derived lists used to be *computed
    // properties*, re-evaluated on every body pass AND once per row (the
    // per-bubble .overlay read `filteredMessages`, each bubble's
    // onAppear/onDisappear read `loadedMessages.last`). Every read re-filtered
    // and re-sorted the ENTIRE global `messageManager.messages` array, making a
    // single render O(messages × rows) — the lag that grew with history size.
    // They're now cached in @State and rebuilt only when an input actually
    // changes, via rebuildMessageCaches() — driven by .onReceive($messages)
    // (fires on every publish, including in-place delivery-status edits) and the
    // search/date .onChange hooks. Reads are now O(1).
    @State private var cachedLoadedMessages: [SecureMessage] = []
    @State private var cachedFilteredMessages: [SecureMessage] = []
    @State private var cachedMessagesByDate: [(String, [SecureMessage])] = []

    private var loadedMessages: [SecureMessage] { cachedLoadedMessages }

    // Thread membership (`ChatThreadPipeline.threadMessages`): the global message
    // array filtered down to this conversation, sorted oldest→newest.
    //
    // __IDENTITY_CANONICAL_2026_08_15__ Membership is decided on the CANONICAL
    // spelling of both keys. The same 32-byte identity arrives as base64url from
    // the VPS and as standard base64 from the Keychain / v2 relay, and this `==`
    // was the last place that difference could still hide a message: it was in
    // `messageManager.messages`, it had already fired its notification (posted
    // from `receiveMessage`, keyed on the sender key alone), and it was not in the
    // thread. `receiveMessage` now stamps the canonical form on the way in, so
    // this only has to cover history stored by earlier builds — which it does,
    // with no migration.
    @AppStorage("contactAliases") private var contactAliasesData: Data = Data()
    // __SHARED_NICKNAME_2026_09_22__ observe pour redessiner l'en-tete quand le
    // contact change son pseudo.
    @AppStorage(SharedNickname.defaultsKey) private var sharedNicknamesData: Data = Data()
    @StateObject private var mediaManager = MediaManager()
    
    // ✅ Wallpaper support
    @ObservedObject private var wallpaperManager = ChatWallpaperManager.shared
    @State private var showWallpaperPicker = false
    @State private var wallpaperRefreshId = UUID()  // 🔧 FIX: Force refresh on wallpaper sync
    
    // ✅ Blocking functionality
    @StateObject private var blockedManager = BlockedContactsManager.shared
    @State private var showingBlockSheet = false
    @State private var showingUnblockAlert = false
    @State private var showingResetSessionAlert = false
    // 🚩 Reporting (App Store guideline 1.2) — sits beside Block, same surfaces.
    @State private var showingReportSheet = false

    // 🔧 2026-06-09 (b47): "Loading new message…" pill shown at the top of
    // the conversation when the user reached this chat via a notification
    // tap. The push banner arrives ~500ms-3s before the IPFS fetch +
    // decrypt completes; without feedback the user sees an empty / stale
    // conversation and assumes the notification was a phantom. The pill
    // auto-hides when (a) messagesByDate.count grows, or (b) 8 s elapse.
    @State private var showLoadingNewMessagePill: Bool = false
    @State private var loadingPillHideTask: DispatchWorkItem?
    /// Reading a thread can touch the entire persisted message history (and update
    /// the conversation badge). Keep that bookkeeping out of the navigation
    /// transaction so the push gets its first frames on time.
    @State private var initialReadMarkTask: DispatchWorkItem?

    // ✅ Audio recording
    @StateObject private var audioRecorder = AudioRecorderManager()
    @State private var isRecordingAudio = false
    @State private var recordingMode: RecordingMode = .holdToRecord
    @State private var showRecordingHint = false
    @State private var recordedAudio: AudioRecorderManager.AudioAttachment?
    @State private var showAudioPreview = false
    
    // ✅ Voice calls
    @StateObject private var voiceCallManager = VoiceCallManager.shared
    @State private var showVoiceCall = false
    // 📹 Video calls handled by ContentView ZStack (no duplicate fullScreenCover)
    
    // ✅ Scheduled calls
    @StateObject private var scheduledCallManager = ScheduledCallManager.shared
    @State private var showCreateScheduledCall = false
    @State private var showUpcomingCallAlert = false

    // __TRANSLATE_HOIST_2026_09_05__ Translate result, owned by the screen (the
    // row's own alert never presented — see MessageBubble.onTranslateRequest).
    @State private var chatTranslatedText = ""
    @State private var showChatTranslationResult = false
    @State private var chatTranslationSource = ""
    @State private var showChatTranslationSheet = false

    /// Same two-step as the group chat: on-device translator first, then the
    /// iOS 18 Translation sheet, then the localized failure text.
    private func requestTranslation(of text: String) {
        guard !text.isEmpty else { return }
        Task {
            let translated = await NLPManager.shared.processTranslateReceived(text)
            await MainActor.run {
                if let translated {
                    chatTranslatedText = translated
                    showChatTranslationResult = true
                } else if #available(iOS 18.0, *) {
                    chatTranslationSource = text
                    showChatTranslationSheet = true
                } else {
                    chatTranslatedText = "ai.translate_failed".localized
                    showChatTranslationResult = true
                }
            }
        }
    }

    // ✅ Scheduled "Thinking of You" messages
    @State private var showScheduledMessage = false

    // Map & AI shortcuts
    @State private var showMapView = false
    @State private var showAIChatView = false

    // Media gallery (P2 #20)
    @State private var showMediaGallery = false
    @State private var showContactProfile = false

    /// (N11) "Possibilité de pincer avec deux doigts pour rétrécir ou agrandir
    /// le texte dans une conversation sans pour autant avoir d'impact dans la
    /// taille du texte dans le reste de l'application."
    ///
    /// Scoped to this screen's own `@State`, never written to any setting, so
    /// it cannot reach another conversation or any other screen — which is the
    /// half of the request that is easy to get wrong. Same bounds as Android
    /// (0.75…2.5) so a pinch reaches the same place on both phones.
    ///
    /// Applied by giving the message list a `.font`, which works here for the
    /// same reason it works on Android: the view that draws message body text
    /// (`ClickableTextView`) sets no font of its own and inherits one, exactly
    /// as `LinkifyText` reads `LocalTextStyle.current`. Every other label in a
    /// bubble passes an explicit `.font(.scaled(10))` or similar and is
    /// therefore untouched — the timestamps and ticks keep their size while the
    /// words change, which is what was asked for.
    @State private var chatTextScale: CGFloat = 1.0
    /// The scale at the start of the current pinch, so the gesture is relative.
    @State private var chatTextScaleAtGestureStart: CGFloat = 1.0

    // 🔧 FIX (1.0.10 v5): Force ChatView body re-evaluation when the app returns
    // to foreground. SwiftUI doesn't reliably re-render views for @Published
    // mutations that happened while the app was backgrounded — so a sender who
    // taps Send → swipes up → reopens would see the bubble stuck on the clock
    // until they manually left the chat and re-entered. On scenePhase=.active
    // we explicitly re-emit messageManager.objectWillChange so every observing
    // view in this tree re-renders, picking up the .sent state set during bg.
    @Environment(\.scenePhase) private var chatScenePhase
    /// __DUO_TITLE_2026_11_18__ the thread is the detail pane beside the list.
    @Environment(\.threadSplitActive) private var chatInSplit
    
    // ✅ Cache for each effect to avoid reprocessing
    @State private var effectCache: [AudioRecorderManager.VoiceEffect: AudioRecorderManager.AudioAttachment] = [:]

    // ✅ Track visibility for onChange guard
    @State private var isVisible = false

    // Smart replies
    @ObservedObject private var nlpManager = NLPManager.shared

    enum RecordingMode {
        case holdToRecord
        case tapToRecord
    }
    
    // Search functionality
    @State private var showSearch = false
    // 🧹 Retractable chat action bar (2026-07-13): collapses the call/schedule/
    // search actions behind one toggle so the nav bar stays clean (Map + AI were
    // removed from 1:1 chat entirely — they belong to the tab bar, not a chat).
    @State private var searchText = ""
    @State private var currentSearchIndex: Int = 0
    
    // ✅ Date search functionality
    @State private var showDateSearch = false
    @State private var selectedSearchDate: Date?
    @State private var dateRangeStart: Date?
    @State private var dateRangeEnd: Date?
    @State private var scrollToMessageId: String?

    // 🔧 FIX (leak): handleOnAppear() registers block observers, and block observers are
    // retained by NotificationCenter until explicitly removed — `[weak self]` cannot help,
    // there is no self to weaken (a View is a struct). Every chat open used to add four more
    // live handlers, so after N opens a single "ReplyToMessage" post ran N closures against N
    // stale @State boxes. Tokens are parked here and torn down in .onDisappear.
    @State private var chatObservers: [NSObjectProtocol] = []


    // Scroll functionality
    @State private var showScrollToBottom = false
    /// L'ancre de bas de liste est-elle réellement à l'écran ? Vérité de terrain
    /// pour « suis-je en bas », et pour savoir s'il faut coller le défilement au
    /// bas quand la taille du contenu change. Voir `ChatBottomAnchored`.
    @State private var bottomAnchorSighted = false

    /// __OPEN_AT_THE_FIRST_UNREAD_2026_10_16__ Where this thread should LAND.
    ///
    /// Opening a conversation did one thing unconditionally: jump to the bottom.
    /// Then, 0.40 s later, `scheduleInitialReadMark` marked the WHOLE thread
    /// read. So arriving at a chat with thirty unread messages put you on the
    /// newest one, destroyed the unread boundary before you could see it, and
    /// left you to scroll back up and guess where you had stopped. Every
    /// mainstream messenger lands on the FIRST unread instead and reads
    /// forward; that is the behaviour this restores.
    ///
    /// Captured once, in `onAppear`, because the read-mark that fires a beat
    /// later is exactly what would erase the answer.
    /// __OFFLINE_ONLY_CONVERSATION_2026_10_23__ Observed, not read once: the
    /// menu label has to flip the moment the switch moves, including when the
    /// move came from the other side as an incoming request.
    @ObservedObject private var offlineOnlyStore = OfflineOnlyStore.shared
    /// 💓 __ALIVE_CHECK_IN_2026_11_04__ Observed, not read once: the peer's
    /// half of the check-in arrives on the receive path and has to appear here
    /// without this screen asking for it.
    @ObservedObject private var aliveStore = AliveCheckInStore.shared
    @State private var showingAliveSheet = false

    /// __MESH_ONLY_UX_2026_11_03__ Whether the radio path to THIS person is
    /// alive. `MeshNetworkManager.connectedPeers` is `[MCPeerID]`, identified by
    /// display name, which cannot be matched to a conversation's public key —
    /// `CrossPlatformMesh` is the one that tracks peers BY key, which is also
    /// why it is the stack that talks to Android.
    @ObservedObject private var crossPlatformMesh = CrossPlatformMesh.shared

    /// Is the person in this conversation reachable over the local mesh now?
    private var peerReachableOverMesh: Bool {
        crossPlatformMesh.connectedPeers.contains {
            $0.publicKey == conversation.participantPublicKey
        }
    }

    @State private var openUnreadAnchorId: String?

    /// __UNREAD_ANCHOR_ON_COLD_CACHE_2026_11_16__ Whether the open has already
    /// decided where to land. Measured on the simulator (`-OSHIScrollLab
    /// -OSHIScrollUnread`, 40 unread): the list's `onAppear` runs BEFORE the
    /// `$messages` subscription fills the row cache (logged rows=0, loaded=0),
    /// so `firstUnreadIncomingId()` answered nil, the thread landed on the
    /// newest message with no divider, and 0.4 s later the read-mark erased
    /// the boundary for good — the exact behaviour the anchor exists to
    /// replace. The decision now waits for the first rows (the 0 → N warm-in).
    @State private var openUnreadAnchorResolved = false

    /// __ANCHOR_SIGHTED_EARLY_2026_11_16__ "The landing loop may stop": the
    /// bottom anchor was sighted AND, where the real scroll geometry is known
    /// (iOS 18+), the gap below the viewport is closed. The anchor alone lies
    /// early — a LazyVStack builds it before it is on screen, and with
    /// accessibility on (VoiceOver, Voice Control, XCUITest) every row is
    /// built: measured (`-OSHIScrollLab`, 90 rows with tall bubbles) the open
    /// stopped after one pass and parked on a 1 000 pt bubble eight rows above
    /// the newest message, scroll bar at 83 %. Closed means within ±24 pt: a
    /// negative gap is the viewport past the END of the content (bare
    /// wallpaper, an overshoot on the LazyVStack's estimate), which is not a
    /// landing either — measured after a very long send (`-OSHISendLong`)
    /// when only the upper bound was checked.
    /// __AX_LAZY_LAYOUT_LIVELOCK_2026_11_16__ The message list is an eager
    /// stack (iOS 18+, search results included — see `messageListView`): its rows'
    /// onAppear/onDisappear fire when BUILT, so "the bottom is on screen" comes
    /// from the scroll geometry (`ChatScrollGapReader`) instead of the anchor.
    private var listIsEager: Bool {
        if #available(iOS 18.0, *) { return true }
        return false
    }

    private var landedAtBottom: Bool {
        bottomAnchorSighted && ChatScrollTracker.gapIsClosed(scrollTracker.gap)
    }

    /// The reader's finger moved the list after `start`: a landing loop that
    /// started before must stand down.
    private func readerMovedSince(_ start: Date) -> Bool {
        scrollTracker.lastUserDragAt.map { $0 > start } ?? false
    }

    /// __UNREAD_HAD_NO_LINE_2026_11_07__ What the open scroll aims at, so the
    /// reader can SEE the boundary it landed on. See the group view's copy for
    /// the full reasoning: landing on the first unread without drawing the line
    /// is half of the design this was modelled on, and the missing half is the
    /// one that explains the position.
    static let unreadDividerId = "chatUnreadDivider"

    /// The oldest message we have not read yet, and did not send ourselves.
    /// nil when the thread is fully caught up — then the bottom is the right
    /// place, and the existing landing machinery handles it unchanged.
    private func firstUnreadIncomingId() -> String? {
        // __CHAT_OPEN_PERF_2026_11_14__ Read off the RENDERED rows: with more
        // unread messages than the window holds (or a hidden control payload
        // first in line) the old answer had no row, so no divider was drawn and
        // the open scroll aimed at nothing for two seconds.
        ChatThreadPipeline.firstUnreadIncoming(in: cachedMessagesByDate, myKey: identityManager.publicKey)
    }
    /// __LONG_SEND_LANDING_2026_09_28__ Scroll facts read by the post-send landing
    /// (real geometry gap, last drag, last send) — a reference box so updating
    /// them never re-renders this view. See `landAtBottomAfterOwnSend`.
    @State private var scrollTracker = ChatScrollTracker()
    @State private var ownSendLandingTask: Task<Void, Never>?
    /// When the conversation was opened. For a short window after open, any growth
    /// in the loaded message set re-settles to the true bottom — this catches
    /// messages (e.g. the user's own sent ones) that materialise AFTER the initial
    /// settle window, which used to leave the thread parked on the last received
    /// message instead of the newest one.
    @State private var justOpenedAt: Date?
    /// __AX_LAZY_LAYOUT_LIVELOCK_2026_11_16__ `ChatBottomFollower` may pull the
    /// list to the bottom. Off while the open aims at the unread line or a jump
    /// target; the follower itself turns it off when the reader scrolls away
    /// and back on when they return.
    @State private var followBottom = true
    /// Set the moment the reader drags the thread themselves. It is the reliable
    /// signal that separates "the user chose to scroll up" from "content grew under
    /// us" — the bottom-anchor visibility flag conflates the two. While it is false
    /// just after open, the settle keeps re-aiming at the true bottom (so lazily
    /// rendered rows, like the user's own sent messages, are reached); the instant
    /// the user drags, the settle stops and never rips an active read.
    @State private var userScrolledSinceOpen = false
    /// __CROSS_CONVERSATION_COUNT_2026_11_09__ The id of the last message THIS
    /// thread's `cachedLoadedMessages` ended on, as of the last time the
    /// `messageManager.messages.count` handler below actually acted on a growth.
    ///
    /// `messageManager.messages` is the GLOBAL 1:1 store (every contact), not this
    /// conversation's — `ChatThreadPipeline.threadMessages` filters it down. So its `.count`
    /// grows every time ANY contact gets a new message, not just this thread's
    /// peer. The handler used `cachedLoadedMessages.last?.senderPublicKey ==
    /// identityManager.publicKey` ("isMyOwnSend") to decide whether to force a
    /// scroll — but that reads whatever THIS thread's newest message already was,
    /// not whether a message was just added TO this thread. If the user's own
    /// message happens to be the standing last one here (common: you spoke last
    /// and are scrolled up re-reading older context while waiting for a reply),
    /// every unrelated contact's incoming message — anywhere in the app — flips
    /// `isMyOwnSend` true and yanks THIS open, scrolled-up thread back to the
    /// bottom for a message it never received. Comparing the thread's current
    /// last-message id against the id it had the last time this handler fired is
    /// a cheap, self-contained way to ask "did a message actually land in THIS
    /// thread", with no dependency on the timing between this `.onChange` and the
    /// separate `.onReceive(messageManager.$messages)` that rebuilds the cache.
    @State private var lastHandledLoadedMessageId: String?
    @State private var floatingDateHeader: String? = nil
    /// __CHAT_OPEN_PERF_2026_11_14__ A reference box, not `@State var
    /// DispatchWorkItem?`: the date header's geometry callback restarts this
    /// timer on EVERY scroll frame while a header is near the top, and writing a
    /// new work item into `@State` invalidated the whole ChatView body each
    /// frame — every visible bubble re-evaluated, 60-120 times a second, for
    /// as long as the finger moved.
    @State private var floatingDateHider = DeferredAction()
    @State private var scrollProxy: ScrollViewProxy?

    // 💬 Reply to message functionality
    @State private var replyingToMessage: ReplyMessage? = nil
    @State private var replyingToMessageId: String? = nil
    
    private var contactAliases: [String: String] {
        guard !contactAliasesData.isEmpty else { return [:] }
        return (try? JSONDecoder().decode([String: String].self, from: contactAliasesData)) ?? [:]
    }
    
    /// __SHARED_NICKNAME_2026_09_22__ Le pseudo que ce contact a partage, s'il
    /// en a un. Distinct de l'alias: montre dans l'editeur d'alias comme indice.
    private var sharedNickname: String? {
        SharedNickname.nickname(for: conversation.participantPublicKey,
                                in: SharedNickname.decode(sharedNicknamesData))
    }

    /// Nom affiche: alias local > pseudo partage > adresse courte.
    private var currentAlias: String {
        SharedNickname.resolve(alias: contactAliases[conversation.participantPublicKey],
                               nickname: sharedNickname)
            ?? formatAddress(conversation.participantAddress)
    }

    /// Ce que l'editeur d'alias pre-remplit: l'alias REEL seulement. Pre-remplir
    /// avec `currentAlias` recopierait le pseudo du pair en alias local au
    /// premier « Enregistrer », et figerait un nom que le pair ne pourrait plus
    /// changer. Sans alias ni pseudo, on garde l'ancien comportement (adresse).
    private var aliasEditorSeed: String {
        if let a = contactAliases[conversation.participantPublicKey],
           !a.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return a }
        return sharedNickname == nil ? formatAddress(conversation.participantAddress) : ""
    }

    /// True for a "lora!<nodehex>" pseudo-identity thread — a bare Meshtastic
    /// node, not an OSHI contact. Unlike the (nonexistent on iOS) welcome
    /// thread, this one CAN be sent into — see `sendMessage()`'s interop
    /// branch — so it drives a marker, not a banner that hides the composer.
    private var isLoRaInteropThread: Bool {
        LoRaNodeIdentityMap.isFallbackConversationKey(conversation.participantPublicKey)
    }
    
    private var isContactBlocked: Bool {
        blockedManager.isBlocked(conversation.participantPublicKey)
    }

    /// __WELCOME_CHAT_IS_NOT_A_PEER_2026_10_16__ The greeting every new user gets
    /// is "sent" by `oshi.welcome`, which WelcomeMessageManager's own comment
    /// calls "not a person": no key, no session, nobody on the other end.
    private var isWelcomeThread: Bool {
        let welcomeKey = WelcomeMessageManager.systemSenderKey
        return conversation.participantPublicKey == welcomeKey
            || MessageManager.canonicalIdentity(conversation.participantPublicKey)
                == MessageManager.canonicalIdentity(welcomeKey)
    }

    /// __BLOCKED_BY_PEER_2026_09_24__ this contact told us it blocks us: calling is refused
    /// locally ("Contact unavailable"), nothing is sent.
    private var isBlockedByContact: Bool {
        BlockedByPeerStore.shared.isBlockedBy(conversation.participantPublicKey)
    }

    /// Conversation context for AI: last 5 human-readable messages (filtered, cleaned).
    /// Media JSON, base64 blobs and signal envelopes are stripped so the LLM sees natural language.
    private var conversationContextForAI: String? {
        // Was a 9-prefix copy of the catalog; a control payload it did not know
        // about went straight into the LLM prompt.
        let readableMessages = loadedMessages.filter { msg in
            guard let text = msg.plaintextContent, !text.isEmpty else { return false }
            return !OSHIControlPayload.isControl(text)
        }
        let recent = readableMessages.suffix(5)
        guard !recent.isEmpty else { return nil }
        let isMe = { (msg: SecureMessage) -> Bool in
            msg.senderPublicKey != self.conversation.participantPublicKey
        }
        let lines = recent.map { msg -> String in
            let who = isMe(msg) ? "You" : "Them"
            let cleaned = Self.sanitizeForAIContext(msg.plaintextContent ?? "")
            return "\(who): \(cleaned)"
        }
        return lines.joined(separator: "\n")
    }

    /// Strip media JSON, raw base64, and signal envelopes from text used in AI prompts.
    /// Returns a short natural-language placeholder when the content is non-textual.
    static func sanitizeForAIContext(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return "" }
        if trimmed.hasPrefix("📍LOCATION📍") { return "[location]" }
        // JSON media envelope — return caption if present, otherwise a media placeholder.
        if trimmed.hasPrefix("{"),
           trimmed.contains("\"type\":\"media\"") || trimmed.contains("\"type\": \"media\""),
           let data = trimmed.data(using: .utf8),
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            let mediaType = (json["mediaType"] as? String ?? "").lowercased()
            let caption = (json["caption"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let label: String
            switch mediaType {
            case "photo", "image": label = "[image]"
            case "video": label = "[video]"
            case "audio", "voice": label = "[audio]"
            case "document", "file": label = "[file]"
            default: label = "[media]"
            }
            return caption.isEmpty ? label : "\(label) \(caption)"
        }
        // Long base64-ish blob with no spaces — likely a raw attachment payload.
        if trimmed.count > 200, !trimmed.contains(" "), !trimmed.contains("\n") {
            return "[media]"
        }
        return trimmed
    }
    
    private var filteredMessages: [SecureMessage] { cachedFilteredMessages }

    /// True while the visible list is a SUBSET of the conversation — search text
    /// or a date filter. Runs (and therefore tails and footers) are a property of
    /// the conversation, not of a filtered result set; see the note at `isTail`.
    private var isFiltering: Bool {
        !searchText.isEmpty || selectedSearchDate != nil || (dateRangeStart != nil && dateRangeEnd != nil)
    }

    /// 💓 __ALIVE_CHECK_IN_2026_11_04__ "I'm alive", from the bar or the sheet.
    private func confirmAlive() {
        messageManager.confirmAlive(forContact: conversation.participantPublicKey,
                                    identityManager: identityManager,
                                    meshManager: meshManager)
    }

    private var messagesByDate: [(String, [SecureMessage])] { cachedMessagesByDate }

    /// Rebuild all cached derived lists from `source`; runs only on input
    /// change (message publish, search text, date filter), never per render.
    ///
    /// __HISTORY_WINDOW_2026_09_06__ Only the tail of a long thread is rendered:
    /// SwiftUI generates every ForEach identity even in a LazyVStack. Searching
    /// or date-filtering needs the whole thread and keeps it.
    ///
    /// __CHAT_OPEN_PERF_2026_11_14__ The derivation itself is in
    /// `ChatThreadPipeline` (measured by `ChatOpenPerformanceTests`, output
    /// compared with the previous code row by row): this runs on the main
    /// thread on EVERY publish of the global store, for any contact.
    private func rebuildMessageCaches(source: [SecureMessage]) {
        applyPipelineOutput(ChatThreadPipeline.build(source: source,
                                                     peerKey: conversation.participantPublicKey,
                                                     myKey: identityManager.publicKey,
                                                     filter: pipelineFilter,
                                                     windowLimit: visibleHistoryLimit))
    }

    /// Re-window without re-scanning the store ("load earlier", reveal a row).
    private func rebuildWindow() {
        applyPipelineOutput(ChatThreadPipeline.derive(loaded: cachedLoadedMessages,
                                                      filter: pipelineFilter,
                                                      windowLimit: visibleHistoryLimit))
    }

    private func applyPipelineOutput(_ out: ChatThreadPipeline.Output) {
        cachedLoadedMessages = out.loaded
        cachedFilteredMessages = out.filtered
        cachedHasEarlierMessages = out.hasEarlier
        if pipelineFilter.isActive {
            // __FILTERED_RESULTS_WINDOW_2026_11_16__ Draw a window of the matches,
            // not all of them (see `ChatThreadPipeline.resultWindowStep`).
            let window = ChatThreadPipeline.resultWindow(count: out.filtered.count,
                                                         start: resultWindowStart,
                                                         size: resultWindowSize)
            cachedMessagesByDate = ChatThreadPipeline.groupByDay(Array(out.filtered[window]),
                                                                 calendar: .current)
            cachedResultsAbove = window.lowerBound
            cachedResultsBelow = out.filtered.count - window.upperBound
        } else {
            cachedMessagesByDate = out.byDate
            cachedResultsAbove = 0
            cachedResultsBelow = 0
        }
    }

    /// A new search or date filter starts on its first window of matches.
    private func resetResultWindow() {
        resultWindowStart = 0
        resultWindowSize = ChatThreadPipeline.resultWindowStep
    }

    /// Move the result window, if needed, so match `index` is drawn.
    /// Returns true when the window moved (the rows change on the next pass).
    @discardableResult
    private func revealResult(at index: Int) -> Bool {
        let w = ChatThreadPipeline.resultWindow(revealing: index, start: resultWindowStart,
                                                size: resultWindowSize,
                                                count: cachedFilteredMessages.count)
        guard w.start != resultWindowStart || w.size != resultWindowSize else { return false }
        resultWindowStart = w.start
        resultWindowSize = w.size
        rebuildWindow()
        return true
    }

    private var pipelineFilter: ChatThreadPipeline.Filter {
        ChatThreadPipeline.Filter(searchText: searchText,
                                  singleDate: selectedSearchDate,
                                  rangeStart: dateRangeStart,
                                  rangeEnd: dateRangeEnd,
                                  searchDateFormatters: cachedSearchDateFormatters)
    }

    /// Scroll to `messageId`, first growing the window when the message is
    /// above it (a quoted reply, the pinned message): a row that is not
    /// rendered cannot be scrolled to, and the jump used to do nothing.
    private func revealAndScroll(to messageId: String) {
        if isFiltering,
           let index = cachedFilteredMessages.firstIndex(where: { $0.id == messageId }),
           revealResult(at: index) {
            DispatchQueue.main.async { scrollToMessageId = messageId }
            return
        }
        if !isFiltering,
           !cachedMessagesByDate.contains(where: { $0.1.contains(where: { $0.id == messageId }) }),
           let needed = ChatThreadPipeline.windowLimitToInclude(messageId, in: cachedLoadedMessages),
           needed > visibleHistoryLimit {
            visibleHistoryLimit = needed + 20
            rebuildWindow()
            DispatchQueue.main.async { scrollToMessageId = messageId }
            return
        }
        scrollToMessageId = messageId
    }

    /// How many of the newest messages the list renders; grows on demand.
    ///
    /// __AX_LAZY_LAYOUT_LIVELOCK_2026_11_16__ 50 (+50 per "load earlier"), the
    /// group thread's numbers: on iOS 18+ the window is laid out eagerly (see
    /// `messageListView`), so its size is the open cost. Measured without
    /// accessibility (debug build, simulator, `-OSHIScrollLabCount`, open to
    /// first frame): 200-message thread, eager 200 rows 2.6-2.9 s, eager 50
    /// rows 0.6-1.05 s, the old lazy 200-row window 0.6-0.75 s.
    @State private var visibleHistoryLimit = 50
    @State private var cachedHasEarlierMessages = false
    /// __FILTERED_RESULTS_WINDOW_2026_11_16__ The drawn slice of the filtered
    /// matches (`ChatThreadPipeline.resultWindow`) and how many are left out
    /// above and below it.
    @State private var resultWindowStart = 0
    @State private var resultWindowSize = ChatThreadPipeline.resultWindowStep
    @State private var cachedResultsAbove = 0
    @State private var cachedResultsBelow = 0
    private static let historyWindowStep = 50
    private func rebuildMessageCaches() { rebuildMessageCaches(source: messageManager.messages) }

    /// O(1) row helpers — precomputed from the caches instead of indexing the
    /// lists inside each bubble's body (the old per-row O(n) cost).
    private var lastLoadedMessageId: String? { cachedLoadedMessages.last?.id }
    private var currentSearchHighlightId: String? {
        guard !searchText.isEmpty,
              currentSearchIndex >= 0,
              currentSearchIndex < cachedFilteredMessages.count else { return nil }
        return cachedFilteredMessages[currentSearchIndex].id
    }

    private var searchBar: some View {
        VStack(spacing: 0) {
            HStack {
                Image(systemName: "magnifyingglass")
                    .foregroundColor(.gray)
                
                TextField("chat.search_placeholder".localized, text: $searchText)
                    .textFieldStyle(.plain)
                    .autocorrectionDisabled()
                    .accessibilityIdentifier("chat.search.field")
                
                if !searchText.isEmpty {
                    Button {
                        searchText = ""
                        currentSearchIndex = 0
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(.gray)
                    }
                    .accessibilityLabel("chat.clear_search".localized)
                }

                // ✅ Calendar button for date search
                Button {
                    showDateSearch = true
                } label: {
                    Image(systemName: "calendar")
                        .foregroundColor(selectedSearchDate != nil || dateRangeStart != nil ? appBrandColor : .gray)
                }
                .accessibilityLabel("chat.search_by_date".localized)

                if !filteredMessages.isEmpty && !searchText.isEmpty {
                    Button {
                        navigateToPreviousResult()
                    } label: {
                        Image(systemName: "chevron.up")
                            .foregroundColor(currentSearchIndex > 0 ? appBrandColor : .gray)
                    }
                    .disabled(currentSearchIndex == 0)
                    .accessibilityLabel("chat.previous_result".localized)

                    Button {
                        navigateToNextResult()
                    } label: {
                        Image(systemName: "chevron.down")
                            .foregroundColor(currentSearchIndex < filteredMessages.count - 1 ? appBrandColor : .gray)
                    }
                    .disabled(currentSearchIndex >= filteredMessages.count - 1)
                    .accessibilityLabel("chat.next_result".localized)
                    
                    Text(String(format: "chat.search_results".localized, currentSearchIndex + 1, filteredMessages.count))
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color(.systemGray5))
                        .cornerRadius(10)
                }
            }
            .padding()
            .background(Color(.systemBackground))
            
            // ✅ Date filter badge
            if selectedSearchDate != nil || dateRangeStart != nil {
                HStack {
                    DateFilterBadge(
                        date: selectedSearchDate,
                        startDate: dateRangeStart,
                        endDate: dateRangeEnd,
                        onClear: {
                            selectedSearchDate = nil
                            dateRangeStart = nil
                            dateRangeEnd = nil
                        }
                    )
                    Spacer()
                }
                .padding(.horizontal)
                .padding(.bottom, 8)
                .background(Color(.systemBackground))
            }
            
            Rectangle()
                .frame(height: 1)
                .foregroundColor(Color(.separator))
        }
    }
    
    var body: some View {
        chatContentView
            // __UNREAD_LANDING_HOLD_2026_11_16__ Observed, not used: with this
            // observer the open is laid out at its final size at once. Without
            // it, opening on the unread line with accessibility on
            // (ChatScrollLabUITests.test1to1OpenOnFirstUnread, 0/6) settled the
            // chat's frame and safe area in tiny per-frame steps for > 10 s —
            // list container 700 -> 553 pt, top inset 0 -> 64 pt, the composer
            // still 71 pt below its place 5 s in — so the first unread, aimed
            // at the visible top, slid 7-8 pt down under the reader (and sat
            // under the header at first). With it: passes, composer at its place
            // from the first frame (counts in the commit message). Measured
            // only; why SwiftUI resolves the window geometry differently while
            // it is observed is not established (the open-at-bottom path never
            // showed the slow settle). Do not remove without re-running that
            // test.
            .onGeometryChange(for: CGRect.self, of: { $0.frame(in: .global) }) { _ in }
            // Drag a file onto the conversation to send it. On the Mac this is
            // the way people expect to attach anything, and it also works with
            // the iPad's split-view drag. The whole conversation is the target,
            // not a small strip: a drop that has to be aimed is a drop that
            // gets missed.
            .onDrop(of: [.fileURL], isTargeted: $isDropTargeted) { providers in
                guard !isContactBlocked else { return false }
                receiveDroppedFiles(providers)
                return true
            }
            .overlay {
                if isDropTargeted && !isContactBlocked {
                    dropTargetOverlay
                }
            }
    }

    /// Shown while a drag is over the conversation. Deliberately loud — the user
    /// is holding a file and needs to know THIS window will take it.
    private var dropTargetOverlay: some View {
        ZStack {
            Color.black.opacity(0.35).ignoresSafeArea()
            VStack(spacing: 14) {
                Image(systemName: "arrow.down.doc.fill")
                    .font(.scaled(44, weight: .semibold))
                Text("chat.drop.title".localized)
                    .font(.headline)
                Text(String(format: "chat.drop.subtitle".localized, currentAlias))
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(28)
            .oledAwareBackground(.regularMaterial, in: RoundedRectangle(cornerRadius: 20))
            .overlay(
                RoundedRectangle(cornerRadius: 20)
                    .strokeBorder(Theme.brand,
                                  style: StrokeStyle(lineWidth: 2, dash: [7, 5]))
            )
            .padding(40)
        }
        .transition(.opacity)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    /// Resolve dropped providers to file URLs and hand each to the existing send
    /// path.
    ///
    /// `loadFileRepresentation` copies into a temporary URL that is deleted when
    /// the completion returns, so the file is copied somewhere durable FIRST and
    /// only then handed on — `handleDocument` reads asynchronously, and reading a
    /// URL that has already been reaped is the classic way this silently sends
    /// nothing.
    private func receiveDroppedFiles(_ providers: [NSItemProvider]) {
        for provider in providers {
            _ = provider.loadFileRepresentation(forTypeIdentifier: UTType.fileURL.identifier) { url, _ in
                guard let url else { return }
                // __PLAINTEXT_LEFTOVERS_2026_09_24__ Copie protégée, supprimée par
                // `processDocument` une fois lue (avant: `tmp/<uuid>-<nom>`, jamais
                // supprimée, et le préfixe partait comme nom du document).
                let staged = MediaPlaintextTemp.url(fileName: url.lastPathComponent)
                do {
                    try FileManager.default.copyItem(at: url, to: staged)
                    try? FileManager.default.setAttributes([.protectionKey: MediaPlaintextTemp.protection],
                                                           ofItemAtPath: staged.path)
                } catch {
                    MediaPlaintextTemp.remove(staged)
                    OshiLog.messaging.error("drop: could not stage \(url.lastPathComponent): \(error)")
                    return
                }
                // ChatView's own send path. `handleDocument` further down this
                // file belongs to ChatSheetsModifier, a different type — it is
                // not in scope here, and reaching for it was a compile error
                // rather than a silent bug, which is the good outcome.
                Task { @MainActor in
                    // STEGO-v1: a PNG that carries a hidden message FOR US is opened,
                    // not forwarded — on the Mac, dragging the photo from Mail onto
                    // the conversation is the natural way to read it. A PNG made for
                    // someone else (or by us) finds nothing and is sent as before.
                    if staged.pathExtension.lowercased() == "png", let me = PhotoDisguise.myKeys() {
                        let contacts = PhotoDisguise.contacts()
                        let found = await Task.detached(priority: .userInitiated) {
                            PhotoDisguise.revealIfDisguised(fileAt: staged, me: me, contacts: contacts)
                        }.value
                        if let found {
                            MediaPlaintextTemp.remove(staged)
                            droppedReveal = RevealOutcomeBox(outcome: found)
                            return
                        }
                    }
                    do {
                        let attachment = try await mediaManager.processDocument(from: staged)
                        sendMediaMessage(attachment)
                    } catch {
                        OshiLog.messaging.error("drop: could not process \(staged.lastPathComponent): \(error)")
                    }
                }
            }
        }
    }
    
    // MARK: - Chat Content View
    
    private var chatContentView: some View {
        chatViewWithNavigation
            // 💓 __ALIVE_CHECK_IN_2026_11_04__ A `ViewModifier` and not a
            // `.sheet` on the main chain. Attached inline it was the modifier that
            // tipped `chatViewWithNavigation` over the Swift type-checker's limit:
            // "unable to type-check this expression in reasonable time". That
            // chain is already at the edge — `MessageBubble` carries the same
            // warning in its own header — so anything new here goes in beside the
            // other two modifiers, where each one is a single opaque type.
            .modifier(AliveCheckInSheetModifier(
                isPresented: $showingAliveSheet,
                peerName: currentAlias,
                entry: aliveStore.entryOrEmpty(for: conversation.participantPublicKey),
                onConfirm: { confirmAlive() },
                onEnable: { seconds in
                    messageManager.setAliveCheckIn(
                        intervalSeconds: seconds,
                        forContact: conversation.participantPublicKey,
                        identityManager: identityManager,
                        meshManager: meshManager)
                },
                onDisable: {
                    messageManager.stopAliveCheckIn(
                        forContact: conversation.participantPublicKey,
                        identityManager: identityManager,
                        meshManager: meshManager)
                }
            ))
            .modifier(ChatAlertsModifier(
                showingAliasEditor: $showingAliasEditor,
                showingError: $showingError,
                showingUnblockAlert: $showingUnblockAlert,
                showingResetSessionAlert: $showingResetSessionAlert,
                currentAlias: currentAlias,
                aliasEditorSeed: aliasEditorSeed,
                sharedNickname: sharedNickname,
                errorMessage: errorMessage,
                saveAlias: saveAlias,
                unblockContact: {
                    blockedManager.unblockContact(publicKey: conversation.participantPublicKey)
                },
                resetSession: {
                    messageManager.forceResetSession(
                        with: conversation.participantPublicKey,
                        identityManager: identityManager
                    )
                }
            ))
            .modifier(ChatSheetsModifier(
                mediaPickerMode: $mediaPickerMode,
                showAudioPreview: $showAudioPreview,
                showingBlockSheet: $showingBlockSheet,
                showingReportSheet: $showingReportSheet,
                showDocumentPicker: $showDocumentPicker,
                showGifPicker: $showGifPicker,
                showContactPicker: $showContactPicker,
                showWallpaperPicker: $showWallpaperPicker,
                showContactPhotoPicker: $showContactPhotoPicker,
                pendingMarkupImage: $pendingMarkupImage,
                pendingMarkupAttachment: $pendingMarkupAttachment,
                sendMediaWithCaption: { attachment, caption in
                    // A photo drawn on from the attachment panel keeps the
                    // caption typed there.
                    let carried = caption.isEmpty ? pendingMarkupCaption : caption
                    pendingMarkupCaption = ""
                    sendMediaMessageWithCaption(attachment, caption: carried)
                },
                recordedAudio: recordedAudio,
                conversationPublicKey: conversation.participantPublicKey,
                currentAlias: currentAlias,
                sendMediaMessage: sendMediaMessage,
                sendMultipleMediaMessages: sendMultipleMediaMessages,
                sendAudioMessage: { audio, viewOnce in
                    sendAudioMessage(audio, viewOnce: viewOnce)
                    recordedAudio = nil
                    showAudioPreview = false
                },
                cancelAudio: {
                    recordedAudio = nil
                    showAudioPreview = false
                }
            ))
            .sheet(isPresented: $showDateSearch) {
                dateSearchSheet
            }
            .sheet(isPresented: $showContactProfile) {
                ContactProfileView(
                    publicKey: conversation.participantPublicKey,
                    alias: currentAlias,
                    // (N16) the same guarded path the nav-bar buttons use.
                    onCall: { video in startHeaderCall(video: video) },
                    // __ANATOLE_1311_IOS__ (N9) Rename from the card, next to
                    // the call buttons — the alias editor is the chat's own.
                    onRename: {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                            showingAliasEditor = true
                        }
                    }
                )
            }
            // Per-chat media gallery (P2 #20)
            .sheet(isPresented: $showMediaGallery) {
                MediaGalleryView(
                    peerPublicKey: conversation.participantPublicKey,
                    groupId: nil
                )
            }
            .sheet(isPresented: $showLocationPicker) {
                LocationShareSheet(
                    onShareLocation: { locationMessage in
                        sendLocationMessage(locationMessage)
                    },
                    conversationId: conversation.participantPublicKey,
                    recipientName: currentAlias
                )
            }
            // 🛡️ Check-In / Accompagnement sheet
            .sheet(isPresented: $showCheckInSheet) {
                CheckInSheet(
                    contactPublicKey: conversation.participantPublicKey,
                    contactName: currentAlias
                ) { checkInMessage in
                    sendCheckInMessage(checkInMessage)
                }
            }
            // 📊 Sondage
            .sheet(isPresented: $showPollSheet) {
                PollComposerSheet(
                    onSend: { poll in sendPoll(poll) },
                    creatorPublicKey: identityManager.publicKey
                )
            }
            // 🪙 Send bitcoin (Cashu) — from the + menu and from `/pay <token>`.
            // A ViewModifier, not an inline `.sheet`: inline it pushed this chain
            // past the type-checker's limit (see the ALIVE note above).
            .modifier(CashuSendSheetModifier(draft: $cashuDraft, isGroup: false) { raw in sendCashuToken(raw) })
            .sheet(isPresented: $showCreateScheduledCall) {
                CreateScheduledCallView(
                    conversationId: conversation.participantPublicKey,
                    conversationName: currentAlias
                ) { event in
                    sendScheduledCallEvent(event)
                }
            }
            .sheet(isPresented: $showScheduledMessage) {
                CreateScheduledMessageView(
                    isPresented: $showScheduledMessage,
                    conversationId: conversation.participantPublicKey,
                    conversationName: currentAlias,
                    conversationContext: conversationContextForAI ?? ""
                )
            }
            // Slow LoRa media send — confirm before minutes of airtime. Kept in a
            // ViewModifier so this already-large body stays inside the compiler's
            // type-check budget. [Media-over-LoRa Phase 1 2026-09-11]
            .modifier(LoRaMediaConfirmModifier(
                pending: $pendingLoRaMedia,
                message: loRaConfirmMessage(for:),
                onConfirm: { performMediaSend($0.attachment, caption: $0.caption) }))
            // ✅ NEW: WhatsApp-style camera with view-once and caption
            .fullScreenCover(isPresented: $showWhatsAppCamera) {
                WhatsAppMediaCaptureSheet(initialCameraMode: captureCameraMode) { attachment, caption in
                    sendMediaMessageWithCaption(attachment, caption: caption)
                }
            }
            #if DEBUG
            // __VIDEO_NOTE_2026_09_24__ capture harness: `-OSHIComposerText <text>`
            // pre-fills the composer, `-OSHIVideoNoteRecorderDemo` opens the recorder.
            .onAppear {
                let args = ProcessInfo.processInfo.arguments
                if let i = args.firstIndex(of: "-OSHIComposerText"), i + 1 < args.count {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { messageText = args[i + 1] }
                }
                if args.contains("-OSHIVideoNoteRecorderDemo") {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { showVideoNoteRecorder = true }
                }
                // __FILTERED_RESULTS_WINDOW_2026_11_16__ `-OSHIOpenSearch`: open the
                // search bar (the ⋯ menu entry) for the scroll lab's search test.
                if args.contains("-OSHIOpenSearch") {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { showSearch = true }
                }
                // __LONG_SEND_LANDING_2026_09_28__ `-OSHISendLong`: type a long
                // multi-paragraph message into the (focused, expanded) composer,
                // then send it through the real `sendMessage()` — the owner's
                // « après un long message, il faut redescendre » case.
                if args.contains("-OSHISendLong") {
                    let para = "This is a long pasted paragraph that wraps over many lines in the bubble, "
                        + "the kind of message that is taller than the whole screen once it is sent. "
                    let body = (1...14).map { "Paragraph \($0). " + String(repeating: para, count: 3) }
                        .joined(separator: "\n\n") + "\n\nEND OF THE LONG MESSAGE"
                    // `-OSHISendNow`: no focus, send 0.3 s after the text lands
                    // (anchor still on screen at insertion time).
                    let now = args.contains("-OSHISendNow")
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
                        if !now {
                            NotificationCenter.default.post(name: NSNotification.Name("FocusChatComposer"), object: nil)
                        }
                        messageText = body
                    }
                    DispatchQueue.main.asyncAfter(deadline: .now() + (now ? 2.8 : 8.0)) { sendMessage() }
                }
            }
            #endif
            .fullScreenCover(isPresented: $showVideoNoteRecorder) {
                VideoNoteRecorderOverlay(
                    onSend: { data, meta in sendVideoNote(data, meta: meta) },
                    onClose: {
                        var t = Transaction()
                        t.disablesAnimations = true
                        withTransaction(t) { showVideoNoteRecorder = false }
                    })
                .presentationBackground(.clear)
            }
            // __CALL_COVER_HID_VIDEO_2026_09_23__ No fullScreenCover for the call.
            // ContentView's ZStack already shows VoiceCallView for connecting /
            // outgoing-ringing / inCall. A cover is presented ABOVE that ZStack, so
            // on a voice→video upgrade VideoCallView mounted (camera on, frames
            // decoded) but stayed hidden behind this stale VoiceCallView — the
            // owner saw "still in call mode, no video window" on a call started
            // from a chat. `showVoiceCall` is kept as a no-op flag.
            .fullScreenCover(isPresented: $showMapView) {
                NavigationStack {
                    OfflineMapView(presentedModally: true)
                }
            }
            .fullScreenCover(isPresented: $showAIChatView) {
                NavigationStack {
                    OfflineAITabView()
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) {
                                Button {
                                    showAIChatView = false
                                } label: {
                                    Image(systemName: "xmark.circle.fill")
                                        .font(.title3)
                                        .foregroundColor(.secondary)
                                        .accessibilityLabel("common.close".localized)
                                }
                            }
                        }
                }
            }
            // 📹 VideoCallView is handled by ContentView ZStack (no duplicate here)
            .onReceive(scheduledCallManager.$upcomingAlert) { event in
                if let event = event, event.conversationId == conversation.participantPublicKey {
                    showUpcomingCallAlert = true
                }
            }
            .alert("event.reminder.title".localized, isPresented: $showUpcomingCallAlert) {
                Button("event.remind.later".localized, role: .cancel) {
                    scheduledCallManager.upcomingAlert = nil
                }
                Button("event.join.call".localized) {
                    scheduledCallManager.upcomingAlert = nil
                    showVoiceCall = true
                    Task {
                        try? await voiceCallManager.startCall(
                            to: conversation.participantPublicKey,
                            peerName: currentAlias
                        )
                    }
                }
            } message: {
                if let event = scheduledCallManager.upcomingAlert {
                    Text(String(format: "event.starting.at".localized, event.formattedTime()))
                }
            }
            // __TRANSLATE_HOIST_2026_09_05__ The 1:1 translate result, presented
            // from the screen like the group chat does — see MessageBubble.
            .alert("message.action.translate".localized, isPresented: $showChatTranslationResult) {
                Button("common.ok".localized, role: .cancel) { }
                // Same switch as the action bar: the translate sheet is the
                // other way message text reaches the pasteboard.
                if !MessagePrivacy.blockForwardAndCopy {
                    Button("message.action.copy".localized) {
                        // __CLIPBOARD_HYGIENE_2026_10_01__ message text: this device only, 60 s
                        SecureClipboard.copy(chatTranslatedText)
                    }
                }
            } message: {
                Text(chatTranslatedText)
            }
            .if_available_translation(sourceText: chatTranslationSource, isPresented: $showChatTranslationSheet)
            // __FOLDABLE_LAYOUT_2026_11_16__ Bubble caps follow THIS view's
            // width (detail pane, Split View, iPhone Duo), not the screen's.
            .providesChatColumnWidth($chatColumnWidth)
            .onAppear {
                isVisible = true
                attachHandoffIfNeeded()
                handleOnAppear()
            }
            .onDisappear {
                isVisible = false
                ThreadHandoff.shared.detach(handoffKey, token: handoffToken)
                handoffAttached = false
                initialReadMarkTask?.cancel()
                initialReadMarkTask = nil
                // Block observers outlive this View struct — drop them or they accumulate.
                removeChatObservers()
                OpenConversation.closed(conversation.participantPublicKey)
            }
            .onChange(of: messageManager.messages.count) { oldCount, newCount in
                guard newCount != oldCount else { return }
                // 🔧 (1.0.10 v4): loadedMessages is now computed, so the view auto-
                // refreshes on every messages mutation. This handler is now scoped
                // to count-growth side effects only: scroll to bottom, smart-replies,
                // and mark-as-read.
                if newCount > oldCount {
                    triggerSmartRepliesIfNeeded()
                    // Soft tactile cue when a message ARRIVES (incoming only —
                    // our own sends already buzz in sendMessage, and we don't
                    // want a double-tap). Only while the chat is on screen, so
                    // background sync bursts don't rattle. WhatsApp/Telegram
                    // give this exact subtle "received" feedback.
                    // Only a single genuinely-new message (not a bulk history
                    // page load, which also grows the count) fires the cue.
                    let latestIsIncoming = cachedLoadedMessages.last?.senderPublicKey != identityManager.publicKey
                    if isVisible && latestIsIncoming && newCount == oldCount + 1 {
                        Haptics.tap(.soft)
                    }
                    // 🔧 2026-06-09 (b47): hide the loading pill — the awaited
                    // message has landed. Animate out so the transition feels
                    // intentional rather than the pill blinking off.
                    if showLoadingNewMessagePill {
                        loadingPillHideTask?.cancel()
                        withAnimation(.easeIn(duration: 0.25)) {
                            showLoadingNewMessagePill = false
                        }
                    }
                }
                if isVisible {
                    markMessagesAsRead()
                }
                // Auto-scroll on new messages if the user is already near the bottom —
                // showScrollToBottom is toggled by the last bubble's .onAppear/.onDisappear,
                // so !showScrollToBottom means the newest is on screen.
                //
                // 🔧 FIX (2026-06-02): ALWAYS scroll when the new message is
                // user-sent (latest message's sender == my publicKey). The
                // `!showScrollToBottom` heuristic was broken with the keyboard
                // up — the just-sent bubble appeared BEHIND the keyboard and the
                // last-bubble onAppear/onDisappear reported it off-screen, so
                // the auto-scroll was skipped and the user had to swipe up to
                // see what they just sent. For INCOMING messages we still gate
                // on showScrollToBottom so we don't yank the scroll while the
                // user is reading old history.
                //
                // 🔧 FIX (2026-08-20) — THE BACKLOG LANDED A SCREENFUL SHORT.
                // This path used ONE blind `scrollTo` at +0.1 s for incoming
                // messages, while the scroll-to-bottom BUTTON three hundred
                // lines below already documents why that is not enough: in a
                // LazyVStack the rows below the viewport are not materialized,
                // so a single `scrollTo` advances toward an ESTIMATED extent —
                // about one screenful — and stops.
                //
                // One arriving message is roughly one screenful, so the bug was
                // invisible on a live conversation. Come back online with a
                // backlog and `newCount - oldCount` is twenty: the scroll landed
                // near the OLDEST of the batch and the newest message sat below
                // the fold, which is the "I have to swipe up to see the last
                // message" report.
                //
                // Worse, it hid its own symptom. `showScrollToBottom` is driven
                // by the onAppear/onDisappear of the row whose id is
                // `lastLoadedMessageId`. After a batch that id belongs to a row
                // that has NEVER been on screen, so neither callback fires and
                // the flag keeps the value it had when the user was pinned to
                // the bottom: false. No button, no scroll, no way down but a
                // swipe. Same `settleScrollToBottom` as the button — it
                // re-issues as the revealed rows materialize and the real extent
                // grows, so it lands at the genuine bottom.
                if newCount > oldCount {
                    // __LONG_SEND_LANDING_2026_09_28__ The pending bubble is added
                    // from a detached task, so also accept "Send was tapped a
                    // moment ago" in case the cached list has not caught up yet —
                    // `threadHasNewMessage` below can still read stale in that
                    // exact race window.
                    let justSent = scrollTracker.lastSendAt.map { Date().timeIntervalSince($0) < 3 } ?? false
                    // __CROSS_CONVERSATION_COUNT_2026_11_09__ `newCount`/`oldCount`
                    // come from the GLOBAL message store, not this thread — see the
                    // `lastHandledLoadedMessageId` declaration above. Without this
                    // check, checking only `cachedLoadedMessages.last?.senderPublicKey`
                    // answers "is this thread's standing last message mine", which
                    // stays true across an unrelated contact's incoming message and
                    // forces the scroll below even though nothing arrived here.
                    // `justSent` is exempt from this gate: it is already scoped to
                    // an action taken IN this view, not a read of shared state.
                    let threadHasNewMessage = cachedLoadedMessages.last?.id != lastHandledLoadedMessageId
                    lastHandledLoadedMessageId = cachedLoadedMessages.last?.id
                    let isMyOwnSend = justSent
                        || (threadHasNewMessage && cachedLoadedMessages.last?.senderPublicKey == identityManager.publicKey)
                    if justSent { scrollTracker.lastSendAt = nil }
                    // Read BEFORE the new rows can touch it: this is "was the
                    // user pinned to the bottom a moment ago", which is the
                    // question that decides whether yanking the scroll is rude.
                    let wasPinnedToBottom = !showScrollToBottom
                    if isMyOwnSend {
                        // __LONG_SEND_LANDING_2026_09_28__ Own send: the anchor's
                        // "sighted" flag is stale/early right now (see
                        // `landAtBottomAfterOwnSend`), so a long bubble used to
                        // park the view on its TOP. Hold the bottom through the
                        // insertion + composer collapse, then verify.
                        followBottom = true
                        ownSendLandingTask?.cancel()
                        ownSendLandingTask = landAtBottomAfterOwnSend(
                            scrollProxy, to: "chatBottomAnchor",
                            tracker: scrollTracker,
                            anchorSighted: { bottomAnchorSighted })
                    } else if wasPinnedToBottom {
                        // A batch changes the content extent for as long as its
                        // rows keep materializing; settle across the next few
                        // runloop ticks rather than firing one shot into an
                        // estimate.
                        // __BOTTOM_LANDING_2026_09_12__ La boucle adaptative
                        // couvre désormais elle-même les bulles disposées en
                        // asynchrone (images, cartes, aperçus de liens): elle
                        // réessaie jusqu'à ce que l'ancre soit VUE, donc jusqu'à
                        // ce que leur vraie hauteur soit prise en compte. Le tir
                        // isolé à +0,6 s qui traînait ici ne rattrapait qu'une
                        // file à délais fixes, et il tirait même une fois arrivé.
                        settleScrollToBottom(scrollProxy, to: "chatBottomAnchor",
                                             isAtBottom: { bottomAnchorSighted })
                    }
                    // (Plus de levée manuelle du drapeau ici: l'ancre du bas est
                    // hors champ dans ce cas, donc son `onDisappear` a DÉJÀ levé
                    // le bouton. Le poser une seconde fois d'ici recréerait les
                    // deux écrivains qu'on vient de supprimer.)
                }
            }
            // 🔧 PERF (1.0.15): Rebuild the cached derived lists on EVERY messages
            // publish — including in-place struct edits (delivery-status / isRead)
            // that don't change `.count`, which the count-scoped onChange above
            // misses. `$messages` also delivers the current value on subscribe, so
            // this populates the cache when the view appears. Use the value Combine
            // hands us (the post-mutation array) rather than re-reading the source.
            .onReceive(messageManager.$messages) { newMessages in
                rebuildMessageCaches(source: newMessages)
            }
            // Search/date filters change the *filtered* output without changing the
            // underlying messages — rebuild so the list & highlight stay in sync.
            .onChange(of: selectedSearchDate) { _, _ in resetResultWindow(); rebuildMessageCaches() }
            .onChange(of: dateRangeStart) { _, _ in resetResultWindow(); rebuildMessageCaches() }
            .onChange(of: dateRangeEnd) { _, _ in resetResultWindow(); rebuildMessageCaches() }
            // 🔧 FIX (1.0.10 v5): On every return to foreground, kick the
            // MessageManager's objectWillChange publisher so SwiftUI re-renders
            // any view (including this one) observing it. Required because
            // @Published mutations done while the app was backgrounded don't
            // always reach the view layer on resume.
            .onChange(of: chatScenePhase) { _, newPhase in
                if newPhase == .active {
                    messageManager.objectWillChange.send()
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("MissedCall"))) { notification in
                handleMissedCall(notification)
            }
            .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("JoinScheduledCall"))) { notification in
                handleJoinScheduledCall(notification)
            }
            .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("SendLocationFromBubble"))) { notification in
                if let locationMsg = notification.userInfo?["locationMessage"] as? LocationMessage {
                    sendLocationMessage(locationMsg)
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("SendScheduledMessage"))) { notification in
                if let recipient = notification.userInfo?["recipient"] as? String,
                   let content = notification.userInfo?["content"] as? String,
                   recipient == conversation.participantPublicKey {
                    messageText = content
                    sendMessage()
                }
            }
    }
    
    // MARK: - Chat View With Navigation
    
    /// __WALLPAPER_TONE_2026_09_24__ The scheme the whole conversation renders
    /// in. It is the system's, unless the wallpaper tone pins it (e.g. the
    /// white-with-violet mesh kept in dark mode): then bubbles, text,
    /// timestamps, badges, composer AND the nav bar switch with it, so nothing
    /// light-on-light or dark-on-dark ever lands on the backdrop.
    private var chatScheme: ColorScheme {
        wallpaperManager.appearance(for: conversation.participantPublicKey,
                                    system: colorScheme).contentScheme
    }

    private var chatViewWithNavigation: some View {
        mainContent
            .environment(\.colorScheme, chatScheme)
            // A `lora!` thread gets the title suffix instead of the normal
            // "🔒 E2E" claim nowhere else in this view makes anyway: this is
            // the one spot in the nav bar a user checks to tell "my contact"
            // from "a stranger's radio", and it must never read as an
            // ordinary, verified OSHI conversation.
            .navigationTitle(isLoRaInteropThread
                ? "\(currentAlias) · \("lora.interop.unverified".localized)"
                : currentAlias)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(chatScheme, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .onChange(of: searchText) { _, _ in
                handleSearchChange()
            }
            .toolbar {
                toolbarContent
            }
    }

    // MARK: - One-tap calls from the chat header
    //
    // __ONE_TAP_CALL_2026_09_29__ Calling was two taps away (a toggle that slid
    // out an action bar, then the phone). The owner asked for one: the phone and
    // the camera now sit directly in the navigation bar, like every messenger
    // people already know; Schedule and Search moved into the ⋯ menu.

    /// Why a call cannot start right now, or nil when it can.
    private var callBlockedMessage: String? {
        if isContactBlocked { return "call.error.blocked".localized }
        if isBlockedByContact { return "call.error.unavailable".localized }
        return nil
    }

    private func startHeaderCall(video: Bool) {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        if let message = callBlockedMessage {
            errorMessage = message; showingError = true
            return
        }
        if !video { showVoiceCall = true }
        let key = conversation.participantPublicKey
        let name = currentAlias
        Task { try? await voiceCallManager.startCall(to: key, peerName: name, isVideoCall: video) }
    }

    /// A round, tinted call button for the navigation bar. On iOS 26 the bar
    /// wraps it in Liquid Glass; earlier systems get the soft tinted disc.
    private func headerCallButton(symbol: String, label: String, video: Bool) -> some View {
        Button { startHeaderCall(video: video) } label: {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .semibold))
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(callBlockedMessage == nil ? appBrandColor : Color.secondary)
                .frame(width: 34, height: 34)
                .background {
                    if #available(iOS 26.0, *) {
                        EmptyView()
                    } else {
                        Circle().fill(appBrandColor.opacity(callBlockedMessage == nil ? 0.14 : 0.06))
                    }
                }
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        // Only while a call is live — `.ended` lingers after hang-up and must not
        // lock the buttons.
        .disabled([.connecting, .ringing, .inCall].contains(voiceCallManager.callState))
        .accessibilityLabel(label)
        .accessibilityHint(Text(currentAlias))
    }

    
    // MARK: - Date Search Sheet
    
    private var dateSearchSheet: some View {
        DateSearchView(
            isPresented: $showDateSearch,
            selectedDate: $selectedSearchDate,
            dateRangeStart: $dateRangeStart,
            dateRangeEnd: $dateRangeEnd,
            onDateSelected: { date in
                scrollToFirstMessageOnDate(date)
            },
            onRangeSelected: { start, end in
                scrollToFirstMessageInRange(start: start, end: end)
            },
            onClearFilter: {
                clearDateFilter()
            }
        )
    }
    
    // MARK: - Date Search Helpers
    
    private func scrollToFirstMessageOnDate(_ date: Date) {
        if let firstMessage = loadedMessages.first(where: { msg in
            Calendar.current.isDate(msg.timestamp, inSameDayAs: date)
        }) {
            scrollToMessageId = firstMessage.id
        }
    }
    
    private func scrollToFirstMessageInRange(start: Date, end: Date) {
        if let firstMessage = loadedMessages.first(where: { msg in
            msg.timestamp >= start && msg.timestamp <= end
        }) {
            scrollToMessageId = firstMessage.id
        }
    }
    
    private func clearDateFilter() {
        selectedSearchDate = nil
        dateRangeStart = nil
        dateRangeEnd = nil
    }
    
    private func handleSearchChange() {
        resetResultWindow()
        rebuildMessageCaches()   // searchText changed → refresh filtered/grouped cache
        currentSearchIndex = 0
        // The cache was just reassigned; defer the scroll one runloop tick so the
        // refreshed @State is observable when scrollToCurrentSearchResult reads it.
        DispatchQueue.main.async {
            if !searchText.isEmpty && !filteredMessages.isEmpty {
                scrollToCurrentSearchResult()
            }
        }
    }
    
    /// __FOLDABLE_LAYOUT_2026_11_16__ Registers this view with `ThreadHandoff`
    /// and takes over what a view of the same thread, replaced a moment ago by
    /// a layout change, left behind: text, keyboard, reading position. Called
    /// from the first of the two `onAppear`s that runs (their order is not
    /// guaranteed); idempotent.
    private func attachHandoffIfNeeded() {
        guard !handoffAttached else { return }
        handoffAttached = true
        composerDraft.handoffKey = handoffKey
        guard let carried = ThreadHandoff.shared.attach(handoffKey, token: handoffToken,
                                                         currentText: composerDraft.text) else { return }
        if composerDraft.text.isEmpty, !carried.text.isEmpty {
            composerDraft.text = carried.text
        }
        if !carried.atBottom, let anchor = carried.readingAnchor {
            handoffReadingAnchor = anchor
        }
        if carried.composerFocused {
            ThreadHandoff.requestComposerFocus(handoffKey, token: handoffToken)
        }
    }

    /// Lands the reader on `id` (top of the viewport), growing the window if
    /// the row is older than it, and re-aims while the bubbles decrypt and grow.
    /// Stops for good as soon as the reader touches the list.
    private func restoreReadingAnchor(_ rowId: String, proxy: ScrollViewProxy) {
        if !cachedMessagesByDate.contains(where: { $0.1.contains(where: { $0.id == rowId }) }),
           let needed = ChatThreadPipeline.windowLimitToInclude(rowId, in: cachedLoadedMessages),
           needed > visibleHistoryLimit {
            visibleHistoryLimit = needed + 20
            rebuildWindow()
        }
        let start = Date()
        for delay in [0.05, 0.25, 0.6, 1.2] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                guard !readerMovedSince(start), scrollToMessageId == nil else { return }
                withAnimation(nil) { proxy.scrollTo(rowId, anchor: .top) }
            }
        }
        // The landing is over: hand the thread back to the ordinary follow
        // logic (new messages, own sends), which the flag held off meanwhile.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            handoffAnchorActive = false
        }
    }

    private func handleOnAppear() {
        // __ONE_NOTICE_IN_FOREGROUND_2026_11_09__ no in-app banner for the
        // thread on screen.
        OpenConversation.opened(conversation.participantPublicKey)
        loadMessages()
        // `$messages` emits its current value when the view subscribes, so the
        // cache is already populated by the `.onReceive` above. Rebuilding it
        // here duplicated a full filter/sort/date-group pass exactly while the
        // navigation controller was animating into a conversation.
        //
        // __CROSS_CONVERSATION_COUNT_2026_11_09__ Baseline for the "did a message
        // actually land in THIS thread" check in the messageManager.messages.count
        // handler below — must be set from the cache, already populated per the
        // paragraph above, not left at its nil default, or the very first
        // unrelated contact's message after opening reads as "new" here too
        // (nil != any real id).
        lastHandledLoadedMessageId = cachedLoadedMessages.last?.id
        warmFirstScreenPhotos()
        checkConnection()
        scheduleInitialReadMark()

        // 🔧 2026-06-09 (b47): if we arrived here from a recent notification
        // tap, surface the loading pill. The user just tapped the banner; the
        // message is in flight on the IPFS fetch path. The pill auto-hides
        // when a new message lands (onChange of messagesByDate.count) or
        // after 8s as a safety timeout.
        let arrivedFromTap = PendingNotificationDeepLink.shared.wasTappedRecently(
            forPeer: conversation.participantPublicKey,
            within: 6
        )
        if arrivedFromTap {
            withAnimation(.easeOut(duration: 0.2)) {
                showLoadingNewMessagePill = true
            }
            loadingPillHideTask?.cancel()
            let task = DispatchWorkItem {
                withAnimation(.easeIn(duration: 0.3)) {
                    showLoadingNewMessagePill = false
                }
            }
            loadingPillHideTask = task
            DispatchQueue.main.asyncAfter(deadline: .now() + 8, execute: task)
            // Also kick a fast poll cadence so the message is fetched ASAP
            // (the periodic 2s poll would otherwise take up to 2s to start).
            MessageManager.shared?.checkPendingIPFSMessages()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                MessageManager.shared?.checkPendingIPFSMessages()
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
                MessageManager.shared?.checkPendingIPFSMessages()
            }
        }

        // 🔧 FIX: smart replies live on NLPManager.shared, which is global —
        // they used to "stick" across conversations because:
        //  (a) we only regenerate on `messages.count` *increase*, so opening
        //      an existing chat with no new message kept the previous chat's
        //      replies visible at the bottom of the input bar;
        //  (b) the shared @Published bleeds across every ChatView instance.
        // Clear the stale set on every appear, then ask for a fresh one
        // scoped to THIS conversation's last incoming message. The check
        // inside triggerSmartRepliesIfNeeded() decides whether to actually
        // run the LLM (skips for outgoing-last, system-prefix, etc.).
        nlpManager.smartReplies = []
        triggerSmartRepliesIfNeeded()

        // 📸 FIX: Request contact's profile picture if we don't have it
        // This ensures profile pictures are synchronized when opening a chat
        requestContactProfileIfNeeded()

        // 🔗 Auto-share profile links if user has links filled
        autoShareProfileLinksIfNeeded()

        let hasSeenHint = UserDefaults.standard.bool(forKey: "hasSeenAudioRecordingHint")
        if !hasSeenHint {
            showRecordingHint = true
            UserDefaults.standard.set(true, forKey: "hasSeenAudioRecordingHint")
        }

        // 🔧 FIX (leak + 0x8BADF00D watchdog): these four used to be plain
        // `addObserver(…, queue: .main)` calls with the tokens thrown away, re-registered on
        // EVERY appearance and never removed. Two separate defects:
        //  (1) the leak — N chat opens meant N handlers per post (see `chatObservers`);
        //  (2) `queue: .main` does NOT mean "hop to main". NSNotificationCenter enqueues an
        //      NSOperation on that OperationQueue and parks the POSTING thread on
        //      `-[NSOperation waitUntilFinished]` until the main runloop drains. Wallpaper and
        //      presence syncs are posted from background network queues, so a chat left open
        //      turned every one of those posts into a rendezvous with main — the exact half of
        //      the deadlock that killed the app. addMainThreadObserver (queue: nil + async hop)
        //      runs the block inline when already on main and never blocks the poster.
        // Tear-down happens in `removeChatObservers()`, called from .onDisappear.
        removeChatObservers()

        // Observe wallpaper sync updates to refresh the view
        chatObservers.append(NotificationCenter.default.addMainThreadObserver(
            forName: NSNotification.Name("WallpaperChanged")
        ) { notification in
            // Only refresh if this is for our conversation
            if let senderKey = notification.object as? String,
               senderKey == conversation.participantPublicKey {
                OshiLog.messaging.info("🎨 Wallpaper sync received - refreshing ChatView")
                wallpaperRefreshId = UUID()
            }
        })

        // Observe profile updates to refresh contact info
        chatObservers.append(NotificationCenter.default.addMainThreadObserver(
            forName: .contactPresenceUpdated
        ) { notification in
            if let publicKey = notification.object as? String,
               publicKey == conversation.participantPublicKey {
                OshiLog.messaging.info("📸 Profile sync received - refreshing ChatView")
                // Trigger a minor UI refresh
                wallpaperRefreshId = UUID()
            }
        })

        // 💬 Observe reply requests from message bubbles
        chatObservers.append(NotificationCenter.default.addMainThreadObserver(
            forName: NSNotification.Name("ReplyToMessage")
        ) { notification in
            if let userInfo = notification.userInfo,
               let reply = userInfo["replyMessage"] as? ReplyMessage {
                OshiLog.messaging.info("💬 Reply requested - setting up reply UI")
                replyingToMessage = reply
                replyingToMessageId = reply.originalMessageId
            }
        })

        // Scroll-to-original when the user taps the quoted preview inside a message bubble.
        chatObservers.append(NotificationCenter.default.addMainThreadObserver(
            forName: NSNotification.Name("ScrollToOriginalMessage")
        ) { notification in
            if let id = notification.userInfo?["messageId"] as? String {
                revealAndScroll(to: id)
            }
        })
    }

    /// Let SwiftUI commit the pushed chat before the potentially expensive
    /// read-state persistence and conversation-list rebuild begin. A fresh task
    /// is scheduled on each appearance and cancelled when the chat is dismissed,
    /// so an abandoned push never marks a conversation as read.
    /// __CHAT_OPEN_PERF_2026_11_14__ Start decoding the newest photos (off main)
    /// while the push animation runs, so the first screen's pictures are
    /// usually ready by the time their rows ask. Same key as the bubble.
    private func warmFirstScreenPhotos() {
        let newest = cachedMessagesByDate.flatMap { $0.1 }.suffix(24).reversed()
        let photos: [(id: String, data: Data)] = newest.compactMap { message in
            guard message.mediaType == .image, !message.isViewOnce,
                  let data = message.mediaAttachment, data.count < 50_000_000,
                  !MediaManager.isGIFData(data) else { return nil }
            return (message.id, data)
        }
        ChatImageLoader.shared.warm(photos, maxDimension: 800)
    }

    private func scheduleInitialReadMark() {
        initialReadMarkTask?.cancel()
        let task = DispatchWorkItem {
            guard isVisible else { return }
            markMessagesAsRead()
        }
        initialReadMarkTask = task
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.40, execute: task)
    }

    /// Unregister the block observers added by `handleOnAppear()`.
    ///
    /// Block observers survive the View struct that created them, so this must run on every
    /// disappear — and defensively at the top of `handleOnAppear()` too, because SwiftUI can
    /// fire onAppear again without an intervening onDisappear (tab switches, sheet dismissal).
    private func removeChatObservers() {
        for token in chatObservers {
            NotificationCenter.default.removeObserver(token)
        }
        chatObservers = []
    }

    /// 📸 Request contact's profile picture if we don't have it yet
    private func requestContactProfileIfNeeded() {
        let contactKey = conversation.participantPublicKey

        // Check if we already have their profile from any source
        let hasLocalPhoto = ContactPhotoManager.shared.getPhoto(for: contactKey) != nil
        let hasReceivedPhoto = ContactPresenceManager.shared.getProfileImage(publicKey: contactKey) != nil

        if !hasLocalPhoto && !hasReceivedPhoto {
            OshiLog.messaging.info("📸 No profile picture for \(contactKey.prefix(8))... - requesting")
            messageManager.requestProfileFromContact(contactKey)
        } else {
            OshiLog.messaging.info("📸 Already have profile for \(contactKey.prefix(8))...")
        }
    }
    
    // MARK: - iPad Layout Helpers

    /// Check if running on iPad
    private var isIPad: Bool {
        UIDevice.current.userInterfaceIdiom == .pad
    }

    /// Max width for messages on iPad to prevent overly wide layout
    private var maxContentWidth: CGFloat {
        isIPad ? 700 : .infinity
    }

    // MARK: - Main Content

    @ViewBuilder
    private var mainContent: some View {
        GeometryReader { geometry in
            ZStack {
                // ✅ Wallpaper background (WhatsApp style)
                // 🔧 FIX: Use .id() to force refresh when wallpaper syncs from contact
                ChatWallpaperView(wallpaper: wallpaperManager.getWallpaper(for: conversation.participantPublicKey))
                    .id(wallpaperRefreshId)

                // 🔧 FIX: iPad - Center content with max width to prevent overly wide layout
                HStack {
                    if isIPad {
                        Spacer(minLength: 0)
                    }

                    VStack(spacing: 0) {
                        if showSearch {
                            searchBar
                        }

                        // 📍 Live Location Banner - shows when sharing live location
                        LiveLocationBanner(conversationId: conversation.participantPublicKey)

                        // 🔗 Profile Links Pinned Bar
                        ProfileLinksPinnedBar(contactPublicKey: conversation.participantPublicKey)

                        // 📌 Pinned Message Banner
                        pinnedMessageBannerView

                        // 💓 __ALIVE_CHECK_IN_2026_11_04__ The liveness strip,
                        // present ONLY once a check-in exists in one direction or
                        // the other. The way IN when there is none is the overflow
                        // menu: a bar in every conversation, for a feature most of
                        // a user's conversations will never use, is the clutter the
                        // design brief rules out.
                        AliveCheckInBar(
                            peerName: currentAlias,
                            entry: aliveStore.entryOrEmpty(for: conversation.participantPublicKey),
                            onConfirm: { confirmAlive() },
                            onOpenSettings: { showingAliveSheet = true })

                        // __PEER_VERSION_2026_09_09__ Le correspondant est reste
                        // sur une version qui ne parle pas le nouveau calendrier
                        // de cles. Non bloquant, et refermable.
                        outdatedPeerBanner

                        messageListView

                        // ⚠️ Blocked Contact Warning
                        if isContactBlocked {
                            blockedBanner
                        }

                        // Smart reply suggestions
                        ComposerDraftReader(draft: composerDraft) { text in
                            smartRepliesSection(text: text.wrappedValue)
                        }

                        // __CASHU_2026_10_25__ Le menu `/`, au-dessus du champ,
                        // exactement comme en groupe. Il n'existait qu'en groupe
                        // et uniquement pour les bots: `/pay` n'etait donc
                        // trouvable nulle part en tete-a-tete, alors que c'est la
                        // ou un paiement a le plus de sens.
                        ComposerDraftReader(draft: composerDraft) { text in
                            let commands = slashCommandSuggestions(for: text.wrappedValue)
                            if !commands.isEmpty && !isContactBlocked {
                                SlashCommandBar(commands: commands,
                                                tint: Theme.brand) { cmd in
                                    messageText = SlashCommands.completion(for: cmd)
                                }
                                .padding(.horizontal, 12)
                                .padding(.bottom, 6)
                            }
                        }

                        // __WELCOME_IS_A_REAL_CONVERSATION_2026_10_16__ The notice
                        // sits ABOVE a working composer. Earlier revisions of this
                        // fix first refused the send with an alert, then removed
                        // the composer outright — both wrong: the thread now goes
                        // to the creator's real account, and he wants to be
                        // written to. All this has to do is say who answers.
                        if isWelcomeThread {
                            welcomeThreadFooter
                        }

                        // 📡 "lora!" pseudo-identity reminder — above the box, below
                        // everything else, right before the composer it is warning
                        // about.
                        loRaInteropBanner

                        // ✅ Input with INSTANT visual feedback
                        ComposerDraftReader(draft: composerDraft) { text in
                            ProfessionalAudioInputView(
                                text: text,
                                audioRecorder: audioRecorder,
                                isRecordingAudio: $isRecordingAudio,
                                recordingMode: $recordingMode,
                                showRecordingHint: $showRecordingHint,
                                handoffKey: handoffKey,
                                handoffToken: handoffToken,
                                onComposerFocused: {
                                    // __KEYBOARD_LANDING_2026_11_16__ Only a reader who
                                    // was AT the newest message is carried back to it;
                                    // one reading history keeps their place. Measured
                                    // (`-OSHIScrollLab`): the old two blind shots were
                                    // a coin toss both ways — at the bottom the newest
                                    // bubble sometimes stayed behind the keyboard
                                    // (LazyVStack estimate), and from history they did
                                    // not move at all. Read BEFORE the keyboard
                                    // shrinks the viewport.
                                    guard landedAtBottom else { return }
                                    withAnimation(nil) {
                                        scrollProxy?.scrollTo("chatBottomAnchor", anchor: .bottom)
                                    }
                                    // After the keyboard animation: re-aim until the
                                    // real gap is closed, not once.
                                    let start = Date()
                                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                                        landAtBottom(scrollProxy, to: "chatBottomAnchor",
                                                     isAtBottom: { landedAtBottom || readerMovedSince(start) })
                                    }
                                },
                                onSend: isContactBlocked ? { } : sendMessage,
                                // 🔴 __VIEW_ONCE_UNREACHABLE_2026_08_22__ « Vue unique »
                                // reste joignable: dans le panneau (menu minuteur à côté
                                // de la légende) et via « Toutes les photos », qui ouvre
                                // toujours la pellicule avec sa feuille d'aperçu.
                                onAttachTap: isContactBlocked ? { } : { showAttachPanel = true },
                                onCameraTap: isContactBlocked ? { } : {
                                    captureCameraMode = .photo
                                    showWhatsAppCamera = true
                                },
                                onVideoTap: isContactBlocked ? { } : {
                                    // __VIDEO_NOTE_2026_09_24__ The compact round recorder
                                    // pops over the chat; the system slide-up is disabled
                                    // so its own spring is the only motion.
                                    var t = Transaction()
                                    t.disablesAnimations = true
                                    withTransaction(t) { showVideoNoteRecorder = true }
                                },
                                onAudioRecorded: isContactBlocked ? { _ in } : { audioAttachment in
                                    recordedAudio = audioAttachment
                                    showAudioPreview = true
                                },
                                // 💬 Reply support - iPad ergonomics
                                replyMessage: replyingToMessage,
                                replyContactAlias: replyingToMessage != nil ? currentAlias : nil,
                                onCancelReply: {
                                    replyingToMessage = nil
                                    replyingToMessageId = nil
                                },
                                aiReplyContext: conversationContextForAI
                            )
                            // __SMART_REPLIES_COVER_2026_11_16__ The suggestion bar
                            // arrives a beat AFTER an incoming message and shrinks
                            // the list from below: measured, it covered the second
                            // half of the message that had just been followed into
                            // view (text cut, time hidden). Re-land if we were down
                            // (read before the bar's layout lands). Observed here,
                            // inside the draft reader, because the bar depends on
                            // the composer being empty.
                            .onChange(of: smartRepliesVisible(text: text.wrappedValue)) { _, visible in
                                guard visible, landedAtBottom else { return }
                                let start = Date()
                                DispatchQueue.main.async {
                                    landAtBottom(scrollProxy, to: "chatBottomAnchor",
                                                 isAtBottom: { landedAtBottom || readerMovedSince(start) })
                                }
                            }
                        }
                        .disabled(isContactBlocked)
                        // __VOICE_MINIPLAYER_2026_10_09__ The floating
                        // voice-note pill rides above this bar instead of over
                        // the text field and the send button.
                        .voiceNotePlayerObstruction()
                        // __ATTACH_PANEL_2027_2026_11_17__ Sheet on iPhone, popover
                        // on the "+" on iPad / Mac. Outside `.disabled` so the
                        // panel never inherits it.
                        .attachmentPanel(isPresented: $showAttachPanel,
                                         kind: .direct(cashu: true),
                                         options: attachPanelOptions,
                                         tint: appBrandColor,
                                         plusAnchor: CGRect(x: 10, y: 0, width: 40, height: 4),
                                         onOption: performAttachOption,
                                         onSend: sendFromAttachPanel)
                        // STEGO-v1: hide an encrypted message for this contact in a PNG, shared
                        // out of band. Attached here, not on the main chain, which is already at
                        // the type checker's limit.
                        .sheet(isPresented: $showDisguiseSheet) {
                            DisguiseAsPhotoSheet(contactPublicKey: conversation.participantPublicKey,
                                                 contactName: currentAlias, text: messageText)
                        }
                        .sheet(item: $droppedReveal) { box in
                            RevealHiddenMessageSheet(initialOutcome: box.outcome)
                        }
                        // Mac menu bar: File ▸ Disguise as Photo… (⇧⌘D) acts on THIS chat.
                        .focusedSceneValue(\.disguiseAsPhoto, isContactBlocked ? nil
                                           : DisguiseFocusedAction(open: { showDisguiseSheet = true }))
                    }
                    .frame(maxWidth: maxContentWidth)  // 🔧 FIX: iPad max width

                    if isIPad {
                        Spacer(minLength: 0)
                    }
                }

                // ✅ First-time hint
                if showRecordingHint {
                    recordingHintOverlay
                }
            }
        }
    }

    // MARK: - Pinned Message Banner (1-on-1)

    @ViewBuilder
    private var pinnedMessageBannerView: some View {
        if let pinnedId = messageManager.pinnedMessages[conversation.participantPublicKey],
           let pinnedMsg = loadedMessages.first(where: { $0.id == pinnedId }) {
            Button {
                revealAndScroll(to: pinnedId)
            } label: {
                HStack(spacing: 10) {
                    RoundedRectangle(cornerRadius: 2)
                        .fill(appBrandColor)
                        .frame(width: 3, height: 30)

                    Image(systemName: "pin.fill")
                        .font(.caption)
                        .foregroundColor(appBrandColor)
                        .rotationEffect(.degrees(45))

                    VStack(alignment: .leading, spacing: 2) {
                        Text("pinned.title".localized)
                            .font(.caption2)
                            .fontWeight(.semibold)
                            .foregroundColor(appBrandColor)

                        // Pinned-message banner: pinning a call summary or a
                        // reply used to park its raw payload at the top of the
                        // thread, where it stayed until the user unpinned it.
                        Text(OSHIControlPayload.previewText(
                            for: pinnedMsg.editedContent ?? pinnedMsg.plaintextContent ?? "media.attachment".localized
                        ))
                            .font(.caption)
                            .foregroundColor(.primary)
                            .lineLimit(1)
                    }

                    Spacer()

                    Button {
                        messageManager.unpinMessageAndSync(
                            in: conversation.participantPublicKey,
                            messageId: pinnedId,
                            identityManager: identityManager,
                            meshManager: meshManager
                        )
                    } label: {
                        Image(systemName: "xmark")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                            .padding(6)
                            .frame(minWidth: 44, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel("pinned.unpin".localized)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color(.secondarySystemGroupedBackground))
            }
            .buttonStyle(.plain)
            .accessibilityHint("pinned.tap_to_scroll.hint".localized)
        }
    }

    // MARK: - Message List View

    /// __FILTERED_RESULTS_WINDOW_2026_11_16__ "+N more results" above or below
    /// the drawn window of matches; each tap draws `resultWindowStep` more.
    private func moreResultsButton(count: Int, above: Bool) -> some View {
        Button {
            let step = ChatThreadPipeline.resultWindowStep
            if above {
                let newStart = max(0, resultWindowStart - step)
                resultWindowSize += resultWindowStart - newStart
                resultWindowStart = newStart
            } else {
                resultWindowSize += step
            }
            rebuildWindow()
        } label: {
            Text(String(format: "search.more_results".localized, count))
                .font(.footnote.weight(.semibold))
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(Capsule().fill(Color(.systemGray6)))
        }
        .buttonStyle(.plain)
        .padding(.vertical, 10)
        .accessibilityIdentifier(above ? "chat.search.moreAbove" : "chat.search.moreBelow")
    }

    @ViewBuilder
    private var messageListView: some View {
        ZStack(alignment: .bottomTrailing) {
            ScrollViewReader { proxy in
                ScrollView {
                    // Base spacing 0 — per-row top padding drives the grouping
                    // (tight within a sender's run, wider between senders).
                    //
                    // __AX_LAZY_LAYOUT_LIVELOCK_2026_11_16__ Eager on iOS 18+, like
                    // the group thread (see `ChatMessageStack`), over a window of
                    // 50 rows (`visibleHistoryLimit`). With accessibility
                    // on (VoiceOver, Voice Control, XCUITest) the `LazyVStack`
                    // livelocked: sampled at 100 % CPU, the main thread never left
                    // `GraphHost.flushTransactions` — `LazySubviewPlacements.
                    // placeSubviews` and `LazyLayoutViewCache.updateItemPhases`
                    // (AllItemsPhaseMutation) re-dirtying each other with no app
                    // code in the cycle, while tall bubbles' real heights replaced
                    // their estimates under the bottom anchor. Seen while typing
                    // (the composer growing a line resizes the viewport) and on
                    // open; the same estimate churn parked the open on a tall
                    // bubble eight rows above the newest message, and with
                    // accessibility on the history could not be scrolled past it.
                    // Ruled out by experiment (each still livelocked): composer
                    // state isolated, no `.sizeChanges` anchor, no count
                    // animation, no date-header geometry, list behind an overlay.
                    // An eager stack has one exact extent, so there is nothing to
                    // oscillate.
                    // __FILTERED_RESULTS_WINDOW_2026_11_16__ Search / date results
                    // too: they used to stay lazy (a filter can match the whole
                    // thread), i.e. exposed to the same livelock while typing in
                    // the search field or the composer. They are now a bounded
                    // window of the matches, 50 at a time, like the thread itself.
                    ChatMessageStack(spacing: 0, scrollTargets: true) {
                        if cachedResultsAbove > 0 {
                            moreResultsButton(count: cachedResultsAbove, above: true)
                        }
                        if cachedHasEarlierMessages {
                            Button {
                                visibleHistoryLimit += Self.historyWindowStep
                                rebuildWindow()
                            } label: {
                                Text("chat.load_earlier_messages".localized)
                                    .font(.footnote.weight(.semibold))
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 8)
                                    .background(Capsule().fill(Color(.systemGray6)))
                            }
                            .buttonStyle(.plain)
                            .padding(.vertical, 10)
                        }
                        ForEach(messagesByDate, id: \.0) { dateHeader, messages in
                            Text(dateHeader)
                                .font(.caption)
                                .fontWeight(.semibold)
                                .foregroundColor(.secondary)
                                .padding(.vertical, 8)
                                .padding(.horizontal, 12)
                                .background(
                                    Capsule()
                                        .fill(Color(.systemGray6))
                                )
                                .oledGlassEdge(Capsule())  // __OLED_GLASS_2026_11_16__
                                .padding(.vertical, 5)
                                .background(
                                    GeometryReader { geo in
                                        Color.clear
                                            .onChange(of: geo.frame(in: .named("chatScroll")).minY) { _, minY in
                                                if minY < 80 && minY > -50 {
                                                    if floatingDateHeader != dateHeader {
                                                        floatingDateHeader = dateHeader
                                                    }
                                                    // Reset hide timer
                                                    floatingDateHider.restart {
                                                        withAnimation(.easeOut(duration: 0.3)) {
                                                            floatingDateHeader = nil
                                                        }
                                                    }
                                                }
                                            }
                                    }
                                )

                            ForEach(Array(messages.enumerated()), id: \.element.id) { index, message in
                                // Group consecutive messages from the same sender
                                // (within ~2 min) into tight runs — Telegram/
                                // WhatsApp feel. Only the first message of a run
                                // gets the full gap above it.
                                let prev = index > 0 ? messages[index - 1] : nil
                                let next = index + 1 < messages.count ? messages[index + 1] : nil
                                let grouped = ChatView.messagesAreGrouped(prev, message)
                                // __BUBBLE_RUN_2026_08_18__ Grouping used to be
                                // ONE padding value; the bubble itself knew
                                // nothing about its run, which is why every
                                // bubble in a burst was an identical shape. The
                                // LAST of a run is the one that carries the tail
                                // and the footer. Same predicate as `grouped`,
                                // read one message forward — deliberately NOT a
                                // second rule that could disagree with this one.
                                //
                                // 🔴 …AND IT IS COMPUTED OVER A FILTERED LIST.
                                // `messagesByDate` comes from `computeFilteredMessages`,
                                // which drops messages by search text and date range. Two
                                // hits from the same sender 90 s apart but a thousand
                                // messages apart in the real thread become ADJACENT here,
                                // so the predicate says "grouped", the earlier one loses
                                // its tail — and with it its footer, i.e. the timestamp
                                // and the delivery ticks. In search results, where the
                                // timestamp is how you locate a hit, that is information
                                // loss, not a padding nicety.
                                //
                                // While a filter is active every message is therefore its
                                // own tail. Runs are a property of the CONVERSATION, and
                                // a filtered list is not one.
                                let isTail = isFiltering
                                    ? true
                                    : (next.map { !ChatView.messagesAreGrouped(message, $0) } ?? true)
                                // __UNREAD_HAD_NO_LINE_2026_11_07__ Drawn from
                                // the captured anchor, never from a live
                                // `!isRead` test: this thread is marked read a
                                // beat after it opens, so reading the flag would
                                // erase the line before it could be seen.
                                if let anchor = openUnreadAnchorId, anchor == message.id {
                                    HStack(spacing: 8) {
                                        Rectangle()
                                            .fill(Color.accentColor.opacity(0.35))
                                            .frame(height: 1)
                                        Text("chat_unread_divider".localized)
                                            .font(.caption2)
                                            .fontWeight(.semibold)
                                            .foregroundColor(.accentColor)
                                            .fixedSize()
                                        Rectangle()
                                            .fill(Color.accentColor.opacity(0.35))
                                            .frame(height: 1)
                                    }
                                    .padding(.top, 10)
                                    .modifier(ChatUnreadDividerTracker(tracker: scrollTracker))
                                    .id(ChatView.unreadDividerId)
                                }

                                messageRowView(for: message, isTail: isTail)
                                    .padding(.top, grouped ? 2 : 10)
                            }
                        }
                        // Bottom anchor for reliable scroll-to-bottom.
                        //
                        // 🔴 FIX (2026-08-21) — ET SEULE VÉRITÉ SUR « SUIS-JE EN BAS ».
                        // `showScrollToBottom` était piloté par les onAppear /
                        // onDisappear de la LIGNE dont l'id vaut
                        // `lastLoadedMessageId`. Or cet id CHANGE à chaque message
                        // reçu: la nouvelle dernière ligne n'a jamais été à
                        // l'écran, donc elle ne peut ni apparaître ni disparaître,
                        // et le drapeau gèle sur sa valeur précédente.
                        //
                        // L'ancre, elle, garde la même identité pour toujours et
                        // EST le bas. Elle voit donc ce que la ligne ne voit pas.
                        // __BOTTOM_LANDING_2026_09_12__ 8 pt, pas 1 pt. Toute la
                        // détection « suis-je en bas » repose sur les onAppear /
                        // onDisappear de cette vue, et un `LazyVStack` ne
                        // matérialise que ce qui approche du viewport: une vue
                        // d'un point est le pire candidat possible pour un signal
                        // dont dépendent l'ancrage système, la flèche et la
                        // boucle d'atterrissage.
                        if cachedResultsBelow > 0 {
                            moreResultsButton(count: cachedResultsBelow, above: false)
                        }
                        Color.clear.frame(height: 8).id("chatBottomAnchor")
                            // Lazy stack only (iOS 17, or a filtered list): an eager
                            // stack fires these when the row is BUILT, not when it is
                            // seen; the scroll geometry reports instead
                            // (`ChatScrollGapReader` below, see `listIsEager`).
                            .onAppear {
                                if listIsEager { return }
                                bottomAnchorSighted = true
                                withAnimation { showScrollToBottom = false }
                            }
                            .onDisappear {
                                if listIsEager { return }
                                bottomAnchorSighted = false
                                withAnimation { showScrollToBottom = true }
                            }
                    }
                    .padding(.horizontal)
                    .padding(.vertical, 4)
                    // (N11) 17 pt is the size the inherited `.body` already
                    // resolved to, so a scale of 1 leaves today's bubble
                    // exactly as it was. `Font.scaled` rather than
                    // `.system(size:)` on purpose: it runs the size through
                    // `UIFontMetrics`, so pinching does not cost the reader
                    // their Dynamic Type setting — the two multiply instead of
                    // one replacing the other.
                    .font(.scaled(17 * chatTextScale))
                    // Animate ONLY on message-count change so genuinely-new
                    // bubbles rise+fade in (via the row's .transition). Status/
                    // read updates don't change the count, so they never trigger
                    // this — the checkmarks crossfade on their own.
                    .animation(Theme.Motion.smooth, value: cachedLoadedMessages.count)
                }
                .coordinateSpace(name: "chatScroll")
                // Not pinned while an eager list lands on the unread line: the
                // window's bubbles decrypt (and grow) after it is built, and
                // growth below must not push the line up under the header.
                .modifier(ChatBottomAnchored(pinnedToBottom: bottomAnchorSighted
                    || (!userScrolledSinceOpen && !(listIsEager && openUnreadAnchorId != nil))))
                // Reliable "the reader dragged" signal so the open-settle knows to
                // stop. Runs simultaneously with the scroll, so it never blocks it.
                .simultaneousGesture(
                    DragGesture(minimumDistance: 12).onChanged { _ in
                        userScrolledSinceOpen = true
                        scrollTracker.lastUserDragAt = Date()
                    }
                )
                // (N11) Two fingers, so it never contends with the one-finger
                // scroll this list is mostly used for — the same gate Android
                // puts on its pointer handler (`changes.size >= 2`).
                // `simultaneousGesture` for the same reason as the drag above:
                // it must not win the arena and block scrolling.
                .simultaneousGesture(
                    MagnificationGesture()
                        .onChanged { value in
                            chatTextScale = min(max(chatTextScaleAtGestureStart * value, 0.75), 2.5)
                        }
                        .onEnded { _ in
                            chatTextScaleAtGestureStart = chatTextScale
                        }
                )
                .modifier(ChatScrollGapReader(tracker: scrollTracker))
                // __FOLDABLE_LAYOUT_2026_11_16__ Where the reader is, for the view
                // that replaces this one on a fold / unfold.
                .modifier(ThreadReadingPositionReporter(handoffKey: handoffKey,
                                                        enabled: !isFiltering))
                // Same view, new width (iPad rotation, Split View resize): the
                // rows reflow under a fixed offset; keep the row being read.
                .onChange(of: chatColumnWidth) { old, new in
                    guard old > 0, abs(new - old) > 40, !isFiltering,
                          let anchor = ThreadHandoff.shared.readingAnchorIfScrolledUp(handoffKey) else { return }
                    for delay in [0.05, 0.3] {
                        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                            withAnimation(nil) { proxy.scrollTo(anchor, anchor: .top) }
                        }
                    }
                }
                // Eager stack only (see `listIsEager`): the group thread's
                // geometry follower. It keeps a reader who has not left the
                // bottom ON it while the window's bubbles decrypt a beat after
                // they are built ("Decrypting…" → text): measured under XCUITest
                // the content grew from 4 044 to 11 588 pt about a second after
                // the open had landed, and the eager stack, unlike the lazy one,
                // was left mid-thread. It is also the single writer of "the
                // bottom is on screen", reported on change only.
                .modifier(ChatBottomFollower(
                    // Not over search results: they are read from the top.
                    following: Binding(get: { followBottom && listIsEager && !isFiltering },
                                       set: { followBottom = $0 }),
                    proxy: proxy, anchorID: "chatBottomAnchor",
                    onBottomChange: { atBottom in
                        guard listIsEager else { return }
                        bottomAnchorSighted = atBottom
                        ThreadHandoff.shared.setAtBottom(handoffKey, atBottom)
                        withAnimation { showScrollToBottom = !atBottom }
                    }))
                .scrollDismissesKeyboard(.interactively)
                .refreshable {
                    await refreshMessages()
                }
                .onAppear {
                    scrollProxy = proxy
                    justOpenedAt = Date()
                    followBottom = true
                    userScrolledSinceOpen = false
                    // __FOLDABLE_LAYOUT_2026_11_16__ Re-laid out mid-read (fold,
                    // unfold, Split View): land where the reader was, not on the
                    // newest message. Nothing else of the open landing runs — it
                    // would pull the thread to the bottom.
                    attachHandoffIfNeeded()
                    if let anchor = handoffReadingAnchor {
                        openUnreadAnchorResolved = true
                        userScrolledSinceOpen = true
                        followBottom = false
                        handoffAnchorActive = true
                        if !cachedMessagesByDate.isEmpty {
                            handoffReadingAnchor = nil
                            restoreReadingAnchor(anchor, proxy: proxy)
                        }
                        return
                    }
                    // Land at the TRUE bottom on open. Two blind scrollTo shots
                    // used to leave tall threads (a live-location map, tall
                    // images) parked partway up — the user had to swipe to reach
                    // the newest message. settleScrollToBottom re-issues as the
                    // lazy rows materialise (and their real heights land), so we
                    // end up at the genuine bottom. A final longer pass covers the
                    // async-laid-out Map/image bubbles.
                    // __OPEN_LANDS_SHORT_2026_08_22__ Les passes s'arrêtaient à
                    // 0,30 s plus un tir isolé à 0,60 s. Sur un fil chargé de
                    // photos, les images se décodent après: le contenu pousse
                    // encore, et on atterrit à ce qui était le bas une
                    // demi-seconde plus tôt. D'où « il faut balayer vers le bas ».
                    //
                    // La queue va maintenant jusqu'à 2,4 s, et chaque passe
                    // vérifie AVANT de tirer que l'utilisateur n'a pas commencé à
                    // remonter — `showScrollToBottom` passe à vrai dès que la
                    // dernière bulle quitte l'écran. Allonger sans cette garde
                    // arracherait la lecture, ce qui est pire que le symptôme.
                    // __OPEN_AT_THE_FIRST_UNREAD_2026_10_16__ see the property.
                    openUnreadAnchorId = firstUnreadIncomingId()
                    // __UNREAD_ANCHOR_ON_COLD_CACHE_2026_11_16__ see `openUnreadAnchorResolved`.
                    openUnreadAnchorResolved = !cachedMessagesByDate.isEmpty
                    if let unreadId = openUnreadAnchorId {
                        landOnUnreadAnchor(proxy, id: unreadId)
                    } else if openUnreadAnchorResolved {
                        settleScrollToBottomOnOpen(
                            proxy,
                            to: "chatBottomAnchor",
                            isAtBottom: { landedAtBottom },
                            shouldAbort: { userScrolledSinceOpen }
                        )
                    }
                    // else: no rows yet — the `messagesByDate.count` 0 → N
                    // handler below lands (unread boundary or bottom) once
                    // they exist; a bottom loop started here would fight it.
                }
                // 🔧 2026-06-09 BLACK-CHAT-ON-OPEN FIX:
                // Opening a 1-to-1 chat sometimes shows a black screen until the
                // user manually swipes. Cause: when `cachedLoadedMessages` is
                // still empty at the moment `.onAppear` fires its delayed
                // scrolls (>0.5 s old caches not yet warmed for first-time-
                // opened conversations), the ScrollView sits at the top of an
                // empty LazyVStack and never re-scrolls when messages finally
                // arrive. Any user gesture wakes the layout. This onChange
                // mirrors GroupChatView's equivalent (line 3265 there) and
                // forces a no-animation scroll the moment messages appear.
                .onChange(of: messagesByDate.count) { oldCount, newCount in
                    // __FOLDABLE_LAYOUT_2026_11_16__ A hand-off landing owns this open.
                    if handoffAnchorActive {
                        if let anchor = handoffReadingAnchor, newCount > 0 {
                            handoffReadingAnchor = nil
                            restoreReadingAnchor(anchor, proxy: proxy)
                        }
                        return
                    }
                    // Re-settle on the cold-cache warm-in (0 -> N) AND on any growth
                    // within a short window after open (N -> N+k). The second case is
                    // what fixed "opens on the last received message, not the newest":
                    // the user's own sent messages materialise a beat after the initial
                    // settle window has ended, so a one-shot 0->N guard missed them.
                    //
                    // __LOAD_EARLIER_FIGHTS_OPEN_SETTLE_2026_11_09__ The comment above
                    // used to claim "the `!showScrollToBottom` predicate still stops the
                    // moment the reader has scrolled up" — but `landAtBottom` only checks
                    // its stop predicate BEFORE each *retry*; the first `scrollTo` of a
                    // pass fires unconditionally. So the `justOpened` branch, gated only
                    // on a wall clock and not on reader state, could fire from a cause
                    // that has nothing to do with a new message arriving: tapping "load
                    // earlier messages" (:1611-1614) grows `visibleHistoryLimit`, which
                    // rebuilds `cachedMessagesByDate` and very often ADDS a date group for
                    // the older messages just paged in — `messagesByDate.count` grows for
                    // a reason that is the opposite of "new message, follow it down".
                    // Reaching that button requires having already scrolled to the top,
                    // so `userScrolledSinceOpen` is already true — and within 4 s of
                    // opening a long thread (>200 messages, scrolled fast) that is a real
                    // combination, not a hypothetical one. Without this guard the reader
                    // got yanked back to the bottom the instant their own pagination tap
                    // landed — fighting the "load earlier" action it is supposed to
                    // respect (see GroupViews.swift's equivalent growth branch, which
                    // gates on `wasPinnedToBottom || !userScrolledSinceOpen` instead of a
                    // timer and does not have this bug). A genuine own-sent or
                    // still-pinned incoming message in this same window is still carried
                    // to the bottom by the sibling `messageManager.messages.count` handler
                    // above, which gates on `isMyOwnSend || wasPinnedToBottom` rather than
                    // on time — so narrowing this branch loses no real coverage.
                    let justOpened = justOpenedAt.map { Date().timeIntervalSince($0) < 4.0 } ?? false
                    // __OPEN_AT_THE_FIRST_UNREAD_2026_10_16__ If we deliberately
                    // landed on the unread boundary, this re-settle would undo
                    // it: a cold cache finishing its warm-in counts as growth,
                    // and the reader would be thrown to the newest message —
                    // the exact behaviour the anchor exists to replace. The
                    // cold-cache case is handled by `landOnUnreadAnchor`, which
                    // re-issues on the same schedule.
                    if openUnreadAnchorId != nil { return }
                    // __UNREAD_ANCHOR_ON_COLD_CACHE_2026_11_16__ First rows of this
                    // open: the unread boundary is decided NOW, on rows that exist.
                    if !openUnreadAnchorResolved && oldCount == 0 && newCount > 0 {
                        openUnreadAnchorResolved = true
                        if let unreadId = firstUnreadIncomingId() {
                            openUnreadAnchorId = unreadId
                            landOnUnreadAnchor(proxy, id: unreadId)
                            return
                        }
                    }
                    // `!userScrolledSinceOpen` on the timed branch: `shouldAbort`
                    // only stops RETRIES — `landAtBottom`'s first `scrollTo` fires
                    // unconditionally (see __LOAD_EARLIER_FIGHTS_OPEN_SETTLE__ above).
                    if (oldCount == 0 && newCount > 0) || (justOpened && newCount > oldCount && !userScrolledSinceOpen) {
                        settleScrollToBottomOnOpen(
                            proxy,
                            to: "chatBottomAnchor",
                            isAtBottom: { landedAtBottom },
                            shouldAbort: { userScrolledSinceOpen }
                        )
                    }
                }
                .onChange(of: scrollToMessageId) { _, newValue in
                    if let messageId = newValue {
                        followBottom = false
                        withAnimation(.easeInOut(duration: 0.3)) {
                            proxy.scrollTo(messageId, anchor: .center)
                        }
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                            scrollToMessageId = nil
                        }
                    }
                }
            }

            // 🔧 2026-06-09 (b47): "Loading new message…" pill (Option C UX).
            // Shown when the user reached this chat from a notification tap;
            // the IPFS fetch + decrypt takes ~0.5-3s after the APNs banner
            // appears. Without this feedback the conversation looks empty
            // and the user thinks the notification was a phantom.
            if showLoadingNewMessagePill {
                VStack {
                    HStack(spacing: 8) {
                        ProgressView()
                            .scaleEffect(0.7)
                            .tint(.white)
                        Text("chat.loading_new_message".localized)
                            .font(.caption)
                            .fontWeight(.medium)
                            .foregroundColor(.white)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 7)
                    .background(
                        Capsule()
                            .fill(appBrandColor.opacity(0.92))
                            .shadow(color: .black.opacity(0.15), radius: 4, x: 0, y: 2)
                    )
                    .padding(.top, 8)
                    Spacer()
                }
                .transition(.move(edge: .top).combined(with: .opacity))
                .zIndex(2)
            }

            // Floating date header (Telegram-style)
            if let dateText = floatingDateHeader {
                VStack {
                    Text(dateText)
                        .font(.caption)
                        .fontWeight(.semibold)
                        .foregroundColor(.white)
                        .padding(.vertical, 6)
                        .padding(.horizontal, 14)
                        .background(
                            Capsule()
                                .fill(Color.black.opacity(0.6))
                        )
                        .transition(.opacity.combined(with: .scale(scale: 0.8)))
                        .padding(.top, 8)
                    Spacer()
                }
                .frame(maxWidth: .infinity)
                .allowsHitTesting(false)
            }

            // Friendly, on-brand empty state for a fresh conversation. An E2E
            // messenger should reassure on first open — same instinct as
            // WhatsApp's encryption notice — instead of a blank wallpaper.
            if cachedLoadedMessages.isEmpty {
                chatEmptyState
                    .transition(.opacity)
                    .allowsHitTesting(false)
            }

            if showScrollToBottom {
                scrollToBottomButton
            }
        }
    }

    // MARK: - Empty State

    private var chatEmptyState: some View {
        VStack(spacing: Theme.Space.md) {
            // __PILL_CONTRAST_2026_08_22__ Encre de marque sur un fond de la
            // MÊME marque à 12 %: en mode sombre le fond reste quasi-noir et le
            // violet profond (luminance ~0.06) disparaît dessus. La marque est
            // une TEINTE de surface, pas une encre, dès que la surface porte
            // déjà cette teinte. Voir LocationSharingView.
            Image(systemName: "lock.fill")
                .font(.scaled(26, weight: .semibold))
                .foregroundColor(.primary)
                .padding(Theme.Space.lg)
                .background(
                    Circle().fill(appBrandColor.opacity(0.22))
                )
            Text(NSLocalizedString("chat.empty.title",
                                   value: "Messages are end-to-end encrypted",
                                   comment: "Empty chat title"))
                .font(Theme.Font.headline)
                .foregroundColor(Theme.textPrimary)
                .multilineTextAlignment(.center)
            Text(NSLocalizedString("chat.empty.subtitle",
                                   value: "No one outside this chat, not even OSHI, can read them. Say hi 👋",
                                   comment: "Empty chat subtitle"))
                .font(.subheadline)
                .foregroundColor(Theme.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, Theme.Space.xl)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.bottom, 80)
    }

    // MARK: - Message Row View (with date search highlight)

    /// Whether `message` continues a run started by `prev`: same sender, close
    /// in time (≤2 min). Drives the tighter run spacing.
    static func messagesAreGrouped(_ prev: SecureMessage?, _ message: SecureMessage) -> Bool {
        guard let prev else { return false }
        guard prev.senderPublicKey == message.senderPublicKey else { return false }
        return message.timestamp.timeIntervalSince(prev.timestamp) <= 120
    }

    @ViewBuilder
    private func messageRowView(for message: SecureMessage, isTail: Bool = true) -> some View {
        let isHighlighted = scrollToMessageId == message.id

        messageBubbleView(for: message, isTail: isTail)
            .overlay(
                // Radius follows the user's bubble shape instead of a fixed 12:
                // a rounded halo behind a square bubble (or the reverse) reads
                // as two unrelated shapes stacked.
                RoundedRectangle(cornerRadius: BubbleStyleManager.shared.cornerRadius,
                                 style: .continuous)
                    .fill(appBrandColor.opacity(isHighlighted ? 0.15 : 0))
                    .padding(-8)
                    .allowsHitTesting(false)
            )
            // New messages rise + fade in; removals just fade (faster). Driven by
            // the `.animation(value: count)` on the LazyVStack below.
            .transition(.asymmetric(
                insertion: .move(edge: .bottom).combined(with: .opacity),
                removal: .opacity))
            // __RIGHT_CLICK_TO_COPY_2026_10_19__ Le menu contextuel du Mac a
            // vécu ici, sur la ligne. Il est maintenant sur la BULLE, avec les
            // actions de `ReactionOverlay` — voir
            // `__MAC_MENU_MATCHES_THE_SHEET_2026_10_20__` dans `MessageBubble`.
    }
    
    // MARK: - Message Bubble
    
    /// __LORA_CHANNEL_IS_A_ROOM_2026_11_04__ Is this thread a LoRa channel?
    ///
    /// A room with several people in it, so each incoming bubble has to carry the
    /// speaker's name — which a 1:1 thread never needs, because there is only
    /// one person it could be.
    private var isLoRaChannelThread: Bool {
        LoRaChannelThread.isChannelKey(conversation.participantPublicKey)
    }

    /// The name above an incoming bubble in a channel room, or nil.
    ///
    /// Resolved HERE and not inside `MessageBubble`: that view's body is
    /// documented as sitting at the Swift type-checker's time limit (see
    /// `failedDecryptText`), and a conditional label inside it is exactly the
    /// kind of addition that tips such a body over into a compile that never
    /// finishes. Wrapping from outside costs one `VStack` and touches nothing.
    private func channelSpeakerLabel(for message: SecureMessage) -> String? {
        guard isLoRaChannelThread else { return nil }
        guard MessageManager.canonicalIdentity(message.senderPublicKey)
                != MessageManager.canonicalIdentity(identityManager.publicKey) else { return nil }
        return LoRaChannelThread.speakerName(
            senderKey: message.senderPublicKey,
            nodes: MeshtasticManager.shared.nodes
        )
    }

    @ViewBuilder
    private func messageBubbleView(for message: SecureMessage, isTail: Bool = true) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            if let speaker = channelSpeakerLabel(for: message) {
                Text(speaker)
                    .font(.caption2)
                    .fontWeight(.semibold)
                    .foregroundColor(.secondary)
                    .padding(.leading, 14)
                    .lineLimit(1)
            }
            messageBubbleBody(for: message, isTail: isTail)
        }
    }

    @ViewBuilder
    private func messageBubbleBody(for message: SecureMessage, isTail: Bool = true) -> some View {
        MessageBubble(
            message: message,
            currentUserPublicKey: identityManager.publicKey,
            identityManager: identityManager,
            meshManager: meshManager,
            contactName: currentAlias,
            contactPublicKey: conversation.participantPublicKey,
            // Drives a body re-render on status/read change (see MessageBubble).
            statusVersion: "\(message.deliveryStatus.rawValue)-\(message.isRead)",
            // __BUBBLE_RUN_2026_08_18__ Position in the run. Computed in the
            // list, where the neighbours are, not in the bubble.
            isLastInRun: isTail,
            // __TRANSLATE_HOIST_2026_09_05__ Present the result from this screen.
            onTranslateRequest: { text in requestTranslation(of: text) },
            // 📊 La bulle remonte le choix; c'est ici qu'on sait à qui l'envoyer.
            onPollVote: { poll, picks in sendPollVote(poll: poll, picks: picks) }
        )
        // STABLE identity: keeps the SAME bubble across status changes so it
        // never destroys/recreates (which would fire the insertion transition on
        // every delivery tick). Status updates come through `statusVersion`
        // instead; genuinely-new messages get a fresh id and animate in.
        .id(message.id)
        // Swipe right to reply, matching groups. Long-press already opens the
        // action menu here (ReactionOverlay), so only the drag is added — this
        // modifier's long-press closure is deliberately a no-op rather than a
        // second, competing gesture.
        .modifier(SwipeToReply(isMine: message.senderPublicKey == identityManager.publicKey) {
            replyingToMessage = ReplyMessage(
                originalMessageId: message.id,
                originalSenderKey: message.senderPublicKey,
                // 🔧 RAW-JSON FIX: this took `plaintextContent` VERBATIM, which
                // is the sentinel payload for a call summary, a location, a
                // reaction or a nested reply — so swiping to reply quoted raw
                // JSON in the banner, in the sent bubble, and in the copy the
                // recipient received. The long-press Reply path (below) had its
                // own hand-rolled cleaner covering 4 of the 24 prefixes; both
                // now go through the one catalog.
                originalText: String(OSHIControlPayload
                    .previewText(for: message.plaintextContent ?? "")
                    .prefix(180)),
                originalTimestamp: message.timestamp,
                originalMediaType: message.mediaType?.rawValue
            )
            replyingToMessageId = message.id
        })
        // 🔧 (2026-08-21) Les deux callbacks qui pilotaient `showScrollToBottom`
        // depuis ICI ont été retirés: un id de ligne qui change à chaque message
        // ne peut pas servir de repère stable. L'ancre du bas s'en charge — un
        // seul écrivain, une seule vérité.
        .overlay(
            // Same reason as the scroll halo above: the search ring has to be
            // the shape of the thing it is ringing, not a fixed 15.
            RoundedRectangle(cornerRadius: BubbleStyleManager.shared.cornerRadius,
                             style: .continuous)
                .stroke(message.id == currentSearchHighlightId ? Color.yellow : Color.clear, lineWidth: 3)
        )
    }
    
    // MARK: - Scroll To Bottom Button
    
    @ViewBuilder
    private var scrollToBottomButton: some View {
        Button {
            Haptics.tap(.light)
            // 🔧 SINGLE-TAP FIX: a lone scrollTo on a LazyVStack only advances
            // ~one screenful (off-screen rows aren't materialized so the extent
            // is just an estimate), which forced 3 taps to reach the bottom.
            // settleScrollToBottom re-issues the scroll over the next few
            // runloop ticks so the newly-materialized rows are accounted for and
            // it lands at the true bottom on this one tap.
            // 🔴 FIX (2026-08-21) — « LA FLÈCHE DISPARAÎT QUAND ON APPUIE DESSUS ».
            // Le tap posait `showScrollToBottom = false` immédiatement et
            // INCONDITIONNELLEMENT, sans jamais vérifier qu'on avait atterri.
            // `settleScrollToBottom` commence par `guard let proxy else { return }`
            // — un no-op silencieux si le proxy est nil — et même avec un proxy
            // valide il peut s'arrêter court sur un fil filtré ou plein d'images.
            // Résultat: le bouton s'efface, on n'est PAS en bas, et plus rien ne
            // peut le relever puisque la dernière ligne n'a jamais été affichée.
            //
            // C'est désormais l'ARRIVÉE au bas qui masque le bouton, via
            // l'onAppear de l'ancre. Si le défilement échoue, la flèche reste —
            // ce qui est exactement ce qu'on veut d'un bouton de secours.
            // __BOTTOM_LANDING_2026_09_12__ « LA FLÈCHE NE RÉAGIT PAS, IL FAUT
            // TAPER PLUSIEURS FOIS ». Ce n'était pas la réactivité du bouton:
            // l'ancien `settleScrollToBottom` lançait QUATRE animations qui se
            // chevauchaient (0,25 s, puis 0,2 s à +0,05 / +0,15 / +0,30 s).
            // Chacune interrompait la précédente et repartait d'une position à
            // mi-parcours vers une étendue encore estimée — d'où un défilement
            // mou qui s'arrêtait court, et un deuxième tap pour finir le travail.
            // Désormais: une seule animation, puis des corrections instantanées
            // jusqu'à ce que l'ancre soit vue.
            // 🔧 FIX (2026-09-15, « flèche plus réactive au clic »): the first
            // pass used a 0.25 s ease-out animation, which on a long thread reads
            // as a soft, laggy glide before it lands. This is a "jump to newest"
            // shortcut — make the first pass INSTANT so the tap snaps straight to
            // the latest message; the settle loop (instant corrections) still
            // accounts for rows/images materializing after the jump.
            followBottom = true
            settleScrollToBottom(scrollProxy, to: "chatBottomAnchor",
                                 animated: false,
                                 isAtBottom: { bottomAnchorSighted })
        } label: {
            Image(systemName: "arrow.down.circle.fill")
                .font(.scaled(36))
                .foregroundColor(.white)
                .background(
                    Circle()
                        .fill(appBrandColor)
                        .frame(width: 44, height: 44)
                )
                .shadow(color: .black.opacity(0.2), radius: 4, x: 0, y: 2)
        }
        .accessibilityLabel("chat.scroll_to_bottom".localized)
        .padding(.trailing, 16)
        .padding(.bottom, 8)
        .transition(.scale.combined(with: .opacity))
    }
    
    // MARK: - Outdated Peer Banner
    //
    // __PEER_VERSION_2026_09_09__ Le commit 0cfc8c9 a change le calendrier de
    // cles du repondeur du Double Ratchet. Un telephone a jour qui parle a un
    // telephone reste en 1.0.43 echoue a dechiffrer sur le chemin « le
    // repondeur parle en premier », et il n'existe aucun moyen de reparer ca
    // depuis ce cote-ci: seule la mise a jour de l'autre le repare.
    //
    // Alors on le DIT, et on le dit platement. Le texte ne parle ni de
    // securite, ni de faille, ni de panne — cette banniere finit en capture
    // d'ecran, et une capture qui dit « faille » circule bien plus loin que le
    // fait qu'elle decrit. Elle dit ce que la personne peut faire.
    //
    // Elle ne bloque RIEN: la saisie, l'envoi, la lecture et la maille
    // continuent exactement comme avant. C'est une ligne au-dessus du fil.

    @ViewBuilder
    private var outdatedPeerBanner: some View {
        if !outdatedPeerBannerDismissed,
           peerVersions.peerIsOutdated(conversation.participantPublicKey) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "arrow.up.circle")
                    .font(.subheadline)
                    .foregroundColor(.orange)
                    .accessibilityHidden(true)

                Text(LocalizedOrEnglish.text("chat.peer_outdated.message"))
                    .font(.caption)
                    .foregroundColor(.primary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)

                Button {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        outdatedPeerBannerDismissed = true
                    }
                } label: {
                    Image(systemName: "xmark")
                        .font(.caption2.weight(.bold))
                        .foregroundColor(.secondary)
                        .padding(4)
                }
                .accessibilityLabel(Text(LocalizedOrEnglish.text("chat.peer_outdated.dismiss")))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Color.orange.opacity(0.12))
            .transition(.opacity)
        }
    }

    // MARK: - Blocked Banner

    @ViewBuilder
    /// __WELCOME_IS_A_REAL_CONVERSATION_2026_10_16__ Who is actually on the
    /// other end of the welcome thread. Shown above a working composer, because
    /// the point is that this message CAN be answered — by a person.
    private var welcomeThreadFooter: some View {
        HStack(spacing: 10) {
            Image(systemName: "person.crop.circle.badge.checkmark")
                .font(.subheadline)
                .foregroundColor(.secondary)
                .accessibilityHidden(true)
            Text("chat.welcome.creator_notice".localized)
                .font(.caption)
                .foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity)
        .oledAwareBackground(.ultraThinMaterial)
    }

    private var blockedBanner: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Image(systemName: "hand.raised.fill")
                    .font(.title3)
                    .foregroundColor(.white)
                
                VStack(alignment: .leading, spacing: 2) {
                    Text("chat.contact_blocked_title".localized)
                        .font(.headline)
                        .foregroundColor(.white)
                    Text("chat.contact_blocked_message".localized)
                        .font(.caption)
                        .foregroundColor(.white.opacity(0.9))
                }
                
                Spacer()
                
                Button {
                    showingUnblockAlert = true
                } label: {
                    Text("chat.unblock_button".localized)
                        .font(.subheadline)
                        .fontWeight(.semibold)
                        .foregroundColor(.red)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Color.white)
                        .cornerRadius(8)
                }
            }
            .padding()
            .background(Color.red)
        }
    }

    /// Reminder right above the box the user is about to type into — sending
    /// here IS allowed (see `sendMessage()`'s interop branch), but it is not
    /// the same thing as texting an OSHI contact, and the compose box is the
    /// last place to say so before the message is gone.
    @ViewBuilder
    private var loRaInteropBanner: some View {
        if isLoRaInteropThread && !isContactBlocked {
            HStack(spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundColor(.orange)
                Text("lora.interop.unverified.detail".localized)
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.orange.opacity(0.12))
        }
    }

    // MARK: - Smart Replies Section

    /// Les commandes proposees pour le texte courant. Vide = pas de barre.
    private func slashCommandSuggestions(for text: String) -> [SlashCommand] {
        SlashCommands.matches(for: text, bots: BotManager.shared.bots)
    }

    private func smartRepliesVisible(text: String) -> Bool {
        smartRepliesEnabled && !nlpManager.smartReplies.isEmpty && text.isEmpty && !isContactBlocked
    }

    @ViewBuilder
    private func smartRepliesSection(text: String) -> some View {
        if smartRepliesVisible(text: text) {
            SmartRepliesView(replies: nlpManager.smartReplies) { reply in
                messageText = reply
            }
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    // MARK: - Recording Hint Overlay

    @ViewBuilder
    private var recordingHintOverlay: some View {
        VStack {
            Spacer()
            HStack {
                Spacer()
                VStack(spacing: 8) {
                    Text("💡 " + "audio.tap_to_start".localized)
                        .font(.caption)
                        .foregroundColor(.white)
                    Text("audio.or_hold".localized)
                        .font(.caption2)
                        .foregroundColor(.white.opacity(0.8))
                }
                .padding()
                .background(Color.black.opacity(0.8))
                .cornerRadius(12)
                .padding(.trailing, 60)
                .padding(.bottom, 140)
                Spacer()
            }
        }
        .transition(.opacity)
        .zIndex(99)
        .onAppear {
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                withAnimation {
                    showRecordingHint = false
                }
            }
        }
    }
    
    // MARK: - Toolbar Content

    /// __TAP_THE_FACE_2026_10_27__ Reporter's N9: "lors de l'appui sur la photo
    /// de profil dans une conversation, afficher la fiche de contact".
    ///
    /// There was no photo to press. A 1:1 chat drew the contact's name as a
    /// plain `navigationTitle` and their face nowhere at all, while
    /// `ContactProfileView` — which already holds the picture, the full public
    /// key, their links and (since today) their QR code — was buried three taps
    /// deep behind the ⋯ menu.
    ///
    /// So the title becomes the door, the way every messenger the person
    /// already uses behaves. `navigationTitle` is kept below for the BACK
    /// button's label on the previous screen, which a principal item does not
    /// supply.
    private var headerTitleButton: some View {
        Button {
            showContactProfile = true
        } label: {
            HStack(spacing: 8) {
                contactHeaderAvatar
                Text(currentAlias)
                    .font(.system(.headline, design: .default).weight(.bold))
                    .foregroundColor(.primary)
                    .lineLimit(1)
                // __DUO_POLISH_2026_11_18__ was the first trailing bar item.
                Circle()
                    .fill(isConnected ? Color.green : Color.gray)
                    .frame(width: 8, height: 8)
                    .accessibilityHidden(true)
                // __MESH_ONLY_UX_2026_11_03__ In a mesh-only conversation, say
                // so here and say whether the radio path to this person is
                // alive. Until now the switch was invisible once set: the menu
                // label flipped, one system line appeared, and after that a
                // mesh-only thread looked exactly like a normal one. At a
                // demonstration that is the difference between "sent" and
                // "sent nowhere", and the person holding the phone could not
                // tell which they had done.
                //
                // Icons and a colour, no new strings: the lock says the relay
                // is off, the antenna goes green when they are in range. Words
                // here would mean 34 locales for less signal than the colour.
                if offlineOnlyStore.isOfflineOnly(conversation.participantPublicKey) {
                    Image(systemName: "lock.fill")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                    Image(systemName: "antenna.radiowaves.left.and.right")
                        .font(.caption2)
                        .foregroundColor(peerReachableOverMesh ? .green : .secondary)
                }
            }
        }
        .buttonStyle(.plain)
        // Existing keys only. A `.localized` lookup that misses returns the KEY,
        // so inventing `mesh.peer_in_range_a11y` here would have had VoiceOver
        // read "mesh.peer_in_range_a11y" aloud in all 34 languages — the same
        // way Vallon's rating prompt shipped raw keys for five versions.
        // The connection dot beside the name reads here too (it used to be
        // its own toolbar element with this label).
        .accessibilityValue(
            offlineOnlyStore.isOfflineOnly(conversation.participantPublicKey)
                ? (peerReachableOverMesh
                    ? "chat.status_connected".localized
                    : "chat.status_disconnected".localized)
                : (isConnected
                    ? "chat.status_connected".localized
                    : "chat.status_disconnected".localized)
        )
        .accessibilityElement(children: .combine)
        .accessibilityLabel(currentAlias)
        .accessibilityHint("profile.contact_profile".localized)
        .accessibilityAddTraits(.isButton)
    }

    /// Their picture if we have one from any source, their initial otherwise —
    /// the same precedence `ContactAddressSheet` uses, so the two never
    /// disagree about what someone looks like.
    @ViewBuilder
    private var contactHeaderAvatar: some View {
        let key = conversation.participantPublicKey
        let photo = ContactPresenceManager.shared.getProfileImage(publicKey: key)
            ?? ContactPhotoManager.shared.getPhoto(for: key)
        if let photo {
            Image(uiImage: photo)
                .resizable()
                .scaledToFill()
                .frame(width: Self.headerAvatarSize, height: Self.headerAvatarSize)
                .clipShape(Circle())
        } else {
            // __ONE_AVATAR_NOT_THREE_2026_11_07__ was a flat
            // `appBrandColor.opacity(0.18)` circle: every contact without a
            // picture was the SAME blue, while the conversation list two taps
            // away gave that same person one of fourteen colours. Reported as
            // "someone with no picture shows up blue in the conversation", and
            // as Anatole's N12.
            DefaultAvatar(key: key, name: currentAlias, size: Self.headerAvatarSize, initialCount: 1)
        }
    }

    /// __ANATOLE_1311_IOS__ (N5) "augmenter la taille du nom et de la photo de
    /// profil" in the chat's top bar: 30 → 36 pt, the largest that still sits
    /// inside the 44 pt compact navigation bar. The bar carries no encryption
    /// line on iOS (the lock below shows only for a mesh-only conversation).
    static let headerAvatarSize: CGFloat = 36

    // Two items now, so the result builder is explicit: without it the second
    // ToolbarItem is read as a plain expression and the whole property stops
    // being ToolbarContent.
    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        // __TAP_THE_FACE_2026_10_27__ The face and the name, together, opening
        // the contact card.
        //
        // __DUO_TITLE_2026_11_18__ ...except in the detail pane of a split
        // (open iPhone Duo upright: a 368 pt pane). A principal item is CENTRED,
        // so it only gets the width left after the trailing group is mirrored on
        // the left — about 50 pt there, and the bar dropped the name and the face
        // altogether. In a split there is no back button, so the leading edge is
        // free: the title takes it and runs up to the call buttons.
        if chatInSplit {
            ToolbarItem(placement: .topBarLeading) {
                headerTitleButton
            }
        } else {
            ToolbarItem(placement: .principal) {
                headerTitleButton
            }
        }
        // __DUO_POLISH_2026_11_18__ A GROUP of items, not one item holding an
        // HStack. On a folded iPhone Duo the system moves bar items into a
        // rail beside the cover screen, one round button per item: the single
        // HStack item was squeezed into one 44 pt circle, the video glyph
        // spilled out of it and the "…" menu was cut off by the screen edge.
        // The connection dot moved to the title, beside the name.
        ToolbarItemGroup(placement: .navigationBarTrailing) {
                // 📞 One tap to call — video first, then voice, closest to the
                // thumb on the right like the platform's own apps.
                headerCallButton(symbol: "video.fill", label: "chat.video_call".localized, video: true)
                headerCallButton(symbol: "phone.fill", label: "chat.voice_call".localized, video: false)

                Menu {
                    Button {
                        showCreateScheduledCall = true
                    } label: {
                        Label("chat.schedule_call".localized, systemImage: "calendar.badge.plus")
                    }
                    Button {
                        withAnimation { showSearch.toggle(); if !showSearch { searchText = "" } }
                    } label: {
                        Label(showSearch ? "chat.close_search".localized : "chat.search".localized,
                              systemImage: showSearch ? "xmark" : "magnifyingglass")
                    }

                    Divider()

                    // 👤 Contact profile — picture (tap to enlarge), the public
                    // key in full with a copy affordance, and the links they
                    // shared. See ContactProfileView.swift.
                    Button {
                        showContactProfile = true
                    } label: {
                        Label("profile.contact_profile".localized, systemImage: "person.crop.circle")
                    }

                    Button {
                        showingAliasEditor = true
                    } label: {
                        Label("chat.set_alias".localized, systemImage: "person.text.rectangle")
                    }

                    // 🔧 REMOVED: Manual contact photo setting
                    // Contact's profile picture should come from THEM, not be set manually
                    // This is WhatsApp behavior - you see the profile picture your contact chose

                    // ✅ Wallpaper option (WhatsApp style)
                    Button {
                        showWallpaperPicker = true
                    } label: {
                        Label("wallpaper.title".localized, systemImage: "photo.artframe")
                    }

                    // 🔗 Profile Links sharing toggle
                    if ProfileLinksManager.shared.myLinks.hasAnyLink {
                        let isSharing = ProfileLinksManager.shared.isSharingEnabled(for: conversation.participantPublicKey)
                        Button {
                            let newValue = !isSharing
                            ProfileLinksManager.shared.setSharingEnabled(newValue, for: conversation.participantPublicKey)
                            let content = ProfileLinksManager.shared.createVisibilityMessage(visible: newValue)
                            sendSystemMessage(content)
                        } label: {
                            Label(
                                isSharing ? "profile_links.stop_sharing".localized : "profile_links.share_links".localized,
                                systemImage: isSharing ? "link.circle.fill" : "link.badge.plus"
                            )
                        }
                    }

                    // 🔗 Hide/Show other's profile links
                    if ProfileLinksManager.shared.getVisibleLinks(for: conversation.participantPublicKey)?.hasAnyLink == true {
                        let isHidden = ProfileLinksManager.shared.isLinksHiddenFor(conversationKey: conversation.participantPublicKey)
                        Button {
                            ProfileLinksManager.shared.toggleLinksHidden(for: conversation.participantPublicKey)
                        } label: {
                            Label(
                                isHidden ? "profile_links.show_bar".localized : "profile_links.hide_bar".localized,
                                systemImage: isHidden ? "eye" : "eye.slash"
                            )
                        }
                    }
                    
                    Divider()

                    // Per-chat media gallery (P2 #20)
                    Button {
                        showMediaGallery = true
                    } label: {
                        Label("media_gallery.title".localized, systemImage: "photo.on.rectangle.angled")
                    }

                    // Copy this contact's public key (for forwarding into group invites etc.)
                    Button {
                        UIPasteboard.general.string = conversation.participantPublicKey
                    } label: {
                        Label("chat.copy_public_key".localized, systemImage: "doc.on.doc")
                    }

                    Divider()

                    // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ Take THIS
                    // conversation off the relay.
                    //
                    // A toggle rather than a destructive-looking button: it is
                    // reversible, and both directions matter — a conversation
                    // that stayed restricted after someone meant to lift it
                    // would strand messages with no explanation.
                    //
                    // The subtitle counts the peers who confirmed, and says so
                    // honestly. Nothing can make another phone refuse the
                    // internet; what this buys is that every peer which
                    // understands the request stops too, and that the number is
                    // visible rather than assumed.
                    Button {
                        let now = !offlineOnlyStore.isOfflineOnly(conversation.participantPublicKey)
                        messageManager.setOfflineOnly(now,
                                                      forContact: conversation.participantPublicKey,
                                                      identityManager: identityManager,
                                                      meshManager: meshManager)
                    } label: {
                        Label(offlineOnlyStore.isOfflineOnly(conversation.participantPublicKey)
                                ? "offline_only.menu.off".localized
                                : "offline_only.menu.on".localized,
                              systemImage: offlineOnlyStore.isOfflineOnly(conversation.participantPublicKey)
                                ? "lock.open" : "lock")
                    }

                    // 💓 __ALIVE_CHECK_IN_2026_11_04__ The only way in while
                    // nothing is configured, and the way back to the settings
                    // afterwards.
                    Button {
                        showingAliveSheet = true
                    } label: {
                        Label("alive.title".localized, systemImage: "heart.text.square")
                    }

                    Divider()

                    // Reset Session button for encryption resync
                    Button {
                        showingResetSessionAlert = true
                    } label: {
                        Label("chat.reset_session".localized, systemImage: "arrow.triangle.2.circlepath")
                    }
                    
                    Divider()
                    
                    // 🚩 Report (App Store guideline 1.2) — always available,
                    // including for an already-blocked contact.
                    Button {
                        showingReportSheet = true
                    } label: {
                        Label("report.contact_title".localized, systemImage: "flag.fill")
                    }

                    // Block/Unblock button
                    if isContactBlocked {
                        Button {
                            showingUnblockAlert = true
                        } label: {
                            Label("chat.unblock_contact".localized, systemImage: "hand.raised.slash")
                        }
                    } else {
                        Button {
                            showingBlockSheet = true
                        } label: {
                            Label("chat.block_contact".localized, systemImage: "hand.raised.fill")
                        }
                    }

                    Divider()
                    
                    Button(role: .destructive) {
                        let publicKey = conversation.participantPublicKey
                        messageManager.deleteConversation(with: publicKey)
                        // 🔧 FIX R20: Clean up orphaned contact alias on conversation deletion
                        var aliases = contactAliases
                        if aliases.removeValue(forKey: publicKey) != nil {
                            if let data = try? JSONEncoder().encode(aliases) {
                                contactAliasesData = data
                                OshiLog.messaging.info("🧹 Cleaned up alias for deleted conversation: \(publicKey.prefix(12))...")
                            }
                        }
                    } label: {
                        Label("alert.delete_conversation".localized, systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .foregroundColor(chatScheme == .dark ? .white : .black)
                }
        }
    }
    
    // 🔴 __PICKER_MODE_RACE_2026_08_22__ `mediaPicker` vivait ici: une SECONDE
    // copie du même branchement single/multiple, déclarée et jamais utilisée —
    // le seul chemin réel est `.sheet(item:)` dans `ChatSheetsModifier`. Deux
    // copies d'une décision, dont une morte, c'est comme ça qu'on corrige la
    // mauvaise pendant une heure. Supprimée plutôt que migrée.

    // MARK: - Audio Preview Sheet
    
    @ViewBuilder
    private var audioPreviewSheet: some View {
        if let audio = recordedAudio {
            AudioPreviewSheet(
                audioAttachment: audio,
                onSend: { processedAudio in
                    sendAudioMessage(processedAudio)
                    recordedAudio = nil
                    showAudioPreview = false
                },
                onCancel: {
                    recordedAudio = nil
                    showAudioPreview = false
                },
                onSendViewOnce: { processedAudio in
                    sendAudioMessage(processedAudio, viewOnce: true)
                    recordedAudio = nil
                    showAudioPreview = false
                }
            )
        }
    }
    
    // MARK: - Functions
    
    /// 🔧 (1.0.10 v4): No-op now that loadedMessages is computed. Kept as a
    /// stable API for the existing call sites (handleOnAppear, refreshMessages,
    /// scenePhase observers). Smart-replies firing on count growth moved to
    /// .onChange(of: messageManager.messages.count) where it belongs.
    private func loadMessages() {
        // Body intentionally empty — see loadedMessages computed property.
    }

    /// Smart-replies trigger: previously inside loadMessages(), now driven from
    /// .onChange(of: messages.count) so it only fires on true count growth (not
    /// on the in-place mutations that now trigger view re-renders too).
    private func triggerSmartRepliesIfNeeded() {
        // __SUGGESTIONS_ARE_A_CHOICE_2026_10_27__ Switched off means the model
        // is not run at all, and anything already computed is dropped.
        guard smartRepliesEnabled else {
            if !nlpManager.smartReplies.isEmpty { nlpManager.smartReplies = [] }
            return
        }
        guard let lastMsg = loadedMessages.last,
              lastMsg.senderPublicKey == conversation.participantPublicKey,
              let content = lastMsg.plaintextContent,
              !content.isEmpty else { return }
        // Was a 13-prefix copy of the catalog.
        let isSystem = OSHIControlPayload.isControl(content)
        if !isSystem {
            let context = conversationContextForAI
            Task {
                await nlpManager.generateSmartReplies(to: content, conversationContext: context)
            }
        } else if !nlpManager.smartReplies.isEmpty {
            // Last message turned into a system event (incoming call, receipt…):
            // drop any stale LLM-generated replies so the bar doesn't keep showing
            // suggestions that were computed against an older peer message.
            nlpManager.smartReplies = []
        }
    }

    /// Pull-to-refresh: Trigger message sync
    private func refreshMessages() async {
        // Trigger VPS/IPFS polling for new messages
        NotificationCenter.default.post(name: NSNotification.Name("CheckForNewMessages"), object: nil)

        // Wait a moment for polling to complete
        try? await Task.sleep(nanoseconds: 1_000_000_000) // 1 second

        // Reload local messages
        await MainActor.run {
            loadMessages()
        }
    }

    private func sendMessage() {
        guard !messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }

        // __CASHU_2026_10_24__ `/pay <token>`. Handled before anything else is
        // formatted or sent: nothing that fails to decode goes on the wire,
        // because a typo in a pasted token is money thrown away and neither
        // side would ever learn why. A 1:1 thread never needs the `public`
        // word, so that outcome is unreachable here.
        if CashuPayCommand.handles(messageText) {
            let outcome = CashuPayCommand.parse(messageText, isGroup: false)
            switch outcome {
            // __CASHU_SEND_READY_2026_11_09__ A decoded token is NOT sent from
            // here any more: it opens the send sheet, which reads back the
            // amount and the mint first (the guide promised exactly that, and a
            // bearer token cannot be recalled). `/pay` alone opens it empty.
            case .send(let token, _):
                cashuDraft = CashuSendDraft(text: token.raw)
                messageText = ""
            case .usage:
                cashuDraft = CashuSendDraft()
                messageText = ""
            default:
                // Refusals KEEP what was typed, like the rate-limit path below:
                // a token is tedious to paste twice and the user has to be able
                // to see what they pasted in order to fix it.
                if let line = CashuPayCommand.message(for: outcome) {
                    errorMessage = line
                    showingError = true
                }
            }
            return
        }

        // __WELCOME_CHAT_IS_NOT_A_PEER_2026_10_16__ The greeting every new user
        // gets is "sent" by `oshi.welcome`, which WelcomeMessageManager's own
        // comment calls "not a person" — there is no key, no session and nobody
        // on the other end. Nothing stopped a reply: the thread opened with a
        // normal composer, the send went to the literal string "oshi.welcome"
        // as if it were a public key, and the message simply never arrived.
        //
        // The greeting's own text is "Click to start a conversation with me",
        // so the app was inviting precisely the one action it could not honour.
        // This is the first thing a new user does, and it failed in silence —
        // reported from the Mac build, but it was never platform-specific.
        //
        // Refused here rather than deeper down so the typed text is KEPT and
        // the reason is shown, which is the same contract as the rate-limit and
        // too-long refusals below.

        // Subtle tactile confirmation on send — the WhatsApp/Telegram "whoosh"
        // feel. Light so rapid-fire sending never feels buzzy.
        Haptics.tap(.light)

        // A "lora!<nodehex>" thread (a bare Meshtastic node, not an OSHI
        // contact) is routed to the plaintext interop lane INSIDE
        // `sendMessage` itself (`MessageManager.sendLoRaInteropReply`, the
        // same choke point as blocking and the rate limiter) — no special
        // case needed here, `outcome.userFacingMessage` below already covers
        // its refusal reasons (no radio, too long for one frame, media).

        // 💬 Format message with reply data if replying
        //
        // __RESTRICTED_NETWORK_HINT_2026_11_18__ With "Disguise OSHI traffic" ON and
        // "Let contacts know…" allowed, the text (or the reply's content) carries the
        // invisible RN-HINT suffix, inside the E2E plaintext. Contacts only: never the
        // welcome thread, a LoRa channel or a bare radio node.
        let key = conversation.participantPublicKey
        let hintable = !isWelcomeConversation() && !LoRaChannelThread.isChannelKey(key) && !key.hasPrefix("lora!")
        let messageContent = RestrictedNetworkHint.outgoing(
            text: messageText, replyTo: replyingToMessage,
            coverOn: hintable && CoverTransport.mode != nil,
            allowed: RestrictedNetworkHint.senderAllows())

        let outcome = messageManager.sendMessage(
            content: messageContent,
            recipientAddress: conversation.participantPublicKey,
            identityManager: identityManager,
            meshManager: meshManager
        )

        // A refusal (rate limit, or too long even split) must be visible and
        // must NOT eat what the user typed — leave the box exactly as it was
        // and say why. `userFacingMessage` is localized in all 34 languages.
        guard outcome.didSend else {
            errorMessage = outcome.userFacingMessage ?? "alert.error".localized
            showingError = true
            return
        }

        // __LONG_SEND_LANDING_2026_09_28__ the bubble lands a beat later (async
        // insert); this tells the count observer it is ours to follow down.
        scrollTracker.lastSendAt = Date()
        messageText = ""
        nlpManager.smartReplies = []  // Clear smart replies after sending
        // __RATING_THREE_MOMENTS_2026_11_15__ Rating moment 1, the early ask:
        // the user's FIRST message in the welcome thread to the developer.
        // `recordWelcomeReply` self-limits to once per install and only arms;
        // Apple's dialog is requested once the screen is calm (keyboard down,
        // nothing presented, no call).
        if isWelcomeConversation() {
            RateAppManager.shared.recordWelcomeReply()
        }

        // 💬 Clear reply state after sending
        replyingToMessage = nil
        replyingToMessageId = nil
    }

    /// __CASHU_SEND_READY_2026_11_09__ The one place a Cashu token leaves a 1:1
    /// composer. Returns the refusal to show, or nil once sent. The outcome used
    /// to be discarded, so a rate-limited or over-budget token still cleared the
    /// box and said "Sent" — the one lie a payment must never tell.
    private func sendCashuToken(_ raw: String) -> String? {
        let outcome = messageManager.sendMessage(
            content: raw,
            recipientAddress: conversation.participantPublicKey,
            identityManager: identityManager,
            meshManager: meshManager
        )
        guard outcome.didSend else {
            return CashuPayCommand.refusalMessage(for: outcome) ?? "alert.error".localized
        }
        scrollTracker.lastSendAt = Date()
        return nil
    }

    /// The positive moment for the first-use rating request is a completed
    /// welcome exchange, not merely tapping Reply on the local system greeting.
    /// The current send is intentionally not yet in `loadedMessages`, so this
    /// verifies the preceding sequence is: user message → Hugo reply → user
    /// follow-up (the send that called this method).
    private func isWelcomeConversation() -> Bool {
        RateAppManager.isWelcomeAuthor(conversation.participantPublicKey)
    }
    
    private func sendAudioMessage(_ attachment: AudioRecorderManager.AudioAttachment, viewOnce: Bool = false) {
        OshiLog.messaging.info("📤 sendAudioMessage() called")
        OshiLog.messaging.info("   Audio data size: \(attachment.data.count) bytes (\(String(format: "%.2f", Double(attachment.data.count) / 1000.0)) KB)")
        OshiLog.messaging.info("   Duration: \(attachment.duration) seconds")
        OshiLog.messaging.info("   File size formatted: \(attachment.fileSizeFormatted)")
        
        messageManager.sendMessage(
            content: "",
            recipientAddress: conversation.participantPublicKey,
            identityManager: identityManager,
            meshManager: meshManager,
            mediaAttachment: attachment.data,
            mediaType: .audio,
            // __VOICE_PREVIEW_2026_09_29__ Vocal « vue unique »: même drapeau que
            // les photos, le destinataire (iOS `ViewOnceAudioPlayer`, Android
            // `ViewOnceMediaContent`) l'écoute une fois puis les octets sont détruits.
            isViewOnce: viewOnce
        )
        
        OshiLog.messaging.info("✅ Audio message sent to MessageManager (viewOnce: \(viewOnce))")
    }
    
    // MARK: - __ATTACH_PANEL_2027_2026_11_17__ Attachment panel

    /// The option row of a 1:1: every entry of the old "+" menu, plus Camera
    /// and Video note (also on the composer). Video note only where a camera
    /// exists (a Mac without one).
    private var attachPanelOptions: [AttachOption] {
        var o = AttachmentPanelLogic.options(for: .direct(cashu: true))
        #if targetEnvironment(macCatalyst)
        if !VideoNoteCaptureController.cameraPresent { o.removeAll { $0 == .videoNote } }
        #endif
        return o
    }

    /// Runs once the panel has closed — the same state flips the menu made.
    private func performAttachOption(_ option: AttachOption) {
        guard !isContactBlocked else { return }
        switch option {
        case .gallery: mediaPickerMode = .library
        case .camera:
            captureCameraMode = .photo
            showWhatsAppCamera = true
        case .file: showDocumentPicker = true
        case .gif: showGifPicker = true
        case .location: showLocationPicker = true
        case .contact: showContactPicker = true
        case .poll: showPollSheet = true
        case .checkIn: showCheckInSheet = true
        case .videoNote:
            var t = Transaction()
            t.disablesAnimations = true
            withTransaction(t) { showVideoNoteRecorder = true }
        case .cashu: cashuDraft = CashuSendDraft()
        case .thinkingOfYou: showScheduledMessage = true
        case .scheduleCall: break  // 1:1 schedules calls from its nav bar
        case .disguisePhoto: showDisguiseSheet = true
        }
    }

    /// Photos/videos picked in the panel's grid, through the existing paths:
    /// one item → `sendMediaMessageWithCaption` (LoRa confirm, view once,
    /// progressive reveal); several → the multi path with the caption on the
    /// first; "Draw" → the markup sheet, caption carried through it.
    private func sendFromAttachPanel(_ send: AttachPanelSend) {
        guard !isContactBlocked, let first = send.attachments.first else { return }
        let caption = send.caption.trimmingCharacters(in: .whitespacesAndNewlines)
        if send.attachments.count == 1 {
            if send.markup, let ui = markupCandidate(first) {
                pendingMarkupCaption = caption
                pendingMarkupImage = MarkupSubject(image: ui)
                pendingMarkupAttachment = first
                return
            }
            sendMediaMessageWithCaption(first, caption: caption)
        } else {
            sendMultipleMediaMessages(send.attachments, caption: caption)
        }
    }

    private func sendMediaMessage(_ attachment: MediaManager.MediaAttachment) {
        // __PHOTO_MARKUP_2026_11_08__ An IMAGE goes to the markup sheet first —
        // which is also iOS's only confirm step for a photo, since a picked
        // image used to be sent the instant it was chosen. Drawing is optional:
        // Send with no strokes sends the original bytes untouched.
        //
        // Videos and everything else keep the immediate path: drawing on a
        // video means re-encoding it, which is a different feature.
        if let ui = markupCandidate(attachment) {
            pendingMarkupCaption = ""
            pendingMarkupImage = MarkupSubject(image: ui)
            pendingMarkupAttachment = attachment
            return
        }
        sendMediaMessageWithCaption(attachment, caption: "")
    }

    /// The image behind an attachment, when it is one we can draw on.
    private func markupCandidate(_ attachment: MediaManager.MediaAttachment) -> UIImage? {
        guard attachment.type == .image else { return nil }
        return UIImage(data: attachment.data)
    }

    /// __VIDEO_NOTE_2026_09_24__ A round video message goes through the SAME
    /// encrypted media path as any video (V2 blob store, legacy fallbacks, retry
    /// queue); only the two optional wire keys of docs/VIDEO_NOTE_SPEC.md differ.
    private func sendVideoNote(_ data: Data, meta: VideoNoteMetadata) {
        guard !isContactBlocked else { return }
        messageManager.sendMessage(
            content: "",
            recipientAddress: conversation.participantPublicKey,
            identityManager: identityManager,
            meshManager: meshManager,
            mediaAttachment: data,
            mediaType: .video,
            mediaFileName: VideoNote.fileName(),
            videoNote: meta
        )
    }
    
    // ✅ NEW: Send media with optional caption (like WhatsApp)
    private func sendMediaMessageWithCaption(_ attachment: MediaManager.MediaAttachment, caption: String) {
        // Slow-send guard: with no physical internet path at all and a LoRa radio
        // ready, an opted-in media send is about to ride LoRa — minutes on the
        // air, and only small media fits. Confirm first, with an airtime estimate.
        // Every other path (any internet, or no radio) sends straight through.
        if mediaOverLoRaEnabled,
           MeshtasticManager.shared.isReady,
           !NetworkReachabilityManager.shared.hasPhysicalPath {
            pendingLoRaMedia = PendingLoRaMedia(
                attachment: attachment, caption: caption,
                estimate: MeshtasticManager.loRaMediaEstimate(mediaBytes: attachment.data.count))
            return
        }
        performMediaSend(attachment, caption: caption)
    }

    /// Body of the slow-LoRa-send confirmation: an honest airtime estimate, or a
    /// heads-up that the media is likely too large for LoRa (the real .tooLarge
    /// refusal, with its Settings banner, remains the authority either way).
    private func loRaConfirmMessage(for pending: PendingLoRaMedia) -> String {
        let s = pending.estimate.seconds
        let duration = s < 60
            ? "lora.media.confirm.seconds".localized(with: s)
            : "lora.media.confirm.minutes".localized(with: Int((Double(s) / 60.0).rounded(.up)))
        return pending.estimate.fits
            ? "lora.media.confirm.body".localized(with: duration)
            : "lora.media.confirm.toobig".localized
    }

    /// The actual media send, reached directly when online and after the user
    /// confirms the slow LoRa send when off-grid.
    private func performMediaSend(_ attachment: MediaManager.MediaAttachment, caption: String) {
        let isOnlineMode = meshManager.connectedPeers.isEmpty
        
        // Cloud delivery ceiling. Raised 50 → 200 MB: the V2 blob path streams the
        // file to the server chunk-by-chunk (no base64 envelope, no nginx wall), so
        // the old base64/RAM reasoning no longer bounds it. Files >50 MB to a
        // non-V2 peer still fall back to the legacy resumable chunked path.
        let maxFileSize = 200_000_000  // 200MB
        if isOnlineMode && attachment.data.count > maxFileSize {
            showingError = true
            errorMessage = "error.file_too_large_message".localized
            return
        }
        
        // __REVEAL_SEND_2026_08_22__ Une durée cochée dans l'écran d'aperçu était
        // jusqu'ici JETÉE ici même: `attachment.revealDuration` n'était lu par
        // personne, et la photo partait entière.
        if let duration = attachment.revealDuration, attachment.type == .image,
           sendAsTimedReveal(attachment, caption: caption, duration: duration) {
            return
        }

        messageManager.sendMessage(
            content: caption,  // ✅ Include caption with media
            recipientAddress: conversation.participantPublicKey,
            identityManager: identityManager,
            meshManager: meshManager,
            mediaAttachment: attachment.data,
            mediaType: attachment.type,
            mediaFileName: attachment.fileName,
            isViewOnce: attachment.isViewOnce  // ✅ Pass view once flag
        )
    }

    /// Envoie une photo en révélation progressive.
    ///
    /// - Returns: `false` quand la photo ne s'y prête pas — l'appelant enchaîne
    ///   alors sur l'envoi normal. C'est délibérément un REFUS SILENCIEUX plutôt
    ///   qu'une erreur: une photo trop petite pour être dégradée (l'échelle ne
    ///   produit qu'une couche) part simplement telle quelle. Refuser l'envoi
    ///   parce qu'une option décorative ne s'applique pas serait absurde.
    ///
    /// La couche 0 part TOUT DE SUITE, avec l'identifiant qui servira de fil
    /// conducteur; les suivantes sont persistées avec leur échéance absolue.
    private func sendAsTimedReveal(_ attachment: MediaManager.MediaAttachment,
                                   caption: String,
                                   duration: TimeInterval) -> Bool {
        let layers = TimedRevealEncoder.layers(from: attachment.data)
        guard layers.count >= 2 else {
            OshiLog.messaging.info("🖼️ Photo trop petite pour une révélation (\(layers.count) couche) — envoi normal")
            return false
        }

        let revealId = UUID().uuidString
        let totalMs = Int(duration * 1000)
        let peer = conversation.participantPublicKey

        _ = messageManager.sendMessage(
            content: caption,
            recipientAddress: peer,
            identityManager: identityManager,
            meshManager: meshManager,
            mediaAttachment: layers[0].jpeg,
            mediaType: .image,
            mediaFileName: attachment.fileName,
            reveal: RevealSendContext(revealId: revealId, layerIndex: 0,
                                      layerCount: layers.count, totalMs: totalMs),
            forcedMessageId: revealId
        )

        RevealOutbox.shared.enqueue(
            revealId: revealId, peerKey: peer,
            filename: attachment.fileName, mime: "image/jpeg",
            layers: layers.map { $0.jpeg },
            totalSeconds: duration
        )
        OshiLog.messaging.info("🖼️ Révélation envoyée: couche 0 partie, \(layers.count - 1) en file sur \(Int(duration)) s")
        return true
    }
    
    private func sendMultipleMediaMessages(_ attachments: [MediaManager.MediaAttachment]) {
        sendMultipleMediaMessages(attachments, caption: "")
    }

    /// `caption` rides the FIRST item only (`AttachmentPanelLogic.captions`).
    private func sendMultipleMediaMessages(_ attachments: [MediaManager.MediaAttachment], caption: String) {
        let captions = AttachmentPanelLogic.captions(count: attachments.count, typed: caption)
        let isOnlineMode = meshManager.connectedPeers.isEmpty
        let maxFileSize = 200_000_000  // 200MB — matches the V2 streaming send ceiling

        for (index, attachment) in attachments.enumerated() {
            if isOnlineMode && attachment.data.count > maxFileSize {
                showingError = true
                errorMessage = String(format: "media.file_number_too_large".localized, index + 1, String(format: "%.1f", Double(attachment.data.count) / 1_000_000.0))
                return
            }
        }
        
        for (index, attachment) in attachments.enumerated() {
            messageManager.sendMessage(
                content: captions[index],
                recipientAddress: conversation.participantPublicKey,
                identityManager: identityManager,
                meshManager: meshManager,
                mediaAttachment: attachment.data,
                mediaType: attachment.type,
                mediaFileName: attachment.fileName,
                isViewOnce: attachment.isViewOnce  // ✅ Pass view once flag
            )
        }
    }
    
    // MARK: - Scheduled Call Event
    
    private func sendScheduledCallEvent(_ event: ScheduledCallEvent) {
        // Encode event as JSON and embed in content with special prefix
        guard let eventData = try? JSONEncoder().encode(event),
              let eventJson = String(data: eventData, encoding: .utf8) else { return }
        
        // Use a special prefix to identify scheduled call messages
        let content = "📅SCHEDULED_CALL📅\(eventJson)"
        
        messageManager.sendMessage(
            content: content,
            recipientAddress: conversation.participantPublicKey,
            identityManager: identityManager,
            meshManager: meshManager
        )
    }
    
    // 🔗 Send system message (links visibility, etc.)
    private func sendSystemMessage(_ content: String) {
        messageManager.sendMessage(
            content: content,
            recipientAddress: conversation.participantPublicKey,
            identityManager: identityManager,
            meshManager: meshManager
        )
    }

    // 🔗 Auto-share profile links when user has links and opens conversation
    private func autoShareProfileLinksIfNeeded() {
        if let message = ProfileLinksManager.shared.autoShareMessageIfNeeded(for: conversation.participantPublicKey) {
            sendSystemMessage(message)
            OshiLog.messaging.info("🔗 Auto-shared profile links with \(conversation.participantPublicKey.prefix(10))...")
        }
    }

    // ✅ Location sharing (WhatsApp-style)
    private func sendLocationMessage(_ locationMessage: LocationMessage) {
        let content = locationMessage.toMessageString()
        
        // 📍 If this is a live location UPDATE, update the existing message instead of creating new
        if locationMessage.isUpdate, let sessionId = locationMessage.sessionId {
            // Update own message locally (sender's view)
            messageManager.updateOwnLocationMessage(sessionId: sessionId, newLocationMessage: locationMessage)
            
            // Send update to recipient - skip creating new local message since we just updated
            messageManager.sendMessage(
                content: content,
                recipientAddress: conversation.participantPublicKey,
                identityManager: identityManager,
                meshManager: meshManager,
                skipLocalMessage: true  // 📍 Don't create new local message
            )
            OshiLog.messaging.info("📍 Live location UPDATE sent (sessionId: \(sessionId.prefix(8))...)")
        } else {
            // Initial live location or one-time location - create new message
            messageManager.sendMessage(
                content: content,
                recipientAddress: conversation.participantPublicKey,
                identityManager: identityManager,
                meshManager: meshManager
            )
            OshiLog.messaging.info("📍 Location message sent: \(locationMessage.isLive ? "Live (initial)" : "One-time")")
        }
    }

    // 📊 Sondage
    //
    // Un sondage est un message ordinaire: il part comme du texte, il se range
    // dans le fil, et il se dessine parce que son préfixe est au catalogue.
    private func sendPoll(_ poll: PollMessage) {
        messageManager.sendMessage(
            content: poll.toMessageString(),
            recipientAddress: conversation.participantPublicKey,
            identityManager: identityManager,
            meshManager: meshManager
        )
        OshiLog.messaging.infoPublic("📊 poll sent with \(poll.options.count) option(s)")
    }

    /// Le bulletin est enregistré LOCALEMENT d'abord, puis envoyé.
    ///
    /// L'ordre compte: la barre doit bouger sous le doigt, sans attendre un
    /// aller-retour réseau qui, sur un maillage, peut ne jamais revenir. Le
    /// message est `skipLocalMessage: true` — un bulletin n'est pas une ligne
    /// du fil, il est muet au catalogue, et le fil n'a rien à en montrer.
    private func sendPollVote(poll: PollMessage, picks: [Int]) {
        let vote = PollVotePayload(pollId: poll.id, picks: picks)
        PollStore.shared.record(vote: vote, from: identityManager.publicKey)
        messageManager.sendMessage(
            content: vote.toMessageString(),
            recipientAddress: conversation.participantPublicKey,
            identityManager: identityManager,
            meshManager: meshManager,
            skipLocalMessage: true
        )
        OshiLog.messaging.infoPublic("📊 ballot sent (\(picks.count) pick(s))")
    }

    // 🛡️ Check-In / Accompagnement
    private func sendCheckInMessage(_ checkInMessage: CheckInMessage) {
        let content = checkInMessage.toMessageString()

        // 🔧 FIX: For Check-In updates, update existing message instead of creating new
        // This prevents flooding the chat with "Location Update" messages every 5 minutes
        if checkInMessage.type == .update {
            // Update own message locally (sender's view)
            messageManager.updateOwnCheckInMessage(sessionId: checkInMessage.sessionId, newCheckInMessage: checkInMessage)

            // Send update to recipient - skip creating new local message
            messageManager.sendMessage(
                content: content,
                recipientAddress: conversation.participantPublicKey,
                identityManager: identityManager,
                meshManager: meshManager,
                skipLocalMessage: true
            )
            OshiLog.messaging.info("🛡️ Check-In UPDATE sent (sessionId: \(checkInMessage.sessionId.prefix(8))...)")
        } else {
            // Initial check-in, arrived, warning, emergency - create new message
            messageManager.sendMessage(
                content: content,
                recipientAddress: conversation.participantPublicKey,
                identityManager: identityManager,
                meshManager: meshManager
            )
            OshiLog.messaging.info("🛡️ Check-In message sent: \(checkInMessage.type.rawValue)")
        }
    }

    // MARK: - Call Notification Handlers
    
    // 📞 __CALL_SUMMARY_LOCAL_2026_08_22__ `handleCallEnded` vivait ici et
    // ENVOYAIT le résumé au pair — qui en fabriquait un de son côté et nous
    // l'envoyait aussi. D'où les deux messages « appel entrant 2:10 » / « appel
    // sortant 2:10 » à la fin de chaque appel, celui d'en face portant en plus
    // la direction de SON expéditeur.
    //
    // L'écriture est passée dans `MessageManager.handleCallEndedWriteSummary`:
    // rien ne part sur le réseau, et le résumé est consigné même quand cette
    // conversation n'est pas à l'écran — ce qui était le cas de tout appel pris
    // depuis CallKit ou depuis un autre écran.
    //
    // `handleMissedCall` reste ici, et continue d'émettre: un appel manqué peut
    // n'avoir été vu que par l'appelant.
    
    private func handleMissedCall(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let peerKey = userInfo["peerPublicKey"] as? String,
              peerKey == conversation.participantPublicKey,
              let reason = userInfo["reason"] as? String,
              let wasOutgoing = userInfo["wasOutgoing"] as? Bool else { return }
        // __BLOCKED_NO_CALL_TRACE_2026_09_24__ no call line with a blocked contact, ever.
        guard !BlockedContactsManager.shared.isBlocked(peerKey) else { return }
        
        // Store localization KEY (not localized text) so each device can display in their language
        var content: String
        
        if wasOutgoing {
            // I called them - store key for recipient to localize
            switch reason {
            case "no_answer":
                content = "📱CALL_SUMMARY📱📞↗️call.no.answer"
            case "declined":
                content = "📱CALL_SUMMARY📱📞↗️call.declined"
            default:
                content = "📱CALL_SUMMARY📱📞↗️call.no.answer"
            }
        } else {
            // They called me
            switch reason {
            case "no_answer":
                content = "📱MISSED_CALL📱❌call.missed"
            case "declined":
                content = "📱CALL_SUMMARY📱📞↙️call.declined"
            case "hung_up":
                // They hung up before I answered
                content = "📱MISSED_CALL📱❌call.missed"
            default:
                content = "📱MISSED_CALL📱❌call.missed"
            }
        }
        
        messageManager.sendMessage(
            content: content,
            recipientAddress: conversation.participantPublicKey,
            identityManager: identityManager,
            meshManager: meshManager
        )
    }
    
    private func handleJoinScheduledCall(_ notification: Notification) {
        guard notification.userInfo?["eventId"] != nil else { return }
        
        // Start voice call
        showVoiceCall = true
        Task {
            try? await voiceCallManager.startCall(
                to: conversation.participantPublicKey,
                peerName: currentAlias
            )
        }
    }
    
    /// Format event duration (in seconds) to human readable string
    private func formatEventDuration(_ seconds: TimeInterval) -> String {
        let totalMinutes = Int(seconds / 60)
        if totalMinutes < 60 {
            return "\(totalMinutes) min"
        } else {
            let hours = totalMinutes / 60
            let mins = totalMinutes % 60
            if mins == 0 {
                return "\(hours)h"
            }
            return "\(hours)h \(mins)m"
        }
    }
    
    private func saveAlias(_ alias: String) {
        var aliases = contactAliases
        // __SHARED_NICKNAME_2026_09_22__ Un alias vide EFFACE l'alias: le contact
        // retombe alors sur son pseudo partage au lieu d'un nom blanc.
        let trimmed = alias.trimmingCharacters(in: .whitespacesAndNewlines)
        aliases[conversation.participantPublicKey] = trimmed.isEmpty ? nil : trimmed
        if let encoded = try? JSONEncoder().encode(aliases) {
            contactAliasesData = encoded
        }
    }
    
    private func checkConnection() {
        isConnected = meshManager.connectedPeers.contains { peer in
            peer.displayName.contains(conversation.participantAddress.suffix(8))
        }
    }
    
    /// Put the first unread message at the TOP of the viewport and keep it
    /// there while the list materialises.
    ///
    /// Same shape, and same reason, as `landAtBottom`: a `LazyVStack` row that
    /// has not been built cannot be scrolled to, and photo bubbles grow as they
    /// decode, so one shot lands in the wrong place on exactly the threads that
    /// matter. `.top` rather than `.center` so the unread run reads downward
    /// from the divider, with the last read message just above it for context.
    @MainActor
    private func landOnUnreadAnchor(_ proxy: ScrollViewProxy, id: String) {
        followBottom = false
        // __UNREAD_HAD_NO_LINE_2026_11_07__ The divider, not the bubble: anchoring
        // `.top` on the message puts the line one row above the viewport, where
        // the reader never sees the thing that explains where they are.
        // __UNREAD_LANDING_HOLD_2026_11_16__ `.top` on the divider, as the group
        // thread does. The first unread moved AFTER it was first drawn
        // (ChatScrollLabUITests.test1to1OpenOnFirstUnread, 0/3 once the list
        // became eager, by 3-7 pt; logged frame by frame), for two reasons:
        //  1. The eager list aimed at a FRACTION of the viewport (72 / height,
        //     `UnitPoint(y: 0.117)`). When the smart-reply bar arrives (3-7 s
        //     after the open) the list loses 66.67 pt from below, and the scroll
        //     view re-places the last target at the same fraction of the smaller
        //     viewport: the line jumped up 0.117 x 66.67 = 7.7 pt (logged
        //     239.67 -> 232) until the next pass re-aimed. A `.top` target does
        //     not move when the list shrinks from below. It also lands the line
        //     right under the floating header (first unread at y 223.3, as on
        //     the lazy list before), not under it — measured on the current list.
        //  2. The chat's frame and safe area kept settling, in tiny per-frame
        //     steps, for > 10 s after the open (top inset 0 -> 14 pt and more,
        //     list container 700 -> 600 pt) while the fixed 4.5 s hold had
        //     already ended: the line drifted 184 -> 191.7 pt with nothing
        //     re-aiming it. The cause of that slow settle is handled at the
        //     root of `body` (see the geometry observer there); the hold also
        //     lasts until the list's shape (viewport, top inset, content
        //     height) and the line's position have been still for
        //     `unreadLandingSettle`, bounded by `unreadLandingMaxHold`, and
        //     re-aims only when one of them moved.
        // Anything the reader does — a drag, the down arrow or a send (both turn
        // `followBottom` back on), a jump to a message, a search — ends it.
        let eager = listIsEager
        withAnimation(nil) { proxy.scrollTo(ChatView.unreadDividerId, anchor: .top) }
        let start = Date()
        Task { @MainActor in
            let minHold: TimeInterval = eager ? 4.5 : 2.0
            // What the list looked like once the last aim had landed: the
            // divider's position and the list's shape. Re-aim only when one of
            // them moved — under accessibility every `scrollTo` re-lays out the
            // whole eager window (sampled: `ScrollViewProxy.scrollTo` was the
            // top main-thread frame, 209 samples, with the old blind 80 ms
            // re-aim).
            var landed: [CGFloat]? = nil
            var aimPending = true
            var stillSince = Date()
            while true {
                try? await Task.sleep(for: .milliseconds(80))
                // The reader moving is always the last word, as everywhere else
                // in this view.
                if userScrolledSinceOpen || followBottom || scrollToMessageId != nil
                    || isFiltering || openUnreadAnchorId != id
                    || (scrollTracker.lastSendAt.map { $0 > start } ?? false) { return }

                // __UNREAD_ANCHOR_MUST_BE_A_RENDERED_ROW_2026_10_23__ The same
                // trap as `GroupChatView`, narrower here because this anchor is
                // already read off the loaded window rather than the whole
                // history — but the retry below is just as blind, and the
                // `.onChange` re-settle deliberately stands down while
                // `openUnreadAnchorId` is set. So if the row stops existing, the
                // thread would stay parked at the top with nothing coming to
                // rescue it. Clearing the anchor is what hands the job back to
                // that re-settle.
                let rows = loadedMessages
                if !rows.isEmpty, !rows.contains(where: { $0.id == id }) {
                    openUnreadAnchorId = nil
                    followBottom = true
                    settleScrollToBottomOnOpen(
                        proxy,
                        to: "chatBottomAnchor",
                        isAtBottom: { landedAtBottom },
                        shouldAbort: { userScrolledSinceOpen }
                    )
                    return
                }

                let now = Date()
                let state = [scrollTracker.unreadDividerMinY ?? .nan] + scrollTracker.layoutSignature
                // Not built yet, or an aim fired before the row existed and went
                // nowhere: the line is not in the top part of the viewport.
                let dividerY = scrollTracker.unreadDividerMinY
                let viewport = scrollTracker.viewportHeight ?? 0
                if dividerY == nil || viewport <= 0 || dividerY! < -2 || dividerY! > viewport * 0.5 {
                    stillSince = now
                    withAnimation(nil) { proxy.scrollTo(ChatView.unreadDividerId, anchor: .top) }
                    aimPending = true
                    let held = now.timeIntervalSince(start)
                    if held >= ChatView.unreadLandingMaxHold { break }
                    continue
                }
                if aimPending {
                    // The previous aim has been laid out: this is where it landed.
                    aimPending = false
                    if landed.map({ !Self.sameLayout($0, state) }) ?? true { stillSince = now }
                    landed = state
                } else if let landed, !Self.sameLayout(landed, state) {
                    stillSince = now
                    withAnimation(nil) { proxy.scrollTo(ChatView.unreadDividerId, anchor: .top) }
                    aimPending = true
                    continue
                }
                let held = now.timeIntervalSince(start)
                if held >= ChatView.unreadLandingMaxHold { break }
                if held >= minHold && now.timeIntervalSince(stillSince) >= ChatView.unreadLandingSettle { break }
            }
        }
    }

    /// Divider position + list shape, compared to half a point.
    private static func sameLayout(_ a: [CGFloat], _ b: [CGFloat]) -> Bool {
        guard a.count == b.count else { return false }
        return zip(a, b).allSatisfy { x, y in (x.isNaN && y.isNaN) || abs(x - y) < 0.5 }
    }

    /// How long the list geometry must stay still before the open stops holding
    /// the unread line, and the longest it holds it at all.
    static let unreadLandingSettle: TimeInterval = 1.0
    static let unreadLandingMaxHold: TimeInterval = 15

    private func markMessagesAsRead() {
        let autoDeleteOnRead = UserDefaults.standard.bool(forKey: "autoDeleteOnRead")

        if autoDeleteOnRead {
            // Must handle individually for deletion
            for message in loadedMessages where !message.isRead {
                messageManager.markAsRead(message.id)
                messageManager.deleteMessage(message.id)
            }
        } else {
            // Batch mark all as read (single save)
            messageManager.markConversationAsRead(participantPublicKey: conversation.participantPublicKey)
        }
    }
    
    private func formatAddress(_ address: String) -> String {
        guard address.count > 10 else { return address }
        let start = address.prefix(6)
        let end = address.suffix(4)
        return "\(start)...\(end)"
    }
    
    private func navigateToNextResult() {
        guard !filteredMessages.isEmpty else { return }
        currentSearchIndex = min(currentSearchIndex + 1, filteredMessages.count - 1)
        scrollToCurrentSearchResult()
    }
    
    private func navigateToPreviousResult() {
        guard !filteredMessages.isEmpty else { return }
        currentSearchIndex = max(currentSearchIndex - 1, 0)
        scrollToCurrentSearchResult()
    }
    
    private func scrollToCurrentSearchResult() {
        guard currentSearchIndex < filteredMessages.count else { return }
        let message = filteredMessages[currentSearchIndex]
        // __FILTERED_RESULTS_WINDOW_2026_11_16__ A match outside the drawn window
        // has no row to scroll to: move the window first, scroll once it is built.
        if revealResult(at: currentSearchIndex) {
            DispatchQueue.main.async {
                withAnimation(nil) { scrollProxy?.scrollTo(message.id, anchor: .center) }
            }
            return
        }
        withAnimation {
            scrollProxy?.scrollTo(message.id, anchor: .center)
        }
    }
    
    private func formatDuration(_ duration: TimeInterval) -> String {
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

// MARK: - ✅ Input View with INSTANT Visual Feedback

struct ProfessionalAudioInputView: View {
    @Binding var text: String
    @ObservedObject var audioRecorder: AudioRecorderManager
    @Binding var isRecordingAudio: Bool
    @Binding var recordingMode: ChatView.RecordingMode
    @Binding var showRecordingHint: Bool
    /// True while the keyboard is up for this composer. Owned here, observed by
    /// the chat so it can return to the newest message — see the `.focused`
    /// modifier for why the scroll anchor alone does not cover it.
    @FocusState private var isComposerFocused: Bool
    /// __FOLDABLE_LAYOUT_2026_11_16__ The thread's `ThreadHandoff` key: the
    /// keyboard state is mirrored there, and a fold / unfold asks for it back.
    var handoffKey: String? = nil
    /// This chat view's hand-off identity: a focus request names the view.
    var handoffToken: UUID? = nil
    /// Called when the field gains focus. The chat scrolls to the bottom.
    var onComposerFocused: (() -> Void)? = nil
    let onSend: () -> Void
    /// __ATTACH_PANEL_2027_2026_11_17__ "+" opens the attachment panel (recent
    /// photos + every option the old menu listed); the chat owns it.
    let onAttachTap: () -> Void
    let onCameraTap: () -> Void
    let onVideoTap: () -> Void
    let onAudioRecorded: (AudioRecorderManager.AudioAttachment) -> Void
    
    // 💬 Reply support
    var replyMessage: ReplyMessage? = nil
    var replyContactAlias: String? = nil
    var onCancelReply: (() -> Void)? = nil
    
    // ⌨️ Typing indicator callback
    var onTypingChanged: ((Bool) -> Void)? = nil

    // AI context: last received message for tone/improvement
    var aiReplyContext: String? = nil

    @Environment(\.colorScheme) var colorScheme
    @State private var isPressing = false
    @State private var showAIShortcuts = false
    /// __TYPE_HINT_2026_08_14__ Shown for a few seconds when "Start chat" lands on
    /// the conversation the user is already reading. Focusing the composer alone
    /// was invisible — on the Mac no keyboard rises, and on iOS the field may
    /// already hold focus — so the button still read as dead. This says it.
    @State private var showTypeHint = false

    private var inputBackground: Color {
        colorScheme == .dark ? Color(.systemGray6) : Color(.systemGray5)
    }
    
    private var isEmpty: Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    // MARK: __VIDEO_NOTE_2026_09_24__ Composer chrome
    //
    // Owner: "when I type the box seems small". The camera and video-note buttons
    // fold away as soon as there is text, and the field takes their width and a
    // few more lines; clearing the text brings them back. `ComposerChrome` holds
    // the rule (unit-tested), this view only animates it.
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var showsCaptureButtons: Bool { ComposerChrome.showsCaptureButtons(text: text) }

    private var chromeAnimation: Animation {
        reduceMotion ? .easeInOut(duration: 0.18) : .spring(response: 0.36, dampingFraction: 0.78)
    }

    private var captureButtonTransition: AnyTransition {
        reduceMotion
            ? .opacity
            : .asymmetric(insertion: .scale(scale: 0.4, anchor: .trailing).combined(with: .opacity),
                          removal: .scale(scale: 0.4, anchor: .leading).combined(with: .opacity))
    }

    /// Mac without a camera: the video-note button is shown disabled with a tooltip.
    private var videoNoteAvailable: Bool {
        #if targetEnvironment(macCatalyst)
        return VideoNoteCaptureController.cameraPresent
        #else
        return true
        #endif
    }
    
    var body: some View {
        VStack(spacing: 0) {
            // "You're already here — write." Reuses messages.type_message, which is
            // already translated into all 34 locales, so this ships with no new keys.
            if showTypeHint {
                HStack(spacing: 8) {
                    Image(systemName: "arrow.down")
                        .font(.caption.bold())
                        .accessibilityHidden(true)
                    Text("messages.type_message".localized)
                        .font(.subheadline.weight(.medium))
                }
                .foregroundColor(.white)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(Capsule().fill(Theme.brand))
                .padding(.bottom, 8)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .accessibilityAddTraits(.isStaticText)
            }

            // 💬 Reply preview (if replying to a message)
            if let reply = replyMessage {
                ReplyPreviewView(
                    reply: reply,
                    contactAlias: replyContactAlias,
                    onCancel: {
                        onCancelReply?()
                    }
                )
            }
            
            VStack(spacing: 8) {
                // ✅ INSTANT Recording indicator - shows immediately when holding
                if isRecordingAudio {
                    HStack(spacing: 12) {
                        // Continuously pulsing red dot (was bound to a state that
                        // never changed mid-recording, so it didn't actually pulse).
                        RecordingPulseDot()

                        // Live timer - updates in real-time
                        Text(formatDuration(audioRecorder.recordingDuration))
                            .font(.scaled(16, weight: .medium, design: .monospaced))
                            .foregroundColor(.red)
                            .id(audioRecorder.recordingDuration) // Force refresh
                        
                        // Live waveform
                        MiniWaveformView(levels: audioRecorder.audioLevels)
                            .frame(height: 20)
                        
                        Spacer()
                        
                        // Instruction
                        Text(recordingMode == .tapToRecord ?
                             "audio.tap_stop_to_send".localized :
                             "audio.release_to_send".localized)
                            .font(.caption)
                            .foregroundColor(.red)
                    }
                    .padding(.horizontal)
                    .padding(.vertical, 8)
                    .background(Color.red.opacity(0.1))
                    .cornerRadius(8)
                    .padding(.horizontal)
                    .transition(.move(edge: .top).combined(with: .opacity))
                }
                
                HStack(spacing: 8) {
                    // __ATTACH_PANEL_2027_2026_11_17__ "+" opens the attachment
                    // panel: recent photos with multi-select and a caption, and
                    // the option row (Gallery, Camera, File, GIF, Location,
                    // Contact, Poll, Check-In, Video note, Cashu, Thinking of
                    // You) — the same actions the system menu listed here.
                    Button {
                        onAttachTap()
                    } label: {
                        Image(systemName: "plus")
                            .font(.scaled(22, weight: .medium))
                            .foregroundColor(appBrandColor)
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .frame(width: 36, height: 44)
                    .accessibilityLabel(Text("attach.button".localized))
                    .accessibilityIdentifier("composer.attach")
                    .disabled(isRecordingAudio)
                
                if showsCaptureButtons {
                    // ✅ Camera button - Opens camera directly (WhatsApp style)
                    Button {
                        onCameraTap()
                    } label: {
                        Image(systemName: "camera.fill")
                            .font(.scaled(20))
                            .foregroundColor(appBrandColor)
                            .frame(width: 36, height: 44)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel(Text("camera_permission".localized))
                    .disabled(isRecordingAudio)
                    .transition(captureButtonTransition)

                    // __VIDEO_NOTE_2026_09_24__ Opens the ROUND video recorder
                    // (15 s, OSHI ring) — no longer the full-screen camera.
                    Button {
                        onVideoTap()
                    } label: {
                        Image(systemName: "video.circle.fill")
                            .font(.scaled(24))
                            .foregroundColor(videoNoteAvailable ? appBrandColor : .gray)
                            .frame(width: 36, height: 44)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel(Text("videonote.button".localized))
                    .help(videoNoteAvailable ? "videonote.button".localized : "videonote.no_camera".localized)
                    .disabled(isRecordingAudio || !videoNoteAvailable)
                    .transition(captureButtonTransition)
                }
                
                TextField("messages.type_message".localized, text: $text, axis: .vertical)
                    .privateTypingComposer()  // __KEYBOARD_PRIVACY_2026_10_01__ no autocorrect learning when Private typing is on
                    .accessibilityIdentifier("chat.composer")
                    .textFieldStyle(.plain)
                    .padding(.horizontal, showsCaptureButtons ? 10 : 14)
                    .padding(.vertical, 10)
                    .background(inputBackground)
                    .cornerRadius(20)
                    .oledGlassEdge(RoundedRectangle(cornerRadius: 20, style: .continuous))  // __OLED_GLASS_2026_11_16__
                    // The "grows when you type" cue: a brand rim fades in around the
                    // field once it owns the row.
                    .overlay(
                        RoundedRectangle(cornerRadius: 20, style: .continuous)
                            .stroke(LinearGradient(colors: [appBrandColor.opacity(0.75),
                                                            Color(red: 0.94, green: 0.28, blue: 0.56).opacity(0.75)],
                                                   startPoint: .leading, endPoint: .trailing),
                                    lineWidth: 1.25)
                            .opacity(showsCaptureButtons ? 0 : 1)
                    )
                    .lineLimit(ComposerChrome.lineLimit(expanded: !showsCaptureButtons))
                    .frame(minHeight: 44)
                    .layoutPriority(1)
                    .disabled(isRecordingAudio)
                    // 🔧 FIX (2026-08-09, "the newest message is hidden once the
                    // keyboard comes up"): `ChatBottomAnchored` applies
                    // `.defaultScrollAnchor(.bottom, for: .sizeChanges)`, which
                    // WOULD hold the bottom while the keyboard shrinks the
                    // scroll view — but that anchor disengages as soon as the
                    // user has scrolled by hand, which they almost always have
                    // by the time they tap the field. The list then keeps its
                    // old offset and the last messages sit behind the keyboard.
                    //
                    // Tapping the field is an explicit intent to write, so
                    // returning to the newest message is what was meant.
                    .focused($isComposerFocused)
                    .onChange(of: isComposerFocused) { _, focused in
                        if let handoffKey { ThreadHandoff.shared.setComposerFocused(handoffKey, focused) }
                        // Only on GAINING focus. Firing on dismissal too would
                        // yank a reader back to the bottom every time they close
                        // the keyboard to scroll up and re-read something.
                        guard focused else { return }
                        onComposerFocused?()
                    }
                    .onChange(of: text) { _, newValue in
                        // ⌨️ Notify typing status
                        let isTyping = !newValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        onTypingChanged?(isTyping)
                    }
                    // __FOCUS_COMPOSER_2026_08_13__ "Start chat" on a contact card
                    // that points at the conversation already on screen has nowhere
                    // to navigate. Rather than look like a dead button, it asks for
                    // the composer here — the answer to "start a chat" when you are
                    // already in it is "write your message".
                    .onReceive(NotificationCenter.default.publisher(
                        for: ThreadHandoff.restoreComposerFocus)) { note in
                        // Keyboard was up in the view this one replaced.
                        guard ThreadHandoff.isComposerFocusRequest(note, key: handoffKey, token: handoffToken) else { return }
                        isComposerFocused = true
                    }
                    .onReceive(NotificationCenter.default.publisher(
                        for: NSNotification.Name("FocusChatComposer"))) { _ in
                        isComposerFocused = true
                        withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                            showTypeHint = true
                        }
                        // Long enough to be read, short enough not to sit on the
                        // thread. Re-triggering restarts it because the flag is
                        // simply set true again before this fires.
                        DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
                            withAnimation(.easeOut(duration: 0.25)) { showTypeHint = false }
                        }
                    }
                
                // AI assistant button (only when text is present)
                if !isEmpty {
                    Button {
                        showAIShortcuts = true
                    } label: {
                        ZStack {
                            RoundedRectangle(cornerRadius: 8)
                                .fill(
                                    LinearGradient(
                                        colors: [appBrandColor, Color(red: 0.94, green: 0.28, blue: 0.56)],
                                        startPoint: .topLeading,
                                        endPoint: .bottomTrailing
                                    )
                                )
                                .frame(width: 32, height: 24)
                            Text("AI")
                                .font(.scaled(12, weight: .black, design: .rounded))
                                .foregroundColor(.white)
                        }
                    }
                    .transition(.scale.combined(with: .opacity))
                }

                if !isEmpty {
                    Button {
                        // Crisp send haptic — the signature "message sent" moment.
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        onSend()
                    } label: {
                        ZStack {
                            // The send button is the screen's hero CTA: an iridescent
                            // brand gradient (violet→pink, matching the AI accent) with
                            // a soft neon halo, so it reads as the app's "futuristic"
                            // signature while everything around it stays quiet.
                            Circle()
                                .fill(
                                    LinearGradient(
                                        colors: [appBrandColor, Color(red: 0.94, green: 0.28, blue: 0.56)],
                                        startPoint: .topLeading,
                                        endPoint: .bottomTrailing
                                    )
                                )
                                .frame(width: 44, height: 44)
                                .neonGlow(appBrandColor, radius: 11, opacity: 0.6)

                            Image(systemName: "arrow.up")
                                .font(.scaled(20, weight: .bold))
                                .foregroundColor(.white)
                        }
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .frame(width: 44, height: 44)
                    .accessibilityLabel(Text("general.send".localized))
                    .transition(.scale.combined(with: .opacity))
                } else {
                    audioRecordButton
                        .transition(.scale.combined(with: .opacity))
                }
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 8)
        }
        // __FROSTED_COMPOSER_2026_11_07__ (Anatole N10, checked on the platform
        // he did not test.) This was an OPAQUE `systemBackground`, so the
        // wallpaper stopped dead at the composer and the bar read as a slab
        // bolted to the bottom of the thread. Android has had frosted bars since
        // 02/11 (N12); iOS never got the composer half.
        //
        // `.ultraThinMaterial` lets the wallpaper run under the bar, softened.
        // The TEXT FIELD keeps its own opaque `inputBackground`, so the blur
        // stays strictly behind it — "sauf dans la case de texte", which is the
        // distinction he actually drew.
        // __OLED_BLACK_2026_11_16__ solid black under OLED black.
        .oledAwareBackground(.ultraThinMaterial)
        .animation(chromeAnimation, value: isEmpty)
        .animation(chromeAnimation, value: showsCaptureButtons)
        .animation(.spring(response: 0.3), value: isRecordingAudio)
        .sheet(isPresented: $showAIShortcuts) {
            AIShortcutsView(
                messageText: $text,
                isPresented: $showAIShortcuts,
                replyingToMessage: aiReplyContext
            )
            .presentationDetents([.medium, .large])
        }
    }
    
    private var audioRecordButton: some View {
        ZStack {
            // Expanding pulse ring while recording — radiates "live" energy.
            if isRecordingAudio {
                PulsingRing(color: .red)
                    .frame(width: 44, height: 44)
            }

            Circle()
                .fill(isRecordingAudio ? Color.red : appBrandColor)
                .frame(width: 44, height: 44)
                .scaleEffect(isPressing ? 1.1 : 1.0)
                .animation(.spring(response: 0.3, dampingFraction: 0.6), value: isPressing)

            Image(systemName: isRecordingAudio ? "stop.circle.fill" : "mic.fill")
                .font(.scaled(22))
                .foregroundColor(.white)
                .contentTransition(.opacity)
                .animation(.easeInOut(duration: 0.15), value: isRecordingAudio)
        }
        .frame(width: 44, height: 44)
        .contentShape(Circle())
        .allowsHitTesting(true)  // ✅ Explicitly enable hit testing for iPad
        .onTapGesture {
            if !isRecordingAudio {
                recordingMode = .tapToRecord
                startRecording()
            } else if recordingMode == .tapToRecord {
                stopAndPreviewRecording()
            }
        }
        .onLongPressGesture(minimumDuration: 0.3, maximumDistance: 50) {
            // On long press release
            if !isRecordingAudio {
                recordingMode = .holdToRecord
                startRecording()
            }
            isPressing = false
        } onPressingChanged: { pressing in
            // During long press
            if pressing && !isRecordingAudio {
                isPressing = true
            } else if !pressing && recordingMode == .holdToRecord && isRecordingAudio {
                stopAndPreviewRecording()
                isPressing = false
            }
        }
    }
    
    private func startRecording() {
        Task {
            do {
                try audioRecorder.startRecording()
                await MainActor.run {
                    isRecordingAudio = true
                    let generator = UIImpactFeedbackGenerator(style: .medium)
                    generator.impactOccurred()
                }
            } catch {
                OshiLog.messaging.info("❌ Failed to start recording: \(error)")
            }
        }
    }
    
    private func stopAndPreviewRecording() {
        Task {
            do {
                let attachment = try await audioRecorder.stopRecording()
                await MainActor.run {
                    isRecordingAudio = false
                    onAudioRecorded(attachment)
                    
                    let generator = UINotificationFeedbackGenerator()
                    generator.notificationOccurred(.success)
                }
            } catch {
                OshiLog.messaging.info("❌ Failed to stop recording: \(error)")
                await MainActor.run {
                    isRecordingAudio = false
                }
            }
        }
    }
    
    private func formatDuration(_ duration: TimeInterval?) -> String {
        guard let duration = duration else { return "0:00" }
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

// MARK: - Mini Waveform

/// A red dot that continuously breathes while shown — the recording indicator.
struct RecordingPulseDot: View {
    @State private var pulse = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        Circle()
            .fill(Color.red)
            .frame(width: 10, height: 10)
            .scaleEffect(pulse ? 1.35 : 0.9)
            .opacity(pulse ? 1.0 : 0.55)
            .onAppear {
                guard !reduceMotion else { return }
                withAnimation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true)) {
                    pulse = true
                }
            }
    }
}

/// An expanding, fading ring that radiates outward forever — a "live" pulse
/// behind record/active elements. Self-contained: animates on appear.
struct PulsingRing: View {
    var color: Color = .red
    @State private var animate = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        Circle()
            .stroke(color.opacity(0.55), lineWidth: 3)
            .scaleEffect(animate ? 1.65 : 1.0)
            .opacity(animate ? 0.0 : 0.7)
            .onAppear {
                guard !reduceMotion else { return }
                withAnimation(.easeOut(duration: 1.1).repeatForever(autoreverses: false)) {
                    animate = true
                }
            }
    }
}

struct MiniWaveformView: View {
    let levels: [Float]
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private let barCount = 20

    var body: some View {
        HStack(alignment: .center, spacing: 2) {
            ForEach(0..<barCount, id: \.self) { index in
                // Take the most-recent `barCount` levels so the wave scrolls
                // right-to-left as you speak (newest at the right edge).
                let srcIndex = levels.count - barCount + index
                let level = srcIndex >= 0 && srcIndex < levels.count ? CGFloat(levels[srcIndex]) : 0
                Capsule()
                    .fill(
                        LinearGradient(
                            colors: [Color.red, Color.red.opacity(0.65)],
                            startPoint: .top, endPoint: .bottom
                        )
                    )
                    .frame(width: 3, height: max(3, level * 26))
                    // Each bar springs to its new height for a fluid, alive feel.
                    .animation(reduceMotion ? nil : .spring(response: 0.22, dampingFraction: 0.6),
                               value: level)
            }
        }
        .frame(height: 26)
    }
}

// MARK: - Audio Preview Sheet — __VOICE_PREVIEW_2026_09_29__
//
// L'écran qui suit l'enregistrement d'un vocal: réécouter, changer de voix,
// choisir « vue unique », envoyer. Refondu le 29/09 (« make it more 2026 »):
// une carte en verre au lieu d'une page de navigation, la forme d'onde comme
// élément central, les voix en pastilles qui se font ENTENDRE dès qu'on les
// touche, et la vue unique en bouton « 1 » à côté de l'envoi — le geste de
// WhatsApp, que les gens connaissent déjà.
//
// Le moteur ne change pas: `applyVoiceEffect` rend hors ligne, le cache par
// voix évite de recalculer, et ce qui part est TOUJOURS la version traitée de
// la voix choisie (jamais l'original par erreur).

/// Verre « Liquid Glass » sur iOS 26, matériau translucide avant.
private struct VoiceGlass<S: Shape>: ViewModifier {
    let shape: S
    var tint: Color? = nil
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.glassEffect(tint.map { Glass.regular.tint($0.opacity(0.35)) } ?? .regular, in: shape)
        } else {
            content
                .background(.ultraThinMaterial, in: shape)
                .overlay(shape.stroke(Color.white.opacity(0.12), lineWidth: 1))
        }
    }
}

private extension View {
    func voiceGlass<S: Shape>(_ shape: S, tint: Color? = nil) -> some View {
        modifier(VoiceGlass(shape: shape, tint: tint))
    }
}

private extension AudioRecorderManager.VoiceEffect {
    /// Noms traduits quand la clé existe (5 des 7 dans les 34 locales).
    var displayName: String {
        "audio.effect.\(rawValue.lowercased())".localizedOrFallback(rawValue)
    }
    /// Une couleur par voix: on reconnaît la voix choisie d'un coup d'œil.
    var accent: Color {
        switch self {
        case .normal: return appBrandColor
        case .robot: return .cyan
        case .alien: return .green
        case .deep: return .indigo
        case .chipmunk: return .orange
        case .echo: return .pink
        case .reverb: return .purple
        }
    }
}

/// Forme d'onde de l'aperçu: barres arrondies, partie écoutée pleine, le reste
/// en transparence; « respire » pendant qu'une voix se calcule.
private struct PreviewWaveform: View {
    let levels: [Float]
    let progress: Double
    let tint: Color
    let isProcessing: Bool
    @State private var shimmer = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { geo in
            let n = max(levels.count, 1)
            let spacing: CGFloat = 3
            let barW = max(2, (geo.size.width - CGFloat(n - 1) * spacing) / CGFloat(n))
            HStack(alignment: .center, spacing: spacing) {
                ForEach(Array(levels.enumerated()), id: \.offset) { i, level in
                    let played = Double(i) / Double(n) < progress
                    Capsule()
                        .fill(played
                              ? AnyShapeStyle(LinearGradient(colors: [tint, tint.opacity(0.7)],
                                                             startPoint: .top, endPoint: .bottom))
                              : AnyShapeStyle(Color.primary.opacity(0.22)))
                        .frame(width: barW,
                               height: max(4, CGFloat(level) * geo.size.height * (isProcessing && shimmer ? 0.45 : 0.95)))
                        .animation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.6)
                                    .delay(Double(i) * 0.006), value: shimmer)
                        .animation(.linear(duration: 0.1), value: played)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .onChange(of: isProcessing) { _, busy in
            guard !reduceMotion else { return }
            if busy {
                withAnimation(.easeInOut(duration: 0.6).repeatForever(autoreverses: true)) { shimmer = true }
            } else {
                withAnimation(.spring) { shimmer = false }
            }
        }
    }
}

struct AudioPreviewSheet: View {
    /// __OLED_BLACK_2026_11_16__ a material over black is grey; OLED gets black.
    @Environment(\.oledBlackActive) private var oledBlack
    let audioAttachment: AudioRecorderManager.AudioAttachment
    let onSend: (AudioRecorderManager.AudioAttachment) -> Void
    let onCancel: () -> Void
    /// Présent ⇒ le bouton « 1 » (vue unique) est proposé et l'envoi passe par
    /// ici quand il est allumé. Absent (groupes) ⇒ pas d'option.
    var onSendViewOnce: ((AudioRecorderManager.AudioAttachment) -> Void)? = nil

    @State private var selectedEffect: AudioRecorderManager.VoiceEffect = .normal
    @State private var isProcessing = false
    @State private var processedAttachment: AudioRecorderManager.AudioAttachment?
    @State private var effectCache: [AudioRecorderManager.VoiceEffect: AudioRecorderManager.AudioAttachment] = [:]
    @State private var processingTask: Task<Void, Never>?
    @State private var viewOnce = false
    @State private var sendTick = 0
    @StateObject private var audioRecorder = AudioRecorderManager()
    @StateObject private var audioPlayer = AudioPlayerManager()

    private var currentAttachment: AudioRecorderManager.AudioAttachment {
        processedAttachment ?? audioAttachment
    }

    private var progress: Double {
        guard audioPlayer.isPlaying, currentAttachment.duration > 0 else { return 0 }
        return min(1, audioPlayer.currentTime / currentAttachment.duration)
    }

    private var timeLabel: String {
        let t = audioPlayer.isPlaying ? audioPlayer.currentTime : currentAttachment.duration
        return String(format: "%d:%02d", Int(t) / 60, Int(t) % 60)
    }

    // MARK: Body

    var body: some View {
        VStack(spacing: 18) {
            header
            playerCard
            effectStrip
            if viewOnce {
                viewOnceHint
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
            Spacer(minLength: 0)
            actionBar
        }
        .padding(.horizontal, 20)
        .padding(.top, 14)
        .padding(.bottom, 10)
        .animation(.spring(response: 0.4, dampingFraction: 0.82), value: viewOnce)
        .animation(.spring(response: 0.4, dampingFraction: 0.82), value: isProcessing)
        .sensoryFeedback(.selection, trigger: selectedEffect)
        .sensoryFeedback(.impact(weight: .medium), trigger: viewOnce)
        .sensoryFeedback(.success, trigger: sendTick)
        .presentationDetents([.height(viewOnce ? 520 : 470), .large])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(34)
        .presentationBackground(oledBlack ? AnyShapeStyle(Color.black) : AnyShapeStyle(.regularMaterial))
        .onDisappear {
            audioPlayer.stop()
            processingTask?.cancel()
        }
    }

    // MARK: Sections

    private var header: some View {
        HStack(spacing: 12) {
            Image(systemName: "waveform")
                .font(.scaled(15, weight: .semibold))
                .foregroundStyle(selectedEffect.accent)
                .frame(width: 34, height: 34)
                .voiceGlass(Circle(), tint: selectedEffect.accent)
                .symbolEffect(.variableColor.iterative, isActive: audioPlayer.isPlaying)

            VStack(alignment: .leading, spacing: 1) {
                Text("audio.voice_message".localized)
                    .font(.headline)
                Text("\(currentAttachment.durationFormatted) · \(currentAttachment.fileSizeFormatted)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }

            Spacer()

            Button {
                audioPlayer.toggleSpeaker()
            } label: {
                Image(systemName: audioPlayer.useSpeaker ? "speaker.wave.2.fill" : "ear")
                    .font(.scaled(15, weight: .semibold))
                    .contentTransition(.symbolEffect(.replace))
                    .frame(width: 38, height: 38)
                    .voiceGlass(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(audioPlayer.useSpeaker ? "audio.speaker".localized : "audio.earpiece".localized)
        }
    }

    private var playerCard: some View {
        HStack(spacing: 14) {
            Button(action: playTapped) {
                ZStack {
                    Circle()
                        .fill(LinearGradient(colors: [selectedEffect.accent, selectedEffect.accent.opacity(0.7)],
                                             startPoint: .topLeading, endPoint: .bottomTrailing))
                        .shadow(color: selectedEffect.accent.opacity(0.45), radius: 12, y: 4)
                    if isProcessing {
                        ProgressView().tint(.white)
                    } else {
                        Image(systemName: audioPlayer.isPlaying ? "pause.fill" : "play.fill")
                            .font(.scaled(22, weight: .bold))
                            .foregroundStyle(.white)
                            .contentTransition(.symbolEffect(.replace))
                            .offset(x: audioPlayer.isPlaying ? 0 : 2)
                    }
                }
                .frame(width: 58, height: 58)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(audioPlayer.isPlaying ? "audio.stop".localized : "audio.play".localized)

            VStack(alignment: .leading, spacing: 6) {
                PreviewWaveform(levels: currentAttachment.waveformData ?? Array(repeating: 0.3, count: 40),
                                progress: progress,
                                tint: selectedEffect.accent,
                                isProcessing: isProcessing)
                    .frame(height: 44)

                HStack {
                    if isProcessing {
                        Text(String(format: "audio.processing_effect".localized, selectedEffect.displayName))
                            .foregroundStyle(selectedEffect.accent)
                        Spacer()
                        Button("common.cancel".localized, action: cancelProcessing)
                            .font(.caption.weight(.semibold))
                    } else {
                        Text(selectedEffect == .normal
                             ? "audio.effect.normal.desc".localizedOrFallback("Original voice")
                             : selectedEffect.displayName)
                        Spacer()
                        Text(timeLabel).monospacedDigit()
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
        }
        .padding(14)
        .voiceGlass(RoundedRectangle(cornerRadius: 28, style: .continuous))
    }

    private var effectStrip: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("audio.voice_effect".localized)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.leading, 4)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(AudioRecorderManager.VoiceEffect.allCases, id: \.self) { effect in
                        effectChip(effect)
                    }
                }
                .padding(.horizontal, 2)
                .padding(.vertical, 4)
            }
            .scrollClipDisabled()
        }
    }

    private func effectChip(_ effect: AudioRecorderManager.VoiceEffect) -> some View {
        let selected = effect == selectedEffect
        return Button { select(effect) } label: {
            HStack(spacing: 6) {
                Image(systemName: effect.iconName)
                    .font(.scaled(13, weight: .semibold))
                    .symbolEffect(.bounce, value: selected)
                Text(effect.displayName)
                    .font(.subheadline.weight(selected ? .semibold : .regular))
                    .lineLimit(1)
                if selected && isProcessing {
                    ProgressView().controlSize(.mini).tint(.white)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .foregroundStyle(selected ? Color.white : Color.primary)
            .background {
                if selected {
                    Capsule().fill(effect.accent.gradient)
                        .shadow(color: effect.accent.opacity(0.4), radius: 8, y: 3)
                }
            }
            .voiceGlass(Capsule())
            .scaleEffect(selected ? 1.04 : 1)
            .animation(.spring(response: 0.3, dampingFraction: 0.7), value: selected)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var viewOnceHint: some View {
        HStack(spacing: 10) {
            Image(systemName: "1.circle.fill")
                .font(.scaled(20))
                .foregroundStyle(.orange)
            VStack(alignment: .leading, spacing: 2) {
                Text("media_picker.view_once".localized)
                    .font(.subheadline.weight(.semibold))
                Text("audio.view_once.hint".localizedOrFallback("Can be played only once, then it's gone"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(12)
        .voiceGlass(RoundedRectangle(cornerRadius: 18, style: .continuous), tint: .orange)
    }

    private var actionBar: some View {
        HStack(spacing: 14) {
            Button {
                audioPlayer.stop()
                processingTask?.cancel()
                onCancel()
            } label: {
                Image(systemName: "trash")
                    .font(.scaled(18, weight: .semibold))
                    .foregroundStyle(.red)
                    .frame(width: 54, height: 54)
                    .voiceGlass(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("general.cancel".localized)

            Spacer()

            if onSendViewOnce != nil {
                Button {
                    viewOnce.toggle()
                } label: {
                    Image(systemName: viewOnce ? "1.circle.fill" : "1.circle")
                        .font(.scaled(24, weight: .semibold))
                        .foregroundStyle(viewOnce ? Color.orange : Color.primary)
                        .contentTransition(.symbolEffect(.replace))
                        .frame(width: 54, height: 54)
                        .voiceGlass(Circle(), tint: viewOnce ? .orange : nil)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("media_picker.view_once".localized)
                .accessibilityValue(viewOnce ? "media_picker.view_once.enabled".localized : "")
            }

            Button {
                sendTick += 1
                sendWithEffect()
            } label: {
                HStack(spacing: 8) {
                    if isProcessing {
                        ProgressView().tint(.white)
                    } else {
                        Image(systemName: "arrow.up")
                            .font(.scaled(18, weight: .bold))
                    }
                    Text("general.send".localized)
                        .font(.headline)
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 24)
                .frame(height: 54)
                .background(Capsule().fill(LinearGradient(colors: [appBrandColor, appBrandColor.opacity(0.75)],
                                                          startPoint: .leading, endPoint: .trailing)))
                .shadow(color: appBrandColor.opacity(0.4), radius: 12, y: 5)
            }
            .buttonStyle(.plain)
            .disabled(isProcessing)
            .opacity(isProcessing ? 0.6 : 1)
        }
    }

    // MARK: Actions

    private func playTapped() {
        if isProcessing { cancelProcessing(); return }
        if audioPlayer.isPlaying { audioPlayer.stop(); return }
        audioPlayer.play(currentAttachment.data)
    }

    private func cancelProcessing() {
        processingTask?.cancel()
        processingTask = nil
        isProcessing = false
        selectedEffect = .normal
        processedAttachment = nil
    }

    /// Choisir une voix la fait ENTENDRE: tout de suite si elle est en cache,
    /// dès qu'elle est calculée sinon (tant qu'elle est toujours la voix choisie).
    private func select(_ effect: AudioRecorderManager.VoiceEffect) {
        audioPlayer.stop()
        processingTask?.cancel()
        processingTask = nil
        selectedEffect = effect

        if effect == .normal {
            processedAttachment = nil
            isProcessing = false
            audioPlayer.play(audioAttachment.data)
            return
        }
        if let cached = effectCache[effect] {
            processedAttachment = cached
            isProcessing = false
            audioPlayer.play(cached.data)
            return
        }

        processedAttachment = nil
        isProcessing = true
        let source = audioAttachment
        processingTask = Task { @MainActor in
            do {
                let processed = try await audioRecorder.applyVoiceEffect(effect, to: source)
                try Task.checkCancellation()
                effectCache[effect] = processed
                guard selectedEffect == effect else { return }
                processedAttachment = processed
                isProcessing = false
                processingTask = nil
                audioPlayer.play(processed.data)
            } catch is CancellationError {
                OshiLog.messaging.info("⏹️ Effect processing cancelled: \(effect.rawValue)")
            } catch {
                OshiLog.messaging.info("❌ Failed to pre-process effect: \(error)")
                if selectedEffect == effect {
                    isProcessing = false
                    processingTask = nil
                }
            }
        }
    }

    private func deliver(_ attachment: AudioRecorderManager.AudioAttachment) {
        if viewOnce, let onSendViewOnce {
            onSendViewOnce(attachment)
        } else {
            onSend(attachment)
        }
    }

    /// Ce qui part est la version de la voix CHOISIE: en cache, sinon calculée
    /// maintenant. Seul un échec de calcul retombe sur l'original.
    private func sendWithEffect() {
        audioPlayer.stop()
        OshiLog.messaging.info("📤 Voice preview send — effect \(selectedEffect.rawValue), viewOnce \(viewOnce)")

        if selectedEffect == .normal {
            deliver(audioAttachment)
            return
        }
        if let processed = effectCache[selectedEffect] ?? processedAttachment {
            deliver(processed)
            return
        }

        isProcessing = true
        let effect = selectedEffect
        Task { @MainActor in
            do {
                let processed = try await audioRecorder.applyVoiceEffect(effect, to: audioAttachment)
                effectCache[effect] = processed
                isProcessing = false
                deliver(processed)
            } catch {
                OshiLog.messaging.info("❌ Effect processing failed at send (\(error)) — sending original")
                isProcessing = false
                deliver(audioAttachment)
            }
        }
    }
}

// MARK: - Message Bubble (abbreviated - use your existing one)

struct MessageBubble: View {
    /// __FOLDABLE_LAYOUT_2026_11_16__ The thread's width (see `maxBubbleWidth(in:)`).
    @Environment(\.chatColumnWidth) private var chatColumnWidth
    /// User-chosen bubble accent, shape and text weight. Observed so changing it
    /// in Settings restyles open conversations immediately.
    @ObservedObject private var bubbleStyle = BubbleStyleManager.shared
    /// __PEER_VERSION_EVIDENCE_2026_10_09__ Observed, not read once: the
    /// evidence that a peer is on an old build arrives when a message FAILS to
    /// decrypt, which can be after this bubble is already on screen.
    @ObservedObject private var peerVersions = PeerVersionStore.shared
    /// __LORA_AIRTIME_HINT_2026_11_18__ Its own tiny observable (changes only when a radio holds a
    /// message past a minute), not the radio manager, so bubbles do not redraw on every radio event.
    @ObservedObject private var loRaAirtime = LoRaAirtimeStatus.shared

    let message: SecureMessage
    let currentUserPublicKey: String
    let identityManager: IdentityManager
    let meshManager: MeshNetworkManager
    var contactName: String? = nil
    var contactPublicKey: String? = nil
    /// Changes whenever delivery status / read state changes. `SecureMessage`'s
    /// `==` compares only `id`, so with a STABLE row `.id(message.id)` SwiftUI
    /// would otherwise consider a status change "no change" and skip body — the
    /// old "frozen delivery clock" bug. This explicit, changing input forces a
    /// body re-render on status/read change WITHOUT changing the row identity,
    /// so the checkmarks update live AND new messages can animate in.
    var statusVersion: String = ""
    /// __BUBBLE_RUN_2026_08_18__ Whether this is the LAST message of a run from
    /// the same sender. Decided by the list (ChatView's ForEach), which is the
    /// only place that can see the next message.
    ///
    /// Drives two things: the tail corner, and whether the footer
    /// (timestamp / transport / ticks) is drawn. Defaults to `true` so every
    /// other call site and every preview keeps the standalone-bubble look
    /// without having to be updated.
    var isLastInRun: Bool = true
    /// __TRANSLATE_HOIST_2026_09_05__ When set, "Translate" hands the text to
    /// the OWNING screen instead of presenting from this row. Reported: the
    /// button did nothing in 1:1 while the identical button worked in groups.
    /// The group menu's result alert lives on the group SCREEN; this row's alert
    /// and Translation sheet were chained on a LazyVStack row, right behind the
    /// full-screen cover that had just been dismissed — a presentation SwiftUI
    /// drops without a trace. `nil` keeps the in-row path for other call sites.
    var onTranslateRequest: ((String) -> Void)? = nil
    /// 📊 Un bulletin à émettre. La bulle ne sait pas À QUI envoyer — c'est la
    /// vue de conversation qui le sait — donc elle remonte le choix.
    var onPollVote: ((PollMessage, [Int]) -> Void)? = nil

    @EnvironmentObject var messageManager: MessageManager
    @State private var decryptedText: String?
    @State private var isDecrypting = true

    /// Why this bubble cannot be read, when we know why.
    ///
    /// A bare "Failed to decrypt" reads as "OSHI is broken". When the SENDER
    /// has been proven to be on a pre-0cfc8c9 build — see
    /// `DoubleRatchetSession.looksLikeLegacyResponderChain` — the cause is
    /// known, it is not this device, and there is something the user can do.
    /// Keyed on `message.senderPublicKey` rather than the conversation: it is
    /// that sender's build that decides, and it is what this view has.
    ///
    /// A separate property and not a ternary in `body` on purpose: this body
    /// already sits at the Swift type-checker's time limit.
    private var failedDecryptText: String {
        peerVersions.peerIsOutdated(message.senderPublicKey)
            ? LocalizedOrEnglish.text("chat.failed_decrypt.peer_outdated")
            : "chat.failed_decrypt".localized
    }
    @Environment(\.colorScheme) var colorScheme
    
    // Link preview support
    @StateObject private var linkPreviewFetcher = LinkPreviewFetcher()
    @State private var detectedURL: String?
    
    // 🔥 Reaction support
    @State private var showReactionPicker = false
    @State private var showTranslationResult = false
    @State private var translatedMessageText: String = ""
    @State private var showTranslationSheet = false
    @State private var translationSourceText: String = ""
    @State private var messageFrame: CGRect = .zero
    /// __BUBBLE_TIME_2026_08_17__ Décalage courant du geste de révélation.
    @State private var timeRevealOffset: CGFloat = 0
    @ObservedObject private var reactionManager = MessageReactionManager.shared

    // 🗑️ Delete / ✏️ Edit support
    @State private var showDeleteSheet = false
    @State private var showEditSheet = false
    @State private var editingText = ""

    // ➡️ Forward support — opens a contact picker sheet when user taps "Forward" in the reaction overlay.
    @State private var showForwardPicker = false

    var isFromCurrentUser: Bool {
        message.senderPublicKey == currentUserPublicKey
    }
    
    var deliveryMethod: String {
        switch message.deliveryStatus {
        // `.read` shows the same transport line as `.delivered`: how it
        // travelled does not change once someone opens it.
        case .sent, .delivered, .read:
            if let storedMethod = message.deliveryMethod {
                switch storedMethod {
                case .mesh:
                    return "📱 " + "chat.delivery_direct".localized
                case .lora:
                    return "📡 " + "delivery_received_lora".localized
                // __LORA_PLAINTEXT_IS_NOT_THE_SAME_AS_LORA_2026_11_04__ The
                // warning triangle rather than the antenna: this one arrived by
                // radio too, but unauthenticated, and the status line is read
                // by people deciding whether to trust what they are looking at.
                case .loraPlaintext:
                    return "⚠️ LoRa · " + "delivery_lora_unverified".localized
                case .ipfs:
                    return "☁️ " + "chat.delivery_cloud".localized
                case .pending:
                    return "⏳ " + "messages.sending".localized
                }
            } else {
                return "☁️ " + "chat.delivery_cloud".localized
            }
        case .pending:
            return "⏳ " + "messages.sending".localized
        case .failed:
            if messageManager.isWaitingForCover(message.id, status: .failed) {
                return "🕓 " + "cover.waiting_bubble".localized
            }
            return "❌ " + "messages.failed".localized
        }
    }
    
    private var bubbleBackground: Color {
        if isFromCurrentUser {
            return appBrandColor
        } else {
            return colorScheme == .dark ? Color(.systemGray5) : Color(.systemGray6)
        }
    }

    /// Bubble fill. Outgoing bubbles get a gentle diagonal violet→indigo gradient
    /// for a subtle premium depth (kept quiet so it doesn't compete with the hero
    /// send button); incoming bubbles stay a flat neutral surface for readability.
    /// Now driven by `BubbleStyleManager` so the accent, the corner radius and
    /// the text weight are the user's choice and are identical in 1:1 chats,
    /// group chats and media bubbles — they used to be hard-coded separately in
    /// all three, which is how they drifted apart.
    private var bubbleFillStyle: AnyShapeStyle {
        isFromCurrentUser ? bubbleStyle.outgoingFill
                          : bubbleStyle.incomingFill(colorScheme)
    }

    /// Corner radius for every bubble surface in this row.
    private var bubbleRadius: CGFloat { bubbleStyle.cornerRadius }

    /// __BUBBLE_SHAPE_2026_08_18__ The silhouette every surface in this row is
    /// painted and clipped to. Continuous corners, tail only on the last bubble
    /// of a run. Computed once here so the six bubble variants below cannot
    /// drift apart again — which is exactly how the link-preview branch ended up
    /// frozen at radius 15 while the rest of the thread followed the user.
    private var bubbleOutline: BubbleOutline {
        bubbleStyle.outline(outgoing: isFromCurrentUser, tail: isLastInRun)
    }

    /// Hairline edge. See `BubbleStyleManager.rim` — this is what keeps a pale
    /// received bubble legible over an arbitrary `.customImage` wallpaper.
    private var bubbleRim: Color {
        bubbleStyle.rim(outgoing: isFromCurrentUser, colorScheme)
    }

    /// Link ink for this bubble's side and scheme. Was
    /// `isFromCurrentUser ? .white : appBrandColor` inline in three places —
    /// the incoming half of which was unreadable in dark mode.
    private var bubbleLinkColor: Color {
        bubbleStyle.linkColour(outgoing: isFromCurrentUser, colorScheme)
    }

    /// Text ink. Tokens rather than the raw `.white` / `.black` literals this
    /// used to return: `onAccent` is derived from the chosen accent, and
    /// `Color(.label)` follows the system's increased-contrast and inverted
    /// settings, which a hardcoded black does not.
    private var bubbleForeground: Color {
        isFromCurrentUser ? bubbleStyle.accent.onAccent : Color(.label)
    }

    /// WhatsApp-style read receipt checkmarks
    @ViewBuilder
    private var deliveryStatusIcon: some View {
        // __COVER_WAITING_BUBBLE_2026_11_18__ Cover mode is holding this message for
        // a disguised retry: a clock and the reason, never the red "failed".
        if messageManager.isWaitingForCover(message.id, status: message.deliveryStatus) {
            HStack(spacing: 3) {
                Image(systemName: "clock")
                    .font(.scaled(10))
                Text("cover.waiting_bubble".localized)
                    .font(.caption2)
            }
            .foregroundColor(ChatPalette.metaInk)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("cover.waiting_bubble")
        } else {
        switch message.deliveryStatus {
        case .pending:
            // Single gray clock - sending
            Image(systemName: "clock")
                .font(.scaled(10))
                .foregroundColor(ChatPalette.metaInk)
        case .sent:
            // Single checkmark - sent to server
            Image(systemName: "checkmark")
                .font(.scaled(10, weight: .semibold))
                .foregroundColor(ChatPalette.metaInk)
        case .delivered:
            // Two grey checks — it reached their device. Nobody has opened it.
            HStack(spacing: -3) {
                Image(systemName: "checkmark")
                    .font(.scaled(10, weight: .semibold))
                Image(systemName: "checkmark")
                    .font(.scaled(10, weight: .semibold))
            }
            .foregroundColor(ChatPalette.metaInk)
            .accessibilityLabel("status.delivered".localized)

        case .read:
            // 🔧 (2026-08-08) Two GREEN checks — a person opened it.
            //
            // This used to be the same grey double tick re-tinted from an
            // `isRead` boolean, so "their phone has it" and "they read it" wore
            // one symbol. Green is the distinction people already read as
            // "seen", and it is a different HUE rather than a shade, so it
            // survives a glance and a colour-blind viewer has the label.
            HStack(spacing: -3) {
                Image(systemName: "checkmark")
                    .font(.scaled(10, weight: .bold))
                Image(systemName: "checkmark")
                    .font(.scaled(10, weight: .bold))
            }
            // __CHAT_POLISH_2026_09_24__ A green that clears 4.5:1 on the
            // light wallpaper ground (system green was 2.2:1).
            .foregroundColor(ChatPalette.readTick)
            .accessibilityLabel("status.read".localized)
        case .failed:
            // Red X - failed with retry option
            Button(action: { retryMessage() }) {
                HStack(spacing: 2) {
                    Image(systemName: "exclamationmark.circle.fill")
                        .font(.scaled(10))
                    Text("message.tap_to_retry".localized)
                        .font(.caption2)
                }
                .foregroundColor(.red)
            }
        }
        }
    }

    // 📱 Retry sending a failed message
    private func retryMessage() {
        OshiLog.messaging.info("📱 Retrying failed message: \(message.id)")

        let contentToSend = decryptedText ?? message.plaintextContent ?? ""
        let hasMedia = message.mediaAttachment != nil || message.originalMediaData != nil

        // Skip special message types (call signals, profile updates)
        if contentToSend.hasPrefix("📞CALL_SIGNAL") || contentToSend.hasPrefix("📸PROFILE") {
            OshiLog.messaging.info("⚠️ Cannot retry special message type")
            return
        }

        // 🔧 FIX: Allow retry for media messages (contentToSend is empty for media)
        guard hasMedia || !contentToSend.isEmpty else {
            OshiLog.messaging.info("⚠️ Nothing to retry (no content and no media)")
            return
        }

        // Capture media data BEFORE deleting the failed message
        let mediaData = message.mediaAttachment ?? message.originalMediaData
        let mediaType = message.mediaType
        let mediaFileName = message.mediaFileName
        let recipientKey = message.recipientPublicKey
        let isViewOnce = message.isViewOnce
        let failedMessageId = message.id

        // Delete the failed message first, then resend
        messageManager.deleteMessage(failedMessageId)

        if let media = mediaData {
            // Retry media message
            messageManager.sendMessage(
                content: "",
                recipientAddress: recipientKey,
                identityManager: identityManager,
                meshManager: meshManager,
                mediaAttachment: media,
                mediaType: mediaType,
                mediaFileName: mediaFileName,
                isViewOnce: isViewOnce
            )
        } else {
            // Retry text message. A "lora!<nodehex>" recipient is routed to
            // the plaintext interop lane inside `sendMessage` itself — same
            // choke point as a first send, see the comment in `sendMessage()`
            // above.
            messageManager.sendMessage(
                content: contentToSend,
                recipientAddress: recipientKey,
                identityManager: identityManager,
                meshManager: meshManager
            )
        }

        // Haptic feedback
        let generator = UINotificationFeedbackGenerator()
        generator.notificationOccurred(.success)
    }

    var body: some View {
        // Handle deleted-for-everyone messages
        if message.isDeletedForEveryone {
            return AnyView(
                HStack {
                    if isFromCurrentUser { Spacer() }
                    HStack(spacing: 6) {
                        Image(systemName: "nosign")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Text("message.deleted_for_everyone".localized)
                            .font(.subheadline)
                            .italic()
                            .foregroundColor(.secondary)
                    }
                    .padding(12)
                    // Same silhouette as a live bubble so the thread's rhythm
                    // survives a deletion — but deliberately NO rim and NO
                    // elevation: a tombstone should sit further back than the
                    // messages around it, not level with them.
                    .background(Color(.systemGray6).opacity(0.6), in: bubbleOutline)
                    if !isFromCurrentUser { Spacer() }
                }
                .padding(.horizontal, 4)
            )
        }

        let hasMediaAttachment = message.mediaAttachment != nil || message.mediaType != nil
        let hasMediaMarker = (decryptedText?.hasPrefix("MEDIA|||") ?? false) || (message.plaintextContent?.hasPrefix("MEDIA|||") ?? false)
        let hasMedia = hasMediaAttachment || hasMediaMarker
        
        // Check for scheduled call message
        let isScheduledCall = (decryptedText?.hasPrefix("📅SCHEDULED_CALL📅") ?? false) || (message.plaintextContent?.hasPrefix("📅SCHEDULED_CALL📅") ?? false)
        
        // Check for call summary/missed call message
        let isCallSummary = (decryptedText?.hasPrefix("📱CALL_SUMMARY📱") ?? false) || (message.plaintextContent?.hasPrefix("📱CALL_SUMMARY📱") ?? false)
        let isMissedCall = (decryptedText?.hasPrefix("📱MISSED_CALL📱") ?? false) || (message.plaintextContent?.hasPrefix("📱MISSED_CALL📱") ?? false)
        
        // Check for location message
        let isLocationMessage = (decryptedText?.hasPrefix("📍LOCATION📍") ?? false) || (message.plaintextContent?.hasPrefix("📍LOCATION📍") ?? false)

        // Check for check-in (accompagnement) message
        let isCheckInMessage = (decryptedText?.hasPrefix("🛡️CHECK_IN🛡️") ?? false) || (message.plaintextContent?.hasPrefix("🛡️CHECK_IN🛡️") ?? false)

        // 📊 Sondage. Passe par `PollMessage.isPollMessage`, donc par le
        // catalogue: l'orthographe sans U+FE0F et toute variante future sont
        // reconnues au même endroit que partout ailleurs.
        let isPollMessage = PollMessage.isPollMessage(decryptedText ?? message.plaintextContent ?? "")

        // 🔧 NEW: Check for system info messages (profile update, wallpaper update)
        // Only show bubble for ACTUAL CHANGES, not requests
        let textToCheck = decryptedText ?? message.plaintextContent ?? ""
        let isProfileUpdate = textToCheck.hasPrefix("📸PROFILE_UPDATE📸")
        let isWallpaperUpdate = textToCheck.hasPrefix("🎨WALLPAPER_UPDATE🎨")
        let isLinksVisibility = textToCheck.hasPrefix("🔗LINKS_VISIBILITY🔗")
        // Don't show profile requests - they're silent
        let isProfileRequest = textToCheck.hasPrefix("📸PROFILE_REQUEST📸")
        let isActionMessage = textToCheck.hasPrefix("\u{1F527}ACTION\u{1F527}")
        // __BLOCKED_NOTICE_2026_09_18__ Une capsule centrée, pas une bulle: ce
        // n'est pas quelque chose que l'autre a dit, c'est le fil qui annonce ce
        // qu'il advient désormais de tout ce qu'on y écrit. Android le dessine de
        // la même façon (`ChatScreen.SystemMessageType.BLOCKED_NOTICE`).
        let isBlockedNotice = textToCheck.hasPrefix(OSHIControlPayload.blockedNoticePrefix)
        let isSystemInfoMessage = isProfileUpdate || isWallpaperUpdate || isBlockedNotice
        
        // Check upload progress
        let uploadProgress = messageManager.uploadProgress[message.id]
        let uploadStatus = messageManager.uploadStatus[message.id]
        let isUploading = uploadProgress != nil && uploadProgress! < 1.0
        
        // 🔧 Hide profile requests and links visibility updates completely - they should be silent
        if isProfileRequest || isLinksVisibility || isActionMessage {
            // Process links visibility silently
            if isLinksVisibility {
                ProfileLinksManager.shared.processIncomingVisibility(
                    from: message.senderPublicKey,
                    content: textToCheck
                )
            }
            return AnyView(EmptyView())
        }
        
        // 🔧 Display profile/wallpaper updates as centered WhatsApp-style bubbles
        if isSystemInfoMessage {
            return AnyView(
                SystemInfoBubble(
                    isProfileUpdate: isProfileUpdate,
                    isWallpaperUpdate: isWallpaperUpdate,
                    isBlockedNotice: isBlockedNotice,
                    isFromCurrentUser: isFromCurrentUser,
                    timestamp: message.timestamp
                )
            )
        }
        
        return AnyView(HStack(alignment: .top, spacing: 8) {
            if isFromCurrentUser {
                Spacer()
            }
            
            VStack(alignment: isFromCurrentUser ? .trailing : .leading, spacing: 5) {
                if isScheduledCall {
                    // Display scheduled call as interactive card
                    ScheduledCallBubble(
                        message: message,
                        decryptedText: decryptedText ?? message.plaintextContent ?? "",
                        isFromCurrentUser: isFromCurrentUser
                    )
                } else if isCallSummary || isMissedCall {
                    // Display call summary as styled bubble
                    CallSummaryBubble(
                        content: (decryptedText ?? message.plaintextContent ?? "").replacingOccurrences(of: "📱CALL_SUMMARY📱", with: "").replacingOccurrences(of: "📱MISSED_CALL📱", with: ""),
                        isMissed: isMissedCall,
                        timestamp: message.timestamp
                    )
                } else if isLocationMessage {
                    // Display location as WhatsApp-style map bubble
                    if let locationMsg = LocationMessage.fromMessageString(decryptedText ?? message.plaintextContent ?? "") {
                        LocationMessageBubble(
                            locationMessage: locationMsg,
                            isFromCurrentUser: isFromCurrentUser,
                            contactName: contactName,
                            contactPublicKey: contactPublicKey
                        )
                    } else {
                        // Fallback if parsing fails
                        Text("📍 " + "location.shared_location".localized)
                            .padding(12)
                            .foregroundColor(bubbleForeground)
                            .bubbleSurface(silhouetteOverride: bubbleStyle.silhouette,
                                           fill: bubbleFillStyle,
                                           outline: bubbleOutline,
                                           rim: bubbleRim)
                    }
                } else if isPollMessage {
                    if let poll = PollMessage.fromMessageString(decryptedText ?? message.plaintextContent ?? "") {
                        PollMessageBubble(
                            poll: poll,
                            myPublicKey: currentUserPublicKey,
                            onVote: { picks in onPollVote?(poll, picks) },
                            // Dans un 1:1 il n'y a que deux votants possibles.
                            displayName: { key in
                                MessageManager.canonicalIdentity(key) == MessageManager.canonicalIdentity(currentUserPublicKey)
                                    ? "you".localized
                                    : (contactName ?? "poll.voter".localized)
                            }
                        )
                        .foregroundColor(bubbleForeground)
                        .bubbleSurface(silhouetteOverride: bubbleStyle.silhouette,
                                       fill: bubbleFillStyle,
                                       outline: bubbleOutline,
                                       rim: bubbleRim)
                    } else {
                        Text("📊 " + "poll.title".localized)
                            .padding(12)
                            .foregroundColor(bubbleForeground)
                            .bubbleSurface(silhouetteOverride: bubbleStyle.silhouette,
                                           fill: bubbleFillStyle,
                                           outline: bubbleOutline,
                                           rim: bubbleRim)
                    }
                } else if isCheckInMessage {
                    // Display check-in (accompagnement) as styled bubble
                    if let checkInMsg = CheckInMessage.fromMessageString(decryptedText ?? message.plaintextContent ?? "") {
                        CheckInMessageBubble(
                            message: checkInMsg,
                            isFromCurrentUser: isFromCurrentUser
                        )
                    } else {
                        // Fallback if parsing fails
                        Text("🛡️ " + "checkin.message.started".localized)
                            .padding(12)
                            .foregroundColor(bubbleForeground)
                            .bubbleSurface(silhouetteOverride: bubbleStyle.silhouette,
                                           fill: bubbleFillStyle,
                                           outline: bubbleOutline,
                                           rim: bubbleRim)
                    }
                } else if hasMedia {
                    ZStack {
                        MediaChatBubble(
                            message: message,
                            isFromCurrentUser: isFromCurrentUser
                        )
                        .environmentObject(identityManager)
                        .environmentObject(messageManager)
                        
                        // Upload progress overlay
                        if isUploading, let progress = uploadProgress {
                            UploadProgressOverlay(progress: progress, status: uploadStatus)
                        }
                    }
                } else if isDecrypting {
                    HStack {
                        ProgressView()
                            .scaleEffect(0.8)
                        Text("messages.decrypting".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    .padding(12)
                    // Transient state (a few frames while the message decrypts):
                    // shape only, no rim or elevation, so it does not flash a
                    // fully-dressed bubble that is about to be replaced.
                    // Was `bubbleBackground`, i.e. the RAW brand violet — so on any accent
                    // other than the default the placeholder flashed violet and then
                    // snapped to the user's colour. Same fill as the real bubble.
                    .background(bubbleFillStyle.opacity(0.5), in: bubbleOutline)
                } else if let text = decryptedText, !text.isEmpty, !text.hasPrefix("MEDIA|||") {
                    VStack(alignment: isFromCurrentUser ? .trailing : .leading, spacing: 8) {
                        // 🔧 FIX: Parse reply format if present
                        let parsedMessage = MessageWithReply.fromMessageString(text)
                        // 🔧 RAW-JSON FIX: `?? text` printed the whole
                        // "💬REPLY💬{...}" envelope in the bubble whenever the
                        // JSON failed to decode (truncated by the splitter, or
                        // written by a client with a different schema). Route
                        // the fallback through the catalog so the worst case is
                        // an empty bubble, never a wall of JSON.
                        let displayText = OSHIControlPayload.previewText(for: parsedMessage?.content ?? text)
                        let replyInfo = parsedMessage?.replyTo
                        // __FORWARD_BADGE_2026_08_18__ Hoisted out of the plain
                        // text branch: a forwarded message that CONTAINS a link
                        // renders through the two preview branches below, which
                        // had no badge at all — the same defect, just narrower.
                        // Read before `previewText` unwraps the envelope.
                        let forwardInfo = MessageActionsManager.shared.getForwardInfo(parsedMessage?.content ?? text)

                        // Show reply reference bubble if this is a reply
                        if let reply = replyInfo {
                            // __REPLY_QUOTE_2026_08_19__ Cinquième passe, et la
                            // première qui s'attaque à la bonne chose.
                            //
                            // Les quatre précédentes ont discuté du RAYON, de la
                            // TEINTE et de la BORDURE de ce rectangle. Aucune n'a
                            // demandé s'il devait exister. Un bloc rempli à
                            // l'intérieur d'une bulle se lit comme une deuxième
                            // bulle — une boîte dans une boîte — et c'est ça que
                            // Hugo voyait, pas une mauvaise couleur.
                            //
                            // Ce que dessine un messager 2026 n'est pas une boîte:
                            // une BARRE et deux lignes de texte, posées sur la
                            // bulle qui les porte.
                            //
                            //   • sur MA bulle: aucun fond du tout. La bulle est
                            //     déjà une surface colorée; un second remplissage
                            //     par-dessus EST le rectangle;
                            //   • sur une bulle reçue: un lavis à 7 %, juste assez
                            //     pour tenir la citation sur une bulle pâle posée
                            //     sur un fond d'écran pâle;
                            //   • plus AUCUN contour. Un liseré sur un bloc déjà
                            //     rempli, c'était la ceinture et les bretelles du
                            //     rectangle.
                            HStack(spacing: 9) {
                                Capsule(style: .continuous)
                                    .fill(replyQuoteAccent)
                                    .frame(width: 3)

                                // 🔧 RAW-JSON FIX: stripping the marker with
                                // replacingOccurrences left the BODY behind —
                                // "📍LOCATION📍{...json}" became "📍 {...json}".
                                // The catalog renders a summary instead of
                                // shaving off a prefix; `ReplyQuoteBody` runs
                                // every quote through it (ReplyMessage.bodyText)
                                // and adds the picture when there is one.
                                ReplyQuoteBody(
                                    reply: reply,
                                    title: reply.originalSenderKey == currentUserPublicKey
                                        ? "chat.you".localized
                                        : "chat.reply_to".localized,
                                    titleColor: replyQuoteAccent,
                                    textColor: replyQuoteTextColor,
                                    thumbnailSide: 34
                                )
                            }
                            // Same fix as the composer strip: constrain the row to
                            // its content, or the accent capsule stretches to fill
                            // whatever height the bubble offers.
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.vertical, 3)
                            .padding(.trailing, 4)
                            // __REPLY_QUOTE_ON_THE_WALLPAPER_2026_11_09__ this
                            // strip sits ABOVE the bubble, on the wallpaper —
                            // so it carries its own surface on BOTH sides. See
                            // `ChatPalette.quoteAccentUI`.
                            .background(
                                RoundedRectangle(cornerRadius: 9, style: .continuous)
                                    .fill(ChatPalette.quoteSurface)
                            )
                            .padding(.bottom, 3)
                            // The tap target stays the whole strip even where
                            // there is no longer a filled shape behind it —
                            // removing the box must not remove the affordance.
                            .contentShape(Rectangle())
                            .onTapGesture {
                                // Scroll the chat to the original quoted message.
                                NotificationCenter.default.post(
                                    name: NSNotification.Name("ScrollToOriginalMessage"),
                                    object: nil,
                                    userInfo: ["messageId": reply.originalMessageId]
                                )
                            }
                        }

                        // Combined link preview + message text in a single bubble
                        if let url = detectedURL, let previewData = linkPreviewFetcher.previewData {
                            // __EVERY_LINK_OPENED_THE_FIRST_ONE_2026_11_06__
                            // The preview CARD is tappable. The message text is
                            // not, and that is the fix.
                            //
                            // Reported: a message holding nine Play Store links
                            // opened the FIRST one wherever you tapped, and
                            // neither the text nor an individual link could be
                            // selected.
                            //
                            // The whole bubble used to be one `Button` whose
                            // action opened `detectedURL` — which is
                            // `text.detectFirstURL()`, the first link and only
                            // the first. A SwiftUI `Button` swallows every tap
                            // on its label, and the message text was INSIDE that
                            // label, so the per-link `.link` attributes never
                            // got the gesture. Worse, the text in there was a
                            // plain `Text`, not `ClickableTextView`, so in the
                            // preview case the other eight links were not even
                            // styled as links.
                            //
                            // That also explains why it looked intermittent: the
                            // `isLoading` branch below uses `ClickableTextView`,
                            // so every link worked until the preview arrived and
                            // replaced the bubble with the button.
                            //
                            // Now the Button wraps only the image and the site
                            // line — tapping the CARD still opens the link it is
                            // a preview of — and the body is a
                            // `ClickableTextView` outside it, where each link
                            // carries its own URL and opens that one.
                            VStack(alignment: .leading, spacing: 0) {
                            Button {
                                if let openURL = URL(string: url) {
                                    if !MapOpener.handleLink(openURL) { UIApplication.shared.open(openURL) } // place links → Settings › Maps
                                }
                            } label: {
                                VStack(alignment: .leading, spacing: 0) {
                                    // OG image on top — fills bubble width, clipped to top corners
                                    if let uiImage = linkPreviewFetcher.previewUIImage {
                                        GeometryReader { geo in
                                            Image(uiImage: uiImage)
                                                .resizable()
                                                .aspectRatio(contentMode: .fill)
                                                .frame(width: geo.size.width, height: 180)
                                                .clipped()
                                        }
                                        .frame(height: 180)
                                    }
                                    // Site name + title
                                    VStack(alignment: .leading, spacing: 3) {
                                        if let siteName = previewData.siteName {
                                            Text(siteName.uppercased())
                                                .font(.caption2)
                                                .foregroundColor(isFromCurrentUser ? .white.opacity(0.7) : .secondary)
                                        }
                                        Text(previewData.displayTitle)
                                            .font(.scaled(14, weight: .semibold))
                                            .foregroundColor(bubbleForeground)
                                            .lineLimit(2)
                                            .multilineTextAlignment(.leading)
                                    }
                                    .padding(.horizontal, 12)
                                    .padding(.top, 8)
                                    .padding(.bottom, 4)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                }
                            }
                            .buttonStyle(PlainButtonStyle())

                            // __FORWARD_BADGE_2026_08_18__ inside the
                            // bubble, directly above the body, same as
                            // the plain bubble below.
                            if let forwardInfo {
                                ForwardedBadgeView(forwardCount: forwardInfo.forwardCount,
                                                   isFromCurrentUser: isFromCurrentUser)
                                    .padding(.horizontal, 12)
                            }

                            // Message text at the bottom — OUTSIDE the button,
                            // so each link in it keeps its own tap. Same view
                            // the plain bubble and the loading branch use, so a
                            // message does not change its link behaviour
                            // depending on whether a preview happened to load.
                            ClickableTextView(
                                text: displayText,
                                textColor: bubbleForeground,
                                linkColor: bubbleLinkColor
                            )
                                .multilineTextAlignment(.leading)
                                .padding(.horizontal, 12)
                                .padding(.bottom, 12)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            }
                                .frame(maxWidth: BubbleStyleManager.maxBubbleWidth(in: chatColumnWidth))
                                .background(bubbleFillStyle)
                                // 🔧 (2026-08-18) This was a hardcoded
                                // `RoundedRectangle(cornerRadius: 15)`, so a
                                // message with a link preview kept the DEFAULT
                                // shape while every other bubble in the thread
                                // followed the user's choice — obvious at
                                // round(22) and at square(6). `clipShape`, not
                                // `bubbleSurface`, because the OG image has to
                                // be cut to the corners; the rim and the
                                // elevation are added on top by hand.
                                .clipShape(bubbleOutline)
                                .overlay(bubbleOutline.stroke(bubbleRim, lineWidth: 0.5))
                                .elevation(Theme.Elevation.low)
                        } else if linkPreviewFetcher.isLoading && detectedURL != nil {
                            // Still loading preview — show text + loading indicator
                            VStack(spacing: 4) {
                                // __FORWARD_BADGE_2026_08_18__ the badge and the
                                // body share ONE `.padding(12)` so the bubble's
                                // inset is unchanged when there is no badge.
                                VStack(alignment: .leading, spacing: 0) {
                                    if let forwardInfo {
                                        ForwardedBadgeView(forwardCount: forwardInfo.forwardCount,
                                                           isFromCurrentUser: isFromCurrentUser)
                                    }
                                    // __LONG_MESSAGE_RERENDER_2026_11_09__ see
                                    // ClickableTextView.swift — skips rebuilding
                                    // this row's markdown/ticker/link parse on
                                    // re-renders the open-settle's own retries
                                    // and anchor-flicker trigger.
                                    ClickableTextView(
                                        text: displayText,
                                        textColor: bubbleForeground,
                                        linkColor: bubbleLinkColor
                                    )
                                    .equatable()
                                }
                                .padding(12)
                                // __BUBBLE_HUGS_2026_08_14__ same ordering rule as
                                // the plain bubble below: fill first, cap after.
                                .bubbleSurface(silhouetteOverride: bubbleStyle.silhouette,
                                           fill: bubbleFillStyle,
                                               outline: bubbleOutline,
                                               rim: bubbleRim)
                                .frame(maxWidth: BubbleStyleManager.maxBubbleWidth(in: chatColumnWidth),
                                       alignment: isFromCurrentUser ? .trailing : .leading)
                                LinkPreviewLoadingView()
                            }
                        } else {
                            // No link or preview failed — normal text bubble
                            VStack(alignment: .leading, spacing: 2) {
                                // __FORWARD_BADGE_2026_08_18__ `ForwardedBadgeView`
                                // has existed and been localized since forwarding
                                // shipped, and was instantiated NOWHERE but its own
                                // #Preview — so a forwarded message arrived looking
                                // exactly like something the sender wrote himself,
                                // which is the one thing the badge exists to deny.
                                //
                                // Read from the RAW `text`: `displayText` has
                                // already been unwrapped by the payload catalog, so
                                // the envelope is gone by then.
                                //
                                // INSIDE the bubble, not above it: the badge's ink
                                // is `.white.opacity(0.7)` on an outgoing message,
                                // which is only readable on the accent fill — over
                                // a light wallpaper it would vanish.
                                if let forwardInfo {
                                    ForwardedBadgeView(forwardCount: forwardInfo.forwardCount,
                                                       isFromCurrentUser: isFromCurrentUser)
                                }
                                // __LONG_MESSAGE_RERENDER_2026_11_09__ see
                                // ClickableTextView.swift — this is the plain
                                // text bubble, so it is the one a very long
                                // message actually renders through.
                                ClickableTextView(
                                    text: displayText,
                                    textColor: bubbleForeground,
                                    linkColor: bubbleLinkColor,
                                    weight: bubbleStyle.textWeight
                                )
                                .equatable()
                            }
                            .padding(12)
                            // 🔧 FIX (2026-08-07): this — the ordinary 1:1 text
                            // bubble, the single most-rendered view in the app —
                            // had NO width cap. The only `maxBubbleWidth` in
                            // ChatView sits inside the link-PREVIEW branch above,
                            // so a message with a link was capped and a plain one
                            // was not. Groups and 1:1 now share one value, which
                            // is the whole point of the shared token.
                            //
                            // __BUBBLE_HUGS_2026_08_14__ ORDER MATTERS. The cap
                            // used to be applied BEFORE the background, and a
                            // `.frame(maxWidth:)` does not shrink to its child —
                            // it fills whatever the parent offers, up to the cap.
                            // So the fill painted the full cap width and a
                            // one-word message got a bubble as wide as a
                            // paragraph, with the word merely aligned inside it.
                            // Painting first lets the fill hug the text; the cap
                            // then only limits where the text WRAPS and which
                            // side the finished bubble sits on.
                            //
                            // __BUBBLE_SHAPE_2026_08_18__ `bubbleSurface` is the
                            // old `.background().cornerRadius()` pair plus a
                            // hairline rim and Elevation.low, in ONE modifier so
                            // the ordering rule above cannot be half-applied.
                            // It still has to come BEFORE the cap, for exactly
                            // the reason spelled out above.
                            .bubbleSurface(silhouetteOverride: bubbleStyle.silhouette,
                                           fill: bubbleFillStyle,
                                           outline: bubbleOutline,
                                           rim: bubbleRim)
                            .frame(maxWidth: BubbleStyleManager.maxBubbleWidth(in: chatColumnWidth),
                                   alignment: isFromCurrentUser ? .trailing : .leading)

                            // __TICKER_CHIPS_WIRED_2026_08_25__ Même pastille
                            // qu'en groupe. Rien à l'écran sans `@TICKER`, et
                            // aucun appel réseau avant le premier appui.
                            TickerChipsForText(text: displayText, messageId: message.id,
                                              autoLoad: isFromCurrentUser)   // __TICKER_RECEIVED_NO_AUTOLOAD_2026_11_20__
                                .frame(maxWidth: BubbleStyleManager.maxBubbleWidth(in: chatColumnWidth),
                                       alignment: isFromCurrentUser ? .trailing : .leading)

                            // __CASHU_2026_10_24__ Carte eCash, à la même place
                            // que les pastilles. Rien à l'écran tant qu'aucun
                            // jeton Cashu ne DÉCODE dans le message : une bulle
                            // ne doit jamais proposer d'encaisser une phrase.
                            CashuTokenCardsForText(text: displayText)
                                .frame(maxWidth: BubbleStyleManager.maxBubbleWidth(in: chatColumnWidth),
                                       alignment: isFromCurrentUser ? .trailing : .leading)
                        }
                    }
                    .onAppear {
                        // Detect URL when message appears (for already-decrypted messages)
                        if detectedURL == nil, let url = text.detectFirstURL() {
                            OshiLog.messaging.info("🔗 MessageBubble.onAppear: URL detected: \(url)")
                            detectedURL = url
                            // __LEAK_FIX_2026_09_25__ network only for our own links
                            linkPreviewFetcher.fetchPreview(for: url, allowNetwork: isFromCurrentUser)
                        }
                    }
                } else {
                    // __PEER_VERSION_EVIDENCE_2026_10_09__ Say WHY when we know
                    // why. A bare "Failed to decrypt" reads as "OSHI is
                    // broken"; when the peer has been proven to be on a
                    // pre-0cfc8c9 build (see `looksLikeLegacyResponderChain`)
                    // the cause is known, it is not this device, and there IS
                    // something the user can do about it. Same plain register
                    // as `outdatedPeerBanner` on purpose — no "security", no
                    // "failure": this bubble ends up in screenshots.
                    Text(failedDecryptText)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .padding(12)
                        // Was a hardcoded `Color(.systemGray6)`, which in dark
                        // mode is nearly the page. Uses the INCOMING surface
                        // regardless of sender: the text is `.secondary`, and
                        // `.secondary` on the outgoing accent gradient is not
                        // readable on any of the six accents.
                        .bubbleSurface(fill: bubbleStyle.incomingFill(colorScheme),
                                       outline: bubbleOutline,
                                       rim: bubbleStyle.rim(outgoing: false, colorScheme))
                        // 🚧 The user is LOOKING AT a decryption failure. Stamp
                        // friction so the "Enjoying OSHI?" ask cannot land in
                        // the minutes that follow. (Bubble-level, so it only
                        // fires for failures actually on screen — not for the
                        // background undecryptable-retry queue, which would
                        // otherwise suppress the ask forever.)
                        .onAppear { RateAppManager.shared.recordFriction(reason: "undecryptable-bubble") }
                }

                // __BUBBLE_RUN_2026_08_18__ The footer belongs to the RUN, not to
                // every message in it. Five messages in a burst used to repeat
                // the same minute and the same tick five times, which is the
                // noise that makes a thread look like a log file.
                //
                // The exceptions are not cosmetic, they are the whole reason
                // this is a condition and not `if isLastInRun`:
                //  • `.failed` carries the "tap to retry" BUTTON
                //    (deliveryStatusIcon). Hiding it mid-run would leave a
                //    message that cannot be sent and cannot be retried — a way
                //    to lose a message, not a tidier row.
                //  • `.pending` is the one status a reader needs while it is
                //    still true; by the time it settles the row folds itself.
                //  • an edited message must keep saying so wherever it sits.
                //
                // What IS hidden — the timestamp of an interior message — stays
                // reachable: the swipe-left reveal below is on every bubble.
                // __BUBBLE_MEDIA_2026_11_21__ A media-only picture carries its
                // time and ticks in a pill ON the picture (MediaTimePill), so
                // the row does not print them a second time under it. Failed /
                // pending keep the row: that is where "tap to retry" lives.
                let pillCarriesFooter = hasMedia && MediaChatBubble.showsTimePill(message)
                    && message.deliveryStatus != .failed && message.deliveryStatus != .pending
                let showsFooter = !pillCarriesFooter && (isLastInRun
                    || message.deliveryStatus == .failed
                    || message.deliveryStatus == .pending
                    || message.editedAt != nil)
                if showsFooter {
                    // __CHAT_POLISH_2026_09_24__ `ChatPalette.metaInk`, not
                    // `.secondary`: this row sits on the WALLPAPER, where
                    // `.secondary` measured 3.44:1. metaInk is ≥ 4.5:1 on the
                    // worst ground in light, dark and Light-tone-in-dark.
                    HStack(spacing: 4) {
                        if message.editedAt != nil {
                            Text("message.edited".localized)
                                .font(.caption2)
                                .italic()
                                .foregroundColor(ChatPalette.metaInk)
                        }

                        Text(formatTimestamp(message.timestamp))
                            .font(.caption2)
                            .foregroundColor(ChatPalette.metaInk)

                        // Transport badge. Previously only `.mesh` was shown, on the
                        // grounds that the relay is the default and a label on every
                        // bubble reads as noise. That held while there were two
                        // transports; with LoRa there are three, and which one
                        // carried a message is now the single most useful thing on
                        // the row — it is the difference between "this went over the
                        // internet" and "this crossed 4 km of nothing on a radio".
                        // Hiding two thirds of that answer to save a chip was the
                        // wrong trade, so all three are labelled.
                        //
                        // Still gated on a settled status: a message in flight has
                        // no transport yet, and guessing one would be a lie that
                        // corrects itself a second later.
                        if let method = message.deliveryMethod, method != .pending,
                           message.deliveryStatus == .sent || message.deliveryStatus == .delivered {
                            DeliveryBadgeMinimal(
                                method: method,
                                isOutgoing: isFromCurrentUser
                            )
                        }

                        // ✅ Read receipt checkmarks for sent messages (WhatsApp-style).
                        // Crossfade between states (clock → check → blue read) instead
                        // of a hard snap — `statusVersion` changes drive the re-create.
                        if isFromCurrentUser {
                            deliveryStatusIcon
                                .id(statusVersion)
                                .transition(.opacity)
                                .animation(.easeInOut(duration: 0.22), value: statusVersion)
                        }
                    }
                }

                // __LORA_AIRTIME_HINT_2026_11_18__ The OSHI Mesh radio accepted this message but
                // has held it for over a minute: the hourly duty-cycle budget (EU_868) is spent and it
                // goes out when the budget refills. The phone only ever hears QUEUED, which looked hung.
                if isFromCurrentUser, loRaAirtime.waiting.contains(message.id) {
                    Text("lora.waiting_airtime".localized)
                        .font(.caption2)
                        .foregroundColor(ChatPalette.metaInk)
                        .accessibilityIdentifier("lora.waiting_airtime")
                }

                // __RESTRICTED_NETWORK_HINT_2026_11_18__ the sender wrote this through
                // OSHI's privacy disguise (RN-HINT flag, stripped at ingestion).
                if !isFromCurrentUser, RestrictedNetworkHintStore.contains(message.id) {
                    RestrictedNetworkChip()
                }

                // 🔥 Show reactions below message
                MessageReactionsView(
                    messageId: message.id,
                    isFromCurrentUser: isFromCurrentUser
                )
            }
            
            if !isFromCurrentUser {
                Spacer()
            }
        }
        // __BUBBLE_TIME_2026_08_17__ Glisser vers la gauche pour révéler
        // l'horodatage complet — date ET heure, y compris sur un message d'hier
        // ou d'il y a un mois, ce que l'étiquette de la bulle ne dit pas.
        //
        // Le garde-fou qui compte: on ne bouge QUE si le déplacement est
        // franchement horizontal. Sans ça, un simple défilement de la liste
        // fait trembler chaque bulle qu'il traverse, et la messagerie devient
        // désagréable à parcourir.
        .offset(x: timeRevealOffset)
        .overlay(alignment: .trailing) {
            if timeRevealOffset < -8 {
                Text(fullTimestamp(message.timestamp))
                    .font(.caption2.monospacedDigit())
                    .foregroundColor(ChatPalette.metaInk)
                    .lineLimit(1)
                    .fixedSize()
                    .offset(x: 74 + timeRevealOffset)
                    .opacity(min(1, Double(-timeRevealOffset) / 40))
                    .accessibilityHidden(true)
            }
        }
        // __PHOTO_OPENS_ON_SCROLL_2026_08_24__ `.simultaneousGesture`, pas
        // `.gesture`.
        //
        // Le commentaire ci-dessus appelle le test `abs(width) > abs(height)`
        // « le garde-fou qui compte ». Il ne l'est pas: il vit dans `onChanged`,
        // donc il ne s'exécute qu'APRÈS que le geste a reconnu et capturé le
        // toucher. Il empêche la bulle de trembler — il n'empêche pas le geste
        // de disputer le glissement à la liste. Résultat rapporté: en défilant
        // par-dessus une photo, la photo s'ouvrait au passage.
        //
        // `.simultaneousGesture` laisse le défilement (et la tape) se produire
        // en même temps au lieu de leur disputer le toucher. La révélation de
        // l'horodatage fonctionne à l'identique: le garde-fou horizontal reste,
        // et un glissement vertical n'écrit toujours aucun offset.
        .simultaneousGesture(
            DragGesture(minimumDistance: 14)
                .onChanged { value in
                    guard abs(value.translation.width) > abs(value.translation.height) else { return }
                    // Vers la gauche uniquement, et borné: c'est une révélation,
                    // pas un déplacement de la bulle.
                    timeRevealOffset = max(-72, min(0, value.translation.width))
                }
                .onEnded { _ in
                    withAnimation(.spring(response: 0.32, dampingFraction: 0.82)) {
                        timeRevealOffset = 0
                    }
                }
        )
        // 🔥 Long press for the reaction picker / actions, double-tap for the
        // OSHI smile. __BUBBLE_GESTURES_2026_11_09__ simultaneous, not
        // `.onLongPressGesture`: a child with its own gesture (link card,
        // quote, media, chip) used to win the touch, so the press only worked
        // on bare text. See `BubbleReactionGestures`.
        .modifier(BubbleReactionGestures(
            onDoubleTap: { sendReaction(.oshiSmile) },
            onLongPress: {
                let generator = UIImpactFeedbackGenerator(style: .medium)
                generator.impactOccurred()
                showReactionPicker = true
            }))
        // __MAC_MENU_MATCHES_THE_SHEET_2026_10_20__ Le clic droit du Mac offre
        // maintenant les trois actions que l'appui long donne sur iPhone, et il
        // vit sur la bulle pour pouvoir les RÉUTILISER au lieu de les refaire.
        //
        // Posé sur la ligne (dans `ChatView`), il n'avait accès qu'à
        // `message.plaintextContent`, et les deux conséquences se voyaient:
        //   * il écrivait dans `UIPasteboard` en direct, donc sans l'expiration
        //     de 60 s de `SecureClipboard` que toute autre copie respecte;
        //   * il copiait le JSON sentinelle d'un résumé d'appel ou d'une
        //     position au lieu de sa ligne lisible, faute de passer par
        //     `OSHIControlPayload.previewText`.
        // Et il ne POUVAIT pas proposer Transférer: `showForwardPicker` est un
        // état de la bulle, invisible depuis la ligne.
        //
        // Ici, les trois entrées appellent exactement ce que les fermetures de
        // `ReactionOverlay` juste en dessous appellent — une seule définition de
        // ce que « copier », « traduire » et « transférer » veulent dire.
        #if targetEnvironment(macCatalyst)
        // Mac SEULEMENT, et c'est tout le sujet: un `.contextMenu` SwiftUI PREND
        // la place de l'appui long sur iOS. Posé sans ce garde le 19/10, il a
        // fait disparaître Traduire, Répondre, Transférer et Supprimer de
        // l'iPhone, ne laissant que Copier. Le Mac n'a pas d'appui long, donc le
        // garde donne au Mac son menu et laisse à l'iPhone sa feuille d'actions.
        .contextMenu {
            // __REACTION_MAC_MENU_2026_11_15__ Réagir, même chemin que
            // `onReact` de l'overlay (`sendReaction`).
            ReactionContextMenu(onReact: { sendReaction($0) })
            // Même source que `onCopy` de l'overlay: le texte LISIBLE, jamais le
            // sentinelle.
            let readable = OSHIControlPayload.previewText(
                for: decryptedText ?? message.plaintextContent ?? ""
            )
            if !readable.isEmpty {
                if !MessagePrivacy.blockForwardAndCopy {
                    Button {
                        // __CLIPBOARD_HYGIENE_2026_10_01__ cet appareil, 60 s.
                        SecureClipboard.copy(readable)
                    } label: {
                        Label("message.action.copy".localized, systemImage: "doc.on.doc")
                    }
                }
                // Même porte que l'overlay: on traduit ce qu'on REÇOIT. Et on
                // passe le texte brut, comme `onTranslate`, parce que le
                // traducteur veut la phrase d'origine.
                if !isFromCurrentUser {
                    Button {
                        onTranslateRequest?(decryptedText ?? message.plaintextContent ?? "")
                    } label: {
                        Label("message.action.translate".localized,
                              systemImage: "character.bubble")
                    }
                }
                if !MessagePrivacy.blockForwardAndCopy {
                    Button {
                        // Pas le délai de 0,2 s de l'overlay: il n'existe que
                        // pour laisser le `fullScreenCover` se refermer d'abord,
                        // et un menu contextuel n'en ouvre aucun.
                        //
                        // « Transférer » est un partage INTERNE: le sélecteur
                        // est une liste de contacts OSHI (`ForwardPicker`), pas
                        // la feuille de partage du système. Rien ne quitte l'app
                        // par cette entrée.
                        showForwardPicker = true
                    } label: {
                        Label("message.action.forward".localized,
                              systemImage: "arrowshape.turn.up.right")
                    }
                }

                // __MAC_COULD_NOT_EDIT_AT_ALL_2026_10_22__ Modifier manquait
                // ici, et le Mac n'a pas d'autre chemin: l'appui long iPhone
                // ouvre `ReactionOverlay`, qui propose Modifier depuis
                // `MessageReactionManager` — un clic droit n'ouvre pas cette
                // feuille, donc sur Mac le message était simplement
                // immodifiable.
                //
                // Mêmes conditions QUE l'overlay, à la ligne près (voir le
                // `onEdit:` passé à ReactionOverlay plus bas): notre propre
                // message, pas de média, et dans les 10 minutes. Si la fenêtre
                // doit changer, elle change AUX DEUX endroits.
                // __EDIT_WINDOW_WAS_COSMETIC_2026_11_05__ The 10-minute clock
                // is gone from 1:1. Reported as "the Modify button is not
                // there", and it was not — it had expired.
                //
                // The window protected nothing. The RECEIVE path
                // (`MessageManager.handleMessageAction`, `case .editMessage`)
                // checks ownership and ONLY ownership: no clock. Desktop
                // (`row.fromMe`) has no window either, so a contact there could
                // already edit a three-day-old message and this device would
                // accept and apply it — the window restrained the local user
                // against a rule nothing in the system enforced.
                //
                // Android 1:1 DID have a matching 600 s window
                // (`ChatScreen.kt`, `canEdit`); it was removed in the same
                // change for the same reason, so the two phones agree. An
                // earlier version of this comment claimed Android had no window
                // — that was a misreading of a multi-line condition.
                //
                // It is also the wrong shape for this app. Delivery here can lag
                // for hours — store-and-forward relay, mesh, LoRa — so a
                // 10-minute edit window can expire before the message has even
                // ARRIVED, which is precisely when editing is most harmless.
                //
                // Ownership and no-media still hold. GROUPS keep their 10
                // minutes: iOS and Android agree there (600 s vs
                // GROUP_EDIT_WINDOW_MS = 600_000 ms) and that path has its own
                // enforcement in GroupManager, so loosening one side alone would
                // desync them.
                if isFromCurrentUser,
                   message.mediaType == nil {
                    Button {
                        // Le texte D'ORIGINE, pas `readable`: la boîte d'édition
                        // doit contenir ce que l'utilisateur a écrit, et c'est
                        // exactement ce que la fermeture `onEdit` de l'overlay
                        // met dedans.
                        editingText = decryptedText ?? message.plaintextContent ?? ""
                        showEditSheet = true
                    } label: {
                        Label("message.action.edit".localized, systemImage: "pencil")
                    }
                }
            }

            // Supprimer, DEHORS du test sur `readable`: une photo, une position
            // ou un résumé d'appel n'ont pas forcément de texte lisible, et il
            // faut quand même pouvoir les supprimer. (Première version de ce
            // menu: Supprimer était à l'intérieur, donc invisible sur un
            // message sans texte — exactement le genre de trou que ce menu
            // existe pour combler.)
            //
            // La décision n'est pas prise ici: `showDeleteSheet` ouvre la même
            // `confirmationDialog` que l'appui long iOS — « pour moi » toujours,
            // « pour tout le monde » seulement si le message est le nôtre.
            Divider()
            Button(role: .destructive) {
                showDeleteSheet = true
            } label: {
                Label("message.action.delete".localized, systemImage: "trash")
            }
        }
        #endif
        .background(
            GeometryReader { geo in
                Color.clear
                    .onAppear {
                        messageFrame = geo.frame(in: .global)
                    }
            }
        )
        .fullScreenCover(isPresented: $showReactionPicker) {
            ReactionOverlay(
                messageId: message.id,
                senderPublicKey: currentUserPublicKey,
                senderName: "You",
                isPresented: $showReactionPicker,
                messageFrame: messageFrame,
                isFromCurrentUser: message.senderPublicKey == currentUserPublicKey,
                onReact: { emoji in sendReaction(emoji) },
                messageContent: decryptedText ?? message.plaintextContent,
                // ➡️ Forward support — open contact picker after dismissing the reaction overlay.
                onForward: {
                    guard !MessagePrivacy.blockForwardAndCopy else { return }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                        showForwardPicker = true
                    }
                },
                // 📋 Copy — 🔧 FIX (2026-08-12): `onCopy` was simply never
                // passed here, and it defaults to nil, so `messageActionsBar`
                // (MessageReactionManager.swift:929) skipped the button
                // entirely. Groups had it; 1:1 chat could not copy a message at
                // all. Routed through the control-payload catalog so copying a
                // call summary or a location puts the readable line on the
                // pasteboard, never the sentinel JSON.
                onCopy: {
                    guard !MessagePrivacy.blockForwardAndCopy else { return }
                    let text = OSHIControlPayload.previewText(
                        for: decryptedText ?? message.plaintextContent ?? ""
                    )
                    guard !text.isEmpty else { return }
                    // __CLIPBOARD_HYGIENE_2026_10_01__ message text: this device only, 60 s
                    SecureClipboard.copy(text)
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                },
                // 💬 Reply support - iPad ergonomics
                onReply: {
                    // 🔧 RAW-JSON FIX: 35 lines of hand-rolled marker cleaning
                    // used to live here, covering CALL_SUMMARY / MISSED_CALL /
                    // SCHEDULED_CALL / LOCATION — 4 of the 24 sentinels. Every
                    // other one (reactions, check-ins, profile updates, and a
                    // reply to a reply) quoted its raw JSON. One catalog now.
                    let replyText = OSHIControlPayload.previewText(
                        for: decryptedText ?? message.plaintextContent ?? ""
                    )
                    // Create reply message info
                    let replyInfo = ReplyMessage(
                        originalMessageId: message.id,
                        originalSenderKey: message.senderPublicKey,
                        originalText: replyText,
                        originalTimestamp: message.timestamp,
                        originalMediaType: message.mediaType?.rawValue
                    )
                    // Post notification to ChatView to set reply state
                    NotificationCenter.default.post(
                        name: NSNotification.Name("ReplyToMessage"),
                        object: nil,
                        userInfo: ["replyMessage": replyInfo]
                    )
                },
                onDelete: {
                    showDeleteSheet = true
                },
                onTranslate: {
                    let textToTranslate = decryptedText ?? message.plaintextContent ?? ""
                    if let hoisted = onTranslateRequest {
                        hoisted(textToTranslate)
                        return
                    }
                    Task {
                        if let translated = await NLPManager.shared.processTranslateReceived(textToTranslate) {
                            await MainActor.run {
                                translatedMessageText = translated
                                showTranslationResult = true
                            }
                        } else {
                            // Fallback: use iOS 18 Translation sheet or show unavailable
                            await MainActor.run {
                                if #available(iOS 18.0, *) {
                                    translationSourceText = textToTranslate
                                    showTranslationSheet = true
                                } else {
                                    translatedMessageText = "ai.translate_failed".localized
                                    showTranslationResult = true
                                }
                            }
                        }
                    }
                },
                onPin: {
                    let convKey = message.senderPublicKey == currentUserPublicKey
                        ? message.recipientPublicKey : message.senderPublicKey
                    if messageManager.pinnedMessages[convKey] == message.id {
                        messageManager.unpinMessageAndSync(in: convKey, messageId: message.id, identityManager: identityManager, meshManager: meshManager)
                    } else {
                        messageManager.pinMessageAndSync(in: convKey, messageId: message.id, identityManager: identityManager, meshManager: meshManager)
                    }
                },
                pinLabel: {
                    let convKey = message.senderPublicKey == currentUserPublicKey
                        ? message.recipientPublicKey : message.senderPublicKey
                    return messageManager.pinnedMessages[convKey] == message.id ? "pinned.unpin_message".localized : "pinned.pin_message".localized
                }(),
                isPinnedMessage: {
                    let convKey = message.senderPublicKey == currentUserPublicKey
                        ? message.recipientPublicKey : message.senderPublicKey
                    return messageManager.pinnedMessages[convKey] == message.id
                }(),
                // __EDIT_WINDOW_WAS_COSMETIC_2026_11_05__ Same change as the
                // context menu above, and the comment there explains why. These
                // two sites must always agree — this is the iPhone path
                // (long press) and that is the Mac path (right click).
                onEdit: isFromCurrentUser && message.mediaType == nil ? {
                    // __EDIT_SHEET_RACED_THE_OVERLAY_2026_11_05__ The same 0.2 s
                    // defer `onForward` above uses, and for the same reason its
                    // comment gives: this overlay is a `fullScreenCover`, and
                    // asking for a second presentation before it has finished
                    // dismissing gets the second one SWALLOWED. The sheet then
                    // never appears and the button reads as broken.
                    //
                    // `dismissOverlay` only clears `isPresented` after 0.15 s
                    // (`MessageReactionManager.dismissOverlay`), so a
                    // synchronous `showEditSheet = true` was inside that window
                    // every time. Forward was already guarded; Edit was not.
                    //
                    // The prefill is captured NOW rather than inside the
                    // closure's delay, so a redraw between the tap and the
                    // presentation cannot change what lands in the box.
                    let prefill = decryptedText ?? message.plaintextContent ?? ""
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                        editingText = prefill
                        showEditSheet = true
                    }
                } : nil
            )
            .background(ClearBackgroundView())
        }
        .alert("message.action.translate".localized, isPresented: $showTranslationResult) {
            Button("common.ok".localized, role: .cancel) { }
            if !MessagePrivacy.blockForwardAndCopy {
                Button("message.action.copy".localized) {
                    // __CLIPBOARD_HYGIENE_2026_10_01__ message text: this device only, 60 s
                    SecureClipboard.copy(translatedMessageText)
                }
            }
        } message: {
            Text(translatedMessageText)
        }
        .if_available_translation(sourceText: translationSourceText, isPresented: $showTranslationSheet)
        .confirmationDialog("message.action.delete".localized, isPresented: $showDeleteSheet, titleVisibility: .visible) {
            Button("group.delete.for_me".localized, role: .destructive) {
                messageManager.deleteMessage(message.id)
            }
            if isFromCurrentUser {
                Button("group.delete.for_everyone".localized, role: .destructive) {
                    let recipientKey = message.recipientPublicKey
                    messageManager.deleteMessageForEveryone(message.id, recipientKey: recipientKey, identityManager: identityManager, meshManager: meshManager)
                }
            }
            Button("common.cancel".localized, role: .cancel) { }
        }
        // 🔧 FIX (2026-09-12, "the box to modify my text is not user friendly
        // and complicated to change well when the text is a bit long"): this
        // was an `.alert` holding a bare `TextField`. An alert's text field is
        // a UIAlertController field — one line, ~200pt wide, no wrapping — so a
        // message of any length scrolled sideways through a slit you could not
        // read or aim the caret into. `axis:` and `.lineLimit` are ignored
        // inside an alert, so the container itself had to be replaced.
        //
        // Only the box changed. The `editMessage` call below is byte-for-byte
        // the one the alert made, with the same recipient-key derivation; the
        // old `guard !editingText.isEmpty` moved into the sheet's Save button,
        // where it now DISABLES the button instead of silently dismissing.
        .sheet(isPresented: $showEditSheet) {
            MessageEditSheet(originalText: editingText) { newContent in
                let recipientKey = message.senderPublicKey == currentUserPublicKey
                    ? message.recipientPublicKey : message.senderPublicKey
                messageManager.editMessage(message.id, newContent: newContent, recipientKey: recipientKey, identityManager: identityManager, meshManager: meshManager)
            }
        }
        .sheet(isPresented: $showForwardPicker) {
            ForwardContactPickerView(
                isPresented: $showForwardPicker,
                messageContent: decryptedText ?? message.plaintextContent ?? ""
            ) { recipientPublicKey in
                forwardMessage(to: recipientPublicKey)
            }
        }
        // 🔧 FIX (2026-06-04): re-run decrypt when the underlying message's
        // plaintextContent is mutated in place by MessageManager (live-location
        // and CheckIn updates use the in-place pattern at MessageManager.swift
        // ~line 5239-5300 to avoid spamming the chat with one bubble per
        // 10-second tick). Without `.task(id:)`, decryptedText keeps the
        // ORIGINAL payload forever — so the CheckIn mini-map and the live
        // location coordinate stay frozen on the first value even though
        // newer ones arrived.
        .task(id: message.plaintextContent) {
            await decryptMessage()
        })
    }

    // ➡️ Compose a fresh forwarded message (new id + timestamp) to the chosen recipient.
    private func forwardMessage(to recipientPublicKey: String) {
        let rawText = decryptedText ?? message.plaintextContent ?? ""

        // Media forward — pass media payload, not a link.
        if let mediaData = message.originalMediaData ?? message.mediaAttachment,
           let type = message.mediaType {
            messageManager.sendMessage(
                content: "",
                recipientAddress: recipientPublicKey,
                identityManager: identityManager,
                meshManager: meshManager,
                mediaAttachment: mediaData,
                mediaType: type,
                mediaFileName: message.mediaFileName,
                // __VIDEO_NOTE_2026_09_24__ spec §4: forwarding keeps the round form.
                videoNote: message.videoNoteMetadata
            )
            return
        }

        guard !rawText.isEmpty else { return }
        // Strip any reply prefix so the forwarded message isn't tagged as replying to a message in another chat.
        // Forwarding a control payload would send the SENTINEL to the new
        // chat, where the recipient's client either routes it as a control
        // message or prints its JSON. Forward the human summary instead.
        let cleanContent = OSHIControlPayload.previewText(
            for: MessageWithReply.fromMessageString(rawText)?.content ?? rawText
        )
        let forwarded = MessageActionsManager.shared.createForwardedMessage(
            originalContent: cleanContent,
            originalSenderName: nil
        )
        messageManager.sendMessage(
            content: forwarded,
            recipientAddress: recipientPublicKey,
            identityManager: identityManager,
            meshManager: meshManager
        )

        let generator = UINotificationFeedbackGenerator()
        generator.notificationOccurred(.success)
    }
    
    private func decryptMessage() async {
        if isFromCurrentUser {
            await MainActor.run {
                decryptedText = message.plaintextContent ?? "chat.message_unavailable".localized
                isDecrypting = false
            }
            return
        }
        
        if let plaintext = message.plaintextContent,
           !plaintext.isEmpty,
           !plaintext.starts(with: "eyJ"),
           !plaintext.starts(with: "{\"type\":\"media\""),
           !plaintext.starts(with: "[Media:"),
           !plaintext.starts(with: "MEDIA|||") {
            await MainActor.run {
                decryptedText = plaintext
                isDecrypting = false
            }
            return
        }
        
        let manager = messageManager
        let wallet = identityManager
        let msg = message
        
        // A visible placeholder is preferable to stealing time from the push,
        // scroll and keyboard animations when a thread has several encrypted
        // bubbles to open at once. The decrypt still happens immediately in the
        // background; `.utility` simply leaves interactive UI work first claim
        // on the CPU.
        let decrypted = await Task.detached(priority: .utility) {
            manager.decryptMessage(msg, using: wallet)
        }.value
        
        await MainActor.run {
            decryptedText = decrypted ?? "chat.encrypted".localized
            isDecrypting = false
            
            // Check for URLs and fetch preview
            if let text = decrypted, let url = text.detectFirstURL() {
                OshiLog.messaging.info("🔗 ChatView: URL detected in message: \(url)")
                detectedURL = url
                // __LEAK_FIX_2026_09_25__ network only for our own links
                linkPreviewFetcher.fetchPreview(for: url, allowNetwork: isFromCurrentUser)
            } else {
                OshiLog.messaging.info("ℹ️ ChatView: No URL detected in message")
            }
        }
    }
    
    /// 🔧 PERF: this runs inside `MessageBubble.body`, i.e. once per visible
    /// bubble on every re-render. It used to build a fresh `DateFormatter` each
    /// time — see `OshiRowFormatters` at the top of this file for why that is
    /// the expensive part. Output is byte-identical to the old code.
    // MARK: - __REPLY_QUOTE_2026_08_17__ jetons de la citation
    //
    // Une citation vit DANS une bulle, donc ses couleurs doivent venir de cette
    // bulle. Un gris neutre fixe marche sur la bulle reçue et casse sur la bulle
    // envoyée, qui est peinte à la couleur de marque.

    /// Toggle MY `emoji` on this message and tell the other side. One body for
    /// the reaction overlay and the double-tap (__OSHI_SMILE_REACTION_2026_11_09__),
    /// so both send exactly the same payload.
    private func sendReaction(_ emoji: ReactionEmoji) {
        let isRemoving = reactionManager.hasUserReacted(
            messageId: message.id,
            senderPublicKey: currentUserPublicKey,
            emoji: emoji
        )
        reactionManager.toggleReaction(
            to: message.id,
            emoji: emoji,
            senderPublicKey: currentUserPublicKey,
            senderName: "You"
        )
        let partnerKey = message.senderPublicKey == currentUserPublicKey
            ? message.recipientPublicKey
            : message.senderPublicKey
        let payload = ReactionPayload(
            messageId: message.id,
            emoji: emoji,
            senderPublicKey: currentUserPublicKey,
            senderName: "You",
            timestamp: Date(),
            action: isRemoving ? .remove : .add
        )
        messageManager.sendMessage(
            content: payload.toMessageString(),
            recipientAddress: partnerKey,
            identityManager: identityManager,
            meshManager: meshManager
        )
    }

    /// __REPLY_QUOTE_ON_THE_WALLPAPER_2026_11_09__ Was `.white` on my side,
    /// chosen for the purple bubble — but the quote is drawn ABOVE the bubble,
    /// on the wallpaper, so in light mode it was white on near-white. Both
    /// sides now use wallpaper-safe tokens on the quote's own surface.
    private var replyQuoteAccent: Color { ChatPalette.quoteAccent }

    private var replyQuoteTextColor: Color { ChatPalette.quoteBody }

    /// Accordé au rayon de la bulle porteuse — une citation à rayon fixe dans une
    /// bulle à rayon variable est ce qui donne l'air « collé ».
    private var replyQuoteRadius: CGFloat {
        max(bubbleRadius - 7, 8)
    }

    /// __BUBBLE_TIME_2026_08_17__ Ce que le geste révèle: la date ET l'heure.
    /// Aujourd'hui, la date serait du bruit — l'en-tête de section la donne déjà.
    private func fullTimestamp(_ date: Date) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(date) {
            return OshiRowFormatters.string(date, format: "HH:mm")
        }
        return OshiRowFormatters.string(date, format: "d MMM · HH:mm")
    }

    /// __BUBBLE_TIME_2026_08_17__ L'HEURE, toujours.
    ///
    /// Ceci renvoyait « Hier » pour un message d'hier et « 12 août » pour un plus
    /// ancien — c'est-à-dire que passé minuit, l'heure d'envoi d'un message
    /// devenait introuvable. Or la date est DÉJÀ l'en-tête de section
    /// (`messagesByDate` groupe par jour): l'étiquette de la bulle répétait donc
    /// l'information qu'on avait et supprimait celle qu'on n'avait pas.
    private func formatTimestamp(_ date: Date) -> String {
        OshiRowFormatters.string(date, format: "HH:mm")
    }
}

// MARK: - System Info Bubble (WhatsApp-style centered info messages)

/// Displays system information messages like profile updates, wallpaper changes
/// in a centered, subtle style similar to WhatsApp group info messages
struct SystemInfoBubble: View {
    let isProfileUpdate: Bool
    let isWallpaperUpdate: Bool
    /// __BLOCKED_NOTICE_2026_09_18__ Avec une valeur par défaut: les autres sites
    /// d'appel ne changent pas d'un caractère.
    var isBlockedNotice: Bool = false
    let isFromCurrentUser: Bool
    let timestamp: Date
    
    @Environment(\.colorScheme) var colorScheme
    
    private var infoText: String {
        if isProfileUpdate {
            return isFromCurrentUser
                ? "📸 " + "system.profile_updated".localized
                : "📸 " + "system.contact_profile_updated".localized
        } else if isWallpaperUpdate {
            return isFromCurrentUser
                ? "🎨 " + "system.wallpaper_changed".localized
                : "🎨 " + "system.contact_wallpaper_changed".localized
        } else if isBlockedNotice {
            // Pas de variante « moi »: cet avis n'arrive que dans un sens. On le
            // lit du catalogue plutôt que de le réécrire, pour que la ligne du fil
            // et celle de la liste de conversations disent le même mot.
            return OSHIControlPayload.previewText(for: OSHIControlPayload.blockedNoticePrefix)
        }
        return ""
    }
    
    private var backgroundColor: Color {
        colorScheme == .dark
            ? Color(.systemGray5).opacity(0.8)
            : Color(.systemGray6).opacity(0.9)
    }
    
    var body: some View {
        HStack {
            Spacer()
            
            VStack(spacing: 2) {
                Text(infoText)
                    .font(.caption)
                    .fontWeight(.medium)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                
                Text(formatTime(timestamp))
                    .font(.caption2)
                    .foregroundColor(.secondary.opacity(0.7))
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(
                Capsule()
                    .fill(backgroundColor)
            )
            
            Spacer()
        }
        .padding(.vertical, 4)
    }
    
    /// 🔧 PERF: `SystemInfoBubble` is drawn inline in the message list (see the
    /// `MessageBubble` body), so this ran once per visible system bubble per
    /// re-render — the same per-row `DateFormatter()` cost as
    /// `MessageBubble.formatTimestamp`. See `OshiRowFormatters` at the top of
    /// this file. Output is byte-identical.
    private func formatTime(_ date: Date) -> String {
        return OshiRowFormatters.string(date, format: "HH:mm")
    }
}

// MARK: - Chat Alerts Modifier

struct ChatAlertsModifier: ViewModifier {
    @Binding var showingAliasEditor: Bool
    @Binding var showingError: Bool
    @Binding var showingUnblockAlert: Bool
    @Binding var showingResetSessionAlert: Bool
    let currentAlias: String
    /// __SHARED_NICKNAME_2026_09_22__ alias reel (ou adresse s'il n'y a rien).
    let aliasEditorSeed: String
    /// Le pseudo partage par le contact, affiche comme indice sous le champ.
    let sharedNickname: String?
    let errorMessage: String
    let saveAlias: (String) -> Void
    let unblockContact: () -> Void
    let resetSession: () -> Void
    
    @State private var aliasText: String = ""
    
    func body(content: Content) -> some View {
        content
            .alert("chat.set_alias".localized, isPresented: $showingAliasEditor) {
                // Le pseudo du contact sert d'invite: un champ vide = on garde
                // son pseudo, taper un alias le remplace pour soi seul.
                TextField(sharedNickname ?? "chat.enter_alias".localized, text: $aliasText)
                Button("common.save".localized) {
                    saveAlias(aliasText)
                }
                Button("common.cancel".localized, role: .cancel) {}
            } message: {
                if let nick = sharedNickname {
                    Text("chat.set_alias_message".localized + "\n\n"
                         + String(format: "contact.shared_nickname".localized, nick))
                } else {
                    Text("chat.set_alias_message".localized)
                }
            }
            .alert("alert.error".localized, isPresented: $showingError) {
                Button("common.ok".localized, role: .cancel) {}
            } message: {
                Text(errorMessage)
            }
            .alert("chat.unblock_contact_title".localized, isPresented: $showingUnblockAlert) {
                Button("common.cancel".localized, role: .cancel) { }
                Button("chat.unblock".localized, role: .destructive) {
                    unblockContact()
                }
            } message: {
                Text(String(format: "chat.unblock_message".localized, currentAlias))
            }
            .alert("chat.reset_session_title".localized, isPresented: $showingResetSessionAlert) {
                Button("button_cancel".localized, role: .cancel) { }
                Button("chat.reset_session_confirm".localized, role: .destructive) {
                    resetSession()
                }
            } message: {
                Text("chat.reset_session_message".localized)
            }
            .onAppear {
                aliasText = aliasEditorSeed
            }
    }
}

// MARK: - Chat Sheets Modifier

struct ChatSheetsModifier: ViewModifier {
    @Binding var mediaPickerMode: MediaPickerMode?
    @Binding var showAudioPreview: Bool
    @Binding var showingBlockSheet: Bool
    @Binding var showingReportSheet: Bool      // 🚩 guideline 1.2
    @Binding var showDocumentPicker: Bool      // ✅ NEW
    @Binding var showGifPicker: Bool           // 🎬 GIFs & autocollants
    @Binding var showContactPicker: Bool       // ✅ NEW
    @Binding var showWallpaperPicker: Bool     // ✅ Wallpaper picker
    @Binding var showContactPhotoPicker: Bool  // ✅ Contact photo picker
    // __PHOTO_MARKUP_2026_11_08__ owned by ChatView, drawn here
    @Binding var pendingMarkupImage: MarkupSubject?
    @Binding var pendingMarkupAttachment: MediaManager.MediaAttachment?
    let sendMediaWithCaption: (MediaManager.MediaAttachment, String) -> Void
    let recordedAudio: AudioRecorderManager.AudioAttachment?
    let conversationPublicKey: String
    let currentAlias: String
    let sendMediaMessage: (MediaManager.MediaAttachment) -> Void
    let sendMultipleMediaMessages: ([MediaManager.MediaAttachment]) -> Void
    let sendAudioMessage: (AudioRecorderManager.AudioAttachment, Bool) -> Void  // (audio, viewOnce)
    let cancelAudio: () -> Void
    
    @StateObject private var mediaManager = MediaManager()
    
    func body(content: Content) -> some View {
        content
            // Le mode arrive AVEC la présentation, pas à côté d'elle.
            .sheet(item: $mediaPickerMode) { mode in
                MediaPickerView(
                    allowMultipleSelection: true,
                    autoOpenLibrary: mode == .library,
                    onMediaSelected: sendMediaMessage,
                    onMultipleMediaSelected: sendMultipleMediaMessages
                )
            }
            // __PHOTO_MARKUP_2026_11_08__ `item:` rather than `isPresented:` —
            // the sheet cannot exist without an image, and binding it to the
            // optional makes that unrepresentable instead of a guard.
            .sheet(item: $pendingMarkupImage) { subject in
                PhotoMarkupView(
                    image: subject.image,
                    onCancel: {
                        pendingMarkupImage = nil
                        pendingMarkupAttachment = nil
                    },
                    onSend: { data in
                        // `MediaAttachment.data` is a `let`, so the edited photo
                        // becomes a NEW attachment carrying the same metadata.
                        // `fileSize` is recomputed: shipping the pre-markup size
                        // would mis-describe the bytes actually sent.
                        if let a = pendingMarkupAttachment {
                            var edited = MediaManager.MediaAttachment(
                                id: a.id,
                                type: a.type,
                                data: data,
                                thumbnailData: a.thumbnailData,
                                fileName: a.fileName,
                                fileSize: data.count,
                                timestamp: a.timestamp,
                                originalSize: a.originalSize,
                                compressionRatio: a.compressionRatio,
                                isCompressed: a.isCompressed,
                                isViewOnce: a.isViewOnce
                            )
                            // Not an init parameter, and it must survive: a
                            // drawn-on photo that silently loses its reveal
                            // duration would be shown in full immediately.
                            edited.revealDuration = a.revealDuration
                            pendingMarkupImage = nil
                            pendingMarkupAttachment = nil
                            sendMediaWithCaption(edited, "")
                        }
                    }
                )
            }
            .sheet(isPresented: $showDocumentPicker) {
                DocumentPicker { url in
                    handleDocument(url)
                }
            }
            .sheet(isPresented: $showGifPicker) {
                StickerGifPicker { attachment in
                    sendMediaMessage(attachment)
                }
            }
            .sheet(isPresented: $showContactPicker) {
                ContactSelectorView { publicKey, alias in
                    handleContact(publicKey: publicKey, alias: alias)
                }
            }
            .sheet(isPresented: $showAudioPreview) {
                if let audio = recordedAudio {
                    AudioPreviewSheet(
                        audioAttachment: audio,
                        onSend: { sendAudioMessage($0, false) },
                        onCancel: cancelAudio,
                        onSendViewOnce: { sendAudioMessage($0, true) }
                    )
                }
            }
            .sheet(isPresented: $showingBlockSheet) {
                BlockContactSheet(
                    contactPublicKey: conversationPublicKey,
                    contactDisplayName: currentAlias,
                    onBlock: { }
                )
            }
            // 🚩 Report contact (App Store guideline 1.2). Reporting offers to
            // block in the same flow; the block itself stays owned by
            // BlockedContactsManager.
            .sheet(isPresented: $showingReportSheet) {
                ReportSheet(
                    subject: .contact,
                    subjectId: conversationPublicKey,
                    subjectName: currentAlias,
                    onReported: { alsoBlock in
                        if alsoBlock {
                            BlockedContactsManager.shared.blockContact(
                                publicKey: conversationPublicKey,
                                displayName: currentAlias,
                                reason: nil
                            )
                        }
                    }
                )
            }
            // ✅ Wallpaper picker (WhatsApp style)
            .sheet(isPresented: $showWallpaperPicker) {
                WallpaperPickerView(conversationId: conversationPublicKey)
            }
            // ✅ Contact photo picker
            .sheet(isPresented: $showContactPhotoPicker) {
                ContactPhotoPickerView(publicKey: conversationPublicKey)
            }
    }
    
    private func handleDocument(_ url: URL) {
        Task {
            do {
                let attachment = try await mediaManager.processDocument(from: url)
                await MainActor.run {
                    sendMediaMessage(attachment)
                }
            } catch {
                OshiLog.messaging.info("❌ Document error: \(error)")
            }
        }
    }
    
    private func handleContact(publicKey: String, alias: String?) {
        Task {
            do {
                let attachment = try await mediaManager.createContactCard(publicKey: publicKey, alias: alias)
                await MainActor.run {
                    sendMediaMessage(attachment)
                }
            } catch {
                OshiLog.messaging.info("❌ Contact error: \(error)")
            }
        }
    }
}

// MARK: - Upload Progress Overlay

// MARK: - Upload Progress Overlay (Minimalist Design)
// Small indicator in corner - doesn't block user interaction

struct UploadProgressOverlay: View {
    let progress: Double
    let status: MessageManager.UploadStatus?
    
    var body: some View {
        VStack {
            Spacer()
            HStack {
                Spacer()
                
                // ✅ Minimalist: Small circular progress in corner
                ZStack {
                    // Background blur circle
                    Circle()
                        .fill(.ultraThinMaterial)
                        .oledGlassEdge(Circle())  // __OLED_GLASS_2026_11_16__
                        .frame(width: 36, height: 36)
                    
                    // Background track
                    Circle()
                        .stroke(Color.white.opacity(0.3), lineWidth: 3)
                        .frame(width: 28, height: 28)
                    
                    // Progress arc
                    Circle()
                        .trim(from: 0, to: progress)
                        .stroke(Color.white, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                        .frame(width: 28, height: 28)
                        .rotationEffect(.degrees(-90))
                        .animation(.easeInOut(duration: 0.2), value: progress)
                    
                    // Icon or percentage
                    if progress < 1.0 {
                        if progress < 0.1 {
                            // Show preparing icon
                            Image(systemName: "arrow.up.circle")
                                .font(.scaled(12, weight: .bold))
                                .foregroundColor(.white)
                        } else {
                            // Show percentage
                            Text("\(Int(progress * 100))")
                                .font(.scaled(10, weight: .bold))
                                .foregroundColor(.white)
                        }
                    } else {
                        // Complete checkmark
                        Image(systemName: "checkmark")
                            .font(.scaled(12, weight: .bold))
                            .foregroundColor(.green)
                    }
                }
                .shadow(color: .black.opacity(0.3), radius: 4)
                .padding(8)
            }
        }
    }
}

// MARK: - Alternative: Inline Progress Bar (even more minimal)
// Can be used at bottom of media bubble

struct InlineUploadProgress: View {
    let progress: Double
    let status: MessageManager.UploadStatus?
    
    var body: some View {
        HStack(spacing: 6) {
            // Thin progress bar
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 2)
                        .fill(Color.white.opacity(0.3))
                        .frame(height: 3)
                    
                    RoundedRectangle(cornerRadius: 2)
                        .fill(Color.white)
                        .frame(width: geo.size.width * progress, height: 3)
                        .animation(.easeInOut(duration: 0.2), value: progress)
                }
            }
            .frame(height: 3)
            
            // Small percentage
            Text("\(Int(progress * 100))%")
                .font(.scaled(10, weight: .medium))
                .foregroundColor(.white.opacity(0.8))
                .frame(width: 30, alignment: .trailing)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Color.black.opacity(0.4))
        .cornerRadius(8)
    }
}

// MARK: - Scheduled Call Bubble

struct ScheduledCallBubble: View {
    let message: SecureMessage
    let decryptedText: String
    let isFromCurrentUser: Bool
    
    @StateObject private var scheduledCallManager = ScheduledCallManager.shared
    @State private var event: ScheduledCallEvent?
    @State private var timeRemaining: String = ""
    @State private var timer: Timer?
    @State private var addedToCalendar = false
    @State private var showCalendarError = false
    @State private var calendarError = ""
    
    private var myPublicKey: String {
        UserDefaults.standard.string(forKey: "publicKey") ?? ""
    }
    
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let event = event {
                // Header with calendar icon
                HStack(spacing: 10) {
                    ZStack {
                        Circle()
                            .fill(LinearGradient(
                                colors: [.orange, .red],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            ))
                            .frame(width: 44, height: 44)
                        
                        Image(systemName: "calendar.badge.clock")
                            .font(.scaled(20))
                            .foregroundColor(.white)
                    }
                    
                    VStack(alignment: .leading, spacing: 2) {
                        Text(event.title ?? "event.call.scheduled".localized)
                            .font(.headline)
                            .foregroundColor(.primary)
                        
                        Text("event.created.by".localized + " " + event.createdByName)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    
                    Spacer()
                    
                    // Status badge
                    statusBadge(for: event)
                }
                
                Divider()
                
                // Date and time
                HStack(spacing: 16) {
                    VStack(alignment: .leading, spacing: 4) {
                        Label {
                            Text(formattedDate(event.scheduledTime))
                                .font(.subheadline)
                        } icon: {
                            Image(systemName: "calendar")
                                .foregroundColor(.orange)
                        }
                        
                        Label {
                            Text(formattedTime(event.scheduledTime))
                                .font(.subheadline.bold())
                        } icon: {
                            Image(systemName: "clock.fill")
                                .foregroundColor(appBrandColor)
                        }
                    }
                    
                    Spacer()
                    
                    // Duration if set
                    if let duration = event.duration {
                        VStack(alignment: .trailing, spacing: 2) {
                            Text(formatEventDuration(duration))
                                .font(.title3.bold())
                                .foregroundColor(.orange)
                            Text("event.duration".localized)
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                    }
                }
                
                // Countdown timer
                if event.status != .completed && event.status != .cancelled {
                    HStack {
                        Image(systemName: event.isNow ? "phone.fill" : "timer")
                            .foregroundColor(event.isNow ? .green : .orange)
                        
                        Text(timeRemaining)
                            .font(.subheadline.bold())
                            .foregroundColor(event.isNow ? .green : .primary)
                        
                        Spacer()
                    }
                    .padding(.vertical, 8)
                    .padding(.horizontal, 12)
                    .background(
                        RoundedRectangle(cornerRadius: 8)
                            .fill(event.isNow ? Color.green.opacity(0.15) : Color.orange.opacity(0.1))
                    )
                }
                
                // Action buttons (only for recipient, not creator)
                if !isFromCurrentUser && event.status == .pending {
                    let myResponse = event.responses[myPublicKey]
                    
                    if myResponse == nil {
                        HStack(spacing: 12) {
                            Button {
                                scheduledCallManager.respondToEvent(event.id, response: .accepted)
                                loadEvent()
                            } label: {
                                Label("event.accept".localized, systemImage: "checkmark.circle.fill")
                                    .font(.subheadline.bold())
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 10)
                                    .background(Color.green)
                                    .foregroundColor(.white)
                                    .cornerRadius(10)
                            }
                            
                            Button {
                                scheduledCallManager.respondToEvent(event.id, response: .declined)
                                loadEvent()
                            } label: {
                                Label("event.decline".localized, systemImage: "xmark.circle.fill")
                                    .font(.subheadline.bold())
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 10)
                                    .background(Color.red.opacity(0.15))
                                    .foregroundColor(.red)
                                    .cornerRadius(10)
                            }
                        }
                    } else {
                        HStack {
                            Image(systemName: myResponse == .accepted ? "checkmark.circle.fill" : "xmark.circle.fill")
                            Text(myResponse == .accepted ? "event.you.accepted".localized : "event.you.declined".localized)
                                .font(.subheadline)
                        }
                        .foregroundColor(myResponse == .accepted ? .green : .red)
                        .padding(.vertical, 6)
                    }
                }
                
                // Join call button (when it's time)
                if event.isNow && (event.status == .confirmed || event.status == .pending) {
                    Button {
                        // Join call action - will be handled by parent
                        NotificationCenter.default.post(
                            name: NSNotification.Name("JoinScheduledCall"),
                            object: nil,
                            userInfo: ["eventId": event.id.uuidString]
                        )
                    } label: {
                        Label("event.join.call".localized, systemImage: "phone.fill")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(
                                LinearGradient(
                                    colors: [.green, appBrandColor],
                                    startPoint: .leading,
                                    endPoint: .trailing
                                )
                            )
                            .foregroundColor(.white)
                            .cornerRadius(12)
                    }
                }
                
                // Add to Calendar button (show if event is in the future)
                if event.status != .completed && event.status != .cancelled && !event.isPast {
                    HStack(spacing: 12) {
                        // Add to Apple Calendar
                        Button(action: {
                            OshiLog.messaging.info("📅 Calendar button tapped!")
                            let generator = UIImpactFeedbackGenerator(style: .medium)
                            generator.impactOccurred()
                            
                            Task {
                                OshiLog.messaging.info("📅 Calling addToCalendar...")
                                let success = await CalendarExportHelper.shared.addToCalendar(event: event)
                                OshiLog.messaging.info("📅 addToCalendar result: \(success)")
                                await MainActor.run {
                                    if success {
                                        OshiLog.messaging.info("📅 Success! Updating UI")
                                        // Haptic feedback
                                        let generator = UINotificationFeedbackGenerator()
                                        generator.notificationOccurred(.success)
                                        
                                        addedToCalendar = true
                                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                                            addedToCalendar = false
                                        }
                                    } else {
                                        OshiLog.messaging.info("📅 Failed! Error: \(CalendarExportHelper.shared.errorMessage)")
                                        // Error haptic
                                        let generator = UINotificationFeedbackGenerator()
                                        generator.notificationOccurred(.error)
                                        
                                        // Show error alert
                                        calendarError = CalendarExportHelper.shared.errorMessage
                                        showCalendarError = true
                                    }
                                }
                            }
                        }) {
                            HStack {
                                Image(systemName: addedToCalendar ? "checkmark.circle.fill" : "calendar.badge.plus")
                                Text(addedToCalendar ? "calendar.added".localized : "calendar.add".localized)
                                    .font(.caption)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(addedToCalendar ? Color.green.opacity(0.2) : appBrandColor.opacity(0.15))
                            .foregroundColor(addedToCalendar ? .green : appBrandColor)
                            .cornerRadius(8)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(BorderlessButtonStyle())
                        .allowsHitTesting(true)
                        
                        // Share .ics file
                        Button(action: {
                            OshiLog.messaging.info("📅 Share ICS button tapped!")
                            let generator = UIImpactFeedbackGenerator(style: .medium)
                            generator.impactOccurred()
                            shareICSFile(for: event)
                        }) {
                            HStack {
                                Image(systemName: "square.and.arrow.up")
                                Text("calendar.share".localized)
                                    .font(.caption)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(Color.purple.opacity(0.15))
                            .foregroundColor(.purple)
                            .cornerRadius(8)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(BorderlessButtonStyle())
                        .allowsHitTesting(true)
                    }
                    .padding(.top, 8)
                }
            } else {
                // Loading or error state
                HStack {
                    ProgressView()
                    Text("chat.loading_event".localized)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color(.systemBackground))
                .shadow(color: .black.opacity(0.1), radius: 8, y: 4)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(
                    LinearGradient(
                        colors: [.orange.opacity(0.5), .red.opacity(0.3)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    lineWidth: 1.5
                )
        )
        .frame(maxWidth: 300)
        .onAppear {
            loadEvent()
            startTimer()
        }
        .onDisappear {
            timer?.invalidate()
        }
        .alert("calendar.error.title".localized, isPresented: $showCalendarError) {
            Button("calendar.open.privacy.settings".localized) {
                // Open OSHI's app settings directly - user can enable Calendar there
                if let settingsURL = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(settingsURL)
                }
            }
            Button("cancel".localized, role: .cancel) {}
        } message: {
            Text(calendarError)
        }
    }
    
    private func loadEvent() {
        // Parse event from message content
        let content = decryptedText
        if content.hasPrefix("📅SCHEDULED_CALL📅") {
            let jsonString = String(content.dropFirst("📅SCHEDULED_CALL📅".count))
            if let data = jsonString.data(using: .utf8),
               let parsed = try? JSONDecoder().decode(ScheduledCallEvent.self, from: data) {
                // Check if we have a newer version in the manager
                if let stored = scheduledCallManager.events.first(where: { $0.id == parsed.id }) {
                    event = stored
                } else {
                    event = parsed
                    // Store it
                    scheduledCallManager.handleReceivedEvent(data: data, from: message.senderPublicKey)
                }
            }
        }
    }
    
    private func startTimer() {
        updateTimeRemaining()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
            updateTimeRemaining()
        }
    }
    
    private func updateTimeRemaining() {
        guard let event = event else { return }
        
        let now = Date()
        let diff = event.scheduledTime.timeIntervalSince(now)
        
        if diff <= 0 {
            if diff > -3600 { // Within 1 hour after start
                timeRemaining = "🔴 " + "event.starting.soon".localized
            } else {
                timeRemaining = "event.status.completed".localized
            }
        } else if diff < 60 {
            timeRemaining = "⏰ " + String(format: "event.in.seconds".localized, Int(diff))
        } else if diff < 3600 {
            let minutes = Int(diff / 60)
            timeRemaining = "⏰ " + String(format: "event.in.minutes".localized, minutes)
        } else if diff < 86400 {
            let hours = Int(diff / 3600)
            timeRemaining = "📅 " + String(format: "event.in.hours".localized, hours)
        } else {
            let days = Int(diff / 86400)
            timeRemaining = "📅 " + String(format: "event.in.days".localized, days)
        }
    }
    
    // 🔧 PERF: `ScheduledCallBubble` is one of the inline bubble variants, so
    // both of these ran per visible bubble per re-render. Style-based
    // formatters are the locale-correct ones — keep the styles, just stop
    // rebuilding the formatter. See `OshiRowFormatters`.
    private func formattedDate(_ date: Date) -> String {
        return OshiRowFormatters.string(date, dateStyle: .medium, timeStyle: .none)
    }

    private func formattedTime(_ date: Date) -> String {
        return OshiRowFormatters.string(date, dateStyle: .none, timeStyle: .short)
    }
    
    private func shareICSFile(for event: ScheduledCallEvent) {
        guard let icsURL = CalendarExportHelper.shared.generateICSFile(for: event) else { return }
        
        let activityVC = UIActivityViewController(
            activityItems: [icsURL],
            applicationActivities: nil
        )
        
        // Present the share sheet
        if let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
           let rootVC = windowScene.windows.first?.rootViewController {
            // Handle iPad
            if let popover = activityVC.popoverPresentationController {
                popover.sourceView = rootVC.view
                popover.sourceRect = CGRect(x: rootVC.view.bounds.midX, y: rootVC.view.bounds.midY, width: 0, height: 0)
                popover.permittedArrowDirections = []
            }
            rootVC.present(activityVC, animated: true)
        }
    }
    
    @ViewBuilder
    private func statusBadge(for event: ScheduledCallEvent) -> some View {
        let (text, color): (String, Color) = {
            switch event.status {
            case .pending: return ("event.status.pending".localized, .orange)
            case .confirmed: return ("event.status.confirmed".localized, .green)
            case .declined: return ("event.status.declined".localized, .red)
            case .cancelled: return ("event.status.cancelled".localized, .gray)
            case .completed: return ("event.status.completed".localized, appBrandColor)
            }
        }()
        
        Text(text)
            .font(.caption.bold())
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(color.opacity(0.2))
            .foregroundColor(color)
            .cornerRadius(6)
    }
    
    /// Format event duration (in seconds) to human readable string
    private func formatEventDuration(_ seconds: TimeInterval) -> String {
        let totalMinutes = Int(seconds / 60)
        if totalMinutes < 60 {
            return "\(totalMinutes) min"
        } else {
            let hours = totalMinutes / 60
            let mins = totalMinutes % 60
            if mins == 0 {
                return "\(hours)h"
            }
            return "\(hours)h \(mins)m"
        }
    }
}

// MARK: - Call Summary Bubble

struct CallSummaryBubble: View {
    let content: String
    let isMissed: Bool
    let timestamp: Date
    
    // Parse and localize the content
    private var localizedContent: String {
        let text = content
        
        // Handle completed calls: "📞↗️call.outgoing|0:45" or "📞↙️call.incoming|1:23"
        if text.contains("|") {
            let parts = text.components(separatedBy: "|")
            if parts.count >= 2 {
                let prefix = parts[0]
                let duration = parts[1]
                
                // Extract icon and key
                let icons = ["📞↗️", "📞↙️", "❌"]
                var icon = ""
                var key = prefix
                
                for ic in icons {
                    if prefix.hasPrefix(ic) {
                        icon = ic
                        key = String(prefix.dropFirst(ic.count))
                        break
                    }
                }
                
                // Localize the key
                let localizedType = key.localized
                return "\(icon) \(localizedType) • \(duration)"
            }
        }
        
        // Handle missed/no answer calls: "📞↗️call.no.answer" or "❌call.missed"
        let icons = ["📞↗️", "📞↙️", "❌"]
        for icon in icons {
            if text.hasPrefix(icon) {
                let key = String(text.dropFirst(icon.count))
                // Only localize if it looks like a key (contains dots)
                if key.contains(".") {
                    return "\(icon) \(key.localized)"
                }
            }
        }
        
        // Fallback: return as-is (for old format messages)
        return text
    }
    
    var body: some View {
        HStack(spacing: 12) {
            // Phone icon
            ZStack {
                Circle()
                    .fill(isMissed ? Color.red.opacity(0.15) : Color.green.opacity(0.15))
                    .frame(width: 40, height: 40)
                
                Image(systemName: isMissed ? "phone.down.fill" : "phone.fill")
                    .font(.scaled(18))
                    .foregroundColor(isMissed ? .red : .green)
            }
            
            VStack(alignment: .leading, spacing: 4) {
                Text(localizedContent)
                    .font(.subheadline)
                    .foregroundColor(.primary)
                
                Text(formatTime(timestamp))
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
            
            Spacer()
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(.systemGray6))
        )
        .frame(maxWidth: 250)
    }
    
    /// 🔧 PERF: per visible call-summary bubble, per re-render. The original set
    /// only `timeStyle`, leaving `dateStyle` at its default — which for a fresh
    /// `DateFormatter` is `.none`, so passing `.none` explicitly here keeps the
    /// output identical.
    private func formatTime(_ date: Date) -> String {
        return OshiRowFormatters.string(date, dateStyle: .none, timeStyle: .short)
    }
}

