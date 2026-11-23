//
//  QRCodeDisplayView.swift
//  ✅ APPLE STORE COMPLIANT: All strings localized
//  ✅ DEEP LINK: Share includes smart link that opens app or redirects to App Store
//

import SwiftUI

// MARK: - Brand Color
private let appBrandColor = Theme.brand
import CoreImage.CIFilterBuiltins

// MARK: - Brand Color

// MARK: - Configuration

/// OSHI Website - handles app opening and App Store redirect
/// The share link will be: https://oshi-messenger.org/add?key=PUBLIC_KEY
let OSHI_DEEP_LINK_BASE_URL = "https://oshi-messenger.org/add"

struct QRCodeDisplayView: View {
    let identityAddress: String
    let publicKey: String
    @Environment(\.dismiss) var dismiss
    @State private var qrImage: UIImage?
    @State private var showingShareSheet = false
    
    // ✅ Cache QR codes to avoid regeneration
    private static var qrCache: [String: UIImage] = [:]
    
    init(identityAddress: String, publicKey: String = "") {
        self.identityAddress = identityAddress
        self.publicKey = publicKey.isEmpty ? identityAddress : publicKey
        
        // ✅ Check cache first
        if let cached = Self.qrCache[publicKey.isEmpty ? identityAddress : publicKey] {
            _qrImage = State(initialValue: cached)
        }
    }
    
    /// Generate the shareable deep link URL
    /// ✅ FIXED: No slash before ?key=
    private var deepLinkURL: String {
        let encodedKey = publicKey.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? publicKey
        return "\(OSHI_DEEP_LINK_BASE_URL)?key=\(encodedKey)"
    }
    
