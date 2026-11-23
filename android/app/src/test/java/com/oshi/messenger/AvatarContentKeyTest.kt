package com.oshi.messenger

import com.oshi.messenger.ui.components.ContentKeyedCache
import com.oshi.messenger.ui.components.avatarSampleSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __GROUP_PHOTO_FLICKERED_2026_11_04__ (Anatole) "la photo du groupe clignote".
 *
 * The flicker was one line: three screens keyed `remember`/`LaunchedEffect` on the
 * avatar's `ByteArray`, and `ByteArray.equals` is REFERENCE equality. Every inbound
 * group definition carries the picture as base64 and decodes a fresh array holding
 * byte-for-byte the same image, so the key "changed", the state reset to null, and
 * the photo blanked and re-decoded. Opening a group chat asks every member for a
 * full definition, so it blinked once per answer.
 *
 * The first test is therefore the bug itself, stated as the thing that must stay
 * true: EQUAL CONTENT IS ONE KEY, however many arrays carry it.
 */
class AvatarContentKeyTest {

    @Test
    fun `two arrays holding the same image are one key`() {
        val a = byteArrayOf(1, 2, 3, 4, 5)
        val b = byteArrayOf(1, 2, 3, 4, 5)
        assertNotEquals("precondition: these are different objects", a === b, true)
        assertEquals(ContentKeyedCache.keyOf(a), ContentKeyedCache.keyOf(b))
    }

    @Test
    fun `a different image is a different key`() {
        assertNotEquals(
            ContentKeyedCache.keyOf(byteArrayOf(1, 2, 3)),
            ContentKeyedCache.keyOf(byteArrayOf(1, 2, 4)),
        )
    }

    @Test
    fun `length is part of the key`() {
        assertNotEquals(
            ContentKeyedCache.keyOf(byteArrayOf(1, 2, 3)),
            ContentKeyedCache.keyOf(byteArrayOf(1, 2, 3, 0)),
        )
    }

    // ------------------------------------------------------------------ hits

    @Test
    fun `a hit is returned for an equal but distinct array`() {
        // The whole point: the second array is the one that arrives from the next
        // group definition, and it must hit rather than trigger a re-decode.
        val cache = ContentKeyedCache<String>()
        val stored = byteArrayOf(9, 8, 7)
        cache.put(ContentKeyedCache.keyOf(stored), stored, "photo")
        val arrived = byteArrayOf(9, 8, 7)
        assertEquals("photo", cache.peek(ContentKeyedCache.keyOf(arrived), arrived))
    }

    @Test
    fun `a miss returns null rather than anything stale`() {
        val cache = ContentKeyedCache<String>()
        val stored = byteArrayOf(1)
        cache.put(ContentKeyedCache.keyOf(stored), stored, "photo")
        val other = byteArrayOf(2)
        assertNull(cache.peek(ContentKeyedCache.keyOf(other), other))
    }

    // ------------------------------------------------------- hash collisions

    @Test
    fun `a genuine hash collision does not serve the wrong image`() {
        // `ByteArray.contentHashCode` is Arrays.hashCode: h = 31h + e, 32 bits. These
        // two are the same LENGTH and the same HASH and different CONTENT:
        //   [0, 31] -> 31*1+0  = 31,  31*31+31 = 992
        //   [1, 0]  -> 31*1+1  = 32,  31*32+0  = 992
        // so the key alone cannot tell them apart. Without the `contentEquals` check
        // on a hit, one person's avatar would render as another's. Constructed rather
        // than argued, because "unlikely" is not a guarantee.
        val a = byteArrayOf(0, 31)
        val b = byteArrayOf(1, 0)
        assertEquals("precondition: these collide", ContentKeyedCache.keyOf(a), ContentKeyedCache.keyOf(b))
        assertNotEquals("precondition: and are different", a.toList(), b.toList())

        val cache = ContentKeyedCache<String>()
        cache.put(ContentKeyedCache.keyOf(a), a, "alice")
        assertNull("must not serve alice's photo for bob", cache.peek(ContentKeyedCache.keyOf(b), b))
        assertEquals("alice", cache.peek(ContentKeyedCache.keyOf(a), a))
    }

    // ------------------------------------------------------------- eviction

    @Test
    fun `the cache stays bounded`() {
        val cache = ContentKeyedCache<String>(maxEntries = 3)
        repeat(10) { i ->
            val bytes = byteArrayOf(i.toByte())
            cache.put(ContentKeyedCache.keyOf(bytes), bytes, "v$i")
        }
        assertEquals(3, cache.size)
    }

