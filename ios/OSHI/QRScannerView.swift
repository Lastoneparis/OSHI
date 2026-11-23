//
//  QRScannerView.swift
//  OPTIMIZED: Instant scan validation, faster response, fully localizable
//

import SwiftUI
import AVFoundation

struct QRScannerView: View {
    @Binding var scannedAddress: String
    @Environment(\.dismiss) var dismiss
    @State private var manualAddress = ""
    @StateObject private var scanner = QRScanner()
    @State private var showPermissionAlert = false
    
    var body: some View {
        NavigationView {
            VStack(spacing: 20) {
                ZStack {
                    if scanner.isCameraAvailable {
                        CameraPreviewView(session: scanner.captureSession)
                            .frame(height: 400)
                            .cornerRadius(20)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(Color.green, lineWidth: 3)
                                    .frame(width: 250, height: 250)
                            )
                            .accessibilityLabel("qr_scanner.camera_preview".localized)
                            .accessibilityHint("qr_scanner.camera_preview_hint".localized)
                    } else {
                        VStack(spacing: 20) {
                            Image(systemName: scanner.permissionDenied ? "camera.fill.badge.ellipsis" : "camera.fill")
                                .font(.scaled(60))
                                .foregroundColor(.gray)
                                .accessibilityHidden(true)

                            Text(scanner.permissionDenied ?
                                 NSLocalizedString("qr_scanner_camera_access_denied", comment: "Camera access denied title") :
                                 NSLocalizedString("qr_scanner_camera_unavailable", comment: "Camera unavailable title"))
                                .font(.headline)
                                .multilineTextAlignment(.center)

                            if scanner.permissionDenied {
                                Text(NSLocalizedString("qr_scanner_enable_camera_message", comment: "Message asking user to enable camera access"))
                                    .font(.subheadline)
                                    .foregroundColor(.secondary)
                                    .multilineTextAlignment(.center)
                                    .padding(.horizontal)

                                Button {
                                    if let url = URL(string: UIApplication.openSettingsURLString) {
                                        UIApplication.shared.open(url)
                                    }
                                } label: {
                                    Label(NSLocalizedString("qr_scanner_open_settings", comment: "Open Settings button"),
                                          systemImage: "gear")
                                        .padding()
                                        .frame(minHeight: 44)
                                        .background(Color.blue)
                                        .foregroundColor(.white)
                                        .cornerRadius(12)
                                }
                                .accessibilityHint("qr_scanner.open_settings_hint".localized)
                            }
                        }
                        .padding(.horizontal, 24)
                        .frame(height: 400)
                        .frame(maxWidth: .infinity)
                        .background(Color(.systemGray6))
                        .cornerRadius(20)
                    }
                }
                .padding()
                
                VStack(spacing: 15) {
                    Text(NSLocalizedString("qr_scanner_manual_entry", comment: "Or enter manually text"))
                        .font(.subheadline)
                        .foregroundColor(.secondary)

                    TextField(NSLocalizedString("qr_scanner_public_key_placeholder", comment: "Public key placeholder"),
                              text: $manualAddress)
                        .textFieldStyle(.roundedBorder)
                        .autocapitalization(.none)
                        .font(.system(.body, design: .monospaced))
                        .padding(.horizontal)
                        .accessibilityLabel("qr_scanner.public_key_field".localized)

                    Button {
                        let generator = UIImpactFeedbackGenerator(style: .medium)
                        generator.impactOccurred()
                        scannedAddress = manualAddress
                        dismiss()
                    } label: {
                        Text(NSLocalizedString("qr_scanner_use_address", comment: "Use Address button"))
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .padding()
                            .background(manualAddress.isEmpty ? Color.gray : Color.blue)
                            .foregroundColor(.white)
                            .cornerRadius(12)
                    }
                    .disabled(manualAddress.isEmpty)
                    .animation(.easeInOut(duration: 0.2), value: manualAddress.isEmpty)
                    .padding(.horizontal)
                }

                Spacer()
            }
            // Keep scanner + form readable (centered, not stretched) on iPad/landscape.
            .frame(maxWidth: 600)
            .frame(maxWidth: .infinity)
            .navigationTitle(NSLocalizedString("qr_scanner_title", comment: "Scan QR Code navigation title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(NSLocalizedString("qr_scanner_cancel", comment: "Cancel button")) {
                        dismiss()
                    }
                }
            }
            .task {
                await scanner.requestPermissionAndSetup()
            }
            .onDisappear {
                scanner.stopScanning()
            }
            .onChange(of: scanner.scannedCode) { _, newCode in
                if let code = newCode, !code.isEmpty {
                    // ✅ Instant validation and dismiss
                    scannedAddress = code
                    dismiss()
                }
            }
        }
        .navigationViewStyle(.stack) // Portrait only
    }
}

/// Custom UIView that auto-resizes AVCaptureVideoPreviewLayer on layout changes
class CameraPreviewUIView: UIView {
    private let previewLayer: AVCaptureVideoPreviewLayer

    init(session: AVCaptureSession) {
        previewLayer = AVCaptureVideoPreviewLayer(session: session)
        previewLayer.videoGravity = .resizeAspectFill
        super.init(frame: .zero)
        backgroundColor = .black
        layer.addSublayer(previewLayer)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        previewLayer.frame = bounds
    }
}

struct CameraPreviewView: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> CameraPreviewUIView {
        CameraPreviewUIView(session: session)
    }

    func updateUIView(_ uiView: CameraPreviewUIView, context: Context) {
        // layoutSubviews handles frame updates automatically
    }
}

class QRScanner: NSObject, ObservableObject {
    @Published var scannedCode: String?
    @Published var isCameraAvailable = false
    @Published var permissionDenied = false
    
