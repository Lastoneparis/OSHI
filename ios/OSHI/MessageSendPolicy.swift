//
//  MessageSendPolicy.swift
//  OSHI
//
//  Two rules that apply to every user-composed message, and nothing else:
//
//    1. A text too long for one envelope is SPLIT into several messages
//       (Telegram behaviour) instead of being rejected by the radio or
//       413'd by the relay. Splitting happens on a sensible boundary and
//       NEVER inside a grapheme cluster.
//    2. At most 100 messages per minute leave this device. Tripping the
//       limit produces a localized, visible refusal — never a silent drop.
//
//  Both live here, away from the send pipelines, because they are pure
//  decisions: no I/O, no crypto, no UI. That is what makes them testable, and
//  a splitter that is not tested is a splitter that corrupts emoji.
//
//  WHERE THE LENGTH LIMIT COMES FROM  (this is the part not to invent)
//  ------------------------------------------------------------------
//  OSHI has two transports with a real ceiling:
//
//    * LoRa (Meshtastic).  `MeshtasticManager.sendSecureMessage` chunks the
//      JSON-encoded `SecureMessage.meshWireSafe()` at 180 bytes/frame and
//      REFUSES anything over 12 frames — 12 × 180 = 2160 bytes on the wire
//      (MeshtasticManager.swift, `loRaChunk` and the `chunks.count <= 12`
//      guard). That budget is spent on the envelope, not on the text: a
//      stripped SecureMessage costs ~1114 bytes empty, and every plaintext
//      byte costs ~1.83 wire bytes once it has been through AES-GCM →
//      DoubleRatchetMessage JSON → AES-GCM → base64 → JSON. Measured, not
//      estimated: 572 UTF-8 bytes of text is the exact ceiling that still
//      fits in 2160. `loRaSafeUTF8Bytes` is 500, leaving ~130 bytes of
//      headroom for the fields that vary in length (message id, the
//      `senderAddress` that is sometimes a 44-char key and sometimes a short
//      identity address, `replyToId`). `MessageSendPolicyTests` re-measures
//      this and fails if the envelope grows.
//
//    * The V2 relay. `ServerVPS/v2/relay_v2.js` caps a single envelope's
//      base64 ciphertext at 256 KB (`MAX_CIPHERTEXT_BYTES`) → about 105 KB
//      of plaintext after the same 1.83× inflation. Three orders of
//      magnitude above anything a person types, so it does not bind.
//
//  So exactly ONE transport constrains typed text, and only when the radio is
//  the path the message will actually take. `standardUTF8Bytes` is therefore
//  INVENTED. No OSHI transport requires it; it only exists so the relay's
//  256 KB ceiling can never be reached by typing.
//
//  __LONG_TEXT_ONE_MESSAGE_2026_09_28__ Owner report: a ~2,000-character text
//  pasted into a 1:1 chat went out as FIVE messages, its paragraphs scattered
//  across bubbles. Cause: the 500-byte LoRa budget was applied whenever a radio
//  had EVER been paired (`isRadioPaired` = a UserDefaults key), i.e. to every
//  message of a radio owner, although LoRa is a fallback that carries a message
//  only off-grid or to an unreachable peer — the V2 relay carried all five
//  parts. Two changes:
//    * the LoRa budget now applies only when the phone has NO physical network
//      path and a radio is paired (`loRaBudgetApplies`) — the one situation in
//      which LoRa is the route. Online, a long text rides the relay in one piece;
//      if the peer looks unreachable, `deliverViaLoRa` refuses the oversize
//      envelope (`.tooLarge`) and the relay's store-and-forward carries it.
//    * `standardUTF8Bytes` 4000 → 16000 (≈ 8,000 accented-Latin characters,
//      16,000 ASCII, 5,300 CJK, 4,000 four-byte emoji). Wire cost ≈ 1.83× ⇒
//      ~30 KB of base64 ciphertext, well under the relay's 256 KB cap and the
//      sync store's 256 KB per item. Receivers (iOS, Android, Desktop, all
//      shipped versions) have no length cap on inbound text — only the SENDING
//      side ever limited it — so older clients display it unchanged.
//

import Foundation

// MARK: - Reply suggestions

/// __SUGGESTIONS_ARE_A_CHOICE_2026_10_27__ Whether the reply suggestions above
/// the composer are offered at all. Reporter's N1.
///
/// The key lives here, next to the other send-side policy, rather than as a
/// string literal in both `ChatView` and `SettingsView`: a defaults key spelled
/// in two places is a switch that silently stops working the first time one of
/// them is edited. That is exactly how `profile_photo_visible` became an orphan
/// key with a UI and no reader.
///
/// Views read it through `@AppStorage(SmartReplyPreference.enabledKey)`;
/// `isEnabled` is for non-view code.
enum SmartReplyPreference {
    static let enabledKey = "oshi.smartReplies.enabled"

