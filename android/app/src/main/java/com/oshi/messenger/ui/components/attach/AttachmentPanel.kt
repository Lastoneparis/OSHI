package com.oshi.messenger.ui.components.attach

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.oshi.messenger.R
import com.oshi.messenger.ui.components.ChatMediaSelection
import com.oshi.messenger.ui.theme.GlassTier
import com.oshi.messenger.ui.theme.glassOpaque
import com.oshi.messenger.ui.theme.brandInk
import com.oshi.messenger.ui.theme.GlassWindowBlurEffect
import com.oshi.messenger.ui.theme.glassEdge
import com.oshi.messenger.ui.theme.glassFill
import com.oshi.messenger.ui.theme.glassGlow
import com.oshi.messenger.ui.theme.glassHairline
import com.oshi.messenger.ui.theme.glassOn
import com.oshi.messenger.ui.theme.glassTextFieldColors
import com.oshi.messenger.ui.theme.isDarkScheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * __ATTACH_PANEL_2027_2026_11_16__ The one attachment panel, for 1:1 AND group chats.
 *
 * What opens on "+": a sheet whose hero is the user's RECENT photos and videos
 * (multi-select, numbered badges, duration chips), with the other ways to share —
 * gallery, camera, file, GIF, location, contact, poll… — as a row of tiles pinned at
 * the bottom. Picking photos turns that row into a caption field and a "Send N" button.
 *
 * It replaces two copies of a 13-row text list (`AttachmentMenuSheet` in ChatScreen,
 * `GroupAttachmentMenuSheet` in GroupChatScreen) that had drifted apart: the group
 * copy had lost the GIF picker and Check-In. The screens now only say WHICH actions
 * exist ([AttachAction]) and what a send does.
 *
 * Mechanics, all in this file so they can be read in one place:
 *  * its own sheet (a full-window [Dialog]) rather than Material 3 1.1's
 *    `ModalBottomSheet`, because the action row / send bar must stay pinned to the
 *    bottom of the SCREEN while the sheet is half open, which that sheet cannot do;
 *  * two resting points, half and full ([AttachmentPanelLogic.anchors]); the grid drags
 *    the sheet up before it scrolls and down once it is at its top (nested scroll),
 *    and every release settles on a spring ([AttachmentPanelLogic.settle]);
 *  * gallery permission handled in all three states ([GalleryAccess]) and re-read on
 *    resume, with the system photo picker always one tap away so nobody is forced to
 *    grant anything;
 *  * light, dark and OLED glass (Glass.kt helpers: sheet fill, lit rim, blur behind,
 *    bar edge, focus glow on Send).
 */
data class AttachAction(
    val key: String,
    val icon: ImageVector,
    val label: String,
    val tint: Color,
    val onClick: () -> Unit,
)

/** Test tags, for instrumented tests and screenshot scripts. */
object AttachPanelTags {
    const val PANEL = "attach-panel"
    const val GRID = "attach-grid"
    const val SEND = "attach-send"
    const val CAPTION = "attach-caption"
    const val ACCESS_CTA = "attach-access-cta"
    fun action(key: String) = "attach-action-$key"
    fun tile(index: Int) = "attach-tile-$index"
}

private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
private val TileShape = RoundedCornerShape(6.dp)
private val SheetSpring = spring<Float>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)

