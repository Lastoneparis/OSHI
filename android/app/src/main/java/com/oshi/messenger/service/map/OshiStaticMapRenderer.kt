package com.oshi.messenger.service.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.config.Configuration
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * OshiStaticMapRenderer — composites a small "static map" image around a
 * coordinate out of OSM slippy tiles, cache-first.
 *
 * ══ WHY THIS REPLACES AN <img> ══
 *
 * Every location preview in the app (share sheet + every received location
 * bubble) was an `AsyncImage` of
 *
 *     https://staticmap.openstreetmap.de/staticmap.php?center=<LAT>,<LON>&…&markers=<LAT>,<LON>,red-pushpin
 *
 * `host staticmap.openstreetmap.de` → **NXDOMAIN**. The domain does not resolve,
 * so every preview has been a blank grey box. And had it resolved it would have
 * been worse than blank: a privacy-first messenger would have been handing a
 * contact's exact shared coordinates, in the query string, to an uncontrolled
 * third party — on a screen the user reaches by opening a message.
 *
 * ══ PRIVACY PROPERTIES OF THE REPLACEMENT ══
 *
 *   • No new host. Tiles come from the local cache first — the per-region cache
 *     `Maps/regions/<id>/tiles/{z}/{x}/{y}.png` and osmdroid's
 *     `Mapnik/{z}/{x}/{y}.png.tile` — exactly the two locations
 *     [OshiOfflineTileProvider] already reads. A user with the region downloaded
 *     renders **fully offline** and emits no request at all.
 *   • On a cache miss it falls back to `https://oshi-messenger.com/tiles/{z}/{x}/{y}.png`,
 *     OSHI's own first-party proxy, which is already the tile source the offline
 *     map stack downloads from (`OfflineMapManager.OSM_TILE_URL`). NOT
 *     `tile.openstreetmap.org`, and NOT any static-map service.
 *   • What the proxy can observe is a tile index, never a coordinate: at z=15 a
 *     tile spans ~1.2 km, and the request carries no marker, no centre point and
 *     no query string. The exact point stays on the device — the pin is drawn
 *     locally, on top.
 *   • Honours the app's offline-only preference: with it on, nothing leaves the
 *     device and a miss simply renders as a blank tile.
 *
 * The tile math is pure Kotlin so it is covered by JVM unit tests.
 */
object OshiStaticMapRenderer {
    private const val TAG = "OshiStaticMapRenderer"
    private const val TILE_SIZE = 256

    /** First-party tile proxy — the same origin OfflineMapManager downloads from. */
    private const val TILE_BASE_URL = "https://oshi-messenger.com/tiles"

    /**
     * The one compliant, contactable User-Agent, shared with the interactive map
     * and the region downloader.
     *
     * This used to be a private literal, `"OSHI-Messenger/1.0 (Android; static
     * map preview)"` — which names the app but carries **no way to contact the
     * operator**. A UA with no contact URL is exactly what got the previous tile
     * requests answered with the "not following the tile usage policy"
     * placeholder, and it made the preview renderer the one map component that
     * did not match iOS `OfflineFirstTileOverlay.swift:168`. It also sent no
     * `Referer`, which the same iOS block sets at `:169`.
     */
    private val USER_AGENT = OshiTileSource.USER_AGENT

    /**
     * Legacy fixed preview zoom. Retained because it names the value the dead
     * `staticmap.openstreetmap.de` URL requested, but it is no longer what the
     * renderer uses by default — see [DEFAULT_SPAN_METERS].
     */
    const val DEFAULT_ZOOM = 15

    /**
     * Metres across the preview's **width**, replacing the fixed [DEFAULT_ZOOM].
     *
     * iOS does not pin a zoom level; it pins a distance. The bubble preview uses
     * an 800 m span (`OSHI/LocationSharingView.swift:630`) and the share sheet
     * 700 m (`:517`) — described in-source as "the scale at which a street still
     * carries its name".
     *
     * Android was rendering at a hard-coded z=15 regardless of screen size or
     * latitude. At latitude 48.9 that is
     * `156543.03 * cos(48.9) / 2^15` ≈ **3.15 m/px**, so the 280 dp box on a 3×
     * device (840 px) covered ≈ **2.6 km** — about **3.5× wider** than iOS. The
     * user saw a neighbourhood where iOS shows a street.
     *
     * 800 m is used for both call sites: `OshiMapPreview` is one composable
     * shared by the sheet and the bubble with nothing to distinguish them, and
     * 800 m is within 14 % of iOS's sheet value while fixing the 3.5× error.
     */
    const val DEFAULT_SPAN_METERS = 800.0

