package com.oshi.messenger.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * __SCREENSHOT_NOTICE_2026_11_18__ Process-wide stream of "a screenshot was blocked"
 * moments (already rate-limited by [ScreenshotProtectionManager.onScreenCaptured]),
 * read by the root-hosted `ScreenCaptureNoticeHost`.
 */
object ScreenCaptureNotices {
    private val _events = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val events: SharedFlow<Long> = _events.asSharedFlow()
    fun emit(atMs: Long) { _events.tryEmit(atMs) }
}
