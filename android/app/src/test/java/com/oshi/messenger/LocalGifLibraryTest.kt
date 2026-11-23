package com.oshi.messenger

import com.oshi.messenger.service.media.FilePackSource
import com.oshi.messenger.service.media.GifBytes
import com.oshi.messenger.service.media.LocalGifLibrary
import com.oshi.messenger.service.media.LocalPackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Port de `OSHITests/LocalGifLibraryTests.swift`.
 *
 * Ce que ces tests gardent fermé, c'est la promesse faite à l'utilisateur: « tout
 * ce qui est dans la bibliothèque doit être atteignable en cliquant une
 * catégorie ». Avec 286 éléments, une vignette orpheline se retrouvait en faisant
 * défiler. Avec près de trois mille, elle n'existe pas.
 *
 * Ils lisent le VRAI paquet — `app/src/main/assets/` sur le disque — et pas un jeu
 * d'essai. Un manifeste qui promet un fichier absent, une catégorie qu'aucune puce
 * ne propose, un identifiant en double qui ferait s'écraser deux entrées: rien de
 * tout cela n'est visible en lisant le code, et tout est visible ici.
 *
 * Pas de Robolectric dans ce module (et pas dans ses dépendances), d'où
 * [FilePackSource]: `AssetManager` n'existe pas sur la JVM, mais les octets, si.
 */
class LocalGifLibraryTest {

    private fun assetsDir(): File? = sequenceOf(
        // Le répertoire de travail d'une tâche de test Gradle est le module.
        File("src/main/assets"),
        File("app/src/main/assets"),
        File("../app/src/main/assets"),
    ).firstOrNull { File(it, "GifPack/manifest.json").isFile }

    private fun library(deviceLang: String = "en"): LocalGifLibrary? =
        assetsDir()?.let { LocalGifLibrary(FilePackSource(it), deviceLang) }

    /**
     * Le paquet arrive par un dossier d'assets: s'il saute, tout est vide et chaque
     * test passerait À VIDE. On le dit plutôt que de laisser une suite verte mentir.
     */
    private fun requireLibrary(deviceLang: String = "en"): LocalGifLibrary {
        val lib = library(deviceLang)
        assumeTrue("app/src/main/assets absent — rien à vérifier", lib != null)
        return lib!!
    }

    @Test
    fun `both packs are substantial`() {
        val lib = requireLibrary()
        // Un plancher volontairement bas: il attrape « le dossier n'a pas été
        // copié » et « le manifeste a été tronqué », pas une variation normale du
        // contenu.
        assertTrue(
            "GifPack: ${lib.all(LocalPackKind.GIF).size}",
            lib.all(LocalPackKind.GIF).size > 1000
        )
        assertTrue(
            "StickerPack: ${lib.all(LocalPackKind.STICKER).size}",
            lib.all(LocalPackKind.STICKER).size > 800
        )
    }

    /** LA garantie demandée: aucune vignette hors catégorie. */
    @Test
    fun `every item is reachable from a category chip`() {
        val lib = requireLibrary()
        for (kind in LocalPackKind.values()) {
            val chips = lib.categories(kind).toSet()
            for (item in lib.all(kind)) {
                assertFalse("${item.id} n'a pas de catégorie", item.category.isBlank())
                assertTrue(
                    "${item.id} est dans « ${item.category} », qu'aucune puce ne propose",
                    item.category in chips
                )
            }
        }
    }

    /** Et l'inverse: une puce qui ne mène à rien est un cul-de-sac. */
    @Test
    fun `every category chip yields items`() {
        val lib = requireLibrary()
        for (kind in LocalPackKind.values()) {
            val chips = lib.categories(kind)
            assertTrue("aucune puce pour ${kind.folderName}", chips.isNotEmpty())
            for (c in chips) {
                assertFalse(
                    "la puce « $c » ouvre une grille vide",
                    lib.search("", kind, c).isEmpty()
                )
            }
        }
    }

    /**
     * Et l'ordre des puces est celui qui a été DÉCIDÉ, pas l'alphabet: on veut
     * « Reactions » avant « Urgent ».
     */
    @Test
    fun `category chips keep the deliberate order`() {
        val lib = requireLibrary()
        for (kind in LocalPackKind.values()) {
            val chips = lib.categories(kind)
            val preferredPresent = LocalGifLibrary.PREFERRED_CATEGORIES.filter { it in chips }
            assertEquals(preferredPresent, chips.take(preferredPresent.size))
        }
    }

