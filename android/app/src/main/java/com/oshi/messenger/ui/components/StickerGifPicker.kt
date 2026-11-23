package com.oshi.messenger.ui.components

import androidx.compose.foundation.background
import com.oshi.messenger.ui.theme.OshiFilterChip
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.oshi.messenger.R
import com.oshi.messenger.data.model.Mascot
import com.oshi.messenger.data.model.OshiMascot
import com.oshi.messenger.network.giphy.GiphyClient
import com.oshi.messenger.network.giphy.GiphyItem
import com.oshi.messenger.network.giphy.GiphyKind
import com.oshi.messenger.service.media.LocalGifItem
import com.oshi.messenger.service.media.LocalGifLibrary
import com.oshi.messenger.service.media.LocalPackKind
import com.oshi.messenger.ui.media.BubbleMedia
import com.oshi.messenger.ui.theme.OSHIPurple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * __LOCAL_GIF_LIBRARY_2026_09_13__ Port Android de `OSHI/StickerGifPicker.swift`.
 *
 * DEUX ONGLETS SEULEMENT — GIFs et Autocollants. Il n'y a PAS d'onglet « GIPHY »:
 * les résultats de GIPHY arrivent DANS ces deux catégories, à la suite du paquet
 * local. Pour la personne qui l'utilise il n'y a qu'une bibliothèque; d'où vient
 * chaque vignette est un détail d'implémentation, pas une décision à lui confier.
 *
 * L'ORDRE EST DÉLIBÉRÉ: GIPHY d'abord, le paquet local ensuite. Côte à côte, les
 * vrais personnages animés de GIPHY battent nettement nos formes plates, et les
 * enterrer derrière trois mille vignettes revenait à les cacher. Le paquet local
 * reste ce qui rend la grille utilisable: il est instantané, il marche hors ligne,
 * et quand GIPHY est injoignable ou que le quota horaire est épuisé la grille ne
 * se vide pas et n'affiche aucune erreur — elle est simplement le paquet local.
 *
 * Le temps que la première page distante arrive, la grille RÉSERVE sa place (voir
 * [GIPHY_PLACEHOLDERS]): sans ça le paquet local occupe le haut pendant une
 * seconde, puis les résultats s'insèrent devant lui et tout descend d'un coup —
 * la grille se réorganise sous le doigt.
 *
 * 29/09: à la demande du propriétaire, les GIFs EMBARQUÉS mis en avant (`fc_`,
 * `gp_`, `cc_`) passent devant GIPHY en direct, et la mention « Powered by GIPHY »
 * n'est plus affichée.
 *
 * Point d'entrée, calqué sur `OshiContactPickerSheet`:
 * ```
 * if (showGifPicker) {
 *     StickerGifPicker(
 *         onSelected = { bytes, name -> viewModel.sendGifMessage(bytes, name) },
 *         onDismiss = { showGifPicker = false }
 *     )
 * }
 * ```
 */
/**
 * Combien de cases la grille réserve le temps que la première page GIPHY arrive.
 *
 * Le nombre n'a pas à égaler la page entière (cinquante): il suffit qu'il couvre
 * ce qui est VISIBLE, parce que seul ce qu'on regarde peut sembler bouger.
 */
private const val GIPHY_PLACEHOLDERS = 12

/** GIFs embarqués qui mènent la grille: phrases OSHI (Fooocus), GIPHY, émojis Noto. */
private val FEATURED_PREFIXES = listOf("fc_", "gp_", "cc_")

/** Repère de test des cases réservées — c'est leur PRÉSENCE qui est la correction. */
const val GIPHY_PLACEHOLDER_TAG = "giphy-placeholder"

