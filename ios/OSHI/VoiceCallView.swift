import SwiftUI
import AVFoundation
#if canImport(UIKit) && os(iOS)
import UIKit
#endif

// MARK: - Voice Call View (WhatsApp-style UX)
struct VoiceCallView: View {
    @ObservedObject var voiceCallManager: VoiceCallManager
    @Environment(\.dismiss) private var dismiss
    @Environment(\.oledBlackActive) private var oledBlack
    
    // 📹 Video upgrade handled by setting isCurrentCallVideo (ContentView switches view)
    @State private var showCallEndedSummary = false
    @State private var callEndReason: String = ""
    @State private var finalDuration: TimeInterval = 0
    @State private var reconnectingPulse = false
    @State private var statusPulse = false  // 🔔 ICON: gentle pulse for waiting/ringing status glyph
    @State private var previousQuality: ConnectionQuality = .good
    @State private var showEffectPicker = false
    /// Set when "Call again" is tapped on the lost-connection summary, so the
    /// pending auto-dismiss does not close the screen of the new call.
    @State private var redialing = false
    
    let contactName: String
    let contactPublicKey: String

    /// __CALL_SHOWS_THE_ALIAS_2026_10_28__ N19, reported by a user: *"L'application
    /// n'affiche pas l'alias défini lors d'un appel ni de photo de profil."*
    ///
    /// The name handed to this screen is whatever `VoiceCallManager` happened to store
    /// when the call was set up — for an incoming call that is the name the CALLER
    /// announced, and the local alias the user chose for that person is never consulted.
    /// CallKit has always been right here (`VoIPPushManager` builds every `CXHandle`
    /// from `MessageManager.resolveContactAlias`), so the system call UI showed the
    /// alias while the app's own screen, one tap later, showed something else.
    ///
    /// Resolved from the public key, which is the only stable identifier either side
    /// agrees on. `resolveContactAlias` falls back to a key prefix, so when there is no
    /// local contact the name that was passed in is still preferred over an ellipsised
    /// key — a peer's announced name beats eight base64 characters.
    /// The key the photo and the alias are looked up under — never an empty string while
    /// a call is up. See the note at the photo lookup.
    private var photoKey: String {
        contactPublicKey.isEmpty ? (voiceCallManager.currentCallPeer ?? "") : contactPublicKey
    }

    private var displayName: String {
        let key = photoKey
        guard !key.isEmpty else { return contactName }
        let resolved = MessageManager.resolveContactAlias(key)
        let isKeyPrefix = resolved.hasSuffix("…")
        if isKeyPrefix {
            let trimmed = contactName.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? resolved : contactName
        }
        return resolved
    }
    
    // Brand color
    private let brandColor = Theme.brand
    
    // Convenience initializer
    init(voiceCallManager: VoiceCallManager) {
        self._voiceCallManager = ObservedObject(wrappedValue: voiceCallManager)
        self.contactName = voiceCallManager.currentCallPeerName ?? "call.contact.default".localized
        self.contactPublicKey = voiceCallManager.currentCallPeer ?? ""
    }
    
    // Full initializer
    init(voiceCallManager: VoiceCallManager, contactName: String, contactPublicKey: String) {
        self._voiceCallManager = ObservedObject(wrappedValue: voiceCallManager)
        self.contactName = contactName
        self.contactPublicKey = contactPublicKey
    }
    