    /// Defaults to TRUE: absent means "never chosen", and the feature shipped on.
    static var isEnabled: Bool { isEnabled(storedValue: UserDefaults.standard.object(forKey: enabledKey) as? Bool) }

    /// Pure, so the absent-means-on rule is testable without UserDefaults.
    static func isEnabled(storedValue: Bool?) -> Bool { storedValue ?? true }
}

// MARK: - Splitting

enum MessageTextSplitter {

    /// Largest text, in UTF-8 bytes, that survives the 12-frame LoRa cap.
    /// Derived above; pinned by test. Not a guess.
    static let loRaSafeUTF8Bytes = 500

    /// __LORA_LONG_TEXT_OMP_2026_11_18__ Largest text when the paired radio runs OSHI Mesh firmware
    /// (it answered the OMP probe). That radio takes the WHOLE envelope (`sendViaOMP`) and fragments it
    /// itself: 64 x 182 = 11,648 B of OMP body, not 12 x 180. Measured (Android LoRaLongTextTest, same
    /// layering): wire ≈ 1,350 + 2.38 × bytes — the 1.83× above under-counts one base64 layer — so
    /// 4,000 B is 10,861 B raw (fits even uncompressed) and ~8 KB / ~44 frames once deflated. Bench: 2,000 B and 3,800 B both ways on two T1000-E. Android
    /// `MessageSendPolicy.LORA_OMP_UTF8_BYTES`.
    static let loRaOmpUTF8Bytes = 4_000

    /// Limit whenever LoRa is not the route. INVENTED — see the file header
    /// (`__LONG_TEXT_ONE_MESSAGE_2026_09_28__`, was 4000). Nothing in the
    /// transport stack requires this number; the relay's own cap is ~140 KB of
    /// plaintext.
    static let standardUTF8Bytes = 16_000

    /// A paste past this many parts is refused rather than turned into a
    /// message storm. 50 × 16 KB ≈ 800 KB of text; nobody types that. The
    /// off-grid LoRa budget keeps its own 50 × 500 B ceiling.
    static let maxParts = 50

    /// A boundary is only "good enough" if it fills at least this much of the
    /// budget. Without it, a paragraph break in the first 20 characters would
    /// make every part 20 characters long.
    private static let minimumFill = 0.5

    /// Byte budget for the transport that is actually reachable.
    ///
    /// `loRaPaired` is injected rather than read from `MeshtasticManager` so
    /// the decision is testable without a radio. Call sites pass
    /// `MeshCoreManager.anyLoRaRadioPaired` (a Meshtastic or a MeshCore radio).
    static func maxUTF8Bytes(loRaPaired: Bool) -> Int {
        loRaPaired ? loRaSafeUTF8Bytes : standardUTF8Bytes
    }

    /// True only when LoRa is a route this envelope will actually be offered
    /// to — which is what decides whether the text has to fit 12 frames.
    ///
    /// `radioPaired` alone (what the call sites used to pass) held EVERY
    /// message of a radio owner to 500 bytes, online or not, so a 2,000-char
    /// paste left as five messages over the relay
    /// (`__LONG_TEXT_ONE_MESSAGE_2026_09_28__`). Pure; call sites pass
    /// `MeshtasticManager.isRadioPaired`,
    /// `NetworkReachabilityManager.shared.hasPhysicalPath`,
    /// `TransportPolicy.internetAllowedForSending` and
    /// `TransportPolicy.preferRadioOverRelay`.
    ///
    /// __LORA_BUDGET_FOLLOWS_THE_ROUTE_2026_10_27__ Owner report from an
    /// iPhone 17 paired to a ThinkNode M1: the screen said "4298 octets —
    /// message trop long pour LoRa" and nothing went out by radio. Measured
    /// cause: this predicate asked "is the phone off-grid?" while
    /// `TransportPolicy.order(_:)` asks "did the user put the radio first?".
    /// With Wi-Fi up and the radio-first toggle ON, the splitter handed out
    /// the 16,000-byte relay budget, and `deliverViaLoRa` then refused the
    /// resulting 4,298-byte envelope against its 12 × 180 = 2,160-byte
    /// ceiling. The one transport the user had explicitly chosen was the one
    /// transport the message could not use, every single time.
    ///
    /// The budget now follows the ROUTE, not the weather: LoRa binds when it
    /// is the only way out (no link underneath, or internet sending switched
    /// off app-wide) and when the user asked for it ahead of the relay.
    ///
    /// Deliberately NOT included: `peerLikelyOnline`. `order(_:)` also puts
    /// the radio first for a peer who looks offline, but that flag is true
    /// "whenever we do not know", and the relay still sits behind the radio to
    /// carry the long version. Binding the budget to a guess would reinstate
    /// the 2026-09-28 regression across most online sends.
    static func loRaBudgetApplies(radioPaired: Bool,
                                  hasPhysicalPath: Bool,
                                  internetAllowedForSending: Bool,
                                  prefersRadioOverRelay: Bool) -> Bool {
        guard radioPaired else { return false }
        // LoRa is the only way out.
        if !hasPhysicalPath || !internetAllowedForSending { return true }
        // A path exists and may be used: LoRa binds only because the user
        // asked for it first.
        return prefersRadioOverRelay
    }

