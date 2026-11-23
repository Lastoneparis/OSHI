package com.oshi.messenger.service

import com.oshi.messenger.service.diag.CallDiag
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.service.audio.VideoJitterBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VideoCallManager - iOS Compatible Video Call Handler
 *
 * Features:
 * - H.264 video encoding/decoding
 * - Adaptive quality (240p/480p/720p)
 * - Jitter buffer for smooth playback
 * - Background blur modes
 * - Picture-in-picture local preview
 */
@Singleton
class VideoCallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager
) {
    companion object {
        private const val TAG = "VideoCallManager"

        // Video quality presets
        const val QUALITY_LOW = "low"       // 480p @ 30fps (iOS compatible)
        const val QUALITY_MEDIUM = "medium" // 640p @ 30fps (iOS compatible)
        const val QUALITY_HIGH = "high"     // 1280p @ 30fps (iOS compatible)

        // Bitrates (matching iOS for VPS relay transport)
        private const val BITRATE_LOW = 500_000      // 500 kbps (increased from 300 — reduces motion pixelation)
        private const val BITRATE_MEDIUM = 800_000   // 800 kbps (matching iOS)
        private const val BITRATE_HIGH = 1_500_000   // 1.5 Mbps (matching iOS)

        // ────────────────────────────────────────────────────────────────────────
        // 📹 Cross-platform application-level video fragmentation (iOS↔Android)
        //
        // Wire format of each fragment (after AES-GCM decrypt, inside 0xF1 envelope):
        //   byte 0       : 0x01                  — fragment magic
        //   bytes 1..2   : frame_id    (UInt16 BE)
        //   byte 3       : fragment_index (UInt8)  0..(total-1)
        //   byte 4       : total_fragments (UInt8) 1..255
        //   bytes 5..    : payload bytes for this fragment
        //
        // Budget (1000 NAL/payload + 5 frag header + 1 type + 8 seq + 12 nonce + 16
        // GCM tag = 1042B). ALL frames use this format (single-fragment frames have total=1, idx=0).
        //
        // __VIDEO_MTU_2026_09_23__ 1100 → 1000 (sender-only: every receiver concatenates
        // whatever fragment sizes arrive, so no peer needs an update). The old "1142 B, under
        // 1200 B" forgot the carrier: the :8089 relay frame adds [t][len][recip][len][sender]
        // [len][callId] = 126-128 B, so a full fragment left as a 1270 B UDP payload — 1318 B
        // on IPv6, over the 1280 B minimum MTU ⇒ IP-fragmented and dropped on VPN/5G, the
        // same failure as the 1957 B raw-PCM audio. At 1000: ≤ 1170 B relay-up, ≤ 1126 B
        // relay-down (under the server's UDP_SAFE_DATAGRAM = 1200), ≤ 1218 B on IPv6.
        // ────────────────────────────────────────────────────────────────────────
        private const val VIDEO_FRAG_MAGIC: Byte = 0x01
        private const val VIDEO_FRAG_HEADER_SIZE: Int = 5
        private const val MAX_FRAGMENT_PAYLOAD_BYTES: Int = 1000
        /** Inbound `0x0B` acted on at most every 500 ms (~2/s). */
        private const val PEER_IDR_MIN_INTERVAL_MS: Long = 500L

        // 📹 Local capture watchdog (iOS VideoCallManager.swift:760-807).
        private const val CAPTURE_WATCHDOG_ATTEMPTS: Int = 3
        private const val CAPTURE_WATCHDOG_INTERVAL_MS: Long = 3_500L

        /** Error codes handed to [onError]; the UI maps them to a localized string. */
        const val ERROR_CAMERA_PERMISSION = "camera_permission_denied"
        const val ERROR_CAMERA_NO_FRAMES = "camera_no_frames"
    }

    // ============================================================================
    // STATE
    // ============================================================================

    private val _isVideoEnabled = MutableStateFlow(false)
    val isVideoEnabled: StateFlow<Boolean> = _isVideoEnabled.asStateFlow()

    private val _isSendingVideo = MutableStateFlow(true)
    val isSendingVideo: StateFlow<Boolean> = _isSendingVideo.asStateFlow()

    private val _isReceivingVideo = MutableStateFlow(false)
    val isReceivingVideo: StateFlow<Boolean> = _isReceivingVideo.asStateFlow()

    // ── Remote video health ────────────────────────────────────────────────────
    // `_isReceivingVideo` above is a ONE-WAY LATCH, so on its own it can only ever
    // say "there is a stream" — never "and it is 0.6 frames per second". See
    // [RemoteVideoHealth] for what that cost on iOS. Mirrors
    // `OSHI/VideoCallManager.swift:110-112` (`remoteVideoFPS`, `isRemoteVideoStalled`).

    /** Decoded remote frames per second, refreshed once a second. */
    private val _remoteVideoFps = MutableStateFlow(0)
    val remoteVideoFps: StateFlow<Int> = _remoteVideoFps.asStateFlow()

    /** No decoded remote frame for [RemoteVideoHealth.STALL_MS]. */
    private val _isRemoteVideoStalled = MutableStateFlow(false)
    val isRemoteVideoStalled: StateFlow<Boolean> = _isRemoteVideoStalled.asStateFlow()

    /** Frames decoded since the current start — what the health tick differentiates. */
    @Volatile private var decodedFrameCount = 0L
    private var healthJob: Job? = null
    private var lastHealthFrameCount = 0L
    /** Wall clock of the last decoded frame; `0` means nothing has EVER arrived. */
    @Volatile private var lastDecodedAtMs = 0L

    /**
     * The camera is producing frames. Set from the capture callback, never from
     * `openCamera()` having returned — `openCamera()` returns normally on a missing
     * permission, a missing camera id, a lock timeout and `StateCallback.onError`.
     * This is what the retry guard and the watchdog read.
     */
    private val _isCameraActive = MutableStateFlow(false)
    val isCameraActive: StateFlow<Boolean> = _isCameraActive.asStateFlow()

    /** Re-entrance guard for [startVideo]; `_isVideoEnabled` never did this job. */
    @Volatile private var isStartingVideo = false

    /** Frames actually delivered by the camera since the current start. */
    @Volatile private var capturedFrameCount = 0L

    private var localCaptureWatchdog: Job? = null

    /** Reported to the call layer so a silent camera failure reaches the user. */
    var onError: ((String) -> Unit)? = null

    // Default to MEDIUM for cross-platform: 360x640 @ 30fps @ 1.2Mbps produces
    // ~5-15KB IDRs (vs ~80KB at 720p), so each keyframe needs only ~5-15 fragments
    // instead of ~75. Smaller fragment count → drastically lower probability of
    // a single drop killing the whole frame on weak networks. iPhone receives
    // smooth, complete frames much faster (the user reported HIGH took
    // "way more time" to arrive on iOS).
    private val _currentQuality = MutableStateFlow(VideoQuality.MEDIUM)
    val currentQuality: StateFlow<VideoQuality> = _currentQuality.asStateFlow()

    private val _blurMode = MutableStateFlow(BlurMode.NONE)
    val blurMode: StateFlow<BlurMode> = _blurMode.asStateFlow()

    private val _blurIntensity = MutableStateFlow(0.5f)
    val blurIntensity: StateFlow<Float> = _blurIntensity.asStateFlow()

    private val _localVideoFrame = MutableStateFlow<Bitmap?>(null)
    val localVideoFrame: StateFlow<Bitmap?> = _localVideoFrame.asStateFlow()

    private val _remoteVideoFrame = MutableStateFlow<Bitmap?>(null)
    val remoteVideoFrame: StateFlow<Bitmap?> = _remoteVideoFrame.asStateFlow()

    private val _outgoingBandwidth = MutableStateFlow(0)
    val outgoingBandwidth: StateFlow<Int> = _outgoingBandwidth.asStateFlow()

    private val _jitterBufferDepth = MutableStateFlow(0)
    val jitterBufferDepth: StateFlow<Int> = _jitterBufferDepth.asStateFlow()

    // ============================================================================
    // VIDEO COMPONENTS
    // ============================================================================

    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var mediaCodecEncoder: MediaCodec? = null
    private var mediaCodecDecoder: MediaCodec? = null

    private val cameraOpenCloseLock = Semaphore(1)
    private var isFrontCamera = true
    private var sensorOrientation = 0  // Camera sensor rotation (0/90/180/270)

    // Jitter buffer for video
    private val videoJitterBuffer = VideoJitterBuffer(
        maxBufferSize = 10,  // ~500ms at 20fps — deeper buffer for smoother playback
        minBufferDepth = 3   // wait for 3 frames before starting (60ms at 20fps)
    )

    // Session key for encryption
    private var sessionKey: ByteArray? = null

    // Callbacks
    var onVideoPacketReady: ((ByteArray) -> Unit)? = null
    // Raw callback (no encryption) - used when EnhancedCallManager handles encryption
    var onRawVideoPacketReady: ((ByteArray) -> Unit)? = null

    // Stats
    private var bytesSentInLastSecond = 0L
    private var lastBandwidthUpdate = System.currentTimeMillis()

    // ============================================================================
    // COROUTINES
    // ============================================================================

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var encodeJob: Job? = null
    private var decodeJob: Job? = null
    private var playbackJob: Job? = null

    // ============================================================================
    // PUBLIC API
    // ============================================================================

    fun setSessionKey(key: ByteArray) {
        sessionKey = key
        Log.d(TAG, "📹 Session key set (${key.size} bytes)")
    }

    // DIAG: Counters for diagnostic logging
    private var diagFramesSent = 0L
    private var diagFramesReceived = 0L
    private var diagDecodeAttempts = 0L
    private var diagDecodeOutputs = 0L
    private var diagPlaybackFrames = 0L

    // ────────────────────────────────────────────────────────────────────────
    // 📹 Application-level fragmentation state (iOS↔Android cross-platform)
    // TX: monotonic UInt16 frame_id, wraps at 65535.
    // RX: small reassembly map keyed by frame_id — capped at MAX_REASSEMBLY_ENTRIES,
    //     drops oldest on overflow. Late fragments (older than current best) are
    //     ignored. Out-of-order new frame_id triggers GC of the stale entry and
    //     a peer keyframe request (its IDR/P-frame is unrecoverable).
    // ────────────────────────────────────────────────────────────────────────
    private var txFrameId: Int = 0  // Treated as UInt16 (0..65535)

    // __VIDEO_REORDER_2026_09_23__ The RX side used to hold ONE frame in flight: the first
    // fragment of a newer frame abandoned the unfinished one (and asked for a keyframe), and
    // its late fragments were then refused — so plain datagram REORDERING cost a frame and an
    // IDR exactly like a loss. Now a small reorder window (2 frames / 100 ms, keyed by
    // frame_id) completes frames out of order, releases them in decode order, drops stale
    // ones, and asks the peer for a keyframe only when a reference frame is truly lost.
    // Shared with the desktop: see [VideoReorderReassembler].
    private var reassembler = VideoReorderReassembler()
    private val reassemblerLock = Any()
    private var diagFragTxCount: Long = 0
    private var diagFragRxCount: Long = 0

    fun startVideo() {
        // 📹 The retry guard used to be `if (_isVideoEnabled.value) return`, i.e. the
        // same flag that a FAILED start sets to true. See [VideoStartGate].
        val decision = VideoStartGate.decide(
            isStartingVideo = isStartingVideo,
            isVideoEnabled = _isVideoEnabled.value,
            cameraLive = _isCameraActive.value
        )
        if (!VideoStartGate.proceeds(decision)) {
            Log.w(TAG, "DIAG: VIDEO MANAGER START — skipped ($decision)")
            return
        }
        if (decision == VideoStartGate.Decision.RESTART) {
            Log.w(TAG, "DIAG: VIDEO MANAGER START — isVideoEnabled=true but no frames captured; relaunching instead of refusing")
            closeCamera()
        }
        isStartingVideo = true
        try {
            startVideoLocked(decision)
        } finally {
            // Released on EVERY path, including the silent permission return inside
            // openCamera(); without this a denied camera would lock out every later
            // start — the user grants access and nothing ever restarts.
            isStartingVideo = false
        }
    }

    private fun startVideoLocked(decision: VideoStartGate.Decision) {
        Log.w(TAG, "DIAG: VIDEO MANAGER START — camera=${cameraDevice != null}, encoder=${mediaCodecEncoder != null}, decoder=${mediaCodecDecoder != null}, sessionKey=${sessionKey != null}")
        Log.d(TAG, "📹 Starting video...")

        // Reset diagnostic counters
        diagFramesSent = 0
        diagFramesReceived = 0
        diagDecodeAttempts = 0
        diagDecodeOutputs = 0
        diagPlaybackFrames = 0

        // Clear stale frames from previous call
        _remoteVideoFrame.value = null
        _localVideoFrame.value = null
        _isReceivingVideo.value = false

        // isReceivingVideo stays false until first frame is decoded (matching iOS)
        // _isVideoEnabled gates packet acceptance instead

        // Capture evidence, reset per start: the watchdog and the retry guard both
        // read it, and both must judge THIS attempt.
        capturedFrameCount = 0L
        _isCameraActive.value = false

        // 📹 CAMERA permission is checked here rather than only inside openCamera()
        // so the caller learns about it. The mid-call video upgrade reaches this
        // function without ever having asked for CAMERA — before, that produced a
        // black self-view with no prompt, no error and no way to retry.
        //
        // A denial degrades to receive-only rather than aborting: the peer's video
        // must still arrive, which is what the rest of this function sets up.
        val cameraAllowed = hasCameraPermission()
        if (!cameraAllowed) {
            // `_isSendingVideo` is deliberately NOT forced false here: it is the
            // user's own camera-on/off choice, and clearing it would be a second
            // latch of exactly the kind this change removes. The screen learns from
            // `onError` + `isCameraActive` instead.
            Log.e(TAG, "📹 startVideo: CAMERA permission not granted — receive-only")
            onError?.invoke(ERROR_CAMERA_PERMISSION)
        } else {
            startCameraThread()
            Log.w(TAG, "DIAG: VIDEO MANAGER — camera thread started")
            openCamera()
            Log.w(TAG, "DIAG: VIDEO MANAGER — camera opened (device=${cameraDevice != null})")
        }
        startEncoder()
        Log.w(TAG, "DIAG: VIDEO MANAGER — encoder started (encoder=${mediaCodecEncoder != null})")
        startDecoder()
        Log.w(TAG, "DIAG: VIDEO MANAGER — decoder started (decoder=${mediaCodecDecoder != null})")

        // CRITICAL FIX: Set _isVideoEnabled BEFORE starting the playback loop.
        // Previously this was set AFTER startPlaybackLoop(), which caused two problems:
        // 1. The playback loop's while-condition checked _isVideoEnabled and could exit
        //    immediately if the coroutine started before the flag was set.
        // 2. Remote video packets arriving between startDecoder() and this line were
        //    silently dropped by receiveRawVideoData() (which gates on _isVideoEnabled),
        //    causing the first keyframes to be lost. Without a keyframe the decoder
        //    cannot produce any output, resulting in a permanent black remote video.
        //
        // NOTE this flag says "a video session is up", NOT "the camera works" — it
        // gates the RX path, so it must be set even when our own camera never starts,
        // or a failed local camera would also blind us to the peer's video. The
        // camera's own health is `_isCameraActive`, set from the capture callback.
        _isVideoEnabled.value = true

        startPlaybackLoop()
        // 📶 Runs whether or not our own camera came up: a receive-only participant is
        // precisely the person who needs to be told that nothing is arriving, since
        // they have no self-preview to prove the call is alive.
        // iOS `startRemoteHealthMonitor`, OSHI/VideoCallManager.swift:1341-1355.
        startRemoteHealthMonitor()
        if (cameraAllowed) armLocalCaptureWatchdog()
        Log.w(TAG, "DIAG: VIDEO MANAGER — playback loop started (playbackJob=${playbackJob != null})")
        Log.w(TAG, "DIAG: VIDEO MANAGER START COMPLETE — decision=$decision, isVideoEnabled=true, isSendingVideo=${_isSendingVideo.value}, onRawCallback=${onRawVideoPacketReady != null}, onEncCallback=${onVideoPacketReady != null}")
    }

    /**
     * 📶 Measure what is ACTUALLY arriving, once a second. The decision itself lives
     * in [RemoteVideoHealth], pure and tested; this only samples and publishes.
     * iOS `OSHI/VideoCallManager.swift:1341-1390`.
     */
    private fun startRemoteHealthMonitor() {
        healthJob?.cancel()
        lastHealthFrameCount = decodedFrameCount
        lastDecodedAtMs = 0L
        _remoteVideoFps.value = 0
        _isRemoteVideoStalled.value = false
        healthJob = scope.launch {
            while (isActive) {
                delay(1000)
                tickRemoteHealth()
            }
        }
    }

    private fun stopRemoteHealthMonitor() {
        healthJob?.cancel()
        healthJob = null
        decodedFrameCount = 0L
        lastHealthFrameCount = 0L
        lastDecodedAtMs = 0L
        _remoteVideoFps.value = 0
        _isRemoteVideoStalled.value = false
    }

    private fun tickRemoteHealth() {
        // __VIDEO_REORDER_2026_09_23__ A frame held behind a hole is released by the clock
        // when no newer datagram arrives to do it (the stream paused or ended).
        val polled = synchronized(reassemblerLock) {
            reassembler.poll().also { o -> for (f in o.released) processCompleteVideoFrame(f.packet, f.frameId) }
        }
        if (polled.requestKeyframe) requestKeyFrameFromPeer()
        val count = decodedFrameCount
        val fps = (count - lastHealthFrameCount).coerceAtLeast(0L).toInt()
        lastHealthFrameCount = count

        val stalled = RemoteVideoHealth.isStalled(lastDecodedAtMs, System.currentTimeMillis())

        if (_remoteVideoFps.value != fps) _remoteVideoFps.value = fps
        if (_isRemoteVideoStalled.value != stalled) {
            _isRemoteVideoStalled.value = stalled
            CallDiag.w(TAG, "DIAG_VIDEO_RX_HEALTH | stalled=$stalled | fps=$fps | decoded=$count")
        }
    }

    private fun hasCameraPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * 📹 iOS 59972bb: the camera can fail to start IN SILENCE.
     *
     * This watches neither a flag nor a session object — both can be true without a
     * single frame coming out — but the count of frames ACTUALLY captured. Three
     * spaced relaunches, then it gives up: past that it is not a slow start, it is a
     * camera we are not going to get. iOS `VideoCallManager.swift:760-807`.
     */
    private fun armLocalCaptureWatchdog() {
        localCaptureWatchdog?.cancel()
        localCaptureWatchdog = scope.launch {
            var restarts = 0
            for (attempt in 1..CAPTURE_WATCHDOG_ATTEMPTS) {
                delay(CAPTURE_WATCHDOG_INTERVAL_MS)
                // Camera deliberately off in the meantime (stop, watch-only, video→voice).
                if (!_isVideoEnabled.value || !_isSendingVideo.value) {
                    Log.d(TAG, "📹 watchdog: camera deliberately off — done")
                    return@launch
                }
                if (capturedFrameCount > 0L) {
                    Log.d(TAG, "📹 watchdog: $capturedFrameCount frame(s) captured — camera alive")
                    return@launch
                }
                if (!hasCameraPermission()) {
                    // Nothing to relaunch, and burning an attempt on it would only
                    // hide the real cause from the log.
                    Log.w(TAG, "📹 watchdog: CAMERA permission missing — nothing to relaunch")
                    onError?.invoke(ERROR_CAMERA_PERMISSION)
                    return@launch
                }
                restarts++
                Log.w(TAG, "⚠️ watchdog: NO frame captured after $attempt window(s) — relaunch $restarts/$CAPTURE_WATCHDOG_ATTEMPTS")
                closeCamera()
                openCamera()
            }
            if (capturedFrameCount == 0L && restarts > 0) {
                Log.e(TAG, "❌ watchdog: $restarts relaunch(es) without a single frame — the camera will not start")
                onError?.invoke(ERROR_CAMERA_NO_FRAMES)
            }
        }
    }

    private fun cancelLocalCaptureWatchdog() {
        localCaptureWatchdog?.cancel()
        localCaptureWatchdog = null
    }

    fun stopVideo() {
        Log.d(TAG, "📹 Stopping video...")

        cancelLocalCaptureWatchdog()
        stopRemoteHealthMonitor()
        stopRateAdaptation()
        _isVideoEnabled.value = false
        _isReceivingVideo.value = false
        _isCameraActive.value = false
        capturedFrameCount = 0L

        encodeJob?.cancel()
        decodeJob?.cancel()
        playbackJob?.cancel()

        closeCamera()
        stopEncoder()
        stopDecoder()
        stopCameraThread()

        videoJitterBuffer.reset()

        // 📹 Reset cross-platform fragmentation state so a new call doesn't reassemble
        // against stale entries / a stale rxLastCompletedFrameId.
        synchronized(reassemblerLock) { reassembler = VideoReorderReassembler() }
        txFrameId = 0
        diagFragTxCount = 0
        diagFragRxCount = 0

        // Clear stale frame so next call doesn't show frozen image from previous call
        _remoteVideoFrame.value = null
        _localVideoFrame.value = null
    }

    /**
     * Ask the PEER to emit a fresh IDR (PLI, wire type 0x0B).
     *
     * Wired by [EnhancedCallManager] to its `sendKeyframeRequest()`. Until this
     * existed, every "ask the peer for a keyframe" site in this file called
     * [requestKeyFrame] instead — which pokes the LOCAL encoder — so the request
     * never reached the wire (audit A-4 / D-7). iOS:
     * `VideoCallManager.swift:2745-2761 requestKeyframeFromPeer()` →
     * `VoiceCallManager.swift:10344 sendKeyframeRequest()`.
     */
    var onKeyframeRequestToPeer: (() -> Unit)? = null

    /**
     * Ask the peer for a fresh IDR because OUR decode/reassembly just failed.
     * Rate limiting lives in the sender (token bucket, iOS
     * `VoiceCallManager.swift:10344-10373`).
     */
    fun requestKeyFrameFromPeer() {
        try {
            onKeyframeRequestToPeer?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send keyframe request to peer: ${e.message}")
        }
    }

    /**
     * Force an IDR keyframe from the LOCAL encoder — used when the PEER asked us
     * for one (0x0B RX, iOS `VoiceCallManager.swift:5302-5308`) and for the
     * staggered start-up IDRs.
     * Matching iOS: staggered IDR requests ensure remote decoder can start.
     */
    fun requestKeyFrame() {
        try {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            mediaCodecEncoder?.setParameters(params)
            Log.d(TAG, "📹 IDR keyframe requested")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request keyframe: ${e.message}")
        }
    }

    // ============================================================================
    // __VIDEO_ABR_2026_09_23__ SEND-SIDE BANDWIDTH ADAPTATION
    // ============================================================================
    // Until now only iOS adapted its video bitrate; this client sent the preset's full
    // 1.2 Mbit/s into any link. [VideoRateController] (shared with the desktop) turns the
    // call's loss/RTT and the PEER'S keyframe requests into a rung of a ladder
    // (bitrate, fps, resolution): step down fast, up slowly, 150 kbit/s floor, preset
    // ceiling. Bitrate is changed LIVE (MediaCodec PARAMETER_KEY_VIDEO_BITRATE, no IDR),
    // fps by skipping camera frames, resolution by one preset step (encoder restart).

    @Volatile private var rateController: VideoRateController? = null
    private var rateJob: Job? = null
    /** The preset the ladder dropped us to, or null for the user's own [_currentQuality]. */
    @Volatile private var ladderQuality: VideoQuality? = null
    @Volatile private var ladderFps: Int = 30
    @Volatile private var appliedBitrate: Int = 0
    private var lastEncodedAtMs = 0L
    @Volatile private var lastPeerIdrAtMs = 0L

    private val _videoTargetKbps = MutableStateFlow(0)
    /** What the encoder is currently asked for, kbit/s (diagnostics / UI). */
    val videoTargetKbps: StateFlow<Int> = _videoTargetKbps.asStateFlow()

    /** The preset the encoder actually runs at (the ladder may sit one step below the user's). */
    private fun encodeQuality(): VideoQuality = ladderQuality ?: _currentQuality.value

    /**
     * The PEER asked us for a keyframe (`0x0B`, media or signal lane).
     *
     * Acted on at most every [PEER_IDR_MIN_INTERVAL_MS] (~2/s): one IDR serves every
     * request raised while it is in flight, and an unauthenticated cleartext `0x0B` flood
     * can no longer hold the encoder at keyframes. Every request still counts as feedback
     * for the rate controller — it is the peer telling us a reference frame of ours did not
     * arrive.
     */
    fun onPeerKeyframeRequest() {
        rateController?.onPeerKeyframeRequest()
        val now = System.currentTimeMillis()
        if (now - lastPeerIdrAtMs < PEER_IDR_MIN_INTERVAL_MS) return
        lastPeerIdrAtMs = now
        requestKeyFrame()
    }

    /**
     * Loss (%) and RTT (ms) as the call measures them (EnhancedCallManager's audio loop,
     * iOS `audioCongestionFactor`'s inputs). Either may be unknown (≤ 0 RTT).
     */
    fun onNetworkFeedback(lossPercent: Double, rttMs: Double) {
        val rc = rateController ?: return
        rc.onLossSample(lossPercent)
        rc.onRtt(rttMs)
    }

    private fun startRateAdaptation() {
        if (rateJob?.isActive == true) return
        val rc = VideoRateController(_currentQuality.value.bitrate)
        rateController = rc
        ladderQuality = null
        ladderFps = 30
        appliedBitrate = _currentQuality.value.bitrate
        rateJob = scope.launch {
            while (isActive) {
                delay(VideoRateController.TICK_MS)
                if (!_isVideoEnabled.value || !_isSendingVideo.value) continue
                applyRateDecision(rc.tick())
            }
        }
    }

    private fun stopRateAdaptation() {
        rateJob?.cancel()
        rateJob = null
        rateController = null
        ladderQuality = null
        ladderFps = 30
        appliedBitrate = 0
    }

    private fun applyRateDecision(d: VideoRateController.Decision) {
        ladderFps = d.fps
        val user = _currentQuality.value
        val wantQuality = if (d.scalePct < 100) stepDown(user) else null
        if (wantQuality != ladderQuality) {
            ladderQuality = wantQuality
            CallDiag.w(TAG, "DIAG_VIDEO_ABR | resolution → ${encodeQuality().resolution.width}x${encodeQuality().resolution.height}")
            restartEncoder() // new SPS/PPS + IDR; every receiver handles a size change (iOS rotates the same way)
            appliedBitrate = 0
        }
        val bps = d.bitrateBps
        if (bps != appliedBitrate) {
            try {
                val params = android.os.Bundle()
                params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps)
                mediaCodecEncoder?.setParameters(params)
                appliedBitrate = bps
                _videoTargetKbps.value = bps / 1000
            } catch (e: Exception) {
                CallDiag.w(TAG, "DIAG_VIDEO_ABR | bitrate update failed: ${e.message}")
            }
        }
        if (d.changed) {
            CallDiag.w(TAG, "DIAG_VIDEO_ABR | rung=${d.rung} | bitrate=${bps / 1000}k | fps=${d.fps} | scale=${d.scalePct}% | target=${d.targetBps / 1000}k | peerPLI=${rateController?.peerKeyframeRequests}")
        }
    }

    private fun stepDown(q: VideoQuality): VideoQuality = when (q) {
        VideoQuality.HIGH -> VideoQuality.MEDIUM
        VideoQuality.MEDIUM -> VideoQuality.LOW
        VideoQuality.LOW -> VideoQuality.LOW
    }

    /** Frame pacing for the ladder's fps: true when this camera frame should be skipped. */
    private fun skipForFps(nowMs: Long): Boolean {
        val fps = ladderFps
        if (fps >= 30) { lastEncodedAtMs = nowMs; return false }
        val interval = 1000L / fps
        if (nowMs - lastEncodedAtMs < interval - 4) return true
        lastEncodedAtMs = nowMs
        return false
    }

    /**
     * Force staggered IDR keyframes matching iOS pattern.
     * iOS: 800ms, 1300ms, 2300ms after video start + 0ms, 500ms, 1500ms on connect
     */
    fun forceStaggeredKeyFrames() {
        scope.launch {
            val delays = longArrayOf(0, 500, 1000, 2000)
            for (delayMs in delays) {
                kotlinx.coroutines.delay(delayMs)
                if (_isVideoEnabled.value) {
                    requestKeyFrame()
                }
            }
        }
    }

    /**
     * Pause camera capture without releasing the encoder.
     * Used when the app is backgrounded during a video call.
     */
    fun pauseCamera() {
        try {
            cameraCaptureSession?.stopRepeating()
            Log.d(TAG, "📹 Camera paused (capture stopped, encoder retained)")
        } catch (e: Exception) {
            Log.w(TAG, "📹 Failed to pause camera: ${e.message}")
        }
        _isSendingVideo.value = false
    }

    /**
     * Resume camera capture after a pause.
     * Requests a keyframe so the remote decoder can resync.
     */
    fun resumeCamera() {
        try {
            val camera = cameraDevice ?: return
            val reader = imageReader ?: return
            val session = cameraCaptureSession ?: return
            val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(reader.surface)
            captureRequestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )
            session.setRepeatingRequest(captureRequestBuilder.build(), null, cameraHandler)
            Log.d(TAG, "📹 Camera resumed (capture restarted)")
        } catch (e: Exception) {
            Log.w(TAG, "📹 Failed to resume camera: ${e.message}")
        }
        _isSendingVideo.value = true
        requestKeyFrame()
    }

    fun toggleSendingVideo() {
        _isSendingVideo.value = !_isSendingVideo.value
        Log.d(TAG, "📹 Sending video: ${_isSendingVideo.value}")
    }

    fun toggleReceivingVideo() {
        _isReceivingVideo.value = !_isReceivingVideo.value
        Log.d(TAG, "📹 Receiving video: ${_isReceivingVideo.value}")
    }

    fun setQuality(quality: VideoQuality) {
        _currentQuality.value = quality
        stopRateAdaptation() // a new ceiling: the ladder restarts from the new preset
        restartEncoder()
        Log.d(TAG, "📹 Quality set to: ${quality.name}")
    }

    fun setBlurMode(mode: BlurMode) {
        _blurMode.value = mode
        Log.d(TAG, "📹 Blur mode set to: ${mode.name}")
    }

    fun setBlurIntensity(intensity: Float) {
        _blurIntensity.value = intensity.coerceIn(0f, 1f)
    }

    fun switchCamera() {
        isFrontCamera = !isFrontCamera
        closeCamera()
        openCamera()
        Log.d(TAG, "📹 Switched to ${if (isFrontCamera) "front" else "back"} camera")
    }

    /**
     * Receive video packet from peer (encrypted - legacy path)
     */
    fun receiveVideoPacket(encryptedPacket: ByteArray) {
        if (!_isVideoEnabled.value) return

        sessionKey?.let { key ->
            try {
                val decrypted = cryptoManager.decryptAESGCM(encryptedPacket, key)
                receiveRawVideoData(decrypted)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to receive video packet: ${e.message}")
            }
        }
    }

    /**
     * Receive raw (already decrypted) video data from EnhancedCallManager.
     *
     * Wire is a video-fragment packet (cross-platform, iOS↔Android):
     *   [0x01][frame_id:2BE][idx:1][total:1][payload bytes]
     * Reassembled payload is the legacy frame format:
     *   [flags(1)][timestamp(8,LE)][spsLen(2,LE)][sps][ppsLen(2,LE)][pps][frameData]
     */
    fun receiveRawVideoData(data: ByteArray) {
        if (!_isVideoEnabled.value) {
            if (diagFramesReceived < 5) {
                Log.w(TAG, "DIAG: receiveRawVideoData DROPPED — isVideoEnabled=false, size=${data.size}")
            }
            return
        }

        // Parse fragment header. Receivers no longer need legacy/non-fragmented
        // detection logic — every frame on the wire shares this format.
        if (data.size < VIDEO_FRAG_HEADER_SIZE + 1 || data[0] != VIDEO_FRAG_MAGIC) {
            Log.w(TAG, "DIAG: receiveRawVideoData DROPPED — not a fragment packet (size=${data.size}, magic=0x%02X)".format(data.getOrNull(0)?.toInt() ?: 0))
            return
        }

        val frameId = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        val total = data[4].toInt() and 0xFF
        diagFragRxCount++

        // Released frames go to the jitter buffer INSIDE the lock, so two transports
        // delivering at once (P2P + relay) cannot interleave the decode order.
        val outcome = synchronized(reassemblerLock) {
            reassembler.offer(data).also { o -> for (f in o.released) processCompleteVideoFrame(f.packet, f.frameId) }
        }
        if (diagFragRxCount <= 30 || diagFragRxCount % 60 == 0L) {
            val r = reassembler
            CallDiag.w(TAG, "DIAG_VIDEO_FRAG_RX | frameId=$frameId | total=$total | ${outcome.reason} | released=${outcome.released.size} | lost=${r.lostFrames} | reordered=${r.reorderedFrames} | late=${r.lateFragments}")
        }
        if (outcome.reason == VideoReorderReassembler.Reason.MALFORMED) {
            Log.w(TAG, "DIAG: receiveRawVideoData DROPPED — bad header frameId=$frameId total=$total")
            return
        }
        if (outcome.requestKeyframe) {
            // Ask THE PEER for a fresh IDR — a reference frame is gone for good.
            // 🔧 FIX (audit A-4 / D-7): never our own encoder. iOS: 0x0B on the wire.
            requestKeyFrameFromPeer()
        }
    }

    /**
     * Process a fully-reassembled video frame payload.
     * Format: [flags(1)][timestamp(8,LE)][spsLen(2,LE)][sps][ppsLen(2,LE)][pps][frameData]
     */
    private fun processCompleteVideoFrame(data: ByteArray, frameId: Int) {
        diagFramesReceived++
        try {
            if (data.size < 11) {
                Log.w(TAG, "DIAG: processCompleteVideoFrame #$diagFramesReceived DROPPED — too small: ${data.size}")
                return
            }

            var offset = 0

            // 🔧 iOS-COMPATIBLE FORMAT: [flags(1)][timestamp(8,LE)][spsLen(2,LE)][sps][ppsLen(2,LE)][pps][frameData]
            val flags = data[offset].toInt() and 0xFF
            val isKeyFrame = (flags and 1) != 0
            offset += 1

            if (diagFramesReceived <= 10 || diagFramesReceived % 30 == 0L) {
                Log.w(TAG, "DIAG: receiveRawVideoData #$diagFramesReceived — size=${data.size}, keyFrame=$isKeyFrame, jitterDepth=${videoJitterBuffer.depth}")
            }

            // Timestamp: 8 bytes little-endian (iOS native byte order)
            var timestamp = 0L
            for (i in 0..7) {
                timestamp = timestamp or ((data[offset + i].toLong() and 0xFF) shl (i * 8))
            }
            offset += 8

            // SPS length: 2 bytes little-endian
            val spsLength = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2

            val spsData = if (spsLength > 0 && offset + spsLength <= data.size) {
                data.copyOfRange(offset, offset + spsLength).also { offset += spsLength }
            } else null

            // PPS length: 2 bytes little-endian
            val ppsLength = if (offset + 2 <= data.size) {
                (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            } else 0
            offset += 2

            val ppsData = if (ppsLength > 0 && offset + ppsLength <= data.size) {
                data.copyOfRange(offset, offset + ppsLength).also { offset += ppsLength }
            } else null

            val h264Data = if (offset < data.size) data.copyOfRange(offset, data.size) else return

            videoJitterBuffer.addFrame(timestamp, h264Data, isKeyFrame, spsData, ppsData)
            _jitterBufferDepth.value = videoJitterBuffer.depth

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse raw video data: ${e.message}")
        }
    }

    // ============================================================================
    // CAMERA
    // ============================================================================

    private fun startCameraThread() {
        // Idempotent: the retry path (VideoStartGate.RESTART, and the capture
        // watchdog) re-enters startVideo without a stopVideo in between, and the old
        // body overwrote `cameraThread` — leaking one HandlerThread per retry.
        if (cameraThread != null && cameraHandler != null) return
        cameraThread = HandlerThread("CameraThread").apply {
            start()
            cameraHandler = Handler(looper)
        }
    }

    private fun stopCameraThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join()
            cameraThread = null
            cameraHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Camera thread interrupted: ${e.message}")
        }
    }

    private fun openCamera() {
        // Check CAMERA permission before accessing hardware
        if (!hasCameraPermission()) {
            Log.e(TAG, "CAMERA permission not granted — cannot open camera")
            onError?.invoke(ERROR_CAMERA_PERMISSION)
            return
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            val cameraId = getCameraId(manager)
            if (cameraId == null) {
                Log.e(TAG, "No camera found")
                onError?.invoke(ERROR_CAMERA_NO_FRAMES)
                return
            }

            // 🔧 Read sensor orientation for PiP rotation correction
            val characteristics = manager.getCameraCharacteristics(cameraId)
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            Log.d(TAG, "📹 Camera sensor orientation: $sensorOrientation° (front=$isFrontCamera)")

            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Camera lock timeout")
            }

            val size = _currentQuality.value.resolution

            imageReader = ImageReader.newInstance(
                size.width,
                size.height,
                ImageFormat.YUV_420_888,
                2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    var image: Image? = null
                    try {
                        image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                        // 📹 THE evidence the camera works: a frame the hardware
                        // actually delivered. Counted before any send/enable gate, so
                        // a watch-only session still proves the camera is alive.
                        capturedFrameCount++
                        if (!_isCameraActive.value) {
                            _isCameraActive.value = true
                            Log.d(TAG, "📹 First captured frame — camera live")
                        }
                        if (_isSendingVideo.value && _isVideoEnabled.value) {
                            processFrame(image)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "onImageAvailable error: ${e.message}")
                    } finally {
                        try { image?.close() } catch (_: Exception) { }
                    }
                }, cameraHandler)
            }

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    _isCameraActive.value = false
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    _isCameraActive.value = false
                    Log.e(TAG, "Camera error: $error")
                }
            }, cameraHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera: ${e.message}")
            cameraOpenCloseLock.release()
            _isCameraActive.value = false
        }
    }

    private fun closeCamera() {
        _isCameraActive.value = false
        try {
            cameraOpenCloseLock.acquire()
            cameraCaptureSession?.close()
            cameraCaptureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera: ${e.message}")
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private fun getCameraId(manager: CameraManager): String? {
        val facing = if (isFrontCamera) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }

        for (cameraId in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) == facing) {
                return cameraId
            }
        }
        return manager.cameraIdList.firstOrNull()
    }

    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val reader = imageReader ?: return

        try {
            val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(reader.surface)

            camera.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        cameraCaptureSession = session
                        captureRequestBuilder.set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                        )
                        session.setRepeatingRequest(
                            captureRequestBuilder.build(),
                            null,
                            cameraHandler
                        )
                        Log.d(TAG, "📹 Camera session started")
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera configuration failed")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session: ${e.message}")
        }
    }

    // ============================================================================
    // ENCODER
    // ============================================================================

    // The actual color format the encoder accepts. Resolved at startEncoder() based on
    // the device codec's reported supported formats. We always feed NV12 bytes from
    // yuvImageToNV12()/bitmapToNV12(), so we MUST configure the encoder with a
    // SemiPlanar (NV12) variant. If the device's only supported format is Planar (I420),
    // we transpose chroma before queueing input.
    //
    // Why not COLOR_FormatYUV420Flexible? It is a *capability* flag for Image input via
    // getInputImage(); when feeding raw ByteBuffers the underlying acceptance is
    // device-specific and on Samsung Exynos codecs frequently maps to Planar (I420).
    // Feeding NV12 to a Planar-expecting codec produces the textbook green-cast frames.
    private var encoderColorFormat: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    private var encoderWantsPlanar: Boolean = false

    private fun resolveEncoderColorFormat(): Int {
        // Prefer NV12 (SemiPlanar) since our input pipeline produces NV12.
        // Only fall back to Planar (I420) if SemiPlanar is not advertised.
        return try {
            val mcl = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val mime = MediaFormat.MIMETYPE_VIDEO_AVC
            for (info in mcl.codecInfos) {
                if (!info.isEncoder) continue
                if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                val caps = info.getCapabilitiesForType(mime)
                val formats = caps.colorFormats
                // Prefer SemiPlanar (NV12) — matches our input.
                if (formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)) {
                    return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                }
            }
            // Second pass: any encoder supporting Planar (I420)
            for (info in mcl.codecInfos) {
                if (!info.isEncoder) continue
                if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                val caps = info.getCapabilitiesForType(mime)
                if (caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)) {
                    return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                }
            }
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        } catch (e: Exception) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        }
    }

    // AVC profile/level chosen for the encoder after a device capability query.
    private data class AvcProfileLevel(val profile: Int, val level: Int)

    /**
     * Resolve the AVC profile/level to request from the hardware encoder.
     *
     * iOS parity: the sender was moved from Baseline to Main profile so CABAC entropy
     * coding kicks in, which markedly reduces "pixelation when moving". On Android CABAC
     * is implied by Main profile — there is no separate MediaFormat key for it.
     *
     * We only request Main if *some* AVC encoder on the device advertises Main at
     * Level 3.1 or higher (Level 3.1 already covers 720p@30fps at up to 14 Mbps, which
     * comfortably fits our 2.5 Mbps HIGH tier). Otherwise we fall back to Baseline 3.1,
     * exactly what shipped before, so encode never fails on a codec without Main support.
     */
    private fun resolveAvcProfileLevel(): AvcProfileLevel {
        val baselineFallback = AvcProfileLevel(
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
            MediaCodecInfo.CodecProfileLevel.AVCLevel31
        )
        return try {
            val mcl = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val mime = MediaFormat.MIMETYPE_VIDEO_AVC
            for (info in mcl.codecInfos) {
                if (!info.isEncoder) continue
                if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                val caps = info.getCapabilitiesForType(mime)
                // Level constants are increasing powers of two, so a numeric >=
                // comparison correctly means "Level 3.1 or higher".
                val hasMain = caps.profileLevels.any {
                    it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileMain &&
                        it.level >= MediaCodecInfo.CodecProfileLevel.AVCLevel31
                }
                if (hasMain) {
                    return AvcProfileLevel(
                        MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                        MediaCodecInfo.CodecProfileLevel.AVCLevel31
                    )
                }
            }
            baselineFallback
        } catch (e: Exception) {
            baselineFallback
        }
    }

    private fun startEncoder() {
        val quality = encodeQuality()
        if (rateController == null) startRateAdaptation()
        // A restart (resolution rung, camera switch) keeps the ladder's bitrate.
        val startBitrate = if (appliedBitrate > 0) minOf(appliedBitrate, quality.bitrate) else quality.bitrate

        try {
            // FIX: Resolve a concrete color format for the encoder INPUT.
            // Feeding NV12 bytes to an I420-expecting encoder = green frames on remote.
            encoderColorFormat = resolveEncoderColorFormat()
            encoderWantsPlanar =
                encoderColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar

            // KEY_MAX_INPUT_SIZE: large enough to hold raw NV12 of one frame +
            // headroom for occasional spikes. (Default may be too small on some devices.)
            val maxInputSize = quality.resolution.width * quality.resolution.height * 3 / 2 + 1024

            // Resolve the AVC profile/level once (device capability query) so it can also
            // be logged after configure().
            val avc = resolveAvcProfileLevel()

            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                quality.resolution.width,
                quality.resolution.height
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, startBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, quality.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)  // Keyframe every 1s (matching iOS: 30 frames @ 30fps)
                // FIX: explicit color format (was COLOR_FormatYUV420Flexible — ambiguous for ByteBuffer input)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, encoderColorFormat)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
                // Match iOS H.264 profile: Main (CABAC entropy, no B-frames) to cut
                // pixelation on motion. Guarded by a device capability query — falls back
                // to Baseline 3.1 if the encoder doesn't advertise Main. CABAC is implied
                // by Main on Android; there is no separate key.
                setInteger(MediaFormat.KEY_PROFILE, avc.profile)
                setInteger(MediaFormat.KEY_LEVEL, avc.level)
                // VBR mode: allows encoder to allocate more bits during motion (reduces pixelation)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                // Match iOS: RealTime = true + AllowFrameReordering = false
                setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = realtime priority (low latency)
                // Disable B-frames explicitly (Baseline shouldn't have them, but enforce it)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                // Latency target: minimize encoding latency for live video
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }

            mediaCodecEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            val avcName = if (avc.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileMain) "Main" else "Baseline"
            CallDiag.w(TAG, "DIAG_VIDEO_TX | encoderStart | profile=$avcName | colorFormat=0x${Integer.toHexString(encoderColorFormat)} | wantsPlanar=$encoderWantsPlanar | maxInputSize=$maxInputSize | ${quality.resolution.width}x${quality.resolution.height}@${quality.frameRate}fps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start encoder: ${e.message}")
        }
    }

    /**
     * Convert NV12 (SemiPlanar UVUVUV) to I420 (Planar U plane + V plane) for codecs
     * that report COLOR_FormatYUV420Planar as their accepted input.
     */
    private fun nv12ToI420(nv12: ByteArray, width: Int, height: Int): ByteArray {
        val frameSize = width * height
        val chromaSize = frameSize / 4
        val i420 = ByteArray(nv12.size)
        // Copy Y
        System.arraycopy(nv12, 0, i420, 0, frameSize)
        // De-interleave UVUV -> UU then VV
        var src = frameSize
        var u = frameSize
        var v = frameSize + chromaSize
        repeat(chromaSize) {
            i420[u++] = nv12[src++]
            i420[v++] = nv12[src++]
        }
        return i420
    }

    private fun stopEncoder() {
        try {
            mediaCodecEncoder?.stop()
            mediaCodecEncoder?.release()
            mediaCodecEncoder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping encoder: ${e.message}")
        }
    }

    private fun restartEncoder() {
        stopEncoder()
        cachedSps = null
        cachedPps = null
        startEncoder()
    }

    private var diagProcessFrameCount = 0L

    private fun processFrame(image: Image) {
        val encoder = mediaCodecEncoder ?: return
        diagProcessFrameCount++

        if (diagProcessFrameCount <= 5 || diagProcessFrameCount % 100 == 0L) {
            val targetW = encodeQuality().resolution.width
            val targetH = encodeQuality().resolution.height
            Log.w(TAG, "DIAG: processFrame #$diagProcessFrameCount — cam=${image.width}x${image.height}, enc=${targetW}x${targetH}, encoder=${mediaCodecEncoder != null}, sending=${_isSendingVideo.value}, h264Out=$diagFramesSent, rawCb=${onRawVideoPacketReady != null}")
        }

        try {
            // Convert YUV to bitmap for local preview (may throw if Image already closed)
            var bitmap = try {
                yuvImageToBitmap(image)
            } catch (e: Exception) {
                Log.w(TAG, "📹 Frame conversion failed (image may be closed): ${e.message}")
                return
            }

            // NOTE: yuvImageToBitmap() already applies sensor-orientation
            // rotation inline (see end of that function), so the bitmap here
            // is already upright. The deformation fix relies on the
            // center-crop in bitmapToNV12 below to preserve aspect when the
            // upright frame is then scaled to encoder dimensions.

            // Apply blur/pixelation effect if active
            val currentBlur = _blurMode.value
            if (currentBlur != BlurMode.NONE) {
                bitmap = applyBlurEffect(bitmap, currentBlur, _blurIntensity.value)
            }

            _localVideoFrame.value = bitmap

            // __VIDEO_ABR_2026_09_23__ The ladder's frame rate: the preview keeps every
            // camera frame, the encoder only the ones the rung allows.
            if (skipForFps(System.currentTimeMillis())) return

            // Get input buffer
            val inputBufferIndex = encoder.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputBufferIndex) ?: return

                // Convert frame to NV12 for encoder at the encoder's target resolution.
                // Always go through the bitmap → NV12 path now since the bitmap is
                // rotated; the old yuvImageToNV12 fast-path produced sideways video.
                val targetWidth = encodeQuality().resolution.width
                val targetHeight = encodeQuality().resolution.height
                var yuvData = bitmapToNV12(bitmap, targetWidth, targetHeight)
                // FIX: If the encoder advertises Planar (I420) input only, transpose chroma.
                // Without this, the encoder reads NV12 bytes as I420 — chroma is misinterpreted
                // as a single U plane producing the textbook green-cast remote frames.
                if (encoderWantsPlanar) {
                    yuvData = nv12ToI420(yuvData, targetWidth, targetHeight)
                }
                inputBuffer.clear()
                inputBuffer.put(yuvData)

                encoder.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    yuvData.size,
                    System.nanoTime() / 1000,
                    0
                )
            }

            // Get output
            val bufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)

            while (outputBufferIndex >= 0) {
                val outputBuffer = encoder.getOutputBuffer(outputBufferIndex) ?: break

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    // Extract SPS/PPS from codec config buffer
                    val configData = ByteArray(bufferInfo.size)
                    outputBuffer.get(configData)
                    extractSpsPps(configData)
                    Log.d(TAG, "📹 Extracted SPS/PPS from encoder config: sps=${cachedSps?.size}, pps=${cachedPps?.size}")
                } else if (bufferInfo.size > 0) {
                    val h264Data = ByteArray(bufferInfo.size)
                    outputBuffer.get(h264Data)

                    // Send encoded frame
                    sendVideoFrame(h264Data, bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                }

                encoder.releaseOutputBuffer(outputBufferIndex, false)
                outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error: ${e.message}")
        }
    }

    private fun sendVideoFrame(h264Data: ByteArray, isKeyFrame: Boolean) {
        diagFramesSent++
        if (diagFramesSent <= 5 || diagFramesSent % 30 == 0L) {
            Log.w(TAG, "DIAG: sendVideoFrame #$diagFramesSent — h264=${h264Data.size}B, keyFrame=$isKeyFrame, sps=${cachedSps?.size}, pps=${cachedPps?.size}, rawCallback=${onRawVideoPacketReady != null}")
        }
        // Wire-format diagnostic — symmetric with DIAG_VIDEO_RX, parses first NAL of
        // the encoder's Annex-B output to confirm what we are about to AVCC-wrap.
        run {
            val firstNalType: Int = if (h264Data.size >= 5) {
                // MediaCodec emits Annex-B; skip start code 0x000001 or 0x00000001
                val skip = if (h264Data.size >= 4 &&
                    h264Data[0] == 0x00.toByte() && h264Data[1] == 0x00.toByte() &&
                    h264Data[2] == 0x00.toByte() && h264Data[3] == 0x01.toByte()) 4
                else if (h264Data[0] == 0x00.toByte() && h264Data[1] == 0x00.toByte() &&
                    h264Data[2] == 0x01.toByte()) 3
                else 0
                if (skip in 0..(h264Data.size - 1)) (h264Data[skip].toInt() and 0x1F) else -1
            } else -1
            Log.w(TAG, "DIAG_VIDEO_TX | nalType=$firstNalType | size=${h264Data.size} | seq=$diagFramesSent | isIDR=$isKeyFrame | wire=AVCC+enriched")
        }
        try {
            // iOS-COMPATIBLE FORMAT: [flags(1)][timestamp(8,LE)][spsLen(2,LE)][sps][ppsLen(2,LE)][pps][frameData]
            val timestamp = System.currentTimeMillis()

            // CRITICAL: Include SPS/PPS in EVERY packet (matching iOS — only ~21B overhead)
            // iOS: "ALWAYS include cached SPS/PPS with every packet" (VideoCallManager.swift line 1003)
            val sps = getCodecConfigData("sps")
            val pps = getCodecConfigData("pps")

            val spsLength = sps?.size ?: 0
            val ppsLength = pps?.size ?: 0

            // Convert H.264 from Annex B to AVCC first
            val avccH264 = ensureAvccFormat(h264Data)

            // CRITICAL: Embed SPS/PPS as AVCC NAL units in frame data (matching iOS lines 1010-1025)
            // iOS prepends [spsLen(4,BE)][sps][ppsLen(4,BE)][pps] to frame data in AVCC format
            // This makes the stream self-describing — iOS receiver can extract SPS/PPS from frame data
            val enrichedFrameData: ByteArray
            if (sps != null && pps != null) {
                val enriched = java.io.ByteArrayOutputStream(sps.size + pps.size + 8 + avccH264.size)
                // SPS as AVCC NAL unit: [4-byte big-endian length][SPS data]
                enriched.write(byteArrayOf(
                    (sps.size shr 24).toByte(), (sps.size shr 16).toByte(),
                    (sps.size shr 8).toByte(), sps.size.toByte()
                ))
                enriched.write(sps)
                // PPS as AVCC NAL unit: [4-byte big-endian length][PPS data]
                enriched.write(byteArrayOf(
                    (pps.size shr 24).toByte(), (pps.size shr 16).toByte(),
                    (pps.size shr 8).toByte(), pps.size.toByte()
                ))
                enriched.write(pps)
                // Actual frame data
                enriched.write(avccH264)
                enrichedFrameData = enriched.toByteArray()
            } else {
                enrichedFrameData = avccH264
            }

            val packetSize = 1 + 8 + 2 + spsLength + 2 + ppsLength + enrichedFrameData.size
            val packet = ByteBuffer.allocate(packetSize)
            packet.order(java.nio.ByteOrder.LITTLE_ENDIAN)  // Match iOS native byte order

            // Flags byte layout (iOS-compatible, with new rotation extension):
            //   bit 0    : keyframe
            //   bits 1-2 : clockwise rotation the receiver should apply (0/90/180/270 → 0/1/2/3)
            //   bits 3-7 : reserved, must be zero
            //
            // 🔧 FIX (audit D-12) — stamp 0, NOT `sensorOrientation / 90`.
            // We already rotate the bitmap UPRIGHT before encoding (see
            // `rotateBitmapUpright`, this file :1630-1643), so the frame on the wire is
            // portrait and the receiver must render it with identity. iOS honours the
            // hint (`VideoCallManager.swift:2249-2257` → `applyRemoteVideoRotation`
            // :1545-1558), so shipping a non-zero code on a pre-rotated frame made iOS
            // rotate it a SECOND time — remote video appeared sideways (90°/270° on a
            // typical front sensor) on every Android→iOS video call.
            // iOS does exactly this: it pre-rotates then sets
            // `pendingWireRotationCode = 0` — "After pre-rotation the buffer is portrait
            // → receiver renders with identity" (`VideoCallManager.swift:1709-1712`).
            val rotationCode = 0
            val flagsByte = ((if (isKeyFrame) 0x01 else 0x00) or (rotationCode shl 1)).toByte()
            packet.put(flagsByte)
            packet.putLong(timestamp)
            packet.putShort(spsLength.toShort())
            sps?.let { packet.put(it) }
            packet.putShort(ppsLength.toShort())
            pps?.let { packet.put(it) }
            packet.put(enrichedFrameData)

            val rawPacket = packet.array()

            // Update bandwidth stats
            bytesSentInLastSecond += rawPacket.size
            val now = System.currentTimeMillis()
            if (now - lastBandwidthUpdate >= 1000) {
                _outgoingBandwidth.value = ((bytesSentInLastSecond * 8) / 1000).toInt() // kbps
                bytesSentInLastSecond = 0
                lastBandwidthUpdate = now
            }

            // 📹 Application-level fragmentation (iOS↔Android cross-platform).
            // Split rawPacket into ≤ MAX_FRAGMENT_PAYLOAD_BYTES chunks. Each fragment
            // gets its own [0x01][frame_id:2BE][idx:1][total:1][payload] envelope and
            // is independently encrypted by EnhancedCallManager — single-fragment
            // loss does NOT kill the whole frame.
            val frameId = txFrameId and 0xFFFF
            txFrameId = (txFrameId + 1) and 0xFFFF

            val total = ((rawPacket.size + MAX_FRAGMENT_PAYLOAD_BYTES - 1) / MAX_FRAGMENT_PAYLOAD_BYTES)
                .coerceAtLeast(1)
                .coerceAtMost(255)

            for (idx in 0 until total) {
                val start = idx * MAX_FRAGMENT_PAYLOAD_BYTES
                val end = minOf(start + MAX_FRAGMENT_PAYLOAD_BYTES, rawPacket.size)
                val payloadLen = end - start
                val frag = ByteArray(VIDEO_FRAG_HEADER_SIZE + payloadLen)
                frag[0] = VIDEO_FRAG_MAGIC
                frag[1] = ((frameId ushr 8) and 0xFF).toByte()  // frame_id high (BE)
                frag[2] = (frameId and 0xFF).toByte()           // frame_id low (BE)
                frag[3] = (idx and 0xFF).toByte()               // fragment_index
                frag[4] = (total and 0xFF).toByte()             // total_fragments
                System.arraycopy(rawPacket, start, frag, VIDEO_FRAG_HEADER_SIZE, payloadLen)

                diagFragTxCount++
                if (diagFragTxCount <= 20 || diagFragTxCount % 60 == 0L) {
                    CallDiag.w(TAG, "DIAG_VIDEO_FRAG_TX | frameId=$frameId | total=$total | idx=$idx | size=${frag.size}")
                }

                if (onRawVideoPacketReady != null) {
                    onRawVideoPacketReady?.invoke(frag)
                } else {
                    // Legacy path: encrypt locally
                    sessionKey?.let { key ->
                        val encrypted = cryptoManager.encryptAESGCM(frag, key)
                        onVideoPacketReady?.invoke(encrypted)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send video frame: ${e.message}")
        }
    }

    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null

    private fun getCodecConfigData(type: String): ByteArray? {
        return when (type) {
            "sps" -> cachedSps
            "pps" -> cachedPps
            else -> null
        }
    }

    /**
     * Extract SPS and PPS NAL units from codec config data (Annex B format)
     * Format: 0x00000001 [SPS] 0x00000001 [PPS]
     */
    private fun extractSpsPps(configData: ByteArray) {
        // Find NAL unit boundaries (startcode 0x00000001 or 0x000001)
        val nalUnits = mutableListOf<ByteArray>()
        var i = 0
        var lastStart = -1

        while (i < configData.size - 3) {
            val isStartCode4 = i < configData.size - 3 &&
                    configData[i] == 0x00.toByte() && configData[i + 1] == 0x00.toByte() &&
                    configData[i + 2] == 0x00.toByte() && configData[i + 3] == 0x01.toByte()
            val isStartCode3 = configData[i] == 0x00.toByte() && configData[i + 1] == 0x00.toByte() &&
                    configData[i + 2] == 0x01.toByte()

            if (isStartCode4 || isStartCode3) {
                val startCodeLen = if (isStartCode4) 4 else 3
                if (lastStart >= 0) {
                    nalUnits.add(configData.copyOfRange(lastStart, i))
                }
                lastStart = i + startCodeLen
                i += startCodeLen
            } else {
                i++
            }
        }
        // Add last NAL unit
        if (lastStart >= 0 && lastStart < configData.size) {
            nalUnits.add(configData.copyOfRange(lastStart, configData.size))
        }

        // Identify SPS (NAL type 7) and PPS (NAL type 8)
        for (nalu in nalUnits) {
            if (nalu.isEmpty()) continue
            val nalType = nalu[0].toInt() and 0x1F
            when (nalType) {
                7 -> cachedSps = nalu  // SPS
                8 -> cachedPps = nalu  // PPS
            }
        }
    }

    // ============================================================================
    // DECODER
    // ============================================================================

    // Track remote SPS/PPS for decoder reconfiguration
    private var remoteSps: ByteArray? = null
    private var remotePps: ByteArray? = null
    private var decoderConfigured = false

    private fun startDecoder() {
        try {
            mediaCodecDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

            val quality = _currentQuality.value
            // Allow large IDRs (up to ~3x raw frame size) — peer may send a different
            // resolution. Default decoder input buffer can be < 64KB which silently
            // drops large iOS keyframes.
            val maxInputSize = 1280 * 720 * 3 / 2 + 16384
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                quality.resolution.width,
                quality.resolution.height
            ).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }

            mediaCodecDecoder?.configure(format, null, null, 0)
            mediaCodecDecoder?.start()
            decoderConfigured = false

            CallDiag.w(TAG, "DIAG_VIDEO_RX | decoderStart | maxInputSize=$maxInputSize | awaitingSpsPps=true")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start decoder: ${e.message}")
        }
    }

    /**
     * Reconfigure decoder with SPS/PPS from remote peer
     * This is required for H.264 decoding to work correctly
     */
    private fun reconfigureDecoderWithSpsPps(sps: ByteArray, pps: ByteArray) {
        if (decoderConfigured && sps.contentEquals(remoteSps) && pps.contentEquals(remotePps)) {
            return // Already configured with same SPS/PPS
        }

        Log.d(TAG, "📹 Reconfiguring decoder with remote SPS(${sps.size}) PPS(${pps.size})")

        try {
            // Stop old decoder
            mediaCodecDecoder?.stop()
            mediaCodecDecoder?.release()

            // Create new decoder with SPS/PPS in format
            mediaCodecDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

            val quality = _currentQuality.value
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                quality.resolution.width,
                quality.resolution.height
            )

            // Set SPS/PPS as CSD (Codec Specific Data)
            // csd-0 = SPS with Annex B startcode, csd-1 = PPS with Annex B startcode
            val startCode = byteArrayOf(0x00, 0x00, 0x00, 0x01)
            val spsWithStartCode = startCode + sps
            val ppsWithStartCode = startCode + pps

            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(spsWithStartCode))
            format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(ppsWithStartCode))
            // FIX: ensure decoder input buffer can hold large iOS IDR frames.
            val maxInputSize = 1280 * 720 * 3 / 2 + 16384
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }

            mediaCodecDecoder?.configure(format, null, null, 0)
            mediaCodecDecoder?.start()

            remoteSps = sps
            remotePps = pps
            decoderConfigured = true

            Log.d(TAG, "📹 Decoder reconfigured successfully with SPS/PPS")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reconfigure decoder: ${e.message}")
            // Fallback: restart basic decoder
            startDecoder()
        }
    }

    private fun stopDecoder() {
        try {
            mediaCodecDecoder?.stop()
            mediaCodecDecoder?.release()
            mediaCodecDecoder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping decoder: ${e.message}")
        }
    }

    private fun startPlaybackLoop() {
        Log.w(TAG, "DIAG: startPlaybackLoop() ENTER — isVideoEnabled=${_isVideoEnabled.value}, quality=${_currentQuality.value.name}, frameRate=${_currentQuality.value.frameRate}")
        playbackJob = scope.launch {
            var loopCount = 0L
            var framesDequeued = 0L
            var lastLogTime = System.currentTimeMillis()
            Log.w(TAG, "DIAG: playbackLoop STARTED — coroutine active")

            while (isActive && _isVideoEnabled.value) {
                val loopStart = System.currentTimeMillis()
                loopCount++
                val frame = videoJitterBuffer.getNextFrame()

                if (frame != null && _isVideoEnabled.value) {
                    framesDequeued++
                    if (framesDequeued <= 5 || framesDequeued % 30 == 0L) {
                        Log.w(TAG, "DIAG: playbackLoop frame #$framesDequeued — dataSize=${frame.data.size}, keyFrame=${frame.isKeyFrame}, jitterDepth=${videoJitterBuffer.depth}")
                    }
                    decodeFrame(frame.data, frame.isKeyFrame, frame.spsData, frame.ppsData)
                }

                val now = System.currentTimeMillis()
                if (now - lastLogTime >= 5000) {
                    Log.w(TAG, "DIAG: playbackLoop stats — loops=$loopCount, framesDequeued=$framesDequeued, decodeAttempts=$diagDecodeAttempts, decodeOutputs=$diagDecodeOutputs, jitterDepth=${videoJitterBuffer.depth}, isReceiving=${_isReceivingVideo.value}")
                    lastLogTime = now
                }

                // Quality-dependent playback rate — subtract elapsed time to prevent drift
                val intervalMs = 1000L / _currentQuality.value.frameRate.toLong()
                val elapsed = System.currentTimeMillis() - loopStart
                val remaining = (intervalMs - elapsed).coerceAtLeast(1L)
                delay(remaining)
            }
            Log.w(TAG, "DIAG: playbackLoop ENDED — loops=$loopCount, framesDequeued=$framesDequeued, isActive=$isActive, videoEnabled=${_isVideoEnabled.value}")
        }
    }

    private fun decodeFrame(h264Data: ByteArray, isKeyFrame: Boolean, spsData: ByteArray?, ppsData: ByteArray?) {
        diagDecodeAttempts++
        if (diagDecodeAttempts <= 10 || diagDecodeAttempts % 30 == 0L) {
            Log.w(TAG, "DIAG: decodeFrame #$diagDecodeAttempts — h264=${h264Data.size}B, keyFrame=$isKeyFrame, sps=${spsData?.size}, pps=${ppsData?.size}, decoderConfigured=$decoderConfigured")
        }

        // Reconfigure decoder with SPS/PPS on keyframes (proper H.264 initialization)
        if (isKeyFrame && spsData != null && ppsData != null) {
            reconfigureDecoderWithSpsPps(spsData, ppsData)
        }

        val decoder = mediaCodecDecoder ?: run {
            if (diagDecodeAttempts <= 5) {
                Log.w(TAG, "DIAG: decodeFrame #$diagDecodeAttempts ABORT — decoder is null")
            }
            return
        }

        try {
            // CRITICAL FIX: iOS sends AVCC format (4-byte length prefix), convert to Annex B for MediaCodec
            val annexBData = ensureAnnexBFormat(h264Data)
            // Wire-format diagnostic — symmetric with DIAG_VIDEO_TX. Walk the first NAL
            // unit of the Annex-B stream so we can prove sender/receiver agree on layout.
            run {
                val firstNalType: Int = if (annexBData.size >= 5) {
                    val skip = if (annexBData.size >= 4 &&
                        annexBData[0] == 0x00.toByte() && annexBData[1] == 0x00.toByte() &&
                        annexBData[2] == 0x00.toByte() && annexBData[3] == 0x01.toByte()) 4
                    else if (annexBData[0] == 0x00.toByte() && annexBData[1] == 0x00.toByte() &&
                        annexBData[2] == 0x01.toByte()) 3
                    else 0
                    if (skip in 0..(annexBData.size - 1)) (annexBData[skip].toInt() and 0x1F) else -1
                } else -1
                Log.w(TAG, "DIAG_VIDEO_RX | nalType=$firstNalType | size=${annexBData.size} | seq=$diagDecodeAttempts | isIDR=$isKeyFrame | decodeOk=pending")
            }
            feedDecoderInput(decoder, annexBData)

            // Drain ALL available output buffers from the decoder.
            // After reconfiguration or keyframe processing, the decoder may first return
            // INFO_OUTPUT_FORMAT_CHANGED (-2) before returning the actual decoded frame.
            // Previously only one dequeueOutputBuffer call was made, so the format-change
            // notification consumed the timeout and the real frame was never retrieved,
            // causing a permanent black remote video.
            drainDecoderOutput(decoder)

        } catch (e: Exception) {
            Log.e(TAG, "Decode error: ${e.message}")
            // 📹 Decode failed → ask the PEER for a fresh IDR (PLI). Mirrors iOS,
            // which fires `requestKeyframeFromPeer()` on decode failure
            // (`VideoCallManager.swift:2745-2761`). The token bucket in the sender
            // (iOS `VoiceCallManager.swift:10344-10373`) absorbs bursts.
            requestKeyFrameFromPeer()
        }
    }

    /**
     * Drain all available output buffers from the decoder.
     * Handles INFO_OUTPUT_FORMAT_CHANGED and INFO_OUTPUT_BUFFERS_CHANGED gracefully,
     * then retrieves any decoded frames that are ready.
     */
    private fun drainDecoderOutput(decoder: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        var retriesAfterFormatChange = 0
        val maxRetries = 3  // After a format change, retry up to 3 times to get the actual frame

        while (true) {
            val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)

            if (diagDecodeAttempts <= 10 || diagDecodeAttempts % 30 == 0L) {
                Log.w(TAG, "DIAG: drainDecoderOutput #$diagDecodeAttempts — outputIndex=$outputIndex, bufferSize=${bufferInfo.size}, retries=$retriesAfterFormatChange")
            }

            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Decoder signalled a format change (e.g., after processing SPS/PPS).
                    // Continue draining — the actual decoded frame comes next.
                    val newFormat = decoder.outputFormat
                    Log.d(TAG, "Decoder output format changed: $newFormat")
                    retriesAfterFormatChange++
                    if (retriesAfterFormatChange > maxRetries) break
                    continue
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // No output available yet
                    break
                }
                @Suppress("DEPRECATION")
                outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                    // Deprecated but some older devices still return this; just continue
                    continue
                }
                outputIndex >= 0 -> {
                    val outputBuffer = decoder.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        diagDecodeOutputs++
                        // Use ACTUAL decoded dimensions from outputFormat, not local quality.
                        // The remote peer may send at a different resolution than our local quality preset.
                        // Using wrong dimensions causes YUV plane offsets to be wrong -> green video.
                        val outputFormat = decoder.outputFormat
                        val actualWidth = try { outputFormat.getInteger(MediaFormat.KEY_WIDTH) } catch (_: Exception) { _currentQuality.value.resolution.width }
                        val actualHeight = try { outputFormat.getInteger(MediaFormat.KEY_HEIGHT) } catch (_: Exception) { _currentQuality.value.resolution.height }
                        val actualSize = Size(actualWidth, actualHeight)

                        val bitmap = createBitmapFromYuv(outputBuffer, actualSize)
                        _remoteVideoFrame.value = bitmap
                        // 📶 RX health: this is the ONE place a remote frame actually
                        // becomes a picture, so it is the only honest place to count.
                        // `diagDecodeOutputs` above counts decoder outputs including
                        // the zero-size ones this branch skips.
                        decodedFrameCount++
                        lastDecodedAtMs = System.currentTimeMillis()
                        // Mark receiving video on first successful decode (matches iOS behavior)
                        if (!_isReceivingVideo.value) {
                            _isReceivingVideo.value = true
                            Log.d(TAG, "First video frame decoded — receiving video active (${actualWidth}x${actualHeight})")
                        }
                        if (diagDecodeOutputs <= 5 || diagDecodeOutputs % 30 == 0L) {
                            CallDiag.w(TAG, "DIAG_VIDEO_RX | nalType=decoded | size=${bufferInfo.size} | seq=$diagDecodeOutputs | decodeOk=true | isIDR=- | dims=${actualWidth}x$actualHeight")
                        }
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    // Continue draining in case more frames are ready
                    // (e.g., decoder had queued multiple outputs)
                }
                else -> break
            }
        }
    }

    private fun feedDecoderInput(decoder: MediaCodec, data: ByteArray) {
        val inputIndex = decoder.dequeueInputBuffer(10000)
        if (inputIndex >= 0) {
            val inputBuffer = decoder.getInputBuffer(inputIndex)
            if (inputBuffer == null) {
                // Release back to decoder so we don't leak the slot.
                try { decoder.queueInputBuffer(inputIndex, 0, 0, 0, 0) } catch (_: Exception) {}
                return
            }
            val capacity = inputBuffer.capacity()
            if (data.size > capacity) {
                // FIX: oversize frame would throw BufferOverflowException and
                // permanently desync the decoder. Drop this frame and log; the
                // next IDR will resync. (We also raise MAX_INPUT_SIZE at config.)
                CallDiag.w(TAG, "DIAG_VIDEO_RX | oversizeDrop | size=${data.size} | cap=$capacity | seq=$diagDecodeAttempts")
                try { decoder.queueInputBuffer(inputIndex, 0, 0, 0, 0) } catch (_: Exception) {}
                return
            }
            inputBuffer.clear()
            inputBuffer.put(data)
            decoder.queueInputBuffer(inputIndex, 0, data.size, System.nanoTime() / 1000, 0)
        }
    }

    // ============================================================================
    // H.264 FORMAT CONVERSION (iOS <-> Android compatibility)
    // ============================================================================

    /**
     * Convert AVCC format (4-byte length prefix) to Annex B format (start codes).
     * iOS VTCompressionSession outputs AVCC format, but Android MediaCodec decoder expects Annex B.
     */
    private fun convertAvccToAnnexB(avccData: ByteArray): ByteArray {
        val startCode = byteArrayOf(0x00, 0x00, 0x00, 0x01)
        val output = java.io.ByteArrayOutputStream(avccData.size + 32)
        var offset = 0

        while (offset + 4 <= avccData.size) {
            val nalLength = ((avccData[offset].toInt() and 0xFF) shl 24) or
                           ((avccData[offset + 1].toInt() and 0xFF) shl 16) or
                           ((avccData[offset + 2].toInt() and 0xFF) shl 8) or
                           (avccData[offset + 3].toInt() and 0xFF)
            offset += 4

            if (nalLength <= 0 || nalLength > avccData.size - offset) {
                Log.w(TAG, "AVCC->AnnexB: invalid NAL length $nalLength at offset ${offset - 4}, remaining=${avccData.size - offset}")
                break
            }

            output.write(startCode)
            output.write(avccData, offset, nalLength)
            offset += nalLength
        }

        return if (output.size() > 0) output.toByteArray() else avccData
    }

    /**
     * Convert Annex B format (start codes) to AVCC format (4-byte length prefix).
     * Android MediaCodec encoder outputs Annex B, but iOS VTDecompressionSession expects AVCC.
     */
    private fun convertAnnexBToAvcc(annexBData: ByteArray): ByteArray {
        val nalUnits = splitAnnexBNalUnits(annexBData)
        if (nalUnits.isEmpty()) return annexBData

        val output = java.io.ByteArrayOutputStream(annexBData.size)
        for (nalu in nalUnits) {
            val length = nalu.size
            output.write(byteArrayOf(
                (length shr 24).toByte(),
                (length shr 16).toByte(),
                (length shr 8).toByte(),
                length.toByte()
            ))
            output.write(nalu)
        }

        return if (output.size() > 0) output.toByteArray() else annexBData
    }

    /**
     * Split Annex B formatted H.264 data into individual NAL units (without start codes).
     */
    private fun splitAnnexBNalUnits(data: ByteArray): List<ByteArray> {
        val nalUnits = mutableListOf<ByteArray>()
        var i = 0
        var lastStart = -1

        while (i < data.size - 3) {
            val is4Byte = i + 3 < data.size &&
                    data[i] == 0x00.toByte() && data[i + 1] == 0x00.toByte() &&
                    data[i + 2] == 0x00.toByte() && data[i + 3] == 0x01.toByte()
            val is3Byte = !is4Byte &&
                    data[i] == 0x00.toByte() && data[i + 1] == 0x00.toByte() &&
                    data[i + 2] == 0x01.toByte()

            if (is4Byte || is3Byte) {
                val startCodeLen = if (is4Byte) 4 else 3
                if (lastStart >= 0) {
                    nalUnits.add(data.copyOfRange(lastStart, i))
                }
                lastStart = i + startCodeLen
                i += startCodeLen
            } else {
                i++
            }
        }
        // Add last NAL unit
        if (lastStart >= 0 && lastStart < data.size) {
            nalUnits.add(data.copyOfRange(lastStart, data.size))
        }

        return nalUnits
    }

    /**
     * Detect if H.264 data is in Annex B format (starts with 0x00000001 or 0x000001).
     */
    private fun isAnnexBFormat(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return (data[0] == 0x00.toByte() && data[1] == 0x00.toByte() &&
                data[2] == 0x00.toByte() && data[3] == 0x01.toByte()) ||
               (data[0] == 0x00.toByte() && data[1] == 0x00.toByte() &&
                data[2] == 0x01.toByte())
    }

    /**
     * Ensure H.264 data is in Annex B format for MediaCodec decoder.
     * Auto-detects AVCC vs Annex B and converts if needed.
     */
    private fun ensureAnnexBFormat(data: ByteArray): ByteArray {
        if (data.size < 4) return data
        return if (isAnnexBFormat(data)) data else convertAvccToAnnexB(data)
    }

    /**
     * Ensure H.264 data is in AVCC format for iOS VTDecompressionSession.
     * Auto-detects Annex B vs AVCC and converts if needed.
     */
    private fun ensureAvccFormat(data: ByteArray): ByteArray {
        if (data.size < 4) return data
        return if (isAnnexBFormat(data)) convertAnnexBToAvcc(data) else data
    }

    // ============================================================================
    // IMAGE CONVERSION
    // ============================================================================

    /**
     * Convert Camera2 YUV_420_888 Image to Bitmap for local PiP preview.
     *
     * FIX: Properly handles Camera2 plane pixel stride.
     * Most devices use semi-planar (pixelStride=2) where U/V buffers share memory.
     * The old code read U/V buffers sequentially which produced wrong UV pairing.
     * Now reads U_i and V_i at correct positions using pixelStride.
     */
    private fun yuvImageToBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val nv21 = ByteArray(width * height * 3 / 2)

        // Copy Y plane (row-stride aware)
        val yBuffer = yPlane.buffer
        if (yRowStride == width) {
            yBuffer.position(0)
            yBuffer.get(nv21, 0, width * height)
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, row * width, width)
            }
        }

        // Build NV21 chroma plane (V U V U ...) using correct pixel stride
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        var dstOffset = width * height

        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val uvIndex = row * uvRowStride + col * uvPixelStride
                nv21[dstOffset++] = vBuffer.get(uvIndex)  // V first for NV21
                nv21[dstOffset++] = uBuffer.get(uvIndex)  // U second for NV21
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, out)
        val imageBytes = out.toByteArray()

        val rawBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Apply sensor orientation so local PiP preview is upright
        // 🔧 FIX: Must rotate FIRST, then mirror for front camera.
        // Old order (setScale → postRotate) produced upside-down on most devices.
        // New order (setRotate → postScale) matches iOS AVCaptureConnection behavior.
        if (sensorOrientation == 0 && !isFrontCamera) return rawBitmap

        val matrix = Matrix()
        if (isFrontCamera) {
            // Rotate first to make upright, then mirror for selfie view
            matrix.setRotate(sensorOrientation.toFloat())
            matrix.postScale(-1f, 1f, rawBitmap.width / 2f, rawBitmap.height / 2f)
        } else {
            matrix.setRotate(sensorOrientation.toFloat())
        }

        return Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
    }

    /**
     * Convert Camera2 YUV_420_888 Image to NV12 for MediaCodec encoder input.
     *
     * FIX: Properly handles Camera2 plane pixel stride.
     * Most devices use semi-planar (pixelStride=2) where U/V buffers share interleaved memory.
     * The old code read U/V buffers sequentially and re-interleaved them, which double-interleaved
     * the already-interleaved data → corrupted chroma → green video on the remote end.
     * Now reads U_i and V_i at correct positions using pixelStride.
     */
    private fun yuvImageToNV12(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val nv12 = ByteArray(width * height * 3 / 2)

        // Copy Y plane (row-stride aware)
        val yBuffer = yPlane.buffer
        if (yRowStride == width) {
            yBuffer.position(0)
            yBuffer.get(nv12, 0, width * height)
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv12, row * width, width)
            }
        }

        // Build NV12 chroma plane (U V U V ...) using correct pixel stride
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        var dstOffset = width * height

        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val uvIndex = row * uvRowStride + col * uvPixelStride
                nv12[dstOffset++] = uBuffer.get(uvIndex)  // U first for NV12
                nv12[dstOffset++] = vBuffer.get(uvIndex)  // V second for NV12
            }
        }

        return nv12
    }

    /**
     * Convert YUV420 decoder output to ARGB Bitmap
     * Uses YuvImage (NV21 format) → JPEG → Bitmap pipeline for reliable conversion
     *
     * 🔧 FIX: Reads KEY_STRIDE and KEY_SLICE_HEIGHT from decoder outputFormat.
     * MediaCodec buffers have stride padding (e.g., stride=768 for width=720).
     * Without stride awareness, Y/U/V planes read from wrong offsets → green video.
     */
    private fun createBitmapFromYuv(buffer: ByteBuffer, size: Size): Bitmap {
        val width = size.width
        val height = size.height

        try {
            // Get decoder output format to determine actual color format and stride
            val decoder = mediaCodecDecoder ?: return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val outputFormat = decoder.outputFormat
            val colorFormat = outputFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT)

            // 🔧 Read stride/sliceHeight — decoder may pad rows for alignment
            val stride = try { outputFormat.getInteger(MediaFormat.KEY_STRIDE) } catch (_: Exception) { width }
            val sliceHeight = try { outputFormat.getInteger(MediaFormat.KEY_SLICE_HEIGHT) } catch (_: Exception) { height }

            buffer.position(0)

            val yuvData: ByteArray

            if (stride == width && sliceHeight == height) {
                // Fast path: no padding, read contiguously
                val yuvSize = (width * height * 1.5).toInt()
                yuvData = ByteArray(yuvSize)
                if (buffer.remaining() >= yuvSize) {
                    buffer.get(yuvData, 0, yuvSize)
                } else {
                    val available = buffer.remaining()
                    buffer.get(yuvData, 0, available)
                }
            } else {
                // 🔧 Stride-aware extraction: copy width bytes per row, skip padding
                val yuvSize = (width * height * 1.5).toInt()
                yuvData = ByteArray(yuvSize)

                // Y plane: height rows, each `stride` bytes in buffer but we need `width`
                var srcOffset = 0
                var dstOffset = 0
                for (row in 0 until height) {
                    if (srcOffset + width <= buffer.capacity()) {
                        buffer.position(srcOffset)
                        buffer.get(yuvData, dstOffset, width)
                    }
                    srcOffset += stride
                    dstOffset += width
                }

                // UV plane starts at stride * sliceHeight in the buffer
                val uvSrcStart = stride * sliceHeight
                val uvHeight = height / 2
                srcOffset = uvSrcStart
                for (row in 0 until uvHeight) {
                    if (srcOffset + width <= buffer.capacity()) {
                        buffer.position(srcOffset)
                        buffer.get(yuvData, dstOffset, width)
                    }
                    srcOffset += stride
                    dstOffset += width
                }

                Log.d(TAG, "📹 Stride-aware YUV extraction: ${width}x${height}, stride=$stride, sliceHeight=$sliceHeight")
            }

            // Convert YUV420 to NV21 format (what YuvImage expects)
            // MediaCodec typically outputs YUV420SemiPlanar (NV12) or YUV420Planar (I420)
            val nv21Data = when {
                colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
                colorFormat == 0x7F000001 /* OMX_COLOR_FormatYUV420SemiPlanar */ -> {
                    // NV12 → NV21: swap U and V bytes in chroma plane
                    convertNv12ToNv21(yuvData, width, height)
                }
                colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> {
                    // I420 → NV21: interleave V and U planes
                    convertI420ToNv21(yuvData, width, height)
                }
                else -> {
                    // Assume NV12 as most common output format
                    convertNv12ToNv21(yuvData, width, height)
                }
            }

            // Use YuvImage → JPEG → Bitmap for reliable conversion
            val yuvImage = YuvImage(nv21Data, ImageFormat.NV21, width, height, null)
            val outputStream = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, outputStream)
            val jpegData = outputStream.toByteArray()

            return BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        } catch (e: Exception) {
            Log.e(TAG, "YUV to Bitmap conversion failed: ${e.message}")
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
    }

    /**
     * Convert NV12 (YUV420SemiPlanar) to NV21
     * NV12: YYYYYYYY UVUV → NV21: YYYYYYYY VUVU
     */
    private fun convertNv12ToNv21(nv12: ByteArray, width: Int, height: Int): ByteArray {
        val nv21 = nv12.copyOf()
        val frameSize = width * height

        // Swap U and V bytes in chroma plane
        var i = frameSize
        while (i < nv21.size - 1) {
            val temp = nv21[i]
            nv21[i] = nv21[i + 1]
            nv21[i + 1] = temp
            i += 2
        }

        return nv21
    }

    /**
     * Convert I420 (YUV420Planar) to NV21
     * I420: YYYYYYYY UU VV → NV21: YYYYYYYY VUVU
     */
    private fun convertI420ToNv21(i420: ByteArray, width: Int, height: Int): ByteArray {
        val frameSize = width * height
        val chromaSize = frameSize / 4
        val nv21 = ByteArray(i420.size)

        // Copy Y plane
        System.arraycopy(i420, 0, nv21, 0, frameSize)

        // Interleave V and U planes
        val uOffset = frameSize
        val vOffset = frameSize + chromaSize
        for (j in 0 until chromaSize) {
            nv21[frameSize + j * 2] = i420[vOffset + j]       // V
            nv21[frameSize + j * 2 + 1] = i420[uOffset + j]   // U
        }

        return nv21
    }

    // ============================================================================
    // BLUR / PIXELATION EFFECTS
    // ============================================================================

    /**
     * Apply blur or pixelation effect to a bitmap.
     * Uses fast downscale→upscale technique (no RenderScript needed).
     */
    private fun applyBlurEffect(source: Bitmap, mode: BlurMode, intensity: Float = 0.5f): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return source

        return when (mode) {
            BlurMode.NONE -> source
            BlurMode.BACKGROUND_BLUR, BlurMode.FULL_BLUR -> {
                // Downscale factor scales with intensity: intensity=0 → /2, intensity=1 → /8
                val scale = (2 + intensity * 6).toInt().coerceIn(2, 8)
                val smallW = maxOf(w / scale, 1)
                val smallH = maxOf(h / scale, 1)
                val small = Bitmap.createScaledBitmap(source, smallW, smallH, true)
                Bitmap.createScaledBitmap(small, w, h, true)
            }
            BlurMode.PIXELATE -> {
                // Pixel block size scales with intensity: intensity=0 → 64px, intensity=1 → 8px
                val size = (64 - intensity * 48).toInt().coerceIn(8, 64)
                val small = Bitmap.createScaledBitmap(source, maxOf(w / size, 1), maxOf(h / size, 1), false)
                Bitmap.createScaledBitmap(small, w, h, false)
            }
            BlurMode.HEAVY_PIXELATE -> {
                val size = (32 - intensity * 24).toInt().coerceIn(4, 32)
                val small = Bitmap.createScaledBitmap(source, maxOf(w / size, 1), maxOf(h / size, 1), false)
                Bitmap.createScaledBitmap(small, w, h, false)
            }
            BlurMode.ULTRA_PIXELATE -> {
                val size = (16 - intensity * 12).toInt().coerceIn(2, 16)
                val small = Bitmap.createScaledBitmap(source, maxOf(w / size, 1), maxOf(h / size, 1), false)
                Bitmap.createScaledBitmap(small, w, h, false)
            }
        }
    }

    /**
     * Crop the input bitmap to match the target aspect ratio (centred), without
     * resizing it. Output bitmap has aspect == targetWidth:targetHeight.
     * If aspect already matches, returns the input unchanged.
     */
    private fun centerCropToAspect(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val srcW = bitmap.width
        val srcH = bitmap.height
        if (srcW <= 0 || srcH <= 0 || targetWidth <= 0 || targetHeight <= 0) return bitmap
        val srcAspect = srcW.toFloat() / srcH
        val dstAspect = targetWidth.toFloat() / targetHeight
        // Allow a small tolerance — same aspect ⇒ no-op.
        if (kotlin.math.abs(srcAspect - dstAspect) < 0.01f) return bitmap
        return if (srcAspect > dstAspect) {
            // Source is too wide → crop horizontally.
            val newW = (srcH * dstAspect).toInt().coerceAtLeast(1)
            val xOff = ((srcW - newW) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(bitmap, xOff, 0, newW, srcH)
        } else {
            // Source is too tall → crop vertically.
            val newH = (srcW / dstAspect).toInt().coerceAtLeast(1)
            val yOff = ((srcH - newH) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(bitmap, 0, yOff, srcW, newH)
        }
    }

    /**
     * Convert ARGB Bitmap back to NV12 format for the encoder.
     * Used when blur is active (we need to re-encode the blurred bitmap).
     */
    private fun bitmapToNV12(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): ByteArray {
        // Center-crop FIRST to match the encoder's aspect ratio, THEN scale.
        // The previous unconditional `createScaledBitmap` stretched/squashed the
        // frame whenever the camera's natural aspect (e.g., 4:3 = 0.75) didn't
        // match the encoder's portrait aspect (9:16 ≈ 0.5625). The result on
        // the iPhone receiver was a horizontally-squashed face ("écrasé"),
        // since iOS's AVSampleBufferDisplayLayer with `.resizeAspect` honors the
        // declared SPS dimensions but the *content* arrived already squashed
        // by the encoder side. Center-cropping keeps facial proportions intact.
        val cropped = centerCropToAspect(bitmap, targetWidth, targetHeight)
        val scaledBitmap = if (cropped.width != targetWidth || cropped.height != targetHeight) {
            Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
        } else {
            cropped
        }

        val w = scaledBitmap.width
        val h = scaledBitmap.height
        val argbPixels = IntArray(w * h)
        scaledBitmap.getPixels(argbPixels, 0, w, 0, 0, w, h)

        val nv12 = ByteArray(w * h * 3 / 2)

        // Y plane
        for (y in 0 until h) {
            for (x in 0 until w) {
                val pixel = argbPixels[y * w + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                // BT.601 luma
                val yVal = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                nv12[y * w + x] = yVal.coerceIn(0, 255).toByte()
            }
        }

        // UV plane (NV12: U V U V ...)
        var uvOffset = w * h
        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                val pixel = argbPixels[y * w + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                nv12[uvOffset++] = u.coerceIn(0, 255).toByte()
                nv12[uvOffset++] = v.coerceIn(0, 255).toByte()
            }
        }

        return nv12
    }

    fun cleanup() {
        stopVideo()
        scope.cancel()
    }
}

// ============================================================================
// DATA CLASSES & ENUMS
// ============================================================================

enum class VideoQuality(
    val resolution: Size,
    val frameRate: Int,
    val bitrate: Int
) {
    LOW(Size(270, 480), 30, 600_000),        // 480p portrait @ 30fps, 600kbps (matches iOS ladder)
    // Bumped to 1.2 Mbps so 360x640 still has crisp detail when motion + bg
    // textures consume bits. Was 800k → visibly pixelated on faces.
    MEDIUM(Size(360, 640), 30, 1_200_000),   // 640p portrait @ 30fps, 1.2Mbps
    // Bumped to 2.5 Mbps to match WhatsApp/Telegram-class clarity at 720p.
    // Was 1.5 Mbps which caused the "very pixel" complaint at HIGH on motion.
    HIGH(Size(720, 1280), 30, 2_500_000)     // 720p portrait @ 30fps, 2.5Mbps
}

enum class BlurMode(val displayName: String, val icon: String) {
    NONE("None", "person"),
    BACKGROUND_BLUR("Background Blur", "blur_on"),
    FULL_BLUR("Full Blur", "blur_circular"),
    PIXELATE("Pixelate", "grid_on"),
    HEAVY_PIXELATE("Heavy Pixelate", "grid_view"),
    ULTRA_PIXELATE("Ultra Pixelate", "apps")
}
