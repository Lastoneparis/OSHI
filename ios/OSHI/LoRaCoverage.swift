import Foundation
import CoreLocation

/// LoRaCoverage — an ESTIMATED reach ring for a radio on the map.
///
/// WHY A RING AT ALL
/// -----------------
/// Requested: "put a circle of their connection, to see if they are reachable,
/// like Meshtastic does." The question behind it is the right one — a list of
/// nodes tells you who exists, not whether a message would get through — and a
/// ring answers it at a glance.
///
/// WHY IT IS DRAWN SOFT, AND WHY THAT IS NOT LAZINESS
/// --------------------------------------------------
/// A radius computed from signal strength is an ESTIMATE and a weak one. Radio
/// coverage is not a circle: it is a shape carved by terrain, buildings,
/// antenna orientation, humidity and the other radio's transmit power, none of
/// which we can see from a received packet. The same −90 dBm means two
/// kilometres across a lake and eighty metres through a city block.
///
/// So the ring is deliberately rendered with a soft fill and a dashed edge
/// rather than a crisp boundary. That is a correctness decision rather than a
/// decorative one: a sharp circle asserts "inside this line you are reachable",
/// and somebody standing just inside it in a dead zone — the exact situation
/// this app exists for — would have relied on a line we invented. A gradient
/// says "roughly this far", which is the truth.
///
/// THE TRAP: A RELAYED NODE'S SIGNAL IS NOT ITS OWN
/// ------------------------------------------------
/// [radiusMeters] returns nil for anything heard through a relay, and this is
/// the part most worth keeping. When `hopsAway > 0`, the RSSI and SNR we
/// measured belong to the LAST HOP — the neighbour that forwarded the packet —
/// not to the node that originated it. Drawing a ring of that size around the
/// distant node would be a confident circle built from a measurement of
/// somebody else entirely, and it would be wrong by however far the relay
/// happens to be. A node we can only reach through a relay gets no ring,
/// because we have no measurement of our link to it. That is not missing
/// information; it is the honest absence of it.
enum LoRaCoverage {

    /// Signal floor below which a link is not usefully alive.
    ///
    /// LongFast demodulates to roughly −130 dBm in ideal conditions; −125 is a
    /// practical floor where packets still arrive but barely.
    static let minUsableRSSI = -125

    /// Signal at which the other radio is effectively next to you.
    static let maxRSSI = -30

    /// Clamp on what we are willing to claim, in metres.
    ///
    /// The upper bound is NOT the record for LoRa — hilltop-to-hilltop links of
    /// hundreds of kilometres exist. It is the furthest we will assert from a
    /// single RSSI reading on the ground, because beyond a few kilometres the
    /// estimate is dominated by terrain we cannot see and the ring would be
    /// fiction drawn at map scale.
    static let minRadius: Double = 150
    static let maxRadius: Double = 5_000

    /// An estimated reach radius in metres, or nil when no honest estimate
    /// exists.
    ///
    /// nil when: the node was never heard directly (no RSSI), or it was heard
    /// through a relay (see the class comment), or the signal is below the
    /// usable floor.
    static func radiusMeters(rssi: Int?, hopsAway: Int?) -> Double? {
        // Heard through someone else: we have no measurement of OUR link.
        if let hopsAway, hopsAway > 0 { return nil }
        guard let rssi, rssi >= minUsableRSSI, rssi <= 0 else { return nil }

        // Normalised link quality, 0 at the floor and 1 touching the radio.
        let span = Double(maxRSSI - minUsableRSSI)
        let quality = min(1, max(0, Double(rssi - minUsableRSSI) / span))

        // Inverted: a STRONG signal means the other radio is close, so the
        // circle of "where this link reaches" is small and tight around it; a
        // weak signal means it is far away and already at the edge of what this
        // link covers. The ring shows the uncertainty of the link, so it grows
        // as confidence falls.
        //
        // Squared rather than linear because path loss is logarithmic in
        // distance: a 10 dB drop is roughly a tripling of range, not a 10%
        // change, so a linear mapping would make every mid-strength node look
        // identical.
        let weakness = 1 - quality
        return minRadius + (maxRadius - minRadius) * (weakness * weakness)
    }

    /// How opaque the ring's fill should be — fainter when less certain, so a
    /// large speculative ring is visually quieter than a small confident one.
    static func fillOpacity(rssi: Int?, hopsAway: Int?) -> Double {
        guard let radius = radiusMeters(rssi: rssi, hopsAway: hopsAway) else { return 0 }
        let spread = (radius - minRadius) / (maxRadius - minRadius)
        return 0.16 - 0.10 * spread      // 0.16 tight and sure → 0.06 wide and vague
    }
}

import MapKit

/// An `MKCircle` that remembers how certain it is, so the renderer can draw a
/// vague ring more faintly than a confident one.
///
/// A subclass rather than a lookup table keyed on coordinate: two radios can
/// sit at the same position (a pair on one desk), and a coordinate key would
/// collide and give one of them the other's opacity.
final class LoRaCoverageCircle: MKCircle {
    /// Set at construction; see `LoRaCoverage.fillOpacity`.
    var fillOpacity: Double = 0.12
    /// The node this ring belongs to, for diffing on update.
    var nodeNum: UInt32 = 0
}