/** Test tag of the O-bubble mascot section header. */
const val TAG_MASCOT_SECTION = "gif-picker-oshi-mascots"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerGifPicker(
    onSelected: (bytes: ByteArray, fileName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val library = remember { LocalGifLibrary.get(context) }
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf(LocalPackKind.GIF) }
    var category by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }

    var giphyItems by remember { mutableStateOf<List<GiphyItem>>(emptyList()) }
    /**
     * Vrai DÈS LA PREMIÈRE IMAGE quand GIPHY est configuré, et pas seulement une
     * fois la requête partie: c'est ce drapeau qui fait exister les cases
     * réservées, et si elles n'apparaissaient qu'au tour suivant le paquet local
     * occuperait le haut le temps d'un aller-retour — précisément le saut que les
     * cases sont là pour supprimer.
     */
    var giphyLoading by remember(kind, query) { mutableStateOf(GiphyClient.isConfigured) }

    val categories = remember(kind) { library.categories(kind) }
    // The O-bubble section: shown with no category or with "OSHI", filtered by
    // the search (name, label, emoji: "love", "❤️", "oshi").
    val mascotResults = remember(category, query) {
        if (category == null || category == "OSHI") OshiMascot.search(query) else emptyList()
    }
    val localResults = remember(kind, category, query) { library.search(query, kind, category) }
    val featuredResults = remember(localResults) {
        // Dans l'ordre des préfixes (phrases OSHI, puis GIPHY, puis émojis).
        FEATURED_PREFIXES.flatMap { prefix -> localResults.filter { it.id.startsWith(prefix) } }
    }
    val otherLocalResults = remember(localResults) {
        localResults.filterNot { item -> FEATURED_PREFIXES.any { item.id.startsWith(it) } }
    }
    // GIPHY en direct, sans ceux déjà embarqués (`gp_<id GIPHY>`).
    val bundledIds = remember(featuredResults) { featuredResults.map { it.id }.toSet() }
    val liveGiphyItems = giphyItems.filterNot { "gp_${it.id}" in bundledIds }

    /**
     * GIPHY ne connaît pas nos catégories. Choisir une puce veut dire « je regarde
     * le paquet local », donc on n'y mêle pas de résultats distants: sinon la
     * catégorie sélectionnée mentirait sur ce qui est affiché.
     */
    val showsGiphy = category == null

    /**
     * Page suivante à demander. Changer d'onglet ou de recherche REMET à zéro:
     * sans ça la page 2 d'une recherche viendrait se coller aux résultats d'une
     * autre.
     */
    var giphyPage by remember(kind, query) { mutableStateOf(0) }
    var giphyExhausted by remember(kind, query) { mutableStateOf(false) }

    /**
     * Numéro d'ordre des chargements. Une page lancée dans la portée du sélecteur
     * n'est PAS annulée quand l'onglet ou la recherche change; sans ce compteur,
     * celle qui se termine en retard éteindrait le drapeau de celle qui l'a
     * remplacée, et le saut corrigé plus haut reviendrait sous forme de
     * clignotement.
     */
    val giphyGeneration = remember { java.util.concurrent.atomic.AtomicInteger(0) }

    /**
     * Charge [giphyPage] et l'AJOUTE à ce qui est déjà là.
     *
     * Le délai sert à ne pas dépenser un appel par TOUCHE tapée — la clé bêta
     * plafonne à cent appels par heure pour TOUTE l'application, pas par
     * téléphone. Il ne sert à rien quand personne n'a rien tapé: à l'ouverture,
     * attendre une demi-seconde ne fait que retarder ce qu'on est venu voir. On
     * n'attend donc que pour une RECHERCHE en cours de frappe, et jamais pour une
     * page suivante, qui vient d'un défilement.
     */
    suspend fun loadGiphyPage(page: Int) {
        if (!GiphyClient.isConfigured) return
        val giphyKind = if (kind == LocalPackKind.STICKER) GiphyKind.STICKER else GiphyKind.GIF
        val forKind = kind
        val forQuery = query
        val generation = giphyGeneration.incrementAndGet()
        giphyLoading = true
        try {
            if (page == 0 && forQuery.isNotBlank()) delay(GiphyClient.SEARCH_DEBOUNCE_MS)
            // Un échec — quota épuisé, réseau coupé — ne vide rien et n'affiche
            // aucune erreur: la grille reste le paquet local, qui marche toujours.
            //
            // Sans terme de recherche on ne peut PAS se contenter de la tendance:
            // elle ne contient que ~174 éléments. `feed` mélange donc tendance et
            // termes larges pour que le défaut aille aussi loin qu'une recherche.
            val fetched = if (forQuery.isBlank()) {
                GiphyClient.feed(giphyKind, page)
            } else {
                GiphyClient.search(forQuery, giphyKind, offset = page * GiphyClient.PAGE_SIZE)
            }

            // La page 1+ est lancée dans la portée du SÉLECTEUR, pas dans celle de
            // la cellule qui l'a déclenchée (une cellule disparaît en défilant, et
            // la requête mourrait avec elle en laissant le spinner allumé pour
            // toujours). En échange elle n'est pas annulée quand l'onglet ou la
            // recherche change, donc on vérifie ici que la réponse concerne encore
            // ce qui est à l'écran.
            if (forKind != kind || forQuery != query) return

            // Une page vide veut dire qu'il n'y a plus rien: on arrête de demander,
            // sinon chaque défilement en bas de grille dépenserait un appel pour
            // rien. Une page INCOMPLÈTE ne suffit pas comme signal dans le flux par
            // défaut, où chaque page vient d'un terme différent.
            if (fetched.isEmpty()) giphyExhausted = true
            // Le même GIF revient sous plusieurs termes: dédoublonner par
            // identifiant, sinon la grille répète et la clé de `items` devient
            // ambiguë — ce qui, dans un `LazyVerticalGrid`, lève.
            val known = giphyItems.mapTo(HashSet()) { it.id }
            giphyItems = giphyItems + fetched.filter { it.id !in known }
            giphyPage = page + 1
            if (giphyPage >= GiphyClient.MAX_PAGES) giphyExhausted = true
        } finally {
            // `finally`: une annulation ne doit pas laisser le spinner allumé, ce
            // qui bloquerait aussi toute page suivante (la garde lit ce drapeau).
            // Mais SEULEMENT si c'est encore mon chargement — voir [giphyGeneration].
            if (generation == giphyGeneration.get()) giphyLoading = false
        }
    }

    /** Demande la page suivante, une seule à la fois. */
    fun requestNextGiphyPage() {
        if (giphyLoading || giphyExhausted || !GiphyClient.isConfigured) return
        val page = giphyPage
        giphyLoading = true // pris SYNCHRONE: deux cellules peuvent apparaître ensemble
        scope.launch { loadGiphyPage(page) }
    }

    // Première page. `LaunchedEffect` est aussi ce qui ANNULE la requête en vol
    // quand l'onglet ou la recherche change — même contrat que le
    // `searchTask?.cancel()` d'iOS.
    LaunchedEffect(kind, query) {
        giphyItems = emptyList()
        loadGiphyPage(0)
    }

    fun send(bytesProvider: suspend () -> Pair<ByteArray, String>?) {
        if (isSending) return
        isSending = true
        scope.launch {
            try {
                val (bytes, name) = bytesProvider() ?: return@launch
                onSelected(bytes, name)
                onDismiss()
            } finally {
                isSending = false
            }
        }
    }

    OshiModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Box {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.gif_picker_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.accessibility_close))
                    }
                }

                // Deux onglets, et deux seulement.
                TabRow(selectedTabIndex = if (kind == LocalPackKind.GIF) 0 else 1) {
                    LocalPackKind.values().forEach { k ->
                        Tab(
                            selected = kind == k,
                            onClick = {
                                if (kind == k) return@Tab
                                kind = k
                                // Une catégorie de GIFs n'existe pas forcément côté
                                // autocollants: la garder en changeant d'onglet
                                // donnerait une grille vide.
                                category = null
                            },
                            text = {
                                Text(
                                    stringResource(
                                        if (k == LocalPackKind.GIF) R.string.gif_tab_gifs
                                        else R.string.gif_tab_stickers
                                    )
                                )
                            },
                        )
                    }
                }

                // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text(stringResource(R.string.gif_picker_search)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = OSHIPurple,
                            cursorColor = OSHIPurple,
                        ),
                    )
                }

                // Puces de catégorie. TOUTES celles du manifeste sont proposées:
                // une vignette qu'aucune puce n'atteint est introuvable dans une
                // grille de 2700 éléments.
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        OshiFilterChip(
                            selected = category == null,
                            onClick = { category = null },
                            label = { Text(stringResource(R.string.gif_category_all)) },
                        )
                    }
                    items(categories, key = { it }) { c ->
                        OshiFilterChip(
                            selected = category == c,
                            onClick = { category = c },
                            label = { Text(c) },
                        )
                    }
                }

                // Une SEULE grille: GIPHY d'abord, le paquet local à la suite.
                //
                // C'était l'inverse. Côte à côte, les vrais personnages animés de
                // GIPHY battent nettement nos formes plates, et les enterrer
                // derrière trois mille vignettes revenait à les cacher. Ce que
                // l'inversion ne change PAS: quand GIPHY est injoignable ou que le
                // quota horaire est épuisé, il n'y a aucune erreur à l'écran — la
                // grille est simplement le paquet local, qui est déjà là.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 460.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // __O_BUBBLE_MASCOT_2026_11_16__ The O-bubble mascot leads
                    // the picker in both tabs: 20 GIFs bundled in the app
                    // (assets/OshiMascot/gif), so the section works offline and
                    // over the mesh like every other bundled GIF. What is sent
                    // is the GIF file through the ordinary GIF path — never the
                    // WebP: iOS (1.0.43 included) animates a picture only when
                    // its bytes start with GIF87a/GIF89a
                    // (MediaManager.isGIFData), and every client already shows
                    // GIFs.
                    if (mascotResults.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "mascot-header") {
                            Text(
                                text = "OSHI",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 2.dp).testTag(TAG_MASCOT_SECTION),
                            )
                        }
                        items(mascotResults, key = { "m:${it.name}" }) { mascot ->
                            MascotGifCell(mascot) {
                                send {
                                    val bytes = withContext(Dispatchers.IO) {
                                        runCatching {
                                            context.assets.open(mascot.gifAsset).use { it.readBytes() }
                                        }.getOrNull()
                                    }
                                    bytes?.let { it to mascot.gifFileName }
                                }
                            }
                        }
                        if (featuredResults.isNotEmpty() || otherLocalResults.isNotEmpty() || showsGiphy) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "mascot-divider") {
                                Box(Modifier.fillMaxWidth().padding(vertical = 2.dp))
                            }
                        }
                    }

                    // __GIF_FEATURED_TOP_2026_09_29__ Les GIFs EMBARQUÉS mis en avant
                    // (`fc_…` dessinés pour OSHI, `gp_…` venus de GIPHY, `cc_…` libres
                    // de droits) passent devant tout, y compris GIPHY en direct.
                    items(featuredResults, key = { "f:${it.id}" }) { item ->
                        LocalGifCell(item) {
                            send {
                                val bytes = withContext(Dispatchers.IO) { library.data(item) }
                                bytes?.let { it to item.fileName }
                            }
                        }
                    }

                    // Cases RÉSERVÉES pendant que la première page distante
                    // arrive. Douze, pas cinquante: seul ce qu'on voit peut
                    // sembler bouger, le reste de la page atterrit hors du champ.
                    if (showsGiphy && giphyItems.isEmpty() && giphyLoading) {
                        items(GIPHY_PLACEHOLDERS, key = { "ph:$it" }) {
                            Box(
                                Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .testTag(GIPHY_PLACEHOLDER_TAG)
                            )   // pas de description: rien à annoncer, c'est une place vide
                        }
                    }

                    if (showsGiphy) {
                        items(liveGiphyItems, key = { "g:${it.id}" }) { item ->
                            // La DERNIÈRE vignette distante à l'écran demande la
                            // page suivante. Pas de bouton « voir plus »: on fait
                            // défiler un sélecteur de GIFs, on ne le pilote pas.
                            if (item.id == liveGiphyItems.lastOrNull()?.id) {
                                LaunchedEffect(item.id) { requestNextGiphyPage() }
                            }
                            RemoteGifCell(item) {
                                send {
                                    val bytes = GiphyClient.download(item.sendUrl)
                                    bytes?.let { it to "giphy-${item.id}.gif" }
                                }
                            }
                        }
                    }

                    items(otherLocalResults, key = { "l:${it.id}" }) { item ->
                        LocalGifCell(item) {
                            send {
                                val bytes = withContext(Dispatchers.IO) { library.data(item) }
                                bytes?.let { it to item.fileName }
                            }
                        }
                    }

                    if (giphyLoading) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            }
                        }
                    }

                    if (localResults.isEmpty() && mascotResults.isEmpty() && giphyItems.isEmpty() && !giphyLoading) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = stringResource(R.string.gif_picker_no_results),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            )
                        }
                    }

                }
            }

            if (isSending) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
        }
    }
}

