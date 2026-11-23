import SwiftUI
import AVFoundation

// MARK: - In-App Incoming Call Surface
// `IncomingCallOverlayView` is the app's own incoming-call screen. It is presented
// from exactly one place — the guarded branch in ContentView.body — and only for
// calls CallKit is NOT presenting (China / callKitDisabled bypass, mesh/WebSocket
// calls, or a reportNewIncomingCall that failed). Do not add a second presenter.

// 🔧 FIX (2026-07-27): the `IncomingCallOverlay` ViewModifier that used to live
// here has been deleted. It was the ONLY place that checked
// `isCallKitHandlingIncoming` before drawing the incoming-call UI — and it was
// never installed on any view (`withIncomingCallOverlay()` had zero call sites),
// so that guard could never run. Meanwhile the surface that IS live, the branch
// in ContentView.body, had no guard, which is how a VoIP-push call ended up
// drawing the CallKit banner and a full-screen overlay at the same time.
//
// A dead copy of the presentation logic is worse than none: it made the codebase
// read as if the duplication were already handled. The one rule that decides which
// surface appears is `VoIPPushManager.incomingCallSurfaceOwner`, and since
// 2026-07-27b this view enforces it on itself (see `body`) instead of trusting its
// caller to have checked.

// 🔧 FIX (2026-07-27b): the guard is now INSIDE this view, not only in its caller.
//
// MECHANISM: fd48232 put the rule in ContentView ("draw this only if
// !isCallKitPresentingIncoming"). That makes correctness a property of every call
// site — exactly the arrangement that produced the original bug, where one of three
// presenters remembered to check and the live one did not. `body` now asks
// `VoIPPushManager.incomingCallSurfaceOwner` itself and renders nothing when the
// answer is `.callKit`, so a presenter that forgets the check still cannot stack this
// screen on top of CallKit's banner. ContentView keeps its check as a cheap early-out.

// MARK: - Incoming Call Overlay View (WhatsApp-style)
struct IncomingCallOverlayView: View {
    let callerName: String

    /// __RINGING_AVATAR_2026_08_14__ Find the caller's picture whatever encoding
    /// their key arrives in, and whichever of the two stores holds it.
    ///
    /// `getPhoto(for:)` is an exact-key lookup, and its own comment admits the
    /// disk filenames confuse `_` with `/`. A ringing call's key comes off the
    /// VoIP push, i.e. base64URL, while the photo was saved under standard
    /// base64 — so the avatar was missing on exactly the screen that most needs
    /// it. There are also TWO stores: ContactPhotoManager (disk) and
    /// ContactPresenceManager (received over profile sync); the overlay only ever
    /// asked the first. Try both, in both encodings.
    static func callerPhoto(for key: String) -> UIImage? {
        let std = key.replacingOccurrences(of: "-", with: "+")
                     .replacingOccurrences(of: "_", with: "/")
        let url = key.replacingOccurrences(of: "+", with: "-")
                     .replacingOccurrences(of: "/", with: "_")
        for candidate in [key, std, url, std + "=", std + "=="] {
            if let p = ContactPhotoManager.shared.getPhoto(for: candidate) { return p }
            if let p = ContactPresenceManager.shared.getProfileImage(publicKey: candidate) { return p }
        }
        return nil
    }

    let callerPublicKey: String
    let isVideoCall: Bool
    @ObservedObject var voiceCallManager: VoiceCallManager
    let onDismiss: () -> Void
    let onAccepted: (Bool) -> Void

    /// Observed so the view re-renders the moment CallKit takes or releases the
    /// incoming surface. Without this observation the enforcement below would be
    /// evaluated once and never re-evaluated.
    @ObservedObject private var voipPushManager = VoIPPushManager.shared

    @State private var pulseAnimation = false
    @State private var isAccepting = false
    @State private var isDeclining = false
    @Environment(\.colorScheme) var colorScheme
    /// __OLED_BLACK_2026_11_16__ OLED black: black ground, the brand glow stays.
    @Environment(\.oledBlackActive) private var oledBlack

    private var brandColor: Color {
        Theme.brand
    }

    /// Peer id for the log lines below, so a duplicate surface can be attributed to a
    /// specific call straight from call_debug.log. `VoiceCallManager.currentCallId` is
    /// private, but this prefix matches the `key=` field the push path already logs
    /// next to its `callId=`, so the two sides line up.
    private var logPeer: String {
        String((voiceCallManager.incomingCall?.peerPublicKey ?? callerPublicKey).prefix(12))
    }

