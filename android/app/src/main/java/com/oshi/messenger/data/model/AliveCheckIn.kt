package com.oshi.messenger.data.model

import org.json.JSONObject

/**
 * __ALIVE_CHECK_IN_2026_11_04__ "I'm alive." A recurring, per-conversation
 * liveness confirmation.
 *
 * ## What this is, and what it is NOT
 *
 * This is **not** the existing check-in ([CheckInMessage], `🛡️CHECK_IN🛡️`),
 * which accompanies ONE journey: an ETA, a route, a location, and an emergency
 * payload if the ETA passes. That feature answers "did she get home?".
 *
 * This one answers a different question, repeatedly and with no location at
 * all: "is he still answering?". A person commits to confirming every N hours;
 * the other side sees a countdown, and learns when the deadline passes
 * unanswered. Sharing nothing but a timestamp is the whole design — a liveness
 * signal that also broadcast a position would be a tracker, and the people who
 * need this most are the ones who can least afford to be tracked.
 *
 * The two features deliberately keep separate sentinels. Reusing `🛡️CHECK_IN🛡️`
 * would have put these events through `CheckInMessage`'s seven-type renderer,
 * where they would print as "Check-in started" and carry a location field no
 * one filled in.
 *
 * ## It is a pair of one-way commitments, not a shared state
 *
 * Each side owns its own schedule. My interval and my last confirmation are
 * mine to set and mine to announce; yours are yours. So a conversation holds
 * TWO independent states, and the local store keeps them apart
 * ([com.oshi.messenger.service.AliveCheckInStore.Entry]). A single shared
 * "interval for this conversation" would mean one person could set a deadline
 * the other has to meet, which is not a safety feature, it is a leash.
 *
 * ## Wire
 *
 * `💓ALIVE💓{"v":1,"t":"ok","at":1762300123.456,"iv":86400}` — declared once in
 * [OSHIControlPayload.catalog].
 *
 * `at` is Unix **seconds**, fractional, for the same reason
 * [OfflineOnlyRequest] uses seconds: this JSON crosses to a platform whose
 * native `Date` is seconds-based. The fraction is not decoration — it carries
 * the milliseconds that make two events in the same second distinguishable,
 * which is what [AliveCheckInPolicy.accepts] needs to drop a duplicate without
 * dropping a real second event.
 *
 * `iv` is the sender's interval in seconds, restated on EVERY event rather than
 * only when it changes. A peer that was offline when the schedule was set would
 * otherwise show a confirmation with no deadline attached to it, which is the
 * one display state that reassures without meaning anything.
 *
 * iOS: `OSHI/AliveCheckIn.swift`.
 */
