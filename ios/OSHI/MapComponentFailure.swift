//
//  MapComponentFailure.swift
//  OSHI
//
//  __MAP_STALLED_AT_24_PERCENT_2026_10_28__
//

import Foundation

/// Has one part of a region download given up, and may the ring say so?
///
/// Reported from the field: downloading Haute-Savoie went "from 2% to 24% almost
/// instantly, then blocked at 24%". Both halves are explained by the same arithmetic,
/// and only the second half is a defect.
///
/// **The jump is honest.** `aggregateProgress` weights each component by its bytes, and
/// for `fr-74` the manifest gives poi 3.2 MB, streets 78.5 MB and roadgraph 272.4 MB —
/// so streets alone is 22% of the download. `fr-streets.db.gz` is SHARED by every French
/// region (`"shared": true`), so anyone who has already taken one French region has it on
/// disk, `isStreetIndexAvailable` answers true, and 22% appears the instant the tap lands.
/// Nothing is wrong there.
///
/// **The freeze is the bug, and it is a missing reader.** Each downloader publishes its own
/// error, and the region screen reads only two of them: routing
/// (`MapRegionDownloadView.swift:411`) and streets (`:482`). `OfflineRoadGraphDownloader`
/// publishes `downloadError` that NOTHING anywhere reads, and the POI path had no error
/// channel at all — it removed its progress entry and returned. So when the roadgraph
/// stopped, its progress was deleted, the weighted average fell, and
/// `aggregateProgressFloor` — which exists so the ring never runs backwards — pinned the
/// display at the last good value for ever.
///
/// For `fr-74` that last good value is poi + streets ≈ 23%, with a little tile progress on
/// top. The reported 24% is not a coincidence: it is the exact share of the two components
/// that finished, with the 77% one silently dead behind it.
///
/// The failing component is most often the roadgraph for the most ordinary reason: it is
/// 272 MB compressed and `downloadRoadGraph` refuses up front when the decompressed graph
/// plus headroom will not fit. That refusal was already written, already correct, and
/// invisible.
enum MapComponentFailure {

    /// One component's state, as the screen can observe it.
    struct Component {
        /// For the message. Not localised here: the caller holds the catalog.
        let id: String
        /// Already on disk — a finished component can never be the failure.
        let available: Bool
        /// Still working. A component in flight has not given up, however long it takes.
        let downloading: Bool
        /// What it last reported, or nil.
        let error: String?
    }

    /// The first component that has stopped with an error, or nil while there is hope.
    ///
    /// Deliberately pure and deliberately ORDERED by the caller: with two components dead
    /// the user is told about the first one the caller listed, which lets the caller put
    /// the heaviest — the one actually blocking the number on screen — first.
    ///
    /// `available` is checked before `error` on purpose. A component can fail once, be
    /// retried, and succeed; a stale error string must not keep a finished download looking
    /// broken. `downloading` is checked too, for the same reason in the other direction:
    /// a retry in flight is not a failure yet.
    static func first(in components: [Component]) -> Component? {
        components.first { c in
            guard !c.available, !c.downloading else { return false }
            guard let e = c.error, !e.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
            return true
        }
    }

    /// Does this region still have somewhere to go, or is every unfinished part stopped?
    ///
    /// Used to decide whether a ring is merely slow or is never going to move again. A
    /// download with ANY component still in flight is slow, not stuck — the roadgraph for a
    /// French département is a quarter of a gigabyte and a phone on a train will take a
    /// while, which must not be reported as an error.
    static func allStopped(_ components: [Component]) -> Bool {
        let unfinished = components.filter { !$0.available }
        guard !unfinished.isEmpty else { return false }
        return unfinished.allSatisfy { !$0.downloading }
    }
}
