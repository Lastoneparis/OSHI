package com.oshi.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Surfing
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import com.oshi.messenger.R
import com.oshi.messenger.service.audio.CallVoiceEffectProcessor.VoiceEffect

/**
 * VoiceEffectSelector — horizontal scrollable row of voice-effect chips.
 *
 * Mirrors iOS CallVoiceEffect parity (None / Robot / Alien / Deep /
 * Chipmunk / Echo / Reverb / Anonymous). Tapping a chip applies the
 * effect to the OUTBOUND PCM stream — the receiving peer hears the
 * effect without any change on their side, since the effect is
 * stamped into the encrypted PCM before transmission.
 */
@Composable
fun VoiceEffectSelector(
    current: VoiceEffect,
    onSelect: (VoiceEffect) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VoiceEffect.entries.forEach { effect ->
            VoiceEffectChip(
                effect = effect,
                isSelected = effect == current,
                onClick = { onSelect(effect) }
            )
        }
    }
}

@Composable
private fun VoiceEffectChip(
    effect: VoiceEffect,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val label = stringResource(effect.labelRes())
    val icon = effect.icon()

    val bg = if (isSelected) Color.White else Color.Black.copy(alpha = 0.45f)
    val fg = if (isSelected) Color(0xFF5616EC) else Color.White

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = fg,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = fg,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

private fun VoiceEffect.labelRes(): Int = when (this) {
    VoiceEffect.NORMAL -> R.string.call_effect_normal
    VoiceEffect.ROBOT -> R.string.call_effect_robot
    VoiceEffect.ALIEN -> R.string.call_effect_alien
    VoiceEffect.DEEP -> R.string.call_effect_deep
    VoiceEffect.CHIPMUNK -> R.string.call_effect_chipmunk
    VoiceEffect.ECHO -> R.string.call_effect_echo
    VoiceEffect.REVERB -> R.string.call_effect_reverb
    VoiceEffect.ANONYMOUS -> R.string.call_effect_anonymous
}

private fun VoiceEffect.icon(): ImageVector = when (this) {
    VoiceEffect.NORMAL -> Icons.Default.RecordVoiceOver
    VoiceEffect.ROBOT -> Icons.Default.SmartToy
    VoiceEffect.ALIEN -> Icons.Default.AutoAwesome
    VoiceEffect.DEEP -> Icons.Default.GraphicEq
    VoiceEffect.CHIPMUNK -> Icons.Default.Surfing
    VoiceEffect.ECHO -> Icons.Default.Replay
    VoiceEffect.REVERB -> Icons.Default.Hearing
    VoiceEffect.ANONYMOUS -> Icons.Default.PrivacyTip
}
