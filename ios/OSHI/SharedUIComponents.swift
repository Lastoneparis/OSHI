//
//  SharedUIComponents.swift
//  Common UI components shared across the app
//

import SwiftUI

// MARK: - Chat Bottom Anchoring (iOS 18 scroll-anchor roles)

/// Keeps a chat ScrollView reliably opened at (and pinned to) the newest
/// message at the bottom.
///
/// On iOS 18+ the role-based anchors make the SYSTEM hold the position:
/// `.initialOffset` opens at the bottom, `.sizeChanges` re-pins the bottom
/// while LazyVStack materializes rows and async bubble heights (images,
/// link previews) settle, and `.alignment` bottom-aligns short threads.
/// This closes the "open chat → blank/black until you swipe" race that
/// timed scrollTo() retries can miss: retries fire at fixed delays, but
/// row-height settling can finish after the last retry. The retries are
/// kept as an iOS 17 fallback and are harmless on 18+ (they scroll to
/// where the system already is).
struct ChatBottomAnchored: ViewModifier {

    /// Le lecteur est-il réellement collé au bas ? Piloté par la visibilité de
    /// l'ancre de fin de liste.
    ///
    /// 🔴 FIX (2026-08-21) — « QUAND JE LIS PLUS HAUT ET QU'UN MESSAGE ARRIVE,
    /// ÇA BOUGE ».
    ///
    /// `.defaultScrollAnchor(.bottom, for: .sizeChanges)` était appliqué en
    /// PERMANENCE. Ce rôle dit au système: « quand la taille du contenu change,
    /// conserve ma distance au BAS ». Collé au bas, c'est exactement ce qu'on
    /// veut — le nouveau message glisse en vue tout seul.
    ///
    /// Mais en train de lire trois écrans plus haut, c'est l'inverse: du contenu
    /// s'ajoute en dessous, le système maintient la distance au bas, et le texte
    /// qu'on était en train de lire se DÉPLACE sous les yeux. Le défaut n'était
    /// pas de mal viser, il était de viser tout le temps.
    ///
    /// En haut de fil, on ne pose donc AUCUN ancrage pour ce rôle: sans
    /// contrainte, SwiftUI garde le contenu visible immobile et le message
    /// arrive silencieusement en dessous — la flèche « descendre » étant là pour
    /// dire qu'il y a du neuf.
    ///
    /// `initialOffset` et `alignment` restent inconditionnels: ouvrir en bas et
    /// aligner un fil court en bas sont vrais dans les deux cas.
    var pinnedToBottom: Bool = true

    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content
                .defaultScrollAnchor(.bottom, for: .initialOffset)
                .defaultScrollAnchor(pinnedToBottom ? .bottom : nil, for: .sizeChanges)
                .defaultScrollAnchor(.bottom, for: .alignment)
        } else {
            content.defaultScrollAnchor(.bottom)
        }
    }
}

// MARK: - Composer draft  __COMPOSER_DRAFT_ISOLATED_2026_11_16__

/// The 1:1 composer's text, held by reference so that a keystroke re-renders the
/// views that show it (through `ComposerDraftReader`) and not the chat that owns
/// it. The chat keeps it in `@State` for identity only; `@State` does not
/// subscribe to an `ObservableObject`, which is the point.
final class ComposerDraft: ObservableObject {
    @Published var text = "" {
        // __FOLDABLE_LAYOUT_2026_11_16__ Mirrored (in memory) so the chat that
        // replaces this one on a fold / unfold starts with the same text.
        didSet { if let handoffKey, text != oldValue { ThreadHandoff.shared.setText(handoffKey, text) } }
    }
    /// Set by the owning chat once it knows its thread.
    var handoffKey: String?
}

/// Re-renders `content` when the draft changes — and only it.
struct ComposerDraftReader<Content: View>: View {
    @ObservedObject var draft: ComposerDraft
    @ViewBuilder let content: (Binding<String>) -> Content

    var body: some View { content($draft.text) }
}

// MARK: - Chat message stack

