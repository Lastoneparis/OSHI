//
//  MascotLaunchView.swift
//  OSHI — cold-launch hello from the O bubble (__O_BUBBLE_MASCOT_2026_11_16__)
//
//  Replaces the 5.1 s `LaunchMotion.mp4` splash. Choreography, ≈1.15 s:
//
//    0.00  the plain OSHI mark (`LaunchMark`) — the SAME image, size and
//          centre as the system launch screen (Info.plist `UILaunchScreen`
//          → `UIImageName = LaunchMark`, `UIColorName = LaunchBackground`),
//          so the hand-off from the OS is invisible
//    0.05  crossfade to the neutral mascot (0.25 s); the ring and tail sit
//          on the same pixels in both files, so nothing moves
//    0.30  wave, every other frame of `launch/wave` at ~31 fps (0.65 s);
//          it starts and ends on the neutral pose
//    0.95  fade out (0.2 s)
//
//  NEVER DELAYS READINESS. It never takes touches (`allowsHitTesting(false)`),
//  so the app underneath is usable the moment it is ready; it is skipped
//  entirely when the launch comes from a notification (caller), and it is
//  never on screen for more than its ~1.15 s once the app is ready. If the
//  app is NOT ready when the wave ends, it rests on the neutral pose instead
//  of revealing the interim splash, and fades as soon as it is (≤ 2.5 s
//  more). Its clock only advances on rendered frames (see
//  `MascotLaunchClock`), so a busy main thread pauses it instead of skipping
//  straight to the end.
//
//  Reduce Motion: logo → neutral fade, short hold, fade out (~0.9 s). The
//  wave frames are decoded off the main thread; if they are late the wave is
//  skipped, never waited for.
//

import SwiftUI
import UIKit

enum MascotLaunchTiming {
    static let crossfadeDelay: TimeInterval = 0.05
    static let crossfade: TimeInterval = 0.25
    /// Frames of `launch/wave` played: every other one, 00 … 38, then 39.
    static let waveFrames: [Int] = Array(stride(from: 0, to: 40, by: 2)) + [39]
    static let waveFPS: Double = 31
    static var wave: TimeInterval { Double(waveFrames.count) / waveFPS }
    static let fadeOut: TimeInterval = 0.2
    static let reduceMotionHold: TimeInterval = 0.35
    /// Longest rest on the neutral pose waiting for the app (rendered time).
    static let maxHold: TimeInterval = 2.5
    /// Rendered frames to wait after the app reports ready.
    static let readySettleTicks = 6
    /// Never stay past this much wall time after the first rendered frame.
    static let hardCap: TimeInterval = 8.0
    /// Same box as the system launch screen's `LaunchMark` (200 pt @1x).
    static let side: CGFloat = 200

    /// Total on a fast launch.
    static var total: TimeInterval { crossfadeDelay + crossfade + wave + fadeOut }
    static var totalReduceMotion: TimeInterval { crossfadeDelay + crossfade + reduceMotionHold + fadeOut }
}

/// The choreography's clock. NOT wall time: each display frame advances it
/// by at most 1/30 s, so a main thread busy with launch work PAUSES the
/// animation instead of skipping it (measured on a debug simulator build:
/// with wall-clock timers the first rendered frame was already mid-wave and
/// the crossfade was never seen). It starts on the first display-link tick,
/// i.e. once the first frame is actually on screen.
final class MascotLaunchClock: ObservableObject {
    @Published private(set) var t: TimeInterval = 0
    /// Wall time since the first rendered frame.
    private(set) var wall: TimeInterval = 0
    private var link: CADisplayLink?
    private var last: CFTimeInterval?
    private var first: CFTimeInterval?
    var onTick: (() -> Void)?
    /// Holds `t` at this value while true (waiting for the app).
    var holdAt: TimeInterval?
    /// Mirrors the view's `isAppReady` (the tick closure holds a copy of the
    /// view struct, whose `let` would be stale).
    var appReady = false
    /// Rendered ticks so far, and the tick the app was first seen ready.
    private(set) var ticks = 0
    var readyTick: Int?

    func start() {
        guard link == nil else { return }
        let l = CADisplayLink(target: self, selector: #selector(tick(_:)))
        l.add(to: .main, forMode: .common)
        link = l
    }

    func stop() { link?.invalidate(); link = nil }

    @objc private func tick(_ l: CADisplayLink) {
        let now = l.targetTimestamp
        // Not on screen yet (the OS still shows the launch screen until the
        // scene is active): do not start the choreography.
        guard Self.sceneIsActive() else { last = now; return }
        if first == nil { first = now }
        ticks += 1
        let dt = min(max(now - (last ?? now), 0), 1.0 / 30)
        last = now
        wall = now - (first ?? now)
        var next = t + dt
        if let h = holdAt, next > h { next = max(t, h) }
        if next != t { t = next }
        onTick?()
    }

    static func sceneIsActive() -> Bool {
        UIApplication.shared.connectedScenes.contains { $0.activationState == .foregroundActive }
    }

    deinit { link?.invalidate() }
}

struct MascotLaunchView: View {
    /// true once the app underneath can be shown (`!isInitializing`).
    let isAppReady: Bool
    let onFinish: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.displayScale) private var displayScale
    /// __OLED_BLACK_2026_11_16__ OLED black active: the ground ends on #000.
    @Environment(\.oledBlackActive) private var oledBlack

