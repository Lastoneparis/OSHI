package com.oshi.messenger.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * __LONG_PRESS_ANYWHERE_ON_THE_BUBBLE_2026_11_09__
 *
 * THE REPORT: "long-pressing a message takes real effort. On some messages it's
 * fine but on some I need to find a spot where it works."
 *
 * THE CAUSE: the bubble's long-press (message actions / reaction menu) and
 * double-tap live in ONE `combinedClickable` on the bubble container
 * (`ChatScreen.MessageBubble`, `GroupChatScreen.GroupMessageBubble`). In
 * Compose foundation 1.5 every inner `clickable`, and `ClickableText` (which
 * is `detectTapGestures`), CONSUMES the pointer-down on its first event; the
 * bubble's detector waits for an UNCONSUMED down (`awaitFirstDown`'s default)
 * and so never starts. Children are hit first, so wherever a child is
 * tappable the bubble's gesture is dead:
 *  - the message TEXT itself (`LinkifyText` was a `ClickableText` — the whole
 *    paragraph, not only its links). That is why a short message worked
 *    (mostly padding) and a long one needed a hunt for a spot of padding;
 *  - the reply quote, photos, videos, documents, contact cards, ticker chips,
 *    link previews, poll rows, location cards.
 *
 * THE FIX, two halves:
 *  1. `LinkifyText` no longer consumes anything but a short tap that lands ON
 *     a link (see `LinkPreview.kt`), so press, long-press and double-tap on the
 *     text fall through to the bubble.
 *  2. Every other tappable child inside a bubble uses [bubbleClickable]: the
 *     same single tap as before PLUS `onLongClick` = the bubble's own long-press,
 *     read from [LocalBubbleLongPress]. A child that must own the down to give
 *     its tap now gives the long-press back.
 *
 * Double-tap is NOT forwarded to children: adding `onDoubleClick` to a child
 * delays its single tap by the double-tap timeout, and opening a photo or a link
 * 300 ms late reads as lag. Double-tap toggles the OSHI reaction on the bubble
 * background and the message text (which is most of the bubble).
 */
val LocalBubbleLongPress = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * `clickable` for an element inside a message bubble: [onClick] as before, and a
 * long-press that opens the BUBBLE's menu rather than being swallowed. Outside a
 * bubble (no [LocalBubbleLongPress]) it is exactly `clickable`.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bubbleClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val longPress = LocalBubbleLongPress.current
    if (longPress == null) {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    } else {
        Modifier.combinedClickable(enabled = enabled, onClick = onClick, onLongClick = longPress)
    }
}

/** Test seam: which gesture set an inner element gets, as a pure decision. */
internal object BubbleGestureRule {
    /** A child forwards long-press iff it sits in a bubble that has one. */
    fun childForwardsLongPress(bubbleLongPress: (() -> Unit)?): Boolean = bubbleLongPress != null

    /** A tap on the message text is consumed by the text only when it hits a link. */
    fun textConsumesTap(hitsLink: Boolean, pressDurationMs: Long, longPressTimeoutMs: Long): Boolean =
        hitsLink && pressDurationMs < longPressTimeoutMs
}
