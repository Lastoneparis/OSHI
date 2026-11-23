package com.oshi.messenger

import com.oshi.messenger.network.giphy.GiphyClient
import com.oshi.messenger.network.giphy.GiphyKind
import com.oshi.messenger.service.media.GifBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'analyse des réponses GIPHY, séparée du réseau exprès pour être testable ici.
 *
 * Ce qui compte n'est pas « ça décode du JSON »: c'est qu'une entrée abîmée soit
 * IGNORÉE au lieu d'emporter la page, et que la rendition choisie pour l'envoi
 * tienne sous le plafond — un GIF de plusieurs mégaoctets bascule l'envoi sur IPFS
 * au lieu de la maille, et c'est l'inverse du but.
 */
class GiphyClientParseTest {

    private fun rendition(url: String, size: Int?): String =
        """{"url":"$url"${if (size != null) ""","size":"$size"""" else ""}}"""

    @Test
    fun `picks the smallest rendition that fits the budget`() {
        val body = """
            {"data":[{"id":"abc","title":"a cat","images":{
              "fixed_width_small": ${rendition("https://cdn/small.gif", 12000)},
              "downsized_small":   ${rendition("https://cdn/ds.gif", 9_000_000)},
              "fixed_width":       ${rendition("https://cdn/fw.gif", 400_000)},
              "downsized":         ${rendition("https://cdn/dz.gif", 800_000)},
              "original":          ${rendition("https://cdn/orig.gif", 20_000_000)}
            }}]}
        """.trimIndent()
        val items = GiphyClient.parse(body)
        assertEquals(1, items.size)
        assertEquals("https://cdn/small.gif", items[0].previewUrl)
        // `downsized_small` est le premier candidat mais fait 9 Mo: on descend au
        // suivant qui TIENT, pas au suivant tout court.
        assertEquals("https://cdn/fw.gif", items[0].sendUrl)
        assertEquals("a cat", items[0].title)
    }

    @Test
    fun `a rendition without a declared size is not assumed to fit`() {
        val body = """
            {"data":[{"id":"abc","images":{
              "fixed_width_small": ${rendition("https://cdn/small.gif", 1000)},
              "downsized_small":   ${rendition("https://cdn/ds.gif", null)},
              "fixed_width":       ${rendition("https://cdn/fw.gif", 1000)}
            }}]}
        """.trimIndent()
        // Taille absente = `Int.MAX_VALUE` = ne tient pas. Se rabattre dessus
        // enverrait un fichier dont on ne sait rien.
        assertEquals("https://cdn/fw.gif", GiphyClient.parse(body)[0].sendUrl)
    }

    @Test
    fun `an entry missing its renditions is skipped not fatal`() {
        val body = """
            {"data":[
              {"id":"broken"},
              {"images":{"fixed_width":${rendition("https://cdn/x.gif", 10)}}},
              {"id":"ok","images":{"fixed_width":${rendition("https://cdn/ok.gif", 10)}}}
            ]}
        """.trimIndent()
        val items = GiphyClient.parse(body)
        assertEquals(1, items.size)
        assertEquals("ok", items[0].id)
    }

    @Test
    fun `garbage yields an empty list rather than an exception`() {
        assertTrue(GiphyClient.parse("not json at all").isEmpty())
        assertTrue(GiphyClient.parse("{}").isEmpty())
        assertTrue(GiphyClient.parse("""{"data":{}}""").isEmpty())
        assertTrue(GiphyClient.parse("""{"data":[]}""").isEmpty())
    }

    @Test
    fun `the send ceiling and the debounce are the ones the beta key needs`() {
        assertEquals(5 * 1024 * 1024, GiphyClient.MAX_SEND_BYTES)
        assertEquals(500L, GiphyClient.SEARCH_DEBOUNCE_MS)
        assertTrue(GiphyClient.isConfigured)
        assertEquals("gifs", GiphyKind.GIF.path)
        assertEquals("stickers", GiphyKind.STICKER.path)
    }

