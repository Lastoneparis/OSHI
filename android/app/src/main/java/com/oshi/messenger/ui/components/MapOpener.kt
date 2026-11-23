package com.oshi.messenger.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import androidx.datastore.preferences.core.stringPreferencesKey
import com.oshi.messenger.service.map.PlatformGeocoderGate
import com.oshi.messenger.ui.viewmodel.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * THE single place that opens a specific place — a coordinate or a street
 * address — honouring Settings › "Open locations in" (`map_open_preference`:
 * "oshi" | "google" | "waze"). Mirrors iOS `MapOpener` (LocationSharingView.swift).
 *
 * __MAP_OPENER_2026_09_23__ Before this, only the 1:1 location bubble really
 * honoured the setting:
 *   • the group bubble, with "OSHI Maps" chosen, fired a bare `geo:` intent —
 *     i.e. opened Google Maps, the exact opposite of the choice;
 *   • check-in bubbles had no way to open the position at all;
 *   • map links in messages (maps.google.com, maps.apple.com, geo:) always went
 *     to the browser / system handler;
 *   • street addresses in a message weren't tappable.
 *
 * "oshi" opens OSHI's Map destination focused on the place: callers that own a
 * richer presentation (contact avatar, live tracking) pass `onOshi`; everyone
 * else goes through [oshiRequests], which MainActivity turns into
 * `map?lat=…&lon=…&name=…`. The Map TAB itself is OSHI's map and is not
 * affected by the setting.
 */
object MapOpener {

    const val PREF_OSHI = "oshi"
    const val PREF_GOOGLE = "google"
    const val PREF_WAZE = "waze"

    private val prefKey = stringPreferencesKey("map_open_preference")
    private const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"

    /** Scheme used by LinkifyText for detected street addresses; never leaves the app. */
    const val ADDRESS_SCHEME = "oshi-address"

    data class OshiRequest(val lat: Double, val lon: Double, val label: String?)

    private val _oshiRequests = MutableSharedFlow<OshiRequest>(extraBufferCapacity = 4)
    /** Collected by MainActivity: navigate to the Map destination focused on the point. */
    val oshiRequests: SharedFlow<OshiRequest> = _oshiRequests.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    suspend fun preference(context: Context): String =
        context.dataStore.data.first()[prefKey] ?: PREF_OSHI

    // ── Coordinates ─────────────────────────────────────────────────────────

    fun open(
        context: Context,
        preference: String,
        lat: Double,
        lon: Double,
        label: String?,
        onOshi: (() -> Unit)? = null
    ) {
        when (preference) {
            PREF_GOOGLE -> {
                val q = Uri.encode(label?.takeIf { it.isNotBlank() } ?: "$lat,$lon")
                val app = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon($q)"))
                    .setPackage(GOOGLE_MAPS_PACKAGE)
                val web = Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lon")
                if (!start(context, app)) start(context, Intent(Intent.ACTION_VIEW, web))
            }
            PREF_WAZE -> {
                val app = Intent(Intent.ACTION_VIEW, Uri.parse("waze://?ll=$lat,$lon&navigate=yes"))
                val web = Uri.parse("https://waze.com/ul?ll=$lat,$lon&navigate=yes")
                if (!start(context, app)) start(context, Intent(Intent.ACTION_VIEW, web))
            }
            else -> {
                if (onOshi != null) onOshi()
                else _oshiRequests.tryEmit(OshiRequest(lat, lon, label))
            }
        }
    }

    /** Same as [open] but reads the preference itself (for non-Compose call sites). */
    fun open(context: Context, lat: Double, lon: Double, label: String?) {
        scope.launch { open(context, preference(context), lat, lon, label) }
    }

    // ── Addresses ───────────────────────────────────────────────────────────

