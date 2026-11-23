package com.oshi.messenger.ui

import com.oshi.messenger.data.model.MapRegion
import com.oshi.messenger.ui.screens.continentKey
import com.oshi.messenger.ui.screens.regionMatchesQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __MAP_PICKER_SEARCH_2026_10_14__ The region picker's filter and grouping.
 *
 * Both behaviours were real defects on 2026-10-14:
 *  * the screen grouped by the RAW `continent` string, so the manifest's
 *    "south-america" (Uruguay) rendered as a SECOND section titled
 *    "South America" holding one country, next to the real one.
 *  * there was no filter at all — the only way to reach a country among 259
 *    regions was to scroll past every other one.
 */
class MapRegionPickerTest {

    private fun region(id: String, name: String, country: String = "", continent: String = "") =
        MapRegion(id = id, name = name, country = country, continent = continent)

    // ---------------------------------------------------------------- grouping

    @Test
    fun `hyphen and underscore spellings group together`() {
        assertEquals(continentKey("south_america"), continentKey("south-america"))
        assertEquals("south_america", continentKey("South-America"))
    }

    @Test
    fun `spaces and case and padding all normalise`() {
        assertEquals("middle_east", continentKey("  Middle East "))
        assertEquals("middle_east", continentKey("MIDDLE_EAST"))
    }

    @Test
    fun `grouping by the normalised key yields ONE section per continent`() {
        // Exactly the shape the manifest had: nine regions under the underscore
        // spelling and Uruguay under the hyphenated one.
        val regions = listOf(
            region("br", "Brazil", "BR", "south_america"),
            region("ar", "Argentina", "AR", "south_america"),
            region("uy", "Uruguay", "UY", "south-america")
        )
        val grouped = regions.groupBy { continentKey(it.continent) }
        assertEquals(1, grouped.size)
        assertEquals(3, grouped.getValue("south_america").size)

        // and the bug this replaces, for the record
        assertEquals(2, regions.groupBy { it.continent }.size)
    }

    // ------------------------------------------------------------------ filter

    @Test
    fun `an empty query keeps every region`() {
        assertTrue(regionMatchesQuery(region("fr", "France", "FR"), ""))
    }

    @Test
    fun `name matches on a substring, not only a prefix`() {
        val ae = region("ae", "United Arab Emirates", "AE", "middle_east")
        assertTrue(regionMatchesQuery(ae, "emirates"))
        assertTrue(regionMatchesQuery(ae, "arab"))
    }

    @Test
    fun `the country code finds a country whose name you cannot spell`() {
        assertTrue(regionMatchesQuery(region("ae", "United Arab Emirates", "AE"), "ae"))
        assertTrue(regionMatchesQuery(region("qa", "Qatar", "QA"), "qa"))
    }

    @Test
    fun `the manifest id finds a sub-region`() {
        // Reunion is carried as a French sub-region, so its name is "Reunion"
        // but its id is what the rest of the pipeline calls it.
        assertTrue(regionMatchesQuery(region("fr-re", "La Reunion", "FR"), "fr-re"))
    }

    @Test
    fun `a non-match is rejected`() {
        assertFalse(regionMatchesQuery(region("fr", "France", "FR"), "zzz"))
    }

    @Test
    fun `the case of the query does not matter to the match`() {
        // This test used to assert the OPPOSITE — that "FRANCE" must not match —
        // guarding a contract where the caller lower-cased once per keystroke and
        // the function trusted it. `__REGION_ALIAS_2026_10_21__` folds the query
        // through `foldForRegionSearch` inside the function, for diacritics, and
        // that folding lower-cases too; the contract widened and this test was left
        // asserting the narrow one, so it had been red ever since. The caller still
        // folds once per keystroke, but as an optimisation: folding is idempotent,
        // so correctness no longer depends on it.
        assertTrue(regionMatchesQuery(region("fr", "France", "FR"), "FRANCE"))
        assertTrue(regionMatchesQuery(region("fr", "France", "FR"), "france"))
        assertTrue(regionMatchesQuery(region("fr", "France", "FR"), "FrAnCe"))
        // And the diacritic folding the change was actually made for.
        assertTrue(regionMatchesQuery(region("ci", "Côte d'Ivoire", "CI"), "cote"))
        assertTrue(regionMatchesQuery(region("ci", "Côte d'Ivoire", "CI"), "CÔTE"))
        // A non-match is still a non-match, in any case.
        assertFalse(regionMatchesQuery(region("fr", "France", "FR"), "ZZZ"))
    }

    @Test
    fun `every Gulf country added on 2026-10-14 is reachable by code and by name`() {
        val gulf = listOf(
            region("ae", "United Arab Emirates", "AE", "middle_east"),
            region("sa", "Saudi Arabia", "SA", "middle_east"),
            region("om", "Oman", "OM", "middle_east"),
            region("bh", "Bahrain", "BH", "middle_east"),
            region("kw", "Kuwait", "KW", "middle_east"),
            region("qa", "Qatar", "QA", "middle_east"),
            region("iq", "Iraq", "IQ", "middle_east"),
            region("ir", "Iran", "IR", "middle_east"),
            region("sy", "Syria", "SY", "middle_east")
        )
        for (r in gulf) {
            assertTrue("${r.id} not found by its code", regionMatchesQuery(r, r.id))
            assertTrue(
                "${r.id} not found by its name",
                regionMatchesQuery(r, r.name.lowercase())
            )
        }
        // and they all land in one section
        assertEquals(1, gulf.groupBy { continentKey(it.continent) }.size)
    }

    // ------------------------------------------- regions published 2026-10-14