    /// Byte budget for a 1:1 send right now. See `loRaBudgetApplies`.
    static func maxUTF8Bytes(radioPaired: Bool,
                             hasPhysicalPath: Bool,
                             internetAllowedForSending: Bool,
                             prefersRadioOverRelay: Bool,
                             oshiFirmware: Bool = false) -> Int {
        let applies = loRaBudgetApplies(
            radioPaired: radioPaired,
            hasPhysicalPath: hasPhysicalPath,
            internetAllowedForSending: internetAllowedForSending,
            prefersRadioOverRelay: prefersRadioOverRelay)
        // __LORA_LONG_TEXT_OMP_2026_11_18__ Stock or unknown firmware keeps the 12-frame budget.
        if applies && oshiFirmware { return loRaOmpUTF8Bytes }
        return maxUTF8Bytes(loRaPaired: applies)
    }

    // MARK: Line breaks

    /// Line/paragraph separators other than LF that pasted text can carry:
    /// CR (classic Mac / Word paragraphs), VT (Word's manual line break),
    /// NEL, U+2028 LINE SEPARATOR and U+2029 PARAGRAPH SEPARATOR (Pages, some
    /// web and PDF sources). SwiftUI draws them all as breaks, so the SENDER
    /// sees a well-laid-out bubble — but Android's text layout breaks
    /// paragraphs on LF only, so the recipient got one run-on block.
    private static let foreignLineBreaks: Set<Unicode.Scalar> =
        ["\r", "\u{0B}", "\u{85}", "\u{2028}", "\u{2029}"]

    /// Every line break becomes a single LF; CRLF collapses to one LF. Nothing
    /// else changes (no trimming, blank lines kept) — the layout the user
    /// pasted is exactly what the recipient sees, on every platform.
    static func normalizeLineBreaks(_ text: String) -> String {
        guard text.unicodeScalars.contains(where: { foreignLineBreaks.contains($0) }) else {
            return text
        }
        var out = String.UnicodeScalarView()
        var previousWasCR = false
        for scalar in text.unicodeScalars {
            if scalar == "\n", previousWasCR { previousWasCR = false; continue }
            previousWasCR = scalar == "\r"
            out.append(foreignLineBreaks.contains(scalar) ? "\n" : scalar)
        }
        return String(out)
    }

    /// `content` with its line breaks normalised, for user prose only.
    ///
    /// Control payloads pass through untouched. Reply / forward envelopes are
    /// decoded and only their BODY is normalised, then re-wrapped: the
    /// envelope is JSON, and `JSONEncoder` writes U+2028/U+2029 raw, so a blind
    /// replace on the wire string would put a raw LF inside a JSON string
    /// literal — invalid JSON the receiver could no longer parse.
    static func normalizedForSend(_ content: String) -> String {
        guard isUserProse(content) else { return content }
        if content.hasPrefix(replyPrefix) {
            guard let wrapped = MessageWithReply.fromMessageString(content),
                  let reply = wrapped.replyTo else { return content }
            let body = normalizeLineBreaks(wrapped.content)
            return body == wrapped.content
                ? content
                : MessageWithReply(content: body, replyTo: reply).toMessageString()
        }
        if content.hasPrefix(forwardPrefix) {
            guard let fwd = ForwardedMessage.fromMessageString(content) else { return content }
            let body = normalizeLineBreaks(fwd.content)
            return body == fwd.content
                ? content
                : ForwardedMessage(originalSenderName: fwd.originalSenderName,
                                   content: body,
                                   isForwarded: fwd.isForwarded,
                                   forwardCount: fwd.forwardCount).toMessageString()
        }
        return normalizeLineBreaks(content)
    }

