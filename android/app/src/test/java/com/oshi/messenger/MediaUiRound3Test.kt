package com.oshi.messenger

import com.oshi.messenger.data.model.GroupMessage
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.ui.media.BubbleMedia
import com.oshi.messenger.ui.media.VideoThumbnails
import com.oshi.messenger.ui.media.VoiceNoteAnalysis
import com.oshi.messenger.ui.media.VoiceNoteAnalysisCache
import com.oshi.messenger.ui.media.VoiceNoteAudio
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * FIX TEAM MEDIA-UI, round 3.
 *
 * Everything here is pure JVM. What is deliberately NOT asserted, because it cannot be
 * off-device, is stated in the report rather than faked with a test that cannot fail:
 * the actual `MediaMetadataRetriever` probe, Coil's decode, and the rendered bars.
 */
class MediaUiRound3Test {

    @Before
    fun reset() {
        VoiceNoteAnalysisCache.clear()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. The voice-note duration: field → legacy body → "ask the bytes"
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the stored field wins over the legacy body`() {
        // The body says 5 s, the field says 12 s. A row written by the NEW sender has an
        // empty body, but a row MIGRATED from the old convention can carry both, and the
        // field is the one the recorder actually measured.
        assertEquals(12f, VoiceNoteAudio.resolveDurationSeconds(12_000L, "[AUDIO] 0:05")!!, 0.001f)
    }

    @Test
    fun `a note already on disk still reads its duration out of the legacy body`() {
        // This is the fallback the last team's fix removed by accident: emptying the body
        // without keeping this blanks the scrubber for every note already recorded.
        assertEquals(65f, VoiceNoteAudio.resolveDurationSeconds(null, "[AUDIO] 1:05")!!, 0.001f)
        assertEquals(5f, VoiceNoteAudio.resolveDurationSeconds(null, "[AUDIO] 0:05")!!, 0.001f)
    }

    @Test
    fun `a clock time in a caption is NOT a duration`() {
        // The old `extractAudioDuration` matched `\d+:\d+` anywhere in the body, so a
        // photo caption "call me at 3:30" became a 3-and-a-half-minute voice note.
        assertNull(VoiceNoteAudio.resolveDurationSeconds(null, "call me at 3:30"))
        assertNull(VoiceNoteAudio.resolveDurationSeconds(null, ""))
        assertNull(VoiceNoteAudio.resolveDurationSeconds(null, null))
    }

    @Test
    fun `an iPhone voice note falls through to the bytes`() {
        // iOS sends a voice note with an EMPTY caption (`GroupViews.swift:5265-5276`), and
        // the 1:1 V2 file path carries no caption at all. Neither the field nor the body
        // can answer, and null is the signal to probe the container — the old code
        // returned 0f here, which is why every inbound iOS note showed 0:00.
        assertNull(VoiceNoteAudio.resolveDurationSeconds(null, ""))
        assertNull(VoiceNoteAudio.resolveDurationSeconds(0L, ""))
    }

    @Test
    fun `a zero or negative field is not a duration`() {
        assertEquals(5f, VoiceNoteAudio.resolveDurationSeconds(0L, "[AUDIO] 0:05")!!, 0.001f)
        assertEquals(5f, VoiceNoteAudio.resolveDurationSeconds(-1L, "[AUDIO] 0:05")!!, 0.001f)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Nothing new crosses the wire
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a voice note's group media JSON keeps exactly iOS's key set`() {
        // iOS builds `type` / `mediaType` / `content` / `size` (+ `fileName` when there is
        // one) — `GroupMessaging.swift:2534-2546` — and carries NO duration in either
        // direction. `mediaDurationMs` is a local column; inventing a key here would be a
        // one-sided divergence. The caption key must also be gone now that the body is "".
        val json = JSONObject(
            GroupManager.buildGroupMediaJson(
                iosMediaType = "audio",
                contentBase64 = "AAEC",
                size = 3,
                caption = "",          // what the new sender passes
                fileName = null
            )
        )
        assertEquals(setOf("type", "mediaType", "content", "size"), json.keys().asSequence().toSet())
        assertEquals("media", json.getString("type"))
        assertEquals("audio", json.getString("mediaType"))
        assertFalse("no duration key may appear on the wire", json.has("duration"))
        assertFalse("no duration key may appear on the wire", json.has("durationMs"))
        assertFalse("no duration key may appear on the wire", json.has("mediaDurationMs"))
    }

    @Test
    fun `the duration lives on the row, defaults to null, and survives a copy`() {
        val fresh = GroupMessage(
            groupId = "g", senderPublicKey = "pk", senderName = "n",
            content = "", isFromMe = true, mediaType = MediaType.AUDIO
        )
        assertNull("a row written before the column existed reads null", fresh.mediaDurationMs)
        assertEquals(5_000L, fresh.copy(mediaDurationMs = 5_000L).mediaDurationMs)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. The Room edge — the same word-apart mistake the shell team caught
    // ─────────────────────────────────────────────────────────────────────────

    private val appDatabaseSource: String by lazy {
        val f = File("src/main/java/com/oshi/messenger/data/local/AppDatabase.kt")
        assertTrue("AppDatabase.kt not found at ${f.absolutePath}", f.exists())
        f.readText()
    }

    @Test
    fun `the v14 to v15 edge adds mediaDurationMs to group_messages and not to groups`() {
        // `RoomMigrationChainTest` proves the chain is contiguous and wired. It cannot see
        // WHICH table an edge touches, and "groups" vs "group_messages" is one word apart —
        // Room would only complain on the user's device, at launch, after the update.
        val edge = Regex(
            """val MIGRATION_14_15 = object : Migration\(14, 15\)\s*\{(.*?)\n        }""",
            RegexOption.DOT_MATCHES_ALL
        ).find(appDatabaseSource)?.groupValues?.get(1)
            ?: error("MIGRATION_14_15 not declared in AppDatabase.kt")

        assertTrue(
            "the column must be added to `group_messages`",
            edge.contains("\"group_messages\", \"mediaDurationMs\", \"INTEGER\"")
        )
        assertFalse("`groups` is the wrong table", edge.contains("\"groups\""))
        // 2026-08-25 (groups-finish): this used to be
        // `assertTrue(appDatabaseSource.contains("version = 15,"))`, i.e. a hard pin on
        // the CURRENT database version — so the next legitimate edge (v15 → v16, the
        // group message-edit columns) failed a test about the v14 → v15 edge. What this
        // test is actually about is that the v14 → v15 edge exists and touches the right
        // table; the version only has to be AT LEAST the edge's target, which is what
        // catches an edge shipped without its bump.
        val declaredVersion = Regex("""version\s*=\s*(\d+)""")
            .find(appDatabaseSource)?.groupValues?.get(1)?.toInt()
            ?: error("no `version = N` in AppDatabase.kt")
        assertTrue(
            "declared version must be at least 15 — the v14 → v15 edge cannot ship without its bump",
            declaredVersion >= 15
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. The waveform — a verbatim port of iOS's raw-byte analyser
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `bytes are read UNSIGNED, the way Swift's Data reads them`() {
        // THE mutation-sensitive assertion in this file. iOS centres each byte on 128 over
        // a UInt8; Kotlin's ByteArray is SIGNED. A buffer of 0x80 is exactly 128, so the
        // centred value is 0, RMS and peak are 0, and the sample clamps to the floor 0.15.
        //
        // Read as a signed byte, 0x80 is -128, the centred value is -256, RMS is 256, and
        // the same sample clamps to the CEILING 0.95. Two ends of the range from one
        // missing `and 0xFF` — and the bars would still have looked plausible on screen.
        val allMid = ByteArray(500) { 0x80.toByte() }
        val w = VoiceNoteAudio.waveformFromRawData(allMid, 50)
        assertEquals(50, w.size)
        for (v in w) assertEquals(0.15f, v, 0.0001f)
    }

    @Test
    fun `every sample stays inside iOS's 0_15 to 0_95 band`() {
        val data = ByteArray(4096) { (it * 37 + 11).toByte() }
        for (v in VoiceNoteAudio.waveformFromRawData(data, 50)) {
            assertTrue("$v below floor", v >= 0.15f)
            assertTrue("$v above ceiling", v <= 0.95f)
        }
    }

    @Test
    fun `samples past the end of short audio take iOS's 0_3 placeholder`() {
        val tiny = byteArrayOf(1, 2, 3)
        val w = VoiceNoteAudio.waveformFromRawData(tiny, 50)
        assertEquals(50, w.size)
        assertEquals(0.3f, w[10], 0.0001f)
        assertEquals(0.3f, w[49], 0.0001f)
    }

    @Test
    fun `a flat analysis falls through to the deterministic pseudo-random wave`() {
        // iOS: variance < 0.01 means the raw pass told us nothing, so draw something
        // shaped like audio rather than a straight line (`AudioRecorderManager.swift:270-274`).
        val allMid = ByteArray(500) { 0x80.toByte() }
        assertTrue(VoiceNoteAudio.variance(VoiceNoteAudio.waveformFromRawData(allMid, 50)) < 0.01f)

        val w = VoiceNoteAudio.generateWaveform(allMid)
        assertEquals(VoiceNoteAudio.WAVEFORM_SAMPLE_COUNT, w.size)
        assertTrue("the fallback must not be flat", w.toSet().size > 5)
        for (v in w) {
            assertTrue(v >= 0.15f)
            assertTrue(v <= 0.95f)
        }
    }

    @Test
    fun `the same bytes always draw the same bars`() {
        // Not cosmetic: a non-deterministic waveform re-shuffles the bubble on every
        // recomposition. iOS seeds its generator from the data for exactly this reason.
        val a = ByteArray(500) { 0x80.toByte() }
        assertTrue(VoiceNoteAudio.generateWaveform(a).contentEquals(VoiceNoteAudio.generateWaveform(a)))
        val b = ByteArray(500) { if (it == 0) 0x81.toByte() else 0x80.toByte() }
        assertFalse(
            "different audio must not draw an identical wave",
            VoiceNoteAudio.pseudoRandomWaveform(a, 50).contentEquals(VoiceNoteAudio.pseudoRandomWaveform(b, 50))
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Feeding the retriever from RAM (no temp file, no plaintext on disk)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `readAt copies the right slice and reports EOF as minus one`() {
        val data = ByteArray(10) { it.toByte() }
        val buf = ByteArray(8) { -1 }

        assertEquals(4, VoiceNoteAudio.readInto(data, 3L, buf, 0, 4))
        assertEquals(listOf<Byte>(3, 4, 5, 6), buf.take(4))

        // Clamped by what is left in the source...
        assertEquals(2, VoiceNoteAudio.readInto(data, 8L, buf, 0, 8))
        // ...and by the room left in the destination.
        assertEquals(2, VoiceNoteAudio.readInto(data, 0L, buf, 6, 8))

        // -1, never 0: `MediaMetadataRetriever` spins forever on a 0 at end of stream.
        assertEquals(-1, VoiceNoteAudio.readInto(data, 10L, buf, 0, 4))
        assertEquals(-1, VoiceNoteAudio.readInto(data, 99L, buf, 0, 4))
        assertEquals(-1, VoiceNoteAudio.readInto(data, -1L, buf, 0, 4))
        assertEquals(-1, VoiceNoteAudio.readInto(data, 0L, buf, buf.size, 4))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Cache keys — the whole point of M-7
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the bubble cache key is iOS's, and the dimension is part of it`() {
        // iOS: `"<messageId>@<maxDimension>"` (`MediaAttachmentCache.swift:82`). The
        // dimension must be in the key or the full-screen viewer would be served the
        // bubble-sized decode.
        assertEquals("abc@1080", BubbleMedia.cacheKey("abc", 1080))
        assertNotEquals(BubbleMedia.cacheKey("abc", 1080), BubbleMedia.cacheKey("abc", 1920))
        assertNotEquals(BubbleMedia.cacheKey("abc", 1080), BubbleMedia.cacheKey("abd", 1080))
    }

    @Test
    fun `the id-less content key separates two photos of the same length`() {
        // The failure this guards is specific: Coil derives a memory key from the model,
        // and `HeapByteBuffer.toString()` is "…[pos=0 lim=N cap=N]" — IDENTICAL for any two
        // different photos of the same byte length. That would serve one conversation's
        // photo inside another's bubble.
        val a = ByteArray(64) { it.toByte() }
        val b = ByteArray(64) { (it + 1).toByte() }
        assertEquals(BubbleMedia.contentKey(a), BubbleMedia.contentKey(a.copyOf()))
        assertNotEquals(BubbleMedia.contentKey(a), BubbleMedia.contentKey(b))
        assertNotEquals(BubbleMedia.contentKey(a), BubbleMedia.contentKey(ByteArray(65) { it.toByte() }))
    }

    @Test
    fun `the analysis cache is bounded and evicts least-recently-used`() {
        // Keyed by MESSAGE ID, never by the byte array: `ByteArray.hashCode()` is the
        // identity hash and Room hands out a new instance on every emission, so a
        // byte-keyed cache would never hit and every receipt would re-analyse every note.
        val one = VoiceNoteAnalysis(floatArrayOf(0.5f), 1f)
        for (i in 0 until VoiceNoteAnalysisCache.MAX_ENTRIES) {
            VoiceNoteAnalysisCache.put("m$i", one)
        }
        assertEquals(VoiceNoteAnalysisCache.MAX_ENTRIES, VoiceNoteAnalysisCache.size())

        // Touch the oldest so it is no longer the eviction candidate.
        assertTrue(VoiceNoteAnalysisCache.get("m0") != null)
        VoiceNoteAnalysisCache.put("overflow", one)

        assertEquals(VoiceNoteAnalysisCache.MAX_ENTRIES, VoiceNoteAnalysisCache.size())
        assertTrue("the touched entry must survive", VoiceNoteAnalysisCache.get("m0") != null)
        assertNull("the true LRU must be gone", VoiceNoteAnalysisCache.get("m1"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. The cacheDir the old video bubble filled up
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the playback scratch name is stable, hex, and cannot escape cacheDir`() {
        val id = "a/../../etc/passwd é"
        val name = VideoThumbnails.playbackTempName(id)
        assertEquals(name, VideoThumbnails.playbackTempName(id))
        assertTrue(name, Regex("""^playback_[0-9a-f]{32}\.mp4$""").matches(name))
        assertNotEquals(name, VideoThumbnails.playbackTempName("b"))
    }

    @Test
    fun `the sweep matches only the identity-hash names, never the replacement`() {
        // The sweep runs on every chat open. If it matched on the prefix alone it would
        // delete the file the player is about to hand to ExoPlayer.
        assertTrue(VideoThumbnails.isLegacyTempFile("thumb_123456.mp4"))
        assertTrue(VideoThumbnails.isLegacyTempFile("thumb_-987.mp4"))
        assertTrue(VideoThumbnails.isLegacyTempFile("playback_5551212.mp4"))
        assertFalse(VideoThumbnails.isLegacyTempFile(VideoThumbnails.playbackTempName("m1")))
        assertFalse(VideoThumbnails.isLegacyTempFile("thumb_abc.mp4"))
        assertFalse(VideoThumbnails.isLegacyTempFile("holiday.mp4"))
        assertFalse(VideoThumbnails.isLegacyTempFile("thumb_1.mp4.bak"))
    }

    @Test
    fun `sweeping deletes the leaked copies and nothing else`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "oshi-sweep-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        try {
            val leaked = listOf("thumb_1.mp4", "thumb_-2.mp4", "playback_3.mp4")
            val keep = listOf(VideoThumbnails.playbackTempName("m1"), "profile.jpg", "thumb_x.mp4")
            (leaked + keep).forEach { File(dir, it).writeBytes(ByteArray(4)) }

            assertEquals(leaked.size, VideoThumbnails.sweepLegacyTempFiles(dir))
            leaked.forEach { assertFalse(it, File(dir, it).exists()) }
            keep.forEach { assertTrue(it, File(dir, it).exists()) }

            // Idempotent: nothing left to remove on the next chat open.
            assertEquals(0, VideoThumbnails.sweepLegacyTempFiles(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
