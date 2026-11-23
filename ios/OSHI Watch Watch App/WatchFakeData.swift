//
//  WatchFakeData.swift
//  OSHI Watch
//
//  __APPLE_WATCH_2027__ DEBUG-ONLY canned bridge for the simulator and App
//  Store screenshots — the twin of Wear's `-PwearFakeData`. Release builds
//  contain only the `isActive == false` stub below.
//
//  simctl launch <udid> Moriceau-lab.Genesis.watchkitapp -OSHIWatchFake YES \
//      [-OSHIWatchMode disabled|locked|unavailable|nophone|empty] \
//      [-OSHIWatchOpen dm|group|reply|widget]
//

import Foundation
import SwiftUI
import WidgetKit

#if DEBUG

enum WatchFakeData {
    static var isActive: Bool { UserDefaults.standard.bool(forKey: "OSHIWatchFake") }
    static var mode: String { UserDefaults.standard.string(forKey: "OSHIWatchMode") ?? "" }
    static var open: String { UserDefaults.standard.string(forKey: "OSHIWatchOpen") ?? "" }

    static func installIfRequested() {
        guard isActive else { return }
        let fake = FakeBridgeSource(mode: mode)
        WatchStore.shared.use(fake)
        switch open {
        case "dm", "reply":
            WatchStore.shared.pendingOpen = WatchConversationRef(kind: .direct, cid: FakeBridgeSource.lea, name: "Léa Martin")
        case "group":
            WatchStore.shared.pendingOpen = WatchConversationRef(kind: .group, cid: FakeBridgeSource.crew, name: "Climbing crew")
        default:
            break
        }
        if open != "widget" {
            WatchWidgetSnapshotStore.write(.make(status: mode.isEmpty ? .ok : .locked, unread: 8,
                                                 names: ["Léa Martin", "Climbing crew", "Noor"]))
        }
    }

    static var autoOpenReply: Bool { isActive && open == "reply" }
    /// "top" / "bottom": where the thread / reply screen starts (screenshots).
    static var scroll: String { isActive ? (UserDefaults.standard.string(forKey: "OSHIWatchScroll") ?? "") : "" }
    static var showWidgetGallery: Bool { isActive && open == "widget" }
}

