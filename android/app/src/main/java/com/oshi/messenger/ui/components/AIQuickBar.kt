package com.oshi.messenger.ui.components

import com.oshi.messenger.ui.theme.glassHairline
import com.oshi.messenger.ui.theme.GlassTier
import com.oshi.messenger.ui.theme.glassOpaque
import com.oshi.messenger.ui.theme.glassOn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.oshi.messenger.R

/**
 * AIQuickBar - Inline AI actions above keyboard
 * Matches iOS AIQuickBar component
 */
@Composable
fun AIQuickBar(
    messageText: String,
    isProcessing: Boolean = false,
    onCorrect: () -> Unit,
    onImprove: () -> Unit,
    onOpenFullAI: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (messageText.length < 3) return // Don't show for very short text

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isProcessing) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Color(0xFF5616EC)
            )
        } else {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Color(0xFF5616EC)
            )
        }

        val glass = glassOn()
        SuggestionChip(
            onClick = onCorrect,
            enabled = !isProcessing,
            label = { Text(stringResource(R.string.ai_quickbar_correct), fontSize = 12.sp) },
            shape = RoundedCornerShape(16.dp),
            // __OLED_GLASS_2026_11_16__ OLED black: glass chips.
            colors = if (glass) SuggestionChipDefaults.suggestionChipColors(containerColor = glassOpaque(GlassTier.Chip))
                else SuggestionChipDefaults.suggestionChipColors(),
            border = if (glass) null else SuggestionChipDefaults.suggestionChipBorder(),
            modifier = if (glass) Modifier.glassHairline(RoundedCornerShape(16.dp), highlight = false, visibleHeight = 32.dp) else Modifier
        )

        SuggestionChip(
            onClick = onImprove,
            enabled = !isProcessing,
            label = { Text(stringResource(R.string.ai_quickbar_improve), fontSize = 12.sp) },
            shape = RoundedCornerShape(16.dp),
            // __OLED_GLASS_2026_11_16__ OLED black: glass chips.
            colors = if (glass) SuggestionChipDefaults.suggestionChipColors(containerColor = glassOpaque(GlassTier.Chip))
                else SuggestionChipDefaults.suggestionChipColors(),
            border = if (glass) null else SuggestionChipDefaults.suggestionChipBorder(),
            modifier = if (glass) Modifier.glassHairline(RoundedCornerShape(16.dp), highlight = false, visibleHeight = 32.dp) else Modifier
        )

        SuggestionChip(
            onClick = onOpenFullAI,
            enabled = !isProcessing,
            label = { Text("...", fontSize = 12.sp) },
            shape = RoundedCornerShape(16.dp),
            // __OLED_GLASS_2026_11_16__ OLED black: glass chips.
            colors = if (glass) SuggestionChipDefaults.suggestionChipColors(containerColor = glassOpaque(GlassTier.Chip))
                else SuggestionChipDefaults.suggestionChipColors(),
            border = if (glass) null else SuggestionChipDefaults.suggestionChipBorder(),
            modifier = if (glass) Modifier.glassHairline(RoundedCornerShape(16.dp), highlight = false, visibleHeight = 32.dp) else Modifier
        )
    }
}