    /**
     * Metres per pixel at the equator for a 256 px tile at z=0 — the Web
     * Mercator ground-resolution constant, `2 * PI * 6378137 / 256`.
     */
    private const val EQUATOR_METERS_PER_PIXEL_Z0 = 156_543.03392804097

    /**
     * The integer zoom whose ground resolution puts [spanMeters] across
     * [widthPx] at latitude [lat].
     *
     * `res(z) = EQUATOR_METERS_PER_PIXEL_Z0 * cos(lat) / 2^z`, and we want
     * `widthPx * res(z) == spanMeters`, so
     * `z = log2(EQUATOR_METERS_PER_PIXEL_Z0 * cos(lat) * widthPx / spanMeters)`.
     *
     * Rounded (not floored): flooring biases every preview one zoom level too
     * far out, which is the direction of the bug being fixed. Clamped to the
     * zooms for which raster tiles physically exist.
     *
     * Degenerate inputs fall back to [DEFAULT_ZOOM] rather than throwing —
     * a preview must never take a chat screen down.
     */
    fun zoomForSpan(lat: Double, spanMeters: Double, widthPx: Int): Int {
        if (widthPx <= 0 || spanMeters <= 0.0 || !spanMeters.isFinite() || !lat.isFinite()) {
            return DEFAULT_ZOOM
        }
        val cosLat = cos(Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878)))
        // At the Mercator limit cos(lat) is ~0.0864, never 0, but guard anyway:
        // ln(0) would hand back -Infinity and coerceIn cannot repair a NaN.
        if (cosLat <= 0.0) return DEFAULT_ZOOM
        val scale = EQUATOR_METERS_PER_PIXEL_Z0 * cosLat * widthPx / spanMeters
        if (scale <= 0.0 || !scale.isFinite()) return DEFAULT_ZOOM
        val z = ln(scale) / ln(2.0)
        if (!z.isFinite()) return DEFAULT_ZOOM
        return Math.round(z).toInt().coerceIn(OshiTileSource.MIN_ZOOM, OshiTileSource.MAX_ZOOM)
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", OshiTileSource.REFERER)
                        .build()
                )
            }
            .build()
    }

    // ───────────────────────────────────────────────────────────────────
    // Slippy-map math (pure — unit tested)
    // ───────────────────────────────────────────────────────────────────

    /** Fractional tile X for a longitude at zoom [z]. */
    fun tileXf(lon: Double, z: Int): Double {
        val n = 1 shl z
        return (lon + 180.0) / 360.0 * n
    }

    /** Fractional tile Y for a latitude at zoom [z] (Web Mercator). */
    fun tileYf(lat: Double, z: Int): Double {
        val n = 1 shl z
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val rad = Math.toRadians(clamped)
        val y = (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n
        // Clamping the LATITUDE is not enough to clamp the RESULT: at the Web
        // Mercator limit the expression evaluates to -1.99e-10 (north) and
        // 32.0000000002 at z=5 (south). `floor(-1.99e-10)` is -1, i.e. a tile row
        // that does not exist, which would leave an unpainted strip at the top of
        // any preview taken near the poles.
        return y.coerceIn(0.0, n.toDouble())
    }

    /** Inverse of [tileXf] — west edge longitude of tile column [x]. */
    fun tileXToLon(x: Double, z: Int): Double {
        val n = 1 shl z
        return x / n * 360.0 - 180.0
    }

    /** Inverse of [tileYf] — north edge latitude of tile row [y]. */
    fun tileYToLat(y: Double, z: Int): Double {
        val n = 1 shl z
        return Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / n))))
    }

    /**
     * The tiles that must be blitted to fill a [widthPx] × [heightPx] viewport
     * centred on (lat, lon), plus where to put them.
     *
     * `offsetX/offsetY` is the pixel position of tile (x0, y0)'s top-left corner
     * relative to the viewport origin, and is normally negative.
     */
    data class TilePlan(
        val zoom: Int,
        val x0: Int,
        val y0: Int,
        val cols: Int,
        val rows: Int,
        val offsetX: Int,
        val offsetY: Int,
        /** Where the coordinate itself lands inside the viewport, for the pin. */
        val pinX: Int,
        val pinY: Int,
        /**
         * World-pixel coordinate of the viewport's top-left corner at [zoom].
         *
         * Kept so ANY coordinate — not just the centre one — can be projected
         * into the viewport by [projectInto]. Without it the plan could only
         * place the point it was built around, which is exactly why the bubble
         * could never draw a second marker.
         */
        val worldLeft: Double = 0.0,
        val worldTop: Double = 0.0,
    )

    /**
     * A camera: an integer zoom and the point the viewport is centred on.
     *
     * The bubble needs a camera it did not previously have. iOS frames the
     * chat bubble two ways — one point at an 800 m span
     * (`OSHI/LocationSharingView.swift:630`, `followMovingPoint()` at `:1520`)
     * and TWO points fitted together (`fitBothLocations()` at `:1469`) — and
     * Android had neither, because the preview was a bitmap rendered around a
     * single hard coordinate.
     */
    data class Framing(
        val zoom: Int,
        val centerLat: Double,
        val centerLon: Double,
    )

    fun plan(lat: Double, lon: Double, zoom: Int, widthPx: Int, heightPx: Int): TilePlan {
        val z = zoom.coerceIn(0, 19)
        val cxf = tileXf(lon, z)
        val cyf = tileYf(lat, z)

        // World-pixel coordinate of the centre point.
        val cx = cxf * TILE_SIZE
        val cy = cyf * TILE_SIZE

        // World-pixel coordinate of the viewport's top-left corner.
        val left = cx - widthPx / 2.0
        val top = cy - heightPx / 2.0

        val x0 = floor(left / TILE_SIZE).toInt()
        val y0 = floor(top / TILE_SIZE).toInt()
        val offsetX = (x0 * TILE_SIZE - left).toInt()
        val offsetY = (y0 * TILE_SIZE - top).toInt()

        val cols = ((widthPx - offsetX) + TILE_SIZE - 1) / TILE_SIZE
        val rows = ((heightPx - offsetY) + TILE_SIZE - 1) / TILE_SIZE

        return TilePlan(
            zoom = z,
            x0 = x0,
            y0 = y0,
            cols = cols.coerceAtLeast(1),
            rows = rows.coerceAtLeast(1),
            offsetX = offsetX,
            offsetY = offsetY,
            pinX = (cx - left).toInt(),
            pinY = (cy - top).toInt(),
            worldLeft = left,
            worldTop = top,
        )
    }

    /**
     * Where (lat, lon) lands inside the viewport described by [plan], in pixels
     * from its top-left corner.
     *
     * May legitimately return a point OUTSIDE the viewport — the caller decides
     * whether to draw it. Clamping here would silently pile every off-screen
     * marker onto the edge, which reads as "they are right there" and is the
     * opposite of the honesty this screen needs.
     */
    fun projectInto(plan: TilePlan, lat: Double, lon: Double): Pair<Int, Int> {
        val x = tileXf(lon, plan.zoom) * TILE_SIZE - plan.worldLeft
        val y = tileYf(lat, plan.zoom) * TILE_SIZE - plan.worldTop
        return Math.round(x).toInt() to Math.round(y).toInt()
    }

    /**
     * iOS pads a two-point fit to 1.5× the raw separation
     * (`OSHI/LocationSharingView.swift:1487-1490`) so neither marker sits on the
     * frame edge. Same number here, for the same reason.
     */
    const val TWO_POINT_PADDING = 1.5

    /**
     * Floor on how tight a two-point fit may zoom, in metres across the width.
     *
     * iOS expresses the same floor in degrees — `max(0.01, delta * 1.5)`, i.e.
     * about 1.1 km of latitude — so that two coordinates a few metres apart do
     * not produce a camera zoomed onto a single doorway. 800 m is this app's
     * one-point span ([DEFAULT_SPAN_METERS]); using it keeps "both of us here"
     * and "just this point" at the same scale instead of jumping between them.
     */
    const val MIN_TWO_POINT_SPAN_METERS = DEFAULT_SPAN_METERS

    /**
     * The camera that shows BOTH points, padded and floored.
     *
     * Computed in world pixels at a reference zoom rather than in degrees:
     * longitude degrees are not the same distance at every latitude, and a
     * degree-based fit that ignores that puts one of the two markers off-frame
     * as soon as the pair is far from the equator. Working in Mercator pixels
     * and dividing by powers of two is exact for the projection the tiles are
     * actually in.
     */
    fun fitTwoPoints(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
        widthPx: Int,
        heightPx: Int,
        padding: Double = TWO_POINT_PADDING,
    ): Framing {
        val safeW = widthPx.coerceAtLeast(1)
        val safeH = heightPx.coerceAtLeast(1)
        if (!lat1.isFinite() || !lon1.isFinite() || !lat2.isFinite() || !lon2.isFinite()) {
            return Framing(DEFAULT_ZOOM, lat1, lon1)
        }

        // A reference zoom high enough that the pixel separation of two points a
        // metre apart is still meaningful, and low enough that a whole-world
        // separation stays inside Double's exact-integer range.
        val zRef = 22
        val x1 = tileXf(lon1, zRef) * TILE_SIZE
        val x2 = tileXf(lon2, zRef) * TILE_SIZE
        val y1 = tileYf(lat1, zRef) * TILE_SIZE
        val y2 = tileYf(lat2, zRef) * TILE_SIZE

        val centerLon = tileXToLon((x1 + x2) / 2.0 / TILE_SIZE, zRef)
        val centerLat = tileYToLat((y1 + y2) / 2.0 / TILE_SIZE, zRef)

        val dx = kotlin.math.abs(x1 - x2) * padding
        val dy = kotlin.math.abs(y1 - y2) * padding

        // The tightest zoom at which the padded separation still fits each axis.
        // FLOOR, not round: rounding up by half a level is enough to push a
        // marker off the edge, and an over-wide frame still shows both.
        val zx = if (dx <= 0.0) Double.MAX_VALUE else zRef + ln(safeW / dx) / ln(2.0)
        val zy = if (dy <= 0.0) Double.MAX_VALUE else zRef + ln(safeH / dy) / ln(2.0)
        val fit = minOf(zx, zy)

        // Never tighter than the one-point span, so a near-coincident pair does
        // not slam the camera to street-door zoom.
        val ceiling = zoomForSpan(centerLat, MIN_TWO_POINT_SPAN_METERS, safeW)
        val z = if (!fit.isFinite()) {
            ceiling
        } else {
            floor(fit).toInt().coerceAtMost(ceiling)
        }

        return Framing(
            zoom = z.coerceIn(OshiTileSource.MIN_ZOOM, OshiTileSource.MAX_ZOOM),
            centerLat = centerLat,
            centerLon = centerLon,
        )
    }

    /**
     * The camera for a single point — the framing the bubble uses when it has
     * only one thing to show.
     */
    fun fitOnePoint(lat: Double, lon: Double, widthPx: Int, spanMeters: Double = DEFAULT_SPAN_METERS): Framing =
        Framing(zoomForSpan(lat, spanMeters, widthPx), lat, lon)

    /**
     * The two characters drawn in an avatar disc when there is no photo.
     *
     * iOS takes `String(contactName.prefix(2)).uppercased()`
     * (`OSHI/OfflineMapView.swift:2467`) — the first two CHARACTERS of the name,
     * not the initials of two words. Matched deliberately, so the same contact
     * shows the same two letters on both platforms.
     *
     * Swift's `prefix(2)` counts grapheme clusters; Kotlin's `take(2)` counts
     * UTF-16 units and would cut an emoji or an astral-plane script in half and
     * render two replacement boxes. Code points are the cheap approximation that
     * gets every real name and every emoji right.
     */
    fun initialsFor(name: String?): String {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return "?"
        val out = StringBuilder()
        var i = 0
        var taken = 0
        while (i < trimmed.length && taken < 2) {
            val cp = trimmed.codePointAt(i)
            val n = Character.charCount(cp)
            out.append(trimmed, i, i + n)
            i += n
            taken++
        }
        return out.toString().uppercase()
    }

    // ───────────────────────────────────────────────────────────────────
    // Rendering
    // ───────────────────────────────────────────────────────────────────

    /**
     * Render the preview. Returns null when nothing could be drawn (no cached
     * tiles and either offline-only or no connectivity) so the caller can show an
     * honest placeholder instead of a silent grey box.
     */
    suspend fun render(
        context: Context,
        lat: Double,
        lon: Double,
        widthPx: Int,
        heightPx: Int,
        /**
         * Metres across [widthPx]. Defaults to iOS's preview span; the zoom is
         * derived from it and the device's real pixel width, so the same
         * preview covers the same ground on a phone and on a tablet.
         */
        spanMeters: Double = DEFAULT_SPAN_METERS,
        /** Explicit zoom override. `null` derives it from [spanMeters]. */
        zoom: Int? = null,
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (widthPx <= 0 || heightPx <= 0) return@withContext null
        val z = zoom ?: zoomForSpan(lat, spanMeters, widthPx)
        val p = plan(lat, lon, z, widthPx, heightPx)
        val out = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // Neutral "paper" so a partially-cached preview does not read as broken.
        canvas.drawColor(0xFFE8E6E1.toInt())

        val n = 1 shl p.zoom
        var drew = 0
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (dy in 0 until p.rows) {
            for (dx in 0 until p.cols) {
                val tx = p.x0 + dx
                val ty = p.y0 + dy
                if (ty < 0 || ty >= n) continue
                val wrappedX = ((tx % n) + n) % n  // longitude wraps, latitude does not
                val bmp = loadTile(context, p.zoom, wrappedX, ty) ?: continue
                val dest = Rect(
                    p.offsetX + dx * TILE_SIZE,
                    p.offsetY + dy * TILE_SIZE,
                    p.offsetX + (dx + 1) * TILE_SIZE,
                    p.offsetY + (dy + 1) * TILE_SIZE,
                )
                canvas.drawBitmap(bmp, null, dest, paint)
                bmp.recycle()
                drew++
            }
        }
        if (drew == 0) {
            out.recycle()
            return@withContext null
        }
        out
    }

    // ───────────────────────────────────────────────────────────────────
    // Who is who on the preview
    // ───────────────────────────────────────────────────────────────────

    /**
     * One thing to draw on the preview.
     *
     * The owner's complaint was literally "I don't understand, when sharing
     * position with someone, where I am and where the other person is". The
     * Android bubble drew a single generic red Material pin over a bitmap and
     * nothing else — no second point, so there was nothing to tell apart. iOS
     * draws two annotations with deliberately different identities
     * (`OSHI/LocationSharingView.swift:696-800`):
     *
     *   • the SHARED point in brand purple, carrying a face — the contact's
     *     profile photo when you are looking at their share, your own when you
     *     are looking at yours;
     *   • YOUR point in blue, a plain dot with "Me" written in it.
     *
     * Colour AND shape AND label all differ, so the pair is readable in
     * greyscale, at a glance, and for a colour-blind reader.
     */
    data class MarkerSpec(
        val lat: Double,
        val lon: Double,
        val style: MarkerStyle,
        /** Decoded profile photo, or null to fall back to [initials]. */
        val avatar: Bitmap? = null,
        /** Two characters from the contact's name — see [initialsFor]. */
        val initials: String? = null,
        /** Short word drawn inside a [MarkerStyle.ME] dot; iOS writes "Me" there. */
        val label: String? = null,
        /** Draws the live ring iOS shows around an active share. */
        val isLive: Boolean = false,
    )

    enum class MarkerStyle {
        /** The shared coordinate: brand halo + avatar disc. */
        SHARED,

        /** The viewer's own position: blue halo + blue dot + label. */
        ME,
    }

    /** OSHI brand purple, `Color(0xFF5616EC)` — the same value the UI layer uses. */
    private val BRAND = 0xFF5616EC.toInt()

    /** iOS uses system blue for "my dot"; this is its sRGB value. */
    private val ME_BLUE = 0xFF007AFF.toInt()

    // Mirrors OSHIGreen (ui/theme/Theme.kt). Kept as a literal because this is a
    // Paint colour in a service and importing the Compose token here would pull UI
    // types into the renderer. It was #4CAF50 while the chat's live-location card
    // used the brand green, so the same "live" state rendered in two shades.
    private val LIVE_GREEN = 0xFF22C55E.toInt()

    /**
     * Render the preview with markers composited in.
     *
     * The markers are drawn HERE, into the bitmap, rather than overlaid as
     * composables: the preview is one image whose camera the caller does not
     * know (the zoom is derived from the pixel width), so only this function can
     * place a second coordinate correctly. The previous overlay could only ever
     * sit at the exact centre — which is why it was always the shared point and
     * never the viewer.
     *
     * Returns null on the same terms as [render]: nothing drawn, nothing to show.
     */
    suspend fun renderWithMarkers(
        context: Context,
        framing: Framing,
        widthPx: Int,
        heightPx: Int,
        markers: List<MarkerSpec>,
        /** Device density, so the markers are the same physical size everywhere. */
        density: Float = 1f,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val base = render(
            context = context,
            lat = framing.centerLat,
            lon = framing.centerLon,
            widthPx = widthPx,
            heightPx = heightPx,
            zoom = framing.zoom,
        ) ?: return@withContext null

        val p = plan(framing.centerLat, framing.centerLon, framing.zoom, widthPx, heightPx)
        val canvas = Canvas(base)
        // Draw the viewer's own dot first so the shared point — the subject of
        // the message — is never hidden underneath it when the two overlap.
        markers.sortedBy { if (it.style == MarkerStyle.ME) 0 else 1 }.forEach { m ->
            if (!m.lat.isFinite() || !m.lon.isFinite()) return@forEach
            val (x, y) = projectInto(p, m.lat, m.lon)
            when (m.style) {
                MarkerStyle.ME -> drawMeDot(canvas, x.toFloat(), y.toFloat(), m.label, density)
                MarkerStyle.SHARED -> drawSharedMarker(canvas, x.toFloat(), y.toFloat(), m, density)
            }
        }
        base
    }

    /**
     * The shared point: brand halo, then either the profile photo or an
     * initials disc, ringed in white.
     *
     * Sizes follow the iOS bubble annotation — 44 pt halo, 26 pt face, 2 pt white
     * ring (`OSHI/LocationSharingView.swift:700-742`) — and the initials
     * treatment follows the iOS map pin, a brand→blue gradient with the first two
     * characters of the name in bold white (`OSHI/OfflineMapView.swift:2455-2480`).
     */
    private fun drawSharedMarker(canvas: Canvas, x: Float, y: Float, m: MarkerSpec, d: Float) {
        val halo = 22f * d          // 44 dp diameter
        val face = 13f * d          // 26 dp diameter
        val ring = 2f * d
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.style = Paint.Style.FILL
        paint.color = withAlpha(BRAND, 0.30f)
        canvas.drawCircle(x, y, halo, paint)

        if (m.isLive) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * d
            paint.color = LIVE_GREEN
            canvas.drawCircle(x, y, halo - d, paint)
            paint.style = Paint.Style.FILL
        }

        val avatar = m.avatar
        val initials = m.initials
        if (avatar != null || !initials.isNullOrEmpty()) {
            // White ring under the face, exactly as iOS strokes the photo.
            paint.color = android.graphics.Color.WHITE
            canvas.drawCircle(x, y, face + ring, paint)

            val save = canvas.save()
            val clip = android.graphics.Path().apply {
                addCircle(x, y, face, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(clip)
            val dest = RectF(x - face, y - face, x + face, y + face)
            if (avatar != null) {
                canvas.drawBitmap(avatar, null, dest, Paint(Paint.FILTER_BITMAP_FLAG))
            } else {
                val gradient = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        dest.left, dest.top, dest.right, dest.bottom,
                        BRAND, ME_BLUE, Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(dest, gradient)
            }
            canvas.restoreToCount(save)

            if (avatar == null && !initials.isNullOrEmpty()) {
                drawCenteredText(canvas, initials, x, y, face * 0.76f, android.graphics.Color.WHITE)
            }
        } else {
            // No identity to show (the share sheet's own preview): iOS draws a
            // plain brand dot with a white ring — `LocationMapPreview` at
            // `OSHI/LocationSharingView.swift:538-546`.
            paint.color = android.graphics.Color.WHITE
            canvas.drawCircle(x, y, 8f * d + ring, paint)
            paint.color = BRAND
            canvas.drawCircle(x, y, 8f * d, paint)
        }
    }

    /**
     * The viewer's own dot: blue halo, blue core, white ring, and the word iOS
     * writes inside it.
     *
     * The label is passed in already localized — this object has no `Context`
     * for resources and must not acquire one, or the tile math stops being unit
     * testable.
     */
    private fun drawMeDot(canvas: Canvas, x: Float, y: Float, label: String?, d: Float) {
        val halo = 20f * d
        val core = 9f * d
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL

        paint.color = withAlpha(ME_BLUE, 0.30f)
        canvas.drawCircle(x, y, halo, paint)

        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(x, y, core + 2f * d, paint)
        paint.color = ME_BLUE
        canvas.drawCircle(x, y, core, paint)

        if (!label.isNullOrEmpty()) {
            drawCenteredText(canvas, label, x, y, core * 0.95f, android.graphics.Color.WHITE)
        }
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val fm = paint.fontMetrics
        canvas.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, paint)
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or (((alpha.coerceIn(0f, 1f) * 255f).toInt() and 0xFF) shl 24)

    /** Cache-first tile load. Network only as a fallback, and never in offline-only mode. */
    private fun loadTile(context: Context, z: Int, x: Int, y: Int): Bitmap? {
        cachedTileFiles(context, z, x, y).forEach { f ->
            if (f.exists() && f.length() > 0 && !PoisonedTilePurge.isPoisonedFile(f)) {
                try {
                    BitmapFactory.decodeFile(f.absolutePath)?.let { return it }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decode cached tile ${f.path}: ${e.message}")
                }
            }
        }
        if (OshiOfflineTileProvider.isOfflineOnly(context)) return null
        return fetchTile(context, z, x, y)
    }

    /**
     * Both on-disk tile locations, in the order [OshiOfflineTileProvider] reads
     * them: per-region download dirs first, then osmdroid's own cache.
     */
    private fun cachedTileFiles(context: Context, z: Int, x: Int, y: Int): List<File> {
        val files = mutableListOf<File>()
        val regionsRoot = File(context.filesDir, "Maps/regions")
        regionsRoot.listFiles()?.forEach { regionDir ->
            if (regionDir.isDirectory) {
                files.add(File(regionDir, "tiles/$z/$x/$y.png"))
            }
        }
        files.add(File(osmDroidTileCacheDir(context), "Mapnik/$z/$x/$y.png.tile"))
        return files
    }

    private fun osmDroidTileCacheDir(context: Context): File {
        val cfg = Configuration.getInstance()
        val basePath = cfg.osmdroidBasePath ?: File(context.filesDir, "osmdroid")
        return cfg.osmdroidTileCache ?: File(basePath, "tiles")
    }

    private fun fetchTile(context: Context, z: Int, x: Int, y: Int): Bitmap? {
        val url = "$TILE_BASE_URL/$z/$x/$y.png"
        return try {
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                if (bytes.isEmpty()) return null
                if (PoisonedTilePurge.isPoisoned(bytes)) return null
                // Populate osmdroid's cache so the next preview — and the map
                // itself — is served offline.
                try {
                    val dest = File(osmDroidTileCacheDir(context), "Mapnik/$z/$x/$y.png.tile")
                    dest.parentFile?.mkdirs()
                    FileOutputStream(dest).use { it.write(bytes) }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to cache tile $z/$x/$y: ${e.message}")
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tile fetch failed for $z/$x/$y: ${e.message}")
            null
        }
    }
}
