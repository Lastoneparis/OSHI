package com.oshi.messenger.service

import android.app.Activity
import android.graphics.Color
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.oshi.messenger.R

/**
 * PrivacyCoverManager — Shows the OSHI logo overlay when the app goes to background.
 *
 * This makes the task switcher (recents) show the OSHI logo instead of app content,
 * matching iOS behavior.
 *
 * With screenshot protection ON (the default) the window is FLAG_SECURE, and
 * Android then never snapshots its pixels for recents: it draws a solid
 * placeholder in the TaskDescription background colour, which
 * ScreenshotProtectionManager sets to [COVER_COLOR] (API 33+), under the OSHI
 * icon and name. The cover still matters there: it is what is on screen during
 * the swipe-to-recents transition and in multi-window while paused. With
 * protection OFF the recents snapshot is real pixels, and it captures this cover.
 */
object PrivacyCoverManager {
    private const val TAG = "PrivacyCover"
    private const val COVER_VIEW_TAG = "oshi_privacy_cover"

    /** OSHI ink (#0A0A14). Also the recents placeholder fill, see ScreenshotProtectionManager. */
    const val COVER_COLOR: Int = 0xFF0A0A14.toInt()

        /**
         * 180 ms: long enough to be perceived as a transition rather than a
         * glitch, short enough that nobody waits for it. Same value as iOS.
         */
        private const val COVER_FADE_OUT_MS = 180L

    /**
     * Show the OSHI logo cover over the activity window.
     * Called from onPause — the system captures this view for recents.
     */
    fun showCover(activity: Activity) {
        try {
            val decorView = activity.window.decorView as? ViewGroup ?: return
            // Don't add duplicate covers
            if (decorView.findViewWithTag<View>(COVER_VIEW_TAG) != null) return

            val cover = FrameLayout(activity).apply {
                tag = COVER_VIEW_TAG
                setBackgroundColor(COVER_COLOR) // Dark background matching OSHI theme
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )

                // Center container with logo + text
                val container = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                }

                // OSHI app icon
                val logo = ImageView(activity).apply {
                    setImageResource(R.mipmap.ic_launcher)
                    val size = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, 96f,
                        activity.resources.displayMetrics
                    ).toInt()
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP, 16f,
                            activity.resources.displayMetrics
                        ).toInt()
                    }
                }
                container.addView(logo)

                // "OSHI" text
                val title = TextView(activity).apply {
                    text = "OSHI"
                    setTextColor(Color.WHITE)
                    textSize = 28f
                    gravity = Gravity.CENTER
                    letterSpacing = 0.15f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP, 8f,
                            activity.resources.displayMetrics
                        ).toInt()
                    }
                }
                container.addView(title)

                // Subtitle
                val subtitle = TextView(activity).apply {
                    text = "End-to-End Encrypted"
                    setTextColor(Color.parseColor("#888888"))
                    textSize = 13f
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                }
                container.addView(subtitle)

                addView(container)
            }

            decorView.addView(cover)
            Log.d(TAG, "Privacy cover shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show cover: ${e.message}")
        }
    }

    /**
     * Remove the OSHI logo cover when the app comes back to foreground.
     * Called from onResume.
     */
    fun hideCover(activity: Activity) {
        try {
            val decorView = activity.window.decorView as? ViewGroup ?: return
            val cover = decorView.findViewWithTag<View>(COVER_VIEW_TAG)
            if (cover != null) {
                // __PRIVACY_COVER_IS_NOT_A_RELAUNCH_2026_10_23__ Fade it out
                // rather than cutting it, matching `SceneDelegate` on iOS.
                //
                // `removeView` is a jump cut: one frame of logo, next frame of
                // conversation. The eye reads a cut as a NEW scene, so flicking
                // to another app and straight back looked like the app had
                // restarted — and in a messenger that reads as "did my message
                // survive?". A short cross-fade reads as coming back, which is
                // what actually happened.
                //
                // The tag is cleared FIRST so a rapid background/foreground pair
                // cannot find this dying view and decide a cover already exists.
                // The removal is in the end action, so an interrupted animation
                // still cannot leave the cover behind.
                cover.tag = null
                cover.animate()
                    .alpha(0f)
                    .setDuration(COVER_FADE_OUT_MS)
                    .withEndAction { decorView.removeView(cover) }
                    .start()
                Log.d(TAG, "Privacy cover fading out")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide cover: ${e.message}")
        }
    }
}
