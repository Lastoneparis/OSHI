package com.oshi.messenger

import com.oshi.messenger.ui.viewmodel.LoRaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parité avec iOS `OSHITests/MeshNameAndDistanceTests.swift`, cas pour cas.
 *
 * Deux fonctions pures qui se trompaient d'unité, dans deux directions
 * opposées: l'une comptait la mauvaise, l'autre affichait la mauvaise.
 */
class LoRaShortNameTest {

    /**
     * __SHORT_NAME_IS_BYTES_2026_10_21__ LA propriété.
     *
     * Meshtastic plafonne `User.short_name` à quatre OCTETS. L'ancien code
     * écrivait `shortName.take(4)`, qui prend quatre `Char` — quatre unités
     * UTF-16. Sur Android le piège est pire qu'ailleurs: un emoji est une PAIRE
     * de substitution, donc `take(4)` laissait passer deux emoji (8 octets) ou,
     * pire, coupait une paire en deux et produisait un demi-caractère encodé en
     * U+FFFD, affiché en losange noir par tous les autres clients.
     */
    @Test
    fun theBudgetIsFourBytesNotFourChars() {
        for (raw in listOf("TEA", "TEAM", "🚀", "🚀🚀", "🚀🚀🚀🚀", "é", "ééé", "日本語", "ABCDEFGH", "")) {
            val out = LoRaFormat.clampShortName(raw)
            assertTrue(
                "\"$raw\" a produit ${out.toByteArray(Charsets.UTF_8).size} octets: \"$out\"",
                out.toByteArray(Charsets.UTF_8).size <= 4
            )
        }
    }

