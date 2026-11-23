package com.oshi.messenger.network.version

import android.util.Log
import com.oshi.messenger.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Numeric version comparison.
 *
 * The whole reason this object exists rather than a `<` on two Strings: a
 * lexicographic compare says `"1.0.9" > "1.0.43"`, because `'9' > '4'`. That is
 * not a hypothetical — it has shipped on this project before, and it makes a
 * version floor silently invert, so the users who are furthest BEHIND are the
 * only ones the gate leaves alone.
 *
 * Rules, all of them deliberate:
 *
 *  * components are split on `.` and compared as **integers**, most significant
 *    first;
 *  * a missing trailing component is `0`, so `1.6` == `1.6.0`;
 *  * a component that does not START with a digit makes the whole string
 *    **unjudgeable** — [compareOrNull] returns `null` rather than guessing. Every
 *    caller reads `null` as "no opinion", which is what keeps the gate fail-open;
 *  * a suffix on a numeric component is ignored (`"14-beta"` is 14), because a
 *    build tagged that way is still that version's wire format.
 */
object AppVersions {

    /**
     * The first OSHI version whose Double Ratchet responder starts its sending
     * chain at `HKDF(sharedSecret, "ResponderInitialChain-v3")` instead of 32 zero
     * bytes (commit 0cfc8c9 and its Kotlin mirror).
     *
     * A peer BELOW this derives a different chain, so the first message a v3
     * responder sends fails at the AEAD — in silence, on both sides. That is what
     * the in-conversation notice is for.
     *
     * Keep this equal to the `versionName` that first shipped the new schedule.
     */
    const val MIN_KEY_SCHEDULE_VERSION = "1.6.14"

    /**
     * The iOS floor for the SAME protocol change, on the 1.0.x ladder.
     *
     * `RatchetInterop.minimumVersion` in `OSHI/PeerVersionCompat.swift`. The two
     * numbers name one wire change; they differ because the two platforms number
     * the app differently, not because they ship different things.
     */
    const val MIN_KEY_SCHEDULE_VERSION_IOS = "1.0.44"

    /**
     * The floor to apply, PER PLATFORM.
     *
     * This is what the `appPlatform` tag finally makes possible. While the wire
     * said nothing about which ladder a number came from, silence was the only
     * honest behaviour — and it meant the notice could never help the case it was
     * written for, an iPhone left behind on the old key schedule. Now that the tag
     * is there, an iOS peer below 1.0.44 can be told, and iOS holds the mirror of
     * this table.
     *
     * A platform absent from this map is NOT judged. That is the default for
     * anything that comes later — a third-party client, a future platform — and
     * saying nothing beats comparing a number against a ladder that is not its own.
     */
    private val KEY_SCHEDULE_FLOORS: Map<String, String> = mapOf(
        ProfileVersionWire.PLATFORM_ANDROID to MIN_KEY_SCHEDULE_VERSION,
        ProfileVersionWire.PLATFORM_IOS to MIN_KEY_SCHEDULE_VERSION_IOS,
    )

