package com.oshi.messenger.ui.theme

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties

// ─────────────────────────────────────────────────────────────────────────
// __OLED_GLASS_2026_11_16__ Drop-in Material 3 (1.1.2) wrappers that turn
// into glass under OLED black and are EXACTLY the Material component otherwise.
//
// Same parameters, same defaults, same order as the Material function they
// wrap, so every call site was converted by renaming the call and nothing
// else. Outside OLED black every argument is forwarded untouched — the
// regular Dark and Light themes are pixel-identical to before.
//
// A colour parameter that defaults to `Color.Unspecified` here means "the
// Material default": in OLED black it resolves to the glass fill, otherwise to
// Material's own default. A caller that passes an explicit colour keeps it,
// and still gets the glass rim.
// ─────────────────────────────────────────────────────────────────────────

/** [AlertDialog] (button-slot overload) — glass under OLED black. */
@Composable
fun OshiAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = Color.Unspecified,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    val glass = glassOn()
    if (!glass) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            shape = shape,
            containerColor = if (containerColor == Color.Unspecified) AlertDialogDefaults.containerColor else containerColor,
            iconContentColor = iconContentColor,
            titleContentColor = titleContentColor,
            textContentColor = textContentColor,
            tonalElevation = tonalElevation,
            properties = properties,
        )
        return
    }
    val canBlur = rememberCrossWindowBlur()
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            // Runs inside the dialog's own window: this is where it can be blurred behind.
            GlassWindowBlurEffect()
            GlassButtonInk(confirmButton)
        },
        modifier = modifier.glassHairline(shape),
        dismissButton = dismissButton?.let { d -> { GlassButtonInk(d) } },
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = if (containerColor == Color.Unspecified || containerColor == MaterialTheme.colorScheme.surface) glassFill(GlassTier.Dialog, canBlur) else containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = 0.dp,
        properties = properties,
    )
}

/**
 * Dialog buttons under OLED black. A `TextButton` inks its label in
 * `colorScheme.primary` — brand #5616EC, which is about 2:1 on a near-black
 * glass dialog and fails WCAG AA. Inside the button slots only, `primary`
 * becomes the readable brand ink #B899FF (≈ 9:1 on the dialog glass) and
 * `onPrimary` a deep violet, so a filled button stays AA as well.
 */
@Composable
private fun GlassButtonInk(content: @Composable () -> Unit) {
    // __GLASS_ALL_THEMES_2026_11_21__ the light palette keeps brand #5616EC
    // (8:1 on its white dialog glass); only the dark grounds swap.
    val p = currentGlass()
    val ink = p?.buttonInk
    if (ink == null) { content(); return }
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(primary = ink, onPrimary = p.onButtonInk ?: GlassOnBrandInk),
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography,
        content = content,
    )
}

/** Label colour on a [Oshi.BrandInkDark] filled button. */
val GlassOnBrandInk = Color(0xFF14082E)

/** [AlertDialog] (content-slot overload) — the caller draws its own surface; only the blur is added. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OshiAlertDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val glass = glassOn()
    AlertDialog(onDismissRequest = onDismissRequest, modifier = modifier, properties = properties) {
        if (glass) GlassWindowBlurEffect()
        content()
    }
}

/** [ModalBottomSheet] — glass under OLED black. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OshiModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    tonalElevation: Dp = BottomSheetDefaults.Elevation,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    windowInsets: WindowInsets = BottomSheetDefaults.windowInsets,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = glassOn()
    if (!glass) {
        val container = if (containerColor == Color.Unspecified) BottomSheetDefaults.ContainerColor else containerColor
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetState = sheetState,
            shape = shape,
            containerColor = container,
            contentColor = if (contentColor == Color.Unspecified) contentColorFor(container) else contentColor,
            tonalElevation = tonalElevation,
            scrimColor = scrimColor,
            dragHandle = dragHandle,
            windowInsets = windowInsets,
            content = content,
        )
        return
    }
    val canBlur = rememberCrossWindowBlur()
    // An explicit `colorScheme.surface` is what most call sites pass to mean "the default".
    val container = if (containerColor == Color.Unspecified || containerColor == MaterialTheme.colorScheme.surface) glassFill(GlassTier.Sheet, canBlur) else containerColor
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        // NOT `modifier.glassHairline(shape)`: M3 1.1 applies the caller's
        // modifier BEFORE the sheet's own `offset`, so a rim drawn there sits at
        // the top of the SCREEN, not on the sheet. The rim is drawn by the drag
        // handle instead, which is the first thing in the sheet — see [glassSheetRim].
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = container,
        contentColor = if (contentColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else contentColor,
        tonalElevation = 0.dp,
        scrimColor = scrimColor,
        dragHandle = dragHandle?.let { handle ->
            {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth().glassSheetRim(shape),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) { handle() }
            }
        },
        windowInsets = windowInsets,
    ) {
        GlassWindowBlurEffect()
        content()
    }
}

/** How far down a sheet's lit rim runs before it has faded out. */
private val SheetRimDepth = 160.dp

/**
 * A sheet's lit rim and top sheen, drawn from the full-width drag-handle row
 * (the first child of the sheet, so its top IS the sheet's top edge) and
 * running down past it; the sheet Surface clips to its shape, so the stroke
 * lands on the rounded top. It fades out by [SheetRimDepth] — light catches
 * the top edge and corners of a sheet; its sides run off the screen anyway.
 */