    var body: some View {
        // THE enforcement. `.callKit` means CallKit is drawing the ring; this screen
        // must not exist at the same time, in any code path, for any presenter.
        if voipPushManager.incomingCallSurfaceOwner == .callKit {
            Color.clear
                .frame(width: 0, height: 0)
                .allowsHitTesting(false)
                .onAppear {
                    CallFileLogger.shared.log("📞 SURFACE inapp SUPPRESSED peer=\(logPeer) — CallKit owns the incoming surface")
                }
        } else {
            presentedBody
                .onAppear {
                    CallFileLogger.shared.log("📞 SURFACE +inapp peer=\(logPeer) caller=\(callerName) isVideo=\(isVideoCall)")
                }
                .onDisappear {
                    CallFileLogger.shared.log("📞 SURFACE -inapp peer=\(logPeer)")
                }
        }
    }

    private var presentedBody: some View {
        ZStack {
            // Deep-space OSHI brand ground: a dark violet→indigo gradient (matching
            // the Signal-O icon) instead of a generic purple/blue, with a soft
            // radial brand glow behind the caller for depth. Reads unmistakably as
            // OSHI and gives the white caller name/controls strong contrast.
            LinearGradient(
                colors: oledBlack ? [.black, .black] : isVideoCall ?
                    [Color(red: 0.16, green: 0.05, blue: 0.42), Color(red: 0.05, green: 0.04, blue: 0.16)] :
                    [Color(red: 0.20, green: 0.06, blue: 0.52), Color(red: 0.04, green: 0.03, blue: 0.14)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()
            .overlay(
                RadialGradient(
                    colors: [brandColor.opacity(0.45), .clear],
                    center: .init(x: 0.5, y: 0.34),
                    startRadius: 8,
                    endRadius: 340
                )
                .ignoresSafeArea()
                .allowsHitTesting(false)
            )

            VStack(spacing: 24) {
                Spacer()

                // Caller avatar with pulse animation
                avatarView

                // Caller name
                Text(callerName)
                    .font(.scaled(32, weight: .semibold, design: .rounded))
                    .foregroundColor(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .padding(.horizontal)
                    .accessibilityAddTraits(.isHeader)

                // Call type indicator
                HStack(spacing: 8) {
                    Image(systemName: isVideoCall ? "video.fill" : "phone.fill")
                        .font(.title3)
                    Text(isVideoCall ? "call.incoming.video".localized : "call.incoming.voice".localized)
                        .font(.headline)
                }
                .foregroundColor(.white.opacity(0.9))
                .accessibilityElement(children: .combine)

                // OSHI branding
                Text("OSHI")
                    .font(.caption)
                    .fontWeight(.semibold)
                    .foregroundColor(.white.opacity(0.5))

                // Encryption badge. The localized string already carries a 🔒
                // glyph, so we don't add a second SF Symbol lock (that showed a
                // double padlock). Rendered on a subtle glass pill for polish.
                Text("call.state.encrypted".localized)
                    .font(.caption2)
                    .foregroundColor(.white.opacity(0.6))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 5)
                    .background(Capsule().fill(Color.white.opacity(0.10)))
                    .overlay(Capsule().stroke(Color.white.opacity(0.15), lineWidth: 0.5))
                    .padding(.top, 4)
                    .accessibilityElement(children: .combine)

                Spacer()

                // WhatsApp-style: Just two buttons (Decline / Accept)
                callActionButtons
                    .padding(.bottom, 60)
            }
            .padding()
            // Keep the incoming-call UI centered and not stretched on iPad / landscape.
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
        }
        .onAppear {
            pulseAnimation = true
        }
        // Auto dismiss if call ends remotely
        .onChange(of: voiceCallManager.callState) { _, newState in
            if newState == .idle || newState == .ended(reason: .hungUp) {
                onDismiss()
            }
        }
    }
    
    // MARK: - Avatar View
    private var avatarView: some View {
        ZStack {
            // Pulsing rings — an iridescent Apple-Intelligence gradient stroke
            // (violet→pink→amber→cyan) that ripples outward, giving the incoming
            // call a lively, futuristic "signal reaching you" feel.
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .stroke(AIGradient.angular, lineWidth: 2.5)
                    .frame(width: 140 + CGFloat(index * 30), height: 140 + CGFloat(index * 30))
                    .scaleEffect(pulseAnimation ? 1.2 : 1.0)
                    .opacity(pulseAnimation ? 0 : 0.6)
                    .animation(
                        Animation.easeOut(duration: 1.5)
                            .repeatForever(autoreverses: false)
                            .delay(Double(index) * 0.3),
                        value: pulseAnimation
                    )
            }

            // Avatar circle — frosted glass with a soft brand halo.
            Circle()
                .fill(.ultraThinMaterial)
                .oledGlassEdge(Circle())  // __OLED_GLASS_2026_11_16__
                .frame(width: 140, height: 140)
                .overlay(Circle().fill(Color.white.opacity(0.08)))
                .overlay(Circle().stroke(Color.white.opacity(0.25), lineWidth: 1))
                .neonGlow(brandColor, radius: 20, opacity: 0.5)
            
            // Avatar content — show the caller's saved profile photo when we have
            // one, otherwise fall back to a video glyph / initial.
            if let photo = Self.callerPhoto(for: callerPublicKey) {
                Image(uiImage: photo)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 140, height: 140)
                    .clipShape(Circle())
                    .overlay(Circle().stroke(Color.white.opacity(0.35), lineWidth: 1))
            } else if isVideoCall {
                Image(systemName: "video.fill")
                    .font(.scaled(50))
                    .foregroundColor(.white)
            } else {
                // __AVATAR_SAME_COLOUR_EVERYWHERE_2026_11_09__ the caller's
                // `AvatarIdentity` colour, as in the conversation list.
                DefaultAvatar(key: voiceCallManager.incomingCall?.peerPublicKey ?? callerPublicKey,
                              name: callerName, size: 140, initialCount: 1)
            }
        }
        .accessibilityHidden(true)
    }
    
