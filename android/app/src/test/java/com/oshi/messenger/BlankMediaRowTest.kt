package com.oshi.messenger

import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.data.repository.MessageRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __A_PHOTO_WITH_NO_CAPTION_WAS_A_BLANK_ROW_2026_11_04__
 *
 * Anatole, with a screenshot: a conversation row showing an avatar, "Livio" and
 * "1h ago", and nothing else. He had reported a blank area before, during the
 * relay outage on the morning of the 4th, so this read as the same thing coming
 * back. It was not. The relay was healthy (eighteen probes, all 200). This is a
 * second, unrelated defect with the same symptom, and it had been there all along.
 *
 * THE CHAIN
 *
 *  1. `wireCaption` STRIPS our own `[IMAGE]` marker before sending, deliberately,
 *     so a peer is never shown a word nobody typed.
 *  2. So every captionless photo arrives with `caption = ""`. Android to Android
 *     included; this is not an iPhone quirk.
 *  3. `inboundMediaDisplayText` returned the caption "empty or not" for anything
 *     that was not a document or a contact, so the message was stored with
 *     `content = ""`.
 *  4. `listPreview("")` matched none of its branches and handed the empty string
 *     back, so the row rendered blank.
 *
 * Every photo sent without a caption. Not an edge case.
 */
class BlankMediaRowTest {

    private val labels = OSHIControlPayload.Labels()

    // ------------------------------------------------- the source of the blank

    @Test
    fun `a captionless photo is stored as something, not as nothing`() {
        val stored = MessageRepository.inboundMediaDisplayText(MediaType.IMAGE, "", null)
        assertFalse("a photo must never be stored with an empty body", stored.isEmpty())
        assertEquals("[IMAGE]", stored)
    }

    @Test
    fun `video and audio get the same treatment`() {
        assertEquals("[VIDEO]", MessageRepository.inboundMediaDisplayText(MediaType.VIDEO, "", null))
        assertEquals("[AUDIO]", MessageRepository.inboundMediaDisplayText(MediaType.AUDIO, "", null))
    }

    @Test
    fun `a typed caption still wins over the placeholder`() {
        // The whole point of the caption slot. A regression here would replace
        // what somebody actually wrote with a marker.
        assertEquals(
            "regarde ça",
            MessageRepository.inboundMediaDisplayText(MediaType.IMAGE, "regarde ça", null)
        )
    }

    @Test
    fun `a document still prefers its real file name`() {
        // Unchanged behaviour, pinned because the fix reshaped this branch: an
        // iPhone document arrives with an empty body and its name in `fileName`,
        // and that name is what the row and the Download button need.
        assertEquals(
            "rapport.pdf",
            MessageRepository.inboundMediaDisplayText(MediaType.DOCUMENT, "", "rapport.pdf")
        )
    }

    @Test
    fun `a document with no usable name falls back to the placeholder, not to blank`() {
        // `sanitize` answers null when nothing usable survives (a traversal, an
        // empty name). That used to fall through to the empty caption.
        val stored = MessageRepository.inboundMediaDisplayText(MediaType.DOCUMENT, "", "../../etc/passwd")
        assertFalse("still must not be blank", stored.isEmpty())
    }

    @Test
    fun `a location is NOT given a placeholder`() {
        // The one exclusion, and it is deliberate: a location is not drawn as a
        // media bubble and has no entry in the preview catalog, so "[LOCATION]"
        // would be the single placeholder a user could actually read.
        assertEquals("", MessageRepository.inboundMediaDisplayText(MediaType.LOCATION, "", null))
    }

    // ----------------------------------------------------------- the last net

    @Test
    fun `the preview of an empty body is never empty`() {
        // The net, independent of the fix above: whatever future path stores an
        // empty body, it must not reach a user's screen as a blank row.
        val preview = OSHIControlPayload.listPreview("", labels)
        assertFalse("a row must never render empty", preview.isEmpty())
    }

    @Test
    fun `whitespace alone is treated as empty`() {
        // A body of spaces renders exactly as blank as a body of nothing.
        assertFalse(OSHIControlPayload.listPreview("   ", labels).isBlank())
        assertFalse(OSHIControlPayload.listPreview("\n", labels).isBlank())
    }

    @Test
    fun `the placeholder becomes a readable preview`() {
        // The two halves meeting: what receiving now stores is what the list
        // already knew how to render.
        val stored = MessageRepository.inboundMediaDisplayText(MediaType.IMAGE, "", null)
        val preview = OSHIControlPayload.listPreview(stored, labels)
        assertTrue("got '$preview'", preview.contains(labels.mediaImage))
        assertFalse("and it is not the raw marker", preview == "[IMAGE]")
    }

    @Test
    fun `ordinary prose is still handed back untouched`() {
        // The net must not start rewriting real messages.
        assertEquals("salut, tu viens ce soir ?",
            OSHIControlPayload.listPreview("salut, tu viens ce soir ?", labels))
    }
}