    /**
     * Deux entrées de même identifiant viseraient le même fichier: la grille
     * montrerait deux fois la même vignette, et `LazyVerticalGrid` s'en sert comme
     * clé — une clé en double y est une exception, pas un doublon cosmétique.
     */
    @Test
    fun `identifiers are unique`() {
        val lib = requireLibrary()
        for (kind in LocalPackKind.values()) {
            val items = lib.all(kind)
            assertEquals(
                "identifiants en double dans ${kind.folderName}",
                items.size,
                items.map { it.id }.toSet().size
            )
        }
    }

    /**
     * Le manifeste peut promettre ce que le paquet ne contient pas. Une entrée
     * pareille donne une case grise et muette — le chargement doit l'avoir écartée.
     */
    @Test
    fun `every listed item has real bytes`() {
        val lib = requireLibrary()
        for (kind in LocalPackKind.values()) {
            val items = lib.all(kind)
            // Tout relire ferait une suite de plusieurs secondes pour rien: un
            // échantillon régulier trouve un dossier mal copié aussi bien.
            for (i in items.indices step 17) {
                val bytes = lib.data(items[i])
                assertNotNull("${items[i].id}: aucun octet", bytes)
                assertTrue("${items[i].id}: octets vides", bytes!!.isNotEmpty())
                // …et ce sont bien des GIF, pas un fichier renommé: c'est ce que
                // le chemin d'envoi renifle pour décider de ne PAS recompresser.
                assertTrue("${items[i].id}: pas un GIF", GifBytes.isGif(bytes))
            }
        }
    }

    /**
     * La langue du téléphone passe devant. C'est ce qui rend une bibliothèque de
     * quatorze langues utilisable au lieu de seulement grande.
     */
    @Test
    fun `device language comes first`() {
        for (lang in listOf("ru", "fr", "ja", "en")) {
            val lib = requireLibrary(lang)
            // Les motifs dessinés ont leur propre rang, DEVANT: on ordonne ici ce
            // qui reste, c'est-à-dire ce qui porte du texte.
            val items = lib.all(LocalPackKind.GIF).filter { it.lang != LocalGifLibrary.ANY_LANG }
            assumeTrue("le paquet ne couvre pas $lang", items.any { it.lang == lang })

            val lastMine = items.indexOfLast { it.lang == lang }
            val firstForeign = items.indexOfFirst { it.lang != lang && it.lang != "en" }
            if (lastMine >= 0 && firstForeign >= 0) {
                assertTrue(
                    "des vignettes en $lang arrivent après une autre langue",
                    lastMine < firstForeign
                )
            }
            // …et l'anglais est le second groupe, jamais après une troisième langue.
            val lastEn = items.indexOfLast { it.lang == "en" }
            if (lang != "en" && lastEn >= 0 && firstForeign >= 0) {
                assertTrue("l'anglais passe après une langue tierce", lastEn < firstForeign)
            }
        }
    }

    /**
     * __DRAWN_MOTIFS_2026_09_13__ Les motifs DESSINÉS ouvrent la grille, avant même
     * la langue du téléphone.
     *
     * Une image se lit d'un coup d'œil dans toutes les langues; un mot doit être
     * lu. Rangés par langue, les 109 motifs tomberaient tous en fin de grille —
     * l'inverse exact de ce qu'on veut de la seule partie du paquet qui parle à
     * tout le monde. Et le défaut serait invisible: la bibliothèque a la bonne
     * taille, chaque puce marche, seule la PLACE est fausse.
     */
    @Test
    fun `drawn motifs lead the grid whatever the device language is`() {
        for (lang in listOf("fr", "ru", "en", "zz")) {
            val lib = requireLibrary(lang)
            for (kind in LocalPackKind.values()) {
                val items = lib.all(kind)
                assumeTrue(
                    "aucun motif dessiné dans ${kind.folderName}",
                    items.any { it.lang == LocalGifLibrary.ANY_LANG }
                )

                assertEquals(
                    "en $lang, ${kind.folderName} n'ouvre pas sur un motif dessiné " +
                        "(${items.first().id}, lang=${items.first().lang})",
                    LocalGifLibrary.ANY_LANG,
                    items.first().lang
                )

                // Aucun « any » après une vignette qui porte du texte.
                val firstTextual = items.indexOfFirst { it.lang != LocalGifLibrary.ANY_LANG }
                val lastAny = items.indexOfLast { it.lang == LocalGifLibrary.ANY_LANG }
                assertTrue(
                    "un motif dessiné (#$lastAny) arrive après une vignette " +
                        "textuelle (#$firstTextual) dans ${kind.folderName}",
                    lastAny < firstTextual
                )
            }
        }
    }

