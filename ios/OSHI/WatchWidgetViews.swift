//
//  WatchWidgetViews.swift
//  OSHI — compiled into the Apple Watch widget extension AND the watch app
//  (the app draws them in its DEBUG widget preview for screenshots).
//
//  __APPLE_WATCH_2027__ Complication / Smart Stack widget: the unread COUNT
//  and up to three conversation NAMES. Never message text. When the iPhone
//  says the bridge is off or OSHI is locked, the widget says so and shows no
//  name and no number.
//

import SwiftUI
import WidgetKit

nonisolated enum WatchWidgetText {
    static func L(_ key: String, _ english: String) -> String {
        NSLocalizedString(key, tableName: nil, bundle: .main, value: english, comment: "")
    }
    static var off: String { L("w2.widget.off", "Turn on Smartwatch in OSHI on your iPhone") }
    static var offShort: String { L("w2.widget.off_short", "Off") }
    static var locked: String { L("w2.widget.locked", "Unlock OSHI on your iPhone") }
    static var lockedShort: String { L("w2.widget.locked_short", "Locked") }
    static var open: String { L("w2.widget.open", "Open OSHI") }
    static var allRead: String { L("w2.all_read", "All read") }
    static func unread(_ n: Int) -> String { String(format: L("w2.unread_count", "%d unread"), n) }
    static var displayName: String { L("w2.widget.name", "OSHI unread") }
    static var description: String { L("w2.widget.desc", "Unread count and who wrote — never the messages.") }
}

/// The OSHI ring + tail, same geometry as the app's mark (WatchTheme.OSHIOMark).
struct WidgetOMark: View {
    var size: CGFloat
    var body: some View {
        Canvas { ctx, sz in
            let s = min(sz.width, sz.height) / 64.137
            func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: (x - 21.932) * s, y: (y - 20.327) * s) }
            var ring = Path()
            ring.addEllipse(in: CGRect(origin: p(27, 23), size: CGSize(width: 54 * s, height: 54 * s)))
            ring.addEllipse(in: CGRect(origin: p(39, 35), size: CGSize(width: 30 * s, height: 30 * s)))
            var tail = Path()
            tail.move(to: p(49.31, 76.59))
            tail.addQuadCurve(to: p(37.10, 81.79), control: p(43.80, 81.38))
            tail.addQuadCurve(to: p(33.32, 67.36), control: p(39.84, 68.12))
            tail.closeSubpath()
            ctx.fill(ring, with: .foreground, style: FillStyle(eoFill: true))
            ctx.fill(tail, with: .foreground)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

struct WatchUnreadWidgetView: View {
    let snapshot: WatchBridgeSnapshot?
    let family: WidgetFamily

    private var status: WatchStatus? { snapshot?.status }
    private var unread: Int { snapshot?.status == .ok ? (snapshot?.unread ?? 0) : 0 }
    private var lilac: Color { Color(red: 0xA9 / 255, green: 0x8B / 255, blue: 0xFF / 255) }

    var body: some View {
        switch family {
        case .accessoryCircular: circular
        case .accessoryInline: inline
        case .accessoryCorner: corner
        default: rectangular
        }
    }

    private var circular: some View {
        ZStack {
            AccessoryWidgetBackground()
            VStack(spacing: 0) {
                WidgetOMark(size: 16).foregroundStyle(lilac)
                switch status {
                case .ok?:
                    Text(unread > 99 ? "99+" : "\(unread)")
                        .font(.system(.title3, design: .rounded).weight(.bold))
                        .minimumScaleFactor(0.6)
                case .locked?:
                    Image(systemName: "lock.fill").font(.caption)
                default:
                    Image(systemName: "applewatch.slash").font(.caption)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(accessibilityText))
    }

    private var corner: some View {
        WidgetOMark(size: 22)
            .foregroundStyle(lilac)
            .widgetLabel {
                Text(status == .ok ? WatchWidgetText.unread(unread) : shortStatus)
            }
            .accessibilityLabel(Text(accessibilityText))
    }

    private var inline: some View {
        Text(status == .ok ? "OSHI · " + (unread > 0 ? WatchWidgetText.unread(unread) : WatchWidgetText.allRead)
                           : "OSHI · " + shortStatus)
    }

    private var shortStatus: String {
        switch status {
        case .locked?: return WatchWidgetText.lockedShort
        case .disabled?: return WatchWidgetText.offShort
        default: return WatchWidgetText.open
        }
    }

    private var rectangular: some View {
        VStack(alignment: .leading, spacing: 1) {
            HStack(spacing: 4) {
                WidgetOMark(size: 14).foregroundStyle(lilac)
                Text("OSHI").font(.headline).widgetAccentable()
                Spacer(minLength: 2)
                if status == .ok, unread > 0 {
                    Text(unread > 99 ? "99+" : "\(unread)")
                        .font(.headline.weight(.bold))
                        .foregroundStyle(lilac)
                        .widgetAccentable()
                }
            }
            switch status {
            case .ok?:
                if let names = snapshot?.names, !names.isEmpty {
                    ForEach(Array(names.enumerated()), id: \.offset) { _, n in
                        Text(n).font(.footnote).lineLimit(1)
                    }
                } else {
                    Text(WatchWidgetText.allRead).font(.footnote).foregroundStyle(.secondary)
                }
            case .locked?:
                Label(WatchWidgetText.locked, systemImage: "lock.fill")
                    .font(.footnote).lineLimit(2)
            case .disabled?:
                Text(WatchWidgetText.off).font(.footnote).lineLimit(3)
            default:
                Text(WatchWidgetText.open).font(.footnote)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private var accessibilityText: String {
        switch status {
        case .ok?: return "OSHI, " + (unread > 0 ? WatchWidgetText.unread(unread) : WatchWidgetText.allRead)
        case .locked?: return "OSHI, " + WatchWidgetText.locked
        case .disabled?: return "OSHI, " + WatchWidgetText.off
        default: return "OSHI, " + WatchWidgetText.open
        }
    }
}
