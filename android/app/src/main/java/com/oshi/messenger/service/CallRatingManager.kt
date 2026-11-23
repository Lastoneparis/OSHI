package com.oshi.messenger.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * __CALL_RATING_2026_09_22__
 *
 * After a call ends, ask how it went — the same question, the same policy and the
 * same wire format as iOS `CallRatingManager.swift`, so one channel receives both.
 *
 * Why it exists: the server can measure a call (packets each way, which transport
 * carried them, how lopsided the directions were) but it cannot tell whether the
 * person could actually HEAR. Measured on the live server Sep 15-22: calls that
 * looked healthy in the relay counters were one-way in practice, and calls that
 * looked dead there had in fact run cleanly over TURN, which the relay never sees.
 *
 * What leaves the device: a star count, optional canned reasons, an optional note
 * the person typed, the callId, platform and version. Never the peer's identity,
 * never anything from the conversation.
 */
object CallRatingManager {

    private const val TAG = "CallRating"
    private const val ENDPOINT = "https://oshi-messenger.com/voip/call-rating"
    private const val PREFS = "call_rating_prefs"

    /** Mirrors `PendingCallRating` on iOS. */
    data class Pending(
        val callId: String,
        val peerName: String,
        val durationSeconds: Long,
        val wasVideo: Boolean,
        val endedAbnormally: Boolean,
        /** Carried here so the dialog never has to reach into the call manager. */
        val myPublicKey: String,
        /** Read at call end, for the same reason: the dialog outlives the call. */
        val evidence: Evidence? = null
    )

    /**
     * __TX_VOICE_EVIDENCE_2026_10_29__ What THIS side knows about the call, sent with
     * the verdict. Mirrors `CallRatingEvidence` on iOS; the wire names must match.
     *
     * WHY: the monitoring channel's numbers are the relay's, and the relay can only see
     * that bytes moved. Call `97d98d39` was rated 1/5 `theyCouldNotHearMe` with 4257
     * frames relayed in the complained-about direction over a proven UDP path for the
     * whole 1:25 — a perfect record of a call the user says was silent. The relay has no
     * way to tell a microphone that captured silence from a peer that failed to play
     * what arrived, so the report could not name a side. These five numbers can:
     *
     *   txVoicePercent  ~0 with [txFrames] high  → we transmitted silence; look here.
     *   txVoicePercent  high and the relay saw the frames → the peer did not play them;
     *                                                       look at [peerPlatform].
     *   rxFrames ~0 while the relay says it forwarded → our own receive path, which is
     *                                                   the `couldNotHearThem` case.
     */
    data class Evidence(
        /** Audio frames we encoded and handed to the transport. */
        val txFrames: Long,
        /** Audio frames we decoded. */
        val rxFrames: Long,
        /** 0…100 share of transmitted frames carrying voice; null when never measured. */
        val txVoicePercent: Int?,
        /** 0…100 loudest transmitted frame; null when never measured. */
        val txPeakPercent: Int?,
        /** The negotiated wire codec, e.g. "opus". */
        val codec: String,
        /** The peer's platform as this side resolved it: "ios", "android", "unknown". */
        val peerPlatform: String,
        /** __CALL_TX_SILENT_DROP_2026_11_10__ captured frames that never left, and why. */
        val txDropped: Long = 0,
        val txDropStage: String? = null,
        val txError: String? = null,
        val opusFallback: Boolean = false
    )

    /** Mirrors `CallRatingReason` on iOS — the wire values must match exactly. */
    enum class Reason(val wire: String) {
        COULD_NOT_HEAR_THEM("couldNotHearThem"),
        THEY_COULD_NOT_HEAR_ME("theyCouldNotHearMe"),
        CHOPPY("choppy"),
        DELAY("delay"),
        ECHO("echo"),
        DROPPED("dropped")
    }

    private object Config {
        /** Let the call UI finish dismissing before the sheet rises. */
        const val PRESENT_DELAY_MS = 600L
    }

    private object Key {
        const val GOOD_CALL_COUNTER = "good_call_counter"
        const val LAST_ASK_AT = "last_ask_at"
        const val ASK_TIMES = "ask_times"          // comma-separated epoch millis
        const val LAST_RATED_CALL_ID = "last_rated_call_id"
    }

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val submitted = mutableSetOf<String>()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // ---------------------------------------------------------------- deciding