    /** Et le barème lui-même, celui d'iOS: any &lt; langue du téléphone &lt; en &lt; reste. */
    @Test
    fun `the ranking is any then device language then english then the rest`() {
        val lib = requireLibrary("fr")
        assertEquals(0, lib.rank(LocalGifLibrary.ANY_LANG))
        assertEquals(1, lib.rank("fr"))
        assertEquals(2, lib.rank("en"))
        assertEquals(3, lib.rank("ru"))
    }

    /**
     * Les motifs portent des étiquettes SYNONYMES à dessein — une image n'a pas de
     * texte à chercher. Chercher « triste » doit ramener le visage qui pleure.
     * Sans cette assertion, un manifeste régénéré peut perdre les synonymes sans
     * que rien ne devienne rouge: le motif serait toujours là, simplement
     * introuvable.
     */
    @Test
    fun `a drawn motif is reachable through its synonym tags`() {
        val lib = requireLibrary()
        val hits = lib.search("triste", LocalPackKind.GIF).map { it.id }
        assertTrue("« triste » ne ramène rien", hits.isNotEmpty())
        assertTrue("« triste » ne ramène pas le visage qui pleure: $hits", "p_f_cry" in hits)

        // Et sans accent ni casse, sur un synonyme accentué d'un AUTRE motif.
        assertTrue("« pleure » ne ramène pas p_f_cry", "p_f_cry" in lib.search("PLEURE", LocalPackKind.GIF).map { it.id })
        // Le même motif est atteignable en anglais: c'est tout l'intérêt des synonymes.
        assertTrue("« crying » ne ramène pas p_f_cry", "p_f_cry" in lib.search("crying", LocalPackKind.GIF).map { it.id })
    }

    /**
     * Le tri par langue est STABLE: l'ordre du manifeste est celui du contenu, et
     * le contenu récent y est placé en tête. Un tri instable le mélangerait.
     */
    @Test
    fun `locale ordering is stable within a language group`() {
        val lib = requireLibrary("fr")
        val fr = lib.all(LocalPackKind.GIF).filter { it.lang == "fr" }.map { it.id }
        val manifestOrder = requireLibrary("zz")
            .all(LocalPackKind.GIF).filter { it.lang == "fr" }.map { it.id }
        assertEquals(manifestOrder, fr)
    }

    /**
     * La recherche doit ignorer la casse ET les accents: quelqu'un qui tape
     * « felicitations » sans accent cherche bien « FÉLICITATIONS ».
     */
    @Test
    fun `search is accent and case insensitive`() {
        val lib = requireLibrary()
        val plain = lib.search("felicitations", LocalPackKind.GIF).map { it.id }.toSet()
        val accented = lib.search("Félicitations", LocalPackKind.GIF).map { it.id }.toSet()
        val shouty = lib.search("FELICITATIONS", LocalPackKind.GIF).map { it.id }.toSet()
        // Non vide, sinon les trois ensembles seraient égaux… et vides, et ce test
        // passerait sans rien prouver.
        assertFalse("aucun résultat: le test serait vide de sens", plain.isEmpty())
        assertEquals(accented, plain)
        assertEquals(shouty, plain)
    }

    /** La recherche porte aussi sur l'identifiant, pas seulement sur les étiquettes. */
    @Test
    fun `search matches identifiers too`() {
        val lib = requireLibrary()
        // The point of this test is that an IDENTIFIER is searchable at all — the
        // haystack is `id + tags`. It used to assert `hits.size == 1`, which was
        // true only while `congrats_fr` was the one id containing that text; the
        // pack also ships `fc_congrats_fr`, and since search is a SUBSTRING match
        // over ids, two hits is the correct answer, not a regression. Asserting a
        // COUNT over shipped assets makes every future addition to the pack a
        // failing test somewhere else, so assert the property instead: the exact
        // id is found, and every hit is a genuine substring match.
        val hits = lib.search("congrats_fr", LocalPackKind.GIF)
        assertTrue("expected congrats_fr among ${hits.map { it.id }}",
            hits.any { it.id == "congrats_fr" })
        assertTrue("every hit must actually contain the query: ${hits.map { it.id }}",
            hits.all { it.id.contains("congrats_fr") || it.tags.any { t -> t.contains("congrats_fr") } })
    }

