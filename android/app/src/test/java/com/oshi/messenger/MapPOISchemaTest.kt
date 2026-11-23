package com.oshi.messenger

import com.oshi.messenger.data.model.POICategory
import com.oshi.messenger.service.MapPOIDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * POI vocabulary and query construction, pinned against the LIVE `poi.db`.
 *
 * Ground truth read 2026-08-13 from
 * `GET https://oshi-messenger.com/maps/v2/fr-idf/poi.db` (200, 31 191 040 B):
 *
 *   CREATE TABLE pois (id INTEGER PRIMARY KEY, name TEXT NOT NULL,
 *                      category TEXT NOT NULL, subcategory TEXT,
 *                      lat REAL NOT NULL, lon REAL NOT NULL,
 *                      address TEXT, phone TEXT, website TEXT, opening_hours TEXT);
 *   190 364 rows. There is no `region_id` column and no `tags` column.
 *
 *   SELECT category, COUNT(*) … →
 *     parking 55 564 · bus_station 37 725 · restaurant 24 161 · supermarket 15 232
 *     · school 12 174 · park 4 390 · cafe 4 061 · bar 3 709 · bank 3 481
 *     · place_of_worship 3 375 · pharmacy 3 309 · gym 3 242 · hotel 3 059
 *     · train_station 2 775 · toilet 2 710 · charging_station 2 633
 *     · hospital 2 055 · atm 1 778 · post_office 1 461 · museum 1 002
 *     · cinema 948 · fuel 852 · police 633 · airport 35
 */
class MapPOISchemaTest {

    /** Exactly the category vocabulary the live database contains. */
    private val liveCategories = listOf(
        "parking", "bus_station", "restaurant", "supermarket", "school", "park",
        "cafe", "bar", "bank", "place_of_worship", "pharmacy", "gym", "hotel",
        "train_station", "toilet", "charging_station", "hospital", "atm",
        "post_office", "museum", "cinema", "fuel", "police", "airport"
    )

    @Test
    fun `every live category resolves to a real enum case, never OTHER`() {
        for (c in liveCategories) {
            val resolved = POICategory.fromServerKey(c)
            assertTrue(
                "live category '$c' collapsed to OTHER",
                resolved != POICategory.OTHER
            )
            assertEquals(c, resolved.serverKey)
        }
    }

    /**
     * The three categories the old enum name-matching silently lost. Together
     * they are 44 k of the 190 k rows in fr-idf alone: `bus_station` was matched
     * as `bus_stop`, `fuel` as `gas_station`, `charging_station` as `ev_charging`.
     */
    @Test
    fun `server keys differ from enum names where the server says so`() {
        assertEquals("bus_station", POICategory.BUS_STATION.serverKey)
        assertEquals("fuel", POICategory.GAS_STATION.serverKey)
        assertEquals("charging_station", POICategory.EV_CHARGING.serverKey)
        // …and the old, wrong values must NOT resolve back to those cases.
        assertEquals(POICategory.OTHER, POICategory.fromServerKey("gas_station_wrong"))
    }

    @Test
    fun `categories that used to have no enum case now have one`() {
        assertEquals(POICategory.GYM, POICategory.fromServerKey("gym"))
        assertEquals(POICategory.TOILET, POICategory.fromServerKey("toilet"))
        assertEquals(POICategory.CINEMA, POICategory.fromServerKey("cinema"))
    }

    @Test
    fun `unknown and empty categories degrade to OTHER instead of throwing`() {
        // The old `POICategory.valueOf(raw.uppercase())` threw on every unknown.
        assertEquals(POICategory.OTHER, POICategory.fromServerKey("nightclub"))
        assertEquals(POICategory.OTHER, POICategory.fromServerKey(""))
        assertEquals(POICategory.OTHER, POICategory.fromServerKey(null))
        assertEquals(POICategory.RESTAURANT, POICategory.fromServerKey("  RESTAURANT "))
    }

    @Test
    fun `server keys are unique so the lookup table cannot silently shadow a case`() {
        val keys = POICategory.entries.map { it.serverKey }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `fts tokens are quoted so punctuation cannot become syntax`() {
        // Bare `l'eglise*` is an FTS5 syntax error, which is what used to push
        // every accented/apostrophised query into the (also broken) LIKE path.
        assertEquals("\"l\"* \"eglise\"*", MapPOIDatabase.buildFtsQuery("l'eglise"))
        assertEquals("\"saint\"* \"denis\"*", MapPOIDatabase.buildFtsQuery("saint-denis"))
        assertEquals("\"cafe\"*", MapPOIDatabase.buildFtsQuery("  cafe  "))
        assertNull(MapPOIDatabase.buildFtsQuery("   "))
        assertNull(MapPOIDatabase.buildFtsQuery("!!!"))
    }

    @Test
    fun `an address filter parenthesises its tokens`() {
        // `address : "rue"* "pasteur"*` would bind the column filter to the first
        // token only, so the second would match any column.
        assertEquals(
            "address : (\"rue\"* \"pasteur\"*)",
            MapPOIDatabase.buildFtsQuery("rue pasteur", column = "address")
        )
    }

    @Test
    fun `diacritic folding is what makes cafe reach Cafe-accented`() {
        // The server's prebuilt index ships WITHOUT `remove_diacritics 2`. That
        // is not a reason to drop it (the old code did, destroying 190 364 rows
        // of index) — folding happens here instead.
        assertEquals("cafe", MapPOIDatabase.normalizeForLike("Café"))
        assertEquals("la poste", MapPOIDatabase.normalizeForLike("La Poste"))
        assertEquals("eglise", MapPOIDatabase.normalizeForLike("Église"))
    }
}