private fun Modifier.glassSheetRim(shape: Shape): Modifier = composed {
    val p = currentGlass() ?: GlassPalette.Oled
    Modifier.drawWithCache {
    val strokePx = OledGlass.HairlineWidth.toPx()
    val depth = SheetRimDepth.toPx()
    val outline = shape.createOutline(Size(size.width - strokePx, depth * 2), layoutDirection, this)
    val rim = Brush.verticalGradient(
        0f to p.hairlineTop,
        0.5f to p.hairlineBottom,
        1f to p.hairlineBottom.copy(alpha = 0f),
        startY = 0f, endY = depth,
    )
    val sheenH = OledGlass.HighlightDepth.toPx().coerceAtMost(size.height)
    val sheen = Brush.verticalGradient(
        0f to p.highlight,
        1f to p.highlight.copy(alpha = 0f),
        startY = 0f, endY = sheenH.coerceAtLeast(1f),
    )
    onDrawBehind {
        drawRect(sheen, size = Size(size.width, sheenH))
        translate(strokePx / 2f, strokePx / 2f) {
            drawOutline(outline, brush = rim, style = Stroke(width = strokePx))
        }
    }
    }
}

/** Menu glass corner: a little softer than Material's 4 dp, like a pane of glass. */
private val GlassMenuShape = RoundedCornerShape(14.dp)

/** [DropdownMenu] — glass under OLED black. */
@Composable
fun OshiDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!glassOn()) {
        DropdownMenu(expanded, onDismissRequest, modifier, offset, properties, content)
        return
    }
    // M3 1.1's menu draws `Surface(color = colorScheme.surface, shape = shapes.extraSmall)`
    // around `modifier`; re-scoping those two theme values is the only way in.
    // A menu is a popup window with nothing blurred behind it: translucency
    // would only let the row under it show through as a stray shade.
    val fill = glassOpaque(GlassTier.Menu)
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.withGlassSurface(fill),
        shapes = MaterialTheme.shapes.copy(extraSmall = GlassMenuShape),
        typography = MaterialTheme.typography,
    ) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier.glassHairline(GlassMenuShape),
            offset = offset,
            properties = properties,
            content = content,
        )
    }
}

/**
 * [androidx.compose.material3.Card] — under OLED black every card gets the glass
 * rim (its fill is the scheme's near-black `surfaceVariant`, or whatever the
 * caller chose). Identical to Material's card otherwise.
 */
@Composable
fun OshiCard(
    modifier: Modifier = Modifier,
    shape: Shape = androidx.compose.material3.CardDefaults.shape,
    colors: androidx.compose.material3.CardColors = androidx.compose.material3.CardDefaults.cardColors(),
    elevation: androidx.compose.material3.CardElevation = androidx.compose.material3.CardDefaults.cardElevation(),
    border: androidx.compose.foundation.BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = if (glassOn()) modifier.glassLift(shape).glassHairline(shape) else modifier,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        content = content,
    )
}

/** Clickable [androidx.compose.material3.Card] — see [OshiCard]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OshiCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = androidx.compose.material3.CardDefaults.shape,
    colors: androidx.compose.material3.CardColors = androidx.compose.material3.CardDefaults.cardColors(),
    elevation: androidx.compose.material3.CardElevation = androidx.compose.material3.CardDefaults.cardElevation(),
    border: androidx.compose.foundation.BorderStroke? = null,
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource =
        androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.Card(
        onClick = onClick,
        modifier = if (glassOn()) modifier.glassLift(shape).glassHairline(shape) else modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        interactionSource = interactionSource,
        content = content,
    )
}

/**
 * [androidx.compose.material3.FilterChip] — under OLED black: the glass rim in
 * place of Material's outline, and the SELECTED chip glows (the focus rule).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OshiFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    shape: Shape = androidx.compose.material3.FilterChipDefaults.shape,
    colors: androidx.compose.material3.SelectableChipColors = androidx.compose.material3.FilterChipDefaults.filterChipColors(),
    elevation: androidx.compose.material3.SelectableChipElevation? = androidx.compose.material3.FilterChipDefaults.filterChipElevation(),
    border: androidx.compose.material3.SelectableChipBorder? = androidx.compose.material3.FilterChipDefaults.filterChipBorder(),
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource =
        androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
) {
    val glass = glassOn()
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = if (!glass) modifier
            // Chips pad a 32 dp capsule to a 48 dp touch target: draw on the capsule.
            else if (selected) modifier.glassGlow(shape, alpha = 0.22f, spread = 6.dp, visibleHeight = 32.dp)
            else modifier.glassHairline(shape, highlight = false, visibleHeight = 32.dp),
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = if (glass) androidx.compose.material3.FilterChipDefaults.filterChipBorder(
            borderColor = Color.Transparent,
            selectedBorderColor = Color.Transparent,
        ) else border,
        interactionSource = interactionSource,
    )
}

/**
 * For a hand-built `Dialog { Surface(color = colorScheme.surface) { … } }`:
 * the surface colour to use (the dialog glass under OLED black — a #000000
 * dialog on a #000000 screen has no edge at all), and [GlassDialogChrome] to
 * call inside the dialog for the rim and the blur behind.
 */
@Composable
fun glassDialogSurfaceColor(default: Color = MaterialTheme.colorScheme.surface): Color =
    if (glassOn()) glassFill(GlassTier.Dialog, rememberCrossWindowBlur()) else default

/** The dialog rim (on the dialog's own Surface modifier) under OLED black. */
@Composable
fun Modifier.glassDialogRim(shape: Shape): Modifier =
    if (glassOn()) {
        GlassWindowBlurEffect()
        this.glassHairline(shape)
    } else this