@Composable
fun AttachmentPanel(
    actions: List<AttachAction>,
    onDismiss: () -> Unit,
    /** The pick, in badge order, the caption typed in the bar, and the view-once flag. */
    onSendMedia: (items: List<RecentMediaItem>, caption: String, viewOnce: Boolean) -> Unit,
    /** "All photos": the system photo picker (needs no permission). */
    onBrowseGallery: () -> Unit,
    onCamera: () -> Unit,
    /** 1:1 only — the group wire has no view-once (see GroupChatScreen). */
    showViewOnce: Boolean = false,
    initialViewOnce: Boolean = false,
    maxSelection: Int = ChatMediaSelection.MAX_SELECTION,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,     // handled below so the exit animates
        ),
    ) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { w ->
                // The scrim is drawn (and animated) here; the platform dim would pop.
                w.setDimAmount(0f)
                w.setWindowAnimations(0)
                // Edge to edge: the scrim covers the status bar and the bar below sits on
                // the navigation area with its own inset padding.
                w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
                @Suppress("DEPRECATION")
                run {
                    w.statusBarColor = android.graphics.Color.TRANSPARENT
                    w.navigationBarColor = android.graphics.Color.TRANSPARENT
                }
                // A dialog window is framed INSIDE the system bars by default
                // (fitInsetsTypes = systemBars): lift that, or the scrim stops under the
                // status bar and the action row is pushed under the navigation bar.
                w.attributes = w.attributes.apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        fitInsetsTypes = 0
                    } else {
                        flags = flags or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    w.isNavigationBarContrastEnforced = false
                }
                @Suppress("DEPRECATION")
                w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
        if (glassOn()) GlassWindowBlurEffect()
        PanelBody(
            actions = actions,
            onDismiss = onDismiss,
            onSendMedia = onSendMedia,
            onBrowseGallery = onBrowseGallery,
            onCamera = onCamera,
            showViewOnce = showViewOnce,
            initialViewOnce = initialViewOnce,
            maxSelection = maxSelection,
        )
    }
}

