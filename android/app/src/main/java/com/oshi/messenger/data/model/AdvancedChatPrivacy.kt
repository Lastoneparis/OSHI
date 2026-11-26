package com.oshi.messenger.data.model

import org.json.JSONObject

/**
 * __ADV_CHAT_PRIVACY_2026_11_26__ "Advanced chat privacy" (WhatsApp's name for
 * it), per conversation — one contact, or one group.
 *
 * When ANY participant turns it on, for everyone in that conversation:
 *
 *  1. media from this chat is not saved to the gallery / Downloads (OSHI has no
 *     automatic save, so this is the manual "Save" on media);
 *  2. the conversation is left out of the message export;
 *  3. on-device AI (smart replies, the writing assistant's conversation
 *     context, translation) does not read its messages;
 *  4. the chat screen is FLAG_SECURE whatever the global screenshot setting
 *     says — OSHI goes beyond WhatsApp here.
 *
 * Forwarding stays allowed, as on WhatsApp.
 *
 * ## What this can and cannot promise
 *
 * Like [OfflineOnlyRequest], it is enforced locally and REQUESTED of the others.
 * A peer on a build that does not know the sentinel keeps every capability, and
 * nothing can stop a camera pointed at a screen. The explanatory sheet says so.
 *
 * ## Wire (shared with iOS — do not change)
 *
 * `🛡ADV_PRIVACY🛡{"v":1,"on":true,"at":<unix seconds>}`
 *
 * `at` is written as an INTEGER: a Swift `Int` field refuses a fractional JSON
 * number, while a `Double` field accepts an integer, so the integer is the one
 * spelling both decoders read. On input any number is accepted.
 *
 * Last-writer-wins by `at`, with the peer's clock clamped to our now + 300 s
 * exactly as [OfflineOnlyRequest.clampedAt] does.
 *
 * iOS: `OSHI/AdvancedChatPrivacy.swift` (parallel implementation).
 */
data class AdvancedChatPrivacyChange(
    val version: Int = CURRENT_VERSION,
    val on: Boolean,
    val atUnixSeconds: Long,
) {
    fun toJson(): String = JSONObject()
        .put("v", version)
        .put("on", on)
        .put("at", atUnixSeconds)
        .toString()

    fun toMessageString(): String = OSHIControlPayload.ADV_PRIVACY_WIRE + toJson()

    companion object {
        const val CURRENT_VERSION = 1

        /** How far ahead of our clock a peer's `at` may be (same bound as offline-only). */
        const val MAX_FUTURE_SKEW_SECONDS = 300L

        fun create(on: Boolean, atMillis: Long = System.currentTimeMillis()) =
            AdvancedChatPrivacyChange(on = on, atUnixSeconds = atMillis / 1000L)

        /**
         * The peer's `at`, bounded by our clock. Unbounded, a peer could send
         * `on:false, at:<year 3000>` and no later change — anyone's — would ever
         * be newer: the protection could never be turned back on.
         */
        fun clampedAt(atUnixSeconds: Long, nowUnixSeconds: Long): Long =
            minOf(atUnixSeconds, nowUnixSeconds + MAX_FUTURE_SKEW_SECONDS)

        /** Does this text carry the sentinel at all (either spelling)? */
        fun isPayload(text: String): Boolean =
            OSHIControlPayload.body(text, OSHIControlPayload.ADV_PRIVACY) != null

        fun fromMessageString(text: String): AdvancedChatPrivacyChange? {
            val body = OSHIControlPayload.body(text, OSHIControlPayload.ADV_PRIVACY) ?: return null
            return try {
                val o = JSONObject(body)
                val v = o.optInt("v", 0)
                if (v <= 0 || v > CURRENT_VERSION) return null
                // The restrictive reading for an unreadable flag: a corrupt payload
                // must not read as "protection lifted".
                val on = o.optBoolean("on", true)
                val at = o.optDouble("at", Double.NaN)
                if (at.isNaN() || at.isInfinite()) return null
                AdvancedChatPrivacyChange(version = v, on = on, atUnixSeconds = at.toLong())
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Every decision the feature makes, with no Android dependency, so each one is a
 * plain JVM test.
 */
object AdvancedChatPrivacyPolicy {

    /** May media from this conversation be written to the gallery / Downloads? */
    fun mediaSaveAllowed(privacyOn: Boolean, isFromMe: Boolean = false): Boolean =
        !privacyOn || isFromMe

    /** May this conversation go into a message export? */
    fun exportAllowed(privacyOn: Boolean): Boolean = !privacyOn

    /** May on-device AI read this conversation (smart replies, context, translation)? */
    fun aiAllowed(privacyOn: Boolean): Boolean = !privacyOn

    /**
     * The messages the AI context builder may see: none at all when the
     * conversation is protected; otherwise the [readable] ones, unchanged.
     */
    fun <T> aiReadable(privacyOn: Boolean, readable: List<T>): List<T> =
        if (aiAllowed(privacyOn)) readable else emptyList()

    /** Must the chat window be FLAG_SECURE regardless of the global setting? */
    fun forcesSecureWindow(privacyOn: Boolean): Boolean = privacyOn

    /** iOS `RatchetHealSignal.Caps.advancedPrivacy`; Android `GroupLegacyCompat.CAP_ADVANCED_PRIVACY`. */
    const val CAP_ADVANCED_PRIVACY: Int = 1 shl 4

    /**
     * Does a peer read `🛡ADV_PRIVACY🛡`? Its bundle says so, or it sent us one.
     * Unknown caps (`null`) mean no.
     */
    fun peerReads(accountCaps: Int?, sentUsOne: Boolean): Boolean =
        sentUsOne || (accountCaps != null && (accountCaps and CAP_ADVANCED_PRIVACY) != 0)

    /** The members a group payload may go to; the rest are "on an older version". */
    fun readers(members: Collection<String>, reads: (String) -> Boolean): List<String> =
        members.filter(reads)

    /** Should Android's system-generated replies / actions be offered on a banner from this chat? */
    fun systemGeneratedRepliesAllowed(privacyOn: Boolean): Boolean = !privacyOn
}
