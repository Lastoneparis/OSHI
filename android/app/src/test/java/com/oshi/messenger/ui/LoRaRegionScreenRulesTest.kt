package com.oshi.messenger.ui

import com.oshi.messenger.service.lora.LoRaRegion
import com.oshi.messenger.ui.viewmodel.LoRaDiscoveredUi
import com.oshi.messenger.ui.viewmodel.LoRaFormat
import com.oshi.messenger.ui.viewmodel.LoRaRefusal
import com.oshi.messenger.ui.viewmodel.LoRaSignalWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoRaRegionScreenRulesTest — the presentation rules the LoRa region/node
 * screens follow, pinned so a refactor cannot quietly break them.
 *
 * Three of these are behavioural contracts from the iOS port, not cosmetics:
 *   • an unreported value renders "—", NEVER "0 %" (`LoRaNodeProtocol.swift:108-112`);
 *   • the signal buckets are the ones the phone-holder's words are built on
 *     (`LoRaNodeProtocol.swift:187-194`);
 *   • region UNSET — and only UNSET — is the loud state, because it is the #1
 *     cause of "my radio does nothing" (`MeshtasticManager.swift:577-586`).
 */
class LoRaRegionScreenRulesTest {

    // =====================================================================
    // Signal buckets
    // =====================================================================

    @Test
    fun `signal bars use the iOS buckets`() {
        // ≥ -60 → 4
        assertEquals(4, LoRaFormat.signalBars(0))
        assertEquals(4, LoRaFormat.signalBars(-30))
        assertEquals(4, LoRaFormat.signalBars(-60))
        // -70 ..< -60 → 3
        assertEquals(3, LoRaFormat.signalBars(-61))
        assertEquals(3, LoRaFormat.signalBars(-70))
        // -85 ..< -70 → 2
        assertEquals(2, LoRaFormat.signalBars(-71))
        assertEquals(2, LoRaFormat.signalBars(-85))
        // else → 1
        assertEquals(1, LoRaFormat.signalBars(-86))
        assertEquals(1, LoRaFormat.signalBars(-120))
    }

    /** The boundaries are where an off-by-one hides. */
    @Test
    fun `signal bar boundaries are exact`() {
        assertEquals(4, LoRaFormat.signalBars(-60))
        assertNotEquals4(LoRaFormat.signalBars(-61))
        assertEquals(3, LoRaFormat.signalBars(-70))
        assertEquals(2, LoRaFormat.signalBars(-71))
        assertEquals(2, LoRaFormat.signalBars(-85))
        assertEquals(1, LoRaFormat.signalBars(-86))
    }

    private fun assertNotEquals4(bars: Int) = assertTrue(bars != 4)

    @Test
    fun `signal words follow the same buckets`() {
        assertEquals(LoRaSignalWord.EXCELLENT, LoRaFormat.signalWord(-45))
        assertEquals(LoRaSignalWord.GOOD, LoRaFormat.signalWord(-65))
        assertEquals(LoRaSignalWord.FAIR, LoRaFormat.signalWord(-80))
        assertEquals(LoRaSignalWord.WEAK, LoRaFormat.signalWord(-100))
    }

    @Test
    fun `discovered node exposes the same bars`() {
        val n = LoRaDiscoveredUi(id = "AA:BB", name = "Meshtastic 23d8", rssi = -66)
        assertEquals(3, n.signalBars)
        assertEquals(LoRaFormat.signalBars(-66), n.signalBars)
    }

    // =====================================================================
    // Unknown is not zero
    // =====================================================================

