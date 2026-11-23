package com.oshi.messenger

import androidx.compose.ui.graphics.Color
import com.oshi.messenger.ui.theme.AvatarIdentity
import com.oshi.messenger.ui.theme.AvatarNameColors
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Guards `AvatarIdentity` — the default-avatar palette (v2, 2026-11-17).
 *
 * The source of truth is `docs/avatar-palette.json`, shared with iOS
 * (`AvatarIdentityTests.swift`) and desktop (`AvatarParityTest.kt`). Everything
 * here is checked against the FILE, not against the Kotlin literals:
 *  - the literals equal the spec (Android cannot drift alone);
 *  - every identifier -> slot vector (the hash/normalisation cannot drift alone);
 *  - every ink clears 3:1 on start, end and highlighted centre (re-measured);
 *  - no two entries are perceptually close (OKLab distance between midpoints).
 */
class AvatarIdentityTest {

    private val spec: JSONObject by lazy {
        val file = listOf("../../docs/avatar-palette.json", "../docs/avatar-palette.json", "docs/avatar-palette.json")
            .map { java.io.File(it) }
            .firstOrNull { it.exists() }
        assertTrue("docs/avatar-palette.json not found from ${java.io.File(".").absolutePath}", file != null)
        JSONObject(file!!.readText())
    }

    private fun hex(s: String): Long = s.removePrefix("#").toLong(16)
    private fun color(s: String): Color = Color(0xFF000000L or hex(s))

