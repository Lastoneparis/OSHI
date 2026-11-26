package com.oshi.messenger.service

import com.oshi.messenger.service.diag.CallDiag
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.*
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import android.util.Log
import com.oshi.messenger.data.local.AppDatabase
import com.oshi.messenger.data.model.CallHistoryEntry
import com.oshi.messenger.data.model.CallStatus
import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.mesh.CrossPlatformMesh
import com.oshi.messenger.network.mesh.MeshMessage
import com.oshi.messenger.network.mesh.MeshNetworkManager
import com.oshi.messenger.network.mesh.MessageType
import com.oshi.messenger.network.vps.CallSignal
import com.oshi.messenger.network.vps.CallSignalType
import com.oshi.messenger.network.vps.VPSClient
import com.oshi.messenger.service.audio.*
import com.oshi.messenger.service.p2p.P2PTransport
import com.oshi.messenger.ui.viewmodel.dataStore
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EnhancedCallManager - iOS Compatible Voice/Video Call Handler
 *
 * Features:
 * - AAC-ELD codec at 48kHz (same as FaceTime, iOS compatible)
 * - AES-256-GCM encryption for both audio and video (matching iOS)
 * - Jitter buffer for smooth audio
 * - Adaptive quality based on network conditions
 * - Cross-platform calls (iOS ↔ Android)
 * - VPS relay and mesh direct modes
 */
@Singleton
class EnhancedCallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vpsClient: VPSClient,
    private val meshManager: MeshNetworkManager,
    private val crossPlatformMesh: CrossPlatformMesh,
    private val cryptoManager: CryptoManager,
    private val database: AppDatabase,
    val videoCallManager: VideoCallManager
) {
    companion object {
        private const val TAG = "EnhancedCallManager"
        // SECURITY (CSPN 2026-09): compile-time guard. Must stay false in release.
        private const val LOG_SENSITIVE = false

        /**
         * Package-local broadcast raised by `FCMService.kt:818-824` when the
         * `callAnsweredElsewhere` push arrives. The action string is duplicated there
         * as a literal; `FCMService` is not this team's file to edit, so the constant
         * lives on the receiving side and must not be renamed without changing both.
         */
        const val ACTION_CALL_ANSWERED_ELSEWHERE = "com.oshi.messenger.CALL_ANSWERED_ELSEWHERE"

        // Audio capture at 48kHz (matching iOS AAC-ELD: sampleRate = 48000)
        // iOS uses AAC-ELD at 48kHz (same codec as FaceTime) — Android must match
        private const val CAPTURE_SAMPLE_RATE = 48000
        private const val PLAYBACK_SAMPLE_RATE = 48000  // 48kHz matching iOS
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Packet timing
        private const val PACKET_DURATION_MS = 20
        private const val PACKETS_PER_SECOND = 50

        // 📹 Video packet type (iOS compatible: 0xF1)
        private const val VIDEO_PACKET_TYPE: Byte = 0xF1.toByte()

        // 📹 Video toggle signal types (iOS compatible: 0x0C/0x0D)
        private const val VIDEO_PAUSED_TYPE: Byte = 0x0C
        private const val VIDEO_RESUMED_TYPE: Byte = 0x0D

        // 📞 In-band callEnd control (iOS CALL_END_INBAND_TYPE = 0x0D).
        // Wire: [type=0x0D][seq(8 BE)][AES-GCM sealed box] — sealed-box shape of
        // an audio frame ([nonce(12)][ct][tag(16)]), plaintext = end-reason string
        // UTF-8, sharing the audio nonce counter. Mirrors the 0x0E/0x0F
        // video-upgrade/stop control packets and is fanned out over EVERY live
        // media transport so a zombie WebSocket (connected flag true, silent 30s+)
        // can no longer strand the peer on a frozen call while the signaling
        // callEnd never arrives. iOS OSHI/VoiceCallManager.swift.
        //
        // ⚠️ SAME BYTE as VIDEO_RESUMED_TYPE (0x0D). The two are disambiguated by
        // structure on RX: videoResumed is a bare `[type][ts BE8]` = exactly 9
        // bytes with NO encrypted payload; the in-band callEnd carries a sealed
        // box, so its total size is > 9 (≥ 37). iOS overloads the byte the same
        // way and separates them by the presence of that sealed box.
        private const val CALL_END_INBAND_TYPE: Byte = 0x0D

        // 📞 Multi-device: answered elsewhere signal (0x0A)
        private const val ANSWERED_ELSEWHERE_TYPE: Byte = 0x0A

        // 🔧 CROSS-PLATFORM: Raw PCM audio type (0x15) — used for iOS↔Android calls
        // Apple AudioToolbox and Android MediaCodec produce incompatible AAC-ELD bitstreams.
        // Type 0x15 signals: payload is raw PCM Int16 (no codec), universally compatible.
        private const val RAW_PCM_AUDIO_TYPE: Byte = 0x15

        // Call v2 — OshiCodec wire type + capability flag (Agent 6).
        // 0x16 = proprietary OshiCodec frames; distinct from 0x15 (raw PCM) and 0x05 (AAC-ELD).
        // A single 1-byte capability flag (0x01) is appended to the encrypted call-offer
        // and call-accept payloads. Both sides must advertise to enable OshiCodec; if either
        // side omits it (legacy client), both fall back to raw PCM 0x15. See CALL_V2_PLAN.md §3.
        private const val OSHI_CODEC_AUDIO_TYPE: Byte = 0x16
        private const val OSHI_CODEC_CAP_FLAG: Byte = 0x01

        // 16 kHz downsampled PCM wire type + capability flag.
        // 0x17 = raw PCM Int16 LE at 16 kHz (20 ms frames = 320 samples = 640 B).
        // Capability byte 0x02 rides as the SECOND trailing byte of the encrypted
        // offer/accept payloads (after OSHI_CODEC_CAP_FLAG); legacy builds that
        // parse only the first cap byte silently ignore it. Both sides must
        // advertise 0x02 to flip use16kHzPCM on; receive side auto-upgrades on
        // the first 0x17 frame to survive split-brain negotiation.
        private const val RAW_PCM_16K_AUDIO_TYPE: Byte = 0x17
        // Canonical wire constant — MUST stay 0x02 so the parser correctly
        // detects peer support. Advertising is gated separately on
        // `localSupports16kHzPCM`.
        private const val RAW_PCM_16K_CAP_FLAG: Byte = 0x02

        /**
         * __VUPG_CAPABILITY_2026_09_14__ Quatrième octet de capacité: « je sais
         * faire la vidéo ».
         *
         * Il n'existait AUCUNE négociation de capacité vidéo. Monter en vidéo
         * vers un client qui ne sait pas la faire donnait quinze secondes de
         * rond qui tourne puis « pas de réponse » — indiscernable de quelqu'un
         * qui a posé son téléphone.
         *
         * ⚠️ L'EMPLACEMENT COMPTE, PAS SEULEMENT LA VALEUR. iOS lit ces octets
         * POSITIONNELLEMENT: slot 0 = OshiCodec, 1 = PCM 16 kHz, 2 = codec
         * Apple, 3 = vidéo. Android n'écrivait que deux octets, donc écrire la
         * vidéo à la suite l'aurait placée dans l'emplacement du codec Apple.
         * On comble donc l'emplacement 2 avec 0x00 — ce qui est la vérité,
         * Android n'a pas d'AAC-ELD — et la vidéo va en 3.
         *
         * Reste sous 0x2D pour que le découpage du callId côté offre continue
         * de séparer les capacités de l'UUID ASCII.
         */
        private const val VIDEO_CAP_FLAG: Byte = 0x08

        /** Cet appareil sait faire la vidéo. */
        private const val LOCAL_SUPPORTS_VIDEO = true
        // Temporarily false: the reference biquad stores state in sample units
        // instead of Q30 accumulator precision, losing ~30 bits per step.
        // On real voice the residual noise is audible as crackling / electronic
        // artifact. Falling back to 0x15 (48 kHz raw PCM) until the biquad
        // stores state as Long (Q30). When false, this build appends 0x00 to
        // the cap slot and never flips use16kHzPCM on via negotiation or
        // auto-upgrade — both sides fall back to 0x15.
        private const val localSupports16kHzPCM: Boolean = false

        // Biquad LPF @ 7 kHz coefficients in Q1.30 fixed-point. Pre-computed in
        // double precision (see companion-object init block below) so the iOS
        // and Android filters produce bit-identical int32 state from the same
        // input. Fc=7000, Q=0.7071 (Butterworth), Fs=48000.
        private val PCM16_COEF_B0: Int
        private val PCM16_COEF_B1: Int
        private val PCM16_COEF_B2: Int
        private val PCM16_COEF_A1: Int
        private val PCM16_COEF_A2: Int

        init {
            // RBJ LPF cookbook: https://www.w3.org/TR/audio-eq-cookbook/
            val fc = 7000.0
            val fs = 48000.0
            val q  = 0.7071067811865476
            val omega = 2.0 * Math.PI * fc / fs
            val cosw = Math.cos(omega)
            val sinw = Math.sin(omega)
            val alpha = sinw / (2.0 * q)
            val b0d = (1.0 - cosw) / 2.0
            val b1d =  1.0 - cosw
            val b2d = (1.0 - cosw) / 2.0
            val a0d =  1.0 + alpha
            val a1d = -2.0 * cosw
            val a2d =  1.0 - alpha
            val inv = 1.0 / a0d
            val Q30 = (1 shl 30).toDouble()
            fun q30(x: Double): Int {
                val v = Math.round(x * Q30)
                return when {
                    v >=  Int.MAX_VALUE.toLong() -> Int.MAX_VALUE
                    v <= -Int.MAX_VALUE.toLong() -> -Int.MAX_VALUE
                    else -> v.toInt()
                }
            }
            PCM16_COEF_B0 = q30(b0d * inv)
            PCM16_COEF_B1 = q30(b1d * inv)
            PCM16_COEF_B2 = q30(b2d * inv)
            PCM16_COEF_A1 = q30(a1d * inv)
            PCM16_COEF_A2 = q30(a2d * inv)
        }

        // 📹 Video upgrade mid-call signal (0x0E)
        // Wire format: [type=0x0E][seq:8 BE][AES-GCM(salt+counter): 1-byte plaintext]
        // Plaintext payload bytes:
        //   0x01 = upgrade REQUEST  (caller → callee)
        //   0x02 = upgrade ACCEPT   (callee → caller)
        //   0x03 = upgrade DECLINE  (callee → caller)
        //   0x04 = stop video       (either side)
        private const val VIDEO_UPGRADE_TYPE: Byte = 0x0E
        private const val VIDEO_STOP_TYPE:    Byte = 0x0F
        private const val VUPG_PAYLOAD_REQUEST: Byte = 0x01
        private const val VUPG_PAYLOAD_ACCEPT:  Byte = 0x02
        private const val VUPG_PAYLOAD_DECLINE: Byte = 0x03
        private const val VUPG_PAYLOAD_STOP:    Byte = 0x04
        // 📹 Extended VUPG payloads — iOS VoiceCallManager.swift:10748-10750.
        //   0x05 = accept, receive-only ("Watch only" — peer will NOT send camera)
        //   0x06 = I just turned my camera ON  mid-video
        //   0x07 = I just turned my camera OFF mid-video (still watching)
        // Without these three, an iPhone tapping "Watch only" (VideoCallView.swift:128-130)
        // sends 0x05, Android logs DIAG_VUPGRADE_RX_UNKNOWN_CODE and hangs in
        // _isAwaitingVideoUpgrade until the 15 s timeout says "No answer".
        private const val VUPG_PAYLOAD_ACCEPT_WATCH: Byte = 0x05
        private const val VUPG_PAYLOAD_CAMERA_ON:    Byte = 0x06
        private const val VUPG_PAYLOAD_CAMERA_OFF:   Byte = 0x07
        private const val VUPGRADE_TIMEOUT_MS: Long = 15_000L

        // 📹 requestKeyframe (PLI) — iOS CallPacketType.requestKeyframe = 0x0B
        // (VoiceCallManager.swift:1976-2000). Send: VoiceCallManager.swift:10344-10373
        // (token bucket 0.35 s / burst 3, :10315-10316).
        // Receive: VoiceCallManager.swift:5302-5308 and :6972-6979 → ForceVideoKeyframe.
        private const val KEYFRAME_REQUEST_TYPE: Byte = 0x0B
        /** CallPacketType.keepAlive (iOS). Never audio — see handleAudioData. */
        private const val KEEPALIVE_TYPE: Byte = 0x06

        // 🔄 resyncRequest — iOS CallPacketType.resyncRequest = 0x0F
        // (VoiceCallManager.swift:1922). "The sender just foregrounded after a
        // screen-lock and is about to burst the backlog its network buffered while
        // it was suspended; flush your jitter buffer so that burst does not push
        // your playback delay to ~600 ms." Send: VoiceCallManager.swift:10385-10390
        // (encrypted for the peer, SIGNAL channel). Receive: :5336-5342 and
        // :7025-7028 → clearJitterBuffer().
        //
        // ⚠️ 0x0F is ALSO [VIDEO_STOP_TYPE] — on the MEDIA channel. The two are
        // disambiguated by which channel carried them, on both platforms: iOS reads
        // stop-video off 0x0F only inside its media handler (:11623) and
        // resyncRequest only inside processReceivedCallSignal (:5336). Android does
        // the same — handleAudioData owns the media meaning, handleCallSignal
        // intercepts the signal meaning before the media dispatch.
        private const val RESYNC_REQUEST_TYPE: Byte = 0x0F
        /// iOS VoiceCallManager.swift:5105 — only flush if we were away long enough
        /// that the OS would have suspended capture; a Siri tap accumulates nothing.
        private const val RESYNC_MIN_BACKGROUND_MS: Long = 2_000L

        /// __CALL_VIDEO_SIGNAL_2026_09_23__ Keyframe requests, per call:
        ///  - the plaintext MEDIA copy: 500 ms / burst 2 (≤ 2/s — the rate the peer's
        ///    encoder acts on; it rides the media budget, 400 pkt/s, where 9 bytes are free);
        ///  - the sealed SIGNAL copy (/signal, typed `keyframeRequest`): ≤ 1/s, like iOS and
        ///    the desktop. The old shared 350 ms / burst 3 bucket put ≈ 170/min on /signal,
        ///    which the server now coalesces above 2/s; one IDR answers every request raised
        ///    while it is in flight, and the receive side now asks only for a reference frame
        ///    that is truly lost ([VideoReorderReassembler]).
        private const val KEYFRAME_REQUEST_MIN_INTERVAL_MS: Long = 500L
        private const val KEYFRAME_REQUEST_BURST: Int = 2
        private const val KEYFRAME_SIGNAL_MIN_INTERVAL_MS: Long = 1_000L

        // 🔒 TURN/STUN server configuration (Phase 4).
        // Credentials are supplied via BuildConfig from keystore.properties
        // (gitignored) so they're never committed in plaintext.
        private const val TURN_SERVER_URL = "turn:45.67.216.197:3478"
        private val TURN_USERNAME: String = com.oshi.messenger.BuildConfig.TURN_USERNAME
        private val TURN_CREDENTIAL: String = com.oshi.messenger.BuildConfig.TURN_PASSWORD

        // ──────────────────────────────────────────────────────────────
        // P2P Internet Call stack — Phase 2 (reserved, not wired up yet).
        // See /Users/HUGOMORICEAU/Documents/Genesis/OSHI-P2P-PLAN.md §2.
        // Must match iOS CallPacketType values in VoiceCallManager.swift.
        // ──────────────────────────────────────────────────────────────
        private const val ICE_CANDIDATE_EXCHANGE_TYPE: Byte = 0x30
        private const val HOLEPUNCH_PING_TYPE:         Byte = 0x31
        private const val HOLEPUNCH_PONG_TYPE:         Byte = 0x32
    }

    // ============================================================================
    // STATE
    // ============================================================================

    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _currentCall = MutableStateFlow<CurrentCall?>(null)
    val currentCall: StateFlow<CurrentCall?> = _currentCall.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _callDuration = MutableStateFlow(0L)
    val callDuration: StateFlow<Long> = _callDuration.asStateFlow()

    /**
     * Why the last call finished. iOS carries the reason **inside** the state —
     * `case ended(reason: CallEndReason)` (`VoiceCallManager.swift:1797-1802`) — and
     * both call views print it (`VoiceCallView.swift:699-725`,
     * `VideoCallView.swift:121-133`). Android's `CallState.ENDED` is a bare token, so
     * a call that failed on a dead network, a call the peer declined and a normal
     * goodbye all reached the screen as the same thing and the screen just vanished.
     *
     * Written once per call, at the top of [endCall] after its guards; read only while
     * the state is `ENDED`, which is the transition that immediately follows.
     */
    private val _lastEndReason = MutableStateFlow<CallEndReason?>(null)
    val lastEndReason: StateFlow<CallEndReason?> = _lastEndReason.asStateFlow()
    /**
     * __LATE_ACCEPT_2026_11_23__ normalized callId of a callEnd that named the current call
     * exactly ([LateAcceptPolicy.isEndForThisCall]). Such an end is never "stale": it may
     * end a CONNECTING / just-connected call, which [endCall]'s guards otherwise refuse.
     */
    @Volatile private var remoteEndConfirmedCallId: String? = null
    private fun remoteEndConfirmedFor(callId: String?): Boolean {
        val c = CallSignalGuards.normalizeCallId(callId)
        return c.isNotEmpty() && c == remoteEndConfirmedCallId
    }
    /** A callEnd naming THIS call (exact id) is real, even mid-accept: remember it. */
    private fun noteRemoteEnd(signalCallId: String?) {
        if (LateAcceptPolicy.isEndForThisCall(signalCallId, _currentCall.value?.callId)) {
            remoteEndConfirmedCallId = CallSignalGuards.normalizeCallId(signalCallId)
        }
    }
    /** __EGRESS_STALL_2026_11_23__ how long no carrier could take our frames. */
    private val egressStall = EgressStallPolicy.Tracker()

    // __CALL_WAKE_2026_11_22__ caller side: did the callee's phone get the call?
    // (GET /call-reach, polled with the fast accept poll while the call rings). null =
    // unknown / server without the route: the screen keeps its old wording.
    private val _calleeReach = MutableStateFlow<CallReach?>(null)
    val calleeReach: StateFlow<CallReach?> = _calleeReach.asStateFlow()
    private val _outgoingRingingSince = MutableStateFlow(0L)
    val outgoingRingingSince: StateFlow<Long> = _outgoingRingingSince.asStateFlow()
    /** Callee side: callIds we already reported as ringing (one report per call). */
    private val reportedRingingCallIds = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    private val _connectionQuality = MutableStateFlow(NetworkQuality.GOOD)
    val connectionQuality: StateFlow<NetworkQuality> = _connectionQuality.asStateFlow()

    /**
     * __QUALITY_FROM_WHAT_IS_HEARD_2026_09_22__ The badge's tier, sampled every 2 s from
     * the frames actually handed to the speaker (see [CallQualityTracker]). Written only
     * by the stats coroutine; [_connectionQuality] mirrors it except while the transport
     * layer owns the badge (RECONNECTING / DISCONNECTED).
     */
    private val qualityTracker = com.oshi.messenger.service.audio.CallQualityTracker()
    /** Cumulative real / PLC frames written to the AudioTrack this call (playback loop). */
    @Volatile private var qualityRealFrames = 0L
    @Volatile private var qualityConcealedFrames = 0L

    /**
     * __VOICE_METER_2026_09_22__ My microphone level, 0…1 on a dB scale
     * ([com.oshi.messenger.service.audio.VoiceMeter.meterLevel]), published at 20 Hz.
     * 0 while muted.
     */
    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    /**
     * __TX_VOICE_EVIDENCE_2026_10_29__ The same levels, kept for the whole call, so
     * a "they could not hear me" rating can say whether we were transmitting a voice
     * or silence. See [com.oshi.messenger.service.audio.CallTxVoiceEvidence] for why
     * the relay's own numbers cannot answer that.
     */
    private val txVoiceEvidence = com.oshi.messenger.service.audio.CallTxVoiceEvidence()

    /**
     * __VOICE_METER_2026_09_22__ The PEER's voice level, 0…1, published when that audio
     * actually leaves the speaker — i.e. delayed by what is already queued in the
     * AudioTrack — so the call-screen bars move with the voice, not ahead of it.
     */
    private val _remoteLevel = MutableStateFlow(0f)
    val remoteLevel: StateFlow<Float> = _remoteLevel.asStateFlow()
    @Volatile private var lastMicLevelAtMs = 0L
    @Volatile private var lastRemoteLevelAtMs = 0L
    /** PCM frames (samples) written to the AudioTrack this call — for its queue depth. */
    @Volatile private var playbackFramesWritten = 0L

    private val _connectionType = MutableStateFlow(ConnectionType.MESH_DIRECT)
    val connectionType: StateFlow<ConnectionType> = _connectionType.asStateFlow()

    private val _jitterBufferStats = MutableStateFlow<JitterBufferStats?>(null)
    val jitterBufferStats: StateFlow<JitterBufferStats?> = _jitterBufferStats.asStateFlow()

    private val _latencyMs = MutableStateFlow(0L)
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()

    private val _bitrateLevel = MutableStateFlow(com.oshi.messenger.service.audio.AdaptiveBitrateController.BitrateLevel.MEDIUM)
    val bitrateLevel: StateFlow<com.oshi.messenger.service.audio.AdaptiveBitrateController.BitrateLevel> = _bitrateLevel.asStateFlow()

    private val _callQualityStats = MutableStateFlow<com.oshi.messenger.service.audio.CallQualityStats?>(null)
    val callQualityStats: StateFlow<com.oshi.messenger.service.audio.CallQualityStats?> = _callQualityStats.asStateFlow()

    private val _isBluetoothOn = MutableStateFlow(false)
    val isBluetoothOn: StateFlow<Boolean> = _isBluetoothOn.asStateFlow()

    // 🎤 Voice effect state for real-time calls
    private val _currentVoiceEffect = MutableStateFlow(CallVoiceEffectProcessor.VoiceEffect.NORMAL)
    val currentVoiceEffect: StateFlow<CallVoiceEffectProcessor.VoiceEffect> = _currentVoiceEffect.asStateFlow()

    // 📹 Remote camera paused state (set by 0x0C/0x0D and by VUPG 0x05/0x06/0x07)
    private val _isRemoteCameraPaused = MutableStateFlow(false)
    val isRemoteCameraPaused: StateFlow<Boolean> = _isRemoteCameraPaused.asStateFlow()

    // 📹 Are WE sending our camera in this video call? false = "watch only".
    // iOS `shareOwnVideo` — VoiceCallManager.swift:10531,10566,10615,10625.
    private val _shareOwnVideo = MutableStateFlow(true)
    val shareOwnVideo: StateFlow<Boolean> = _shareOwnVideo.asStateFlow()

    // 📹 Pending video upgrade request from remote peer
    private val _pendingVideoUpgrade = MutableStateFlow(false)
    val pendingVideoUpgrade: StateFlow<Boolean> = _pendingVideoUpgrade.asStateFlow()

    // 📹 Display name of the peer asking us to enable video (set together with _pendingVideoUpgrade)
    private val _pendingVideoUpgradeFrom = MutableStateFlow<String?>(null)
    val pendingVideoUpgradeFrom: StateFlow<String?> = _pendingVideoUpgradeFrom.asStateFlow()

    // 📹 Caller-side: we sent 0x0E request and are waiting for accept/decline (or 15 s timeout)
    private val _isAwaitingVideoUpgrade = MutableStateFlow(false)
    val isAwaitingVideoUpgrade: StateFlow<Boolean> = _isAwaitingVideoUpgrade.asStateFlow()

    // 📹 Caller-side: emitted when peer declined or timeout expired (UI shows toast)
    private val _videoUpgradeDeclined = MutableStateFlow<String?>(null)
    val videoUpgradeDeclined: StateFlow<String?> = _videoUpgradeDeclined.asStateFlow()

    // 📹 Local capture failed (permission, no camera, no frames). Null while healthy;
    // the screen consumes it. Codes are VideoCallManager.ERROR_CAMERA_*.
    private val _videoError = MutableStateFlow<String?>(null)
    val videoError: StateFlow<String?> = _videoError.asStateFlow()

    fun consumeVideoError() { _videoError.value = null }

    /**
     * Ce que le pair a annoncé sur la vidéo.
     *
     * `null` = il n'a RIEN annoncé, donc on ne SAIT pas — et « je ne sais pas »
     * doit se comporter exactement comme avant, pas comme un refus. Sinon la
     * mise à jour désactiverait la vidéo avec tous les téléphones qui ne se sont
     * pas encore mis à jour.
     */
    @Volatile
    private var peerSupportsVideo: Boolean? = null

    private var videoUpgradeTimeoutJob: Job? = null

    /**
     * The fire-and-forget coroutine `endCall` uses to tear the video subsystem
     * down (`stopVideo()` + null the raw-video callback). It is UNSEQUENCED with
     * respect to the next call: `stopVideo()` closes the camera and stops both
     * MediaCodecs, which takes long enough that a fast second call can reach
     * `startVideoForCall()` while this is still in flight. If it lands afterwards
     * it sets `_isVideoEnabled=false` and nulls `onRawVideoPacketReady`, which
     * blinds the RX gate (inbound video dropped) and kills the TX callback
     * (frames encode locally but never reach the wire) — the "2nd/3rd video call
     * shows no video, audio fine" bug. `startVideoForCall()` joins this before
     * re-arming so every start begins from a fully torn-down subsystem, the same
     * way audio recreates AudioRecord/AudioTrack from scratch each call.
     */
    private var videoTeardownJob: Job? = null

    /**
     * __VUPG_CALLEE_TIMEOUT_2026_09_14__ Le pendant côté DESTINATAIRE.
     *
     * Le demandeur abandonnait au bout de quinze secondes; celui qui reçoit
     * n'avait AUCUN délai, et le dialogue Android est MODAL — sa seule sortie
     * est de refuser. Pire: accepter une minute plus tard tirait le demandeur,
     * à qui on avait dit « pas de réponse », dans un appel vidéo caméra allumée.
     */
    private var calleeVideoRequestTimeoutJob: Job? = null

    // 📹 Video state (delegated from VideoCallManager)
    val isVideoEnabled: StateFlow<Boolean> get() = videoCallManager.isVideoEnabled
    val isSendingVideo: StateFlow<Boolean> get() = videoCallManager.isSendingVideo
    val isReceivingVideo: StateFlow<Boolean> get() = videoCallManager.isReceivingVideo
    val localVideoFrame: StateFlow<android.graphics.Bitmap?> get() = videoCallManager.localVideoFrame
    val remoteVideoFrame: StateFlow<android.graphics.Bitmap?> get() = videoCallManager.remoteVideoFrame
    val videoQuality: StateFlow<VideoQuality> get() = videoCallManager.currentQuality
    val videoBlurMode: StateFlow<BlurMode> get() = videoCallManager.blurMode
    val videoBlurIntensity: StateFlow<Float> get() = videoCallManager.blurIntensity

    // ============================================================================
    // AUDIO COMPONENTS
    // ============================================================================

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    /**
     * Platform echo canceller / noise suppressor explicitly bound to the capture session.
     * `AudioSource.VOICE_COMMUNICATION` asks the HAL for them, but several OEM builds
     * leave the pre-processing OFF unless an effect is enabled on the session — then a
     * handsfree Android returns the peer's own voice to them. Enabling an effect the HAL
     * already applies is a no-op; see [attachCaptureEffects].
     */
    private var echoCanceler: android.media.audiofx.AcousticEchoCanceler? = null
    private var noiseSuppressor: android.media.audiofx.NoiseSuppressor? = null
    private var audioManager: AudioManager? = null

    // Proximity sensor for earpiece/speaker switching
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    // Separate locks for AudioTrack (playback) and AudioRecord (capture) to prevent contention
    private val audioTrackLock = Object()
    private val audioRecordLock = Object()
    @Volatile private var audioStopping = false

    // 🔧 iOS-COMPATIBLE CODEC: AAC-ELD is PRIMARY (same as FaceTime), G.726 is fallback
    // iOS uses AAC-ELD at 48kHz — Android MUST match for cross-platform calls
    // startAudio() will try AAC-ELD first; only falls back to G.726 if device doesn't support it
    private var aacEldCodec: AacEldCodec? = null
    private val g726Fallback = WhatsAppQualityCodec(CAPTURE_SAMPLE_RATE)
    private var useAacEld = false  // Set to true by startAudio() when AAC-ELD init succeeds

    // Jitter buffer for smooth playback
    private val jitterBuffer = AudioJitterBuffer(
        targetDepthMs = 100,
        minDepthMs = 60,
        maxDepthMs = 200
    )

    /**
     * __CALL_FLUIDITY_2026_11_21__ The Opus (0x19) receive path: frames queued ENCODED by
     * codec seq and decoded at playout ([AdaptiveJitterBuffer] + [OpusPlayoutDecoder]),
     * the same engine as the desktop. Every other codec keeps [jitterBuffer].
     * Measured (FluiditySimBench, virtual time, same impairments as the desktop bench):
     * see the commit / AdaptiveJitterBuffer's doc.
     */
    private val opusPlayout = com.oshi.messenger.service.audio.OpusPlayoutDecoder()
    private val opusJitter = com.oshi.messenger.service.audio.AdaptiveJitterBuffer(opusPlayout)
    private val opusSeq = com.oshi.messenger.service.audio.SeqUnwrapper()
    /** Set by the first 0x19 received: from then on the playout loop plays [opusJitter]. */
    @Volatile private var opusRxActive = false

    private fun resetOpusPlayout() {
        opusRxActive = false
        opusJitter.reset()
        opusSeq.reset()
    }

    // Adaptive bitrate controller (iOS compatible)
    private val adaptiveBitrate = com.oshi.messenger.service.audio.AdaptiveBitrateController()

    // Forward Error Correction (iOS compatible)
    private val fecSender = com.oshi.messenger.service.audio.ForwardErrorCorrection()
    private val fecReceiver = com.oshi.messenger.service.audio.ForwardErrorCorrection()

    // 🎤 Real-time voice effect processor for calls
    private val callVoiceEffectProcessor = CallVoiceEffectProcessor()

    // Callback for sending call summary messages (set by ViewModel)
    var onCallSummary: ((peerPublicKey: String, content: String) -> Unit)? = null

    // Session key for encryption
    private var sessionKey: ByteArray? = null

    // 🔧 CROSS-PLATFORM: Track remote peer's platform to skip codec for iOS↔Android calls
    // AAC-ELD bitstreams from Apple AudioToolbox and Android MediaCodec are INCOMPATIBLE.
    // Default "unknown" so first packets use raw PCM (universally decodable). Only
    // after we confirm peer is Android do we switch to AAC-ELD. "ios" forces raw PCM.
    private var remotePlatform: String = "unknown"

    // Call v2 — OshiCodec negotiation state (Agent 6).
    // This build always supports OshiCodec locally; older builds don't parse the flag
    // byte and effectively advertise "unsupported", which forces the raw-PCM fallback.
    // Currently forced false — the reference LPC-10 impl decimates the residual too
    // aggressively and the piecewise-constant expansion on the decode side generates
    // electronic-noise artifacts on real voice. Falling back to raw PCM until the codec
    // is tuned or replaced. Negotiation + auto-upgrade + wire path all stay intact;
    // flip back to `OshiCodec.isAvailable` to re-enable OshiCodec end-to-end.
    private val localSupportsOshiCodec: Boolean = false
    // Negotiated outcome — true only when BOTH sides advertised OshiCodec support.
    @Volatile private var useOshiCodec: Boolean = false
    // Separate instances for each direction keep LPC history from cross-pollinating.
    private var oshiEncoder: OshiCodec? = null
    private var oshiDecoder: OshiCodec? = null

    // 16 kHz PCM downsampled path — halves cross-platform bandwidth. True only
    // after BOTH sides advertised RAW_PCM_16K_CAP_FLAG (0x02); the receive side
    // also auto-upgrades on the first 0x17 packet. When true, TX downsamples
    // effectPcm 48 → 16 kHz via biquad+decimate BEFORE encryption and the
    // wire type flips from 0x15 to 0x17.
    @Volatile private var use16kHzPCM: Boolean = false
    // __WB_ADPCM_CODEC_2026_09_23__ wideband IMA-ADPCM (0x18) — the codec Android, iOS and
    // Desktop share: 200 B/datagram instead of 1957 B raw PCM (no IP fragments ⇒ survives
    // VPNs / carriers / the relay). Negotiated by capability byte 0x10 (offer 5th trailing
    // byte, accept index 13). Used only where raw PCM (0x15/0x17) would otherwise go out;
    // Android↔Android AAC-ELD is untouched.
    private val localSupportsWbAdpcm: Boolean = true
    @Volatile private var useWbAdpcm: Boolean = false
    // __OPUS_CODEC_2026_09_23__ Opus (0x19) — PESQ-WB ~4.2 where 0x18 gives ~2.5-3.6, in
    // ≤ 300 B datagrams (2 older frames repeated inside for loss recovery). Pure-Java
    // Concentus compiled from the vendored source (Vendor/concentus); iOS runs libopus built
    // from Vendor/opus; interop measured both ways. Capability 0x20: 6th trailing byte of the
    // offer, index 14 of the accept. Preferred over 0x18 when both sides advertise it; 0x18
    // / 0x15 remain the fallbacks for older peers.
    private val localSupportsOpus: Boolean = true
    @Volatile private var useOpus: Boolean = false
    private var opusTx: com.oshi.messenger.service.audio.OpusCallEncoder? = null
    /** __CALL_TX_SILENT_DROP_2026_11_10__ every way a captured frame failed to leave. */
    private val txHealth = com.oshi.messenger.service.audio.TxHealth()
    private val wbTxState = com.oshi.messenger.service.audio.WbAdpcmCodec.EncoderState()
    private val wbTxResampler = com.oshi.messenger.service.audio.WbResampler()
    private val wbRxResampler = com.oshi.messenger.service.audio.WbResampler()
    // __WB_POSTFILTER_2026_09_23__ receiver-side ADPCM hiss removal (PESQ-WB 2.5 → 3.6).
    private val wbRxPostFilter = com.oshi.messenger.service.audio.WbPostFilter()
    // __WB_RX_LOCK_2026_09_24__ handleAudioData runs on several threads (P2P coroutine,
    // relay and WebSocket paths). WbPostFilter / WbResampler / OpusCallDecoder are stateful
    // and not thread-safe; a peer sending on two carriers at once (the desktop while it
    // probes routes, or any client mirroring after a switch) raced them — the same bug
    // crashed iOS build 148 (Array index trap in WbPostFilter.process). One lock serialises
    // RX decode state and its reset.
    private val wbRxLock = Any()
    // Biquad state (transposed direct-form II). int32 to match iOS's Swift
    // Int32 state bit-for-bit. Separate TX/RX filters — they run on different
    // sample streams and must not share memory.
    private var pcm16TxZ1: Int = 0
    private var pcm16TxZ2: Int = 0
    private var pcm16RxZ1: Int = 0
    private var pcm16RxZ2: Int = 0

    // 🔧 Relay toggle: when true, force VPS relay even when cross-platform mesh is
    // available (user-configurable in Settings). Default false = P2P preferred.
    @Volatile
    var useRelayMode: Boolean = false
        set(value) {
            field = value
            Log.d(TAG, "📞 Relay mode: $value")
        }

    // 🔧 iOS-COMPATIBLE: Nonce salt for audio encryption (4 random bytes per call)
    // iOS format: nonce = salt(4) + counter_BE(8) — replay protection checks counter monotonicity
    private var sessionNonceSalt: ByteArray = ByteArray(4)

    // ────────────────────────────────────────────────────────────────────────
    // 🔐 CRYPTO FIX — AES-GCM NONCE REUSE ACROSS DIRECTIONS (audio).
    // Port of iOS VoiceCallManager.swift:2996-3034 (fix landed there 2026-07-26).
    //
    // What was broken here: `encryptAudioPacket` always stamped `sessionNonceSalt`,
    // which is the SAME 4 bytes on both ends (the caller generates it and ships it
    // in the offer body at :948/:973; the callee installs it verbatim at :1904).
    // Both ends also share one AES key and both start their counter at 1. So packet
    // n in each direction carried a BIT-IDENTICAL (key, nonce) pair. That is a
    // textbook GCM break: the XOR of the two ciphertexts leaks the XOR of the
    // plaintexts, and the GHASH authentication subkey H is recoverable from the
    // pair → forgery. It failed silently because the 12-byte nonce rides on the
    // wire (iOS VoiceCallManager.swift:12512, Android :2447) so decryption never
    // noticed.
    //
    // Fix (verbatim iOS): the RESPONDER derives its transmit salt from the shared
    // one by XOR-ing the first byte with 0xA5 (iOS VoiceCallManager.swift:3019-3024).
    // WIRE-TRANSPARENT: nothing on any receiver derives meaning from the nonce
    // BYTES, so an OLD peer (old Android, current iOS) decrypts our packets
    // unchanged. Old-responder ↔ new-initiator still collides — that half cannot be
    // fixed unilaterally without a negotiated capability byte, and iOS explicitly
    // accepted the same residual risk (iOS VoiceCallManager.swift:3009-3010).
    // ────────────────────────────────────────────────────────────────────────
    /** Salt WE stamp into every nonce we emit. iOS `txNonceSalt` (VoiceCallManager.swift:3012). */
    private var txNonceSalt: ByteArray = ByteArray(0)
    /** Salt we EXPECT from the peer. Diagnostics only — decrypt reads the wire. iOS :3014. */
    private var rxNonceSalt: ByteArray = ByteArray(0)

    /**
     * Responder's transmit salt = base salt with its first byte flipped by 0xA5.
     * Always differs from the base, so initiator-TX and responder-TX nonces can
     * never collide even at identical counter values.
     * iOS VoiceCallManager.swift:3019-3024 (`responderNonceSalt(from:)`).
     */
    private fun responderNonceSalt(base: ByteArray): ByteArray =
        com.oshi.messenger.service.audio.CallNonce.responderNonceSalt(base)

    /**
     * Assign the per-direction salts once the shared salt is known.
     * [isInitiator] = we placed the call (we generated the salt).
     * iOS VoiceCallManager.swift:3028-3034 (`applyDirectionalNonceSalts`),
     * called from the initiator at :5710 and the responder at :6531.
     */
    private fun applyDirectionalNonceSalts(isInitiator: Boolean) {
        val base = sessionNonceSalt
        txNonceSalt = com.oshi.messenger.service.audio.CallNonce.audioTxSalt(base, isInitiator)
        rxNonceSalt = com.oshi.messenger.service.audio.CallNonce.audioRxSalt(base, isInitiator)
        Log.d(
            TAG,
            "🔐 Nonce salts: role=${if (isInitiator) "initiator" else "responder"} " +
                "tx=${txNonceSalt.joinToString("") { "%02x".format(it) }} " +
                "rx=${rxNonceSalt.joinToString("") { "%02x".format(it) }}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // 🔐 CRYPTO FIX — AES-GCM NONCE REUSE IN VIDEO.
    // Port of iOS VideoCallManager.swift:241-330 (fix landed there 2026-07-27).
    //
    // What was broken here: `encryptVideoPacket` built `[counter LE(8)][0x00 ×4]` —
    // VERBATIM the layout iOS documents as "a total GCM break" at
    // VideoCallManager.swift:244-252. No salt, no direction bit, and the counter is
    // reset to 0 on every startVideo. Two Android peers therefore emit bit-identical
    // nonces for fragment n in both directions under the same (audio) session key.
    //
    // New layout — iOS VideoCallManager.swift:257-271:
    //     nonce = [salt(4)][counter BE(8)]
    //   • bit 7 of salt byte 0 = DIRECTION BIT, taken from `isOutgoing`: caller ⇒ 1,
    //     callee ⇒ 0. The two devices in one call always disagree, so the two
    //     directions occupy provably DISJOINT nonce spaces.
    //   • the remaining 31 bits are fresh random per VIDEO SESSION (re-drawn on every
    //     startVideo/stopVideo cycle) so a voice→video→voice→video toggle inside one
    //     call — which resets the counter — cannot replay a nonce.
    //   • bit 63 of the counter field is FORCED SET (VIDEO_NONCE_DOMAIN_BIT) so the
    //     video nonce space can never alias the audio [salt(4)][counter BE(8)] space
    //     that shares the same key.
    // ────────────────────────────────────────────────────────────────────────
    /** TX salt for the CURRENT video session. null ⇒ derive on next use. iOS :284. */
    private var videoTxSalt: ByteArray? = null
    /** Video GCM counter, independent of the outer wire seq. iOS `_videoNonce` :282. */
    private var videoNonceCounter: Long = 0L
    private val videoNonceLock = Any()

    // 🔐 RX anti-replay for video — iOS VideoCallManager.swift:332-384.
    // A bounded set of recently-seen 12-byte nonces rather than a counter
    // high-water mark, deliberately: peers running the OLD build emit
    // [counter LE(8)][0000], whose bytes 4..11 are constant zero, so a
    // counter-based window would read every one of their packets as "counter 0"
    // and reject the whole stream after the first fragment. Comparing whole
    // nonces is layout-agnostic and safe against every peer version.
    // Checked AFTER the GCM tag verifies, so an attacker cannot poison the window.
    /** iOS VideoCallManager.swift:352 — `MAX_SEEN_NONCES = 1024` (~2 s at 30 fps × 16 frags). */
    private val videoReplayWindow = com.oshi.messenger.service.audio.NonceReplayWindow()

    // Audio sequence number (also used as the AES-GCM nonce counter)
    @Volatile private var audioSequenceNumber = 0
    private val audioCounterLock = Any()

    /**
     * Atomically consume the next audio nonce counter.
     *
     * 🔐 Three independent threads draw from this counter — the 50 Hz audio send
     * loop, the 3 s keepalive coroutine, and the video-upgrade control path (plus
     * keepalive PONGs from the RX thread). A plain `audioSequenceNumber++` is a
     * read-modify-write, so two of them racing could hand out the SAME counter
     * twice, i.e. the same (key, nonce) pair under one direction — the same class
     * of break as the cross-direction one fixed above. iOS serialises this with
     * `incrementCallNonce()` (VoiceCallManager.swift:12480).
     */
    private fun nextAudioCounter(): Long = synchronized(audioCounterLock) {
        audioSequenceNumber += 1
        audioSequenceNumber.toLong()
    }

    // Adaptive bitrate: track last applied bitrate to avoid redundant setBitrate() calls
    private var lastAdaptiveBitrate = 64000  // default matches AacEldCodec DEFAULT_BIT_RATE

    // Inline redundancy: previous encoded frame for FEC-style redundancy (iOS compatible)
    // Format: [0x01 version][2 bytes prev length BE][prev frame][current frame]
    // Version 0x00 = no redundancy (backwards compat with old clients)
    private var previousEncodedFrame: ByteArray? = null

    // Video sequence number
    private var videoSequenceNumber = 0

    // Audio focus for proper VoIP routing (matching iOS AVAudioSession)
    private var audioFocusRequest: AudioFocusRequest? = null

    // Ringtone & Ringback — __OSHI_CALL_SOUNDS_2026_09_28__ the OSHI sounds (CallSounds)
    private var ringtoneSound: CallSounds.Handle? = null
    /** The caller's sound: the connecting pulse, then the ringback. */
    private var outgoingSound: CallSounds.Handle? = null
    private var ringbackStartJob: Job? = null
    private var connectingSoundStartedAt = 0L
    private var vibrator: Vibrator? = null

    // ============================================================================
    // COROUTINES
    // ============================================================================

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 🧭 P2P Internet Call stack (Phase 3 + 4). Owns UDP socket, STUN discovery,
    // hole-punching, TURN relay fallback and ICE restart. Audio + video route
    // through this whenever state is CONNECTED or RELAY.
    private val p2p: P2PTransport = P2PTransport(scope).also { transport ->
        // __RELAY_FIRST_UPGRADE_2026_09_23__ verify direct with the size we really send:
        // 1200 B once media is compact (WB-ADPCM / AAC-ELD / Oshi), 2000 B for raw PCM.
        transport.mediaProbeBytesProvider = {
            com.oshi.messenger.service.p2p.P2PPathPolicy.probeBytesFor(compactMediaOnWire())
        }
        transport.onStateChanged = { s ->
            CallDiag.d(TAG, "🧭 P2P: state → $s")
            // WHY: DIAG_P2P_STATE — one line per transition; mirrors iOS so logs can be diffed side-by-side.
            val newStr = s.toString()
            if (newStr != diagLastP2PStateStr) {
                val reason = when (s) {
                    P2PTransport.State.FAILED -> "all_probes_failed"
                    P2PTransport.State.CONNECTED -> "direct_pair_selected"
                    P2PTransport.State.RELAY -> "turn_relay_selected"
                    P2PTransport.State.PROBING -> "sending_ping"
                    P2PTransport.State.SIGNALING -> "gathered_emit_candidates"
                    P2PTransport.State.GATHERING -> "stun_running"
                    P2PTransport.State.IDLE -> "idle"
                }
                val localCount = transport.localCandidates.size
                CallDiag.w(TAG, "DIAG_P2P_STATE | old=${diagLastP2PStateStr.ifEmpty { "idle" }} | new=$newStr | reason=$reason | localCands=$localCount | forceRelay=$p2pForceRelayActive")
                diagLastP2PStateStr = newStr
            }
            if (s == P2PTransport.State.FAILED) {
                CallDiag.w(TAG, "🧭 P2P: ⚠️ fallback to VPS after P2P failure")
                _connectionType.value = ConnectionType.VPS
            }
            if (s == P2PTransport.State.CONNECTED) {
                p2pLitUpAt = System.currentTimeMillis()
                _connectionType.value = ConnectionType.MESH_DIRECT
            }
            if (s == P2PTransport.State.RELAY) {
                p2pLitUpAt = System.currentTimeMillis()
                _connectionType.value = ConnectionType.RELAY
            }
        }
        // Inbound UDP packets (direct + relay) feed the existing decrypt +
        // jitter-buffer pipeline. Type 0x05 / 0x15 / 0xF1 / 0xFE are all
        // dispatched inside handleAudioData(); 0xF1 is routed onward to
        // videoCallManager.receiveRawVideoData() there.
        transport.onPacketReceived = { data ->
            // Liveness tick — drives the 2 s stale-inbound fallback in
            // p2pCanCarryAudio() so silent P2P failures roll back to VPS fast.
            lastP2PInboundAt = System.currentTimeMillis()
            notePathRx(AudioTxPrimary.P2P)
            val peerKey = _currentCall.value?.peerPublicKey
            if (peerKey != null) {
                scope.launch { handleAudioData(data, peerKey, fromP2P = true) }
            }
        }
    }

    // F6 — TURN credential rotation cache
    private data class CachedTurnCreds(val username: String, val password: String, val expiresAtMs: Long)
    @Volatile private var cachedTurnCreds: CachedTurnCreds? = null
    @Volatile private var turnCredsFetchInFlight: Boolean = false

    /**
     * Refresh cached TURN credentials when stale (within 5 min of expiry) and
     * push them onto the [p2p] transport via [P2PTransport.configureTurn].
     * Falls back silently to legacy hardcoded creds (TURN_USERNAME / TURN_CREDENTIAL)
     * if the fetch fails — keeps calls working during transition. Safe to call
     * before every [p2p.start] — short-circuits when cache is fresh.
     */
    private suspend fun ensureFreshTurnCreds() {
        val now = System.currentTimeMillis()
        val cached = cachedTurnCreds
        if (cached != null && cached.expiresAtMs - now > 300_000L) {
            // Fresh enough — push current cached creds onto transport in case
            // a previous configureTurn was cleared by transport recreation.
            p2p.configureTurn(cached.username, cached.password)
            return
        }
        if (turnCredsFetchInFlight) return
        turnCredsFetchInFlight = true
        try {
            val creds = vpsClient.getTurnCreds()
            if (creds != null) {
                val (user, pass, ttl) = creds
                cachedTurnCreds = CachedTurnCreds(user, pass, now + ttl * 1000L)
                p2p.configureTurn(user, pass)
                Log.d(TAG, "🔑 TURN creds refreshed — ttl=${ttl}s")
            } else {
                Log.w(TAG, "🔑 TURN creds fetch failed — keeping legacy fallback (TURN_USERNAME/CREDENTIAL)")
            }
        } finally {
            turnCredsFetchInFlight = false
        }
    }

    /** True when the user's "Always use relay" toggle is on for this call — P2P may gather but must NOT carry audio. */
    @Volatile private var p2pForceRelayActive: Boolean = false
    /** Wall-clock ms when P2P first reached CONNECTED or RELAY. 0 = not yet. */
    @Volatile private var p2pLitUpAt: Long = 0L
    /** Wall-clock ms of last inbound packet over P2P. 0 = none yet. */
    @Volatile private var lastP2PInboundAt: Long = 0L
    /** Dual-send grace window after P2P lights up (keeps VPS send too). */
    private val P2P_GRACE_PERIOD_MS = 5_000L
    /** After the grace window, this much silence on P2P triggers VPS fallback. */
    private val P2P_INBOUND_STALE_MS = 2_000L

    /** True when the P2P transport has a live pair (direct or via TURN relay). */
    private fun p2pReady(): Boolean {
        val s = p2p.state
        return s == P2PTransport.State.CONNECTED || s == P2PTransport.State.RELAY
    }

    /**
     * True when P2P is live AND allowed to carry audio. Relay-toggle ON
     * always returns false — VPS carries every frame. Stale inbound (no
     * packet for [P2P_INBOUND_STALE_MS] after the grace window) also returns
     * false so a silent P2P failure rolls back to VPS within 2 s.
     */
    /** One-shot watchdog: log if P2P has not lit up within 5s of call start. */
    private fun armP2PWatchdog() {
        p2pWatchdogJob?.cancel()
        p2pWatchdogJob = scope.launch {
            delay(5_000L)
            val s = p2p.state
            if (s != P2PTransport.State.CONNECTED && s != P2PTransport.State.RELAY) {
                CallDiag.w(TAG, "🧭 P2P DID NOT LIGHT UP — falling back to VPS for send")
            }
        }
    }

    /**
     * True when the P2P UDP transport is live AND allowed to carry audio.
     *
     * 🔧 FIX (audit B-2, "connected, zero audio") — port of iOS
     * VoiceCallManager.swift:10024-10042. The staleness check used to require BOTH
     * `p2pLitUpAt` AND `lastP2PInboundAt` to be non-zero, so the "never received
     * anything" case fell straight through and returned true. But
     * `lastP2PInboundAt` is only set by APPLICATION packets — ping/pong are consumed
     * inside P2PTransport and never reach here. A pair that pongs happily but
     * BLACK-HOLES media therefore had `lastP2PInboundAt == 0`, skipped the guard,
     * and was reported HEALTHY for the whole call. iOS names this exact defect at
     * :10028-10036. After the grace period, "never received anything" must count as
     * UNHEALTHY.
     *
     * This MUST land with the TX-selection change below: while the 4-way shotgun
     * was live it papered over the black hole; with one nominated carrier a
     * permanently-"healthy" black hole is a silent call.
     */
    /** Every media datagram we emit fits in 1200 B (no raw 48 kHz PCM on the wire). */
    private fun compactMediaOnWire(): Boolean =
        useOpus || useWbAdpcm || useOshiCodec || remotePlatform == "android"

    private fun p2pCanCarryAudio(): Boolean {
        if (p2pForceRelayActive) return false
        if (!p2pReady()) return false
        // __RELAY_FIRST_UPGRADE_2026_09_23__ consent: the active direct pair must have ponged
        // within ~1 s (+RTT) — two lost 2 Hz pongs put the next frame back on the warm VPS
        // relay instead of waiting 2 s (inbound staleness) or 5 s (pair liveness).
        if (p2p.state == P2PTransport.State.CONNECTED && !p2p.activePairConsentFresh) return false
        val lit = p2pLitUpAt
        val last = lastP2PInboundAt
        if (lit > 0L) {
            val now = System.currentTimeMillis()
            if (now - lit >= P2P_GRACE_PERIOD_MS) {
                // iOS :10038 — `guard let last = lastP2PInboundAt else { return false }`
                if (last == 0L) return false
                // iOS :10039 — stale inbound after the grace window.
                if (now - last > P2P_INBOUND_STALE_MS) return false
            }
        }
        return true
    }

    // ────────────────────────────────────────────────────────────────────────
    // 🚚 TX CARRIER SELECTION — port of iOS VoiceCallManager.swift:10044-10128.
    //
    // 🔧 FIX (audit B-1): Android sent every audio frame over P2P + UDP relay
    // :8089 + CrossPlatformMesh TCP + VPS WS/HTTP, unconditionally. At 50 fps ×
    // 1957 B raw PCM that is up to ~3.1 Mbit/s of uplink on a cross-platform call.
    // iOS deleted exactly this policy on 2026-07-27 with a measurement in its own
    // comment (VoiceCallManager.swift:10195-10201): "2095 sent / 106 arrived …
    // doubling the offered load on precisely the link that could least afford it".
    //
    // Order of authority (iOS :10056-10062):
    //   1. received-media EVIDENCE — a path actually landing frames wins over any
    //      amount of ICE/registration state;
    //   2. availability (relay registered and the server answered recently);
    //   3. during a TOTAL blackout — nothing arriving on ANY path — rotate through
    //      the candidates every TX_BLACKOUT_ROTATE_MS. This breaks the mutual
    //      demotion deadlock iOS documents at :10064-10072: both ends stop sending
    //      on the relay because neither has received on it, and neither ever will.
    // ────────────────────────────────────────────────────────────────────────

    /**
     * MESH is an Android-only candidate that iOS does not have.
     *
     * iOS deleted mesh as a media carrier outright (VoiceCallManager.swift:10261-10289)
     * and could do so safely because `connectionType` is ALWAYS `.meshDirect` on iOS —
     * a constant, not a decision — so mesh was silently winning every co-located call
     * and making transport measurements meaningless. On Android the CrossPlatformMesh
     * TCP bridge is a genuinely distinct carrier and is the ONLY one available on a
     * fully offline LAN call, so deleting it outright would turn those calls silent.
     *
     * The compromise keeps iOS's actual property — ONE carrier per frame, never a
     * shotgun. Mesh is never chosen while P2P, the :8089 relay or VPS are delivering;
     * it only enters the total-blackout rotation, where exactly one candidate is live
     * at a time. Steady-state cost: zero.
     */
    enum class AudioTxPrimary { P2P, UDP_RELAY, WS, MESH }

    /** iOS `transportLivenessWindow` — a path must have delivered inside this window. */
    private val TRANSPORT_LIVENESS_WINDOW_MS = 2_000L
    /** iOS `txBlackoutRotateSeconds`. */
    private val TX_BLACKOUT_ROTATE_MS = 2_000L
    /** iOS `txDuplicateWindowSeconds` — mirror window after a change of primary. */
    private val TX_DUPLICATE_WINDOW_MS = 2_000L

    /** Per-path last received-media timestamp. Evidence, not configuration. */
    private val pathLastRxAt = java.util.concurrent.ConcurrentHashMap<AudioTxPrimary, Long>()
    @Volatile private var txPrimaryCurrent: AudioTxPrimary? = null
    @Volatile private var videoTxPrimaryCurrent: AudioTxPrimary? = null
    @Volatile private var txPrimarySelectedAt: Long = 0L
    /** __RELAY_FIRST_UPGRADE_2026_09_23__ make-before-break window after relay → direct. */
    @Volatile private var txUpgradeOverlapUntil: Long = 0L
    @Volatile private var txUpgradeOverlapFrom: AudioTxPrimary? = null
    @Volatile private var txBlackoutRotatedAt: Long = 0L
    @Volatile private var txBlackoutCursor: Int = 0

    /** Record that [path] just delivered media to us. iOS `recordRx`/`lastRxAt`. */
    private fun notePathRx(path: AudioTxPrimary) {
        pathLastRxAt[path] = System.currentTimeMillis()
    }

    /**
     * True when a path has DELIVERED media inside the liveness window.
     * iOS VoiceCallManager.swift:10048-10051 (`pathIsDelivering`).
     */
    private fun pathIsDelivering(path: AudioTxPrimary, now: Long): Boolean {
        val rx = pathLastRxAt[path] ?: return false
        return now - rx < TRANSPORT_LIVENESS_WINDOW_MS
    }

    /**
     * True when the :8089 UDP relay is a legitimate carrier right now: registered
     * AND the server answered inside [UDP_RELAY_STALE_MS] (the very first send is
     * always allowed, since no reply can exist before it).
     * iOS VoiceCallManager.swift:10124-10128 (`udpRelayUsable`).
     */
    private fun udpRelayUsable(): Boolean {
        val relay = udpRelay ?: return false
        if (!relay.isRegistered) return false
        val t = relay.lastServerReplyAtMs
        if (t == 0L) return true  // first send is always allowed
        return System.currentTimeMillis() - t < UDP_RELAY_STALE_MS
    }

    /**
     * Pick the ONE path that carries this frame.
     * iOS VoiceCallManager.swift:10079-10117 (`chooseAudioTxPrimary`).
     *
     * [advanceRotation] exists because the video TX path calls this too, from a
     * different thread. Only the audio send loop may advance the blackout cursor —
     * otherwise video steals rotation ticks and shortens every probe window
     * (iOS :10074-10078).
     */
    private fun chooseAudioTxPrimary(
        p2pHealthy: Boolean,
        relayUsable: Boolean,
        advanceRotation: Boolean = true
    ): AudioTxPrimary {
        // P2P (incl. TURN) suppresses the VPS tier entirely when it is healthy.
        if (p2pHealthy) return AudioTxPrimary.P2P

        val now = System.currentTimeMillis()

        // 1) Evidence first.
        if (pathIsDelivering(AudioTxPrimary.UDP_RELAY, now)) return AudioTxPrimary.UDP_RELAY
        if (pathIsDelivering(AudioTxPrimary.WS, now)) return AudioTxPrimary.WS
        if (pathIsDelivering(AudioTxPrimary.MESH, now)) return AudioTxPrimary.MESH

        // 3) Total blackout — nothing has delivered on any transport recently.
        val nothingArriving = now - lastAudioReceivedTime > TRANSPORT_LIVENESS_WINDOW_MS
        val p2pPlausible = p2pReady() && !p2pForceRelayActive
        if (nothingArriving) {
            val candidates = ArrayList<AudioTxPrimary>(4)
            if (relayUsable) candidates.add(AudioTxPrimary.UDP_RELAY)
            candidates.add(AudioTxPrimary.WS)
            if (p2pPlausible) candidates.add(AudioTxPrimary.P2P)
            // Android-only: the offline-LAN carrier, probed like any other candidate.
            val meshPeer = _currentCall.value?.peerPublicKey
            if (meshPeer != null && try { crossPlatformMesh.isPeerReachable(meshPeer) } catch (_: Exception) { false }) {
                candidates.add(AudioTxPrimary.MESH)
            }
            if (advanceRotation) {
                if (txBlackoutRotatedAt == 0L) {
                    // First frame of this blackout — start the clock, don't skip a
                    // candidate (otherwise rotation always begins on index 1).
                    txBlackoutRotatedAt = now
                } else if (now - txBlackoutRotatedAt >= TX_BLACKOUT_ROTATE_MS) {
                    txBlackoutRotatedAt = now
                    txBlackoutCursor++
                }
            }
            return candidates[Math.floorMod(txBlackoutCursor, candidates.size)]
        }
        // Media is flowing again — arm the rotation for the next blackout.
        if (advanceRotation) txBlackoutRotatedAt = 0L

        // 2) Availability.
        return if (relayUsable) AudioTxPrimary.UDP_RELAY else AudioTxPrimary.WS
    }

    /**
     * True while it is safe to mirror the frame onto the backup path. Duplication
     * is a fast-failover PROBE, not a steady state: it only runs for
     * [TX_DUPLICATE_WINDOW_MS] after the primary changes (which includes call
     * start), and never on a mobile/metered/constrained bearer — the 95-98 % loss
     * iOS measured was on exactly such a link, with the duplicate stream supplying
     * half the offered load.
     * iOS VoiceCallManager.swift:10136-10156.
     */
    private fun txDuplicationAllowed(): Boolean {
        if (!mediaDuplicationAffordable()) return false
        return System.currentTimeMillis() - txPrimarySelectedAt < TX_DUPLICATE_WINDOW_MS
    }

    /**
     * The BEARER-level veto. iOS MEASURED that the 95-98 %-loss leg reported
     * `expensive=false` and `constrained=false`, so the interface TYPE is the
     * signal that matters — do not "simplify" this to isMetered.
     * iOS VoiceCallManager.swift:10152-10156 (`mediaDuplicationAffordable`).
     */
    private fun mediaDuplicationAffordable(): Boolean {
        return try {
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            val cellular = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
            val metered = !caps.hasCapability(
                android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED
            )
            !(cellular || metered)
        } catch (_: Exception) {
            false
        }
    }

    private var audioSendJob: Job? = null
    private var audioPlayJob: Job? = null
    private var vpsAudioReceiveJob: Job? = null
    private var durationJob: Job? = null
    private var statsJob: Job? = null
    private var keepaliveJob: Job? = null
    private var p2pWatchdogJob: Job? = null

    // Receive-side dedup: same (type,seq) may arrive via P2P and VPS within overlap window.
    private val rxDedupSeen = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val RX_DEDUP_WINDOW_MS = 2_000L

    // 🛟 MULTI-DEVICE FIX (mirrors iOS 1.0.9): callIds we've explicitly ended
    // on this device. The caller's VoIP-push retries / VPS retransmits keep
    // re-delivering the same call for several seconds; without this dedup,
    // ending on phone A caused the same callId to re-ring phone A once the
    // retry landed. TTL 90 s — long enough to outlast any reasonable retry
    // burst, short enough that a legitimate call-back uses a fresh UUID
    // and isn't blocked.
    private val recentlyEndedCallIds = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val ENDED_CALL_ID_TTL_MS = 90_000L

    private fun markCallIdAsEnded(callId: String?) {
        if (callId.isNullOrEmpty()) return
        // Release the single-surface claim immediately so a legitimately new call
        // reusing this id can ring again; recentlyEndedCallIds still blocks late
        // duplicate pushes for this same (now-ended) call.
        clearPresentedIncomingSurface(callId)
        recentlyEndedCallIds[callId] = System.currentTimeMillis()
        if (recentlyEndedCallIds.size > 64) {
            val cutoff = System.currentTimeMillis() - ENDED_CALL_ID_TTL_MS
            val it = recentlyEndedCallIds.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value < cutoff) it.remove()
            }
        }
    }

    /** Public wrapper used by `FCMService` to drop a wake-up push if its
     *  callId was just ended on this device. */
    fun isCallIdRecentlyEnded(callId: String?): Boolean {
        if (callId.isNullOrEmpty()) return false
        val ts = recentlyEndedCallIds[callId] ?: return false
        return System.currentTimeMillis() - ts < ENDED_CALL_ID_TTL_MS
    }

    // 🛟 SINGLE-SURFACE FIX (mirrors iOS double-notification fix): one incoming call
    // can arrive on TWO ingresses — the instant WebSocket/mesh CALL_REQUEST and a
    // delayed FCM push (and the caller sends BOTH `incoming_call_wake` and `call`
    // pushes). Each used to raise its own incoming surface, so the callee saw the
    // full-screen ringer twice (or the in-app overlay AND the system notification).
    // This set records every callId that has already raised an incoming surface;
    // the first ingress wins and every later one coalesces. Cleared when the call
    // leaves the incoming state (accepted / declined / ended / idle).
    private val presentedIncomingCallIds =
        java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Claim the single incoming surface for [callId].
     *
     * @return true the FIRST time this callId raises a surface (caller SHOULD
     *   present the ring/full-screen); false on every later ingress for the same
     *   callId (caller MUST coalesce — do NOT present a second surface).
     *
     * An empty callId cannot be deduplicated, so it is always allowed through
     * (better to ring than to silently drop a call whose id we never learned).
     */
    /**
     * __CALL_WAKE_2026_11_22__ Callee side: tell the caller (through the call server) that
     * this phone is ringing for [callId]. Once per call, fire-and-forget; raised from the
     * in-app ring and from the FCM full-screen ring (whichever comes first).
     */
    fun reportRinging(callerPublicKey: String?, callId: String?) {
        if (callerPublicKey.isNullOrEmpty() || callId.isNullOrEmpty()) return
        if (!reportedRingingCallIds.add(callId)) return
        if (reportedRingingCallIds.size > 32) {
            synchronized(reportedRingingCallIds) {
                val it = reportedRingingCallIds.iterator()
                while (reportedRingingCallIds.size > 16 && it.hasNext()) { it.next(); it.remove() }
            }
        }
        scope.launch {
            val sent = runCatching { vpsClient.sendCallRinging(callerPublicKey, callId) }.getOrDefault(false)
            if (!sent) reportedRingingCallIds.remove(callId)   // the in-app ring may try again
        }
    }

    /**
     * __CALL_WAKE_2026_11_22__ An FCM call push arrived while the app is in the
     * FOREGROUND. FCMService used to drop it ("the in-app overlay handles it") — but the
     * overlay only exists once the offer itself arrives over the socket or the poll, and
     * when that copy is lost (zombie socket, a poll dropped by a wrong clock: 21/11) the
     * phone never rang. Fetch the offer now; if after 3 s nothing rings for this call,
     * raise the system ringer ([startSystemRinger]) like a backgrounded phone would.
     */
    fun onCallPushWhileForeground(callId: String, startSystemRinger: () -> Unit) {
        scope.launch {
            runCatching { vpsClient.pollCallSignals().forEach { handleCallSignal(it) } }
            delay(3000)
            val state = _callState.value
            val sameCall = _currentCall.value?.callId?.equals(callId, ignoreCase = true) == true
            if (ForegroundCallPushPolicy.shouldRaiseSystemRinger(
                    callId = callId,
                    idle = state == CallState.IDLE || state == CallState.ENDED,
                    alreadyThisCall = sameCall,
                    recentlyEnded = isCallIdRecentlyEnded(callId),
                ) && tryPresentIncomingSurface(callId)
            ) {
                Log.w(TAG, "📞 FCM call ${callId.take(8)} in foreground and no offer arrived — raising the system ringer")
                startSystemRinger()
            }
        }
    }

    fun tryPresentIncomingSurface(callId: String?): Boolean {
        val key = callId?.takeIf { it.isNotEmpty() } ?: return true
        // Set.add() returns false when the element was already present.
        val firstPresentation = presentedIncomingCallIds.add(key)
        if (presentedIncomingCallIds.size > 64) {
            // Bounded — this set never legitimately holds more than one live call.
            presentedIncomingCallIds.clear()
            presentedIncomingCallIds.add(key)
        }
        return firstPresentation
    }

    /** Release the single-surface claim for [callId] (or all, if null/empty) so a
     *  genuinely new call with the same id can ring again after this one is gone. */
    private fun clearPresentedIncomingSurface(callId: String?) {
        if (callId.isNullOrEmpty()) presentedIncomingCallIds.clear()
        else presentedIncomingCallIds.remove(callId)
    }

    // Keepalive state (matching iOS: 3-second interval)
    private var pingSequence: Int = 0
    /**
     * Written by the 3 s keepalive coroutine (incl. an iterate-and-remove trim) and
     * read from the RX thread — a plain `mutableMapOf` here is a
     * ConcurrentModificationException waiting for a long call. Its sibling
     * `keepaliveAnswered` was already synchronized.
     */
    private val keepaliveSendTimes = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    /** Highest peer sequence number seen this call; -1 = none yet. Loss detection. */
    @Volatile private var rxPrevSeq: Long = -1L
    /** Ping sequences whose RTT has already been reported (fan-out duplicate guard). */
    private val keepaliveAnswered = java.util.Collections.synchronizedSet(HashSet<Int>())
    /** Last (seq, stamp) we echoed, so N duplicate copies produce ONE pong. */
    @Volatile private var lastEchoedPingKey: Long = Long.MIN_VALUE

    // 🛰️ VPS UDP audio relay (port 8089). Wired between the P2P transport and
    // the WebSocket / HTTP fallbacks in sendAudio. Wire format and per-call
    // lifecycle live in service/UDPRelayClient.kt. The transport is "active"
    // only when (a) we've placed a register packet on the wire and (b) the
    // server has replied within `UDP_RELAY_STALE_MS`. If the server stops
    // replying, we demote (currently to "drop" since strict-P2P RX would
    // ignore a WS-delivered audio packet) and resume on the next reply.
    @Volatile private var udpRelay: UDPRelayClient? = null
    private val UDP_RELAY_STALE_MS = 5_000L

    // My identity
    private var myPublicKey: String = ""
    private var myDisplayName: String = "OSHI User"

    // 🔒 Call privacy mode: "direct" (default, lower latency) or "relay" (IP hidden)
    // Read from DataStore at call time; negotiated with remote peer
    private var localPrivacyMode: String = "direct"
    private var remotePrivacyMode: String = "direct"
    // Negotiated transport for current call (placeholder until TURN UDP transport is ready)
    private var negotiatedTransport: CallTransport = CallTransport.WEB_SOCKET

    // Call timing
    private var callConnectedTime: Long? = null
    private var lastAudioReceivedTime: Long = 0
    private var diagSnapshotTick: Int = 0
    /** Stats-loop ticks since connect; the quality badge samples every 4th (2 s). */
    private var qualitySampleTick: Int = 0
    private var callStartTime: Long? = null

    // Network change detection
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // 🔧 FIX: Deduplication to prevent duplicate signals (iOS fix port)
    private val processedCallSignalIDs = mutableSetOf<String>()
    private val processedIncomingCallKeys = mutableSetOf<String>()
    private var lastIncomingCallTime: Long? = null
    private var receivedRemoteEndSignal = false
    private var isEndingCall = false

    // AUTO-ACCEPT: When caller receives media from peer during RINGING, peer has accepted
    // iOS sends accept via HTTP (slow polling) but starts video immediately — we detect media as implicit accept
    @Volatile private var hasAutoAcceptedFromMedia = false

    // Deduplication window (30 seconds)
    private val DEDUPLICATION_WINDOW_MS = 30000L

    // 🔧 FIX: Grace periods (iOS compatible)
    private val POST_CONNECT_GRACE_PERIOD_MS = 15000L  // 15 seconds (was 5s)
    private val RINGING_GRACE_PERIOD_MS = 3000L  // 3 seconds for stale signals

    // ⏱️ Lifecycle watchdogs. Every one of these is callId-keyed: the state alone is
    // not enough, because a second call can be in the SAME state by the time the
    // timer fires, and then the old call's watchdog ends the new call.
    /// Caller rings this long before giving up. iOS VoiceCallManager.swift:5850.
    private val CALLER_NO_ANSWER_TIMEOUT_MS = 45_000L
    /// Callee rings this long, then records the missed call and stops.
    /// iOS `calleeRingWatchdog`, VoiceCallManager.swift:4001-4017 — 55 s, longer than
    /// the caller's 45 s so the CALLER is normally the one that ends the call and the
    /// callee's watchdog is only the backstop for a caller that vanished.
    /// Android had NO callee watchdog: `IncomingCallService` silenced the ringtone at
    /// 45 s (:60, :321-330) and broadcast `com.oshi.messenger.CALL_MISSED`, which has
    /// no receiver in code or in the manifest — so `_callState` stayed RINGING
    /// forever, the incoming-call screen never went away and no missed call was
    /// recorded.
    private val CALLEE_RING_TIMEOUT_MS = 55_000L
    /// Offer sent / accept sent, and nothing came back. iOS
    /// `connectingTimeoutSeconds = 20.0` (VoiceCallManager.swift:3267, armed :5686-5695)
    /// → `endCall(reason: .networkError)`, which is exactly what `CallEndReason.ERROR`
    /// puts on the wire here (CallEndReasonWire maps it to "network_error").
    /// Android had no CONNECTING watchdog at all,
    /// so any failure between "Connecting…" and RINGING/IN_CALL parked the call screen
    /// with no way out but the Back button.
    private val CONNECTING_TIMEOUT_MS = 20_000L
    /** __OSHI_CALL_SOUNDS_2026_09_28__ iOS `outgoingConnectingSeconds`. */
    private val OUTGOING_CONNECTING_MS = 3_000L

    // ============================================================================
    // INITIALIZATION
    // ============================================================================

    fun initialize(publicKey: String, displayName: String = "OSHI User") {
        myPublicKey = publicKey
        myDisplayName = displayName
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Initialize vibrator
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // Listen for VPS call signals
        vpsClient.startCallPolling { signal ->
            scope.launch {
                handleCallSignal(signal)
            }
        }

        // Listen for mesh call signals
        meshManager.addMessageCallback { message ->
            if (message.type == MessageType.CALL_SIGNAL) {
                scope.launch {
                    handleMeshCallSignal(message)
                }
            }
        }

        // Listen for cross-platform call signals (iOS)
        crossPlatformMesh.addMessageCallback { message ->
            if (message.type == "CALL_SIGNAL" || message.type == "CALL_AUDIO") {
                scope.launch {
                    handleCrossPlatformMessage(message)
                }
            }
        }

        // Listen for call audio/video via WebSocket (iOS sends via WS after ~7s)
        vpsClient.onCallAudioReceived = { packet ->
            // WHY: DIAG_WS — proves WS delivered an audio packet to Android (server-side dropoff detection).
            val n = diagWsAudioRxCount.incrementAndGet()
            if (n <= 5L || n % 200L == 0L) {
                CallDiag.w(TAG, "DIAG_WS | event=audio_rx | count=$n | size=${packet.size}")
            }
            // Received-media evidence for chooseAudioTxPrimary — iOS `recordRx(.vpsWebSocket)`.
            notePathRx(AudioTxPrimary.WS)
            scope.launch {
                val peerKey = _currentCall.value?.peerPublicKey ?: return@launch
                handleAudioData(packet, peerKey)
            }
        }
        vpsClient.onCallSignalViaWs = { signal ->
            scope.launch {
                handleCallSignal(signal)
            }
        }

        // 📹 Route VideoCallManager's "I need an IDR from the peer" events onto the
        // wire as 0x0B. Before this the decode-failure and reassembly-GC sites called
        // `requestKeyFrame()`, which pokes the LOCAL encoder (audit A-4 / D-7).
        videoCallManager.onKeyframeRequestToPeer = { sendKeyframeRequest() }

        // 📹 A camera that never starts used to be invisible: openCamera() returns
        // normally on a missing permission, a missing camera id, a lock timeout and
        // StateCallback.onError. Surface it so the screen can say something.
        videoCallManager.onError = { code ->
            Log.e(TAG, "📹 Video capture error: $code")
            _videoError.value = code
        }

        Log.d(TAG, "📞 EnhancedCallManager initialized (AAC-ELD + AES-GCM video encryption)")
    }

    // ============================================================================
    // VPS UDP AUDIO RELAY (port 8089)
    // ============================================================================

    /**
     * Bring up the per-call UDP relay client. Inbound bytes are piped into
     * [handleAudioData] — the same callback the WebSocket transport feeds —
     * so the existing decrypt/jitter/playback pipeline is unchanged.
     *
     * Idempotent: a second call replaces any prior client (defensive, in
     * case start/accept run twice).
     */
    private fun startUdpRelay(senderKey: String, peerKey: String, callId: String) {
        if (senderKey.isEmpty() || peerKey.isEmpty() || callId.isEmpty()) {
            Log.w(TAG, "🛰️ UDP RELAY skipped — missing identity/peer/callId")
            return
        }
        try { udpRelay?.stop() } catch (_: Throwable) {}
        // __CALL_MEDIA_AUTH_2026_09_23__ contract §2: fetch the relay token as soon as
        // callId + peer are known — caller at dial (before the offer), callee at accept —
        // and whether or not UDP ends up carrying media (under `enforce` WS/HTTP media is
        // relayed only between two holders of mutual live tokens). Off the calling thread:
        // the offer never waits on it; the UDP client waits for it before its 1st register.
        beginRelayTokenFetch(peerKey, callId)
        val client = UDPRelayClient()
        client.tokenProvider = { force -> relayTokenFor(callId, peerKey, force) }
        client.onAudioPacket = { payload, origin ->
            // Received-media evidence for chooseAudioTxPrimary — iOS `recordRx(.udpRelay)`.
            notePathRx(AudioTxPrimary.UDP_RELAY)
            scope.launch {
                val peerForPipe = _currentCall.value?.peerPublicKey ?: return@launch
                // The relay-stamped origin is the only thing that can vouch for a
                // legacy bare 0x04 hang-up (__BARE_CALLEND_ATTESTED_2026_11_21__).
                handleAudioData(payload, peerForPipe, relayOrigin = origin)
            }
        }
        // 📹 Video-over-UDP receive (interop-critical, iOS parity).
        // The relay hands us the INNER app packet `[0xF1][seq(8)][sealed box]`
        // exactly as P2P/WS/HTTP deliver it. Route it through the SAME
        // [handleAudioData] dispatcher every other transport uses: it detects
        // 0xF1, strips the 9-byte `[type][seq]` header (`copyOfRange(9, …)`) and
        // hands the sealed box `[nonce(12)][ct][tag(16)]` to
        // [decryptVideoPacket]. iOS's bug was the relay video RX forwarding the
        // raw payload WITHOUT stripping those 9 bytes so every relay video frame
        // failed AES-GCM auth (OSHI/VoiceCallManager.swift relay video path);
        // routing through handleAudioData strips them the same as audio, so a
        // call whose only working carrier is :8089 now decodes iOS↔Android video.
        client.onVideoPacket = { payload ->
            notePathRx(AudioTxPrimary.UDP_RELAY)
            scope.launch {
                val peerForPipe = _currentCall.value?.peerPublicKey ?: return@launch
                handleAudioData(payload, peerForPipe)
            }
        }
        client.start(senderKey, peerKey, callId)
        udpRelay = client
        Log.d(TAG, "🛰️ UDP RELAY started (host=${UDPRelayClient.DEFAULT_HOST}:${UDPRelayClient.DEFAULT_PORT}, callId=$callId)")
    }

    /** Tear down the per-call UDP relay client. Safe when udpRelay is null. */
    private fun stopUdpRelay() {
        try { udpRelay?.stop() } catch (_: Throwable) {}
        udpRelay = null
        // The token (and its macKey) live for the call only (contract §2).
        relayTokenFetch?.cancel()
        relayTokenFetch = null
        relayTokenCallId = null
    }

    // __CALL_MEDIA_AUTH_2026_09_23__ The call's relay token (one per call and peer).
    @Volatile private var relayTokenFetch: Deferred<RelayToken?>? = null
    @Volatile private var relayTokenCallId: String? = null
    private val RELAY_TOKEN_WAIT_MS = 8_000L

    private fun beginRelayTokenFetch(peerKey: String, callId: String) {
        synchronized(this) {
            if (relayTokenCallId == callId && relayTokenFetch?.isCancelled == false) return
            relayTokenFetch?.cancel()
            relayTokenCallId = callId
            relayTokenFetch = scope.async {
                val t = try { vpsClient.requestRelayToken(peerKey, callId) } catch (e: Exception) {
                    Log.w(TAG, "🛰️ relay-token fetch failed: ${e.message}"); null
                }
                CallDiag.w(TAG, "DIAG_RELAY_TOKEN | callId=${callId.take(8)} | ok=${t != null}")
                t
            }
        }
    }

    /** Token for [callId]; [force] fetches a fresh one (nack 01/02/04, near expiry). */
    private suspend fun relayTokenFor(callId: String, peerKey: String, force: Boolean): RelayToken? {
        val live = _currentCall.value?.callId
        if (live != null && live != callId) return null  // another call is up now
        if (force || relayTokenFetch == null || (relayTokenFetch?.isCompleted == true &&
                relayTokenFetch?.getCompletedOrNullSafe() == null)) {
            synchronized(this) {
                relayTokenFetch?.cancel()
                relayTokenFetch = null
                relayTokenCallId = null
            }
            beginRelayTokenFetch(peerKey, callId)
        }
        val d = relayTokenFetch ?: return null
        return withTimeoutOrNull(RELAY_TOKEN_WAIT_MS) { d.await() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Deferred<RelayToken?>.getCompletedOrNullSafe(): RelayToken? =
        runCatching { getCompleted() }.getOrNull()

    // ============================================================================
    // CALL PRIVACY / TURN TRANSPORT NEGOTIATION (Phase 4 + 6)
    // ============================================================================

    /**
     * Read local call privacy mode from DataStore.
     * "relay" = always use relay (IP hidden), "direct" = allow P2P attempt.
     * Default is "relay" for maximum privacy.
     */
    private suspend fun readLocalPrivacyMode(): String {
        return try {
            val key = stringPreferencesKey("call_privacy_mode")
            val prefs = context.dataStore.data.first()
            prefs[key] ?: "relay"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read call privacy mode, defaulting to relay: ${e.message}")
            "relay"
        }
    }

    /**
     * Apply the user's saved Settings → "Default voice effect" + "Call quality" to this
     * call. Reads `default_voice_effect` (stringPref) and `call_quality` (stringPref)
     * from the same DataStore SettingsViewModel writes to. Called from startCall and
     * acceptCall so both incoming and outgoing calls honour the user's defaults.
     */
    private suspend fun applyCallDefaultsFromSettings() {
        try {
            val prefs = context.dataStore.data.first()
            // Voice effect default
            val effectName = prefs[stringPreferencesKey("default_voice_effect")] ?: "None"
            val effect = when (effectName.lowercase()) {
                "none", "normal" -> CallVoiceEffectProcessor.VoiceEffect.NORMAL
                "robot" -> CallVoiceEffectProcessor.VoiceEffect.ROBOT
                "alien" -> CallVoiceEffectProcessor.VoiceEffect.ALIEN
                "deep" -> CallVoiceEffectProcessor.VoiceEffect.DEEP
                "chipmunk" -> CallVoiceEffectProcessor.VoiceEffect.CHIPMUNK
                "echo" -> CallVoiceEffectProcessor.VoiceEffect.ECHO
                "reverb" -> CallVoiceEffectProcessor.VoiceEffect.REVERB
                "anonymous" -> CallVoiceEffectProcessor.VoiceEffect.ANONYMOUS
                else -> CallVoiceEffectProcessor.VoiceEffect.NORMAL
            }
            if (_currentVoiceEffect.value != effect) {
                _currentVoiceEffect.value = effect
                Log.d(TAG, "🎤 Applied saved default voice effect: ${effect.displayName}")
            }
            // Call quality → encoder bitrate (Auto / Low / High / Medium)
            val quality = prefs[stringPreferencesKey("call_quality")] ?: "Auto"
            val bitrate = when (quality.lowercase()) {
                "low" -> 16_000     // narrowband-like
                "medium" -> 32_000
                "high" -> 64_000    // hi-fi (Opus stereo-ish)
                else -> 48_000      // Auto / default — current value
            }
            preferredCallBitrate = bitrate
            Log.d(TAG, "📶 Applied saved call quality '$quality' → ${bitrate / 1000} kbps")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply call defaults from Settings: ${e.message}")
        }
    }

    /** Mutable preferred bitrate read by encoder factories. Default 48 kbps (Auto). */
    @Volatile private var preferredCallBitrate: Int = 48_000

    /**
     * Read the force-relay toggle (SettingsScreen → "Always use relay server").
     * When true, [P2PTransport.start] skips direct probing and allocates TURN
     * immediately. Mirrors iOS "oshi.call.forceRelay".
     */
    private suspend fun readForceRelayMode(): Boolean {
        return try {
            val key = androidx.datastore.preferences.core.booleanPreferencesKey("force_relay_mode")
            val prefs = context.dataStore.data.first()
            prefs[key] ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read force_relay_mode, defaulting to false: ${e.message}")
            false
        }
    }

    /**
     * Negotiate transport based on both parties' privacy preferences.
     * If EITHER party wants relay -> use relay (TURN or WebSocket fallback).
     * If BOTH want direct -> attempt P2P (with TURN fallback).
     * Currently falls back to WebSocket since TURN UDP transport is not yet wired.
     */
    private fun negotiateTransport(localMode: String, remoteMode: String): CallTransport {
        val negotiated = if (localMode == "direct" && remoteMode == "direct") {
            // Both allow direct -> attempt P2P, fallback to TURN
            Log.d(TAG, "🔒 Both parties allow direct calls -> P2P attempt (placeholder: WebSocket)")
            // TODO: When TURN UDP transport is ready, return CallTransport.DIRECT_P2P
            CallTransport.WEB_SOCKET  // Placeholder — falls back to existing WebSocket
        } else {
            // At least one party wants relay -> use relay
            Log.d(TAG, "🔒 Relay mode negotiated (local=$localMode, remote=$remoteMode) -> TURN relay (placeholder: WebSocket)")
            // TODO: When TURN UDP transport is ready, return CallTransport.TURN_RELAY
            CallTransport.WEB_SOCKET  // Placeholder — falls back to existing WebSocket
        }
        negotiatedTransport = negotiated
        return negotiated
    }

    /**
     * TURN server configuration for future TURN UDP transport.
     * Returns the TURN credentials as a map for inclusion in call signaling.
     */
    fun getTurnConfig(): Map<String, String> = mapOf(
        "url" to TURN_SERVER_URL,
        "username" to TURN_USERNAME,
        "credential" to TURN_CREDENTIAL
    )

    // ============================================================================
    // OUTGOING CALLS
    // ============================================================================

    suspend fun startCall(
        recipientPublicKey: String,
        recipientName: String,
        isVideoCall: Boolean = false
    ): Result<String> {
        if (_callState.value != CallState.IDLE) {
            return Result.failure(Exception("Already in a call"))
        }

        // 🚫 Never place a call TO someone we have blocked. iOS throws
        // `CallError.peerBlocked` at the top of `startCall`
        // (`VoiceCallManager.swift:5533-5536`) — a failure, not a silent no-op, so the
        // UI can say why nothing happened.
        if (CallAdmissionPolicy.isBlocked(database.contactDao(), recipientPublicKey)) {
            Log.w(TAG, "🚫 Refusing outgoing call to blocked contact ${recipientPublicKey.take(12)}…")
            return Result.failure(Exception("Contact is blocked"))
        }
        // __BLOCKED_BY_PEER_2026_09_24__ the peer told us (🚫BLOCKED🚫) it blocks us: no
        // CALL_REQUEST, no VoIP push, no FCM wake — its iPhone is never woken into the CallKit
        // report PushKit forces (a Recents "missed call" there). The UI shows "Contact unavailable".
        if (BlockedByPeerStore.get(context).isBlockedBy(recipientPublicKey)) {
            Log.w(TAG, "🚫 Not calling ${recipientPublicKey.take(12)}… — it said it blocks us (unavailable)")
            return Result.failure(PeerUnavailableException())
        }

        return try {
            val callId = UUID.randomUUID().toString()

            // 🆕 Honour Settings → Default voice effect + Call quality before any encoder is created.
            applyCallDefaultsFromSettings()

            // 🔒 Read local call privacy mode for negotiation
            localPrivacyMode = readLocalPrivacyMode()
            remotePrivacyMode = "relay"  // Reset; will be set when answer arrives
            negotiatedTransport = CallTransport.WEB_SOCKET  // Default until negotiated
            Log.d(TAG, "🔒 Call privacy mode: $localPrivacyMode")

            // Generate session key
            sessionKey = cryptoManager.generateSymmetricKey()
            audioSequenceNumber = 0
            videoSequenceNumber = 0

            // Connect call WebSocket for real-time audio/video (matching iOS)
            vpsClient.currentCallId = callId
            vpsClient.connectCallWebSocket()
            CallDiag.w(TAG, "DIAG_WS | event=connect_requested | role=caller | callId=$callId")

            // 🔧 FIX: Re-wire WebSocket callbacks (may have been cleared by previous call)
            vpsClient.onCallAudioReceived = { packet ->
                scope.launch {
                    val peerKey = _currentCall.value?.peerPublicKey ?: return@launch
                    handleAudioData(packet, peerKey)
                }
            }
            vpsClient.onCallSignalViaWs = { signal ->
                scope.launch {
                    handleCallSignal(signal)
                }
            }

            // 🛰️ Start the VPS UDP audio relay (port 8089). Must run after we
            // have callId + peer + identity. Inbound bytes pipe straight into
            // handleAudioData — same callback the WS transport feeds — so the
            // existing decrypt/jitter/playback pipeline is unchanged.
            startUdpRelay(myPublicKey, recipientPublicKey, callId)

            // Start network monitoring for call reconnection
            startNetworkMonitoring()

            // 🔧 FIX: Track call start time for grace period protection
            callStartTime = System.currentTimeMillis()
            receivedRemoteEndSignal = false
            isEndingCall = false
            hasAutoAcceptedFromMedia = false

            _currentCall.value = CurrentCall(
                callId = callId,
                peerPublicKey = recipientPublicKey,
                peerName = recipientName,
                isOutgoing = true,
                isVideoCall = isVideoCall,
                startTime = System.currentTimeMillis()
            )

            // 🔧 CROSS-PLATFORM: Detect if calling an iOS peer from mesh connected peers
            // This sets remotePlatform BEFORE audio starts, ensuring correct codec from first packet
            val isIOSPeer = crossPlatformMesh.connectedPeers.value.any {
                it.publicKey == recipientPublicKey && it.platform == "ios"
            }
            if (isIOSPeer) {
                remotePlatform = "ios"
                Log.d(TAG, "📞 ✅ Remote platform set to iOS from mesh peers (will use raw PCM)")
            }

            _callState.value = CallState.CONNECTING
            // __CALL_AUDIO_FOCUS_AT_DIAL_2026_11_02__ pause the user's music
            // BEFORE the dial tone, not when the call connects.
            requestVoiceCallAudioFocus("dial")
            playConnectingSound()
            // ⏱️ iOS VoiceCallManager.swift:5686-5695 — if the offer never turns into
            // RINGING (signal send throws, no transport, no reply) the screen used to
            // sit on "Connecting…" forever.
            armCallWatchdog(callId, CallState.CONNECTING, CONNECTING_TIMEOUT_MS,
                CallEndReason.ERROR, "outgoing call never left CONNECTING")

            // 🔧 CRITICAL FIX: Create BINARY payload in iOS format!
            // iOS format: [type(1)][timestamp(8)][sessionKey(32)][salt(4)][callIdUTF8][OshiCodec cap(1)][16kHz PCM cap(1)]
            // type = 0x01 (callRequest) or 0x08 (videoCallRequest). Two trailing
            // cap bytes — legacy (pre-v2) callees stop at the callId, so adding
            // a second cap byte stays fully backwards compatible.
            val packetType: Byte = if (isVideoCall) 0x08 else 0x01  // iOS CallPacketType.callRequest/videoCallRequest
            val timestampMs = System.currentTimeMillis()
            val salt = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
            sessionNonceSalt = salt  // 🔧 Store salt for audio encryption nonce (iOS-compatible)
            // 🔐 CRYPTO FIX: we are the INITIATOR — we transmit under the base salt,
            // the responder transmits under the derived one.
            // iOS VoiceCallManager.swift:5708-5710.
            applyDirectionalNonceSalts(isInitiator = true)
            val callIdBytes = callId.toByteArray(Charsets.UTF_8)

            // Build binary packet: type(1) + timestamp(8) + sessionKey(32) + salt(4) + callId(variable) + OshiCodec cap(1) + 16kHz PCM cap(1)
            // +2: l'emplacement du codec Apple (toujours 0x00 ici) et la vidéo.
            val requestPayload = ByteArray(1 + 8 + 32 + 4 + callIdBytes.size + 1 + 1 + 1 + 1 + 1 + 1)
            var offset = 0

            // Type byte
            requestPayload[offset++] = packetType

            // Timestamp (8 bytes, big endian)
            requestPayload[offset++] = (timestampMs shr 56).toByte()
            requestPayload[offset++] = (timestampMs shr 48).toByte()
            requestPayload[offset++] = (timestampMs shr 40).toByte()
            requestPayload[offset++] = (timestampMs shr 32).toByte()
            requestPayload[offset++] = (timestampMs shr 24).toByte()
            requestPayload[offset++] = (timestampMs shr 16).toByte()
            requestPayload[offset++] = (timestampMs shr 8).toByte()
            requestPayload[offset++] = timestampMs.toByte()

            // Session key (32 bytes)
            System.arraycopy(sessionKey, 0, requestPayload, offset, 32)
            offset += 32

            // Salt (4 bytes)
            System.arraycopy(salt, 0, requestPayload, offset, 4)
            offset += 4

            // Call ID (UTF-8 string)
            System.arraycopy(callIdBytes, 0, requestPayload, offset, callIdBytes.size)
            offset += callIdBytes.size

            // Call v2 — capability flag (advertise OshiCodec support). Legacy iOS/Android
            // callees ignore this trailing byte (they compute payload bounds from the prior
            // fields), so it is backwards compatible.
            requestPayload[offset++] = if (localSupportsOshiCodec) OSHI_CODEC_CAP_FLAG else 0x00
            // 16 kHz downsampled PCM capability — second trailing byte. Outermost byte,
            // peeled first by the peer parser. Gated on localSupports16kHzPCM so
            // we never advertise a path whose filter state precision is broken.
            requestPayload[offset++] = if (localSupports16kHzPCM) RAW_PCM_16K_CAP_FLAG else 0x00
            // Emplacement 2 = codec Apple. Toujours 0x00: Android n'a pas d'AAC-ELD,
            // et le combler est ce qui garde la vidéo à l'emplacement 3 côté iOS.
            requestPayload[offset++] = 0x00
            requestPayload[offset++] = if (LOCAL_SUPPORTS_VIDEO) VIDEO_CAP_FLAG else 0x00
            // 5th slot: wideband IMA-ADPCM (0x18). < 0x2D like every cap byte.
            requestPayload[offset++] = if (localSupportsWbAdpcm) com.oshi.messenger.service.audio.WbAdpcmCodec.CAP_FLAG.toByte() else 0x00
            // 6th slot: Opus (0x19). < 0x2D like every cap byte.
            requestPayload[offset++] = if (localSupportsOpus) com.oshi.messenger.service.audio.OpusWire.CAP_FLAG.toByte() else 0x00

            Log.d(TAG, "📞 Created iOS-compatible call request: type=$packetType, timestamp=$timestampMs, keySize=32, saltSize=4, callIdSize=${callIdBytes.size}, oshiCodecFlag=$localSupportsOshiCodec, raw16kFlag=$localSupports16kHzPCM")

            val encryptedPayload = cryptoManager.encryptCallSignal(requestPayload, recipientPublicKey)

            // 📲 SEND WAKE PUSH - Wake up recipient device BEFORE call signal
            // iOS: APNs VoIP push  |  Android: high-priority FCM data message (task 1.2.9)
            scope.launch {
                try {
                    // VoIP push for iOS devices
                    vpsClient.sendVoIPPush(
                        recipientPublicKey = recipientPublicKey,
                        callerPublicKey = myPublicKey,
                        callerName = com.oshi.messenger.network.vps.PUSH_CALLER_NAME_SENTINEL,  // __LEAK_FIX_2026_09_25__
                        callId = callId,
                        isVideoCall = isVideoCall
                    )
                    // High-priority FCM data message for Android devices (wakes Doze/locked)
                    vpsClient.sendFcmCallWake(
                        recipientPublicKey = recipientPublicKey,
                        callerPublicKey = myPublicKey,
                        callerName = com.oshi.messenger.network.vps.PUSH_CALLER_NAME_SENTINEL,  // __LEAK_FIX_2026_09_25__
                        callId = callId,
                        isVideoCall = isVideoCall
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Wake push failed (non-fatal): ${e.message}")
                }
            }

            // ALWAYS send via VPS (reliable delivery path)
            val signal = CallSignal(
                callId = callId,
                senderPublicKey = myPublicKey,
                type = CallSignalType.CALL_REQUEST,
                payload = encryptedPayload,
                timestamp = System.currentTimeMillis(),
                privacyMode = localPrivacyMode
            )
            // 🔧 UNIFIED P2P-FIRST (matches iOS): connectionType is ALWAYS MESH_DIRECT
            // (P2P posture). Signal is sent via BOTH mesh and VPS for reliability; audio
            // send logic then picks mesh when a peer is reachable, VPS otherwise per-packet.
            // Setting forces VPS-only if user toggles useRelayMode.
            _connectionType.value = if (useRelayMode) ConnectionType.VPS else ConnectionType.MESH_DIRECT
            scope.launch {
                try {
                    vpsClient.sendCallSignal(recipientPublicKey, signal)
                    Log.d(TAG, "📞 Call request sent via VPS (wake + reliability)")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to send VPS call signal: ${e.message}")
                }
            }

            // 🧭 P2P Phase 3/4: kick off UDP transport + STUN gathering in parallel
            // with the VPS signal. When local candidates are ready, piggyback them on
            // the existing encrypted call_signal envelope as 0x30 iceCandidateExchange.
            // Audio/video route through p2p.sendPacket once state becomes CONNECTED or
            // RELAY; otherwise legacy mesh/VPS delivery fires.
            if (!useRelayMode) {
                val forceRelay = readForceRelayMode()
                // Relay-ON means VPS carries audio — P2P may still gather for stats.
                p2pForceRelayActive = forceRelay
                p2pLitUpAt = 0L
                lastP2PInboundAt = 0L
                CallDiag.d(TAG, "🧭 P2P: startCall forceRelay=$forceRelay")
                ensureFreshTurnCreds()  // F6: rotate before allocate
                p2p.onLocalCandidatesReady = { cands -> sendLocalCandidatesToPeer(cands) }
                p2p.start(forceRelay = forceRelay)
                armP2PWatchdog()
            }

            // Always try mesh too (P2P primary path)
            if (!useRelayMode && crossPlatformMesh.isPeerReachable(recipientPublicKey)) {
                try {
                    crossPlatformMesh.sendCallSignal(recipientPublicKey, encryptedPayload)
                    Log.d(TAG, "📞 Call request also sent via CrossPlatformMesh (P2P)")
                } catch (e: Exception) {
                    Log.d(TAG, "📞 Mesh send failed, VPS delivery will handle it")
                }
            } else if (meshManager.connectedPeers.value.any { it.publicKey == recipientPublicKey }) {
                try {
                    val meshMessage = MeshMessage(
                        id = callId,
                        type = MessageType.CALL_SIGNAL,
                        senderPublicKey = myPublicKey,
                        senderName = "",
                        payload = """{"signalType": "CALL_REQUEST", "data": "${Base64.encodeToString(encryptedPayload, Base64.NO_WRAP)}"}""",
                        timestamp = System.currentTimeMillis()
                    )
                    meshManager.sendMessageToPublicKey(recipientPublicKey, meshMessage)
                    _connectionType.value = ConnectionType.MESH
                    Log.d(TAG, "📞 Call request also sent via Mesh")
                } catch (e: Exception) {
                    Log.d(TAG, "📞 Mesh send failed, VPS delivery will handle it")
                }
            }

            _calleeReach.value = null  // __CALL_WAKE_2026_11_22__
            _outgoingRingingSince.value = System.currentTimeMillis()
            _callState.value = CallState.RINGING
            playRingbackTone()

            // Fast-poll for accept signal during RINGING (iOS sends accept via HTTP, not WS)
            // Poll every 2 seconds until call connects or ends
            scope.launch {
                var pollCount = 0
                while (_callState.value == CallState.RINGING && pollCount < 23) {
                    delay(2000)
                    pollCount++
                    if (_callState.value != CallState.RINGING) break
                    try {
                        val signals = vpsClient.pollCallSignals()
                        signals.forEach { signal ->
                            handleCallSignal(signal)
                        }
                        if (signals.isNotEmpty()) {
                            Log.d(TAG, "📞 Fast poll #$pollCount: ${signals.size} signal(s) received")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Fast poll #$pollCount failed: ${e.message}")
                    }
                    // __CALL_WAKE_2026_11_22__ ringing / delivered / pushed / unreachable
                    if (_callState.value == CallState.RINGING && _currentCall.value?.callId == callId) {
                        val reach = runCatching { vpsClient.fetchCallReach(callId) }.getOrNull()
                        if (reach != null && _currentCall.value?.callId == callId) {
                            _calleeReach.value = CallReach.merge(_calleeReach.value, reach)
                        }
                    }
                }
            }

            // Start timeout (45 seconds). ⏱️ Keyed on the callId, not just the state:
            // hang up and re-dial the same person inside 45 s and this coroutine wakes
            // to find state == RINGING again and kills call N+1 on call N's timer.
            // iOS keys its own on `noAnswerWatchdogCallId` for the same reason
            // (VoiceCallManager.swift:5845-5857).
            scope.launch {
                delay(CALLER_NO_ANSWER_TIMEOUT_MS)
                if (_callState.value == CallState.RINGING && _currentCall.value?.callId == callId) {
                    Log.w(TAG, "⏱️ No answer after ${CALLER_NO_ANSWER_TIMEOUT_MS}ms — ending outgoing call")
                    endCall(CallEndReason.NO_ANSWER)
                }
            }

            Result.success(callId)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start call: ${e.message}")
            stopOutgoingSound()
            _callState.value = CallState.IDLE
            _currentCall.value = null
            Result.failure(e)
        }
    }

    // ============================================================================
    // INCOMING CALLS
    // ============================================================================

    /** __LATE_ACCEPT_2026_11_23__ May [acceptCall] still start media for [call]? */
    private fun lateAcceptVerdict(call: CurrentCall, serverSaysEnded: Boolean): LateAcceptPolicy.Verdict =
        LateAcceptPolicy.verdict(
            serverSaysEnded = serverSaysEnded,
            remoteEndForThisCall = remoteEndConfirmedFor(call.callId),
            callIdRecentlyEnded = isCallIdRecentlyEnded(call.callId),
            stillThisCall = _currentCall.value?.callId == call.callId,
            endingOrEnded = isEndingCall || _callState.value == CallState.ENDED,
        )

    /**
     * __LATE_ACCEPT_2026_11_23__ The call [acceptCall] is answering is already over: tear it
     * down now, before any media starts, and let the ended overlay say "Call ended"
     * ([CallEndReason.PEER_ENDED]). Nothing to do when endCall is already running (its own
     * reason stands) or another call has become current (not ours to end).
     */
    private fun finishLateAccept(call: CurrentCall, why: String) {
        CallDiag.urgent(TAG, "DIAG_LATE_ACCEPT | callId=${call.callId.take(8)} | $why — media not started")
        if (_currentCall.value?.callId != call.callId) return
        if (isEndingCall || _callState.value == CallState.ENDED) return
        remoteEndConfirmedCallId = CallSignalGuards.normalizeCallId(call.callId)
        receivedRemoteEndSignal = true  // the caller is gone: no in-band 0x0D echo, no sibling dismissal
        endCall(CallEndReason.PEER_ENDED)
    }

    suspend fun acceptCall() {
        val call = _currentCall.value ?: return
        stopRingtoneAndRingback()

        // __LATE_ACCEPT_2026_11_23__ Accept tapped on a call that is already over (its
        // callEnd arrived a moment ago, the ended overlay is still up): no accept, no media.
        if (lateAcceptVerdict(call, serverSaysEnded = false) == LateAcceptPolicy.Verdict.CALL_OVER) {
            finishLateAccept(call, "call already over when Accept was tapped")
            return
        }

        try {
            _callState.value = CallState.CONNECTING
            // ⏱️ We answered; if nothing completes the handshake, don't strand the
            // user on "Connecting…". iOS VoiceCallManager.swift:3267, :5686-5695.
            armCallWatchdog(call.callId, CallState.CONNECTING, CONNECTING_TIMEOUT_MS,
                CallEndReason.ERROR, "accepted call never left CONNECTING")

            // 🆕 Honour Settings → Default voice effect + Call quality on accept too.
            applyCallDefaultsFromSettings()

            // 🔒 Read local privacy mode for accept response
            localPrivacyMode = readLocalPrivacyMode()
            // Negotiate transport: if either party wants relay, use relay
            val transport = negotiateTransport(localPrivacyMode, remotePrivacyMode)
            Log.d(TAG, "🔒 Accept call: local=$localPrivacyMode, remote=$remotePrivacyMode -> transport=$transport")

            // Start network monitoring for call reconnection
            startNetworkMonitoring()

            // Connect call WebSocket for real-time audio/video (matching iOS)
            vpsClient.currentCallId = call.callId
            vpsClient.connectCallWebSocket()
            CallDiag.w(TAG, "DIAG_WS | event=connect_requested | role=callee | callId=${call.callId}")

            // 🔧 FIX: Re-wire WebSocket callbacks (may have been cleared by previous call)
            vpsClient.onCallAudioReceived = { packet ->
                scope.launch {
                    val peerKey = _currentCall.value?.peerPublicKey ?: return@launch
                    handleAudioData(packet, peerKey)
                }
            }
            vpsClient.onCallSignalViaWs = { signal ->
                scope.launch {
                    handleCallSignal(signal)
                }
            }

            // 🛰️ Callee-side: also bring up the VPS UDP audio relay (port 8089)
            // so the first frames after accept can flow over UDP if P2P hasn't
            // hole-punched yet. Same wiring as the caller path above.
            startUdpRelay(myPublicKey, call.peerPublicKey, call.callId)

            // 🔧 CRITICAL FIX: Create BINARY accept signal in iOS format!
            // iOS format: [type(1)][timestamp(8)][OshiCodec cap(1)][16kHz PCM cap(1)]
            // type = 0x02 (callAccept) or 0x09 (videoCallAccept). Legacy accept body was
            // empty; Call v2 appends an OshiCodec cap byte; this patch appends a second
            // 16 kHz-PCM cap byte so the caller can finalize both negotiations. Legacy
            // callers stop parsing after the type+timestamp prefix so both bytes are safe.
            val packetType: Byte = if (call.isVideoCall) 0x09 else 0x02  // iOS CallPacketType.callAccept/videoCallAccept
            val timestampMs = System.currentTimeMillis()

            // Build binary packet: type(1) + timestamp(8) + OshiCodec cap(1) + 16kHz PCM cap(1)
            // +2: emplacement du codec Apple (0x00) et vidéo — voir VIDEO_CAP_FLAG.
            val acceptPayload = ByteArray(1 + 8 + 1 + 1 + 1 + 1 + 1 + 1)
            acceptPayload[0] = packetType
            acceptPayload[1] = (timestampMs shr 56).toByte()
            acceptPayload[2] = (timestampMs shr 48).toByte()
            acceptPayload[3] = (timestampMs shr 40).toByte()
            acceptPayload[4] = (timestampMs shr 32).toByte()
            acceptPayload[5] = (timestampMs shr 24).toByte()
            acceptPayload[6] = (timestampMs shr 16).toByte()
            acceptPayload[7] = (timestampMs shr 8).toByte()
            acceptPayload[8] = timestampMs.toByte()
            acceptPayload[9] = if (localSupportsOshiCodec) OSHI_CODEC_CAP_FLAG else 0x00
            acceptPayload[10] = if (localSupports16kHzPCM) RAW_PCM_16K_CAP_FLAG else 0x00
            acceptPayload[11] = 0x00                                  // emplacement codec Apple
            acceptPayload[12] = if (LOCAL_SUPPORTS_VIDEO) VIDEO_CAP_FLAG else 0x00
            acceptPayload[13] = if (localSupportsWbAdpcm) com.oshi.messenger.service.audio.WbAdpcmCodec.CAP_FLAG.toByte() else 0x00
            acceptPayload[14] = if (localSupportsOpus) com.oshi.messenger.service.audio.OpusWire.CAP_FLAG.toByte() else 0x00

            // Allocate encoder/decoder if Call v2 negotiation flipped useOshiCodec on
            // during handleCallSignal. Separate instances per direction (LPC history).
            if (useOshiCodec && oshiEncoder == null) {
                try {
                    oshiEncoder = OshiCodec().apply { setBitrate(preferredCallBitrate) }
                    oshiDecoder = OshiCodec().apply { setBitrate(preferredCallBitrate) }
                    Log.w(TAG, "📞 Call v2: OshiCodec allocated (callee) — 48 kbps default")
                } catch (t: Throwable) {
                    Log.w(TAG, "📞 Call v2: failed to allocate OshiCodec — disabling. ${t.message}")
                    useOshiCodec = false
                    oshiEncoder = null
                    oshiDecoder = null
                }
            }

            Log.d(TAG, "📞 Created iOS-compatible accept signal: type=$packetType, timestamp=$timestampMs, oshiCodecFlag=$localSupportsOshiCodec")

            val encryptedPayload = cryptoManager.encryptCallSignal(acceptPayload, call.peerPublicKey)

            // ALWAYS send accept via VPS (reliable delivery)
            val acceptSignal = CallSignal(
                callId = call.callId,
                senderPublicKey = myPublicKey,
                type = CallSignalType.CALL_ACCEPT,
                payload = encryptedPayload,
                timestamp = System.currentTimeMillis(),
                privacyMode = localPrivacyMode
            )
            // P2P-preferred: optimistic MESH_DIRECT label; P2P.onStateChanged adjusts to RELAY/VPS on fallback.
            _connectionType.value = ConnectionType.MESH_DIRECT
            // __LATE_ACCEPT_2026_11_23__ the server's answer is kept: it may say the caller
            // already gave up. (The old `repeat` + `return@repeat` never broke out: every
            // accept went three times, and a failed POST — `false`, not a throw — had no
            // back-off. Now: stop at the first delivery, retry only a failure.)
            var acceptReplySaysEnded = false
            for (attempt in 0 until 3) {
                val r = vpsClient.sendCallSignalForResult(call.peerPublicKey, acceptSignal)
                if (r.callEnded) { acceptReplySaysEnded = true; break }
                if (r.delivered) {
                    Log.d(TAG, "📞 Accept sent via VPS (attempt ${attempt + 1})")
                    break
                }
                delay(200L * (attempt + 1))
            }
            if (lateAcceptVerdict(call, acceptReplySaysEnded) == LateAcceptPolicy.Verdict.CALL_OVER) {
                finishLateAccept(
                    call,
                    if (acceptReplySaysEnded) "call server answered the accept with callEnd"
                    else "call ended while the accept was in flight"
                )
                return
            }

            // Also try mesh for lower latency (best-effort, won't cause duplicates — receiver deduplicates by callId)
            if (crossPlatformMesh.isPeerReachable(call.peerPublicKey)) {
                try {
                    crossPlatformMesh.sendCallSignal(call.peerPublicKey, encryptedPayload)
                    _connectionType.value = ConnectionType.MESH_DIRECT
                    Log.d(TAG, "📞 Accept also sent via CrossPlatformMesh")
                } catch (e: Exception) {
                    Log.d(TAG, "📞 Mesh accept failed, VPS delivery will handle it")
                }
            }

            // __LATE_ACCEPT_2026_11_23__ last look before any media machinery starts.
            if (lateAcceptVerdict(call, serverSaysEnded = false) == LateAcceptPolicy.Verdict.CALL_OVER) {
                finishLateAccept(call, "call ended during transport setup")
                return
            }

            // 🧭 P2P Phase 3/4 (callee): start our own UDP socket + STUN gather and
            // send local candidates back. Gathering runs in parallel with audio startup.
            if (!useRelayMode) {
                val forceRelay = readForceRelayMode()
                // Relay-ON means VPS carries audio — P2P may still gather for stats.
                p2pForceRelayActive = forceRelay
                p2pLitUpAt = 0L
                lastP2PInboundAt = 0L
                CallDiag.d(TAG, "🧭 P2P: acceptCall forceRelay=$forceRelay")
                ensureFreshTurnCreds()  // F6: rotate before allocate
                p2p.onLocalCandidatesReady = { cands -> sendLocalCandidatesToPeer(cands) }
                p2p.start(forceRelay = forceRelay)
                armP2PWatchdog()
            }

            // Start audio
            CallDiag.d(TAG, "📞 acceptCall: starting audio...")
            startAudio()
            CallDiag.d(TAG, "📞 acceptCall: audio started")

            // 📹 Start video if video call
            if (call.isVideoCall) {
                CallDiag.d(TAG, "📹 acceptCall: starting video...")
                startVideoForCall()
                CallDiag.d(TAG, "📹 acceptCall: video started")
            }

            // __LATE_ACCEPT_2026_11_23__ endCall may have run WHILE audio/video started (the
            // callEnd landed mid-start): its teardown can have passed stopAudio() already, so
            // stop what we just started instead of overwriting ENDED with IN_CALL.
            if (_callState.value != CallState.CONNECTING || _currentCall.value?.callId != call.callId) {
                CallDiag.urgent(TAG, "DIAG_LATE_ACCEPT | callId=${call.callId.take(8)} | ended while media was starting (state=${_callState.value}) — stopping it")
                // Only if no OTHER call has taken the audio since (theirs is not ours to stop).
                val current = _currentCall.value
                if (current == null || current.callId == call.callId) {
                    stopAudio()
                    if (call.isVideoCall) scope.launch { videoCallManager.stopVideo() }
                }
                return
            }

            callConnectedTime = System.currentTimeMillis()
            lastAudioReceivedTime = System.currentTimeMillis()
            egressStall.reset()  // __EGRESS_STALL_2026_11_23__
            _callState.value = CallState.IN_CALL
            Log.w(TAG, "DIAG: STATE TRANSITION -> IN_CALL (from acceptCall, video=${call.isVideoCall}, wsConnected=${vpsClient.isCallWebSocketConnected})")
            CallDiag.d(TAG, "📞 acceptCall: state → IN_CALL")

            // WHY: DIAG_ACCEPT — side-by-side baseline with iOS; saltHex proves both sides have the same encryption salt.
            diagCallStartedAtMs = System.currentTimeMillis()
            diagLastTxPath = ""
            diagMicReadFired = false
            diagLastP2PStateStr = ""
            diagDecryptFailLogCount = 0
            val saltHex = sessionNonceSalt.joinToString("") { "%02x".format(it) }
            val forceRelayLog = readForceRelayMode()
            CallDiag.w(TAG, "DIAG_ACCEPT | role=callee | callId=${call.callId} | remotePlatform=$remotePlatform | sessionKeyPresent=${sessionKey != null} | saltHex=$saltHex | isVideo=${call.isVideoCall} | forceRelay=$forceRelayLog | wsConnected=${vpsClient.isCallWebSocketConnected}")

            // 📞 Broadcast answeredElsewhere (0x0A) so other devices of this user dismiss the call
            sendAnsweredElsewhereSignal(call)

            // Always start VPS audio polling (VPS is always the reliable fallback)
            Log.w(TAG, "DIAG: Starting VPS audio polling from acceptCall...")
            startVpsAudioPolling()
            CallDiag.d(TAG, "📞 acceptCall: VPS audio polling started")

            startDurationTimer()
            startStatsUpdater()
            startKeepalive()

            Log.d(TAG, "📞 Call accepted — all systems running (video=${call.isVideoCall})")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to accept call: ${e.message}")
            endCall(CallEndReason.ERROR)
        }
    }

    suspend fun declineCall() {
        val call = _currentCall.value ?: return

        // 🔧 CRITICAL FIX: Create iOS-compatible binary decline signal
        // iOS format: [type(1)][timestamp(8)] where type = 0x03 (callDecline)
        val timestampMs = System.currentTimeMillis()
        val declinePayload = ByteArray(1 + 8)
        declinePayload[0] = 0x03  // iOS CallPacketType.callDecline
        declinePayload[1] = (timestampMs shr 56).toByte()
        declinePayload[2] = (timestampMs shr 48).toByte()
        declinePayload[3] = (timestampMs shr 40).toByte()
        declinePayload[4] = (timestampMs shr 32).toByte()
        declinePayload[5] = (timestampMs shr 24).toByte()
        declinePayload[6] = (timestampMs shr 16).toByte()
        declinePayload[7] = (timestampMs shr 8).toByte()
        declinePayload[8] = timestampMs.toByte()

        val encryptedPayload = cryptoManager.encryptCallSignal(declinePayload, call.peerPublicKey)

        val signal = CallSignal(
            callId = call.callId,
            senderPublicKey = myPublicKey,
            type = CallSignalType.CALL_REJECT,
            payload = encryptedPayload,
            timestamp = timestampMs
        )
        vpsClient.sendCallSignal(call.peerPublicKey, signal)
        Log.d(TAG, "📞 Sent iOS-compatible decline signal")

        endCall(CallEndReason.DECLINED)
    }

    /**
     * __STALE_RING_2026_09_23__ `callDecline` (0x03) to a caller we cannot take because we
     * are in a live call with someone else — iOS `declineIncomingCall(from:)`
     * (VoiceCallManager.swift:6737-6742, :7880-7904). Unlike [declineCall] it touches
     * NOTHING of the current call: iOS also resets its own state there, which drops the
     * call it was protecting; that half is deliberately not copied.
     */
    private fun sendBusyDecline(peerPublicKey: String, callId: String) {
        scope.launch {
            try {
                val ts = System.currentTimeMillis()
                val payload = ByteArray(9)
                payload[0] = 0x03
                for (i in 0 until 8) payload[1 + i] = (ts shr (56 - 8 * i)).toByte()
                val signal = CallSignal(
                    callId = callId,
                    senderPublicKey = myPublicKey,
                    type = CallSignalType.CALL_REJECT,
                    payload = cryptoManager.encryptCallSignal(payload, peerPublicKey),
                    timestamp = ts
                )
                vpsClient.sendCallSignal(peerPublicKey, signal)
                Log.d(TAG, "📵 Busy decline sent to ${peerPublicKey.take(12)}")
            } catch (e: Exception) {
                Log.w(TAG, "📵 Busy decline failed (caller will time out): ${e.message}")
            }
        }
    }

    /**
     * __STALE_RING_2026_09_23__ Drop a ring that is already dead so a new call can ring.
     *
     * Synchronous on purpose. [endCall] would do it through a coroutine that publishes
     * IDLE two seconds later and — the stale case exactly — may be refused outright by the
     * `isEndingCall` guard, which is how the ring got stuck in the first place. A ring has
     * no media, so there is little to release: the ringer, the pre-warmed transports, the
     * flags that gate the next call, and one missed-call history row (no summary message:
     * the caller is gone, and a late summary is what a stale Accept already sent once).
     */
    private suspend fun abandonStaleRing() {
        val stale = _currentCall.value
        stopRingtoneAndRingback()
        if (stale != null) {
            markCallIdAsEnded(stale.callId)
            clearPresentedIncomingSurface(stale.callId)
            try {
                saveCallHistory(stale, if (stale.isOutgoing) CallEndReason.NO_ANSWER else CallEndReason.MISSED)
            } catch (e: Exception) {
                Log.w(TAG, "🧹 Stale ring history row not written: ${e.message}")
            }
        }
        try { stopUdpRelay() } catch (_: Exception) {}
        try { vpsClient.disconnectCallWebSocket() } catch (_: Exception) {}
        try { stopNetworkMonitoring() } catch (_: Exception) {}
        try { p2p.stop() } catch (_: Exception) {}
        _lastEndReason.value = if (stale?.isOutgoing == true) CallEndReason.NO_ANSWER else CallEndReason.MISSED
        _currentCall.value = null
        sessionKey = null
        callStartTime = null
        callConnectedTime = null
        isEndingCall = false
        receivedRemoteEndSignal = false
        hasAutoAcceptedFromMedia = false
        _callState.value = CallState.IDLE
    }

    // ============================================================================
    // END CALL
    // ============================================================================

    /**
     * Arm a one-shot watchdog that ends the call if it is STILL in [stuckState] with
     * the SAME [callId] after [afterMs].
     *
     * The callId check is the part that matters. `_callState` alone is not an
     * identity: hang up and re-dial and the next call is in the same state when the
     * old timer fires, so the previous call's watchdog kills the new one. iOS learned
     * this and keys its own on `noAnswerWatchdogCallId`
     * (VoiceCallManager.swift:5845-5857).
     */
    private fun armCallWatchdog(
        callId: String,
        stuckState: CallState,
        afterMs: Long,
        reason: CallEndReason,
        why: String
    ) {
        if (callId.isEmpty()) return
        scope.launch {
            delay(afterMs)
            if (_callState.value == stuckState && _currentCall.value?.callId == callId) {
                Log.w(TAG, "⏱️ $why — ${afterMs}ms in $stuckState, ending call (${callId.take(8)})")
                endCall(reason)
                // __STALE_RING_2026_09_23__ endCall can REFUSE (its `isEndingCall` guard).
                // A watchdog that is refused is no backstop at all: the ring then lived
                // until the next offer, which it blocked. Still ringing on the same call
                // after endCall returned means nothing is going to end it — drop it here.
                if (stuckState == CallState.RINGING &&
                    _callState.value == CallState.RINGING && _currentCall.value?.callId == callId
                ) {
                    Log.w(TAG, "⏱️ endCall refused for a dead ring (${callId.take(8)}) — abandoning it")
                    abandonStaleRing()
                }
            }
        }
    }

    fun endCall(reason: CallEndReason = CallEndReason.HUNG_UP) {
        val call = _currentCall.value ?: return

        Log.w(TAG, "DIAG: endCall() CALLED — reason=$reason, state=${_callState.value}, isEndingCall=$isEndingCall, isOutgoing=${call.isOutgoing}, video=${call.isVideoCall}, audioSent=$audioPacketsSent, audioRcvd=$audioPacketsReceived, videoSent=$videoPacketsSent, videoRcvd=$videoPacketsReceived")
        Log.w(TAG, "DIAG: endCall() — callDuration=${_callDuration.value}s, wsConnected=${vpsClient.isCallWebSocketConnected}, timeSinceConnect=${callConnectedTime?.let { System.currentTimeMillis() - it } ?: "never"}")
        Log.w(TAG, "DIAG: endCall() PACKET TYPES — audioType(0x05)=$diagAudioTypeCount, videoType(0xF1)=$diagVideoTypeCount, otherType=$diagOtherTypeCount, totalIncoming=$diagIncomingPacketCount")

        // 🔧 FIX: Prevent multiple concurrent endCall executions
        if (isEndingCall) {
            Log.w(TAG, "⚠️ Ignoring endCall - already ending call")
            return
        }

        // 🔧 FIX: Protect CONNECTING state from stale remote end signals
        // When we're in the middle of accepting a call, ignore remote hangup signals
        // __LATE_ACCEPT_2026_11_23__ ...unless the end names this exact call: then it is the
        // caller who gave up before our accept landed (call ea1fac0f, 22/11), not a straggler.
        val endIsForThisCall = reason == CallEndReason.PEER_ENDED && remoteEndConfirmedFor(call.callId)
        if (_callState.value == CallState.CONNECTING && reason == CallEndReason.PEER_ENDED && !endIsForThisCall) {
            Log.w(TAG, "⚠️ Blocking endCall - call is still connecting (likely stale signal)")
            return
        }

        // 🔧 CRITICAL FIX: Extended grace period protection (iOS compatible - 15 seconds)
        callConnectedTime?.let { connectedTime ->
            val timeSinceConnect = System.currentTimeMillis() - connectedTime

            // For connection loss, use 5 second protection
            if (reason == CallEndReason.CONNECTION_LOST && timeSinceConnect < 5000) {
                Log.w(TAG, "⚠️ Blocking endCall - call just connected ${timeSinceConnect}ms ago (connection loss)")
                return
            }

            // 🔧 NEW: For remote-initiated hangup, use 3 second protection
            // This prevents stale callEnd signals from previous call attempts
            if (reason == CallEndReason.PEER_ENDED && receivedRemoteEndSignal && !endIsForThisCall && timeSinceConnect < RINGING_GRACE_PERIOD_MS) {
                Log.w(TAG, "⚠️ Blocking remote endCall - call just connected ${timeSinceConnect}ms ago (stale signal)")
                return
            }
        }

        // 🔧 NEW: Protect outgoing calls in ringing state from stale end signals
        if (_currentCall.value?.isOutgoing == true && _callState.value == CallState.RINGING) {
            if (reason == CallEndReason.PEER_ENDED && receivedRemoteEndSignal) {
                callStartTime?.let { startTime ->
                    val timeSinceStart = System.currentTimeMillis() - startTime
                    if (timeSinceStart < RINGING_GRACE_PERIOD_MS) {
                        Log.w(TAG, "⚠️ Blocking endCall - outgoing call just started ringing ${timeSinceStart}ms ago")
                        return
                    }
                }
            }
        }

        isEndingCall = true
        // Past every guard: this end is really happening, so it is the reason the
        // ended overlay will name. See [lastEndReason].
        _lastEndReason.value = reason
        stopRingtoneAndRingback()

        // 🛟 MULTI-DEVICE FIX (mirrors iOS 1.0.9): mark this callId so any
        // re-delivered ring signal for the same call is silently dropped by
        // the recentlyEndedCallIds dedup. Caller may keep retrying VoIP push /
        // sendCallSignal for several seconds after we've already hung up.
        markCallIdAsEnded(call.callId)

        // 📱 MULTI-DEVICE: tell our OTHER devices the call is over. Until now the
        // 0x0A dismissal fired only from `acceptCall`, so DECLINING or hanging up on
        // this phone left the user's second phone ringing until its own watchdog
        // gave up. iOS added exactly this at VoiceCallManager.swift:8080-8098, with
        // the same two exclusions: a remote-driven end already reaches the siblings
        // through their own poll, and an `answeredElsewhere` end is itself the echo
        // of a sibling's dismissal.
        if (!receivedRemoteEndSignal && reason != CallEndReason.ANSWERED_ELSEWHERE) {
            sendAnsweredElsewhereSignal(call)
        }

        // ⏱️ TEARDOWN ON THE SIGNAL, NOT BEHIND IT.
        //
        // Everything the USER can see — the peer's picture and the call screen — is
        // released here, before a single byte goes out. It used to live at the tail of
        // the coroutine below, seven steps in, *after* `vpsClient.sendCallSignal()`:
        // a blocking OkHttp POST with `connectTimeout = 10 s` and `readTimeout = 30 s`
        // (`VPSClient.kt:99-102`, `:1176`, `:2291-2316`), retried across DNS routes.
        // The case where that POST is slowest is exactly this one — the peer has just
        // hung up, so there is nobody left on the other end of the call it is
        // announcing. Until it returned, `stopVideo()` had not run, the decoder kept
        // its last frame on the surface, and the screen still read IN_CALL. That is
        // the "video kept playing for about twenty seconds after I hung up on the
        // iPhone" report: not a signal arriving late, a socket timing out in front of
        // the teardown.
        //
        // The fan-out below is unchanged and still happens — it is how the PEER learns,
        // and on a locally-initiated hang-up it is the only thing that tells them. It
        // simply no longer gates our own eyes. Order within the coroutine still
        // matters there (the WebSocket must carry the end signal *before*
        // `disconnectCallWebSocket()` closes it), which is why the rest is left alone.
        //
        // Its own coroutine rather than a straight call: `stopVideo()` closes the
        // camera and stops both MediaCodecs on the calling thread
        // (`VideoCallManager.kt:453-501`), and `endCall` is reachable from Main (the
        // PiP END_CALL broadcast, `VideoCallScreen.kt:106`). It is idempotent, so
        // racing it with anything else is harmless — the only thing that matters is
        // that nothing between here and it can block.
        val teardownDecidedAtMs = System.currentTimeMillis()
        // Same line as iOS `📞 CALL ENDED - <reason>`; sealed right away, the process
        // may not live long after a hang-up.
        CallDiag.urgent(TAG, "📞 CALL ENDED - $reason | duration=${_callDuration.value}s | audioSent=$audioPacketsSent | audioRcvd=$audioPacketsReceived | videoSent=$videoPacketsSent | videoRcvd=$videoPacketsReceived")
        _callState.value = CallState.ENDED
        // Tracked (not fire-and-forget) so the NEXT call's startVideoForCall() can
        // join it before re-arming. Without that join this coroutine can land after
        // the next call has started its video and clobber it — see [videoTeardownJob].
        videoTeardownJob = scope.launch {
            videoCallManager.stopVideo()
            videoCallManager.onRawVideoPacketReady = null
            CallDiag.w(
                TAG,
                "DIAG_TEARDOWN | video released ${System.currentTimeMillis() - teardownDecidedAtMs}ms " +
                    "after $reason was decided (remoteSignal=$receivedRemoteEndSignal)"
            )
        }

        scope.launch {
            // 🔧 CRITICAL FIX: Create iOS-compatible binary end signal
            // iOS format: [type(1)][timestamp(8)][reason(UTF-8)] where type = 0x04 (callEnd)
            val timestampMs = System.currentTimeMillis()
            // ⚠️ iOS's vocabulary, NOT `reason.name`. iOS parses this body with
            // `CallEndReason(rawValue:)` (VoiceCallManager.swift:5313-5319, :6992-6997)
            // and silently falls back to `.hungUp` on anything it does not know —
            // and it knew none of the Kotlin enum names. See [CallEndReasonWire].
            val reasonBytes = CallEndReasonWire.toWire(reason).toByteArray(Charsets.UTF_8)
            val endPayload = ByteArray(1 + 8 + reasonBytes.size)

            endPayload[0] = 0x04  // iOS CallPacketType.callEnd
            endPayload[1] = (timestampMs shr 56).toByte()
            endPayload[2] = (timestampMs shr 48).toByte()
            endPayload[3] = (timestampMs shr 40).toByte()
            endPayload[4] = (timestampMs shr 32).toByte()
            endPayload[5] = (timestampMs shr 24).toByte()
            endPayload[6] = (timestampMs shr 16).toByte()
            endPayload[7] = (timestampMs shr 8).toByte()
            endPayload[8] = timestampMs.toByte()
            System.arraycopy(reasonBytes, 0, endPayload, 9, reasonBytes.size)

            val encryptedPayload = cryptoManager.encryptCallSignal(endPayload, call.peerPublicKey)

            val signal = CallSignal(
                callId = call.callId,
                senderPublicKey = myPublicKey,
                type = CallSignalType.CALL_END,
                payload = encryptedPayload,
                timestamp = timestampMs
            )

            // 🔧 iOS-COMPATIBLE DUAL PATH: Send end signal via both WebSocket AND HTTP
            // iOS sends via both paths to ensure reliable delivery (matching iOS endCall behavior)

            // Path 1: WebSocket (fast, real-time) — send raw binary signal
            try {
                val endSignalData = ByteArray(1 + 8)
                endSignalData[0] = 0x04  // CallPacketType.callEnd
                for (i in 0..7) {
                    endSignalData[1 + i] = (timestampMs shr (56 - i * 8)).toByte()
                }
                val sentViaWs = vpsClient.sendCallAudioViaWebSocket(call.callId, endSignalData, call.peerPublicKey)
                if (sentViaWs) {
                    Log.d(TAG, "📞 End signal sent via WebSocket")
                }
            } catch (e: Exception) {
                Log.w(TAG, "WebSocket end signal failed: ${e.message}")
            }

            // Path 2: HTTP (reliable backup) — encrypted call signal
            try {
                vpsClient.sendCallSignal(call.peerPublicKey, signal)
                Log.d(TAG, "📞 End signal sent via HTTP (reason: $reason)")
            } catch (e: Exception) {
                Log.w(TAG, "HTTP end signal failed: ${e.message}")
            }

            // 🔧 FIX: Path 3: Cross-platform mesh — required for iOS peers on MESH_DIRECT.
            // Without this, if the call is going P2P via mesh and VPS is unreachable,
            // iOS never learns the call ended and hangs in the call UI indefinitely.
            try {
                if (crossPlatformMesh.isPeerReachable(call.peerPublicKey)) {
                    crossPlatformMesh.sendCallSignal(call.peerPublicKey, encryptedPayload)
                    Log.d(TAG, "📞 End signal sent via cross-platform mesh")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cross-platform mesh end signal failed: ${e.message}")
            }

            // 📞 Path 4: IN-BAND callEnd (0x0D) over the LIVE media transports
            // (P2P + UDP relay + mesh bridge + WS/HTTP), the same fan-out the
            // video-upgrade control uses. The signaling paths above ride the
            // call_signal / WS-audio channels, which a zombie WebSocket (connected
            // flag true but silent 30s+) can swallow whole — leaving the peer on a
            // frozen call. The in-band copy travels the media path the audio/video
            // frames themselves use, so it lands wherever media still flows and the
            // peer tears down within ~1s. Sent ONLY when WE initiated the hang-up:
            // on a remote-driven end `receivedRemoteEndSignal` is true and echoing
            // 0x0D back would just bounce between the two devices. Must run BEFORE
            // the transports are torn down below (disconnectCallWebSocket /
            // stopUdpRelay / p2p.stop). iOS OSHI/VoiceCallManager.swift.
            if (!receivedRemoteEndSignal) {
                sendInBandCallEnd(reason)
            }

            // Stop audio & VPS polling
            stopAudio()
            vpsAudioReceiveJob?.cancel()
            vpsAudioReceiveJob = null

            // Disconnect call WebSocket
            CallDiag.w(TAG, "DIAG_WS | event=disconnect | wsAudioRxCount=${diagWsAudioRxCount.get()}")
            vpsClient.disconnectCallWebSocket()

            // 🛰️ Tear down the UDP relay heartbeat + socket. Safe when null.
            stopUdpRelay()

            // Stop network monitoring
            stopNetworkMonitoring()

            // 🧭 P2P: release UDP socket + STUN state; clear cutover bookkeeping.
            try { p2p.stop() } catch (_: Exception) {}
            try { p2pWatchdogJob?.cancel() } catch (_: Exception) {}
            p2pWatchdogJob = null
            p2pForceRelayActive = false
            p2pLitUpAt = 0L
            lastP2PInboundAt = 0L
            rxDedupSeen.clear()
            // 🚚 TX carrier selection state — start the next call with no evidence,
            // no nominated primary and the blackout rotation disarmed.
            pathLastRxAt.clear()
            txPrimaryCurrent = null
            videoTxPrimaryCurrent = null
            txPrimarySelectedAt = 0L
            txBlackoutRotatedAt = 0L
            txBlackoutCursor = 0

            // 📹 Video is already down — released before this coroutine started, see
            // the DIAG_TEARDOWN block above. Deliberately NOT repeated here: putting
            // it back would re-establish the very ordering this fix removes, and the
            // next person to read the file would assume this is where it happens.

            // 📹 Reset video-upgrade negotiation state for the next call
            videoUpgradeTimeoutJob?.cancel(); videoUpgradeTimeoutJob = null
            calleeVideoRequestTimeoutJob?.cancel(); calleeVideoRequestTimeoutJob = null
            _pendingVideoUpgrade.value = false
            _pendingVideoUpgradeFrom.value = null
            _isAwaitingVideoUpgrade.value = false
            _videoUpgradeDeclined.value = null
            _videoError.value = null
            _shareOwnVideo.value = true

            // Save to history
            saveCallHistory(call, reason)

            // Send call summary message (matches iOS format)
            sendCallSummaryMessage(call, reason)

            // __CALL_RATING_2026_09_22__ Ask how the call actually sounded. Read
            // here, before the reset below clears `_currentCall` and
            // `_callDuration`. The manager decides whether THIS call is worth
            // asking about — a call that dropped or errored always is, a good one
            // is sampled. Same policy and same wire format as iOS.
            CallRatingManager.callDidEnd(
                context = context,
                callId = call.callId,
                peerName = call.peerName,
                durationSeconds = _callDuration.value,
                wasVideo = call.isVideoCall,
                endedNormally = (reason == CallEndReason.HUNG_UP || reason == CallEndReason.PEER_ENDED),
                myPublicKey = myPublicKey,
                // __TX_VOICE_EVIDENCE_2026_10_29__ read here too, and for the same
                // reason: `remotePlatform` is reset a few lines below.
                evidence = callRatingEvidence(),
                // __SHORT_FAILURES_COULD_NEVER_BE_REPORTED_2026_11_07__ narrower
                // than `!endedNormally` on purpose: DECLINED / NO_ANSWER /
                // ANSWERED_ELSEWHERE are not faults and must not raise a sheet.
                wasFault = (reason == CallEndReason.ERROR
                        || reason == CallEndReason.CONNECTION_LOST)
            )

            // Reset state. `ENDED` was already published before the fan-out (see the
            // DIAG_TEARDOWN block); re-publishing the same value on a StateFlow is a
            // no-op, and it is left here so the ENDED → (2 s) → IDLE pair below still
            // reads as one sequence.
            _callState.value = CallState.ENDED
            _currentCall.value = null
            sessionKey = null
            remotePlatform = "unknown"  // Reset for next call — safe cross-platform default
            sessionNonceSalt = ByteArray(4)
            // 🔐 Clear the directional salts too — iOS VoiceCallManager.swift:8552-8553.
            txNonceSalt = ByteArray(0)
            rxNonceSalt = ByteArray(0)
            callConnectedTime = null
            callStartTime = null
            durationJob?.cancel()
            statsJob?.cancel()
            _callDuration.value = 0
            videoSequenceNumber = 0
            // 🔐 Video nonce session state — salt is re-drawn on the next startVideo.
            synchronized(videoNonceLock) {
                videoTxSalt = null
                videoNonceCounter = 0L
            }
            videoReplayWindow.reset()
            jitterBuffer.reset()
            resetOpusPlayout()
            aacEldCodec?.release()
            aacEldCodec = null
            useAacEld = false
            g726Fallback.reset()
            // Call v2 — tear down OshiCodec so the next call renegotiates cleanly.
            try { oshiEncoder?.close() } catch (_: Throwable) {}
            try { oshiDecoder?.close() } catch (_: Throwable) {}
            oshiEncoder = null
            oshiDecoder = null
            useOshiCodec = false
            // 16 kHz PCM path — reset negotiation state + both biquad filter
            // memories so the next call starts from zero (prevents a phantom
            // low-pass tail leaking into the first few frames of the next call).
            use16kHzPCM = false
            pcm16TxZ1 = 0; pcm16TxZ2 = 0
            pcm16RxZ1 = 0; pcm16RxZ2 = 0
            useWbAdpcm = false
            useOpus = false
            opusTx = null
            wbTxState.predictor = 0; wbTxState.index = 0
            wbTxResampler.reset()
            synchronized(wbRxLock) {
                wbRxResampler.reset()
                wbRxPostFilter.reset()
            }
            adaptiveBitrate.reset()
            fecSender.reset()
            fecReceiver.reset()
            _latencyMs.value = 0
            _isBluetoothOn.value = false
            _isRemoteCameraPaused.value = false
            _pendingVideoUpgrade.value = false

            delay(2000)
            _callState.value = CallState.IDLE

            // 🔧 FIX: Reset deduplication state after call ends (with delay)
            delay(5000)
            processedCallSignalIDs.clear()
            processedIncomingCallKeys.clear()
            // __STALE_RING_2026_09_23__ A NEW call may already be ringing by now (we
            // published IDLE 5 s ago). Its flags and its surface claim are its own; only
            // reset them when nothing has taken our place.
            if (_currentCall.value == null) {
                // Single-surface guard: release all claims once the call is fully idle.
                clearPresentedIncomingSurface(null)
                lastIncomingCallTime = null
                receivedRemoteEndSignal = false
                isEndingCall = false
                hasAutoAcceptedFromMedia = false
            }

            Log.d(TAG, "📞 Call ended: $reason")
        }
    }

    // ============================================================================
    // SIGNAL HANDLING
    // ============================================================================

    private suspend fun handleCallSignal(signal: CallSignal) {
        // Reject stale signals older than 60 seconds
        if (signal.timestamp > 0 && System.currentTimeMillis() - signal.timestamp > 60000) {
            Log.w(TAG, "DIAG: Rejecting stale signal — age=${System.currentTimeMillis() - signal.timestamp}ms, type=${signal.type}")
            return
        }

        // 🛟 MULTI-DEVICE FIX (mirrors iOS 1.0.9): drop signals for callIds
        // we've explicitly ended. VoIP-push retries can re-deliver the offer
        // for several seconds after the user has hung up; without this guard
        // the device started ringing again immediately after endCall.
        if (isCallIdRecentlyEnded(signal.callId)) {
            Log.w(TAG, "🛟 Ignoring signal for recently-ended callId ${signal.callId.take(8)} (type=${signal.type})")
            return
        }

        diagSignalCount++
        Log.w(TAG, "DIAG: handleCallSignal #$diagSignalCount RAW: type=${signal.type}, callId=${signal.callId.take(8)}, sender=${signal.senderPublicKey.take(16)}, payloadSize=${signal.payload.size}, state=${_callState.value}")

        // CRITICAL: Resolve actual type from binary payload (iOS doesn't send type field)
        val resolvedSignal = resolveSignalType(signal)

        Log.w(TAG, "DIAG: handleCallSignal #$diagSignalCount RESOLVED: type=${resolvedSignal.type}, callId=${resolvedSignal.callId.take(8)}, sender=${resolvedSignal.senderPublicKey.take(16)}, currentCallId=${_currentCall.value?.callId?.take(8) ?: "none"}, currentPeer=${_currentCall.value?.peerPublicKey?.take(16) ?: "none"}")
        Log.d(TAG, "📞 Received call signal: ${resolvedSignal.type} (state=${_callState.value}, callId=${resolvedSignal.callId.take(8)}, sender=${resolvedSignal.senderPublicKey.take(12)})")

        // 📱 MULTI-DEVICE: callAnsweredElsewhere (0x0A) is intercepted BEFORE the
        // media dispatch. handleAudioData's body sits inside `sessionKey?.let`, and a
        // device that is merely ringing has no media session — routing it there would
        // drop the dismissal on exactly the devices it exists for.
        // iOS VoiceCallManager.swift:6430-6447.
        if (resolvedSignal.type == CallSignalType.AUDIO_DATA &&
            resolvedSignal.payload.isNotEmpty() &&
            resolvedSignal.payload[0] == ANSWERED_ELSEWHERE_TYPE
        ) {
            handleAnsweredElsewhere(resolvedSignal.payload)
            return
        }

        // 🔄 resyncRequest (0x0F) is intercepted here too, and for the mirror-image
        // reason: on the MEDIA channel 0x0F means "stop video", so letting it reach
        // handleAudioData would tear the peer's video down every time they unlocked
        // their screen. iOS VoiceCallManager.swift:5336-5342.
        if (resolvedSignal.type == CallSignalType.AUDIO_DATA &&
            resolvedSignal.payload.isNotEmpty() &&
            resolvedSignal.payload[0] == RESYNC_REQUEST_TYPE
        ) {
            handleResyncRequest()
            return
        }

        // Deduplicate call signals by ID (except audio which has its own dedup)
        if (resolvedSignal.type != CallSignalType.AUDIO_DATA) {
            val signalId = "${resolvedSignal.callId}_${resolvedSignal.type}_${resolvedSignal.timestamp}"

            // Clean up old signal IDs
            if (processedCallSignalIDs.size > 100) {
                processedCallSignalIDs.clear()
            }

            if (processedCallSignalIDs.contains(signalId)) {
                Log.w(TAG, "⚠️ Duplicate signal ignored: $signalId")
                return
            }
            processedCallSignalIDs.add(signalId)
        }

        // __CROSS_CALL_GUARD_2026_11_21__ an accept / decline / end for ANOTHER callId
        // (a late signal from the previous call with this peer) must not touch this one.
        // See [CallSignalGuards]; iOS and desktop already refuse these.
        val activeCallId = _currentCall.value?.callId
        if (CallSignalGuards.isForeignCallSignal(resolvedSignal.type, resolvedSignal.callId, activeCallId)) {
            Log.w(TAG, "🛡️ Ignoring ${resolvedSignal.type} for foreign callId ${resolvedSignal.callId.take(8)} — active call is ${activeCallId?.take(8)} (state ${_callState.value})")
            return
        }

        when (resolvedSignal.type) {
            CallSignalType.CALL_REQUEST -> handleIncomingCall(resolvedSignal)
            CallSignalType.CALL_ACCEPT -> handleCallAccepted(resolvedSignal)
            CallSignalType.CALL_REJECT -> handleCallRejected()
            CallSignalType.CALL_END -> {
                receivedRemoteEndSignal = true
                noteRemoteEnd(resolvedSignal.callId)  // __LATE_ACCEPT_2026_11_23__
                handleCallEnded()
            }
            CallSignalType.AUDIO_DATA -> handleAudioData(resolvedSignal.payload, resolvedSignal.senderPublicKey)
            CallSignalType.ICE_CANDIDATE -> handleIceCandidateExchange(resolvedSignal)
            else -> Log.w(TAG, "Unhandled signal type: ${resolvedSignal.type}")
        }
    }

    /**
     * 🧭 P2P: handle an incoming iceCandidateExchange (0x30) call_signal.
     * Decrypts, strips the leading [0x30] type byte, decodes the candidate list
     * via [P2PTransport.decodeCandidates] and hands each entry to P2PTransport.
     */
    private fun handleIceCandidateExchange(signal: CallSignal) {
        try {
            val senderKey = signal.senderPublicKey.ifEmpty {
                _currentCall.value?.peerPublicKey
            } ?: run {
                CallDiag.w(TAG, "🧭 P2P: ICE exchange dropped — no sender key")
                return
            }
            val decrypted = try {
                cryptoManager.decryptCallSignal(signal.payload, senderKey)
            } catch (e: Exception) {
                CallDiag.w(TAG, "🧭 P2P: failed to decrypt ICE exchange: ${e.message}")
                return
            }
            if (decrypted.isEmpty() || decrypted[0] != ICE_CANDIDATE_EXCHANGE_TYPE) {
                CallDiag.w(TAG, "🧭 P2P: ICE exchange has unexpected leading byte=${decrypted.getOrNull(0)}")
                return
            }
            // iOS wire format is [type:1][timestamp:8 BE][encodedCandidates]. Strip 9 bytes.
            // For backwards-compat with older Android builds that omitted the timestamp,
            // fall back to stripping only 1 byte if the 9-byte strip produces a bad version.
            val candidates: List<P2PTransport.P2PCandidate>
            if (decrypted.size >= 9) {
                val newFormatBody = decrypted.copyOfRange(9, decrypted.size)
                val newFormatList = p2p.decodeCandidates(newFormatBody)
                if (newFormatList.isNotEmpty()) {
                    candidates = newFormatList
                } else {
                    // Legacy Android-to-Android: no timestamp byte
                    candidates = p2p.decodeCandidates(decrypted.copyOfRange(1, decrypted.size))
                }
            } else {
                candidates = p2p.decodeCandidates(decrypted.copyOfRange(1, decrypted.size))
            }
            CallDiag.d(TAG, "🧭 P2P: received ${candidates.size} remote candidates")
            // WHY: DIAG_ICE_RX — side-by-side with peer's DIAG_ICE_TX; ageMs tells if candidates were delayed.
            val typesCsv = candidates.joinToString(",") { c ->
                when (c.type) {
                    P2PTransport.CandidateType.HOST -> "host"
                    P2PTransport.CandidateType.SRFLX -> "srflx"
                    P2PTransport.CandidateType.RELAY -> "relay"
                }
            }
            val ageMs = if (diagCallStartedAtMs > 0) System.currentTimeMillis() - diagCallStartedAtMs else -1L
            CallDiag.w(TAG, "DIAG_ICE_RX | count=${candidates.size} | typesCsv=$typesCsv | ageMsSinceCallStart=$ageMs")
            if (candidates.isEmpty()) return
            p2p.addRemoteCandidates(candidates)
        } catch (e: Exception) {
            CallDiag.w(TAG, "🧭 P2P: handleIceCandidateExchange error: ${e.message}")
        }
    }

    /**
     * 🧭 P2P: send the given local candidates to the current peer over the
     * existing encrypted call_signal envelope. Wire format: [0x30][encoded
     * candidates per P2PTransport.encodeCandidates].
     */
    private fun sendLocalCandidatesToPeer(candidates: List<P2PTransport.P2PCandidate>) {
        val call = _currentCall.value ?: return
        if (candidates.isEmpty()) return
        scope.launch {
            try {
                // Wire format must match iOS createCallPacket: [type:1][timestamp:8 BE][payload].
                // Without the timestamp iOS strips 9 bytes and reads garbage; with it Android can
                // also validate freshness later if we want.
                val encoded = p2p.encodeCandidates(candidates)
                val payload = ByteArray(1 + 8 + encoded.size)
                payload[0] = ICE_CANDIDATE_EXCHANGE_TYPE
                val ts = System.currentTimeMillis()
                for (i in 0..7) {
                    payload[1 + i] = ((ts shr ((7 - i) * 8)) and 0xFF).toByte()
                }
                System.arraycopy(encoded, 0, payload, 9, encoded.size)

                val encrypted = cryptoManager.encryptCallSignal(payload, call.peerPublicKey)
                val signal = CallSignal(
                    callId = call.callId,
                    senderPublicKey = myPublicKey,
                    type = CallSignalType.ICE_CANDIDATE,
                    payload = encrypted,
                    timestamp = System.currentTimeMillis(),
                    privacyMode = localPrivacyMode
                )
                vpsClient.sendCallSignal(call.peerPublicKey, signal)
                CallDiag.d(TAG, "🧭 P2P: sent ${candidates.size} local candidates to peer via call_signal")
                // WHY: DIAG_ICE_TX — time-sync with peer's DIAG_ICE_RX to detect candidate delivery gaps.
                val typesCsv = candidates.joinToString(",") { c ->
                    when (c.type) {
                        P2PTransport.CandidateType.HOST -> "host"
                        P2PTransport.CandidateType.SRFLX -> "srflx"
                        P2PTransport.CandidateType.RELAY -> "relay"
                    }
                }
                CallDiag.w(TAG, "DIAG_ICE_TX | count=${candidates.size} | typesCsv=$typesCsv | sentVia=vps_call_signal(ws=${vpsClient.isCallWebSocketConnected})")
            } catch (e: Exception) {
                CallDiag.w(TAG, "🧭 P2P: failed to send local candidates: ${e.message}")
            }
        }
    }

    /**
     * Resolve the actual signal type from the binary payload.
     * iOS doesn't send a "type" field in VPS JSON — the type is the first byte of the
     * DECRYPTED binary payload. Without this, ALL iOS signals default to CALL_REQUEST.
     */
    private fun resolveSignalType(signal: CallSignal): CallSignal {
        // Only resolve if type defaulted to CALL_REQUEST (iOS signals without type field)
        // Android signals already have the correct type set from JSON
        if (signal.type != CallSignalType.CALL_REQUEST || signal.payload.isEmpty()) return signal

        try {
            val senderKey = signal.senderPublicKey.ifEmpty {
                _currentCall.value?.peerPublicKey
            } ?: return signal

            val decrypted = cryptoManager.decryptCallSignal(signal.payload, senderKey)
            if (decrypted.isEmpty()) return signal

            val firstByte = decrypted[0].toInt() and 0xFF
            val binaryType = when (firstByte) {
                0x01 -> CallSignalType.CALL_REQUEST      // callRequest
                0x02 -> CallSignalType.CALL_ACCEPT       // callAccept
                0x03 -> CallSignalType.CALL_REJECT       // callDecline
                0x04 -> CallSignalType.CALL_END          // callEnd
                0x05 -> CallSignalType.AUDIO_DATA        // audioData
                0x08 -> CallSignalType.CALL_REQUEST      // videoCallRequest
                0x09 -> CallSignalType.CALL_ACCEPT       // videoCallAccept
                // 📹 VIDEO PAUSE/RESUME — iOS encrypts the whole packet on the
                // call_signal channel. Hand the decrypted [type][timestamp]
                // bytes to handleAudioData by routing as AUDIO_DATA — the
                // handler already understands 0x0C / 0x0D as video-toggle
                // signals from its plaintext sibling path.
                // 📹 requestKeyframe (PLI). iOS sends 0x0B on the SIGNAL channel
                // (VoiceCallManager.swift:10367-10372) and reads it there
                // (:5302-5308, :6972-6979). Without this case it fell through the
                // `else` and was handed to handleIncomingCall, which dropped it —
                // so every iOS PLI evaporated and Android never emitted an IDR.
                0x0B, 0x0C, 0x0D -> CallSignalType.AUDIO_DATA
                // 📱 callAnsweredElsewhere. Addressed to our OWN public key, so it
                // lands here on the signal channel, never on the media channel.
                // Routed as AUDIO_DATA with the decrypted body so handleCallSignal
                // can intercept it by byte 0 — see the guard there.
                0x0A -> CallSignalType.AUDIO_DATA
                // 🔄 resyncRequest. Without this case an iOS peer's 0x0F fell into
                // the `else` below, stayed CALL_REQUEST and was handed to
                // handleIncomingCall, which threw "Unknown call request format" —
                // and, worse, had already added the sender to
                // processedIncomingCallKeys on the way in, so a genuine call from
                // that peer in the next 10 s was swallowed as a duplicate.
                0x0F -> CallSignalType.AUDIO_DATA
                0x30 -> CallSignalType.ICE_CANDIDATE     // 🧭 P2P iceCandidateExchange
                else -> return signal
            }

            Log.d(TAG, "📞 Resolved iOS signal type: ${signal.type} -> $binaryType (byte=0x${"%02X".format(decrypted[0])})")
            // 🔧 CROSS-PLATFORM: If we resolved a binary iOS signal, the peer is iOS
            if (binaryType == CallSignalType.CALL_ACCEPT) {
                remotePlatform = "ios"
                Log.d(TAG, "📞 CROSS-PLATFORM: Detected iOS peer from accept signal (codec bypass enabled)")
            }
            // For video toggles we MUST hand the decrypted bytes downstream so
            // handleAudioData can read the 0x0C/0x0D type from byte 0. The
            // original signal.payload is the pre-decrypt ciphertext.
            if (firstByte == 0x0A || firstByte == 0x0B || firstByte == 0x0C ||
                firstByte == 0x0D || firstByte == 0x0F
            ) {
                return signal.copy(type = binaryType, payload = decrypted)
            }
            return signal.copy(type = binaryType)
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve signal type: ${e.message}")
            return signal
        }
    }

    private suspend fun handleMeshCallSignal(message: MeshMessage) {
        try {
            val json = JSONObject(message.payload)
            val signalType = json.getString("signalType")
            val data = Base64.decode(json.getString("data"), Base64.NO_WRAP)

            when (signalType) {
                "CALL_REQUEST" -> {
                    val fakeSignal = CallSignal(
                        callId = message.id,
                        senderPublicKey = message.senderPublicKey,
                        type = CallSignalType.CALL_REQUEST,
                        payload = data,
                        timestamp = message.timestamp
                    )
                    handleIncomingCall(fakeSignal)
                }
                "CALL_ACCEPT" -> handleCallAccepted(null)
                "CALL_AUDIO" -> handleAudioData(data, message.senderPublicKey)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse mesh call signal: ${e.message}")
        }
    }

    private suspend fun handleCrossPlatformMessage(message: com.oshi.messenger.network.mesh.CrossPlatformMessage) {
        when (message.type) {
            "CALL_SIGNAL" -> {
                try {
                    // 🔧 A CALL_SIGNAL carries a SEALED CALL SIGNAL, not media. Every
                    // sender puts `cryptoManager.encryptCallSignal(...)` output on this
                    // wire — the offer (:1455), the accept (:1632), the end (:1872) and
                    // the multi-device dismissal — and iOS does the same
                    // (VoiceCallManager.swift:5986 offer, :7590 answeredElsewhere,
                    // :13356). Handing those bytes to `handleAudioData` meant byte 0 was
                    // a random AES-GCM nonce byte: no branch matched, and the whole
                    // LAN-mesh signalling lane was dead in both directions. The comment
                    // at :1832-1842 ("without this, iOS never learns the call ended")
                    // was describing a path that could not work.
                    //
                    // Route it exactly like a VPS-delivered signal instead. `type` is
                    // CALL_REQUEST because that is the value `resolveSignalType` acts
                    // on; it reads the true type off byte 0 of the decrypted body,
                    // which is how every typeless iOS signal is already resolved.
                    val data = Base64.decode(message.payload, Base64.NO_WRAP)
                    handleCallSignal(
                        CallSignal(
                            callId = "",
                            senderPublicKey = message.senderPublicKey,
                            type = CallSignalType.CALL_REQUEST,
                            payload = data,
                            // Mesh is a LAN hop; stamp arrival so the 60 s staleness
                            // gate cannot reject it over a peer clock offset.
                            timestamp = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle cross-platform call signal: ${e.message}")
                }
            }
            "CALL_AUDIO" -> {
                try {
                    val data = Base64.decode(message.payload, Base64.NO_WRAP)
                    // Received-media evidence for chooseAudioTxPrimary. Mesh RX is always
                    // accepted (as on iOS, VoiceCallManager.swift:10287-10289) so a peer
                    // on an older build that still shotguns mesh audio is heard.
                    notePathRx(AudioTxPrimary.MESH)
                    handleAudioData(data, message.senderPublicKey)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle cross-platform audio: ${e.message}")
                }
            }
        }
    }

    private suspend fun handleIncomingCall(signal: CallSignal) {
        if (_callState.value != CallState.IDLE) {
            // __STALE_RING_2026_09_23__ Was: drop every offer while not IDLE. A ring that
            // outlived its caller then blocked every later call. See [IncomingCallAdmission].
            val cur = _currentCall.value
            val decision = IncomingCallAdmission.decide(
                state = when (_callState.value) {
                    CallState.IDLE -> IncomingCallAdmission.State.IDLE
                    CallState.RINGING -> IncomingCallAdmission.State.RINGING
                    CallState.CONNECTING -> IncomingCallAdmission.State.CONNECTING
                    CallState.IN_CALL -> IncomingCallAdmission.State.IN_CALL
                    CallState.ENDED -> IncomingCallAdmission.State.ENDED
                },
                currentIsOutgoing = cur?.isOutgoing == true,
                currentPeer = cur?.peerPublicKey,
                currentCallId = cur?.callId,
                ringStartedAtMs = cur?.startTime,
                nowMs = System.currentTimeMillis(),
                newPeer = signal.senderPublicKey,
                newCallId = signal.callId,
                calleeTimeoutMs = CALLEE_RING_TIMEOUT_MS,
                callerTimeoutMs = CALLER_NO_ANSWER_TIMEOUT_MS,
            )
            when (decision) {
                IncomingCallAdmission.Decision.ACCEPT -> Unit
                IncomingCallAdmission.Decision.REPLACE_STALE -> {
                    Log.w(TAG, "🧹 Stale ${_callState.value} (${cur?.callId?.take(8) ?: "no call"}) replaced by a new incoming call")
                    abandonStaleRing()
                }
                IncomingCallAdmission.Decision.BUSY -> {
                    // Same order as iOS: blocked / stale-identity / contacts-only callers
                    // are dropped silently BEFORE the busy check, so a decline never tells
                    // a blocked caller anything (VoiceCallManager.swift:6675-6742).
                    val silent = StaleIdentityStore.get(context).isStale(signal.senderPublicKey) ||
                        CallAdmissionPolicy.isBlocked(database.contactDao(), signal.senderPublicKey) ||
                        CallAdmissionPolicy.rejectsUnknownCaller(context, database.contactDao(), signal.senderPublicKey)
                    if (!silent) {
                        Log.d(TAG, "📵 Busy (${_callState.value}) — declining incoming call from ${signal.senderPublicKey.take(12)}")
                        sendBusyDecline(signal.senderPublicKey, signal.callId)
                    }
                    return
                }
                IncomingCallAdmission.Decision.DUPLICATE,
                IncomingCallAdmission.Decision.TEARDOWN -> {
                    Log.d(TAG, "⚠️ Ignoring incoming call - already in call state: ${_callState.value} ($decision)")
                    return
                }
            }
        }

        // 🪦 Stale identity gate — caller's public key has been marked as a
        // rotated/old identity (manual flag or auto via QR re-scan / group
        // KEY_ROTATION). Drop the CALL_REQUEST silently — no ring, no ACK
        // back, no callee-side state change. The new identity will pass
        // through this gate normally with its new key.
        // Runs after the in-call short-circuit so we never disturb an active
        // call, and BEFORE dedup so we don't waste a callKey slot.
        if (StaleIdentityStore.get(context).isStale(signal.senderPublicKey)) {
            Log.w(TAG, "🪦 Stale identity caller — dropping CALL_REQUEST silently: ${signal.senderPublicKey.take(12)}…")
            return
        }

        // 🚫 Blocked caller. iOS drops it silently here too — no ring, and deliberately
        // no decline signal back, so the caller cannot learn they were blocked
        // (`VoiceCallManager.swift:6265-6270`).
        if (CallAdmissionPolicy.isBlocked(database.contactDao(), signal.senderPublicKey)) {
            Log.w(TAG, "🚫 Blocked caller — dropping CALL_REQUEST silently: ${signal.senderPublicKey.take(12)}…")
            // Still no ring and no call signal back; the caller learns through the
            // rate-limited 🚫BLOCKED🚫 chat line (__BLOCKED_NOTICE_SWITCH_2026_09_23__).
            com.oshi.messenger.data.repository.MessageRepository.noticeBlockedCaller(signal.senderPublicKey)
            return
        }
        // __BLOCKED_BY_PEER_2026_09_24__ a peer that calls us no longer blocks us.
        BlockedByPeerStore.get(context).clear(signal.senderPublicKey)

        // 🚫 Contacts-only whitelist. iOS applies it immediately after the stale check,
        // in the same order (`VoiceCallManager.swift:6280-6284`). Until now
        // `contacts_only_calls` was written by SettingsViewModel and read by nobody on
        // Android, so a user protected on their iPhone was wide open on their Android.
        if (CallAdmissionPolicy.rejectsUnknownCaller(context, database.contactDao(), signal.senderPublicKey)) {
            Log.w(TAG, "🚫 Call rejected — caller not in contacts (contacts-only mode)")
            return
        }

        // Deduplicate incoming calls by callId (not sender key — sender key blocks retries)
        val now = System.currentTimeMillis()
        if (lastIncomingCallTime != null && now - lastIncomingCallTime!! > 10000L) {
            processedIncomingCallKeys.clear()
        }

        val callKey = signal.callId.ifEmpty { signal.senderPublicKey }
        if (processedIncomingCallKeys.contains(callKey)) {
            Log.w(TAG, "⚠️ Duplicate incoming call ID - ignoring")
            return
        }

        processedIncomingCallKeys.add(callKey)
        lastIncomingCallTime = now

        try {
            // 🔧 CRITICAL FIX: Use call-specific HKDF params (salt="OSHI-VoiceCall-v1", info="signaling")
            // This matches iOS VoiceCallManager.encryptForPeer()
            val decrypted = cryptoManager.decryptCallSignal(signal.payload, signal.senderPublicKey)

            var callId: String
            var isVideo: Boolean
            var platform: String

            // Check first byte to determine format
            // iOS format: [type(1)][timestamp(8)][sessionKey(32)][salt(4)][callId(UTF-8)]
            // Android legacy: JSON string
            val packetType = decrypted[0]

            if (packetType == 0x01.toByte() || packetType == 0x08.toByte()) {
                // 📱 iOS BINARY FORMAT
                Log.d(TAG, "📞 Parsing iOS binary call request (type=$packetType)")

                isVideo = (packetType == 0x08.toByte())
                platform = "ios"

                // Skip type(1) + timestamp(8) = 9 bytes to get to sessionKey
                if (decrypted.size < 9 + 32 + 4) {
                    throw Exception("iOS packet too small: ${decrypted.size} bytes")
                }

                // Read timestamp (for validation)
                var timestampMs = 0L
                for (i in 1..8) {
                    timestampMs = (timestampMs shl 8) or (decrypted[i].toLong() and 0xFF)
                }
                Log.d(TAG, "   Timestamp: $timestampMs")

                // Read session key (32 bytes starting at offset 9)
                sessionKey = decrypted.copyOfRange(9, 9 + 32)
                Log.d(TAG, "   Session key extracted: ${sessionKey?.size} bytes")

                // Read salt (4 bytes starting at offset 41) - store for nonce derivation
                val salt = decrypted.copyOfRange(41, 41 + 4)
                sessionNonceSalt = salt  // 🔧 FIX: Use iOS caller's salt for audio encryption nonce
                // 🔐 CRYPTO FIX: we are the RESPONDER — transmit under the DERIVED salt
                // so our nonces can never collide with the caller's (both ends start
                // their counter at 1 under the same key). Fully wire-compatible: the
                // peer decrypts using the nonce carried in each packet.
                // iOS VoiceCallManager.swift:6527-6531.
                applyDirectionalNonceSalts(isInitiator = false)
                Log.d(TAG, "   Salt extracted: ${salt.size} bytes (stored for nonce)")

                // Call v2 capability bytes. The body ends [callId][cap bytes…]. A
                // capability byte is ALWAYS < 0x2D; a UUID callId is hex + hyphen, every
                // byte >= 0x2D. So peel the WHOLE trailing run of bytes < 0x2D — the old
                // code only knew two specific tails, so when iOS started appending a
                // THIRD (APPLE_CODEC_CAP_FLAG = 0x04) it left 0x04 inside the callId,
                // changing its SHA-256; the derived P2P cid then mismatched and every
                // ICE ping was dropped (cid_mismatch) → cross-platform calls fell back
                // to relay-only, i.e. total silence whenever the relay was unavailable.
                // Peeling all cap bytes restores the correct callId; we act only on the
                // flags we understand (Apple's 0x04 is ignored — Android renders any
                // non-OshiCodec audio as raw PCM regardless).
                // Positional peel with an 8-byte bound — see CallOfferCapabilities.
                val caps = CallOfferCapabilities.peel(decrypted, callIdStart = 45)
                val callIdEnd = caps.callIdEnd
                val peerSupportsOshiCodec = caps.oshiCodec
                val peerSupports16kHzPCM = caps.pcm16k
                // Ce que l'appelant annonce sur la vidéo, appris dès l'offre.
                peerSupportsVideo = caps.video
                CallDiag.w(TAG, "DIAG_VUPGRADE_PEER_CAP | (offre) vidéo=${caps.video}")

                // Read callId (bytes from offset 45, excluding the optional cap tail)
                val callIdBytes = if (callIdEnd > 45) decrypted.copyOfRange(45, callIdEnd) else ByteArray(0)
                callId = String(callIdBytes, Charsets.UTF_8)
                Log.d(TAG, "   CallId: $callId")

                // Finalize Call v2 negotiations on the callee side. Both flags are
                // independent: peer could advertise one, both, or neither.
                useOshiCodec = localSupportsOshiCodec && peerSupportsOshiCodec
                use16kHzPCM = localSupports16kHzPCM && peerSupports16kHzPCM
                useWbAdpcm = localSupportsWbAdpcm && caps.wbAdpcm
                useOpus = localSupportsOpus && caps.opus
                CallDiag.w(TAG, "DIAG_CODEC | role=callee | useOpus=$useOpus | peerOpus=${caps.opus} | useWbAdpcm=$useWbAdpcm | peerWb=${caps.wbAdpcm} | remotePlatform=$remotePlatform")
                Log.w(TAG, "📞 Call v2 negotiation: peerAdvertised=$peerSupportsOshiCodec localSupports=$localSupportsOshiCodec useOshiCodec=$useOshiCodec peer16k=$peerSupports16kHzPCM use16kHzPCM=$use16kHzPCM")

                Log.d(TAG, "📞 Incoming iOS call: video=$isVideo, callId=$callId")

            } else if (decrypted[0] == '{'.code.toByte()) {
                // 📱 ANDROID JSON FORMAT (legacy)
                Log.d(TAG, "📞 Parsing Android JSON call request")
                val jsonString = String(decrypted, Charsets.UTF_8)
                val json = JSONObject(jsonString)

                callId = json.getString("callId")
                sessionKey = Base64.decode(json.getString("sessionKey"), Base64.NO_WRAP)
                isVideo = json.optBoolean("isVideo", false)
                platform = json.optString("platform", "android")
                val codec = json.optString("codec", "unknown")

                Log.d(TAG, "📞 Incoming Android call from $platform using codec: $codec")

            } else {
                throw Exception("Unknown call request format: first byte=${decrypted[0]}")
            }

            // 🔒 Extract remote privacy mode from signal metadata (Phase 4)
            remotePrivacyMode = signal.privacyMode
            Log.d(TAG, "🔒 Remote privacy mode: $remotePrivacyMode")

            val contact = database.contactDao().getContactByPublicKey(signal.senderPublicKey)
            // __SHARED_NICKNAME_2026_09_22__ alias > peer's shared nickname > key prefix.
            val callerName = com.oshi.messenger.data.model.PeerNicknameStore.displayName(contact?.alias, signal.senderPublicKey)

            _currentCall.value = CurrentCall(
                callId = callId,
                peerPublicKey = signal.senderPublicKey,
                peerName = callerName,
                isOutgoing = false,
                isVideoCall = isVideo,
                startTime = System.currentTimeMillis()
            )

            // 🛟 SINGLE-SURFACE FIX (mirrors iOS): claim the one incoming surface for
            // this callId. __B1_RING_DEDUPE_2026_09_23__ If FCM already raised the system
            // full-screen ringer (IncomingCallService) for the same call, KEEP it: it is
            // the only ring a locked/backgrounded phone shows. Dismissing it here (as this
            // used to) cut the full-screen ring the moment the relay copy of the offer
            // landed. We only skip our own in-app ringtone below — see IncomingRingPolicy.
            val ring = IncomingRingPolicy.onIncomingOffer(tryPresentIncomingSurface(callId))
            if (ring == IncomingRingPolicy.Ring.KEEP_SYSTEM_RINGER) {
                Log.d(TAG, "🛟 Incoming surface already up for ${callId.take(8)} — FCM ringer keeps ringing, no second ringtone")
            }

            // __STALE_RING_2026_09_23__ Same reset `startCall` does (:1583) and this path
            // never did. A call that starts ringing while the previous call's teardown
            // coroutine is in its final 5 s still saw `isEndingCall = true`, so the
            // caller's cancel hit endCall's "already ending call" guard and was dropped —
            // the ring then survived its caller.
            isEndingCall = false
            receivedRemoteEndSignal = false
            _callState.value = CallState.RINGING
            reportRinging(signal.senderPublicKey, callId)  // __CALL_WAKE_2026_11_22__
            // ⏱️ Callee-side ring watchdog — iOS `calleeRingWatchdog`,
            // VoiceCallManager.swift:4006-4016. Without it the incoming-call state
            // never ended: IncomingCallService silenced the ringtone at 45 s and
            // broadcast CALL_MISSED to nobody, so the screen stayed up and no missed
            // call was written. 55 s > the caller's 45 s on purpose, so the caller's
            // own end signal normally wins and this is only the backstop.
            armCallWatchdog(callId, CallState.RINGING, CALLEE_RING_TIMEOUT_MS,
                CallEndReason.MISSED, "incoming call rang unanswered")
            audioSequenceNumber = 0
            // 🔧 CROSS-PLATFORM: Store remote platform for codec bypass
            remotePlatform = platform
            Log.d(TAG, "📞 Remote platform detected: $remotePlatform (codec bypass: ${remotePlatform == "ios"})")
            // 🔧 Generate nonce salt only for legacy JSON callers (binary offers carry
            // the caller's salt, already installed above together with the directional
            // split). We are the RESPONDER on this path too — iOS VoiceCallManager.swift:6531.
            if (platform != "ios") {
                sessionNonceSalt = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
                applyDirectionalNonceSalts(isInitiator = false)
            }
            if (ring == IncomingRingPolicy.Ring.PLAY_IN_APP) playRingtone()

            // P2P pre-warm: start STUN gathering + candidate exchange while still ringing.
            // By the time the user taps Accept, hole-punch is usually already done and audio
            // flows over P2P from the first frame — no 3-5s VPS-to-P2P cutover lag.
            try {
                val forceRelay = readForceRelayMode()
                p2pForceRelayActive = forceRelay
                ensureFreshTurnCreds()  // F6: rotate before allocate
                p2p.onLocalCandidatesReady = { cands -> sendLocalCandidatesToPeer(cands) }
                p2p.start(forceRelay = forceRelay)
                CallDiag.d(TAG, "🧭 P2P: prewarm started during ringing")
            } catch (e: Exception) {
                CallDiag.w(TAG, "🧭 P2P: prewarm failed — ${e.message}")
            }

            Log.d(TAG, "📞 Incoming call from: $callerName ($platform)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to handle incoming call: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun handleCallAccepted(signal: CallSignal?) {
        val currentState = _callState.value
        Log.w(TAG, "DIAG: handleCallAccepted ENTER state=$currentState, signal=${signal != null}, video=${_currentCall.value?.isVideoCall}, callId=${_currentCall.value?.callId?.take(8) ?: "none"}")
        // 🔧 FIX: Only accept from RINGING state — prevents double audio/video start
        // iOS sends multiple accept signals (retry), we must only process the first one
        if (currentState != CallState.RINGING) {
            Log.w(TAG, "DIAG: handleCallAccepted REJECTED — state=$currentState (expected RINGING)")
            Log.w(TAG, "⚠️ handleCallAccepted: ignoring — state=$currentState (expected RINGING)")
            return
        }

        Log.d(TAG, "📞 handleCallAccepted: processing (state=$currentState, video=${_currentCall.value?.isVideoCall})")

        try {
            // 🔒 Extract remote privacy mode from accept signal and negotiate transport (Phase 4)
            if (signal != null) {
                remotePrivacyMode = signal.privacyMode
                val transport = negotiateTransport(localPrivacyMode, remotePrivacyMode)
                Log.d(TAG, "🔒 Call accepted: local=$localPrivacyMode, remote=$remotePrivacyMode -> transport=$transport")
            }

            // Call v2 — finalize capability negotiation on the caller side. Parse
            // the trailing bytes of the decrypted accept body: [OshiCodec cap(1)]
            // [16 kHz PCM cap(1)]. Legacy callees send an empty body → both flags
            // remain false → we fall back to 0x15 (raw 48 kHz PCM).
            var peerAdvertisedOshiCodec = false
            var peerAdvertised16kHzPCM = false
            var peerAdvertisedWbAdpcm = false
            var peerAdvertisedOpus = false
            if (signal != null && signal.payload.isNotEmpty()) {
                try {
                    val senderKey = signal.senderPublicKey.ifEmpty { _currentCall.value?.peerPublicKey }
                    if (senderKey != null) {
                        val decryptedAccept = cryptoManager.decryptCallSignal(signal.payload, senderKey)
                        // Accept payload = [type(1)][ts(8)][cap bytes…]. The base is
                        // 9 bytes; every byte after it is a capability flag. Collect
                        // them all (length-agnostic) so a THIRD iOS cap byte (e.g.
                        // APPLE_CODEC_CAP_FLAG=0x04) is handled instead of mis-read —
                        // mirroring the offer-side peel. We act only on the flags we
                        // understand; anything else is ignored.
                        if (decryptedAccept.size > 9) {
                            val caps = HashSet<Int>()
                            for (k in 9 until decryptedAccept.size) caps.add(decryptedAccept[k].toInt() and 0xFF)
                            peerAdvertisedOshiCodec = caps.contains(OSHI_CODEC_CAP_FLAG.toInt() and 0xFF)
                            peerAdvertised16kHzPCM = caps.contains(RAW_PCM_16K_CAP_FLAG.toInt() and 0xFF)
                            // La vidéo se lit POSITIONNELLEMENT (emplacement 3),
                            // comme iOS, et non par appartenance à l'ensemble: un
                            // drapeau futur qui répéterait une valeur antérieure
                            // dans un autre emplacement rendrait `contains` faux.
                            // Absent = le pair n'a rien dit, pas « il a dit non ».
                            peerSupportsVideo = if (decryptedAccept.size > 12)
                                (decryptedAccept[12] == VIDEO_CAP_FLAG) else null
                            // WB-ADPCM: positional, slot 4 of the cap run (index 13).
                            peerAdvertisedWbAdpcm = decryptedAccept.size > 13 &&
                                (decryptedAccept[13].toInt() and 0xFF) == com.oshi.messenger.service.audio.WbAdpcmCodec.CAP_FLAG
                            // Opus: positional, slot 5 of the cap run (index 14).
                            peerAdvertisedOpus = decryptedAccept.size > 14 &&
                                (decryptedAccept[14].toInt() and 0xFF) == com.oshi.messenger.service.audio.OpusWire.CAP_FLAG
                            CallDiag.w(TAG, "DIAG_VUPGRADE_PEER_CAP | vidéo=$peerSupportsVideo")
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "📞 Call v2: could not parse accept flags — disabling. ${t.message}")
                }
            }
            useOshiCodec = localSupportsOshiCodec && peerAdvertisedOshiCodec
            use16kHzPCM = localSupports16kHzPCM && peerAdvertised16kHzPCM
            // Only ever turn it ON here: an accept that lost its tail (duplicate / legacy
            // path) must not undo an auto-upgrade from a 0x18 already received.
            if (localSupportsWbAdpcm && peerAdvertisedWbAdpcm) useWbAdpcm = true
            if (localSupportsOpus && peerAdvertisedOpus) useOpus = true
            CallDiag.w(TAG, "DIAG_CODEC | role=caller | useOpus=$useOpus | peerOpus=$peerAdvertisedOpus | useWbAdpcm=$useWbAdpcm | peerWb=$peerAdvertisedWbAdpcm | remotePlatform=$remotePlatform")
            Log.w(TAG, "📞 Call v2 negotiation (caller): peerAdvertised=$peerAdvertisedOshiCodec localSupports=$localSupportsOshiCodec useOshiCodec=$useOshiCodec peer16k=$peerAdvertised16kHzPCM use16kHzPCM=$use16kHzPCM")
            if (useOshiCodec && oshiEncoder == null) {
                try {
                    oshiEncoder = OshiCodec().apply { setBitrate(preferredCallBitrate) }
                    oshiDecoder = OshiCodec().apply { setBitrate(preferredCallBitrate) }
                    Log.w(TAG, "📞 Call v2: OshiCodec allocated (caller) — 48 kbps default")
                } catch (t: Throwable) {
                    Log.w(TAG, "📞 Call v2: OshiCodec allocation failed — disabling. ${t.message}")
                    useOshiCodec = false
                    oshiEncoder = null
                    oshiDecoder = null
                }
            }

            stopRingtoneAndRingback()
            _callState.value = CallState.CONNECTING
            // ⏱️ Peer accepted; if the media path never comes up we must not sit in
            // CONNECTING forever. iOS VoiceCallManager.swift:3267, :5686-5695.
            _currentCall.value?.callId?.let {
                armCallWatchdog(it, CallState.CONNECTING, CONNECTING_TIMEOUT_MS,
                    CallEndReason.ERROR, "accepted-by-peer call never left CONNECTING")
            }

            Log.d(TAG, "📞 Starting audio...")
            startAudio()
            Log.d(TAG, "📞 Audio started")

            // 📹 Start video if video call
            val call = _currentCall.value
            if (call?.isVideoCall == true) {
                CallDiag.d(TAG, "📹 Starting video for call...")
                startVideoForCall()
                CallDiag.d(TAG, "📹 Video started")
            }

            callConnectedTime = System.currentTimeMillis()
            lastAudioReceivedTime = System.currentTimeMillis()
            egressStall.reset()  // __EGRESS_STALL_2026_11_23__
            _callState.value = CallState.IN_CALL
            Log.w(TAG, "DIAG: STATE TRANSITION -> IN_CALL (from handleCallAccepted, video=${call?.isVideoCall}, wsConnected=${vpsClient.isCallWebSocketConnected}, binaryWs=${vpsClient.serverSupportsBinary})")

            // WHY: DIAG_ACCEPT — side-by-side baseline for caller role.
            diagCallStartedAtMs = System.currentTimeMillis()
            diagLastTxPath = ""
            diagMicReadFired = false
            diagLastP2PStateStr = ""
            diagDecryptFailLogCount = 0
            val saltHexCaller = sessionNonceSalt.joinToString("") { "%02x".format(it) }
            val forceRelayLogC = readForceRelayMode()
            val callIdLog = call?.callId ?: "nil"
            val videoLog = call?.isVideoCall ?: false
            CallDiag.w(TAG, "DIAG_ACCEPT | role=caller | callId=$callIdLog | remotePlatform=$remotePlatform | sessionKeyPresent=${sessionKey != null} | saltHex=$saltHexCaller | isVideo=$videoLog | forceRelay=$forceRelayLogC | wsConnected=${vpsClient.isCallWebSocketConnected}")
            Log.d(TAG, "📞 State → IN_CALL")

            // Always start VPS audio polling (VPS is always the reliable fallback)
            Log.w(TAG, "DIAG: Starting VPS audio polling from handleCallAccepted...")
            startVpsAudioPolling()
            Log.w(TAG, "DIAG: VPS audio polling started from handleCallAccepted")
            Log.d(TAG, "📞 VPS audio polling started")

            startDurationTimer()
            startStatsUpdater()
            startKeepalive()

            Log.w(TAG, "DIAG: handleCallAccepted COMPLETE — all systems running (audio=${audioRecord != null}, audioTrack=${audioTrack != null}, video=${call?.isVideoCall}, videoEnabled=${videoCallManager.isVideoEnabled.value})")
            Log.d(TAG, "📞 Call accepted by peer (video=${call?.isVideoCall}) — all systems running")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to handle call accepted: ${e.message}", e)
            endCall(CallEndReason.ERROR)
        }
    }

    private fun handleCallRejected() {
        stopRingtoneAndRingback()
        endCall(CallEndReason.DECLINED)
    }

    private fun handleCallEnded() {
        stopRingtoneAndRingback()
        endCall(CallEndReason.PEER_ENDED)
    }

    /**
     * RX for callAnsweredElsewhere (0x0A). Body layout in [AnsweredElsewherePacket].
     *
     * The deviceId comparison is the point. The signal is addressed to our own public
     * key, so the VPS fan-out hands it back to the device that sent it; without the
     * guard that device ends the call it has just answered.
     * iOS VoiceCallManager.swift:7069-7072 / :6437-6440.
     */
    private fun handleAnsweredElsewhere(packet: ByteArray) {
        applyAnsweredElsewhere(
            senderDeviceId = AnsweredElsewherePacket.deviceIdOf(packet),
            signalCallId = AnsweredElsewherePacket.callIdOf(packet),
            source = "0x0A"
        )
    }

    /**
     * 📱 The one place a sibling's dismissal is honoured or refused, whichever
     * transport carried it — the sealed 0x0A packet (relay / cross-platform mesh) or
     * the `callAnsweredElsewhere` FCM push. The three guards live in
     * [AnsweredElsewherePacket.dismisses], pure and tested; only the logging and the
     * teardown are here, so the two legs cannot drift apart.
     */
    private fun applyAnsweredElsewhere(
        senderDeviceId: String?,
        signalCallId: String?,
        source: String
    ) {
        val ourCallId = _currentCall.value?.callId
        val ringing = _callState.value == CallState.RINGING
        if (!AnsweredElsewherePacket.dismisses(
                senderDeviceId = senderDeviceId,
                myDeviceId = FCMService.getDeviceId(context),
                isRinging = ringing,
                signalCallId = signalCallId,
                ourCallId = ourCallId
            )
        ) {
            Log.d(
                TAG,
                "📱 answeredElsewhere ($source) ignored — sender=${senderDeviceId?.take(8) ?: "unknown"} " +
                    "state=${_callState.value} theirCall=${signalCallId?.take(8) ?: "-"} ourCall=${ourCallId?.take(8) ?: "-"}"
            )
            return
        }

        Log.d(TAG, "📱 Call answered elsewhere ($source) by device ${senderDeviceId?.take(8) ?: "unknown"}")
        stopRingtoneAndRingback()
        endCall(CallEndReason.ANSWERED_ELSEWHERE)
    }

    /**
     * 📱 Push leg of the sibling dismissal. `FCMService` hands the push-borne
     * `callAnsweredElsewhere` to "any in-process call manager" over a package-local
     * broadcast (`FCMService.kt:818-824`) — and until now **nothing received it**, the
     * same dead-broadcast shape as `CALL_MISSED`. Verified: the only other match for
     * `CALL_ANSWERED_ELSEWHERE` in the tree was that one `sendBroadcast`.
     *
     * That left the push leg half-wired. It dismissed the ringer SERVICE
     * (`FCMService.kt:797-810`) but left this manager in `RINGING` with the in-app
     * incoming-call overlay still up — which is exactly the state a foregrounded app
     * is in, and the one case where the ringer service is not running at all.
     *
     * Registered from `init` and never unregistered: this is a `@Singleton` that lives
     * for the whole process, so there is no lifecycle to unwind.
     */
    private val answeredElsewherePushReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
            if (intent?.action != ACTION_CALL_ANSWERED_ELSEWHERE) return
            applyAnsweredElsewhere(
                senderDeviceId = intent.getStringExtra("answered_by"),
                signalCallId = intent.getStringExtra("call_id"),
                source = "push"
            )
        }
    }

    init {
        // __CALL_LOG_AT_REST_2026_09_23__ iOS writes `🔄 STATE CHANGE: a → b` from
        // `callState.didSet`; here the sixteen assignments are left alone and the flow is
        // observed instead. Unconfined: the line is taken on the assigning thread, before
        // a later value can conflate it away.
        scope.launch(Dispatchers.Unconfined) {
            var previous: CallState? = null
            _callState.collect { next ->
                val prev = previous
                previous = next
                if (prev != null && prev != next) {
                    CallDiag.urgent(TAG, "🔄 STATE CHANGE: $prev → $next")
                }
            }
        }
        try {
            val filter = android.content.IntentFilter(ACTION_CALL_ANSWERED_ELSEWHERE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    answeredElsewherePushReceiver, filter, Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(answeredElsewherePushReceiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "📱 answeredElsewhere push receiver not registered: ${e.message}")
        }
    }

    /**
     * Broadcast answeredElsewhere (0x0A) signal so other devices of this user dismiss the incoming call.
     * 📱 MULTI-DEVICE: Also fans out via push_service /signal-answered as backup so siblings
     * that haven't received the relay packet (Doze mode, app killed) still get dismissed.
     *
     * 🔧 FIX: this used to build a 9-byte PLAINTEXT `[0x0A][ts]` and send it to
     * `call.peerPublicKey` on the AUDIO channel — to the person on the other end of
     * the call, whose handler ends the call, on a channel iOS never reads 0x0A from.
     * It only ever reached nobody because every VPS receive path drops packets of
     * 9 bytes or fewer, so multi-device dismissal did not work at all.
     *
     * Now it mirrors iOS `VoiceCallManager.swift:7566-7571` + `:7683-7718`: the body
     * carries our deviceId and the callId, it is sealed with the call-signal HKDF
     * params, and recipient == sender == our OWN public key on the /signal channel.
     * The server relays it to every device registered under that key.
     */
    private fun sendAnsweredElsewhereSignal(call: CurrentCall) {
        val myKey = myPublicKey
        if (myKey.isEmpty()) {
            Log.w(TAG, "📱 answeredElsewhere skipped — no local public key yet")
        } else {
            val packet = AnsweredElsewherePacket.build(
                timestampMs = System.currentTimeMillis(),
                deviceId = FCMService.getDeviceId(context),
                callId = call.callId
            )
            scope.launch {
                // 📶 Mesh FIRST: a sibling on the same Wi-Fi gets the dismissal in
                // under 100 ms instead of waiting on a VPS round-trip, which is the
                // difference between "it stopped" and "it rang three more times".
                // iOS VoiceCallManager.swift:7588-7591.
                try {
                    val sealed = cryptoManager.encryptCallSignal(packet, myKey)
                    if (crossPlatformMesh.isPeerReachable(myKey)) {
                        crossPlatformMesh.sendCallSignal(myKey, sealed)
                        Log.d(TAG, "📱 answeredElsewhere broadcast via cross-platform mesh")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "📱 answeredElsewhere mesh broadcast failed (non-fatal): ${e.message}")
                }
                try {
                    // The signal is addressed to ourselves, so `type` must NOT resolve to
                    // a concrete CallSignalType on the sibling: CALL_REQUEST is the value
                    // that sends it through resolveSignalType, which reads the real type
                    // (0x0A) off byte 0 of the decrypted body. iOS ignores the JSON `type`
                    // entirely and does the same (VoiceCallManager.swift:4893-4930).
                    val signal = CallSignal(
                        callId = call.callId,
                        senderPublicKey = myKey,
                        type = CallSignalType.CALL_REQUEST,
                        payload = cryptoManager.encryptCallSignal(packet, myKey),
                        timestamp = System.currentTimeMillis()
                    )
                    vpsClient.sendCallSignal(myKey, signal)
                    Log.d(TAG, "📱 answeredElsewhere (0x0A) sent to own devices via /signal")
                } catch (e: Exception) {
                    Log.w(TAG, "📱 answeredElsewhere send failed (non-fatal): ${e.message}")
                }
            }
        }

        // Push-service redundant fanout
        scope.launch {
            try {
                val myPubKey = context.dataStore.data
                    .map { prefs -> prefs[stringPreferencesKey("public_key")] }
                    .first() ?: return@launch
                val deviceId = FCMService.getDeviceId(context)
                val json = JSONObject().apply {
                    put("identity", myPubKey)
                    put("callId", call.callId)
                    put("exceptDeviceId", deviceId)
                }
                val body = json.toString()
                    .toRequestBody("application/json".toMediaType())
                val req = okhttp3.Request.Builder()
                    .url("https://oshi-messenger.com/push/signal-answered")
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .build()
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    // __CALL_PUSH_AUTH_2026_09_23__ contract §3 row 6: identity = body `identity`.
                    .addInterceptor(com.oshi.messenger.network.auth.CallPushSigning.interceptor)
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    Log.d(TAG, "📱 push_service /signal-answered ack")
                } else {
                    Log.w(TAG, "push_service /signal-answered HTTP ${resp.code}")
                }
                resp.close()
            } catch (e: Exception) {
                Log.w(TAG, "push_service /signal-answered failed (non-fatal): ${e.message}")
            }
        }

        Log.d(TAG, "📞 Sent answeredElsewhere signal (0x0A)")
    }

    private var diagAudioTypeCount = 0L
    private var diagVideoTypeCount = 0L
    private var diagOtherTypeCount = 0L

    // WHY: DIAG — tracks last TX path emitted so DIAG_PATH_CHOSEN fires only on change.
    @Volatile private var diagLastTxPath: String = ""
    // WHY: DIAG — ms timestamp call went in-call, used for ageMsSinceCallStart in DIAG_ICE_RX.
    @Volatile private var diagCallStartedAtMs: Long = 0L
    // WHY: DIAG — flipped true when audioRecord.read returns >0, polled + cleared by DIAG_ENGINE.
    @Volatile private var diagMicReadFired: Boolean = false
    // WHY: DIAG — last P2P state string emitted; avoids duplicate DIAG_P2P_STATE.
    @Volatile private var diagLastP2PStateStr: String = ""
    // WHY: DIAG — decrypt failure log counter, rate-limited to 10 per call.
    @Volatile private var diagDecryptFailLogCount: Int = 0
    // WHY: DIAG_WS audio_rx counter (for rate-limited logging).
    private val diagWsAudioRxCount = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * AUTO-ACCEPT: if we are the OUTGOING caller and still RINGING, an authenticated media
     * frame from the peer means it has accepted (iOS starts media before its accept lands).
     * Only a frame that decrypted under this call's session key counts — see
     * [CallSignalGuards.mayAutoAcceptFromMedia].
     */
    private fun maybeAutoAcceptFromMedia(senderPublicKey: String) {
        val call = _currentCall.value
        if (!CallSignalGuards.mayAutoAcceptFromMedia(
                ringingOutgoing = _callState.value == CallState.RINGING && call?.isOutgoing == true,
                alreadyAutoAccepted = hasAutoAcceptedFromMedia,
                decryptedUnderSessionKey = true,
                senderKey = senderPublicKey,
                peerKey = call?.peerPublicKey,
            )) return
        hasAutoAcceptedFromMedia = true
        Log.w(TAG, "DIAG: AUTO-ACCEPT — authenticated media from peer while RINGING (outgoing), triggering handleCallAccepted")
        scope.launch {
            handleCallAccepted(null)
        }
    }

    /**
     * @param relayOrigin sender + callId stamped by the per-call UDP relay on the frame that
     *   carried [encryptedData]; null on every other transport. Only consulted for the legacy
     *   bare `0x04` hang-up (see [CallSignalGuards.mayEndFromBareMediaCallEnd]).
     */
    private fun handleAudioData(
        encryptedData: ByteArray,
        senderPublicKey: String,
        fromP2P: Boolean = false,
        relayOrigin: UDPRelayClient.RelayOrigin? = null,
    ) {
        // 🛟 SILENCE FIX (mirrors iOS 1.0.9): the previous unconditional
        // strict-P2P drop killed every mesh/VPS-WebSocket audio frame even
        // when P2P UDP hadn't (or couldn't) hole-punch — the common case for
        // calls between devices on different networks or behind symmetric
        // NAT, and the exact symptom that produced "iPad-only-hears" /
        // "no audio after 20s" on iOS until that side stopped dropping
        // non-P2P frames. Genuine duplicates (same packet via two
        // transports) are already filtered later in this function by
        // `rxDedupSeen[(packetType, sequenceNumber)]` so dropping by
        // transport here is redundant AND harmful when the peer is fanning
        // out audio across mesh + WS as a fallback for a stalled P2P leg.
        // Drop is removed entirely; the dedup below carries the load.
        diagIncomingPacketCount++
        // Track packet type distribution for debugging audioRcvd=0 issues
        if (encryptedData.isNotEmpty()) {
            when (encryptedData[0].toInt() and 0xFF) {
                0x05, 0x15, 0x16, 0x17, 0x18, 0x19 -> diagAudioTypeCount++  // 0x05 = AAC-ELD, 0x15 = raw PCM 48k, 0x16 = OshiCodec, 0x17 = raw PCM 16k
                0xF1 -> diagVideoTypeCount++
                else -> diagOtherTypeCount++
            }
        }
        if (diagIncomingPacketCount <= 10 || diagIncomingPacketCount % 50 == 0L) {
            val typeHex = if (encryptedData.isNotEmpty()) "0x%02X".format(encryptedData[0]) else "empty"
            Log.w(TAG, "DIAG: handleAudioData #$diagIncomingPacketCount — size=${encryptedData.size}, type=$typeHex, sender=${senderPublicKey.take(16)}, hasSessionKey=${sessionKey != null}, state=${_callState.value}, audioTypes=$diagAudioTypeCount, videoTypes=$diagVideoTypeCount, otherTypes=$diagOtherTypeCount")
        }

        // AUTO-ACCEPT: If we're the OUTGOING caller in RINGING state and receive media,
        // the peer has accepted the call. iOS sends accept via HTTP (polled every ~30s)
        // but starts sending video immediately — receiving media IS the implicit accept.
        // __AUTO_ACCEPT_AUTHENTICATED_2026_11_21__ no longer on the raw packet: see
        // [maybeAutoAcceptFromMedia], called once a frame has DECRYPTED under this call's key.

        sessionKey?.let { key ->
            try {
                // 🔧 iOS-COMPATIBLE FORMAT: type+seq are CLEARTEXT (outside encryption)
                // Wire format: [type(1)][seq(8)][payload]
                // Minimum 9 bytes for signal-only packets (0x04 callEnd, 0x0C videoPaused, 0x0D videoResumed)
                if (encryptedData.size < 9) {
                    Log.w(TAG, "DIAG: handleAudioData SKIP — packet too small: ${encryptedData.size} bytes")
                    return
                }

                // Read type byte from cleartext header
                val packetType = encryptedData[0]

                // 📊 Sequence high-water mark, parsed BEFORE the per-type early returns.
                // Every packet the peer emits (audio, keepalive 0x06, VUPG 0x0E/0x0F,
                // FEC 0xFE, video 0xF1) draws from ONE monotonic counter, so the gap
                // between successive arrivals is only a real loss signal if control
                // packets are counted too. Feeds the adaptive-bitrate controller in the
                // audio branch below — iOS drives its controller from real RX the same
                // way (VoiceCallManager.swift:239-385).
                //
                // WHITELIST, not "every type": 0x0B / 0x0C / 0x0D arrive as
                // `[type][timestamp BE8]` — on the plaintext audio channel AND (after
                // resolveSignalType decrypts them) on the signal channel. Bytes 1..8 of
                // those are a wall-clock timestamp (~1.7e12), not a counter, so feeding
                // them to the high-water mark would pin `rxPrevSeq` at an unreachable
                // value and silently kill loss detection for the rest of the call.
                //
                // 0x0A is the same shape ([type][timestamp BE8][…]) and belongs here
                // for the same reason.
                //
                // 0x06 keepalive is excluded HERE and counted inside its own branch, only
                // once it authenticates. The iPhone App Store build sends its keepalive
                // in CLEAR on the P2P media path as `[0x06][wall-clock ms BE8][…]` (21 B,
                // `createCallPacket`): the same timestamp shape as 0x0B/0x0C/0x0D, so it
                // pinned `rxPrevSeq` at ~1.7e12 and killed loss detection for every
                // Android ↔ iPhone P2P call (__KEEPALIVE_IS_NOT_AUDIO_2026_09_22__).
                // Android's own keepalive is sealed and its bytes 1..8 ARE the counter.
                val rxSeqCounted = when (packetType) {
                    KEYFRAME_REQUEST_TYPE, VIDEO_PAUSED_TYPE, VIDEO_RESUMED_TYPE,
                    ANSWERED_ELSEWHERE_TYPE, KEEPALIVE_TYPE -> false
                    else -> true
                }
                var rxGap = 0L
                if (rxSeqCounted) {
                    var rxSeq = 0L
                    for (i in 0..7) rxSeq = (rxSeq shl 8) or (encryptedData[1 + i].toLong() and 0xFF)
                    if (rxPrevSeq >= 0L && rxSeq > rxPrevSeq) rxGap = rxSeq - rxPrevSeq - 1L
                    if (rxSeq > rxPrevSeq) rxPrevSeq = rxSeq
                }

                // Handle keepalive packets (type 0x06) with RTT measurement.
                //
                // __KEEPALIVE_IS_NOT_AUDIO_2026_09_22__ (iOS): a keepalive is never audio.
                // This branch returns on every path — it never reaches the decrypt-fail
                // handler below, and nothing on Android injects PLC for a packet that
                // fails authentication (the catch at the end of this function only logs).
                //
                // Liveness: it still refreshes `lastAudioReceivedTime` BEFORE it
                // authenticates, deliberately. The iPhone App Store build's keepalive is
                // cleartext (it cannot authenticate) and it is the ONLY thing a MUTED
                // App Store iPhone sends on P2P — requiring authentication here would
                // hang up on it after 45 s. The current iOS build sends none on P2P; its
                // liveness while muted comes from P2P pongs (see the stats loop).
                if (packetType == KEEPALIVE_TYPE) {
                    lastAudioReceivedTime = System.currentTimeMillis()
                    // 🔧 FIX (audit C-6): this used to read `encryptedData[9..12]` as the
                    // remote ping sequence. Bytes 9–12 are the first four bytes of the
                    // AES-GCM NONCE (the session salt) — a constant per call, never equal
                    // to a pingSequence of 1,2,3… So `keepaliveSendTimes[…]` never hit,
                    // `_latencyMs` stayed 0 forever, and `adaptiveBitrate` — whose ONLY
                    // feed sites were inside this dead branch — never adapted.
                    //
                    // The plaintext is `[pingSeq BE4][stamp BE8]` (iOS
                    // VoiceCallManager.swift:15105-15109), so it must be DECRYPTED first.
                    val kaPayload = encryptedData.copyOfRange(9, encryptedData.size)
                    val kaPlain: ByteArray? = try {
                        cryptoManager.decryptAESGCM(kaPayload, key)
                    } catch (_: Exception) { null }
                    if (kaPlain != null && kaPlain.size >= 12) {
                        // Authenticated ⇒ bytes 1..8 are the peer's media counter: feed
                        // the loss high-water mark that the whitelist above skipped.
                        var kaSeq = 0L
                        for (i in 0..7) kaSeq = (kaSeq shl 8) or (encryptedData[1 + i].toLong() and 0xFF)
                        if (kaSeq > rxPrevSeq) rxPrevSeq = kaSeq
                        var remotePingSeq = 0
                        for (i in 0..3) remotePingSeq = (remotePingSeq shl 8) or (kaPlain[i].toInt() and 0xFF)
                        var stamp = 0L
                        for (i in 4..11) stamp = (stamp shl 8) or (kaPlain[i].toLong() and 0xFF)

                        val ourStamp = keepaliveSendTimes[remotePingSeq]
                        if (ourStamp != null && ourStamp == stamp) {
                            // Our own ping, echoed back by the peer → RTT is measurable.
                            // The entry is deliberately NOT removed: the 4-way fan-out can
                            // deliver several copies, and keeping it means every duplicate
                            // still matches here instead of being mistaken for a fresh peer
                            // ping and echoed again (which would be an infinite pong loop).
                            if (keepaliveAnswered.add(remotePingSeq)) {
                                val rtt = System.currentTimeMillis() - stamp
                                val oneWay = rtt / 2
                                _latencyMs.value = oneWay
                                adaptiveBitrate.reportLatency(oneWay.toDouble())
                                adaptiveBitrate.reportPacketReceived()
                                Log.d(TAG, "💓 Keepalive RTT=${rtt}ms (one-way ${oneWay}ms, seq=$remotePingSeq)")
                            }
                        } else {
                            // A ping from the PEER → echo it back verbatim so THEY can
                            // measure. Dedup so the fan-out's duplicates only pong once.
                            val echoKey = remotePingSeq.toLong() shl 32 xor (stamp and 0xFFFFFFFFL)
                            if (echoKey != lastEchoedPingKey) {
                                lastEchoedPingKey = echoKey
                                sendKeepalivePayload(remotePingSeq, stamp, isEcho = true)
                            }
                        }
                    }
                    return
                }

                // Legacy bare callEnd `[0x04][ts BE8]` on the media path (Android's
                // endCall "Path 1"). Cleartext and callId-less, so it is honoured ONLY when
                // the per-call UDP relay attests it came from this call's peer for this
                // callId (__BARE_CALLEND_ATTESTED_2026_11_21__). Otherwise it is dropped —
                // never handed to the decoders — and the hang-up arrives through the
                // sealed, callId-guarded signal or the sealed in-band 0x0D every sender
                // also emits.
                if (packetType == 0x04.toByte()) {
                    val call = _currentCall.value
                    val state = _callState.value
                    val accepted = CallSignalGuards.mayEndFromBareMediaCallEnd(
                        packetSize = encryptedData.size,
                        callLive = call != null && state != CallState.IDLE && state != CallState.ENDED,
                        attestedSender = relayOrigin?.sender,
                        attestedCallId = relayOrigin?.callId,
                        peerKey = call?.peerPublicKey,
                        activeCallId = call?.callId,
                    )
                    if (!accepted) {
                        CallDiag.w(
                            TAG,
                            "DIAG_BARE_CALLEND_DROPPED | size=${encryptedData.size} | relay=${relayOrigin != null} | " +
                                "frameCall=${relayOrigin?.callId?.take(8)} | call=${call?.callId?.take(8)} | state=$state"
                        )
                        return
                    }
                    Log.w(TAG, "📞 Received relay-attested bare callEnd (0x04) for the current call")
                    receivedRemoteEndSignal = true
                    handleCallEnded()
                    return
                }

                // 📹 Handle video toggle signals from remote (iOS compatible: 0x0C/0x0D)
                if (packetType == VIDEO_PAUSED_TYPE) {
                    lastAudioReceivedTime = System.currentTimeMillis()
                    _isRemoteCameraPaused.value = true
                    CallDiag.d(TAG, "📹 Remote paused video (0x0C)")
                    return
                }

                // 📞 In-band callEnd (0x0D WITH a sealed box) — hangup over the live
                // media path. Same byte as VIDEO_RESUMED_TYPE, separated by structure:
                // videoResumed is a bare [type][ts BE8] = exactly 9 bytes, whereas the
                // in-band callEnd carries [nonce(12)][ct][tag(16)] after the 9-byte
                // [type][seq] header (total ≥ 37). iOS sends this over P2P + relay +
                // mesh + WS so a zombie WebSocket can't strand us on a frozen call when
                // the signaling callEnd never arrives. iOS CALL_END_INBAND_TYPE=0x0D.
                if (packetType == CALL_END_INBAND_TYPE && encryptedData.size > 9) {
                    lastAudioReceivedTime = System.currentTimeMillis()
                    val endBox = encryptedData.copyOfRange(9, encryptedData.size)
                    val reasonPlain: ByteArray? = try {
                        cryptoManager.decryptAESGCM(endBox, key)
                    } catch (e: Exception) {
                        CallDiag.w(TAG, "DIAG_CALLEND_INBAND_RX_DECRYPT_FAIL | size=${endBox.size} | err=${e.message}")
                        return
                    }
                    val reasonStr = reasonPlain?.toString(Charsets.UTF_8) ?: ""
                    Log.w(TAG, "📞 In-band callEnd (0x0D) received — reason='$reasonStr'")
                    // Mark BEFORE tearing down so endCall's in-band fan-out does NOT
                    // re-broadcast our own callEnd back to the peer (echo guard), and so
                    // the sibling/answered-elsewhere paths know this end was remote.
                    // handleCallEnded → endCall(PEER_ENDED) is idempotent (isEndingCall
                    // guard), so duplicate fan-out copies of this packet are harmless.
                    receivedRemoteEndSignal = true
                    handleCallEnded()
                    return
                }

                if (packetType == VIDEO_RESUMED_TYPE) {
                    lastAudioReceivedTime = System.currentTimeMillis()
                    _isRemoteCameraPaused.value = false
                    CallDiag.d(TAG, "📹 Remote resumed video (0x0D)")
                    // Ask the PEER for a fresh IDR so we can resume decoding — a local
                    // sync frame would only help what WE transmit. iOS does the same on
                    // its foreground transition (VideoCallView.swift:244-262).
                    sendKeyframeRequest()
                    return
                }

                // 📹 requestKeyframe (0x0B) — the peer's decoder failed and is asking
                // US for an out-of-band IDR. iOS VoiceCallManager.swift:5302-5308 /
                // :6972-6979 post "ForceVideoKeyframe"; the Android equivalent is a
                // sync-frame request on our LOCAL encoder.
                if (packetType == KEYFRAME_REQUEST_TYPE) {
                    lastAudioReceivedTime = System.currentTimeMillis()
                    Log.d(TAG, "📹 Received requestKeyframe (0x0B) from peer — forcing local IDR")
                    // __VIDEO_ABR_2026_09_23__ rate-limited (~2/s) and fed to the video
                    // rate controller: it is the peer's only report about our uplink.
                    videoCallManager.onPeerKeyframeRequest()
                    return
                }

                // 📹 Handle video upgrade negotiation packets (0x0E) and stop-video (0x0F).
                // Both types share the same wire format as audio:
                //   [type(1)][seq(8 BE)][AES-GCM nonce(12)+ciphertext+tag(16)]
                // Plaintext is a single byte:
                //   0x01=request, 0x02=accept, 0x03=decline, 0x04=stop.
                if (packetType == VIDEO_UPGRADE_TYPE || packetType == VIDEO_STOP_TYPE) {
                    lastAudioReceivedTime = System.currentTimeMillis()
                    val payload = encryptedData.copyOfRange(9, encryptedData.size)
                    val plain: ByteArray = try {
                        cryptoManager.decryptAESGCM(payload, key)
                    } catch (e: Exception) {
                        CallDiag.w(TAG, "DIAG_VUPGRADE_RX_DECRYPT_FAIL | type=0x%02X | size=${payload.size} | err=${e.message}".format(packetType))
                        return
                    }
                    if (plain.isEmpty()) {
                        CallDiag.w(TAG, "DIAG_VUPGRADE_RX_EMPTY | type=0x%02X".format(packetType))
                        return
                    }
                    val code = plain[0]
                    val callForLog = _currentCall.value
                    Log.w(TAG, "DIAG_VUPGRADE_RX | type=0x%02X | code=0x%02X | peer=${callForLog?.peerName ?: "?"}".format(packetType, code))
                    when (code) {
                        VUPG_PAYLOAD_REQUEST -> {
                            // Guard against duplicate / stale REQUESTs received
                            // while we're already on video (e.g. a retransmit
                            // crossing the wire just after our own ACCEPT).
                            // Without this we'd keep a phantom dialog flag set
                            // that VideoCallScreen never shows but VoiceCallScreen
                            // would re-display if we later revert to voice.
                            if (callForLog?.isVideoCall == true) {
                                CallDiag.w(TAG, "DIAG_VUPGRADE_RX_REQUEST | ignored — already in video call")
                            } else {
                                _pendingVideoUpgradeFrom.value = callForLog?.peerName
                                _pendingVideoUpgrade.value = true
                                // Short haptic so the user notices the prompt.
                                try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        vibrator?.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
                                    } else {
                                        @Suppress("DEPRECATION") vibrator?.vibrate(150)
                                    }
                                } catch (_: Exception) {}
                                armCalleeVideoRequestTimeout()
                                Log.w(TAG, "DIAG_VUPGRADE_RX_REQUEST | from=${callForLog?.peerName} — UI banner ON")
                            }
                        }
                        VUPG_PAYLOAD_ACCEPT, VUPG_PAYLOAD_ACCEPT_WATCH -> {
                            // We are the REQUESTER. Peer accepted. If they accepted
                            // "watch only" (0x05) they are NOT sending their camera →
                            // show their "camera off" placeholder. We always share (we
                            // asked for video). iOS VoiceCallManager.swift:11605-11639.
                            //
                            // 🔧 FIX (audit D-8): 0x05 used to fall into the `else`
                            // branch and log DIAG_VUPGRADE_RX_UNKNOWN_CODE, so an iPhone
                            // tapping "Watch only" left Android hanging in
                            // _isAwaitingVideoUpgrade for the full 15 s timeout.
                            // __VUPG_UNSOLICITED_ACCEPT_2026_09_14__ N'honorer un
                            // ACCEPT que si l'on a VRAIMENT une demande en cours.
                            //
                            // Ce cas ne vérifiait rien: n'importe quel 0x02 allumait
                            // la caméra. Deux façons d'y arriver, toutes deux réelles:
                            // les deux personnes appuient sur Vidéo en même temps et
                            // l'une refuse — son ACCEPT arrive quand même et celle qui
                            // venait de REFUSER se met à diffuser; ou le délai de 15 s
                            // expire, on est prévenu « pas de réponse », et l'ACCEPT
                            // arrive en retard nous tirer dans un appel vidéo.
                            //
                            // Même règle que iOS. Les doublons de la retransmission
                            // tombent ici aussi, ce qui les rend inoffensifs.
                            if (!_isAwaitingVideoUpgrade.value) {
                                CallDiag.w(TAG, "DIAG_VUPGRADE_RX_ACCEPT_UNSOLICITED | ignoré: aucune demande en cours")
                                return
                            }
                            val peerWatchOnly = (code == VUPG_PAYLOAD_ACCEPT_WATCH)
                            videoUpgradeTimeoutJob?.cancel(); videoUpgradeTimeoutJob = null
                            calleeVideoRequestTimeoutJob?.cancel(); calleeVideoRequestTimeoutJob = null
                            _isAwaitingVideoUpgrade.value = false
                            _shareOwnVideo.value = true
                            _isRemoteCameraPaused.value = peerWatchOnly
                            val cur = _currentCall.value
                            if (cur != null && !cur.isVideoCall) {
                                OshiConnection.updateCallHasVideo(true)
                                _currentCall.value = cur.copy(isVideoCall = true)
                                try { startVideoForCall() } catch (e: Exception) {
                                    Log.e(TAG, "❌ Failed to start local video on peer accept: ${e.message}", e)
                                }
                            }
                            CallDiag.w(TAG, "DIAG_VUPGRADE_RX_ACCEPT | peerWatchOnly=$peerWatchOnly — starting local video")
                        }
                        VUPG_PAYLOAD_CAMERA_ON -> {
                            // Peer turned their camera on mid-video → clear the placeholder.
                            // iOS VoiceCallManager.swift:11640-11643.
                            _isRemoteCameraPaused.value = false
                            CallDiag.w(TAG, "DIAG_VUPGRADE_RX_CAMERA_ON | peer camera live")
                        }
                        VUPG_PAYLOAD_CAMERA_OFF -> {
                            // Peer turned their camera off but stays in the video call →
                            // show their "camera off" placeholder (call + our video continue).
                            // iOS VoiceCallManager.swift:11644-11648.
                            _isRemoteCameraPaused.value = true
                            CallDiag.w(TAG, "DIAG_VUPGRADE_RX_CAMERA_OFF | peer camera off (still watching)")
                        }
                        VUPG_PAYLOAD_DECLINE -> {
                            videoUpgradeTimeoutJob?.cancel(); videoUpgradeTimeoutJob = null
                            _isAwaitingVideoUpgrade.value = false
                            _videoUpgradeDeclined.value = "Peer declined"
                            CallDiag.w(TAG, "DIAG_VUPGRADE_RX_DECLINE | peer rejected upgrade")
                        }
                        VUPG_PAYLOAD_STOP -> {
                            // Either side asked to drop back to voice-only.
                            val cur = _currentCall.value
                            if (cur?.isVideoCall == true) {
                                try { videoCallManager.stopVideo() } catch (_: Exception) {}
                                OshiConnection.updateCallHasVideo(false)
                                _currentCall.value = cur.copy(isVideoCall = false)
                            }
                            _pendingVideoUpgrade.value = false
                            _pendingVideoUpgradeFrom.value = null
                            _isAwaitingVideoUpgrade.value = false
                            // Les deux drapeaux caméra manquaient ici aussi: iOS les
                            // remet, et sans ça un « regarder seulement » teinte la
                            // montée suivante.
                            _shareOwnVideo.value = true
                            _isRemoteCameraPaused.value = false
                            videoUpgradeTimeoutJob?.cancel(); videoUpgradeTimeoutJob = null
                            calleeVideoRequestTimeoutJob?.cancel(); calleeVideoRequestTimeoutJob = null
                            CallDiag.w(TAG, "DIAG_VUPGRADE_RX_STOP | dropped back to voice-only")
                        }
                        else -> CallDiag.w(TAG, "DIAG_VUPGRADE_RX_UNKNOWN_CODE | code=0x%02X".format(code))
                    }
                    return
                }

                // 📞 Handle answeredElsewhere signal (0x0A)
                // Reached only by an OLD peer that still shotguns 0x0A onto the media
                // channel; the current path is the signal channel (handleCallSignal →
                // handleAnsweredElsewhere). Guarded the same way either way — ending
                // the call unconditionally here is what made this packet dangerous.
                if (packetType == ANSWERED_ELSEWHERE_TYPE) {
                    lastAudioReceivedTime = System.currentTimeMillis()
                    handleAnsweredElsewhere(encryptedData)
                    return
                }

                // FEC parity (0xFE): swallowed, never processed (__FEC_PLAINTEXT_A0_2026_09_23__).
                // Only an older Android still sends it, and it is UNAUTHENTICATED cleartext:
                // feeding it to the recovery path would let anyone on path inject audio. It is
                // not counted as received audio either (a stream of forged 0xFE would otherwise
                // hold the no-audio watchdog off).
                if (packetType == 0xFE.toByte()) {
                    return
                }

                // 🔧 CROSS-PLATFORM: Auto-detect raw PCM audio from iOS (type 0x15)
                // If we receive type 0x15, the peer is sending raw PCM (cross-platform mode).
                // Set remotePlatform so we also send raw PCM back.
                if (packetType == RAW_PCM_AUDIO_TYPE && remotePlatform != "ios") {
                    remotePlatform = "ios"
                    Log.d(TAG, "🔊 CROSS-PLATFORM: Detected raw PCM audio (type 0x15) — switching to raw PCM mode")
                }

                // Read 8-byte sequence number from cleartext header (big-endian, matching sendAudio() BE write)
                // 🔧 FIX: Was reading LE but sendAudio() writes BE — mismatch caused jitter buffer
                // confusion for Android↔Android calls (seq numbers were byte-swapped).
                // iOS also reads BE (UInt64(bigEndian:)), so BE is correct for both paths.
                var sequenceNumber = 0L
                for (i in 0..7) {
                    sequenceNumber = (sequenceNumber shl 8) or (encryptedData[1 + i].toLong() and 0xFF)
                }

                // Dedup same (type,seq) arriving via both P2P and VPS within overlap window.
                val dedupKey: Long = ((packetType.toLong() and 0xFF) shl 56) or (sequenceNumber and 0x00FF_FFFF_FFFF_FFFFL)
                val nowMs = System.currentTimeMillis()
                val prior = rxDedupSeen[dedupKey]
                if (prior != null && nowMs - prior < RX_DEDUP_WINDOW_MS) {
                    return
                }
                rxDedupSeen[dedupKey] = nowMs
                if (rxDedupSeen.size > 512) {
                    val cutoff = nowMs - RX_DEDUP_WINDOW_MS
                    val it = rxDedupSeen.entries.iterator()
                    while (it.hasNext()) { if (it.next().value < cutoff) it.remove() }
                }

                // 🔧 iOS-COMPATIBLE: Video packets ARE encrypted with AES-256-GCM (matching iOS VideoCallManager)
                // iOS encrypts video: [nonce(12)][ciphertext][tag(16)]
                if (packetType == VIDEO_PACKET_TYPE) {
                    val encryptedVideoData = encryptedData.copyOfRange(9, encryptedData.size)
                    val rawVideoData = decryptVideoPacket(encryptedVideoData, key)
                    if (rawVideoData != null) {
                        maybeAutoAcceptFromMedia(senderPublicKey)
                        // __VUPG_SELF_HEAL_2026_09_14__ Recevoir des trames vidéo
                        // PROUVE que le pair est passé en vidéo. On le suit.
                        //
                        // C'est le rattrapage qui manquait. Quand un ACCEPT se perd,
                        // l'un des deux diffuse et l'autre reste sur l'écran audio:
                        // aucun accusé, aucune retransmission, aucune réconciliation
                        // ne pouvait les remettre d'accord — les trames arrivaient et
                        // partaient dans un gestionnaire éteint, indéfiniment. Ici la
                        // preuve arrive d'elle-même, soixante fois par seconde.
                        //
                        // On ouvre la vidéo en RÉCEPTION SEULE: on montre leur caméra,
                        // on n'allume pas la nôtre. Suivre le pair ne doit jamais
                        // vouloir dire diffuser sans qu'on l'ait demandé.
                        val healCall = _currentCall.value
                        if (healCall != null && !healCall.isVideoCall) {
                            CallDiag.w(TAG, "DIAG_VUPGRADE_SELF_HEAL | trames vidéo hors mode vidéo — bascule en réception seule")
                            videoUpgradeTimeoutJob?.cancel(); videoUpgradeTimeoutJob = null
                            calleeVideoRequestTimeoutJob?.cancel(); calleeVideoRequestTimeoutJob = null
                            _pendingVideoUpgrade.value = false
                            _pendingVideoUpgradeFrom.value = null
                            _isAwaitingVideoUpgrade.value = false
                            _videoUpgradeDeclined.value = null
                            _shareOwnVideo.value = false
                            _isRemoteCameraPaused.value = false
OshiConnection.updateCallHasVideo(true)
                            _currentCall.value = healCall.copy(isVideoCall = true)
                            try { startVideoForCall(startCapture = false) } catch (e: Exception) {
                                Log.e(TAG, "❌ Échec du démarrage vidéo au rattrapage: ${e.message}", e)
                            }
                        }
                        videoCallManager.receiveRawVideoData(rawVideoData)
                        videoPacketsReceived++
                        if (videoPacketsReceived <= 5 || videoPacketsReceived % 30 == 0L) {
                            Log.w(TAG, "DIAG: VIDEO PACKET RECEIVED #$videoPacketsReceived — raw=${rawVideoData.size}B, encrypted=${encryptedVideoData.size}B, videoEnabled=${videoCallManager.isVideoEnabled.value}")
                            Log.d(TAG, "📹 Video packet #$videoPacketsReceived received (${rawVideoData.size} bytes)")
                        }
                    } else {
                        Log.w(TAG, "DIAG: VIDEO DECRYPT FAILED — encryptedSize=${encryptedVideoData.size}, keySize=${key.size}")
                        Log.w(TAG, "📹 Video decryption failed (size=${encryptedVideoData.size})")
                    }
                    lastAudioReceivedTime = System.currentTimeMillis()
                    return
                }

                // Extract encrypted payload (everything after type+seq)
                val encryptedPayload = encryptedData.copyOfRange(9, encryptedData.size)

                if (audioPacketsReceived == 0L) {
                    Log.w(TAG, "DIAG: FIRST AUDIO PACKET — type=0x%02X, totalSize=${encryptedData.size}, payloadSize=${encryptedPayload.size}, seq=$sequenceNumber".format(packetType))
                }

                // WHY: DIAG — compute before decrypt so we log even when decrypt fails.
                val diagRxShouldLog = audioPacketsReceived <= 5L || audioPacketsReceived % 200L == 0L
                val diagRxPath = if (p2pCanCarryAudio()) "p2p" else if (vpsClient.isCallWebSocketConnected) "vps_ws_or_mesh" else "vps_http_or_mesh"

                // Decrypt ONLY the audio payload (video is already handled above without decryption)
                val decrypted: ByteArray = try {
                    cryptoManager.decryptAESGCM(encryptedPayload, key)
                } catch (de: Exception) {
                    // WHY: DIAG_DECRYPT_FAIL + DIAG_RX_AUDIO_FIRST(decryptOk=false) — same-shape failure logs as iOS.
                    if (diagRxShouldLog) {
                        CallDiag.w(TAG, "DIAG_RX_AUDIO_FIRST | path=$diagRxPath | typeByte=0x%02X | size=${encryptedData.size} | seq=$sequenceNumber | decryptOk=false | decryptedSize=0 | enqueuedToJitter=false".format(packetType))
                    }
                    if (diagDecryptFailLogCount < 10) {
                        diagDecryptFailLogCount++
                        val pktSalt = if (encryptedPayload.size >= 4) encryptedPayload.copyOfRange(0, 4) else ByteArray(0)
                        val pktSaltHex = pktSalt.joinToString("") { "%02x".format(it) }
                        // 🔐 The peer transmits under the salt WE expect on RX
                        // (rxNonceSalt), not the shared base — iOS VoiceCallManager.swift:11709
                        // applies the same `rxNonceSalt ?: sessionNonceSalt` fallback.
                        val expectedRxSalt = if (rxNonceSalt.size == 4) rxNonceSalt else sessionNonceSalt
                        val localSaltHex = expectedRxSalt.joinToString("") { "%02x".format(it) }
                        val saltMatch = pktSalt.contentEquals(expectedRxSalt)
                        CallDiag.w(TAG, "DIAG_DECRYPT_FAIL | reason=${de.javaClass.simpleName}:${de.message ?: "null"} | typeByte=0x%02X | size=${encryptedData.size} | seq=$sequenceNumber | keyPresent=true | saltMatch=$saltMatch | pktSalt=$pktSaltHex | localSalt=$localSaltHex".format(packetType))
                    }
                    throw de
                }
                maybeAutoAcceptFromMedia(senderPublicKey)

                // 📊 Feed the adaptive-bitrate controller from REAL received media.
                // Before this its ONLY feed sites were inside the broken keepalive-RTT
                // branch (audit C-6/C-7), so it never saw a single packet sample:
                // packetLossPercent was structurally 0.0 and `_bitrateLevel` sat at
                // MEDIUM for every call. `adjustLevel()` runs on every report, so with
                // 50 samples/s of real evidence the loop now actually moves.
                if (rxGap > 0L) {
                    repeat(rxGap.coerceAtMost(50L).toInt()) { adaptiveBitrate.reportPacketLost() }
                }
                adaptiveBitrate.reportPacketReceived()

                // Call v2 — OshiCodec path (0x16). No redundancy wrapper: decrypted bytes
                // are directly one or more OshiCodec frames. Decode to Int16 PCM and feed
                // the jitter buffer (same destination as every other codec path).
                if (packetType == OSHI_CODEC_AUDIO_TYPE) {
                    // Auto-upgrade: if the peer starts sending 0x16 frames, they clearly support
                    // OshiCodec. Flip useOshiCodec on and allocate encoder/decoder so our OUTGOING
                    // also switches to 0x16 instead of 780 kbps raw PCM that saturates TCP mesh.
                    if (!useOshiCodec) {
                        Log.w(TAG, "📞 Call v2: auto-upgrade — peer sent 0x16, enabling OshiCodec for our TX")
                        useOshiCodec = true
                    }
                    if (oshiEncoder == null) {
                        try {
                            oshiEncoder = OshiCodec(48000, 1).also { it.setBitrate(48000) }
                        } catch (t: Throwable) {
                            Log.w(TAG, "📞 Call v2 auto-upgrade: encoder init failed: ${t.message}")
                        }
                    }
                    if (oshiDecoder == null) {
                        try {
                            oshiDecoder = OshiCodec(48000, 1)
                        } catch (t: Throwable) {
                            Log.w(TAG, "📞 Call v2 auto-upgrade: decoder init failed: ${t.message}")
                        }
                    }
                    val decoder = oshiDecoder
                    if (decoder == null) {
                        if (diagDecryptFailLogCount < 10) {
                            diagDecryptFailLogCount++
                            Log.w(TAG, "📞 Call v2: received 0x16 but decoder is null — dropping packet. useOshiCodec=$useOshiCodec")
                        }
                        return
                    }
                    val pcmSamples = try {
                        decoder.decode(decrypted)
                    } catch (t: Throwable) {
                        Log.w(TAG, "📞 Call v2: OshiCodec.decode failed (size=${decrypted.size}): ${t.message}")
                        ShortArray(0)
                    }
                    if (pcmSamples.isEmpty()) {
                        return
                    }
                    // Convert ShortArray -> little-endian byte[] (PCM 16-bit LE format,
                    // matching AudioTrack + the jitter buffer's expectation).
                    val pcmBytes = ByteArray(pcmSamples.size * 2)
                    val bb = java.nio.ByteBuffer.wrap(pcmBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    bb.asShortBuffer().put(pcmSamples)
                    jitterBuffer.addPacket(sequenceNumber, pcmBytes)
                    fecReceiver.storeReceivedPacket(pcmBytes, sequenceNumber)
                    audioPacketsReceived++
                    if (audioPacketsReceived <= 3 || audioPacketsReceived % 100 == 0L) {
                        Log.w(TAG, "📞 Call v2: audio packet #$audioPacketsReceived via OshiCodec — seq=$sequenceNumber, wire=${decrypted.size}B → pcm=${pcmBytes.size}B")
                    }
                    if (diagRxShouldLog) {
                        CallDiag.w(TAG, "DIAG_RX_AUDIO_FIRST | path=$diagRxPath | typeByte=0x%02X | size=${encryptedData.size} | seq=$sequenceNumber | decryptOk=true | decryptedSize=${decrypted.size} | enqueuedToJitter=true".format(packetType))
                    }
                    lastAudioReceivedTime = System.currentTimeMillis()
                    restoreQualityAfterMedia()
                    return
                }

                // 16 kHz downsampled PCM path (0x17). Payload is raw Int16 LE at
                // 16 kHz — one 20 ms frame = 320 samples = 640 B on the wire. No
                // redundancy wrapper (same reasoning as 0x16: cross-platform MTU
                // safety). Upsample to 48 kHz with the biquad (zero-stuff + ×3
                // gain compensation) before enqueueing; the jitter buffer and
                // AudioTrack already expect 48 kHz Int16 LE.
                // __WB_ADPCM_CODEC_2026_09_23__ 0x18 — self-contained IMA-ADPCM frame at 16 kHz.
                // Only a build that decodes it sends it, so receiving one proves the peer
                // decodes it: flip our TX too (converges a split-brain negotiation). Does NOT
                // touch remotePlatform (iOS and Android both send 0x18).
                // __OPUS_CODEC_2026_09_23__ 0x19 — Opus with the previous frames repeated inside.
                // Frames recovered from that redundancy (or concealed by Opus PLC for a hole)
                // are filed under the seqs of the packets that were lost; see OpusCallDecoder.keyed.
                if (packetType == com.oshi.messenger.service.audio.OpusWire.WIRE_TYPE.toByte()) {
                    if (!useOpus && localSupportsOpus && remotePlatform != "android") {
                        useOpus = true
                        Log.w(TAG, "📞 Opus auto-upgrade — peer sent 0x19")
                    }
                    // __CALL_FLUIDITY_2026_11_21__ queued encoded (newest + its redundant
                    // copies) under the codec seq, decoded at playout. Was: decoded here, a hole
                    // filled with PLC on the spot, the real frames discarded when they turned up
                    // 20-60 ms later, and late frames dropped by a slot clock that never waited.
                    if (!com.oshi.messenger.service.audio.OpusPlayoutDecoder.file(decrypted, opusJitter, opusSeq)) return
                    opusRxActive = true
                    audioPacketsReceived++
                    if (audioPacketsReceived <= 3 || audioPacketsReceived % 100 == 0L) {
                        Log.w(TAG, "📞 Opus audio packet #$audioPacketsReceived — seq=$sequenceNumber, wire=${decrypted.size}B | ${opusJitter.stats()}")
                    }
                    if (diagRxShouldLog) {
                        CallDiag.w(TAG, "DIAG_RX_AUDIO_FIRST | path=$diagRxPath | typeByte=0x%02X | size=${encryptedData.size} | seq=$sequenceNumber | decryptOk=true | decryptedSize=${decrypted.size} | enqueuedToJitter=true".format(packetType))
                    }
                    lastAudioReceivedTime = System.currentTimeMillis()
                    restoreQualityAfterMedia()
                    return
                }

                if (packetType == com.oshi.messenger.service.audio.WbAdpcmCodec.WIRE_TYPE) {
                    if (!useWbAdpcm && localSupportsWbAdpcm && remotePlatform != "android") {
                        useWbAdpcm = true
                        Log.w(TAG, "📞 WB-ADPCM auto-upgrade — peer sent 0x18")
                    }
                    val pcmBytes = synchronized(wbRxLock) { com.oshi.messenger.service.audio.WbAdpcmCodec.decodeToPcm48(decrypted, wbRxResampler, wbRxPostFilter) }
                    if (pcmBytes.isEmpty()) return
                    jitterBuffer.addPacket(sequenceNumber, pcmBytes)
                    fecReceiver.storeReceivedPacket(pcmBytes, sequenceNumber)
                    audioPacketsReceived++
                    if (audioPacketsReceived <= 3 || audioPacketsReceived % 100 == 0L) {
                        Log.w(TAG, "📞 WB-ADPCM audio packet #$audioPacketsReceived — seq=$sequenceNumber, wire=${decrypted.size}B → pcm=${pcmBytes.size}B")
                    }
                    if (diagRxShouldLog) {
                        CallDiag.w(TAG, "DIAG_RX_AUDIO_FIRST | path=$diagRxPath | typeByte=0x%02X | size=${encryptedData.size} | seq=$sequenceNumber | decryptOk=true | decryptedSize=${decrypted.size} | enqueuedToJitter=true".format(packetType))
                    }
                    lastAudioReceivedTime = System.currentTimeMillis()
                    restoreQualityAfterMedia()
                    return
                }

                if (packetType == RAW_PCM_16K_AUDIO_TYPE) {
                    // Auto-upgrade blocked: the current biquad loses precision
                    // below the Int16 noise floor and produces crackling on real
                    // voice. Keep decode working (so we still hear stale-build
                    // peers) but do not flip our TX path over.
                    if (!localSupports16kHzPCM) {
                        Log.w(TAG, "📞 Received 0x17 but 16 kHz path is disabled in this build — decoding only, TX stays on 48 kHz raw PCM")
                    } else if (!use16kHzPCM) {
                        Log.w(TAG, "📞 Call v2: auto-upgrade — peer sent 0x17, enabling 16 kHz PCM for our TX")
                        use16kHzPCM = true
                    }
                    // Mirror the 0x15 platform-detection cue: 0x17 implies a
                    // cross-platform peer running the same build. Avoids silent
                    // audio if the outgoing-call path never confirmed platform.
                    if (remotePlatform != "ios") {
                        remotePlatform = "ios"
                        Log.d(TAG, "🔊 CROSS-PLATFORM: Detected 16 kHz PCM (0x17) — switching to raw-PCM mode")
                    }
                    val pcmBytes = upsample16to48(decrypted)
                    if (pcmBytes.isEmpty()) {
                        return
                    }
                    jitterBuffer.addPacket(sequenceNumber, pcmBytes)
                    fecReceiver.storeReceivedPacket(pcmBytes, sequenceNumber)
                    audioPacketsReceived++
                    if (audioPacketsReceived <= 3 || audioPacketsReceived % 100 == 0L) {
                        Log.w(TAG, "📞 Call v2: audio packet #$audioPacketsReceived via 16 kHz PCM — seq=$sequenceNumber, wire=${decrypted.size}B → pcm=${pcmBytes.size}B")
                    }
                    if (diagRxShouldLog) {
                        CallDiag.w(TAG, "DIAG_RX_AUDIO_FIRST | path=$diagRxPath | typeByte=0x%02X | size=${encryptedData.size} | seq=$sequenceNumber | decryptOk=true | decryptedSize=${decrypted.size} | enqueuedToJitter=true".format(packetType))
                    }
                    lastAudioReceivedTime = System.currentTimeMillis()
                    restoreQualityAfterMedia()
                    return
                }

                // 🔊 Inline redundancy: extract current frame (and recover previous if lost)
                // Format: [version(1)][...depends on version...]
                // 0x00: [0x00][current frame]
                // 0x01: [0x01][2 bytes prev length BE][prev frame][current frame]
                //
                // CRITICAL: cross-platform raw PCM (0x15) from iOS has NO version byte —
                // the payload is raw Int16 LE samples end to end. Reading byte 0 as a
                // "version" and stripping it when a quiet Int16 sample has LE byte 0 = 0x00
                // misaligns every subsequent sample by 1 byte and the played-back audio
                // sounds like electronic noise. Mirror the TX-side crossPlatformSafeMode gate.
                val rxCrossPlatformSafeMode = (packetType == RAW_PCM_AUDIO_TYPE) || (remotePlatform != "android") || useOshiCodec
                val currentFrameData: ByteArray
                if (rxCrossPlatformSafeMode) {
                    // Raw payload — no version byte, no redundancy wrapper.
                    currentFrameData = decrypted
                } else if (decrypted.isNotEmpty() && decrypted[0] == 0x01.toByte() && decrypted.size >= 3) {
                    // Version 1: has redundancy
                    val prevLen = ((decrypted[1].toInt() and 0xFF) shl 8) or (decrypted[2].toInt() and 0xFF)
                    if (prevLen > 0 && 3 + prevLen < decrypted.size) {
                        val prevFrameData = decrypted.copyOfRange(3, 3 + prevLen)
                        currentFrameData = decrypted.copyOfRange(3 + prevLen, decrypted.size)

                        // If previous packet was lost, inject the redundancy frame
                        val prevSeq = sequenceNumber - 1
                        if (prevSeq >= 0 && !jitterBuffer.hasPacket(prevSeq)) {
                            jitterBuffer.addPacket(prevSeq, prevFrameData)
                            Log.d(TAG, "Inline redundancy: recovered lost packet seq=$prevSeq from redundancy in seq=$sequenceNumber")
                        }
                    } else {
                        // Malformed redundancy header — treat entire payload as frame
                        currentFrameData = decrypted
                    }
                } else if (decrypted.isNotEmpty() && decrypted[0] == 0x00.toByte()) {
                    // Version 0: no redundancy, strip version byte
                    currentFrameData = decrypted.copyOfRange(1, decrypted.size)
                } else {
                    // Legacy packet (no version byte) — use as-is for backward compat
                    currentFrameData = decrypted
                }

                // 🔧 CROSS-PLATFORM: Decode audio here (not in playback loop) so jitter buffer
                // always contains PCM Int16, regardless of whether the packet was codec-compressed
                // (type 0x05) or raw PCM (type 0x15, cross-platform).
                val pcmForBuffer: ByteArray = if (packetType == RAW_PCM_AUDIO_TYPE) {
                    // Type 0x15: raw PCM Int16 from cross-platform peer — use directly
                    if (audioPacketsReceived == 0L) {
                        Log.d(TAG, "🔊 CROSS-PLATFORM: First raw PCM packet (0x15), ${currentFrameData.size} bytes")
                    }
                    currentFrameData
                } else if (useAacEld) {
                    val decoded = aacEldCodec?.decode(currentFrameData)
                    if (decoded != null) {
                        decoded
                    } else {
                        // AAC-ELD decode failed — might be cross-platform data before switch
                        Log.w(TAG, "🔊 AAC-ELD decode returned null (input ${currentFrameData.size} bytes) — using as raw PCM")
                        currentFrameData
                    }
                } else {
                    g726Fallback.decode(currentFrameData, PLAYBACK_SAMPLE_RATE)
                }

                jitterBuffer.addPacket(sequenceNumber, pcmForBuffer)
                fecReceiver.storeReceivedPacket(currentFrameData, sequenceNumber)
                audioPacketsReceived++

                if (audioPacketsReceived <= 3 || audioPacketsReceived % 100 == 0L) {
                    val typeDesc = if (packetType == RAW_PCM_AUDIO_TYPE) "RAW-PCM" else if (useAacEld) "AAC-ELD" else "G.726"
                    Log.w(TAG, "🔊 Audio packet #$audioPacketsReceived received: seq=$sequenceNumber, size=${decrypted.size} bytes, codec=$typeDesc, pcm=${pcmForBuffer.size} bytes")
                }

                // WHY: DIAG_RX_AUDIO_FIRST (success path) — tells us frames are entering jitter buffer.
                if (diagRxShouldLog) {
                    CallDiag.w(TAG, "DIAG_RX_AUDIO_FIRST | path=$diagRxPath | typeByte=0x%02X | size=${encryptedData.size} | seq=$sequenceNumber | decryptOk=true | decryptedSize=${decrypted.size} | enqueuedToJitter=true".format(packetType))
                }

                lastAudioReceivedTime = System.currentTimeMillis()
                restoreQualityAfterMedia()

            } catch (e: Exception) {
                Log.w(TAG, "DIAG: decrypt FAILED for packet #$diagIncomingPacketCount — type=0x%02X, size=${encryptedData.size}, error=${e.message}".format(if (encryptedData.isNotEmpty()) encryptedData[0] else 0))
                if (diagIncomingPacketCount <= 10) {
                    Log.w(TAG, "DIAG: decrypt detail — keySize=${key.size}, payloadSize=${encryptedData.size - 9}, first4payload=${if (encryptedData.size > 12) encryptedData.sliceArray(9..12).joinToString("") { "%02X".format(it) } else "N/A"}")
                }
            }
        }
        if (sessionKey == null && diagIncomingPacketCount <= 5) {
            Log.w(TAG, "DIAG: handleAudioData DROPPED — sessionKey is NULL, packet #$diagIncomingPacketCount")
        }
    }

    // ============================================================================
    // AUDIO
    // ============================================================================

    /**
     * Take voice-call audio focus, which is what makes Spotify / YouTube Music /
     * a podcast PAUSE.
     *
     * __CALL_AUDIO_FOCUS_AT_DIAL_2026_11_02__ This used to live inline in
     * `startAudio()`, and `startAudio()` runs when the call CONNECTS. So the
     * caller heard the dial tone mixed over whatever was already playing for
     * the entire ring — the same gap the iOS fix closes
     * (`CallAudioPolicy.shouldInterruptOtherAudio`). Requesting at the dial
     * closes it; requesting again from `startAudio` is harmless, and keeping
     * BOTH call sites means a path that reaches audio without dialling (an
     * answered incoming call) still has focus.
     *
     * AUDIOFOCUS_GAIN_TRANSIENT, not ..._MAY_DUCK, is deliberate: ducking would
     * leave the music playing quietly UNDER the call, which is what was
     * reported, not what anyone wants.
     *
     * The request object is retained because `abandonAudioFocusRequest` takes
     * the SAME instance — the bug `CallManager` documents at its own
     * `audioFocusRequest` field.
     */
    private fun requestVoiceCallAudioFocus(reason: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val existing = audioFocusRequest
                if (existing != null) {
                    // Already held: re-requesting the same focus is a no-op that
                    // can only churn the other app's transport state.
                    Log.d(TAG, "🔇 Audio focus already held ($reason)")
                    return
                }
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                val granted = audioManager?.requestAudioFocus(focusRequest)
                audioFocusRequest = focusRequest
                Log.d(TAG, "🔇 Audio focus requested ($reason) → $granted")
            } else {
                @Suppress("DEPRECATION")
                val granted = audioManager?.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
                Log.d(TAG, "🔇 Audio focus requested ($reason, legacy) → $granted")
            }
        } catch (e: Exception) {
            Log.w(TAG, "🔇 Audio focus request failed ($reason): ${e.message}")
        }
    }

    private fun startAudio() {
        Log.w(TAG, "DIAG: startAudio() ENTER — state=${_callState.value}, video=${_currentCall.value?.isVideoCall}")
        // Check RECORD_AUDIO permission before accessing microphone
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted — cannot start audio")
            Log.w(TAG, "DIAG: startAudio ABORT — no RECORD_AUDIO permission")
            return
        }

        try {
            // CRITICAL FIX: Set audio mode for voice communication
            // Equivalent to iOS AVAudioSession.setCategory(.playAndRecord, mode: .videoChat)
            // Without this, Android audio routing doesn't activate AEC/NS and audio is noise
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION

            // Request audio focus for voice communication
            requestVoiceCallAudioFocus("startAudio")

            // 🔧 FIX: Explicitly route audio to earpiece for voice calls (speaker for video).
            // Without this, some devices default to speaker in MODE_IN_COMMUNICATION,
            // causing "can't hear" when user holds phone to ear.
            val isVideoCall = _currentCall.value?.isVideoCall == true
            if (!isVideoCall) {
                audioManager?.isSpeakerphoneOn = false
                _isSpeakerOn.value = false
            } else {
                audioManager?.isSpeakerphoneOn = true
                _isSpeakerOn.value = true
            }

            Log.d(TAG, "Audio mode set to MODE_IN_COMMUNICATION, audio focus acquired, speaker=${audioManager?.isSpeakerphoneOn}")

            val captureBufferSize = AudioRecord.getMinBufferSize(
                CAPTURE_SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT
            ).let { if (it <= 0) 4096 else it * 2 }

            val playbackBufferSize = AudioTrack.getMinBufferSize(
                PLAYBACK_SAMPLE_RATE,
                CHANNEL_CONFIG_OUT,
                AUDIO_FORMAT
            ).let { if (it <= 0) 4096 else it * 2 }

            // AudioRecord for capture
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                CAPTURE_SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                captureBufferSize
            )

            // AudioTrack for playback
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(PLAYBACK_SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG_OUT)
                        .setEncoding(AUDIO_FORMAT)
                        .build()
                )
                .setBufferSizeInBytes(playbackBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            // DIAG: log actual AudioTrack buffer sizing. Expected frame size at 48 kHz PCM16
            // for 20 ms is 1920 bytes. playbackBufferSize should comfortably exceed that;
            // if buffer size ≫ frame size, write() may not pace per-frame and the defensive
            // guard in audioPlayJob kicks in.
            val minOut = AudioTrack.getMinBufferSize(PLAYBACK_SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)
            val expectedFrameBytes = PLAYBACK_SAMPLE_RATE * PACKET_DURATION_MS / 1000 * 2
            Log.d(
                TAG,
                "AudioTrack built: rate=$PLAYBACK_SAMPLE_RATE, bufferSize=$playbackBufferSize " +
                        "(min=$minOut, frame=${expectedFrameBytes}B, ratio=${if (expectedFrameBytes > 0) playbackBufferSize / expectedFrameBytes else -1}x frame)"
            )

            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord?.startRecording()
                Log.d(TAG, "AudioRecord started: rate=$CAPTURE_SAMPLE_RATE, bufferSize=$captureBufferSize")
            } else {
                Log.e(TAG, "AudioRecord not initialized at ${CAPTURE_SAMPLE_RATE}Hz — trying 48kHz fallback")
                // Fallback to 48kHz if device doesn't support 16kHz
                val fallbackRate = 48000
                val fallbackBuf = AudioRecord.getMinBufferSize(fallbackRate, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
                    .let { if (it <= 0) 4096 else it * 2 }
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    fallbackRate,
                    CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT,
                    fallbackBuf
                )
                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord?.startRecording()
                    Log.w(TAG, "AudioRecord fallback to 48kHz — audio quality may be degraded")
                } else {
                    Log.e(TAG, "AudioRecord failed even at 48kHz — no mic available")
                    audioRecord = null
                }
            }

            audioRecord?.let { attachCaptureEffects(it.audioSessionId) }

            if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack?.play()
            } else {
                Log.e(TAG, "AudioTrack not initialized — speaker may be unavailable")
                audioTrack = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize audio: ${e.message}")
            // Continue without audio rather than crashing the call
        }

        // 🔧 PRIMARY CODEC: AAC-ELD (matching iOS FaceTime codec)
        // Try AAC-ELD first — fall back to G.726 only if device doesn't support it
        Log.d(TAG, "🔊 Attempting AAC-ELD codec init (primary codec)...")
        try {
            aacEldCodec = AacEldCodec()
            useAacEld = aacEldCodec?.start() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "AAC-ELD codec init failed: ${e.message}")
            useAacEld = false
            aacEldCodec = null
        }
        if (useAacEld) {
            Log.d(TAG, "🔊 AAC-ELD codec started successfully (primary codec, iOS compatible)")
        } else {
            Log.w(TAG, "⚠️ AAC-ELD not available, falling back to G.726 (cross-platform audio WILL NOT work)")
            g726Fallback.reset()
        }

        // Reset jitter buffer and packet counters
        jitterBuffer.reset()
        resetOpusPlayout()
        qualityTracker.reset()
        qualityRealFrames = 0L
        qualityConcealedFrames = 0L
        playbackFramesWritten = 0L
        lastMicLevelAtMs = 0L
        lastRemoteLevelAtMs = 0L
        _micLevel.value = 0f
        _remoteLevel.value = 0f
        audioSequenceNumber = 0
        audioPacketsSent = 0
        txHealth.reset()
        audioPacketsReceived = 0
        txVoiceEvidence.reset()
        previousEncodedFrame = null
        lastAdaptiveBitrate = 64000
        videoPacketsReceived = 0
        diagIncomingPacketCount = 0
        diagAudioSendCount = 0
        diagAudioTypeCount = 0
        diagVideoTypeCount = 0
        diagOtherTypeCount = 0
        diagTxPcmCount = 0
        diagRxPcmCount = 0
        diagPbWindowStartMs = System.currentTimeMillis()
        diagPbRealPackets = 0
        diagPbConcealed = 0
        diagPbSilence = 0
        diagPbLoopIters = 0
        lastPacketStatsTime = System.currentTimeMillis()
        callVoiceEffectProcessor.reset()

        // WHY: DIAG_AUDIO_TRACK_CFG — one-shot config snapshot at call start so we
        // can correlate later TX/RX PCM stats with actual buffer sizing, rate, and
        // transfer mode. AudioRecord.bufferSizeInFrames is API 23+, AudioTrack
        // .bufferSizeInFrames is API 23+ — both available on our minSdk.
        try {
            val recState = audioRecord?.state ?: -1
            val trackState = audioTrack?.state ?: -1
            val trackBufFrames = try { audioTrack?.bufferSizeInFrames ?: -1 } catch (_: Exception) { -1 }
            val recBufFrames = try { audioRecord?.bufferSizeInFrames ?: -1 } catch (_: Exception) { -1 }
            val expectedFrame = PLAYBACK_SAMPLE_RATE * PACKET_DURATION_MS / 1000 * 2
            CallDiag.w(
                TAG,
                "DIAG_AUDIO_TRACK_CFG | trackBufferFrames=$trackBufFrames | recordBufferFrames=$recBufFrames | mode=STREAM | channelMask=${CHANNEL_CONFIG_OUT} | sampleRate=$PLAYBACK_SAMPLE_RATE | encoding=${AUDIO_FORMAT} | frameBytes20ms=$expectedFrame | recState=$recState | trackState=$trackState | codec=${if (useAacEld) "AAC-ELD" else "G.726"}"
            )
        } catch (_: Exception) { /* never block startAudio on a log */ }

        Log.w(TAG, "DIAG: startAudio() READY — audioRecord=${audioRecord?.state == AudioRecord.STATE_INITIALIZED}, audioTrack=${audioTrack?.state == AudioTrack.STATE_INITIALIZED}, codec=${if (useAacEld) "AAC-ELD" else "G.726"}, sessionKey=${sessionKey != null}, sessionKeySize=${sessionKey?.size ?: 0}")

        // Start capture and send loop
        // Use CONNECTING or IN_CALL state check so loops don't exit prematurely
        audioSendJob = scope.launch {
            val samplesPerPacket = CAPTURE_SAMPLE_RATE * PACKET_DURATION_MS / 1000
            val buffer = ByteArray(samplesPerPacket * 2)

            while (isActive && !audioStopping && _callState.value.let { it == CallState.IN_CALL || it == CallState.CONNECTING }) {
                // CRITICAL: Lock protects against stopAudio() releasing native AudioRecord
                // while read() is in progress (causes SIGABRT in libaudioclient.so)
                val read = synchronized(audioRecordLock) {
                    val record = audioRecord ?: return@synchronized -1
                    try {
                        record.read(buffer, 0, buffer.size)
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioRecord read failed: ${e.message}")
                        -1
                    }
                }

                if (read > 0 && !_isMuted.value) {
                    // WHY: DIAG — flips true on real mic data; polled + reset by DIAG_ENGINE every 2s.
                    diagMicReadFired = true
                    // __VOICE_METER_2026_09_22__ My level, on the raw mic frame (before
                    // the voice effect), at 20 Hz.
                    val meterNow = System.currentTimeMillis()
                    if (meterNow - lastMicLevelAtMs >= com.oshi.messenger.service.audio.VoiceMeter.PUBLISH_INTERVAL_MS) {
                        lastMicLevelAtMs = meterNow
                        val level = com.oshi.messenger.service.audio.VoiceMeter.levelOfInt16Le(buffer, read)
                        _micLevel.value = level
                        // __TX_VOICE_EVIDENCE_2026_10_29__ same level, also kept for the
                        // call rating. Here and nowhere else: this branch runs only for
                        // frames we are about to transmit, which is exactly the set the
                        // peer could have heard.
                        txVoiceEvidence.observe(level)
                    }
                    // WHY: DIAG_AUDIO_TX_PCM — every 100th captured packet, compute peak/rms/nonZeroPct
                    // on the raw mic Int16 buffer *before* it's encoded/sent. This tells us whether the
                    // mic is actually delivering voice-level energy or silence/DC. Stats are O(N≤4096)
                    // over the same buffer we're about to send — no extra allocations, no effect on path.
                    diagTxPcmCount++
                    if (diagTxPcmCount % 100L == 1L) {
                        val (peak, rms, nzPct) = pcmStats(buffer, read)
                        CallDiag.w(
                            TAG,
                            "DIAG_AUDIO_TX_PCM | n=$diagTxPcmCount | peak=$peak | rms=$rms | samples=${read / 2} | nonZeroPct=$nzPct | muted=false"
                        )
                    }
                    sendAudio(buffer.copyOf(read))
                } else if (read > 0) {
                    // Still reading, even if muted we know mic is alive.
                    diagMicReadFired = true
                    if (_micLevel.value != 0f) _micLevel.value = 0f
                    delay(5)
                } else {
                    delay(5)
                }
            }
        }

        // Start playback loop
        audioStopping = false
        audioPlayJob = scope.launch {
            val silenceSize = PLAYBACK_SAMPLE_RATE * PACKET_DURATION_MS / 1000 * 2 // PCM16 silence
            // RATE PACING — explicit wall-clock target.
            // We do NOT rely on AudioTrack.MODE_STREAM blocking write(): some
            // Samsung devices return write() much faster than the hardware drain
            // rate (large internal buffer, fast-mixer interaction), which lets
            // the loop run unbounded and the jitter buffer drains to constant
            // PLC concealment — that's the metallic / robotic signature users
            // are hearing. Drive the loop from a fixed-period schedule instead:
            // each iteration must take exactly PACKET_DURATION_MS (20 ms). If
            // we're running too fast, sleep the difference. If we're running
            // late (write took longer than 20 ms), don't sleep — try to catch up.
            val cycleNanos = PACKET_DURATION_MS * 1_000_000L  // 20 ms
            var nextDeadlineNs = System.nanoTime() + cycleNanos
            while (isActive && !audioStopping && _callState.value.let { it == CallState.IN_CALL || it == CallState.CONNECTING }) {
                val loopStartNs = System.nanoTime()
                // Bytes of PCM this iteration handed the speaker; paces the loop below.
                var writtenBytesThisIter = silenceSize
                // WHY: DIAG_PLAYBACK_RATE — count every loop iteration (real / concealed / silence)
                // so we can see if the coroutine is actually ticking at PACKETS_PER_SECOND.
                diagPbLoopIters++

                // Get packet from jitter buffer (codec-agnostic)
                // __CALL_FLUIDITY_2026_11_21__ Opus plays from the adaptive buffer: always
                // exactly 20 ms, real / concealed told by what it was made of.
                val fromOpus = opusRxActive
                val compressedData = if (fromOpus) {
                    val pcm = opusJitter.pull()
                    val b = ByteArray(pcm.size * 2)
                    for (i in pcm.indices) { val v = pcm[i].toInt(); b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() }
                    b
                } else jitterBuffer.getNextPacket()

                if (compressedData != null) {
                    // PLC fade gain: 1.0 for real packets, <1.0 for concealment frames
                    // consecutiveLosses is updated inside getNextPacket(), so read after
                    val fadeGain = if (fromOpus) {
                        when (opusJitter.kind()) {
                            com.oshi.messenger.service.audio.AdaptiveJitterBuffer.Kind.NORMAL,
                            com.oshi.messenger.service.audio.AdaptiveJitterBuffer.Kind.PRIMING -> 1.0f
                            else -> 0.5f
                        }
                    } else jitterBuffer.plcFadeGain()

                    // Classify real vs concealed by fade gain (1.0 = fresh packet, <1.0 = PLC repeat).
                    if (fadeGain >= 1.0f) diagPbRealPackets++ else diagPbConcealed++
                    // Cumulative, for the badge (__QUALITY_FROM_WHAT_IS_HEARD_2026_09_22__):
                    // the DIAG counters above are zeroed every log window.
                    if (fadeGain >= 1.0f) qualityRealFrames++ else qualityConcealedFrames++

                    // 🔧 CROSS-PLATFORM: Jitter buffer now stores decoded PCM Int16 directly
                    // (decode moved to handleAudioData for correct per-packet type handling).
                    // No codec decode needed here — data is already PCM Int16.
                    // Concealment frames: the linear fade-to-silence is now baked
                    // into generateConcealmentAudio() per sample, so we DO NOT apply
                    // an additional flat gain here (that would compound and produce
                    // the same metallic artifact we were trying to remove).
                    val pcmData: ByteArray? = compressedData

                    if (pcmData != null && pcmData.isNotEmpty()) {
                        // WHY: DIAG_AUDIO_RX_PCM — every 100th playback frame, O(N) PCM stats on the
                        // bytes we're about to push to AudioTrack. Distinguishes "decoded garbage" (peak
                        // pinned, rms high, nonZero near 100%) from "silence stream" (peak near 0) from
                        // "normal voice" (peak 5k–30k, rms 500–5k, nonZero 40–90%). fadeGain flags PLC
                        // domination — sustained real=false is a classic metallic-speech cause.
                        diagRxPcmCount++
                        if (diagRxPcmCount % 100L == 1L) {
                            val (peak, rms, nzPct) = pcmStats(pcmData, pcmData.size)
                            CallDiag.w(
                                TAG,
                                "DIAG_AUDIO_RX_PCM | n=$diagRxPcmCount | peak=$peak | rms=$rms | samples=${pcmData.size / 2} | nonZeroPct=$nzPct | fadeGain=${"%.2f".format(fadeGain)} | real=${fadeGain >= 1.0f}"
                            )
                        }
                        // CRITICAL: Lock protects against stopAudio() releasing native AudioTrack
                        // while write() is in progress (causes SIGSEGV in libaudioclient.so)
                        // __VOICE_METER_2026_09_22__ The peer's level, measured on real
                        // frames only (PLC is invented audio), published when this frame
                        // actually leaves the speaker: behind everything already queued.
                        if (fadeGain >= 1.0f) publishRemoteLevel(pcmData)
                        synchronized(audioTrackLock) {
                            val track = audioTrack ?: return@synchronized
                            try {
                                val n = track.write(pcmData, 0, pcmData.size)
                                if (n > 0) playbackFramesWritten += n / 2
                            } catch (e: Exception) {
                                Log.w(TAG, "AudioTrack write failed: ${e.message}")
                            }
                        }
                        writtenBytesThisIter = pcmData.size
                    }
                } else {
                    // PLC exhausted or buffer building — write silence to avoid audio glitches
                    diagPbSilence++
                    synchronized(audioTrackLock) {
                        val track = audioTrack ?: return@synchronized
                        try {
                            val n = track.write(ByteArray(silenceSize), 0, silenceSize)
                            if (n > 0) playbackFramesWritten += n / 2
                        } catch (e: Exception) {
                            Log.w(TAG, "AudioTrack silence write failed: ${e.message}")
                        }
                    }
                }

                // WHY: DIAG_PLAYBACK_RATE — once-per-second snapshot of pacing. Target at 50 pps is
                // real≈50, loopIters≈50, silence/concealed near 0 on a clean call. A metallic call
                // often shows concealed≫0 (PLC dominates), silence≫0 (jitter buffer starved), or
                // loopIters≫50 (hot loop with no blocking write() pacing).
                val nowPb = System.currentTimeMillis()
                if (nowPb - diagPbWindowStartMs >= 1000L) {
                    val windowMs = nowPb - diagPbWindowStartMs
                    val jumpsTotal = jitterBuffer.totalSlotJumps
                    CallDiag.w(
                        TAG,
                        "DIAG_PLAYBACK_RATE | windowMs=$windowMs | realPackets=$diagPbRealPackets | concealed=$diagPbConcealed | silence=$diagPbSilence | loopIters=$diagPbLoopIters | slotJumpsTotal=$jumpsTotal"
                    )
                    diagPbWindowStartMs = nowPb
                    diagPbRealPackets = 0
                    diagPbConcealed = 0
                    diagPbSilence = 0
                    diagPbLoopIters = 0
                }

                // Wall-clock pacing: sleep until the next 20 ms deadline. If
                // write+work already overran the deadline (write blocked, GC, etc),
                // skip the sleep and slide the deadline forward so we don't try to
                // catch up faster than the hardware can play, but we also don't
                // accumulate latency forever — clamp catch-up to ≤2 frames behind.
                //
                // The period is the duration of what was just WRITTEN, never less than
                // 20 ms. Every current build sends 20 ms frames, for which this is the
                // old fixed cycle; the iPhone App Store build sends ~32 ms frames at
                // ~30 pkt/s, and a fixed 20 ms loop asked for a frame 50 times a second
                // from a stream that delivers 30 — the difference came out as PLC and
                // silence written between real frames (see AudioJitterBuffer.frameMsFor).
                val iterNanos = maxOf(cycleNanos, writtenBytesThisIter.toLong() * 1_000_000L / 96L)
                nextDeadlineNs += iterNanos
                val nowNs = System.nanoTime()
                val sleepNs = nextDeadlineNs - nowNs
                if (sleepNs > 0) {
                    delay(sleepNs / 1_000_000)
                } else if (sleepNs < -2 * cycleNanos) {
                    // We're more than 2 frames behind — reset deadline to "now"
                    // rather than running the loop continuously to catch up.
                    nextDeadlineNs = nowNs + iterNanos
                }
            }
        }

        val codecName = if (useAacEld) "AAC-ELD (iOS compatible)" else "G.726 (fallback)"
        Log.w(TAG, "DIAG: startAudio() COMPLETE — audioSendJob=${audioSendJob != null}, audioPlayJob=${audioPlayJob != null}, codec=$codecName")
        Log.d(TAG, "🔊 Audio started with $codecName codec")

        // Start proximity sensor for earpiece/speaker switching
        startProximitySensor()
    }

    /**
     * An authenticated media packet just arrived: if the transport layer had put the
     * badge in RECONNECTING, media is back — show the sampled quality again. Replaces the
     * per-packet `_connectionQuality = jitterBuffer.networkQuality` that re-painted the
     * badge 50 times a second from a loss window (__QUALITY_FROM_WHAT_IS_HEARD_2026_09_22__).
     */
    private fun restoreQualityAfterMedia() {
        val cur = _connectionQuality.value
        if (cur == NetworkQuality.RECONNECTING || cur == NetworkQuality.DISCONNECTED) {
            _connectionQuality.value = qualityTracker.shown
        }
    }

    /**
     * __VOICE_METER_2026_09_22__ Measure the peer's level on a real frame about to be
     * written, and publish it after the AudioTrack's current backlog has played — the
     * moment the user actually hears it. Throttled to 20 Hz.
     */
    private fun publishRemoteLevel(pcm: ByteArray) {
        val now = System.currentTimeMillis()
        if (now - lastRemoteLevelAtMs < com.oshi.messenger.service.audio.VoiceMeter.PUBLISH_INTERVAL_MS) return
        lastRemoteLevelAtMs = now
        val level = com.oshi.messenger.service.audio.VoiceMeter.levelOfInt16Le(pcm)
        val head = try {
            (audioTrack?.playbackHeadPosition ?: 0).toLong() and 0xFFFFFFFFL
        } catch (_: Exception) { 0L }
        val queuedMs = ((playbackFramesWritten - head).coerceIn(0L, PLAYBACK_SAMPLE_RATE.toLong())) * 1000L /
            PLAYBACK_SAMPLE_RATE
        if (queuedMs <= 5L) {
            _remoteLevel.value = level
        } else {
            scope.launch {
                delay(queuedMs)
                if (_callState.value == CallState.IN_CALL) _remoteLevel.value = level
            }
        }
    }

    /** See [echoCanceler]. Every step guarded: an effect that will not bind costs nothing. */
    private fun attachCaptureEffects(sessionId: Int) {
        releaseCaptureEffects()
        try {
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                echoCanceler = android.media.audiofx.AcousticEchoCanceler.create(sessionId)?.also {
                    if (!it.enabled) it.enabled = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AEC attach failed: ${e.message}")
        }
        try {
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                noiseSuppressor = android.media.audiofx.NoiseSuppressor.create(sessionId)?.also {
                    if (!it.enabled) it.enabled = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "NS attach failed: ${e.message}")
        }
        CallDiag.w(TAG, "DIAG_CAPTURE_FX | aec=${echoCanceler?.enabled} | ns=${noiseSuppressor?.enabled} | session=$sessionId")
    }

    private fun releaseCaptureEffects() {
        try { echoCanceler?.release() } catch (_: Exception) { }
        try { noiseSuppressor?.release() } catch (_: Exception) { }
        echoCanceler = null
        noiseSuppressor = null
    }

    private fun stopAudio() {
        // Signal playback loop to exit BEFORE cancelling (volatile flag checked each iteration)
        audioStopping = true

        // Cancel coroutines
        audioSendJob?.cancel()
        audioPlayJob?.cancel()

        // Wait for playback job to actually finish (prevents SIGSEGV from concurrent write+release)
        runBlocking {
            try {
                withTimeout(500) { audioPlayJob?.join() }
            } catch (_: Exception) { /* timeout OK — loop should have exited via audioStopping flag */ }
            try {
                withTimeout(200) { audioSendJob?.join() }
            } catch (_: Exception) { }
        }

        // Now safe to release native resources — loops have exited
        // Use separate locks to match the capture/playback loops
        synchronized(audioRecordLock) {
            val record = audioRecord
            audioRecord = null
            try { record?.stop() } catch (_: Exception) { }
            releaseCaptureEffects()
            record?.release()
        }
        _micLevel.value = 0f
        _remoteLevel.value = 0f
        synchronized(audioTrackLock) {
            val track = audioTrack
            audioTrack = null
            try { track?.stop() } catch (_: Exception) { }
            track?.release()
        }

        // Stop Bluetooth SCO if active
        if (_isBluetoothOn.value) {
            try {
                audioManager?.stopBluetoothSco()
                audioManager?.isBluetoothScoOn = false
            } catch (_: Exception) { }
            _isBluetoothOn.value = false
        }

        // CRITICAL FIX: Reset audio mode back to normal
        audioManager?.mode = AudioManager.MODE_NORMAL

        // Abandon audio focus
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }

        audioManager?.isSpeakerphoneOn = false

        // Release AAC-ELD codec
        aacEldCodec?.release()
        aacEldCodec = null
        useAacEld = false

        vpsAudioReceiveJob?.cancel()
        vpsAudioReceiveJob = null
        keepaliveJob?.cancel()
        keepaliveJob = null

        // Stop proximity sensor
        stopProximitySensor()

        Log.d(TAG, "🔇 Audio stopped")
    }

    /**
     * Start polling VPS for incoming audio/video packets.
     * Matches iOS pollForAudio() behavior (~30ms interval).
     * Only used when connectionType == VPS.
     */
    /**
     * Smart HTTP polling — only polls when WebSocket is NOT active (matching iOS behavior).
     * When WebSocket is connected, audio/video arrives via the WS callback with zero overhead.
     * HTTP polling starts as a fallback after 1.5s or when WS disconnects.
     */
    private fun startVpsAudioPolling() {
        Log.w(TAG, "DIAG: startVpsAudioPolling() ENTER — state=${_callState.value}, video=${_currentCall.value?.isVideoCall}, wsConnected=${vpsClient.isCallWebSocketConnected}")
        vpsAudioReceiveJob?.cancel()

        val peerKey = _currentCall.value?.peerPublicKey ?: run {
            Log.e(TAG, "🔊 Cannot start VPS audio polling — no peer public key!")
            Log.w(TAG, "DIAG: startVpsAudioPolling ABORT — no peer key")
            return
        }

        // Wire WebSocket state callback for smart polling
        vpsClient.onWebSocketStateChanged = { connected ->
            if (connected) {
                Log.d(TAG, "🔌 WebSocket active — HTTP polling paused")
            } else {
                Log.d(TAG, "🔌 WebSocket disconnected — HTTP polling will resume")
            }
        }

        vpsAudioReceiveJob = scope.launch {
            // Defer polling for 1.5s to let WebSocket connect first (matching iOS)
            Log.w(TAG, "DIAG: VPS poll job started — deferring 1.5s for WS connect...")
            delay(1500)

            Log.w(TAG, "DIAG: VPS poll job ACTIVE — wsConnected=${vpsClient.isCallWebSocketConnected}, binaryWs=${vpsClient.serverSupportsBinary}, isVideo=${_currentCall.value?.isVideoCall}")
            Log.d(TAG, "🔊 Starting VPS audio polling (smart mode, peer=${peerKey.take(12)}...)")
            var pollCount = 0L
            var totalPacketsReceived = 0L
            var totalVideoPacketsReceived = 0L
            var lastLogTime = System.currentTimeMillis()

            while (isActive && _callState.value.let { it == CallState.IN_CALL || it == CallState.CONNECTING }) {
                // When WebSocket is connected, still do periodic HTTP polls for audio
                // iOS may send audio via HTTP POST when its WS is flaky, and VPS queues it
                if (vpsClient.isCallWebSocketConnected) {
                    // Poll HTTP every 2s even when WS is up — catches audio from iOS HTTP fallback
                    val shouldPollHttp = pollCount % 4 == 0L // every 2s (4 * 500ms)
                    if (pollCount == 0L) {
                        Log.w(TAG, "DIAG: VPS poll — WebSocket active, will also poll HTTP every 2s for audio fallback")
                    }
                    if (!shouldPollHttp) {
                        delay(500)
                        pollCount++
                        continue
                    }
                    // Fall through to HTTP poll below
                }

                try {
                    val packets = vpsClient.receiveCallAudioPackets()
                    // Received-media evidence for chooseAudioTxPrimary (HTTP shares the
                    // WS tier, as in iOS's `pathIsDelivering(.vpsHttp)` → `.ws` branch).
                    if (packets.isNotEmpty()) notePathRx(AudioTxPrimary.WS)
                    for (packet in packets) {
                        handleAudioData(packet, peerKey)
                    }
                    totalPacketsReceived += packets.size

                    // 🔧 FIX: Also poll for video packets (matching iOS pollForVideo)
                    if (_currentCall.value?.isVideoCall == true) {
                        val videoPackets = vpsClient.receiveCallVideoPackets()
                        for (packet in videoPackets) {
                            handleAudioData(packet, peerKey) // handleAudioData routes 0xF1 to video
                        }
                        totalVideoPacketsReceived += videoPackets.size
                        totalPacketsReceived += videoPackets.size
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastLogTime >= 3000) {
                        Log.w(TAG, "DIAG: VPS HTTP poll stats — totalAudio=$totalPacketsReceived, totalVideo=$totalVideoPacketsReceived, polls=$pollCount, wsUp=${vpsClient.isCallWebSocketConnected}, isVideo=${_currentCall.value?.isVideoCall}")
                        Log.d(TAG, "🔊 VPS HTTP poll: ${totalPacketsReceived} packets in ${pollCount} polls")
                        lastLogTime = now
                    }
                } catch (e: Exception) {
                    if (pollCount % 100 == 0L) {
                        Log.w(TAG, "DIAG: VPS audio poll error (poll #$pollCount): ${e.message}")
                        Log.w(TAG, "🔊 VPS audio poll error (poll #$pollCount): ${e.message}")
                    }
                }

                pollCount++
                delay(30) // ~30ms when actually polling
            }

            Log.w(TAG, "DIAG: VPS audio polling STOPPED — reason: state=${_callState.value}, pollCount=$pollCount, totalAudio=$totalPacketsReceived, totalVideo=$totalVideoPacketsReceived")
            Log.d(TAG, "🔊 VPS audio polling stopped after $pollCount polls ($totalPacketsReceived packets received)")
        }
    }

    /**
     * Send keepalive packets every 3 seconds during active call (matching iOS).
     * Format: [0x06][pingSequence:4 BE][timestamp:8]
     */
    private fun startKeepalive() {
        keepaliveJob?.cancel()
        pingSequence = 0
        keepaliveSendTimes.clear()
        keepaliveAnswered.clear()
        lastEchoedPingKey = Long.MIN_VALUE
        rxPrevSeq = -1L

        keepaliveJob = scope.launch {
            while (isActive && _callState.value.let { it == CallState.IN_CALL || it == CallState.CONNECTING }) {
                sendKeepalive()
                delay(3000)
            }
        }
    }

    private suspend fun sendKeepalive() {
        pingSequence++
        val sentAtMs = System.currentTimeMillis()
        keepaliveSendTimes[pingSequence] = sentAtMs
        // Clean old entries
        if (keepaliveSendTimes.size > 20) {
            val cutoff = pingSequence - 20
            keepaliveSendTimes.keys.filter { it < cutoff }.forEach { keepaliveSendTimes.remove(it) }
            keepaliveAnswered.removeAll { it < cutoff }
        }
        // Keepalive payload (iOS VoiceCallManager.swift:15105-15109):
        //   [pingSequence BE(4)][sentAt BE(8)]
        // The timestamp we ship is EXACTLY the one we recorded in keepaliveSendTimes,
        // which is what lets us recognise our own ping when the peer echoes it back.
        sendKeepalivePayload(pingSequence, sentAtMs, isEcho = false)
    }

    /**
     * Emit one `[0x06][seq BE8][AES-GCM(nonce|ct|tag)]` keepalive whose plaintext is
     * `[pingSeq BE4][stamp BE8]`.
     *
     * Used for both our own pings and for the PONG that echoes a peer's ping back
     * verbatim — the echo is what makes RTT measurable at all (see the RX handler in
     * [handleAudioData]). An iOS peer receiving an echo just resets its audio timer
     * (`VoiceCallManager.swift:6966`), so the pong is harmless cross-platform.
     */
    private fun sendKeepalivePayload(seqValue: Int, stampMs: Long, isEcho: Boolean) {
        val call = _currentCall.value ?: return
        val key = sessionKey ?: return

        val payload = ByteArray(4 + 8)
        payload[0] = (seqValue shr 24).toByte()
        payload[1] = (seqValue shr 16).toByte()
        payload[2] = (seqValue shr 8).toByte()
        payload[3] = seqValue.toByte()
        for (i in 0..7) {
            payload[4 + i] = (stampMs shr (56 - i * 8)).toByte()
        }

        try {
            // 🔐 CRYPTO FIX: consume a counter value instead of peeking at
            // `audioSequenceNumber + 1`. The old code encrypted under
            // `audioSequenceNumber + 1` WITHOUT incrementing, so the very next audio
            // frame — which increments to exactly that value — reused the same
            // (key, nonce) pair inside a single direction. Same class of break as the
            // cross-direction one fixed above; `sendVideoUpgradeSignal` already did
            // this correctly.
            val counter = nextAudioCounter()
            val encrypted = encryptAudioPacket(payload, key, counter)

            // Build outer packet: type(1) + seq(8 BE) + encrypted_payload
            val packet = ByteArray(1 + 8 + encrypted.size)
            packet[0] = 0x06  // CallPacketType.keepAlive
            for (i in 0..7) {
                packet[1 + i] = (counter shr (56 - i * 8)).toByte()
            }
            System.arraycopy(encrypted, 0, packet, 9, encrypted.size)

            when (_connectionType.value) {
                ConnectionType.MESH_DIRECT -> crossPlatformMesh.sendCallAudio(call.peerPublicKey, packet)
                else -> {
                    if (!vpsClient.sendCallAudioViaWebSocket(call.callId, packet, call.peerPublicKey)) {
                        scope.launch {
                            try { vpsClient.sendCallAudio(call.callId, packet, call.peerPublicKey) } catch (_: Exception) { }
                        }
                    }
                }
            }
            if (isEcho) Log.v(TAG, "💓 Keepalive PONG echoed (seq=$seqValue)")
        } catch (_: Exception) { }
    }

    /**
     * 🔧 iOS-COMPATIBLE audio encryption: nonce = salt(4) + counter_BE(8)
     * iOS uses this format for replay protection (extracts counter from nonce bytes 4-11).
     * Random nonces would cause iOS to reject packets as replays.
     */
    // MARK: - 48 ↔ 16 kHz resampling (biquad LPF @ ~7 kHz)
    //
    // Drops the 8-24 kHz band before 3:1 decimation so nothing aliases into
    // the 0-8 kHz output. One biquad is enough: ~24 dB/oct rolloff at 7 kHz
    // puts >30 dB attenuation at 8 kHz (the post-decimation Nyquist), pushing
    // surviving band-edge energy below the Int16 quantization floor.
    //
    // Transposed direct-form II with Q1.30 fixed-point coefficients. int32
    // state matches Swift's Int32, and the int64 multiply-accumulate stays
    // well inside Long range (coefficients < 2, x ≤ 2^15, so 2 * 2^15 * 2^30 ≈ 2^46 < 2^63).
    @Suppress("NOTHING_TO_INLINE")
    private inline fun biquadStep(x: Short, stateRef: IntArray, gain: Int = 1): Short {
        // stateRef[0] = z1, stateRef[1] = z2 (caller picks TX or RX state array).
        val xi = (x.toInt() * gain).toLong()
        // y = (b0*x + z1<<30) >> 30
        val yAcc = PCM16_COEF_B0.toLong() * xi + (stateRef[0].toLong() shl 30)
        var y = (yAcc shr 30).toInt()
        if (y >  32767) y =  32767
        if (y < -32768) y = -32768
        // z1_new = ((b1*x - a1*y) >> 30) + z2
        val z1Acc = PCM16_COEF_B1.toLong() * xi - PCM16_COEF_A1.toLong() * y.toLong()
        val newZ1 = (z1Acc shr 30).toInt() + stateRef[1]
        // z2_new = (b2*x - a2*y) >> 30
        val z2Acc = PCM16_COEF_B2.toLong() * xi - PCM16_COEF_A2.toLong() * y.toLong()
        val newZ2 = (z2Acc shr 30).toInt()
        stateRef[0] = newZ1
        stateRef[1] = newZ2
        return y.toShort()
    }

    /// Downsample 48 kHz Int16 LE → 16 kHz Int16 LE: biquad anti-alias then
    /// keep every 3rd filtered sample. TX only. In: N bytes, Out: N/6 bytes.
    private fun downsample48to16(pcm48: ByteArray): ByteArray {
        val sampleCount = pcm48.size / 2
        if (sampleCount < 3) return ByteArray(0)
        val outCount = sampleCount / 3
        val out = ByteArray(outCount * 2)
        val inBuf = java.nio.ByteBuffer.wrap(pcm48).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val outBuf = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val state = intArrayOf(pcm16TxZ1, pcm16TxZ2)
        var outIdx = 0
        for (i in 0 until sampleCount) {
            val y = biquadStep(inBuf.short, state)
            if (i % 3 == 2 && outIdx < outCount) {
                outBuf.putShort(y)
                outIdx++
            }
        }
        pcm16TxZ1 = state[0]; pcm16TxZ2 = state[1]
        return out
    }

    /// Upsample 16 kHz Int16 LE → 48 kHz Int16 LE: zero-stuff (insert 2 zeros
    /// between samples) then pass through the biquad with gain ×3 to restore
    /// the energy lost to the stuffed zeros. RX only.
    private fun upsample16to48(pcm16: ByteArray): ByteArray {
        val sampleCount = pcm16.size / 2
        if (sampleCount == 0) return ByteArray(0)
        val outCount = sampleCount * 3
        val out = ByteArray(outCount * 2)
        val inBuf = java.nio.ByteBuffer.wrap(pcm16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val outBuf = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val state = intArrayOf(pcm16RxZ1, pcm16RxZ2)
        for (i in 0 until sampleCount) {
            val s = inBuf.short
            outBuf.putShort(biquadStep(s,     state, gain = 3))
            outBuf.putShort(biquadStep(0,     state, gain = 3))
            outBuf.putShort(biquadStep(0,     state, gain = 3))
        }
        pcm16RxZ1 = state[0]; pcm16RxZ2 = state[1]
        return out
    }

    /**
     * Build the 12-byte audio nonce this device transmits under.
     * `[txSalt(4)][counter BE(8)]` — iOS VoiceCallManager.swift:12482-12491.
     * Extracted so the unit tests can assert the layout without a live call.
     */
    internal fun buildAudioNonce(counter: Long): ByteArray {
        // 🔐 CRYPTO FIX: per-DIRECTION salt (was `sessionNonceSalt`, identical on both
        // ends ⇒ both directions emitted the same nonces under the same key).
        // iOS VoiceCallManager.swift:12487 uses exactly this fallback expression.
        val salt = if (txNonceSalt.size == 4) txNonceSalt else sessionNonceSalt
        return com.oshi.messenger.service.audio.CallNonce.audioNonce(salt, counter)
    }

    private fun encryptAudioPacket(plaintext: ByteArray, key: ByteArray, counter: Long): ByteArray {
        // Build 12-byte nonce: txSalt(4) + counter_BE(8) — matching iOS encryptAudioData()
        val nonce = buildAudioNonce(counter)

        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = javax.crypto.spec.SecretKeySpec(key, "AES")
        val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, nonce)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
        val ciphertextWithTag = cipher.doFinal(plaintext)

        // Return: nonce(12) + ciphertext + tag(16) — same format as iOS
        return nonce + ciphertextWithTag
    }

    private suspend fun sendAudio(pcmData: ByteArray) {
        val call = _currentCall.value ?: return
        // Hard guard: drop outgoing frames whenever the mic is muted, regardless of caller path.
        if (_isMuted.value) return
        if (sessionKey == null) {
            // __CALL_TX_SILENT_DROP_2026_11_10__ was a silent no-op.
            txHealth.noKey()
            if (txHealth.droppedNoKey == 1L || txHealth.droppedNoKey % 250L == 0L) {
                CallDiag.w(TAG, "DIAG_TX_DROP | stage=noKey | n=${txHealth.droppedNoKey}")
            }
        }
        sessionKey?.let { key ->
            try {
                diagAudioSendCount++
                if (diagAudioSendCount <= 3) {
                    Log.w(TAG, "DIAG: sendAudio #$diagAudioSendCount — pcmSize=${pcmData.size}, codec=${if (useAacEld) "AAC-ELD" else "G.726"}, muted=${_isMuted.value}, ws=${vpsClient.isCallWebSocketConnected}")
                }
                // 🎤 Apply real-time voice effect before encoding (if active)
                val effectPcm = callVoiceEffectProcessor.process(pcmData, _currentVoiceEffect.value)

                // Call v2 — OshiCodec path. When negotiated AND the encoder is allocated,
                // compress via OshiCodec and send with type byte 0x16. If encode fails
                // for any reason, fall through to the raw-PCM path.
                var oshiEncoded: ByteArray? = null
                if (useOshiCodec) {
                    val encoder = oshiEncoder
                    if (encoder != null) {
                        try {
                            // effectPcm is 16-bit little-endian PCM. AudioRecord delivers
                            // native-endian, which on Android is always LE. Convert bytes
                            // to ShortArray in a single aligned copy for the codec.
                            val sampleCount = effectPcm.size / 2
                            val shortSamples = ShortArray(sampleCount)
                            val bb = java.nio.ByteBuffer.wrap(effectPcm).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            bb.asShortBuffer().get(shortSamples)
                            // OshiCodec expects exactly one 960-sample frame. The capture
                            // path already reads one 20 ms frame per tick; defensively
                            // truncate to the nearest whole frame when it disagrees.
                            val usable = (sampleCount / OshiCodec.FRAME_SIZE) * OshiCodec.FRAME_SIZE
                            if (usable >= OshiCodec.FRAME_SIZE) {
                                val framed = if (usable == sampleCount) shortSamples
                                             else shortSamples.copyOfRange(0, usable)
                                // Encode frame-by-frame so the wire format matches the
                                // encoder.encode(pcm = 960) contract.
                                val out = java.io.ByteArrayOutputStream(usable / OshiCodec.FRAME_SIZE * 120)
                                var frameOff = 0
                                while (frameOff < framed.size) {
                                    val frame = framed.copyOfRange(frameOff, frameOff + OshiCodec.FRAME_SIZE)
                                    out.write(encoder.encode(frame))
                                    frameOff += OshiCodec.FRAME_SIZE
                                }
                                oshiEncoded = out.toByteArray()
                            }
                        } catch (t: Throwable) {
                            if (audioPacketsSent < 5) {
                                Log.w(TAG, "📞 Call v2: OshiCodec.encode failed, falling back to raw PCM. ${t.message}")
                            }
                            oshiEncoded = null
                        }
                    }
                }

                // 🔧 CROSS-PLATFORM: Skip AAC-ELD when calling iOS OR when peer is UNKNOWN.
                // Apple AudioToolbox and Android MediaCodec produce incompatible AAC-ELD
                // bitstreams, so we must only use AAC-ELD when we're CERTAIN peer is Android.
                // Unknown platform → send raw PCM Int16 (universally decodable) — prevents
                // silent audio when outgoing-call platform detection missed the iOS peer.
                // Track whether we emitted 16 kHz-downsampled PCM so the type byte below
                // picks 0x17 instead of 0x15. Flipped only when use16kHzPCM is negotiated
                // AND we're on the cross-platform raw-PCM branch.
                var sent16kHzPCM = false
                var sentWbAdpcm = false
                var sentOpus = false
                val rawEncodedData: ByteArray = if (oshiEncoded != null) {
                    oshiEncoded
                } else if (remotePlatform != "android" && useOpus) {
                    // __OPUS_CODEC_2026_09_23__ peer advertised 0x20. 20 ms in ⇒ one payload out
                    // (the FIFO re-cuts any other size; a short read yields nothing yet).
                    val enc = opusTx ?: com.oshi.messenger.service.audio.OpusCallEncoder(32_000, 8).also { opusTx = it }
                    var failure: Throwable? = null
                    val payloads = try { enc.encodePcm48(effectPcm) } catch (t: Throwable) {
                        failure = t; emptyList()
                    }
                    // Two payloads only if the capture handed us > 20 ms: the newest one carries
                    // the older frame in its redundancy, so the receiver still gets both.
                    val last = payloads.lastOrNull()
                    if (last == null) {
                        // __CALL_TX_SILENT_DROP_2026_11_10__ a short read legitimately yields
                        // nothing yet; a FULL frame yielding nothing (or a throw) is a failing
                        // encoder, and it used to stay failing — silently — for the whole call.
                        if (failure != null || effectPcm.size >= com.oshi.messenger.service.audio.OpusWire.FRAME_SAMPLES * 2) {
                            when (txHealth.opusFailed(failure)) {
                                com.oshi.messenger.service.audio.TxHealth.Action.RESET_ENCODER -> {
                                    CallDiag.urgent(TAG, "DIAG_TX_DROP | stage=opus | action=resetEncoder | resets=${txHealth.opusResets} | err=${txHealth.lastError}")
                                    opusTx = null
                                }
                                com.oshi.messenger.service.audio.TxHealth.Action.DISABLE_OPUS -> {
                                    CallDiag.urgent(TAG, "DIAG_TX_DROP | stage=opus | action=fallback | err=${txHealth.lastError} — Opus off for this call (WB-ADPCM/PCM)")
                                    opusTx = null
                                    useOpus = false
                                }
                                else -> {}
                            }
                        }
                        return@let
                    }
                    txHealth.opusOk()
                    sentOpus = true
                    last
                } else if (remotePlatform != "android" && useWbAdpcm) {
                    // __WB_ADPCM_CODEC_2026_09_23__ peer advertised 0x10: 48→16 kHz FIR +
                    // IMA-ADPCM ⇒ 163 B instead of 1920 B raw PCM.
                    sentWbAdpcm = true
                    com.oshi.messenger.service.audio.WbAdpcmCodec.encodePcm48(effectPcm, wbTxResampler, wbTxState)
                } else if (remotePlatform != "android") {
                    // Cross-platform PCM. When both sides advertised RAW_PCM_16K_CAP_FLAG,
                    // downsample 48 → 16 kHz first — drops each 20 ms frame from 1920 B
                    // to 640 B (wire 256 kbps instead of 780 kbps, below MTU).
                    if (use16kHzPCM) {
                        val down = downsample48to16(effectPcm)
                        if (diagAudioSendCount <= 3) {
                            Log.d(TAG, "🔊 CROSS-PLATFORM: Sending 16 kHz PCM (peer=$remotePlatform, ${effectPcm.size} -> ${down.size} bytes)")
                        }
                        sent16kHzPCM = true
                        down
                    } else {
                        if (diagAudioSendCount <= 3) {
                            Log.d(TAG, "🔊 CROSS-PLATFORM: Sending raw PCM (peer=$remotePlatform, ${effectPcm.size} bytes, no codec)")
                        }
                        effectPcm  // Raw PCM Int16 — no codec
                    }
                } else if (useAacEld) {
                    val encoded = aacEldCodec?.encode(effectPcm)
                    if (encoded == null) {
                        Log.w(TAG, "🔊 AAC-ELD encode returned null (input ${effectPcm.size} bytes) — sending raw PCM")
                        effectPcm // Last-resort: send raw PCM so there's some audio
                    } else {
                        encoded
                    }
                } else {
                    g726Fallback.encode(effectPcm)
                }

                // Inline redundancy: prepend previous frame for packet loss recovery (iOS compatible)
                // Format: [0x01 version][2 bytes prev length BE][prev frame][current frame]
                // Cross-platform safe mode: doubling per-packet size to ~3880 B at 50 pps saturates
                // the CrossPlatformMesh TCP bridge → HoL blocking → iPhone's jitter buffer conceals
                // ~600 packets. Send single frames when peer is iOS.
                // Call v2 — OshiCodec already compresses to ≤120 B per frame; its frame header
                // carries a sequence byte, so wrapping with our redundancy scheme would require
                // re-decode + reorder on the receiver for little gain. Skip redundancy for 0x16.
                val crossPlatformSafeMode = (remotePlatform == "ios" || remotePlatform == "unknown") || (oshiEncoded != null) || sentWbAdpcm || sentOpus
                val prevFrame = previousEncodedFrame
                val compressedData: ByteArray = if (!crossPlatformSafeMode && prevFrame != null && prevFrame.size <= 0xFFFF) {
                    val result = ByteArray(1 + 2 + prevFrame.size + rawEncodedData.size)
                    result[0] = 0x01  // version 1 = has redundancy
                    result[1] = (prevFrame.size shr 8).toByte()  // prev length high byte
                    result[2] = (prevFrame.size and 0xFF).toByte()  // prev length low byte
                    System.arraycopy(prevFrame, 0, result, 3, prevFrame.size)
                    System.arraycopy(rawEncodedData, 0, result, 3 + prevFrame.size, rawEncodedData.size)
                    result
                } else if (!crossPlatformSafeMode) {
                    // No previous frame, same-platform — emit version 0x00 so the
                    // Android-↔-Android receiver strips a single header byte, same
                    // shape as the version-1 redundancy frame above.
                    val result = ByteArray(1 + rawEncodedData.size)
                    result[0] = 0x00  // version 0 = no redundancy
                    System.arraycopy(rawEncodedData, 0, result, 1, rawEncodedData.size)
                    result
                } else {
                    // CROSS-PLATFORM (peer = iOS or OshiCodec): NO version byte.
                    // iOS's `rxCrossPlatformSafeMode` gate skips the version-byte
                    // strip for 0x15/0x16/0x17 from non-iOS peers because the
                    // payload is supposed to be raw Int16 PCM end-to-end. If we
                    // prepend 0x00 here, iOS reads every Int16 sample misaligned
                    // by 1 byte → garbage values → noise on every received packet.
                    // Just send rawEncodedData unchanged.
                    rawEncodedData
                }
                previousEncodedFrame = rawEncodedData  // store for next packet

                // __FEC_PLAINTEXT_A0_2026_09_23__ (CALL_MEDIA_RELAY_AUTH_CONTRACT §1/§9 A0):
                // parity 0xFE is NO LONGER SENT. It was the XOR of 4 PRE-encryption codec
                // frames, sent in clear over P2P/TURN/UDP/WS/HTTP — content leaked to the relay
                // and to anyone on path. Disabled rather than sealed because it never bought
                // anything: the receiver files decoded PCM into its groups while the sender
                // XORed compressed frames, groups were keyed on different counters, and
                // nothing ever read a recovered frame (`tryRecover` has no caller). iOS turned
                // its FEC off for the same "no benefit" reason (VoiceCallManager.swift
                // `useFEC = false`); Desktop drops any non-0x15 frame. Loss is covered by the
                // inline redundancy (version byte 0x01) above and by PLC.

                // 🔧 iOS-COMPATIBLE FORMAT: type+seq OUTSIDE encryption, only audio data encrypted
                // iOS format on wire: [type(1)][seq(8)][encrypt(audio_data)]
                // Call v2 — OshiCodec frames use 0x16 when negotiated; fall back to the
                // cross-platform (0x15 = raw PCM) / same-platform (0x05 = AAC-ELD) matrix.
                val AUDIO_PACKET_TYPE: Byte = when {
                    oshiEncoded != null -> OSHI_CODEC_AUDIO_TYPE
                    sentOpus -> com.oshi.messenger.service.audio.OpusWire.WIRE_TYPE.toByte()
                    sentWbAdpcm -> com.oshi.messenger.service.audio.WbAdpcmCodec.WIRE_TYPE
                    sent16kHzPCM -> RAW_PCM_16K_AUDIO_TYPE
                    remotePlatform != "android" -> RAW_PCM_AUDIO_TYPE
                    else -> 0x05
                }

                // 🔧 iOS-COMPATIBLE: Increment first, then use SAME counter for nonce AND header seq
                // (Matching iOS incrementCallNonce() which increments before use)
                val currentCounter = nextAudioCounter()

                val encrypted = encryptAudioPacket(compressedData, key, currentCounter)

                // Build outer packet: type(1) + seq(8) + encrypted_payload
                val packet = ByteArray(1 + 8 + encrypted.size)
                packet[0] = AUDIO_PACKET_TYPE

                // 🔧 iOS writes seq as native LE (Data(bytes:&seq,count:8) on ARM)
                // but reads as BE (UInt64(bigEndian:)). Android writes/reads BE.
                // Since iOS reads BE correctly, Android writing BE is fine.
                val seq = currentCounter
                packet[1] = (seq shr 56).toByte()
                packet[2] = (seq shr 48).toByte()
                packet[3] = (seq shr 40).toByte()
                packet[4] = (seq shr 32).toByte()
                packet[5] = (seq shr 24).toByte()
                packet[6] = (seq shr 16).toByte()
                packet[7] = (seq shr 8).toByte()
                packet[8] = seq.toByte()

                System.arraycopy(encrypted, 0, packet, 9, encrypted.size)
                audioPacketsSent++

                if (audioPacketsSent <= 3 || audioPacketsSent % 100 == 0L) {
                    Log.d(TAG, "🔊 Audio packet #$audioPacketsSent sent (${packet.size} bytes, seq=$currentCounter, codec=${if (useAacEld) "AAC-ELD" else "G.726"}, ws=${vpsClient.isCallWebSocketConnected})")
                }
                logPacketStats()

                // WHY: DIAG_TX_AUDIO_FIRST + DIAG_PATH_CHOSEN — rate-limited so we can correlate with iOS RX logs.
                val diagShouldLog = audioPacketsSent <= 5L || audioPacketsSent % 200L == 0L
                val diagTypeByte = packet[0].toInt() and 0xFF
                val diagNonceHex = if (packet.size >= 17) {
                    packet.copyOfRange(9, 17).joinToString("") { "%02x".format(it) }
                } else ""

                // 🔧 FIX (audit B-1) — ONE nominated carrier, chosen on received-media
                // evidence, replacing the unconditional 4-way shotgun (P2P + UDP relay
                // :8089 + CrossPlatformMesh TCP + VPS WS/HTTP on EVERY frame). At 50 fps
                // × 1957 B raw PCM that shotgun offered up to ~3.1 Mbit/s of uplink on a
                // cross-platform call. iOS removed exactly this policy on 2026-07-27 with
                // the measurement in its own comment (VoiceCallManager.swift:10195-10201):
                // "2095 sent / 106 arrived … doubling the offered load on precisely the
                // link that could least afford it".
                //
                // The old comment claimed the fan-out was a symmetry fix. It was also a
                // fallback masking a broken primary: it completely hid the P2P black-hole
                // bug in p2pCanCarryAudio() (audit B-2), which is fixed in the same change.
                //
                // Mesh is GONE as a media carrier here — iOS deleted it for the same
                // measured reason (VoiceCallManager.swift:10261-10289): two co-located
                // phones ran a whole call over Bluetooth/LAN while the server logged zero
                // frames, which made every same-room transport measurement meaningless.
                // Mesh RX still works, and mesh SIGNALLING (offer/accept/end) is untouched.
                // Mirrors iOS sendAudioPacket (VoiceCallManager.swift:10164-10307).
                val useP2P = p2pCanCarryAudio()
                // Use the value cached at call start, NOT readForceRelayMode(): that is a
                // suspend DataStore flow collection and this runs 50×/s.
                // iOS reads UserDefaults here (VoiceCallManager.swift:10204), which is a
                // cheap in-memory lookup; the Android equivalent is this field, assigned
                // from the same toggle at :1391 / :1594 / :2368.
                val userPrefersRelay = p2pForceRelayActive
                val allowVPS = !useP2P || userPrefersRelay
                val relay = udpRelay
                val relayUsable = udpRelayUsable()
                val primary = chooseAudioTxPrimary(p2pHealthy = useP2P, relayUsable = relayUsable)
                if (txPrimaryCurrent != primary) {
                    val previous = txPrimaryCurrent?.name ?: "none"
                    val prevPrimary = txPrimaryCurrent
                    if (primary == AudioTxPrimary.P2P && prevPrimary != null && prevPrimary != AudioTxPrimary.P2P) {
                        txUpgradeOverlapUntil = System.currentTimeMillis() + com.oshi.messenger.service.p2p.P2PPathPolicy.UPGRADE_OVERLAP_MS
                        txUpgradeOverlapFrom = prevPrimary
                    }
                    txPrimaryCurrent = primary
                    txPrimarySelectedAt = System.currentTimeMillis()  // (re-)opens the mirror window
                    // Two different relay predicates on two different clocks: `relayUsable`
                    // is the control-plane one (lastServerReplyAt freshness); the branch
                    // that normally decides is the media-evidence one (lastRxAt inside the
                    // 2 s liveness window). iOS notes at :10224-10229 that reading them as
                    // contradictory cost it a debugging session.
                    val relayDelivering = pathIsDelivering(AudioTxPrimary.UDP_RELAY, System.currentTimeMillis())
                    CallDiag.w(TAG, "DIAG_TX_PRIMARY | $previous -> ${primary.name} | relayUsable=$relayUsable | relayDelivering=$relayDelivering | p2pHealthy=$useP2P | frameBytes=${packet.size}")
                }
                val duplicate = txDuplicationAllowed()
                // __RELAY_FIRST_UPGRADE_2026_09_23__ make-before-break after relay → direct:
                // compact frames also ride the path they came from for 1 s; the receiver keeps
                // the first copy of each (type, seq), so the upgrade has no gap.
                val overlapFrom = txUpgradeOverlapFrom
                val upgradeOverlap = useP2P && !allowVPS && overlapFrom != null &&
                    com.oshi.messenger.service.p2p.P2PPathPolicy.upgradeOverlapActive(
                        txUpgradeOverlapUntil, System.currentTimeMillis(), packet.size)

                val peerKey = _currentCall.value?.peerPublicKey
                val callId = _currentCall.value?.callId

                if (diagShouldLog) {
                    CallDiag.w(TAG, "DIAG_TX_AUDIO_FIRST | path=${primary.name} | dup=$duplicate | typeByte=0x%02X | size=${packet.size} | seq=$currentCounter | nonceHex=$diagNonceHex | remotePlatform=$remotePlatform | muted=${_isMuted.value}".format(diagTypeByte))
                }
                if (diagLastTxPath != primary.name) {
                    CallDiag.w(TAG, "DIAG_PATH_CHOSEN | path=${primary.name} | seq=$currentCounter | p2p=${p2p.state} | relayUsable=$relayUsable")
                    diagLastTxPath = primary.name
                }

                // One local helper so the audio frame and its FEC sibling take the
                // IDENTICAL route — the old code duplicated the fan-out and could drift.
                suspend fun dispatch(pkt: ByteArray) {
                    // P2P (direct or TURN) — carried when healthy, or when the blackout
                    // rotation inside chooseAudioTxPrimary is probing it. iOS :10239-10246.
                    if (useP2P || primary == AudioTxPrimary.P2P) {
                        try { p2p.sendPacket(pkt) } catch (_: Exception) { }
                    }
                    // UDP relay :8089 — primary, or backup inside the mirror window. iOS :10250-10259.
                    var relaySent = false
                    if (relayUsable && relay != null &&
                        ((allowVPS && (primary == AudioTxPrimary.UDP_RELAY || (duplicate && primary != AudioTxPrimary.P2P))) ||
                            (upgradeOverlap && overlapFrom == AudioTxPrimary.UDP_RELAY))
                    ) {
                        try { relay.sendAudio(pkt); relaySent = true } catch (_: Exception) { }
                    }
                    // CrossPlatformMesh TCP bridge — Android-only, and ONLY when the
                    // selector nominated it (i.e. nothing else is delivering). See the
                    // AudioTxPrimary.MESH doc comment for why this is not the old shotgun.
                    if (primary == AudioTxPrimary.MESH && peerKey != null) {
                        try { crossPlatformMesh.sendCallAudio(peerKey, pkt) } catch (_: Exception) { }
                    }
                    // VPS WebSocket / HTTP. The `!relaySent` clause is the safety net iOS
                    // documents at :10295-10298: the relay can be nominated on delivery
                    // evidence and then refuse to send, and without this the frame would
                    // be dropped on the floor with no carrier at all.
                    if (callId != null && peerKey != null &&
                        ((allowVPS && (primary == AudioTxPrimary.WS ||
                            (duplicate && primary != AudioTxPrimary.P2P && primary != AudioTxPrimary.MESH) ||
                            (primary == AudioTxPrimary.UDP_RELAY && !relaySent))) ||
                            (upgradeOverlap && !relaySent && overlapFrom != AudioTxPrimary.MESH))
                    ) {
                        try {
                            if (!vpsClient.sendCallAudioViaWebSocket(callId, pkt, peerKey)) {
                                vpsClient.sendCallAudio(callId, pkt, peerKey)
                            }
                        } catch (_: Exception) { }
                    }
                }

                dispatch(packet)
                // (No FEC 0xFE sibling any more — __FEC_PLAINTEXT_A0_2026_09_23__.)

            } catch (e: Exception) {
                // __CALL_TX_SILENT_DROP_2026_11_10__ still never fatal to the call, but no
                // longer invisible: counted for the rating and logged on the 1st/250th.
                txHealth.error(e)
                if (txHealth.droppedError == 1L || txHealth.droppedError % 250L == 0L) {
                    CallDiag.urgent(TAG, "DIAG_TX_DROP | stage=error | n=${txHealth.droppedError} | err=${txHealth.lastError}")
                }
            }
        }
    }

    /**
     * Apply gain to PCM16 audio data (for PLC fade-repeat).
     * Scales each 16-bit sample by the given gain factor.
     */
    private fun applyPcmGain(pcm: ByteArray, gain: Float): ByteArray {
        val output = ByteArray(pcm.size)
        for (i in pcm.indices step 2) {
            if (i + 1 < pcm.size) {
                val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
                val scaled = (sample * gain).toInt().coerceIn(-32768, 32767).toShort()
                output[i] = (scaled.toInt() and 0xFF).toByte()
                output[i + 1] = (scaled.toInt() shr 8).toByte()
            }
        }
        return output
    }

    // ============================================================================
    // RINGTONE & RINGBACK (matching CallManager / iOS)
    // ============================================================================

    private fun playRingtone() {
        Log.d(TAG, "🔔 Playing ringtone for incoming call...")
        try {
            ringtoneSound?.stop()
            ringtoneSound = CallSounds.startRingtone(context)

            // Vibrate pattern: vibrate 500ms, pause 500ms, repeat
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pattern = longArrayOf(0, 500, 500, 500, 500, 500)
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 500, 500, 500, 500, 500), 0)
            }

            Log.d(TAG, "🔔 Ringtone started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play ringtone: ${e.message}")
        }
    }

    /**
     * __OSHI_CALL_SOUNDS_2026_09_28__ "Connecting…" used to be silent. A soft pulse from the
     * dial until the ringback takes over, or the call connects / fails / ends.
     */
    private fun playConnectingSound() {
        stopOutgoingSound()
        connectingSoundStartedAt = android.os.SystemClock.elapsedRealtime()
        outgoingSound = CallSounds.startConnecting(context)
    }

    /**
     * Ringback once the connecting pulse has run [OUTGOING_CONNECTING_MS] — iOS's
     * `outgoingConnectingSeconds`: the callee's phone is still waking up from its push, so both
     * platforms keep "connecting" for the first 3 s instead of ringing into a sleeping phone.
     */
    private fun playRingbackTone() {
        ringbackStartJob?.cancel()
        val wait = OUTGOING_CONNECTING_MS - (android.os.SystemClock.elapsedRealtime() - connectingSoundStartedAt)
        ringbackStartJob = scope.launch {
            if (connectingSoundStartedAt > 0 && wait > 0) delay(wait)
            if (_callState.value != CallState.RINGING) return@launch
            Log.d(TAG, "📞 Playing ringback tone (waiting for answer)...")
            outgoingSound?.stop()
            outgoingSound = CallSounds.startRingback(context) { _callState.value == CallState.RINGING }
        }
    }

    private fun stopOutgoingSound() {
        ringbackStartJob?.cancel()
        ringbackStartJob = null
        outgoingSound?.stop()
        outgoingSound = null
        connectingSoundStartedAt = 0L
    }

    private fun stopRingtoneAndRingback() {
        ringtoneSound?.stop()
        ringtoneSound = null
        vibrator?.cancel()

        stopOutgoingSound()

        dismissIncomingCallRinger()
    }

    /**
     * 🔕 The ringer that actually makes noise when the app is not on screen is NOT the
     * one above. It lives in [IncomingCallService], a foreground service with its own
     * `Ringtone`, its own `Vibrator` and the ongoing full-screen notification;
     * `stopRingtoneAndRingback()` silences only this process's copies.
     *
     * Nothing in this class dismissed that service. The only dismissal in the tree was
     * the `callAnsweredElsewhere` FCM push (`FCMService.kt:797-810`), so a locked phone
     * kept ringing for the full 45 s of `IncomingCallService.RING_TIMEOUT_MS`:
     *  - after the CALLER hung up (`handleCallEnded` → `endCall(PEER_ENDED)`),
     *  - after a sibling device answered and the 0x0A arrived over the relay or the
     *    cross-platform mesh rather than over push — the two fast legs,
     *  - after the callee ring watchdog gave up (`CALLEE_RING_TIMEOUT_MS`),
     *  - and after the user answered or declined from the in-app overlay.
     *
     * `startService`, not `startForegroundService`: while the ringer is live the app is
     * in a foreground state and the start is allowed; when it is not live there is
     * nothing to dismiss, so the background-start refusal is the correct outcome and is
     * swallowed. Using `startForegroundService` here would instead CREATE the service
     * on a dismissal, and the `ACTION_DISMISS` arm never calls `startForeground`.
     */
    private fun dismissIncomingCallRinger() {
        try {
            val intent = android.content.Intent(context, IncomingCallService::class.java).apply {
                action = IncomingCallService.ACTION_DISMISS
                _currentCall.value?.callId?.takeIf { it.isNotEmpty() }?.let {
                    putExtra(IncomingCallService.EXTRA_CALL_ID, it)
                }
            }
            context.startService(intent)
        } catch (e: Exception) {
            // Not running (nothing to dismiss) or background-start refused. Both benign.
            Log.d(TAG, "🔕 IncomingCallService dismiss skipped: ${e.message}")
        }
    }

    private fun playConnectionLossBeep() {
        Log.d(TAG, "⚠️ Playing connection loss beep")
        try {
            val toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 200)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(100)
            }

            scope.launch {
                delay(300)
                toneGenerator.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play connection loss beep: ${e.message}")
        }
    }

    // ============================================================================
    // CONTROLS
    // ============================================================================

    /**
     * __TX_VOICE_EVIDENCE_2026_10_29__ What this side knows about the call that just
     * ended, for the rating. See [CallRatingManager.Evidence].
     */
    private fun callRatingEvidence(): CallRatingManager.Evidence {
        val voice = txVoiceEvidence.snapshot()
        return CallRatingManager.Evidence(
            txFrames = audioPacketsSent,
            rxFrames = audioPacketsReceived,
            txVoicePercent = voice?.voicePercent,
            txPeakPercent = voice?.peakPercent,
            codec = negotiatedCodecName(),
            peerPlatform = remotePlatform,
            txDropped = txHealth.dropped,
            txDropStage = when {
                txHealth.dropped == 0L -> null
                txHealth.droppedEncode >= txHealth.droppedError && txHealth.droppedEncode >= txHealth.droppedNoKey -> "encode"
                txHealth.droppedError >= txHealth.droppedNoKey -> "error"
                else -> "noKey"
            },
            txError = txHealth.lastError,
            opusFallback = txHealth.opusDisabled
        )
    }

    /**
     * The codec the TX branch of [sendAudio] actually used — same conditions, same order.
     * Reporting what was negotiated rather than what was sent would name a codec the peer
     * never received a byte of.
     */
    private fun negotiatedCodecName(): String = when {
        useOshiCodec && oshiEncoder != null -> "oshi"
        remotePlatform != "android" && useOpus -> "opus"
        remotePlatform != "android" && useWbAdpcm -> "wbadpcm"
        remotePlatform != "android" && use16kHzPCM -> "pcm16k"
        remotePlatform != "android" -> "pcm48k"
        useAacEld -> "aaceld"
        else -> "g726"
    }

    fun toggleMute() {
        _isMuted.value = !_isMuted.value
        Log.d(TAG, "🎤 Mute: ${_isMuted.value}")
    }

    fun toggleSpeaker() {
        _isSpeakerOn.value = !_isSpeakerOn.value
        audioManager?.isSpeakerphoneOn = _isSpeakerOn.value
        Log.d(TAG, "🔊 Speaker: ${_isSpeakerOn.value}")
    }

    fun toggleBluetooth() {
        _isBluetoothOn.value = !_isBluetoothOn.value
        try {
            if (_isBluetoothOn.value) {
                audioManager?.startBluetoothSco()
                audioManager?.isBluetoothScoOn = true
            } else {
                audioManager?.stopBluetoothSco()
                audioManager?.isBluetoothScoOn = false
            }
            Log.d(TAG, "Bluetooth SCO: ${_isBluetoothOn.value}")
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth toggle failed: ${e.message}")
            _isBluetoothOn.value = false
        }
    }

    // ============================================================================
    // VIDEO CONTROLS
    // ============================================================================

    // Track packet counts for debugging
    private var audioPacketsSent = 0L
    private var audioPacketsReceived = 0L
    private var videoPacketsSent = 0L
    private var videoPacketsReceived = 0L
    private var lastPacketStatsTime = 0L

    // DIAG: Counters for first-N diagnostic logging
    private var diagSignalCount = 0
    private var diagIncomingPacketCount = 0L
    private var diagAudioSendCount = 0L

    // DIAG_AUDIO_*: rate-limited PCM-level diagnostics. Counters are modulo-N based
    // (every 100th packet) for TX/RX PCM stats; the playback-rate stats use wall-clock.
    private var diagTxPcmCount = 0L
    private var diagRxPcmCount = 0L
    // Playback-rate accumulators (window reset every ~1000ms).
    private var diagPbWindowStartMs: Long = 0L
    private var diagPbRealPackets: Long = 0L
    private var diagPbConcealed: Long = 0L
    private var diagPbSilence: Long = 0L
    private var diagPbLoopIters: Long = 0L
    // Slot-jump counter lives on the jitter buffer itself — we read
    // jitterBuffer.totalSlotJumps at DIAG_PLAYBACK_RATE emission time.

    /**
     * O(N) PCM16 stats over a byte buffer (little-endian Int16 samples). Caps the
     * scan at 4096 samples even if buffer is larger so it stays cheap in the hot
     * path. Returns Triple(peak, rms, nonZeroPct) — peak is max |sample|, rms is
     * sqrt(mean(sample²)) in Int16 units, nonZeroPct is % of samples with |v|>100.
     * Read-only — never mutates the buffer.
     */
    private fun pcmStats(buf: ByteArray, lenBytes: Int): Triple<Int, Int, Int> {
        val totalSamples = (lenBytes / 2).coerceAtLeast(0)
        if (totalSamples == 0) return Triple(0, 0, 0)
        val n = totalSamples.coerceAtMost(4096)
        // Sub-sample stride when buffer > 4096 samples so we cover the full frame cheaply.
        val stride = (totalSamples / n).coerceAtLeast(1)
        var peak = 0
        var sumSq = 0.0
        var nonZero = 0
        var sampled = 0
        var i = 0
        while (sampled < n && i + 1 < lenBytes) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()      // sign-extend
            val v = (hi shl 8) or lo
            val av = if (v < 0) -v else v
            if (av > peak) peak = av
            sumSq += (v.toDouble() * v.toDouble())
            if (av > 100) nonZero++
            sampled++
            i += 2 * stride
        }
        val rms = kotlin.math.sqrt(sumSq / sampled).toInt()
        val nonZeroPct = (nonZero * 100) / sampled
        return Triple(peak, rms, nonZeroPct)
    }

    private fun logPacketStats() {
        val now = System.currentTimeMillis()
        if (now - lastPacketStatsTime >= 5000) {
            Log.d(TAG, "📊 STATS: audio sent=$audioPacketsSent rcvd=$audioPacketsReceived | video sent=$videoPacketsSent rcvd=$videoPacketsReceived | codec=${if (useAacEld) "AAC-ELD" else "G.726"}")
            lastPacketStatsTime = now
        }
    }

    /**
     * Initialize and start video for the current call.
     * Sets session key, wires the raw video packet callback, and starts capture/encode/decode.
     */
    private fun startVideoForCall(startCapture: Boolean = true) {
        Log.w(TAG, "DIAG: VIDEO START TRIGGER — startVideoForCall(startCapture=$startCapture) called, state=${_callState.value}, sessionKey=${sessionKey != null}")
        val key = sessionKey ?: run {
            Log.e(TAG, "📹 Cannot start video — no session key!")
            CallDiag.file("📹 Cannot start video — no session key!")
            Log.w(TAG, "DIAG: VIDEO START ABORT — no session key!")
            return
        }
        // 🎥 Re-arm on the IO scope AFTER the previous call's video teardown has
        // fully finished. `endCall` tears video down in [videoTeardownJob], a
        // coroutine that outlives the hang-up (closing the camera + stopping two
        // MediaCodecs is slow). On a fast second/third call it could otherwise land
        // AFTER this re-arm and flip `_isVideoEnabled` back to false and null the
        // raw-video callback — the exact "no video on the 2nd call, audio fine" bug
        // (inbound frames dropped by the RX gate, our frames never reaching the TX
        // callback). Joining it first guarantees the subsystem is fully down, so the
        // start below always takes VideoStartGate's clean cold-start path — the same
        // guarantee audio gets by recreating AudioRecord/AudioTrack every call.
        val pendingTeardown = videoTeardownJob
        scope.launch {
            try {
                if (pendingTeardown?.isActive == true) {
                    Log.w(TAG, "DIAG: VIDEO START — awaiting prior call's video teardown before re-arming...")
                    pendingTeardown.join()
                }

                videoCallManager.setSessionKey(key)
                videoSequenceNumber = 0
                videoPacketsSent = 0
                // 🔐 Re-draw the video nonce salt for THIS video session and restart the
                // counter. A voice→video→voice→video toggle inside one call resets the
                // counter, so without a fresh salt the second session would replay the
                // first session's nonces under the same key.
                // iOS VideoCallManager.swift:264-266.
                synchronized(videoNonceLock) {
                    videoTxSalt = null
                    videoNonceCounter = 0L
                }

                // Wire raw video callback → wrap in [0xF1][seq:8], encrypt, send.
                // Set AFTER the join so a late teardown can no longer null it out.
                videoCallManager.onRawVideoPacketReady = { rawVideoData ->
                    scope.launch {
                        sendVideoPacket(rawVideoData)
                    }
                }
                Log.w(TAG, "DIAG: VIDEO START — onRawVideoPacketReady callback wired, calling videoCallManager.startVideo()...")

                videoCallManager.startVideo()
                Log.w(TAG, "DIAG: VIDEO START — videoCallManager.startVideo() returned, isVideoEnabled=${videoCallManager.isVideoEnabled.value}")
                // "Watch only" accept (VUPG 0x05): the receive/decode half runs, but we do not
                // transmit our camera. iOS VoiceCallManager.swift:10549-10552.
                if (!startCapture) {
                    try { videoCallManager.pauseCamera() } catch (_: Exception) { }
                }
                // Force staggered IDR keyframes so remote can decode (matching iOS behavior)
                videoCallManager.forceStaggeredKeyFrames()
                // Enable speaker for video calls by default
                if (!_isSpeakerOn.value) {
                    toggleSpeaker()
                }
                Log.w(TAG, "DIAG: VIDEO START COMPLETE — video=${videoCallManager.isVideoEnabled.value}, sending=${videoCallManager.isSendingVideo.value}, receiving=${videoCallManager.isReceivingVideo.value}")
                Log.d(TAG, "📹 Video started for call (key=${key.size} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ startVideoForCall re-arm failed: ${e.message}", e)
            }
        }
    }

    /**
     * Wrap raw video data in iOS-compatible packet format, encrypt with AES-GCM, and send.
     * Format: [0xF1][seq:8 little-endian][nonce(12)+ciphertext+tag(16)]
     * Matches iOS VoiceCallManager.sendVideoPacketInternal() — Data(bytes: &seq, count: 8) on ARM64 = LE
     */
    private suspend fun sendVideoPacket(rawVideoData: ByteArray) {
        val call = _currentCall.value ?: return
        val key = sessionKey ?: return

        if (videoPacketsSent < 5) {
            Log.w(TAG, "DIAG: sendVideoPacket #${videoPacketsSent + 1} — rawSize=${rawVideoData.size}, seq=$videoSequenceNumber, ws=${vpsClient.isCallWebSocketConnected}, binaryWs=${vpsClient.serverSupportsBinary}")
        }

        try {
            val encryptedVideo = encryptVideoPacket(rawVideoData, key)

            val packet = ByteArray(1 + 8 + encryptedVideo.size)
            packet[0] = VIDEO_PACKET_TYPE

            // Sequence number in LITTLE-ENDIAN (matching iOS ARM64 native byte order)
            val seq = videoSequenceNumber.toLong()
            for (i in 0..7) {
                packet[1 + i] = (seq shr (i * 8)).toByte()
            }

            System.arraycopy(encryptedVideo, 0, packet, 9, encryptedVideo.size)
            videoSequenceNumber++
            videoPacketsSent++

            // 🔧 FIX (audit B-10): route video through the SAME evidence-driven selector
            // audio uses, and give the :8089 UDP relay a real video destination. Before
            // this the ladder was p2p → MESH_DIRECT → WS → HTTP with the relay never a
            // video carrier, so on a call where :8089 was the only working path audio
            // flowed and video simply had no carrier — the exact failure iOS fixed at
            // VoiceCallManager.swift:10925-10934.
            //
            // `advanceRotation = false`: only the audio send loop may advance the
            // blackout cursor, or video steals rotation ticks and shortens every probe
            // window (iOS VoiceCallManager.swift:10074-10078, :10943-10945).
            val useP2P = p2pCanCarryAudio()
            val relayUsable = udpRelayUsable()
            val videoPrimary = chooseAudioTxPrimary(
                p2pHealthy = useP2P, relayUsable = relayUsable, advanceRotation = false
            )
            if (videoTxPrimaryCurrent != videoPrimary) {
                val previous = videoTxPrimaryCurrent?.name ?: "none"
                videoTxPrimaryCurrent = videoPrimary
                CallDiag.w(TAG, "DIAG_VIDEO_TX_PRIMARY | $previous -> ${videoPrimary.name} | relayUsable=$relayUsable | p2pHealthy=$useP2P | frameBytes=${packet.size}")
            }
            var sent = false
            if (useP2P || videoPrimary == AudioTxPrimary.P2P) {
                try { p2p.sendPacket(packet); sent = true } catch (_: Exception) { }
            }
            var relaySent = false
            if (!useP2P && relayUsable && videoPrimary == AudioTxPrimary.UDP_RELAY) {
                try { udpRelay?.sendAudio(packet); relaySent = true; sent = true } catch (_: Exception) { }
            }
            if (!useP2P && videoPrimary == AudioTxPrimary.MESH) {
                try { crossPlatformMesh.sendCallVideo(call.peerPublicKey, packet); sent = true } catch (_: Exception) { }
            }
            if (!useP2P && (videoPrimary == AudioTxPrimary.WS ||
                    (videoPrimary == AudioTxPrimary.UDP_RELAY && !relaySent))
            ) {
                val sentViaWs = vpsClient.sendCallVideoViaWebSocket(call.callId, packet, call.peerPublicKey)
                if (!sentViaWs) {
                    // 🔧 Use sendCallVideo (POST /video with "video" key), not sendCallAudio:
                    // sendCallAudio posts to /audio with "audio" key → iOS polls /video and never sees it.
                    vpsClient.sendCallVideo(call.callId, packet, call.peerPublicKey)
                }
                sent = true
            }

            if (videoPacketsSent <= 3 || videoPacketsSent % 30 == 0L) {
                Log.d(TAG, "📹 Video packet #$videoPacketsSent sent (${packet.size} bytes, seq=$videoSequenceNumber, ws=${vpsClient.isCallWebSocketConnected})")
            }

            logPacketStats()
        } catch (e: Exception) {
            Log.w(TAG, "📹 Video send error #$videoSequenceNumber: ${e.message}")
        }
    }

    /**
     * Derive (once per VIDEO SESSION) the 4-byte transmit salt: 31 random bits
     * plus the direction bit in the MSB of byte 0. Caller ⇒ 1, callee ⇒ 0, so the
     * two devices in one call always disagree and their nonce spaces are disjoint.
     * MUST be called with [videoNonceLock] held.
     * iOS VideoCallManager.swift:293-307 (`videoTxSaltLocked`).
     */
    private fun videoTxSaltLocked(): ByteArray {
        videoTxSalt?.let { return it }
        val random4 = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
        // iOS VideoCallManager.swift:301-302 — `isOutgoingCall` is the direction bit.
        val weAreCaller = _currentCall.value?.isOutgoing == true
        val salt = com.oshi.messenger.service.audio.CallNonce.videoTxSalt(random4, weAreCaller)
        videoTxSalt = salt
        Log.d(
            TAG,
            "🔐 Video nonce salt: role=${if (weAreCaller) "caller" else "callee"} " +
                "tx=${salt.joinToString("") { "%02x".format(it) }} (direction bit = MSB)"
        )
        return salt
    }

    /**
     * Thread-safe video nonce increment. Returns the full 12-byte nonce so salt +
     * counter are produced atomically. Counter starts at 1 and carries the video
     * domain bit so it can never alias an audio nonce built under the same key.
     * iOS VideoCallManager.swift:311-322 (`nextVideoNonceBytes`).
     */
    internal fun nextVideoNonceBytes(): ByteArray {
        val salt: ByteArray
        val counter: Long
        synchronized(videoNonceLock) {
            salt = videoTxSaltLocked()
            videoNonceCounter += 1
            counter = videoNonceCounter
        }
        return com.oshi.messenger.service.audio.CallNonce.videoNonce(salt, counter)
    }

    /**
     * True when this nonce has already been accepted in this session.
     * iOS VideoCallManager.swift:366-384 (`isReplayedNonce`).
     */
    internal fun isReplayedVideoNonce(nonce: ByteArray): Boolean {
        if (videoReplayWindow.isReplay(nonce)) {
            val n = videoReplayWindow.dropCount
            if (n <= 5 || n % 200 == 0L) {
                CallDiag.w(
                    TAG,
                    "DIAG_VIDEO_REPLAY | dropped=$n | nonce=" +
                        nonce.take(4).joinToString("") { "%02x".format(it) } + "…"
                )
            }
            return true
        }
        return false
    }

    /**
     * 🔐 Video encryption — nonce = `[salt(4) with direction bit][counter BE(8) | domain bit]`.
     * Port of iOS VideoCallManager.swift:293-322. The previous
     * `[counter LE(8)][0x00 ×4]` layout is verbatim what iOS documents as
     * "a total GCM break" at VideoCallManager.swift:244-252.
     *
     * WIRE COMPATIBILITY (verified, not assumed): every receiver — iOS
     * `decryptPacket` (VideoCallManager.swift:273-278) and Android
     * [decryptVideoPacket] below — takes the 12 nonce bytes straight off the wire
     * and never compares them against a locally derived salt or counter. Changing
     * what we transmit is therefore invisible to old peers.
     */
    private fun encryptVideoPacket(plaintext: ByteArray, key: ByteArray): ByteArray {
        val nonce = nextVideoNonceBytes()

        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = javax.crypto.spec.SecretKeySpec(key, "AES")
        val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, nonce)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
        val ciphertextWithTag = cipher.doFinal(plaintext)

        // Return: nonce(12) + ciphertext + tag(16) — same format as iOS
        return nonce + ciphertextWithTag
    }

    /**
     * 🔧 iOS-COMPATIBLE video decryption.
     * Input format: [nonce(12)][ciphertext][tag(16)]
     * Matches iOS VideoCallManager.decryptPacket() format, including the
     * anti-replay window checked AFTER the GCM tag verifies so an attacker
     * cannot poison it with forged nonces (iOS VideoCallManager.swift:344-345).
     */
    private fun decryptVideoPacket(encryptedData: ByteArray, key: ByteArray): ByteArray? {
        if (encryptedData.size < 28) return null // 12 nonce + 16 tag minimum

        try {
            val nonce = encryptedData.copyOfRange(0, 12)
            val ciphertextWithTag = encryptedData.copyOfRange(12, encryptedData.size)

            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = javax.crypto.spec.SecretKeySpec(key, "AES")
            val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, nonce)
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            val plain = cipher.doFinal(ciphertextWithTag)
            // 🔐 Anti-replay AFTER tag verification — iOS VideoCallManager.swift:344-345.
            if (isReplayedVideoNonce(nonce)) return null
            return plain
        } catch (e: Exception) {
            Log.e(TAG, "📹 Video decrypt failed: ${e.message}")
            return null
        }
    }

    fun toggleVideo() {
        videoCallManager.toggleSendingVideo()

        // 📹 Send videoPaused/videoResumed signal to remote (matching iOS format)
        // iOS sends [type][timestamp(8 bytes big-endian)] via WebSocket
        val isSending = videoCallManager.isSendingVideo.value
        val signalType = if (isSending) VIDEO_RESUMED_TYPE else VIDEO_PAUSED_TYPE
        scope.launch {
            sendVideoToggleSignal(signalType)
        }
    }

    /**
     * Send video toggle signal (videoPaused 0x0C / videoResumed 0x0D) to peer.
     *
     * Dual-path delivery to keep both Android and iOS peers in sync:
     *  • plaintext `[type][ts]` over the audio data channel — original
     *    behaviour, used by Android↔Android and older iOS builds that
     *    still read these signals from the audio stream;
     *  • encrypted call_signal — the path iOS actually expects on this
     *    call_signal channel. Without it iOS receives the plaintext blob,
     *    fails to decrypt, silently drops the signal, and the no-audio
     *    watchdog ends up killing the call ~45 s after the user toggled
     *    video.
     */
    private suspend fun sendVideoToggleSignal(signalType: Byte) {
        val call = _currentCall.value ?: return
        try {
            val timestampMs = System.currentTimeMillis()

            // [type][timestamp big-endian]
            val signalData = ByteArray(9)
            signalData[0] = signalType
            for (i in 0..7) signalData[1 + i] = (timestampMs shr (56 - i * 8)).toByte()

            // Path 1: plaintext via audio channel
            val sentViaWs = vpsClient.sendCallAudioViaWebSocket(call.callId, signalData, call.peerPublicKey)
            if (!sentViaWs) {
                vpsClient.sendCallAudio(call.callId, signalData, call.peerPublicKey)
            }

            // Path 2: encrypted call_signal — iOS decrypts and reads the type
            // byte from the cleartext payload to route to videoPaused/Resumed.
            try {
                val encrypted = cryptoManager.encryptCallSignal(signalData, call.peerPublicKey)
                val signal = CallSignal(
                    callId = call.callId,
                    senderPublicKey = myPublicKey,
                    type = CallSignalType.CALL_REQUEST,  // iOS resolves real type from byte 0
                    payload = encrypted,
                    timestamp = timestampMs
                )
                vpsClient.sendCallSignal(call.peerPublicKey, signal)
            } catch (e: Exception) {
                Log.w(TAG, "encrypted video-toggle signal send failed: ${e.message}")
            }

            val signalName = if (signalType == VIDEO_PAUSED_TYPE) "videoPaused" else "videoResumed"
            Log.d(TAG, "📹 Sent $signalName signal to remote (ws=$sentViaWs, encrypted=true)")
        } catch (e: Exception) {
            Log.e(TAG, "📹 Failed to send video toggle signal: ${e.message}")
        }
    }

    // ============================================================================
    // CAMERA PAUSE/RESUME (lifecycle-aware)
    // ============================================================================

    private fun sendVideoPausedSignal() {
        scope.launch { sendVideoToggleSignal(VIDEO_PAUSED_TYPE) }
    }
    private fun sendVideoResumedSignal() {
        scope.launch { sendVideoToggleSignal(VIDEO_RESUMED_TYPE) }
    }

    // ============================================================================
    // 📹 KEYFRAME REQUEST (PLI, 0x0B)
    // ============================================================================

    /** Token bucket state — iOS VoiceCallManager.swift:10311-10319. */
    private var lastKeyframeRequestAt: Long = 0L
    private var keyframeTokens: Double = KEYFRAME_REQUEST_BURST.toDouble()
    private var keyframeRequestsSent: Long = 0L
    private var keyframeRequestsSwallowed: Long = 0L
    private val keyframeBucketLock = Any()
    private var lastKeyframeSignalAt: Long = 0L

    /**
     * Send a keyframe request (PLI, type 0x0B) to the peer when OUR decode fails
     * or reassembly loses a frame.
     *
     * Rate limit (__CALL_VIDEO_SIGNAL_2026_09_23__): ≤ 2/s on the media channel, ≤ 1/s
     * on /signal — see [KEYFRAME_REQUEST_MIN_INTERVAL_MS]. The first request after a
     * quiet second still leaves at once on both.
     *
     * Transport mirrors [sendVideoToggleSignal]: the plaintext copy on the audio
     * channel (which Android peers read in `handleAudioData`) plus the encrypted
     * `call_signal` copy, which is the channel iOS parses
     * (`VoiceCallManager.swift:5302-5308`, `:6972-6979`).
     */
    fun sendKeyframeRequest() {
        val now = System.currentTimeMillis()
        synchronized(keyframeBucketLock) {
            val elapsed = now - lastKeyframeRequestAt
            if (elapsed > 0) {
                keyframeTokens = minOf(
                    KEYFRAME_REQUEST_BURST.toDouble(),
                    keyframeTokens + elapsed.toDouble() / KEYFRAME_REQUEST_MIN_INTERVAL_MS
                )
            }
            if (keyframeTokens < 1.0) {
                keyframeRequestsSwallowed++
                if (keyframeRequestsSwallowed <= 5 || keyframeRequestsSwallowed % 25 == 0L) {
                    CallDiag.w(
                        TAG,
                        "DIAG_VIDEO_IDR_REQ_SWALLOWED | total=$keyframeRequestsSwallowed | " +
                            "tokens=%.2f | sinceLastMs=$elapsed | minIntervalMs=$KEYFRAME_REQUEST_MIN_INTERVAL_MS"
                                .format(keyframeTokens)
                    )
                }
                return
            }
            keyframeTokens -= 1.0
            lastKeyframeRequestAt = now
            keyframeRequestsSent++
        }

        if (_callState.value != CallState.IN_CALL) return
        val call = _currentCall.value ?: return

        scope.launch {
            try {
                // Same [type][timestamp BE8] body iOS's createCallPacket builds.
                val signalData = ByteArray(9)
                signalData[0] = KEYFRAME_REQUEST_TYPE
                for (i in 0..7) signalData[1 + i] = (now shr (56 - i * 8)).toByte()

                // Path 1: plaintext on the audio channel (Android peers).
                if (!vpsClient.sendCallAudioViaWebSocket(call.callId, signalData, call.peerPublicKey)) {
                    vpsClient.sendCallAudio(call.callId, signalData, call.peerPublicKey)
                }
                // Path 2: encrypted call_signal — the channel iOS parses. ≤ 1/s.
                val signalDue = synchronized(keyframeBucketLock) {
                    (now - lastKeyframeSignalAt >= KEYFRAME_SIGNAL_MIN_INTERVAL_MS).also { if (it) lastKeyframeSignalAt = now }
                }
                if (signalDue) try {
                    val encrypted = cryptoManager.encryptCallSignal(signalData, call.peerPublicKey)
                    vpsClient.sendCallSignal(
                        call.peerPublicKey,
                        CallSignal(
                            callId = call.callId,
                            senderPublicKey = myPublicKey,
                            type = CallSignalType.CALL_REQUEST,  // peer resolves the real type from byte 0
                            payload = encrypted,
                            timestamp = now,
                            // __CALL_VIDEO_SIGNAL_2026_09_23__ an explicit envelope type, so
                            // the server budgets it as a keyframe request and not by size.
                            wireType = CallSignal.WIRE_TYPE_KEYFRAME_REQUEST,
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "encrypted keyframe-request send failed: ${e.message}")
                }
                Log.d(
                    TAG,
                    "📹 Sent requestKeyframe (0x0B) to peer " +
                        "(sent=$keyframeRequestsSent swallowed=$keyframeRequestsSwallowed)"
                )
            } catch (e: Exception) {
                Log.w(TAG, "📹 Failed to send keyframe request: ${e.message}")
            }
        }
    }

    /// Wall clock of the last background transition, so the resync on foreground
    /// only fires after a real suspension. iOS VoiceCallManager.swift:5092-5106.
    @Volatile private var lastBackgroundedAtMs: Long = 0L

    fun onAppBackgrounded() {
        lastBackgroundedAtMs = System.currentTimeMillis()
        if (_currentCall.value?.isVideoCall == true && _callState.value == CallState.IN_CALL) {
            videoCallManager.pauseCamera()
            sendVideoPausedSignal()
        }
    }

    fun onAppForegrounded() {
        if (_currentCall.value?.isVideoCall == true && _callState.value == CallState.IN_CALL) {
            videoCallManager.resumeCamera()
            sendVideoResumedSignal()
        }

        // 🔄 We were suspended; the network buffered our TX and is about to dump it
        // at the peer, and our own RX buffer holds a backlog we would otherwise play
        // out before any fresh audio. Flush ours, and ask the peer to flush theirs.
        // iOS VoiceCallManager.swift:5098-5116 (same 2 s floor, same two actions).
        val state = _callState.value
        if (state != CallState.IN_CALL && state != CallState.CONNECTING) return
        val backgroundedAt = lastBackgroundedAtMs
        lastBackgroundedAtMs = 0L
        if (backgroundedAt == 0L) return
        val awayMs = System.currentTimeMillis() - backgroundedAt
        if (awayMs < RESYNC_MIN_BACKGROUND_MS) return

        Log.d(TAG, "🔄 Foregrounded after ${awayMs}ms — flushing local jitter + asking peer to resync")
        flushPlaybackForResync("local_foreground")
        sendResyncRequest()
    }

    /**
     * Drop everything already buffered for playback: the jitter buffer AND the
     * bytes already handed to `AudioTrack`. Flushing only the jitter buffer leaves
     * seconds of stale audio queued inside the track, which is exactly the residue
     * iOS documents at `VoiceCallManager.swift:12490-12497` — it cleared the jitter
     * buffer and the user still heard the OLD audio drain first, so
     * `flushPlayerNodeQueue()` was added alongside.
     */
    private fun flushPlaybackForResync(why: String) {
        jitterBuffer.reset()
        opusJitter.reset()
        synchronized(audioTrackLock) {
            val track = audioTrack ?: return@synchronized
            try {
                // AudioTrack.flush() only discards data on a track that is not
                // playing, so pause → flush → play, mirroring iOS's stop/reset/play.
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.play()
                } else {
                    track.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "🔄 AudioTrack flush failed ($why): ${e.message}")
            }
        }
        Log.d(TAG, "🔄 PLAYER_RESET | reason=$why")
    }

    /**
     * RX for `resyncRequest` (0x0F on the SIGNAL channel): the peer just came back
     * from a suspension. iOS VoiceCallManager.swift:5336-5342 / :7025-7028.
     */
    private fun handleResyncRequest() {
        if (_callState.value != CallState.IN_CALL) return
        Log.d(TAG, "🔄 Received resyncRequest from peer — flushing jitter")
        flushPlaybackForResync("peer_resync")
    }

    /**
     * TX for `resyncRequest`. Body is iOS's bare call packet `[type][timestamp BE8]`
     * (`createCallPacket`, VoiceCallManager.swift:13400-13412) with an empty payload,
     * sealed with the call-signal HKDF params and posted on the signal channel —
     * exactly `sendResyncRequest()` at VoiceCallManager.swift:10385-10390.
     *
     * `type` on the JSON envelope is CALL_REQUEST because that is the only value
     * that routes a peer's Android build through `resolveSignalType`, which reads
     * the real type off byte 0. iOS ignores the JSON type entirely.
     */
    private fun sendResyncRequest() {
        val call = _currentCall.value ?: return
        val myKey = myPublicKey
        if (myKey.isEmpty()) return
        scope.launch {
            try {
                val timestampMs = System.currentTimeMillis()
                val body = ByteArray(9)
                body[0] = RESYNC_REQUEST_TYPE
                for (i in 0..7) body[1 + i] = (timestampMs shr (56 - i * 8)).toByte()
                val signal = CallSignal(
                    callId = call.callId,
                    senderPublicKey = myKey,
                    type = CallSignalType.CALL_REQUEST,
                    payload = cryptoManager.encryptCallSignal(body, call.peerPublicKey),
                    timestamp = timestampMs
                )
                vpsClient.sendCallSignal(call.peerPublicKey, signal)
                Log.d(TAG, "🔄 Sent resyncRequest (0x0F) to peer")
            } catch (e: Exception) {
                Log.w(TAG, "🔄 resyncRequest send failed (non-fatal): ${e.message}")
            }
        }
    }

    // ============================================================================
    // VIDEO UPGRADE MID-CALL
    // ============================================================================

    /**
     * Build + send a video-upgrade negotiation packet over the SAME P2P UDP
     * path that audio uses. Wire format mirrors audio:
     *   [type(1)][seq(8 BE)][AES-GCM(salt || counter): plaintext = payloadByte]
     * The type byte is 0x0E for upgrade negotiation (request/accept/decline)
     * and 0x0F for "stop video". Returns true if the packet was successfully
     * placed on the wire.
     */
    private fun sendVideoUpgradeSignal(typeByte: Byte, payloadByte: Byte): Boolean {
        val key = sessionKey ?: run {
            CallDiag.w(TAG, "DIAG_VUPGRADE_TX_NO_KEY | type=0x%02X | code=0x%02X".format(typeByte, payloadByte))
            return false
        }
        // Increment shared audio counter so this packet's nonce is globally unique
        // alongside audio packets — same key, never the same (salt+counter).
        val counter = nextAudioCounter()
        val encrypted = try {
            encryptAudioPacket(byteArrayOf(payloadByte), key, counter)
        } catch (e: Exception) {
            CallDiag.w(TAG, "DIAG_VUPGRADE_TX_ENCRYPT_FAIL | err=${e.message}")
            return false
        }
        val packet = ByteArray(1 + 8 + encrypted.size)
        packet[0] = typeByte
        for (i in 0..7) packet[1 + i] = (counter shr (56 - i * 8)).toByte()
        System.arraycopy(encrypted, 0, packet, 9, encrypted.size)

        // 🔧 FIX (audit D-9): send the upgrade/stop CONTROL packet over EVERY
        // transport the call is currently using, not P2P only. iOS names this exact
        // regression at VoiceCallManager.swift:10453-10466: "The previous P2P-only
        // gate meant that on any call NOT running over direct/TURN P2P (notably
        // iOS↔Android …) the REQUEST/ACCEPT/DECLINE/STOP packet was never
        // transmitted, so 'switch to video' silently did nothing."
        //
        // These are one-packet-per-user-action control packets, not 50/s media, so
        // the fan-out is NOT the audio shotgun this change set removes elsewhere —
        // iOS keeps mesh here deliberately (VoiceCallManager.swift:10472-10479) even
        // though it deleted mesh as a MEDIA carrier. The receiver dedups by
        // (type, seq), so duplicates are free.
        // Mirrors iOS VoiceCallManager.swift:10460-10493.
        val onP2P = p2pCanCarryAudio()
        val call = _currentCall.value
        val peerKey = call?.peerPublicKey
        var sent = false

        if (onP2P) {
            try { p2p.sendPacket(packet); sent = true } catch (e: Exception) {
                CallDiag.w(TAG, "DIAG_VUPGRADE_TX_SEND_FAIL | path=p2p | err=${e.message}")
            }
        }
        // UDP relay :8089 — iOS VoiceCallManager.swift:10468-10471.
        val relay = udpRelay
        if (relay != null && relay.isRegistered) {
            try { relay.sendAudio(packet); sent = true } catch (_: Exception) { }
        }
        // CrossPlatformMesh TCP bridge — iOS VoiceCallManager.swift:10484-10487.
        if (peerKey != null) {
            try { crossPlatformMesh.sendCallAudio(peerKey, packet); sent = true } catch (_: Exception) { }
        }
        // VPS WebSocket / HTTP when P2P is not the active path — iOS :10490-10493.
        if (!onP2P && call != null && peerKey != null) {
            try {
                if (!vpsClient.sendCallAudioViaWebSocket(call.callId, packet, peerKey)) {
                    scope.launch {
                        try { vpsClient.sendCallAudio(call.callId, packet, peerKey) } catch (e: Exception) {
                            CallDiag.w(TAG, "DIAG_VUPGRADE_TX_SEND_FAIL | path=vps_http | err=${e.message}")
                        }
                    }
                }
                sent = true
            } catch (e: Exception) {
                CallDiag.w(TAG, "DIAG_VUPGRADE_TX_SEND_FAIL | path=vps | err=${e.message}")
            }
        }

        CallDiag.w(
            TAG,
            ("DIAG_VUPGRADE_TX | type=0x%02X | code=0x%02X | seq=$counter | size=${packet.size} | " +
                "sent=$sent | path=[p2p=$onP2P relay=${relay?.isRegistered == true} bridge=${peerKey != null}]")
                .format(typeByte, payloadByte)
        )
        return sent
    }

    /**
     * 📞 Send an IN-BAND callEnd (0x0D) over EVERY live media transport, so a
     * locally-initiated hang-up reaches the peer even when the signaling channel
     * (call_signal / WS-audio) is a zombie — a foreground WebSocket whose
     * connected flag is still true but that has been silent 30s+. Wire mirrors an
     * audio frame and the 0x0E/0x0F video-upgrade controls:
     *   [type=0x0D][seq(8 BE)][AES-GCM sealed box]
     * where the sealed box is [nonce(12)][ciphertext][tag(16)] and the plaintext
     * is the end-reason string UTF-8 (iOS's vocabulary, see [CallEndReasonWire]),
     * drawing from the SAME audio nonce counter as everything else on this key.
     *
     * Fan-out is identical to [sendVideoUpgradeSignal]: P2P + UDP relay :8089 +
     * CrossPlatformMesh bridge + VPS WS/HTTP. One packet per hang-up, not media
     * cadence; the peer dedups by (type, seq) and ends idempotently, so the
     * duplicate copies are free. iOS OSHI/VoiceCallManager.swift.
     *
     * MUST be called while the transports are still up (before
     * disconnectCallWebSocket / stopUdpRelay / p2p.stop) and ONLY when WE ended
     * the call — the caller gates on `!receivedRemoteEndSignal` so a device that
     * is ending BECAUSE the peer told it to never echoes 0x0D back.
     */
    private fun sendInBandCallEnd(reason: CallEndReason) {
        val key = sessionKey ?: run {
            CallDiag.w(TAG, "DIAG_CALLEND_INBAND_TX_NO_KEY | reason=$reason")
            return
        }
        val call = _currentCall.value ?: run {
            CallDiag.w(TAG, "DIAG_CALLEND_INBAND_TX_NO_CALL | reason=$reason")
            return
        }
        val peerKey = call.peerPublicKey
        // iOS's snake_case wire vocabulary, not the Kotlin enum name.
        val reasonBytes = CallEndReasonWire.toWire(reason).toByteArray(Charsets.UTF_8)

        // Shared audio counter → this packet's nonce is globally unique alongside
        // audio / video-upgrade packets under the same key.
        val counter = nextAudioCounter()
        val encrypted = try {
            encryptAudioPacket(reasonBytes, key, counter)
        } catch (e: Exception) {
            CallDiag.w(TAG, "DIAG_CALLEND_INBAND_TX_ENCRYPT_FAIL | err=${e.message}")
            return
        }
        val packet = ByteArray(1 + 8 + encrypted.size)
        packet[0] = CALL_END_INBAND_TYPE
        for (i in 0..7) packet[1 + i] = (counter shr (56 - i * 8)).toByte()
        System.arraycopy(encrypted, 0, packet, 9, encrypted.size)

        val onP2P = p2pCanCarryAudio()
        var sent = false

        // P2P UDP.
        if (onP2P) {
            try { p2p.sendPacket(packet); sent = true } catch (e: Exception) {
                CallDiag.w(TAG, "DIAG_CALLEND_INBAND_TX_SEND_FAIL | path=p2p | err=${e.message}")
            }
        }
        // UDP relay :8089.
        val relay = udpRelay
        if (relay != null && relay.isRegistered) {
            try { relay.sendAudio(packet); sent = true } catch (_: Exception) { }
        }
        // CrossPlatformMesh TCP bridge.
        try { crossPlatformMesh.sendCallAudio(peerKey, packet); sent = true } catch (_: Exception) { }
        // VPS WebSocket / HTTP when P2P is not the active carrier.
        if (!onP2P) {
            try {
                if (!vpsClient.sendCallAudioViaWebSocket(call.callId, packet, peerKey)) {
                    scope.launch {
                        try { vpsClient.sendCallAudio(call.callId, packet, peerKey) } catch (e: Exception) {
                            CallDiag.w(TAG, "DIAG_CALLEND_INBAND_TX_SEND_FAIL | path=vps_http | err=${e.message}")
                        }
                    }
                }
                sent = true
            } catch (e: Exception) {
                CallDiag.w(TAG, "DIAG_CALLEND_INBAND_TX_SEND_FAIL | path=vps | err=${e.message}")
            }
        }

        CallDiag.w(
            TAG,
            "DIAG_CALLEND_INBAND_TX | reason=$reason | seq=$counter | size=${packet.size} | " +
                "sent=$sent | path=[p2p=$onP2P relay=${relay?.isRegistered == true} bridge=true]"
        )
    }

    /**
     * Caller-side: A taps "Video" → send 0x0E request (plaintext 0x01) to B.
     * Does NOT start local camera yet — that happens only when B accepts.
     * Times out after 15 s and reverts to voice-only with a toast.
     */
    fun requestVideoUpgrade() {
        val call = _currentCall.value ?: return
        if (call.isVideoCall) return
        if (_isAwaitingVideoUpgrade.value) {
            CallDiag.w(TAG, "DIAG_VUPGRADE_TX | already awaiting accept — ignoring duplicate request")
            return
        }
        // Le pair a ANNONCÉ qu'il ne sait pas faire la vidéo: inutile de lui
        // demander et de le faire attendre quinze secondes. `null` (rien annoncé)
        // n'est pas un refus et passe par le chemin habituel.
        if (peerSupportsVideo == false) {
            _videoUpgradeDeclined.value = "Their app can't do video calls"
            CallDiag.w(TAG, "DIAG_VUPGRADE_TX_REQUEST_BLOCKED | le pair n'annonce pas la vidéo")
            return
        }
        val ok = sendVideoUpgradeSignal(VIDEO_UPGRADE_TYPE, VUPG_PAYLOAD_REQUEST)
        if (!ok) {
            _videoUpgradeDeclined.value = "Could not send video request"
            return
        }
        _videoUpgradeDeclined.value = null
        _isAwaitingVideoUpgrade.value = true
        // We initiated → we share our camera; the peer's camera is live once they
        // accept (cleared so no stale "camera off" placeholder).
        // iOS VoiceCallManager.swift:10531-10532.
        _shareOwnVideo.value = true
        _isRemoteCameraPaused.value = false
        videoUpgradeTimeoutJob?.cancel()
        videoUpgradeTimeoutJob = scope.launch {
            delay(VUPGRADE_TIMEOUT_MS)
            if (_isAwaitingVideoUpgrade.value) {
                _isAwaitingVideoUpgrade.value = false
                // Un pair qui n'a jamais annoncé la vidéo est probablement une
                // version antérieure à la négociation: le dire vaut mieux que
                // « pas de réponse », qui laisse croire que quelqu'un a ignoré.
                _videoUpgradeDeclined.value = if (peerSupportsVideo == null)
                    "No answer — their version may not support video" else "No answer"
                CallDiag.w(TAG, "DIAG_VUPGRADE_TIMEOUT | no accept within ${VUPGRADE_TIMEOUT_MS}ms — reverting to voice-only")
            }
        }
        CallDiag.w(TAG, "DIAG_VUPGRADE_TX_REQUEST | awaiting peer accept (15 s timeout)")
    }

    /**
     * Callee-side: B taps Accept → send 0x0E accept and flip to video.
     *
     * [shareCamera] decides whether B also turns on their OWN camera (true → both
     * see each other, code 0x02) or accepts receive-only / "watch only"
     * (false → B sees A, A sees a "camera off" placeholder for B, code 0x05).
     * Either way both sides flip to the video screen.
     * iOS VoiceCallManager.swift:10553-10607 (`acceptVideoUpgrade(shareCamera:)`).
     */
    @JvmOverloads
    fun acceptVideoUpgrade(shareCamera: Boolean = true) {
        // Clear any in-flight outbound request (cross-fire: both sides tapped
        // Video at almost the same instant). Whichever side's accept wins, we
        // don't want a stale "Inviting…" dialog or 15-second timeout toast
        // firing once we're already in video.
        videoUpgradeTimeoutJob?.cancel(); videoUpgradeTimeoutJob = null
        calleeVideoRequestTimeoutJob?.cancel(); calleeVideoRequestTimeoutJob = null
        _isAwaitingVideoUpgrade.value = false
        _videoUpgradeDeclined.value = null
        _pendingVideoUpgrade.value = false
        _pendingVideoUpgradeFrom.value = null
        _shareOwnVideo.value = shareCamera
        // The requester is always sharing (they asked to turn on video), so our view
        // of THEIR camera is live, not paused. iOS VoiceCallManager.swift:10569.
        _isRemoteCameraPaused.value = false
        val call = _currentCall.value ?: return
        // iOS VoiceCallManager.swift:10560 — `shareCamera ? ACCEPT : ACCEPT_WATCH`.
        val code = if (shareCamera) VUPG_PAYLOAD_ACCEPT else VUPG_PAYLOAD_ACCEPT_WATCH
        sendVideoUpgradeSignal(VIDEO_UPGRADE_TYPE, code)
        // __VUPG_ACCEPT_RETRY_2026_09_14__ L'ACCEPT part TROIS fois.
        //
        // Ces paquets de contrôle n'ont ni accusé ni retransmission. Un seul
        // ACCEPT perdu coupait l'appel en deux définitivement: nous en vidéo,
        // caméra allumée, et le pair renvoyé à l'audio par son propre délai.
        // Trois envois espacés coûtent 76 octets et suppriment la perte isolée,
        // qui est le cas courant. Les doublons sont inoffensifs depuis que la
        // réception d'un ACCEPT exige une demande en cours.
        scope.launch {
            repeat(2) {
                delay(700)
                if (_currentCall.value?.isVideoCall == true) {
                    sendVideoUpgradeSignal(VIDEO_UPGRADE_TYPE, code)
                }
            }
        }
        OshiConnection.updateCallHasVideo(true)
        _currentCall.value = call.copy(isVideoCall = true)
        try {
            startVideoForCall(startCapture = shareCamera)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start local video on accept: ${e.message}", e)
        }
        CallDiag.w(TAG, "DIAG_VUPGRADE_TX_ACCEPT | shareCamera=$shareCamera (code=0x%02X)".format(code))
    }

    /**
     * Mid-video: turn THIS device's camera ON (e.g. a "watch only" user decides to
     * start sharing). Tells the peer via CAMERA_ON (0x06) so it stops showing our
     * "camera off" placeholder.
     * iOS VoiceCallManager.swift:10612-10618 (`enableLocalCameraMidVideo`).
     */
    fun enableLocalCameraMidVideo() {
        val call = _currentCall.value ?: return
        if (_callState.value != CallState.IN_CALL || !call.isVideoCall) return
        sendVideoUpgradeSignal(VIDEO_UPGRADE_TYPE, VUPG_PAYLOAD_CAMERA_ON)
        _shareOwnVideo.value = true
        try { videoCallManager.resumeCamera() } catch (_: Exception) { }
        CallDiag.w(TAG, "DIAG_VUPGRADE_TX_CAMERA_ON | local camera starting mid-video")
    }

    /**
     * Mid-video: turn THIS device's camera OFF but keep watching the peer.
     * Tells the peer via CAMERA_OFF (0x07) so it shows our "camera off" placeholder.
     * iOS VoiceCallManager.swift:10622-10628 (`disableLocalCameraMidVideo`).
     */
    fun disableLocalCameraMidVideo() {
        val call = _currentCall.value ?: return
        if (_callState.value != CallState.IN_CALL || !call.isVideoCall) return
        sendVideoUpgradeSignal(VIDEO_UPGRADE_TYPE, VUPG_PAYLOAD_CAMERA_OFF)
        _shareOwnVideo.value = false
        try { videoCallManager.pauseCamera() } catch (_: Exception) { }
        CallDiag.w(TAG, "DIAG_VUPGRADE_TX_CAMERA_OFF | local camera stopped, still watching")
    }

    /**
     * Au bout de quinze secondes sans réponse, on refuse pour de bon.
     *
     * Refuser plutôt qu'effacer le dialogue est délibéré: sans le 0x03, le
     * demandeur ne saurait jamais si sa demande est morte en route ou si
     * personne n'a regardé son téléphone. Et surtout, cela ferme la fenêtre où
     * un « Accepter » tardif viendrait rallumer une caméra chez quelqu'un à qui
     * on a déjà annoncé « pas de réponse ».
     */
    private fun armCalleeVideoRequestTimeout() {
        calleeVideoRequestTimeoutJob?.cancel()
        calleeVideoRequestTimeoutJob = scope.launch {
            delay(VUPGRADE_TIMEOUT_MS)
            if (_pendingVideoUpgrade.value) {
                CallDiag.w(TAG, "DIAG_VUPGRADE_CALLEE_TIMEOUT | aucune réponse en ${VUPGRADE_TIMEOUT_MS}ms — refus automatique")
                declineVideoUpgrade()
            }
        }
    }

    /** Callee-side: B taps Decline → send 0x0E decline (0x03), UI stays on voice. */
    fun declineVideoUpgrade() {
        // Refuser annule AUSSI notre propre demande en vol. Sans ça, quand les
        // deux ont appuyé sur Vidéo en même temps, on refusait la leur en
        // gardant la nôtre armée: leur ACCEPT arrivait ensuite et nous faisait
        // passer en vidéo — après avoir refusé.
        videoUpgradeTimeoutJob?.cancel(); videoUpgradeTimeoutJob = null
        calleeVideoRequestTimeoutJob?.cancel(); calleeVideoRequestTimeoutJob = null
        _isAwaitingVideoUpgrade.value = false
        _pendingVideoUpgrade.value = false
        _pendingVideoUpgradeFrom.value = null
        sendVideoUpgradeSignal(VIDEO_UPGRADE_TYPE, VUPG_PAYLOAD_DECLINE)
        CallDiag.w(TAG, "DIAG_VUPGRADE_TX_DECLINE | user declined upgrade")
    }

    /**
     * Either side: tap "Stop video" mid-video-call → send 0x0F (plaintext 0x04),
     * stop local camera, and revert UI to voice-only. Audio path is untouched.
     */
    fun stopVideo() {
        val call = _currentCall.value ?: return
        if (!call.isVideoCall) return
        sendVideoUpgradeSignal(VIDEO_STOP_TYPE, VUPG_PAYLOAD_STOP)
        try { videoCallManager.stopVideo() } catch (_: Exception) {}
        // iOS remet TOUS les drapeaux de négociation; ici il en manquait quatre
        // plus l'annulation des délais. Ça se rattrapait à la montée suivante,
        // mais un délai resté armé peut refuser une demande qui n'existe plus.
        videoUpgradeTimeoutJob?.cancel(); videoUpgradeTimeoutJob = null
        calleeVideoRequestTimeoutJob?.cancel(); calleeVideoRequestTimeoutJob = null
        _pendingVideoUpgrade.value = false
        _pendingVideoUpgradeFrom.value = null
        _isAwaitingVideoUpgrade.value = false
        _shareOwnVideo.value = true
        _isRemoteCameraPaused.value = false
        OshiConnection.updateCallHasVideo(false)
        _currentCall.value = call.copy(isVideoCall = false)
        CallDiag.w(TAG, "DIAG_VUPGRADE_TX_STOP | local video stopped, peer notified")
    }

    /** UI helper: clear the one-shot "no answer" / "declined" toast slot. */
    fun consumeVideoUpgradeDeclined() {
        _videoUpgradeDeclined.value = null
    }

    fun switchCamera() {
        videoCallManager.switchCamera()
    }

    fun setVideoQuality(quality: VideoQuality) {
        videoCallManager.setQuality(quality)
    }

    fun setVideoBlurMode(mode: BlurMode) {
        videoCallManager.setBlurMode(mode)
    }

    fun setVideoBlurIntensity(intensity: Float) {
        videoCallManager.setBlurIntensity(intensity)
    }

    // ============================================================================
    // PROXIMITY SENSOR
    // ============================================================================

    private val proximitySensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_PROXIMITY) return
            val distance = event.values[0]
            val maxRange = event.sensor.maximumRange
            val isNear = distance < maxRange

            val am = audioManager ?: return
            val isVideoCall = _currentCall.value?.isVideoCall == true

            if (isNear) {
                // Near face — switch to earpiece (both voice and video calls)
                am.isSpeakerphoneOn = false
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                _isSpeakerOn.value = false
                Log.d(TAG, "📱 Proximity: NEAR — switching to earpiece")
            } else {
                // Away from face:
                // - Video calls: switch to speaker (user is looking at screen)
                // - Voice calls: stay on earpiece (user may be holding phone normally)
                // 🔧 FIX: Previously forced speaker for ALL calls when FAR, causing
                // voice call audio to blast from speaker unexpectedly
                if (isVideoCall) {
                    am.isSpeakerphoneOn = true
                    _isSpeakerOn.value = true
                    Log.d(TAG, "📱 Proximity: FAR (video) — switching to speaker")
                } else {
                    // Voice call: keep earpiece, don't auto-switch to speaker
                    Log.d(TAG, "📱 Proximity: FAR (voice) — keeping earpiece")
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }
    }

    private fun startProximitySensor() {
        try {
            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            if (proximitySensor != null) {
                sensorManager?.registerListener(
                    proximitySensorListener,
                    proximitySensor,
                    SensorManager.SENSOR_DELAY_NORMAL
                )
                Log.d(TAG, "📱 Proximity sensor registered")
            } else {
                Log.w(TAG, "📱 No proximity sensor available on device")
            }

            // Acquire proximity wake lock to turn screen off when near.
            // PERF/SAFETY: bound the wake lock to a generous call timeout
            // (4 hours). Without a timeout, a missed stopProximitySensor() call
            // (e.g. process killed mid-call) would leak the wake lock, draining
            // battery and triggering "App is using too much battery" warnings
            // that the system surfaces alongside ANR dialogs.
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm?.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "oshi:call_proximity"
            )
            wakeLock?.acquire(4L * 60L * 60L * 1000L) // 4h max — system releases automatically
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proximity sensor: ${e.message}")
        }
    }

    private fun stopProximitySensor() {
        try {
            sensorManager?.unregisterListener(proximitySensorListener)
            proximitySensor = null
            sensorManager = null
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
            Log.d(TAG, "📱 Proximity sensor unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop proximity sensor: ${e.message}")
        }
    }

    // ============================================================================
    // VOICE EFFECTS (real-time call audio)
    // ============================================================================

    /**
     * Cycle through voice effects: NORMAL → ROBOT → ALIEN → DEEP → CHIPMUNK → ECHO → REVERB → ANONYMOUS → NORMAL
     */
    fun cycleVoiceEffect() {
        val next = _currentVoiceEffect.value.next()
        if (next == _currentVoiceEffect.value) return
        _currentVoiceEffect.value = next
        Log.d(TAG, "🎤 Voice effect: ${next.displayName}")
        // DIAG: emit one-shot transmit-effect change marker, mirrors iOS DIAG_VEFFECT_APPLIED.
        CallDiag.w(TAG, "DIAG_VEFFECT_TX | effect=${next.displayName}")
    }

    /**
     * Set a specific voice effect for the call.
     */
    fun setVoiceEffect(effect: CallVoiceEffectProcessor.VoiceEffect) {
        if (_currentVoiceEffect.value == effect) return
        _currentVoiceEffect.value = effect
        Log.d(TAG, "🎤 Voice effect set to: ${effect.displayName}")
        // DIAG: emit one-shot transmit-effect change marker, mirrors iOS DIAG_VEFFECT_APPLIED.
        CallDiag.w(TAG, "DIAG_VEFFECT_TX | effect=${effect.displayName}")
    }

    // ============================================================================
    // TIMERS & STATS
    // ============================================================================

    private fun startDurationTimer() {
        durationJob = scope.launch {
            while (isActive && _callState.value.let { it == CallState.IN_CALL || it == CallState.CONNECTING }) {
                delay(1000)
                if (_callState.value == CallState.IN_CALL) {
                    _callDuration.value = _callDuration.value + 1
                }
            }
        }
    }

    private fun startStatsUpdater() {
        statsJob = scope.launch {
            while (isActive && _callState.value.let { it == CallState.IN_CALL || it == CallState.CONNECTING }) {
                _jitterBufferStats.value = jitterBuffer.getStats()
                // Don't update quality from jitter buffer during first 3 seconds (grace period)
                // The buffer is still filling and underruns are not real packet loss
                //
                // __QUALITY_FROM_WHAT_IS_HEARD_2026_09_22__ Sampled every 4th tick
                // (2 s, iOS's cadence — the 2-sample downgrade hysteresis is 4 s) from
                // the frames actually played; see [CallQualityTracker]. RECONNECTING /
                // DISCONNECTED belong to the transport layer: set by the silence policy
                // or a lost network, cleared by the next authenticated media packet
                // ([restoreQualityAfterMedia]). The sampler must not paint over them.
                val timeSinceConnected = callConnectedTime?.let { System.currentTimeMillis() - it } ?: 0L
                if (timeSinceConnected > 3000) {
                    qualitySampleTick++
                    if (qualitySampleTick % 4 == 0) {
                        val shown = qualityTracker.sample(
                            realFramesTotal = qualityRealFrames,
                            concealedFramesTotal = qualityConcealedFrames,
                            latencyMs = _latencyMs.value,
                            jitterMs = jitterBuffer.jitterEstimateMs
                        )
                        val cur = _connectionQuality.value
                        if (cur != NetworkQuality.RECONNECTING && cur != NetworkQuality.DISCONNECTED) {
                            _connectionQuality.value = shown
                        }
                    }
                } else if (_connectionQuality.value != NetworkQuality.RECONNECTING) {
                    _connectionQuality.value = NetworkQuality.GOOD
                }

                // 📶 Adaptive codec bitrate — 80 / 48 / 24 kbps.
                //
                // The decision itself lives in [AdaptiveCodecBitratePolicy], which is
                // pure and unit-tested, because the version that used to sit inline
                // here flipped the encoder 80 ↔ 48 kbps ONCE PER SECOND for an entire
                // call on any link with an ordinary ~1 % loss — bare thresholds read
                // off a 50-packet window whose resolution is 2 percentage points, so
                // the reading could only ever be 0 % or 2 % and `loss < 1.0` sat
                // exactly between them. That reconfiguration of a live AAC-ELD encoder
                // is what gets reported as "metallic" audio. See that file's header.
                //
                // The loss figure comes from the LONG window for the same reason.
                if (useAacEld) {
                    val rtt = _latencyMs.value * 2  // latencyMs is one-way, RTT = 2x
                    val (lossPercent, lossSamples) = adaptiveBitrate.codecLossWindowStats
                    val targetBitrate = com.oshi.messenger.service.audio.AdaptiveCodecBitratePolicy.nextBitrate(
                        currentBps = lastAdaptiveBitrate,
                        rttMs = rtt,
                        lossPercent = lossPercent,
                        lossSamples = lossSamples
                    )
                    if (targetBitrate != lastAdaptiveBitrate) {
                        lastAdaptiveBitrate = targetBitrate
                        aacEldCodec?.setBitrate(targetBitrate)
                        val levelName = com.oshi.messenger.service.audio.AdaptiveCodecBitratePolicy.label(targetBitrate)
                        Log.d(TAG, "Adaptive bitrate: ${targetBitrate/1000}kbps (RTT=${rtt}ms, loss=${"%.1f".format(lossPercent)}% over $lossSamples pkts, level=$levelName)")
                    }
                }

                // __VIDEO_ABR_2026_09_23__ The video sender adapts on the same measured
                // loss/RTT as the audio codec (iOS folds `audioCongestionFactor` into its
                // video target the same way), plus the peer's keyframe requests.
                // (A no-op until the camera's encoder runs — upgraded calls included.)
                run {
                    val (vLoss, vSamples) = adaptiveBitrate.codecLossWindowStats
                    if (vSamples >= 20) videoCallManager.onNetworkFeedback(vLoss, _latencyMs.value * 2.0)
                }

                // Update call quality stats
                _bitrateLevel.value = adaptiveBitrate.currentLevel
                val stats = jitterBuffer.getStats()
                _callQualityStats.value = com.oshi.messenger.service.audio.CallQualityStats(
                    latencyMs = _latencyMs.value.toInt(),
                    jitterMs = jitterBuffer.jitterEstimateMs.toInt(),
                    // Heard loss (concealed / played, smoothed) — the number the badge uses.
                    packetLossPercent = qualityTracker.lossEwmaPercent,
                    bitrateLevel = adaptiveBitrate.currentLevel.label,
                    jitterBufferDepthMs = stats.bufferDepthMs.toDouble(),
                    packetsRecovered = fecReceiver.packetsRecovered,
                    packetsConcealed = stats.packetsConcealed
                )

                // ⏱️ BACKSTOP ONLY. The peer telling us it hung up (`0x04`) is the
                // primary teardown path — see [endCall]'s comment. This branch is for
                // a peer that vanished without saying anything, and the whole decision
                // lives in [PeerSilencePolicy], pure and tested: the previous inline
                // rule carried a `&& !_isRemoteCameraPaused` term that made it
                // UNBOUNDED for a peer that paused its camera and then died.
                val timeSinceAudio = System.currentTimeMillis() - lastAudioReceivedTime
                // Proof the peer is alive when it sends no media: its P2P transport still
                // answers OUR pings (a pong echoes a nonce only our ping carried). A muted
                // iPhone sends nothing else on P2P since iOS stopped its keepalive there
                // (__KEEPALIVE_IS_NOT_AUDIO_2026_09_22__) — without this, muting an iPhone
                // made Android beep "reconnecting" at 10 s and hang up at 45 s.
                val pongAt = try { p2p.lastCorrelatedPongAtMs } catch (_: Exception) { 0L }
                val timeSincePeerProof = PeerSilencePolicy.silenceMs(
                    msSinceMedia = timeSinceAudio,
                    msSincePeerTransport = if (pongAt > 0L) System.currentTimeMillis() - pongAt else null
                )
                callConnectedTime?.let { connectedTime ->
                    val timeSinceConnect = System.currentTimeMillis() - connectedTime
                    when (
                        PeerSilencePolicy.verdict(
                            msSinceConnect = timeSinceConnect,
                            msSinceMedia = timeSincePeerProof,
                            remoteCameraPaused = _isRemoteCameraPaused.value
                        )
                    ) {
                        PeerSilencePolicy.Verdict.HEALTHY -> {
                            // Media stopped but the peer's transport still answers (muted
                            // peer): if silence had put the badge in RECONNECTING, the
                            // proof clears it — no audio packet will come to do so.
                            if (_connectionQuality.value == NetworkQuality.RECONNECTING &&
                                timeSinceAudio > PeerSilencePolicy.RECONNECTING_SILENCE_MS
                            ) {
                                _connectionQuality.value = qualityTracker.shown
                            }
                        }
                        PeerSilencePolicy.Verdict.RECONNECTING -> {
                            if (_connectionQuality.value != NetworkQuality.RECONNECTING) {
                                playConnectionLossBeep()
                            }
                            _connectionQuality.value = NetworkQuality.RECONNECTING
                        }
                        PeerSilencePolicy.Verdict.LOST -> {
                            if (_connectionQuality.value != NetworkQuality.RECONNECTING) {
                                playConnectionLossBeep()
                            }
                            _connectionQuality.value = NetworkQuality.RECONNECTING
                            CallDiag.w(
                                TAG,
                                "DIAG_PEER_SILENCE | LOST after ${timeSinceAudio}ms of silence " +
                                    "(cameraPaused=${_isRemoteCameraPaused.value}) — backstop fired, " +
                                    "no callEnd was ever received"
                            )
                            endCall(CallEndReason.CONNECTION_LOST)
                        }
                    }
                }

                // __EGRESS_STALL_2026_11_23__ Stop streaming to nobody: no carrier can take
                // our frames (WS down / refused / clogged, UDP relay refused or silent, no
                // P2P) AND nothing comes in, for EgressStallPolicy.STALL_MS → "Connection lost".
                if (_callState.value == CallState.IN_CALL && !isEndingCall) {
                    val nowMs = System.currentTimeMillis()
                    val relay = udpRelay
                    val udpReplyAt = relay?.lastServerReplyAtMs ?: 0L
                    val blocked = EgressStallPolicy.egressBlocked(
                        p2pCarrying = p2pCanCarryAudio(),
                        udpRegistered = relay?.isRegistered == true,
                        udpAuthRefused = relay?.isAuthRefused == true,
                        msSinceUdpReply = if (udpReplyAt > 0L) nowMs - udpReplyAt else null,
                        wsConnected = vpsClient.isCallWebSocketConnected,
                        wsAuthRefused = vpsClient.callWebSocketAuthRefused,
                        wsQueuedBytes = vpsClient.callWebSocketQueuedBytes,
                        msSinceHttpOk = vpsClient.lastCallAudioPostOkAtMs.takeIf { it > 0L }?.let { nowMs - it },
                    )
                    val msBlocked = egressStall.update(nowMs, blocked)
                    if (EgressStallPolicy.shouldEnd(msBlocked, timeSincePeerProof)) {
                        CallDiag.urgent(
                            TAG,
                            "DIAG_EGRESS_STALL | egress blocked ${msBlocked}ms + no inbound ${timeSincePeerProof}ms — ending | " +
                                "ws=${vpsClient.isCallWebSocketConnected} wsRefused=${vpsClient.callWebSocketAuthRefused} " +
                                "wsQueue=${vpsClient.callWebSocketQueuedBytes} udpReg=${relay?.isRegistered} " +
                                "udpRefused=${relay?.isAuthRefused} udpReplyAge=${if (udpReplyAt > 0L) nowMs - udpReplyAt else -1} | " +
                                "sent=$audioPacketsSent/$videoPacketsSent"
                        )
                        endCall(CallEndReason.CONNECTION_LOST)
                    }
                }

                // Cross-platform audio diagnostic — structured snapshot every ~2s.
                diagSnapshotTick++
                if (diagSnapshotTick % 4 == 0) {
                    val recState = audioRecord?.recordingState ?: -1
                    val trackState = audioTrack?.playState ?: -1
                    CallDiag.w(TAG, "DIAG_SNAP | state=${_callState.value} | rp=$remotePlatform | aac=$useAacEld | muted=${_isMuted.value} | key=${sessionKey != null} | rec=$recState | trk=$trackState | sent=$audioPacketsSent | recv=$audioPacketsReceived | lastRxAge=${timeSinceAudio / 1000}s | ws=${vpsClient.isCallWebSocketConnected}")

                    // WHY: DIAG_ENGINE — proves whether AudioRecord read is actually returning non-zero frames.
                    val micTapReceiving = diagMicReadFired
                    diagMicReadFired = false
                    val reportedSr = audioRecord?.sampleRate ?: -1
                    val trackSr = audioTrack?.sampleRate ?: -1
                    CallDiag.w(TAG, "DIAG_ENGINE | running=${recState == AudioRecord.RECORDSTATE_RECORDING} | playing=${trackState == AudioTrack.PLAYSTATE_PLAYING} | micTapReceiving=$micTapReceiving | audioLevelRMS=n/a | sampleRateReported=$reportedSr | nativeFormat=rec_sr=$reportedSr,track_sr=$trackSr")

                    // WHY: DIAG_JITTER — buffer depth/in/out so we can tell if RX frames are stuck before playback.
                    val jStats = jitterBuffer.getStats()
                    CallDiag.w(TAG, "DIAG_JITTER | depthPackets=${jStats.bufferDepth} | depthMs=${jStats.bufferDepthMs} | packetsIn=${jStats.packetsReceived} | packetsOut=${jStats.packetsPlayed} | lost=${jStats.packetsLost} | concealed=${jStats.packetsConcealed}")

                    // WHY: DIAG_PATH — the iOS periodic path snapshot (trimmed to what this
                    // stack tracks): which carrier we transmit on, whether P2P is up, and
                    // whether media still arrives. The first line to read in an export.
                    CallDiag.w(TAG, "DIAG_PATH | p2pState=${p2p.state} | txPrimary=${txPrimaryCurrent?.name ?: "none"} | vTxPrimary=${videoTxPrimaryCurrent?.name ?: "none"} | txCount=$audioPacketsSent | rxRaw=$audioPacketsReceived | lastRxAge=${timeSinceAudio}ms | ws=${vpsClient.isCallWebSocketConnected} | forceRelay=$p2pForceRelayActive | conn=${_connectionType.value}")
                }

                delay(500)
            }
        }
    }

    private suspend fun saveCallHistory(call: CurrentCall, reason: CallEndReason) {
        // __BLOCKED_NO_TRACE_2026_09_24__ never a history row for a blocked contact.
        if (CallAdmissionPolicy.isBlocked(database.contactDao(), call.peerPublicKey)) {
            Log.d(TAG, "📞 No call history — peer is blocked")
            return
        }
        val entry = CallHistoryEntry(
            callId = call.callId,
            peerPublicKey = call.peerPublicKey,
            peerName = call.peerName,
            isOutgoing = call.isOutgoing,
            isVideoCall = call.isVideoCall,
            startTime = call.startTime,
            endTime = System.currentTimeMillis(),
            duration = _callDuration.value,
            status = when (reason) {
                CallEndReason.HUNG_UP, CallEndReason.PEER_ENDED -> CallStatus.COMPLETED
                CallEndReason.DECLINED -> CallStatus.DECLINED
                CallEndReason.NO_ANSWER -> CallStatus.NO_ANSWER
                CallEndReason.MISSED -> CallStatus.MISSED
                CallEndReason.ERROR, CallEndReason.CONNECTION_LOST -> CallStatus.FAILED
                CallEndReason.ANSWERED_ELSEWHERE -> CallStatus.COMPLETED
            }
        )
        database.callHistoryDao().insert(entry)
        // __RATING_THREE_MOMENTS_2026_11_15__ A call that connected (it ran at
        // least one second) and ended normally is rating moment 2; a failed or
        // dropped call is friction and silences the ask for ten minutes.
        when {
            entry.status == CallStatus.COMPLETED && entry.duration > 0 ->
                com.oshi.messenger.util.AppReviewManager.trackCallCompleted(context)
            entry.status == CallStatus.FAILED ->
                com.oshi.messenger.util.AppReviewManager.trackFriction(context)
            else -> Unit
        }
    }

    private fun sendCallSummaryMessage(call: CurrentCall, reason: CallEndReason) {
        // __BLOCKED_NO_TRACE_2026_09_24__ no MISSED_CALL / CALL_SUMMARY row with a blocked contact.
        if (CallAdmissionPolicy.isBlockedCached(call.peerPublicKey)) {
            Log.d(TAG, "📞 No call summary — peer is blocked")
            return
        }
        // The whole decision lives in [CallSummaryFormat] — pure, and tested without
        // a device. `null` means "write nothing", which is the answered-elsewhere
        // case: the device that actually answered writes the summary.
        val content = CallSummaryFormat.summaryFor(
            reason = reason,
            isOutgoing = call.isOutgoing,
            durationSeconds = _callDuration.value
        )
        if (content == null) {
            Log.d(TAG, "📞 No call summary — answered on another device")
            return
        }

        if (LOG_SENSITIVE) Log.d(TAG, "📞 Sending call summary: $content")
        onCallSummary?.invoke(call.peerPublicKey, content)
    }

    // ============================================================================
    // NETWORK CHANGE DETECTION
    // ============================================================================

    private fun startNetworkMonitoring() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available — reconnecting call transport")
                scope.launch {
                    if (_callState.value == CallState.IN_CALL || _callState.value == CallState.CONNECTING) {
                        // Reconnect WebSocket transport (don't restart call or audio)
                        try {
                            vpsClient.connectCallWebSocket()
                            Log.d(TAG, "WebSocket reconnected after network change")
                        } catch (e: Exception) {
                            Log.w(TAG, "WebSocket reconnect failed after network change: ${e.message}")
                        }
                        // Send keepalive to VPS to confirm connectivity
                        try {
                            sendKeepalive()
                            Log.d(TAG, "Keepalive sent after network change")
                        } catch (e: Exception) {
                            Log.w(TAG, "Keepalive failed after network change: ${e.message}")
                        }
                        // __CALL_MEDIA_AUTH_2026_09_23__ contract §3: re-register (with the
                        // trailer) right away when the network changes.
                        try { udpRelay?.reRegisterNow() } catch (_: Throwable) {}
                        // 🧭 P2P: network change → ICE restart
                        try {
                            CallDiag.d(TAG, "🧭 P2P: network change → ICE restart")
                            p2p.restart()
                        } catch (e: Exception) {
                            CallDiag.w(TAG, "🧭 P2P: restart failed after network change: ${e.message}")
                        }
                        // Update quality after short delay to let transport stabilize
                        delay(1000)
                        if (_callState.value == CallState.IN_CALL) {
                            _connectionQuality.value = qualityTracker.shown
                        }
                    }
                }
            }
            override fun onLost(network: Network) {
                Log.w(TAG, "Network lost during call — marking RECONNECTING")
                _connectionQuality.value = NetworkQuality.RECONNECTING
                // 🧭 P2P: loss of network invalidates current pinned pair. Restart
                // will fire on next onAvailable; we also proactively reset so no
                // stale pair is used when a different network comes up.
                try { p2p.restart() } catch (_: Exception) {}
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: android.net.NetworkCapabilities) {
                // Network type changed (e.g. WiFi ↔ cellular) — send keepalive to verify path
                if (_callState.value == CallState.IN_CALL) {
                    scope.launch {
                        try {
                            sendKeepalive()
                        } catch (_: Exception) { }
                    }
                }
            }
        }
        networkCallback = callback
        // __WIFI_AWARE_2026_11_25__ INTERNET only. A bare Builder() also matches
        // local-only networks — a Wi-Fi Aware data path to a nearby phone among
        // them — and their coming and going would restart ICE and flag the call
        // RECONNECTING although the call's own path never changed.
        val request = NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
    }

    private fun stopNetworkMonitoring() {
        networkCallback?.let { cb ->
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            try { cm?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        networkCallback = null
    }

    fun cleanup() {
        stopRingtoneAndRingback()
        stopAudio()
        stopNetworkMonitoring()
        try { p2p.stop() } catch (_: Exception) {}
        try { p2pWatchdogJob?.cancel() } catch (_: Exception) {}
        p2pWatchdogJob = null
        rxDedupSeen.clear()
        // 🛰️ Belt-and-suspenders: endCall() already calls stopUdpRelay(), but
        // process-wide cleanup must release the socket too in case endCall
        // never ran (rare termination paths).
        stopUdpRelay()
        videoCallManager.cleanup()
        scope.cancel()
    }
}

// ============================================================================
// DATA CLASSES & ENUMS - Imported from CallManager.kt
// ============================================================================
// CallState, CallEndReason, ConnectionType, and CurrentCall are defined in CallManager.kt

/**
 * Call transport layer — determines HOW audio/video packets are delivered.
 * The actual packet format, codec, and encryption are unchanged regardless of transport.
 */
enum class CallTransport {
    WEB_SOCKET,   // Current (always works, existing implementation)
    TURN_RELAY,   // TURN UDP relay (lower latency, IP hidden via relay)
    DIRECT_P2P    // STUN P2P (lowest latency, IP visible to peer)
}
