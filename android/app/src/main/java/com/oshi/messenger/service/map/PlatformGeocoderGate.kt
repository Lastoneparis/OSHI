package com.oshi.messenger.service.map

import android.content.Context

/**
 * PlatformGeocoderGate — the "Offline maps only" gate for `android.location.Geocoder`.
 *
 * ══ WHY THIS EXISTS ══
 *
 * `android.location.Geocoder` is **not** an on-device library. On a stock Android
 * device it is backed by the Google geocoding backend: `getFromLocationName(query, n)`
 * ships the raw query string — and `getFromLocation(lat, lon, n)` ships the user's
 * exact coordinates — to Google over the network. It is indistinguishable, from a
 * privacy standpoint, from calling Nominatim.
 *
 * The "Offline maps only" switch promises the opposite: that nothing about where the
 * user is or where they are going leaves the device. Every other consumer in the map
 * stack was gated on that promise —
 *
 *   * tiles      → `OshiOfflineTileProvider.create` drops the network module
 *   * previews   → `OshiStaticMapRenderer.loadTile` returns null
 *   * Nominatim  → `MapViewModel.searchNominatim` guarded
 *   * Overpass   → `MapViewModel.searchOverpassNearby` guarded
 *   * OSRM       → `MapViewModel.fetchOSRMRoute` guarded
 *
 * — and `ItineraryScreen` was not. Typing a destination with the switch ON still sent
 * that address to Google (2026-08-23 map audit §5, recorded there as the single
 * highest-priority open item).
 *
 * ══ WHY IT IS A SEPARATE, PURE OBJECT ══
 *
 * The decision is worth testing on its own, and a decision buried inside a
 * `@Composable`'s local `fun` cannot be. [decide] with an explicit `offlineOnly`
 * takes no Android dependency at all, so a JVM unit test can assert the property
 * that actually matters:
 *
 *   **for every possible query, `offlineOnly = true` never yields [Decision.Geocode].**
 *
 * That is a structural proof, not a wire proof. See the report: no packet capture was
 * possible this round.
 */
object PlatformGeocoderGate {

    /**
     * Shortest query worth geocoding. Matches the threshold `ItineraryScreen` has
     * always used; kept here so the gate and the caller cannot drift apart.
     */
    const val MIN_QUERY_LENGTH = 2

    /** What the caller is allowed to do with a given query. */
    sealed interface Decision {
        /**
         * Nothing to do: the query is blank or shorter than [MIN_QUERY_LENGTH].
         * The caller must clear any previous suggestions (a leftover dropdown from
         * an earlier, longer query is a stale value) and show no message.
         */
        object Idle : Decision

        /**
         * "Offline maps only" is ON. The caller must **not** touch
         * `android.location.Geocoder`, must clear previous suggestions, and must
         * tell the user why the field went quiet.
         */
        object BlockedOfflineOnly : Decision

        /** Online geocoding is permitted for this query. */
        object Geocode : Decision
    }

    /**
     * Pure decision. No Android types, no I/O — the whole point.
     *
     * Order matters and is deliberate: a one-character query is [Decision.Idle] even
     * when offline-only is on, so the user does not get a warning banner for having
     * typed a single letter. The privacy guarantee is unaffected, because
     * [Decision.Geocode] is unreachable whenever `offlineOnly` is true.
     */
    fun decide(query: String, offlineOnly: Boolean): Decision {
        if (query.trim().length < MIN_QUERY_LENGTH) return Decision.Idle
        if (offlineOnly) return Decision.BlockedOfflineOnly
        return Decision.Geocode
    }

    /**
     * Same decision, reading the live preference through the one function that owns
     * it ([OshiOfflineTileProvider.isOfflineOnly] — which ORs the legacy
     * `oshi_settings` key, so users who turned the switch on before the key moved are
     * still honoured).
     */
    @JvmStatic
    fun decide(context: Context, query: String): Decision =
        decide(query, OshiOfflineTileProvider.isOfflineOnly(context))
}
