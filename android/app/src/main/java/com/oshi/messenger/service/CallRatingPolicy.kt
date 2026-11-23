package com.oshi.messenger.service

/**
 * __CALL_RATING_2026_09_22__
 *
 * Whether to ask this person about this call.
 *
 * Pure and separate from [CallRatingManager] — the same reasoning as
 * [CallSummaryFormat] — so every gate can be tested without a device, a clock or
 * a SharedPreferences. The manager reads the stored state, calls [decide], and
 * writes back whatever the decision says changed.
 *
 * The rules, in the order they are applied:
 *
 *  1. **Too short.** Under [MIN_SECONDS] there was no conversation to judge —
 *     UNLESS the call ended on a fault (`wasFault`). See
 *     `__SHORT_FAILURES_COULD_NEVER_BE_REPORTED_2026_11_07__` on [decide]: this
 *     gate used to sit above rule 4 and quietly voided it.
 *  2. **Weekly ceiling.** At most [MAX_ASKS_PER_WEEK] asks in any rolling week,
 *     whatever else is true. This outranks every reason to ask.
 *  3. **Cooldown.** A quiet period after the previous ask, shorter when the call
 *     ended badly ([COOLDOWN_ABNORMAL_MS]) than when it ended normally
 *     ([COOLDOWN_MS]).
 *  4. **Abnormal end always asks.** A call that dropped, errored or lost the peer
 *     is exactly the one worth hearing about, so it skips the sampling below.
 *  5. **Sampling.** Otherwise only every [SAMPLE_EVERY_NTH_GOOD_CALL]-th good
 *     call is asked about. Being asked after every call is the fastest way to
 *     train someone to dismiss the sheet unread.
 */
object CallRatingPolicy {

    const val MIN_SECONDS = 8L
    const val SAMPLE_EVERY_NTH_GOOD_CALL = 3
    const val COOLDOWN_MS = 20L * 60 * 60 * 1000
    const val COOLDOWN_ABNORMAL_MS = 3L * 60 * 60 * 1000
    const val MAX_ASKS_PER_WEEK = 5
    const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

    /** What the manager should do once [decide] has run. */
    data class Decision(
        val ask: Boolean,
        /** The new good-call counter to store, or null to leave it alone. */
        val newGoodCallCounter: Int?,
        /** Why we are not asking — one reason per rule, so a test can name the gate. */
        val blockedBy: Block? = null
    )

    enum class Block { TOO_SHORT, ALREADY_RATED, WEEKLY_CEILING, COOLDOWN, NOT_SAMPLED }

    /**
     * __SHORT_FAILURES_COULD_NEVER_BE_REPORTED_2026_11_07__
     *
     * THE BUG THIS CLOSES: rule 4 above promises "a call that dropped, errored
     * or lost the peer is exactly the one worth hearing about", and the
     * [MIN_SECONDS] gate sat above it — so the promise was void for any such
     * call shorter than 8 s, which is most of them. Measured on the relay over
     * 06-07/11: 17 calls, 8 of them 0-4 s, ONE rating returned in total.
     *
     * [wasFault] is deliberately NARROWER than `!endedNormally`: a DECLINED or
     * ANSWERED_ELSEWHERE call is also "not normal" and is not a fault, and
     * asking "how did that sound?" after one is how a sheet gets trained into
     * reflex dismissal. The floor still applies to those.
     *
     * @param wasFault the call ended because something broke (ERROR,
     *   CONNECTION_LOST), not because someone chose to end it.
     * @param askTimes every previous ask, in epoch millis; older entries are ignored.
     * @param goodCallCounter how many good calls have been counted so far.
     */
    fun decide(
        now: Long,
        durationSeconds: Long,
        endedNormally: Boolean,
        alreadyRated: Boolean,
        lastAskAt: Long,
        askTimes: List<Long>,
        goodCallCounter: Int,
        wasFault: Boolean = false
    ): Decision {
        if (alreadyRated) return Decision(false, null, Block.ALREADY_RATED)
        if (!wasFault && durationSeconds < MIN_SECONDS) {
            return Decision(false, null, Block.TOO_SHORT)
        }

        val recent = askTimes.filter { it > now - WEEK_MS }
        if (recent.size >= MAX_ASKS_PER_WEEK) return Decision(false, null, Block.WEEKLY_CEILING)

        val abnormal = !endedNormally
        if (lastAskAt > 0) {
            val cooldown = if (abnormal) COOLDOWN_ABNORMAL_MS else COOLDOWN_MS
            if (now - lastAskAt < cooldown) return Decision(false, null, Block.COOLDOWN)
        }

        // A call that dropped or errored is always worth asking about, and it does
        // NOT consume the good-call counter — that counter paces ordinary calls.
        if (abnormal) return Decision(true, null)

        val n = goodCallCounter + 1
        return if (n % SAMPLE_EVERY_NTH_GOOD_CALL == 0) {
            Decision(true, n)
        } else {
            Decision(false, n, Block.NOT_SAMPLED)
        }
    }
}
