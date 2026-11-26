package com.oshi.messenger.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.oshi.messenger.R
import com.oshi.messenger.ui.components.isSystemReduceMotion
import com.oshi.messenger.ui.locale.AppLanguage
import com.oshi.messenger.ui.onboarding.GlassIconTile
import com.oshi.messenger.ui.onboarding.GradientIcon
import com.oshi.messenger.ui.onboarding.AuroraBackground
import com.oshi.messenger.ui.onboarding.MascotOrb
import com.oshi.messenger.ui.onboarding.ObAccent
import com.oshi.messenger.ui.onboarding.ObColors
import com.oshi.messenger.ui.onboarding.ObGlassCard
import com.oshi.messenger.ui.onboarding.ObPrimaryButton
import com.oshi.messenger.ui.onboarding.OnboardingFlow
import com.oshi.messenger.ui.onboarding.OnboardingIcons
import com.oshi.messenger.ui.onboarding.OnboardingPage
import com.oshi.messenger.ui.onboarding.PagerDots
import com.oshi.messenger.ui.onboarding.accent
import com.oshi.messenger.ui.onboarding.mascot
import com.oshi.messenger.ui.onboarding.obGlassPane
import com.oshi.messenger.ui.onboarding.rememberIdleLoop
import com.oshi.messenger.ui.onboarding.rememberObColors
import com.oshi.messenger.ui.theme.OshiAlertDialog
import com.oshi.messenger.ui.theme.brandInk
import com.oshi.messenger.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * __ONBOARDING_2027__ First launch, from the first frame to the chat list.
 *
 * Steps and their order live in [OnboardingFlow] (pure, unit-tested). This file
 * is the presentation:
 *
 * - a swipeable [HorizontalPager] over the four slides and the rules page, with
 *   parallax (the mascot orb lags the page, the icon tiles lead it, the aurora
 *   drifts), elongating progress dots, Skip (to the rules, never past them) and
 *   a language chip on the first slide;
 * - then the notification ask (Android 13+, only if not granted) and the
 *   identity step, which slide in after the pager;
 * - system Back walks back through every step (the old screen had no
 *   BackHandler: Back on page seven left the app and lost the progress).
 *
 * Unchanged on purpose: identity creation (`createIdentity`), restore from a
 * recovery key, recover-from-this-device, the creation-error dialog, and
 * navigation on completion (both `identityCreationComplete` and `isOnboarded`).
 * The window's FLAG_SECURE / privacy cover are applied by MainActivity and are
 * not touched here. Deep links that arrive during onboarding are held by
 * MainActivity until `isOnboarded` (see `OnboardingBehaviourTest`).
 *
 * Shape rules kept from the old screen: every page's content scrolls in a
 * weighted region with its buttons pinned outside (`.weight(1f)` +
 * `verticalScroll(rememberScrollState())`), so a landscape phone or a foldable's
 * cover screen never strands the primary action off the bottom edge.
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val isCreatingIdentity by viewModel.isCreatingIdentity.collectAsState()
    val identityCreationComplete by viewModel.identityCreationComplete.collectAsState()
    val creationError by viewModel.creationError.collectAsState()
    val isOnboarded by viewModel.isOnboarded.collectAsState()

    // Navigate after identity creation OR after a successful recovery-key restore
    LaunchedEffect(identityCreationComplete) {
        if (identityCreationComplete) {
            viewModel.resetIdentityCreationState()
            onComplete()
        }
    }
    LaunchedEffect(isOnboarded) {
        if (isOnboarded) {
            onComplete()
        }
    }

    OnboardingContent(
        isCreatingIdentity = isCreatingIdentity,
        creationError = creationError,
        onCreateIdentity = { displayName -> viewModel.createIdentity(displayName) },
        onImportRecoveryKey = { recoveryKey -> viewModel.importRecoveryKey(recoveryKey) },
        onRecoverFromDevice = { viewModel.recoverIdentityFromDevice() },
    )
}