    let captureSession = AVCaptureSession()
    private var output = AVCaptureMetadataOutput()
    private var didScan = false

    /// Serial queue owning every AVCaptureSession mutation (configure / start / stop).
    ///
    /// 🔧 FIX (0x8BADF00D watchdog): `beginConfiguration`/`addInput`/`commitConfiguration`
    /// take the capture session's internal lock and block on the camera hardware for
    /// ~0.3–1 s. Doing that on the main thread is a guaranteed hitch, and it means the main
    /// runloop is stalled inside AVFoundation at exactly the moment other background queues
    /// may be trying to rendezvous with it. Serialising all of it here also removes the old
    /// start/stop race: both used to be flung at `DispatchQueue.global()` concurrently, so a
    /// `stopScanning()` could overtake the `startRunning()` it was meant to cancel.
    private let sessionQueue = DispatchQueue(label: "com.oshi.qrscanner.session")

    /// Delegate-delivery queue for metadata objects. Deliberately NOT `sessionQueue`:
    /// `stopRunning()` waits for in-flight delegate callbacks to return, so delivering on
    /// the same queue that calls `stopRunning()` would be re-entrant.
    private let metadataQueue = DispatchQueue(label: "com.oshi.qrscanner.metadata")

    override init() {
        super.init()
    }

    @MainActor
    func requestPermissionAndSetup() async {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        
        switch status {
        case .authorized:
            setupCamera()
            
        case .notDetermined:
            let granted = await AVCaptureDevice.requestAccess(for: .video)
            if granted {
                setupCamera()
            } else {
                permissionDenied = true
            }
            
        case .denied, .restricted:
            permissionDenied = true
            
        @unknown default:
            permissionDenied = true
        }
    }
    
    /// Kicks the (slow, blocking) camera configuration onto `sessionQueue` and returns at once.
    /// `nonisolated` so it can be reached from the delegate side without an actor hop.
    nonisolated private func setupCamera() {
        sessionQueue.async { [weak self] in
            self?.configureSessionOnSessionQueue()
        }
    }

    /// MUST run on `sessionQueue`. Every call in here — device discovery, input creation,
    /// begin/commitConfiguration — is a synchronous hardware round-trip.
    private func configureSessionOnSessionQueue() {
        dispatchPrecondition(condition: .onQueue(sessionQueue))

        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device) else {
            OshiLog.ui.info("❌ Failed to create camera input")
            return
        }

        captureSession.beginConfiguration()

        // ✅ Set preset for faster QR detection
        captureSession.sessionPreset = .high

        if captureSession.canAddInput(input) {
            captureSession.addInput(input)
        } else {
            OshiLog.ui.info("❌ Cannot add camera input")
            captureSession.commitConfiguration()
            return
        }

        if captureSession.canAddOutput(output) {
            captureSession.addOutput(output)
            // 🔧 FIX (0x8BADF00D watchdog): this used to be `DispatchQueue.main`, so *everything*
            // a successful scan triggers — contact creation, Double Ratchet `getOrCreateSession`
            // (which does a `queue.sync` onto a background serial queue), Keychain writes,
            // persistence — ran on the main thread from inside an AVFoundation callback. That is
            // the same main-waits-for-queue / queue-waits-for-main cycle the crash reports show,
            // reached from the "scan a contact's key" entry point. It also made `stopRunning()`
            // (which drains in-flight delegate callbacks) wait on the main runloop.
            output.setMetadataObjectsDelegate(self, queue: metadataQueue)
            output.metadataObjectTypes = [.qr]
        } else {
            OshiLog.ui.info("❌ Cannot add metadata output")
            captureSession.commitConfiguration()
            return
        }

        captureSession.commitConfiguration()

        OshiLog.ui.info("✅ Camera setup complete")

        // Publish UI state on main, then start the session back on `sessionQueue`.
        DispatchQueue.main.async { [weak self] in
            self?.isCameraAvailable = true
            self?.startScanning()
        }
    }

    nonisolated func startScanning() {
        // `didScan` is only ever touched on `metadataQueue`, so reset it there.
        metadataQueue.async { [weak self] in self?.didScan = false }

        sessionQueue.async { [weak self] in
            guard let self, !self.captureSession.isRunning else { return }
            self.captureSession.startRunning()
            OshiLog.ui.info("✅ Camera scanning started")
        }
    }

    nonisolated func stopScanning() {
        // The `isRunning` guard moved inside the queue: reading it from the caller's thread
        // takes the session's internal lock, and `stopRunning()` itself blocks until the
        // capture pipeline has drained — neither belongs on the main thread.
        sessionQueue.async { [weak self] in
            guard let self, self.captureSession.isRunning else { return }
            self.captureSession.stopRunning()
            OshiLog.ui.info("⏹️ Camera scanning stopped")
        }
    }
}

extension QRScanner: AVCaptureMetadataOutputObjectsDelegate {
    /// Called on `metadataQueue`, never on main — see `configureSessionOnSessionQueue()`.
    nonisolated func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !didScan,
              let code = (metadataObjects.first as? AVMetadataMachineReadableCodeObject)?.stringValue,
              !code.isEmpty else {
            return
        }

        didScan = true

        OshiLog.ui.info("✅ QR Code scanned: \(code.prefix(16))...")

        // Tear the session down straight away on `sessionQueue`. The old code waited 0.1 s on
        // the main runloop first, which meant the camera kept feeding a main thread that the
        // scan handler was about to occupy.
        stopScanning()

        // Main is only entered to publish UI state (@Published) and fire the haptic — both are
        // main-thread-only APIs. Nothing heavy is done here.
        DispatchQueue.main.async { [weak self] in
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            self?.scannedCode = code
        }
    }
}
