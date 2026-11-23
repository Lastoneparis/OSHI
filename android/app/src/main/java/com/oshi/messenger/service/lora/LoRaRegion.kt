package com.oshi.messenger.service.lora

import java.util.Locale

/**
 * LoRaRegion — the regulatory region a LoRa node transmits in.
 *
 * Port of iOS `LoRaRegion.swift` (lines 31-167). READ THAT FILE BEFORE TOUCHING
 * THE RAW VALUES.
 *
 * WHY THIS IS NOT OPTIONAL
 * ------------------------
 * A freshly flashed node boots with no region and REFUSES TO TRANSMIT. Measured
 * on a real Heltec V3 (iOS `LoRaRegion.swift:7-16`):
 *
 *     INFO | Wanted region 0, using UNSET
 *     INFO | Set radio: region=UNSET, name=LongFast, config=0, ch=19, power=30
 *
 * So "flash it, pair it, start messaging" is missing a step: without a region
 * the node pairs happily over Bluetooth, shows as connected, and silently sends
 * nothing. Surfacing that is the single most important thing the LoRa screens do.
 *
 * It is a legal constraint, not a preference. Which frequencies and power a
 * radio may use is set by the country's spectrum regulator, so OSHI PROPOSES a
 * region from the device's own locale and asks the user to confirm — it must
 * never be silently guessed, and it must never be left unset.
 *
 * ⚠️ [rawValue] is Meshtastic's `Config.LoRaConfig.RegionCode` wire enum. These
 * numbers are the firmware's, not ours: a wrong one puts a radio on a frequency
 * its owner is not allowed to use. `LoRaRegionTableTest` pins every one of them
 * against the Swift source; do not "tidy" the ordering by renumbering.
 *
 * Declaration order below matches iOS exactly, which is also how the picker
 * orders itself (a UI concern only).
 */
enum class LoRaRegion(val rawValue: Int, val displayName: String) {

    // iOS LoRaRegion.swift:32-58 — raw values are the wire contract.
    /** No region written yet: the node listens and NEVER transmits. */
    UNSET(0, "Not set"),
    US(1, "United States · 915 MHz"),
    EU433(2, "Europe · 433 MHz"),
    EU868(3, "Europe · 868 MHz"),
    CN(4, "China · 470 MHz"),
    JP(5, "Japan · 920 MHz"),
    // Chile's Meshtastic community uses the same 915–928 MHz plan. Meshtastic
    // exposes that plan as ANZ rather than a Chile-specific enum value.
    ANZ(6, "Australia / New Zealand · 915 MHz"),
    KR(7, "Korea · 920 MHz"),
    TW(8, "Taiwan · 920 MHz"),
    RU(9, "Russia · 868 MHz"),
    IND(10, "India · 865 MHz"),
    NZ865(11, "New Zealand · 865 MHz"),
    TH(12, "Thailand · 920 MHz"),
    LORA24(13, "2.4 GHz (worldwide)"),
    UA433(14, "Ukraine · 433 MHz"),
    UA868(15, "Ukraine · 868 MHz"),
    MY433(16, "Malaysia · 433 MHz"),
    MY919(17, "Malaysia · 919 MHz"),
    SG923(18, "Singapore · 923 MHz"),
    PH433(19, "Philippines · 433 MHz"),
    PH868(20, "Philippines · 868 MHz"),
    PH915(21, "Philippines · 915 MHz"),
    ANZ433(22, "Australia / New Zealand · 433 MHz"),
    KZ433(23, "Kazakhstan · 433 MHz"),
    KZ863(24, "Kazakhstan · 863 MHz"),
    NP865(25, "Nepal · 865 MHz"),
    BR902(26, "Brazil · 902 MHz");

    /**
     * True for the one state that makes a node look connected and transmit
     * nothing. Callers surface it loudly rather than treating it as "no value".
     */
    val isUnset: Boolean get() = this == UNSET

