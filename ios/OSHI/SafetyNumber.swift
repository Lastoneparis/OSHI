//
//  SafetyNumber.swift
//  Signal-style Safety Numbers for key verification
//

import Foundation

// MARK: - Brand Color
private let appBrandColor = Theme.brand
import CryptoKit

// MARK: - Brand Color
import SwiftUI

// MARK: - Brand Color

struct SafetyNumber {
    let displayString: String
    let qrCodeData: Data
    
    static func generate(ourKey: String, theirKey: String, ourName: String = "You", theirName: String = "Contact") -> SafetyNumber {
        let keys = [ourKey, theirKey].sorted()
        let combinedKeys = keys.joined()
        
        let hash = SHA256.hash(data: combinedKeys.data(using: .utf8)!)
        let hashData = Data(hash)
        
        let numericString = hashData.prefix(30).reduce("") { result, byte in
            result + String(format: "%02d", byte % 100)
        }
        
        let formatted = stride(from: 0, to: numericString.count, by: 5)
            .map { String(numericString.dropFirst($0).prefix(5)) }
            .joined(separator: " ")
        
        let qrData = "SAFETY:v1:\(combinedKeys)".data(using: .utf8)!
        
        return SafetyNumber(displayString: formatted, qrCodeData: qrData)
    }
    
    static func verify(scannedData: Data, expectedNumber: SafetyNumber) -> Bool {
        return scannedData == expectedNumber.qrCodeData
    }
}

// Simple Contact struct for SafetyNumber (if not defined elsewhere)
struct SafetyContact {
    let publicKey: String
    let name: String
    
    var isVerified: Bool {
        get { UserDefaults.standard.bool(forKey: "verified_\(publicKey)") }
        set { UserDefaults.standard.set(newValue, forKey: "verified_\(publicKey)") }
    }
}

struct SafetyNumberView: View {
    let contactKey: String
    let contactName: String
    let ourPublicKey: String
    @State private var showScanner = false
    @State private var verificationStatus: VerificationStatus = .unverified
    @Environment(\.dismiss) var dismiss
    
    enum VerificationStatus {
        case unverified
        case verified
        case failed
    }
    
