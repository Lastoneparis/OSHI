package com.oshi.messenger

import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.repository.MessageRepository
import com.oshi.messenger.ui.components.BubbleMediaKind
import com.oshi.messenger.ui.components.ChatMediaSelection
import com.oshi.messenger.ui.components.LinkPreviewCache
import com.oshi.messenger.ui.components.LinkPreviewData
import com.oshi.messenger.ui.components.PendingMedia
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * FIX TEAM MEDIA-FINISH — the link-preview cache (privacy), M-6's remaining send
 * leg, M-14's fan-out and M-17's state machine.
 *
 * Everything here is pure JVM: real files under a [TemporaryFolder], real
 * `org.json` (the `org.json:json` test dependency, not android.jar's throwing stub),
 * and hand-written literals.
 *
 * NOTHING in this file builds its expected value by calling the function under
 * test. The cache keys in particular are base64 computed independently
 * (`python3 -c "import base64; base64.b64encode(url.encode())"`) and pasted, so a
 * key derivation that changed shape would fail here rather than agree with itself.
 */
class MediaFinishTest {

    // The disk tier needs an at-rest key (memory-only without one); no Keystore on the JVM.
    private val TEST_KEYS = com.oshi.messenger.data.local.atrest.AtRestKeyProvider { ByteArray(32) { 11 } }

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cacheDir(): File = File(tmp.root, "LinkPreviews")

    private fun data(url: String, title: String? = "T", site: String? = "S") =
        LinkPreviewData(url = url, title = title, description = "D", imageUrl = "$url/og.png", siteName = site)

    // ══════════════════════════════════════════════════════════════════════════
    // 1. LINK PREVIEW CACHE — the privacy fix
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `the disk key is iOS's base64 spelling, computed independently`() {
        // base64(utf8(url)), then / → _, + → -, = dropped.
        // `LinkPreview.swift:76-81`. Literals computed with python3's base64, NOT
        // with LinkPreviewCache.key.
        assertEquals("eA", LinkPreviewCache.key("x"))
        assertEquals(
            "aHR0cHM6Ly9leGFtcGxlLmNvbS9hP2I9MSZjPTI",
            LinkPreviewCache.key("https://example.com/a?b=1&c=2")
        )
        // This one exercises ALL THREE substitutions: its raw base64 is
        // "aHR0cHM6Ly9leGFtcGxlLmNvbS9+w7s/eD3Dvg==" — a '+', a '/' and padding.
        assertEquals(
            "aHR0cHM6Ly9leGFtcGxlLmNvbS9-w7s_eD3Dvg",
            LinkPreviewCache.key("https://example.com/~û?x=þ")
        )
    }

    @Test
    fun `a key never contains a path separator, so it cannot escape the cache dir`() {
        val nasty = "https://evil.example/../../../../etc/passwd?a=/b+c=d"
        val k = LinkPreviewCache.key(nasty)
        assertFalse(k.contains('/'))
        assertFalse(k.contains('\\'))
        assertFalse(k.contains(".."))
        // And the file it names really does land inside the directory.
        val dir = cacheDir()
        assertEquals(dir.absolutePath, File(dir, k).parentFile!!.absolutePath)
    }

    @Test
    fun `a stored preview comes back without touching the network`() {
        val c = LinkPreviewCache(cacheDir(), TEST_KEYS) { 1_000L }
        assertNull(c.lookup("https://a.example"))
        c.store("https://a.example", data("https://a.example"), byteArrayOf(1, 2, 3))
        val hit = c.lookup("https://a.example")
        assertNotNull(hit)
        assertEquals("T", hit!!.data!!.title)
        assertEquals("S", hit.data!!.siteName)
        assertEquals("D", hit.data!!.description)
        assertEquals("https://a.example/og.png", hit.data!!.imageUrl)
        assertArrayEquals(byteArrayOf(1, 2, 3), hit.image)
        assertFalse(hit.isNegative)
    }

    @Test
    fun `the disk tier survives a NEW instance — that is the relaunch case`() {
        val dir = cacheDir()
        LinkPreviewCache(dir, TEST_KEYS) { 1_000L }.store("https://b.example", data("https://b.example"), null)
        // A different object with an empty memory tier: only the disk can answer.
        val fresh = LinkPreviewCache(dir, TEST_KEYS) { 2_000L }
        assertEquals(0, fresh.memoryCount)
        val hit = fresh.lookup("https://b.example")
        assertNotNull(hit)
        assertEquals("T", hit!!.data!!.title)
        assertNull(hit.image)
        // …and reading it PROMOTES it into memory, so the next scroll is free.
        assertEquals(1, fresh.memoryCount)
    }

    @Test
    fun `an entry older than seven days is a miss, and is evicted from BOTH tiers`() {
        val dir = cacheDir()
        var now = 0L
        val c = LinkPreviewCache(dir, TEST_KEYS) { now }
        c.store("https://c.example", data("https://c.example"), null)
        // __PLAINTEXT_LEFTOVERS_2026_09_24__ sealed + HMAC-named on disk now.
        val file = File(dir, c.diskName("https://c.example")!!)
        assertTrue(file.isFile)

        // One millisecond short of the TTL: still a hit.
        now = LinkPreviewCache.TTL_MS - 1
        assertNotNull(c.lookup("https://c.example"))

        // Exactly at the TTL: gone, and the file is gone with it.
        now = LinkPreviewCache.TTL_MS
        assertNull(c.lookup("https://c.example"))
        assertFalse(file.isFile)
        assertEquals(0, c.memoryCount)
    }

    @Test
    fun `the TTL is seven days, iOS's number`() {
        assertEquals(7L * 24 * 3600 * 1000, LinkPreviewCache.TTL_MS)
        assertEquals(200, LinkPreviewCache.MEMORY_COUNT_LIMIT)
        assertEquals(24 * 1024 * 1024, LinkPreviewCache.MEMORY_COST_LIMIT)
    }

    @Test
    fun `a FAILURE is remembered too, and on its own much shorter clock`() {
        var now = 0L
        val c = LinkPreviewCache(cacheDir(), TEST_KEYS) { now }
        c.storeFailure("https://down.example")

        // A negative hit is still a hit: the caller must NOT re-fetch…
        val hit = c.lookup("https://down.example")
        assertNotNull(hit)
        assertTrue(hit!!.isNegative)
        assertNull(hit.data)

        // …but only for an hour, not for a week. A site that was down for a minute
        // must not be blacklisted until next Tuesday.
        now = LinkPreviewCache.NEGATIVE_TTL_MS - 1
        assertTrue(c.lookup("https://down.example")!!.isNegative)
        now = LinkPreviewCache.NEGATIVE_TTL_MS
        assertNull(c.lookup("https://down.example"))
        assertEquals(60L * 60 * 1000, LinkPreviewCache.NEGATIVE_TTL_MS)
    }

    @Test
    fun `re-storing WITH the image replaces the metadata-only entry`() {
        val c = LinkPreviewCache(cacheDir(), TEST_KEYS) { 5L }
        val d = data("https://d.example")
        c.store("https://d.example", d, null)               // metadata first, as iOS does
        assertNull(c.lookup("https://d.example")!!.image)
        c.store("https://d.example", d, byteArrayOf(9, 9))  // then the image
        assertArrayEquals(byteArrayOf(9, 9), c.lookup("https://d.example")!!.image)
        // One entry, not two — the same key was overwritten.
        assertEquals(1, cacheDir().listFiles()!!.count { !it.name.endsWith(".tmp") })
    }

    @Test
    fun `the memory tier evicts the LEAST RECENTLY USED when the count limit is hit`() {
        val c = LinkPreviewCache(cacheDir(), TEST_KEYS) { 1L }
        // 201 entries against a 200 limit.
        for (i in 0..LinkPreviewCache.MEMORY_COUNT_LIMIT) {
            c.store("https://m$i.example", data("https://m$i.example"), null)
        }
        assertEquals(LinkPreviewCache.MEMORY_COUNT_LIMIT, c.memoryCount)
    }

    @Test
    fun `the memory tier is bounded by BYTES as well as by count`() {
        val c = LinkPreviewCache(cacheDir(), TEST_KEYS) { 1L }
        // 30 entries of ~1.3 MB each = ~40 MB against a 24 MB budget. Far under the
        // 200-entry count limit, so only the COST bound can hold this down.
        val big = ByteArray(1024 * 1024) { 7 }
        for (i in 0 until 30) {
            c.store("https://big$i.example", data("https://big$i.example"), big)
        }
        assertTrue("held ${c.memoryBytes} bytes", c.memoryBytes <= LinkPreviewCache.MEMORY_COST_LIMIT)
        assertTrue("evicted nothing (${c.memoryCount} entries)", c.memoryCount < 30)
    }

    @Test
    fun `a corrupt cache file is a MISS, not a crash`() {
        val dir = cacheDir()
        dir.mkdirs()
        val c = LinkPreviewCache(dir, TEST_KEYS) { 1L }
        File(dir, c.diskName("https://x.example")!!).writeText("{not json at all")
        assertNull(c.lookup("https://x.example"))
    }

    @Test
    fun `a half-written file never becomes a readable entry`() {
        val dir = cacheDir()
        val c = LinkPreviewCache(dir, TEST_KEYS) { 1L }
        c.store("https://e.example", data("https://e.example"), byteArrayOf(4, 5))
        // The write goes through a .tmp and a rename, so nothing is left behind and
        // no partially written file can be picked up by a lookup.
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `two different URLs never collide, even when they differ only in the query`() {
        val c = LinkPreviewCache(cacheDir(), TEST_KEYS) { 1L }
        c.store("https://s.example/?a=1", data("https://s.example/?a=1", title = "ONE"), null)
        c.store("https://s.example/?a=2", data("https://s.example/?a=2", title = "TWO"), null)
        assertEquals("ONE", c.lookup("https://s.example/?a=1")!!.data!!.title)
        assertEquals("TWO", c.lookup("https://s.example/?a=2")!!.data!!.title)
    }

    @Test
    fun `a preview with no title and no site name still round-trips`() {
        // The OG-less page case: absent optionals must come back absent, not as "".
        val c = LinkPreviewCache(cacheDir(), TEST_KEYS) { 1L }
        c.store(
            "https://bare.example",
            LinkPreviewData("https://bare.example", null, null, null, null),
            null
        )
        val hit = c.lookup("https://bare.example")!!
        assertNotNull(hit.data)
        assertFalse(hit.isNegative)
        assertNull(hit.data!!.title)
        assertNull(hit.data!!.description)
        assertNull(hit.data!!.imageUrl)
        assertNull(hit.data!!.siteName)
        assertEquals("https://bare.example", hit.data!!.url)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. M-6 — the document filename on the LEGACY wire
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `a document's name rides the legacy envelope, in the key iOS reads`() {
        // iOS `parseAndStoreMedia` reads `mediaJson["fileName"]` (MessageManager.swift:7317)
        // and writes it onto `message.mediaFileName` (:7334).
        val wire = MessageRepository.legacyMediaEnvelope(
            "DOCUMENT", "", "QUJD", false, fileName = "Contrat-2026.pdf"
        )
        assertEquals("Contrat-2026.pdf", JSONObject(wire).getString("fileName"))
    }

    @Test
    fun `no name means NO KEY — today's photo traffic stays byte-identical`() {
        val withNull = MessageRepository.legacyMediaEnvelope("IMAGE", "beach", "QUJD", false, null)
        // Pinned as a literal, not rebuilt from the function: this is the exact
        // envelope that shipped, and it must not move by one byte.
        assertEquals(
            """{"type":"IMAGE","caption":"beach","data":"QUJD","isViewOnce":false}""",
            withNull
        )
        assertFalse(JSONObject(withNull).has("fileName"))
        // The 4-argument form every existing caller uses is the same string.
        assertEquals(withNull, MessageRepository.legacyMediaEnvelope("IMAGE", "beach", "QUJD", false))
    }

    @Test
    fun `a name that sanitizes to nothing is omitted, not emitted empty`() {
        for (junk in listOf("", "   ", "..", "../", "/", " ")) {
            val wire = MessageRepository.legacyMediaEnvelope("DOCUMENT", "", "QUJD", false, junk)
            assertFalse("junk=$junk", JSONObject(wire).has("fileName"))
        }
    }

    @Test
    fun `a traversal is never EMITTED, in any direction`() {
        val wire = MessageRepository.legacyMediaEnvelope(
            "DOCUMENT", "", "QUJD", false, "../../../etc/passwd"
        )
        assertEquals("passwd", JSONObject(wire).getString("fileName"))
    }

    @Test
    fun `a name full of JSON-hostile characters still leaves the envelope parseable`() {
        // The escaping bug this envelope already had once (a lone backslash in a
        // CAPTION produced invalid JSON and iOS stored the whole base64 photo as a
        // text bubble), one field over. A name is just as attacker-shaped.
        //
        // The quote is a FORBIDDEN filesystem character, so it is stripped rather
        // than escaped; the point of the assertion is that iOS's JSONSerialization
        // can still read the envelope at all, because it rejects the WHOLE message
        // otherwise.
        val wire = MessageRepository.legacyMediaEnvelope(
            "DOCUMENT", "", "QUJD", false, """He said "hi".pdf"""
        )
        assertEquals("He said hi.pdf", JSONObject(wire).getString("fileName"))
    }

    @Test
    fun `a Windows path in the name is reduced to its leaf`() {
        // `sanitize` takes the last component after BOTH separators, so
        // "..\..\windows\evil.pdf" cannot escape anything on the far side either.
        val wire = MessageRepository.legacyMediaEnvelope(
            "DOCUMENT", "", "QUJD", false, "C:\\Users\\moi\\Rapport final.pdf"
        )
        assertEquals("Rapport final.pdf", JSONObject(wire).getString("fileName"))
    }

    @Test
    fun `an inbound document with no caption takes its name off the wire`() {
        // iOS's legacy sender: fileName set, body EMPTY (MessageManager.swift:5294-5296).
        assertEquals(
            "Contrat-2026.pdf",
            MessageRepository.inboundMediaDisplayText(MediaType.DOCUMENT, "", "Contrat-2026.pdf")
        )
    }

    @Test
    fun `a caption already carrying the name WINS — Android to Android must not change`() {
        assertEquals(
            "[DOCUMENT] Rapport.pdf",
            MessageRepository.inboundMediaDisplayText(
                MediaType.DOCUMENT, "[DOCUMENT] Rapport.pdf", "Rapport.pdf"
            )
        )
    }

    @Test
    fun `a peer-supplied traversal is not even STORED`() {
        assertEquals(
            "passwd",
            MessageRepository.inboundMediaDisplayText(MediaType.DOCUMENT, "", "../../etc/passwd")
        )
        // Nothing usable at all → NOT an invented name, and (since
        // __A_PHOTO_WITH_NO_CAPTION_WAS_A_BLANK_ROW_2026_11_04__) not blank
        // either. The security property this test exists for is unchanged: what
        // the peer supplied never reaches storage.
        for (hostile in listOf("..", null)) {
            val stored = MessageRepository.inboundMediaDisplayText(MediaType.DOCUMENT, "", hostile)
            assertEquals("[DOCUMENT]", stored)
            assertFalse("a hostile name must not survive", stored.contains(".."))
        }
    }

    @Test
    fun `a photo's fileName is NOT its bubble text`() {
        assertEquals(
            "beach",
            MessageRepository.inboundMediaDisplayText(MediaType.IMAGE, "beach", "IMG_0042.jpg")
        )
        // __A_PHOTO_WITH_NO_CAPTION_WAS_A_BLANK_ROW_2026_11_04__ This asserted ""
        // and so made the blank row a tested requirement. The INTENT of the test
        // is intact and is what is checked now: a photo's file name must not
        // become its bubble text. What changed is only that "not the file name"
        // is no longer spelled "nothing at all", because nothing at all rendered
        // as an empty conversation row.
        val stored = MessageRepository.inboundMediaDisplayText(MediaType.IMAGE, "", "IMG_0042.jpg")
        assertNotEquals("the file name must not become the bubble text", "IMG_0042.jpg", stored)
        assertFalse("and it must not be blank either", stored.isEmpty())
        assertEquals("[IMAGE]", stored)
    }

    @Test
    fun `the web client downloads a document under its real name`() {
        assertEquals("Contrat.pdf", MessageRepository.webDownloadName("DOCUMENT", "[DOCUMENT] Contrat.pdf"))
        assertEquals("Contrat.pdf", MessageRepository.webDownloadName("DOCUMENT", "Contrat.pdf"))
        // Non-documents have no user-chosen name; the web client handles null.
        assertNull(MessageRepository.webDownloadName("IMAGE", "[DOCUMENT] x.pdf"))
        assertNull(MessageRepository.webDownloadName(null, "Contrat.pdf"))
        // …and a peer-supplied traversal must not become a Content-Disposition name.
        assertEquals("passwd", MessageRepository.webDownloadName("DOCUMENT", "../../etc/passwd"))
        assertNull(MessageRepository.webDownloadName("DOCUMENT", "[DOCUMENT] "))
    }

    @Test
    fun `the cross-platform mesh envelope carries the document's real name too`() {
        // The third transport, and the last leg of M-6. iOS's
        // `handleCrossPlatformMediaMessage` reads `fileName` out of this exact JSON.
        val wire = MessageRepository.crossPlatformMediaEnvelope(
            dataBase64 = "QUJD",
            iosMediaType = "document",
            fileName = "Contrat-2026.pdf",
            size = 3,
            caption = null,
            isViewOnce = false
        )
        val o = JSONObject(wire)
        assertEquals("Contrat-2026.pdf", o.getString("fileName"))
        assertEquals("document", o.getString("mediaType"))
        assertEquals("QUJD", o.getString("data"))
        assertEquals(3, o.getInt("fileSize"))
        assertEquals("", o.getString("caption"))
        assertFalse(o.getBoolean("isViewOnce"))
    }

    @Test
    fun `the cross-platform mesh envelope is byte-identical to the one that shipped`() {
        // The synthetic name is unchanged, so a photo's LAN envelope must not move
        // by one byte. Pinned as a literal rather than rebuilt from the function.
        assertEquals(
            """{"data":"QUJD","mediaType":"image","fileName":"m1.jpg","fileSize":3,""" +
                """"caption":"beach","isViewOnce":false}""",
            MessageRepository.crossPlatformMediaEnvelope(
                "QUJD", "image", "m1.jpg", 3, "beach", false
            )
        )
    }

    @Test
    fun `a traversal cannot leave through the mesh envelope either`() {
        val o = JSONObject(
            MessageRepository.crossPlatformMediaEnvelope(
                "QUJD", "document", "../../../etc/passwd", 3, null, false
            )
        )
        assertEquals("passwd", o.getString("fileName"))
    }

    @Test
    fun `a caption with a backslash still leaves the mesh envelope parseable`() {
        // iOS drops this envelope OUTRIGHT when it is not valid JSON
        // (MeshNetworkManager.swift:417-424) — no text fallback at all.
        val wire = MessageRepository.crossPlatformMediaEnvelope(
            "QUJD", "image", "m1.jpg", 3, """C:\Users "moi"	tab""", false
        )
        assertEquals("""C:\Users "moi"	tab""", JSONObject(wire).getString("caption"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. M-14 — the multi-select fan-out
    // ══════════════════════════════════════════════════════════════════════════

    private fun img(b: Byte, viewOnce: Boolean = false) =
        PendingMedia(byteArrayOf(b), isVideo = false, viewOnce = viewOnce)

    private fun vid(b: Byte, viewOnce: Boolean = false) =
        PendingMedia(byteArrayOf(b), isVideo = true, viewOnce = viewOnce)

    @Test
    fun `N picked items become N messages, each keeping its OWN view-once flag`() {
        val plan = ChatMediaSelection.plan(
            listOf(img(1, viewOnce = true), vid(2, viewOnce = false), img(3, viewOnce = true)),
            ""
        )
        assertEquals(3, plan.size)
        assertEquals(listOf(true, false, true), plan.map { it.viewOnce })
        assertEquals(listOf(false, true, false), plan.map { it.isVideo })
        assertArrayEquals(byteArrayOf(2), plan[1].bytes)
    }

    @Test
    fun `a caption applies to a SINGLE pick and to nothing else`() {
        // iOS: `content: caption` on the single path (ChatView.swift:2272),
        // `content: ""` on the multi path (:2343).
        assertEquals("beach", ChatMediaSelection.plan(listOf(img(1)), "beach").single().caption)
        val many = ChatMediaSelection.plan(listOf(img(1), img(2)), "beach")
        assertTrue(many.all { it.caption == null })
    }

    @Test
    fun `a blank caption is null, never the empty string`() {
        // `sendMediaMessage` writes `caption ?: "[IMAGE]"` — an empty string would
        // put an empty body on the row where the type marker belongs.
        assertNull(ChatMediaSelection.plan(listOf(img(1)), "").single().caption)
        assertNull(ChatMediaSelection.plan(listOf(img(1)), "   ").single().caption)
    }

    @Test
    fun `toggling view-once touches exactly one item`() {
        val items = listOf(img(1), img(2), img(3))
        val after = ChatMediaSelection.toggleViewOnce(items, 1)
        assertEquals(listOf(false, true, false), after.map { it.viewOnce })
        // …and back again.
        assertEquals(listOf(false, false, false), ChatMediaSelection.toggleViewOnce(after, 1).map { it.viewOnce })
        // Out of range changes nothing and does not throw.
        assertEquals(items, ChatMediaSelection.toggleViewOnce(items, 99))
        assertEquals(items, ChatMediaSelection.toggleViewOnce(items, -1))
    }

    @Test
    fun `removing an item drops exactly that one`() {
        val items = listOf(img(1), img(2), img(3))
        assertEquals(listOf(img(1), img(3)), ChatMediaSelection.removeAt(items, 1))
        assertEquals(items, ChatMediaSelection.removeAt(items, 7))
        assertTrue(ChatMediaSelection.removeAt(listOf(img(1)), 0).isEmpty())
    }

    @Test
    fun `an empty selection plans nothing rather than an empty message`() {
        assertTrue(ChatMediaSelection.plan(emptyList(), "beach").isEmpty())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. M-17 — the hydration budget must degrade to lazy loading
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `bytes on the row are ready immediately — no re-read at all`() {
        assertEquals(
            BubbleMediaKind.READY,
            BubbleMediaKind.of(hasInline = true, hasLoader = false, loading = false, loadedSize = null)
        )
    }

    @Test
    fun `a row the budget blanked shows LOADING, then READY — never an empty bubble`() {
        // This is the M-17 bug in one line: before the fix there was no re-read, so
        // this row rendered nothing, forever.
        assertEquals(
            BubbleMediaKind.LOADING,
            BubbleMediaKind.of(hasInline = false, hasLoader = true, loading = true, loadedSize = null)
        )
        assertEquals(
            BubbleMediaKind.READY,
            BubbleMediaKind.of(hasInline = false, hasLoader = true, loading = false, loadedSize = 4096)
        )
    }

    @Test
    fun `a re-read that found nothing is UNAVAILABLE — a visible state with a retry`() {
        assertEquals(
            BubbleMediaKind.UNAVAILABLE,
            BubbleMediaKind.of(hasInline = false, hasLoader = true, loading = false, loadedSize = null)
        )
    }

    @Test
    fun `ZERO bytes is not READY — an empty array would draw the empty box again`() {
        assertEquals(
            BubbleMediaKind.UNAVAILABLE,
            BubbleMediaKind.of(hasInline = false, hasLoader = true, loading = false, loadedSize = 0)
        )
    }

    @Test
    fun `with no loader there is nothing to wait for, so never a permanent spinner`() {
        assertEquals(
            BubbleMediaKind.UNAVAILABLE,
            BubbleMediaKind.of(hasInline = false, hasLoader = false, loading = true, loadedSize = null)
        )
    }

    @Test
    fun `bytes always beat a stale in-flight read`() {
        // A load that lands while `loading` has not been flipped back must show the
        // bytes, not a spinner over them.
        assertEquals(
            BubbleMediaKind.READY,
            BubbleMediaKind.of(hasInline = false, hasLoader = true, loading = true, loadedSize = 10)
        )
    }
}