    companion object {

        /**
         * The wire value back to a case, or null for a value this build does not
         * know (a newer firmware region).
         *
         * Returning null on purpose: mapping an unknown region onto UNSET would
         * tell the user their configured node is unconfigured, and mapping it
         * onto a known region would put a wrong band name on screen.
         */
        fun fromRaw(raw: Int): LoRaRegion? = BY_RAW[raw]

        // `by lazy` on purpose: a companion property initialised eagerly would
        // run inside the enum's static initialiser, next to the constants it
        // reads.
        private val BY_RAW: Map<Int, LoRaRegion> by lazy { entries.associateBy { it.rawValue } }

        /** Everything except the placeholder, for a picker. iOS `:97-99`. */
        val selectable: List<LoRaRegion> get() = entries.filter { it != UNSET }

        /**
         * The region we PROPOSE for an ISO country code. iOS `:107-133`.
         *
         * A suggestion the user confirms, never an automatic write: getting this
         * wrong means transmitting on a band the user is not allowed to use, and
         * the phone's locale is not proof of where the node will actually live.
         * An unmapped country returns null so the UI asks rather than inventing.
         */
        fun suggestedForRegionCode(code: String): LoRaRegion? =
            when (code.uppercase(Locale.US)) {
                // EU 868 — the ETSI band, by far the most common in Europe.
                "FR", "DE", "ES", "IT", "PT", "NL", "BE", "LU", "AT", "CH", "IE",
                "DK", "SE", "NO", "FI", "IS", "PL", "CZ", "SK", "HU", "RO", "BG",
                "GR", "HR", "SI", "EE", "LV", "LT", "GB", "MT", "CY" -> EU868
                "US", "CA", "MX", "PR" -> US
                // 902-928 MHz, the same band the US code covers. Meshtastic has
                // no region code of its own for these countries, and a node
                // bought in Santiago or Lima is a 915 MHz node — so proposing
                // UNSET instead left a Chilean user (Play review, 2026-10-25)
                // with a node that pairs and never transmits, and no row in the
                // picker that obviously belonged to them.
                //
                // Brazil is NOT here: it has its own code (BR902) with its own
                // duty-cycle rules, and it is already mapped below.
                "CL", "AR", "PE", "CO", "EC", "BO", "PY", "UY", "VE",
                "CR", "PA", "GT", "SV", "HN", "NI", "DO" -> US
                "AU" -> ANZ
                "NZ" -> NZ865
                "JP" -> JP
                "KR" -> KR
                "TW" -> TW
                "CN" -> CN
                "RU" -> RU
                "UA" -> UA868
                "IN" -> IND
                "TH" -> TH
                "MY" -> MY919
                "SG" -> SG923
                "PH" -> PH915
                "KZ" -> KZ863
                "NP" -> NP865
                "BR" -> BR902
                else -> null
            }

        /**
         * The device's best guess from its own locale, or null when unmapped.
         * iOS `:136-141`.
         */
        val suggestedFromDevice: LoRaRegion?
            get() = suggestedForRegionCode(Locale.getDefault().country ?: "")
    }
}

/**
 * The radio preset. Only the two that matter to a user are offered: the default
 * everyone else is on, and the slow one that reaches further.
 *
 * Raw values are Meshtastic's `Config.LoRaConfig.ModemPreset`.
 * iOS `LoRaRegion.swift:148-167`.
 *
 * Labels live in `strings_lora.xml` (`lora_preset_*`), not here: this type is
 * used by the radio layer, which has no business holding UI copy.
 */
enum class LoRaModemPreset(val rawValue: Int) {
    LONG_FAST(0),
    LONG_SLOW(1);

    companion object {
        fun fromRaw(raw: Int): LoRaModemPreset? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * How the node asks this phone to pair over Bluetooth.
 *
 * Port of `MeshtasticManager.BluetoothPairing` (iOS `MeshtasticManager.swift:640-660`).
 * A node ships asking for a 6-digit code shown on its own screen — the safer
 * default, and it stays the default. But plenty of boards have no screen to read
 * it off, so the choice is offered rather than decided for everyone.
 *
 * ⚠️ `NO_PIN` is 2, not 1. The wire enum skips a value.
 */
enum class LoRaBluetoothPairing(val rawValue: Int) {
    /** Code shown on the node's screen. iOS `:641`. */
    RANDOM_PIN(0),
    /** Pairs on a single tap. iOS `:642`. */
    NO_PIN(2);

    companion object {
        fun fromRaw(raw: Int): LoRaBluetoothPairing? = entries.firstOrNull { it.rawValue == raw }
    }
}
