//
//  OSHIControlPayload.swift
//  OSHI
//
//  THE list of emoji-sentinel control payloads, and the only place that decides
//  what one of them looks like on screen.
//
//  Why this file exists
//  --------------------
//  OSHI rides its control messages (receipts, typing, call summaries, location,
//  reactions, reply/forward envelopes…) on the SAME `content` string as user
//  prose, distinguished by an emoji prefix followed by a JSON or key|value body.
//  Every surface that shows a message therefore has to know the list, and before
//  this file there were SIX partial copies of it:
//
//      OSHI/ChatView.swift:214        9 prefixes   (AI context)
//      OSHI/ChatView.swift:265        9 prefixes   (bubble filter)
//      OSHI/ChatView.swift:1910      13 prefixes   (AI context)
//      OSHI/GroupViews.swift:4328    15 prefixes   (AI context)
//      OSHI/GroupViews.swift:2427     3 prefixes   (group bubble filter)
//      OSHI/MessageSendPolicy.swift  22 prefixes   (wire splitter)
//
//  Six lists, six different lengths — so "add the missing prefix" fixed exactly
//  one surface and the JSON kept showing up in the other five. The 2026-08-10
//  fix landed in `GroupViews.swift:4328`, which is the SMART-REPLY prompt
//  filter: it feeds the LLM, it does not render anything. That is why the user
//  still saw raw JSON afterwards.
//
//  The rule from now on: a new control message is declared ONCE, in `catalog`
//  below. Nothing else in the app may hold a prefix array.
//
//  The three questions a surface can ask
//  -------------------------------------
//    isControl(_:)      "is this a sentinel payload at all?"  — use it to keep
//                       protocol text out of AI prompts, search indexes, etc.
//    isSilent(_:)       "must this never be shown, anywhere?" — use it to drop
//                       the message from a bubble list entirely.
//    previewText(for:)  "what one line do I put on screen?"   — use it for list
//                       previews, notification bodies, reply quotes, the Watch.
//                       Plain prose comes back unchanged, so it is always safe
//                       to route text through it.
//
//  `displaySummary(for:)` is the raw form of the last one: nil when the text is
//  ordinary prose, "" when it must be hidden, a human sentence otherwise.
//
//  Localization: every string produced here uses a key that already exists in
//  all 34 `OSHI/*.lproj/Localizable.strings`. Adding a new key would mean 34
//  edits, and a key missing from one of them ships the raw key to that language.
//

import Foundation

enum OSHIControlPayload {

    // MARK: - How a payload presents itself

    enum Kind {
        /// Pure protocol. Not a message. Never render it, never count it.
        case silent
        /// A real user-facing event that happens to travel as a sentinel
        /// payload (a call, a location, a reaction…). It has a summary.
        case rendered
        /// A user message wrapped in an envelope (reply / forward). The body
        /// inside is what the user wrote — unwrap, never summarise away.
        case envelope
    }

    // MARK: - THE catalog (single source of truth)

    struct Entry {
        let prefix: String
        let kind: Kind
        init(_ prefix: String, _ kind: Kind) { self.prefix = prefix; self.kind = kind }
    }

