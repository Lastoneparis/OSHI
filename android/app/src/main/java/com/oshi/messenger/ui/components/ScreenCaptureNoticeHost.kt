package com.oshi.messenger.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.oshi.messenger.R
import com.oshi.messenger.service.ScreenCaptureNotices
import com.oshi.messenger.ui.theme.glassSurface
import kotlinx.coroutines.delay

/** How long the pill stays up. */
internal const val SCREEN_CAPTURE_NOTICE_VISIBLE_MS = 3_000L

/**
 * __SCREENSHOT_NOTICE_2026_11_18__ Root-hosted (MainActivity, next to CallRatingHost).
 * A screenshot over a protected window comes out black; this says why, as a small
 * glass pill with the OSHI "O" at the top of the screen — never a dialog, never a
 * button to press. It lives in a Popup (its own window, which inherits FLAG_SECURE
 * from the activity) so it floats above whatever screen is open.
 */
@Composable
fun ScreenCaptureNoticeHost() {
    var shownAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { ScreenCaptureNotices.events.collect { shownAt = it } }
    LaunchedEffect(shownAt) {
        if (shownAt != null) { delay(SCREEN_CAPTURE_NOTICE_VISIBLE_MS); shownAt = null }
    }
    if (shownAt == null) return
    Popup(alignment = Alignment.TopCenter, properties = PopupProperties(focusable = false)) {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
        ) {
            ScreenCaptureNoticePill(Modifier.statusBarsPadding().padding(top = 8.dp, start = 16.dp, end = 16.dp))
        }
    }
}

/** The pill itself (also drawn by the screenshot test). */
@Composable
fun ScreenCaptureNoticePill(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    val dark = MaterialTheme.colorScheme.surface.let { (it.red + it.green + it.blue) / 3f < 0.5f }
    val fill = if (dark) Color(0xF71C1A24) else Color(0xF7FFFFFF)
    Row(
        modifier = modifier
            .widthIn(max = 420.dp)
            .shadow(10.dp, shape, clip = false)
            .glassSurface(shape, fill)
            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        OshiOMark(size = 28.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.screenshot_blocked_notice),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            lineHeight = 18.sp,
        )
    }
}
