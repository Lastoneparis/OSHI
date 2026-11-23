//
//  AnimatedImageView.swift
//  OSHI — real GIF playback
//
//  Why this file exists
//  --------------------
//  Every path in the app that turns bytes into a picture runs them through
//  `MediaManager.safeUIImage(from:)`, which ends at
//  `CGImageSourceCreateThumbnailAtIndex(source, 0, …)`. That call is correct for
//  a photo and catastrophic for a GIF: index 0 is FRAME ZERO. A GIF displayed
//  that way is not "a GIF that doesn't animate yet", it is a still picture, and
//  every test that checks "the image rendered" passes on it.
//
//  So GIFs need their own view, and it must not go through UIImage at all.
//

import SwiftUI
import UIKit
import ImageIO

// MARK: - SwiftUI entry point

/// Plays an animated GIF (or APNG) from raw bytes.
///
/// Falls back to the first frame, silently, when the bytes turn out not to
/// animate — a single-frame GIF, a JPEG that reached us mislabelled, a truncated
/// download. A still picture is the right answer there; an error state would be
/// noise for something the user cannot act on.
///
///     AnimatedImageView(data: attachment.data)
///         .aspectRatio(ratio, contentMode: .fit)
///
struct AnimatedImageView: UIViewRepresentable {

    let data: Data

    /// Set false to hold the animation without tearing the view down (the
    /// picker uses it while a sheet is over the grid). Off-screen and
    /// backgrounded pausing is handled by the view itself — callers do not need
    /// to drive this for either.
    var isPlaying: Bool = true

    var contentMode: UIView.ContentMode = .scaleAspectFill

    func makeUIView(context: Context) -> AnimatedGIFView {
        let view = AnimatedGIFView()
        view.contentMode = contentMode
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        view.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        view.setContentHuggingPriority(.defaultLow, for: .horizontal)
        view.setContentHuggingPriority(.defaultLow, for: .vertical)
        return view
    }

    func updateUIView(_ view: AnimatedGIFView, context: Context) {
        view.imageContentMode = contentMode
        view.isPlaybackRequested = isPlaying
        view.setGIFData(data)
    }

    static func dismantleUIView(_ view: AnimatedGIFView, coordinator: Coordinator) {
        // Belt and braces. `didMoveToWindow(nil)` already stops playback, but a
        // representable can be torn down without that firing in some SwiftUI
        // update orders, and a running animation retains its block — and its
        // frames — for as long as it runs.
        view.stopAnimating()
    }
}

// MARK: - The UIKit view that does the work

/// A `UIImageView` fed one `CGImage` at a time by `CGAnimateImageDataWithBlock`.
///
/// Why that API and not the alternatives:
///
///  * **Not `UIImage.animatedImage(with:duration:)`.** That decodes EVERY frame
///    into memory up front and then plays them at ONE uniform duration. A 5 MB
///    GIF becomes hundreds of megabytes of bitmaps (frames are decompressed
///    RGBA — a 480×480 frame is ~900 KB each), and per-frame delays — which real
///    GIFs use constantly — are flattened to an average, so the timing is wrong
///    on almost everything.
///  * **Not a hand-rolled `CADisplayLink` + `CGImageSourceCreateImageAtIndex`
///    loop.** That is the classic third-party approach and it means reimplementing
///    frame-delay parsing, the 10 ms→100 ms delay clamp browsers apply, loop
///    counts, and a frame cache eviction policy.
///  * **`CGAnimateImageDataWithBlock` decodes lazily, one frame at a time,
///    honours the file's own per-frame delays and loop count, and hands back a
///    ready `CGImage`.** Peak memory is roughly one frame.
///
/// The one thing its documentation makes explicit and that shapes this class:
/// *"The block is called on the main queue at time intervals specified by the
/// delay time of the image."* Every playing GIF is therefore main-thread work at
/// its own frame rate. Twenty of them in a picker grid is twenty timers on the
/// main queue, which is precisely why stopping off-screen playback below is not
/// a nicety.
final class AnimatedGIFView: UIView {