/**
 * __ONBOARDING_POLISH_2026_11_24__ The whole flow without the view model, so the design
 * shots (`OnboardingDesignShotsTest`) render exactly what ships. [OnboardingScreen] is the
 * only production caller and passes the same state and actions it always used;
 * [initialStep] / [initialName] exist for the shots only.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OnboardingContent(
    isCreatingIdentity: Boolean,
    creationError: String?,
    onCreateIdentity: (String) -> Unit,
    onImportRecoveryKey: (String) -> Boolean,
    onRecoverFromDevice: () -> Boolean,
    initialStep: OnboardingPage? = null,
    initialName: String = "",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = rememberObColors()
    val reduceMotion = remember { isSystemReduceMotion(context) }
    val animate = !reduceMotion

    var displayName by rememberSaveable { mutableStateOf(initialName) }
    // null = in the pager (tour); otherwise the step after it.
    var setupStepName by rememberSaveable {
        mutableStateOf(initialStep?.takeIf { OnboardingFlow.tourIndex(it) < 0 }?.name)
    }
    val setupStep = setupStepName?.let { name -> OnboardingPage.entries.firstOrNull { it.name == name } }
    val askNotifications = rememberSaveable {
        OnboardingFlow.needsNotificationStep(
            Build.VERSION.SDK_INT,
            Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var showRestoreDialog by remember { mutableStateOf(false) }
    var restoreError by remember { mutableStateOf<String?>(null) }
    var deviceRecoveryMessage by remember { mutableStateOf<String?>(null) }
    var showLanguagePicker by remember { mutableStateOf(false) }

    if (showRestoreDialog) {
        RestoreIdentityDialog(
            onDismiss = {
                showRestoreDialog = false
                restoreError = null
            },
            onRestore = { recoveryKey ->
                val ok = onImportRecoveryKey(recoveryKey)
                if (!ok) {
                    restoreError = context.getString(R.string.onboarding_invalid_recovery_key)
                    false
                } else {
                    showRestoreDialog = false
                    true
                }
            },
            error = restoreError
        )
    }
    if (showLanguagePicker) {
        AppLanguagePickerDialog(onDismiss = { showLanguagePicker = false })
    }

    // A failed identity creation must be dismissable (it used to be a modal
    // with an empty OK button on the app's first screen). The dialog shows only
    // while the CURRENT error has not been acknowledged.
    var acknowledgedCreationError by remember { mutableStateOf<String?>(null) }
    val liveCreationError = creationError?.takeIf { it != acknowledgedCreationError }
    if (liveCreationError != null) {
        OshiAlertDialog(
            onDismissRequest = { acknowledgedCreationError = liveCreationError },
            title = { Text(stringResource(R.string.error)) },
            text = { Text(stringResource(R.string.onboarding_error_create, liveCreationError)) },
            confirmButton = {
                TextButton(onClick = { acknowledgedCreationError = liveCreationError }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    val pagerState = rememberPagerState(
        initialPage = initialStep?.let { OnboardingFlow.tourIndex(it) }?.takeIf { it >= 0 } ?: 0,
        pageCount = { OnboardingFlow.TOUR.size }
    )
    val current: OnboardingPage = setupStep ?: OnboardingFlow.TOUR[pagerState.currentPage]

    fun goTo(page: OnboardingPage) {
        val idx = OnboardingFlow.tourIndex(page)
        if (idx >= 0) {
            setupStepName = null
            scope.launch {
                if (animate) pagerState.animateScrollToPage(idx) else pagerState.scrollToPage(idx)
            }
        } else {
            setupStepName = page.name
        }
    }

    fun createIdentity() {
        if (displayName.isNotBlank() && !isCreatingIdentity) {
            onCreateIdentity(displayName)
            // Navigation happens via LaunchedEffect when identityCreationComplete becomes true
        }
    }

    fun advance() {
        val next = OnboardingFlow.next(current, askNotifications)
        if (next != null) goTo(next) else createIdentity()
    }

    val previous = OnboardingFlow.previous(current, askNotifications)
    BackHandler(enabled = previous != null && !isCreatingIdentity) {
        previous?.let { goTo(it) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> goTo(OnboardingPage.IDENTITY) }

    // Accent follows the pager position continuously, then the setup step.
    val tourPosition = { pagerState.currentPage + pagerState.currentPageOffsetFraction }
    val tourAccent: Color = run {
        val p = tourPosition().coerceIn(0f, (OnboardingFlow.TOUR.size - 1).toFloat())
        val i = floor(p).toInt()
        val a = OnboardingFlow.TOUR[i].accent().glow
        val b = OnboardingFlow.TOUR[(i + 1).coerceAtMost(OnboardingFlow.TOUR.lastIndex)].accent().glow
        lerp(a, b, p - i)
    }
    val accentTarget = setupStep?.accent()?.glow ?: tourAccent
    val accent by animateColorAsState(
        targetValue = accentTarget,
        animationSpec = tween(if (animate && setupStep != null) 400 else 0),
        label = "ob-accent"
    )

    val idle = rememberIdleLoop(animate)
    val windowShort = LocalConfigurationHeightDp() < 560

    Box(Modifier.fillMaxSize()) {
        AuroraBackground(
            accent = accent,
            shift = { if (setupStep == null) tourPosition() else OnboardingFlow.TOUR.size.toFloat() },
            colors = colors
        )

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            val allSteps = OnboardingFlow.steps(askNotifications)
            OnboardingTopBar(
                current = current,
                // __ONBOARDING_POLISH_2026_11_24__ the pager has its dots; the setup
                // steps after it get a step meter so the end is in sight.
                meter = setupStep?.let { allSteps.indexOf(it) to allSteps.size },
                showBack = previous != null,
                onBack = { previous?.let { goTo(it) } },
                onSkip = { goTo(OnboardingFlow.SKIP_TARGET) },
                onLanguage = { showLanguagePicker = true },
                colors = colors,
            )

            AnimatedContent(
                targetState = setupStep,
                transitionSpec = { stepTransition(initialState, targetState, animate) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "ob-step"
            ) { step ->
                when (step) {
                    null -> TourPager(
                        pagerState = pagerState,
                        colors = colors,
                        idle = idle,
                        animate = animate,
                        short = windowShort,
                    )
                    OnboardingPage.NOTIFICATIONS -> NotificationsStep(colors, idle, animate, windowShort)
                    else -> IdentityStep(
                        displayName = displayName,
                        onDisplayNameChange = { displayName = it },
                        isCreating = isCreatingIdentity,
                        onDone = { createIdentity() },
                        onRestoreClick = { showRestoreDialog = true },
                        onRecoverFromDeviceClick = {
                            val ok = onRecoverFromDevice()
                            deviceRecoveryMessage =
                                if (ok) context.getString(R.string.onboarding_recover_device_ok)
                                else context.getString(R.string.onboarding_recover_device_none)
                        },
                        deviceRecoveryMessage = deviceRecoveryMessage,
                        colors = colors,
                        idle = idle,
                        animate = animate,
                        short = windowShort,
                    )
                }
            }

            // Pinned actions: never scroll away (landscape / cover screens).
            Column(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 24.dp)
                    .padding(top = if (windowShort) 4.dp else 8.dp, bottom = if (windowShort) 8.dp else 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (setupStep) {
                    null -> {
                        PagerDots(
                            count = OnboardingFlow.TOUR.size,
                            position = tourPosition,
                            accent = lerp(accent, brandInk(), 0.35f),
                            colors = colors,
                            label = stringResource(
                                R.string.ob27_page_of,
                                pagerState.currentPage + 1,
                                OnboardingFlow.TOUR.size
                            ),
                            modifier = Modifier.padding(bottom = if (windowShort) 8.dp else 20.dp)
                        )
                        val onRules = current == OnboardingPage.RULES
                        ObPrimaryButton(
                            text = stringResource(if (onRules) R.string.ob27_agree else R.string.ob27_continue),
                            onClick = { advance() },
                        )
                    }
                    OnboardingPage.NOTIFICATIONS -> {
                        ObPrimaryButton(
                            text = stringResource(R.string.ob27_notif_allow),
                            trailing = null,
                            onClick = {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    goTo(OnboardingPage.IDENTITY)
                                }
                            },
                        )
                        TextButton(
                            onClick = { goTo(OnboardingPage.IDENTITY) },
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .heightIn(min = 48.dp)
                        ) {
                            Text(
                                stringResource(R.string.ob27_not_now),
                                color = colors.body,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    else -> {
                        ObPrimaryButton(
                            text = stringResource(
                                if (isCreatingIdentity) R.string.onboarding_creating else R.string.onboarding_create_identity
                            ),
                            enabled = displayName.isNotBlank() && !isCreatingIdentity,
                            loading = isCreatingIdentity,
                            onClick = { createIdentity() },
                        )
                    }
                }
            }
        }
    }
}

/** Height of the window in dp (short = landscape phone, split screen, cover screen). */
@Composable
private fun LocalConfigurationHeightDp(): Int =
    androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp

