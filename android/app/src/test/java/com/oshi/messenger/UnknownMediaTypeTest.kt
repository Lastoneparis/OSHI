package com.oshi.messenger

import com.oshi.messenger.data.local.Converters
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.service.MultiDeviceSyncManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The unknown-media-type audit, ported from iOS on 2026-09-12.
 *
 * On iOS one message carrying an unrecognised `mediaType` failed the decode of the
 * ENTIRE stored array, after which the store was rewritten empty — total history
 * loss. Android stores 1:1 messages in Room rather than one archive, so the blast
 * radius differs, but every seam where an unknown label meets a `valueOf` is the
 * same class of defect and is pinned here.
 *
 * The label that makes this concrete is `gif`: iOS is adding the case, Android's
 * enum does not have it, and Android will start receiving it. Every assertion below
 * would have failed (or thrown) before the fix.
 */
class UnknownMediaTypeTest {

    // =====================================================================
    // The shared label table
    // =====================================================================

    @Test
    fun `an unknown label yields null rather than throwing`() {
        assertNull(MediaType.fromWireLabel("sticker"))
        assertNull(MediaType.fromWireLabel(""))
        assertNull(MediaType.fromWireLabel(null))
    }

    @Test
    fun `every spelling the wire actually uses maps`() {
        // iOS MediaManager.MediaType raw values…
        assertEquals(MediaType.IMAGE, MediaType.fromWireLabel("image"))
        assertEquals(MediaType.VIDEO, MediaType.fromWireLabel("video"))
        assertEquals(MediaType.AUDIO, MediaType.fromWireLabel("audio"))
        assertEquals(MediaType.DOCUMENT, MediaType.fromWireLabel("document"))
        assertEquals(MediaType.CONTACT, MediaType.fromWireLabel("contact"))
        // …iOS group messages say "photo" (GroupMessaging.swift:406-412)…
        assertEquals(MediaType.IMAGE, MediaType.fromWireLabel("photo"))
        // …and Android's own archives carry the uppercase enum NAME.
        assertEquals(MediaType.LOCATION, MediaType.fromWireLabel("LOCATION"))
    }

    @Test
    fun `the gif case iOS is adding lands on IMAGE, not on null`() {
        assertEquals(MediaType.IMAGE, MediaType.fromWireLabel("gif"))
        assertEquals(MediaType.IMAGE, GroupManager.mapIOSMediaType("gif"))
        assertEquals("IMAGE", MultiDeviceSyncManager.mediaTypeFromIos("gif"))
    }

    /** The contract the older tests pin must survive the table move. */
    @Test
    fun `the pre-existing group mappings are unchanged`() {
        assertEquals(MediaType.CONTACT, GroupManager.mapIOSMediaType("contact"))
        assertEquals(MediaType.IMAGE, GroupManager.mapIOSMediaType("photo"))
        assertNull(GroupManager.mapIOSMediaType("sticker"))
        assertNull(MultiDeviceSyncManager.mediaTypeFromIos("location"))
    }

    // =====================================================================
    // The byte sniff — iOS's fallback when the label is missing or unknown
    // =====================================================================

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `both GIF versions are recognised from their magic`() {
        // "GIF87a" and "GIF89a" — byte 4 is the version, so the prefix covers both.
        assertEquals(MediaType.IMAGE, MediaType.sniff("GIF87a".toByteArray() + ByteArray(8)))
        assertEquals(MediaType.IMAGE, MediaType.sniff("GIF89a".toByteArray() + ByteArray(8)))
    }

    @Test
    fun `the other renderable containers are recognised too`() {
        assertEquals(MediaType.IMAGE, MediaType.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A)))   // PNG
        assertEquals(MediaType.IMAGE, MediaType.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0)))               // JPEG
        assertEquals(MediaType.DOCUMENT, MediaType.sniff("%PDF-1.7".toByteArray()))                 // PDF
        assertEquals(
            MediaType.IMAGE,
            MediaType.sniff("RIFF".toByteArray() + bytes(0, 0, 0, 0) + "WEBP".toByteArray())
        )
    }

    @Test
    fun `bytes it cannot place are null, so the caller decides`() {
        assertNull(MediaType.sniff(null))
        assertNull(MediaType.sniff(ByteArray(0)))
        assertNull(MediaType.sniff(bytes(0x01)))                    // shorter than any signature
        assertNull(MediaType.sniff(bytes(0x00, 0x01, 0x02, 0x03)))
        // "RIFF" alone is a WAV or an AVI, not a WEBP — the tag at offset 8 decides.
        assertNull(MediaType.sniff("RIFF".toByteArray() + bytes(0, 0, 0, 0) + "WAVE".toByteArray()))
    }

    // =====================================================================
    // The Room converter — where one bad row could take a whole query
    // =====================================================================

    /**
     * Room calls this inside the cursor loop, so a throw here does not lose one
     * message: it fails `getMessagesForConversation` entirely and the thread renders
     * empty. A row written by a later build (the `.gif` case, a sideloaded
     * downgrade) must cost one media bubble, never the conversation.
     */
    @Test
    fun `an unreadable stored value degrades to null instead of throwing`() {
        val converters = Converters()
        assertNull(converters.toMediaType("GIF"))
        assertNull(converters.toMediaType("whatever-a-later-build-writes"))
        // The known values still round-trip.
        assertEquals(MediaType.IMAGE, converters.toMediaType(converters.fromMediaType(MediaType.IMAGE)))
        assertEquals(MediaType.CONTACT, converters.toMediaType(converters.fromMediaType(MediaType.CONTACT)))
        assertNull(converters.toMediaType(null))
    }
}