    /// Every sentinel on the wire. **Add a new control message here and nowhere
    /// else.**
    ///
    /// Duplicate spellings are deliberate, not sloppiness: iOS and Android
    /// disagree on `CHECK_IN`/`CHECKIN` and on the reaction marker, and both
    /// forms are live on the wire today. Dropping either one un-fixes a device.
    static let catalog: [Entry] = [
        Entry("📞CALL_SIGNAL📞", .silent),
        Entry("📬DELIVERY_RECEIPT📬", .silent),
        Entry("📖READ_RECEIPT📖", .silent),
        Entry("⌨️TYPING⌨️", .silent),
        Entry("✍️TYPING✍️", .silent),
        Entry("🔗LINKS_VISIBILITY🔗", .silent),
        Entry("📸PROFILE_REQUEST📸", .silent),
        Entry("🔧ACTION🔧", .silent),
        Entry("📢GROUP_UPDATE📢", .silent),
        Entry("📊POLL_VOTE📊", .silent),
        // __GROUP_RECEIPTS_2026_11_02__ (N8) per-message, per-member "received"
        // and "read" for a GROUP message, addressed to the sender alone.
        Entry("📑GROUP_RECEIPT📑", .silent),
        Entry("__SESSION_RESET__", .silent),

        Entry("📱CALL_SUMMARY📱", .rendered),
        Entry("📱MISSED_CALL📱", .rendered),
        Entry("📍LOCATION📍", .rendered),
        Entry("📅SCHEDULED_CALL📅", .rendered),
        Entry("🛡️CHECK_IN🛡️", .rendered),
        Entry("🛡️CHECKIN🛡️", .rendered),
        // __ALIVE_CHECK_IN_2026_11_04__ The recurring liveness confirmation. A
        // SEPARATE sentinel from the two journey check-ins above: that feature is
        // an ETA with a location, this one is a heartbeat with neither, and
        // sharing a prefix would route these events through CheckInMessage's
        // seven-type renderer. Rendered, because a confirmation the reader cannot
        // find afterwards is one they must take on trust from a coloured dot.
        Entry("💓ALIVE💓", .rendered),
        Entry("📸PROFILE_UPDATE📸", .rendered),
        Entry("🎨WALLPAPER_UPDATE🎨", .rendered),
        Entry("🔥REACTION🔥", .rendered),
        Entry("👍 REACTION👍", .rendered),
        Entry("🔑KEY_ROTATION🔑", .rendered),
        Entry("🚫BLOCKED🚫", .rendered),
        Entry("📊POLL📊", .rendered),
        // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ Rendered, not silent: a change
        // to how a conversation is routed is exactly what a reader should be able
        // to see happen, and when. See `OfflineOnlyConversation.swift`.
        Entry("🔒OFFLINE_ONLY🔒", .rendered),
        Entry("MEDIA|||", .rendered),

        Entry("💬REPLY💬", .envelope),
        Entry("➡️FORWARDED➡️", .envelope),
    ]

    /// Named accessors for the handful of prefixes other code constructs or
    /// routes on. These read from `catalog` positions by value so a typo here
    /// cannot introduce a 25th, un-catalogued prefix.
    static let offlineOnlyPrefix   = "🔒OFFLINE_ONLY🔒"
    static let callSummaryPrefix   = "📱CALL_SUMMARY📱"
    static let missedCallPrefix    = "📱MISSED_CALL📱"

    /// Ce payload est-il le résumé d'un appel DÉCROCHÉ ?
    ///
    /// Le seul discriminant est la DURÉE, et donc le `|` qui la sépare de la clé:
    /// `answeredCallSummary` ci-dessous produit `…📞↗️call.outgoing|2:10`, tandis
    /// que `ChatView.handleMissedCall` produit `…📞↗️call.no.answer` — sans durée,
    /// parce qu'il n'y en a pas eu.
    ///
    /// Ça compte parce que les deux catégories se traitent à l'OPPOSÉ à la
    /// réception: un appel décroché est forcément observé des deux côtés, donc la
    /// copie du pair est un doublon à jeter; un appel manqué peut n'avoir été vu
    /// que par l'appelant, donc son message est la seule trace et doit passer.
    /// Voir `MessageManager.receiveMessage`.
    static func isAnsweredCallSummary(_ text: String) -> Bool {
        text.hasPrefix(callSummaryPrefix) && text.contains("|")
    }

    /// Le résumé d'un appel DÉCROCHÉ, tel qu'il est écrit dans le fil.
    ///
    /// __CALL_SUMMARY_LOCAL_2026_08_22__ Le format vivait en clair dans
    /// `ChatView.handleCallEnded`, à un fichier et 3 000 lignes du prédicat qui
    /// décide de son sort. Producteur et filtre sont maintenant côte à côte:
    /// changer l'un sans l'autre casse un test au lieu de casser un fil.
    ///
    /// La direction est celle de l'appareil QUI ÉCRIT — et c'est tout l'intérêt
    /// de l'écrire localement. La copie du pair porte SA direction et n'est
    /// jamais retournée à la lecture: reçue telle quelle, elle affiche « appel
    /// sortant » sur un appel qu'on a reçu.
    static func answeredCallSummary(wasOutgoing: Bool, duration: String) -> String {
        let icon = wasOutgoing ? "📞↗️" : "📞↙️"
        let key  = wasOutgoing ? "call.outgoing" : "call.incoming"
        return "\(callSummaryPrefix)\(icon)\(key)|\(duration)"
    }
    /// Les deux orthographes du « en train d'écrire » vivantes sur le fil: iOS
    /// émet `⌨️TYPING⌨️`, Android `✍️TYPING✍️`. Le corps est le JSON de
    /// `TypingStatus` — SAUF pour le coup de pouce de ré-établissement de
    /// session (`MessageManager.sendRatchetRehandshake`), qui envoie la
    /// sentinelle NUE parce que seul le fait de traverser le ratchet compte.
    static let typingPrefix        = "⌨️TYPING⌨️"
    static let typingPrefixAndroid = "✍️TYPING✍️"

