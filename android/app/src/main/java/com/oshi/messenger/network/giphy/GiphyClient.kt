package com.oshi.messenger.network.giphy

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * __GIPHY_2026_09_13__ Port Android de `OSHI/GiphyClient.swift`.
 *
 * La source EN LIGNE qui allonge le paquet local.
 *
 * Il n'y a PAS d'onglet « GIPHY » dans le sélecteur: ces résultats sont ajoutés
 * À LA SUITE du paquet local, dans les onglets GIFs et Autocollants. La personne
 * qui s'en sert voit une bibliothèque, pas deux fournisseurs.
 *
 * POURQUOI PAR L'API ET PAS EN TÉLÉCHARGEANT LE CONTENU. Les GIFs de GIPHY
 * appartiennent à ceux qui les ont mis en ligne; les étiquettes « public domain »
 * qu'on y lit sont des MOTS-CLÉS saisis par ces mêmes personnes, pas une licence.
 * Les conditions de GIPHY autorisent l'affichage VIA leur API, pas la
 * redistribution des fichiers dans un binaire. On les affiche donc en direct, on
 * n'en embarque aucun, et la mention « Powered by GIPHY » est affichée — c'est ce
 * que leur licence demande en échange.
 *
 * ⚠️ LA CLÉ BÊTA PLAFONNE À 100 APPELS PAR HEURE, TOUS UTILISATEURS CONFONDUS.
 * Ce n'est pas un quota par téléphone: c'est le total de l'application. Quelques
 * personnes qui ouvrent le sélecteur en même temps l'épuisent. D'où trois
 * précautions, qui sont la seule raison pour laquelle cette source est utilisable:
 *   1. une frappe ne déclenche PAS un appel — 500 ms de silence d'abord;
 *   2. tout résultat est mis en cache en mémoire, donc rouvrir un terme déjà vu
 *      ne coûte rien;
 *   3. sur 429 (ou toute panne), la grille ne dit RIEN et redevient simplement le
 *      paquet local — une erreur pour un GIF indisponible serait du bruit.
 * Une clé de production lèvera ce plafond; rien d'autre n'a à changer.
 *
 * RIEN NE PART DE L'APPAREIL TANT QUE LE SÉLECTEUR N'EST PAS OUVERT. Le paquet
 * local reste le défaut, et c'est délibéré: une recherche envoie à Google ce que
 * la personne tape, ce qui n'est pas un défaut souhaitable pour un messager sans
 * compte dont le troisième marché est la Russie, suivie de la Turquie, de
 * l'Arabie saoudite et de l'Irak.
 */
enum class GiphyKind(val path: String) {
    GIF("gifs"),
    STICKER("stickers"),
}

data class GiphyItem(
    val id: String,
    val title: String,
    /**
     * Petite rendition pour la grille — jamais l'originale, qui peut peser
     * plusieurs mégaoctets par vignette.
     */
    val previewUrl: String,
    /** Rendition choisie pour l'envoi, bornée en taille. */
    val sendUrl: String,
)

object GiphyClient {

    private const val TAG = "GiphyClient"

    /**
     * Clé BÊTA. Une clé client est par nature extractible du binaire — c'est le
     * modèle de GIPHY, pas une négligence. Ne rien y mettre d'autre que ça.
     */
    // __GIPHY_PROXY_2026_09_25__ No key in the APK: the OSHI proxy adds it server-side,
    // rate-limits, and strips the caller's IP/identifiers before reaching GIPHY.
    const val PROXY_BASE = "https://oshi-messenger.com/api/giphy/"

    // 🛡 __STRICT_MODE_2026_11_22__ GIPHY search is a third-party request: off in strict mode
    // (the picker then shows only the packs bundled in the app).
    val isConfigured: Boolean get() = com.oshi.messenger.security.StrictModeGate.thirdPartyFetchAllowed

    /** Une frappe ne vaut PAS un appel. Seule la PREMIÈRE page attend. */
    const val SEARCH_DEBOUNCE_MS = 500L