/**
 * Une vignette du paquet local.
 *
 * Le modèle est l'URI d'asset et pas les octets: Coil résout
 * `file:///android_asset/…` nativement, garde le résultat DÉCODÉ dans sa mémoire
 * cache, et c'est ce qui empêche la grille de relire le fichier à chaque
 * recomposition. Le décodeur GIF est enregistré sur ce même `ImageLoader`
 * ([BubbleMedia.imageLoader]) — sans lui Coil n'afficherait que la première image.
 *
 * __GIF_ANIMATION_LIFECYCLE_2026_09_13__ Pourquoi il n'y a AUCUN code ici pour
 * mettre l'animation en pause, alors qu'iOS en a (`AnimatedImageView.swift`:
 * `didMoveToWindow` et `didEnterBackgroundNotification`). Les deux cas ont été
 * MESURÉS sur appareil (`GifAnimationLifecycleTest`), pas supposés:
 *
 *  * **Hors écran: déjà couvert, et vérifié.** Une cellule qui quitte la grille
 *    déclenche `AsyncImagePainter.onForgotten()` → `DrawablePainter.onForgotten()`
 *    → `drawable.stop()`. Après un défilement, 0 des 12 drawables de la première
 *    fenêtre tournait encore, et le nombre de drawables vivants reste celui d'UNE
 *    fenêtre d'écran. (Coil ne met pas non plus les drawables animés en mémoire
 *    cache — `MemoryCacheService.setValue` ne garde que les `BitmapDrawable` —
 *    donc rien ne s'accumule derrière le défilement.)
 *
 *  * **Arrière-plan: rien à faire, et c'est la plateforme qui le donne.** Après un
 *    appui sur Accueil, `isRunning` reste `true` sur les 12 — mais le processus
 *    consomme 1 jiffy par 4 s contre 50 au premier plan, parce qu'un
 *    `AnimatedImageDrawable` n'avance ses images QUE quand on le dessine, et une
 *    activité arrêtée n'est pas dessinée. Le drapeau ment, la facture non. iOS a
 *    besoin de son observateur parce que `CGAnimateImageDataWithBlock` est une
 *    minuterie sur la file principale, qui elle continue vraiment de tirer.
 *
 * Et un garde-fou par `mutableStateOf` NE PEUT PAS marcher ici de toute façon:
 * en dessous de STARTED, Compose met le `Recomposer` en pause, donc la
 * recomposition qui retirerait l'image n'arrive qu'au RETOUR au premier plan.
 * Essayé, mesuré, retiré — un garde-fou qui ne se déclenche jamais est pire que
 * pas de garde-fou, parce qu'il a l'air d'en être un.
 */
