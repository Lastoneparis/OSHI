package com.oshi.messenger.ui.components

import com.oshi.messenger.ui.theme.glassHairline
import com.oshi.messenger.ui.theme.GlassTier
import com.oshi.messenger.ui.theme.glassOn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import androidx.compose.ui.res.stringResource

/**
 * SmartRepliesBar - Shows 3 AI-generated reply suggestions as tappable chips.
 * Displayed above AIQuickBar when message text is empty and replies are available.
 * Matches iOS smart reply functionality.
 */
@Composable
fun SmartRepliesBar(
    replies: List<String>,
    onReplySelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (replies.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        // Label
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = Color(0xFF5616EC)
            )
            Text(
                stringResource(R.string.smart_replies_title),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF5616EC)
            )
        }

        // Reply chips
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val glass = glassOn()
            replies.forEach { reply ->
                SuggestionChip(
                    onClick = { onReplySelected(reply) },
                    label = {
                        Text(
                            text = if (reply.length > 40) reply.take(37) + "..." else reply,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    },
                    shape = RoundedCornerShape(16.dp),
                    // __OLED_GLASS_2026_11_16__ OLED black: glass chips, rim instead of outline.
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = if (glass) GlassTier.Chip.opaque else Color(0xFF5616EC).copy(alpha = 0.08f)
                    ),
                    border = if (glass) null else SuggestionChipDefaults.suggestionChipBorder(),
                    modifier = if (glass) Modifier.glassHairline(RoundedCornerShape(16.dp), highlight = false, visibleHeight = 32.dp) else Modifier
                )
            }
        }
    }
}