    private let imageView = UIImageView()

    /// Kept so playback can be restarted (scrolling back into view, returning
    /// from the background) without the caller having to re-supply it.
    private var gifData: Data?

    /// Set to true to make the running block stop at its next frame.
    ///
    /// The animation is only stoppable from INSIDE its own callback — the API
    /// gives us a `bool*` in the block and nothing else. There is no handle to
    /// cancel from outside, so "stop" is a flag the block reads.
    private var stopRequested = false

    private var isAnimating = false

    /// Where playback resumes. Restarting a 100-frame GIF from frame 0 every
    /// time it scrolls back into view makes a list of GIFs look like it is
    /// stuttering; `kCGImageAnimationStartIndex` costs nothing and makes the
    /// resume invisible.
    private var lastFrameIndex: Int = 0

    /// Guards against two animations running at once. Each start takes the next
    /// generation; a block from an older generation stops itself the first time
    /// it fires. Without this, a data change or a fast scroll leaves two loops
    /// writing to the same image view and the GIF plays at double speed.
    private var generation: UInt64 = 0

    var isPlaybackRequested: Bool = true {
        didSet { syncPlaybackState() }
    }

    var imageContentMode: UIView.ContentMode = .scaleAspectFill {
        didSet { imageView.contentMode = imageContentMode }
    }

    // MARK: Init

    override init(frame: CGRect) {
        super.init(frame: frame)
        setUp()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setUp()
    }

    private func setUp() {
        clipsToBounds = true
        imageView.contentMode = imageContentMode
        imageView.clipsToBounds = true
        imageView.translatesAutoresizingMaskIntoConstraints = false
        addSubview(imageView)
        NSLayoutConstraint.activate([
            imageView.leadingAnchor.constraint(equalTo: leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: trailingAnchor),
            imageView.topAnchor.constraint(equalTo: topAnchor),
            imageView.bottomAnchor.constraint(equalTo: bottomAnchor)
        ])

        // Backgrounding: iOS does not stop the animation for us, and a GIF that
        // keeps ticking the main queue while the app is suspended is both wasted
        // battery and, on a return to foreground, a burst of queued frames.
        NotificationCenter.default.addObserver(
            self, selector: #selector(appDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification, object: nil)
        NotificationCenter.default.addObserver(
            self, selector: #selector(appWillEnterForeground),
            name: UIApplication.willEnterForegroundNotification, object: nil)
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    // MARK: Content

    /// Idempotent: handing back the same bytes does not restart the animation.
    ///
    /// This matters because `updateUIView` is called on every SwiftUI update,
    /// which in a scrolling grid is constantly. Comparing `Data` is a memcmp of
    /// megabytes, so identity is checked cheaply first (count) and the full
    /// comparison only runs when it could plausibly differ.
    func setGIFData(_ data: Data) {
        if let current = gifData, current.count == data.count, current == data {
            syncPlaybackState()
            return
        }

        stopAnimating()
        gifData = data
        lastFrameIndex = 0

        // Paint frame 0 immediately. `CGAnimateImageDataWithBlock` does not call
        // back synchronously, so without this the view is blank for one frame
        // interval — which in a grid reads as "the GIFs load one by one" rather
        // than "the GIFs are there".
        imageView.image = Self.firstFrame(of: data)

        syncPlaybackState()
    }

    /// Frame 0 as a `UIImage`, for the initial paint and the non-animated
    /// fallback. This is the very `CGImageSourceCreateThumbnailAtIndex` call
    /// that ruins a GIF when it is used as the whole render path — here it is
    /// only the poster.
    private static func firstFrame(of data: Data) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let cgImage = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
            return nil
        }
        return UIImage(cgImage: cgImage)
    }

    // MARK: Playback lifecycle

