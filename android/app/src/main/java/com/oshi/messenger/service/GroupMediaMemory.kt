package com.oshi.messenger.service

import com.oshi.messenger.data.model.MediaType

/**
 * __GROUP_MEDIA_MEMORY_2026_11_11__ Which group attachments stay ON THE HEAP.
 *
 * Every group's last 300 messages live in one in-memory map (`GroupManager._groupMessages`)
 * and `loadMessages()` used to read EVERY stored attachment back into it at launch, before
 * any group was opened. Measured on release 1.6.93 (192 MB heap emulator): a group holding
 * a 26 MB and a 60 MB document ⇒ Java heap 97 MB right after a cold start. Two or three
 * more and the app cannot open at all.
 *
 * Large documents and videos now stay on disk (the file store, keyed by message id) and
 * are read when the user taps them. Photos, voice notes, contact cards and round video
 * notes (≤ 15 s, rendered inline from the bytes) keep their bytes — they are small and the
 * bubble draws from them.
 */
object GroupMediaMemory {
    const val MAX_IN_MEMORY_BYTES: Long = 2L * 1024 * 1024

    fun keepInMemory(type: MediaType?, isVideoNote: Boolean, size: Long): Boolean {
        if (size <= MAX_IN_MEMORY_BYTES) return true
        if (isVideoNote) return true
        return type != MediaType.DOCUMENT && type != MediaType.VIDEO
    }
}