    /**
     * 50 est le maximum que GIPHY sert en un appel. On le prend: ces vignettes
     * arrivent maintenant EN TÊTE de la grille, et une première page maigre serait
     * la première chose que l'on voit.
     */
    const val PAGE_SIZE = 50

    /**
     * Dix pages, soit cinq cents vignettes distantes avant le paquet local.
     *
     * ⚠️ C'EST LA CLÉ BÊTA QUI DEVIENT LE MUR, PAS CE NOMBRE. Chaque page est un
     * appel, et la clé bêta en autorise cent par heure POUR TOUTE L'APPLICATION —
     * pas par téléphone. Une personne qui déroule jusqu'au bout en dépense dix;
     * dix personnes en même temps épuisent le quota horaire de tout le monde, et
     * la grille retombe alors sur le paquet local, en silence. Une clé de
     * production lève ce plafond et rien d'autre n'a à changer ici.
     *
     * Ce qui limite les dégâts en attendant: le cache est par terme ET par page,
     * donc rouvrir le sélecteur sur une recherche déjà vue ne coûte rien.
     *
     * Dix est aussi le plafond RÉEL d'une recherche: `total_count` vaut 500 pour
     * happy / love / funny, soit exactement dix pages de cinquante.
     */
    const val MAX_PAGES = 10

    /**
     * Le flux par défaut, quand personne n'a rien tapé.
     *
     * ⚠️ « TENDANCES » NE CONTIENT QUE ~174 ÉLÉMENTS. Mesuré, pas supposé:
     * `offset=450` rend zéro et l'API annonce `total_count: 174`. Le flux par
     * défaut plafonnerait donc à trois écrans et demi — alors qu'une RECHERCHE va
     * jusqu'à 500 par terme.
     *
     * D'où ce mélange: la première page est la vraie tendance du jour, et les
     * suivantes puisent dans une liste de termes larges. Ce n'est pas du
     * remplissage: ce sont les catégories que les gens cherchent effectivement
     * dans un sélecteur de GIFs. La liste est identique à celle d'iOS, mot pour
     * mot, pour que les deux clients montrent le même défaut.
     */
    val FEED_TERMS = listOf(
        "", "reaction", "funny", "love", "dance",
        "happy", "wow", "excited", "hello", "thank you",
    )

    /**
     * Au-delà, on ne propose pas: un GIF de plusieurs mégaoctets bascule l'envoi
     * sur IPFS au lieu de la maille, et c'est l'inverse du but.
     */
    const val MAX_SEND_BYTES = 5 * 1024 * 1024