@Composable
private fun LocalGifCell(item: LocalGifItem, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(item.assetUri)
                .crossfade(false)
                .build(),
            imageLoader = BubbleMedia.imageLoader(context),
            contentDescription = item.tags.firstOrNull() ?: item.id,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(4.dp),
        )
    }
}

/** Une vignette GIPHY. Le CDN sert des fichiers: cela ne consomme pas le quota horaire. */
@Composable
private fun RemoteGifCell(item: GiphyItem, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(item.previewUrl)
                // Jamais sur disque: cohérent avec le reste du chargeur, qui ne
                // persiste aucun média.
                .diskCachePolicy(CachePolicy.DISABLED)
                .crossfade(false)
                .build(),
            imageLoader = BubbleMedia.imageLoader(context),
            contentDescription = item.title.ifEmpty { "GIF" },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * One O-bubble mascot GIF. Shows the GIF that will be SENT (240 px, 1-bit alpha
 * with a dark outline), animated while visible; the cell recycles its drawable
 * on scroll like [LocalGifCell].
 */
@Composable
private fun MascotGifCell(mascot: Mascot, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("gif-mascot-${mascot.name}"),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data("file:///android_asset/${mascot.gifAsset}")
                .crossfade(false)
                .build(),
            imageLoader = BubbleMedia.imageLoader(context),
            contentDescription = "${mascot.emoji} ${mascot.label}",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(6.dp),
        )
    }
}