/// __GROUP_OPEN_ANCHOR_2026_09_23__ The group thread's row container.
///
/// Measured on iOS 26 with a cold 78-message group (window of 50 rows, date
/// headers, bot rows): the `LazyVStack` reported a content height flipping
/// between 1602 and 5421 pt within the same frame and drew NOTHING in the
/// viewport — bare wallpaper plus the down arrow, the owner's « on ne voit pas
/// les derniers messages, il faut scroller ». No scrollTo can aim at a target
/// whose extent is a guess that changes every pass. The same thread in an
/// eager `VStack` measured exactly (7233 pt, stable) and landed on the newest
/// message first time, late batches included.
///
/// The window is capped by `messageDisplayLimit` (50, +50 per "load earlier"),
/// so eager layout is bounded. iOS 17 keeps the lazy stack and its existing
/// verified-landing loops, since the geometry follower needs iOS 18 APIs.
struct ChatMessageStack<Content: View>: View {
    let spacing: CGFloat
    /// Force the lazy stack. Unused by the chat lists since search results are
    /// windowed too (__FILTERED_RESULTS_WINDOW_2026_11_16__); an unbounded list
    /// must not be laid out eagerly.
    var lazy: Bool = false
    /// __FOLDABLE_LAYOUT_2026_11_16__ Makes the rows scroll targets, so the
    /// thread can tell which one is at the top (`ThreadReadingPositionReporter`)
    /// and hand it to the view that replaces it on a fold / unfold. Changes no
    /// layout and no scrolling behaviour (no `scrollTargetBehavior` is set).
    var scrollTargets: Bool = false
    @ViewBuilder let content: () -> Content

    /// __DUO_POLISH_2026_11_18__ Widest a thread's message column gets. On a
    /// full-width iPad, an iPad/Mac window or an unfolded iPhone Duo on its
    /// side, an incoming and an outgoing bubble used to sit 900+ pt apart; past
    /// ~700 pt the eye loses the conversation. Narrower threads (every phone,
    /// the Duo's detail pane) are below the cap, so nothing moves there.
    static var readableColumnWidth: CGFloat { 720 }

    var body: some View {
        if lazy {
            LazyVStack(spacing: spacing, content: content)
        } else if #available(iOS 18.0, *) {
            if scrollTargets {
                VStack(spacing: spacing, content: content)
                    .scrollTargetLayout()
                    .frame(maxWidth: Self.readableColumnWidth)
                    .frame(maxWidth: .infinity)
            } else {
                VStack(spacing: spacing, content: content)
            }
        } else {
            LazyVStack(spacing: spacing, content: content)
        }
    }
}

// MARK: - Geometry-driven bottom follower (iOS 18+)

/// __GROUP_OPEN_ANCHOR_2026_09_23__ « J'OUVRE UN GROUPE À FROID, ON NE VOIT PAS
/// LES DERNIERS MESSAGES, IL FAUT SCROLLER ».
///
/// Every earlier fix aimed with `scrollTo` and judged success by the bottom
/// anchor's `onAppear`. Both are wrong on a cold `LazyVStack`: `scrollTo` aims
/// at an ESTIMATED extent, and the anchor can report "sighted" during the
/// estimate and never report the drift that follows. Measured on a cold group
/// (history + late batches): the viewport sat on bare wallpaper, off the end of
/// the content, with the down arrow showing — and nothing left to correct it,
/// because every loop had already stopped on a false "landed".
///
/// This follower reads the ground truth instead: `onScrollGeometryChange`
/// reports the real offset, content height and viewport on every layout. While
/// `following` is true and the user's finger is not on the list, any gap
/// between the viewport's bottom and the content's bottom — above it (new
/// batch, a bubble that grew, a banner that shrank the viewport) or below it
/// (estimate overshoot, "only wallpaper") — is closed on the next layout. It
/// stops by itself when there is nothing to close, so it never fights the
/// system anchor, and it cannot yank a reader: a drag that ends away from the
/// bottom turns `following` off; coming back to the bottom turns it on again.
struct ChatBottomFollower: ViewModifier {
    @Binding var following: Bool
    let proxy: ScrollViewProxy?
    let anchorID: String
    /// Ground-truth "the bottom is on screen" (iOS 18+), reported on change.
    /// With an eager `ChatMessageStack` the anchor's onAppear/onDisappear no
    /// longer track visibility, so the scroll-to-bottom arrow reads this.
    var onBottomChange: ((Bool) -> Void)? = nil

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.modifier(ChatBottomFollower18(following: $following, proxy: proxy, anchorID: anchorID,
                                                  onBottomChange: onBottomChange))
        } else {
            content
        }
    }
}

