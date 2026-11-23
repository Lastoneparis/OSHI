package com.oshi.messenger.service

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.oshi.messenger.data.local.AppDatabase
import com.oshi.messenger.data.model.CallHistoryEntry
import com.oshi.messenger.data.model.CallStatus
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.mesh.CrossPlatformMesh
import com.oshi.messenger.network.mesh.CrossPlatformMessage
import com.oshi.messenger.network.mesh.MeshMessage
import com.oshi.messenger.network.mesh.MeshNetworkManager
import com.oshi.messenger.network.mesh.MessageType
import com.oshi.messenger.network.vps.CallSignal
import com.oshi.messenger.network.vps.CallSignalType
import com.oshi.messenger.network.vps.VPSClient
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CallManager - Voice and Video Call Handler
 *
 * Compatible with iOS OSHI VoiceCallManager:
 * - Same signaling protocol
 * - Same encryption (AES-256-GCM)
 * - VPS relay for remote calls
 * - Mesh direct for nearby calls
 */
@Singleton
class CallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vpsClient: VPSClient,
    private val meshManager: MeshNetworkManager,
    private val crossPlatformMesh: CrossPlatformMesh,
    private val cryptoManager: CryptoManager,
    private val database: AppDatabase
) {
    companion object {
        private const val TAG = "CallManager"

        // Audio settings (same as iOS for compatibility)
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2

        // Cross-platform packet types (must match iOS VoiceCallManager.swift)
        private const val AUDIO_TYPE_CODEC: Byte = 0x05        // CallPacketType.audioData (iOS codec)
        private const val AUDIO_TYPE_RAW_PCM: Byte = 0x15      // RAW_PCM_AUDIO_TYPE (cross-platform)
    }

    // 🔧 Sequence counter for audio packets (big-endian, matches iOS)
    private val audioSeq = AtomicLong(1)

    // 🔧 Relay toggle: if true, force VPS relay even when mesh peer is available
    // Either side can enable this in Settings → default is P2P mesh first
    @Volatile var useRelayMode: Boolean = false

    // Call state
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

    // Audio
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null

    /**
     * __AUDIO_FOCUS_TAKEN_AND_NEVER_GIVEN_BACK_2026_10_27__ The focus request
     * this call is holding, kept so it can actually be handed back.
     *
     * It was not kept at all: `startAudio()` built an `AudioFocusRequest`
     * inline, passed it to `requestAudioFocus` and dropped it on the floor.
     * `abandonAudioFocusRequest` takes the SAME object, so once the reference
     * was gone the focus could not be released even in principle — and
     * `stopAudio()` did not try. Every call through this manager left OSHI
     * holding AUDIOFOCUS_GAIN_TRANSIENT for ever, which is what keeps the music
     * the user had paused from ever resuming. `EnhancedCallManager` has always
     * done this correctly (:4855); this is the same bug WayCar had.
     */
    private var audioFocusRequest: AudioFocusRequest? = null

    // Ringtone & Ringback
    // __OSHI_CALL_SOUNDS_2026_09_28__ the OSHI sounds (CallSounds)
    private var ringtone: CallSounds.Handle? = null
    private var ringback: CallSounds.Handle? = null
    private var vibrator: Vibrator? = null

    // Session key for encryption
    private var sessionKey: ByteArray? = null

    // Coroutines
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioJob: Job? = null
    private var durationJob: Job? = null

    // My identity
    private var myPublicKey: String = ""

    // ============================================================================
    // INITIALIZATION
    // ============================================================================

    fun initialize(publicKey: String) {
        myPublicKey = publicKey
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Initialize vibrator
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // Listen for call signals
        vpsClient.startCallPolling { signal ->
            scope.launch {
                handleCallSignal(signal)
            }
        }

        // Listen for native mesh call signals (Android↔Android Nearby Connections)
        // Audio does NOT use native mesh because MeshMessage JSON mangles binary.
        meshManager.addMessageCallback { message ->
            if (message.type == MessageType.CALL_SIGNAL) {
                scope.launch { handleMeshCallSignal(message) }
            }
        }

        // Listen for cross-platform mesh (TCP bridge — iOS↔Android and Android↔Android)
        crossPlatformMesh.addMessageCallback { xpMessage ->
            when (xpMessage.type) {
                "CALL_SIGNAL" -> scope.launch { handleCrossPlatformCallSignal(xpMessage) }
                "CALL_AUDIO" -> handleCrossPlatformCallAudio(xpMessage)
            }
        }

        Log.d(TAG, "📞 CallManager initialized (P2P cross-platform mesh + VPS relay fallback)")
    }

    // ============================================================================
    // AUDIO PACKET FORMAT (matches iOS VoiceCallManager packet structure)
    //   [type(1)] [seq(8 big-endian)] [encrypted_payload...]
    // ============================================================================

    private fun buildAudioPacket(typeByte: Byte, encrypted: ByteArray): ByteArray {
        val seq = audioSeq.getAndIncrement()
        val packet = ByteArray(9 + encrypted.size)
        packet[0] = typeByte
        // Big-endian 8-byte sequence number (matches iOS packetNonce.bigEndian)
        for (i in 0..7) {
            packet[1 + i] = ((seq ushr (56 - i * 8)) and 0xFF).toByte()
        }
        System.arraycopy(encrypted, 0, packet, 9, encrypted.size)
        return packet
    }

    private fun parseAudioPacket(packet: ByteArray): Triple<Byte, Long, ByteArray>? {
        if (packet.size < 10) return null
        val typeByte = packet[0]
        var seq = 0L
        for (i in 0..7) {
            seq = (seq shl 8) or (packet[1 + i].toLong() and 0xFF)
        }
        val payload = packet.copyOfRange(9, packet.size)
        return Triple(typeByte, seq, payload)
    }

    // ============================================================================
    // INCOMING MESH AUDIO (Android peer → us, or iOS-via-mesh → us)
    // ============================================================================

    private fun handleMeshCallAudio(packet: ByteArray) {
        val call = _currentCall.value ?: return
        val key = sessionKey ?: return
        val parsed = parseAudioPacket(packet) ?: return
        val (typeByte, _, encrypted) = parsed

        // Detect iOS peer from packet type (0x15 = raw PCM from iOS cross-platform)
        if (typeByte == AUDIO_TYPE_RAW_PCM && call.remotePlatform != "ios") {
            call.remotePlatform = "ios"
            Log.d(TAG, "🔊 Detected iOS peer from raw PCM packet (0x15)")
        }

        try {
            val decrypted = cryptoManager.decryptAESGCM(encrypted, key)
            // Both iOS raw PCM and Android PCM16 at 48kHz mono → play directly
            playAudio(decrypted)
        } catch (e: Exception) {
            Log.w(TAG, "Mesh audio decrypt failed: ${e.message}")
        }
    }

    private fun handleCrossPlatformCallAudio(xpMessage: CrossPlatformMessage) {
        val call = _currentCall.value ?: return
        if (xpMessage.senderPublicKey != call.peerPublicKey) return

        // CrossPlatformMesh base64-encodes the packet; decode first
        val packet = try {
            android.util.Base64.decode(xpMessage.payload, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Bad base64 in cross-platform audio: ${e.message}")
            return
        }
        handleMeshCallAudio(packet)
    }

    private suspend fun handleCrossPlatformCallSignal(xpMessage: CrossPlatformMessage) {
        // Cross-platform call signals flow through the same path as mesh signals
        // The payload is base64-encoded encrypted CallSignal data
        try {
            val signalBytes = android.util.Base64.decode(xpMessage.payload, android.util.Base64.NO_WRAP)
            // Wrap in MeshMessage for handleMeshCallSignal — reuse existing logic
            val meshMsg = MeshMessage(
                id = xpMessage.id,
                type = MessageType.CALL_SIGNAL,
                senderPublicKey = xpMessage.senderPublicKey,
                senderName = xpMessage.senderName,
                payload = String(signalBytes, Charsets.ISO_8859_1),
                timestamp = xpMessage.timestamp.toLong()
            )
            handleMeshCallSignal(meshMsg)
        } catch (e: Exception) {
            Log.w(TAG, "Cross-platform signal parse failed: ${e.message}")
        }
    }

    // ============================================================================
    // RINGTONE & RINGBACK TONES
    // ============================================================================

    /**
     * Play ringtone for incoming calls
     */
    private fun playRingtone() {
        Log.d(TAG, "🔔 Playing ringtone for incoming call...")
        try {
            ringtone?.stop()
            ringtone = CallSounds.startRingtone(context)

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
     * Stop ringtone
     */
    private fun stopRingtone() {
        Log.d(TAG, "🔔 Stopping ringtone")
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
    }

    /**
     * Play ringback tone for outgoing calls (what caller hears while waiting)
     */
    private fun playRingbackTone() {
        Log.d(TAG, "📞 Playing ringback tone (waiting for answer)...")
        try {
            ringback?.stop()
            ringback = CallSounds.startRingback(context) { _callState.value == CallState.RINGING }

            Log.d(TAG, "📞 Ringback tone started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play ringback tone: ${e.message}")
        }
    }

    /**
     * Stop ringback tone
     */
    private fun stopRingbackTone() {
        Log.d(TAG, "📞 Stopping ringback tone")
        ringback?.stop()
        ringback = null
    }

    /**
     * Play connection loss beep
     */
    private fun playConnectionLossBeep() {
        Log.d(TAG, "⚠️ Playing connection loss beep")
        try {
            val toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 200)

            // Haptic feedback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(100)
            }

            // Release after tone finishes
            scope.launch {
                delay(300)
                toneGenerator.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play connection loss beep: ${e.message}")
        }
    }

    // ============================================================================
    // OUTGOING CALLS
    // ============================================================================

    /**
     * Start a voice call
     */
    /**
     * 🚫 Refuses to dial a blocked contact, like iOS's `startCall`
     * (`VoiceCallManager.swift:5533-5536`, `throw CallError.peerBlocked`). Reached from
     * the web-session call bridge (`MessageRepository.onWebCallControl`), so a linked
     * browser cannot be used to route around a block set on the phone.
     */
    suspend fun startCall(
        recipientPublicKey: String,
        recipientName: String,
        isVideoCall: Boolean = false
    ): Result<String> {
        if (_callState.value != CallState.IDLE) {
            return Result.failure(Exception("Already in a call"))
        }
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

            // Generate session key for call encryption
            sessionKey = cryptoManager.generateSymmetricKey()

            val outgoingContact = database.contactDao().getContactByPublicKey(recipientPublicKey)
            _currentCall.value = CurrentCall(
                callId = callId,
                peerPublicKey = recipientPublicKey,
                peerName = recipientName,
                peerProfilePicture = outgoingContact?.profilePicture,
                isOutgoing = true,
                isVideoCall = isVideoCall,
                startTime = System.currentTimeMillis()
            )

            _callState.value = CallState.CONNECTING

            // Create call request payload
            val requestPayload = """
                {
                    "callId": "$callId",
                    "sessionKey": "${android.util.Base64.encodeToString(sessionKey, android.util.Base64.NO_WRAP)}",
                    "isVideo": $isVideoCall
                }
            """.trimIndent()

            val encryptedPayload = cryptoManager.encryptMessage(requestPayload, recipientPublicKey)

            // Try mesh first
            val meshPeer = meshManager.connectedPeers.value.find { it.publicKey == recipientPublicKey }
            if (meshPeer != null) {
                val meshMessage = MeshMessage(
                    id = callId,
                    type = MessageType.CALL_SIGNAL,
                    senderPublicKey = myPublicKey,
                    senderName = "",
                    payload = """{"signalType": "CALL_REQUEST", "data": "${android.util.Base64.encodeToString(encryptedPayload, android.util.Base64.NO_WRAP)}"}""",
                    timestamp = System.currentTimeMillis()
                )
                meshManager.sendMessageToPublicKey(recipientPublicKey, meshMessage)
                Log.d(TAG, "📞 Call request sent via Mesh")
            } else {
                // Send via VPS
                val signal = CallSignal(
                    callId = callId,
                    senderPublicKey = myPublicKey,
                    type = CallSignalType.CALL_REQUEST,
                    payload = encryptedPayload,
                    timestamp = System.currentTimeMillis()
                )
                vpsClient.sendCallSignal(recipientPublicKey, signal)
                Log.d(TAG, "📞 Call request sent via VPS")
            }

            _callState.value = CallState.RINGING

            // 🔔 Play ringback tone for caller (waiting for answer)
            playRingbackTone()

            // Start timeout
            // 🔧 FIX: Increased timeout to 45 seconds for network delays
            scope.launch {
                delay(45000) // 45 second timeout
                if (_callState.value == CallState.RINGING) {
                    endCall(CallEndReason.NO_ANSWER)
                }
            }

            Result.success(callId)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start call: ${e.message}")
            _callState.value = CallState.IDLE
            _currentCall.value = null
            Result.failure(e)
        }
    }

    // ============================================================================
    // INCOMING CALLS
    // ============================================================================

    /**
     * Accept an incoming call
     */
    suspend fun acceptCall() {
        val call = _currentCall.value ?: return

        // 🔔 Stop ringtone immediately when answering
        stopRingtone()

        try {
            _callState.value = CallState.CONNECTING

            // Send accept signal
            val acceptPayload = """{"callId": "${call.callId}", "accepted": true}""".toByteArray()
            val encryptedPayload = cryptoManager.encryptMessage(acceptPayload.toString(Charsets.UTF_8), call.peerPublicKey)

            val signal = CallSignal(
                callId = call.callId,
                senderPublicKey = myPublicKey,
                type = CallSignalType.CALL_ACCEPT,
                payload = encryptedPayload,
                timestamp = System.currentTimeMillis()
            )

            // 🔧 FIX: Send via BOTH mesh AND VPS for maximum reliability
            // Try mesh first (faster if peer is nearby)
            val meshPeer = meshManager.connectedPeers.value.find { it.publicKey == call.peerPublicKey }
            if (meshPeer != null) {
                try {
                    val meshMessage = MeshMessage(
                        id = call.callId,
                        type = MessageType.CALL_SIGNAL,
                        senderPublicKey = myPublicKey,
                        senderName = "",
                        payload = """{"signalType": "CALL_ACCEPT", "data": "${android.util.Base64.encodeToString(encryptedPayload, android.util.Base64.NO_WRAP)}"}""",
                        timestamp = System.currentTimeMillis()
                    )
                    meshManager.sendMessageToPublicKey(call.peerPublicKey, meshMessage)
                    Log.d(TAG, "📞 Accept sent via Mesh")
                } catch (e: Exception) {
                    Log.w(TAG, "Mesh accept failed: ${e.message}")
                }
            }

            // ALWAYS also send via VPS as backup/primary
            var vpsSent = false
            for (attempt in 1..3) {
                try {
                    vpsClient.sendCallSignal(call.peerPublicKey, signal)
                    vpsSent = true
                    Log.d(TAG, "📞 Accept sent via VPS (attempt $attempt)")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "VPS accept failed (attempt $attempt): ${e.message}")
                    if (attempt < 3) delay(200L * attempt) // Exponential backoff
                }
            }

            if (!vpsSent) {
                Log.e(TAG, "❌ All accept attempts failed")
            }

            // 🔧 FIX: Resend accept signal 2 more times with delays for reliability
            // The caller relies on polling to detect accept - redundancy prevents missed signals
            if (vpsSent) {
                val resendSignal = signal
                val resendPeer = call.peerPublicKey
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        delay(1000L) // 1 second
                        vpsClient.sendCallSignal(resendPeer, resendSignal)
                        Log.d(TAG, "☁️ Accept signal resent (2/3)")
                        delay(1500L) // 1.5 seconds
                        vpsClient.sendCallSignal(resendPeer, resendSignal)
                        Log.d(TAG, "☁️ Accept signal resent (3/3)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Accept resend failed: ${e.message}")
                    }
                }
            }

            // Start audio
            startAudio()

            _callState.value = CallState.IN_CALL
            startDurationTimer()
            startOngoingCallNotification()   // __RETURN_TO_CALL_2026_10_28__

            Log.d(TAG, "📞 Call accepted")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to accept call: ${e.message}")
            endCall(CallEndReason.ERROR)
        }
    }

    /**
     * Decline an incoming call
     */
    suspend fun declineCall() {
        val call = _currentCall.value ?: return

        val signal = CallSignal(
            callId = call.callId,
            senderPublicKey = myPublicKey,
            type = CallSignalType.CALL_REJECT,
            payload = ByteArray(0),
            timestamp = System.currentTimeMillis()
        )
        vpsClient.sendCallSignal(call.peerPublicKey, signal)

        endCall(CallEndReason.DECLINED)
    }

    // ============================================================================
    // END CALL
    // ============================================================================

    fun endCall(reason: CallEndReason = CallEndReason.HUNG_UP) {
        val call = _currentCall.value ?: return

        // 🔔 Stop all tones immediately
        stopRingtone()
        stopRingbackTone()

        scope.launch {
            val peerPublicKey = call.peerPublicKey

            // 🔧 FIX: Send callEnd via BOTH mesh AND VPS for reliability
            // For P2P mesh calls, VPS might not be reachable. Mesh is always available.
            try {
                val endPayload = """{"callId": "${call.callId}", "reason": "${reason.name}"}"""
                val encryptedPayload = cryptoManager.encryptMessage(endPayload, peerPublicKey)

                // 1. Try mesh first (instant delivery if peer is nearby)
                val meshPeer = meshManager.connectedPeers.value.find { it.publicKey == peerPublicKey }
                if (meshPeer != null) {
                    val meshMessage = MeshMessage(
                        id = call.callId,
                        type = MessageType.CALL_SIGNAL,
                        senderPublicKey = myPublicKey,
                        senderName = "",
                        payload = """{"signalType": "CALL_END", "data": "${android.util.Base64.encodeToString(encryptedPayload, android.util.Base64.NO_WRAP)}"}""",
                        timestamp = System.currentTimeMillis()
                    )
                    meshManager.sendMessageToPublicKey(peerPublicKey, meshMessage)
                    Log.d(TAG, "📞 callEnd sent via Mesh")
                }

                // 2. Also send via VPS (in case peer has no mesh connection)
                val signal = CallSignal(
                    callId = call.callId,
                    senderPublicKey = myPublicKey,
                    type = CallSignalType.CALL_END,
                    payload = reason.name.toByteArray(),
                    timestamp = System.currentTimeMillis()
                )
                vpsClient.sendCallSignal(peerPublicKey, signal)
                Log.d(TAG, "📞 callEnd sent via VPS")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send end signal: ${e.message}")
            }

            // Stop audio
            stopAudio()

            // Save to history
            saveCallHistory(call, reason)

            // Reset state
            stopOngoingCallNotification()   // __RETURN_TO_CALL_2026_10_28__
            _callState.value = CallState.ENDED
            _currentCall.value = null
            sessionKey = null
            durationJob?.cancel()
            _callDuration.value = 0

            // Reset to idle after brief delay
            delay(2000)
            _callState.value = CallState.IDLE

            Log.d(TAG, "📞 Call ended: $reason")
        }
    }

    // ============================================================================
    // SIGNAL HANDLING
    // ============================================================================

    private suspend fun handleCallSignal(signal: CallSignal) {
        Log.d(TAG, "📞 Received call signal: ${signal.type}")

        when (signal.type) {
            CallSignalType.CALL_REQUEST -> handleIncomingCall(signal)
            CallSignalType.CALL_ACCEPT -> handleCallAccepted(signal)
            CallSignalType.CALL_REJECT -> handleCallRejected(signal)
            CallSignalType.CALL_END -> handleCallEnded(signal)
            CallSignalType.AUDIO_DATA -> handleAudioData(signal)
            else -> Log.w(TAG, "Unhandled signal type: ${signal.type}")
        }
    }

    private suspend fun handleMeshCallSignal(message: MeshMessage) {
        // Parse signal from mesh message
        // Similar handling to VPS signals
    }

    private suspend fun handleIncomingCall(signal: CallSignal) {
        if (_callState.value != CallState.IDLE) {
            // Already in a call, send busy signal
            return
        }

        // 🚫 SECOND ring path. `EnhancedCallManager` is the manager wired to the call
        // UI, but this one also reaches `playRingtone()` below, so gating only the
        // other would leave a blocked contact able to ring through here.
        // iOS: VoiceCallManager.swift:6265-6270 (blocked), :6280-6284 (contacts-only) —
        // both drop silently, with no decline signal, so the caller cannot learn they
        // were blocked.
        if (CallAdmissionPolicy.isBlocked(database.contactDao(), signal.senderPublicKey)) {
            Log.w(TAG, "🚫 Blocked caller — dropping CALL_REQUEST silently: ${signal.senderPublicKey.take(12)}…")
            // Still no ring and no call signal back; the caller learns through the
            // rate-limited 🚫BLOCKED🚫 chat line (__BLOCKED_NOTICE_SWITCH_2026_09_23__).
            com.oshi.messenger.data.repository.MessageRepository.noticeBlockedCaller(signal.senderPublicKey)
            return
        }
        // __BLOCKED_BY_PEER_2026_09_24__ a peer that calls us no longer blocks us.
        BlockedByPeerStore.get(context).clear(signal.senderPublicKey)
        if (CallAdmissionPolicy.rejectsUnknownCaller(context, database.contactDao(), signal.senderPublicKey)) {
            Log.w(TAG, "🚫 Call rejected — caller not in contacts (contacts-only mode)")
            return
        }

        try {
            // Decrypt payload
            val decrypted = cryptoManager.decryptMessageToString(signal.payload, signal.senderPublicKey)
            val json = org.json.JSONObject(decrypted)

            val callId = json.getString("callId")
            sessionKey = android.util.Base64.decode(json.getString("sessionKey"), android.util.Base64.NO_WRAP)
            val isVideo = json.optBoolean("isVideo", false)

            // Get contact name and profile picture
            val contact = database.contactDao().getContactByPublicKey(signal.senderPublicKey)
            // __SHARED_NICKNAME_2026_09_22__ alias > peer's shared nickname > key prefix.
            val callerName = com.oshi.messenger.data.model.PeerNicknameStore.displayName(contact?.alias, signal.senderPublicKey)

            _currentCall.value = CurrentCall(
                callId = callId,
                peerPublicKey = signal.senderPublicKey,
                peerName = callerName,
                peerProfilePicture = contact?.profilePicture,
                isOutgoing = false,
                isVideoCall = isVideo,
                startTime = System.currentTimeMillis()
            )

            _callState.value = CallState.RINGING

            // 🔔 Play ringtone for incoming call
            playRingtone()

            Log.d(TAG, "📞 Incoming call from: $callerName")
        } catch (e: Exception) {
            // Class name only: an org.json message would quote the decrypted signal.
            Log.e(TAG, "❌ Failed to handle incoming call: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun handleCallAccepted(signal: CallSignal) {
        if (_callState.value != CallState.RINGING) return

        // 🔔 Stop all tones when call connects
        stopRingtone()
        stopRingbackTone()

        startAudio()
        _callState.value = CallState.IN_CALL
        startDurationTimer()
        startOngoingCallNotification()   // __RETURN_TO_CALL_2026_10_28__

        Log.d(TAG, "📞 Call accepted by peer")
    }

    private fun handleCallRejected(signal: CallSignal) {
        endCall(CallEndReason.DECLINED)
    }

    private fun handleCallEnded(signal: CallSignal) {
        endCall(CallEndReason.PEER_ENDED)
    }

    private fun handleAudioData(signal: CallSignal) {
        // Decrypt and play audio
        sessionKey?.let { key ->
            try {
                val decryptedAudio = cryptoManager.decryptAESGCM(signal.payload, key)
                playAudio(decryptedAudio)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt audio: ${e.message}")
            }
        }
    }

    // ============================================================================
    // AUDIO
    // ============================================================================

    private fun startAudio() {
        // 🔧 FIX: Set audio mode and route for voice communication
        // Without MODE_IN_COMMUNICATION, Android doesn't activate AEC/NS and
        // audio may not route to earpiece, causing "can't hear" on calls.
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager?.isSpeakerphoneOn = false

        // Request audio focus
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            audioFocusRequest = focusRequest
            audioManager?.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR

        // AudioRecord for capture
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
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
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioRecord?.startRecording()
        audioTrack?.play()

        // Start audio capture and send
        audioJob = scope.launch {
            val buffer = ByteArray(bufferSize)
            while (isActive && _callState.value == CallState.IN_CALL) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0 && !_isMuted.value) {
                    sendAudio(buffer.copyOf(read))
                }
                delay(20) // ~50 packets/second
            }
        }

        Log.d(TAG, "🔊 Audio started")
    }

    private fun stopAudio() {
        audioJob?.cancel()

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null

        // 🔧 FIX: Reset audio mode back to normal
        audioManager?.mode = AudioManager.MODE_NORMAL
        audioManager?.isSpeakerphoneOn = false

        // __AUDIO_FOCUS_TAKEN_AND_NEVER_GIVEN_BACK_2026_10_27__ Give the focus
        // back. Without this the mode was restored but the focus was not, so
        // whatever was ducked or paused when the call started stayed that way.
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not abandon audio focus: ${e.message}")
        }
        audioFocusRequest = null

        Log.d(TAG, "🔇 Audio stopped")
    }

    /**
     * Send captured audio. Routing:
     *   1. If useRelayMode == true (settings toggle) → VPS relay
     *   2. Else if peer is on CrossPlatformMesh (TCP bridge, Android↔Android and Android↔iOS)
     *      → P2P mesh (preferred, works for both platforms)
     *   3. Else → VPS relay fallback
     *
     * Packet format matches iOS VoiceCallManager:
     *   [type(1)] [seq(8 big-endian)] [AES-GCM encrypted PCM16 mono 48kHz]
     *   type = 0x15 (raw PCM) — both iOS and Android capture PCM16 @ 48kHz mono.
     *
     * Note: native MeshNetworkManager is NOT used for audio because its JSON
     * payload serialization mangles binary data. CrossPlatformMesh base64-encodes
     * the whole packet so binary is preserved.
     */
    private suspend fun sendAudio(audioData: ByteArray) {
        val call = _currentCall.value ?: return
        val key = sessionKey ?: return
        val peerPk = call.peerPublicKey

        val encrypted = try {
            cryptoManager.encryptAESGCM(audioData, key)
        } catch (e: Exception) {
            return
        }

        // ---- Path selection ----
        if (useRelayMode) {
            sendAudioViaVps(call.callId, encrypted)
            return
        }

        // Try cross-platform mesh (TCP-based P2P, works for Android↔Android and Android↔iOS)
        val xpPeer = crossPlatformMesh.connectedPeers.value.find { it.publicKey == peerPk }
        if (xpPeer != null) {
            val packet = buildAudioPacket(AUDIO_TYPE_RAW_PCM, encrypted)
            crossPlatformMesh.sendCallAudio(peerPk, packet)
            if (call.remotePlatform == "unknown") call.remotePlatform = xpPeer.platform
            return
        }

        // Fallback: VPS relay (when no direct mesh connection available)
        sendAudioViaVps(call.callId, encrypted)
    }

    private suspend fun sendAudioViaVps(callId: String, encrypted: ByteArray) {
        try {
            vpsClient.sendCallAudio(callId, encrypted)
        } catch (e: Exception) {
            // Ignore send errors during call
        }
    }

    private fun playAudio(audioData: ByteArray) {
        audioTrack?.write(audioData, 0, audioData.size)
    }

    // ============================================================================
    // CONTROLS
    // ============================================================================

    fun toggleMute() {
        _isMuted.value = !_isMuted.value
        Log.d(TAG, "🎤 Mute: ${_isMuted.value}")
    }

    fun toggleSpeaker() {
        _isSpeakerOn.value = !_isSpeakerOn.value
        audioManager?.isSpeakerphoneOn = _isSpeakerOn.value
        Log.d(TAG, "🔊 Speaker: ${_isSpeakerOn.value}")
    }

    // ============================================================================
    // HELPERS
    // ============================================================================


    /**
     * __RETURN_TO_CALL_2026_10_28__ N19, reported by a user: *"lors qu'on écrit un
     * message pendant l'appel il est impossible de retrouver la page d'appel pour
     * raccrocher"*.
     *
     * There was no way back because there was no ongoing-call notification at all.
     * `CallService` exists, builds exactly that notification — title "<Voice call> with
     * <name>", a tappable body whose string is `channel_ongoing_call_action`
     * ("Tap to return to call"), and an End Call action — and its channel name,
     * description and action are translated into all 34 locales.
     *
     * **Nothing ever started it.** `grep CallService::class` across the app returned only
     * its own file. The feature was written, localised and never wired, so leaving the
     * call screen left the call with no handle on it: no banner, no notification, and the
     * only way to hang up was to find the screen again by luck.
     *
     * Started when the call becomes IN_CALL and stopped wherever it ends, with the peer
     * name RESOLVED (alias > shared nickname > key prefix) rather than CallService's
     * "Unknown" default — the same N19 mail also says the alias is missing during a call.
     */
    private fun startOngoingCallNotification() {
        val call = _currentCall.value ?: return
        runCatching {
            val intent = android.content.Intent(context, CallService::class.java).apply {
                action = CallService.ACTION_START_CALL
                putExtra(CallService.EXTRA_PEER_NAME, call.peerName)
                putExtra(CallService.EXTRA_PEER_KEY, call.peerPublicKey)
                putExtra(CallService.EXTRA_IS_VIDEO, call.isVideoCall)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }.onFailure { Log.w(TAG, "ongoing-call notification not started: ${it.message}") }
    }

    private fun stopOngoingCallNotification() {
        runCatching {
            context.startService(
                android.content.Intent(context, CallService::class.java).apply {
                    action = CallService.ACTION_END_CALL
                }
            )
        }.onFailure { Log.w(TAG, "ongoing-call notification not stopped: ${it.message}") }
    }

    private fun startDurationTimer() {
        durationJob = scope.launch {
            while (isActive && _callState.value == CallState.IN_CALL) {
                delay(1000)
                _callDuration.value = _callDuration.value + 1
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

    fun cleanup() {
        stopAudio()
        scope.cancel()
    }
}

// ============================================================================
// DATA CLASSES
// ============================================================================

enum class CallState {
    IDLE,
    CONNECTING,
    RINGING,
    IN_CALL,
    ENDED
}

enum class CallEndReason {
    HUNG_UP,
    PEER_ENDED,
    DECLINED,
    NO_ANSWER,
    MISSED,
    ERROR,
    CONNECTION_LOST,
    ANSWERED_ELSEWHERE
}

enum class ConnectionType {
    MESH_DIRECT,  // Direct P2P (cross-platform)
    MESH,         // Native mesh relay
    VPS,          // Server relay
    TOR,          // Tor hidden service
    CROSS_PLATFORM, // Cross-platform mesh (iOS ↔ Android)
    RELAY         // Via relay node
}

data class CurrentCall(
    val callId: String,
    val peerPublicKey: String,
    val peerName: String,
    val peerProfilePicture: ByteArray? = null,
    val isOutgoing: Boolean,
    val isVideoCall: Boolean,
    val startTime: Long,
    // Cross-platform: "ios" or "android" — detected at call time, determines audio format
    @Volatile var remotePlatform: String = "unknown"
)