    var body: some View {
        ZStack {
            // Dynamic background based on call state
            backgroundGradient
                .ignoresSafeArea()
                .animation(.easeInOut(duration: 0.5), value: voiceCallManager.connectionQuality)
                // Block hit-testing on the underlying ChatView so a tap on
                // any call control (Speaker, Mute, etc) cannot fall through
                // to the message TextField behind us — that was popping the
                // keyboard up on top of the call screen.
                .contentShape(Rectangle())
                .onTapGesture { dismissAnyKeyboard() }
            
            // Main content
            VStack(spacing: 24) {
                Spacer()
                
                // __CALL_MINIMIZE_2026_08_16__ Réduire l'appel pour aller lire un
                // message. Visible seulement pendant `.inCall` et sur un appel
                // AUDIO: un appel vidéo réduit garderait la caméra allumée sans
                // aperçu (`VideoCallView.onDisappear` ne coupe la vidéo que si
                // l'appel se termine ou repasse en audio), ce qui est une
                // décision produit, pas un détail d'affichage.
                if voiceCallManager.callState == .inCall, !voiceCallManager.isCurrentCallVideo {
                    HStack {
                        Button {
                            withAnimation(.easeInOut(duration: 0.22)) {
                                voiceCallManager.isCallMinimized = true
                            }
                        } label: {
                            Image(systemName: "chevron.down")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundColor(.white)
                                .frame(width: 40, height: 40)
                                .background(Circle().fill(Color.white.opacity(0.16)))
                                .contentShape(Circle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(Text(NSLocalizedString(
                            "call.minimize", value: "Minimize call",
                            comment: "Button that hides the call screen so the app can be used during the call")))
                        Spacer()
                    }
                    .transition(.opacity)
                }

                // Connection type badge (top)
                if voiceCallManager.callState == .inCall {
                    connectionTypeBadge
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
                
                // Avatar with status ring
                avatarView
                
                // Contact name
                Text(displayName)
                    .font(.scaled(28, weight: .semibold, design: .rounded))
                    .foregroundColor(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .accessibilityAddTraits(.isHeader)
                
                // Call status with animation
                callStatusView

                // __PEER_LOST_UX_2026_09_29__ The peer went silent: say so, and
                // show how long we keep waiting before hanging up for them.
                if voiceCallManager.callState == .inCall, let deadline = voiceCallManager.peerLostDeadline {
                    PeerLostCard(
                        peerName: displayName,
                        deadline: deadline,
                        totalSeconds: 12,
                        selfOffline: !voiceCallManager.hasInternetConnection
                    )
                    .transition(.scale(scale: 0.92).combined(with: .opacity))
                }
                
                // Duration (when connected)
                if voiceCallManager.callState == .inCall {
                    Text(formattedDuration)
                        .font(.scaled(48, weight: .light, design: .rounded))
                        .foregroundColor(.white)
                        .monospacedDigit()
                        .minimumScaleFactor(0.6)
                        .lineLimit(1)
                        .transition(.scale.combined(with: .opacity))
                        .accessibilityLabel(Text("accessibility.call.duration".localized))
                        .accessibilityValue(Text(formattedDuration))
                }
                
                // Quality indicator with details
                if voiceCallManager.callState == .inCall {
                    qualityIndicatorView
                        .transition(.opacity)
                }
                
                // Encryption badge
                encryptionBadge
                
                Spacer()
                
                // Waveform visualization (when in call and not muted)
                if voiceCallManager.callState == .inCall && !voiceCallManager.isMuted {
                    waveformView
                        .frame(height: 40)
                        .padding(.horizontal, 40)
                }

                // __MUTE_BANNER_2026_06_19__ Prominent muted indicator — prevents the
                // classic "talking while on mute" moment. Sits where the waveform would be.
                if voiceCallManager.callState == .inCall && voiceCallManager.isMuted {
                    HStack(spacing: 6) {
                        Image(systemName: "mic.slash.fill")
                        Text(NSLocalizedString("call.muted.banner", value: "Muted", comment: "Shown during a call when the mic is muted"))
                    }
                    .font(.footnote.weight(.semibold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 7)
                    .background(Capsule().fill(Color.red.opacity(0.85)))
                    .frame(height: 40)
                    .transition(.opacity)
                    .accessibilityLabel(Text(NSLocalizedString("call.muted.banner", value: "Muted", comment: "")))
                }
                
                // Control buttons
                controlButtonsView

                // End call button
                endCallButton
                    .padding(.bottom, 50)
            }
            .padding()
            // Cap content width so the call UI stays centered and readable on
            // iPad / landscape instead of stretching edge-to-edge.
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
            
            // Call ended overlay
            if showCallEndedSummary {
                callEndedOverlay
                    .transition(.opacity)
            }

            // 📹 Video upgrade request banner (callee side) — shown when peer
            // sends 0x0E REQUEST. Tapping Accept calls acceptVideoUpgrade()
            // which sets isCurrentCallVideo=true; ContentView swaps the view.
            if voiceCallManager.pendingVideoUpgradeRequest {
                videoUpgradeRequestBanner
                    .transition(.move(edge: .top).combined(with: .opacity))
            }

            // 📹 "Inviting to video..." overlay (caller side) — shown while
            // we await peer's accept (15 s timeout).
            if voiceCallManager.isAwaitingVideoUpgrade && !voiceCallManager.pendingVideoUpgradeRequest {
                awaitingVideoUpgradeOverlay
                    .transition(.opacity)
            }

            // __VUPG_REASON_SHOWN_2026_09_13__ Le motif est enfin AFFICHÉ.
            //
            // `videoUpgradeDeclinedReason` était calculé à chaque refus, chaque
            // expiration et chaque échec caméra — et sa seule lecture dans tout
            // le dépôt était le `onChange` ci-dessous, qui déclenche une
            // vibration puis l'efface. Aucune vue ne l'affichait: on appuyait
            // sur Vidéo, le rond disparaissait, le téléphone vibrait, et on
            // n'apprenait jamais si l'autre avait refusé, n'avait pas répondu,
            // ou si le paquet était mort en route.
            if let reason = voiceCallManager.videoUpgradeDeclinedReason {
                Text(reason)
                    .font(.scaled(14, weight: .medium))
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .oledAwareBackground(.ultraThinMaterial, in: Capsule())
                    .padding(.horizontal, 24)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                    .padding(.bottom, 180)
                    .transition(.opacity)
                    .accessibilityAddTraits(.isStaticText)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: voiceCallManager.videoUpgradeDeclinedReason)
        .onChange(of: voiceCallManager.videoUpgradeDeclinedReason) { _, reason in
            // Show a brief toast when peer declines or our request times out.
            // Toast emulated as a transient overlay via the same banner slot.
            // The flag is consumed here so the overlay only flashes once.
            if let _ = reason {
                hapticFeedback(.medium)
                // 3,5 s, pas 2: personne ne lit « Camera access is off for
                // OSHI — turn it on in Settings » en deux secondes.
                DispatchQueue.main.asyncAfter(deadline: .now() + 3.5) {
                    voiceCallManager.consumeVideoUpgradeDeclined()
                }
            }
        }
        // 📹 VideoCallView is handled by ContentView ZStack (no duplicate here)
        .onChange(of: voiceCallManager.callState) { oldState, newState in
            handleCallStateChange(from: oldState, to: newState)
        }
        .onChange(of: voiceCallManager.connectionQuality) { oldQuality, newQuality in
            handleQualityChange(from: oldQuality, to: newQuality)
        }
        .onAppear {
            // Dismiss any keyboard the underlying ChatView may have left up
            // — otherwise it covers the End-Call button and the user is stuck.
            dismissAnyKeyboard()
        }
        .onDisappear {
            // 🔧 FIX: Only end call if we're actually navigating away, NOT during state transitions
            // When upgrading to video, ContentView removes VoiceCallView and shows VideoCallView
            // callState == .inCall means call is still active — don't end it
            if !showCallEndedSummary && voiceCallManager.callState != .inCall && voiceCallManager.callState != .connecting {
                voiceCallManager.endCall()
            }
        }
        // __VOICE_EFFECT_PICKER_2026_06_19__ one-tap effect chooser (replaces blind cycling)
        .sheet(isPresented: $showEffectPicker) {
            VoiceEffectPickerView(voiceCallManager: voiceCallManager)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
    }
    
    // MARK: - Background
    @ViewBuilder
    private var backgroundGradient: some View {
        switch voiceCallManager.connectionQuality {
        case .reconnecting:
            LinearGradient(
                colors: [Color.orange.opacity(0.8), Color.red.opacity(0.6)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        case .poor, .veryPoor:
            LinearGradient(
                colors: [Color.red.opacity(0.7), Color.orange.opacity(0.6)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        case .fair:
            LinearGradient(
                colors: [Color.yellow.opacity(0.7), brandColor.opacity(0.6)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        default:
            if oledBlack {
                // __OLED_BLACK_2026_11_16__ Black ground with the brand glow
                // behind the avatar; the warning colours above are kept because
                // they carry information (reconnecting / poor line).
                RadialGradient(colors: [brandColor.opacity(0.40), .black],
                               center: .init(x: 0.5, y: 0.30),
                               startRadius: 8, endRadius: 360)
                    .background(Color.black)
            } else {
            LinearGradient(
                colors: [brandColor.opacity(0.9), Color.blue.opacity(0.7)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            }
        }
    }
    
    // MARK: - Connection Type Badge (Phase 1 live transport indicator)
    @State private var showTransportDetails = false

    private var connectionTypeBadge: some View {
        Button(action: { showTransportDetails = true }) {
            HStack(spacing: 6) {
                Image(systemName: voiceCallManager.activeTransport.symbol)
                    .font(.caption)
                Text(voiceCallManager.activeTransport.displayName)
                    .font(.caption)
                    .fontWeight(.medium)
            }
            .foregroundColor(.white)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(transportTierColor(voiceCallManager.activeTransport.tier).opacity(0.85))
            .clipShape(Capsule())
        }
        .accessibilityLabel(Text("accessibility.call.transport".localized))
        .accessibilityHint(Text("accessibility.call.transport.hint".localized))
        .accessibilityValue(Text(voiceCallManager.activeTransport.displayName))
        .sheet(isPresented: $showTransportDetails) {
            TransportDetailsSheet(voiceCallManager: voiceCallManager)
                .presentationDetents([.medium])
        }
    }

    private func transportTierColor(_ tier: VoiceCallManager.TransportPath.Tier) -> Color {
        switch tier {
        case .direct:  return Color.green.opacity(0.7)
        case .relayed: return Color.yellow.opacity(0.8)
        case .server:  return Color.orange.opacity(0.85)
        case .unknown: return Color.black.opacity(0.4)
        }
    }
    
    // MARK: - Avatar View
    private var avatarView: some View {
        ZStack {
            // Quality-based ring
            Circle()
                .stroke(qualityRingColor, lineWidth: 4)
                .frame(width: 150, height: 150)
                .scaleEffect(reconnectingPulse ? 1.1 : 1.0)
                .opacity(reconnectingPulse ? 0.5 : 1.0)
                .animation(
                    voiceCallManager.connectionQuality == .reconnecting ?
                    Animation.easeInOut(duration: 0.8).repeatForever(autoreverses: true) :
                    .default,
                    value: reconnectingPulse
                )
            
            // Ringing pulse animation
            if voiceCallManager.callState == .ringing {
                ForEach(0..<3, id: \.self) { index in
                    Circle()
                        .stroke(Color.white.opacity(0.3), lineWidth: 2)
                        .frame(width: 140 + CGFloat(index * 25), height: 140 + CGFloat(index * 25))
                        .scaleEffect(1.2)
                        .opacity(0)
                        .animation(
                            Animation.easeOut(duration: 1.5)
                                .repeatForever(autoreverses: false)
                                .delay(Double(index) * 0.3),
                            value: voiceCallManager.callState
                        )
                }
            }
            
            // Avatar circle
            Circle()
                .fill(Color.white.opacity(0.2))
                .frame(width: 130, height: 130)

            // Contact photo (if saved) → reconnecting icon → initial
            //
            // __CALL_SHOWS_THE_ALIAS_2026_10_28__ keyed on [photoKey], not on
            // `contactPublicKey` directly: the convenience initialiser takes the key from
            // `voiceCallManager.currentCallPeer ?? ""`, and when that is still nil at the
            // moment the view is built the lookup ran against an EMPTY string and silently
            // returned no photo for a contact that has one. Re-reading the manager here
            // picks it up once the call state has caught up.
            let contactPhoto = ContactPhotoManager.shared.getPhoto(for: photoKey)
            if voiceCallManager.connectionQuality == .reconnecting {
                Image(systemName: "arrow.triangle.2.circlepath")
                    .font(.scaled(44))
                    .foregroundColor(.white)
                    .rotationEffect(.degrees(reconnectingPulse ? 360 : 0))
                    .animation(
                        Animation.linear(duration: 1.5).repeatForever(autoreverses: false),
                        value: reconnectingPulse
                    )
            } else if let photo = contactPhoto {
                Image(uiImage: photo)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 130, height: 130)
                    .clipShape(Circle())
            } else {
                // __AVATAR_SAME_COLOUR_EVERYWHERE_2026_11_09__ was a bare
                // initial on a white-0.2 disc: the one screen where a contact
                // without a picture lost the colour the conversation list
                // gives them. Same key, same `AvatarIdentity` gradient.
                DefaultAvatar(key: photoKey, name: displayName, size: 130, initialCount: 1)
            }
        }
        .onAppear {
            if voiceCallManager.connectionQuality == .reconnecting {
                reconnectingPulse = true
            }
        }
        .onChange(of: voiceCallManager.connectionQuality) { _, quality in
            reconnectingPulse = (quality == .reconnecting)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(displayName))
        .accessibilityHidden(true)
    }

    private var qualityRingColor: Color {
        switch voiceCallManager.connectionQuality {
        case .excellent: return .green
        case .good: return .green.opacity(0.8)
        case .fair: return .yellow
        case .poor, .veryPoor: return .red
        case .reconnecting: return .orange
        case .disconnected: return .gray
        }
    }
    
    // MARK: - Call Status View
    private var callStatusView: some View {
        HStack(spacing: 8) {
            // __CONNECTING_SPINNER_2026_06_19__ reassure the user during the 1-3s setup.
            // 🔔 ICON (modern set): swap the bare spinner for a meaningful glyph —
            // an antenna while waiting/connecting, an outgoing-phone while ringing —
            // with a gentle pulse so it still reads as "in progress".
            if voiceCallManager.connectionQuality == .reconnecting
                || voiceCallManager.callState == .connecting
                || (voiceCallManager.callState == .ringing && voiceCallManager.isOutgoingCall) {
                let isRingingOut = voiceCallManager.callState == .ringing
                    && voiceCallManager.isOutgoingCall
                    && !voiceCallManager.isOutgoingConnecting
                    && voiceCallManager.connectionQuality != .reconnecting
                Image(systemName: isRingingOut ? "phone.arrow.up.right" : "antenna.radiowaves.left.and.right")
                    .font(.subheadline)
                    .foregroundColor(.white)
                    .opacity(statusPulse ? 1.0 : 0.4)
                    .animation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true), value: statusPulse)
                    .onAppear { statusPulse = true }
            }

            Text(callStatusText)
                .font(.subheadline)
                .fontWeight(.medium)
        }
        .foregroundColor(.white.opacity(0.9))
    }
    
    private var callStatusText: String {
        if voiceCallManager.connectionQuality == .reconnecting {
            return "call.quality.reconnecting".localized
        }

        switch voiceCallManager.callState {
        case .idle: return "call.quality.disconnected".localized
        case .ringing:
            // __CONNECTING_FIRST_2026_06_21__ show "Connecting…" during the wake window, then "Ringing…"
            if voiceCallManager.isOutgoingCall {
                return voiceCallManager.isOutgoingConnecting ? "call.state.connecting".localized : "call.state.ringing".localized
            }
            return "call.state.incoming".localized
        case .connecting: return "call.state.connecting".localized
        case .inCall: return "call.state.on_call".localized
        case .ended: return "call.ended.hungup".localized
        }
    }
    
    // MARK: - Quality Indicator
    private var qualityIndicatorView: some View {
        HStack(spacing: 12) {
            // Signal bars
            HStack(spacing: 2) {
                ForEach(0..<4, id: \.self) { index in
                    RoundedRectangle(cornerRadius: 1)
                        .fill(signalBarColor(for: index))
                        .frame(width: 4, height: CGFloat(6 + index * 4))
                }
            }
            
            // Quality text
            Text(qualityText)
                .font(.caption)
                .fontWeight(.medium)
            
            // Latency if available
            let latency = voiceCallManager.currentLatency
            if latency > 0 {
                Text("•")
                    .foregroundColor(.white.opacity(0.5))
                Text("\(latency)ms")
                    .font(.caption)
            }
        }
        .foregroundColor(.white.opacity(0.8))
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(Color.black.opacity(0.2))
        .clipShape(Capsule())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("accessibility.call.quality".localized))
        .accessibilityValue(Text(qualityText))
    }

    private func signalBarColor(for index: Int) -> Color {
        let activeCount: Int
        switch voiceCallManager.connectionQuality {
        case .excellent: activeCount = 4
        case .good: activeCount = 3
        case .fair: activeCount = 2
        case .poor, .veryPoor, .reconnecting: activeCount = 1
        case .disconnected: activeCount = 0
        }
        
        return index < activeCount ? qualityRingColor : Color.white.opacity(0.3)
    }
    
    private var qualityText: String {
        switch voiceCallManager.connectionQuality {
        case .excellent: return "call.quality.excellent".localized
        case .good: return "call.quality.good".localized
        case .fair: return "call.quality.fair".localized
        case .poor: return "call.quality.poor".localized
        case .veryPoor: return "call.quality.very_poor".localized
        case .reconnecting: return "call.quality.reconnecting".localized
        case .disconnected: return "call.quality.disconnected".localized
        }
    }
    
    // MARK: - Encryption Badge
    private var encryptionBadge: some View {
        HStack(spacing: 4) {
            Image(systemName: "lock.fill")
                .font(.caption2)
            Text("call.state.encrypted".localized)
                .font(.caption2)
        }
        .foregroundColor(.white.opacity(0.6))
        .accessibilityElement(children: .combine)
    }
    
    // MARK: - Waveform View
    // __VOICE_METER_2026_09_22__ Les barres suivent la VOIX, pas une sinusoïde.
    // Avant: une onde qui tournait sur un minuteur, à peine modulée par un
    // niveau que la vue plafonnait à 0,15 — elle bougeait pareil qu'on parle
    // ou non. Maintenant: le plus fort de ma voix et de celle du pair, avec
    // attaque rapide et retombée douce; blanc quand c'est le pair, vert quand
    // c'est moi.
    private var waveformView: some View {
        CallVoiceWaveform(micLevel: voiceCallManager.audioLevel,
                          remoteLevel: voiceCallManager.remoteAudioLevel)
            .accessibilityHidden(true)
    }

    // MARK: - Control Buttons
    private var controlButtonsView: some View {
        // __CALL_CONTROLS_FIT_2026_08_21__ Each button takes an EQUAL share of
        // the row instead of a `minWidth: 60` floor it was free to exceed.
        //
        // The old shape could only grow: the caption under the circle was
        // unconstrained, so `minimumScaleFactor(0.8)` never engaged — text only
        // shrinks when something limits it — and the button widened to whatever
        // the localized word needed. "Speaker" is narrow. "Lautsprecher",
        // "Haut-parleur" and "Динамик" are not, and four of them plus 25 pt gaps
        // stopped fitting a 375 pt iPhone in exactly the languages nobody
        // screenshots. Equal shares cannot overflow at any width, and they give
        // the caption the bound it needed to start scaling.
        HStack(spacing: 12) {
            // Mute button
            CallControlButton(
                icon: voiceCallManager.isMuted ? "mic.slash.fill" : "mic.fill",
                label: voiceCallManager.isMuted ? "call.unmute".localized : "call.mute".localized,
                isActive: voiceCallManager.isMuted,
                activeColor: .red
            ) {
                dismissAnyKeyboard()
                voiceCallManager.toggleMute()
                hapticFeedback(.light)
            }

            // Speaker button
            CallControlButton(
                icon: voiceCallManager.isSpeakerOn ? "speaker.wave.3.fill" : "speaker.fill",
                label: "call.speaker".localized,
                isActive: voiceCallManager.isSpeakerOn,
                activeColor: .blue
            ) {
                // Force-dismiss the keyboard FIRST. SwiftUI Buttons inside a
                // ZStack overlay can let the underlying ChatView TextField
                // re-focus on state change, popping the keyboard over the
                // call controls.
                dismissAnyKeyboard()
                voiceCallManager.toggleSpeaker()
                hapticFeedback(.light)
            }
            
            // Video button (when in call) — sends 0x0E REQUEST and waits
            // for the peer to accept. ContentView swaps VoiceCallView for
            // VideoCallView only after `isCurrentCallVideo` flips, which
            // happens on inbound 0x0E ACCEPT (or when the peer sends one
            // back). Until then we show a small "Inviting..." overlay.
            if voiceCallManager.callState == .inCall {
                CallControlButton(
                    icon: "video.fill",
                    label: "call.video.button".localized,
                    isActive: voiceCallManager.isAwaitingVideoUpgrade,
                    activeColor: .green
                ) {
                    voiceCallManager.sendVideoUpgradeSignal()
                    hapticFeedback(.medium)
                }
            }
            
            // Effects button - shows current effect name and icon
            CallControlButton(
                icon: voiceCallManager.currentEffect.icon,
                label: voiceCallManager.currentEffect == .none ? "call.effect".localized : voiceCallManager.currentEffect.displayName,
                isActive: voiceCallManager.currentEffect != .none,
                activeColor: .purple
            ) {
                hapticFeedback(.light)
                showEffectPicker = true
            }
        }
    }
    
    // MARK: - End Call Button
    private var endCallButton: some View {
        Button(action: {
            hapticFeedback(.medium)
            finalDuration = voiceCallManager.callDuration
            callEndReason = "call.ended.you".localized
            
            withAnimation(.easeInOut(duration: 0.3)) {
                showCallEndedSummary = true
            }
            
            voiceCallManager.endCall()
            
            // Auto dismiss after 2 seconds
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                dismiss()
            }
        }) {
            Image(systemName: "phone.down.fill")
                .font(.scaled(28, weight: .semibold))
                .foregroundColor(.white)
                .frame(width: 72, height: 72)
                .background(Color.red)
                .clipShape(Circle())
                .shadow(color: .red.opacity(0.4), radius: 10, y: 5)
        }
        .buttonStyle(ScaleButtonStyle())
        .accessibilityLabel(Text("accessibility.call.end".localized))
        .accessibilityHint(Text("accessibility.call.end.hint".localized))
    }
    
    // MARK: - Call Ended Overlay
    private var endedByLostConnection: Bool {
        voiceCallManager.lastCallEndReason == .peerDisconnected
            || voiceCallManager.lastCallEndReason == .connectionLost
    }

    private var callEndedOverlay: some View {
        ZStack {
            Color.black.opacity(0.55)
                .ignoresSafeArea()

            VStack(spacing: 18) {
                ZStack {
                    Circle()
                        .fill((endedByLostConnection ? Color.orange : Color.white).opacity(0.18))
                        .frame(width: 92, height: 92)
                    Image(systemName: endedByLostConnection ? "wifi.exclamationmark" : "phone.down.fill")
                        .font(.system(size: 38, weight: .semibold))
                        .foregroundStyle(endedByLostConnection ? Color.orange : Color.white)
                        .symbolEffect(.bounce, value: showCallEndedSummary)
                }

                Text(endedByLostConnection
                     ? String(format: "call.ended.connection_lost".localized, displayName)
                     : "call.ended.hungup".localized)
                    .font(.scaled(22, weight: .semibold, design: .rounded))
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)

                if !endedByLostConnection {
                    Text(displayName)
                        .font(.headline)
                        .foregroundColor(.white.opacity(0.8))
                }

                if finalDuration > 0 {
                    Text(formatDuration(finalDuration))
                        .font(.scaled(34, weight: .light, design: .rounded))
                        .foregroundColor(.white)
                        .monospacedDigit()
                }

                if endedByLostConnection, !contactPublicKey.isEmpty {
                    Button {
                        hapticFeedback(.medium)
                        redialing = true
                        withAnimation { showCallEndedSummary = false }
                        let key = contactPublicKey, name = contactName
                        Task { try? await voiceCallManager.startCall(to: key, peerName: name) }
                    } label: {
                        Label("call.redial".localized, systemImage: "phone.arrow.up.right.fill")
                            .font(.scaled(17, weight: .semibold))
                            .foregroundColor(.white)
                            .padding(.horizontal, 26)
                            .padding(.vertical, 13)
                            .background(Capsule().fill(Color.green))
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 4)
                } else if !endedByLostConnection {
                    Text(callEndReason)
                        .font(.caption)
                        .foregroundColor(.white.opacity(0.6))
                }
            }
            .padding(.horizontal, 36)
            .padding(.vertical, 32)
            .frame(maxWidth: 360)
            .oledAwareBackground(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
            .environment(\.colorScheme, .dark)
        }
    }

    // MARK: - Helper Functions
    private var formattedDuration: String {
        formatDuration(voiceCallManager.callDuration)
    }
    
    private func formatDuration(_ duration: TimeInterval) -> String {
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%02d:%02d", minutes, seconds)
    }
    
    private func hapticFeedback(_ style: UIImpactFeedbackGenerator.FeedbackStyle) {
        let generator = UIImpactFeedbackGenerator(style: style)
        generator.impactOccurred()
    }
    
    private func handleCallStateChange(from oldState: CallState, to newState: CallState) {
        switch newState {
        case .inCall:
            // Call connected - success haptic
            let generator = UINotificationFeedbackGenerator()
            generator.notificationOccurred(.success)
            
        case .ended:
            // Call ended
            if !showCallEndedSummary {
                finalDuration = voiceCallManager.callDuration
                callEndReason = voiceCallManager.lastCallEndReason?.displayText ?? "call.ended.hungup".localized
                redialing = false
                if endedByLostConnection {
                    UINotificationFeedbackGenerator().notificationOccurred(.warning)
                }

                withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                    showCallEndedSummary = true
                }

                // Auto dismiss — longer when the line dropped, so "Call again"
                // is actually reachable.
                let hold: TimeInterval = endedByLostConnection ? 7.0 : 2.0
                DispatchQueue.main.asyncAfter(deadline: .now() + hold) {
                    if !redialing { dismiss() }
                }
            }
            
        default:
            break
        }
    }
    
    // MARK: - Video Upgrade Request Banner (callee side)
    private var videoUpgradeRequestBanner: some View {
        VStack {
            VStack(spacing: 12) {
                HStack(spacing: 12) {
                    Image(systemName: "video.badge.plus")
                        .font(.scaled(28))
                        .foregroundColor(.white)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("call.video_upgrade.title".localized)
                            .font(.scaled(16, weight: .semibold))
                            .foregroundColor(.white)
                        Text(String(format: "call.video_upgrade.message".localized, voiceCallManager.pendingVideoUpgradeFrom ?? voiceCallManager.currentCallPeerName ?? "Peer"))
                            .font(.scaled(13))
                            .foregroundColor(.white.opacity(0.85))
                            .lineLimit(2)
                    }
                    Spacer()
                }
                // 📹 Three-way proposal: share my camera too (we see each other),
                // watch only (I see them, they don't see me), or decline.
                VStack(spacing: 10) {
                    Button(action: {
                        hapticFeedback(.medium)
                        voiceCallManager.acceptVideoUpgrade(shareCamera: true)
                    }) {
                        HStack(spacing: 6) {
                            Image(systemName: "video.fill")
                            Text("call.video_upgrade.accept_share".localized)
                        }
                        .font(.scaled(15, weight: .semibold))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 11)
                        .background(Color.green)
                        .cornerRadius(10)
                    }
                    HStack(spacing: 12) {
                        Button(action: {
                            hapticFeedback(.medium)
                            voiceCallManager.acceptVideoUpgrade(shareCamera: false)
                        }) {
                            HStack(spacing: 6) {
                                Image(systemName: "eye.fill")
                                Text("call.video_upgrade.watch_only".localized)
                            }
                            .font(.scaled(14, weight: .semibold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(Color.blue.opacity(0.85))
                            .cornerRadius(10)
                        }
                        Button(action: {
                            hapticFeedback(.medium)
                            voiceCallManager.declineVideoUpgrade()
                        }) {
                            Text("call.video_upgrade.decline".localized)
                                .font(.scaled(14, weight: .semibold))
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 10)
                                .background(Color.white.opacity(0.18))
                                .cornerRadius(10)
                        }
                    }
                }
            }
            .padding(16)
            .oledAwareBackground(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
            .cornerRadius(16)
            .padding(.horizontal, 16)
            .padding(.top, 60)
            Spacer()
        }
    }

    // MARK: - Awaiting Video Upgrade Overlay (caller side)
    private var awaitingVideoUpgradeOverlay: some View {
        VStack {
            HStack(spacing: 10) {
                ProgressView()
                    .progressViewStyle(CircularProgressViewStyle(tint: .white))
                    .scaleEffect(0.8)
                Text(String(format: "call.video_upgrade.inviting".localized, voiceCallManager.currentCallPeerName ?? "peer"))
                    .font(.scaled(14, weight: .medium))
                    .foregroundColor(.white)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Color.black.opacity(0.55))
            .cornerRadius(20)
            .padding(.top, 60)
            Spacer()
        }
    }

    private func handleQualityChange(from oldQuality: ConnectionQuality, to newQuality: ConnectionQuality) {
        // Play sound and haptic when quality changes significantly
        if newQuality == .reconnecting && oldQuality != .reconnecting {
            // Started reconnecting - warning haptic
            let generator = UINotificationFeedbackGenerator()
            generator.notificationOccurred(.warning)
            reconnectingPulse = true
            
            // Play reconnecting beep
            AudioServicesPlaySystemSound(1073)
            
        } else if oldQuality == .reconnecting && newQuality != .reconnecting {
            // Reconnected - success haptic
            let generator = UINotificationFeedbackGenerator()
            generator.notificationOccurred(.success)
            reconnectingPulse = false
            
            // Play reconnected sound
            AudioServicesPlaySystemSound(1003)
            
        } else if (newQuality == .poor || newQuality == .veryPoor) && oldQuality != .poor && oldQuality != .veryPoor {
            // Quality dropped to poor
            let generator = UINotificationFeedbackGenerator()
            generator.notificationOccurred(.warning)
        }
        
        previousQuality = newQuality
    }
}

// MARK: - Call Control Button
// MARK: - Keyboard dismissal helper

private func dismissAnyKeyboard() {
    #if canImport(UIKit) && os(iOS)
    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder),
                                    to: nil, from: nil, for: nil)
    #endif
}

// MARK: - Voice Effect Picker (one-tap grid; replaces blind cycling)
// __VOICE_EFFECT_PICKER_2026_06_19__
struct VoiceEffectPickerView: View {
    @ObservedObject var voiceCallManager: VoiceCallManager
    @Environment(\.dismiss) private var dismiss
    private let brandColor = Theme.brand
    private let columns = [GridItem(.adaptive(minimum: 104), spacing: 14)]

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 14) {
                    ForEach(CallVoiceEffect.allCases) { effect in
                        let selected = voiceCallManager.currentEffect == effect
                        Button {
                            #if os(iOS)
                            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                            #endif
                            voiceCallManager.setEffect(effect)
                        } label: {
                            VStack(spacing: 8) {
                                Image(systemName: effect.icon)
                                    .font(.scaled(26))
                                    .frame(height: 30)
                                Text(effect.displayName)
                                    .font(.subheadline.weight(.semibold))
                                Text(effect.description)
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                                    .multilineTextAlignment(.center)
                                    .lineLimit(2)
                            }
                            .frame(maxWidth: .infinity, minHeight: 116)
                            .padding(12)
                            .background(
                                RoundedRectangle(cornerRadius: 16)
                                    .fill(selected ? brandColor.opacity(0.18) : Color(.secondarySystemBackground))
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: 16)
                                    .stroke(selected ? brandColor : Color.clear, lineWidth: 2)
                            )
                            .overlay(alignment: .topTrailing) {
                                if selected {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundStyle(brandColor)
                                        .padding(8)
                                }
                            }
                            .foregroundStyle(selected ? brandColor : Color.primary)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(Text(effect.displayName))
                        .accessibilityHint(Text(effect.description))
                        .accessibilityAddTraits(selected ? [.isSelected] : [])
                    }
                }
                .padding()
            }
            .navigationTitle(Text("call.effect".localized))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(NSLocalizedString("common.done", value: "Done", comment: "")) { dismiss() }
                }
            }
        }
    }
}

struct CallControlButton: View {
    /// (N12) Disc diameter, pinned by `CallControlLayoutTests`.
    static let discSize: CGFloat = 60