    // MARK: Wire-format guards

    /// Control payloads that ride the same `sendMessage` as user text. Cutting
    /// one of these in half does not produce two receipts — it produces two
    /// messages neither side can parse, which is how a "harmless" splitter
    /// silently breaks read receipts and live location.
    ///
    /// This list was taken from the RECEIVE side (every `hasPrefix`/`starts(with:)`
    /// in MessageManager, MessageManager+V2 and GroupMessaging) rather than
    /// written from memory, because a marker missing here is a control message
    /// that gets chopped in half. The `CHECK_IN`/`CHECKIN` pair and the two
    /// `REACTION` spellings are both real: iOS and Android disagree, and both
    /// forms are on the wire today.
    ///
    /// 🔧 2026-08-11: this WAS a 22-entry literal — the most complete of the six
    /// divergent copies, and still not the same list as any of the other five.
    /// It now derives from `OSHIControlPayload.catalog`, which is the only place
    /// a prefix may be declared. Reply and forward envelopes are filtered out
    /// because `splitForSend` deliberately DOES cut them: it splits the body
    /// inside the wrapper and re-wraps each part.
    static let controlPrefixes: [String] =
        OSHIControlPayload.prefixes.filter { !OSHIControlPayload.isEnvelope($0) }

    static let replyPrefix = OSHIControlPayload.replyPrefix
    static let forwardPrefix = OSHIControlPayload.forwardPrefix

    /// True when `content` is ordinary user prose that may be cut up.
    ///
    /// Reply and forward wrappers are deliberately NOT excluded here: they are
    /// handled by `splitForSend`, which cuts the body inside the wrapper and
    /// re-wraps. Everything else that starts with a marker, and anything that
    /// looks like the media JSON the group path builds, is left alone.
    static func isUserProse(_ content: String) -> Bool {
        if content.isEmpty { return false }
        if controlPrefixes.contains(where: { content.hasPrefix($0) }) { return false }
        // Group media rides as a raw JSON object in `content`.
        let head = content.drop(while: { $0 == " " || $0 == "\n" || $0 == "\t" }).first
        if head == "{" || head == "[" { return false }
        return true
    }

    // MARK: Outcome

    enum SplitOutcome: Equatable {
        /// Fits as-is. Send it unchanged — no behaviour change at all.
        case single
        /// Send these, in this order, as separate messages.
        case parts([String])
        /// Too long even split. The caller MUST tell the user.
        case tooLong(partsNeeded: Int)
    }

    /// The entry point the send pipelines call.
    ///
    /// `content` is the WIRE string — it may already be wrapped in a reply or
    /// forward envelope, because that wrapping happens in the composer before
    /// the manager is reached.
    static func splitForSend(_ content: String, maxUTF8Bytes limit: Int) -> SplitOutcome {
        // __CASHU_SEND_READY_2026_11_09__ A Cashu token is never split. Cut in
        // two it is two strings no wallet will take, the receiver sees no card,
        // and the sender saw "sent" — money thrown away in silence. A V3 token
        // of two proofs is ~620 B, over the 500 B LoRa budget, so this was a
        // real path off-grid. Refuse instead; the composer says why.
        if content.utf8.count > limit,
           content.range(of: "cashu", options: .caseInsensitive) != nil,
           CashuTokens.containsToken(content) {
            return .tooLong(partsNeeded: 2)
        }

        // Reply: quote the first part only (Telegram does the same — repeating
        // the quote on every fragment is noise), the rest go out as plain text.
        if content.hasPrefix(replyPrefix),
           let wrapped = MessageWithReply.fromMessageString(content),
           let reply = wrapped.replyTo {
            return splitWrapped(body: wrapped.content, maxUTF8Bytes: limit) { index, body in
                index == 0
                    ? MessageWithReply(content: body, replyTo: reply).toMessageString()
                    : body
            }
        }

        // Forward: every part keeps the "forwarded" attribution. Dropping it
        // from parts 2…n would relabel someone else's words as the sender's.
        if content.hasPrefix(forwardPrefix),
           let fwd = ForwardedMessage.fromMessageString(content) {
            return splitWrapped(body: fwd.content, maxUTF8Bytes: limit) { _, body in
                ForwardedMessage(originalSenderName: fwd.originalSenderName,
                                 content: body,
                                 isForwarded: fwd.isForwarded,
                                 forwardCount: fwd.forwardCount).toMessageString()
            }
        }

        guard isUserProse(content) else { return .single }
        return splitPlain(content, maxUTF8Bytes: limit)
    }

