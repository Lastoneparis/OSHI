package com.oshi.messenger.ui.screens

import android.provider.Settings
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.repeatCount
import com.oshi.messenger.ui.media.BubbleMedia
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R

/**
 * Launch screen — a 1:1 port of the shipping iOS `LaunchScreenView`
 * (OSHI/LaunchScreenView.swift:17-190). Every colour, size, opacity and animation
 * duration below is taken from that file so both platforms open the same way.
 */

// OSHI/LaunchScreenView.swift:12-15
private val OshiPurpleAccent = Color(0xFF5616EC)                  // Theme.brand
private val OshiPurpleGlow = Color(red = 0.5f, green = 0.2f, blue = 0.8f)

// OSHI/LaunchScreenView.swift:27-31 — deep purple-black diagonal gradient
private val LaunchBgTop = Color(red = 0.08f, green = 0.02f, blue = 0.15f)
private val LaunchBgMid = Color(red = 0.12f, green = 0.04f, blue = 0.22f)
private val LaunchBgBottom = Color(red = 0.06f, green = 0.01f, blue = 0.12f)

/**
 * [handedOver] (cold start only): false while the OS splash, or the copy of it
 * the system lays over our window on API 31+, may still be drawn on top. Until
 * then this screen draws exactly what that splash draws — the logo on the flat
 * splash ground ([splashGroundBlack]: black for an OLED splash, else
 * `launch_background`), no wordmark, glow, grid or dots — and the intro clock
 * does not run. Afterwards: logo hold → crossfade to the mascot, with the rest
 * of the screen fading in alongside → wave. See MainActivity.launchHandedOver.
 */
@Composable
fun LaunchScreen(
    modifier: Modifier = Modifier,
    playIntro: Boolean = true,
    handedOver: Boolean = true,
    splashGroundBlack: Boolean = false,
) {
    val context = LocalContext.current
    // iOS honours `accessibilityReduceMotion` (swift:21, 176). The Android analogue is
    // the system animator duration scale being switched off.
    val reduceMotion = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }

    val transition = rememberInfiniteTransition(label = "launch")
    // swift:180 — continuous pulse, 1.5 s, autoreverses, to 1.08
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (reduceMotion) 1f else 1.08f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "pulse"
    )
    // swift:152-158 — three dots, 0.4 s, autoreverses, staggered 0.15 s
    val dotScales = (0..2).map { i ->
        transition.animateFloat(
            initialValue = if (reduceMotion) 1f else 0.5f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(400, delayMillis = i * 150),
                RepeatMode.Reverse
            ),
            label = "dot$i"
        )
    }

    // __OLED_BLACK_2026_11_16__ In OLED black the deep-violet ramp becomes flat
    // black; the glow, rings, mark and wordmark (the brand) are unchanged. This
    // is also the auto-lock cover, which an OLED user sees far more often than
    // the cold start.
    val oled = com.oshi.messenger.ui.theme.isOledScheme()
    val ground = if (oled) listOf(Color.Black, Color.Black) else listOf(LaunchBgTop, LaunchBgMid, LaunchBgBottom)

    // __SPLASH_HANDOVER_2026_11_16__ The intro clock. `face` 0 → 1 is the
    // logo → mascot crossfade; the screen's own ground, grid, glow, wordmark
    // and dots fade in with it ([chrome]), from the splash's flat ground.
    val face = remember { Animatable(if (playIntro) 0f else 1f) }
    var waving by remember { mutableStateOf(false) }
    LaunchedEffect(playIntro, handedOver) {
        if (!playIntro || !handedOver) return@LaunchedEffect
        kotlinx.coroutines.delay(LaunchMetrics.HOLD_LOGO_MS.toLong())
        face.animateTo(1f, tween(LaunchMetrics.CROSSFADE_MS, easing = FastOutSlowInEasing))
        if (!reduceMotion && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) waving = true
    }
    val chrome = if (playIntro) face.value else 1f
    val splashGround = if (splashGroundBlack) Color.Black else colorResource(R.color.launch_background)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(splashGround)
            .semantics { contentDescription = "OSHI" },   // swift:166-168
        contentAlignment = Alignment.Center
    ) {
        // swift:37-56 — subtle grid: 12 horizontal lines @8%, 8 vertical @6%
        // (drawn over the launch gradient, both fading in over the splash ground)
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = chrome }.background(
                Brush.linearGradient(
                    ground,
                    start = Offset.Zero,
                    end = Offset.Infinite
                )
            ).drawBehind {
                val h = size.height
                val w = size.width
                for (i in 0 until 12) {
                    val y = i * (h / 12f)
                    drawRect(
                        color = OshiPurpleAccent.copy(alpha = 0.08f),
                        topLeft = Offset(0f, y),
                        size = Size(w, 0.5.dp.toPx())
                    )
                }
                for (i in 0 until 8) {
                    val x = i * (w / 8f)
                    drawRect(
                        color = OshiPurpleAccent.copy(alpha = 0.06f),
                        topLeft = Offset(x, 0f),
                        size = Size(0.5.dp.toPx(), h)
                    )
                }
            }
        )

        // __O_BUBBLE_MASCOT_2026_11_16__ The O-bubble mascot sits at the
        // EXACT window centre, at the size the Android 12+ system splash draws
        // `splash_mascot_logo` (see that drawable), so the hand-over from the
        // OS splash to this screen does not move a pixel. The rotating arcs
        // around the old 116dp logo are gone: they would cut through the
        // mascot's hands; the soft glow stays behind it.
        Box(
            Modifier
                .size(LaunchMetrics.GLOW_DP.dp)
                .graphicsLayer { alpha = chrome }
                .scale(pulseScale)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            0.33f to OshiPurpleGlow.copy(alpha = 0.3f),
                            0.66f to OshiPurpleAccent.copy(alpha = 0.1f),
                            1.0f to Color.Transparent,
                            center = center,
                            radius = size.minDimension / 2f
                        )
                    )
                }
        )
        MascotIntro(
            face = if (playIntro) face.value else 0f,
            waving = playIntro && waving,
            modifier = Modifier.size(LaunchMetrics.MASCOT_DP.dp),
        )

        // swift:123-141 — wordmark + localized subtitle, under the mascot
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .offset(y = LaunchMetrics.WORDMARK_OFFSET_DP.dp)
                .graphicsLayer { alpha = chrome }
        ) {
            Text(
                text = "OSHI",
                style = TextStyle(
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp,                       // swift:137 .tracking(6)
                    brush = Brush.verticalGradient(
                        listOf(Color.White, Color(red = 0.8f, green = 0.7f, blue = 1.0f))
                    )
                )
            )
            Text(
                text = stringResource(R.string.launch_subtitle),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp,                       // swift:140 .tracking(2)
                    color = OshiPurpleGlow.copy(alpha = 0.9f)
                )
            )
        }

        // swift:146-162 — three staggered loading dots
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 50.dp)
                .graphicsLayer { alpha = chrome }
        ) {
            dotScales.forEach { s ->
                Box(
                    Modifier
                        .size(6.dp)
                        .scale(s.value)
                        .drawBehind { drawCircle(color = OshiPurpleAccent) }
                )
            }
        }
    }
}

