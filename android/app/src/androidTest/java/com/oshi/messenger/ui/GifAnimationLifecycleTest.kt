package com.oshi.messenger.ui

import android.app.Instrumentation
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.oshi.messenger.service.media.LocalGifItem
import com.oshi.messenger.service.media.LocalGifLibrary
import com.oshi.messenger.service.media.LocalPackKind
import com.oshi.messenger.ui.media.BubbleMedia
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * __GIF_ANIMATION_LIFECYCLE_2026_09_13__ Est-ce qu'une vignette qui SORT de l'écran
 * arrête vraiment de tourner — et est-ce que la grille s'arrête quand l'app passe
 * en arrière-plan?
 *
 * La question vient d'iOS. `AnimatedImageView.swift` dit dans son en-tête que
 * « chaque GIF qui joue est du travail sur le fil principal à sa propre cadence.
 * Vingt dans une grille, ce sont vingt minuteries sur la file principale, ce qui
 * est précisément pourquoi arrêter la lecture hors écran n'est pas un agrément »,
 * et il pose deux garde-fous explicites: `didMoveToWindow` (sorti de l'écran =
 * arrêt) et `didEnterBackgroundNotification` (app en arrière-plan = arrêt).
 *
 * Côté Android la mécanique n'est PAS la nôtre, c'est celle de Coil, donc la seule
 * réponse qui vaille est mesurée. Le chemin, lu dans les sources de Coil 2.5.0:
 *
 *   `AsyncImagePainter.onForgotten()` (`AsyncImagePainter.kt:248`)
 *     → `(_painter as? RememberObserver)?.onForgotten()`
 *     → accompanist `DrawablePainter.onForgotten()`
 *         `if (drawable is Animatable) drawable.stop()`
 *         `drawable.setVisible(false, false)`
 *         `drawable.callback = null`
 *
 * Ce test vérifie que cette chaîne se déclenche POUR DE VRAI quand une cellule
 * quitte la composition d'un `LazyVerticalGrid`, et il compte combien de drawables
 * tournent en même temps. Il n'affirme rien sur ce que l'API « devrait » faire.
 *
 * Le harnais reproduit la cellule du sélecteur (même `ImageRequest`, même
 * `ImageLoader`, même grille) plutôt que d'appeler `StickerGifPicker`, qui ne donne
 * aucune poignée sur ses drawables. Ce qui est sous test est de toute façon le
 * cycle de vie de Coil, pas notre mise en page.
 */
@RunWith(AndroidJUnit4::class)
class GifAnimationLifecycleTest {

    @get:Rule
    val compose = createComposeRule()

    private val instrumentation: Instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context get() = instrumentation.targetContext

    /** Drawables vus au moins une fois, par identifiant de vignette. */
    private val seen = Collections.synchronizedMap(LinkedHashMap<String, Drawable>())

    private fun running(): List<String> = mainSync {
        seen.entries.filter { (it.value as? Animatable)?.isRunning == true }.map { it.key }
    }

    private fun <T> mainSync(block: () -> T): T {
        var out: T? = null
        instrumentation.runOnMainSync { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    /** Même `ImageRequest`, même `ImageLoader`, même grille que la cellule du sélecteur. */
    @Composable
    private fun Harness(items: List<LocalGifItem>, height: Int = 300) {
        val ctx = LocalContext.current
        val state = rememberLazyGridState()
        gridState = state
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = state,
            modifier = Modifier.height(height.dp),
        ) {
            items(items, key = { it.id }) { item ->
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(item.assetUri).crossfade(false).build(),
                    imageLoader = BubbleMedia.imageLoader(ctx),
                    contentDescription = item.id,
                    contentScale = ContentScale.Fit,
                    onState = { st ->
                        if (st is AsyncImagePainter.State.Success) seen[item.id] = st.result.drawable
                    },
                    modifier = Modifier.height(96.dp).padding(2.dp),
                )
            }
        }
    }

    private var gridState: androidx.compose.foundation.lazy.grid.LazyGridState? = null

    private fun items(n: Int) =
        LocalGifLibrary.get(context).all(LocalPackKind.GIF).take(n)

    /**
     * 🔴 LA question. Une cellule qui quitte la composition doit ARRÊTER son
     * animation; sinon chaque défilement laisse derrière lui un décodeur de plus
     * qui tourne, et une grille de 1609 éléments finit par en faire tourner des
     * centaines.
     */
    @Test
    fun aCellThatScrollsOutOfTheGridStopsAnimating() {
        val pack = items(120)
        compose.setContent { MaterialTheme { Harness(pack) } }
        compose.waitForIdle()
        compose.waitUntil(10_000) { seen.size >= 6 }

        val firstWindow = seen.keys.toList()
        val runningAtStart = running()
        Log.i(TAG, "visible window decoded=${firstWindow.size} running=${runningAtStart.size}")
        assertTrue("aucune vignette ne tourne au départ", runningAtStart.isNotEmpty())

        // Défiler LOIN: bien au-delà de la fenêtre de réutilisation du LazyGrid.
        compose.runOnIdle { }
        mainSyncSuspendScroll(90)
        compose.waitForIdle()
        compose.waitUntil(10_000) { seen.size > firstWindow.size + 3 }
        Thread.sleep(1_000)

        val stillRunning = running().toSet()
        val leakedFromFirstWindow = firstWindow.filter { it in stillRunning }
        Log.i(
            TAG,
            "after scroll: seen=${seen.size} running=${stillRunning.size} " +
                "leaked=${leakedFromFirstWindow.size} $leakedFromFirstWindow"
        )

        assertTrue(
            "des vignettes de la première fenêtre tournent encore après défilement: " +
                leakedFromFirstWindow,
            leakedFromFirstWindow.isEmpty()
        )
        // …et la grille n'est pas simplement morte: ce qui est visible tourne.
        assertTrue("plus rien ne tourne du tout après défilement", stillRunning.isNotEmpty())
        // Le nombre de drawables VIVANTS reste de l'ordre d'une fenêtre d'écran,
        // pas de tout ce qui a défilé.
        assertTrue(
            "trop de drawables tournent (${stillRunning.size}) pour ${seen.size} vignettes vues",
            stillRunning.size < seen.size
        )
    }