    /// Split `body`, then re-apply `wrap` to each piece, and verify that the
    /// WRAPPED result still fits.
    ///
    /// The verification is the point. Both wrappers are JSON, so the body is
    /// re-escaped when it goes back in: a newline becomes `\n` (two bytes for
    /// one), a quote becomes `\"`, and a control character becomes a six-byte
    /// `\u00XX`. Budgeting by "limit minus the cost of an empty wrapper" is
    /// therefore an UNDER-estimate for exactly the messages people actually
    /// paste — multi-line ones — and the wrapped part would silently come out
    /// over the transport's ceiling. So we measure the real thing and shrink
    /// the body budget until it fits, rather than trusting the arithmetic.
    private static func splitWrapped(body: String,
                                     maxUTF8Bytes limit: Int,
                                     wrap: (Int, String) -> String) -> SplitOutcome {
        let emptyWrapperCost = wrap(0, "").utf8.count
        var bodyLimit = max(32, limit - emptyWrapperCost)

        // Each pass shrinks the budget by the worst observed overflow. Escaping
        // can at most inflate a byte six-fold, so this converges immediately in
        // practice; the bound is here so a pathological input cannot spin.
        for _ in 0..<8 {
            guard bodyLimit > 16 else { break }
            let outcome = splitPlain(body, maxUTF8Bytes: bodyLimit)
            switch outcome {
            case .single:
                // The body fits unsplit — but the caller's ORIGINAL wrapped
                // string is what is being sent, and we only got here because it
                // was over the limit. Treat it as a one-part split so the
                // wrapper is rebuilt and measured like every other case.
                let single = wrap(0, body)
                if single.utf8.count <= limit { return .single }
                bodyLimit = max(16, bodyLimit - (single.utf8.count - limit))
                continue
            case .tooLong(let n):
                return .tooLong(partsNeeded: n)
            case .parts(let bodies):
                let wrapped = bodies.enumerated().map { wrap($0.offset, $0.element) }
                let worstOverflow = wrapped.map { $0.utf8.count - limit }.max() ?? 0
                if worstOverflow <= 0 { return .parts(wrapped) }
                bodyLimit = max(16, bodyLimit - worstOverflow)
            }
        }
        // Could not make the wrapper fit. Refusing with a message the user can
        // read beats emitting parts the radio will drop without telling anyone.
        return .tooLong(partsNeeded: maxParts + 1)
    }

    private static func splitPlain(_ text: String, maxUTF8Bytes limit: Int) -> SplitOutcome {
        guard text.utf8.count > limit else { return .single }
        let pieces = split(text, maxUTF8Bytes: limit)
        if pieces.count <= 1 { return .single }
        if pieces.count > maxParts { return .tooLong(partsNeeded: pieces.count) }
        return .parts(pieces)
    }

    // MARK: The algorithm

    /// Cut `text` into pieces each ≤ `limit` UTF-8 bytes.
    ///
    /// Boundary preference, best first: blank line (paragraph) → line break →
    /// whitespace (word) → grapheme cluster (mid-word, last resort). A boundary
    /// is only taken if it fills at least half the budget; otherwise the next,
    /// worse class is tried. If NO boundary of any class fills half the budget,
    /// the latest boundary of any class is used anyway — a bad boundary always
    /// beats cutting a word in half. Only a single unbroken run (one long URL,
    /// a CJK paragraph, base64) reaches the mid-word case.
    ///
    /// The cut index is always a `String.Index` obtained by walking
    /// `Character`s, which in Swift ARE extended grapheme clusters. That is the
    /// whole reason this function does not do byte arithmetic: a family emoji
    /// is 25 UTF-8 bytes and one Character, "é" written as e+U+0301 is three
    /// bytes and one Character, and cutting either by offset ships mojibake.
    static func split(_ text: String, maxUTF8Bytes limit: Int) -> [String] {
        guard limit > 0 else { return [text] }
        guard text.utf8.count > limit else { return [text] }

        var parts: [String] = []
        var cursor = text.startIndex

        while cursor < text.endIndex {
            let rest = text[cursor...]
            if rest.utf8.count <= limit {
                let tail = String(rest).trimmingCharacters(in: .whitespacesAndNewlines)
                if !tail.isEmpty { parts.append(tail) }
                break
            }

            // Furthest grapheme boundary whose prefix still fits the budget.
            var hardEnd = cursor
            var used = 0
            var idx = cursor
            while idx < text.endIndex {
                let cost = String(text[idx]).utf8.count
                if used + cost > limit { break }
                used += cost
                idx = text.index(after: idx)
                hardEnd = idx
            }
            // A single grapheme bigger than the whole budget (only reachable
            // with an absurdly small limit): emit it alone rather than spin.
            if hardEnd == cursor { hardEnd = text.index(after: cursor) }

            let cut = boundary(in: text, from: cursor, hardEnd: hardEnd, limit: limit)
            let piece = String(text[cursor..<cut]).trimmingCharacters(in: .whitespacesAndNewlines)
            if !piece.isEmpty { parts.append(piece) }

            // Drop the run of whitespace we broke on, so the next part does not
            // open with a stray newline. Never past `hardEnd`, and never so far
            // that the cursor fails to advance (that would loop forever).
            var next = cut
            while next < text.endIndex, text[next].isWhitespace {
                next = text.index(after: next)
            }
            cursor = next > cursor ? next : text.index(after: cursor)
        }

        // Whitespace-only input leaves nothing after trimming. Returning `[text]`
        // here would hand back a piece BIGGER than the budget — the one thing
        // this function promises never to do — so return nothing and let
        // `splitPlain` treat it as "send unchanged".
        return parts
    }