    private var safetyNumber: SafetyNumber {
        SafetyNumber.generate(
            ourKey: ourPublicKey,
            theirKey: contactKey,
            ourName: "You",
            theirName: contactName
        )
    }
    
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 30) {
                    VStack(spacing: 10) {
                        Image(systemName: verificationIcon)
                            .font(.scaled(60))
                            .foregroundColor(verificationColor)
                            .accessibilityHidden(true)

                        Text("safety.title".localized)
                            .font(.title2)
                            .fontWeight(.bold)

                        Text(String(format: "safety.verify_with".localized, contactName))
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding()
                    .accessibilityElement(children: .combine)

                    if let qrImage = generateQRCode(from: safetyNumber.qrCodeData) {
                        Image(uiImage: qrImage)
                            .interpolation(.none)
                            .resizable()
                            .frame(width: 200, height: 200)
                            .background(Color.white)
                            .cornerRadius(12)
                            .accessibilityLabel("safety.qr_code_a11y".localized)
                    }
                    
                    VStack(spacing: 15) {
                        Text("safety.your_number".localized)
                            .font(.headline)
                        
                        Text(safetyNumber.displayString)
                            .font(.system(.body, design: .monospaced))
                            .multilineTextAlignment(.center)
                            .padding()
                            .background(Color(.systemGray6))
                            .cornerRadius(12)
                            .contextMenu {
                                Button {
                                    UIPasteboard.general.string = safetyNumber.displayString
                                } label: {
                                    Label("common.copy".localized, systemImage: "doc.on.doc")
                                }
                            }
                            .accessibilityLabel("safety.your_number".localized)
                            .accessibilityValue(safetyNumber.displayString)
                            .accessibilityHint("safety.number_copy_hint".localized)
                    }
                    .padding(.horizontal)

                    if verificationStatus != .unverified {
                        HStack(spacing: 12) {
                            Image(systemName: verificationStatus == .verified ? "checkmark.circle.fill" : "xmark.circle.fill")
                                .foregroundColor(verificationStatus == .verified ? .green : .red)
                                .accessibilityHidden(true)

                            Text(verificationStatus == .verified ? "safety.verified".localized : "safety.verification_failed".localized)
                                .fontWeight(.semibold)
                        }
                        .padding()
                        .background(verificationStatus == .verified ? Color.green.opacity(0.1) : Color.red.opacity(0.1))
                        .cornerRadius(12)
                        .accessibilityElement(children: .combine)
                        .transition(.opacity.combined(with: .scale(scale: 0.95)))
                    }
                    
                    VStack(alignment: .leading, spacing: 15) {
                        Text("safety.how_to_verify".localized)
                            .font(.headline)

                        InstructionRow(number: "1", text: String(format: "safety.instruction_1".localized, contactName))
                        InstructionRow(number: "2", text: "safety.instruction_2".localized)
                        InstructionRow(number: "3", text: "safety.instruction_3".localized)
                    }
                    .padding()
                    .background(Color(.systemGray6))
                    .cornerRadius(12)
                    .padding(.horizontal)
                    
                    VStack(spacing: 12) {
                        Button {
                            showScanner = true
                        } label: {
                            Label("safety.scan_qr".localized, systemImage: "qrcode.viewfinder")
                                .frame(maxWidth: .infinity, minHeight: 44)
                                .padding()
                                .background(appBrandColor)
                                .foregroundColor(.white)
                                .cornerRadius(12)
                        }
                        .accessibilityHint("safety.scan_qr_hint".localized)

                        Button {
                            markAsVerified()
                        } label: {
                            Label("safety.mark_verified".localized, systemImage: "checkmark.shield")
                                .frame(maxWidth: .infinity, minHeight: 44)
                                .padding()
                                .background(Color.green)
                                .foregroundColor(.white)
                                .cornerRadius(12)
                        }
                        .accessibilityHint("safety.mark_verified_hint".localized)
                    }
                    .padding(.horizontal)
                }
                .padding(.vertical)
                .frame(maxWidth: 600)
                .frame(maxWidth: .infinity)
                .animation(.easeInOut(duration: 0.25), value: verificationStatus)
            }
            .navigationTitle("safety.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("common.done".localized) { dismiss() }
                }
            }
            .sheet(isPresented: $showScanner) {
                QRScannerView(scannedAddress: .constant(""))
            }
        }
    }
    
    private var verificationIcon: String {
        switch verificationStatus {
        case .unverified: return "shield"
        case .verified: return "checkmark.shield.fill"
        case .failed: return "xmark.shield.fill"
        }
    }
    
    private var verificationColor: Color {
        switch verificationStatus {
        case .unverified: return appBrandColor
        case .verified: return .green
        case .failed: return .red
        }
    }
    
    private func markAsVerified() {
        UserDefaults.standard.set(true, forKey: "verified_\(contactKey)")
        verificationStatus = .verified

        #if !targetEnvironment(macCatalyst)
        let generator = UINotificationFeedbackGenerator()
        generator.notificationOccurred(.success)
        #endif
    }
    
    private func generateQRCode(from data: Data) -> UIImage? {
        let filter = CIFilter(name: "CIQRCodeGenerator")
        filter?.setValue(data, forKey: "inputMessage")
        filter?.setValue("H", forKey: "inputCorrectionLevel")
        
        guard let outputImage = filter?.outputImage else { return nil }
        
        let transform = CGAffineTransform(scaleX: 10, y: 10)
        let scaledImage = outputImage.transformed(by: transform)
        
        let context = CIContext()
        guard let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) else {
            return nil
        }
        
        return UIImage(cgImage: cgImage)
    }
}

struct InstructionRow: View {
    let number: String
    let text: String
    
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text(number)
                .fontWeight(.bold)
                .frame(width: 24, height: 24)
                .background(appBrandColor)
                .foregroundColor(.white)
                .clipShape(Circle())
                .accessibilityHidden(true)

            Text(text)
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)

            Spacer()
        }
        .accessibilityElement(children: .combine)
    }
}