actor FakeBridgeSource: WatchBridgeSource {
    static let lea = "q3V8bD0Zr2kLx9tYp1MfQe7wHc4nJ6aSu5gR0iTzKoE="
    static let crew = "6f1c2b9e-3a44-4d1f-9b0e-1d2c3e4f5a6b"
    static let noor = "Zx1Yw2Vu3Ts4Rq5Po6Nm7Lk8Ji9Hg0FeDcBa1234567="
    static let mom = "Mm0Nn1Oo2Pp3Qq4Rr5Ss6Tt7Uu8Vv9Ww0Xx1Yy2Zz3="
    static let alex = "AaBbCcDdEeFf0011223344556677889900aabbccdd="
    static let welcome = "oshi.welcome"

    private let mode: String
    private var convs: [WatchConversation]
    private var threads: [String: [WatchMessage]]

    init(mode: String) {
        self.mode = mode
        let now = Date().timeIntervalSince1970
        func c(_ kind: WatchConvKind, _ cid: String, _ name: String, _ preview: String, _ unread: Int,
               _ ago: Double, mine: Bool = false, media: WatchMedia? = nil, ro: Bool = false) -> WatchConversation {
            var x = WatchConversation(kind: kind, cid: cid, name: name, preview: preview, unread: unread,
                                      ts: now - ago * 60, akey: cid)
            x.mine = mine; x.pmedia = media; x.ro = ro
            return x
        }
        convs = mode == "empty" ? [] : [
            c(.direct, Self.lea, "Léa Martin", "On se retrouve à 18h devant la gare ?", 2, 3),
            c(.group, Self.crew, "Climbing crew", "Sam: ", 5, 9, media: .voice),
            c(.direct, Self.noor, "Noor", "", 1, 42, media: .photo),
            c(.direct, Self.mom, "Mom", "Call me when you land ❤️", 0, 130),
            c(.direct, Self.alex, "Alex", "Deal, see you Saturday", 0, 1440, mine: true),
        ]
        func m(_ id: String, _ mine: Bool, _ text: String, _ ago: Double, sender: String? = nil, skey: String? = nil,
               media: WatchMedia? = nil, vo: Bool = false, reacts: [String] = [], myr: String? = nil) -> WatchMessage {
            var x = WatchMessage(mid: id, mine: mine, text: text, ts: now - ago * 60)
            x.sender = sender; x.skey = skey; x.media = media; x.vo = vo; x.reacts = reacts; x.myr = myr
            return x
        }
        threads = [
            "dm:" + Self.lea: [
                m("l1", false, "Tu as vu le message de Noor ?", 50),
                m("l2", true, "Oui ! Elle arrive demain matin", 48, reacts: ["\u{1F44D}"]),
                m("l3", false, "", 30, media: .photo),
                m("l4", false, "", 20, vo: true),
                m("l5", true, "Trop bien 😍", 12),
                m("l6", false, "On se retrouve à 18h devant la gare ?", 3, reacts: ["\u{2764}\u{FE0F}", "\u{2B55}"]),
            ],
            "group:" + Self.crew: [
                m("g1", false, "Who's in for Sunday?", 120, sender: "Chloé", skey: "chloe-key-1"),
                m("g2", true, "Me! 🧗", 110),
                m("g3", false, "Meet at the gym at 10, bring the long rope", 30, sender: "Chloé", skey: "chloe-key-1",
                  reacts: ["\u{1F525}", "\u{1F44D}"]),
                m("g4", false, "", 9, sender: "Sam", skey: "sam-key-2", media: .voice),
            ],
        ]
    }

    func request(_ req: WatchRequest) async -> WatchResponse? {
        try? await Task.sleep(nanoseconds: 120_000_000)
        switch mode {
        case "nophone": return nil
        case "disabled": return .status(req.rid, .disabled)
        case "locked": return .status(req.rid, .locked)
        case "unavailable": return .status(req.rid, .unavailable)
        default: break
        }
        guard req.isWellFormed else { return .status(req.rid, .invalid) }
        let key = (req.kind?.rawValue ?? "") + ":" + (req.cid ?? "")
        switch req.op {
        case .list:
            var r = WatchResponse(rid: req.rid, status: .ok)
            r.convs = convs
            return r
        case .thread:
            guard let conv = convs.first(where: { $0.id == key }) else { return .status(req.rid, .notFound) }
            var r = WatchResponse(rid: req.rid, status: .ok)
            r.msgs = threads[key] ?? [WatchMessage(mid: key + ".1", mine: false,
                                                    text: conv.preview.isEmpty ? "👋" : conv.preview, ts: conv.ts)]
            r.title = conv.name
            r.ro = conv.ro
            return r
        case .send:
            var msgs = threads[key] ?? []
            msgs.append(WatchMessage(mid: UUID().uuidString, mine: true, text: req.text ?? "",
                                     ts: Date().timeIntervalSince1970))
            threads[key] = msgs
            return .status(req.rid, .ok)
        case .react:
            guard var msgs = threads[key], let i = msgs.firstIndex(where: { $0.mid == req.mid }) else {
                return .status(req.rid, .notFound)
            }
            let e = req.emoji ?? ""
            if msgs[i].myr == e {
                msgs[i].myr = nil
                msgs[i].reacts.removeAll { $0 == e }
            } else {
                if let old = msgs[i].myr { msgs[i].reacts.removeAll { $0 == old } }
                msgs[i].myr = e
                if !msgs[i].reacts.contains(e) { msgs[i].reacts.insert(e, at: 0) }
            }
            threads[key] = msgs
            return .status(req.rid, .ok)
        case .markRead:
            if let i = convs.firstIndex(where: { $0.id == key }) {
                let c = convs[i]
                var read = WatchConversation(kind: c.kind, cid: c.cid, name: c.name, preview: c.preview,
                                             unread: 0, ts: c.ts, akey: c.akey)
                read.mine = c.mine; read.pmedia = c.pmedia; read.ro = c.ro
                convs[i] = read
            }
            return .status(req.rid, .ok)
        }
    }
}

/// DEBUG: the widget's four families drawn in-app, for screenshots (the
/// simulator gives no scriptable way to put a complication on a face).
struct WidgetGalleryView: View {
    @State private var page = 0
    private let ok = WatchBridgeSnapshot.make(status: .ok, unread: 8, names: ["Léa Martin", "Climbing crew", "Noor"])
    private let locked = WatchBridgeSnapshot.make(status: .locked, unread: 0, names: [])

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                WatchUnreadWidgetView(snapshot: ok, family: .accessoryRectangular)
                    .padding(8)
                    .background(RoundedRectangle(cornerRadius: 14).fill(Color.white.opacity(0.08)))
                HStack(spacing: 10) {
                    WatchUnreadWidgetView(snapshot: ok, family: .accessoryCircular)
                        .frame(width: 50, height: 50)
                        .clipShape(Circle())
                    WatchUnreadWidgetView(snapshot: locked, family: .accessoryCircular)
                        .frame(width: 50, height: 50)
                        .clipShape(Circle())
                }
                WatchUnreadWidgetView(snapshot: ok, family: .accessoryInline)
                    .font(.footnote)
                WatchUnreadWidgetView(snapshot: locked, family: .accessoryRectangular)
                    .padding(8)
                    .background(RoundedRectangle(cornerRadius: 14).fill(Color.white.opacity(0.08)))
            }
            .padding(.horizontal, 4)
        }
        .background(Color.black)
    }
}

#else

enum WatchFakeData {
    static var isActive: Bool { false }
    static var autoOpenReply: Bool { false }
    static var showWidgetGallery: Bool { false }
    static var scroll: String { "" }
}

#endif
