package com.oshi.messenger.ui

import android.graphics.drawable.AnimatedImageDrawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.oshi.messenger.service.media.GifBytes
import com.oshi.messenger.service.media.LocalGifLibrary
import com.oshi.messenger.service.media.LocalPackKind
import com.oshi.messenger.ui.components.GIPHY_PLACEHOLDER_TAG
import com.oshi.messenger.ui.components.StickerGifPicker
import com.oshi.messenger.ui.media.BubbleMedia
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * __LOCAL_GIF_LIBRARY_2026_09_13__ Ce que les tests JVM ne peuvent PAS prouver.
 *
 * Les tests unitaires lisent le paquet depuis le disque du dépôt. Ici on lit le
 * paquet tel qu'il est EMPAQUETÉ, par l'`AssetManager` du vrai APK — la seule
 * façon de savoir que les 2736 fichiers sont bien montés dans l'app et pas
 * seulement présents dans l'arbre source.
 *
 * Et surtout: que le GIF ANIME. Une capture d'écran ne le dit pas (une image fixe
 * et une animation arrêtée sur sa première image se ressemblent); un
 * `AnimatedImageDrawable` / `MovieDrawable` rendu par le chargeur de l'app, si.
 * C'est exactement le défaut que `coil-gif` existe pour éviter.
 */
@RunWith(AndroidJUnit4::class)
class StickerGifPickerInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theRealAssetPackIsMountedInTheApk() {
        val lib = LocalGifLibrary.get(context)
        val gifs = lib.all(LocalPackKind.GIF)
        val stickers = lib.all(LocalPackKind.STICKER)
        assertTrue("GifPack = ${gifs.size}", gifs.size > 1000)
        assertTrue("StickerPack = ${stickers.size}", stickers.size > 800)
        assertTrue(lib.categories(LocalPackKind.GIF).first() == "Reactions")

