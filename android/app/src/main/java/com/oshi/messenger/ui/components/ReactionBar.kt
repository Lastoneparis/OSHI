package com.oshi.messenger.ui.components

import com.oshi.messenger.ui.theme.glassGlow
import com.oshi.messenger.ui.theme.glassHairline
import com.oshi.messenger.ui.theme.GlassTier
import com.oshi.messenger.ui.theme.glassOpaque
import com.oshi.messenger.ui.theme.glassFill
import com.oshi.messenger.ui.theme.glassOn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.data.model.OshiReaction

/**
 * __REACTION_BAR_ONE_COMPONENT_2026_11_15__ The reaction row at the top of the
 * long-press menu, shared by the 1:1 and the group thread. The two screens had
 * a copy each, and the copies had already drifted (the group one never removed
 * a reaction, see GroupChatScreen); one composable is the only way they stay
 * the same.
 *
 * Fixed against the copies it replaces:
 *  - every cell is a 48dp touch target (was 40dp);
 *  - the row WRAPS instead of scrolling: 7 reactions + "+" at 40dp + 6dp
 *    gaps is 362dp, wider than the ~290dp an AlertDialog gives its content
 *    on a phone, so the "+" (the only way to the full set) sat off the right
 *    edge with nothing hinting it was there; the expanded set (22) was one
 *    1 000dp-long strip;
 *  - TalkBack announces which reaction is the current one (`selected`) and
 *    each cell as a button.
 *
 * [onPick] receives the tapped code; the caller TOGGLES (tapping the
 * highlighted one removes it), exactly like any other emoji.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReactionPicker(
    currentReaction: String?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * __INTEROP_2026_11_21__ GROUP_INFO_SETTINGS_SPEC §2.3 "the picker shows only allowed
     * emoji": non-null ⇒ exactly these (no "+"), empty ⇒ no picker at all. Null = the full set.
     */
    restrictTo: List<String>? = null,
) {
    if (restrictTo != null && restrictTo.isEmpty()) return
    var showAll by remember { mutableStateOf(restrictTo != null) }
    val glass = glassOn()
    val offered = restrictTo ?: if (showAll) OshiReaction.PICKER_ALL else OshiReaction.QUICK_ROW
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .testTag(TAG_PICKER),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        offered.forEach { code ->
            val isSelected = currentReaction == code
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(ReactionBarMetrics.CELL_DP.dp)
                    .then(if (glass && isSelected) Modifier.glassGlow(CircleShape, spread = 4.dp) else Modifier)
                    .clip(CircleShape)
                    .semantics { selected = isSelected }
                    .clickable(role = Role.Button) { onPick(code) }
                    .testTag(TAG_CELL_PREFIX + code),
            ) {
                // __OLED_GLASS_2026_11_16__ OLED black: glass discs; the current
                // reaction glows.
                Surface(
                    color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    else if (glass) glassFill(GlassTier.Chip)
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                    border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier
                        .size(ReactionBarMetrics.DISC_DP.dp)
                        .then(if (glass && !isSelected) Modifier.glassHairline(CircleShape) else Modifier),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        ReactionGlyph(code, fontSize = 20.sp, animate = true)
                    }
                }
                // __REACTION_SAFE_MAPPING_2026_11_16__ "new" dot: an iPhone on an
                // older version may show this one as 👍 (caption below the set).
                if (showAll && OshiReaction.mayShowAsThumbsOnOldIos(code)) {
                    NewReactionDot(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp))
                }
            }
        }
        if (!showAll) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(ReactionBarMetrics.CELL_DP.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { showAll = true }
                    .testTag(TAG_MORE),
            ) {
                Surface(
                    color = if (glass) glassFill(GlassTier.Chip) else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(ReactionBarMetrics.DISC_DP.dp)
                        .then(if (glass) Modifier.glassHairline(CircleShape) else Modifier),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.reactions_show_all),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        if (showAll && offered.any(OshiReaction::mayShowAsThumbsOnOldIos)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, top = 4.dp, end = 6.dp)
                    .testTag(TAG_NEW_HINT),
            ) {
                NewReactionDot()
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.reactions_new_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One reaction chip under a bubble, shared by both threads. The count is
 * ALWAYS shown, "1" included — iOS draws `emoji + count` unconditionally and
 * the 1:1 thread already did (R-5); the group copy hid it for a lone reaction,
 * so the same reaction looked different in a group and gave no hint that a tap
 * does anything.
 *
 * __REACTION_CHIP_TOUCH_2026_11_16__ The visible capsule stays small (~24dp
 * tall) but the touch target is a [ReactionBarMetrics.CHIP_TOUCH_DP] box
 * (Material minimum). The capsule sits at the TOP of that box, so the extra
 * touch area lies in the space reserved below the bubble — it never overlaps
 * this bubble's text or the next bubble. Tap toggles ([onClick]); a long press
 * ([onLongClick]) opens the "who reacted" sheet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReactionChip(
    code: String,
    count: Int,
    isMine: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .defaultMinSize(
                minWidth = ReactionBarMetrics.CHIP_TOUCH_DP.dp,
                minHeight = ReactionBarMetrics.CHIP_TOUCH_DP.dp,
            )
            .semantics { selected = isMine }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .testTag(TAG_CHIP_PREFIX + code),
    ) {
        // __OLED_GLASS_2026_11_16__ OLED black: a glass capsule, no shadow.
        val glass = glassOn()
        Surface(
            color = if (isMine) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            else if (glass) glassOpaque(GlassTier.Chip)
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(50),
            border = if (isMine) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null,
            shadowElevation = if (glass) 0.dp else 2.dp,
            modifier = Modifier
                .then(if (glass && !isMine) Modifier.glassHairline(RoundedCornerShape(50), highlight = false) else Modifier)
                .clip(RoundedCornerShape(50))
                .indication(interaction, LocalIndication.current),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReactionGlyph(code, fontSize = 14.sp)
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = count.toString(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One line of the "who reacted" sheet: a reaction and the people who chose it. */
data class ReactionWhoRow(val code: String, val names: List<String>)

/**
 * Pure: build the "who reacted" rows. [byCode] is reaction -> reactor keys (the
 * 1:1 [com.oshi.messenger.data.model.ReactionSummary.reactions] shape; a group's
 * key -> reaction map is inverted by the caller). Most-used reaction first; in
 * each row "You" comes first, then the others by name. A key is "me" whatever
 * its base64 / base64url spelling.
 */
fun reactionWhoRows(
    byCode: Map<String, List<String>>,
    myKey: String,
    youLabel: String,
    nameFor: (String) -> String,
): List<ReactionWhoRow> = byCode.entries
    .filter { it.value.isNotEmpty() }
    .sortedByDescending { it.value.size }
    .map { (code, keys) ->
        val (mine, others) = keys.partition { sameReactorKey(it, myKey) }
        val names = (if (mine.isNotEmpty()) listOf(youLabel) else emptyList()) +
            others.map(nameFor).sortedBy { it.lowercase() }
        ReactionWhoRow(code, names)
    }

/** Key identity across base64 / base64url / padding spellings. */
fun sameReactorKey(a: String, b: String): Boolean {
    if (a.isEmpty() || b.isEmpty()) return false
    if (a.equals(b, ignoreCase = true)) return true
    fun n(k: String) = k.trim().replace('-', '+').replace('_', '/').replace("=", "")
    return n(a) == n(b)
}

/**
 * The "who reacted" sheet, opened by a long press on a chip (1:1 and groups).
 * Lists every reaction on the message with the people behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionWhoSheet(
    rows: List<ReactionWhoRow>,
    onDismiss: () -> Unit,
) {
    OshiModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .testTag(TAG_WHO_SHEET),
        ) {
            rows.forEach { row ->
                val who = stringResource(R.string.reaction_by, row.names.joinToString(", "))
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .semantics(mergeDescendants = true) { contentDescription = who }
                        .testTag(TAG_WHO_ROW_PREFIX + row.code),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(36.dp),
                    ) { ReactionGlyph(row.code, fontSize = 22.sp) }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f).padding(top = 6.dp)) {
                        row.names.forEach { name ->
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = row.names.size.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * A one-line preview "<reaction> <label>" (conversation list) with an OSHI
 * mark drawn inline instead of its emoji. Plain text for every other code.
 */
fun reactionPreviewText(code: String, label: String): AnnotatedString = buildAnnotatedString {
    if (OshiReaction.isOshiMark(code)) appendInlineContent(INLINE_MARK_ID, code) else append(code)
    append(" ")
    append(label)
}

/** The inline content [reactionPreviewText] refers to, sized for [fontSize] text. */
fun reactionPreviewInlineContent(code: String, fontSize: TextUnit): Map<String, InlineTextContent> =
    if (!OshiReaction.isOshiMark(code)) emptyMap() else mapOf(
        INLINE_MARK_ID to InlineTextContent(
            Placeholder(1.2.em, 1.2.em, PlaceholderVerticalAlign.TextCenter)
        ) { ReactionGlyph(code, fontSize = fontSize) }
    )

/** Pure numbers so the touch-target rule is testable. */
object ReactionBarMetrics {
    /** Touch target of a picker cell: Material's 48dp minimum. */
    const val CELL_DP = 48
    /** The visible disc inside it (unchanged from before). */
    const val DISC_DP = 40
    /** Touch target of a reaction chip under a bubble (the capsule stays small). */
    const val CHIP_TOUCH_DP = 48
}

private const val INLINE_MARK_ID = "oshi_reaction_mark"
const val TAG_PICKER = "reaction_picker"
const val TAG_NEW_HINT = "reaction_picker_new_hint"

/** The small "new" marker of [ReactionPicker] (cell corner and caption). */
@Composable
private fun NewReactionDot(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(7.dp)
            .background(MaterialTheme.colorScheme.tertiary, CircleShape)
    )
}
const val TAG_MORE = "reaction_picker_more"
const val TAG_CELL_PREFIX = "reaction_cell_"
const val TAG_CHIP_PREFIX = "reaction_chip_"
const val TAG_WHO_SHEET = "reaction_who_sheet"
const val TAG_WHO_ROW_PREFIX = "reaction_who_"