@available(iOS 18.0, *)
private struct ChatBottomFollower18: ViewModifier {
    @Binding var following: Bool
    let proxy: ScrollViewProxy?
    let anchorID: String
    var onBottomChange: ((Bool) -> Void)?
    @State private var userInControl = false
    @State private var lastGap: CGFloat = 0
    @State private var lastReportedAtBottom: Bool?

    /// Distance from the viewport's bottom to the content's bottom. Positive:
    /// newer content hidden below. Negative: scrolled past the end (wallpaper).
    /// `visibleRect` already accounts for the content insets (measured: a raw
    /// `contentOffset` formula read a steady 80 pt "gap" at the true bottom).
    private static func gap(_ g: ScrollGeometry) -> CGFloat {
        g.contentSize.height - g.visibleRect.maxY
    }

    /// The list's own bottom padding sits below the anchor, so "at the bottom"
    /// is within this slack, never exactly zero.
    private static let slack: CGFloat = 24

    func body(content: Content) -> some View {
        content
            .onScrollGeometryChange(for: CGFloat.self, of: { Self.gap($0) }) { _, gap in
                lastGap = gap
                let atBottom = gap <= Self.slack
                if atBottom != lastReportedAtBottom {
                    lastReportedAtBottom = atBottom
                    onBottomChange?(atBottom)
                }
                if userInControl {
                    // Reading history: stop following. Back at the bottom: resume.
                    if gap > Self.slack + 40 { following = false } else if atBottom { following = true }
                    return
                }
                guard following, gap > Self.slack || gap < -2 else { return }
                var t = Transaction(); t.disablesAnimations = true
                withTransaction(t) { proxy?.scrollTo(anchorID, anchor: .bottom) }
            }
            .onScrollPhaseChange { _, phase in
                switch phase {
                case .tracking, .interacting:
                    userInControl = true
                case .decelerating:
                    userInControl = true
                case .idle:
                    if userInControl { following = lastGap <= Self.slack }
                    userInControl = false
                case .animating:
                    break
                @unknown default:
                    break
                }
            }
    }
}

// MARK: - Single-Tap Settle Scroll

