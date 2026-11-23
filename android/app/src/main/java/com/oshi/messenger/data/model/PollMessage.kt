package com.oshi.messenger.data.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A poll in a conversation: one question, two to twelve answers, and the tally
 * of who chose what. Same in a 1:1 chat and in a group.
 *
 * Why it rides on a sentinel payload
 * ----------------------------------
 * OSHI has no server to hold a poll, so there is nowhere to "fetch results"
 * from. A poll is therefore just a message — `📊POLL📊` followed by JSON — and
 * a vote is another message, `📊POLL_VOTE📊`, that every participant applies
 * locally. Both are declared in [OSHIControlPayload.catalog], the only place
 * allowed to know a sentinel exists.
 *
 * The definition never changes after it is sent. Votes are NOT written back
 * into the poll message: they live in `PollStore`, keyed by poll id.
 *
 * Who voted is decided by the ratchet, never by the wire
 * -----------------------------------------------------
 * [PollVotePayload] carries no voter field. The voter is the sender of the
 * decrypted envelope, which is authenticated; a voter name inside the JSON
 * would let anyone vote as anyone.
 *
 * iOS: `OSHI/PollMessage.swift`. The two must stay byte-compatible — same JSON
 * keys, same Unix-seconds timestamps. The JSON is built by hand with
 * [JSONObject] rather than by Gson precisely so that the key names sit in this
 * file, next to the iOS ones, instead of in a serializer's defaults.
 */
