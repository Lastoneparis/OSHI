package com.oshi.messenger

import com.oshi.messenger.data.repository.MessageRepository
import com.oshi.messenger.network.v2.V2FileKeyMessage
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.service.media.ComposerShortcuts
import com.oshi.messenger.service.media.VideoNote
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * __VIDEO_NOTE_2026_09_24__ Round video messages — the wire contract of
 * `docs/VIDEO_NOTE_SPEC.md` (shared with iOS and Desktop), its §6 test vectors, the 15 s
 * cap and the composer rule.
 */
class VideoNoteTest {

    private val key32 = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private val nonce8 = Base64.getEncoder().encodeToString(ByteArray(8) { (0xA0 + it).toByte() })
    private val manifest = Base64.getEncoder().encodeToString(ByteArray(48) { (it * 3).toByte() })

    /** Spec §2.A, byte-for-byte the key set and order an iPhone emits (real base64 values). */
    private fun specFixture(extra: String = ",\"videoNote\":true,\"durationMs\":12480", mediaType: String = "video", mime: String = "video/mp4") =
        """{"kind":"v2file","blobId":"b_5f3c","fileKey":"$key32","fileNonce":"$nonce8","chunkCount":1,""" +
            """"manifest":"$manifest","filename":"videonote_1758711234567.mp4","mime":"$mime",""" +
            """"mediaType":"$mediaType"$extra}"""

    // ── §6 vectors ────────────────────────────────────────────────────────────

    @Test
    fun `vector 1 - the spec 2A payload is a note of 12480 ms`() {
        val k = V2FileKeyMessage.parse(specFixture())
        assertNotNull(k); k!!
        assertEquals(true, k.videoNote)
        assertEquals(12480, k.durationMs)
        assertTrue(VideoNote.isVideoNote(k.videoNote, k.mediaType, k.mime))
    }

    @Test
    fun `vector 2 - both keys removed is a normal video and still decodes`() {
        val k = V2FileKeyMessage.parse(specFixture(extra = ""))
        assertNotNull(k); k!!
        assertNull(k.videoNote)
        assertNull(k.durationMs)
        assertFalse(VideoNote.isVideoNote(k.videoNote, k.mediaType, k.mime))
        assertEquals("video", k.mediaType)
    }

    @Test
    fun `vector 3 - videoNote false is a normal video`() {
        val k = V2FileKeyMessage.parse(specFixture(extra = ",\"videoNote\":false"))!!
        assertFalse(VideoNote.isVideoNote(k.videoNote, k.mediaType, k.mime))
    }

    @Test
    fun `vector 4 - duration clamped, negatives and garbage are unknown, floats rounded`() {
        assertEquals(15000, V2FileKeyMessage.parse(specFixture(",\"videoNote\":true,\"durationMs\":99999"))!!.durationMs)
        assertNull(V2FileKeyMessage.parse(specFixture(",\"videoNote\":true,\"durationMs\":-3"))!!.durationMs)
        assertEquals(1235, V2FileKeyMessage.parse(specFixture(",\"videoNote\":true,\"durationMs\":1234.6"))!!.durationMs)
        val garbage = V2FileKeyMessage.parse(specFixture(",\"videoNote\":true,\"durationMs\":\"abc\""))
        assertNotNull("a malformed duration must not cost the message", garbage)
        assertNull(garbage!!.durationMs)
        assertEquals(true, garbage.videoNote)
    }

    @Test
    fun `vector 5 - videoNote on a photo is ignored`() {
        val k = V2FileKeyMessage.parse(specFixture(mediaType = "image", mime = "image/jpeg"))!!
        assertEquals(true, k.videoNote)
        assertFalse(VideoNote.isVideoNote(k.videoNote, k.mediaType, k.mime))
    }

    @Test
    fun `vector 6 - re-encoding a decoded note emits both keys again`() {
        val k = V2FileKeyMessage.parse(specFixture())!!
        val again = JSONObject(k.toJson())
        assertEquals(true, again.getBoolean("videoNote"))
        assertEquals(12480, again.getInt("durationMs"))
        assertEquals(k, V2FileKeyMessage.parse(k.toJson()))
    }

    @Test
    fun `a normal file key is byte-identical - no videoNote, no durationMs`() {
        val k = V2FileKeyMessage.parse(specFixture(extra = ""))!!
        val json = k.toJson()
        assertFalse(json.contains("videoNote"))
        assertFalse(json.contains("durationMs"))
    }

    @Test
    fun `mime alone decides when mediaType is absent`() {
        assertTrue(VideoNote.isVideoNote(true, null, "video/mp4"))
        assertFalse(VideoNote.isVideoNote(true, null, "image/jpeg"))
        assertTrue(VideoNote.isVideoNote(true, "VIDEO", null))
        assertFalse(VideoNote.isVideoNote(null, "video", "video/mp4"))
    }

    @Test
    fun `flag only counts as a real boolean`() {
        assertNull(VideoNote.flagFrom(JSONObject("""{"videoNote":"true"}""")))
        assertNull(VideoNote.flagFrom(JSONObject("""{"videoNote":1}""")))
        assertEquals(true, VideoNote.flagFrom(JSONObject("""{"videoNote":true}""")))
        assertNull(VideoNote.flagFrom(JSONObject("""{}""")))
    }

