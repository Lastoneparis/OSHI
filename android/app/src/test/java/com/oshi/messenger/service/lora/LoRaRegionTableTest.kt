package com.oshi.messenger.service.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoRaRegionTableTest — the region table is a REGULATORY contract.
 *
 * These raw values are Meshtastic's `Config.LoRaConfig.RegionCode` wire enum. A
 * silent off-by-one here does not produce a UI bug; it produces a radio
 * transmitting on a band its owner is not allowed to use. So every case is
 * pinned individually against the iOS source of truth
 * (`OSHI/LoRaRegion.swift:32-58`) rather than by iterating the table, which
 * would happily agree with itself after a renumbering.
 */
class LoRaRegionTableTest {

    // =====================================================================
    // Every raw value, one assertion each, cited to the Swift line.
    // =====================================================================

    @Test
    fun `every region raw value matches iOS`() {
        assertEquals("unset — LoRaRegion.swift:32", 0, LoRaRegion.UNSET.rawValue)
        assertEquals("us — :33", 1, LoRaRegion.US.rawValue)
        assertEquals("eu433 — :34", 2, LoRaRegion.EU433.rawValue)
        assertEquals("eu868 — :35", 3, LoRaRegion.EU868.rawValue)
        assertEquals("cn — :36", 4, LoRaRegion.CN.rawValue)
        assertEquals("jp — :37", 5, LoRaRegion.JP.rawValue)
        assertEquals("anz — :38", 6, LoRaRegion.ANZ.rawValue)
        assertEquals("kr — :39", 7, LoRaRegion.KR.rawValue)
        assertEquals("tw — :40", 8, LoRaRegion.TW.rawValue)
        assertEquals("ru — :41", 9, LoRaRegion.RU.rawValue)
        assertEquals("ind — :42", 10, LoRaRegion.IND.rawValue)
        assertEquals("nz865 — :43", 11, LoRaRegion.NZ865.rawValue)
        assertEquals("th — :44", 12, LoRaRegion.TH.rawValue)
        assertEquals("lora24 — :45", 13, LoRaRegion.LORA24.rawValue)
        assertEquals("ua433 — :46", 14, LoRaRegion.UA433.rawValue)
        assertEquals("ua868 — :47", 15, LoRaRegion.UA868.rawValue)
        assertEquals("my433 — :48", 16, LoRaRegion.MY433.rawValue)
        assertEquals("my919 — :49", 17, LoRaRegion.MY919.rawValue)
        assertEquals("sg923 — :50", 18, LoRaRegion.SG923.rawValue)
        assertEquals("ph433 — :51", 19, LoRaRegion.PH433.rawValue)
        assertEquals("ph868 — :52", 20, LoRaRegion.PH868.rawValue)
        assertEquals("ph915 — :53", 21, LoRaRegion.PH915.rawValue)
        assertEquals("anz433 — :54", 22, LoRaRegion.ANZ433.rawValue)
        assertEquals("kz433 — :55", 23, LoRaRegion.KZ433.rawValue)
        assertEquals("kz863 — :56", 24, LoRaRegion.KZ863.rawValue)
        assertEquals("np865 — :57", 25, LoRaRegion.NP865.rawValue)
        assertEquals("br902 — :58", 26, LoRaRegion.BR902.rawValue)
    }

    /**
     * A new case appended without updating the pins above would slip through
     * every other test in this file. iOS has 27 cases (0…26).
     */
    @Test
    fun `table has exactly the iOS case count and no gaps`() {
        assertEquals("iOS LoRaRegion.swift:32-58 declares 27 cases", 27, LoRaRegion.entries.size)
        val raws = LoRaRegion.entries.map { it.rawValue }
        assertEquals("raw values must be 0…26 with no gap", (0..26).toList(), raws.sorted())
        assertEquals("no duplicate raw values", raws.size, raws.toSet().size)
    }

    /** Declaration order is the picker order and mirrors iOS exactly. */
    @Test
    fun `declaration order matches iOS`() {
        assertEquals((0..26).toList(), LoRaRegion.entries.map { it.rawValue })
    }

    // =====================================================================
    // fromRaw
    // =====================================================================

    @Test
    fun `fromRaw round-trips every case`() {
        for (r in LoRaRegion.entries) {
            assertEquals(
                "fromRaw(${r.rawValue}) must return $r",
                r,
                LoRaRegion.fromRaw(r.rawValue)
            )
        }
    }