    /// Ce payload est-il un ping « en train d'écrire » ? Passe par
    /// `matchedPrefix`, donc les orthographes sans U+FE0F comptent aussi.
    static func isTypingPing(_ text: String) -> Bool {
        guard let p = matchedPrefix(text) else { return false }
        return p == typingPrefix || p == typingPrefixAndroid
    }

    static let locationPrefix      = "📍LOCATION📍"
    static let scheduledCallPrefix = "📅SCHEDULED_CALL📅"
    static let checkInPrefix       = "🛡️CHECK_IN🛡️"
    /// __ALIVE_CHECK_IN_2026_11_04__ `💓ALIVE💓`. See `AliveCheckIn.swift`;
    /// Android declares the same constant in `OSHIControlPayload.kt`.
    static let aliveCheckInPrefix  = "💓ALIVE💓"
    static let profileUpdatePrefix = "📸PROFILE_UPDATE📸"
    static let wallpaperPrefix     = "🎨WALLPAPER_UPDATE🎨"
    static let replyPrefix         = "💬REPLY💬"
    static let forwardPrefix       = "➡️FORWARDED➡️"
    static let mediaPrefix         = "MEDIA|||"

    /// __POLLS_2026_10_23__ Un sondage et un bulletin.
    ///
    /// Le sondage s'affiche (c'est une bulle, et il mérite une notification).
    /// Le bulletin est muet: il ne se dessine pas et ne réveille personne —
    /// dans un groupe de vingt, vingt votes feraient vingt bannières. Le corps
    /// des deux est le JSON de `PollMessage` / `PollVotePayload`.
    ///
    /// Le votant n'est PAS dans le JSON: c'est l'expéditeur de l'enveloppe
    /// déchiffrée, donc authentifié par le ratchet. Un champ « votant » sur le
    /// fil laisserait voter à la place de n'importe qui.
    static let pollPrefix          = "📊POLL📊"
    static let pollVotePrefix      = "📊POLL_VOTE📊"

    /// Le corps d'un payload sentinelle, ou nil si `text` n'est pas ce payload.
    ///
    /// Passe par `matchedVariantLength`, donc l'orthographe sans U+FE0F est
    /// coupée à la bonne longueur: `dropFirst(prefix.count)` sur une variante
    /// plus courte emporte le premier caractère du JSON et le décodage échoue
    /// pour une raison invisible.
    static func body(of text: String, expecting prefix: String) -> String? {
        guard matchedPrefix(text) == prefix else { return nil }
        return String(text.dropFirst(matchedVariantLength(text, canonical: prefix)))
    }

    /// __BLOCKED_NOTICE_2026_09_18__ « Ce que vous envoyez ici n'arrive plus. »
    ///
    /// Émis PAR l'appareil qui bloque, VERS la clé bloquée, quand quelque chose
    /// adressé à un contact bloqué se présente. Aucun corps: tout le sens tient
    /// dans la sentinelle, et le côté bloqué l'affiche dans SA langue.
    ///
    /// C'est la moitié visible d'une décision produit. Le blocage était muet —
    /// l'expéditeur regardait partir un message et n'apprenait jamais qu'il avait
    /// été refusé. La limite de débit est au site d'émission et non ici
    /// (`MessageManager.noticeBlockedSender`): une fois par pair et par jour,
    /// pour qu'un indésirable ne transforme pas son propre trafic en battement
    /// de cœur lui disant quand la personne qui l'a bloqué a son téléphone en
    /// main. Android: `OSHIControlPayload.kt` (`BLOCKED_NOTICE`).
    static let blockedNoticePrefix = "🚫BLOCKED🚫"