@Composable
private fun PanelBody(
    actions: List<AttachAction>,
    onDismiss: () -> Unit,
    onSendMedia: (List<RecentMediaItem>, String, Boolean) -> Unit,
    onBrowseGallery: () -> Unit,
    onCamera: () -> Unit,
    showViewOnce: Boolean,
    initialViewOnce: Boolean,
    maxSelection: Int,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val glass = glassOn()
    val dark = isDarkScheme()

    // ── Gallery state ──────────────────────────────────────────────────────
    var access by remember { mutableStateOf(RecentMedia.currentAccess(context)) }
    var askedBefore by remember { mutableStateOf(false) }
    val items = remember { mutableStateListOf<RecentMediaItem>() }
    var endReached by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var firstPageLoaded by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    val selection = remember { mutableStateListOf<Long>() }
    var caption by remember { mutableStateOf("") }
    var viewOnce by remember { mutableStateOf(initialViewOnce) }

    fun loadMore() {
        if (loading || endReached) return
        loading = true
        scope.launch {
            val page = RecentMedia.page(context, items.size)
            val merged = AttachmentPanelLogic.appendPage(items, page)
            items.addAll(merged.subList(items.size, merged.size))
            endReached = page.size < AttachmentPanelLogic.PAGE_SIZE
            loading = false
            firstPageLoaded = true
        }
    }

    // __RECENT_PHOTOS_ORDER_2026_11_21__ Re-read the newest page when the gallery changes
    // under an open panel (a screenshot, a photo taken in the camera app, a deletion) or
    // when the user comes back to it — the grid must never stay frozen on what was there
    // when it first opened. The pages already scrolled through are kept.
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    fun refreshHead() {
        if (access == GalleryAccess.NONE || !firstPageLoaded) return
        refreshJob?.cancel()
        refreshJob = scope.launch {
            kotlinx.coroutines.delay(300) // MediaStore fires a burst of changes per file
            val head = RecentMedia.page(context, 0)
            val merged = AttachmentPanelLogic.mergeFreshHead(items.toList(), head)
            if (merged != items.toList()) {
                items.clear(); items.addAll(merged)
                val kept = AttachmentPanelLogic.prune(selection.toList(), merged.mapTo(HashSet()) { it.id })
                if (kept != selection.toList()) { selection.clear(); selection.addAll(kept) }
                if (head.size < AttachmentPanelLogic.PAGE_SIZE) endReached = true
            }
        }
    }
    DisposableEffect(access) {
        val stop = if (access != GalleryAccess.NONE) RecentMedia.observe(context) { refreshHead() } else ({})
        onDispose { stop() }
    }

    LaunchedEffect(access, reloadKey) {
        items.clear(); endReached = false; firstPageLoaded = false; loading = false
        if (access != GalleryAccess.NONE) loadMore() else firstPageLoaded = true
    }

    // Re-read the grant when the user comes back from Settings or the partial picker.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                val now = RecentMedia.currentAccess(context)
                if (now != access) access = now else if (now == GalleryAccess.PARTIAL) reloadKey++ else refreshHead()
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        askedBefore = true
        val now = RecentMedia.currentAccess(context)
        if (now == access) reloadKey++ else access = now
    }
    val requestAccess: () -> Unit = {
        val activity = context.findActivity()
        val perms = AttachmentPanelLogic.permissionsToRequest(Build.VERSION.SDK_INT)
        val rationale = activity != null && perms.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (AttachmentPanelLogic.mustOpenSettings(askedBefore, access, rationale)) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } else {
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().testTag(AttachPanelTags.PANEL)) {
        val topInsetPx = WindowInsets.statusBars.getTop(density).toFloat()
        val gapPx = with(density) { 8.dp.toPx() }
        val fullHeightPx = constraints.maxHeight - topInsetPx - gapPx
        val anchors = remember(fullHeightPx) { AttachmentPanelLogic.anchors(fullHeightPx, collapsedFraction = 0.62f) }
        var sheetOffset by remember { mutableFloatStateOf(fullHeightPx) }
        var settleJob by remember { mutableStateOf<Job?>(null) }
        var closing by remember { mutableStateOf(false) }
        var barHeightPx by remember { mutableStateOf(with(density) { 140.dp.roundToPx() }) }

        fun animateTo(target: SheetTarget, velocity: Float = 0f, then: (() -> Unit)? = null) {
            settleJob?.cancel()
            settleJob = scope.launch {
                val spec = if (target == SheetTarget.HIDDEN) tween<Float>(durationMillis = 220) else SheetSpring
                animate(sheetOffset, anchors.of(target), velocity, spec) { v, _ -> sheetOffset = v }
                then?.invoke()
            }
        }

        fun close(then: (() -> Unit)? = null) {
            if (closing) return
            closing = true
            animateTo(SheetTarget.HIDDEN) {
                onDismiss()
                then?.invoke()
            }
        }

        LaunchedEffect(anchors) {
            if (!closing) animateTo(SheetTarget.COLLAPSED)
        }
        BackHandler {
            if (sheetOffset < anchors.collapsed - 1f) animateTo(SheetTarget.COLLAPSED) else close()
        }

        fun settle(velocity: Float) {
            when (val t = AttachmentPanelLogic.settle(sheetOffset, velocity, anchors)) {
                SheetTarget.HIDDEN -> close()
                else -> animateTo(t, velocity)
            }
        }

        val nested = remember(anchors) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    val dy = available.y
                    if (dy < 0f && sheetOffset > anchors.expanded) {
                        settleJob?.cancel()
                        val next = (sheetOffset + dy).coerceAtLeast(anchors.expanded)
                        val used = next - sheetOffset
                        sheetOffset = next
                        return Offset(0f, used)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    val dy = available.y
                    if (dy > 0f && source == NestedScrollSource.Drag) {
                        settleJob?.cancel()
                        val next = (sheetOffset + dy).coerceAtMost(anchors.hidden)
                        val used = next - sheetOffset
                        sheetOffset = next
                        return Offset(0f, used)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (sheetOffset > anchors.expanded + 0.5f) {
                        settle(available.y)
                        return available
                    }
                    return Velocity.Zero
                }
            }
        }

        val dragState = rememberDraggableState { delta ->
            settleJob?.cancel()
            sheetOffset = (sheetOffset + delta).coerceIn(anchors.expanded, anchors.hidden)
        }

        // 0 when hidden, 1 from the half stop up.
        val shown = ((anchors.hidden - sheetOffset) / (anchors.hidden - anchors.collapsed)).coerceIn(0f, 1f)
        val expandedness = ((anchors.collapsed - sheetOffset) / (anchors.collapsed - anchors.expanded)).coerceIn(0f, 1f)

        // ── Scrim ──────────────────────────────────────────────────────────
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = (if (glass) 0.35f else 0.42f) * shown + 0.18f * expandedness))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { close() }
        )

        // ── Sheet ──────────────────────────────────────────────────────────
        val sheetColor = when {
            glass -> glassFill(GlassTier.Sheet)
            dark -> MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
            else -> MaterialTheme.colorScheme.surface
        }
        val sheetHeightDp = with(density) { fullHeightPx.toDp() }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(sheetHeightDp)
                .offset { IntOffset(0, sheetOffset.roundToInt()) }
                .clip(SheetShape)
                .background(sheetColor)
                .then(if (glass) Modifier.glassHairline(SheetShape) else Modifier)
                .nestedScroll(nested)
        ) {
            // Header — the drag surface.
            Column(
                Modifier
                    .fillMaxWidth()
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        onDragStopped = { v -> settle(v) },
                    )
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(width = 36.dp, height = 5.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (glass) 0.30f else 0.18f))
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp).heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (selection.isEmpty()) stringResource(R.string.attach_recent)
                        else stringResource(R.string.attach_selected_count, selection.size),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    AnimatedVisibility(visible = expandedness > 0.5f, enter = fadeIn(), exit = fadeOut()) {
                        IconButton(onClick = { animateTo(SheetTarget.COLLAPSED) }) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.attach_collapse))
                        }
                    }
                    TextButton(onClick = { close(onBrowseGallery) }, colors = inkButton()) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.attach_all_photos), fontWeight = FontWeight.Medium)
                    }
                }
            }

            // The pinned bar's real height (insets included), measured below.
            val barReserve = barHeightPx.toFloat()
            // The visible part of the sheet; the empty / no-access states centre in it.
            val visibleDp = with(density) { (fullHeightPx - sheetOffset - barReserve).coerceAtLeast(0f).toDp() }

            when {
                access == GalleryAccess.NONE -> AccessCard(
                    askedBefore = askedBefore,
                    onAllow = requestAccess,
                    onBrowse = { close(onBrowseGallery) },
                    maxHeight = visibleDp,
                )
                firstPageLoaded && items.isEmpty() && access == GalleryAccess.FULL -> EmptyGallery(
                    onCamera = { close(onCamera) },
                    maxHeight = visibleDp,
                )
                else -> {
                    val gridState = rememberLazyGridState()
                    LaunchedEffect(gridState) {
                        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                            .collect { last ->
                                if (AttachmentPanelLogic.shouldLoadMore(last, items.size, endReached, loading)) loadMore()
                            }
                    }
                    val bottomPad = with(density) { barReserve.toDp() } + 8.dp
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        state = gridState,
                        modifier = Modifier.fillMaxSize().testTag(AttachPanelTags.GRID),
                        contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 2.dp, bottom = bottomPad),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (access == GalleryAccess.PARTIAL) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "partial") {
                                PartialBanner(onManage = requestAccess)
                            }
                        }
                        item(key = "camera") { CameraTile(onClick = { close(onCamera) }) }
                        items(items, key = { it.id }) { item ->
                            val number = AttachmentPanelLogic.badgeNumber(selection, item.id)
                            MediaTile(
                                item = item,
                                number = number,
                                onToggle = {
                                    val r = AttachmentPanelLogic.toggle(selection.toList(), item.id, maxSelection)
                                    when (r.outcome) {
                                        ToggleOutcome.LIMIT -> {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.attach_limit, maxSelection),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        else -> {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            selection.clear(); selection.addAll(r.selection)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // ── Bottom bar: actions, or caption + Send ────────────────────────
        val barOffset = max(0f, sheetOffset - anchors.collapsed)
        val barColor = when {
            // Near-opaque: this bar sits over the photo grid, not over a calm
            // wallpaper, and thumbnails bleeding through the labels read as noise.
            glass -> glassOpaque(GlassTier.Sheet)
            dark -> MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
            else -> MaterialTheme.colorScheme.surface
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .offset { IntOffset(0, barOffset.roundToInt()) }
                .onSizeChanged { barHeightPx = it.height }
                .background(barColor)
                .then(if (glass) Modifier.glassEdge(top = true) else Modifier)
                .navigationBarsPadding()
                .imePadding()
        ) {
            AnimatedContent(
                targetState = selection.isEmpty(),
                transitionSpec = {
                    (slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it / 2 } + fadeIn())
                        .togetherWith(slideOutVertically { it / 2 } + fadeOut())
                },
                label = "attach-bar",
            ) { empty ->
                if (empty) {
                    ActionRow(actions = actions, onAction = { a ->
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        close(a.onClick)
                    })
                } else {
                    SendBar(
                        count = selection.size,
                        caption = caption,
                        onCaption = { caption = it },
                        showViewOnce = showViewOnce,
                        viewOnce = viewOnce,
                        onViewOnce = { viewOnce = it },
                        onSend = {
                            val byId = items.associateBy { it.id }
                            val picked = selection.mapNotNull { byId[it] }
                            if (picked.isNotEmpty()) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                val cap = caption
                                val vo = viewOnce
                                close { onSendMedia(picked, cap, vo) }
                            }
                        },
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Pieces
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun MediaTile(item: RecentMediaItem, number: Int?, onToggle: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val px = with(density) { 160.dp.roundToPx() }
    val bmp: Bitmap? by produceState(RecentMedia.cachedThumb(item, px), item.id) {
        if (value == null) value = RecentMedia.thumbnail(context, item, px)
    }
    val selected = number != null
    val scale by animateFloatAsState(
        if (selected) 0.86f else 1f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "tile-scale"
    )
    val a11yKind = if (item.isVideo) {
        stringResource(R.string.attach_video_a11y, AttachmentPanelLogic.formatDuration(item.durationMs))
    } else stringResource(R.string.attach_photo_a11y)
    Box(
        Modifier
            .aspectRatio(1f)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = a11yKind }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(if (selected) RoundedCornerShape(14.dp) else TileShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
        ) {
            bmp?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (item.isVideo) {
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(start = 4.dp, end = 7.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Text(
                        AttachmentPanelLogic.formatDuration(item.durationMs),
                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            } else if (item.isGif) {
                Text(
                    "GIF",
                    color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
        SelectionBadge(number = number, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}

@Composable
private fun SelectionBadge(number: Int?, modifier: Modifier) {
    val brand = MaterialTheme.colorScheme.primary
    Box(modifier.size(26.dp), contentAlignment = Alignment.Center) {
        // The empty ring: white over any photo, with a soft dark fill so it reads on white too.
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.18f))
                .border(1.75.dp, Color.White.copy(alpha = 0.95f), CircleShape)
        )
        androidx.compose.animation.AnimatedVisibility(
            visible = number != null,
            enter = scaleIn(spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)) + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(brand)
                    .border(1.75.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = (number ?: 1).toString(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun CameraTile(onClick: () -> Unit) {
    val glass = glassOn()
    val label = stringResource(R.string.attach_camera)
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(TileShape)
            .background(
                if (glass) glassFill(GlassTier.Card)
                else MaterialTheme.colorScheme.primary.copy(alpha = if (isDarkScheme()) 0.22f else 0.10f)
            )
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.PhotoCamera, null,
                tint = brandInk(),
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = brandInk())
        }
    }
}

@Composable
private fun ActionRow(actions: List<AttachAction>, onAction: (AttachAction) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(actions, key = { it.key }) { a -> ActionTile(a, onClick = { onAction(a) }) }
    }
}

@Composable
private fun ActionTile(action: AttachAction, onClick: () -> Unit) {
    val glass = glassOn()
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = androidx.compose.material.ripple.rememberRipple(bounded = true), role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp)
            .testTag(AttachPanelTags.action(action.key)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val circle = Modifier.size(54.dp).clip(CircleShape)
        Box(
            if (glass) {
                circle
                    .background(glassFill(GlassTier.Chip))
                    .glassHairline(CircleShape)
            } else {
                circle.background(
                    Brush.linearGradient(
                        listOf(action.tint.lighten(0.18f), action.tint),
                        start = Offset(0f, 0f), end = Offset(0f, Float.POSITIVE_INFINITY),
                    )
                )
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                action.icon,
                contentDescription = null,
                tint = if (glass) action.tint.lighten(0.25f) else Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            action.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SendBar(
    count: Int,
    caption: String,
    onCaption: (String) -> Unit,
    showViewOnce: Boolean,
    viewOnce: Boolean,
    onViewOnce: (Boolean) -> Unit,
    onSend: () -> Unit,
) {
    val glass = glassOn()
    val brand = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showViewOnce) {
            val label = stringResource(R.string.media_view_once)
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (viewOnce) brand else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    .toggleable(value = viewOnce, role = Role.Switch, onValueChange = onViewOnce)
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Timer, null,
                    tint = if (viewOnce) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        val shape = RoundedCornerShape(24.dp)
        OutlinedTextField(
            value = caption,
            onValueChange = onCaption,
            placeholder = { Text(stringResource(R.string.attach_caption_hint)) },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag(AttachPanelTags.CAPTION)
                .then(if (glass) Modifier.glassHairline(shape) else Modifier),
            shape = shape,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
            colors = if (glass) glassTextFieldColors() else OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                unfocusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = brand.copy(alpha = 0.6f),
            ),
        )
        Spacer(Modifier.width(10.dp))
        val sendLabel = stringResource(R.string.attach_send_count, count)
        Box(Modifier.size(56.dp)) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(54.dp)
                    .then(if (glass) Modifier.glassGlow(CircleShape) else Modifier)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(brand.lighten(0.12f), brand)))
                    .clickable(onClickLabel = sendLabel, role = Role.Button, onClick = onSend)
                    .semantics { contentDescription = sendLabel }
                    .testTag(AttachPanelTags.SEND),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Send, null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp).offset(x = 2.dp),
                )
            }
            // The count, on the button's shoulder.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onPrimary)
                    .border(1.5.dp, brand, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(targetState = count, label = "count") { n ->
                    Text(n.toString(), color = brand, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PartialBanner(onManage: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isDarkScheme()) 0.18f else 0.08f))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.PhotoLibrary, null, tint = brandInk(), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.attach_partial),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onManage, colors = inkButton()) { Text(stringResource(R.string.attach_partial_manage), fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun AccessCard(askedBefore: Boolean, onAllow: () -> Unit, onBrowse: () -> Unit, maxHeight: androidx.compose.ui.unit.Dp) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight.coerceAtLeast(240.dp))
            .padding(start = 32.dp, end = 32.dp, top = 4.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isDarkScheme()) 0.22f else 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.PhotoLibrary, null, tint = brandInk(), modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.attach_access_title),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.attach_access_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Surface(
            onClick = onAllow,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 200.dp).testTag(AttachPanelTags.ACCESS_CTA),
        ) {
            Box(Modifier.padding(horizontal = 24.dp, vertical = 13.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (askedBefore) R.string.attach_access_settings else R.string.attach_access_allow),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        TextButton(onClick = onBrowse, modifier = Modifier.heightIn(min = 48.dp), colors = inkButton()) {
            Text(stringResource(R.string.attach_access_browse))
        }
    }
}

@Composable
private fun EmptyGallery(onCamera: () -> Unit, maxHeight: androidx.compose.ui.unit.Dp) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = maxHeight.coerceAtLeast(220.dp)).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.PhotoLibrary, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.attach_empty_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onCamera, colors = inkButton()) {
            Icon(Icons.Rounded.PhotoCamera, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.attach_camera))
        }
    }
}

@Composable
private fun inkButton() = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = brandInk())

private fun Color.lighten(f: Float): Color = Color(
    red = red + (1f - red) * f,
    green = green + (1f - green) * f,
    blue = blue + (1f - blue) * f,
    alpha = alpha,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