data class AliveCheckInEvent(
    val version: Int = CURRENT_VERSION,
    val kind: Kind,
    /** Unix seconds, fractional. The SENDER's clock — see [AliveCheckInPolicy.confirmedAt]. */
    val atUnixSeconds: Double,
    /**
     * The sender's own interval, in seconds. Zero on [Kind.DISABLED], where
     * there is no longer a deadline to describe.
     */
    val intervalSeconds: Long,
) {
    enum class Kind(val wire: String) {
        /** Starting (or re-timing) a schedule. Counts as a confirmation too. */
        ENABLED("on"),

        /** "I'm alive", at [atUnixSeconds]. */
        CONFIRMED("ok"),

        /** Stopping. The other side goes back to showing nothing. */
        DISABLED("off");

        companion object {
            fun fromWire(raw: String): Kind? = entries.firstOrNull { it.wire == raw }
        }
    }

    /** True when this event means "I was alive at [atUnixSeconds]". */
    val isConfirmation: Boolean
        get() = kind == Kind.ENABLED || kind == Kind.CONFIRMED

    fun toJson(): String = JSONObject()
        .put("v", version)
        .put("t", kind.wire)
        .put("at", atUnixSeconds)
        .put("iv", intervalSeconds)
        .toString()

    fun toMessageString(): String = OSHIControlPayload.ALIVE_CHECK_IN + toJson()

    companion object {
        const val CURRENT_VERSION = 1

        fun create(
            kind: Kind,
            intervalSeconds: Long,
            atMillis: Long = System.currentTimeMillis(),
        ) = AliveCheckInEvent(
            kind = kind,
            atUnixSeconds = atMillis / 1000.0,
            intervalSeconds = if (kind == Kind.DISABLED) 0L else intervalSeconds,
        )

        /** Does this text carry the alive-check-in sentinel at all? */
        fun isEvent(text: String): Boolean =
            OSHIControlPayload.body(text, OSHIControlPayload.ALIVE_CHECK_IN) != null

        fun fromMessageString(text: String): AliveCheckInEvent? {
            val body = OSHIControlPayload.body(text, OSHIControlPayload.ALIVE_CHECK_IN) ?: return null
            return try {
                val o = JSONObject(body)
                val v = o.optInt("v", 0)
                if (v <= 0 || v > CURRENT_VERSION) return null
                val kind = Kind.fromWire(o.optString("t")) ?: return null
                val at = o.optDouble("at", 0.0)
                // A payload with no timestamp cannot be placed in time at all, and
                // "now" would be a fabrication that reads as a fresh confirmation.
                if (at <= 0.0) return null
                AliveCheckInEvent(
                    version = v,
                    kind = kind,
                    atUnixSeconds = at,
                    intervalSeconds = o.optLong("iv", 0L).coerceAtLeast(0L),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * __ALIVE_CHECK_IN_2026_11_04__ The intervals offered in the UI.
 *
 * The three presets are the ones the feature is actually used for; `CUSTOM`
 * exists because "every 8 hours" is a real answer for someone who checks in at
 * the start and end of a shift, and a fixed list would tell them the app does
 * not do what they came for.
 */
object AliveCheckInInterval {
    const val HOUR_SECONDS = 3_600L
    const val DAY_SECONDS = 24 * HOUR_SECONDS

    const val EVERY_24H = DAY_SECONDS
    const val EVERY_48H = 2 * DAY_SECONDS
    const val EVERY_72H = 3 * DAY_SECONDS

    val presets: List<Long> = listOf(EVERY_24H, EVERY_48H, EVERY_72H)

    val DEFAULT = EVERY_24H

    /**
     * The narrowest and widest a custom interval may be.
     *
     * The floor is an hour: anything shorter turns a safety feature into a
     * notification every few minutes, and the first thing a user does with that
     * is mute the channel the overdue alert travels on. The ceiling is thirty
     * days, past which "overdue" stops describing anything.
     */
    const val MIN_SECONDS = HOUR_SECONDS
    const val MAX_SECONDS = 30 * DAY_SECONDS

    fun clamp(seconds: Long): Long = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    fun isPreset(seconds: Long): Boolean = seconds in presets
}

/**
 * __ALIVE_CHECK_IN_2026_11_04__ Every decision this feature makes, with no
 * Android, no store and no clock of its own.
 *
 * Pure on purpose, and for a reason stronger than tidiness: the three things
 * that can go wrong here — a deadline computed in the wrong time zone, a
 * duplicate event counted twice, a peer's broken clock reading as a fresh
 * confirmation — are all invisible on a screen and all trivial to state as a
 * test. iOS mirrors this file as `AliveCheckInPolicy` in Swift.
 */
object AliveCheckInPolicy {

    /** What a conversation shows for one direction of the check-in. */
    enum class Status {
        /** Nobody configured anything. Draw nothing. */
        NEUTRAL,

        /** Confirmed, and the next deadline is still ahead. Green. */
        CONFIRMED,

        /** The deadline passed with no confirmation. Red — and see [OVERDUE_IS_NOT_PROOF]. */
        OVERDUE,
    }

    /**
     * The sentence this whole feature has to keep saying.
     *
     * A missed check-in means a notification did not arrive. That is all it can
     * ever mean: the phone may be off, out of coverage, out of battery, on a
     * plane, or simply in a pocket. Every string this feature shows is written
     * to say "no response" and never "in danger", and this constant exists so a
     * future caller looking for the right words finds the rule instead of
     * inventing them.
     */
    const val OVERDUE_IS_NOT_PROOF = "no_response_not_proof_of_harm"

    /**
     * When the next confirmation is expected, in epoch millis.
     *
     * Absolute instants, both sides, which is the entire answer to time zones
     * and daylight saving: a deadline is an instant on the shared timeline, and
     * no local calendar is consulted to compute or compare one. A traveller who
     * crosses six zones between two check-ins sees the same countdown as the
     * person watching them.
     */
    fun dueAtMillis(lastConfirmedAtMillis: Long, intervalSeconds: Long): Long =
        lastConfirmedAtMillis + intervalSeconds * 1_000L

    fun status(
        enabled: Boolean,
        lastConfirmedAtMillis: Long,
        intervalSeconds: Long,
        nowMillis: Long,
    ): Status {
        if (!enabled || lastConfirmedAtMillis <= 0L || intervalSeconds <= 0L) return Status.NEUTRAL
        return if (nowMillis >= dueAtMillis(lastConfirmedAtMillis, intervalSeconds)) {
            Status.OVERDUE
        } else {
            Status.CONFIRMED
        }
    }

    /**
     * How much is left before the deadline (positive) or how long it has been
     * past (negative), in millis.
     */
    fun remainingMillis(
        lastConfirmedAtMillis: Long,
        intervalSeconds: Long,
        nowMillis: Long,
    ): Long = dueAtMillis(lastConfirmedAtMillis, intervalSeconds) - nowMillis

    /**
     * The instant a received confirmation actually vouches for.
     *
     * The timestamp comes off another device's clock, so it cannot be used raw.
     * Two failures matter, and they pull in opposite directions:
     *
     *  * **A clock ahead of ours.** `at` lands in the future, the deadline moves
     *    with it, and the conversation stays green past the point where it
     *    should have gone red. That is the dangerous direction — a safety
     *    indicator that reassures on the strength of a wrong clock. Clamped to
     *    our receive time: the message did arrive, now, and that is the latest
     *    moment it can honestly vouch for.
     *
     *  * **A clock far behind ours.** `at` lands days back and the peer reads as
     *    overdue the instant they check in. That direction is merely wrong, not
     *    dangerous, but it is wrong in a way that cries wolf, so a timestamp
     *    further back than [MAX_BACKDATE_MILLIS] is treated as telling us
     *    nothing about time and replaced by the receive instant too.
     *
     * Between those two limits the declared instant is honoured as it stands,
     * which is what makes an offline queue work: a confirmation that waited six
     * hours in a relay queue is still a confirmation from six hours ago, and
     * dating it to delivery would hide exactly the lateness this feature exists
     * to show.
     */
    const val MAX_BACKDATE_MILLIS = 30L * 24 * 3_600 * 1_000

    fun confirmedAt(declaredAtMillis: Long, receivedAtMillis: Long): Long = when {
        declaredAtMillis <= 0L -> receivedAtMillis
        declaredAtMillis > receivedAtMillis -> receivedAtMillis
        receivedAtMillis - declaredAtMillis > MAX_BACKDATE_MILLIS -> receivedAtMillis
        else -> declaredAtMillis
    }

    /**
     * Should this event be applied, given the newest one already applied from
     * the same peer?
     *
     * Duplicates are the normal case, not the exception: a single send can
     * travel the Android mesh, the cross-platform mesh and the relay, and the
     * receiver takes whichever arrives — then the others arrive too. Mesh
     * delivery is also unordered, so an older event can land after a newer one.
     *
     * Strictly-greater, so a replay of the event we already hold changes
     * nothing, and an out-of-order older one cannot pull a confirmation
     * backwards and manufacture an overdue state.
     */
    fun accepts(eventAtUnixSeconds: Double, lastAppliedAtUnixSeconds: Double): Boolean =
        eventAtUnixSeconds > lastAppliedAtUnixSeconds

    /**
     * Should the overdue notification fire for this deadline?
     *
     * One notification per deadline, tracked by the deadline's own instant
     * rather than by a boolean. A boolean has to be cleared on recovery, and a
     * missed clear means either a second banner for the same silence or no
     * banner at all for the next one. The instant answers both: a deadline we
     * have already announced is `<=` the one recorded, whatever happened since.
     */
    fun shouldNotifyOverdue(dueAtMillis: Long, alreadyNotifiedForDueAtMillis: Long): Boolean =
        dueAtMillis > alreadyNotifiedForDueAtMillis

    /**
     * Was this confirmation a recovery — someone answering after their deadline
     * had already passed?
     *
     * Worth its own notification, and worth it in the plain direction: the
     * person who was told "no response from X" is owed the sentence that closes
     * it. Without this the only signal that the silence ended is a green dot
     * nobody is looking at.
     */
    fun isRecovery(
        previousStatus: Status,
        newStatus: Status,
    ): Boolean = previousStatus == Status.OVERDUE && newStatus == Status.CONFIRMED

    /**
     * A duration split into the largest two units that still describe it, for a
     * countdown that reads like a sentence instead of a stopwatch.
     *
     * Deliberately coarse above an hour. "2 days 7 hours" tells the reader
     * everything they can act on; "2 days 7 hours 14 minutes 3 seconds" ticks in
     * their peripheral vision and makes a safety indicator feel like a bomb
     * timer. Below an hour the minutes matter, so that is where precision
     * arrives.
     */
    data class Breakdown(val days: Long, val hours: Long, val minutes: Long) {
        /** True when nothing is left to show — under a minute. */
        val isImminent: Boolean get() = days == 0L && hours == 0L && minutes == 0L
    }

    fun breakdown(durationMillis: Long): Breakdown {
        val total = kotlin.math.abs(durationMillis) / 1_000
        val days = total / 86_400
        val hours = (total % 86_400) / 3_600
        val minutes = (total % 3_600) / 60
        return when {
            days > 0 -> Breakdown(days, hours, 0)
            hours > 0 -> Breakdown(0, hours, minutes)
            else -> Breakdown(0, 0, minutes)
        }
    }
}
