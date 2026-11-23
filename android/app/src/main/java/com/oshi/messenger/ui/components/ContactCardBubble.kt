package com.oshi.messenger.ui.components

import com.oshi.messenger.ui.components.bubbleClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.data.model.ContactPayload
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.brandInk
import com.oshi.messenger.ui.theme.isDarkScheme
import com.oshi.messenger.ui.theme.Oshi

/**
 * ContactCardBubble - In-app shared contact card.
 *
 * Renders a [ContactPayload] (carried inside a CONTACT-typed message's
 * mediaData) as a tappable card with letter-avatar, alias, and a
 * truncated public-key prefix. Tapping invokes [onTap], which the host
 * uses to open or create a chat with the embedded contact.
 *
 * Mirrors iOS ContactCardView in OSHI/MediaMessageView.swift.
 */
@Composable
fun ContactCardBubble(
    payload: ContactPayload,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayName = payload.alias?.takeIf { it.isNotBlank() }
        ?: (payload.publicKey.take(8) + "...")
    val keyPrefix = payload.publicKey.take(12) + "..."
    val initial = displayName.firstOrNull()?.uppercase() ?: "?"

    Surface(
        modifier = modifier
            .widthIn(min = 220.dp, max = 280.dp)
            .bubbleClickable { onTap() },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            // Letter avatar — __AVATAR_COLOUR_LIST_EQUALS_CHAT_2026_11_09__
            // the card's contact in the colour the conversation list gives
            // that key (was one OSHIPurple disc for everyone).
            com.oshi.messenger.ui.theme.ContactAvatar(
                publicKey = payload.publicKey,
                name = displayName,
                size = 44.dp,
                initialCount = 1,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = displayName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = keyPrefix,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = brandInk(),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