    let icon: String
    let label: String
    let isActive: Bool
    let activeColor: Color
    let action: () -> Void
    /// __OLED_GLASS_2026_11_16__ Under OLED black an idle disc is a glass
    /// bead (dim fill + hairline + sheen) instead of a grey 24 % slab; an
    /// active one keeps its colour and gets a soft halo.
    @Environment(\.oledBlackActive) private var oledBlack

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                // __ANATOLE_1311_IOS__ (N12) "boutons plus ronds et légèrement
                // plus épais": 56 → 60 pt discs (the most four equal shares
                // allow on a 320 pt pane — see CallControlLayoutTests), a
                // semibold glyph, and a slightly denser idle fill so the disc
                // reads as a button and not a ghost on a bright avatar.
                ZStack {
                    Circle()
                        .fill(isActive ? activeColor : Color.white.opacity(oledBlack ? 0.10 : 0.24))
                        .frame(width: CallControlButton.discSize, height: CallControlButton.discSize)
                        .oledGlassEdge(Circle())
                        .shadow(color: oledBlack && isActive ? activeColor.opacity(0.45) : .clear, radius: 12)
                        .animation(.spring(response: 0.3, dampingFraction: 0.7), value: isActive)

                    Image(systemName: icon)
                        .font(.scaled(24, weight: .semibold))
                        .foregroundColor(.white)
                }

