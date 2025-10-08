//
//  SafetyNumber.swift
//  Signal-style Safety Numbers for key verification
//

import Foundation
import CryptoKit
import SwiftUI

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
                            .font(.system(size: 60))
                            .foregroundColor(verificationColor)
                        
                        Text("Safety Number")
                            .font(.title2)
                            .fontWeight(.bold)
                        
                        Text("Verify encryption with \(contactName)")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding()
                    
                    if let qrImage = generateQRCode(from: safetyNumber.qrCodeData) {
                        Image(uiImage: qrImage)
                            .interpolation(.none)
                            .resizable()
                            .frame(width: 200, height: 200)
                            .background(Color.white)
                            .cornerRadius(12)
                    }
                    
                    VStack(spacing: 15) {
                        Text("Your Safety Number")
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
                                    Label("Copy", systemImage: "doc.on.doc")
                                }
                            }
                    }
                    .padding(.horizontal)
                    
                    if verificationStatus != .unverified {
                        HStack(spacing: 12) {
                            Image(systemName: verificationStatus == .verified ? "checkmark.circle.fill" : "xmark.circle.fill")
                                .foregroundColor(verificationStatus == .verified ? .green : .red)
                            
                            Text(verificationStatus == .verified ? "Verified" : "Verification Failed")
                                .fontWeight(.semibold)
                        }
                        .padding()
                        .background(verificationStatus == .verified ? Color.green.opacity(0.1) : Color.red.opacity(0.1))
                        .cornerRadius(12)
                    }
                    
                    VStack(alignment: .leading, spacing: 15) {
                        Text("How to Verify")
                            .font(.headline)
                        
                        InstructionRow(number: "1", text: "Compare this number with \(contactName)'s device")
                        InstructionRow(number: "2", text: "Both devices should show the same number")
                        InstructionRow(number: "3", text: "Or scan their QR code to verify automatically")
                    }
                    .padding()
                    .background(Color(.systemGray6))
                    .cornerRadius(12)
                    .padding(.horizontal)
                    
                    VStack(spacing: 12) {
                        Button {
                            showScanner = true
                        } label: {
                            Label("Scan QR Code", systemImage: "qrcode.viewfinder")
                                .frame(maxWidth: .infinity)
                                .padding()
                                .background(Color.blue)
                                .foregroundColor(.white)
                                .cornerRadius(12)
                        }
                        
                        Button {
                            markAsVerified()
                        } label: {
                            Label("Mark as Verified", systemImage: "checkmark.shield")
                                .frame(maxWidth: .infinity)
                                .padding()
                                .background(Color.green)
                                .foregroundColor(.white)
                                .cornerRadius(12)
                        }
                    }
                    .padding(.horizontal)
                }
                .padding(.vertical)
            }
            .navigationTitle("Safety Number")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") { dismiss() }
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
        case .unverified: return .blue
        case .verified: return .green
        case .failed: return .red
        }
    }
    
    private func markAsVerified() {
        UserDefaults.standard.set(true, forKey: "verified_\(contactKey)")
        verificationStatus = .verified
        
        let generator = UINotificationFeedbackGenerator()
        generator.notificationOccurred(.success)
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
                .background(Color.blue)
                .foregroundColor(.white)
                .clipShape(Circle())
            
            Text(text)
                .font(.subheadline)
            
            Spacer()
        }
    }
}