    /// Best cut point in `from..<hardEnd`, by the preference order documented
    /// on `split`. Returns `hardEnd` when the run has no boundary at all.
    private static func boundary(in text: String,
                                 from start: String.Index,
                                 hardEnd: String.Index,
                                 limit: Int) -> String.Index {
        let window = text[start..<hardEnd]
        guard !window.isEmpty else { return hardEnd }

        var lastParagraph: String.Index? = nil
        var lastLine: String.Index? = nil
        var lastWord: String.Index? = nil

        var i = window.startIndex
        var previousWasNewline = false
        while i < window.endIndex {
            let ch = window[i]
            let after = window.index(after: i)
            if ch.isNewline {
                if previousWasNewline { lastParagraph = after }
                lastLine = after
                previousWasNewline = true
            } else {
                if ch.isWhitespace { lastWord = after }
                previousWasNewline = false
            }
            i = after
        }

        let floorBytes = Int(Double(limit) * minimumFill)
        func fills(_ idx: String.Index) -> Bool {
            text[start..<idx].utf8.count >= floorBytes
        }

        for candidate in [lastParagraph, lastLine, lastWord] {
            if let c = candidate, c > start, fills(c) { return c }
        }
        // Nothing filled the budget: take the latest boundary of any class
        // rather than cutting a word in half.
        let fallbacks = [lastParagraph, lastLine, lastWord].compactMap { $0 }.filter { $0 > start }
        if let best = fallbacks.max() { return best }
        return hardEnd
    }
}

// MARK: - Rate limiting

/// 100 messages a minute, sliding window, device-wide.
///
/// Deliberately NOT an actor and NOT `@MainActor`: the two send sinks are
/// synchronous and are called from the main actor, from `Task.detached`, and
/// from `ScheduledMessageManager`'s timer thread. An async gate here would
/// force every one of those call sites to change. A lock is enough — the
/// critical section is an array trim.
///
/// SPLIT PARTS COST ONE TOKEN, NOT N. The limit exists to bound what a *person*
/// (or something automating them) sends; how many envelopes the transport needs
/// to carry one paste is an implementation detail they did not choose. Charging
/// per part would mean a 60 KB paste on a paired radio (120 parts) trips the
/// limit by itself — which is exactly the failure this must not have. Flooding
/// by splitting is separately impossible: `MessageTextSplitter.maxParts` refuses
/// a paste that needs more than 50 parts.
///
/// Control traffic (receipts, typing, profile sync, location updates) is never
/// charged — it rides the same `sendMessage` but is not a user action, and
/// dropping receipts breaks delivery state.
final class MessageSendRateLimiter {

    static let shared = MessageSendRateLimiter()

    static let maxPerWindow = 100
    static let window: TimeInterval = 60

    private let lock = NSLock()
    private var stamps: [Date] = []

    init() {}

    enum Decision: Equatable {
        case allowed
        /// Whole seconds until the oldest stamp leaves the window. Always ≥ 1,
        /// so the message shown to the user never says "try again in 0 s".
        case denied(retryAfterSeconds: Int)
    }

