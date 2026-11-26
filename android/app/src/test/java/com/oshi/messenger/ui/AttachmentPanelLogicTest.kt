package com.oshi.messenger.ui

import com.oshi.messenger.ui.components.attach.AttachmentPanelLogic
import com.oshi.messenger.ui.components.attach.GalleryAccess
import com.oshi.messenger.ui.components.attach.RecentMediaItem
import com.oshi.messenger.ui.components.attach.SheetTarget
import com.oshi.messenger.ui.components.attach.ToggleOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** __ATTACH_PANEL_2027_2026_11_16__ the attachment panel's rules, off-device. */
class AttachmentPanelLogicTest {

    // ── Order of the "Recent" grid (__RECENT_PHOTOS_ORDER_2026_11_21__) ──────

    private fun item(id: Long, addedSec: Long, takenMs: Long? = null) =
        RecentMediaItem(id, "content://media/external/images/media/$id", false, "image/jpeg", 0, addedSec, takenMs)

    @Test
    fun `capture time wins, index time is the fallback in the same unit`() {
        assertEquals(1_700_000_000_123L, AttachmentPanelLogic.sortTimeMs(1_700_000_000_123L, 5L))
        // No capture time (a PNG, a download): date_added is SECONDS, the sort key ms.
        assertEquals(1_800_000_000_000L, AttachmentPanelLogic.sortTimeMs(null, 1_800_000_000L))
        // MediaStore writes 0, not NULL, for some files: 0 is "unknown", not 1970.
        assertEquals(1_800_000_000_000L, AttachmentPanelLogic.sortTimeMs(0L, 1_800_000_000L))
    }

    @Test
    fun `newest first, a month-old photo restored today does not jump ahead of this week`() {
        val now = 1_800_000_000L // s
        val day = 86_400L
        // A whole library restored onto the phone today: date_added = today for all.
        val restoredMonthOld = item(id = 900, addedSec = now, takenMs = (now - 30 * day) * 1000)
        val takenYesterday = item(id = 100, addedSec = now - day, takenMs = (now - day) * 1000)
        val screenshotNow = item(id = 901, addedSec = now + 60, takenMs = null)
        val sorted = listOf(restoredMonthOld, takenYesterday, screenshotNow).sortedWith(AttachmentPanelLogic.newestFirst)
        assertEquals(listOf(901L, 100L, 900L), sorted.map { it.id })
    }

    @Test
    fun `ties are broken by the larger id, like the SQL clause`() {
        val a = item(1, 10); val b = item(2, 10); val c = item(3, 10)
        assertEquals(listOf(3L, 2L, 1L), listOf(a, c, b).sortedWith(AttachmentPanelLogic.newestFirst).map { it.id })
    }

    @Test
    fun `the SQL order puts a direction on EVERY column`() {
        // QUERY_ARG_SORT_COLUMNS [date_added, _id] + DESCENDING became
        // "date_added, _id DESC" = oldest first. Each term must carry its own DESC.
        val terms = AttachmentPanelLogic.SQL_SORT_ORDER.split(Regex(",(?![^(]*\\))")).map { it.trim() }
        assertEquals(2, terms.size)
        terms.forEach { assertTrue("no direction on '$it'", it.endsWith(" DESC")) }
        assertTrue(terms[0].contains("datetaken") && terms[0].contains("date_added * 1000"))
        assertEquals("_id DESC", terms[1])
        AttachmentPanelLogic.SQL_SORT_ORDER_FALLBACK.split(",").map { it.trim() }
            .forEach { assertTrue("no direction on '$it'", it.endsWith(" DESC")) }
    }

    @Test
    fun `a fresh head adds new photos on top and keeps the scrolled pages`() {
        val shown = (10L downTo 1L).map { item(it, addedSec = it * 100) } // 10 newest .. 1 oldest
        val fresh = listOf(item(11, 1_100), item(10, 1_000), item(9, 900), item(8, 800)) // page of 4
        val merged = AttachmentPanelLogic.mergeFreshHead(shown, fresh, pageSize = 4)
        assertEquals((11L downTo 1L).toList(), merged.map { it.id })
    }

    @Test
    fun `a photo deleted inside the fresh range disappears, older pages stay`() {
        val shown = (10L downTo 1L).map { item(it, addedSec = it * 100) }
        // 9 was deleted: the fresh first page of 4 now reaches down to 6.
        val fresh = listOf(item(10, 1_000), item(8, 800), item(7, 700), item(6, 600))
        val merged = AttachmentPanelLogic.mergeFreshHead(shown, fresh, pageSize = 4)
        assertEquals(listOf(10L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L), merged.map { it.id })
    }