    override func didMoveToWindow() {
        super.didMoveToWindow()
        // `window == nil` covers the case that actually happens: a cell scrolled
        // out of a `LazyVGrid` and recycled. SwiftUI's `.onDisappear` is not
        // reliable for that and fires at different times depending on the
        // container, so the stop is anchored to UIKit's view hierarchy instead,
        // which cannot be wrong about whether the view is on screen.
        syncPlaybackState()
    }

    override func didMoveToSuperview() {
        super.didMoveToSuperview()
        syncPlaybackState()
    }

    @objc private func appDidEnterBackground() { stopAnimating() }

    @objc private func appWillEnterForeground() { syncPlaybackState() }

    private var shouldBeAnimating: Bool {
        isPlaybackRequested
            && window != nil
            && gifData != nil
            && UIApplication.shared.applicationState != .background
    }

    private func syncPlaybackState() {
        if shouldBeAnimating {
            startAnimating()
        } else {
            stopAnimating()
        }
    }

    func startAnimating() {
        guard !isAnimating, let data = gifData else { return }

        generation &+= 1
        let myGeneration = generation
        stopRequested = false
        isAnimating = true

        let options: [CFString: Any] = [
            // The GIF's own loop count is usually "forever", but a surprising
            // number of files declare 1 — and a reaction GIF that plays once and
            // freezes looks broken in a chat bubble. Override to infinite and
            // let leaving the screen be what stops it.
            kCGImageAnimationLoopCount: kCFNumberPositiveInfinity as Any,
            kCGImageAnimationStartIndex: lastFrameIndex
        ]

        // Returns immediately; the block is then called on the main queue at the
        // file's own frame delays until something sets `stop`.
        let status = CGAnimateImageDataWithBlock(data as CFData, options as CFDictionary) { [weak self] index, cgImage, stop in
            guard let self else {
                stop.pointee = true
                return
            }
            // A block left over from a previous start (data swapped, view
            // recycled) retires itself here rather than fighting the current one.
            guard self.generation == myGeneration, !self.stopRequested else {
                stop.pointee = true
                return
            }
            self.lastFrameIndex = index
            self.imageView.image = UIImage(cgImage: cgImage)
        }

        if status != noErr {
            // Not animatable: a single-frame GIF, an unsupported format, or a
            // truncated download. The poster frame painted in `setGIFData` is
            // already on screen and is the correct end state, so this is a
            // debug-level fact, not a user-facing failure.
            isAnimating = false
            OshiLog.ui.debug("🎞️ GIF not animatable (CGImageAnimationStatus \(status)) — showing first frame")
        }
    }

    func stopAnimating() {
        guard isAnimating else { return }
        // Only a request: the running block ends the animation itself on its
        // next tick, because the API exposes no other way to stop it. Flipping
        // `isAnimating` here (rather than in the block) keeps `startAnimating`
        // from racing a stop that has not landed yet — the generation check is
        // what makes that safe.
        stopRequested = true
        isAnimating = false
    }
}

// MARK: - Bubble-ready wrapper

/// A GIF sized for a chat bubble, with the aspect ratio taken from the file.
///
/// Separate from `AnimatedImageView` so the sizing decision lives in one place:
/// a GIF with no readable dimensions falls back to 1:1 rather than collapsing to
/// zero height, which is what a bare `.aspectRatio(nil, …)` inside a `VStack`
/// would do.
struct GIFBubbleView: View {

    let data: Data
    var maxWidth: CGFloat = 240
    var cornerRadius: CGFloat = 16

    private var aspectRatio: CGFloat {
        guard let size = MediaManager.gifPixelSize(from: data), size.height > 0 else { return 1 }
        return size.width / size.height
    }

    var body: some View {
        AnimatedImageView(data: data, contentMode: .scaleAspectFill)
            .aspectRatio(aspectRatio, contentMode: .fit)
            .frame(maxWidth: maxWidth)
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .accessibilityLabel(Text("gif.a11y.animated".tenorLocalized("Animated GIF")))
    }
}