    // MARK: - WhatsApp-style Call Action Buttons (Decline / Accept)
    private var callActionButtons: some View {
        HStack(spacing: 80) {
            // Decline button (red)
            Button(action: declineCall) {
                VStack(spacing: 12) {
                    ZStack {
                        Circle()
                            .fill(Color.red)
                            .frame(width: 70, height: 70)
                            .shadow(color: .red.opacity(0.5), radius: 10)

                        if isDeclining {
                            ProgressView()
                                .progressViewStyle(CircularProgressViewStyle(tint: .white))
                        } else {
                            Image(systemName: "phone.down.fill")
                                .font(.scaled(28, weight: .semibold))  // (N12) thicker glyph
                                .foregroundColor(.white)
                        }
                    }

                    Text("call.action.decline".localized)
                        .font(.subheadline)
                        .fontWeight(.medium)
                        .foregroundColor(.white.opacity(0.9))
                }
            }
            .disabled(isAccepting || isDeclining)
            .buttonStyle(ScaleButtonStyle())
            .accessibilityLabel("accessibility.call.decline".localized)
            .accessibilityHint("accessibility.call.decline.hint".localized)

            // Accept button (green) - accepts as video if it's a video call
            Button(action: { acceptCall(asVideo: isVideoCall) }) {
                VStack(spacing: 12) {
                    ZStack {
                        Circle()
                            .fill(Color.green)
                            .frame(width: 70, height: 70)
                            .shadow(color: .green.opacity(0.5), radius: 10)

                        if isAccepting {
                            ProgressView()
                                .progressViewStyle(CircularProgressViewStyle(tint: .white))
                        } else {
                            Image(systemName: isVideoCall ? "video.fill" : "phone.fill")
                                .font(.scaled(28, weight: .semibold))  // (N12) thicker glyph
                                .foregroundColor(.white)
                        }
                    }

                    Text("call.action.accept".localized)
                        .font(.subheadline)
                        .fontWeight(.medium)
                        .foregroundColor(.white.opacity(0.9))
                }
            }
            .disabled(isAccepting || isDeclining)
            .buttonStyle(ScaleButtonStyle())
            .accessibilityLabel(isVideoCall ? "accessibility.call.accept.video".localized : "accessibility.call.accept.voice".localized)
            .accessibilityHint("accessibility.call.accept.hint".localized)
        }
    }
    
    // MARK: - Actions
    private func acceptCall(asVideo: Bool) {
        guard !isAccepting && !isDeclining else { return }

        isAccepting = true

        // Strong haptic feedback
        let generator = UINotificationFeedbackGenerator()
        generator.notificationOccurred(.success)

        // Play pickup sound
        AudioServicesPlaySystemSound(1003)

        Task {
            do {
                try await voiceCallManager.acceptCall()
                await MainActor.run {
                    onAccepted(asVideo)
                }
            } catch {
                OshiLog.call.info("❌ Failed to accept call: \(error)")
                await MainActor.run {
                    isAccepting = false

                    // Error haptic
                    let generator = UINotificationFeedbackGenerator()
                    generator.notificationOccurred(.error)

                    onDismiss()
                }
            }
        }
    }
    
    private func declineCall() {
        guard !isAccepting && !isDeclining else { return }
        
        isDeclining = true
        
        // Haptic feedback
        let generator = UIImpactFeedbackGenerator(style: .heavy)
        generator.impactOccurred()
        
        voiceCallManager.declineIncomingCall(from: callerPublicKey)
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
            onDismiss()
        }
    }
}

// MARK: - Scale Button Style
struct IncomingCallScaleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.9 : 1.0)
            .animation(.easeInOut(duration: 0.15), value: configuration.isPressed)
    }
}

// MARK: - Preview
#Preview {
    IncomingCallOverlayView(
        callerName: "John Doe",
        callerPublicKey: "abc123",
        isVideoCall: false,
        voiceCallManager: VoiceCallManager.shared,
        onDismiss: {},
        onAccepted: { _ in }
    )
}
