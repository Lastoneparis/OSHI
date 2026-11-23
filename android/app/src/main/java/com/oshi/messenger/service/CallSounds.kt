package com.oshi.messenger.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.util.Log
import com.oshi.messenger.R

/**
 * __OSHI_CALL_SOUNDS_2026_09_28__ The OSHI call sounds, the same on iOS and Android:
 *  - ringtone   incoming call — rising A add9 phrase + its answer, 6 s cycle; the 30 s file
 *                swells from -9 dB over its first 6 s (then loops at full level after 30 s);
 *  - ringback   caller waiting for the peer — the same two phrases, softer, 8 s cycle;
 *  - connecting caller, "Connecting…" — one soft two-note breath every 1.6 s.
 * Soft FM "glass" voice + a small reverb (v2, see the generator's docstring for why).
 *
 * `res/raw/oshi_*.wav` are rendered by `tools/call_sounds/generate_call_sounds.py`, which also
 * writes iOS's `ringtone.caf`; iOS synthesises ringback + connecting with the same numbers
 * (`OshiCallTone`, VoiceCallManager.swift). Before this the ringtone was the device default and
 * the ringback `ToneGenerator.TONE_SUP_RINGTONE` — every OSHI call sounded like any other app.
 *
 * Every start falls back to the previous sound when the player cannot start, so a broken
 * decoder never makes a call silent.
 */
object CallSounds {
    private const val TAG = "CallSounds"

    /** A playing sound; [stop] is idempotent. */
    fun interface Handle { fun stop() }

    /**
     * The incoming-call ring, looping, on the ringer stream (ring volume, silent/vibrate
     * respected: nothing plays unless the ringer mode is NORMAL).
     */
    fun startRingtone(context: Context): Handle? {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio != null && audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            Log.d(TAG, "🔕 ringer mode ${audio.ringerMode} — no ring sound")
            return null
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        loop(context, R.raw.oshi_ringtone, attrs)?.let { return it }

        // fallback: the device ringtone, as before
        return try {
            val r: Ringtone? = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            r?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
            r?.let { Handle { it.stop() } }
        } catch (e: Exception) {
            Log.e(TAG, "fallback ringtone failed: ${e.message}"); null
        }
    }

    /** The caller's "Connecting…" pulse, on the voice-call stream like the ringback. */
    fun startConnecting(context: Context): Handle? = loop(context, R.raw.oshi_connecting, voiceAttrs())

    /** The caller's ringback. Falls back to the old `TONE_SUP_RINGTONE` cadence. */
    fun startRingback(context: Context, stillRinging: () -> Boolean): Handle? {
        loop(context, R.raw.oshi_ringback, voiceAttrs())?.let { return it }
        return try {
            val gen = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 100)
            val thread = Thread {
                try {
                    while (!Thread.currentThread().isInterrupted && stillRinging()) {
                        gen.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1000)
                        Thread.sleep(4000)
                    }
                } catch (_: InterruptedException) {
                }
            }.apply { isDaemon = true; start() }
            Handle { thread.interrupt(); runCatching { gen.release() } }
        } catch (e: Exception) {
            Log.e(TAG, "fallback ringback failed: ${e.message}"); null
        }
    }

    private fun voiceAttrs() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun loop(context: Context, res: Int, attrs: AudioAttributes): Handle? {
        var player: MediaPlayer? = null
        return try {
            val p = MediaPlayer()
            player = p
            p.setAudioAttributes(attrs)
            p.setDataSource(context, Uri.parse("android.resource://${context.packageName}/$res"))
            p.isLooping = true
            p.prepare()
            p.start()
            var stopped = false
            Handle {
                synchronized(p) {
                    if (stopped) return@synchronized
                    stopped = true
                    runCatching { p.stop() }
                    p.release()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not play ${context.resources.getResourceEntryName(res)}: ${e.message}")
            player?.release()
            null
        }
    }
}