    /**
     * A node that has not reported its battery renders "—". "0 %" is a FALSE
     * ALARM — the difference between a blank field and telling someone their
     * radio is about to die.
     */
    @Test
    fun `an unreported battery renders a dash, never zero percent`() {
        assertEquals("—", LoRaFormat.batteryPercent(null))
        assertFalse(LoRaFormat.batteryPercent(null).contains("0"))
        // A genuinely empty battery is a different statement and does show 0 %.
        assertEquals("0 %", LoRaFormat.batteryPercent(0.0))
        assertEquals("50 %", LoRaFormat.batteryPercent(0.5))
        assertEquals("100 %", LoRaFormat.batteryPercent(1.0))
        // Out-of-range and non-finite input never produces nonsense on screen.
        assertEquals("100 %", LoRaFormat.batteryPercent(1.4))
        assertEquals("0 %", LoRaFormat.batteryPercent(-0.2))
        assertEquals("—", LoRaFormat.batteryPercent(Double.NaN))
        assertEquals("—", LoRaFormat.batteryPercent(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `every unreported measurement renders a dash`() {
        assertEquals("—", LoRaFormat.dbm(null))
        assertEquals("—", LoRaFormat.snr(null))
        assertEquals("—", LoRaFormat.snr(Double.NaN))
        assertEquals("—", LoRaFormat.megahertz(null))
        assertEquals("—", LoRaFormat.uptime(null))
        assertEquals("—", LoRaFormat.text(null))
        assertEquals("—", LoRaFormat.text(""))
        assertEquals("—", LoRaFormat.text("   "))
        assertEquals("—", LoRaFormat.nodeId(0L))
    }

    @Test
    fun `reported measurements render their value`() {
        assertEquals("-73 dBm", LoRaFormat.dbm(-73))
        assertEquals("SNR 6.5 dB", LoRaFormat.snr(6.5))
        assertEquals("868.000 MHz", LoRaFormat.megahertz(868.0))
        assertEquals("Heltec V3", LoRaFormat.text("Heltec V3"))
    }

    @Test
    fun `node id renders as the meshtastic bang hex`() {
        assertEquals("!1234abcd", LoRaFormat.nodeId(0x1234ABCDL))
        assertEquals("!0000beef", LoRaFormat.nodeId(0xBEEFL))
        assertEquals("!ffffffff", LoRaFormat.nodeId(-1L))
    }

    @Test
    fun `uptime reads in the largest useful unit`() {
        assertEquals("0m", LoRaFormat.uptime(30L))
        assertEquals("12m", LoRaFormat.uptime(12 * 60L))
        assertEquals("4h 12m", LoRaFormat.uptime(4 * 3600L + 12 * 60L))
        assertEquals("3d 4h", LoRaFormat.uptime(3 * 86400L + 4 * 3600L))
        assertEquals("—", LoRaFormat.uptime(-1L))
    }

    // =====================================================================
    // Distance: only when it is genuinely known
    // =====================================================================

    /**
     * Past roughly -105 dBm the model opens to tens of kilometres and stops
     * saying anything worth reading, so the row falls back to raw dBm instead of
     * printing "14 km – 470 km". iOS `MeshtasticManager.swift:135-141`.
     */
    @Test
    fun `estimated range refuses to guess when the signal is weak`() {
        assertNull(LoRaFormat.estimatedRangeKm(null))
        assertNull("a positive RSSI is not a measurement", LoRaFormat.estimatedRangeKm(5))
        assertNull(LoRaFormat.estimatedRangeKm(-140))
        assertNull("too weak to say anything useful", LoRaFormat.estimatedRangeKm(-130))

        val near = LoRaFormat.estimatedRangeKm(-60)
        assertNotNull(near)
        assertTrue("the band is ordered low to high", near!!.first <= near.second)
        assertTrue("and stays inside the model's useful ceiling", near.second <= 20.0)
    }

    /**
     * The two halves of a band never end up in different units. iOS `:254-259`.
     *
     * `imperial` is passed EXPLICITLY on every call. It defaults to
     * `LoRaFormat.localeIsImperial`, i.e. `Locale.getDefault().country`, so the version of
     * this test that omitted it asserted metric output while asking the machine which units
     * to use: green on a French laptop, red on a US or UK one ("0.2–0.6 mi" where it wanted
     * "300–900 m"), and nothing about the code either way. A test that reads the developer's
     * Locale is measuring the developer. Both branches are pinned here instead, which is also
     * the first coverage the imperial side has had.
     */
    @Test
    fun `span picks one unit from the far end`() {
        assertEquals("300–900 m", LoRaFormat.span(0.3, 0.9, imperial = false))
        assertEquals("1.2–4.8 km", LoRaFormat.span(1.2, 4.8, imperial = false))
        assertEquals("500 m", LoRaFormat.distanceLabel(0.5, imperial = false))
        assertEquals("2.4 km", LoRaFormat.distanceLabel(2.4, imperial = false))
    }

    /** The same bands for a reader whose roads are in miles — US, GB, LR, MM. */
    @Test
    fun `the imperial side picks one unit from the far end too`() {
        assertEquals("0.2–0.6 mi", LoRaFormat.span(0.3, 0.9, imperial = true))
        assertEquals("0.7–3.0 mi", LoRaFormat.span(1.2, 4.8, imperial = true))
        assertEquals("0.3 mi", LoRaFormat.distanceLabel(0.5, imperial = true))
        assertEquals("1.5 mi", LoRaFormat.distanceLabel(2.4, imperial = true))
        // Feet, not miles, once the FAR end is under a tenth of a mile — the same
        // "one unit from the far end" rule the metric side states.
        assertEquals("164–328 ft", LoRaFormat.span(0.05, 0.1, imperial = true))
        assertEquals("164 ft", LoRaFormat.distanceLabel(0.05, imperial = true))
    }

    @Test
    fun `distance is a real great-circle distance`() {
        // Paris ↔ Versailles, roughly 17 km.
        val km = LoRaFormat.distanceKm(48.8566, 2.3522, 48.8049, 2.1204)
        assertTrue("got $km km", km in 15.0..20.0)
        assertEquals(0.0, LoRaFormat.distanceKm(48.85, 2.35, 48.85, 2.35), 0.0001)
    }

    // =====================================================================
    // Region UNSET is the loud state
    // =====================================================================

    /**
     * UNSET is what makes a node pair, report "connected" and silently transmit
     * nothing. The screens must treat it — and a node whose config has been read
     * without producing a region — as needing attention, and must never offer it
     * as a choice.
     */
    @Test
    fun `unset is never offered and never applied`() {
        assertFalse(LoRaRegion.selectable.contains(LoRaRegion.UNSET))
        assertTrue(LoRaRegion.UNSET.isUnset)
        assertFalse(LoRaRegion.EU868.isUnset)
    }

    // =====================================================================
    // Refusals
    // =====================================================================

    /**
     * The two refusals the user can fix from the setup screen get a way in
     * rather than a description of a screen they have to find. iOS
     * `MeshtasticSettingsView.swift:171`.
     */
    @Test
    fun `only no-region and radio-muted offer a fix here`() {
        assertTrue(LoRaRefusal.NoRegion.isFixableHere)
        assertTrue(LoRaRefusal.RadioMuted.isFixableHere)
        assertFalse(LoRaRefusal.NotConnected.isFixableHere)
        assertFalse(LoRaRefusal.CarriesMedia.isFixableHere)
        assertFalse(LoRaRefusal.TooLarge(2400).isFixableHere)
        assertEquals(2400, (LoRaRefusal.TooLarge(2400) as LoRaRefusal.TooLarge).bytes)
    }
}