    private data class Rgb(val r: Double, val g: Double, val b: Double)
    private fun rgb(s: String): Rgb {
        val h = hex(s)
        return Rgb(((h shr 16) and 0xFF) / 255.0, ((h shr 8) and 0xFF) / 255.0, (h and 0xFF) / 255.0)
    }
    private fun lin(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    private fun lum(c: Rgb) = 0.2126 * lin(c.r) + 0.7152 * lin(c.g) + 0.0722 * lin(c.b)
    private fun contrast(a: Rgb, b: Rgb): Double {
        val la = lum(a); val lb = lum(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
    private fun mix(a: Rgb, b: Rgb, t: Double) = Rgb(a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t)
    private fun oklab(c: Rgb): Triple<Double, Double, Double> {
        val r = lin(c.r); val g = lin(c.g); val b = lin(c.b)
        val l = cbrt(0.4122214708 * r + 0.5363031944 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return Triple(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    private fun palette() = spec.getJSONArray("palette").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    // ---- the literals are the spec -------------------------------------

    @Test
    fun `palette literals match the shared spec`() {
        val p = palette()
        assertEquals(16, AvatarIdentity.entries.size)
        assertEquals(p.size, AvatarIdentity.entries.size)
        for (e in p) {
            val i = e.getInt("index")
            val mine = AvatarIdentity.entries[i]
            assertEquals("entry $i start", color(e.getString("start")), mine.start)
            assertEquals("entry $i end", color(e.getString("end")), mine.end)
            assertEquals("entry $i ink", color(e.getString("ink")), mine.ink)
            assertEquals("nameLight $i", color(e.getString("nameLight")), AvatarNameColors.light[i])
            assertEquals("nameDark $i", color(e.getString("nameDark")), AvatarNameColors.dark[i])
        }
        val h = spec.getJSONObject("highlight")
        assertEquals(h.getDouble("centerX"), AvatarIdentity.HIGHLIGHT_X.toDouble(), 1e-6)
        assertEquals(h.getDouble("centerY"), AvatarIdentity.HIGHLIGHT_Y.toDouble(), 1e-6)
        assertEquals(h.getDouble("radius"), AvatarIdentity.HIGHLIGHT_RADIUS.toDouble(), 1e-6)
        assertEquals(h.getDouble("alphaAtCenter"), AvatarIdentity.HIGHLIGHT_ALPHA.toDouble(), 1e-6)
    }

    // ---- the cross-platform contract -----------------------------------

    /**
     * THE test: the same vectors run on iOS and desktop. If someone swaps the
     * SHA-256 rule for `hashCode()` — stable on the JVM and therefore fine-looking
     * in isolation — this names the identifier that moved.
     */
    @Test
    fun `shared vectors give the spec slots`() {
        val v = spec.getJSONArray("vectors")
        assertTrue(v.length() >= 10)
        for (i in 0 until v.length()) {
            val o = v.getJSONObject(i)
            assertEquals("slot for '${o.getString("id")}' diverged from the shared spec",
                o.getInt("slot"), AvatarIdentity.slot(o.getString("id")))
        }
    }

    @Test
    fun `a group id is the same slot in either case and a public key is never case folded`() {
        val upper = java.util.UUID.randomUUID().toString().uppercase()
        assertEquals(AvatarIdentity.slot(upper), AvatarIdentity.slot(upper.lowercase()))
        assertEquals(AvatarIdentity.groupEntry(upper), AvatarIdentity.groupEntry(upper.lowercase()))
        val key = "BHq2Y7qkzL0m1yQe0kq9XW3sV1d6c7m3pPq9yZ4b8tE="
        assertEquals(key, AvatarIdentity.normalizedKey(key))
    }

    // ---- legibility ----------------------------------------------------

    @Test
    fun `every ink clears large text contrast on its gradient`() {
        val h = spec.getJSONObject("highlight")
        val minimum = spec.getJSONObject("contrast").getDouble("minimum")
        val centreAlpha = h.getDouble("alphaAtCenter") *
            max(0.0, 1 - hypot(0.5 - h.getDouble("centerX"), 0.5 - h.getDouble("centerY")) / h.getDouble("radius"))
        for (e in palette()) {
            val s = rgb(e.getString("start")); val t = rgb(e.getString("end")); val ink = rgb(e.getString("ink"))
            val centre = mix(mix(s, t, 0.5), Rgb(1.0, 1.0, 1.0), centreAlpha)
            for ((label, bg) in listOf("start" to s, "end" to t, "centre" to centre)) {
                val r = contrast(ink, bg)
                assertTrue("entry ${e.getInt("index")} (${e.getString("name")}) ink is ${"%.2f".format(r)}:1 on its $label",
                    r >= minimum)
            }
        }
    }

    // ---- variety, the reported bug -------------------------------------

    /** OKLab distance between midpoints: hue alone mis-measures pastels and the neutral. */
    @Test
    fun `no two entries are perceptually close`() {
        val mids = palette().map { oklab(mix(rgb(it.getString("start")), rgb(it.getString("end")), 0.5)) }
        for (i in mids.indices) for (j in i + 1 until mids.size) {
            val (a1, b1, c1) = mids[i]; val (a2, b2, c2) = mids[j]
            val d = sqrt((a1 - a2).pow(2) + (b1 - b2).pow(2) + (c1 - c2).pow(2))
            assertTrue("entries $i and $j are only ΔE_ok ${"%.3f".format(d)} apart", d > 0.05)
        }
    }

    // ---- stability -----------------------------------------------------

    @Test
    fun `index is deterministic and in range`() {
        for (key in listOf("anatole", "", "🙂🙂🙂", "x".repeat(10_000), "\u0000")) {
            val first = AvatarIdentity.slot(key)
            assertEquals("not stable for '$key'", first, AvatarIdentity.slot(key))
            assertTrue("index $first out of range", first in AvatarIdentity.entries.indices)
        }
    }

    @Test
    fun `a zero size palette does not divide by zero`() {
        assertEquals(0, AvatarIdentity.stableIndex("anything", 0))
    }

    @Test
    fun `keys spread across the whole palette`() {
        val used = (0 until 500).map { AvatarIdentity.slot("contact-$it-key") }.toSet()
        assertEquals("500 keys reached only ${used.size} of ${AvatarIdentity.entries.size} colours",
            AvatarIdentity.entries.size, used.size)
    }
}
