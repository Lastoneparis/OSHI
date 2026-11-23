package com.oshi.messenger.service.media

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale

/**
 * __LOCAL_GIF_LIBRARY_2026_09_13__ Port Android de `OSHI/LocalGifLibrary.swift`.
 *
 * La bibliothèque de GIFs et d'autocollants livrée AVEC l'app, lue depuis les
 * assets, sans réseau et sans clé d'API.
 *
 * POURQUOI PAS TENOR / GIPHY PAR DÉFAUT. Les deux exigent une clé, imposent une
 * mention d'attribution, et surtout font PARTIR chaque recherche de l'appareil.
 * Pour un messager sans compte dont le troisième marché est la Russie, suivie de
 * la Turquie, de l'Arabie saoudite et de l'Irak, envoyer la liste de ce que les
 * gens tapent à un tiers est un mauvais défaut. Et c'est incohérent: un sélecteur
 * de GIFs qui exige Internet ne marche pas pendant la coupure réseau pour
 * laquelle cette app existe.
 *
 * Les deux dossiers sont copiés tels quels dans `app/src/main/assets/`: ajouter un
 * GIF demande de déposer un fichier et une ligne de manifeste, jamais de toucher
 * au Gradle.
 */
enum class LocalPackKind(val folderName: String) {
    GIF("GifPack"),
    STICKER("StickerPack"),
}

/** Un élément de la bibliothèque locale. */
data class LocalGifItem(
    val id: String,
    val fileName: String,
    val category: String,
    val tags: List<String>,
    val kind: LocalPackKind,
    /**
     * Code ISO à deux lettres de la langue du texte DESSINÉ dans l'image.
     *
     * Le paquet est multilingue et les images sont PRÉ-RENDUES: « СПАСИБО » est
     * des pixels, pas une chaîne que le téléphone pourrait traduire. Savoir de
     * quelle langue relève chaque vignette est donc la seule façon de montrer la
     * sienne à quelqu'un en premier.
     *
     * Sauf [LocalGifLibrary.ANY_LANG] — « any » — qui n'est pas une langue mais
     * son absence: un motif DESSINÉ, sans un mot dessus. Ceux-là passent devant
     * tout le monde; voir [LocalGifLibrary.rank].
     */
    val lang: String,
    val width: Int = 0,
    val height: Int = 0,
) {
    /** Chemin dans les assets, p.ex. `GifPack/wow_en.gif`. */
    val assetPath: String get() = "${kind.folderName}/$fileName"

    /**
     * URI que Coil sait lire directement. C'est le chemin d'AFFICHAGE: Coil garde
     * sa propre mémoire cache, donc la grille ne relit pas l'asset à chaque
     * recomposition. [LocalGifLibrary.data] reste le chemin d'ENVOI.
     */
    val assetUri: String get() = "file:///android_asset/$assetPath"
}

/**
 * D'où viennent les octets. Une interface, et pas `AssetManager` en dur, parce que
 * les tests unitaires de ce dépôt tournent sur la JVM sans Robolectric: la seule
 * façon de tester la bibliothèque sur les 2736 vraies entrées est de pouvoir lui
 * donner le dossier `app/src/main/assets` depuis le disque.
 */
interface GifPackSource {
    /** Noms de fichiers présents dans [folder] (non récursif). */
    fun list(folder: String): Set<String>
    fun open(path: String): InputStream?
}

class AssetPackSource(private val assets: AssetManager) : GifPackSource {
    override fun list(folder: String): Set<String> =
        runCatching { assets.list(folder)?.toSet() }.getOrNull() ?: emptySet()

    override fun open(path: String): InputStream? =
        runCatching { assets.open(path) }.getOrNull()
}

class FilePackSource(private val root: File) : GifPackSource {
    override fun list(folder: String): Set<String> =
        File(root, folder).list()?.toSet() ?: emptySet()

    override fun open(path: String): InputStream? =
        File(root, path).takeIf { it.isFile }?.inputStream()
}

