//
//  LaunchMotionView.swift
//  OSHI — animated branded launch splash (__LAUNCH_MOTION_2026_06_21__)
//
//  iOS launch screens are static, so the standard pattern is: show a static
//  LaunchScreen, then play a short branded motion on top at cold launch and
//  fade to the app. This plays `LaunchMotion.mp4` (OSHI logo reveal) once,
//  muted, aspect-fit on black, then calls onFinish (also tap-to-skip + a
//  safety timeout so we never get stuck on the splash).
//

import SwiftUI
import AVFoundation

struct LaunchMotionView: View {
    let onFinish: () -> Void
    @State private var didFinish = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let url = Bundle.main.url(forResource: "LaunchMotion", withExtension: "mp4") {
                LaunchPlayerView(url: url, onEnd: finish)
                    .ignoresSafeArea()
            } else {
                // No video bundled → don't block the user; finish right away.
                Color.black.onAppear(perform: finish)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { finish() }   // tap to skip
        .onAppear {
            // Safety net: the clip is ~5.1s — never linger past 7s regardless.
            DispatchQueue.main.asyncAfter(deadline: .now() + 7.0) { finish() }
        }
    }

    private func finish() {
        guard !didFinish else { return }
        didFinish = true
        onFinish()
    }
}

// MARK: - AVPlayerLayer-backed view (no controls, holds last frame)

private struct LaunchPlayerView: UIViewRepresentable {
    let url: URL
    let onEnd: () -> Void

    func makeUIView(context: Context) -> LaunchPlayerUIView {
        LaunchPlayerUIView(url: url, onEnd: onEnd)
    }
    func updateUIView(_ uiView: LaunchPlayerUIView, context: Context) {}
}

private final class LaunchPlayerUIView: UIView {
    private let playerLayer = AVPlayerLayer()
    private var player: AVPlayer?
    private let onEnd: () -> Void
    private var ended = false
    private var endObserver: NSObjectProtocol?

    init(url: URL, onEnd: @escaping () -> Void) {
        self.onEnd = onEnd
        super.init(frame: .zero)
        backgroundColor = .black

        let item = AVPlayerItem(url: url)
        let p = AVPlayer(playerItem: item)
        p.isMuted = true                    // never blast audio on launch
        p.actionAtItemEnd = .pause          // hold the final OSHI logo frame
        player = p

        playerLayer.player = p
        playerLayer.videoGravity = .resizeAspectFill   // portrait clip fills the screen edge-to-edge
        layer.addSublayer(playerLayer)

        // 🔧 LAUNCH-FREEZE FIX: block-based observer with `queue: .main`. The
        // selector form has no queue, so AVFoundation delivered end-of-play on its
        // OWN thread — and `onEnd` here dismisses the splash by mutating SwiftUI
        // @State. Off-main @State mutation is undefined; when it lost, this
        // full-screen opaque overlay (zIndex 1000) simply never went away, which
        // reads as "the app hangs just after the launch screen".
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main
        ) { [weak self] _ in
            self?.didReachEnd()
        }
        p.play()
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func layoutSubviews() {
        super.layoutSubviews()
        playerLayer.frame = bounds
    }

    private func didReachEnd() {
        guard !ended else { return }
        ended = true
        onEnd()
    }

    deinit {
        // Block-based observers are keyed by token, not by observer object.
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
    }
}