    /**
     * 🔴 L'`offset` DOIT être dans la clé de cache.
     *
     * Sans lui, la page 2 d'un terme retrouve l'entrée de la page 1 et rend les
     * mêmes cinquante vignettes: la grille cesse de s'allonger et on conclut, à
     * tort, que GIPHY est à court. Le défaut serait indétectable à la lecture —
     * la requête réseau, elle, porte bien le bon `offset`.
     */
    @Test
    fun `the cache key separates pages of the same term`() {
        val p0 = GiphyClient.cacheKey("q", GiphyKind.GIF, "happy", 0)
        val p1 = GiphyClient.cacheKey("q", GiphyKind.GIF, "happy", 50)
        assertTrue("la clé de page 0 ignore l'offset: $p0", p0 != p1)

        // …et elle sépare aussi les deux onglets et les deux points d'accès.
        assertTrue(p0 != GiphyClient.cacheKey("q", GiphyKind.STICKER, "happy", 0))
        assertTrue(p0 != GiphyClient.cacheKey("trend", GiphyKind.GIF, null, 0))
        assertTrue(
            GiphyClient.cacheKey("trend", GiphyKind.GIF, null, 0) !=
                GiphyClient.cacheKey("trend", GiphyKind.GIF, null, 50)
        )
        // La casse du terme ne crée pas deux entrées pour la même recherche.
        assertEquals(p0, GiphyClient.cacheKey("q", GiphyKind.GIF, "HAPPY", 0))
    }

    /**
     * Les deux plafonds, et ils sont MESURÉS, pas choisis.
     *
     * 50 est le maximum que GIPHY sert en un appel. 10 pages × 50 = 500, et 500 est
     * exactement le `total_count` d'une recherche (happy / love / funny). Dix pages
     * EST donc le plafond réel, pas un nombre rond.
     *
     * ⚠️ Et c'est là que la clé bêta devient le mur: dix pages = dix appels, sur
     * cent par heure POUR TOUTE L'APPLICATION.
     */
    @Test
    fun `the two page ceilings are the measured ones`() {
        assertEquals(50, GiphyClient.PAGE_SIZE)
        assertEquals(10, GiphyClient.MAX_PAGES)
        assertEquals(500, GiphyClient.PAGE_SIZE * GiphyClient.MAX_PAGES)
    }

    /**
     * Le flux par défaut, terme pour terme et dans l'ordre, parce que les deux
     * clients doivent montrer le même.
     *
     * ⚠️ « Tendances » ne contient que ~174 éléments (mesuré: `offset=450` rend
     * zéro, `total_count: 174`). Pager la tendance s'arrêterait donc après trois
     * écrans et demi sans raison visible — d'où la page 0 en vraie tendance et les
     * neuf suivantes sur des termes larges.
     */
    @Test
    fun `the default feed matches iOS term for term`() {
        assertEquals(
            listOf("", "reaction", "funny", "love", "dance",
                   "happy", "wow", "excited", "hello", "thank you"),
            GiphyClient.FEED_TERMS
        )
        // Autant de termes que de pages: une page sans terme rendrait une grille
        // qui s'arrête avant son plafond annoncé.
        assertEquals(GiphyClient.MAX_PAGES, GiphyClient.FEED_TERMS.size)
        // La page 0 est la vraie tendance; aucune autre ne l'est.
        assertEquals("", GiphyClient.FEED_TERMS.first())
        assertTrue(GiphyClient.FEED_TERMS.drop(1).none { it.isBlank() })
        // Et pas de doublon: deux pages sur le même terme, c'est un appel dépensé
        // pour un lot que le dédoublonnage jetterait entièrement.
        assertEquals(GiphyClient.FEED_TERMS.size, GiphyClient.FEED_TERMS.toSet().size)
    }
}

/**
 * Le reniflage de GIF: la SEULE chose qui décide, sur le chemin d'envoi, qu'il ne
 * faut pas recompresser. Une étiquette peut mentir, un nom de fichier aussi.
 */
class GifBytesTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `recognises GIF87a and GIF89a`() {
        assertTrue(GifBytes.isGif("GIF87a…".toByteArray(Charsets.ISO_8859_1)))
        assertTrue(GifBytes.isGif("GIF89a…".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun `rejects everything else`() {
        assertFalse(GifBytes.isGif(null))
        assertFalse(GifBytes.isGif(ByteArray(0)))
        assertFalse(GifBytes.isGif("GIF8".toByteArray()))                  // trop court
        assertFalse(GifBytes.isGif("GIF88a".toByteArray()))                // version inconnue
        assertFalse(GifBytes.isGif(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0)))   // JPEG
        assertFalse(GifBytes.isGif(bytes(0x89, 0x50, 0x4E, 0x47, 0, 0)))   // PNG
    }
}