/// Reliably scrolls a LazyVStack-backed chat ScrollView to its true bottom in a
/// SINGLE user action.
///
/// Why a plain `scrollTo(anchor)` is not enough: in a `LazyVStack` the rows below
/// the current viewport are NOT materialized, so SwiftUI only knows an *estimated*
/// content extent. A single `scrollTo` therefore advances toward that estimate —
/// roughly one screenful — and stops. As the newly-revealed rows materialize the
/// real extent grows, so the user has to tap the scroll-to-bottom button two or
/// three times to actually reach the newest message.
///
/// `settleScrollToBottom` fixes this by re-issuing the scroll across the next few
/// runloop ticks. Each pass materializes the rows that became visible on the
/// previous pass and corrects the target, so the view lands at the genuine bottom
/// after the first tap. All passes share the same easing so it reads as one smooth
/// motion rather than several jumps.
///
/// - Parameters:
///   - proxy: the `ScrollViewProxy` to drive.
///   - anchorID: the id of the 1pt bottom anchor at the tail of the stack.
///   - animated: animate the first (immediate) pass. Pass `false` for the
///     instant landing used when a chat first opens.
/// __BOTTOM_LANDING_2026_09_12__ Atterrir en bas, en s'arrêtant sur la RÉUSSITE
/// et non sur un minuteur.
///
/// C'est le cœur commun de l'ouverture, de l'arrivée d'un message et de la
/// flèche. Les trois avaient leur propre file de `scrollTo` à délais fixes, et
/// les trois souffraient du même défaut: dans un `LazyVStack`, un `scrollTo` ne
/// progresse que vers l'étendue ESTIMÉE, parce que les lignes hors écran ne sont
/// pas encore matérialisées. Une passe avance d'un écran, pas jusqu'au bas.
///
/// Ce que fait celle-ci de différent:
///
/// 1. ELLE S'ARRÊTE QUAND ELLE A RÉUSSI. `isAtBottom` est la vérité de terrain
///    (l'ancre de fin de liste est visible). Dès qu'elle l'est, la boucle cesse.
///    C'est ce qui permet de l'utiliser sur iOS 18 SANS se battre contre
///    `.defaultScrollAnchor(.bottom, for: .sizeChanges)`: on ne tire plus une
///    seule fois après avoir atterri, donc on ne désengage plus l'ancrage
///    système. L'ancienne boucle tirait à heure fixe, atterrie ou pas.
///
/// 2. UNE SEULE ANIMATION. L'ancienne version lançait quatre animations qui se
///    chevauchaient (0,25 s puis 0,2 s à +0,05 / +0,15 / +0,30 s). Chacune
///    interrompait la précédente et repartait d'une position à mi-chemin vers
///    une cible encore estimée: d'où une flèche qui « ne réagit pas » et qu'il
///    faut taper plusieurs fois. Seule la première passe s'anime; les
///    corrections suivantes sont instantanées et invisibles.
///
/// 3. MÊME CHEMIN SUR TOUTES LES VERSIONS D'iOS. Plus de branche
///    `if #available(iOS 18)` qui ne fait rien: la condition d'arrêt rend la
///    boucle inoffensive là où le système sait déjà se tenir.
@MainActor
@discardableResult
func landAtBottom(_ proxy: ScrollViewProxy?,
                  to anchorID: String,
                  animateFirstPass: Bool = true,
                  isAtBottom: @escaping () -> Bool = { false },
                  timeout: TimeInterval = 2.5) -> Task<Void, Never>? {
    guard let proxy else { return nil }

    if animateFirstPass {
        withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo(anchorID, anchor: .bottom) }
    } else {
        withAnimation(nil) { proxy.scrollTo(anchorID, anchor: .bottom) }
    }

    return Task { @MainActor in
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            try? await Task.sleep(for: .milliseconds(80))
            if Task.isCancelled { return }
            // Réussite: on y est. On ne tire plus rien — c'est précisément ce
            // qui laisse l'ancrage système d'iOS 18 faire son travail ensuite.
            if isAtBottom() { return }
            // Correction silencieuse: sans animation, pour ne pas empiler des
            // transitions qui se coupent l'une l'autre.
            withAnimation(nil) { proxy.scrollTo(anchorID, anchor: .bottom) }
        }
    }
}

// MARK: - Landing after the user's OWN send  __LONG_SEND_LANDING_2026_09_28__

/// Reference box for scroll facts that must be readable from a landing loop
/// WITHOUT re-rendering the chat on every scroll frame (a `@State` write per
/// geometry change would re-evaluate the whole ChatView body while scrolling).
final class ChatScrollTracker {
    /// Distance from the viewport's bottom to the content's bottom (iOS 18+).
    /// nil = unknown (iOS 17, or no layout yet).
    var gap: CGFloat?
    /// Last time the reader's finger moved the list.
    var lastUserDragAt: Date?
    /// Last time the user tapped Send in this thread.
    var lastSendAt: Date?
    /// Height of the scroll viewport (iOS 18+), nil until the first layout.
    var viewportHeight: CGFloat?
    /// __UNREAD_LANDING_HOLD_2026_11_16__ Content height and top inset (iOS 18+):
    /// with `viewportHeight`, what tells a landing that the list has stopped
    /// changing shape under it. The scroll offset is deliberately not part of it.
    var contentHeight: CGFloat?
    var topInset: CGFloat?
    /// Where the 1:1 unread divider sits in the list's viewport, while it exists.
    var unreadDividerMinY: CGFloat?

    /// Changes whenever the list's shape does (not when it merely scrolls).
    var layoutSignature: [CGFloat] {
        [viewportHeight ?? -1, contentHeight ?? -1, topInset ?? -1]
    }

    /// __ANCHOR_SIGHTED_EARLY_2026_11_16__ The viewport's bottom is within
    /// ±24 pt of the content's bottom. Above: newer content still hidden.
    /// Below (negative): scrolled past the end onto bare wallpaper. Unknown
    /// geometry (iOS 17) does not veto.
    static func gapIsClosed(_ gap: CGFloat?) -> Bool {
        gap.map { abs($0) <= 24 } ?? true
    }
}

