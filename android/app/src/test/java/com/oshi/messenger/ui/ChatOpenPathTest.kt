package com.oshi.messenger.ui

import com.oshi.messenger.data.local.ThreadListProjection
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.service.media.MediaAttachmentStore
import com.oshi.messenger.service.media.MediaHydration
import com.oshi.messenger.ui.media.BubbleBytesCache
import com.oshi.messenger.ui.screens.ChatDayKey
import com.oshi.messenger.ui.screens.ChatThreadItem
import com.oshi.messenger.ui.screens.ChatThreadItems
import com.oshi.messenger.ui.screens.contentType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/**
 * __CHAT_OPEN_PERF_2026_11_14__ The 1:1 / group open path: what it costs, and that
 * the cheaper path draws the same thread.
 *
 * The always-on tests pin behaviour (day boundaries identical to the old
 * `SimpleDateFormat` helper, identical row list, bounded bytes cache, the light-row
 * rule). The benchmark seeds a ~3 000-message conversation with ~300 photos, videos,
 * documents and voice notes in a REAL encrypted `MediaAttachmentStore` and times both
 * open paths. It writes ~130 MB to the temp dir, so it only runs when asked:
 *
 *     OSHI_BENCH=1 ./gradlew :app:testDebugUnitTest --tests '*ChatOpenPathTest*'
 */
class ChatOpenPathTest {

    private val roots = mutableListOf<File>()