        val bytes = lib.data(gifs.first())
        assertNotNull(bytes)
        assertTrue("les octets empaquetés ne sont pas un GIF", GifBytes.isGif(bytes))
    }

    /**
     * LE test de la fonctionnalité. Sans `coil-gif`, ce `drawable` est un
     * `BitmapDrawable`: la première image, et rien d'autre.
     */
    @Test
    fun packedGifsDecodeToAnAnimatedDrawable() = runBlocking {
        val lib = LocalGifLibrary.get(context)
        val item = lib.all(LocalPackKind.GIF).first()
        val result = BubbleMedia.imageLoader(context).execute(
            ImageRequest.Builder(context).data(item.assetUri).build()
        )
        assertTrue("Coil n'a pas décodé ${item.assetPath}: $result", result is SuccessResult)
        // Coil emballe le résultat dans un `ScaleDrawable` quand une taille est
        // appliquée: c'est l'ENFANT qui dit si le GIF anime.
        val drawable = unwrap((result as SuccessResult).drawable)
        val animated = drawable is AnimatedImageDrawable ||
            drawable::class.java.name.contains("MovieDrawable")
        assertTrue("${item.id} rendu en ${drawable::class.java.name} — pas animé", animated)
    }

    private tailrec fun unwrap(d: android.graphics.drawable.Drawable): android.graphics.drawable.Drawable {
        val child = runCatching {
            d.javaClass.getMethod("getChild").invoke(d) as? android.graphics.drawable.Drawable
        }.getOrNull() ?: return d
        return unwrap(child)
    }

    /**
     * 🔴 La grille ne doit PAS se réorganiser une seconde après l'ouverture.
     *
     * GIPHY est en tête, mais à la première image la liste distante est vide. Sans
     * précaution le paquet local occupe le haut, puis les résultats s'insèrent
     * DEVANT lui et tout descend d'un coup — signalé à l'usage: « on voit une
     * seconde d'émojis, puis la grille bouge sous le doigt ». Les cases réservées
     * sont la correction, et ce test vérifie qu'elles existent DÈS LA PREMIÈRE
     * IMAGE, avant même que l'horloge du test n'avance.
     *
     * Pas besoin de réseau: le drapeau de chargement démarre à vrai dès que GIPHY
     * est configuré, et c'est lui qui fait exister les cases.
     */
    @Test
    fun theGridReservesGiphysPlaceOnTheVeryFirstFrame() {
        compose.setContent {
            MaterialTheme {
                StickerGifPicker(onSelected = { _, _ -> }, onDismiss = { })
            }
        }
        val reserved = compose.onAllNodesWithTag(GIPHY_PLACEHOLDER_TAG).fetchSemanticsNodes()
        assertTrue(
            "aucune case réservée à l'ouverture: le paquet local occupe le haut et " +
                "sera poussé vers le bas quand GIPHY répondra",
            reserved.isNotEmpty()
        )

        // Et elles sont AU-DESSUS du paquet local, sinon elles ne réservent rien.
        val firstLocal = LocalGifLibrary.get(context).all(LocalPackKind.GIF).first()
        val localTop = compose.onAllNodesWithContentDescription(firstLocal.tags.first())
            .fetchSemanticsNodes().firstOrNull()?.positionInRoot?.y
        if (localTop != null) {
            val reservedTop = reserved.minOf { it.positionInRoot.y }
            assertTrue(
                "les cases réservées ($reservedTop) ne sont pas au-dessus de la " +
                    "première vignette locale ($localTop)",
                reservedTop < localTop
            )
        }
    }

    /**
     * Et la grille elle-même s'affiche: le titre, les deux onglets, la puce
     * « All », la première catégorie du manifeste, et une vraie vignette.
     */
    @Test
    fun theGridRendersWithBothTabsAndItsChips() {
        compose.setContent {
            MaterialTheme {
                StickerGifPicker(onSelected = { _, _ -> }, onDismiss = { })
            }
        }
        compose.onNodeWithText("GIFs & Stickers").assertExists()
        compose.onNodeWithText("Stickers").assertExists()
        compose.onNodeWithText("All").assertExists()
        compose.onNodeWithText("Reactions").assertExists()

        // Et la grille contient bien des vignettes, pas seulement sa chrome: la
        // première du paquet est étiquetée par sa propre étiquette.
        val first = LocalGifLibrary.get(context).all(LocalPackKind.GIF).first()
        compose.onNodeWithContentDescription(first.tags.first()).assertExists()

        // 🔴 PAS de `captureToImage` ici, et ce n'est pas un oubli. Sur la fenêtre
        // de la feuille modale il rend une image BLANCHE (l'activité hôte, elle,
        // mesure 0×0 et le fait lever « width and height must be > 0 »): un test
        // vert écrivant une capture vide est exactement le genre de test qui ment.
        //
        // La vraie photo se prend depuis l'hôte, pendant que la feuille est à
        // l'écran:
        //   adb shell am instrument -w \
        //     -e class com.oshi.messenger.ui.StickerGifPickerInstrumentedTest#theGridRendersWithBothTabsAndItsChips \
        //     -e holdForScreenshot true -e holdMillis 20000 \
        //     com.oshi.messenger.test/androidx.test.runner.AndroidJUnitRunner &
        //   sleep 14 && adb exec-out screencap -p > picker.png
        // Deux captures à trois secondes d'intervalle DIFFÈRENT quand les GIFs
        // animent — c'est la seule preuve visuelle qui vaille.
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("holdForScreenshot") == "true") {
            val until = System.currentTimeMillis() +
                (args.getString("holdMillis")?.toLongOrNull() ?: 12_000L)
            // 🔴 PAS un `Thread.sleep` sec, et PAS seulement `waitForIdle`.
            //
            // Sous `createComposeRule`, le `delay` du debounce GIPHY vit sur
            // l'horloge VIRTUELLE du test, et `waitForIdle` ne l'avance pas: une
            // coroutine endormie n'est pas une recomposition en attente, donc
            // `waitForIdle` rend la main tout de suite et les 500 ms ne passent
            // JAMAIS. Deux captures successives ont ainsi montré une grille
            // purement locale et donné à croire que GIPHY avait échoué — alors
            // qu'il répondait 50 éléments dans le même processus. Avancer
            // l'horloge à la main est la seule façon d'obtenir une photo honnête.
            compose.mainClock.advanceTimeBy(1_000)
            while (System.currentTimeMillis() < until) {
                compose.mainClock.advanceTimeBy(500)
                compose.waitForIdle()
                Thread.sleep(200)
            }
        }
    }
}
