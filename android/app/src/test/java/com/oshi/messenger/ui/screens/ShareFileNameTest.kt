package com.oshi.messenger.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** __SHARE_REAL_NAME_2026_09_27__ A shared document keeps its name and extension. */
class ShareFileNameTest {

    @Test
    fun `a document is shared under its own name`() {
        assertEquals("Contrat-2026.pdf", shareFileName("DOCUMENT", "Contrat-2026.pdf"))
        assertEquals("Contrat-2026.pdf", shareFileName("DOCUMENT", "[DOCUMENT] Contrat-2026.pdf"))
        assertEquals("application/pdf", shareMimeType("DOCUMENT", "Contrat-2026.pdf"))
        assertEquals("text/vcard", shareMimeType("DOCUMENT", "contact.vcf"))
    }

    @Test
    fun `no name falls back to the synthetic one`() {
        assertEquals("share_media.bin", shareFileName("DOCUMENT", ""))
        assertEquals("application/octet-stream", shareMimeType("DOCUMENT", "share_media.bin"))
    }

    @Test
    fun `photos and videos ignore the caption`() {
        assertEquals("share_media.jpg", shareFileName("IMAGE", "Sunset at the beach"))
        assertEquals("share_media.mp4", shareFileName("VIDEO", "clip.mov"))
        assertEquals("video/mp4", shareMimeType("VIDEO", "share_media.mp4"))
    }
}