    @Test
    fun `a short fresh head is the whole gallery`() {
        val shown = listOf(item(3, 300), item(2, 200), item(1, 100))
        val merged = AttachmentPanelLogic.mergeFreshHead(shown, listOf(item(4, 400), item(3, 300)), pageSize = 60)
        assertEquals(listOf(4L, 3L), merged.map { it.id })
    }

    @Test
    fun `an appended page never repeats a tile already shown`() {
        val shown = listOf(item(5, 500), item(4, 400))
        val merged = AttachmentPanelLogic.appendPage(shown, listOf(item(4, 400), item(3, 300), item(3, 300)))
        assertEquals(listOf(5L, 4L, 3L), merged.map { it.id })
    }

    // ── Permission states ──────────────────────────────────────────────

    @Test
    fun `Android 14 full, partial and none`() {
        assertEquals(GalleryAccess.FULL, AttachmentPanelLogic.access(34, true, true, true, false))
        assertEquals(GalleryAccess.FULL, AttachmentPanelLogic.access(35, true, false, false, false))
        assertEquals(GalleryAccess.PARTIAL, AttachmentPanelLogic.access(34, false, false, true, false))
        assertEquals(GalleryAccess.NONE, AttachmentPanelLogic.access(36, false, false, false, false))
    }

    @Test
    fun `Android 13 has no partial mode and older builds use the storage grant`() {
        assertEquals(GalleryAccess.NONE, AttachmentPanelLogic.access(33, false, false, true, false))
        assertEquals(GalleryAccess.FULL, AttachmentPanelLogic.access(33, false, true, false, false))
        assertEquals(GalleryAccess.FULL, AttachmentPanelLogic.access(29, false, false, false, true))
        assertEquals(GalleryAccess.NONE, AttachmentPanelLogic.access(26, false, false, false, false))
        // A stale storage grant means nothing on 13+.
        assertEquals(GalleryAccess.NONE, AttachmentPanelLogic.access(33, false, false, false, true))
    }

    @Test
    fun `the permissions asked follow the platform`() {
        assertTrue("android.permission.READ_MEDIA_VISUAL_USER_SELECTED" in AttachmentPanelLogic.permissionsToRequest(34))
        assertFalse("android.permission.READ_MEDIA_VISUAL_USER_SELECTED" in AttachmentPanelLogic.permissionsToRequest(33))
        assertEquals(listOf("android.permission.READ_EXTERNAL_STORAGE"), AttachmentPanelLogic.permissionsToRequest(30))
    }

