package com.oshi.messenger

import com.oshi.messenger.ui.media.VoiceNoteAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __VOICE_SCRUB_2026_10_26__ « on ne peut pas cliquer au milieu du message… ce
 * n'est pas ultra convivial ».
 *
 * La barre d'onde n'était qu'un dessin : aucun geste dessus. Le calcul qui la rend
 * déplaçable tient en une ligne, et c'est exactement le genre de ligne qui part en
 * production fausse — une valeur non bornée fait chercher APRÈS la fin (certains
 * `MediaPlayer.seekTo` lèvent), une largeur nulle (un Canvas pas encore mesuré)
 * divise par zéro et livre `NaN` au lecteur. Invisible sur une capture d'écran.
 */
class VoiceNoteScrubTest {

    @Test
    fun theMiddleOfTheStripIsTheMiddleOfTheNote() {
        assertEquals(0.5f, VoiceNoteAudio.seekProgress(65f, 130f), 1e-6f)
        assertEquals(0.25f, VoiceNoteAudio.seekProgress(32.5f, 130f), 1e-6f)
        assertEquals(0f, VoiceNoteAudio.seekProgress(0f, 130f), 1e-6f)
        assertEquals(1f, VoiceNoteAudio.seekProgress(130f, 130f), 1e-6f)
    }

    /** Un doigt qui sort de la bande ne doit pas chercher hors du fichier. */
    @Test
    fun aTouchPastTheEdgeIsClamped() {
        assertEquals(1f, VoiceNoteAudio.seekProgress(400f, 130f), 1e-6f)
        assertEquals(0f, VoiceNoteAudio.seekProgress(-50f, 130f), 1e-6f)
    }

    /**
     * LE cas qui compte : un `Canvas` pas encore mesuré a une largeur de 0, et
     * `x / 0` vaut `Infinity` ou `NaN`. `NaN.coerceIn(0f, 1f)` rend **NaN**, qui
     * traverse jusqu'à `(NaN * duration).toInt()` — 0 sur la JVM, mais une
     * position indéfinie côté lecteur. On n'y arrive donc jamais.
     */
    @Test
    fun anUnmeasuredStripNeverProducesNaN() {
        for (w in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val p = VoiceNoteAudio.seekProgress(42f, w)
            assertTrue("width=$w a produit $p", p.isFinite())
            assertTrue("width=$w a produit $p", p in 0f..1f)
        }
        assertTrue(VoiceNoteAudio.seekProgress(Float.NaN, 130f).isFinite())
    }

    /** Toute sortie est une progression valable, quelles que soient les entrées. */
    @Test
    fun everyOutputIsAUsableProgress() {
        val xs = listOf(-1e9f, -1f, 0f, 1f, 64f, 129.9f, 130f, 1e9f, Float.NaN)
        val ws = listOf(1f, 24f, 130f, 1080f)
        for (x in xs) for (w in ws) {
            val p = VoiceNoteAudio.seekProgress(x, w)
            assertTrue("x=$x w=$w → $p", p.isFinite() && p in 0f..1f)
        }
    }
}
