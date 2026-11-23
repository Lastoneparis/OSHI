package com.oshi.messenger.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * __GROUP_PHOTO_FLICKERED_2026_11_04__ (Anatole) "la photo du groupe clignote".
 *
 * WHAT WAS WRONG
 *
 * Three screens decoded an avatar with the same shape:
 *
 *     var bitmap by remember(bytes) { mutableStateOf<Bitmap?>(null) }
 *     LaunchedEffect(bytes) { bitmap = decode(bytes) }
 *
 * and `ConversationsScreen` even carried the comment "the remember key ensures we
 * re-decode only when the underlying bytes change". It does not. The key is a
 * `ByteArray`, and `ByteArray.equals` is REFERENCE equality, so the key changes
 * whenever a new array arrives — including when it holds byte-for-byte the same
 * image.
 *
 * That happens constantly. Every inbound group definition carries the picture as
 * base64 and `Base64.decode` allocates a fresh array; `loadGroups()` does the same
 * on every read from disk. `Group.equals` compares the image by CONTENT
 * (`Group.kt:123`), so the group is correctly seen as unchanged — but the avatar's
 * key is the array, not the group, so each arrival reset the state to **null**,
 * blanked the photo for a frame or two, and re-decoded it. Opening a group chat
 * calls `requestGroupStateSync`, every member answers with a full definition, and
 * the photo blinks once per answer. That is the flicker, exactly.
 *
 * `GroupsScreen` had the same wrong key and additionally decoded SYNCHRONOUSLY
 * inside `remember`, so the same churn blocked the frame on the main thread for
 * every visible row.
 *
 * WHAT THIS DOES
 *
 * Keys on the content — `(size, contentHashCode)` — so identical bytes are one
 * key however many arrays carry them, and nothing re-decodes or resets. The
 * previous bitmap is also held across a key change until the new one is ready, so
 * changing a group photo cross-fades through the old image rather than through a
 * blank circle.
 *
 * The cache verifies `contentEquals` on a hit rather than trusting the 32-bit
 * hash: a collision would otherwise show someone else's photo, and the check
 * costs a fraction of the decode it saves.
 */
/**
 * A bounded cache keyed by the CONTENT of a byte array rather than its identity.
 *
 * Generic in the value, and a class rather than an object, so the eviction and the
 * collision check can be unit tested without an Android [Bitmap] — there is no
 * Robolectric in this module, and logic that cannot be tested is logic that drifts.
 */
class ContentKeyedCache<V>(private val maxEntries: Int = 24) {

    /** Size is part of the key so a collision needs both to match, not just the hash. */
    data class Key(val size: Int, val hash: Int)

    private class Entry<V>(val bytes: ByteArray, val value: V)

    // Access-ordered, so `get` moves an entry to the back and the one evicted is
    // genuinely the least recently used.
    private val entries = object : LinkedHashMap<Key, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry<V>>?) =
            size > maxEntries
    }

    @Synchronized
    fun peek(key: Key, bytes: ByteArray): V? {
        val hit = entries[key] ?: return null
        // Verified, not assumed — see the note above.
        return if (hit.bytes.contentEquals(bytes)) hit.value else null
    }

    @Synchronized
    fun put(key: Key, bytes: ByteArray, value: V) {
        entries[key] = Entry(bytes, value)
    }

    @Synchronized
    fun clear() = entries.clear()

    @get:Synchronized
    val size: Int get() = entries.size

    companion object {
        fun keyOf(bytes: ByteArray) = Key(bytes.size, bytes.contentHashCode())
    }
}

/** The process-wide avatar cache. Avatars are the only thing in it, and it is bounded. */
object AvatarBitmapCache {
    private val cache = ContentKeyedCache<Bitmap>()

    fun keyOf(bytes: ByteArray) = ContentKeyedCache.keyOf(bytes)
    fun peek(key: ContentKeyedCache.Key, bytes: ByteArray) = cache.peek(key, bytes)
    fun put(key: ContentKeyedCache.Key, bytes: ByteArray, bitmap: Bitmap) =
        cache.put(key, bytes, bitmap)
    fun clear() = cache.clear()
}

/**
 * The decoded form of [bytes], or null while it is being decoded or if it cannot be.
 *
 * Decoding happens on [Dispatchers.Default]: `BitmapFactory.decodeByteArray` on the
 * main thread was the first jank source in the conversation list.
 */
@Composable
fun rememberAvatarBitmap(bytes: ByteArray?): Bitmap? {
    // `remember(bytes)` on purpose: this is the one place a new array reference has
    // to be looked at, and it collapses it to a content key the rest of the
    // composable can rely on.
    val key = remember(bytes) { bytes?.let { AvatarBitmapCache.keyOf(it) } }

    // NOT keyed on the bytes or the key: that is what made the photo blink. A
    // cached hit is seeded synchronously so an avatar already decoded once never
    // shows a blank frame again.
    var bitmap by remember {
        mutableStateOf(
            if (key != null && bytes != null) AvatarBitmapCache.peek(key, bytes) else null
        )
    }

    LaunchedEffect(key) {
        if (key == null || bytes == null) {
            // The image was genuinely removed. This is the only path that blanks it.
            bitmap = null
            return@LaunchedEffect
        }
        AvatarBitmapCache.peek(key, bytes)?.let {
            bitmap = it
            return@LaunchedEffect
        }
        val decoded = withContext(Dispatchers.Default) { decodeDownsampled(bytes) }
        if (decoded != null) {
            AvatarBitmapCache.put(key, bytes, decoded)
            bitmap = decoded
        }
        // A failed decode deliberately leaves the previous bitmap in place: the
        // bytes we could not read are far more likely to be a truncated transfer
        // than a deliberate change of photo.
    }

    return bitmap
}

/**
 * Largest pixel size any avatar is drawn at, plus headroom — the same 400 px iOS
 * `GroupPictureCache` settled on (`GroupViews.swift:97-100`). The biggest avatar in
 * the Android tree is ~100 dp, which is 350 px on a 3.5x screen.
 */
private const val MAX_AVATAR_PIXELS = 400

/**
 * Decode [bytes] at no more than [MAX_AVATAR_PIXELS] on its longest side.
 *
 * NOT an optimisation — a correctness requirement, and the reason iOS does the same.
 * `compressImageData` only walks JPEG *quality* down; it never resizes. A 4032x3024
 * iPhone photo squeezed under the 500 KB wire limit is still 4032x3024, which is
 * ~48 MB once decoded. Caching a handful of those full-size would turn a flicker
 * into an out-of-memory kill. Sampled, each entry is at most ~640 KB.
 *
 * Two passes: `inJustDecodeBounds` reads the header only, then `inSampleSize` (a
 * power of two, which is all BitmapFactory honours) decodes directly at the smaller
 * size, so the full bitmap is never materialised at all.
 */
private fun decodeDownsampled(bytes: ByteArray): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)

    val opts = BitmapFactory.Options().apply { inSampleSize = avatarSampleSize(longest) }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}.getOrNull()

/**
 * The power-of-two sample factor [decodeDownsampled] picks for an image whose longest
 * side is [longest] pixels. Separated so the arithmetic is unit tested: getting it
 * wrong by one doubling is a 4x memory error that nothing else would notice.
 */
internal fun avatarSampleSize(longest: Int, maxPixels: Int = MAX_AVATAR_PIXELS): Int {
    if (longest <= maxPixels) return 1
    var sample = 1
    while (longest / (sample * 2) >= maxPixels) sample *= 2
    return sample
}
