//
//  OSHIWatchWidgets.swift
//  OSHI Watch Widgets (watchOS widget extension)
//
//  __APPLE_WATCH_2027__ Complications and the Smart Stack card: unread count
//  + up to three conversation names, read from the snapshot the watch app
//  keeps in the app-group container (WatchWidgetSnapshotStore). The widget
//  never asks the phone anything and never sees a message.
//

import SwiftUI
import WidgetKit

struct UnreadEntry: TimelineEntry {
    let date: Date
    let snapshot: WatchBridgeSnapshot?
    let relevance: TimelineEntryRelevance?
}

struct UnreadProvider: TimelineProvider {
    func placeholder(in context: Context) -> UnreadEntry {
        UnreadEntry(date: Date(),
                    snapshot: .make(status: .ok, unread: 3, names: ["Léa", "Climbing crew", "Noor"]),
                    relevance: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (UnreadEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : entry())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<UnreadEntry>) -> Void) {
        // The watch app reloads this timeline whenever the snapshot changes;
        // the hourly refresh only re-reads the file.
        completion(Timeline(entries: [entry()], policy: .after(Date().addingTimeInterval(3600))))
    }

    private func entry() -> UnreadEntry {
        let snap = WatchWidgetSnapshotStore.read()
        let unread = snap?.status == .ok ? (snap?.unread ?? 0) : 0
        // Smart Stack: rise when something is waiting, sink when all is read.
        return UnreadEntry(date: Date(), snapshot: snap,
                           relevance: TimelineEntryRelevance(score: unread > 0 ? Float(min(unread, 10)) : 0))
    }
}

struct OSHIUnreadWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WatchWidgetSnapshotStore.widgetKind, provider: UnreadProvider()) { entry in
            UnreadWidgetEntryView(entry: entry)
        }
        .configurationDisplayName(WatchWidgetText.displayName)
        .description(WatchWidgetText.description)
        .supportedFamilies([.accessoryRectangular, .accessoryCircular, .accessoryInline, .accessoryCorner])
    }
}

struct UnreadWidgetEntryView: View {
    let entry: UnreadEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        WatchUnreadWidgetView(snapshot: entry.snapshot, family: family)
            .containerBackground(for: .widget) { Color.black }
    }
}

@main
struct OSHIWatchWidgets: WidgetBundle {
    var body: some Widget {
        OSHIUnreadWidget()
    }
}