                Text(label)
                    .font(.caption)
                    .foregroundColor(.white.opacity(0.8))
                    .lineLimit(1)
                    // Bounded, so `minimumScaleFactor` has something to scale
                    // against instead of widening the whole button.
                    .frame(maxWidth: .infinity)
                    .minimumScaleFactor(0.7)
            }
            // Fill the equal share the row hands out (see `controlButtonsView`),
            // never less than a comfortable hit target around the 56 pt circle.
            .frame(maxWidth: .infinity, minHeight: 60)
            .contentShape(Rectangle())
        }
        .buttonStyle(ScaleButtonStyle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(label))
        .accessibilityAddTraits(isActive ? [.isSelected] : [])
    }
}

// MARK: - Scale Button Style
struct ScaleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.92 : 1.0)
            .animation(.easeInOut(duration: 0.15), value: configuration.isPressed)
    }
}

// MARK: - Transport Details Sheet (Phase 1 diagnostic drawer)
struct TransportDetailsSheet: View {
    @ObservedObject var voiceCallManager: VoiceCallManager
    @Environment(\.dismiss) private var dismiss

    // Snapshot of stats refreshed at 1 Hz by a Timer. Reading via snapshot
    // avoids making `transportStats` @Published — the audio TX/RX path mutates
    // it ~250×/sec which would saturate MainActor and freeze the call UI.
    @State private var statsSnapshot: [VoiceCallManager.TransportPath: VoiceCallManager.TransportStats] = [:]
    @State private var refreshTimer: Timer?

