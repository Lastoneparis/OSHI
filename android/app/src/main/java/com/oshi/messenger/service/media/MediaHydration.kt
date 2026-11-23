package com.oshi.messenger.service.media

import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.Message

/**
 * Re-attach file-backed blobs to a page of messages, newest-first, under iOS's
 * decrypted-media cache bounds (`MediaAttachmentCache.swift:37-38`: 60 items / 96 MB).
 *
 * Moved out of `MessageRepository.hydrateMedia` unchanged (__CHAT_OPEN_PERF_2026_11_14__)
 * so the cost of the open path can be measured off-device. Since that change the 1:1
 * THREAD no longer goes through here at all — it reads light rows and resolves media
 * per visible bubble. What still uses it (the media gallery) wants every item's bytes.
 *
 * Blocking: reads and decrypts files. Never call on the main thread.
 */
object MediaHydration {
    fun hydrate(messages: List<Message>, store: MediaAttachmentStore): List<Message> {
        var count = 0
        var cost = 0L
        val limitCount = MediaAttachmentStore.CACHE_COUNT_LIMIT
        val limitCost = MediaAttachmentStore.CACHE_TOTAL_COST_LIMIT
        // `getMessagesForContact` is ORDER BY timestamp DESC, so index 0 is newest.
        return messages.map { m ->
            if (count >= limitCount) return@map m          // budget spent — no stat call
            if (m.mediaData != null || m.mediaType == null || m.isDeletedForEveryone) return@map m
            // __DOC_STREAM_2026_11_11__ A document bubble draws a name and an icon; its
            // bytes are only needed when the user saves it, and that streams from the
            // store (`copyMediaTo`). Hydrating them put a 60 MB file on the heap for as
            // long as the chat was open, and the next 26 MB send died of OOM (release
            // 1.6.93, 192 MB heap).
            if (m.mediaType == MediaType.DOCUMENT) return@map m
            val size = store.sizeOf(m.id)
            if (size < 0) return@map m                     // nothing stored for this row
            if (cost + size > limitCost) return@map m
            val bytes = store.load(m.id) ?: return@map m
            count++
            cost += bytes.size.toLong()
            m.copy(mediaData = bytes)
        }
    }
}
