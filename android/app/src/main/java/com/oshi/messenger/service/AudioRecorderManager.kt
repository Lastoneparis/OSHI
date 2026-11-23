package com.oshi.messenger.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.oshi.messenger.service.audio.VoiceEffectProcessor
import com.oshi.messenger.service.audio.VoiceEffectProcessor.VoiceEffect
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AudioRecorderManager - Compatible with iOS AudioRecorderManager.swift
 * Handles voice message recording with waveform visualization and voice effects
 */
@Singleton
class AudioRecorderManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AudioRecorderManager"
        private const val SAMPLE_RATE = 44100
        private const val MAX_RECORDING_DURATION_MS = 300_000L  // 5 minutes max
        private const val WAVEFORM_UPDATE_INTERVAL_MS = 100L
        const val PREVIEW_BARS = 48
    }

    // Voice effect processor (iOS-compatible)
    private val effectProcessor = VoiceEffectProcessor(context)

    // =========================================================================
    // STATE
    // =========================================================================

    enum class RecordingState {
        IDLE,
        RECORDING,
        PAUSED,
        STOPPED
    }

    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0L)  // in milliseconds
    val recordingDuration: StateFlow<Long> = _recordingDuration.asStateFlow()

    private val _waveformAmplitudes = MutableStateFlow<List<Float>>(emptyList())
    val waveformAmplitudes: StateFlow<List<Float>> = _waveformAmplitudes.asStateFlow()

    private val _isCancelled = MutableStateFlow(false)
    val isCancelled: StateFlow<Boolean> = _isCancelled.asStateFlow()

    // 🎙️ Voice effect selection (matching iOS VoiceEffect enum)
    private val _selectedEffect = MutableStateFlow(VoiceEffect.NORMAL)
    val selectedEffect: StateFlow<VoiceEffect> = _selectedEffect.asStateFlow()

    /**
     * Set voice effect for next recording
     */
    fun setVoiceEffect(effect: VoiceEffect) {
        _selectedEffect.value = effect
        Log.d(TAG, "Voice effect set to: ${effect.displayName}")
    }

    /**
     * Get all available voice effects
     */
    fun getAvailableEffects(): List<VoiceEffect> = VoiceEffect.entries

    // =========================================================================
    // INTERNAL
    // =========================================================================

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var recordingJob: Job? = null
    private var waveformJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var recordingStartTime = 0L
    private val amplitudes = mutableListOf<Float>()
    /** Every level of the take (the list above keeps only the last 50 for the live bar). */
    private val allAmplitudes = mutableListOf<Float>()

    // =========================================================================
    // PERMISSION CHECK
    // =========================================================================

    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    // =========================================================================
    // RECORDING CONTROL
    // =========================================================================

    /**
     * Start recording a voice message
     * @return true if recording started successfully
     */
    fun startRecording(): Boolean {
        if (!hasRecordPermission()) {
            Log.e(TAG, "No RECORD_AUDIO permission")
            return false
        }

        if (_recordingState.value == RecordingState.RECORDING) {
            Log.w(TAG, "Already recording")
            return false
        }

        try {
            // Create temp file for recording
            val audioDir = File(context.cacheDir, "voice_messages").apply { mkdirs() }
            audioFile = File(audioDir, "voice_${UUID.randomUUID()}.m4a")

            // Initialize MediaRecorder
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(SAMPLE_RATE)
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }

            _recordingState.value = RecordingState.RECORDING
            _isCancelled.value = false
            recordingStartTime = System.currentTimeMillis()
            amplitudes.clear()
            synchronized(allAmplitudes) { allAmplitudes.clear() }
            _waveformAmplitudes.value = emptyList()
            _recordingDuration.value = 0

            // Start duration and waveform updates
            startRecordingUpdates()

            Log.d(TAG, "Started recording to ${audioFile?.absolutePath}")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            cleanup()
            return false
        }
    }

    /**
     * Stop recording and return the audio data
     * @return Pair of audio data bytes and duration in seconds, or null if cancelled/failed
     */
    fun stopRecording(): Pair<ByteArray, Float>? =
        stopInternal(applyEffect = true)?.let { Pair(it.bytes, it.durationSeconds) }

    /**
     * __VOICE_PREVIEW_2026_09_29__ A finished recording, BEFORE any voice effect, for the
     * preview sheet (iOS `AudioPreviewSheet`): the person hears it, tries voices, picks
     * "view once", then sends. [initialEffect] is the voice chosen on the recording bar.
     */
    class VoiceClip(
        val bytes: ByteArray,
        val durationSeconds: Float,
        /** The whole take summarised to [PREVIEW_BARS] bars, 0..1. */
        val waveform: List<Float>,
        val initialEffect: VoiceEffect,
    )

    /** Stops like [stopRecording] but leaves the effect to the preview. */
    fun stopRecordingForPreview(): VoiceClip? = stopInternal(applyEffect = false)

    /**
     * Renders [effect] over [bytes] OFF the main thread (decode → DSP → AAC re-encode is
     * hundreds of ms to seconds; it used to run inside the send button's click handler).
     * @return the processed M4A, the input for NORMAL, or null if the effect failed —
     * [VoiceEffectProcessor.applyEffect] hands the input back on failure, and sending
     * that under a "Robot" label would be a silent lie.
     */
    suspend fun renderEffect(bytes: ByteArray, effect: VoiceEffect): ByteArray? =
        withContext(Dispatchers.Default) {
            if (effect == VoiceEffect.NORMAL) return@withContext bytes
            val out = effectProcessor.applyEffect(bytes, effect)
            if (out === bytes) null else out
        }

    private fun summariseWaveform(samples: List<Float>): List<Float> {
        if (samples.isEmpty()) return List(PREVIEW_BARS) { 0.15f }
        val peak = samples.maxOrNull()?.takeIf { it > 0f } ?: 1f
        return List(PREVIEW_BARS) { i ->
            val from = i * samples.size / PREVIEW_BARS
            val to = maxOf(from + 1, (i + 1) * samples.size / PREVIEW_BARS).coerceAtMost(samples.size)
            val slice = samples.subList(from.coerceAtMost(samples.size - 1), to)
            (slice.average().toFloat() / peak).coerceIn(0.08f, 1f)
        }
    }

    private fun stopInternal(applyEffect: Boolean): VoiceClip? {
        if (_recordingState.value != RecordingState.RECORDING) {
            Log.w(TAG, "Not recording")
            return null
        }

        return try {
            // Stop updates
            recordingJob?.cancel()
            waveformJob?.cancel()

            // Stop recorder
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null

            _recordingState.value = RecordingState.STOPPED

            // Check if cancelled
            if (_isCancelled.value) {
                Log.d(TAG, "Recording was cancelled")
                cleanup()
                return null
            }

            // Read audio data
            val file = audioFile ?: return null
            if (!file.exists()) {
                Log.e(TAG, "Audio file not found")
                return null
            }

            var audioData = file.readBytes()
            val durationSeconds = _recordingDuration.value / 1000f
            val waveform = summariseWaveform(synchronized(allAmplitudes) { allAmplitudes.toList() })

            // 🎙️ Apply voice effect if selected (matching iOS behavior)
            val effect = _selectedEffect.value
            if (applyEffect && effect != VoiceEffect.NORMAL) {
                Log.d(TAG, "Applying voice effect: ${effect.displayName}")
                audioData = effectProcessor.applyEffect(audioData, effect)
                Log.d(TAG, "Effect applied: ${audioData.size} bytes")
            }

            Log.d(TAG, "Recording complete: ${audioData.size} bytes, ${durationSeconds}s, effect: ${effect.displayName}")

            // __PLAINTEXT_LEFTOVERS_2026_09_24__ The caller sends the in-memory bytes (and
            // the store seals them); the plaintext .m4a in cacheDir must not outlive this call.
            // It used to be kept "in case we need it" — i.e. every sent voice note, forever.
            file.delete()
            audioFile = null
            VoiceClip(audioData, durationSeconds, waveform, effect)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording: ${e.message}", e)
            cleanup()
            null
        } finally {
            _recordingState.value = RecordingState.IDLE
        }
    }

    /**
     * Cancel recording without saving
     */
    fun cancelRecording() {
        Log.d(TAG, "Cancelling recording")
        _isCancelled.value = true

        try {
            recordingJob?.cancel()
            waveformJob?.cancel()

            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder: ${e.message}")
        }

        cleanup()
        _recordingState.value = RecordingState.IDLE
    }

    /**
     * Reset state after sending or cancelling
     */
    fun reset() {
        cleanup()
        _recordingState.value = RecordingState.IDLE
        _recordingDuration.value = 0
        _waveformAmplitudes.value = emptyList()
        _isCancelled.value = false
        _selectedEffect.value = VoiceEffect.NORMAL
    }

    // =========================================================================
    // INTERNAL HELPERS
    // =========================================================================

    private fun startRecordingUpdates() {
        // Duration update
        recordingJob = scope.launch {
            while (isActive && _recordingState.value == RecordingState.RECORDING) {
                val elapsed = System.currentTimeMillis() - recordingStartTime
                _recordingDuration.value = elapsed

                // Check max duration
                if (elapsed >= MAX_RECORDING_DURATION_MS) {
                    Log.d(TAG, "Max recording duration reached")
                    withContext(Dispatchers.Main) {
                        stopRecording()
                    }
                    break
                }

                delay(100)
            }
        }

        // Waveform update
        waveformJob = scope.launch {
            while (isActive && _recordingState.value == RecordingState.RECORDING) {
                try {
                    val amplitude = mediaRecorder?.maxAmplitude ?: 0
                    val normalizedAmplitude = (amplitude / 32767f).coerceIn(0f, 1f)
                    amplitudes.add(normalizedAmplitude)
                    synchronized(allAmplitudes) { allAmplitudes.add(normalizedAmplitude) }

                    // Keep last 50 samples for waveform display
                    if (amplitudes.size > 50) {
                        amplitudes.removeAt(0)
                    }

                    _waveformAmplitudes.value = amplitudes.toList()
                } catch (e: Exception) {
                    // MediaRecorder might throw if stopped during read
                }
                delay(WAVEFORM_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun cleanup() {
        mediaRecorder?.release()
        mediaRecorder = null

        // Delete temp file
        audioFile?.delete()
        audioFile = null

        recordingJob?.cancel()
        waveformJob?.cancel()
        amplitudes.clear()
    }

    fun destroy() {
        cleanup()
        scope.cancel()
    }
}

/**
 * AudioPlayerManager - Handles voice message playback.
 *
 * ## __VOICE_MINIPLAYER_2026_10_09__ Why playback does not live in the bubble
 *
 * On iOS every voice bubble owned its own `AudioPlayerManager` and called `stop()` from
 * its `onDisappear`, so leaving the conversation — or merely switching tab — cut the
 * message off mid-sentence. Android never had that defect: this object is an
 * application-scoped `@Singleton` (`AppModule.provideAudioPlayerManager`), the bubbles
 * only ever *drive* it, and nothing disposes it with a screen. Navigating away has
 * always kept the audio running.
 *
 * What it lacked is the other half of the iOS fix: while the audio survived, every
 * control for it went away with the conversation. A note playing from a thread the user
 * has left could not be paused, scrubbed or stopped from anywhere.
 *
 * So this class now also publishes what a floating player needs —
 * [currentTitle] for "what am I listening to", [visibleOwners] so the pill hides while
 * the bubble that owns the note is on screen and would be a duplicate of it, and
 * [miniPlayerDismissed] for the ✕ — and `VoiceNoteMiniPlayer` renders it once at the
 * Compose root. iOS `OSHI/VoiceNotePlayback.swift` + `OSHI/VoiceNoteMiniPlayerView.swift`.
 */
@Singleton
class AudioPlayerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AudioPlayerManager"

        /**
         * Should the floating mini player be on screen?
         *
         * Pure so the rule is assertable without a device, which matters because it is
         * the one piece of this feature that is a *decision* rather than a drawing.
         *
         * @param currentId      the loaded note, or null when nothing is loaded
         * @param title          what the pill would say. Null means the caller did not
         *                       opt in, and that is the opt-in: the record-preview sheet
         *                       and the view-once full-screen player both drive this
         *                       same singleton, and both are consumed inside their own
         *                       screen. A floating player over a modal sheet — or one
         *                       offering to resume a view-once note from somewhere else
         *                       — is a bug, not a feature. iOS draws the same line by
         *                       keeping `ViewOnceAudioPlayer` off `VoiceNotePlayback`.
         * @param isLive         the player holds a note that is playing or paused —
         *                       a note that has finished takes the pill away by itself
         * @param visibleOwners  message ids of the voice bubbles currently on screen
         * @param dismissed      the user tapped ✕
         */
        fun showsMiniPlayer(
            currentId: String?,
            title: String?,
            isLive: Boolean,
            visibleOwners: Set<String>,
            dismissed: Boolean,
        ): Boolean {
            if (currentId == null || title == null || !isLive || dismissed) return false
            // The owning bubble already offers play/pause, a waveform and the speed
            // control; a floating copy on top of it is clutter, not a feature.
            return !visibleOwners.contains(currentId)
        }
    }

    // =========================================================================
    // STATE
    // =========================================================================

    enum class PlaybackState {
        IDLE,
        LOADING,
        PLAYING,
        PAUSED,
        STOPPED,
        ERROR
    }

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _currentMessageId = MutableStateFlow<String?>(null)
    val currentMessageId: StateFlow<String?> = _currentMessageId.asStateFlow()

    /**
     * __VOICE_MINIPLAYER_2026_10_09__ Who the floating player names while the note
     * plays outside the conversation it came from. Null when the caller did not supply
     * one (the preview sheet, which never leaves its own screen).
     */
    private val _currentTitle = MutableStateFlow<String?>(null)
    val currentTitle: StateFlow<String?> = _currentTitle.asStateFlow()

    /**
     * Message ids of the voice bubbles currently on screen. See
     * [AudioPlayerManager.showsMiniPlayer].
     */
    private val _visibleOwners = MutableStateFlow<Set<String>>(emptySet())
    val visibleOwners: StateFlow<Set<String>> = _visibleOwners.asStateFlow()

    /**
     * The ✕ was tapped. Deliberately NOT "hide but keep playing": a user who closes the
     * player means "stop talking to me", so [closeMiniPlayer] stops the audio too. The
     * flag exists so the pill cannot flash back for the same note.
     */
    private val _miniPlayerDismissed = MutableStateFlow(false)
    val miniPlayerDismissed: StateFlow<Boolean> = _miniPlayerDismissed.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)  // 0.0 to 1.0
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    private val _playbackDuration = MutableStateFlow(0)  // in milliseconds
    val playbackDuration: StateFlow<Int> = _playbackDuration.asStateFlow()

    private val _currentPosition = MutableStateFlow(0)  // in milliseconds
    val currentPosition: StateFlow<Int> = _currentPosition.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(true)  // Default: speaker (loud)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    // Playback speed: cycles 1.0x → 1.5x → 2.0x → 1.0x.
    // Persisted in the `oshi_settings` prefs store under key `audio_playback_speed`
    // so the last-used rate applies across voice notes and app sessions.
    private val playbackPrefs by lazy {
        context.getSharedPreferences("oshi_settings", Context.MODE_PRIVATE)
    }
    private val _playbackSpeed = MutableStateFlow(
        playbackPrefs.getFloat("audio_playback_speed", 1.0f)
    )
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    // =========================================================================
    // INTERNAL
    // =========================================================================

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    /**
     * __VOICE_SCRUB_2026_10_26__ Where a scrub asked playback to begin, for a note
     * that was not loaded yet. `prepareAsync` means the head cannot be placed at
     * the moment of the tap, so the position waits here and is applied inside
     * `setOnPreparedListener`, before `start()` — otherwise the note audibly
     * starts at zero and jumps.
     */
    private var pendingSeek: Pair<String, Float>? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

    // =========================================================================
    // PLAYBACK CONTROL
    // =========================================================================

    /**
     * Play a voice message
     * @param messageId unique identifier for the message
     * @param audioData raw audio bytes (m4a format)
     * @param title     __VOICE_MINIPLAYER_2026_10_09__ what the floating player names
     *                  this note once the user has navigated away from its bubble.
     *                  Null for a player that never leaves its own screen (the record
     *                  preview sheet, a view-once note).
     */
    fun play(
        messageId: String,
        audioData: ByteArray,
        title: String? = null,
        startAtProgress: Float? = null
    ) {
        // __VOICE_MINIPLAYER_2026_10_09__ A fresh tap on play re-arms the pill: a user
        // who closed the last one is not refusing the next note.
        _miniPlayerDismissed.value = false
        // If same message, toggle pause/play
        if (_currentMessageId.value == messageId) {
            when (_playbackState.value) {
                PlaybackState.PLAYING -> {
                    pause()
                    return
                }
                PlaybackState.PAUSED -> {
                    resume()
                    return
                }
                else -> { /* Continue to start fresh */ }
            }
        }

        // Stop any current playback
        stop()

        try {
            _playbackState.value = PlaybackState.LOADING
            // Only when this call carries one: a position armed by
            // [armPendingSeek] just before a caller's own `play()` must survive
            // this assignment, which is the whole point of arming it.
            if (startAtProgress != null) pendingSeek = messageId to startAtProgress
            _currentMessageId.value = messageId
            // AFTER `stop()`, which clears it — setting it before the stop would wipe
            // the title of the note we are about to play.
            _currentTitle.value = title

            // Apply speaker routing — but never while a call owns the route.
            applySpeakerRouting()

            // Initialize MediaPlayer
            mediaPlayer = MediaPlayer().apply {
                // __PLAINTEXT_LEFTOVERS_2026_09_24__ Played straight from memory. The old
                // path wrote each decrypted note to cacheDir/voice_playback/ (up to 20 kept,
                // never cleared across restarts): plaintext audio at rest.
                setDataSource(ByteArrayMediaDataSource(audioData))
                setOnPreparedListener { mp ->
                    _playbackDuration.value = mp.duration
                    // __VOICE_SCRUB_2026_10_26__ A tap in the middle of the
                    // waveform of a note nobody had started must begin THERE.
                    // Keyed by message id so a position armed for one note can
                    // never be applied to the next one.
                    val from = pendingSeek?.takeIf { it.first == messageId }?.second
                    pendingSeek = null
                    if (from != null && mp.duration > 0) {
                        val position = (from.coerceIn(0f, 1f) * mp.duration).toInt()
                        try { mp.seekTo(position) } catch (e: Exception) {
                            Log.w(TAG, "Initial seek failed: ${e.message}")
                        }
                        _playbackProgress.value = from
                        _currentPosition.value = position
                    } else {
                        _playbackProgress.value = 0f
                        _currentPosition.value = 0
                    }
                    // Apply persisted playback speed before start. Note: setPlaybackParams
                    // on some devices auto-starts the player, so we apply before start().
                    val desiredSpeed = _playbackSpeed.value
                    if (desiredSpeed != 1.0f) {
                        try {
                            mp.playbackParams = PlaybackParams().setSpeed(desiredSpeed)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to set initial playback speed: ${e.message}")
                        }
                    }
                    mp.start()
                    _playbackState.value = PlaybackState.PLAYING
                    startProgressUpdates()
                    Log.d(TAG, "Started playing: $messageId, duration: ${mp.duration}ms, speed: ${desiredSpeed}x")
                }
                setOnCompletionListener {
                    _playbackProgress.value = 1f
                    _playbackState.value = PlaybackState.STOPPED
                    progressJob?.cancel()
                    // A note that simply ran out must give the route back too, not
                    // only one the user stopped by hand.
                    restoreSpeakerRouting()
                    Log.d(TAG, "Playback complete: $messageId")
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    _playbackState.value = PlaybackState.ERROR
                    progressJob?.cancel()
                    true
                }
                prepareAsync()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback: ${e.message}", e)
            _playbackState.value = PlaybackState.ERROR
        }
    }

    /**
     * Pause playback
     */
    fun pause() {
        if (_playbackState.value != PlaybackState.PLAYING) return

        try {
            mediaPlayer?.pause()
            _playbackState.value = PlaybackState.PAUSED
            progressJob?.cancel()
            Log.d(TAG, "Paused playback")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause: ${e.message}")
        }
    }

    /**
     * Resume playback
     */
    fun resume() {
        if (_playbackState.value != PlaybackState.PAUSED) return

        try {
            mediaPlayer?.start()
            _playbackState.value = PlaybackState.PLAYING
            startProgressUpdates()
            Log.d(TAG, "Resumed playback")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume: ${e.message}")
        }
    }

    /**
     * Stop playback completely
     */
    fun stop() {
        progressJob?.cancel()

        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping player: ${e.message}")
        }

        mediaPlayer = null
        _playbackState.value = PlaybackState.IDLE
        _currentMessageId.value = null
        _currentTitle.value = null      // __VOICE_MINIPLAYER_2026_10_09__
        _playbackProgress.value = 0f
        _currentPosition.value = 0

        // Hand the global routing switch back — see [applySpeakerRouting].
        restoreSpeakerRouting()
    }

    /**
     * __VOICE_MINIPLAYER_2026_10_09__ Back to the start and playing again — the mini
     * player's replay. A no-op when nothing is loaded.
     */
    fun replay() {
        val player = mediaPlayer ?: return
        try {
            player.seekTo(0)
            _playbackProgress.value = 0f
            _currentPosition.value = 0
            if (_playbackState.value != PlaybackState.PLAYING) {
                player.start()
                _playbackState.value = PlaybackState.PLAYING
            }
            startProgressUpdates()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to replay: ${e.message}")
        }
    }

    /**
     * __VOICE_MINIPLAYER_2026_10_09__ The ✕: stop the audio and take the pill away.
     *
     * [stop] already clears `currentMessageId`, which is what hides the pill; the flag
     * is belt and braces for the window before the state flows settle.
     */
    fun closeMiniPlayer() {
        _miniPlayerDismissed.value = true
        stop()
    }

    /** A voice bubble came on screen. See [AudioPlayerManager.showsMiniPlayer]. */
    fun ownerAppeared(messageId: String) {
        _visibleOwners.value = _visibleOwners.value + messageId
    }

    /** A voice bubble left the screen. */
    fun ownerDisappeared(messageId: String) {
        _visibleOwners.value = _visibleOwners.value - messageId
    }

    /**
     * Seek to position (0.0 to 1.0)
     */
    fun seekTo(progress: Float) {
        val player = mediaPlayer ?: return
        val position = (progress.coerceIn(0f, 1f) * player.duration).toInt()

        try {
            player.seekTo(position)
            _playbackProgress.value = progress.coerceIn(0f, 1f)
            _currentPosition.value = position
        } catch (e: Exception) {
            Log.e(TAG, "Failed to seek: ${e.message}")
        }
    }

    /**
     * __VOICE_SCRUB_2026_10_26__ Seek a note that may not be the loaded one — the
     * Android half of iOS `VoiceNotePlayback.seek(id:title:data:toProgress:startIfIdle:)`.
     *
     * [seekTo] alone can only move whatever is already in the [MediaPlayer], so
     * scrubbing the waveform of a note nobody had started did nothing.
     *
     * [startIfIdle] is what separates a tap from a drag: a tap on the waveform
     * means "play from here", a drag only moves the head and leaves a paused note
     * paused.
     */
    /**
     * Place the head for the note [messageId] the moment it finishes preparing,
     * for callers that start playback through their own `play()` so the floating
     * player still gets the sender's name. Keyed by id: an armed position is
     * dropped rather than applied to a different note.
     */
    fun armPendingSeek(messageId: String, progress: Float) {
        pendingSeek = messageId to progress.coerceIn(0f, 1f)
    }

    fun scrubTo(
        messageId: String,
        audioData: ByteArray,
        title: String?,
        progress: Float,
        startIfIdle: Boolean
    ) {
        val p = progress.coerceIn(0f, 1f)

        if (_currentMessageId.value == messageId && mediaPlayer != null) {
            seekTo(p)
            // `play()` on the loaded note TOGGLES, so a tap must not go through
            // it — it would pause the note the user just asked to hear from a new
            // position. And `resume()` only accepts PAUSED, while a note that ran
            // to the end sits in STOPPED with a live player: both have to restart
            // here, or tapping the middle of a finished note moved the head and
            // stayed silent.
            if (startIfIdle && _playbackState.value != PlaybackState.PLAYING) {
                try {
                    mediaPlayer?.start()
                    _playbackState.value = PlaybackState.PLAYING
                    startProgressUpdates()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start after scrub: ${e.message}")
                }
            }
            return
        }

        // A drag over a note that was never started has nothing to move, and
        // loading it would seize the audio route on a gesture that may still
        // turn out to be a scroll.
        if (!startIfIdle) return

        play(messageId, audioData, title, startAtProgress = p)
    }

    /**
     * Toggle between speaker and earpiece for voice message playback
     */
    fun toggleSpeaker() {
        _isSpeakerOn.value = !_isSpeakerOn.value
        applySpeakerRouting()
        Log.d(TAG, "Speaker: ${if (_isSpeakerOn.value) "ON" else "OFF (earpiece)"}")
    }

    // =========================================================================
    // 📞 CALL-AWARE ROUTING
    // =========================================================================

    /**
     * Is some call — ours or the carrier's — currently the owner of audio routing?
     *
     * `AudioManager.mode` is the only honest answer available here, and it is a
     * better one than any app-internal call flag would be: it is true for OUR calls
     * (`EnhancedCallManager.kt:3614` sets `MODE_IN_COMMUNICATION` and `:4011` puts
     * it back to `MODE_NORMAL` at teardown), for a **cellular** call the user is on
     * while the app is open (`MODE_IN_CALL`), and for an incoming call that is still
     * ringing (`MODE_RINGTONE`). None of those are ours to re-route.
     */
    private fun aCallOwnsTheAudioRoute(): Boolean = when (audioManager.mode) {
        android.media.AudioManager.MODE_IN_COMMUNICATION,
        android.media.AudioManager.MODE_IN_CALL,
        android.media.AudioManager.MODE_RINGTONE -> true
        else -> false
    }

    /**
     * Point voice-note playback at the speaker or the earpiece — **unless a call is
     * up**, in which case do nothing at all.
     *
     * ## What this is guarding
     *
     * `isSpeakerphoneOn` is not a per-player setting. It is one global routing
     * switch, and during a call it is the switch the call is steering
     * (`EnhancedCallManager.kt:3642/3645`, `:4782` when the user taps the speaker
     * button mid-call). Playing a voice note while on a call therefore used to run
     *
     *     audioManager.isSpeakerphoneOn = _isSpeakerOn.value   // default: true
     *
     * which yanks the CALL onto the loudspeaker — the person you are talking to is
     * suddenly on speaker in a room you did not choose that for — and nothing ever
     * puts it back, because [stop] never touched routing either. The call keeps the
     * wrong route for the rest of its life.
     *
     * iOS had the same defect in a different dialect: the voice-note player
     * deactivated the SHARED `AVAudioSession`, which killed call audio outright.
     *
     * Note what is deliberately NOT done here: no `requestAudioFocus`. Voice-note
     * playback asks for no focus today, and asking for transient focus during a call
     * would duck or pause the call audio — it would be the same class of bug, not a
     * fix for it.
     */
    private fun applySpeakerRouting() {
        if (aCallOwnsTheAudioRoute()) {
            Log.d(TAG, "📞 A call owns audio routing — leaving speakerphone alone")
            return
        }
        // Remember what the device was set to the first time we touch it, so [stop]
        // can hand the route back instead of leaving the phone on speaker forever.
        if (speakerBeforePlayback == null) {
            speakerBeforePlayback = audioManager.isSpeakerphoneOn
        }
        audioManager.isSpeakerphoneOn = _isSpeakerOn.value
    }

    /** The global speakerphone state before this manager first changed it; null = untouched. */
    private var speakerBeforePlayback: Boolean? = null

    /**
     * Undo [applySpeakerRouting]. Skipped while a call is up for the same reason the
     * apply is: if a call started mid-note, the route is the call's now, and writing
     * our remembered value over it is exactly the disturbance this pair exists to
     * prevent.
     */
    private fun restoreSpeakerRouting() {
        val previous = speakerBeforePlayback ?: return
        speakerBeforePlayback = null
        if (aCallOwnsTheAudioRoute()) return
        try {
            audioManager.isSpeakerphoneOn = previous
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore speakerphone: ${e.message}")
        }
    }

    /**
     * Cycle playback speed: 1.0x → 1.5x → 2.0x → 1.0x.
     * Persists the selection and applies it live to the current MediaPlayer
     * (via PlaybackParams, requires API 23+).
     */
    fun cyclePlaybackSpeed() {
        val next = when (_playbackSpeed.value) {
            1.0f -> 1.5f
            1.5f -> 2.0f
            else -> 1.0f
        }
        _playbackSpeed.value = next
        playbackPrefs.edit().putFloat("audio_playback_speed", next).apply()

        // Apply live to currently playing MediaPlayer, if any.
        // setPlaybackParams starts playback on some devices when called on a
        // paused/prepared player — we only apply if we're already playing.
        if (_playbackState.value == PlaybackState.PLAYING) {
            try {
                mediaPlayer?.playbackParams = PlaybackParams().setSpeed(next)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply live playback speed: ${e.message}")
            }
        }
        Log.d(TAG, "Playback speed: ${next}x")
    }

    // =========================================================================
    // INTERNAL HELPERS
    // =========================================================================

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && _playbackState.value == PlaybackState.PLAYING) {
                try {
                    val player = mediaPlayer ?: break
                    val position = player.currentPosition
                    val duration = player.duration

                    if (duration > 0) {
                        _currentPosition.value = position
                        _playbackProgress.value = position.toFloat() / duration
                    }
                } catch (e: Exception) {
                    // Player might be released
                }
                delay(100)
            }
        }
    }

    /**
     * Get duration from audio data without playing
     */
    fun getDuration(audioData: ByteArray): Int {
        return try {
            // From memory, like playback: no plaintext temp file.
            val player = MediaPlayer().apply {
                setDataSource(ByteArrayMediaDataSource(audioData))
                prepare()
            }
            val duration = player.duration
            player.release()
            duration
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get duration: ${e.message}")
            0
        }
    }

    /** Kept for callers; there is no on-disk playback cache any more. */
    fun clearCache() {}

    fun destroy() {
        stop()
        clearCache()
        scope.cancel()
    }
}

/**
 * Serves a decrypted voice note to [MediaPlayer] from memory (API 23+), so playback
 * never needs a plaintext file on disk.
 */
internal class ByteArrayMediaDataSource(private val data: ByteArray) : android.media.MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val n = minOf(size.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        return n
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {}
}