    /**
     * Called the moment a call finishes. Decides whether this particular call is
     * worth asking about and, if so, raises [pending] after a short beat so the
     * call UI is gone first.
     */
    fun callDidEnd(
        context: Context,
        callId: String,
        peerName: String,
        durationSeconds: Long,
        wasVideo: Boolean,
        endedNormally: Boolean,
        myPublicKey: String,
        evidence: Evidence? = null,
        /**
         * __SHORT_FAILURES_COULD_NEVER_BE_REPORTED_2026_11_07__ The call ended
         * because something BROKE, not because someone chose to end it. Lets the
         * [CallRatingPolicy.MIN_SECONDS] floor be skipped — see the policy.
         */
        wasFault: Boolean = false
    ) {
        if (callId.isBlank()) return

        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // One verdict per call, however many times the end is reported — the call
        // manager and the signalling layer can both report it.
        val alreadyRated = prefs.getString(Key.LAST_RATED_CALL_ID, null) == callId ||
            synchronized(submitted) { submitted.contains(callId) }
        if (_pending.value != null) return

        val now = System.currentTimeMillis()
        val decision = CallRatingPolicy.decide(
            now = now,
            durationSeconds = durationSeconds,
            endedNormally = endedNormally,
            alreadyRated = alreadyRated,
            lastAskAt = prefs.getLong(Key.LAST_ASK_AT, 0L),
            askTimes = storedAskTimes(prefs),
            goodCallCounter = prefs.getInt(Key.GOOD_CALL_COUNTER, 0),
            wasFault = wasFault
        )
        // The counter advances even when the answer is "not this one" — that is
        // what makes it every third call rather than every third ask.
        decision.newGoodCallCounter?.let {
            prefs.edit().putInt(Key.GOOD_CALL_COUNTER, it).apply()
        }
        if (!decision.ask) {
            Log.d(TAG, "not asking: ${decision.blockedBy}")
            return
        }

        val askTimes = storedAskTimes(prefs).filter { it > now - CallRatingPolicy.WEEK_MS } + now
        prefs.edit()
            .putString(Key.LAST_RATED_CALL_ID, callId)
            .putLong(Key.LAST_ASK_AT, now)
            .putString(Key.ASK_TIMES, askTimes.joinToString(","))
            .apply()

        val item = Pending(callId, peerName, durationSeconds, wasVideo, !endedNormally, myPublicKey, evidence)
        scope.launch {
            delay(Config.PRESENT_DELAY_MS)
            if (_pending.value == null) _pending.value = item
        }
    }

    /**
     * The wire body, built apart from the network call so a test can assert the shape.
     * Server side: `POST /call-rating` in call_server.js.
     */
    internal fun buildBody(
        callId: String,
        senderKey: String,
        rating: Int,
        reasons: Set<Reason>,
        comment: String,
        appVersion: String,
        evidence: Evidence?
    ): JSONObject = JSONObject().apply {
        put("callId", callId)
        put("sender", senderKey)
        put("rating", rating)
        put("platform", "Android")
        put("appVersion", appVersion)
        if (reasons.isNotEmpty()) {
            put("reasons", JSONArray(reasons.map { it.wire }.sorted()))
        }
        if (comment.isNotEmpty()) put("comment", comment)
        if (evidence != null) {
            put("evidence", JSONObject().apply {
                put("txFrames", evidence.txFrames)
                put("rxFrames", evidence.rxFrames)
                // Absent, not zero: a level that was never measured and a microphone
                // that stayed silent are the two answers this field exists to separate.
                evidence.txVoicePercent?.let { put("txVoicePct", it) }
                evidence.txPeakPercent?.let { put("txPeakPct", it) }
                put("codec", evidence.codec)
                put("peer", evidence.peerPlatform)
                if (evidence.txDropped > 0) put("txDropped", evidence.txDropped)
                evidence.txDropStage?.let { put("txDropStage", it) }
                evidence.txError?.let { put("txError", it) }
                if (evidence.opusFallback) put("opusFallback", true)
            })
        }
    }

    private fun storedAskTimes(prefs: android.content.SharedPreferences): List<Long> =
        (prefs.getString(Key.ASK_TIMES, "") ?: "")
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }

    // ---------------------------------------------------------------- answering

    fun dismiss() {
        _pending.value = null
    }

    /**
     * Send the verdict. Fire-and-forget by design: a rating is never worth an
     * error message, a retry queue or a spinner in the person's way.
     */
    fun submit(
        rating: Int,
        reasons: Set<Reason>,
        comment: String,
        appVersion: String
    ) {
        val item = _pending.value ?: return
        _pending.value = null
        synchronized(submitted) { submitted.add(item.callId) }

        val myPublicKey = item.myPublicKey
        if (myPublicKey.isBlank()) return
        val clamped = rating.coerceIn(1, 5)
        val note = comment.trim().take(300)

        val body = buildBody(item.callId, myPublicKey, clamped, reasons, note, appVersion, item.evidence)

        scope.launch {
            try {
                val request = Request.Builder()
                    .url(ENDPOINT)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(request).execute().use { response ->
                    Log.d(TAG, "📞⭐️ Call rating sent for ${item.callId.take(8)}: HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "📞⭐️ Call rating not sent: ${e.message}")
            }
        }
    }
}
