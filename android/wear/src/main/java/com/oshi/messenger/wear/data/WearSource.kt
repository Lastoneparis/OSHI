package com.oshi.messenger.wear.data

import com.oshi.wearbridge.WearConvKind
import com.oshi.wearbridge.WearConversation
import com.oshi.wearbridge.WearMessage
import com.oshi.wearbridge.WearStatus

/** Why the watch has nothing to show; each maps to one screen of copy. */
enum class Reason { DISABLED, LOCKED, UNAVAILABLE, NO_PHONE, NOT_FOUND, READ_ONLY, INVALID, ERROR;

    companion object {
        /** A non-OK phone answer, as the watch explains it. */
        fun of(status: WearStatus): Reason = when (status) {
            WearStatus.OK -> error("OK is not a failure")
            WearStatus.DISABLED -> DISABLED
            WearStatus.LOCKED -> LOCKED
            WearStatus.UNAVAILABLE -> UNAVAILABLE
            WearStatus.NOT_FOUND -> NOT_FOUND
            WearStatus.READ_ONLY -> READ_ONLY
            WearStatus.INVALID -> INVALID
            WearStatus.ERROR -> ERROR
        }
    }
}

sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Fail(val reason: Reason) : Outcome<Nothing>
}

data class WearThread(val title: String, val messages: List<WearMessage>)

/**
 * Where the watch gets its conversations. The real one ([PhoneWearSource])
 * asks the phone; a debug build can swap in sample data (src/debug).
 * Implementations keep NOTHING on disk.
 */
interface WearSource {
    suspend fun conversations(): Outcome<List<WearConversation>>
    suspend fun thread(kind: WearConvKind, id: String): Outcome<WearThread>
    suspend fun send(kind: WearConvKind, id: String, text: String): Outcome<Unit>
    suspend fun react(kind: WearConvKind, id: String, messageId: String, emoji: String): Outcome<Unit>
    fun close() {}
}
