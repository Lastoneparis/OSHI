package com.oshi.messenger.ui.onboarding

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * __ONBOARDING_2027__ The onboarding icon set.
 *
 * Drawn for OSHI, here, as plain geometry — no third-party icon font or asset,
 * so there is no licence to track. One system for every glyph:
 *
 * - 24 × 24 viewport, 2 unit safe margin;
 * - ONE stroke weight ([STROKE]), round caps and round joins, no fills — so a
 *   glyph next to another always reads as the same family;
 * - corners rounded (radius ≥ 1.5) to match the O-bubble mascot's soft shapes;
 * - drawn in black and tinted by the caller (a solid ink, or the brand gradient
 *   through [gradientIcon]), so the same vector works in Light, Dark and OLED.
 *
 * Directional glyphs ([ArrowForward], [ArrowBack]) are `autoMirror`: in Arabic,
 * Persian and Hebrew "Continue" points left, which the Material arrows the old
 * screens used did not do.
 */
object OnboardingIcons {

    const val STROKE = 1.8f

    private fun icon(name: String, vararg paths: String, autoMirror: Boolean = false): ImageVector =
        ImageVector.Builder(
            name = "ob27.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = autoMirror,
        ).apply {
            for (d in paths) {
                addPath(
                    pathData = addPathNodes(d),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = STROKE,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    /** Padlock: end-to-end encryption. */
    val Lock: ImageVector = icon(
        "lock",
        "M8 10.5V8a4 4 0 0 1 8 0v2.5",
        "M7 10.5h10a2 2 0 0 1 2 2v6a2.5 2.5 0 0 1-2.5 2.5h-9A2.5 2.5 0 0 1 5 18.5v-6a2 2 0 0 1 2-2z",
        "M12 14.6v2.4",
    )

    /** Key: an identity / keys made on this phone. */
    val Key: ImageVector = icon(
        "key",
        "M11.5 15a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0z",
        "M10.5 12.5L19 4",
        "M16.2 6.8l2.3 2.3",
        "M14 9l1.6 1.6",
    )

    /** Shield with a tick. */
    val ShieldCheck: ImageVector = icon(
        "shield_check",
        "M12 3.2l6.8 2.9v5.3c0 4.4-2.9 7.9-6.8 9.4-3.9-1.5-6.8-5-6.8-9.4V6.1z",
        "M9.2 12.1l2 2 3.7-3.8",
    )

    /** Shield with an exclamation mark: "no independent audit yet". */
    val ShieldAlert: ImageVector = icon(
        "shield_alert",
        "M12 3.2l6.8 2.9v5.3c0 4.4-2.9 7.9-6.8 9.4-3.9-1.5-6.8-5-6.8-9.4V6.1z",
        "M12 8.2v4.2",
        "M12 15.6v0.01",
    )

    /** Radio mast with waves: LoRa. */
    val Antenna: ImageVector = icon(
        "antenna",
        "M12 11.2V21",
        "M8.8 21h6.4",
        "M13.5 9.5a1.5 1.5 0 1 1-3 0a1.5 1.5 0 1 1 3 0z",
        "M8.9 6.3a4.4 4.4 0 0 0 0 6.4",
        "M15.1 6.3a4.4 4.4 0 0 1 0 6.4",
        "M6.1 3.6a8.3 8.3 0 0 0 0 11.8",
        "M17.9 3.6a8.3 8.3 0 0 1 0 11.8",
    )

    /** Three linked nodes: the phone-to-phone mesh. */
    val Mesh: ImageVector = icon(
        "mesh",
        "M14 6a2 2 0 1 1-4 0a2 2 0 1 1 4 0z",
        "M8 17.5a2 2 0 1 1-4 0a2 2 0 1 1 4 0z",
        "M20 17.5a2 2 0 1 1-4 0a2 2 0 1 1 4 0z",
        "M11 7.9l-4 7.7",
        "M13 7.9l4 7.7",
        "M8.1 17.5h7.8",
    )

    /** A phone, struck through: no phone number. */
    val NoPhone: ImageVector = icon(
        "no_phone",
        "M8.2 2.8h7.6a1.9 1.9 0 0 1 1.9 1.9v14.6a1.9 1.9 0 0 1-1.9 1.9H8.2a1.9 1.9 0 0 1-1.9-1.9V4.7a1.9 1.9 0 0 1 1.9-1.9z",
        "M10.8 5.6h2.4",
        "M3.6 3.6l16.8 16.8",
    )

    /** Eye, struck through: not watched / not collected. */
    val EyeOff: ImageVector = icon(
        "eye_off",
        "M3 12s3.4-6 9-6 9 6 9 6-3.4 6-9 6-9-6-9-6z",
        "M14.5 12a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0z",
        "M4.2 4.2l15.6 15.6",
    )

    /** Two people: groups. */
    val People: ImageVector = icon(
        "people",
        "M12.2 8.6a3.2 3.2 0 1 1-6.4 0a3.2 3.2 0 1 1 6.4 0z",
        "M3.4 19.6c0-3.1 2.5-5.4 5.6-5.4s5.6 2.3 5.6 5.4",
        "M15.2 5.6a2.9 2.9 0 0 1 0 5.8",
        "M17.2 14.4c2.1.6 3.4 2.6 3.4 5.2",
    )

    /** QR code. */
    val Qr: ImageVector = icon(
        "qr",
        "M5.5 4h2.5a1.5 1.5 0 0 1 1.5 1.5V8a1.5 1.5 0 0 1-1.5 1.5H5.5A1.5 1.5 0 0 1 4 8V5.5A1.5 1.5 0 0 1 5.5 4z",
        "M16 4h2.5A1.5 1.5 0 0 1 20 5.5V8a1.5 1.5 0 0 1-1.5 1.5H16A1.5 1.5 0 0 1 14.5 8V5.5A1.5 1.5 0 0 1 16 4z",
        "M5.5 14.5h2.5a1.5 1.5 0 0 1 1.5 1.5v2.5A1.5 1.5 0 0 1 8 20H5.5A1.5 1.5 0 0 1 4 18.5V16a1.5 1.5 0 0 1 1.5-1.5z",
        "M14.5 14.5h2.2v2.2",
        "M20 14.5v0.01",
        "M17.6 20H20v-2.2",
        "M14.5 19.8v0.01",
    )

    /** Chain link: invite link. */
    val Link: ImageVector = icon(
        "link",
        "M10.2 13.8a3.9 3.9 0 0 0 5.5 0l3-3a3.9 3.9 0 0 0-5.5-5.5l-1 1",
        "M13.8 10.2a3.9 3.9 0 0 0-5.5 0l-3 3a3.9 3.9 0 0 0 5.5 5.5l1-1",
    )

    /** Bell: notifications. */
    val Bell: ImageVector = icon(
        "bell",
        "M6.2 16.6V11a5.8 5.8 0 0 1 11.6 0v5.6l1.6 1.6H4.6z",
        "M10 20.6a2 2 0 0 0 4 0",
    )

    /** Bell, struck through. */
    val BellOff: ImageVector = icon(
        "bell_off",
        "M6.2 16.6V11a5.8 5.8 0 0 1 11.6 0v5.6l1.6 1.6H4.6z",
        "M10 20.6a2 2 0 0 0 4 0",
        "M4 4l16 16",
    )

    /** Ticked circle: "please do". */
    val CheckCircle: ImageVector = icon(
        "check_circle",
        "M20.5 12a8.5 8.5 0 1 1-17 0a8.5 8.5 0 1 1 17 0z",
        "M8.3 12.3l2.5 2.5 4.9-5",
    )

    /** Crossed circle: "never". */
    val CrossCircle: ImageVector = icon(
        "cross_circle",
        "M20.5 12a8.5 8.5 0 1 1-17 0a8.5 8.5 0 1 1 17 0z",
        "M9.3 9.3l5.4 5.4",
        "M14.7 9.3l-5.4 5.4",
    )

    /** Cloud: encrypted fallback storage. */
    val Cloud: ImageVector = icon(
        "cloud",
        "M7.2 18.5h9.9a4 4 0 0 0 .5-7.97A5.6 5.6 0 0 0 6.8 9.6a4.45 4.45 0 0 0 .4 8.9z",
    )

    /** Globe: the internet, and the language picker. */
    val Globe: ImageVector = icon(
        "globe",
        "M20.5 12a8.5 8.5 0 1 1-17 0a8.5 8.5 0 1 1 17 0z",
        "M12 3.5c2.3 2.4 3.5 5.2 3.5 8.5s-1.2 6.1-3.5 8.5c-2.3-2.4-3.5-5.2-3.5-8.5s1.2-6.1 3.5-8.5z",
        "M4 9h16",
        "M4 15h16",
    )

    /** Phone with a circular arrow: recover the identity already on this phone. */
    val PhoneRestore: ImageVector = icon(
        "phone_restore",
        "M8.2 2.8h7.6a1.9 1.9 0 0 1 1.9 1.9v14.6a1.9 1.9 0 0 1-1.9 1.9H8.2a1.9 1.9 0 0 1-1.9-1.9V4.7a1.9 1.9 0 0 1 1.9-1.9z",
        "M15.2 12a3.2 3.2 0 1 1-1.1-2.4",
        "M14.6 7.6l-0.4 2.1 2.1 0.3",
    )

    /** Counter-clockwise arrow round a clock: restore from a backup key. */
    val History: ImageVector = icon(
        "history",
        "M4.3 12.9A7.8 7.8 0 1 0 6.6 6.4",
        "M4.6 4.4v3.4H8",
        "M12 8.2v4l2.6 1.6",
    )

    /** Clipboard: paste a recovery key. */
    val Clipboard: ImageVector = icon(
        "clipboard",
        "M8.5 4.6H7.2a2 2 0 0 0-2 2v12.2a2 2 0 0 0 2 2h9.6a2 2 0 0 0 2-2V6.6a2 2 0 0 0-2-2h-1.3",
        "M10 3h4a1 1 0 0 1 1 1v1.2a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
        "M9 12h6",
        "M9 15.6h4",
    )

    /** Forward arrow (mirrored right-to-left). */
    val ArrowForward: ImageVector = icon("arrow_forward", "M5 12h14", "M13 6l6 6-6 6", autoMirror = true)

    /** Back arrow (mirrored right-to-left). */
    val ArrowBack: ImageVector = icon("arrow_back", "M19 12H5", "M11 6l-6 6 6 6", autoMirror = true)

    /** Chevron for an expandable row. */
    val ChevronDown: ImageVector = icon("chevron_down", "M6.5 9.5l5.5 5.5 5.5-5.5")

    /** Every glyph, for the consistency test and the review sheet. */
    val ALL: List<ImageVector> by lazy {
        listOf(
            Lock, Key, ShieldCheck, ShieldAlert, Antenna, Mesh, NoPhone, EyeOff, People, Qr, Link,
            Bell, BellOff, CheckCircle, CrossCircle, Cloud, Globe, PhoneRestore, History, Clipboard,
            ArrowForward, ArrowBack, ChevronDown,
        )
    }
}