    /**
     * An unknown wire value (a newer firmware region) must return null, not a
     * best guess: mapping it onto UNSET would tell the user their configured
     * node is unconfigured, and mapping it onto a neighbour would put a wrong
     * band on screen.
     */
    @Test
    fun `fromRaw returns null for unknown values`() {
        assertNull(LoRaRegion.fromRaw(27))
        assertNull(LoRaRegion.fromRaw(99))
        assertNull(LoRaRegion.fromRaw(-1))
        assertNull(LoRaRegion.fromRaw(Int.MAX_VALUE))
        assertNull(LoRaRegion.fromRaw(Int.MIN_VALUE))
    }

    // =====================================================================
    // Picker contents
    // =====================================================================

    @Test
    fun `selectable excludes only unset`() {
        assertEquals(26, LoRaRegion.selectable.size)
        assertFalse(
            "UNSET is a state a node arrives in, never one a user picks",
            LoRaRegion.selectable.contains(LoRaRegion.UNSET)
        )
        assertEquals(LoRaRegion.entries.size - 1, LoRaRegion.selectable.size)
    }

    @Test
    fun `every region has a non-empty display name and only unset is unset`() {
        for (r in LoRaRegion.entries) {
            assertTrue("$r needs a display name", r.displayName.isNotBlank())
        }
        assertTrue(LoRaRegion.UNSET.isUnset)
        for (r in LoRaRegion.selectable) assertFalse(r.isUnset)
    }

    /** Region first, band second — someone in France looks for "Europe". iOS `:62-63`. */
    @Test
    fun `display names match iOS wording for the common regions`() {
        assertEquals("United States · 915 MHz", LoRaRegion.US.displayName)
        assertEquals("Europe · 433 MHz", LoRaRegion.EU433.displayName)
        assertEquals("Europe · 868 MHz", LoRaRegion.EU868.displayName)
        assertEquals("Australia / New Zealand · 915 MHz", LoRaRegion.ANZ.displayName)
        assertEquals("2.4 GHz (worldwide)", LoRaRegion.LORA24.displayName)
        assertEquals("Nepal · 865 MHz", LoRaRegion.NP865.displayName)
        assertEquals("Brazil · 902 MHz", LoRaRegion.BR902.displayName)
    }

    // =====================================================================
    // Country suggestion — a PROPOSAL, never an automatic write
    // =====================================================================

    @Test
    fun `country suggestions match iOS`() {
        // EU 868 — the ETSI band. iOS `:110-113`.
        for (c in listOf("FR", "DE", "ES", "IT", "GB", "PL", "SE", "CY", "MT")) {
            assertEquals(c, LoRaRegion.EU868, LoRaRegion.suggestedForRegionCode(c))
        }
        assertEquals(LoRaRegion.US, LoRaRegion.suggestedForRegionCode("US"))
        assertEquals(LoRaRegion.US, LoRaRegion.suggestedForRegionCode("CA"))
        assertEquals(LoRaRegion.US, LoRaRegion.suggestedForRegionCode("MX"))
        assertEquals(LoRaRegion.US, LoRaRegion.suggestedForRegionCode("PR"))
        assertEquals(LoRaRegion.ANZ, LoRaRegion.suggestedForRegionCode("AU"))
        // New Zealand is NZ865, NOT the ANZ 915 band it shares a name with.
        assertEquals(LoRaRegion.NZ865, LoRaRegion.suggestedForRegionCode("NZ"))
        assertEquals(LoRaRegion.JP, LoRaRegion.suggestedForRegionCode("JP"))
        assertEquals(LoRaRegion.KR, LoRaRegion.suggestedForRegionCode("KR"))
        assertEquals(LoRaRegion.TW, LoRaRegion.suggestedForRegionCode("TW"))
        assertEquals(LoRaRegion.CN, LoRaRegion.suggestedForRegionCode("CN"))
        assertEquals(LoRaRegion.RU, LoRaRegion.suggestedForRegionCode("RU"))
        assertEquals(LoRaRegion.UA868, LoRaRegion.suggestedForRegionCode("UA"))
        assertEquals(LoRaRegion.IND, LoRaRegion.suggestedForRegionCode("IN"))
        assertEquals(LoRaRegion.TH, LoRaRegion.suggestedForRegionCode("TH"))
        assertEquals(LoRaRegion.MY919, LoRaRegion.suggestedForRegionCode("MY"))
        assertEquals(LoRaRegion.SG923, LoRaRegion.suggestedForRegionCode("SG"))
        assertEquals(LoRaRegion.PH915, LoRaRegion.suggestedForRegionCode("PH"))
        assertEquals(LoRaRegion.KZ863, LoRaRegion.suggestedForRegionCode("KZ"))
        assertEquals(LoRaRegion.NP865, LoRaRegion.suggestedForRegionCode("NP"))
        assertEquals(LoRaRegion.BR902, LoRaRegion.suggestedForRegionCode("BR"))
    }