    /**
     * Et l'arrière-plan — où la réponse n'est PAS celle que le drapeau suggère.
     *
     * iOS coupe explicitement sur `didEnterBackgroundNotification` et dit pourquoi:
     * « un GIF qui continue de faire tictaquer la file principale pendant que l'app
     * est suspendue, c'est de la batterie gâchée ». C'est exact là-bas, parce que
     * `CGAnimateImageDataWithBlock` EST une minuterie sur la file principale.
     *
     * Ici, non. Après un appui sur Accueil, `isRunning` reste vrai sur les douze
     * vignettes — et pourtant le processus ne consomme plus rien, parce qu'un
     * `AnimatedImageDrawable` n'avance ses images que quand on le DESSINE et
     * qu'une activité arrêtée n'est pas dessinée. Ce test mesure donc la seule
     * chose qui coûte quelque chose à l'utilisateur, le TEMPS CPU, et pas le
     * drapeau.
     *
     * Ce qu'il attrape si quelqu'un régresse: un décodeur piloté par minuterie
     * (l'ancien `MovieDrawable` de Coil sous API 28, un `LaunchedEffect` qui
     * pousserait les images à la main) recommencerait à brûler du CPU en
     * arrière-plan, et le chiffre le dirait immédiatement.
     */
    @Test
    fun backgroundedAnimationsCostNoCpuEvenThoughTheyStillReadAsRunning() {
        val pack = items(30)
        compose.setContent { MaterialTheme { Harness(pack) } }
        compose.waitForIdle()
        compose.waitUntil(10_000) { seen.size >= 6 }
        assertTrue("rien ne tourne avant de passer en arrière-plan", running().isNotEmpty())

        val cpuForeground = cpuOver(SAMPLE_MS)

        // C'est le geste que fait un utilisateur: bouton Accueil.
        instrumentation.uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
        )
        Thread.sleep(2_500)

        val cpuBackground = cpuOver(SAMPLE_MS)
        val runningInBackground = running()
        Log.i(
            TAG,
            "backgrounded: running=${runningInBackground.size} " +
                "cpuFg=${cpuForeground}j cpuBg=${cpuBackground}j over ${SAMPLE_MS}ms"
        )

        assertTrue(
            "en arrière-plan le processus brûle encore ${cpuBackground} jiffies " +
                "par ${SAMPLE_MS} ms (premier plan: $cpuForeground) — quelque chose " +
                "pousse les images sans qu'on les dessine",
            cpuBackground < MAX_BACKGROUND_JIFFIES
        )
    }

    /**
     * Jiffies de CPU (utime + stime) consommés par CE processus pendant [millis].
     *
     * `isRunning == true` ne dit pas qu'il se passe quelque chose: un
     * `AnimatedImageDrawable` n'avance ses images que quand on le DESSINE. La
     * question qui compte pour la batterie n'est donc pas le drapeau, c'est le
     * temps CPU — et c'est mesurable ici.
     *
     * Le code d'instrumentation tourne dans le processus de l'app, donc
     * `/proc/self` est bien le processus mesuré. Le nom de la commande peut
     * contenir des espaces, d'où le découpage après la parenthèse fermante.
     */
    private fun cpuOver(millis: Long): Long {
        fun jiffies(): Long {
            val stat = java.io.File("/proc/self/stat").readText()
            val fields = stat.substringAfterLast(") ").split(" ")
            // Après « ) » le champ 0 est `state`; utime est le champ 11, stime le 12.
            return fields[11].toLong() + fields[12].toLong()
        }
        val before = jiffies()
        Thread.sleep(millis)
        return jiffies() - before
    }

    private fun mainSyncSuspendScroll(index: Int) {
        val state = gridState ?: return
        compose.runOnIdle {
            kotlinx.coroutines.runBlocking { state.scrollToItem(index) }
        }
    }

    private companion object {
        const val TAG = "GifAnimLifecycle"

        /** Fenêtre d'échantillonnage CPU. */
        const val SAMPLE_MS = 4_000L

        /**
         * 4 s d'arrière-plan. Mesuré: 1 jiffy (contre 50 au premier plan), soit
         * ~0,25 % d'un cœur. Le seuil est large exprès — il doit distinguer « rien
         * ne tourne » d'« un décodeur à minuterie tourne », pas mesurer finement.
         */
        const val MAX_BACKGROUND_JIFFIES = 15
    }
}