/** Pure numbers of the launch layout, shared with `splash_mascot_logo.xml`. */
object LaunchMetrics {
    /**
     * Side of the 1024 mascot canvas on screen. MUST equal the canvas size in
     * `res/drawable/splash_mascot_logo.xml`: 288dp × (1 − 2 × 15.28%) = 200dp.
     */
    const val MASCOT_DP = 200

    /** Soft glow behind the mascot (was 240dp behind the 116dp logo). */
    const val GLOW_DP = 300

    /** Wordmark centre below the window centre: clears the tail (≈ +77dp). */
    const val WORDMARK_OFFSET_DP = 134

    /**
     * The logo holds this long first, counted from the hand-over (the OS
     * splash and its copy gone, MainActivity.launchHandedOver), so the logo
     * is seen to settle before anything changes.
     */
    const val HOLD_LOGO_MS = 120

    /** Logo → neutral mascot crossfade. */
    const val CROSSFADE_MS = 160

    /** `assets/OshiMascot/launch/wave_launch.webp`: 18 frames × 34 ms, played once. */
    const val WAVE_MS = 18 * 34

    /**
     * Whole intro. Fits inside MainActivity's LAUNCH_MIN_VISIBLE_MS (900 ms),
     * so the mascot never holds the app back: the launch screen leaves at the
     * same moment it always did.
     */
    const val INTRO_MS = HOLD_LOGO_MS + CROSSFADE_MS + WAVE_MS
}

/**
 * __O_BUBBLE_MASCOT_2026_11_16__ The plain OSHI logo (what the OS splash just
 * showed, same pixels) crossfades into the neutral O-bubble mascot ([face]
 * 0 → 1, driven by LaunchScreen once the splash is handed over), which then
 * waves once (`wave_launch.webp`: the pack's wave_512 frames 04–38, every
 * other one, 34 ms each — a quick "hi") and comes to rest on the neutral pose.
 *
 * The neutral still is drawn OVER the opaque logo (whose ring it fully
 * covers) rather than the two dissolving into each other, so the ring never
 * dips in opacity mid-fade. Reduced motion: the crossfade only, no wave. API
 * < 28 (no AnimatedImageDrawable): the same. [play] false (the auto-lock
 * cover): the logo, still.
 *
 * The wave is decoded by Coil off the main thread while the neutral still
 * (identical to its first frame) is on screen, so a slow decode shows a calm
 * mascot, never an empty box, and nothing waits for it.
 */
@Composable
private fun MascotIntro(face: Float, waving: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val neutral = painterResource(R.drawable.mascot_launch_neutral)
    val px = with(LocalDensity.current) { LaunchMetrics.MASCOT_DP.dp.roundToPx() }
    Box(modifier) {
        if (face < 1f) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.mascot_launch_logo),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (waving) {
            val request = remember(px) {
                ImageRequest.Builder(context)
                    .data("file:///android_asset/OshiMascot/launch/wave_launch.webp")
                    .size(px)
                    .repeatCount(0)
                    .crossfade(false)
                    .listener(
                        onSuccess = { _, r -> android.util.Log.d("LaunchScreen", "wave ready: ${r.drawable.javaClass.simpleName}") },
                        onError = { _, r -> android.util.Log.w("LaunchScreen", "wave decode failed, staying on the neutral still", r.throwable) },
                    )
                    .build()
            }
            AsyncImage(
                model = request,
                imageLoader = BubbleMedia.imageLoader(context),
                placeholder = neutral,
                error = neutral,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        } else if (face > 0f) {
            androidx.compose.foundation.Image(
                painter = neutral,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = face }
            )
        }
    }
}
