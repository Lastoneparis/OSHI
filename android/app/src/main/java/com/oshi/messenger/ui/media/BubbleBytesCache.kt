package com.oshi.messenger.ui.media

/**
 * __CHAT_OPEN_PERF_2026_11_14__ Bytes the 1:1 thread already resolved for a bubble,
 * so a bubble scrolled out and back in draws at once instead of passing through a
 * "loading" frame and a new read.
 *
 * Why it exists: the thread now reads LIGHT rows (`MessageDao.getThreadRowsForContact`)
 * and each media bubble resolves its own bytes when composed. `LazyColumn` disposes a
 * row's `remember` when it leaves the screen, so without this every scroll back over a
 * photo was a database read (inline bytes) or an LRU lookup (file-backed) plus a
 * placeholder flash.
 *
 * Bounded on purpose — the 1.6.94 OOM work is the reason the thread stopped holding
 * every attachment: at most [MAX_ENTRIES] items / [MAX_TOTAL_BYTES], and nothing larger
 * than [MAX_ENTRY_BYTES] is kept (a big video is streamed/played from the store, and
 * `MediaAttachmentStore` keeps its own LRU). File-backed bytes usually ARE the store's
 * cached array, so caching them here shares the reference and costs no extra heap.
 * The old eager hydration held up to 96 MB of file-backed media plus every inline
 * attachment of the conversation; this holds at most 24 MB.
 *
 * Attachment bytes never change for a given message id, so entries need no
 * invalidation; [evict] exists for delete paths and tests.
 */
object BubbleBytesCache {
    const val MAX_ENTRIES = 120
    const val MAX_TOTAL_BYTES = 24L * 1024 * 1024
    const val MAX_ENTRY_BYTES = 6L * 1024 * 1024

    private val map = LinkedHashMap<String, ByteArray>(32, 0.75f, true)
    private var total = 0L

    @Synchronized
    fun get(messageId: String): ByteArray? = map[messageId]

    @Synchronized
    fun put(messageId: String, bytes: ByteArray) {
        if (bytes.size > MAX_ENTRY_BYTES) return
        map.put(messageId, bytes)?.let { total -= it.size }
        total += bytes.size
        val it = map.entries.iterator()
        while ((map.size > MAX_ENTRIES || total > MAX_TOTAL_BYTES) && it.hasNext()) {
            val eldest = it.next()
            if (eldest.key == messageId) continue
            total -= eldest.value.size
            it.remove()
        }
    }

    @Synchronized
    fun evict(messageId: String) {
        map.remove(messageId)?.let { total -= it.size }
    }

    @Synchronized
    fun clear() {
        map.clear()
        total = 0
    }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun totalBytes(): Long = total
}