    fun openAddress(context: Context, address: String) {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            val q = URLEncoder.encode(trimmed, "UTF-8").replace("+", "%20")
            when (preference(context)) {
                PREF_GOOGLE -> {
                    val app = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q"))
                        .setPackage(GOOGLE_MAPS_PACKAGE)
                    val web = Uri.parse("https://www.google.com/maps/search/?api=1&query=$q")
                    if (!start(context, app)) start(context, Intent(Intent.ACTION_VIEW, web))
                }
                PREF_WAZE -> {
                    val app = Intent(Intent.ACTION_VIEW, Uri.parse("waze://?q=$q&navigate=yes"))
                    val web = Uri.parse("https://waze.com/ul?q=$q&navigate=yes")
                    if (!start(context, app)) start(context, Intent(Intent.ACTION_VIEW, web))
                }
                else -> {
                    // The platform Geocoder is a Google network service: "Offline maps
                    // only" must refuse it (PlatformGeocoderGate, census-tested in
                    // OfflineOnlyGeocoderGateTest) — and must not fall back to the web.
                    when (PlatformGeocoderGate.decide(context, trimmed)) {
                        PlatformGeocoderGate.Decision.BlockedOfflineOnly -> {
                            android.widget.Toast.makeText(
                                context,
                                com.oshi.messenger.R.string.itinerary_address_lookup_offline_only,
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        PlatformGeocoderGate.Decision.Idle -> Unit
                        PlatformGeocoderGate.Decision.Geocode -> {
                            val point = geocode(context, trimmed)
                            if (point != null) {
                                _oshiRequests.tryEmit(OshiRequest(point.first, point.second, trimmed))
                            } else {
                                // Can't place it — let the system show it rather than do nothing.
                                start(context, Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://www.google.com/maps/search/?api=1&query=$q")))
                            }
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun geocode(context: Context, address: String): Pair<Double, Double>? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!Geocoder.isPresent()) return@runCatching null
                Geocoder(context).getFromLocationName(address, 1)
                    ?.firstOrNull()?.let { it.latitude to it.longitude }
            }.getOrNull()
        }

    // ── Links in messages ───────────────────────────────────────────────────

    sealed interface Target {
        data class Point(val lat: Double, val lon: Double, val label: String?) : Target
        data class Address(val text: String) : Target
    }

    /**
     * Routes a tapped link if it names a place (detected address, Google Maps,
     * Apple Maps, geo:). Returns false for any other URL — the caller opens it
     * as before.
     */
    fun handleLink(context: Context, url: String): Boolean {
        val target = parse(url) ?: return false
        when (target) {
            is Target.Point -> open(context, target.lat, target.lon, target.label)
            is Target.Address -> openAddress(context, target.text)
        }
        return true
    }

    fun addressLink(address: String): String =
        "$ADDRESS_SCHEME://open?q=" + Uri.encode(address)

    /** Pure parser (unit-testable). Only links that NAME a place are recognised. */
    fun parse(url: String): Target? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: ""
        fun q(name: String): String? =
            runCatching { uri.getQueryParameter(name) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        if (scheme == ADDRESS_SCHEME) return q("q")?.let { Target.Address(it) }

        if (scheme == "geo") {
            q("q")?.let { return target(it) }
            val body = uri.schemeSpecificPart?.substringBefore('?')
            return coord(body)?.let { Target.Point(it.first, it.second, null) }
        }
        if (scheme != "http" && scheme != "https") return null

        if (host == "maps.apple.com" || host == "maps.apple") {
            val name = q("q")
            return target(q("ll"), name) ?: target(q("daddr")) ?: target(q("address"))
                ?: target(q("q")) ?: target(q("sll"))
        }
        val path = uri.path ?: ""
        val isGoogle = host == "maps.google.com" ||
            ((host == "google.com" || host.endsWith(".google.com") || host.startsWith("google.") ||
                host.contains(".google.")) && path.startsWith("/maps"))
        if (isGoogle) {
            (target(q("query")) ?: target(q("q")) ?: target(q("destination"))
                ?: target(q("daddr")) ?: target(q("ll")))?.let { return it }
            val at = path.indexOf("/@")
            if (at >= 0) return coord(path.substring(at + 2))?.let { Target.Point(it.first, it.second, null) }
            val place = path.indexOf("/maps/place/")
            if (place >= 0) {
                val rest = path.substring(place + "/maps/place/".length).substringBefore('/')
                return target(rest.replace('+', ' '))
            }
        }
        return null
    }

    private fun target(s: String?, label: String? = null): Target? {
        if (s.isNullOrBlank()) return null
        coord(s)?.let { return Target.Point(it.first, it.second, label) }
        return Target.Address(s)
    }

    private fun coord(s: String?): Pair<Double, Double>? {
        if (s == null) return null
        val parts = s.split(",").map { it.trim().toDoubleOrNull() }
        if (parts.size < 2) return null
        val lat = parts[0] ?: return null
        val lon = parts[1] ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    // ── Address detection (for LinkifyText) ─────────────────────────────────

    /**
     * Street addresses in [text], as [start, end) ranges — on-device via the
     * system TextClassifier (the Android counterpart of NSDataDetector). Blocking:
     * call off the main thread. Empty when the classifier isn't available.
     */
    fun detectAddresses(context: Context, text: String): List<IntRange> {
        if (text.length < 8 || text.none { it.isDigit() }) return emptyList()
        // The system classifier is the better detector where it works, but it is
        // not always there: on a fresh device Android System Intelligence answered
        // "GenerateLinks did not find valid links" for a plain US street address
        // (measured on the 1.6.28 test emulator). The conservative pattern in
        // [AddressHeuristic] fills that gap; classifier ranges win on overlap.
        val fromClassifier = classifierAddresses(context, text)
        val fromPattern = AddressHeuristic.find(text)
            .filter { p -> fromClassifier.none { c -> p.first <= c.last && c.first <= p.last } }
        return (fromClassifier + fromPattern).sortedBy { it.first }
    }

    private fun classifierAddresses(context: Context, text: String): List<IntRange> {
        return runCatching {
            val tcm = context.getSystemService(android.view.textclassifier.TextClassificationManager::class.java)
                ?: return emptyList()
            val classifier = tcm.textClassifier
            val max = classifier.maxGenerateLinksTextLength
            if (text.length > max) return emptyList()
            val request = android.view.textclassifier.TextLinks.Request.Builder(text)
                .setEntityConfig(
                    android.view.textclassifier.TextClassifier.EntityConfig.createWithExplicitEntityList(
                        listOf(android.view.textclassifier.TextClassifier.TYPE_ADDRESS)
                    )
                )
                .build()
            classifier.generateLinks(request).links
                .filter { link ->
                    link.entityCount > 0 &&
                        link.getEntity(0) == android.view.textclassifier.TextClassifier.TYPE_ADDRESS &&
                        link.getConfidenceScore(android.view.textclassifier.TextClassifier.TYPE_ADDRESS) >= 0.5f
                }
                .map { it.start until it.end }
        }.getOrDefault(emptyList())
    }
}
