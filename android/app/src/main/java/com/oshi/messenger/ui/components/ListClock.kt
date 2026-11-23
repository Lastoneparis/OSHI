package com.oshi.messenger.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * __RELATIVE_TIMES_FROZE_2026_11_09__
 *
 * THE REPORT: "In the Chats tab, the time of the last message only updates when
 * I go to another screen. If the Chats tab is open, the phone locks, and I come
 * back after 5 minutes, it still says 'just now'."
 *
 * THE CAUSE: each row computed "Just now / 5m ago" from
 * `System.currentTimeMillis()` inside composition, and nothing ever invalidated
 * it — a row recomposed only when its DATA changed, so the label was a snapshot
 * of whenever that last happened.
 *
 * THE FIX: ONE clock for the whole list, read by every row as a parameter:
 *  - it ticks every [periodMs] (labels are minute-bucketed, so 30 s is enough
 *    and the list is not redrawn needlessly);
 *  - it jumps to the real time the moment the screen RESUMES (unlock, return
 *    from another app) — a ticking coroutine may have been frozen while the
 *    phone slept, and the first frame after unlock must already be right.
 * No per-row timers: a row only recomposes when the shared value changes.
 */
@Composable
fun rememberListClock(periodMs: Long = 30_000L): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            // Align to the next tick boundary so all labels advance together.
            kotlinx.coroutines.delay(periodMs - (System.currentTimeMillis() % periodMs))
            now.longValue = System.currentTimeMillis()
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) now.longValue = System.currentTimeMillis()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return now
}
