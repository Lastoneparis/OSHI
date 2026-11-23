package com.oshi.messenger.network.v2

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The ratchet-heal acknowledgement, byte-compatible with iOS `RatchetHealSignal.swift`.
 *
 * WHY THIS EXISTS ON ANDROID
 * After 8 failed decrypts of the same envelope the router gives up on it. Until now
 * that was ALL it did: the session it could not open stayed in the store, the peer
 * kept writing under it, and every later message produced a notification and was
 * thrown away. The user-visible form is "I get the notification but not the
 * message", and on Android it was permanent — iOS had shipped the cure in 1.0.40
 * and Android neither sent it nor understood it. Worse, an iPhone's acknowledgement
 * decrypted fine (it carries an X3DH header, which [V2MessageRouter.decryptInbound]
 * already adopts) and then landed in the chat as a literal `__RATCHET_HEAL__v1`
 * bubble, with a notification.
 *
 * HOW IT WORKS (Signal's PreKeySignalMessage, with the parts OSHI already has)
 * The receiver — the only side that KNOWS the session is bad — deletes its own
 * session and sends this marker as an ordinary text. Because it has no session any
 * more, [V2MessageRouter.sendText] re-runs X3DH and the envelope carries the header;
 * the peer adopts the fresh session while decrypting it. The acknowledgement is not
 * a request for repair, it IS the repair, and it rides the message channel: if
 * messages get through, so does the cure.
 *
 * TWO MARKS, ONE PER READER (iOS keeps the same two)
 *  • [PAYLOAD_MARKER] — ENCRYPTED. What the app reads so no bubble is created.
 *  • [MSG_ID_PREFIX]  — CLEARTEXT, on the envelope id. What a notification
 *    extension can read without decrypting, so it stays quiet.
 *
 * WIRE GRAMMAR (v2, first line byte-identical to v1)
 *    __RATCHET_HEAL__v1                       ← nothing to re-request (the common case)
 *    __RATCHET_HEAL__v1\n{"v":2,"resend":[…]} ← plus the ids the sender could not read
 * A v1 reader recognises the bare form by EQUALITY, so `encode(emptyList())` must
 * return exactly the marker. The newline after the marker is mandatory: a message
 * that merely STARTS with the marker text is prose, not a signal — otherwise typing
 * the marker would make a message vanish on the other phone.
 *
 * __RESEND_LIST_ANDROID_2026_10_12__ Android ANNOUNCES the list form now, in both
 * places iOS looks: [makeMsgId] emits [CAPABLE_MSG_ID_PREFIX], and the prekey bundle
 * carries `GroupLegacyCompat.CAP_RESEND_LIST`. Until it did, `MessageManager+V2.swift:2364`
 * (`let resend = capable ? drained : []`) WITHHELD the list from every Android peer —
 * so an Android<->iPhone pair had no recovery at all in either direction, and an
 * Android<->Android pair had none either (nobody asked, nobody served). The user-visible
 * form is the one reported twice: "I get every notification but the message often never
 * appears."
 */
object RatchetHealSignal {

    /** Encrypted content of the acknowledgement. Never displayed. */
    const val PAYLOAD_MARKER = "__RATCHET_HEAL__v1"

    /** Cleartext mark on the envelope id — all a notification extension can see. */
    const val MSG_ID_PREFIX = "heal-"

    /** iOS ids carrying this prefix announce that the SENDER can read a resend list. */
    const val CAPABLE_MSG_ID_PREFIX = MSG_ID_PREFIX + "v2-"

    /** What an acknowledgement asks for. An empty list is the normal form. */
    data class Heal(val resend: List<String>)

    /** iOS `Policy.maxResendPerRequest`. */
    const val MAX_RESEND_PER_REQUEST = 32

    /**
     * __RESEND_LIST_ANDROID_2026_10_12__ The CAPABLE spelling, so a peer that receives this
     * acknowledgement learns we can read a resend list (iOS `markPeerUnderstandsResendList`,
     * MessageManager+V2.swift:1947). It still starts with [MSG_ID_PREFIX], so every v1
     * reader — including an iPhone's notification extension — keeps recognising it as a heal.
     */
    fun makeMsgId(): String = CAPABLE_MSG_ID_PREFIX + UUID.randomUUID().toString().uppercase()

    fun isHealMsgId(msgId: String): Boolean = msgId.startsWith(MSG_ID_PREFIX)

    fun announcesListSupport(msgId: String): Boolean = msgId.startsWith(CAPABLE_MSG_ID_PREFIX)

    /**
     * Visee size for EVERY enriched payload. iOS `padTargetBytes` — same number, and it
     * has to be: the two builds must produce indistinguishable envelopes.
     */
    const val PAD_TARGET_BYTES = 1500

    /**
     * Bare marker when there is nothing to re-request; otherwise marker + newline + JSON,
     * PADDED to a fixed size.
     *
     * __HEAL_LENGTH_LEAK_2026_08_28__ (iOS `RatchetHealSignal.encode`) AES-GCM adds no
     * padding of its own: |ciphertext| = |plaintext| + 16. The envelope is identifiable in
     * cleartext by its `heal-` id, so a relay that merely MEASURES the ciphertext learns
     * how many messages a correspondent could not read — the size of the outage and the
     * volume exchanged during it. JSON has no comment syntax, so the padding is a field the
     * decoder ignores ([parse] keeps only `resend`).
     */
    fun encode(resend: List<String> = emptyList()): String {
        val ids = resend.take(MAX_RESEND_PER_REQUEST)
        if (ids.isEmpty()) return PAYLOAD_MARKER
        val body = JSONObject().put("v", 2).put("resend", JSONArray(ids))
        val out = PAYLOAD_MARKER + "\n" + body.toString()
        val size = out.toByteArray(Charsets.UTF_8).size
        if (size >= PAD_TARGET_BYTES) return out
        // -12 for `,"p":"…"}` and the `}` being replaced — iOS's arithmetic, so both
        // platforms land on the same byte count.
        val room = PAD_TARGET_BYTES - size - 12
        if (room <= 0) return out
        return out.dropLast(1) + ",\"p\":\"" + "0".repeat(room) + "\"}"
    }

    /**
     * The acknowledgement carried by [text], or null when [text] is ordinary content.
     * Tolerates surrounding whitespace (iOS trims too) and unknown fields in the JSON
     * body, so a future version amputated of `resend` still reads as a heal.
     */
    fun parse(text: String): Heal? {
        val trimmed = text.trim()
        if (trimmed == PAYLOAD_MARKER) return Heal(emptyList())
        if (!trimmed.startsWith(PAYLOAD_MARKER + "\n")) return null
        val rest = trimmed.substring(PAYLOAD_MARKER.length + 1)
        return try {
            val body = JSONObject(rest)
            val arr = body.optJSONArray("resend")
            val ids = ArrayList<String>()
            if (arr != null) for (i in 0 until arr.length()) {
                val id = arr.optString(i); if (id.isNotEmpty()) ids.add(id)
            }
            Heal(ids.take(MAX_RESEND_PER_REQUEST))
        } catch (_: Exception) {
            null
        }
    }

    fun isHealPayload(text: String): Boolean = parse(text) != null

    /**
     * A message the peer re-sent at our request, wrapped so it is delivered under
     * its ORIGINAL id (a message asked for twice must not become two bubbles).
     * iOS `RatchetHealSignal.Resent`: `__RATCHET_RESENT__v1\n{"id":…,"b":…}`.
     */
    object Resent {
        const val MARKER = "__RATCHET_RESENT__v1"

        fun encode(originalId: String, body: String): String =
            MARKER + "\n" + JSONObject().put("id", originalId).put("b", body).toString()

        /** (originalId, body), or null when [text] is not a resend wrapper. */
        fun parse(text: String): Pair<String, String>? {
            if (!text.startsWith(MARKER + "\n")) return null
            return try {
                val o = JSONObject(text.substring(MARKER.length + 1))
                val id = o.optString("id"); val b = o.optString("b")
                if (id.isEmpty()) null else id to b
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Pure decisions, identical to iOS `RatchetHealSignal.Policy`. */
    object Policy {
        /** Minimum spacing between two acknowledgements to the same peer. */
        const val COOLDOWN_MS = 60_000L

        fun maySend(lastSentAtMs: Long?, nowMs: Long): Boolean =
            lastSentAtMs == null || nowMs - lastSentAtMs >= COOLDOWN_MS

        /**
         * Symmetric tie-break. Two devices that both see a broken batch in the same
         * second would each delete its session and each send a header — a symmetric
         * loop the cooldown only repeats. Exactly one side of a pair acts at once;
         * the other waits a full cooldown (adopting headers all the while).
         */
        fun isHealInitiator(me: String, peer: String): Boolean = me < peer

        // --- Renvoi des messages perdus (iOS `Policy`, same numbers) ---------------
        //
        // The cycle to break is: resend -> failure -> give up -> deleteSession ->
        // acknowledgement -> resend of the SAME ids. [COOLDOWN_MS] does not END that
        // cycle, it only spaces it out; only a per-msgId counter terminates it.

        /** Past this, re-asking is pointless: it was read elsewhere or nobody cares. */
        const val MAX_RESEND_AGE_MS = 7L * 24 * 3600 * 1000

        /** THE brake that ends the cycle. 2, not 1: the first resend may legitimately
         *  be lost with the very session we just rebuilt. */
        const val MAX_RESEND_ATTEMPTS_PER_MSG_ID = 2

        /** Rate ceiling per peer, independent of the above: it bounds what a chatty
         *  peer can make us produce even with ids that are all different. */
        const val MAX_RESENDS_PER_PEER_PER_HOUR = 64

        /**
         * @param attemptsSoFar resends ALREADY made for this msgId.
         * @param messageAgeMs age of the locally stored message.
         * @param peerBudgetLeft resends left towards this peer within the hour.
         */
        fun mayResend(attemptsSoFar: Int, messageAgeMs: Long, peerBudgetLeft: Int): Boolean =
            attemptsSoFar < MAX_RESEND_ATTEMPTS_PER_MSG_ID &&
                messageAgeMs <= MAX_RESEND_AGE_MS &&
                peerBudgetLeft > 0
    }
}
