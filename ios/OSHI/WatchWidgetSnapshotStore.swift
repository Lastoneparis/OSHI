//
//  WatchWidgetSnapshotStore.swift
//  OSHI — compiled into the Apple Watch app and its widget extension.
//
//  __APPLE_WATCH_2027__ The ONE thing the watch writes to disk: the widget's
//  `WatchBridgeSnapshot` — a status, an unread NUMBER and at most three
//  conversation NAMES. Never a message, a preview, a key or an avatar. It sits
//  in the app-group container (the widget extension is another process),
//  excluded from backup, and is rewritten without names the moment the phone
//  says "off" or "locked".
//

import Foundation

nonisolated enum WatchWidgetSnapshotStore {
    static let appGroup = "group.Moriceau-lab.Genesis"
    static let widgetKind = "OSHIUnread"
    private static let fileName = "oshi-watch-widget.json"

    static var url: URL? {
        FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
            .appendingPathComponent(fileName)
    }

    static func read() -> WatchBridgeSnapshot? {
        guard let url, let data = try? Data(contentsOf: url) else { return nil }
        return WatchBridgeSnapshot.decode([WatchBridge.snapshotKey: data])
    }

    /// Returns true when the stored value changed (so the caller reloads the
    /// widget timelines only when there is something new to draw).
    @discardableResult
    static func write(_ snapshot: WatchBridgeSnapshot) -> Bool {
        let clean = WatchBridgeSnapshot.make(status: snapshot.status, unread: snapshot.unread,
                                             names: snapshot.names,
                                             at: Date(timeIntervalSince1970: snapshot.at))
        if let old = read(), old.status == clean.status, old.unread == clean.unread, old.names == clean.names {
            return false
        }
        guard var url, let data = clean.encode()[WatchBridge.snapshotKey] as? Data else { return false }
        do {
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? url.setResourceValues(values)
            return true
        } catch {
            return false
        }
    }

    static func clear() {
        guard let url else { return }
        try? FileManager.default.removeItem(at: url)
    }
}