    /// Charge one user action. Call ONCE per send action, before sending.
    @discardableResult
    func consume(now: Date = Date()) -> Decision {
        lock.lock()
        defer { lock.unlock() }
        trim(now: now)
        if stamps.count < Self.maxPerWindow {
            stamps.append(now)
            return .allowed
        }
        let oldest = stamps.first ?? now
        let wait = Self.window - now.timeIntervalSince(oldest)
        return .denied(retryAfterSeconds: max(1, Int(wait.rounded(.up))))
    }

    /// Non-charging peek, for UI that wants to grey out a button.
    func wouldAllow(now: Date = Date()) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        trim(now: now)
        return stamps.count < Self.maxPerWindow
    }

    func reset() {
        lock.lock()
        stamps.removeAll()
        lock.unlock()
    }

    /// Test/diagnostic accessor.
    var currentCount: Int {
        lock.lock()
        defer { lock.unlock() }
        trim(now: Date())
        return stamps.count
    }

    private func trim(now: Date) {
        let cutoff = now.addingTimeInterval(-Self.window)
        // Stamps are appended in time order, so a prefix drop is enough.
        if let keepFrom = stamps.firstIndex(where: { $0 > cutoff }) {
            if keepFrom > 0 { stamps.removeFirst(keepFrom) }
        } else {
            stamps.removeAll()
        }
    }
}

// MARK: - Outcome reported back to the composer

/// What a send sink did. Existing callers ignore it (`@discardableResult`), so
/// adding this changed no behaviour anywhere; the two composers read it so they
/// can put the user's text back in the box instead of eating it.
enum MessageSendOutcome: Equatable {
    case sent
    case sentAsParts(Int)
    case rateLimited(retryAfterSeconds: Int)
    case tooLong
    /// __BLOCKED_NOTICE_2026_09_18__ Le destinataire est bloqué: rien ne part.
    /// Refusé au même endroit que la limite de débit, et pour la même raison —
    /// c'est le seul point par lequel passent la composition, l'extension de
    /// partage, les messages programmés, les transferts, la Watch, le compagnon
    /// web et la réponse rapide depuis une notification.
    case blocked
    /// __LORA_REPLY_REFUSAL_2026_10_26__ The thread is a `lora!<nodehex>` one:
    /// a raw Meshtastic text packet arrived from a radio that has never proved
    /// an OSHI identity, so the conversation is keyed on a synthetic node id
    /// rather than on a public key. There is nothing to encrypt to. The send
    /// used to be attempted anyway and failed without a word, which reads as
    /// the app ignoring the Send button.
    case loRaUnidentified
    /// __LORA_INTEROP_REPLY_2026_10_28__ A `lora!` thread CAN now be answered
    /// — over the same cleartext interop lane the inbound text arrived on — so
    /// the three cases below replace most of [loRaUnidentified]. Each one is a
    /// real limit of that lane rather than a missing identity:
    /// no radio attached, nothing to transmit through.
    case loRaInteropNoRadio
    /// LoRa carries short text only; a photo or a file cannot ride this lane.
    case loRaInteropMediaUnsupported
    /// One frame, no chunking — see `MeshtasticManager.interopTextLimit`.
    case loRaInteropTooLong(bytes: Int, limit: Int)
    /// __CHANNEL_ROOMS_ALL_SENT_ON_SLOT_ZERO_2026_11_07__ This room names a
    /// channel the ATTACHED radio does not have — renamed, reordered, deleted,
    /// or a room created against a different radio.
    ///
    /// A refusal rather than a fallback on purpose. `sendToLoRaChannel` never
    /// set `MeshPacket.channel`, so every room's text went out on slot 0; for a
    /// private room that is not just misdelivery but disclosure, because slot 0
    /// is normally LongFast and public. "I cannot tell which channel" must
    /// never be answered with "the public one".
    case loRaChannelUnknown

    var didSend: Bool {
        switch self {
        case .sent, .sentAsParts: return true
        case .rateLimited, .tooLong, .blocked, .loRaUnidentified,
             .loRaInteropNoRadio, .loRaInteropMediaUnsupported, .loRaInteropTooLong,
             .loRaChannelUnknown:
            return false
        }
    }

    /// Localized text for the refusal, or nil when the send went through.
    /// Every key here exists in all 34 `*.lproj/Localizable.strings`.
    var userFacingMessage: String? {
        switch self {
        case .sent, .sentAsParts:
            return nil
        case .rateLimited(let seconds):
            return "send.error.rate_limited".localized(with: String(seconds))
        case .tooLong:
            return "send.error.too_long".localized
        case .blocked:
            return "send.error.blocked".localized
        case .loRaUnidentified:
            return "send.error.lora_unidentified".localized
        case .loRaInteropNoRadio:
            return "lora.error.not_connected".localized
        case .loRaInteropMediaUnsupported:
            return "lora.blocked.media".localized
        case .loRaInteropTooLong(let bytes, let limit):
            return "lora.error.too_large".localized(with: String(bytes), String(limit))
        case .loRaChannelUnknown:
            return "lora.error.channel_unknown".localized
        }
    }
}