class LocalGifLibrary internal constructor(
    private val source: GifPackSource,
    /** Langue de l'appareil, injectable pour que le tri soit testable. */
    private val deviceLang: String = Locale.getDefault().language,
) {

    companion object {
        /**
         * L'ordre des puces, décidé et pas alphabétique: on veut « Reactions »
         * avant « Urgent ». Toute catégorie qui apparaîtrait dans le manifeste
         * sans être listée ici reste proposée — à la fin, triée — parce qu'une
         * vignette qu'aucune puce n'atteint n'est trouvable que par hasard dans
         * une grille de 2700 éléments.
         */
        val PREFERRED_CATEGORIES = listOf(
            "Reactions", "Yes / No", "Greetings", "Thanks", "Sorry",
            "Love", "Celebrate", "Funny", "Mood", "Urgent",
            "Work", "Question", "Food", "Night", "Animals", "OSHI",
        )

        /**
         * __DRAWN_MOTIFS_2026_09_13__ Le `lang` d'un motif dessiné: pas une langue,
         * l'absence de texte. Une valeur et pas un littéral, parce que [rank] et
         * les tests doivent parler de la même chose.
         */
        const val ANY_LANG = "any"

        private const val CACHE_COUNT_LIMIT = 400
        private const val CACHE_BYTES_LIMIT = 16 * 1024 * 1024

        @Volatile
        private var shared: LocalGifLibrary? = null

        fun get(context: Context): LocalGifLibrary =
            shared ?: synchronized(this) {
                shared ?: LocalGifLibrary(
                    AssetPackSource(context.applicationContext.assets)
                ).also { shared = it }
            }

        /** Repli insensible à la casse ET aux accents: « felicitations » doit trouver « FÉLICITATIONS ». */
        internal fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(COMBINING_MARKS, "")
                .lowercase(Locale.ROOT)

        private val COMBINING_MARKS = Regex("\\p{Mn}+")
    }

    // MARK: - Lecture du manifeste

    private val loaded = HashMap<LocalPackKind, List<LocalGifItem>>()

    /** Entrées ignorées au chargement, par paquet — exposé pour les diagnostics/tests. */
    val skipped = HashMap<LocalPackKind, Int>()

    @Synchronized
    fun all(kind: LocalPackKind): List<LocalGifItem> =
        loaded.getOrPut(kind) { load(kind) }

    private fun load(kind: LocalPackKind): List<LocalGifItem> {
        val bytes = source.open("${kind.folderName}/manifest.json")?.use { it.readBytes() }
            ?: run { skipped[kind] = 0; return emptyList() }

        // Décodage TOLÉRANT, entrée par entrée: une entrée mal formée ne doit pas
        // emporter la bibliothèque entière. C'est la même leçon que le magasin de
        // messages, où un seul enregistrement inconnu effaçait tout l'historique.
        val array: JSONArray = runCatching {
            JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("gifs")
        }.getOrNull() ?: run { skipped[kind] = 0; return emptyList() }

        // Ne garder que ce qui est RÉELLEMENT dans le paquet: un manifeste qui
        // promet un fichier absent donnerait une case vide et silencieuse.
        val present = source.list(kind.folderName)

        val items = ArrayList<LocalGifItem>(array.length())
        var dropped = 0
        for (i in 0 until array.length()) {
            val item = runCatching { parse(array.getJSONObject(i), kind) }.getOrNull()
            if (item == null || item.fileName !in present) {
                dropped++
                continue
            }
            items.add(item)
        }
        skipped[kind] = dropped
        return localeFirst(items)
    }

    private fun parse(o: JSONObject, kind: LocalPackKind): LocalGifItem? {
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val file = o.optString("file").takeIf { it.isNotEmpty() } ?: return null
        val tagsArray = o.optJSONArray("tags")
        val tags = buildList {
            if (tagsArray != null) {
                for (i in 0 until tagsArray.length()) {
                    tagsArray.optString(i).takeIf { it.isNotEmpty() }?.let { add(it) }
                }
            }
        }
        return LocalGifItem(
            id = id,
            fileName = file,
            category = o.optString("category").ifEmpty { "Other" },
            tags = tags,
            kind = kind,
            // Absent des 286 entrées d'origine, qui sont toutes anglophones.
            lang = o.optString("lang").ifEmpty { "en" },
            width = o.optInt("width", 0),
            height = o.optInt("height", 0),
        )
    }

    /**
     * Les motifs DESSINÉS d'abord, puis la langue du téléphone, puis l'anglais,
     * puis le reste.
     *
     * Le paquet couvre quatorze langues. Présenté dans l'ordre du manifeste, un
     * utilisateur russe ferait défiler des centaines de vignettes anglaises,
     * françaises et arabes avant d'atteindre les siennes — et conclurait, à
     * raison, que ce paquet n'est pas pour lui. C'est la seule chose qui rend une
     * bibliothèque multilingue utilisable plutôt que seulement grande.
     *
     * Le tri est STABLE à l'intérieur de chaque groupe (`sortedBy` = TimSort):
     * l'ordre du manifeste est celui du contenu, et le contenu récent y est placé
     * en tête — c'est ce qui garde les 109 motifs dans l'ordre où ils ont été
     * dessinés plutôt que dans un ordre arbitraire.
     */
    private fun localeFirst(items: List<LocalGifItem>): List<LocalGifItem> =
        items.sortedBy { rank(it.lang) }

    internal fun rank(lang: String): Int = when (lang) {
        // __DRAWN_MOTIFS_2026_09_13__ « any » = un motif dessiné, sans texte. Il
        // passe devant TOUT LE MONDE, y compris la langue du téléphone: une image
        // se lit d'un coup d'œil et dans toutes les langues, là où un mot demande
        // d'être lu. Rangés par langue ils tomberaient tous en fin de grille —
        // exactement l'inverse de ce qu'on veut de la seule partie du paquet qui
        // parle à tout le monde. Même barème que `localeFirst` sur iOS.
        ANY_LANG -> 0
        deviceLang -> 1
        "en" -> 2
        else -> 3
    }

    // MARK: - Parcours

    /**
     * Catégories présentes, dans un ordre stable et lisible plutôt qu'alphabétique
     * pur: on veut « Reactions » avant « Urgent » dans une rangée de puces.
     */
    fun categories(kind: LocalPackKind): List<String> {
        val present = all(kind).mapTo(LinkedHashSet()) { it.category }
        return PREFERRED_CATEGORIES.filter { it in present } +
            (present - PREFERRED_CATEGORIES.toSet()).sorted()
    }

    /**
     * Recherche sur l'identifiant ET les étiquettes, insensible à la casse et aux
     * accents — « felicitations » doit trouver « félicitations ».
     */
    fun search(query: String, kind: LocalPackKind, category: String? = null): List<LocalGifItem> {
        val pool = all(kind)
        val q = fold(query).trim()

        if (q.isEmpty()) {
            return if (category == null) pool else pool.filter { it.category == category }
        }

        // Les meules de foin sont repliées UNE FOIS au chargement. Les replier ici
        // ferait passer `Normalizer` sur 1500 entrées à chaque frappe, dans la
        // composition, et la grille sauterait des images pendant qu'on tape.
        val hay = haystacks(kind)
        val out = ArrayList<LocalGifItem>()
        for (i in pool.indices) {
            val item = pool[i]
            if (category != null && item.category != category) continue
            if (hay[i].contains(q)) out.add(item)
        }
        return out
    }

    private val haystackCache = HashMap<LocalPackKind, List<String>>()

    @Synchronized
    private fun haystacks(kind: LocalPackKind): List<String> =
        haystackCache.getOrPut(kind) {
            all(kind).map { fold((listOf(it.id) + it.tags).joinToString(" ")) }
        }

    // MARK: - Octets

    /**
     * Les octets d'une vignette, mis en cache.
     *
     * C'est le chemin d'ENVOI (et celui des tests). L'AFFICHAGE passe par
     * [LocalGifItem.assetUri] et la mémoire cache de Coil, qui joue exactement le
     * même rôle côté grille: à près de trois mille éléments, relire l'asset à
     * chaque recomposition transformerait chaque défilement en rafale de lectures
     * synchrones et la grille saccaderait.
     *
     * Cache LRU borné EN OCTETS autant qu'en nombre — 400 vignettes de 20 Ko ne
     * pèsent rien, 400 de 400 Ko feraient 160 Mo.
     */
    fun data(item: LocalGifItem): ByteArray? {
        synchronized(cache) { cache[item.assetPath] }?.let { return it }
        val bytes = source.open(item.assetPath)?.use { it.readBytes() } ?: return null
        synchronized(cache) {
            cache[item.assetPath] = bytes
            cacheBytes += bytes.size
            val it = cache.entries.iterator()
            while (it.hasNext() && (cacheBytes > CACHE_BYTES_LIMIT || cache.size > CACHE_COUNT_LIMIT)) {
                val eldest = it.next()
                if (eldest.key == item.assetPath) continue // jamais celui qu'on vient de demander
                cacheBytes -= eldest.value.size
                it.remove()
            }
        }
        return bytes
    }

    /** LRU par ordre d'ACCÈS (`accessOrder = true`), vidé à la main pour suivre le coût. */
    private val cache = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {}
    private var cacheBytes = 0
}