    /// All transports we want to display, in the order shown.
    private let allPaths: [VoiceCallManager.TransportPath] = [
        .p2pDirect, .lanMesh, .lanBridge,
        .p2pTurnRelay, .udpRelay,
        .vpsWebSocket, .vpsHttp
    ]

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        Image(systemName: voiceCallManager.activeTransport.symbol)
                            .font(.title2)
                            .foregroundColor(tierColor(voiceCallManager.activeTransport.tier))
                        VStack(alignment: .leading) {
                            Text("call.diagnostics.active_path".localized)
                                .font(.caption)
                                .foregroundColor(.secondary)
                            Text(voiceCallManager.activeTransport.displayName)
                                .font(.headline)
                        }
                        Spacer()
                        tierBadge(voiceCallManager.activeTransport.tier)
                    }
                    .padding(.vertical, 4)
                } footer: {
                    Text(tierExplanation(voiceCallManager.activeTransport.tier))
                }

                Section("call.diagnostics.activity".localized) {
                    ForEach(allPaths, id: \.self) { path in
                        transportRow(path)
                    }
                }
            }
            .navigationTitle("call.diagnostics.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("common.done".localized) { dismiss() }
                }
            }
            .onAppear {
                statsSnapshot = voiceCallManager.snapshotTransportStats()
                refreshTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { _ in
                    statsSnapshot = voiceCallManager.snapshotTransportStats()
                }
            }
            .onDisappear {
                refreshTimer?.invalidate()
                refreshTimer = nil
            }
        }
    }

    private func transportRow(_ path: VoiceCallManager.TransportPath) -> some View {
        let stats = statsSnapshot[path]
        let alive = stats?.lastRxAt.map { Date().timeIntervalSince($0) < 3.0 } ?? false
        return HStack(spacing: 12) {
            Image(systemName: path.symbol)
                .frame(width: 24)
                .foregroundColor(alive ? tierColor(path.tier) : .secondary.opacity(0.4))
            VStack(alignment: .leading, spacing: 2) {
                Text(path.displayName)
                    .font(.subheadline)
                if let s = stats, s.rxPackets > 0 || s.txPackets > 0 {
                    Text("↑ \(s.txPackets) pkt · ↓ \(s.rxPackets) pkt · \(formatBytes(s.rxBytes + s.txBytes))")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                } else {
                    Text("never used this call")
                        .font(.caption2)
                        .foregroundColor(.secondary.opacity(0.6))
                }
            }
            Spacer()
            if alive {
                Circle().fill(Color.green).frame(width: 8, height: 8)
            }
        }
    }

    private func tierColor(_ tier: VoiceCallManager.TransportPath.Tier) -> Color {
        switch tier {
        case .direct:  return .green
        case .relayed: return .yellow
        case .server:  return .orange
        case .unknown: return .gray
        }
    }

    private func tierBadge(_ tier: VoiceCallManager.TransportPath.Tier) -> some View {
        Text(tierLabel(tier))
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(tierColor(tier).opacity(0.2))
            .foregroundColor(tierColor(tier))
            .clipShape(Capsule())
    }

    private func tierLabel(_ tier: VoiceCallManager.TransportPath.Tier) -> String {
        switch tier {
        case .direct:  return "DIRECT"
        case .relayed: return "RELAYED"
        case .server:  return "VPS"
        case .unknown: return "—"
        }
    }

    private func tierExplanation(_ tier: VoiceCallManager.TransportPath.Tier) -> String {
        switch tier {
        case .direct:  return "Your audio is travelling peer-to-peer with no relay in between. Lowest latency, no server in the path."
        case .relayed: return "Your audio goes through a UDP relay that forwards encrypted packets. The relay can't decrypt content."
        case .server:  return "Your audio is being relayed by the OSHI server. End-to-end encrypted — the server can't decrypt content, but it sees who is calling whom."
        case .unknown: return "No audio packets have been delivered yet."
        }
    }

    private func formatBytes(_ n: UInt64) -> String {
        if n < 1024 { return "\(n) B" }
        if n < 1024 * 1024 { return String(format: "%.1f KB", Double(n) / 1024) }
        return String(format: "%.2f MB", Double(n) / 1024 / 1024)
    }
}

