package com.oshi.messenger.data.model

/**
 * Why the mesh is finding nobody — the difference between "searching" and "switched off".
 *
 * __SEARCHING_IS_NOT_ALWAYS_TRUE_2026_10_28__ (N15, reported by a user: "ajouter sur la
 * première page à l'ouverture de l'application un raccourci permettant de voir si la
 * détection des appareils à proximité, du bluetooth ou du wifi n'est pas activé")
 *
 * The antenna badge on the conversations screen says "Searching for peers…" whenever it has
 * found none. That sentence is TRUE when the radios are on and nobody is in range, and a
 * LIE when Bluetooth is off — and the two look identical. A mesh messenger whose whole
 * point is working without infrastructure should not quietly report that it is looking when
 * it cannot look.
 *
 * Pure, and returns the FIRST blocking reason rather than a set: a person fixes one thing,
 * comes back, and is told the next. A list of four problems at once is a wall; one problem
 * with one fix is an instruction.
 *
 * ORDERED by what blocks the most. Permission before radio, because a permission the app
 * never got stops the scan entirely and silently, whereas a radio the user turned off is
 * something they may have meant. Bluetooth before Wi-Fi because the BLE transport is the
 * one that works with no network at all, which is the case this app exists for.
 */
object MeshReadiness {

    enum class Reason {
        /** The runtime permission to scan for nearby devices was never granted. */
        NEARBY_PERMISSION,
        /** Bluetooth is switched off at the system level. */
        BLUETOOTH_OFF,
        /** Wi-Fi is off, so the LAN transport cannot see anyone. */
        WIFI_OFF,
    }

    /**
     * @param hasNearbyPermission BLUETOOTH_SCAN/CONNECT on API 31+, ACCESS_FINE_LOCATION
     *   below it, NEARBY_WIFI_DEVICES on 33+ — resolved by the caller, which is the only
     *   place that knows the API level.
     * @return the one thing to fix, or null when the radios really are looking.
     */
    fun firstBlocker(
        hasNearbyPermission: Boolean,
        bluetoothOn: Boolean,
        wifiOn: Boolean,
    ): Reason? = when {
        !hasNearbyPermission -> Reason.NEARBY_PERMISSION
        !bluetoothOn -> Reason.BLUETOOTH_OFF
        !wifiOn -> Reason.WIFI_OFF
        else -> null
    }

    /**
     * Is "Searching for peers…" an honest thing to say right now?
     *
     * Deliberately phrased as a question about the SENTENCE rather than about the radios,
     * because that is the bug: the string was shown unconditionally and the caller never
     * had to ask whether it was true.
     */
    fun searchingIsHonest(
        hasNearbyPermission: Boolean,
        bluetoothOn: Boolean,
        wifiOn: Boolean,
    ): Boolean = firstBlocker(hasNearbyPermission, bluetoothOn, wifiOn) == null
}