    @Test
    fun `the least recently USED is evicted, not the least recently added`() {
        // Access ordering matters here: the avatar at the top of the list is read on
        // every recomposition and must not be evicted by a scroll through the rest.
        val cache = ContentKeyedCache<String>(maxEntries = 2)
        val first = byteArrayOf(1)
        val second = byteArrayOf(2)
        val third = byteArrayOf(3)
        cache.put(ContentKeyedCache.keyOf(first), first, "first")
        cache.put(ContentKeyedCache.keyOf(second), second, "second")
        // Touch `first`, making `second` the least recently used.
        assertEquals("first", cache.peek(ContentKeyedCache.keyOf(first), first))
        cache.put(ContentKeyedCache.keyOf(third), third, "third")

        assertEquals("first was just read and must survive", "first", cache.peek(ContentKeyedCache.keyOf(first), first))
        assertNull("second was the least recently used", cache.peek(ContentKeyedCache.keyOf(second), second))
        assertEquals("third", cache.peek(ContentKeyedCache.keyOf(third), third))
    }

    @Test
    fun `re-putting a key replaces rather than grows`() {
        val cache = ContentKeyedCache<String>(maxEntries = 4)
        val bytes = byteArrayOf(7, 7)
        cache.put(ContentKeyedCache.keyOf(bytes), bytes, "old")
        cache.put(ContentKeyedCache.keyOf(bytes), bytes, "new")
        assertEquals(1, cache.size)
        assertEquals("new", cache.peek(ContentKeyedCache.keyOf(bytes), bytes))
    }

    @Test
    fun `an empty image is a usable key and not a crash`() {
        val cache = ContentKeyedCache<String>()
        val empty = ByteArray(0)
        cache.put(ContentKeyedCache.keyOf(empty), empty, "blank")
        assertSame("blank", cache.peek(ContentKeyedCache.keyOf(empty), empty))
    }

    // ----------------------------------------------------------- downsampling

    /**
     * A 500 KB wire limit says nothing about decoded size: `compressImageData` walks
     * JPEG quality down and never resizes, so a 4032x3024 iPhone photo arrives under
     * the limit and still inflates to ~48 MB. Caching those full-size would trade the
     * flicker for an out-of-memory kill, which is why iOS downsamples too
     * (`GroupViews.swift:86-100`). One doubling wrong here is a 4x memory error that
     * nothing else in the app would notice, so the arithmetic is pinned.
     */
    @Test
    fun `an image already small enough is not sampled`() {
        assertEquals(1, avatarSampleSize(400, maxPixels = 400))
        assertEquals(1, avatarSampleSize(399, maxPixels = 400))
        assertEquals(1, avatarSampleSize(1, maxPixels = 400))
    }

    @Test
    fun `sampling never takes the image below the target`() {
        // The invariant that matters for sharpness: longest/sample must stay >= target,
        // so a 32 dp circle is never drawn from a 200 px source.
        for (longest in listOf(401, 500, 799, 800, 801, 1600, 3024, 4032, 8000)) {
            val sample = avatarSampleSize(longest, maxPixels = 400)
            assertTrue(
                "longest=$longest sample=$sample would decode to ${longest / sample}",
                longest / sample >= 400,
            )
        }
    }

    @Test
    fun `sampling is aggressive enough to bound memory`() {
        // The other half: never more than one doubling of headroom above the target,
        // or a 4032 px photo still lands at 2016 px and 16 MB.
        for (longest in listOf(401, 500, 799, 800, 801, 1600, 3024, 4032, 8000)) {
            val sample = avatarSampleSize(longest, maxPixels = 400)
            assertTrue(
                "longest=$longest sample=$sample leaves ${longest / sample} px",
                longest / sample < 800,
            )
        }
    }

    @Test
    fun `the sample size is always a power of two`() {
        // All BitmapFactory honours; anything else is silently rounded, which would
        // make the two tests above lie.
        for (longest in listOf(1, 400, 401, 1000, 3024, 4032, 12000)) {
            val sample = avatarSampleSize(longest, maxPixels = 400)
            assertEquals("longest=$longest sample=$sample", 0, sample and (sample - 1))
        }
    }

    @Test
    fun `a 4032 pixel iPhone photo is cut to a circle-sized bitmap`() {
        // The concrete case from the iOS note: 4032 / 8 = 504 px, ~1 MB, not 48 MB.
        assertEquals(8, avatarSampleSize(4032, maxPixels = 400))
    }
}
