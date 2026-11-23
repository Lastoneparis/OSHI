package com.oshi.messenger.service.lora

/**
 * LoRaCoverage — an ESTIMATED reach ring for a radio on the map.
 *
 * Port of `OSHI/LoRaCoverage.swift`, numbers and rules identical so the two
 * platforms draw the same ring for the same radio.
 *
 * WHY IT IS DRAWN SOFT, AND WHY THAT IS NOT LAZINESS
 * --------------------------------------------------
 * A radius computed from signal strength is a weak estimate. Radio coverage is
 * not a circle: it is a shape carved by terrain, buildings, antenna
 * orientation, humidity and the other radio's transmit power, none of which a
 * received packet reveals. The same −90 dBm means two kilometres across a lake
 * and eighty metres through a city block.
 *
 * So the ring is a faint fill with a dashed edge, never a crisp boundary. That
 * is a correctness decision: a sharp circle asserts "inside this line you are
 * reachable", and somebody standing just inside it in a dead zone — the exact
 * situation this app exists for — would have relied on a line we invented.
 *
 * THE TRAP: A RELAYED NODE'S SIGNAL IS NOT ITS OWN
 * ------------------------------------------------
 * [radiusMeters] returns null for anything heard through a relay. When
 * `hopsAway > 0` the RSSI we measured belongs to the LAST HOP — the neighbour
 * that forwarded the packet — not to the node that originated it. A ring of
 * that size around the distant node would be a confident circle built from a
 * measurement of someone else entirely, wrong by however far the relay happens
 * to be. No measurement of our link to that node exists, so the map draws
 * nothing and says so.
 *
 * Note this is a DIFFERENT quantity from [LoRaMeshNode.estimatedRangeKm], which
 * answers "how far away is this node" for a node with no GPS. This answers "how
 * far does our link to it plausibly reach", and is only drawn for nodes that DO
 * have a position.
 */
object LoRaCoverage {

    /** Signal floor below which a link is not usefully alive (LongFast). */
    const val MIN_USABLE_RSSI = -125

    /** Signal at which the other radio is effectively next to you. */
    const val MAX_RSSI = -30

    const val MIN_RADIUS_M = 150.0

    /**
     * The furthest we will assert from a single ground-level RSSI reading. Not
     * the record for LoRa — hilltop links run hundreds of kilometres — but
     * beyond this the estimate is dominated by terrain we cannot see and the
     * ring would be fiction drawn at map scale.
     */
    const val MAX_RADIUS_M = 5_000.0

    /** Estimated reach radius in metres, or null when no honest estimate exists. */
    fun radiusMeters(rssi: Int?, hopsAway: Int?): Double? {
        if (hopsAway != null && hopsAway > 0) return null
        if (rssi == null || rssi < MIN_USABLE_RSSI || rssi > 0) return null

        val span = (MAX_RSSI - MIN_USABLE_RSSI).toDouble()
        val quality = ((rssi - MIN_USABLE_RSSI) / span).coerceIn(0.0, 1.0)

        // Inverted and squared: a strong signal means the radio is close, so the
        // ring is tight; a weak one means we are already at the edge of what
        // this link covers. Squared because path loss is logarithmic in distance
        // — a 10 dB drop is roughly a tripling of range, so linear scaling made
        // every mid-strength node look identical.
        val weakness = 1 - quality
        return MIN_RADIUS_M + (MAX_RADIUS_M - MIN_RADIUS_M) * (weakness * weakness)
    }

    /**
     * Fill opacity — fainter when less certain, so the least trustworthy drawing
     * on the map is also the quietest.
     */
    fun fillOpacity(rssi: Int?, hopsAway: Int?): Double {
        val radius = radiusMeters(rssi, hopsAway) ?: return 0.0
        val spread = (radius - MIN_RADIUS_M) / (MAX_RADIUS_M - MIN_RADIUS_M)
        return 0.16 - 0.10 * spread
    }
}