    /// Flat list, for callers that genuinely need the strings (the wire
    /// splitter). Ordered longest-first so a prefix that is a prefix of another
    /// can never shadow it.
    static let prefixes: [String] = catalog.map { $0.prefix }.sorted { $0.count > $1.count }

    // MARK: - Matching

    /// U+FE0F (VARIATION SELECTOR-16) is present in `🛡️`, `⌨️`, `✍️` and `➡️`
    /// as they are written in this file — and ABSENT from some payloads built by
    /// older iOS builds and by the Android client, which emit the bare
    /// codepoint. `String.hasPrefix` compares grapheme clusters under canonical
    /// equivalence, and `"🛡"` is NOT canonically equivalent to `"🛡️"`, so a
    /// single spelling silently fails to match half the traffic.
    ///
    private struct Variant {
        /// How it appears on the wire.
        let spelling: String
        /// The catalog entry it resolves to.
        let prefix: String
        let kind: Kind
    }

    /// Rather than duplicate every entry in `catalog` (the exact mistake this
    /// file exists to stop), the variants are DERIVED: each catalog prefix
    /// contributes itself plus its U+FE0F-stripped twin, both mapping back to
    /// the same catalog entry.
    private static let variants: [Variant] = {
        var out: [Variant] = []
        for entry in catalog {
            out.append(Variant(spelling: entry.prefix, prefix: entry.prefix, kind: entry.kind))
            let stripped = entry.prefix.replacingOccurrences(of: "\u{FE0F}", with: "")
            if stripped != entry.prefix {
                out.append(Variant(spelling: stripped, prefix: entry.prefix, kind: entry.kind))
            }
        }
        // Longest variant first: `📱CALL_SUMMARY📱` and `📱MISSED_CALL📱` share
        // a leading emoji, and a shorter entry must never win over a longer one.
        return out.sorted { $0.spelling.count > $1.spelling.count }
    }()

    /// The catalog prefix `text` starts with, in its canonical spelling, or nil.
    ///
    /// Strictly anchored at the start: a message that merely CONTAINS a sentinel
    /// ("we should add a 📍LOCATION📍 marker") is ordinary prose and must be
    /// left alone.
    static func matchedPrefix(_ text: String) -> String? {
        variants.first { text.hasPrefix($0.spelling) }?.prefix
    }

    private static func kind(of text: String) -> Kind? {
        variants.first { text.hasPrefix($0.spelling) }?.kind
    }

    /// True when `text` is any sentinel payload — including reply/forward
    /// envelopes, which are user messages but must never be shown raw.
    static func isControl(_ text: String) -> Bool {
        kind(of: text) != nil || isSilentJSON(text)
    }

    /// True when `text` must never reach the user in any form: drop the message.
    static func isSilent(_ text: String) -> Bool {
        if isSilentJSON(text) { return true }
        return kind(of: text) == .silent
    }

    // MARK: - La quatrième question: ce payload peut-il être une LIGNE du fil ?

    /// __COUNTED_IMPLIES_DRAWN_2026_10_20__
    ///
    /// « Faut-il l'AFFICHER ? » et « faut-il le COMPTER comme non lu ? » étaient
    /// deux questions posées par deux codes différents, et c'est tout le bug:
    /// `ChatView.computeFilteredMessages` jetait les payloads silencieux de la
    /// liste, tandis que le décompte de `MessageManager.updateConversations`
    /// comptait TOUT message reçu et non lu. Un payload qui ne peut pas être
    /// dessiné était donc compté — un badge avec RIEN derrière, qui s'efface à
    /// l'ouverture (`markConversationAsRead` ne regarde pas le contenu) et
    /// revient au ping suivant.
    ///
    /// Mesuré sur un vrai compte: le coup de pouce de ré-établissement de
    /// session part toutes les ~60 s et il est un `⌨️TYPING⌨️`, donc le badge
    /// revenait chaque minute sur une conversation vide.
    ///
    /// Un seul prédicat décide maintenant des deux. La règle: **ce qui n'est
    /// jamais dessiné n'est jamais compté.**
    static func isHiddenFromThread(_ text: String) -> Bool {
        if isSilent(text) { return true }
        guard let p = matchedPrefix(text) else { return false }
        // Rendus ailleurs (l'aperçu de la liste de conversations) mais jamais
        // une bulle: ce sont les deux que `computeFilteredMessages` cachait déjà
        // à la main.
        return p == profileUpdatePrefix || p == wallpaperPrefix
    }