/** Steps slide in from the end going forward, from the start going back; fade only under reduce motion. */
private fun stepTransition(from: OnboardingPage?, to: OnboardingPage?, animate: Boolean): ContentTransform {
    if (!animate) return fadeIn(tween(0)) togetherWith fadeOut(tween(0))
    val fromOrd = (from ?: OnboardingPage.RULES).ordinal
    val toOrd = (to ?: OnboardingPage.RULES).ordinal
    val forward = toOrd >= fromOrd
    val dur = 380
    return (slideInHorizontally(tween(dur)) { w -> if (forward) w / 3 else -w / 3 } + fadeIn(tween(dur))) togetherWith
        (slideOutHorizontally(tween(dur)) { w -> if (forward) -w / 3 else w / 3 } + fadeOut(tween(dur / 2)))
}

@Composable
private fun OnboardingTopBar(
    current: OnboardingPage,
    meter: Pair<Int, Int>?,
    showBack: Boolean,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onLanguage: () -> Unit,
    colors: ObColors,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (current == OnboardingPage.PRIVATE) {
            LanguageChip(onClick = onLanguage, colors = colors)
        } else if (showBack) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    OnboardingIcons.ArrowBack,
                    contentDescription = stringResource(R.string.onboarding_back),
                    tint = colors.title
                )
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (meter != null && meter.first >= 0) {
                com.oshi.messenger.ui.onboarding.StepMeter(
                    index = meter.first,
                    count = meter.second,
                    colors = colors,
                    label = stringResource(R.string.ob27_page_of, meter.first + 1, meter.second),
                )
            }
        }
        if (current.isSlide) {
            // __ONBOARDING_POLISH_2026_11_24__ Skip as a glass pill with a chevron: it reads
            // as a control, not as stray text, and stays a 48 dp target.
            Row(
                Modifier
                    .padding(end = 8.dp)
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onSkip)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier
                        .obGlassPane(CircleShape, colors)
                        .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.ob27_skip),
                        color = colors.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        OnboardingIcons.ArrowForward,
                        contentDescription = null,
                        tint = colors.body,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/** "🌐 English": opens the app-language picker (same dialog as Settings › Language). */