    // ── Legacy / mesh / group envelopes (§2.B-§2.E) ───────────────────────────

    @Test
    fun `legacy 1-1 envelope carries the keys at top level and stays valid JSON`() {
        val frag = VideoNote.jsonFragment(true, 9030)
        val o = JSONObject(MessageRepository.legacyMediaEnvelope("VIDEO", null, "AAAA", false, "videonote_1.mp4", frag))
        assertEquals(true, o.getBoolean("videoNote"))
        assertEquals(9030, o.getInt("durationMs"))
        assertEquals("VIDEO", o.getString("type"))
        assertEquals("videonote_1.mp4", o.getString("fileName"))
    }

    @Test
    fun `old payload without the keys - legacy envelope unchanged`() {
        assertEquals(
            MessageRepository.legacyMediaEnvelope("VIDEO", "hi", "AAAA", false),
            MessageRepository.legacyMediaEnvelope("VIDEO", "hi", "AAAA", false, videoNoteFragment = VideoNote.jsonFragment(false, 9000))
        )
    }

    @Test
    fun `cross-platform mesh envelope carries the keys`() {
        val o = JSONObject(
            MessageRepository.crossPlatformMediaEnvelope(
                "AAAA", "video", "videonote_1.mp4", 3, null, false, VideoNote.jsonFragment(true, 15000)
            )
        )
        assertEquals(true, o.getBoolean("videoNote"))
        assertEquals(15000, o.getInt("durationMs"))
    }

    @Test
    fun `group message JSON carries the keys next to mediaType and only on a video`() {
        val o = JSONObject(
            GroupManager.wireGroupMessageJson(
                "M1", "G1", "pk", 780404034.5, "", "video", "videonote_1.mp4", "", includeBodyCompat = false,
                videoNote = true, durationMs = 9030
            )
        )
        assertEquals(true, o.getBoolean("videoNote"))
        assertEquals(9030, o.getInt("durationMs"))
        val photo = JSONObject(
            GroupManager.wireGroupMessageJson(
                "M2", "G1", "pk", 780404034.5, "", "photo", null, "", includeBodyCompat = false,
                videoNote = true, durationMs = 9030
            )
        )
        assertFalse(photo.has("videoNote"))
        val plain = GroupManager.wireGroupMessageJson("M3", "G1", "pk", 1.0, "", "video", null, "", includeBodyCompat = false)
        assertFalse(plain.contains("videoNote"))
    }

    @Test
    fun `legacy group media blob carries the keys`() {
        val o = JSONObject(GroupManager.buildGroupMediaJson("video", "AAAA", 3, null, "n.mp4", videoNote = true, durationMs = 4000))
        assertEquals(true, o.getBoolean("videoNote"))
        assertEquals(4000, o.getInt("durationMs"))
    }

    @Test
    fun `group rule 5 - either location sets the flag, file key duration first`() {
        assertEquals(true to 12480, VideoNote.merge(true, 12480, null, 9000))
        assertEquals(true to 9000, VideoNote.merge(null, null, true, 9000))
        assertEquals(true to 9000, VideoNote.merge(true, null, false, 9000))
        assertEquals(false to null, VideoNote.merge(null, null, null, null))
    }

    // ── 15 s cap ──────────────────────────────────────────────────────────────

    @Test
    fun `recorder stops at exactly 15 s and the ring fills with it`() {
        assertFalse(VideoNote.mustAutoStop(14_999))
        assertTrue(VideoNote.mustAutoStop(15_000))
        assertTrue(VideoNote.mustAutoStop(20_000))
        assertEquals(0f, VideoNote.progress(-5), 0f)
        assertEquals(0.5f, VideoNote.progress(7_500), 0.0001f)
        assertEquals(1f, VideoNote.progress(16_000), 0f)
        assertEquals(0L, VideoNote.remainingMs(15_200))
    }

    @Test
    fun `sender refuses an encoded clip over 15_5 s and clamps the wire duration`() {
        assertTrue(VideoNote.isSendableDuration(15_500))
        assertFalse(VideoNote.isSendableDuration(15_501))
        assertFalse(VideoNote.isSendableDuration(0))
        assertEquals(15000, VideoNote.wireDuration(15_320))
        assertEquals(8000, VideoNote.wireDuration(8_000))
        assertNull(VideoNote.wireDuration(0))
        assertFalse(VideoNote.isKeepable(300))
        assertTrue(VideoNote.isKeepable(1_000))
    }

    @Test
    fun `clock label`() {
        assertEquals("0:00", VideoNote.formatClock(0))
        assertEquals("0:12", VideoNote.formatClock(12_480))
        assertEquals("0:15", VideoNote.formatClock(15_000))
        assertEquals("videonote_1758711234567.mp4", VideoNote.fileName(1758711234567))
    }

    // ── Composer ──────────────────────────────────────────────────────────────

    @Test
    fun `composer shortcuts step aside while typing and come back when cleared`() {
        assertTrue(ComposerShortcuts.visible(""))
        assertFalse(ComposerShortcuts.visible("h"))
        assertFalse(ComposerShortcuts.visible(" "))
        assertFalse(ComposerShortcuts.visible("", isEditing = true))
        assertEquals(4, ComposerShortcuts.maxLines(""))
        assertEquals(6, ComposerShortcuts.maxLines("hello"))
    }
}