    // MARK: - The third question: may this WAKE someone?

    // __PUSH_LISTS_DRIFTED_2026_09_02__
    //
    // « Faut-il l'AFFICHER ? » et « faut-il faire SONNER le téléphone d'en
    // face ? » sont deux questions différentes, et la seconde vivait dans DEUX
    // tableaux écrits à la main — `MessageManager.silentControlPrefixes` et
    // `MessageManager.v2SilentControlPrefixes`, 8 entrées chacun — alors que le
    // catalogue en compte 10 `.silent`. C'est exactement la dérive que ce
    // fichier a été créé pour tuer, et elle avait déjà produit le symptôme
    // classique: une bannière « vous avez reçu un message » sans rien derrière.
    //
    // Les deux tableaux disparaissent au profit de `suppressesPush`. Le
    // catalogue redevient la seule liste, et
    // `OSHIControlPayloadTests.testEverySilentPayloadIsAlsoPushSilent` échoue si
    // quelqu'un ajoute un `.silent` sans décider de son sort ici.
    //
    // Ce n'est PAS « silencieux ⇒ pas de poussée », pour deux raisons qui
    // doivent rester écrites plutôt que devinées:

    /// Un `.silent` qui doit malgré tout réveiller le pair.
    ///
    /// Un signal d'appel est de la plomberie — il ne s'affiche jamais — mais le
    /// téléphone d'en face DOIT sonner. (Aujourd'hui il ne passe pas par les
    /// chemins qui consultent ce prédicat: `sendCallSignalViaCloud` a sa propre
    /// file, et la sonnerie vient du push VoIP. L'exception est là pour que le
    /// jour où un signal d'appel emprunte le chemin normal, il ne se fasse pas
    /// museler par une règle écrite pour des accusés de lecture.)
    private static let silentButStillRings: Set<String> = ["📞CALL_SIGNAL📞"]

    /// Des payloads qui s'AFFICHENT (ils ont une ligne dans le fil) mais qui ne
    /// valent pas qu'on réveille quelqu'un: un nouvel avatar, un fond d'écran.
    /// Les deux tableaux supprimés les portaient déjà; les garder ici préserve
    /// le comportement au lieu de le changer en passant.
    private static let renderedButNotWorthWaking: Set<String> = [
        "📸PROFILE_UPDATE📸", "🎨WALLPAPER_UPDATE🎨", "🚫BLOCKED🚫"
    ]

    /// Envoyer ce payload ne doit réveiller PERSONNE: le pair le traitera à son
    /// prochain sondage. La donnée part quand même — c'est la NOTIFICATION qui
    /// est supprimée, pas le message.
    static func suppressesPush(_ text: String) -> Bool {
        guard let prefix = matchedPrefix(text) else { return isSilentJSON(text) }
        if silentButStillRings.contains(prefix) { return false }
        if renderedButNotWorthWaking.contains(prefix) { return true }
        return kind(of: text) == .silent
    }

    /// Les préfixes que `suppressesPush` refuse de laisser sonner. Exposé pour
    /// le test qui compare cette décision au catalogue — pas pour filtrer:
    /// filtrer se fait par `suppressesPush`, qui connaît les orthographes sans
    /// U+FE0F.
    static var pushSilentPrefixes: Set<String> {
        var out = renderedButNotWorthWaking
        for entry in catalog where entry.kind == .silent {
            if !silentButStillRings.contains(entry.prefix) { out.insert(entry.prefix) }
        }
        return out
    }

    /// Group sync traffic rides as a bare JSON object with no emoji sentinel, so
    /// it needs its own recogniser or it renders as a wall of braces.
    private static func isSilentJSON(_ text: String) -> Bool {
        guard text.hasPrefix("{") else { return false }
        return text.contains("\"type\":\"member_sync")
            || text.contains("\"type\":\"sync_request")
            || text.contains("\"type\":\"group_update")
            || text.contains("\"type\": \"member_sync")
            || text.contains("\"type\": \"sync_request")
            || text.contains("\"type\": \"group_update")
    }

    // MARK: - Display