/// __CHAT_OPEN_PERF_2026_11_14__ A restartable one-shot timer held by reference,
/// for the same reason as `ChatScrollTracker`: restarting it from a per-frame
/// geometry callback must not write `@State` (which re-evaluates the whole chat
/// body every scroll frame).
@MainActor
final class DeferredAction {
    private var work: DispatchWorkItem?

    /// Cancel the pending action, if any, and schedule `action` after `delay`.
    func restart(after delay: TimeInterval = 1.5, _ action: @escaping () -> Void) {
        work?.cancel()
        let item = DispatchWorkItem(block: action)
        work = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }
}

/// Feeds `ChatScrollTracker.gap` from the real scroll geometry (iOS 18+).
struct ChatScrollGapReader: ViewModifier {
    let tracker: ChatScrollTracker

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.onScrollGeometryChange(for: CGFloat.self,
                                           of: { $0.contentSize.height - $0.visibleRect.maxY }) { _, gap in
                tracker.gap = gap
            }
            .onScrollGeometryChange(for: CGFloat.self, of: { $0.visibleRect.height }) { _, h in
                tracker.viewportHeight = h
            }
            .onScrollGeometryChange(for: CGFloat.self, of: { $0.contentSize.height }) { _, h in
                tracker.contentHeight = h
            }
            .onScrollGeometryChange(for: CGFloat.self, of: { $0.contentInsets.top }) { _, t in
                tracker.topInset = t
            }
        } else {
            content
        }
    }
}

/// « QUAND J'ENVOIE UN LONG MESSAGE, ÇA REMONTE ET IL FAUT REDESCENDRE ».
///
/// After a send, `landAtBottom` stopped at the FIRST tick where the bottom anchor
/// looked "sighted". That signal lies right after a send, in two measured ways
/// (harness `-OSHISendLong`, iOS 26 simulator):
///
/// 1. It is STALE. The anchor was on screen before the send, and the new row is
///    inserted asynchronously (MessageManager adds the pending bubble from a
///    detached task, after the composer has already collapsed). If the anchor's
///    `onDisappear` has not run by the first 80 ms check, the loop reads `true`
///    and quits after one ANIMATED pass aimed at the LazyVStack's ESTIMATED
///    extent — with a bubble taller than the screen that parks the view on the
///    top of the new message, down arrow showing.
/// 2. It is EARLY. A LazyVStack materializes the anchor before it is visible:
///    its `onAppear` fired with 124-154 pt of content still below the fold.
///
/// So after an own send this loop (a) keeps re-aiming for a minimum hold window
/// that covers the async insertion, the composer collapse and the tall row's
/// real height landing, and (b) only then accepts "at bottom" when the anchor is
/// sighted AND, where geometry is available, the real gap is closed — twice in a
/// row. A drag after the send stops it at once: it can never fight the reader.
@MainActor
@discardableResult
func landAtBottomAfterOwnSend(_ proxy: ScrollViewProxy?,
                              to anchorID: String,
                              tracker: ChatScrollTracker,
                              anchorSighted: @escaping () -> Bool,
                              holdFor: TimeInterval = 0.9,
                              timeout: TimeInterval = 3.0) -> Task<Void, Never>? {
    guard let proxy else { return nil }
    let start = Date()
    let userMoved: () -> Bool = {
        guard let drag = tracker.lastUserDragAt else { return false }
        return drag > start
    }
    withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo(anchorID, anchor: .bottom) }

    return Task { @MainActor in
        // Let the one animated pass play; corrections after it are silent.
        try? await Task.sleep(for: .milliseconds(280))
        var settledTicks = 0
        while Date().timeIntervalSince(start) < timeout {
            if Task.isCancelled || userMoved() { return }
            let geometryAtBottom = tracker.gap.map { $0 <= 24 } ?? true
            let atBottom = anchorSighted() && geometryAtBottom
            settledTicks = atBottom ? settledTicks + 1 : 0
            let holding = Date().timeIntervalSince(start) < holdFor
            if !holding && settledTicks >= 2 { return }
            // While holding, re-aim even if it LOOKS landed (the look is what lies);
            // at the true bottom a non-animated scrollTo is a no-op.
            if holding || !atBottom {
                withAnimation(nil) { proxy.scrollTo(anchorID, anchor: .bottom) }
            }
            try? await Task.sleep(for: .milliseconds(80))
        }
    }
}