    /**
     * The 13 metropolitan French sub-regions. Each must be reachable by its
     * manifest id and by its name, and all of them plus France itself must
     * collapse into ONE "Europe" section — a sub-region id carries a hyphen
     * that no country id has, and nothing in the grouping may treat it
     * specially.
     *
     * These exist because whole-France routing asked the phone for 34.9 GB of
     * free space; Auvergne-Rhone-Alpes needs 4.2 GB and Ile-de-France 1.5 GB.
     * A region nobody can find in the list is a region nobody can download.
     */
    @Test
    fun `every French sub-region is reachable and groups with Europe`() {
        val subRegions = listOf(
            region("fr-idf", "Ile-de-France", "FR", "europe"),
            region("fr-ara", "Auvergne-Rhone-Alpes", "FR", "europe"),
            region("fr-occ", "Occitanie", "FR", "europe"),
            region("fr-naq", "Nouvelle-Aquitaine", "FR", "europe"),
            region("fr-ges", "Grand Est", "FR", "europe"),
            region("fr-hdf", "Hauts-de-France", "FR", "europe"),
            region("fr-nor", "Normandie", "FR", "europe"),
            region("fr-cvl", "Centre-Val de Loire", "FR", "europe"),
            region("fr-pdl", "Pays de la Loire", "FR", "europe"),
            region("fr-bre", "Bretagne", "FR", "europe"),
            region("fr-bfc", "Bourgogne-Franche-Comte", "FR", "europe"),
            region("fr-paca", "Provence-Alpes-Cote d'Azur", "FR", "europe"),
            region("fr-cor", "Corse", "FR", "europe")
        )
        for (r in subRegions) {
            assertTrue("${r.id} not found by its id", regionMatchesQuery(r, r.id))
            assertTrue(
                "${r.id} not found by its name",
                regionMatchesQuery(r, r.name.lowercase())
            )
        }
        val withCountry = subRegions + region("fr", "France", "FR", "europe")
        assertEquals(1, withCountry.groupBy { continentKey(it.continent) }.size)
    }

    /**
     * Typing "fr-" must bring up the sub-regions, and typing "france" must not
     * hide them behind the country: both are how someone looks for the piece
     * of France they actually want.
     */
    @Test
    fun `a sub-region prefix lists the sub-regions`() {
        val all = listOf(
            region("fr", "France", "FR", "europe"),
            region("fr-idf", "Ile-de-France", "FR", "europe"),
            region("fr-ara", "Auvergne-Rhone-Alpes", "FR", "europe"),
            region("de", "Germany", "DE", "europe")
        )
        val hits = all.filter { regionMatchesQuery(it, "fr-") }
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.id.startsWith("fr-") })
        // the country code still reaches every French entry
        assertEquals(3, all.count { regionMatchesQuery(it, "fr") })
    }

    /**
     * Overseas France. Until 2026-10-14 all of these SHARED metropolitan
     * France's road graph, which contains none of their roads — a user in
     * Saint-Denis was asked for ~35 GB to install a graph of France with zero
     * Reunion roads. Ten of the eleven now own their data, so they have to be
     * findable, and they must land on their real continent rather than in
     * Europe next to the country that administers them.
     */
    @Test
    fun `overseas France is reachable and filed on its real continent`() {
        val overseas = listOf(
            Triple(region("fr-gp", "Guadeloupe", "FR", "americas"), "americas", "Guadeloupe"),
            Triple(region("fr-mq", "Martinique", "FR", "americas"), "americas", "Martinique"),
            Triple(region("fr-gf", "Guyane francaise", "FR", "americas"), "americas", "Guyane"),
            Triple(region("fr-bl", "Saint-Barthelemy", "FR", "americas"), "americas", "Saint-Barthelemy"),
            Triple(region("fr-mf", "Saint-Martin", "FR", "americas"), "americas", "Saint-Martin"),
            Triple(region("fr-pm", "Saint-Pierre-et-Miquelon", "FR", "americas"), "americas", "Miquelon"),
            Triple(region("fr-re", "La Reunion", "FR", "africa"), "africa", "Reunion"),
            Triple(region("fr-yt", "Mayotte", "FR", "africa"), "africa", "Mayotte"),
            Triple(region("fr-nc", "Nouvelle-Caledonie", "FR", "oceania"), "oceania", "Caledonie"),
            Triple(region("fr-pf", "Polynesie francaise", "FR", "oceania"), "oceania", "Polynesie"),
            Triple(region("fr-wf", "Wallis-et-Futuna", "FR", "oceania"), "oceania", "Futuna")
        )
        for ((r, expectedKey, searchTerm) in overseas) {
            assertEquals(
                "${r.id} must be filed under $expectedKey",
                expectedKey,
                continentKey(r.continent)
            )
            assertTrue("${r.id} not found by its id", regionMatchesQuery(r, r.id))
            assertTrue(
                "${r.id} not found by searching '$searchTerm'",
                regionMatchesQuery(r, searchTerm.lowercase())
            )
        }
        // Not one Europe section: they are spread across three continents.
        assertEquals(3, overseas.map { continentKey(it.first.continent) }.toSet().size)
    }

    /**
     * `americas` is a real key in the live manifest (6 regions on 2026-10-14),
     * not a hypothetical. It must normalise to itself rather than fall through
     * to whatever the default section is.
     */
    @Test
    fun `every continent key the live manifest serves normalises to itself`() {
        val served = listOf(
            "europe", "asia", "africa", "north_america",
            "middle_east", "oceania", "south_america", "americas"
        )
        for (key in served) {
            assertEquals(key, continentKey(key))
            assertEquals(key, continentKey(key.replace('_', '-')))
            assertEquals(key, continentKey("  ${key.uppercase()} "))
        }
        assertEquals(served.size, served.map { continentKey(it) }.toSet().size)
    }
}
