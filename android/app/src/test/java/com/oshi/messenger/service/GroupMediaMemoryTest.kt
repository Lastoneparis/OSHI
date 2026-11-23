package com.oshi.messenger.service

import com.oshi.messenger.data.model.MediaType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** __GROUP_MEDIA_MEMORY_2026_11_11__ */
class GroupMediaMemoryTest {
    private val big = 26L * 1024 * 1024
    private val cap = GroupMediaMemory.MAX_IN_MEMORY_BYTES

    @Test fun largeDocumentsAndVideosStayOnDisk() {
        assertFalse(GroupMediaMemory.keepInMemory(MediaType.DOCUMENT, false, big))
        assertFalse(GroupMediaMemory.keepInMemory(MediaType.VIDEO, false, big))
        assertFalse(GroupMediaMemory.keepInMemory(MediaType.VIDEO, false, cap + 1))
    }

    @Test fun smallMediaAndInlineRenderedKindsStay() {
        assertTrue(GroupMediaMemory.keepInMemory(MediaType.DOCUMENT, false, cap))
        assertTrue(GroupMediaMemory.keepInMemory(MediaType.IMAGE, false, big))
        assertTrue(GroupMediaMemory.keepInMemory(MediaType.AUDIO, false, big))
        assertTrue(GroupMediaMemory.keepInMemory(MediaType.CONTACT, false, big))
        // A round video note plays from its bytes; it is ≤ 15 s anyway.
        assertTrue(GroupMediaMemory.keepInMemory(MediaType.VIDEO, true, 3L * 1024 * 1024))
        assertTrue(GroupMediaMemory.keepInMemory(null, false, big))
    }
}
