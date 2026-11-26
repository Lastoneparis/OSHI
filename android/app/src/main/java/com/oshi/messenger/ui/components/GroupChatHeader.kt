package com.oshi.messenger.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.ui.theme.GlassTier
import com.oshi.messenger.ui.theme.brandInk
import com.oshi.messenger.ui.theme.glassFill
import com.oshi.messenger.ui.theme.glassHairline
import com.oshi.messenger.ui.theme.glassLift
import com.oshi.messenger.ui.theme.glassOn

// ─────────────────────────────────────────────────────────────────────────
// __GROUP_HEADER_ISLAND_2026_11_24__ The group conversation's top bar.
//
// « Keep the arrow on the left, then the loupe, but make a little island /
// cloud floating effect with name and number of members, and on the right,
// instead of the button with the 3 dots, put the picture of the group,
// clickable. » Left to right (mirrored in RTL by the Row itself):
//
//   (←)  (🔍)      ( Group name 🇨🇭 )      (avatar)
//                  (  30 members    )
//
// Every piece is a glass element of its own floating on the frosted band
// (`FrostedChatBarBackdrop`): a round 48 dp button, a capsule "island", a
// round avatar in a glass ring. With « Effet verre » on, they use the glass
// tokens (Chip fill, lit rim, light-theme lift); with it off, the surface at
// 92 % with a hairline outline and a soft shadow, so the island still floats.
//
// The ⋯ menu is gone from the bar. Its items live in the group info sheet's
// quick-action row, and a long-press on the avatar still opens the old menu
// (the [menu] slot is anchored to the avatar).
// ─────────────────────────────────────────────────────────────────────────

object GroupHeaderDefaults {
    /** Round buttons and the avatar ring: the 48 dp touch target, drawn at full size. */
    val ButtonSize: Dp = 48.dp
    /** The avatar inside its ring. */
    val AvatarSize: Dp = 42.dp
    /** Minimum island height; grows with the font scale. */
    val IslandMinHeight: Dp = 48.dp
    val IslandShape: Shape = RoundedCornerShape(50)
}

/** Glass (or, with glass off, tonal + shadow) treatment shared by the header's floating pieces. */
@Composable
private fun Modifier.headerFloat(shape: Shape, glass: Boolean): Modifier {
    return if (glass) {
        this
            .glassLift(shape, spread = 6.dp)
            .clip(shape)
            .background(glassFill(GlassTier.Chip))
            .glassHairline(shape)
    } else {
        val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        this
            .shadow(3.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .border(1.dp, outline, shape)
    }
}

@Composable
fun GroupHeaderRoundButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    glass: Boolean = glassOn(),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(GroupHeaderDefaults.ButtonSize)
            .headerFloat(CircleShape, glass)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * The floating capsule: name on one line (bold, ellipsized) and a quieter
 * second line — the member / subscriber count, or who is typing.
 */
@Composable
fun GroupHeaderIsland(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    infoLabel: String,
    modifier: Modifier = Modifier,
    subtitleIsActivity: Boolean = false,
    glass: Boolean = glassOn(),
) {
    Column(
        modifier = modifier
            .heightIn(min = GroupHeaderDefaults.IslandMinHeight)
            .widthIn(min = 96.dp)
            .headerFloat(GroupHeaderDefaults.IslandShape, glass)
            .clickable(role = Role.Button, onClickLabel = infoLabel, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("group_header_island"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            lineHeight = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            fontSize = 12.5.sp,
            lineHeight = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = if (subtitleIsActivity) brandInk() else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The whole bar. [avatar] draws the group picture (or its initial / gradient
 * fallback) at the size it is given; [menu] is composed inside the avatar's
 * box so a dropdown opened by [onAvatarLongPress] anchors to the avatar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupHeaderBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onInfo: () -> Unit,
    onAvatarLongPress: () -> Unit,
    avatar: @Composable (Dp) -> Unit,
    modifier: Modifier = Modifier,
    subtitleIsActivity: Boolean = false,
    searchActive: Boolean = false,
    menu: @Composable () -> Unit = {},
) {
    val glass = glassOn()
    val infoLabel = stringResource(R.string.group_header_info_a11y, title)
    val moreLabel = stringResource(R.string.group_chat_more_actions)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupHeaderRoundButton(
            onClick = onBack,
            contentDescription = stringResource(R.string.accessibility_back_button),
            glass = glass,
            modifier = Modifier.testTag("group_header_back"),
        ) {
            // Material 1.5's ArrowBack is not auto-mirrored: in RTL "back" points right.
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.graphicsLayer { scaleX = if (rtl) -1f else 1f },
            )
        }
        Spacer(Modifier.width(6.dp))
        GroupHeaderRoundButton(
            onClick = onSearch,
            contentDescription = stringResource(R.string.group_header_search_a11y),
            glass = glass,
            modifier = Modifier.testTag("group_header_search"),
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = if (searchActive) brandInk() else MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            GroupHeaderIsland(
                title = title,
                subtitle = subtitle,
                onClick = onInfo,
                infoLabel = infoLabel,
                subtitleIsActivity = subtitleIsActivity,
                glass = glass,
            )
        }
        Box {
            Box(
                modifier = Modifier
                    .size(GroupHeaderDefaults.ButtonSize)
                    .headerFloat(CircleShape, glass)
                    .combinedClickable(
                        role = Role.Button,
                        onClickLabel = infoLabel,
                        onLongClickLabel = moreLabel,
                        onLongClick = onAvatarLongPress,
                        onClick = onInfo,
                    )
                    .semantics { contentDescription = infoLabel }
                    .testTag("group_header_avatar"),
                contentAlignment = Alignment.Center,
            ) {
                // The picture's own description would be read after the label; the ring speaks for it.
                Box(Modifier.size(GroupHeaderDefaults.AvatarSize).clip(CircleShape).clearAndSetSemantics {}) {
                    avatar(GroupHeaderDefaults.AvatarSize)
                }
            }
            menu()
        }
    }
}
