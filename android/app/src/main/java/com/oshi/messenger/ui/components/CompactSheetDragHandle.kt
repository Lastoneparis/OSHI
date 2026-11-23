package com.oshi.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * (N16, Anatole 13/11) A bottom-sheet grip that does not push the title down.
 *
 * Material's `BottomSheetDefaults.DragHandle` pads itself 22 dp above AND below
 * the 4 dp bar, and the attachment sheets then added their own 16 dp on top of
 * that: ~60 dp of empty sheet before "Share content", which is what he noticed.
 * Same bar, same colour role, 10 dp above / 6 dp below.
 */
@Composable
fun CompactSheetDragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(top = 10.dp, bottom = 6.dp)
            .size(width = 32.dp, height = 4.dp)
            .background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(2.dp)
            )
    )
}