    /// What to put on screen for `text`.
    ///
    /// * `nil`  — ordinary user prose, render it as-is.
    /// * `""`   — a silent payload, render nothing.
    /// * else   — a short human sentence, already localized.
    ///
    /// Reply and forward envelopes resolve to the body the user actually typed.
    static func displaySummary(for text: String) -> String? {
        if isSilentJSON(text) { return "" }
        guard let prefix = matchedPrefix(text), let kind = kind(of: text) else {
            // Bare media JSON is not sentinel-prefixed but is still not prose.
            if let media = mediaJSONSummary(text) { return media }
            return nil
        }

        switch kind {
        case .silent:
            return ""

        case .envelope:
            // The envelope carries the real message in `content`. Recurse, so a
            // reply to a call summary quotes "Outgoing call • 1:23" and not the
            // JSON of a JSON.
            let body = envelopeBody(text, prefix: prefix)
            guard let body else { return "" }
            return displaySummary(for: body) ?? body

        case .rendered:
            let body = String(text.dropFirst(matchedVariantLength(text, canonical: prefix)))
            return summary(forPrefix: prefix, body: body)
        }
    }

    /// One line of human-readable text for ANY message string.
    ///
    /// Plain prose is returned unchanged, so this is always safe to wrap around
    /// something that may or may not be a control payload — which is exactly
    /// what a list preview, a notification body, a reply quote and the Watch
    /// bridge each need.
    static func previewText(for text: String) -> String {
        displaySummary(for: text) ?? text
    }

    /// True when `text` is a reply / forward envelope around a user message.
    static func isEnvelope(_ text: String) -> Bool {
        kind(of: text) == .envelope
    }

    /// Text to hand across the WatchConnectivity bridge.
    ///
    /// The Watch renders `message.content` and `conversation.lastMessage`
    /// directly (`Text(message.content)`, and a local-notification body) with no
    /// filtering of its own — and it CANNOT gain any, because it is a separate
    /// target with its own 17-language `.lproj` set that does not contain these
    /// keys, so a summary built over there would ship the raw key. Sanitising on
    /// the phone side means the Watch only ever receives text a human can read,
    /// already in the phone's language, with no Watch-target change at all.
    ///
    /// A payload with nothing to show collapses to `fallback` rather than to an
    /// empty row.
    static func watchSafeText(_ raw: String?, fallback: String) -> String {
        guard let raw, !raw.isEmpty else { return fallback }
        let readable = previewText(for: raw)
        return readable.isEmpty ? fallback : readable
    }

    /// Like `previewText`, but hands reply / forward envelopes back untouched.
    ///
    /// The 1:1 and group bubble renderers decode the envelope THEMSELVES to draw
    /// the quote box above the body; unwrapping it here would silently delete
    /// that quote box. Every other payload is still summarised, so a group call
    /// summary or reaction can no longer reach `messageBubble(text:)` raw.
    static func displayTextPreservingEnvelopes(for text: String) -> String {
        if isEnvelope(text) { return text }
        return previewText(for: text)
    }

    // MARK: - Per-payload summaries

    private static func summary(forPrefix prefix: String, body: String) -> String {
        switch prefix {
        case callSummaryPrefix:
            return callSummary(body: body, missed: false)
        case missedCallPrefix:
            return callSummary(body: body, missed: true)
        case locationPrefix:
            return locationSummary(body: body)
        case scheduledCallPrefix:
            return "📅 " + "event.call.scheduled".localized
        case checkInPrefix, "🛡️CHECKIN🛡️":
            return "🛡️ " + "checkin.title".localized
        case aliveCheckInPrefix:
            // __ALIVE_CHECK_IN_2026_11_04__ WHICH of the three events it was,
            // because "a check-in happened" is the one reading that tells the
            // reader nothing. An unreadable body falls back to the plain
            // confirmation: a row that said "stopped" on a corrupt payload would
            // claim a schedule ended when it has not.
            return "💓 " + aliveCheckInLabel(body: body)
        case "📸PROFILE_UPDATE📸":
            return "📸 " + "system.profile_updated".localized
        case "🎨WALLPAPER_UPDATE🎨":
            return "🎨 " + "system.wallpaper_changed".localized
        case "🔥REACTION🔥", "👍 REACTION👍":
            return "🔥 " + "notification.reaction".localized
        case "🔑KEY_ROTATION🔑":
            return "🔑 " + "key_rotation_title".localized
        case blockedNoticePrefix:
            // Nomme la CONSÉQUENCE, pas le geste de l'autre: c'est la partie
            // utile, et la seule que cet appareil puisse affirmer — un avis
            // prouve qu'un appareil a refusé un message, rien de plus.
            return "🚫 " + "chat.blocked_by_contact".localized
        case pollPrefix:
            // La QUESTION, pas le mot « sondage »: c'est ce qui rend l'aperçu
            // d'une conversation et une bannière utiles. Repli sur le titre
            // quand le JSON est illisible.
            return pollSummary(body: body)
        case offlineOnlyPrefix:
            // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ Le SENS du changement est
            // tout le contenu de la ligne: dire « hors-ligne uniquement » quand
            // quelqu'un vient de lever la restriction serait un contresens.
            return offlineOnlySummary(body: body)
        case mediaPrefix:
            return mediaMarkerSummary(body: body)
        default:
            // Unreachable while every `.rendered` entry is handled above; a new
            // catalog entry that forgets its case degrades to "hide it" rather
            // than to "print the JSON", which is the failure that matters.
            return ""
        }
    }