    @StateObject private var clock = MascotLaunchClock()
    @State private var waveImages: [UIImage] = []
    /// Clock time the fade-out began, nil while it has not.
    @State private var fadeStart: TimeInterval?
    @State private var waveSkipped = false
    @State private var finished = false

    private let neutral: UIImage? = OSHIMascotCatalog.launchWaveURL(index: 0)
        .flatMap { UIImage(contentsOfFile: $0.path) }

    private typealias T = MascotLaunchTiming

    private var waveAt: TimeInterval { T.crossfadeDelay + T.crossfade }
    /// Clock time the choreography is complete (before the fade).
    private var restAt: TimeInterval {
        reduceMotion || waveSkipped ? waveAt + T.reduceMotionHold : waveAt + T.wave
    }

    var body: some View {
        let t = clock.t
        let neutralOpacity = min(1, max(0, (t - T.crossfadeDelay) / T.crossfade))
        let fade = fadeStart.map { min(1, max(0, (t - $0) / T.fadeOut)) } ?? 0
        ZStack {
            // Starts on the system launch screen's own colour so the OS
            // hand-off stays invisible. Under OLED black the ground then
            // crossfades to true black WITH the mark → mascot crossfade, so
            // the wave plays on #000 like the rest of the app (and like the
            // interim `LaunchScreenView`, already black under OLED).
            ZStack {
                Color("LaunchBackground")
                if oledBlack { Color.black.opacity(neutralOpacity) }
            }
            .ignoresSafeArea()
            ZStack {
                Image("LaunchMark")
                    .resizable()
                    .frame(width: T.side, height: T.side)
                    .opacity(1 - neutralOpacity)
                if let img = mascotImage(at: t) {
                    Image(uiImage: img)
                        .resizable()
                        .frame(width: T.side, height: T.side)
                        .opacity(neutralOpacity)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .ignoresSafeArea()
        }
        // One layer, so the mascot does not show through its own background
        // while both fade.
        .compositingGroup()
        .opacity(1 - fade)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onAppear(perform: start)
        .onDisappear { clock.stop() }
        .onChange(of: isAppReady) { _, ready in clock.appReady = ready }
    }

    private func mascotImage(at t: TimeInterval) -> UIImage? {
        guard !reduceMotion, !waveSkipped, !waveImages.isEmpty, t >= waveAt, t < waveAt + T.wave else { return neutral }
        let i = min(waveImages.count - 1, Int((t - waveAt) * T.waveFPS))
        return waveImages[i]
    }

    private func start() {
        guard neutral != nil else { finish(); return }
        if !reduceMotion { decodeWave() }
        clock.appReady = isAppReady
        clock.onTick = { tick() }
        clock.start()
    }

    private func tick() {
        let t = clock.t
        // Frames not decoded by the time the wave is due: skip it, never wait.
        if !reduceMotion, !waveSkipped, waveImages.isEmpty, t >= waveAt { waveSkipped = true }
        if fadeStart == nil {
            let done = t >= restAt
            // Progress, not wall time: frames the user never saw do not count.
            // Ready = ready for a few rendered frames: the app's first heavy
            // render then happens under the opaque overlay, not during the
            // fade (measured: a stall there made the fade jump 100 % → 25 %).
            if clock.appReady, clock.readyTick == nil { clock.readyTick = clock.ticks }
            let ready = clock.readyTick.map { clock.ticks - $0 >= T.readySettleTicks } ?? false
            if (done && ready) || t >= restAt + T.maxHold || clock.wall >= T.hardCap {
                clock.holdAt = nil
                fadeStart = t
            } else if done {
                clock.holdAt = restAt   // rest on the neutral pose until ready
            }
        } else if let f = fadeStart, t >= f + T.fadeOut {
            finish()
        }
    }

    private func decodeWave() {
        let px = MascotFrameStore.pixelBucketLaunch(points: T.side, scale: displayScale)
        let scale = displayScale
        DispatchQueue.global(qos: .userInteractive).async {
            var images: [UIImage] = []
            for i in T.waveFrames {
                guard let url = OSHIMascotCatalog.launchWaveURL(index: i),
                      let img = MascotFrameStore.decode(url, px: px, scale: scale) else { return }
                images.append(img)
            }
            DispatchQueue.main.async { if !waveSkipped { waveImages = images } }
        }
    }

    private func finish() {
        guard !finished else { return }
        finished = true
        clock.stop()
        waveImages = []
        onFinish()
    }
}

extension MascotFrameStore {
    /// The launch frames are 512 px; never upsample, never decode more than drawn.
    static func pixelBucketLaunch(points: CGFloat, scale: CGFloat) -> Int {
        min(512, Int((points * scale).rounded(.up)))
    }
}