    @Test
    fun `suggestion is case insensitive`() {
        assertEquals(LoRaRegion.EU868, LoRaRegion.suggestedForRegionCode("fr"))
        assertEquals(LoRaRegion.US, LoRaRegion.suggestedForRegionCode("us"))
    }

    /**
     * An unmapped country returns null so the UI ASKS rather than inventing. A
     * suggestion that silently defaults to a band is exactly the "silently
     * guessed" behaviour the iOS header forbids (`:20-21`, `:106`).
     */
    @Test
    fun `unmapped country suggests nothing`() {
        assertNull(LoRaRegion.suggestedForRegionCode("ZZ"))
        assertNull(LoRaRegion.suggestedForRegionCode(""))
        assertNull(LoRaRegion.suggestedForRegionCode("XK"))
        assertNull(LoRaRegion.suggestedForRegionCode("NG"))
    }

    /** A suggestion is never UNSET — that would propose the broken state. */
    @Test
    fun `no country ever suggests unset`() {
        val codes = listOf(
            "FR", "DE", "ES", "IT", "PT", "NL", "BE", "LU", "AT", "CH", "IE",
            "DK", "SE", "NO", "FI", "IS", "PL", "CZ", "SK", "HU", "RO", "BG",
            "GR", "HR", "SI", "EE", "LV", "LT", "GB", "MT", "CY",
            "US", "CA", "MX", "PR", "AU", "NZ", "JP", "KR", "TW", "CN", "RU",
            "UA", "IN", "TH", "MY", "SG", "PH", "KZ", "NP", "BR"
        )
        for (c in codes) {
            val s = LoRaRegion.suggestedForRegionCode(c)
            assertNotNull("$c must map to something", s)
            assertFalse("$c must never suggest UNSET", s!!.isUnset)
        }
    }

    // =====================================================================
    // Modem preset — Config.LoRaConfig.ModemPreset
    // =====================================================================

    @Test
    fun `modem preset raw values match iOS`() {
        assertEquals("longFast — LoRaRegion.swift:149", 0, LoRaModemPreset.LONG_FAST.rawValue)
        assertEquals("longSlow — :150", 1, LoRaModemPreset.LONG_SLOW.rawValue)
        assertEquals(2, LoRaModemPreset.entries.size)
        assertEquals(LoRaModemPreset.LONG_FAST, LoRaModemPreset.fromRaw(0))
        assertEquals(LoRaModemPreset.LONG_SLOW, LoRaModemPreset.fromRaw(1))
        assertNull(LoRaModemPreset.fromRaw(2))
        assertNull(LoRaModemPreset.fromRaw(-1))
    }

    // =====================================================================
    // Bluetooth pairing — Config.BluetoothConfig.PairingMode
    // =====================================================================

    /** ⚠️ NO_PIN is 2, not 1: the wire enum skips a value. iOS `MeshtasticManager.swift:640-643`. */
    @Test
    fun `bluetooth pairing raw values match iOS`() {
        assertEquals("randomPin — MeshtasticManager.swift:641", 0, LoRaBluetoothPairing.RANDOM_PIN.rawValue)
        assertEquals("noPin — :642 (2, NOT 1)", 2, LoRaBluetoothPairing.NO_PIN.rawValue)
        assertEquals(2, LoRaBluetoothPairing.entries.size)
        assertEquals(LoRaBluetoothPairing.RANDOM_PIN, LoRaBluetoothPairing.fromRaw(0))
        assertEquals(LoRaBluetoothPairing.NO_PIN, LoRaBluetoothPairing.fromRaw(2))
        assertNull("1 is not a pairing mode we offer", LoRaBluetoothPairing.fromRaw(1))
        assertNull(LoRaBluetoothPairing.fromRaw(3))
    }
}