    /// Call rows travel in two shapes and BOTH have shipped:
    ///
    ///   "📞↗️call.outgoing|1:23"   icon + localization key + duration
    ///   "❌call.missed"            icon + localization key, no duration
    ///   {"direction":"outgoing","duration":83}   JSON, from other clients
    ///
    /// The key is sent, not the translated text, so each device renders the call
    /// in its OWN language — that is why this cannot be "just show the body".
    private static func callSummary(body: String, missed: Bool) -> String {
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)

        if trimmed.hasPrefix("{") {
            return callSummaryFromJSON(trimmed, missed: missed)
        }

        let icons = ["📞↗️", "📞↙️", "❌", "📞", "\u{1F4DE}\u{2197}", "\u{1F4DE}\u{2199}"]
        var icon = ""
        var rest = trimmed
        for candidate in icons where rest.hasPrefix(candidate) {
            icon = candidate
            rest = String(rest.dropFirst(candidate.count))
            break
        }
        if icon.isEmpty { icon = missed ? "❌" : "📞" }

        let key: String
        let duration: String?
        if let bar = rest.firstIndex(of: "|") {
            key = String(rest[rest.startIndex..<bar])
            let tail = String(rest[rest.index(after: bar)...])
            duration = tail.isEmpty ? nil : tail
        } else {
            key = rest
            duration = nil
        }

        // A key is `call.something`; anything else is either already-localized
        // text from an old build or garbage, and must not be fed to
        // NSLocalizedString (which would echo it back verbatim, JSON included).
        let label: String
        if key.hasPrefix("call.") && !key.contains(" ") {
            label = key.localized
        } else if missed {
            label = "call.missed".localized
        } else {
            label = "call.contact.default".localized
        }