// MARK: - Ordering

/// Hands out strictly increasing send timestamps.
///
/// Message lists sort on `timestamp` (`ChatView.swift`, `GroupViews.swift`,
/// `MessagesListView.swift`) with `sorted(by:)`, which in Swift is NOT stable —
/// two messages with equal timestamps come out in an arbitrary order, and split
/// parts are precisely the case that produces equal timestamps, because they are
/// issued in one synchronous loop.
///
/// The spacing is set by the COARSEST resolution any of these timestamps
/// survives on the wire, which was measured rather than assumed:
///
///   * V2 relay  — `V2SyncModels.swift`: `ts: Int64` = client ms since epoch.
///     MILLISECONDS. This is the binding one.
///   * IPFS 1:1  — `MessageManager.uploadToIPFS` sends
///     `message.timestamp.timeIntervalSince1970`, a full-precision Double.
///   * Group iOS — `GroupMessage` is Codable with a `Date`, which
///     `JSONEncoder` writes as a full-precision Double.
///   * Group cross-platform — Android sends millis
///     (`parseCrossPlatformGroupMessage`). MILLISECONDS.
///
/// So 1 ms is the floor. 10 ms is used, a 10× margin that is still invisible
/// (the 50-part maximum skews the last part by half a second).
///
/// NOTE on the ISO8601-truncated-to-the-second trap: it is real in this
/// codebase but NOT on this path. `MessageManager.swift` (ratchet-reset
/// envelope) and `WebSessionManager.swift` build strings with a bare
/// `ISO8601DateFormatter()`, which drops sub-second precision. The ratchet-reset
/// envelope is a control message that is never split. `WebSessionManager` is the
/// web-companion projection and is fixed alongside this change to emit
/// `.withFractionalSeconds`, so a split paste cannot reorder there either.
enum MessageSendClock {

    static let minimumSpacing: TimeInterval = 0.010

    private static let lock = NSLock()
    private static var lastIssued: Date = .distantPast

    /// One timestamp, never equal to or before any previously issued one.
    static func next(now: Date = Date()) -> Date {
        lock.lock()
        defer { lock.unlock() }
        let candidate = max(now, lastIssued.addingTimeInterval(minimumSpacing))
        lastIssued = candidate
        return candidate
    }

    /// `count` timestamps for the parts of one split, strictly increasing.
    static func next(count: Int, now: Date = Date()) -> [Date] {
        guard count > 0 else { return [] }
        return (0..<count).map { _ in next(now: now) }
    }

    /// Tests only — the allocator is process-global by design.
    static func resetForTesting() {
        lock.lock()
        lastIssued = .distantPast
        lock.unlock()
    }
}

// MARK: - Is a duplicate copy affordable on this bearer?

extension MessageTextSplitter {

    /// __MSG_DUP_COST_2026_10_09__ Whether a message may be sent over a SECOND
    /// transport purely as insurance.
    ///
    /// The message path had no bearer veto at all. The call path has had one
    /// since 2026-07-27 (`VoiceCallManager.mediaDuplicationAffordable()`), and
    /// its comment is emphatic about why the test is the interface TYPE and not
    /// `isExpensive`: the 95-98%-loss cellular leg that motivated it reported
    /// `expensive=false` and `constrained=false`. Same rule here, deliberately
    /// identical rather than a second opinion — "one policy, one place" was the
    /// lesson that fix recorded.
    ///
    /// What it currently guards: every message delivered by mesh is ALSO
    /// posted to the relay (`MessageManager.sendSingleMessage`,
    /// `__SERVER_IS_NOT_A_FALLBACK_2026_08_15__`). That is the right default on
    /// Wi-Fi — the relay is the store-and-forward insurance for a mesh hop that
    /// silently did not arrive — but on a metered or low-data-mode link it
    /// spends the user's allowance on a copy of something already delivered.
    ///
    /// Pure so it can be tested without a live `NWPath`.
    static func duplicationAffordable(cellular: Bool,
                                      expensive: Bool,
                                      constrained: Bool) -> Bool {
        !(cellular || expensive || constrained)
    }
}