data class PollMessage(
    val id: String = UUID.randomUUID().toString(),
    val question: String,
    val options: List<String>,
    val allowsMultipleAnswers: Boolean,
    /**
     * Anonymous means "do not show the names", nothing more. A ballot still
     * travels signed by its sender — it cannot be otherwise on a P2P mesh — so
     * this is a presentation promise, not a cryptographic one, and the composer
     * says so in as many words.
     */
    val isAnonymous: Boolean,
    /**
     * __UNIX_SECONDS_NOT_DATE_2026_10_23__ Seconds since 1970, as a Double.
     * iOS must never write a bare `Date` here: `JSONEncoder` would emit seconds
     * since 2001-01-01, and the 978 307 200 s gap is exactly the trap the
     * cross-platform message envelope already had to be fixed for.
     */
    val createdAtUnix: Double = System.currentTimeMillis() / 1000.0,
    val closesAtUnix: Double? = null,
    val creatorPublicKey: String = "",
    val version: Int = CURRENT_VERSION,
) {
    val isClosed: Boolean
        get() = closesAtUnix?.let { it <= System.currentTimeMillis() / 1000.0 } ?: false

    fun isValidOptionIndex(i: Int): Boolean = i in options.indices

    fun toMessageString(): String {
        val opts = JSONArray().apply { options.forEach { put(it) } }
        val o = JSONObject()
            .put("v", version)
            .put("id", id)
            .put("q", question)
            .put("opts", opts)
            .put("multi", allowsMultipleAnswers)
            .put("anon", isAnonymous)
            .put("at", createdAtUnix)
            .put("by", creatorPublicKey)
        closesAtUnix?.let { o.put("closes", it) }
        return OSHIControlPayload.POLL + o.toString()
    }

    companion object {
        const val CURRENT_VERSION = 1

        // Both platforms enforce the same limits, because a poll that one client
        // accepts and the other truncates shows two different results on two
        // phones.
        const val MAX_QUESTION_LENGTH = 300
        const val MAX_OPTION_LENGTH = 100
        const val MIN_OPTIONS = 2
        const val MAX_OPTIONS = 12

        fun isPollMessage(text: String): Boolean =
            OSHIControlPayload.matchedPrefix(text) == OSHIControlPayload.POLL

        /**
         * `sanitize` is what the composer calls before sending: it is the single
         * definition of "this poll is sendable", so the Send button and the wire
         * can never disagree. Returns null when it is not.
         */
        fun sanitize(question: String, options: List<String>): Pair<String, List<String>>? {
            val q = question.trim()
            if (q.isEmpty()) return null
            val opts = options.map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.take(MAX_OPTION_LENGTH) }
            if (opts.size < MIN_OPTIONS || opts.size > MAX_OPTIONS) return null
            return q.take(MAX_QUESTION_LENGTH) to opts
        }

        fun fromMessageString(text: String): PollMessage? {
            val body = OSHIControlPayload.body(text, OSHIControlPayload.POLL) ?: return null
            return try {
                val o = JSONObject(body)
                val arr = o.optJSONArray("opts") ?: return null
                val opts = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
                if (opts.size < MIN_OPTIONS) return null
                PollMessage(
                    id = o.optString("id").ifEmpty { return null },
                    question = o.optString("q"),
                    options = opts,
                    allowsMultipleAnswers = o.optBoolean("multi", false),
                    isAnonymous = o.optBoolean("anon", false),
                    createdAtUnix = o.optDouble("at", System.currentTimeMillis() / 1000.0),
                    closesAtUnix = if (o.has("closes")) o.optDouble("closes") else null,
                    creatorPublicKey = o.optString("by"),
                    version = o.optInt("v", 1),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * One participant's answer. [picks] is a set of option indexes: a single-answer
 * poll carries exactly one, a multiple-answer poll one or more. An EMPTY
 * [picks] is how a vote is retracted — it is a real ballot, not a missing one,
 * so it has to beat the earlier one on timestamp.
 */
data class PollBallot(
    val picks: List<Int>,
    val atUnix: Double = System.currentTimeMillis() / 1000.0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("picks", JSONArray().apply { picks.forEach { put(it) } })
        .put("at", atUnix)

    companion object {
        fun fromJson(o: JSONObject): PollBallot {
            val arr = o.optJSONArray("picks") ?: JSONArray()
            return PollBallot(
                picks = (0 until arr.length()).map { arr.optInt(it) },
                atUnix = o.optDouble("at", 0.0),
            )
        }
    }
}

data class PollVotePayload(
    val pollId: String,
    val picks: List<Int>,
    val atUnix: Double = System.currentTimeMillis() / 1000.0,
    val version: Int = PollMessage.CURRENT_VERSION,
) {
    val ballot: PollBallot get() = PollBallot(picks, atUnix)

    fun toMessageString(): String = OSHIControlPayload.POLL_VOTE + JSONObject()
        .put("v", version)
        .put("poll", pollId)
        .put("picks", JSONArray().apply { picks.forEach { put(it) } })
        .put("at", atUnix)
        .toString()

    companion object {
        fun isVoteMessage(text: String): Boolean =
            OSHIControlPayload.matchedPrefix(text) == OSHIControlPayload.POLL_VOTE

        fun fromMessageString(text: String): PollVotePayload? {
            val body = OSHIControlPayload.body(text, OSHIControlPayload.POLL_VOTE) ?: return null
            return try {
                val o = JSONObject(body)
                val id = o.optString("poll")
                if (id.isEmpty()) return null
                val arr = o.optJSONArray("picks") ?: JSONArray()
                PollVotePayload(
                    pollId = id,
                    picks = (0 until arr.length()).map { arr.optInt(it) },
                    atUnix = o.optDouble("at", System.currentTimeMillis() / 1000.0),
                    version = o.optInt("v", 1),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * What the bubble draws.
 *
 * [voterCount] is the number of distinct people with a non-empty ballot. In a
 * multiple-answer poll it is LESS than the sum of [counts], which is why the
 * footer says "N people" and the bars are drawn against this number.
 */
data class PollResults(
    val counts: List<Int> = emptyList(),
    val voterCount: Int = 0,
    /**
     * Public keys per option, in ballot order. Empty for an anonymous poll: the
     * names are not withheld at draw time, they are never collected, so a
     * screenshot of the view cannot leak them.
     */
    val votersByOption: List<List<String>> = emptyList(),
    val myPicks: Set<Int> = emptySet(),
) {
    fun share(index: Int): Float =
        if (voterCount > 0 && index in counts.indices) counts[index].toFloat() / voterCount else 0f

    /**
     * Rounded for display, and never 0 % for an option that really has a voter:
     * a bar with a body and "0 %" written on it reads as a bug.
     */
    fun percent(index: Int): Int {
        val raw = share(index) * 100f
        if (raw > 0f && raw < 1f) return 1
        return Math.round(raw)
    }
}