    /** Une recherche vide n'est pas une recherche: elle doit tout rendre. */
    @Test
    fun `empty query returns everything`() {
        val lib = requireLibrary()
        for (kind in LocalPackKind.values()) {
            assertEquals(lib.all(kind).size, lib.search("   ", kind).size)
            assertEquals(lib.all(kind).size, lib.search("", kind).size)
        }
    }

    /** Une catégorie restreint, une recherche dans une catégorie restreint encore. */
    @Test
    fun `category narrows the pool`() {
        val lib = requireLibrary()
        val all = lib.all(LocalPackKind.GIF)
        val reactions = lib.search("", LocalPackKind.GIF, "Reactions")
        assertTrue(reactions.isNotEmpty())
        assertTrue(reactions.size < all.size)
        assertTrue(reactions.all { it.category == "Reactions" })
    }

    /** Les octets sont mis en cache: deux lectures rendent le MÊME tableau. */
    @Test
    fun `bytes are cached`() {
        val lib = requireLibrary()
        val item = lib.all(LocalPackKind.GIF).first()
        val a = lib.data(item)
        val b = lib.data(item)
        assertNotNull(a)
        assertTrue("le cache a rendu un autre tableau", a === b)
    }

    /**
     * Une entrée mal formée ne doit pas emporter la bibliothèque — c'est la leçon
     * du magasin de messages, où un seul enregistrement inconnu effaçait tout
     * l'historique.
     */
    @Test
    fun `one malformed entry does not take out the library`(): Unit = withTempPack { dir ->
        File(dir, "GifPack/manifest.json").writeText(
            """
            {"gifs":[
              {"id":"good","file":"good.gif","category":"Reactions","tags":["ok"],"lang":"en"},
              {"file":"noid.gif","category":"Reactions","tags":[],"lang":"en"},
              {"id":"nofile","file":"absent.gif","category":"Reactions","tags":[],"lang":"en"},
              "not even an object",
              {"id":"second","file":"second.gif","category":"Mood"}
            ]}
            """.trimIndent()
        )
        val lib = LocalGifLibrary(FilePackSource(dir), "en")
        val items = lib.all(LocalPackKind.GIF)
        assertEquals(listOf("good", "second"), items.map { it.id })
        // `lang` absent = anglais, comme les 286 entrées d'origine.
        assertEquals("en", items[1].lang)
        assertEquals(3, lib.skipped[LocalPackKind.GIF])
    }

    /** Un manifeste illisible rend une bibliothèque VIDE, jamais une exception. */
    @Test
    fun `an unreadable manifest yields an empty library`(): Unit = withTempPack { dir ->
        File(dir, "GifPack/manifest.json").writeText("{ this is not json")
        assertEquals(emptyList<Any>(), LocalGifLibrary(FilePackSource(dir), "en").all(LocalPackKind.GIF))
    }

    /** Un manifeste absent aussi. */
    @Test
    fun `a missing manifest yields an empty library`() {
        val dir = createTempDir()
        try {
            assertEquals(
                emptyList<Any>(),
                LocalGifLibrary(FilePackSource(dir), "en").all(LocalPackKind.GIF)
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Toute catégorie hors de la liste préférée reste proposée, à la fin, triée. */
    @Test
    fun `unknown categories are still offered at the end`(): Unit = withTempPack { dir ->
        File(dir, "GifPack/manifest.json").writeText(
            """
            {"gifs":[
              {"id":"a","file":"good.gif","category":"Zebra","tags":[],"lang":"en"},
              {"id":"b","file":"second.gif","category":"Reactions","tags":[],"lang":"en"},
              {"id":"c","file":"third.gif","category":"Aardvark","tags":[],"lang":"en"}
            ]}
            """.trimIndent()
        )
        val lib = LocalGifLibrary(FilePackSource(dir), "en")
        assertEquals(listOf("Reactions", "Aardvark", "Zebra"), lib.categories(LocalPackKind.GIF))
    }

    // MARK: - Outillage

    /** Un faux paquet sur disque avec trois GIF minuscules mais VALIDES. */
    private fun withTempPack(body: (File) -> Unit) {
        val dir = createTempDir()
        try {
            File(dir, "GifPack").mkdirs()
            // En-tête GIF89a: `GifBytes.isGif` et le filtre « le fichier existe »
            // regardent tous les deux de vrais octets.
            val gif = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00)
            for (n in listOf("good.gif", "second.gif", "third.gif")) {
                File(dir, "GifPack/$n").writeBytes(gif)
            }
            body(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun createTempDir(): File =
        File.createTempFile("gifpack", "").let {
            it.delete()
            it.mkdirs()
            it
        }
}