/// Conservée pour les appelants existants. Délègue au noyau adaptatif.
@MainActor
func settleScrollToBottom(_ proxy: ScrollViewProxy?,
                          to anchorID: String,
                          animated: Bool = true,
                          isAtBottom: @escaping () -> Bool = { false }) {
    landAtBottom(proxy, to: anchorID, animateFirstPass: animated, isAtBottom: isAtBottom)
}

/// __OPEN_LANDS_SHORT_2026_08_22__ Ouvrir une conversation et devoir balayer vers
/// le bas pour voir le dernier message.
///
/// `settleScrollToBottom` s'arrête à 0,30 s, et l'ouverture ajoutait un tir
/// isolé à 0,60 s. Sur un fil chargé de PHOTOS, ça ne suffit pas: les images se
/// décodent de façon asynchrone, chaque bulle grandit quand la sienne arrive, et
/// le contenu continue de pousser bien après la dernière passe. On atterrit donc
/// à ce qui était le bas une demi-seconde plus tôt — c'est-à-dire un peu trop
/// haut. Exactement le fil de Nati, qui est plein de médias.
///
/// 🔴 ET ON NE PEUT PAS SE CONTENTER D'ALLONGER LA QUEUE. Une passe qui tombe
/// à 1,6 s alors que l'utilisateur a commencé à remonter le fil lui ARRACHE sa
/// lecture — c'est le défaut corrigé le 21/08, et le réintroduire serait pire
/// que le symptôme.
///
/// D'où `while:` — relu AVANT chaque passe. Dès que l'utilisateur a bougé, on
/// s'arrête net.
@MainActor
func settleScrollToBottomOnOpen(_ proxy: ScrollViewProxy?,
                                to anchorID: String,
                                while shouldContinue: @escaping () -> Bool) {
    // __BOTTOM_LANDING_2026_09_12__ Le prédicat était utilisé À L'ENVERS, et
    // c'est ce qui faisait atterrir une conversation au milieu du fil.
    //
    // Aux deux appels, `shouldContinue` vaut `{ !showScrollToBottom }`, c'est-à-dire
    // « l'ancre de fin de liste est visible » — donc « on est arrivé en bas ».
    // L'ancienne boucle s'en servait comme condition pour CONTINUER:
    //
    //     guard shouldContinue() else { return }
    //
    // Or `showScrollToBottom` passe à vrai sur le `onDisappear` de l'ancre, et
    // l'ancre quitte l'écran aussi bien parce que l'utilisateur a remonté le fil
    // QUE parce que le contenu a grandi sous nos pieds (images qui se décodent,
    // lignes qui se matérialisent). Le drapeau ne distingue pas les deux. Une
    // simple croissance de contenu suffisait donc à faire renoncer TOUTES les
    // passes restantes — et on restait planté là où le fil en était, c'est-à-dire
    // au-dessus des messages les plus récents.
    //
    // Le même prédicat est maintenant la condition d'ARRÊT, ce qu'il a toujours
    // voulu dire: on retire jusqu'à être en bas, puis on se tait.
    landAtBottom(proxy, to: anchorID, animateFirstPass: false, isAtBottom: shouldContinue)
}

/// Opening a conversation has two independent stop conditions: the reader reached the
/// bottom, or the reader deliberately started scrolling away.  The older three-argument
/// helper could express only one of them, so callers passed `userScrolledSinceOpen` into
/// the bottom predicate.  That made the correction loop continue for its whole timeout
/// even after a successful landing, creating needless `scrollTo` work during the first
/// 2.5 seconds of a chat.
@MainActor
func settleScrollToBottomOnOpen(_ proxy: ScrollViewProxy?,
                                to anchorID: String,
                                isAtBottom: @escaping () -> Bool,
                                shouldAbort: @escaping () -> Bool) {
    landAtBottom(proxy, to: anchorID, animateFirstPass: false) {
        isAtBottom() || shouldAbort()
    }
}

