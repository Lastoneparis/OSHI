//
//  WatchStrings.swift
//  OSHI Watch
//
//  __APPLE_WATCH_2027__ Every user-facing string of the watch UI, with its
//  English default (see `L`). Keys `w2.*`, translated in all 34 `.lproj`;
//  wording kept in step with Android Wear's `strings.xml`.
//

import Foundation

enum WS {
    static var chats: String { L("w2.chats", "Chats") }
    static var emptyTitle: String { L("w2.empty.title", "No conversations yet") }
    static var emptyBody: String { L("w2.empty.body", "Your OSHI chats from your iPhone show up here.") }
    static var disabledTitle: String { L("w2.disabled.title", "Smartwatch is off") }
    static var disabledBody: String { L("w2.disabled.body", "Turn it on in OSHI on your iPhone: Settings › Linked devices › Smartwatch.") }
    static var lockedTitle: String { L("w2.locked.title", "OSHI is locked") }
    static var lockedBody: String { L("w2.locked.body", "Unlock OSHI on your iPhone, then try again.") }
    static var unavailableTitle: String { L("w2.unavailable.title", "OSHI isn't ready") }
    static var unavailableBody: String { L("w2.unavailable.body", "Open OSHI once on your iPhone, then try again.") }
    static var noPhoneTitle: String { L("w2.nophone.title", "iPhone not reachable") }
    static var noPhoneBody: String { L("w2.nophone.body", "Keep your iPhone nearby with OSHI installed.") }
    static var errorTitle: String { L("w2.error.title", "Something went wrong") }
    static var errorBody: String { L("w2.error.body", "Your iPhone didn't answer. Try again.") }
    static var notFound: String { L("w2.notfound", "This conversation is no longer on your iPhone.") }
    static var retry: String { L("w2.retry", "Retry") }
    static var reply: String { L("w2.reply", "Reply") }
    static var you: String { L("w2.you", "You") }
    static func youPrefix(_ s: String) -> String { String(format: L("w2.you_prefix", "You: %@"), s) }
    static var react: String { L("w2.react", "React") }
    static var speakOrType: String { L("w2.speak_or_type", "Speak or type") }
    static var quickReplies: String { L("w2.quick_replies", "Quick replies") }
    static var sending: String { L("w2.sending", "Sending…") }
    static var sent: String { L("w2.sent", "Sent") }
    static var sendFailed: String { L("w2.send_failed", "Not sent. Try again on your iPhone.") }
    static var readOnly: String { L("w2.read_only", "You can't reply to this conversation.") }
    static var viewOnce: String { L("w2.view_once", "👁 View once — open it on your iPhone") }
    static var viewOnceShort: String { L("w2.view_once_short", "👁 View once") }
    static var endToEnd: String { L("w2.e2e", "End-to-end encrypted by your iPhone") }
    static var replyingTo: String { L("w2.replying_to", "Replying to") }
    static var loading: String { L("w2.loading", "Loading…") }
    static func unreadCount(_ n: Int) -> String { String(format: L("w2.unread_count", "%d unread"), n) }
    static var allRead: String { L("w2.all_read", "All read") }
    static var call: String { L("w2.call", "Call") }

    static func media(_ m: WatchMedia) -> String {
        switch m {
        case .photo: return L("w2.media.photo", "📷 Photo")
        case .video: return L("w2.media.video", "🎥 Video")
        case .voice: return L("w2.media.voice", "🎤 Voice")
        case .gif: return L("w2.media.gif", "GIF")
        case .file: return L("w2.media.file", "📄 File")
        case .location: return L("w2.media.location", "📍 Location")
        case .contact: return L("w2.media.contact", "👤 Contact")
        }
    }

    /// The seven quick replies, in Wear's order.
    static var quickReplyList: [String] {
        [L("w2.qr.on_my_way", "On my way"),
         L("w2.qr.ok", "OK 👍"),
         L("w2.qr.call_later", "I'll call you later"),
         L("w2.qr.thanks", "Thanks!"),
         L("w2.qr.yes", "Yes"),
         L("w2.qr.no", "No"),
         L("w2.qr.busy", "Can't talk now")]
    }

    /// VoiceOver names of the quick reactions.
    static func reactionName(_ wire: String) -> String {
        switch wire {
        case "\u{2B55}": return L("w2.r.oshi", "OSHI")
        case "\u{2764}\u{FE0F}": return L("w2.r.love", "Love")
        case "\u{1F602}": return L("w2.r.laugh", "Laugh")
        case "\u{1F622}": return L("w2.r.cry", "Sad")
        case "\u{1F44D}": return L("w2.r.thumbs", "Thumbs up")
        case "\u{1F62E}": return L("w2.r.wow", "Wow")
        case "\u{1F525}": return L("w2.r.fire", "Fire")
        default: return wire
        }
    }

    static func statusTitle(_ s: WatchStatus) -> String {
        switch s {
        case .disabled: return disabledTitle
        case .locked: return lockedTitle
        case .unavailable: return unavailableTitle
        case .notFound: return errorTitle
        default: return errorTitle
        }
    }

    static func statusBody(_ s: WatchStatus) -> String {
        switch s {
        case .disabled: return disabledBody
        case .locked: return lockedBody
        case .unavailable: return unavailableBody
        case .notFound: return notFound
        case .readOnly: return readOnly
        default: return errorBody
        }
    }
}