    /**
     * Version string -> integer components, or `null` when the string is not a
     * version we are willing to judge (empty, blank, `"unknown"`, `"1.x.3"`, …).
     */
    fun parse(version: String?): List<Int>? {
        val trimmed = version?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split('.')
        val out = ArrayList<Int>(parts.size)
        for (part in parts) {
            val component = part.trim()
            val digits = component.takeWhile { it.isDigit() }
            if (digits.isEmpty()) return null
            // What follows the digits decides whether this is a version at all.
            // A semver pre-release / build suffix ("14-beta", "14+ci") is still
            // version 14. Anything else — "14 (52)", "14px", "14.x" — is a string
            // that merely BEGINS with a number, and reading a floor out of it would
            // be exactly the kind of guess this object refuses to make.
            val rest = component.substring(digits.length)
            if (rest.isNotEmpty() && rest[0] != '-' && rest[0] != '+') return null
            // A 20-digit component is garbage, not a big number; clamp rather than
            // throw or refuse, so a hostile or corrupt payload cannot crash the
            // caller and cannot pass itself off as unparseable-therefore-current.
            val value = digits.toLongOrNull() ?: Long.MAX_VALUE
            out.add(value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        return out
    }

    /**
     * `-1` / `0` / `1`, or **`null` when either side is unparseable**.
     *
     * The `null` is the point: it is impossible to accidentally treat "I could not
     * read this version" as "this version is old".
     */
    fun compareOrNull(a: String?, b: String?): Int? {
        val left = parse(a) ?: return null
        val right = parse(b) ?: return null
        val n = maxOf(left.size, right.size)
        for (i in 0 until n) {
            val x = left.getOrElse(i) { 0 }
            val y = right.getOrElse(i) { 0 }
            if (x != y) return if (x < y) -1 else 1
        }
        return 0
    }

    /** True only when [candidate] is provably below [floor]. Unknown -> false. */
    fun isOlderThan(candidate: String?, floor: String?): Boolean =
        (compareOrNull(candidate, floor) ?: 0) < 0

    /**
     * Does this peer's reported build speak the new key schedule?
     *
     * Two things must BOTH be known before anything is drawn:
     *
     *  * the peer reported a version — older builds do not send the field, and a
     *    silent peer must not be nagged about; and
     *  * the peer reported that it is an **Android** build.
     *
     * ### Why the platform tag is not optional
     *
     * [MIN_KEY_SCHEDULE_VERSION] is a number on the ANDROID version line. iOS
     * numbers the same app 1.0.x — its threshold for the very same protocol change
     * is `1.0.44` (`OSHI/PeerVersionCompat.swift`, `RatchetInterop.minimumVersion`).
     * Both platforms put their own number in the same `appVersion` field, and until
     * this tag existed nothing in the payload said which line a string belonged to.
     *
     * So comparing an iPhone's "1.0.43" against "1.6.14" says "older" about every
     * iOS peer on earth, forever — a permanent, user-visible lie on a banner whose
     * entire job is to be believed. It cannot be fixed by choosing a cleverer
     * number, because there is no number that is simultaneously correct on two
     * independent version lines. It needs the platform, so the platform is what the
     * payload now carries — and once carried, each ladder gets its OWN floor
     * ([KEY_SCHEDULE_FLOORS]) instead of one side going quiet. An iOS peer on
     * 1.0.43 is genuinely behind this wire change, and is now told so.
     *
     * A peer that sends no platform is not judged at all. That includes Android
     * 1.6.14 itself, which shipped `appVersion` without `appPlatform` — one release
     * of silence, which is the safe direction and the one this whole file prefers.
     */
    fun peerPredatesKeySchedule(peerVersion: String?, peerPlatform: String?): Boolean {
        // Exact lookup, deliberately: `ProfileVersionWire.readPlatform` is the one
        // place that normalises, and `the platform tag is matched exactly` pins
        // that division. A second normalisation here would quietly make the first
        // one optional.
        val floor = KEY_SCHEDULE_FLOORS[peerPlatform] ?: return false
        return peerVersion != null && isOlderThan(peerVersion, floor)
    }
}

/**
 * The `appVersion` field carried by the `📸PROFILE_UPDATE📸` payload.
 *
 * Kept here, as two pure functions over a `JSONObject`, rather than inline in
 * `MessageRepository`, so the wire-compatibility claim is something a JVM unit
 * test can actually drive: [stamp] is what the app sends and [read] is what the
 * app receives, and `ProfileVersionWireTest` runs a pre-change parser over the
 * output of the real [stamp].
 *
 * ### Why this is safe to add to a payload older builds already parse
 *
 * The field is **purely additive** and every existing reader ignores unknown keys:
 *
 *  * Android reads the payload with `org.json.JSONObject` and `opt*` accessors —
 *    `MessageRepository.handleProfileUpdate` asks for `profileImageData`,
 *    `lastSeen`, `groupEnvelopeV3` and `ratchetV3` by name. A key it does not ask
 *    for cannot affect it;
 *  * iOS decodes it into `ProfileUpdatePayload`, a `Codable` struct
 *    (`OSHI/ContactPresenceManager.swift:29`), and `JSONDecoder` ignores keys with
 *    no matching property by default. It does not throw and it does not fail the
 *    decode.
 *
 * The one thing that would NOT be safe is changing an existing key's type or
 * meaning, which is why this is a new key holding a plain string.
 */
object ProfileVersionWire {

    const val KEY = "appVersion"

    /**
     * Which version LINE the [KEY] string belongs to.
     *
     * Android numbers this app 1.6.x and iOS numbers it 1.0.x. Without this,
     * "1.0.43" and "1.6.13" are two strings that compare cleanly and mean nothing
     * together — see `AppVersions.peerPredatesKeySchedule`.
     */
    const val KEY_PLATFORM = "appPlatform"

    const val PLATFORM_ANDROID = "android"
    const val PLATFORM_IOS = "ios"

    /**
     * Write this build's `versionName` AND its platform into an outgoing profile
     * payload. Two keys, both additive, both ignored by every reader that does not
     * ask for them.
     */
    fun stamp(json: JSONObject, versionName: String): JSONObject {
        val clean = versionName.trim()
        if (clean.isNotEmpty()) {
            json.put(KEY, clean)
            // Only alongside a version: a platform with no version says nothing,
            // and a lone tag would just be noise on the wire.
            json.put(KEY_PLATFORM, PLATFORM_ANDROID)
        }
        return json
    }

    /**
     * The peer's version, or `null` when they did not send one (or sent
     * something empty or a JSON null). Never throws.
     */
    fun read(json: JSONObject): String? = readString(json, KEY)

    /**
     * The peer's platform, lowercased, or `null` when they did not send one.
     *
     * `null` is the common case for a while: iOS has not shipped this key yet, and
     * neither did Android 1.6.14. Every caller must read `null` as "do not judge".
     */
    fun readPlatform(json: JSONObject): String? =
        readString(json, KEY_PLATFORM)?.lowercase()

    private fun readString(json: JSONObject, key: String): String? =
        runCatching {
            json.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
        }.getOrNull()
}

/**
 * A version floor asked of OSHI's own server, which **fails open in every
 * direction**.
 *
 * Contract, in the owner's words: "if you put in a version gate, make it FAIL
 * OPEN when the server is unreachable, and NEVER block offline messaging."
 * Concretely:
 *
 *  * nothing on the send, receive, compose, read or mesh path calls into here.
 *    The only consumer is the dismissible notice drawn above a conversation, so
 *    the gate CANNOT block messaging — not by policy, by wiring;
 *  * any exception, any non-2xx, any body that is not JSON, any absent or
 *    unparseable field clears [minimumVersion] back to `null`, which means "no
 *    gate". No log louder than a debug line, no user-visible error;
 *  * a failure never leaves a STALE floor in place; a gate is only ever as good
 *    as the last successful answer, so a failed refresh forgets it.
 *
 * The endpoint is the one the app already has — `GET {VPS_URL}/v2/config`, the
 * unauthenticated rollout config `V2Config` reads. No new host, no literal IP:
 * the base URL comes from `BuildConfig.VPS_URL`, which is set once in
 * `app/build.gradle.kts`. A server that has never heard of
 * [KEY_MIN_SUPPORTED_VERSION] simply omits it, and that is the no-gate case.
 */
object MinimumVersionGate {

    private const val TAG = "MinimumVersionGate"

    /** Path on the project's own server. Same endpoint as `V2Config`. */
    const val CONFIG_PATH = "/v2/config"

    /** Android-specific floor, preferred when present. */
    const val KEY_ANDROID_MIN_SUPPORTED_VERSION = "android_min_supported_version"

    /** Cross-platform floor, used when the Android-specific one is absent. */
    const val KEY_MIN_SUPPORTED_VERSION = "min_supported_version"

    /**
     * __UPDATE_LATEST_VERSION_2026_11_03__ (Anatole A7) The newest build actually
     * available, which is a DIFFERENT question from the minimum still accepted.
     *
     * Without it the Updates sheet could only answer "you are above the floor",
     * so a user on 1.6.38 was told they were up to date while production sat at
     * 1.6.66 — reported as "the button does not warn when an update comes out".
     * Absent until the server publishes it, and absent reads as "we do not
     * know", never as "you are current".
     */
    const val KEY_ANDROID_LATEST_VERSION = "android_latest_version"

    /** Don't ask more than once a TTL; the answer changes at most daily. */
    const val TTL_MS = 6 * 60 * 60 * 1000L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Last floor the server actually gave us. `null` = no gate. */
    @Volatile
    var minimumVersion: String? = null
        private set

    /**
     * Newest published build, or `null` when the server does not say. `null`
     * must never be read as "up to date": it means the question is unanswered.
     */
    @Volatile
    var latestVersion: String? = null
        private set

    /**
     * __FORCED_UPDATE_ANDROID_2026_10_13__ The BLOCKING floor, from the separate
     * `android_blocking_update_version` key, read out of the same single response.
     *
     * Kept here rather than in a second object so there is still exactly one
     * request on one TTL: two independently-refreshing gates could disagree for
     * up to six hours, and the disagreement a user would notice is "blocked by a
     * floor that the nag banner says I already satisfy".
     *
     * `null` = never block. Every failure path leaves it `null`.
     */
    @Volatile
    var blockingVersion: String? = null
        private set

    @Volatile
    private var lastAttemptMs: Long = 0L

    /**
     * Pure: what a response body means. Returns `null` for every kind of
     * failure — not JSON, wrong types, missing key, unparseable version.
     *
     * Split out from the network call precisely so "garbage JSON produces no
     * gate" is a plain JVM unit test and not a hope.
     */
    fun parseMinimumVersion(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val json = JSONObject(body)
            val raw = json.optString(KEY_ANDROID_MIN_SUPPORTED_VERSION)
                .ifBlank { json.optString(KEY_MIN_SUPPORTED_VERSION) }
            // optString hands back "" for an absent key, and the literal "null"
            // for a JSON null. Both mean no gate.
            val candidate = raw.trim().takeIf { it.isNotEmpty() && it != "null" }
            // Only a version we can actually compare is allowed to become a gate.
            if (AppVersions.parse(candidate) == null) null else candidate
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Pure: should this build be offered an update?
     *
     * `false` whenever there is no floor, the floor is unreadable, or our own
     * version is unreadable. Never throws.
     */
    fun shouldPrompt(currentVersion: String?, minimumVersion: String?): Boolean =
        AppVersions.isOlderThan(currentVersion, minimumVersion)

    /** Convenience for the UI: the gate as it applies to THIS build. */
    fun shouldPromptThisBuild(): Boolean =
        shouldPrompt(BuildConfig.VERSION_NAME, minimumVersion)

    /** Pure, same coercion rules as [parseMinimumVersion]. */
    fun parseLatestVersion(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val raw = JSONObject(body).optString(KEY_ANDROID_LATEST_VERSION)
            val candidate = raw.trim().takeIf { it.isNotEmpty() && it != "null" }
            if (AppVersions.parse(candidate) == null) null else candidate
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Pure: is something newer than [currentVersion] published?
     *
     * `false` when the server said nothing, which keeps "we do not know" and
     * "you are current" distinguishable at the call site — the UI shows a
     * third state rather than claiming either.
     */
    fun newerAvailable(currentVersion: String?, latestVersion: String?): Boolean =
        AppVersions.isOlderThan(currentVersion, latestVersion)

    /** Convenience for the UI: is there a newer build than THIS one? */
    fun newerAvailableThanThisBuild(): Boolean =
        newerAvailable(BuildConfig.VERSION_NAME, latestVersion)

    /**
     * Ask the server, at most once per [TTL_MS]. Suspends on IO. Swallows
     * everything: there is no failure mode that surfaces to the user, and no
     * failure mode that produces a gate.
     */
    suspend fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastAttemptMs < TTL_MS) return
        lastAttemptMs = now
        val body = fetchBody()
        // Both floors come out of the SAME body, so a failure clears BOTH. A
        // stale blocking floor surviving a failed refresh is the one state that
        // could wall a user in after the server had already stopped asking for it.
        minimumVersion = parseMinimumVersion(body)
        latestVersion = parseLatestVersion(body)
        blockingVersion = com.oshi.messenger.update.ForcedUpdatePolicy.parseBlockingVersion(body)
    }

    private suspend fun fetchBody(): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${BuildConfig.VPS_URL}$CONFIG_PATH")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) return@withContext null
                resp.body?.string()
            }
        } catch (e: Exception) {
            // Unreachable server, DNS failure, timeout, TLS error, airplane mode,
            // a captive portal serving HTML — every one of them lands here and
            // every one of them means "no gate".
            Log.d(TAG, "no version floor available (${e.javaClass.simpleName}) — no gate")
            null
        }
    }

    /** Tests only. */
    fun resetForTesting() {
        minimumVersion = null
        latestVersion = null
        blockingVersion = null
        lastAttemptMs = 0L
    }
}