    var body: some View {
        NavigationView {
            ZStack {
                // Gradient Background
                LinearGradient(
                    colors: [
                        Color(red: 0.4, green: 0.3, blue: 0.8),
                        Color(red: 0.6, green: 0.2, blue: 0.7)
                    ],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                .ignoresSafeArea()
                
                VStack(spacing: 30) {
                    // Header
                    VStack(spacing: 12) {
                        Image(systemName: "qrcode")
                            .font(.scaled(50))
                            .foregroundColor(.white)
                            .accessibilityHidden(true)

                        // ✅ FIXED: Localized
                        Text("qr.my_public_key".localized)
                            .font(.title)
                            .fontWeight(.bold)
                            .foregroundColor(.white)
                            .multilineTextAlignment(.center)

                        // ✅ FIXED: Localized
                        Text("qr.share_instruction".localized)
                            .font(.subheadline)
                            .foregroundColor(.white.opacity(0.9))
                            .multilineTextAlignment(.center)
                    }
                    .padding(.top, 30)
                    .accessibilityElement(children: .combine)
                    
                    // QR Code Card
                    if let qrImage = qrImage {
                        VStack(spacing: 25) {
                            // QR Code
                            Image(uiImage: qrImage)
                                .interpolation(.none)
                                .resizable()
                                .scaledToFit()
                                .frame(width: 280, height: 280)
                                .padding(25)
                                .background(Color.white)
                                .cornerRadius(25)
                                .shadow(color: .black.opacity(0.3), radius: 15, x: 0, y: 8)
                                .accessibilityLabel("qr.code_image".localized)
                                .accessibilityHint("qr.code_image_hint".localized)
                                .transition(.scale.combined(with: .opacity))
                            
                            // Action Buttons
                            HStack(spacing: 15) {
                                // Copy Button
                                Button {
                                    UIPasteboard.general.string = publicKey
                                    // Haptic feedback
                                    let generator = UINotificationFeedbackGenerator()
                                    generator.notificationOccurred(.success)
                                } label: {
                                    HStack(spacing: 8) {
                                        Image(systemName: "doc.on.doc.fill")
                                            .accessibilityHidden(true)
                                        // ✅ FIXED: Localized
                                        Text("qr.copy_key".localized)
                                            .fontWeight(.semibold)
                                    }
                                    .font(.subheadline)
                                    .foregroundColor(.white)
                                    .frame(maxWidth: .infinity, minHeight: 44)
                                    .padding(.vertical, 14)
                                    .background(Color.white.opacity(0.25))
                                    .cornerRadius(12)
                                }
                                .accessibilityHint("qr.copy_key_hint".localized)

                                // Share Button
                                Button {
                                    let generator = UIImpactFeedbackGenerator(style: .light)
                                    generator.impactOccurred()
                                    showingShareSheet = true
                                } label: {
                                    HStack(spacing: 8) {
                                        Image(systemName: "square.and.arrow.up.fill")
                                            .accessibilityHidden(true)
                                        // ✅ FIXED: Localized
                                        Text("qr.share_key".localized)
                                            .fontWeight(.semibold)
                                    }
                                    .font(.subheadline)
                                    .foregroundColor(.white)
                                    .frame(maxWidth: .infinity, minHeight: 44)
                                    .padding(.vertical, 14)
                                    .background(
                                        LinearGradient(
                                            colors: [appBrandColor, appBrandColor.opacity(0.7)],
                                            startPoint: .leading,
                                            endPoint: .trailing
                                        )
                                    )
                                    .cornerRadius(12)
                                    .shadow(color: appBrandColor.opacity(0.4), radius: 8, x: 0, y: 4)
                                }
                                .accessibilityHint("qr.share_key_hint".localized)
                            }
                            .padding(.horizontal, 25)
                            
                            // Public Key Display
                            VStack(spacing: 8) {
                                // ✅ FIXED: Localized
                                Text("qr.public_key_label".localized)
                                    .font(.caption)
                                    .fontWeight(.medium)
                                    .foregroundColor(.white.opacity(0.7))
                                    .textCase(.uppercase)
                                    .tracking(1)
                                
                                Text(formatAddress(publicKey))
                                    .font(.system(.caption, design: .monospaced))
                                    .foregroundColor(.white)
                                    .padding(.horizontal, 20)
                                    .padding(.vertical, 10)
                                    .background(Color.white.opacity(0.2))
                                    .cornerRadius(8)
                                    .lineLimit(2)
                                    .multilineTextAlignment(.center)
                                    .accessibilityLabel("qr.public_key_label".localized)
                                    .accessibilityValue(publicKey)
                            }
                        }
                    } else {
                        // Loading State
                        VStack(spacing: 16) {
                            ProgressView()
                                .progressViewStyle(CircularProgressViewStyle(tint: .white))
                                .scaleEffect(1.5)
                            
                            // ✅ FIXED: Localized
                            Text("qr.generating".localized)
                                .font(.subheadline)
                                .foregroundColor(.white.opacity(0.8))
                        }
                        .frame(height: 280)
                    }
                    
                    Spacer()
                    
                    // Instructions
                    // ✅ FIXED: Localized
                    VStack(spacing: 12) {
                        QRInstructionRow(number: "1", text: "qr.instructions_1".localized)
                        QRInstructionRow(number: "2", text: "qr.instructions_2".localized)
                        QRInstructionRow(number: "3", text: "qr.instructions_3".localized)
                    }
                    .padding(20)
                    .background(Color.white.opacity(0.15))
                    .cornerRadius(15)
                    .padding(.horizontal, 25)
                    .padding(.bottom, 30)
                }
                // Cap content width so the card/buttons stay centered and
                // legible on iPad and landscape instead of stretching.
                .frame(maxWidth: 540)
                .frame(maxWidth: .infinity)
                .animation(.spring(response: 0.4, dampingFraction: 0.8), value: qrImage != nil)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(.white)
                            .font(.title3)
                    }
                    .accessibilityLabel("common.close".localized)
                }
            }
            .sheet(isPresented: $showingShareSheet) {
                if let qrImage = qrImage {
                    // ✅ DEEP LINK: Include clickable link that opens app or App Store
                    // ✅ FIXED: Using helper function with English fallback
                    let shareText = """
                    \(localizedOrDefault("qr.share_add_me", default: "Add me on OSHI!"))

                    \(deepLinkURL)

                    \(localizedOrDefault("qr.share_public_key", default: "My public key:"))
                    \(publicKey)
                    """
                    
                    ShareSheet(items: [
                        qrImage,
                        shareText
                    ])
                }
            }
        }
        .task {
            // ✅ Generate in background if not cached
            if qrImage == nil {
                await generateQRCodeOptimized()
            }
        }
    }
    
    /// Helper function: returns localized string or default if not found
    private func localizedOrDefault(_ key: String, default defaultValue: String) -> String {
        let localized = NSLocalizedString(key, comment: "")
        // If NSLocalizedString returns the key itself, use the default
        return localized == key ? defaultValue : localized
    }
    
    // ✅ OPTIMIZED: Background generation with caching
    private func generateQRCodeOptimized() async {
        await Task.detached(priority: .userInitiated) {
            let context = CIContext()
            let filter = CIFilter.qrCodeGenerator()
            
            filter.message = Data(self.publicKey.utf8)
            filter.correctionLevel = "M" // Medium is faster than H
            
            if let outputImage = filter.outputImage {
                let transform = CGAffineTransform(scaleX: 10, y: 10)
                let scaledImage = outputImage.transformed(by: transform)
                
                if let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) {
                    let image = UIImage(cgImage: cgImage)
                    
                    await MainActor.run {
                        self.qrImage = image
                        // Cache it
                        Self.qrCache[self.publicKey] = image
                        OshiLog.ui.info("✅ QR code generated and cached")
                    }
                }
            }
        }.value
    }
    
    private func formatAddress(_ address: String) -> String {
        guard address.count > 16 else { return address }
        let start = address.prefix(8)
        let end = address.suffix(8)
        return "\(start)...\(end)"
    }
}

// MARK: - QR Instruction Row Component

struct QRInstructionRow: View {
    let number: String
    let text: String
    
    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(Color.white.opacity(0.3))
                    .frame(width: 28, height: 28)

                Text(number)
                    .font(.scaled(14, weight: .bold))
                    .foregroundColor(.white)
            }
            .accessibilityHidden(true)

            Text(text)
                .font(.subheadline)
                .foregroundColor(.white)
                .fixedSize(horizontal: false, vertical: true)

            Spacer()
        }
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Preview

#Preview {
    QRCodeDisplayView(
        identityAddress: "0x1234567890abcdef",
        publicKey: "kr/6aAArDrl91TOXf/bjv2u1SiRf+H8gIi8BBgPYpi0="
    )
}
