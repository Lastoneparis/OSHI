import SwiftUI
import AVFoundation
import AVKit
import CryptoKit
import UIKit

// MARK: - Keyboard dismissal helper (iOS)
// Mirrors the helper used in VoiceCallView.swift. We need this here because
// when the user upgrades a voice call to video (or accepts an incoming video
// call) the underlying ChatView's message TextField may still hold first
// responder, leaving the on-screen keyboard floating on top of the live video.
// Calling this on appear / state transitions resigns first responder so the
// keyboard collapses and stays down for the duration of the call.
private func dismissAnyKeyboard() {
    #if canImport(UIKit) && os(iOS)
    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder),
                                    to: nil, from: nil, for: nil)
    #endif
}

// MARK: - Video Call View
struct VideoCallView: View {
    @ObservedObject var voiceCallManager: VoiceCallManager
    @StateObject private var videoManager = VideoCallManager()
    // File logger for debugging
    private let fileLog = CallFileLogger.shared
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dismiss) private var dismiss

    @State private var showControls = true
    @State private var controlsTimer: Timer?
    @State private var showQualityPicker = false
    @State private var showBlurPicker = false
    @State private var showEffectPicker = false
    @State private var localVideoOffset = CGSize.zero
    @State private var localVideoPosition: CGPoint? = nil  // Lazy init from geometry
    @State private var backgroundTaskID: UIBackgroundTaskIdentifier = .invalid
    @State private var reconnectingPulse = false
    @State private var showCallEndedOverlay = false
    @State private var showTransportDetails = false

    let contactName: String
    let contactPublicKey: String

    // Convenience initializer that extracts info from voiceCallManager
    init(voiceCallManager: VoiceCallManager) {
        self._voiceCallManager = ObservedObject(wrappedValue: voiceCallManager)
        self.contactName = voiceCallManager.currentCallPeerName ?? "call.video.default".localized
        self.contactPublicKey = voiceCallManager.currentCallPeer ?? ""
    }

    // Full initializer
    init(voiceCallManager: VoiceCallManager, contactName: String) {
        self._voiceCallManager = ObservedObject(wrappedValue: voiceCallManager)
        self.contactName = contactName
        self.contactPublicKey = voiceCallManager.currentCallPeer ?? ""
    }
    
    /// The full-screen call UI.
    ///
    /// __CALL_MINIMIZE_VIDEO_2026_08_20__ Extracted so `body` can swap it for
    /// the compact pill WITHOUT this view leaving the hierarchy. That is not a
    /// style choice: `videoManager` is a `@StateObject` owned right here, so
    /// unmounting `VideoCallView` deallocates the capture session, the encoder,
    /// the decoder and the display layer — i.e. minimizing a video call the way
    /// audio calls do it would tear the whole video pipeline down and rebuild it
    /// on restore, with a new session key exchange. Hence the previous rule that
    /// only audio calls could be minimized.
    private var fullScreenCall: some View {
        GeometryReader { geometry in
            ZStack {
                // Background
                Color.black.ignoresSafeArea()
                
                // Remote video (full screen)
                remoteVideoView

                // 📶 Reconnecting border pulse
                if voiceCallManager.connectionQuality == .reconnecting {
                    RoundedRectangle(cornerRadius: 0)
                        .stroke(Color.orange, lineWidth: 4)
                        .ignoresSafeArea()
                        .opacity(reconnectingPulse ? 0.8 : 0.2)
                        .animation(
                            Animation.easeInOut(duration: 0.8).repeatForever(autoreverses: true),
                            value: reconnectingPulse
                        )
                }

                // 📹 Multilingual "Camera paused" overlay when peer backgrounds
                if voiceCallManager.isRemoteCameraPaused {
                    cameraPausedOverlay
                }

                // 📶 Say what the picture is actually doing.
                if !voiceCallManager.isRemoteCameraPaused,
                   videoManager.isRemoteVideoStalled || videoManager.isRemoteVideoWeak {
                    remoteVideoHealthBadge
                }

                // Local video (PiP)
                localVideoPreview(in: geometry)
                
                // Controls overlay
                if showControls {
                    controlsOverlay(in: geometry)
                }
                
                // Quality picker
                if showQualityPicker {
                    qualityPickerOverlay
                }
                
                // Blur picker
                if showBlurPicker {
                    blurPickerOverlay
                }

                // 🔧 FIX: "Call Ended" overlay — shown when remote party ends the call
                // Provides visual feedback before the view is dismissed
                if showCallEndedOverlay {
                    Color.black.opacity(0.7)
                        .ignoresSafeArea()
                    VStack(spacing: 16) {
                        Image(systemName: "phone.down.fill")
                            .font(.scaled(48))
                            .foregroundColor(.red)
                        Text(voiceCallManager.lastCallEndReason?.displayText ?? "call.ended.hungup".localized)
                            .font(.title2.bold())
                            .foregroundColor(.white)
                    }
                    .transition(.opacity.combined(with: .scale(scale: 0.9)))
                }
            }
        }
    }

    var body: some View {
        // `SwiftUI.` is not noise: OSHI has its own `Group` model type for chat
        // groups, and inside this module the bare name resolves to THAT — which
        // fails with "trailing closure passed to parameter of type 'any Decoder'".
        SwiftUI.Group {
            if voiceCallManager.isCallMinimized, voiceCallManager.callState == .inCall {
                minimizedVideoPill
            } else {
                fullScreenCall
            }
        }
        .onChange(of: videoManager.isReceivingVideo) { _, _ in syncControlsToRemoteVideo() }
        .onChange(of: voiceCallManager.isRemoteCameraPaused) { _, _ in syncControlsToRemoteVideo() }
        .onChange(of: videoManager.isRemoteVideoStalled) { _, _ in syncControlsToRemoteVideo() }
        .sheet(isPresented: $showEffectPicker) {
            VoiceEffectPickerView(voiceCallManager: voiceCallManager)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .onAppear {
            fileLog.log("📹 VideoCallView: onAppear — setting up video call (callState=\(voiceCallManager.callState))")
            // 🔧 UX FIX: Dismiss any keyboard the underlying ChatView left up.
            // Without this, the message-input TextField behind the call view
            // keeps first responder and the on-screen keyboard renders on top
            // of the live video, hiding the End-Call button.
            dismissAnyKeyboard()
            fileLog.log("📹 VideoCallView: dismissed any active keyboard on appear")
            setupVideoManager()
            // 📹 Directional: if the user accepted "watch only" (shareOwnVideo
            // == false) we render the peer's video but DON'T start our camera.
            if voiceCallManager.shareOwnVideo {
                startVideo()
            } else {
                videoManager.startVideoReceiveOnly()
                fileLog.log("📹 VideoCallView: receive-only (camera off) — peer video only")
            }
            startControlsTimer()
            // 🔧 FIX: Force IDR keyframes AFTER camera/encoder have started (not immediately).
            // startVideo() is async — encoder isn't ready yet when onAppear runs.
            // Stagger 3 IDRs to guarantee the remote decoder gets a valid keyframe.
            Task {
                // Wait for camera/encoder setup to complete
                try? await Task.sleep(nanoseconds: 800_000_000)  // 800ms
                videoManager.forceKeyframe()
                fileLog.log("📹 VideoCallView: forced IDR #1 (800ms after appear)")
                try? await Task.sleep(nanoseconds: 500_000_000)  // +500ms
                videoManager.forceKeyframe()
                fileLog.log("📹 VideoCallView: forced IDR #2 (1.3s after appear)")
                try? await Task.sleep(nanoseconds: 1_000_000_000)  // +1s
                videoManager.forceKeyframe()
                fileLog.log("📹 VideoCallView: forced IDR #3 (2.3s after appear)")
            }
            // 📺 Setup PiP after the camera settles.
            //
            // 🔧 FIX (video-only path back into the zero-audio bug fixed in 2f25823):
            // this used to call `voiceCallManager.restartAudioEngineIfNeeded()` 800 ms
            // after the view appeared. That function does an UNCONDITIONAL
            // stopAudioEngine() + startAudioEngine(), bypassing
            // `startAudioEngineForCall` — the CallKit-aware entry point. On a
            // CallKit-answered video call this view appears when callState flips to
            // .inCall, which is BEFORE CallKit's didActivate, so the forced rebuild
            // landed on a session CallKit had not activated yet: setActive fails
            // 561017449 and the graph runs with no hardware binding = silence.
            // Nothing here needs it — the session mode is already .videoChat for
            // voice and video alike, and didActivate owns building the graph.
            Task {
                try? await Task.sleep(nanoseconds: 500_000_000)
                videoManager.setupPiP()
            }
        }
        .onDisappear {
            // 🔧 FIX: Only stop video when call is actually ending, NOT during iPad rotation
            // SwiftUI fires onDisappear during device rotation / layout changes.
            //
            // 📹 NEW: Also stop video on a mid-call revert — i.e. callState is
            // still .inCall but isCurrentCallVideo just flipped to false because
            // either us or the peer tapped "Stop video". ContentView swaps us
            // out for VoiceCallView in that case; the camera must release here.
            // (The notification path also calls stopVideo, but onDisappear is
            // an extra safety net in case the notification was missed because
            // our listener was torn down too quickly.)
            let callIsEnding = voiceCallManager.callState != .inCall && voiceCallManager.callState != .connecting
            let revertedToVoiceMidCall = !voiceCallManager.isCurrentCallVideo
            if callIsEnding || revertedToVoiceMidCall {
                fileLog.log("📹 VideoCallView: onDisappear — stopping video (callIsEnding=\(callIsEnding) revertedToVoiceMidCall=\(revertedToVoiceMidCall))")
                videoManager.stopVideo()
            } else {
                fileLog.log("📹 VideoCallView: onDisappear — rotation/layout change, keeping video alive (state=\(voiceCallManager.callState))")
            }
        }
        .onChange(of: voiceCallManager.callState) { _, newState in
            if newState == .inCall {
                // 🔧 UX FIX: Re-dismiss keyboard at the moment the call goes
                // fullscreen. Covers the voice→video upgrade path where the
                // user first saw the keyboard, accepted the upgrade, and the
                // VideoCallView mounts while the TextField is still focused.
                dismissAnyKeyboard()
                fileLog.log("📹 VideoCallView: dismissed any active keyboard on .inCall transition")
                // 🔧 CRITICAL: Force multiple IDR keyframes after connecting
                // The other side needs an IDR to start decoding — P-frames alone won't work
                // Send 3 IDRs at staggered intervals to guarantee delivery
                fileLog.log("📹 VideoCallView: call connected — forcing IDR keyframes (3x staggered)")
                videoManager.forceKeyframe()
                // Second IDR after 500ms (in case first was dropped or session key not ready)
                Task {
                    try? await Task.sleep(nanoseconds: 500_000_000)
                    videoManager.forceKeyframe()
                    fileLog.log("📹 VideoCallView: forced IDR #2 (500ms)")
                }
                // Third IDR after 1.5s (final safety)
                Task {
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                    videoManager.forceKeyframe()
                    fileLog.log("📹 VideoCallView: forced IDR #3 (1.5s)")
                }
            } else if case .ended = newState {
                // 🔧 FIX: Proactively stop video when call ends (don't rely only on onDisappear)
                // This ensures camera/encoder are released before SwiftUI removes the view
                fileLog.log("📹 VideoCallView: call ended — stopping video proactively")
                videoManager.stopVideo()
                // 🔧 FIX: Show "Call Ended" overlay briefly, then force dismiss
                // This ensures the video call view is dismissed when the remote party ends the call,
                // even if ContentView's conditional rendering has a timing issue
                if !showCallEndedOverlay {
                    withAnimation(.easeInOut(duration: 0.3)) {
                        showCallEndedOverlay = true
                    }
                    // Force dismiss after brief delay — belt-and-suspenders with ContentView's conditional
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                        dismiss()
                    }
                }
            } else if newState == .idle {
                // 🔧 FIX: Also handle idle state — force dismiss if still showing
                // This catches cases where callState skips .ended and goes straight to .idle
                fileLog.log("📹 VideoCallView: call idle — forcing dismiss")
                videoManager.stopVideo()
                dismiss()
            }
        }
        // 🔧 FIX: Handle app background/foreground with PiP for video calls
        // PiP: Keep RECEIVING remote video in floating window. Camera pauses (iOS limitation
        // for 3rd-party apps — only FaceTime can keep camera in background).
        // Sends videoPaused signal to peer so they show "Camera paused" message.
        .onChange(of: scenePhase) { _, newPhase in
            guard voiceCallManager.callState == .inCall else { return }
            if newPhase == .background {
                fileLog.log("📹 VideoCallView: App backgrounded — starting PiP, pausing camera")
                videoManager.startPiP()
                // 🔧 iOS 3rd-party limitation: AVCaptureSession stops in background.
                // Request background task to keep encoding a few more seconds.
                requestBackgroundTask()
                // Notify peer that our camera is paused
                voiceCallManager.sendVideoPausedSignal()
            } else if newPhase == .active {
                fileLog.log("📹 VideoCallView: App foregrounded — resuming camera")
                videoManager.stopPiP()
                endBackgroundTask()
                // Resume capture (camera was paused by iOS in background)
                videoManager.resumeCapture()
                VoiceCallManager.shared.sendKeyframeRequest()
                // Notify peer that our camera is back
                voiceCallManager.sendVideoResumedSignal()
                // 🔧 UX FIX: Foregrounding from PiP/background can re-present
                // a previously-focused TextField's keyboard on top of the
                // video. Force-resign here so it stays down.
                dismissAnyKeyboard()
                fileLog.log("📹 VideoCallView: dismissed any active keyboard on foreground")
            }
        }
        // 🔧 FIX: Track connection quality changes for reconnection feedback
        .onChange(of: voiceCallManager.connectionQuality) { _, newQuality in
            if newQuality == .reconnecting {
                reconnectingPulse = true
                // Warning haptic
                let generator = UINotificationFeedbackGenerator()
                generator.notificationOccurred(.warning)
            } else if reconnectingPulse {
                reconnectingPulse = false
                // Success haptic when reconnected
                let generator = UINotificationFeedbackGenerator()
                generator.notificationOccurred(.success)
            }
        }
        .sheet(isPresented: $showTransportDetails) {
            TransportDetailsSheet(voiceCallManager: voiceCallManager)
                .presentationDetents([.medium])
        }
        // 🔧 FIX: Listen for StopVideoCall notification as a fallback dismiss mechanism
        // This notification is posted by VoiceCallManager.endCall() and guarantees
        // the video view is dismissed even if SwiftUI's conditional rendering is delayed
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("StopVideoCall"))) { _ in
            fileLog.log("📹 VideoCallView: Received StopVideoCall notification — forcing cleanup")
            videoManager.stopVideo()
            if !showCallEndedOverlay {
                withAnimation(.easeInOut(duration: 0.3)) {
                    showCallEndedOverlay = true
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                    dismiss()
                }
            }
        }
        // 📹 Mid-call revert (video → voice) — peer or local user tapped "stop
        // video" but the audio call is STILL ALIVE. Tear down the camera and
        // local video pipeline only; do NOT show the "Call Ended" overlay and
        // do NOT call dismiss(). ContentView observes voiceCallManager.isCurrentCallVideo
        // and will swap us out for VoiceCallView automatically.
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("StopVideoMidCall"))) { _ in
            fileLog.log("📹 VideoCallView: Received StopVideoMidCall — stopping camera, keeping call alive")
            videoManager.stopVideo()
            // No showCallEndedOverlay, no dismiss(). ContentView's reactive
            // rendering on isCurrentCallVideo handles the view swap.
        }
        // 🔧 FIX: Listen for CallEnded notification — covers cases where call ended normally
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("CallEnded"))) { _ in
            fileLog.log("📹 VideoCallView: Received CallEnded notification — ensuring dismiss")
            if !showCallEndedOverlay {
                videoManager.stopVideo()
                withAnimation(.easeInOut(duration: 0.3)) {
                    showCallEndedOverlay = true
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                    dismiss()
                }
            }
        }
        .onTapGesture {
            // 🔧 UX FIX: Defensive dismissal — if a stray TextField managed to
            // re-acquire focus (e.g. system reasserting focus after a modal
            // dismiss), any tap on the video collapses the keyboard.
            dismissAnyKeyboard()
            withAnimation(.easeInOut(duration: 0.3)) {
                showControls.toggle()
            }
            if showControls {
                startControlsTimer()
            }
        }
        .statusBarHidden(true)
    }
    
    // MARK: - Setup
    private func setupVideoManager() {
        fileLog.log("📹 VideoCallView: setupVideoManager START callState:\(voiceCallManager.callState) isVideo:\(voiceCallManager.isCurrentCallVideo)")

        // Connect VideoCallManager to VoiceCallManager for session key
        videoManager.voiceCallManager = voiceCallManager
        // __VUPG_CAMERA_SILENT_2026_09_13__ `onError` était déclaré comme le
        // canal d'erreur de la caméra et n'était assigné nulle part: un refus
        // d'accès mourait ici, laissant une vignette noire sans explication et
        // un pair qui attend des trames pour rien. On le branche, et
        // `reportVideoFailure` prévient l'utilisateur ET le pair (0x07).
        videoManager.onError = { reason in
            VoiceCallManager.shared.reportVideoFailure(reason)
        }

        // Set session key directly if available (convert SymmetricKey to Data)
        if let symmetricKey = voiceCallManager.sessionKey {
            let keyData = symmetricKey.withUnsafeBytes { Data($0) }
            videoManager.setSessionKey(keyData)
            fileLog.log("📹 VideoCallView: Session key set (\(keyData.count) bytes)")
        } else {
            fileLog.log("❌ VideoCallView: No session key available — will retry in receiveVideoPacket")
        }

        // Setup video packet callback
        videoManager.onVideoPacketReady = { [weak voiceCallManager] packet in
            voiceCallManager?.sendVideoPacket(packet)
        }

        // 🔧 FIX: Use setVideoPacketReceiver to connect AND flush any buffered packets
        // This ensures early video packets (including first keyframe) are not lost
        voiceCallManager.setVideoPacketReceiver { [weak videoManager] packet in
            videoManager?.receiveVideoPacket(packet)
        }
        fileLog.log("📹 VideoCallView: setupVideoManager DONE — callbacks connected")
    }

    private func startVideo() {
        fileLog.log("📹 VideoCallView: startVideo called")
        videoManager.startVideo()
    }
    
    /// Auto-hide exists to get the buttons out of the way OF THE VIDEO.
    ///
    /// 🔧 FIX (2026-08-20): it fired unconditionally, five seconds after the call
    /// screen appeared — including when no remote frame had ever arrived. On the
    /// 2026-08-20 call the peer's stream ran at 0.57 fps, so what "uncovering the
    /// video" actually produced was a black rectangle with nothing on it: no
    /// mute, no camera button, no effects button, and no hint that a tap brings
    /// them back. That is the "the call screen took over the whole display, and I
    /// never saw a button to change the voice" report — the controls were there,
    /// for five seconds, over nothing.
    ///
    /// So: only hide the controls when there is something underneath them.
    private var hasSomethingToUncover: Bool {
        // `isReceivingVideo` LATCHES: it flips true on the first packet and only
        // clears at teardown. So a peer whose stream simply dies — their app
        // crashed, the link collapsed, or the 2026-08-20 case where frames fell
        // to 0.57/s — still reads as "receiving", and the controls would hide
        // over a frozen frame with the "no video" badge floating on top of
        // nothing. The measured stall is the third condition.
        videoManager.isReceivingVideo
            && !voiceCallManager.isRemoteCameraPaused
            && !videoManager.isRemoteVideoStalled
    }

    private func startControlsTimer() {
        controlsTimer?.invalidate()
        guard hasSomethingToUncover else { return }
        controlsTimer = Timer.scheduledTimer(withTimeInterval: 6.0, repeats: false) { _ in
            withAnimation(.easeInOut(duration: 0.3)) {
                showControls = false
            }
        }
    }

    /// Bring the controls back when the video underneath them goes away, and arm
    /// the timer when it returns. Without this the guard above would only ever be
    /// consulted at the moment a tap happened.
    private func syncControlsToRemoteVideo() {
        if hasSomethingToUncover {
            if showControls { startControlsTimer() }
        } else {
            controlsTimer?.invalidate()
            controlsTimer = nil
            if !showControls {
                withAnimation(.easeInOut(duration: 0.3)) { showControls = true }
            }
        }
    }
    
    // MARK: - Minimized video pill  __CALL_MINIMIZE_VIDEO_2026_08_20__

    /// The video call, reduced to a bar so the rest of OSHI is usable.
    ///
    /// The audio call has had this since 2026-08-16; video was deliberately
    /// excluded, with the stated reason that "a minimized video call would keep
    /// the camera on with no preview". That objection is answered here rather
    /// than inherited: the pill carries a LIVE thumbnail — the peer's picture
    /// when we are receiving one, our own camera when we are only sending — so
    /// the camera is never on behind a UI that hides it. When neither side is
    /// sending, the thumbnail says so in as many words.
    ///
    /// It renders from inside `VideoCallView` on purpose. `MinimizedCallBar` in
    /// ContentView cannot show any of this: `videoManager` lives here, and
    /// reaching it from there would mean unmounting this view, which is exactly
    /// what must not happen.
    private var minimizedVideoPill: some View {
        HStack(spacing: 10) {
            minimizedThumbnail
                .frame(width: 44, height: 44)
                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .stroke(Color.white.opacity(0.35), lineWidth: 0.5)
                )
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 1) {
                Text(contactName)
                    .font(.footnote.weight(.semibold))
                    .foregroundColor(.white)
                    .lineLimit(1)
                Text(formattedDuration)
                    .font(.caption2.monospacedDigit())
                    .foregroundColor(.white.opacity(0.85))
            }

            Spacer(minLength: 8)

            Button {
                voiceCallManager.toggleMute()
            } label: {
                Image(systemName: voiceCallManager.isMuted ? "mic.slash.fill" : "mic.fill")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(width: 32, height: 32)
                    .background(Circle().fill(Color.white.opacity(voiceCallManager.isMuted ? 0.32 : 0.16)))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(voiceCallManager.isMuted
                ? "call.unmute".localized
                : "call.mute".localized))

            Button {
                voiceCallManager.endCall()
            } label: {
                Image(systemName: "phone.down.fill")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(width: 32, height: 32)
                    .background(Circle().fill(Color.red))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("accessibility.call.end".localized))
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background(
            Capsule().fill(Color.green.opacity(0.92))
                .shadow(color: .black.opacity(0.22), radius: 10, y: 3)
        )
        // Everything but the two buttons is the way back. The shape is bound
        // HERE, to the bar — applied after the expanding frames below it would
        // make the tap target the whole screen and swallow every tap in the app.
        // See the note on `MinimizedCallBar`, where that is what happened.
        .contentShape(Capsule())
        .onTapGesture { restoreFromMinimized() }
        .padding(.horizontal, 14)
        .frame(maxWidth: 520)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isButton)
        .accessibilityHint(Text("call.minimize.restore".localized))
    }

    @ViewBuilder
    private var minimizedThumbnail: some View {
        // ⚠️ Deliberately NOT the remote `AVSampleBufferDisplayLayer`.
        //
        // Showing it here would mean reparenting that layer into this pill and
        // back on restore. An `AVPictureInPictureController` is observing that
        // exact layer — its window, its video rect — and moving it under AVKit is
        // the same shape as the crash documented on `VideoCallManager.teardownPiP`
        // (`OSHI-2026-08-20-202149.ips`). A nicer thumbnail is not worth
        // re-opening that.
        //
        // Your own camera is a plain `CGImage` with no such entanglement, so that
        // is what the pill shows when you are sharing — which is also the state
        // that actually needs a visible indicator, because the camera is on while
        // the call UI is not.
        if let local = videoManager.localVideoImage, voiceCallManager.shareOwnVideo {
            Image(decorative: local, scale: 1.0, orientation: .up)
                .resizable()
                .scaledToFill()
        } else {
            ZStack {
                Color.black.opacity(0.35)
                Image(systemName: "video.slash.fill")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(.white.opacity(0.9))
            }
        }
    }

    /// Back to full screen. The remote layer was off screen while minimized, so
    /// what it holds is whatever frame happened to be last; ask for a fresh IDR
    /// rather than showing a stale one until the peer's own keyframe interval
    /// comes round.
    private func restoreFromMinimized() {
        withAnimation(.easeInOut(duration: 0.22)) {
            voiceCallManager.isCallMinimized = false
        }
        videoManager.forceKeyframe()
        voiceCallManager.sendKeyframeRequest()
    }

    // MARK: - Remote Video View
    @ViewBuilder
    private var remoteVideoView: some View {
        if voiceCallManager.isRemoteCameraPaused {
            // 📹 Peer accepted "watch only" or turned their camera off mid-call.
            // Show a clear "camera off" placeholder instead of a frozen frame.
            VStack(spacing: 16) {
                ZStack {
                    AvatarFill(key: contactPublicKey, size: 120)
                    Image(systemName: "video.slash.fill")
                        .font(.scaled(44))
                        .foregroundColor(AvatarIdentity.ink(for: contactPublicKey))
                }
                Text(contactName)
                    .font(.title2)
                    .foregroundColor(.white)
                Text("call.video.camera_off".localized)
                    .font(.subheadline)
                    .foregroundColor(.white.opacity(0.7))
                Text(formattedDuration)
                    .font(.headline)
                    .foregroundColor(.white)
                    .padding(.top, 10)
            }
        } else if videoManager.isReceivingVideo {
            // AVSampleBufferDisplayLayer — renders decoded H.264 frames directly
            // Also serves as the content source for PiP
            RemoteVideoLayerView(displayLayer: videoManager.displayLayer, videoManager: videoManager)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .ignoresSafeArea()
        } else {
            // Placeholder when no remote video
            VStack(spacing: 20) {
                // Contact avatar — profile photo when saved, else initial
                ZStack {
                    // __AVATAR_SAME_COLOUR_EVERYWHERE_2026_11_09__ was a fixed
                    // blue→purple for everyone; now the contact's own
                    // `AvatarIdentity` colour, as in the conversation list.
                    AvatarFill(key: contactPublicKey, size: 120)

                    if let photo = ContactPhotoManager.shared.getPhoto(for: contactPublicKey) {
                        Image(uiImage: photo)
                            .resizable()
                            .scaledToFill()
                            .frame(width: 120, height: 120)
                            .clipShape(Circle())
                    } else {
                        Text(String(contactName.prefix(1)).uppercased())
                            .font(.scaled(48, weight: .semibold))
                            .foregroundColor(AvatarIdentity.ink(for: contactPublicKey))
                    }
                }
                
                Text(contactName)
                    .font(.title2)
                    .foregroundColor(.white)
                
                if videoManager.isReceivingVideo {
                    Text("call.video.connecting".localized)
                        .font(.subheadline)
                        .foregroundColor(.white.opacity(0.7))
                } else if videoManager.isRemoteVideoNeverArrived {
                    // __PEER_VIDEO_NEVER_ARRIVED_2026_08_26__ « En attente de la
                    // vidéo… » est vrai pendant quelques secondes et devient un
                    // mensonge ensuite. Sur l'appel du 26 août il est resté
                    // affiché tout du long, sans badge ni ligne de journal, et
                    // ce qui a été rapporté c'est « je ne voyais pas Zak ».
                    //
                    // Les deux chaînes sont celles du badge de santé, RÉUTILISÉES
                    // telles quelles: elles disent déjà exactement ceci dans les
                    // 34 langues. Un couple de clés neuves aurait voulu 34
                    // traductions pour une phrase qui existe déjà — et 34
                    // occasions d'en rater une.
                    //
                    // « Le son n'est pas affecté » porte le vrai poids: l'instinct
                    // devant une vidéo absente est de raccrocher et de rappeler,
                    // ce qui jette un appel vocal qui marche. C'est exactement ce
                    // qui s'est passé — plusieurs appels d'affilée.
                    VStack(spacing: 4) {
                        Text("call.video.rx.stalled".localized)
                            .font(.subheadline)
                            .foregroundColor(.white.opacity(0.85))
                        Text("call.video.rx.audio_ok".localized)
                            .font(.caption)
                            .foregroundColor(.white.opacity(0.6))
                    }
                } else {
                    Text("call.video.waiting".localized)
                        .font(.subheadline)
                        .foregroundColor(.white.opacity(0.7))
                }
                
                // Call duration
                Text(formattedDuration)
                    .font(.headline)
                    .foregroundColor(.white)
                    .padding(.top, 10)
            }
        }
    }
    
    // MARK: - Remote video health badge

    /// A frozen frame and a dead call look identical, and until now OSHI showed
    /// the same thing for both: the last decoded picture, forever, with no word
    /// about it. On the 2026-08-20 call the peer delivered 0.57 frames per second
    /// for four minutes and the screen simply held a still image — reported
    /// afterwards as "I never saw his video".
    ///
    /// The badge states the measured fact and, deliberately, that audio is fine —
    /// because the instinct on seeing a frozen picture is to hang up and call
    /// back, which throws away a working voice call.
    private var remoteVideoHealthBadge: some View {
        VStack {
            Spacer()
            HStack(spacing: 8) {
                Image(systemName: videoManager.isRemoteVideoStalled
                      ? "wifi.exclamationmark" : "gauge.with.dots.needle.33percent")
                    .font(.footnote.weight(.semibold))
                VStack(alignment: .leading, spacing: 1) {
                    Text(videoManager.isRemoteVideoStalled
                         ? "call.video.rx.stalled".localized
                         : "call.video.rx.weak".localized)
                        .font(.footnote.weight(.semibold))
                    Text("call.video.rx.audio_ok".localized)
                        .font(.caption2)
                        .opacity(0.85)
                }
            }
            .foregroundColor(.white)
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .background(Capsule().fill(Color.black.opacity(0.62)))
            .padding(.bottom, 190)
        }
        .frame(maxWidth: .infinity)
        .allowsHitTesting(false)
        .transition(.opacity)
        .accessibilityElement(children: .combine)
    }

    // MARK: - Local Video Preview
    @ViewBuilder
    private func localVideoPreview(in geometry: GeometryProxy) -> some View {
        // 📹 __LOCAL_CAPTURE_WATCHDOG_2026_08_22__ Caméra demandée, aucune image
        // encore arrivée: montrer la boîte VIDE plutôt que rien du tout.
        //
        // Sans ça, un démarrage caméra qui traîne — ou qui échoue — se présente
        // exactement comme « il n'y a pas d'aperçu dans cette app »: l'écran ne
        // dit ni qu'on attend, ni qu'il y a un problème. C'est ce qu'a vu
        // Natalia le 2026-08-21 avant de trouver toute seule le geste
        // arrêt/relance. La relance est automatique maintenant (voir
        // `VideoCallManager.armLocalCaptureWatchdog`), mais elle prend quelques
        // secondes, et ces secondes-là doivent se voir.
        //
        // Aucune chaîne à traduire: une icône et un indicateur d'activité.
        if videoManager.localVideoImage == nil, voiceCallManager.shareOwnVideo {
            let pipWidth: CGFloat = 90
            let pipHeight: CGFloat = 160
            ZStack {
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color.white.opacity(0.08))
                VStack(spacing: 8) {
                    Image(systemName: "video.fill")
                        .font(.title3)
                        .foregroundColor(.white.opacity(0.7))
                    ProgressView()
                        .tint(.white.opacity(0.7))
                }
            }
            .frame(width: pipWidth, height: pipHeight)
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(Color.white.opacity(0.3), lineWidth: 2)
            )
            .position(localVideoPosition ?? CGPoint(x: geometry.size.width - pipWidth/2 - 16, y: pipHeight/2 + 60))
            .allowsHitTesting(false)
            .accessibilityLabel(Text("accessibility.call.self_preview".localized))
        }

        if let localImage = videoManager.localVideoImage {
            // PiP self-view rendered in portrait 9:16 (90x160). The capture
            // pipeline tries to rotate the buffer to portrait via the AVCapture
            // connection's videoRotationAngle, but in some cases the CGImage
            // still arrives in sensor orientation (1280x720 landscape) — which
            // made the preview show a sideways/cropped face inside the vertical
            // box. Detect landscape input and rotate it 90° so the user always
            // sees an upright portrait selfie in the portrait PiP.
            //
            // 🔧 FIX (iPhone 17 PiP upside-down): the rotation direction depends
            // on which camera produced the sensor-natural frame. The front
            // camera's natural buffer has the subject's head on the left half,
            // so it needs +90° (CW) to become upright portrait. The back
            // camera's natural buffer has it on the right half and needs -90°
            // (CCW). Using -90° unconditionally produced an upside-down selfie
            // whenever AVCaptureConnection.videoRotationAngle silently failed
            // (which happens on iPhone 17 / iOS 26.4.1).
            let pipWidth: CGFloat = 90
            let pipHeight: CGFloat = 160
            let isLandscapeImage = localImage.width > localImage.height
            let innerW: CGFloat = isLandscapeImage ? pipHeight : pipWidth
            let innerH: CGFloat = isLandscapeImage ? pipWidth : pipHeight
            let landscapeFallbackRotation: Double = videoManager.isUsingFrontCamera ? 90 : -90

            Image(decorative: localImage, scale: 1.0)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(width: innerW, height: innerH)
                .rotationEffect(isLandscapeImage ? .degrees(landscapeFallbackRotation) : .zero)
                .scaleEffect(x: videoManager.isUsingFrontCamera ? -1 : 1, y: 1)
                .frame(width: pipWidth, height: pipHeight)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(Color.white.opacity(0.5), lineWidth: 2)
                )
                .shadow(radius: 5)
                .position(localVideoPosition ?? CGPoint(x: geometry.size.width - pipWidth/2 - 16, y: pipHeight/2 + 60))
                .gesture(
                    DragGesture()
                        .onChanged { value in
                            localVideoPosition = value.location
                        }
                        .onEnded { value in
                            // Snap to edges
                            withAnimation(.spring()) {
                                let padding: CGFloat = pipWidth/2 + 16
                                let x = value.location.x < geometry.size.width / 2 ? padding : geometry.size.width - padding
                                let y = min(max(value.location.y, pipHeight/2 + 20), geometry.size.height - pipHeight/2 - 20)
                                localVideoPosition = CGPoint(x: x, y: y)
                            }
                        }
                )
                .onAppear {
                    // Initialize position based on actual geometry
                    if localVideoPosition == nil {
                        localVideoPosition = CGPoint(x: geometry.size.width - pipWidth/2 - 16, y: pipHeight/2 + 60)
                    }
                }
                .accessibilityLabel(Text("accessibility.call.self_preview".localized))
                .accessibilityHint(Text("accessibility.call.self_preview.hint".localized))
        }
    }
    
    // MARK: - Controls Overlay
    private func controlsOverlay(in geometry: GeometryProxy) -> some View {
        VStack {
            // Top bar
            HStack {
                // __BACK_ARROW_HUNG_UP_2026_08_25__ La flèche n'apparaît QUE
                // tant que l'appel n'est pas connecté.
                //
                // Signalé: « pendant un appel, quand j'appuyais sur la flèche
                // d'en haut, ça coupait le call ». C'était exact et voulu — ce
                // bouton appelait `endCall()`, son ancien commentaire le disait
                // (« Back button — ends call »). Mais `chevron.left` est le
                // glyphe « revenir en arrière » partout ailleurs, il était posé
                // là où se pose un bouton retour, et juste à sa droite se
                // trouvait un `chevron.down` de MÊME taille et MÊME fond qui,
                // lui, ne fait que réduire. Deux ronds noirs identiques, et
                // c'est celui qui promet « retour » qui raccrochait.
                //
                // Tant que ça sonne ou que ça connecte, une flèche qui annule a
                // du sens: il n'y a pas encore d'appel à réduire, et repartir
                // en arrière EST l'annulation. Une fois en ligne, elle
                // disparaît: pour sortir sans couper il y a le chevron de
                // réduction (`minimizedVideoPill`, ajouté le 2026-08-20), et
                // pour raccrocher il y a le bouton rouge des contrôles.
                if voiceCallManager.callState != .inCall {
                    Button(action: {
                        let generator = UIImpactFeedbackGenerator(style: .medium)
                        generator.impactOccurred()
                        // 🔧 FIX: Call endCall FIRST — stopVideo() can hang on VTCompressionSessionInvalidate
                        // and block endCall from ever firing, causing app freeze
                        voiceCallManager.endCall()
                        videoManager.stopVideo()
                    }) {
                        Image(systemName: "chevron.left")
                            .font(.title2)
                            .foregroundColor(.white)
                            .frame(width: 44, height: 44)
                            .background(Color.black.opacity(0.5))
                            .clipShape(Circle())
                    }
                    .buttonStyle(VideoScaleButtonStyle())
                    .accessibilityLabel(Text("accessibility.call.end".localized))
                    .accessibilityHint(Text("accessibility.call.end.hint".localized))
                }

                // __CALL_MINIMIZE_VIDEO_2026_08_20__ Read a message without
                // dropping the call. See `minimizedVideoPill` for why this can
                // exist here now.
                if voiceCallManager.callState == .inCall {
                    Button {
                        withAnimation(.easeInOut(duration: 0.22)) {
                            voiceCallManager.isCallMinimized = true
                        }
                    } label: {
                        Image(systemName: "chevron.down")
                            .font(.title3)
                            .foregroundColor(.white)
                            .frame(width: 44, height: 44)
                            .background(Color.black.opacity(0.5))
                            .clipShape(Circle())
                    }
                    .buttonStyle(VideoScaleButtonStyle())
                    .accessibilityLabel(Text("call.minimize".localized))
                }

                Spacer()
                
                // Contact info
                VStack(spacing: 4) {
                    Text(contactName)
                        .font(.headline)
                        .foregroundColor(.white)

                    Text(formattedDuration)
                        .font(.caption)
                        .foregroundColor(.white.opacity(0.8))

                    // Phase 1 diagnostic: live transport indicator.
                    // Same component as VoiceCallView so the user can see
                    // whether their video call is on pure P2P or a relay.
                    if voiceCallManager.callState == .inCall {
                        Button(action: { showTransportDetails = true }) {
                            HStack(spacing: 4) {
                                Image(systemName: voiceCallManager.activeTransport.symbol)
                                    .font(.caption2)
                                Text(voiceCallManager.activeTransport.displayName)
                                    .font(.caption2.weight(.medium))
                            }
                            .foregroundColor(.white)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(videoTransportTierColor(voiceCallManager.activeTransport.tier).opacity(0.85))
                            .clipShape(Capsule())
                        }
                        .accessibilityLabel(Text("accessibility.call.transport".localized))
                        .accessibilityHint(Text("accessibility.call.transport.hint".localized))
                        .accessibilityValue(Text(voiceCallManager.activeTransport.displayName))
                    }
                }

                Spacer()

                // Quality indicator — uses actual connection quality from VoiceCallManager
                Button(action: { showQualityPicker = true }) {
                    VStack(spacing: 2) {
                        Image(systemName: connectionQualityIcon)
                            .font(.title3)
                            .foregroundColor(connectionQualityColor)

                        HStack(spacing: 4) {
                            // Show latency when available
                            if voiceCallManager.currentLatency > 0 {
                                Text("\(voiceCallManager.currentLatency)ms")
                                    .font(.caption2)
                                    .foregroundColor(.white.opacity(0.7))
                            } else {
                                Text("\(Int(videoManager.outgoingBandwidth))k")
                                    .font(.caption2)
                                    .foregroundColor(.white.opacity(0.7))
                            }

                            // 🔧 FIX: the "buf:N" jitter-buffer chip is gone with
                            // VideoJitterBuffer itself — nothing ever wrote
                            // videoJitterBufferDepth, so this branch could never be
                            // taken and the badge implied a buffering stage that the
                            // decode path never had. See VideoCallManager.
                        }

                        // Show "Reconnecting" text when connection is lost
                        if voiceCallManager.connectionQuality == .reconnecting {
                            Text("call.quality.reconnecting".localized)
                                .font(.caption2)
                                .foregroundColor(.orange)
                        }
                    }
                    .padding(8)
                    .background(Color.black.opacity(0.5))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text("accessibility.call.quality".localized))
                .accessibilityHint(Text("accessibility.video.quality.hint".localized))
            }
            .padding(.horizontal)
            .padding(.top, 50)
            
            Spacer()
            
            // Bottom controls
            VStack(spacing: 20) {
                // Effect controls
                HStack(spacing: 30) {
                    // Blur mode
                    Button(action: { showBlurPicker = true }) {
                        VStack(spacing: 4) {
                            Image(systemName: videoManager.blurMode.icon)
                                .font(.title2)
                            Text(videoManager.blurMode.displayName)
                                .font(.caption)
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                        }
                        .foregroundColor(.white)
                        .frame(width: 70, height: 56)
                        .contentShape(Rectangle())
                    }
                    .accessibilityLabel(Text("call.video.effect".localized))
                    .accessibilityValue(Text(videoManager.blurMode.displayName))

                    // Quality
                    Button(action: { showQualityPicker = true }) {
                        VStack(spacing: 4) {
                            Image(systemName: "dial.medium")
                                .font(.title2)
                            Text(videoManager.currentQuality.rawValue)
                                .font(.caption)
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                        }
                        .foregroundColor(.white)
                        .frame(width: 70, height: 56)
                        .contentShape(Rectangle())
                    }
                    .accessibilityLabel(Text("call.video.quality".localized))
                    .accessibilityValue(Text(videoManager.currentQuality.rawValue))

                    // Voice effect. __VOICE_EFFECT_PICKER_VIDEO_2026_08_20__
                    // This was `cycleVoiceEffect()` — the blind cycling that the
                    // audio call replaced with a chooser on 2026-06-19 and that
                    // video kept. With eight effects, reaching a named one meant
                    // up to seven taps and hearing each of the six you did not
                    // want, live, on the call. Same sheet as VoiceCallView.
                    Button(action: { showEffectPicker = true }) {
                        VStack(spacing: 4) {
                            Image(systemName: "waveform")
                                .font(.title2)
                            Text(voiceCallManager.currentEffect.displayName)
                                .font(.caption)
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                        }
                        .foregroundColor(.white)
                        .frame(width: 70, height: 56)
                        .contentShape(Rectangle())
                    }
                    .accessibilityLabel(Text("call.effect".localized))
                    .accessibilityValue(Text(voiceCallManager.currentEffect.displayName))
                    .accessibilityHint(Text("accessibility.call.effect.hint".localized))
                }
                .padding(.horizontal)
                
                // Main controls. __CALL_CONTROLS_FIT_2026_08_21__ Five buttons at
                // a hard 55 pt with 25 pt gaps needed 407 pt inside a 32 pt
                // padding — wider than every iPhone except the Pro Max class, so
                // mute and switch-camera ran off both edges on all the others.
                // See `CallControlMetrics`.
                // SIX buttons live in this row (mute, stop-video, camera, switch,
                // speaker, END CALL) — the count was 5, sizing the row for one button
                // fewer than it holds, so the end-call button overflowed off the right
                // edge. Fit for the real count.
                let mainMetrics = CallControlMetrics.fitting(
                    availableWidth: geometry.size.width - 32, count: 6)
                HStack(spacing: mainMetrics.spacing) {
                    // Mute
                    VideoControlButton(
                        icon: voiceCallManager.isMuted ? "mic.slash.fill" : "mic.fill",
                        isActive: voiceCallManager.isMuted,
                        activeColor: .red,
                        size: mainMetrics.button
                    ) {
                        voiceCallManager.toggleMute()
                    }
                    .accessibilityLabel(Text(voiceCallManager.isMuted ? "call.unmute".localized : "call.mute".localized))
                    .accessibilityAddTraits(voiceCallManager.isMuted ? [.isSelected] : [])

                    // 📹 Stop video — sends 0x0F to peer, both sides return to
                    // voice-only (audio path untouched). Telegram/WhatsApp UX.
                    // The auto pause/resume on app background still runs via
                    // `scenePhase` listener and uses the 0x0C/0x0D signals.
                    VideoControlButton(
                        icon: "video.slash.fill",
                        isActive: false,
                        activeColor: .red,
                        size: mainMetrics.button
                    ) {
                        let g = UIImpactFeedbackGenerator(style: .medium)
                        g.impactOccurred()
                        voiceCallManager.stopVideoMidCall()
                    }
                    .accessibilityLabel(Text("accessibility.call.stop_video".localized))
                    .accessibilityHint(Text("accessibility.call.stop_video.hint".localized))

                    // 📹 My-camera on/off WITHOUT leaving the video call. When the
                    // user is "watch only" (shareOwnVideo == false) this shows a
                    // "video.badge.plus" (add my video) → turns the camera on and
                    // tells the peer. When sharing, it turns the camera off (peer
                    // sees a "camera off" placeholder) while we keep watching them.
                    VideoControlButton(
                        icon: voiceCallManager.shareOwnVideo ? "video.fill" : "video.badge.plus",
                        isActive: !voiceCallManager.shareOwnVideo,
                        activeColor: .orange,
                        size: mainMetrics.button
                    ) {
                        let g = UIImpactFeedbackGenerator(style: .medium)
                        g.impactOccurred()
                        if voiceCallManager.shareOwnVideo {
                            voiceCallManager.disableLocalCameraMidVideo()
                            videoManager.setLocalCameraEnabled(false)
                        } else {
                            voiceCallManager.enableLocalCameraMidVideo()
                            videoManager.setLocalCameraEnabled(true)
                        }
                    }
                    .accessibilityLabel(Text(voiceCallManager.shareOwnVideo ? "accessibility.call.camera_off".localized : "accessibility.call.camera_on".localized))

                    // Switch camera — highlight when back camera is active
                    VideoControlButton(
                        icon: videoManager.isUsingFrontCamera ? "camera.rotate.fill" : "camera.rotate",
                        isActive: !videoManager.isUsingFrontCamera,
                        activeColor: .blue,
                        size: mainMetrics.button
                    ) {
                        videoManager.switchCamera()
                    }
                    .accessibilityLabel(Text("accessibility.call.switch_camera".localized))

                    // Speaker
                    VideoControlButton(
                        icon: voiceCallManager.isSpeakerOn ? "speaker.wave.3.fill" : "speaker.fill",
                        isActive: voiceCallManager.isSpeakerOn,
                        activeColor: .blue,
                        size: mainMetrics.button
                    ) {
                        voiceCallManager.toggleSpeaker()
                    }
                    .accessibilityLabel(Text("call.speaker".localized))
                    .accessibilityAddTraits(voiceCallManager.isSpeakerOn ? [.isSelected] : [])
                    
                    // End call — ContentView will hide view when callState changes
                    Button(action: {
                        let generator = UIImpactFeedbackGenerator(style: .medium)
                        generator.impactOccurred()
                        // 🔧 FIX: Call endCall FIRST — stopVideo() can hang on VTCompressionSessionInvalidate
                        voiceCallManager.endCall()
                        videoManager.stopVideo()
                    }) {
                        Image(systemName: "phone.down.fill")
                            .font(.title3)
                            .foregroundColor(.white)
                            // Match the fitted size of the other five buttons so all six
                            // fit the row width instead of this one running off the edge.
                            .frame(width: mainMetrics.button, height: mainMetrics.button)
                            .background(Color.red)
                            .clipShape(Circle())
                    }
                    .buttonStyle(VideoScaleButtonStyle())
                    .accessibilityLabel(Text("accessibility.call.end".localized))
                    .accessibilityHint(Text("accessibility.call.end.hint".localized))
                }
                .padding(.bottom, 40)
            }
            .padding()
            .background(
                LinearGradient(
                    colors: [.clear, .black.opacity(0.7)],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
        }
    }
    
    // MARK: - Quality Picker
    private var qualityPickerOverlay: some View {
        ZStack {
            Color.black.opacity(0.6)
                .ignoresSafeArea()
                .onTapGesture { showQualityPicker = false }
            
            VStack(spacing: 0) {
                Text("call.video.quality".localized)
                    .font(.headline)
                    .foregroundColor(.white)
                    .padding()
                
                ForEach(VideoQuality.allCases, id: \.self) { quality in
                    Button(action: {
                        videoManager.setQuality(quality)
                        showQualityPicker = false
                    }) {
                        HStack {
                            VStack(alignment: .leading) {
                                Text(quality.rawValue)
                                    .foregroundColor(.white)
                                Text("\(Int(quality.resolution.width))×\(Int(quality.resolution.height))")
                                    .font(.caption)
                                    .foregroundColor(.white.opacity(0.7))
                            }
                            
                            Spacer()
                            
                            Text("\(quality.bitrate / 1000) kbps")
                                .font(.caption)
                                .foregroundColor(.white.opacity(0.7))
                            
                            if quality == videoManager.currentQuality {
                                Image(systemName: "checkmark")
                                    .foregroundColor(.green)
                            }
                        }
                        .padding()
                        .background(quality == videoManager.currentQuality ? Color.white.opacity(0.1) : Color.clear)
                    }
                }
            }
            .frame(width: 280)
            .background(Color.gray.opacity(0.9))
            .clipShape(RoundedRectangle(cornerRadius: 16))
        }
    }
    
    // MARK: - Blur Picker
    private var blurPickerOverlay: some View {
        ZStack {
            Color.black.opacity(0.6)
                .ignoresSafeArea()
                .onTapGesture { showBlurPicker = false }
            
            VStack(spacing: 0) {
                Text("call.video.effect".localized)
                    .font(.headline)
                    .foregroundColor(.white)
                    .padding()

                ForEach(VideoBlurMode.allCases, id: \.self) { mode in
                    Button(action: {
                        videoManager.setBlurMode(mode)
                        showBlurPicker = false
                    }) {
                        HStack {
                            Image(systemName: mode.icon)
                                .frame(width: 30)
                                .foregroundColor(.white)
                            
                            VStack(alignment: .leading) {
                                Text(mode.displayName)
                                    .foregroundColor(.white)
                                
                                // Description for privacy modes
                                if mode == .heavyPixelate {
                                    Text("call.video.blur.heavy_desc".localized)
                                        .font(.caption2)
                                        .foregroundColor(.white.opacity(0.6))
                                } else if mode == .ultraPixelate {
                                    Text("call.video.blur.ultra_desc".localized)
                                        .font(.caption2)
                                        .foregroundColor(.white.opacity(0.6))
                                }
                            }
                            
                            Spacer()
                            
                            if mode == videoManager.blurMode {
                                Image(systemName: "checkmark")
                                    .foregroundColor(.green)
                            }
                        }
                        .padding()
                        .background(mode == videoManager.blurMode ? Color.white.opacity(0.1) : Color.clear)
                    }
                }
                
                // Intensity slider (if blur mode is active)
                if videoManager.blurMode != .none {
                    VStack {
                        Text("call.video.blur.intensity".localized)
                            .font(.caption)
                            .foregroundColor(.white.opacity(0.7))

                        Slider(value: $videoManager.blurIntensity, in: 0...1)
                            .tint(.blue)
                    }
                    .padding()
                }
            }
            .frame(width: 280)
            .background(Color.gray.opacity(0.9))
            .clipShape(RoundedRectangle(cornerRadius: 16))
        }
    }
    
    // MARK: - Camera Paused Overlay (15 languages)

    /// Multilingual "Camera paused" message shown when peer's camera is off (backgrounded/PiP)
    private var cameraPausedOverlay: some View {
        VStack(spacing: 12) {
            Image(systemName: "video.slash.fill")
                .font(.scaled(40))
                .foregroundColor(.white.opacity(0.8))
                .padding(.bottom, 4)

            // Rotating multilingual text — shows the message is universal
            ScrollView(.vertical, showsIndicators: false) {
                VStack(spacing: 6) {
                    ForEach(Self.cameraPausedTranslations, id: \.self) { text in
                        Text(text)
                            .font(.scaled(14, weight: .medium))
                            .foregroundColor(.white.opacity(0.7))
                    }
                }
            }
            .frame(maxHeight: 200)
        }
        .padding(24)
        .background(
            RoundedRectangle(cornerRadius: 20)
                .fill(.ultraThinMaterial)
                .environment(\.colorScheme, .dark)
        )
        .frame(maxWidth: 280)
        .transition(.opacity.combined(with: .scale(scale: 0.9)))
        .animation(.easeInOut(duration: 0.3), value: voiceCallManager.isRemoteCameraPaused)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("accessibility.call.camera_paused".localized))
    }

    /// "Camera paused" translated in 15 languages
    private static let cameraPausedTranslations: [String] = [
        "Camera paused",           // English
        "Caméra en pause",         // French
        "Cámara en pausa",         // Spanish
        "Kamera pausiert",         // German
        "Fotocamera in pausa",     // Italian
        "Câmera pausada",          // Portuguese
        "相机已暂停",                // Chinese (Simplified)
        "カメラが一時停止中",          // Japanese
        "카메라 일시 중지",           // Korean
        "Камера приостановлена",    // Russian
        "الكاميرا متوقفة مؤقتًا",    // Arabic
        "कैमरा रुका हुआ",            // Hindi
        "Kamera duraklatıldı",     // Turkish
        "Camera gepauzeerd",       // Dutch
        "Kamera wstrzymana",       // Polish
    ]

    // MARK: - Helpers

    private var formattedDuration: String {
        let duration = voiceCallManager.callDuration
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%02d:%02d", minutes, seconds)
    }
    
    private var connectionQualityIcon: String {
        switch voiceCallManager.connectionQuality {
        case .excellent, .good: return "wifi"
        case .fair: return "wifi.exclamationmark"
        case .poor, .veryPoor: return "wifi.slash"
        case .reconnecting: return "arrow.triangle.2.circlepath"
        case .disconnected: return "wifi.slash"
        }
    }

    private var connectionQualityColor: Color {
        switch voiceCallManager.connectionQuality {
        case .excellent: return .green
        case .good: return .green.opacity(0.8)
        case .fair: return .yellow
        case .poor, .veryPoor: return .red
        case .reconnecting: return .orange
        case .disconnected: return .gray
        }
    }

    /// Phase 1 diagnostic: badge color for the live transport tier.
    private func videoTransportTierColor(_ tier: VoiceCallManager.TransportPath.Tier) -> Color {
        switch tier {
        case .direct:  return Color.green.opacity(0.7)
        case .relayed: return Color.yellow.opacity(0.8)
        case .server:  return Color.orange.opacity(0.85)
        case .unknown: return Color.black.opacity(0.4)
        }
    }

    // MARK: - Background Task

    /// Request background execution time so camera can encode a few more frames during PiP transition
    private func requestBackgroundTask() {
        guard backgroundTaskID == .invalid else { return }
        backgroundTaskID = UIApplication.shared.beginBackgroundTask(withName: "VideoCallPiP") { [self] in
            fileLog.log("📹 Background task expired — ending")
            endBackgroundTask()
        }
        fileLog.log("📹 Background task started: \(backgroundTaskID)")
    }

    private func endBackgroundTask() {
        guard backgroundTaskID != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTaskID)
        backgroundTaskID = .invalid
    }
}

// MARK: - Video Control Button Component
struct VideoControlButton: View {
    let icon: String
    let isActive: Bool
    let activeColor: Color
    /// Diameter. Defaults to the preferred size; the call rows pass the value
    /// `CallControlMetrics` computed for the screen they are actually on.
    var size: CGFloat = CallControlMetrics.preferredButton
    let action: () -> Void

    var body: some View {
        Button(action: {
            // Haptic feedback on every tap
            let generator = UIImpactFeedbackGenerator(style: .light)
            generator.impactOccurred()
            action()
        }) {
            // __ANATOLE_1311_IOS__ (N12) semibold glyph — "légèrement plus épais".
            Image(systemName: icon)
                .font(.title2.weight(.semibold))
                .foregroundColor(.white)
                .frame(width: size, height: size)
                .background(isActive ? activeColor : Color.white.opacity(0.3))
                .clipShape(Circle())
        }
        .buttonStyle(VideoScaleButtonStyle())
    }
}

// MARK: - Scale Button Style for Video Controls
struct VideoScaleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.9 : 1.0)
            .animation(.easeInOut(duration: 0.15), value: configuration.isPressed)
    }
}