    /** Une paire de substitution ne doit JAMAIS être coupée en deux. */
    @Test
    fun aSurrogatePairIsNeverSplit() {
        assertEquals("🚀", LoRaFormat.clampShortName("🚀🚀"))
        assertEquals("🚀", LoRaFormat.clampShortName("🚀A"))
        val out = LoRaFormat.clampShortName("🚀🚀🚀")
        // Aller-retour UTF-8: un demi-caractère ne survivrait pas.
        assertEquals(out, String(out.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        assertTrue("un caractère de remplacement a été produit", !out.contains('�'))
    }

    /** Le cas demandé: trois ou quatre lettres passent intactes. */
    @Test
    fun threeOrFourLettersFitUntouched() {
        assertEquals("TEA", LoRaFormat.clampShortName("TEA"))
        assertEquals("TEB", LoRaFormat.clampShortName("TEB"))
        assertEquals("TEAM", LoRaFormat.clampShortName("TEAM"))
        assertEquals("TEAM", LoRaFormat.clampShortName("TEAMS"))
    }

    /** Un latin accentué fait deux octets: compter les caractères débordait. */
    @Test
    fun twoByteCharactersCountDouble() {
        assertEquals("éé", LoRaFormat.clampShortName("ééé"))
        assertEquals(4, LoRaFormat.clampShortName("éé").toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", LoRaFormat.clampShortName(""))
    }
}

class LoRaDistanceTest {

    /**
     * __DISTANCE_FOLLOWS_THE_LOCALE_2026_10_21__ Tout était écrit en dur en
     * m/km. Pour un lecteur aux États-Unis ou au Royaume-Uni, « 4,2 km » n'est
     * pas une information, c'est une conversion à faire de tête.
     */
    @Test
    fun metricUsesMetresBelowAKilometre() {
        assertEquals("300 m", LoRaFormat.distanceLabel(0.3, imperial = false))
        assertEquals("999 m", LoRaFormat.distanceLabel(0.999, imperial = false))
        assertEquals("1.0 km", LoRaFormat.distanceLabel(1.0, imperial = false))
        assertEquals("4.2 km", LoRaFormat.distanceLabel(4.25, imperial = false))
    }

    /** La coupure imperiale est à 0,1 mile, pas à un mile. */
    @Test
    fun imperialUsesFeetBelowATenthOfAMile() {
        assertTrue(LoRaFormat.distanceLabel(0.05, imperial = true).endsWith(" ft"))
        assertTrue(LoRaFormat.distanceLabel(4.0, imperial = true).endsWith(" mi"))
        assertEquals("1.0 mi", LoRaFormat.distanceLabel(1.609344, imperial = true))
    }

    /** Une fourchette ne doit jamais mélanger ses deux moitiés. */
    @Test
    fun aSpanNeverMixesUnits() {
        for (imperial in listOf(true, false)) {
            for ((lo, hi) in listOf(0.3 to 0.9, 0.3 to 4.0, 1.2 to 4.8, 0.02 to 0.08)) {
                val s = LoRaFormat.span(lo, hi, imperial)
                val units = listOf("m", "km", "ft", "mi").filter { s.endsWith(" $it") }
                assertEquals("\"$s\" ne finit pas par exactement une unité", 1, units.size)
                assertTrue("\"$s\" n'est pas une fourchette", s.contains("–"))
            }
        }
    }

    /** Une fourchette à cheval sur le seuil prend l'unité de la borne haute. */
    @Test
    fun aSpanStraddlingTheCutFollowsTheFarEnd() {
        assertEquals("0.3–4.0 km", LoRaFormat.span(0.3, 4.0, imperial = false))
        assertEquals("300–900 m", LoRaFormat.span(0.3, 0.9, imperial = false))
    }

    /** Les deux systèmes doivent différer, sinon le réglage est décoratif. */
    @Test
    fun theTwoSystemsActuallyDiffer() {
        assertNotEquals(
            LoRaFormat.distanceLabel(4.0, imperial = true),
            LoRaFormat.distanceLabel(4.0, imperial = false)
        )
        assertNotEquals(
            LoRaFormat.span(1.2, 4.8, imperial = true),
            LoRaFormat.span(1.2, 4.8, imperial = false)
        )
    }

    /**
     * __ROUNDING_MUST_MATCH_IOS_2026_10_21__ LE cas qui a fait échouer la
     * première version de ce test, et qui est la raison d'être d'un test de
     * parité: `String.format("%.1f", 4.25)` rend "4.3" en Java et "4.2" en
     * Swift, parce que printf (donc Swift) arrondit au pair le plus proche sur
     * la valeur binaire exacte et Java arrondit au supérieur.
     *
     * 4,25 km s'affichait donc "4.2 km" sur l'iPhone et "4.3 km" sur Android,
     * pour le même nœud. Personne n'aurait écrit un rapport de bug là-dessus;
     * c'est exactement pour ça qu'il fallait le fixer par un test.
     */
    @Test
    fun theHalfWayCaseRoundsLikeIos() {
        // Branche kilomètres (>= 1 km), où printf applique %.1f.
        assertEquals("4.2 km", LoRaFormat.distanceLabel(4.25, imperial = false))
        assertEquals("2.2 km", LoRaFormat.distanceLabel(2.25, imperial = false))
        // 1.15 n'est pas représentable en binaire (il vaut un peu MOINS), donc
        // printf descend — et nous aussi, parce qu'on arrondit la valeur
        // binaire exacte et pas son écriture décimale.
        assertEquals("1.1 km", LoRaFormat.distanceLabel(1.15, imperial = false))
        // 3.35 vaut un peu PLUS en binaire: printf monte. Le même mécanisme,
        // dans l'autre sens — ce qui prouve qu'on ne tronque pas bêtement.
        assertEquals("3.4 km", LoRaFormat.distanceLabel(3.35, imperial = false))
        // Branche mètres (< 1 km), où printf applique %.0f.
        assertEquals("0 m", LoRaFormat.distanceLabel(0.0005, imperial = false))
        assertEquals("250 m", LoRaFormat.distanceLabel(0.2505, imperial = false))
    }

    /** Parité stricte avec iOS sur les valeurs produites. */
    @Test
    fun theOutputMatchesIosExactly() {
        // iOS MeshDistanceTests attend exactement ces chaînes.
        assertEquals("300 m", LoRaFormat.distanceLabel(0.3, imperial = false))
        assertEquals("1.0 mi", LoRaFormat.distanceLabel(1.609344, imperial = true))
        assertEquals("300–900 m", LoRaFormat.span(0.3, 0.9, imperial = false))
        assertEquals("0.3–4.0 km", LoRaFormat.span(0.3, 4.0, imperial = false))
    }
}