    @After
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
        BubbleBytesCache.clear()
    }

    // ---------------------------------------------------------------- fixtures

    /** The helper both screens used until this change, verbatim. */
    private fun legacyCalendarDay(timestamp: Long): String {
        val normalizedTs = if (timestamp in 1..1_000_000_000_000L) timestamp * 1000 else timestamp
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date(normalizedTs))
    }

    private class Seeded(
        val rows: List<Message>,          // what `SELECT *` returned: inline bytes on the rows
        val store: MediaAttachmentStore,
        val root: File,
        val fileBacked: Int,
        val fileBackedBytes: Long,
    )

    /**
     * ~3 000 messages over ~120 days, newest first (the DAO's ORDER BY timestamp DESC).
     * Media mix per 10 messages roughly: 1 photo; plus videos, documents and voice notes.
     * Sizes follow the repository's rule: ≤ 1.5 MB inline on the row, larger to the store.
     */
    private fun seed(total: Int, scale: Int, rnd: Random = Random(42)): Seeded {
        val root = File(System.getProperty("java.io.tmpdir"), "oshi-open-bench-${System.nanoTime()}")
        root.mkdirs(); roots += root
        val store = MediaAttachmentStore(root, com.oshi.messenger.data.local.atrest.AtRestKeyProvider { KEY })
        val inlineCap = 1_500_000
        var fileBacked = 0
        var fileBackedBytes = 0L
        val now = 1_763_100_000_000L
        val rows = ArrayList<Message>(total)
        for (i in 0 until total) {
            val ts = now - i * 3_500_000L                // ~1 h apart → ~120 days
            val fromMe = rnd.nextInt(3) == 0
            val (type, size, content) = when {
                i % 10 == 3 && i % 100 < 87 -> Triple(MediaType.IMAGE, 60_000 + rnd.nextInt(340_000), "[Image]")  // 260 inline photos
                i % 10 == 3 -> Triple(MediaType.IMAGE, 1_600_000 + rnd.nextInt(900_000), "[Image]")          // ~40 large photos
                i % 50 == 7 && i % 100 < 50 -> Triple(MediaType.VIDEO, 3_000_000 + rnd.nextInt(2_000_000), "[Video]")
                i % 50 == 7 -> Triple(MediaType.VIDEO, 800_000 + rnd.nextInt(600_000), "[Video]")
                i % 75 == 11 -> Triple(MediaType.DOCUMENT, if (i % 2 == 0) 2_000_000 else 300_000, "report-$i.pdf")
                i % 20 == 15 -> Triple(MediaType.AUDIO, 15_000 + rnd.nextInt(60_000), "[AUDIO] 0:0${i % 9}")
                else -> Triple(null, 0, "message $i " + "lorem ipsum ".repeat(1 + i % 6))
            }
            val id = "m-%05d".format(i)
            var inline: ByteArray? = null
            if (type != null) {
                val bytes = ByteArray(size / scale).also { rnd.nextBytes(it) }
                if (bytes.size <= inlineCap / scale) inline = bytes
                else { check(store.save(id, bytes)); fileBacked++; fileBackedBytes += bytes.size }
            }
            rows += Message(
                id = id, senderPublicKey = if (fromMe) "me" else "peer",
                recipientPublicKey = if (fromMe) "peer" else "me",
                content = content, encryptedContent = ByteArray(200 + content.length),
                timestamp = ts, isFromMe = fromMe, isRead = true, isDelivered = true,
                mediaType = type, mediaData = inline,
            )
        }
        return Seeded(rows, store, root, fileBacked, fileBackedBytes)
    }

    /** Kotlin mirror of the `CASE … AS mediaData` / `NULL AS encryptedContent` in `getThreadRowsForContact`. */
    private fun lightRow(m: Message, limit: Int = ThreadListProjection.INLINE_MAX_BYTES): Message {
        val keep = m.mediaData != null && (m.mediaType == null || m.mediaData.size <= limit)
        return m.copy(encryptedContent = null, mediaData = if (keep) m.mediaData else null)
    }

    /** What the CursorWindow hands Java for one row's blobs: a copy of each. */
    private fun materialise(rows: List<Message>): Long {
        var bytes = 0L
        for (m in rows) {
            m.mediaData?.copyOf()?.let { bytes += it.size }
            m.encryptedContent?.copyOf()?.let { bytes += it.size }
        }
        return bytes
    }

    private inline fun ms(block: () -> Unit): Double {
        val t0 = System.nanoTime(); block(); return (System.nanoTime() - t0) / 1e6
    }

    // ------------------------------------------------------- behaviour, always on

    @Test
    fun `day key agrees with the old SimpleDateFormat day across zones and DST`() {
        val saved = TimeZone.getDefault()
        try {
            for (zoneId in listOf("UTC", "Europe/Paris", "America/New_York", "Australia/Lord_Howe", "Asia/Kolkata", "Pacific/Chatham")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
                val dayOf = ChatDayKey.inDefaultZone()
                val rnd = Random(zoneId.hashCode())
                var prev: Long? = null
                // Every 17 minutes across two years — crosses every DST edge both ways —
                // plus legacy second-resolution timestamps.
                var ts = 1_700_000_000_000L
                while (ts < 1_763_000_000_000L) {
                    val legacy = legacyCalendarDay(ts)
                    val key = dayOf(ts)
                    if (prev != null) {
                        val prevTs = ts - 17 * 60_000L
                        assertEquals("$zoneId @ $ts", legacyCalendarDay(prevTs) == legacy, dayOf(prevTs) == key)
                    }
                    prev = key
                    ts += 17 * 60_000L
                }
                repeat(500) {
                    val a = 1_600_000_000L + rnd.nextLong(100_000_000L)   // seconds
                    val b = a + rnd.nextLong(-200_000L, 200_000L)
                    assertEquals(legacyCalendarDay(a) == legacyCalendarDay(b), dayOf(a) == dayOf(b))
                }
            }
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test
    fun `thread rows built with the day key are identical to the old build`() {
        val s = seed(total = 1500, scale = 1000)
        val old = ChatThreadItems.build(s.rows, ::legacyCalendarDay)
        val new = ChatThreadItems.build(s.rows, ChatDayKey.inDefaultZone())
        assertEquals(old.map { it.key }, new.map { it.key })
        assertEquals(old, new)
        // Keys are unique (LazyColumn requires it) and every row has a content type.
        assertEquals(new.size, new.map { it.key }.toSet().size)
        assertTrue(new.all { it.contentType().isNotEmpty() })
        assertTrue(new.any { it is ChatThreadItem.PhotoStack } || new.any { it is ChatThreadItem.Bubble })
    }

    @Test
    fun `light rows keep order, text and small attachments, and drop the large ones`() {
        val s = seed(total = 200, scale = 1)
        val light = s.rows.map { lightRow(it) }
        assertEquals(s.rows.map { it.id }, light.map { it.id })
        assertEquals(s.rows, light)            // Message.equals: every rendered field equal
        for ((a, b) in s.rows.zip(light)) {
            assertNull(b.encryptedContent)
            val size = a.mediaData?.size ?: 0
            if (a.mediaData != null && size <= ThreadListProjection.INLINE_MAX_BYTES) assertTrue(a.mediaData === b.mediaData)
            if (size > ThreadListProjection.INLINE_MAX_BYTES) assertNull(b.mediaData)
        }
        // A legacy, untyped row keeps its bytes whatever their size (voice notes from old iOS).
        val legacy = Message(id = "x", senderPublicKey = "p", recipientPublicKey = "me", content = "[AUDIO] 0:03",
            isFromMe = false, mediaType = null, mediaData = ByteArray(200_000))
        assertTrue(lightRow(legacy).mediaData != null)
    }

    @Test
    fun `bubble bytes cache stays inside its count and byte bounds`() {
        BubbleBytesCache.clear()
        repeat(400) { BubbleBytesCache.put("id$it", ByteArray(300_000)) }
        assertTrue(BubbleBytesCache.totalBytes() <= BubbleBytesCache.MAX_TOTAL_BYTES)
        assertTrue(BubbleBytesCache.size() <= BubbleBytesCache.MAX_ENTRIES)
        assertTrue(BubbleBytesCache.get("id399") != null)        // newest kept
        assertNull(BubbleBytesCache.get("id0"))                  // oldest evicted
        BubbleBytesCache.put("huge", ByteArray((BubbleBytesCache.MAX_ENTRY_BYTES + 1).toInt()))
        assertNull(BubbleBytesCache.get("huge"))                 // a big video is never pinned
        BubbleBytesCache.put("id399", ByteArray(10))             // replace keeps the books right
        val before = BubbleBytesCache.totalBytes()
        BubbleBytesCache.evict("id399")
        assertEquals(before - 10, BubbleBytesCache.totalBytes())
    }

    // ------------------------------------------------------- benchmark, opt-in

    @Test
    fun `benchmark - open a 3000 message chat with 300 photos`() {
        assumeTrue("set OSHI_BENCH=1 to run", System.getenv("OSHI_BENCH") != null)
        val s = seed(total = 3000, scale = 1)
        val photos = s.rows.count { it.mediaType == MediaType.IMAGE }
        val out = StringBuilder()
        out.appendLine("seeded ${s.rows.size} msgs: $photos photos, ${s.rows.count { it.mediaType == MediaType.VIDEO }} videos, " +
            "${s.rows.count { it.mediaType == MediaType.DOCUMENT }} docs, ${s.rows.count { it.mediaType == MediaType.AUDIO }} voice; " +
            "${s.fileBacked} file-backed (${s.fileBackedBytes / 1_048_576} MB), inline on rows " +
            "${s.rows.sumOf { (it.mediaData?.size ?: 0).toLong() } / 1_048_576} MB")

        // warm-up of JIT on a small copy so the first timed run is not all class loading
        repeat(3) {
            ChatThreadItems.build(s.rows.take(300), ::legacyCalendarDay)
            ChatThreadItems.build(s.rows.take(300), ChatDayKey.inDefaultZone())
        }

        // ---- BEFORE: every emission = SELECT * (copy every blob) + hydrate (60 files / 96 MB) on Main
        var hydrated: List<Message> = emptyList()
        val coldStore = MediaAttachmentStore(s.root, com.oshi.messenger.data.local.atrest.AtRestKeyProvider { KEY })
        val oldCopyMs = ms { materialise(s.rows) }
        val oldBytes = materialise(s.rows)
        val oldHydrateColdMs = ms { hydrated = MediaHydration.hydrate(s.rows, coldStore) }
        val inlineOnRows = s.rows.sumOf { (it.mediaData?.size ?: 0).toLong() }
        val heldByHydrate = hydrated.sumOf { (it.mediaData?.size ?: 0).toLong() } - inlineOnRows
        val hydratedCount = hydrated.zip(s.rows).count { (h, r) -> h.mediaData != null && r.mediaData == null }
        val oldHydrateWarmMs = ms { MediaHydration.hydrate(s.rows, coldStore) }
        val oldBuildMs = (1..5).map { ms { ChatThreadItems.build(hydrated, ::legacyCalendarDay) } }.sorted()[2]

        // ---- AFTER: light rows, no hydration, build once off Main, ~10 visible bubbles load
        val light = s.rows.map { lightRow(it) }
        val newCopyMs = ms { materialise(light) }
        val newBytes = materialise(light)
        val newBuildMs = (1..5).map { ms { ChatThreadItems.build(light, ChatDayKey.inDefaultZone()) } }.sorted()[2]
        val freshStore = MediaAttachmentStore(s.root, com.oshi.messenger.data.local.atrest.AtRestKeyProvider { KEY })
        // Worst case for the first screen: the newest 10 media rows the light row left empty.
        val firstScreen = light.filter { it.mediaType != null && it.mediaData == null && it.mediaType != MediaType.DOCUMENT }.take(10)
        val visibleLoadMs = ms {
            for (m in firstScreen) (s.rows.first { it.id == m.id }.mediaData ?: freshStore.load(m.id))
        }
        val heldAfter = light.sumOf { (it.mediaData?.size ?: 0).toLong() }

        out.appendLine("BEFORE per emission: blob copy ${"%.1f".format(oldCopyMs)} ms for ${oldBytes / 1_048_576} MB; " +
            "hydrate cold ${"%.1f".format(oldHydrateColdMs)} ms / warm ${"%.1f".format(oldHydrateWarmMs)} ms " +
            "(decrypts $hydratedCount files, ${heldByHydrate / 1_048_576} MB; list then holds ${(heldByHydrate + inlineOnRows) / 1_048_576} MB); thread build ${"%.1f".format(oldBuildMs)} ms (per recomposition)")
        out.appendLine("AFTER  per emission: blob copy ${"%.2f".format(newCopyMs)} ms for ${newBytes / 1024} KB; " +
            "no hydrate; thread build ${"%.2f".format(newBuildMs)} ms (once per change, off Main); " +
            "first-screen media ${firstScreen.size} loads ${"%.1f".format(visibleLoadMs)} ms (off Main); list holds ${heldAfter / 1024} KB")
        println(out)
        File(System.getProperty("java.io.tmpdir"), "oshi-chat-open-bench.txt").appendText(out.toString())

        assertTrue(newBytes < oldBytes / 10)
        assertTrue(newBuildMs < oldBuildMs)
    }

    @Test
    fun `benchmark - group thread day flags`() {
        assumeTrue("set OSHI_BENCH=1 to run", System.getenv("OSHI_BENCH") != null)
        val n = 3000
        val ts = LongArray(n) { 1_763_100_000_000L - (n - it) * 3_500_000L }
        repeat(3) { ts.map(::legacyCalendarDay) }
        // GroupChatScreen ran both of these in composition: 2 calls per row for the
        // header flags and 2 per row for the tail flags.
        val oldMs = (1..5).map {
            ms {
                BooleanArray(n) { i -> i == 0 || legacyCalendarDay(ts[i - 1]) != legacyCalendarDay(ts[i]) }
                ChatThreadItems.tailFlags(n, newestFirst = false) { a, b -> legacyCalendarDay(ts[a]) == legacyCalendarDay(ts[b]) }
            }
        }.sorted()[2]
        val newMs = (1..5).map {
            ms {
                val dayOf = ChatDayKey.inDefaultZone()
                val days = LongArray(n) { dayOf(ts[it]) }
                BooleanArray(n) { i -> i == 0 || days[i - 1] != days[i] }
                ChatThreadItems.tailFlags(n, newestFirst = false) { a, b -> days[a] == days[b] }
            }
        }.sorted()[2]
        val line = "GROUP day flags for $n msgs: before ${"%.1f".format(oldMs)} ms, after ${"%.2f".format(newMs)} ms"
        println(line)
        File(System.getProperty("java.io.tmpdir"), "oshi-chat-open-bench.txt").appendText(line + "\n")
        assertTrue(newMs < oldMs)
    }

    private companion object {
        val KEY = ByteArray(32) { (it * 13 + 7).toByte() }
    }
}