// MARK: - Scroll Offset Preference Key

struct ScrollOffsetPreferenceKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

// MARK: - Call control row metrics  __CALL_CONTROLS_FIT_2026_08_21__

/// How wide the buttons of a call-control row may be, given the space there is.
///
/// 🔴 WHY THIS EXISTS. Both call screens laid their control row out in fixed
/// numbers, and the numbers did not fit a phone:
///
///   • `VideoCallView`'s main row is FIVE `VideoControlButton`s at a hard
///     `frame(width: 55)` with `HStack(spacing: 25)`, inside a `.padding()`.
///     5×55 + 4×25 + 32 = **407 pt**. That is wider than an iPhone 16 Pro
///     (402 pt), an iPhone 15/16/17 (393 pt), a 13/14 (390 pt) and an SE or
///     13 mini (375 pt). It fitted from 414 pt up — the Plus and Max sizes,
///     which is the class this app is developed and tested on. So the two outer
///     buttons (mute, and switch-camera) have been running off the edge on every
///     iPhone that is not a Plus or a Max. The exact boundary is asserted in
///     `CallControlLayoutTests.testThePreviousFixedNumbersDidNotFit`.
///
///   • `VoiceCallView`'s row is bounded by `minWidth: 60` — a FLOOR, not a
///     width — and its caption sits under it unconstrained, so the button grows
///     to whatever the localized word needs. `minimumScaleFactor(0.8)` never
///     engages, because text only shrinks when something limits it. "Speaker" is
///     narrow; "Lautsprecher", "Haut-parleur", "Динамик" are not.
///
/// A row that overflows does not report an error, does not clip visibly on the
/// developer's own phone, and looks like a rendering quirk in a screenshot. So
/// the arithmetic lives here as a pure function over a width, and is asserted in
/// `CallControlLayoutTests` against the real width of every iPhone Apple sells.
///
/// Order of concession is deliberate: give up SPACING first (cosmetic), then
/// button size, and never go below 44 pt — Apple's minimum hit target, and a
/// call's mute button is the last control that should be hard to hit.
struct CallControlMetrics: Equatable {
    var button: CGFloat
    var spacing: CGFloat

    static let preferredButton: CGFloat = 55
    static let preferredSpacing: CGFloat = 25
    static let minimumButton: CGFloat = 44
    static let minimumSpacing: CGFloat = 8

    /// - Parameter availableWidth: width the row actually gets, i.e. AFTER the
    ///   surrounding horizontal padding has been taken out.
    static func fitting(availableWidth: CGFloat, count: Int) -> CallControlMetrics {
        guard count > 1 else {
            return CallControlMetrics(button: preferredButton, spacing: preferredSpacing)
        }
        let n = CGFloat(count)
        let gaps = n - 1

        // 1. Everything at its preferred size?
        if n * preferredButton + gaps * preferredSpacing <= availableWidth {
            return CallControlMetrics(button: preferredButton, spacing: preferredSpacing)
        }
        // 2. Keep the buttons, tighten the gaps.
        let spacingIfFullSize = (availableWidth - n * preferredButton) / gaps
        if spacingIfFullSize >= minimumSpacing {
            return CallControlMetrics(button: preferredButton,
                                      spacing: min(preferredSpacing, spacingIfFullSize.rounded(.down)))
        }
        // 3. Gaps are at the floor; shrink the buttons, but not past the hit target.
        let buttonAtMinSpacing = (availableWidth - gaps * minimumSpacing) / n
        return CallControlMetrics(button: max(minimumButton, min(preferredButton, buttonAtMinSpacing.rounded(.down))),
                                  spacing: minimumSpacing)
    }

    /// Total width this row occupies. Above `availableWidth` only when even the
    /// minimums do not fit, which no iPhone hits — the tests prove it.
    func totalWidth(count: Int) -> CGFloat {
        guard count > 0 else { return 0 }
        return CGFloat(count) * button + CGFloat(count - 1) * spacing
    }
}

/// __UNREAD_LANDING_HOLD_2026_11_16__ Reports the unread divider's position to
/// the open's landing loop through the reference box (no re-render per frame).
struct ChatUnreadDividerTracker: ViewModifier {
    let tracker: ChatScrollTracker