    private val cache = LinkedHashMap<String, List<GiphyItem>>()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            // Pas de cache disque, pas de cookie: rien de ce sélecteur n'a à
            // survivre à la session.
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .build()
    }

    // MARK: - Requêtes

    /**
     * Une page du flux par défaut. Page 0 = la vraie tendance; 1 à 9 = un terme
     * large chacune, parce que la tendance seule s'arrête à ~174 éléments.
     */
    suspend fun feed(kind: GiphyKind, page: Int): List<GiphyItem> {
        if (page < 0 || page >= FEED_TERMS.size) return emptyList()
        val term = FEED_TERMS[page]
        return if (term.isEmpty()) trending(kind, offset = page * PAGE_SIZE)
        else search(term, kind, offset = 0)
    }

    suspend fun trending(kind: GiphyKind, limit: Int = PAGE_SIZE, offset: Int = 0): List<GiphyItem> =
        fetch(
            "${kind.path}/trending", emptyMap(),
            cacheKey(prefix = "trend", kind = kind, term = null, offset = offset),
            limit, offset,
        )

    suspend fun search(
        term: String,
        kind: GiphyKind,
        limit: Int = PAGE_SIZE,
        offset: Int = 0,
    ): List<GiphyItem> {
        val t = term.trim()
        if (t.isEmpty()) return trending(kind, limit, offset)
        return fetch(
            "${kind.path}/search",
            mapOf("q" to t),
            cacheKey(prefix = "q", kind = kind, term = t, offset = offset),
            limit, offset,
        )
    }

    /**
     * ⚠️ L'`offset` FAIT PARTIE DE LA CLÉ. Sans lui, la page 2 d'un terme trouve
     * l'entrée de la page 1 dans le cache et rend les mêmes cinquante vignettes:
     * la grille cesse de s'allonger et on croit que GIPHY est à court.
     */
    internal fun cacheKey(prefix: String, kind: GiphyKind, term: String?, offset: Int): String =
        if (term == null) "$prefix:${kind.path}:$offset"
        else "$prefix:${kind.path}:${term.lowercase(Locale.ROOT)}:$offset"

    /**
     * Ne LÈVE jamais. Un échec — quota épuisé, réseau coupé, DNS bloqué — rend une
     * liste vide, et l'appelant se contente du paquet local. Une erreur à l'écran
     * pour un GIF indisponible serait du bruit.
     */
    private suspend fun fetch(
        path: String,
        query: Map<String, String>,
        cacheKey: String,
        limit: Int,
        offset: Int,
    ): List<GiphyItem> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext emptyList()
        synchronized(cache) { cache[cacheKey] }?.let { return@withContext it }

        val url = (PROXY_BASE + path).toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("limit", limit.toString())
            ?.addQueryParameter("offset", offset.toString())
            // `g` = tout public. Un messager n'a pas à servir autre chose par défaut.
            ?.addQueryParameter("rating", "g")
            // La LANGUE aide la pertinence; le PAYS n'est délibérément pas envoyé.
            ?.addQueryParameter("lang", Locale.getDefault().language.ifBlank { "en" })
            ?.also { b -> query.forEach { (k, v) -> b.addQueryParameter(k, v) } }
            ?.build()
            ?: return@withContext emptyList()

        val body = runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                when {
                    // 429 = le quota horaire de TOUTE l'application est épuisé.
                    resp.code == 429 -> { Log.d(TAG, "hourly limit reached — local pack only"); null }
                    !resp.isSuccessful -> { Log.d(TAG, "HTTP ${resp.code}"); null }
                    else -> resp.body?.string()
                }
            }
        }.getOrElse {
            Log.d(TAG, "unreachable (${it.javaClass.simpleName}) — local pack only")
            null
        } ?: return@withContext emptyList()

        val items = parse(body)
        synchronized(cache) { cache[cacheKey] = items }
        items
    }

    /**
     * Analyse TOLÉRANTE, séparée du réseau pour être testable sur la JVM: une
     * entrée dont il manque une rendition est ignorée, elle n'emporte pas la page.
     * Même règle que partout ailleurs ici.
     */
    fun parse(body: String): List<GiphyItem> {
        val arr = runCatching { JSONObject(body).getJSONArray("data") }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<GiphyItem>(arr.length())
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val id = entry.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val images = entry.optJSONObject("images") ?: continue

            fun url(name: String): String? = images.optJSONObject(name)
                ?.optString("url")?.takeIf { it.isNotEmpty() }

            fun bytes(name: String): Int = images.optJSONObject(name)
                ?.optString("size")?.toIntOrNull() ?: Int.MAX_VALUE

            val preview = url("fixed_width_small") ?: url("preview_gif") ?: url("fixed_width")
                ?: continue
            // Du plus petit au plus grand, on prend la PREMIÈRE qui tient dans le
            // budget: compresser un GIF l'aplatit, donc la seule façon de réduire
            // la taille est de choisir une autre rendition.
            val candidates = listOf("downsized_small", "fixed_width", "downsized", "original")
            val send = candidates.firstOrNull { bytes(it) <= MAX_SEND_BYTES && url(it) != null }
                ?.let { url(it) } ?: url("fixed_width") ?: continue

            out.add(GiphyItem(id, entry.optString("title", ""), preview, send))
        }
        return out
    }

    // MARK: - Octets

    /** Les vignettes viennent du CDN: ce sont des fichiers, pas des appels au point
     * d'accès, donc elles ne consomment pas le quota horaire. */
    suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.bytes()
            }
        }.getOrElse {
            Log.d(TAG, "download failed: ${it.message}")
            null
        }
    }

    /** Uniquement pour les tests: repart d'un cache vide. */
    internal fun clearCacheForTest() = synchronized(cache) { cache.clear() }
}