// MARK: - Preview
#Preview {
    VoiceCallView(voiceCallManager: VoiceCallManager.shared, contactName: "John Doe", contactPublicKey: "abc123")
}

/// __VOICE_METER_2026_09_22__ Onde vocale de l'écran d'appel.
///
/// Les niveaux arrivent à 20 Hz; `TimelineView` redessine à la cadence de
/// l'écran et lisse entre deux: attaque en ~2 images, retombée en ~150 ms, ce
/// qui colle aux syllabes sans scintiller. Au silence les barres se posent à
/// plat — c'est ce qui rend le mouvement lisible quand quelqu'un parle.
private struct CallVoiceWaveform: View {
    let micLevel: Float
    let remoteLevel: Float
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var smoother = LevelSmoother()

    private static let barCount = 24

    var body: some View {
        TimelineView(.animation(paused: reduceMotion)) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            let mine = CGFloat(micLevel), theirs = CGFloat(remoteLevel)
            let level = reduceMotion ? max(mine, theirs) : smoother.step(target: max(mine, theirs), at: t)
            let speakingColor = mine > theirs ? Color.green.opacity(0.9) : Color.white.opacity(0.85)
            GeometryReader { geo in
                HStack(alignment: .center, spacing: 3) {
                    ForEach(0..<Self.barCount, id: \.self) { i in
                        Capsule()
                            .fill(level > 0.05 ? speakingColor : Color.white.opacity(0.35))
                            .frame(width: 3, height: barHeight(i, level: level, t: t, maxH: geo.size.height))
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    private func barHeight(_ i: Int, level: CGFloat, t: TimeInterval, maxH: CGFloat) -> CGFloat {
        let minH: CGFloat = 4
        // Enveloppe en cloche: le centre porte la voix, les bords suivent.
        let x = (CGFloat(i) / CGFloat(Self.barCount - 1)) * 2 - 1
        let envelope = 0.35 + 0.65 * exp(-x * x * 2.2)
        // Variation par barre pour que l'onde vive; figée si Réduire les
        // animations est actif (la hauteur suit toujours la voix).
        let wobble: CGFloat = reduceMotion ? 1 : CGFloat(
            0.62 + 0.23 * sin(t * 9.1 + Double(i) * 1.7)
                 + 0.15 * sin(t * 15.3 + Double(i) * 0.6))
        return minH + (maxH - minH) * min(1, level * envelope * wobble * 1.15)
    }
}

/// Lissage attaque/retombée, tenu hors de l'état SwiftUI: il change à chaque
/// image et ne doit pas redéclencher le rendu qui l'appelle.
private final class LevelSmoother {
    private var value: CGFloat = 0
    private var lastT: TimeInterval = 0

    func step(target: CGFloat, at t: TimeInterval) -> CGFloat {
        let dt = lastT == 0 ? 1.0 / 60 : min(0.1, max(0, t - lastT))
        lastT = t
        let tau: Double = target > value ? 0.03 : 0.15
        let k = CGFloat(1 - exp(-dt / tau))
        value += (target - value) * k
        return value
    }
}


// MARK: - Peer lost card
//
// __PEER_LOST_UX_2026_09_29__ Shown while the other side is silent. A ring
// empties toward the auto hang-up so the wait has a visible end, and the copy
// says WHOSE connection is the problem — ours (offline) or theirs.
struct PeerLostCard: View {
    let peerName: String
    let deadline: Date
    let totalSeconds: Double
    let selfOffline: Bool

    @State private var pulse = false

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { context in
            let remaining = max(0, deadline.timeIntervalSince(context.date))
            let fraction = min(1, remaining / totalSeconds)
            HStack(spacing: 14) {
                ZStack {
                    Circle()
                        .stroke(Color.white.opacity(0.18), lineWidth: 4)
                    Circle()
                        .trim(from: 0, to: fraction)
                        .stroke(Color.white, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                    Image(systemName: selfOffline ? "wifi.slash" : "antenna.radiowaves.left.and.right")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(.white)
                        .symbolEffect(.variableColor.iterative, options: .repeating, isActive: !selfOffline)
                        .scaleEffect(pulse ? 1.06 : 0.94)
                }
                .frame(width: 48, height: 48)

                VStack(alignment: .leading, spacing: 3) {
                    Text(selfOffline
                         ? "call.self_offline.title".localized
                         : String(format: "call.peer_lost.waiting".localized, peerName))
                        .font(.scaled(16, weight: .semibold, design: .rounded))
                        .foregroundColor(.white)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                    Text(selfOffline
                         ? "call.self_offline.subtitle".localized
                         : "call.peer_lost.subtitle".localized)
                        .font(.scaled(13))
                        .foregroundColor(.white.opacity(0.8))
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(String(format: "call.peer_lost.ends_in".localized, Int(remaining.rounded(.up))))
                        .font(.scaled(12, weight: .medium))
                        .foregroundColor(.white.opacity(0.65))
                        .monospacedDigit()
                        .contentTransition(.numericText(countsDown: true))
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: 380)
            .oledAwareBackground(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.22), lineWidth: 1)
            )
            .environment(\.colorScheme, .dark)
        }
        .onAppear {
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { pulse = true }
        }
        .accessibilityElement(children: .combine)
    }
}
