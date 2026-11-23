package com.oshi.messenger.network.v2

import android.content.Context
import com.oshi.messenger.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * V2 rollout gate, matching iOS V2FeatureFlag. The V2 transport is OFF unless:
 *   - the local override "oshi_v2_enabled" is set (dev/testing), OR
 *   - GET /v2/config (unauthenticated) says v2_enabled, this build >= min_build,
 *     AND SHA-256(userKey) falls inside rollout_percent.
 *
 * Default is disabled, so nothing V2 runs — and crucially the prekey bundle is NOT
 * published — until this returns true. That prevents iOS from routing V2 to an
 * Android install whose receive path hasn't been proven on a device yet.
 */
@Singleton
class V2Config @Inject constructor(
    private val coverSettings: CoverSettings,
    @ApplicationContext private val context: Context,
) {
    private val client = OkHttpClient.Builder()
        .addInterceptor(coverSettings.interceptor())
        .noSilentReplay()   // __SIGNED_NO_SILENT_REPLAY_2026_11_18__ (CoverInterceptor.kt)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val prefs get() = context.getSharedPreferences("v2_config", Context.MODE_PRIVATE)

    // Stale-while-revalidate cache of the resolved gate. Without this, isEnabled did a
    // BLOCKING GET /v2/config on EVERY send (before the legacy path even started) and on
    // every 3s poll tick — a real regression even with V2 nominally off. Now:
    //   • the hot gate ([isEnabledCached]) is a pure in-memory read that never touches
    //     the network, so a send is never stalled by the V2 config lookup, and
    //   • the value is refreshed at most once per TTL ([refresh]), driven by the poll
    //     loop, shared across every caller. Kill-switch latency is bounded by the TTL.
    @Volatile private var cachedEnabled: Boolean = false
    @Volatile private var cachedAtMs: Long = 0L
    private val refreshMutex = Mutex()

    /** Local dev/testing override — set true to force V2 on for this install. */
    fun localEnabled(): Boolean = prefs.getBoolean("oshi_v2_enabled", false)
    fun setLocalEnabled(on: Boolean) = prefs.edit().putBoolean("oshi_v2_enabled", on).apply()

    /**
     * Non-blocking gate: the last resolved value (default OFF), never a network call.
     * The local override wins immediately. Callers on the hot send/poll path use THIS;
     * freshness is maintained out-of-band by [refresh].
     */
    fun isEnabledCached(): Boolean = localEnabled() || cachedEnabled

    // __PER_DEVICE_MAILBOX_2026_09_23__ `/v2/config.device_mailbox_enabled` (CLIENT_SPEC §3.1).
    // Read from the SAME TTL-bounded fetch as the V2 gate. Fails closed (false) on any error, and
    // a `409 device-mailbox-disabled` from any route forces it off until the next refresh.
    @Volatile private var cachedDeviceMailbox: Boolean = false

    /** Local dev/testing override for the device-mailbox path (never set in production). */
    fun deviceMailboxLocalOverride(): Boolean = prefs.getBoolean("oshi_device_mailbox_enabled", false)
    fun setDeviceMailboxLocalOverride(on: Boolean) = prefs.edit().putBoolean("oshi_device_mailbox_enabled", on).apply()

    /** Non-blocking: V2 on AND the server (or the dev override) says per-device mailboxes are on. */
    fun deviceMailboxEnabledCached(): Boolean = isEnabledCached() && (cachedDeviceMailbox || deviceMailboxLocalOverride())

    /** The server answered `409 device-mailbox-disabled`: stop using device headers now (§3.1). */
    fun noteDeviceMailboxDisabled() { cachedDeviceMailbox = false }

    /**
     * Refresh the cached gate from /v2/config if the cached value is older than the TTL.
     * At most one network call per TTL across all callers (deduped by [refreshMutex] +
     * double-checked staleness). Fails closed. The poll loop calls this every tick, so
     * the actual /v2/config traffic is ~1 request per TTL, not per tick.
     */
    suspend fun refresh(userKey: String) {
        // __PER_DEVICE_MAILBOX_2026_09_23__ the local V2 override used to skip the fetch
        // entirely; the device-mailbox flag still has to come from the server, so a forced-on
        // V2 install keeps refreshing (TTL-bounded) — the V2 gate itself is unaffected.
        if (localEnabled() && System.currentTimeMillis() - cachedAtMs < TTL_MS) return
        if (System.currentTimeMillis() - cachedAtMs < TTL_MS) return
        refreshMutex.withLock {
            if (System.currentTimeMillis() - cachedAtMs < TTL_MS) return  // someone beat us
            cachedEnabled = fetchEnabled(userKey)
            cachedAtMs = System.currentTimeMillis()
        }
    }

    /** Resolve V2 for the given identity: refresh (TTL-bounded) then read the cache. */
    suspend fun isEnabled(userKey: String): Boolean {
        refresh(userKey)
        return isEnabledCached()
    }

    private suspend fun fetchEnabled(userKey: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("${BuildConfig.VPS_URL}/v2/config").get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) return@withContext false
                val json = JSONObject(resp.body?.string().orEmpty())
                // __PER_DEVICE_MAILBOX_2026_09_23__ default false; absent on today's server.
                cachedDeviceMailbox = json.optBoolean("device_mailbox_enabled", false)
                // __LEGACY_COMPAT_2026_09_23__ `group_legacy_compat` (default false, sunset-capped
                // client side in GroupLegacyCompat). Read on every successful answer.
                GroupLegacyCompat.attach(context)
                GroupLegacyCompat.setServerFlag(GroupLegacyCompat.parseFlag(json))
                val v2Enabled = json.optBoolean("v2_enabled", false)
                val rollout = json.optInt("rollout_percent", 0)
                val minBuild = json.optInt("min_build", Int.MAX_VALUE)
                if (!v2Enabled) return@withContext false
                if (BuildConfig.VERSION_CODE < minBuild) return@withContext false
                bucket(userKey) < rollout
            }
        } catch (_: Exception) {
            false // fail closed: any error → stay on the proven legacy path
        }
    }

    /** Stable 0..99 rollout bucket = first 4 bytes of SHA-256(userKey), big-endian,
     *  mod 100 — matching iOS MessageManager+V2 so the same identity lands in the
     *  same cohort on both platforms. */
    private fun bucket(userKey: String): Int {
        val h = MessageDigest.getInstance("SHA-256").digest(userKey.toByteArray(Charsets.UTF_8))
        val v = ((h[0].toLong() and 0xFF) shl 24) or
                ((h[1].toLong() and 0xFF) shl 16) or
                ((h[2].toLong() and 0xFF) shl 8) or
                (h[3].toLong() and 0xFF)
        return (v % 100).toInt()
    }

    private companion object {
        // Gate cache lifetime. Long enough that sends/polls almost never trigger a
        // network refresh; short enough that the rollout_percent=0 kill-switch takes
        // effect within a minute.
        const val TTL_MS = 60_000L
    }
}