@Composable
private fun LanguageChip(onClick: () -> Unit, colors: ObColors) {
    val context = LocalContext.current
    val name = remember {
        AppLanguage.currentName(context) ?: Locale.getDefault().let { l ->
            l.getDisplayLanguage(l).replaceFirstChar { it.titlecase(l) }
        }
    }
    val label = stringResource(R.string.settings_app_language)
    Row(
        Modifier
            .padding(start = 8.dp)
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$label: $name" }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier
                .obGlassPane(CircleShape, colors)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GradientIcon(OnboardingIcons.Globe, colors.iconGradient(ObAccent.Violet), 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(name, color = colors.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Slides
// ─────────────────────────────────────────────────────────────────────────

/** Title style: tight, balanced line breaks, sized down on narrow windows (long German / Russian words). */
@Composable
private fun titleStyle(maxWidth: Dp): TextStyle {
    val narrow = maxWidth < 380.dp
    return TextStyle(
        fontSize = if (narrow) 26.sp else 30.sp,
        lineHeight = if (narrow) 32.sp else 36.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
        textAlign = TextAlign.Center,
        lineBreak = LineBreak.Heading,
    )
}

private val bodyStyle = TextStyle(
    fontSize = 16.sp,
    lineHeight = 24.sp,
    textAlign = TextAlign.Center,
    lineBreak = LineBreak.Paragraph,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TourPager(
    pagerState: PagerState,
    colors: ObColors,
    idle: State<Float>,
    animate: Boolean,
    short: Boolean,
) {
    HorizontalPager(
        state = pagerState,
        beyondBoundsPageCount = 1,
        modifier = Modifier.fillMaxSize(),
    ) { index ->
        val page = OnboardingFlow.TOUR[index]
        // Signed distance of this page from the settled position, in pages.
        val offset = { (pagerState.currentPage - index) + pagerState.currentPageOffsetFraction }
        Box(Modifier.fillMaxSize().clipToBounds()) {
            if (page == OnboardingPage.RULES) {
                RulesPage(colors, idle, animate, offset, short)
            } else {
                SlidePage(page, colors, idle, animate, offset, short)
            }
        }
    }
}

@Composable
private fun SlidePage(
    page: OnboardingPage,
    colors: ObColors,
    idle: State<Float>,
    animate: Boolean,
    offset: () -> Float,
    short: Boolean,
) {
    val accent = page.accent()
    val dir = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxW = maxWidth
        val widthPx = with(density) { boxW.toPx() }
        // Sized off the HEIGHT first: on a 360 × 760 dp phone in German the text
        // block is ~300 dp, so the stage may take only about a third.
        val hero = minOf(maxWidth * 0.56f, maxHeight * 0.30f, 240.dp)
        val stage = hero * 1.42f
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Depth: the orb runs a little ahead of its page and the tiles further
            // ahead, so a neighbour's hero stays out of view at rest; each page
            // clips its own layers (see TourPager).
            Box(
                Modifier
                    .size(stage)
                    .graphicsLayer {
                        if (animate) {
                            val o = offset()
                            translationX = -o * widthPx * 0.25f * dir
                            val s = 1f - 0.12f * abs(o).coerceAtMost(1f)
                            scaleX = s; scaleY = s
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                MascotOrb(
                    mascot = page.mascot(),
                    accent = accent,
                    colors = colors,
                    size = hero,
                    idle = idle,
                    animate = animate,
                )
                SlideTiles(page, accent, colors, stage, hero, idle, animate, offset, widthPx * dir)
            }
            Spacer(Modifier.height(if (short) 12.dp else 28.dp))
            Column(
                Modifier
                    .widthIn(max = 460.dp)
                    .graphicsLayer {
                        if (animate) {
                            val o = offset()
                            alpha = (1f - abs(o) * 1.3f).coerceIn(0f, 1f)
                            translationX = o * widthPx * 0.12f * dir
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(page.titleRes()),
                    style = titleStyle(boxW),
                    color = colors.title,
                    modifier = Modifier.semantics { heading() }
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(page.bodyRes()),
                    style = bodyStyle,
                    color = colors.body,
                )
                if (page == OnboardingPage.OFFLINE) {
                    Spacer(Modifier.height(20.dp))
                    RangeStrip(colors)
                }
            }
        }
    }
}

private fun OnboardingPage.titleRes(): Int = when (this) {
    OnboardingPage.PRIVATE -> R.string.ob27_private_title
    OnboardingPage.OFFLINE -> R.string.ob27_offline_title
    OnboardingPage.ANONYMOUS -> R.string.ob27_anon_title
    OnboardingPage.TOGETHER -> R.string.ob27_together_title
    OnboardingPage.RULES -> R.string.ob27_rules_title
    OnboardingPage.NOTIFICATIONS -> R.string.ob27_notif_title
    OnboardingPage.IDENTITY -> R.string.ob27_identity_title
}

private fun OnboardingPage.bodyRes(): Int = when (this) {
    OnboardingPage.PRIVATE -> R.string.ob27_private_body
    OnboardingPage.OFFLINE -> R.string.ob27_offline_body
    OnboardingPage.ANONYMOUS -> R.string.ob27_anon_body
    OnboardingPage.TOGETHER -> R.string.ob27_together_body
    OnboardingPage.RULES -> R.string.ob27_rules_subtitle
    OnboardingPage.NOTIFICATIONS -> R.string.ob27_notif_body
    OnboardingPage.IDENTITY -> R.string.ob27_identity_body
}

/** Glass icon tiles round the orb: where each sits (fractions of the stage) and what it shows. */
private fun OnboardingPage.tiles(): List<Triple<androidx.compose.ui.graphics.vector.ImageVector, Float, Float>> = when (this) {
    OnboardingPage.PRIVATE -> listOf(
        Triple(OnboardingIcons.Lock, -0.36f, -0.26f),
        Triple(OnboardingIcons.Key, 0.37f, -0.06f),
        Triple(OnboardingIcons.ShieldCheck, -0.30f, 0.33f),
    )
    OnboardingPage.OFFLINE -> listOf(
        Triple(OnboardingIcons.Antenna, 0.35f, -0.28f),
        Triple(OnboardingIcons.Mesh, -0.37f, 0.08f),
    )
    OnboardingPage.ANONYMOUS -> listOf(
        Triple(OnboardingIcons.NoPhone, -0.35f, -0.22f),
        Triple(OnboardingIcons.EyeOff, 0.34f, 0.27f),
    )
    OnboardingPage.TOGETHER -> listOf(
        Triple(OnboardingIcons.People, 0.36f, -0.24f),
        Triple(OnboardingIcons.Qr, -0.37f, -0.02f),
        Triple(OnboardingIcons.Link, 0.28f, 0.34f),
    )
    else -> emptyList()
}

@Composable
private fun SlideTiles(
    page: OnboardingPage,
    accent: ObAccent,
    colors: ObColors,
    stage: Dp,
    hero: Dp,
    idle: State<Float>,
    animate: Boolean,
    offset: () -> Float,
    signedWidthPx: Float,
) {
    val tile = (hero * 0.26f).coerceIn(40.dp, 60.dp)
    page.tiles().forEachIndexed { i, (icon, fx, fy) ->
        GlassIconTile(
            icon = icon,
            accent = accent,
            colors = colors,
            size = tile,
            modifier = Modifier
                .offset(x = stage * fx, y = stage * fy)
                .graphicsLayer {
                    if (animate) {
                        val o = offset()
                        // Further ahead than the orb: the tiles read as the nearest layer.
                        translationX = -o * signedWidthPx * (0.20f + 0.08f * i)
                        val a = (idle.value + i * 0.29f) * 2f * Math.PI.toFloat()
                        translationY = kotlin.math.sin(a) * 4.dp.toPx()
                    }
                }
        )
    }
}

/** ~100 m · ~500 m · ~30 km, each with what it means. Distances are the existing (translated) strings. */
@Composable
private fun RangeStrip(colors: ObColors) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        RangePill(stringResource(R.string.onboarding_reach_range1_dist), stringResource(R.string.ob27_range_direct), colors, Modifier.weight(1f))
        RangePill(stringResource(R.string.onboarding_reach_range2_dist), stringResource(R.string.ob27_range_relayed), colors, Modifier.weight(1f))
        RangePill(stringResource(R.string.onboarding_reach_range3_dist), stringResource(R.string.ob27_range_lora), colors, Modifier.weight(1f))
    }
}

@Composable
private fun RangePill(distance: String, label: String, colors: ObColors, modifier: Modifier) {
    Column(
        modifier
            .widthIn(max = 120.dp)
            .obGlassPane(RoundedCornerShape(18.dp), colors, ObAccent.Teal.glow)
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(distance, color = ObAccent.Teal.ink(colors.dark), fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = colors.body, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 15.sp)
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Rules: the two safety disclosures (old GOOD_CONDUCT + DISCLAIMER pages)
// ─────────────────────────────────────────────────────────────────────────

/** Every string the two old safety pages showed — still shown here. Pinned by `OnboardingRedesignTest`. */
private val DO_RULES = listOf(
    R.string.onboarding_rule_respect,
    R.string.onboarding_rule_privacy,
    R.string.onboarding_rule_secure,
    R.string.onboarding_rule_backup,
)
private val DONT_RULES = listOf(
    R.string.onboarding_rule_no_spam,
    R.string.onboarding_rule_no_abuse,
    R.string.onboarding_rule_no_illegal,
)

private class Limit(val icon: androidx.compose.ui.graphics.vector.ImageVector, val accent: ObAccent, val title: Int, val body: List<Int>)

private val LIMITS = listOf(
    Limit(OnboardingIcons.ShieldAlert, ObAccent.Amber, R.string.onboarding_disclaimer_audit_title, listOf(R.string.onboarding_disclaimer_audit_desc)),
    Limit(OnboardingIcons.EyeOff, ObAccent.Violet, R.string.onboarding_disclaimer_anonymous_title, listOf(R.string.onboarding_disclaimer_anonymous_desc)),
    Limit(OnboardingIcons.BellOff, ObAccent.Violet, R.string.onboarding_disclaimer_notifications_title, listOf(R.string.onboarding_disclaimer_notifications_desc)),
    Limit(OnboardingIcons.Antenna, ObAccent.Teal, R.string.onboarding_disclaimer_mesh_title, listOf(R.string.onboarding_disclaimer_mesh_desc, R.string.onboarding_reach_disclaimer)),
    Limit(OnboardingIcons.Cloud, ObAccent.Blue, R.string.onboarding_disclaimer_cloud_title, listOf(R.string.onboarding_disclaimer_cloud_desc)),
    Limit(OnboardingIcons.Globe, ObAccent.Red, R.string.onboarding_disclaimer_critical_title, listOf(R.string.onboarding_disclaimer_critical_desc)),
)

@Composable
private fun RulesPage(
    colors: ObColors,
    idle: State<Float>,
    animate: Boolean,
    offset: () -> Float,
    short: Boolean,
) {
    val dir = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxW = maxWidth
        val widthPx = with(LocalDensity.current) { boxW.toPx() }
        Column(
            Modifier
                .fillMaxSize()
                .fadingEdges()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MascotOrb(
                    mascot = OnboardingPage.RULES.mascot(),
                    accent = ObAccent.Green,
                    colors = colors,
                    size = if (short) 72.dp else 104.dp,
                    idle = idle,
                    animate = animate,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .graphicsLayer { if (animate) translationX = -offset() * widthPx * 0.25f * dir }
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    stringResource(R.string.ob27_rules_title),
                    style = titleStyle(boxW),
                    color = colors.title,
                    modifier = Modifier.semantics { heading() }
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.ob27_rules_subtitle), style = bodyStyle, color = colors.body)
                Spacer(Modifier.height(20.dp))

                // House rules (Play UGC / Apple 1.2 disclosure) — always fully visible.
                ObGlassCard(colors) {
                    SectionLabel(stringResource(R.string.onboarding_conduct_title), colors)
                    Text(
                        stringResource(R.string.onboarding_conduct_subtitle),
                        color = colors.body,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
                    )
                    RuleGroupLabel(stringResource(R.string.ob27_rules_do), colors)
                    DO_RULES.forEach { RuleRow(true, stringResource(it), colors) }
                    RuleGroupLabel(stringResource(R.string.ob27_rules_dont), colors)
                    DONT_RULES.forEach { RuleRow(false, stringResource(it), colors) }
                }

                Spacer(Modifier.height(14.dp))

                // What OSHI can and can't do yet — every original card, expandable.
                ObGlassCard(colors) {
                    SectionLabel(stringResource(R.string.ob27_good_to_know), colors)
                    LIMITS.forEach { LimitRow(it, colors, animate) }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.onboarding_disclaimer_note),
                    color = ObAccent.Amber.ink(colors.dark),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** Soft top/bottom fade so long scrolling content does not end on a hard cut under the pinned button. */
private fun Modifier.fadingEdges(): Modifier = this
    .graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val fade = 20.dp.toPx() / size.height.coerceAtLeast(1f)
        drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to Color.Transparent, fade to Color.Black, 1f - fade to Color.Black, 1f to Color.Transparent
            ),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn
        )
    }

@Composable
private fun SectionLabel(text: String, colors: ObColors) {
    Text(
        text,
        color = colors.title,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 4.dp)
            .semantics { heading() }
    )
}

@Composable
private fun RuleGroupLabel(text: String, colors: ObColors) {
    Text(
        text.uppercase(Locale.getDefault()),
        color = colors.body,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 2.dp)
    )
}

@Composable
private fun RuleRow(allowed: Boolean, text: String, colors: ObColors) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 18.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val accent = if (allowed) ObAccent.Green else ObAccent.Red
        GradientIcon(
            if (allowed) OnboardingIcons.CheckCircle else OnboardingIcons.CrossCircle,
            listOf(accent.ink(colors.dark), accent.ink(colors.dark)),
            22.dp
        )
        Spacer(Modifier.width(14.dp))
        Text(text, color = colors.title, fontSize = 15.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun LimitRow(limit: Limit, colors: ObColors, animate: Boolean) {
    var open by rememberSaveable(limit.title) { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (open) 180f else 0f, tween(if (animate) 220 else 0), label = "chev")
    val showLabel = stringResource(R.string.ob27_show_details)
    val hideLabel = stringResource(R.string.ob27_hide_details)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (open) hideLabel else showLabel,
                    onClick = { open = !open }
                )
                .semantics { stateDescription = if (open) hideLabel else showLabel }
                .heightIn(min = 52.dp)
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .obGlassPane(RoundedCornerShape(12.dp), colors, limit.accent.glow),
                contentAlignment = Alignment.Center
            ) {
                GradientIcon(limit.icon, colors.iconGradient(limit.accent), 20.dp)
            }
            Spacer(Modifier.width(14.dp))
            Text(
                stringResource(limit.title),
                color = colors.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                OnboardingIcons.ChevronDown,
                contentDescription = null,
                tint = colors.body,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = chevron }
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = if (animate) expandVertically(tween(220)) + fadeIn(tween(220)) else fadeIn(tween(0)),
            exit = if (animate) shrinkVertically(tween(180)) + fadeOut(tween(120)) else fadeOut(tween(0)),
        ) {
            Column(Modifier.padding(start = 68.dp, end = 18.dp, bottom = 10.dp)) {
                limit.body.forEachIndexed { i, res ->
                    if (i > 0) Spacer(Modifier.height(8.dp))
                    Text(stringResource(res), color = colors.body, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Notifications, asked in context
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun NotificationsStep(colors: ObColors, idle: State<Float>, animate: Boolean, short: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxW = maxWidth
        val hero = minOf(maxWidth * 0.52f, maxHeight * 0.32f, 230.dp)
        val stage = hero * 1.42f
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(Modifier.size(stage), contentAlignment = Alignment.Center) {
                MascotOrb(OnboardingPage.NOTIFICATIONS.mascot(), ObAccent.Blue, colors, hero, idle, animate)
                GlassIconTile(
                    OnboardingIcons.Bell, ObAccent.Blue, colors,
                    size = (hero * 0.28f).coerceIn(44.dp, 62.dp),
                    modifier = Modifier.offset(x = stage * 0.34f, y = stage * -0.26f)
                )
            }
            Spacer(Modifier.height(if (short) 12.dp else 28.dp))
            Text(
                stringResource(R.string.ob27_notif_title),
                style = titleStyle(boxW),
                color = colors.title,
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .semantics { heading() }
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.ob27_notif_body),
                style = bodyStyle,
                color = colors.body,
                modifier = Modifier.widthIn(max = 460.dp)
            )
            // __ONBOARDING_POLISH_2026_11_24__ what is being allowed, shown.
            Spacer(Modifier.height(if (short) 14.dp else 24.dp))
            com.oshi.messenger.ui.onboarding.NotificationPreview(colors, idle, animate)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Identity (terminal step)
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun IdentityStep(
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    isCreating: Boolean,
    onDone: () -> Unit,
    onRestoreClick: () -> Unit,
    onRecoverFromDeviceClick: () -> Unit,
    deviceRecoveryMessage: String?,
    colors: ObColors,
    idle: State<Float>,
    animate: Boolean,
    short: Boolean,
) {
    // The mascot reacts: calm while empty, delighted once there is a name, celebrating while creating.
    val mascot = when {
        isCreating -> com.oshi.messenger.R.drawable.onboarding_mascot_party
        displayName.isNotBlank() -> com.oshi.messenger.R.drawable.onboarding_mascot_hearteyes
        else -> OnboardingPage.IDENTITY.mascot()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxW = maxWidth
        val orb = if (short) 84.dp else minOf(maxWidth * 0.36f, maxHeight * 0.22f, 150.dp)
        Column(
            Modifier
                .fillMaxSize()
                .fadingEdges()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(if (short) 4.dp else 12.dp))
                MascotOrb(mascot, ObAccent.Violet, colors, orb, idle, animate)
                Spacer(Modifier.height(if (short) 10.dp else 22.dp))
                Text(
                    stringResource(R.string.ob27_identity_title),
                    style = titleStyle(boxW),
                    color = colors.title,
                    modifier = Modifier.semantics { heading() }
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.ob27_identity_body), style = bodyStyle, color = colors.body)
                Spacer(Modifier.height(if (short) 14.dp else 22.dp))
                // __ONBOARDING_POLISH_2026_11_24__ the contact card this name becomes, live.
                com.oshi.messenger.ui.onboarding.IdentityPreview(
                    name = displayName,
                    placeholder = stringResource(R.string.onboarding_display_name),
                    colors = colors,
                )
                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text(stringResource(R.string.onboarding_display_name)) },
                    singleLine = true,
                    enabled = !isCreating,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { onDone() }),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.pane,
                        unfocusedContainerColor = colors.pane,
                        disabledContainerColor = colors.pane,
                        focusedBorderColor = brandInk(),
                        unfocusedBorderColor = colors.body.copy(alpha = 0.45f),
                        focusedLabelColor = brandInk(),
                        cursorColor = brandInk(),
                    )
                )

                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    GradientIcon(OnboardingIcons.Key, colors.iconGradient(ObAccent.Violet), 20.dp, Modifier.padding(top = 1.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.onboarding_key_info),
                        color = colors.body,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }

                Spacer(Modifier.height(26.dp))
                Text(
                    stringResource(R.string.ob27_already),
                    color = colors.body,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, bottom = 8.dp)
                )
                ObGlassCard(colors) {
                    AccountActionRow(
                        icon = OnboardingIcons.History,
                        text = stringResource(R.string.onboarding_restore_from_backup),
                        colors = colors,
                        enabled = !isCreating,
                        onClick = onRestoreClick
                    )
                    AccountActionRow(
                        icon = OnboardingIcons.PhoneRestore,
                        text = stringResource(R.string.onboarding_recover_from_device),
                        colors = colors,
                        enabled = !isCreating,
                        onClick = onRecoverFromDeviceClick
                    )
                }
                if (deviceRecoveryMessage != null) {
                    Text(
                        deviceRecoveryMessage,
                        fontSize = 13.sp,
                        color = colors.body,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun AccountActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    colors: ObColors,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GradientIcon(icon, colors.iconGradient(ObAccent.Violet), 22.dp)
        Spacer(Modifier.width(14.dp))
        Text(
            text,
            color = brandInk(),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Icon(
            OnboardingIcons.ArrowForward,
            contentDescription = null,
            tint = colors.body,
            modifier = Modifier.size(18.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Restore from a recovery key (unchanged behaviour; icons from the new set)
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun RestoreIdentityDialog(
    onDismiss: () -> Unit,
    onRestore: (String) -> Boolean,
    error: String?
) {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var key by remember { mutableStateOf("") }

    // QR scan launcher — same pattern as KeyBackupScreen / NewMessageScreen.
    // Recovery QRs are emitted with the `oshi-recovery:` prefix; strip it.
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.takeIf { it.isNotBlank() }?.let { scanned ->
            key = scanned.removePrefix("oshi-recovery:").trim()
        }
    }
    val launchScan: () -> Unit = {
        val opts = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(context.getString(R.string.onboarding_scan_prompt))
            setBeepEnabled(true)
            setOrientationLocked(false)
        }
        scanLauncher.launch(opts)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) launchScan() }
    val startScan: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            launchScan()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.onboarding_restore_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.onboarding_restore_description),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text(stringResource(R.string.onboarding_recovery_key)) },
                        singleLine = false,
                        minLines = 3,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        onClick = {
                            clipboard.getText()?.text?.let { pasted ->
                                key = pasted.trim()
                                // __CLIPBOARD_HYGIENE_2026_10_01__ recovery key pasted into OSHI ⇒ take it off the clipboard.
                                com.oshi.messenger.security.SensitiveClipboard.clearIfStillHolds(context, pasted)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(OnboardingIcons.Clipboard, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.onboarding_paste_clipboard))
                    }
                    TextButton(
                        onClick = startScan,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(OnboardingIcons.Qr, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.onboarding_scan_qr))
                    }
                }
                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRestore(key) },
                enabled = key.isNotBlank()
            ) {
                Text(stringResource(R.string.onboarding_restore_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