        if let duration, !duration.isEmpty {
            return "\(icon) \(label) • \(duration)"
        }
        return "\(icon) \(label)"
    }

    private struct CallJSON: Decodable {
        let direction: String?
        let duration: Double?
        let missed: Bool?
        let type: String?
    }

    private static func callSummaryFromJSON(_ json: String, missed: Bool) -> String {
        guard let data = json.data(using: .utf8),
              let call = try? JSONDecoder().decode(CallJSON.self, from: data) else {
            return (missed ? "❌ " : "📞 ") + (missed ? "call.missed" : "call.contact.default").localized
        }
        let wasMissed = call.missed ?? missed
        let outgoing = (call.direction ?? call.type ?? "").lowercased().contains("out")
        let icon = wasMissed ? "❌" : (outgoing ? "📞↗️" : "📞↙️")
        let label: String
        if wasMissed {
            label = "call.missed".localized
        } else {
            label = (outgoing ? "call.outgoing" : "call.incoming").localized
        }
        if let seconds = call.duration, seconds > 0 {
            return "\(icon) \(label) • \(formatDuration(seconds))"
        }
        return "\(icon) \(label)"
    }

    /// mm:ss / h:mm:ss — no localization, digits only.
    static func formatDuration(_ seconds: Double) -> String {
        let total = Int(max(0, seconds.isFinite ? seconds : 0))
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s)
                     : String(format: "%d:%02d", m, s)
    }

    private struct LocationJSON: Decodable {
        let isLive: Bool?
        let address: String?
    }

    private static func locationSummary(body: String) -> String {
        if let data = body.data(using: .utf8),
           let loc = try? JSONDecoder().decode(LocationJSON.self, from: data) {
            if loc.isLive == true {
                return "📍 " + "location.sharing_live".localized
            }
            if let address = loc.address?.trimmingCharacters(in: .whitespacesAndNewlines),
               !address.isEmpty {
                let short = address.components(separatedBy: ",").first ?? address
                return "📍 " + String(short.prefix(40))
            }
        }
        return "📍 " + "location.shared_location".localized
    }

    private static func mediaMarkerSummary(body: String) -> String {
        // Wire form is "MEDIA|||<type>|||<payload>"; `body` is everything after
        // the marker, so the type is the first field.
        let type = body.components(separatedBy: "|||").first?.lowercased() ?? ""
        switch type {
        case "image", "photo": return "📷 " + "media_image".localized
        case "video":          return "🎥 " + "media_video".localized
        default:               return "📎 " + "media_file".localized
        }
    }

    /// Group media rides as a bare JSON object with `"type":"media"` and no
    /// sentinel, which is one more way raw braces reach a preview.
    private static func mediaJSONSummary(_ text: String) -> String? {
        guard text.hasPrefix("{"),
              text.contains("\"type\":\"media\"") || text.contains("\"type\": \"media\""),
              let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        let mediaType = (json["mediaType"] as? String ?? "").lowercased()
        let caption = (json["caption"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let label: String
        switch mediaType {
        case "photo", "image":  label = "📷 " + "media_image".localized
        case "video":           label = "🎥 " + "media_video".localized
        case "audio", "voice":  label = "🎤 " + "media_audio".localized
        default:                label = "📎 " + "media_file".localized
        }
        return caption.isEmpty ? label : "\(label) \(caption)"
    }

    // MARK: - Envelopes

    private struct Envelope: Decodable { let content: String }

    /// The user body inside a reply / forward envelope, or nil when the JSON is
    /// unreadable — in which case the caller must show NOTHING rather than the
    /// broken JSON, which is what the old `?? text` fallbacks did.
    private static func envelopeBody(_ text: String, prefix: String) -> String? {
        let json = String(text.dropFirst(matchedVariantLength(text, canonical: prefix)))
        guard let data = json.data(using: .utf8),
              let envelope = try? JSONDecoder().decode(Envelope.self, from: data)
        else { return nil }
        return envelope.content
    }

    /// Length of the variant actually present at the head of `text` (which may
    /// be the U+FE0F-stripped spelling, one character shorter than `canonical`).
    /// __OFFLINE_ONLY_CONVERSATION_2026_10_23__
    ///
    /// Un corps illisible est lu comme « toujours restreint ». Un payload abîmé
    /// qui se lirait « l'internet est de nouveau autorisé » annulerait en
    /// silence une restriction demandée; dans l'autre sens il coûte au pire un
    /// message qui attend.
    /// __ALIVE_CHECK_IN_2026_11_04__ The one line a row or a banner shows for an
    /// alive check-in. Reads the `t` field directly rather than parsing the whole
    /// event: this runs once per visible row, and the other three fields have no
    /// bearing on the sentence.
    private static func aliveCheckInLabel(body: String) -> String {
        guard let data = body.data(using: .utf8),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let t = o["t"] as? String
        else { return "alive.summary.confirmed".localized }
        switch t {
        case "on":  return "alive.summary.started".localized
        case "off": return "alive.summary.stopped".localized
        default:    return "alive.summary.confirmed".localized
        }
    }

    private static func offlineOnlySummary(body: String) -> String {
        var on = true
        if let data = body.data(using: .utf8),
           let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let value = obj["on"] as? Bool {
            on = value
        }
        return "🔒 " + (on ? "offline_only.summary.on".localized
                           : "offline_only.summary.off".localized)
    }

    private static func pollSummary(body: String) -> String {
        guard let data = body.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let question = obj["q"] as? String,
              !question.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { return "📊 " + "poll.title".localized }
        return "📊 " + question
    }

    private static func matchedVariantLength(_ text: String, canonical: String) -> Int {
        variants.first { $0.prefix == canonical && text.hasPrefix($0.spelling) }?
            .spelling.count ?? canonical.count
    }
}