    @Test
    fun `every permission asked is declared in the manifest`() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        for (sdk in listOf(26, 33, 34)) for (p in AttachmentPanelLogic.permissionsToRequest(sdk)) {
            assertTrue("$p not declared", manifest.contains("android:name=\"$p\""))
        }
    }

    @Test
    fun `settings only after a real refusal`() {
        assertFalse(AttachmentPanelLogic.mustOpenSettings(false, GalleryAccess.NONE, false))
        assertTrue(AttachmentPanelLogic.mustOpenSettings(true, GalleryAccess.NONE, false))
        assertFalse(AttachmentPanelLogic.mustOpenSettings(true, GalleryAccess.NONE, true))
        assertFalse(AttachmentPanelLogic.mustOpenSettings(true, GalleryAccess.PARTIAL, false))
    }

    // ── Selection ──────────────────────────────────────────────────────

    @Test
    fun `selection is ordered and renumbers on removal`() {
        var sel = emptyList<Long>()
        for (id in listOf(10L, 20L, 30L, 40L)) sel = AttachmentPanelLogic.toggle(sel, id, 10).selection
        assertEquals(2, AttachmentPanelLogic.badgeNumber(sel, 20L))
        val r = AttachmentPanelLogic.toggle(sel, 20L, 10)
        assertEquals(ToggleOutcome.REMOVED, r.outcome)
        assertEquals(listOf(10L, 30L, 40L), r.selection)
        assertEquals(2, AttachmentPanelLogic.badgeNumber(r.selection, 30L))
        assertNull(AttachmentPanelLogic.badgeNumber(r.selection, 20L))
    }

    @Test
    fun `the limit refuses the next pick but still allows a removal`() {
        val full = listOf(1L, 2L, 3L)
        val over = AttachmentPanelLogic.toggle(full, 4L, 3)
        assertEquals(ToggleOutcome.LIMIT, over.outcome)
        assertEquals(full, over.selection)
        assertEquals(ToggleOutcome.REMOVED, AttachmentPanelLogic.toggle(full, 2L, 3).outcome)
    }

    @Test
    fun `prune drops items gone from the gallery`() {
        assertEquals(listOf(3L, 1L), AttachmentPanelLogic.prune(listOf(3L, 2L, 1L), setOf(1L, 3L)))
    }

    // ── Presentation ───────────────────────────────────────────────────

    @Test
    fun `durations read like a clock`() {
        assertEquals("0:00", AttachmentPanelLogic.formatDuration(0))
        assertEquals("0:07", AttachmentPanelLogic.formatDuration(6_600))
        assertEquals("3:45", AttachmentPanelLogic.formatDuration(225_000))
        assertEquals("1:02:03", AttachmentPanelLogic.formatDuration(3_723_000))
        assertEquals("0:00", AttachmentPanelLogic.formatDuration(-5))
    }

    @Test
    fun `gif is recognised from the MIME`() {
        val g = RecentMediaItem(1, "content://x/1", false, "image/GIF", 0, 0)
        val j = RecentMediaItem(2, "content://x/2", false, "image/jpeg", 0, 0)
        assertTrue(g.isGif); assertFalse(j.isGif)
        assertFalse(RecentMediaItem(3, "u", false, null, 0, 0).isGif)
    }

    @Test
    fun `album caption rides the first item only, blank is none`() {
        assertEquals(listOf("hi", null, null), AttachmentPanelLogic.captions(3, "  hi "))
        assertEquals(listOf<String?>(null, null), AttachmentPanelLogic.captions(2, "   "))
        assertEquals(listOf("one"), AttachmentPanelLogic.captions(1, "one"))
        assertEquals(emptyList<String?>(), AttachmentPanelLogic.captions(0, "x"))
    }

    @Test
    fun `paging asks for more near the end, never twice, never past the end`() {
        val n = AttachmentPanelLogic.PAGE_SIZE
        assertFalse(AttachmentPanelLogic.shouldLoadMore(0, n, false, false))
        assertTrue(AttachmentPanelLogic.shouldLoadMore(n - AttachmentPanelLogic.PREFETCH_DISTANCE, n, false, false))
        assertFalse(AttachmentPanelLogic.shouldLoadMore(n - 1, n, false, true))
        assertFalse(AttachmentPanelLogic.shouldLoadMore(n - 1, n, true, false))
        assertFalse(AttachmentPanelLogic.shouldLoadMore(0, 0, false, false))
    }

    // ── Sheet physics ──────────────────────────────────────────────────

    @Test
    fun `anchors put the half stop at 58 percent`() {
        val a = AttachmentPanelLogic.anchors(1000f)
        assertEquals(0f, a.expanded, 0.01f)
        assertEquals(420f, a.collapsed, 0.01f)
        assertEquals(1000f, a.hidden, 0.01f)
    }

    @Test
    fun `releases settle on the nearest stop, flicks move one step`() {
        val a = AttachmentPanelLogic.anchors(1000f)
        assertEquals(SheetTarget.EXPANDED, AttachmentPanelLogic.settle(100f, 0f, a))
        assertEquals(SheetTarget.COLLAPSED, AttachmentPanelLogic.settle(300f, 0f, a))
        assertEquals(SheetTarget.COLLAPSED, AttachmentPanelLogic.settle(500f, 0f, a))
        assertEquals(SheetTarget.HIDDEN, AttachmentPanelLogic.settle(800f, 0f, a))
        // Flick up from the half stop → full; flick down from full → half; flick down from half → close.
        assertEquals(SheetTarget.EXPANDED, AttachmentPanelLogic.settle(400f, -3000f, a))
        assertEquals(SheetTarget.COLLAPSED, AttachmentPanelLogic.settle(50f, 3000f, a))
        assertEquals(SheetTarget.HIDDEN, AttachmentPanelLogic.settle(430f, 3000f, a))
        assertEquals(SheetTarget.COLLAPSED, AttachmentPanelLogic.settle(700f, -3000f, a))
    }

    // ── Strings ────────────────────────────────────────────────────────

    @Test
    fun `every panel string exists in every shipped locale`() {
        val res = moduleFile("src/main/AndroidManifest.xml").parentFile!!.resolve("res")
        val base = res.resolve("values/strings_attach.xml").readText()
        val keys = Regex("<string name=\"(attach_[a-z_0-9]+)\"").findAll(base).map { it.groupValues[1] }.toList()
        assertTrue(keys.size >= 20)
        val locales = res.listFiles()!!.filter { File(it, "strings.xml").exists() }
        assertTrue(locales.size >= 30)
        for (d in locales) {
            val f = File(d, "strings_attach.xml")
            assertTrue("${d.name} has no strings_attach.xml", f.exists())
            val xml = f.readText()
            for (k in keys) assertTrue("${d.name} lacks $k", xml.contains("<string name=\"$k\">"))
            for (k in listOf("attach_send_count", "attach_selected_count", "attach_limit")) {
                assertTrue("${d.name}: $k must keep %1\$d", Regex("<string name=\"$k\">[^<]*%1\\\$d").containsMatchIn(xml))
            }
        }
    }

    private fun moduleFile(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            val nested = File(dir, "app/$relative")
            if (nested.isFile) return nested
            dir = dir.parentFile
        }
        error("not found: $relative")
    }
}
