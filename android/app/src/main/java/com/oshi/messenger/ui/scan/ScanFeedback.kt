package com.oshi.messenger.ui.scan

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * The sound and haptic played when a QR code is read.
 *
 * ZXing's `setBeepEnabled(true)` plays a single flat `TONE_PROP_BEEP` through the
 * notification stream — the same blunt tone an error uses, at notification volume,
 * and it fires even when the phone is on silent. That is what the owner heard.
 *
 * This replaces it with a short **rising** two-note chime (A5 → E6). Rising is the
 * point: a confirmation that ends higher than it began reads as success, where a
 * flat or falling tone reads as an error or a dismissal. It is played on the
 * SYSTEM stream so it follows the phone's silent switch rather than overriding it,
 * and it is paired with a light haptic tick so the scan is still confirmed when
 * the device is muted — which is how most people scan.
 *
 * Deliberately not a bundled audio asset: two `ToneGenerator` notes cost no APK
 * size, need no decoding, and cannot be affected by the media focus of whatever
 * else is playing.
 */
object ScanFeedback {

    private const val TAG = "ScanFeedback"

    /** A5. Short enough not to feel like an alert. */
    private const val NOTE_ONE_MS = 90

    /** E6, a fifth above — the interval that makes it read as "done", not "error". */
    private const val NOTE_TWO_MS = 130

    fun success(context: Context) {
        playChime()
        tick(context)
    }

    private fun playChime() {
        var tone: ToneGenerator? = null
        try {
            // STREAM_SYSTEM honours the silent switch. STREAM_NOTIFICATION, which
            // ZXing uses, does not on every OEM skin — Samsung included.
            tone = ToneGenerator(AudioManager.STREAM_SYSTEM, 70)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, NOTE_ONE_MS)
            Thread.sleep(NOTE_ONE_MS.toLong())
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, NOTE_TWO_MS)
            Thread.sleep(NOTE_TWO_MS.toLong())
        } catch (e: Exception) {
            // A missing or busy audio path must never take the scan down with it:
            // the code has already been read by the time this runs.
            Log.w(TAG, "scan chime skipped: ${e.message}")
        } finally {
            runCatching { tone?.release() }
        }
    }

    private fun tick(context: Context) {
        try {
            val effect = VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(VibratorManager::class.java) ?: return
                manager.vibrate(CombinedVibration.createParallel(effect))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
                vibrator.vibrate(effect)
            }
        } catch (e: Exception) {
            Log.w(TAG, "scan haptic skipped: ${e.message}")
        }
    }

    /** Audio attributes used for the chime, exposed so a test can pin the stream. */
    val chimeAttributes: AudioAttributes
        get() = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
}