    func body(content: Content) -> some View {
        content
            .onGeometryChange(for: CGFloat.self, of: { $0.frame(in: .named("chatScroll")).minY }) { y in
                tracker.unreadDividerMinY = y
            }
            .onDisappear { tracker.unreadDividerMinY = nil }
    }
}

// MARK: - __COMPOSER_PILL_2026_11_23__ Google-style composer pill

/// The chat composer drawn like the Google app's search bar (user request,
/// 23/11; Android 99c1844d): ONE rounded pill holds "+", the text field and the
/// capture shortcuts, and the mic / send button is a separate circle of the
/// same height beside it.
///
/// The pill carries the fill and a hairline outline that turns brand-coloured
/// while the field has focus; the field inside has no fill or border of its
/// own. Under a glass theme the pill keeps the glass edge the field used to
/// carry. Shared by the 1:1 and group composers (and the Mac, which is the
/// Catalyst build of the same views).
enum ComposerPillMetrics {
    /// Pill height = round-button diameter, so the two line up. Scaled with
    /// Dynamic Type through `@ScaledMetric` at the call sites.
    static let height: CGFloat = 48
    static let outlineOpacity: Double = 0.45
}

/// The pill's fill: the field background the composer always used.
private struct ComposerPillFill: View {
    @Environment(\.colorScheme) private var colorScheme
    var body: some View {
        colorScheme == .dark ? Color(.systemGray6) : Color(.systemGray5)
    }
}

struct ComposerPill<Content: View>: View {
    let focused: Bool
    let tint: Color
    @ViewBuilder let content: () -> Content

    @ScaledMetric(relativeTo: .body) private var height: CGFloat = ComposerPillMetrics.height
    @Environment(\.glassStyle) private var glassStyle
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var shape: RoundedRectangle {
        RoundedRectangle(cornerRadius: height / 2, style: .continuous)
    }

    private var outline: Color {
        if focused { return tint }
        if glassStyle.isGlass { return .clear }  // the glass edge is the outline
        return Color(.systemGray).opacity(ComposerPillMetrics.outlineOpacity)
    }

    var body: some View {
        // Bottom-aligned: as the field grows to several lines, "+" and the
        // shortcuts stay on its last line, as in the Google bar.
        HStack(alignment: .bottom, spacing: 0) {
            content()
        }
        .padding(.horizontal, 4)
        .frame(minHeight: height)
        .background(ComposerPillFill().clipShape(shape))
        .oledGlassEdge(shape)  // __OLED_GLASS_2026_11_16__ moved from the field to the pill
        .overlay(shape.strokeBorder(outline, lineWidth: 1).allowsHitTesting(false))
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: focused)
    }
}

/// The round button beside the pill. `primary` = the brand-filled send;
/// otherwise the quiet tonal mic circle with a hairline outline. `recording`
/// turns the circle red (the mic doubles as stop while a voice note records).
struct ComposerRoundButtonFace: View {
    let systemImage: String
    let primary: Bool
    var recording: Bool = false
    var tint: Color = Theme.brand
    var enabled: Bool = true

    @ScaledMetric(relativeTo: .body) private var size: CGFloat = ComposerPillMetrics.height
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.glassStyle) private var glassStyle

    private var filled: Bool { primary || recording }

    var body: some View {
        ZStack {
            if filled {
                Circle()
                    .fill(recording ? Color.red : (enabled ? tint : Color.gray))
                    .oledFocusGlow(tint)  // __OLED_GLASS_2026_11_16__ the one lit thing in the bar
            } else {
                ComposerPillFill()
                    .clipShape(Circle())
                    .oledGlassEdge(Circle())
                    .overlay(
                        Circle()
                            .strokeBorder(glassStyle.isGlass ? Color.clear
                                          : Color(.systemGray).opacity(ComposerPillMetrics.outlineOpacity),
                                          lineWidth: 1)
                    )
            }
            Image(systemName: systemImage)
                .font(.system(size: size * 0.42, weight: primary ? .bold : .medium))
                .foregroundStyle(filled ? Color.white : (enabled ? Color.primary : Color.gray))
                .contentTransition(.opacity)
        }
        .frame(width: size, height: size)
        .contentShape(Circle())
    }
}
