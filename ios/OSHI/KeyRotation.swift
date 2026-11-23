//
//  KeyRotation.swift
//  Automated key rotation - Fixed version - LOCALIZED
//

import Foundation
import CryptoKit
import SwiftUI

// Simple Contact struct for KeyRotation if not defined elsewhere
struct KeyRotationContact {
    let publicKey: String
    let name: String
}

class KeyRotationManager: ObservableObject {
    @Published var rotationStatus: [String: RotationInfo] = [:]
    
    private let rotationInterval: TimeInterval = 7 * 24 * 60 * 60 // 7 days
    private var monitorTimer: Timer?
    
    // Static constant accessible from RotationInfo
    static let warningInterval: TimeInterval = 24 * 60 * 60 // 1 day before
    
    struct RotationInfo: Codable {
        let publicKey: String
        let lastRotation: Date
        let nextRotation: Date
        let rotationCount: Int
        var isOverdue: Bool {
            nextRotation < Date()
        }
        var needsWarning: Bool {
            nextRotation.timeIntervalSinceNow < KeyRotationManager.warningInterval && nextRotation.timeIntervalSinceNow > 0
        }
    }
    
    struct KeyRotationPacket: Codable {
        let oldPublicKey: String
        let newPublicKey: String
        let timestamp: Date
        let signature: Data
        let rotationNumber: Int
    }
    
    init() {
        loadRotationInfo()
        // Start monitoring after delay to avoid initialization issues
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
            self?.startRotationMonitor()
        }
    }
    
    deinit {
        monitorTimer?.invalidate()
    }
    
    // MARK: - Key Rotation
    
    func rotateKeys(for contactKey: String, walletPublicKey: String) -> Bool {
        OshiLog.crypto.info("🔄 Initiating key rotation for \(contactKey.prefix(8))...")
        
        let newPrivateKey = Curve25519.KeyAgreement.PrivateKey()
        let newPublicKey = newPrivateKey.publicKey.rawRepresentation.base64EncodedString()
        
        let packet = KeyRotationPacket(
            oldPublicKey: walletPublicKey,
            newPublicKey: newPublicKey,
            timestamp: Date(),
            signature: signRotation(oldKey: walletPublicKey, newKey: newPublicKey),
            rotationNumber: (rotationStatus[contactKey]?.rotationCount ?? 0) + 1
        )
        
        guard sendRotationPacket(packet, to: contactKey) else {
            OshiLog.crypto.info("❌ Failed to send rotation packet")
            return false
        }
        
        let info = RotationInfo(
            publicKey: contactKey,
            lastRotation: Date(),
            nextRotation: Date().addingTimeInterval(rotationInterval),
            rotationCount: packet.rotationNumber
        )
        
        rotationStatus[contactKey] = info
        saveRotationInfo()
        
        OshiLog.crypto.info("✅ Key rotation completed (rotation #\(packet.rotationNumber))")
        
        return true
    }
    
    func handleKeyRotation(_ data: Data, from senderKey: String) -> Bool {
        guard let packet = try? JSONDecoder().decode(KeyRotationPacket.self, from: data) else {
            OshiLog.crypto.info("❌ Failed to decode rotation packet")
            return false
        }

        // ⛔️ FAIL CLOSED (security). An inbound rotation SUBSTITUTES the key we use
        // for a contact — the highest-value target for a MITM. The only defence is
        // a signature the attacker cannot forge, i.e. a REAL asymmetric signature
        // by the peer's OLD identity key (verified against the old key we already
        // hold). `verifyRotationSignature` does NOT do that: it recomputes a KEYLESS
        // `SHA256(old:new)` and compares equality, so anyone who can send us a
        // packet can also compute its "signature". Accepting on that basis let a
        // live attacker silently swap in their own key.
        //
        // Nothing in the app produces a real signed rotation yet (`rotateKeys`
        // discards the freshly generated key and only ever emits the keyless
        // digest — the rotation is cosmetic; no real key material rotates), so the
        // correct posture until Phase 4b is to REJECT every inbound rotation, keep
        // the existing (trusted) key, and surface it to the user as a
        // safety-number / security warning instead of applying it. This matches the
        // Android side (KeyRotationManager was made fail-closed).
        //
        // TODO (Phase 4b — real signed rotation): the sender signs `old || new ||
        // timestamp` with their OLD Ed25519 identity key; the receiver verifies
        // that signature against the peer's KNOWN old key before accepting the new
        // one. Only then may this method apply the change. Until that exists,
        // rotations are rejected here by design.
        guard isRealRotationSignature(packet, from: senderKey) else {
            OshiLog.crypto.info("⛔️ Rejected key rotation from \(senderKey.prefix(8))… — no verifiable signature by the peer's old identity key (fail-closed; possible key-substitution attempt)")
            NotificationCenter.default.post(
                name: NSNotification.Name("KeyRotationRejectedSecurityWarning"),
                object: nil,
                userInfo: [
                    "senderKey": senderKey,
                    "oldPublicKey": packet.oldPublicKey,
                    "newPublicKey": packet.newPublicKey
                ]
            )
            return false
        }

        // Unreachable today (isRealRotationSignature always returns false until
        // Phase 4b). Retained so the accept path is ready once real signatures
        // exist and so the compiler keeps checking it.
        guard packet.oldPublicKey == senderKey else {
            OshiLog.crypto.info("❌ Old public key mismatch")
            return false
        }

        OshiLog.crypto.info("🔄 Processing key rotation from \(senderKey.prefix(8))...")
        OshiLog.crypto.info("   Old key: \(packet.oldPublicKey.prefix(16))...")
        OshiLog.crypto.info("   New key: \(packet.newPublicKey.prefix(16))...")

        let info = RotationInfo(
            publicKey: packet.newPublicKey,
            lastRotation: packet.timestamp,
            nextRotation: packet.timestamp.addingTimeInterval(rotationInterval),
            rotationCount: packet.rotationNumber
        )

        rotationStatus[packet.newPublicKey] = info
        saveRotationInfo()

        OshiLog.crypto.info("✅ Key rotation processed successfully")

        return true
    }
    
    // MARK: - Monitoring
    
    private func startRotationMonitor() {
        // Cancel old timer if exists
        monitorTimer?.invalidate()
        
        // Create new timer
        monitorTimer = Timer.scheduledTimer(withTimeInterval: 3600, repeats: true) { [weak self] _ in
            self?.checkRotationStatus()
        }
        
        // First immediate check
        checkRotationStatus()
    }
    
    private func checkRotationStatus() {
        for (publicKey, info) in rotationStatus {
            if info.isOverdue {
                OshiLog.crypto.info("⚠️ Key rotation overdue for \(publicKey.prefix(8))...")
                sendRotationWarning(for: publicKey)
            } else if info.needsWarning {
                OshiLog.crypto.info("⏰ Key rotation due soon for \(publicKey.prefix(8))...")
                sendRotationReminder(for: publicKey)
            }
        }
    }
    
    // MARK: - Notifications
    
    private func sendRotationWarning(for publicKey: String) {
        NotificationCenter.default.post(
            name: NSNotification.Name("KeyRotationOverdue"),
            object: nil,
            userInfo: ["publicKey": publicKey]
        )
    }
    
    private func sendRotationReminder(for publicKey: String) {
        NotificationCenter.default.post(
            name: NSNotification.Name("KeyRotationDueSoon"),
            object: nil,
            userInfo: ["publicKey": publicKey]
        )
    }
    
    // MARK: - Cryptography
    
    private func signRotation(oldKey: String, newKey: String) -> Data {
        let combined = "\(oldKey):\(newKey)".data(using: .utf8)!
        return Data(SHA256.hash(data: combined))
    }
    
    // ⚠️ FORGEABLE — DO NOT gate acceptance on this. `signRotation` is a KEYLESS
    // `SHA256(old:new)`, so this only checks the packet is well-formed, not that it
    // came from the peer. Kept for the well-formedness log line; `handleKeyRotation`
    // now fails closed via `isRealRotationSignature` instead.
    private func verifyRotationSignature(_ packet: KeyRotationPacket) -> Bool {
        let expectedSignature = signRotation(oldKey: packet.oldPublicKey, newKey: packet.newPublicKey)
        return packet.signature == expectedSignature
    }

    /// Whether `packet` carries a REAL signature by the peer's OLD identity key —
    /// the only thing that makes an inbound rotation safe to apply.
    ///
    /// Returns `false` unconditionally today: no real signed-rotation scheme is
    /// implemented (see the Phase 4b TODO in `handleKeyRotation`), and the keyless
    /// `signRotation` digest is forgeable, so there is nothing trustworthy to
    /// verify. This is the fail-closed gate — every inbound rotation is rejected
    /// until a genuine signature by the peer's known old Ed25519 key can be checked.
    private func isRealRotationSignature(_ packet: KeyRotationPacket, from senderKey: String) -> Bool {
        // TODO (Phase 4b): verify an Ed25519 signature over
        // `packet.oldPublicKey || packet.newPublicKey || packet.timestamp` against
        // the peer's KNOWN old identity key, then return the verification result.
        return false
    }
    
    private func sendRotationPacket(_ packet: KeyRotationPacket, to contactKey: String) -> Bool {
        guard let data = try? JSONEncoder().encode(packet) else {
            return false
        }
        
        NotificationCenter.default.post(
            name: NSNotification.Name("SendKeyRotation"),
            object: nil,
            userInfo: ["data": data, "recipient": contactKey]
        )
        
        return true
    }
    
    // MARK: - Persistence
    
    private func saveRotationInfo() {
        if let encoded = try? JSONEncoder().encode(rotationStatus) {
            UserDefaults.standard.set(encoded, forKey: "keyRotationStatus")
        }
    }
    
    private func loadRotationInfo() {
        if let data = UserDefaults.standard.data(forKey: "keyRotationStatus"),
           let decoded = try? JSONDecoder().decode([String: RotationInfo].self, from: data) {
            rotationStatus = decoded
        }
    }
    
    // MARK: - Helper Methods
    
    func needsRotation(for publicKey: String) -> Bool {
        guard let info = rotationStatus[publicKey] else {
            return false
        }
        return info.isOverdue
    }
    
    func daysUntilRotation(for publicKey: String) -> Int? {
        guard let info = rotationStatus[publicKey] else {
            return nil
        }
        let days = Int(info.nextRotation.timeIntervalSinceNow / 86400)
        return max(0, days)
    }
}

// MARK: - Key Rotation View

struct KeyRotationView: View {
    @ObservedObject var rotationManager: KeyRotationManager
    let contactKey: String
    let contactName: String
    @EnvironmentObject var identityManager: IdentityManager
    @State private var showConfirmation = false
    @State private var isRotating = false
    @Environment(\.dismiss) var dismiss
    
    private var rotationInfo: KeyRotationManager.RotationInfo? {
        rotationManager.rotationStatus[contactKey]
    }
    
    var body: some View {
        NavigationView {
            List {
                Section {
                    HStack {
                        VStack(alignment: .leading) {
                            Text("key_rotation_last".localized)
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                            Text(rotationInfo?.lastRotation.formatted() ?? "key_rotation_never".localized)
                                .font(.body)
                        }
                        Spacer()
                    }
                    
                    HStack {
                        VStack(alignment: .leading) {
                            Text("key_rotation_next".localized)
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                            Text(rotationInfo?.nextRotation.formatted() ?? "key_rotation_not_scheduled".localized)
                                .font(.body)
                                .foregroundColor(rotationInfo?.isOverdue == true ? .red : .primary)
                        }
                        Spacer()
                    }
                    
                    if let days = rotationManager.daysUntilRotation(for: contactKey) {
                        HStack {
                            VStack(alignment: .leading) {
                                Text("key_rotation_status".localized)
                                    .font(.subheadline)
                                    .foregroundColor(.secondary)
                                Text("key_rotation_days_remaining".localized + " \(days)")
                                    .font(.body)
                                    .foregroundColor(days < 2 ? .orange : .green)
                            }
                            Spacer()
                        }
                    }
                } header: {
                    Text("key_rotation_schedule".localized)
                }
                
                Section {
                    Text("key_rotation_description".localized)
                        .font(.footnote)
                        .foregroundColor(.secondary)
                } header: {
                    Text("key_rotation_about".localized)
                }
                
                Section {
                    Button {
                        showConfirmation = true
                    } label: {
                        HStack {
                            Image(systemName: "arrow.triangle.2.circlepath")
                            Text("key_rotation_button".localized)
                            Spacer()
                        }
                    }
                    .disabled(isRotating)
                }
            }
            .navigationTitle("key_rotation_title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarItems(trailing: Button("button_done".localized) { dismiss() })
            .alert("key_rotation_confirm_title".localized, isPresented: $showConfirmation) {
                Button("button_cancel".localized, role: .cancel) { }
                Button("key_rotation_button_rotate".localized) {
                    performRotation()
                }
            } message: {
                Text("key_rotation_confirm_message".localized(with: contactName))
            }
        }
    }
    
    private func performRotation() {
        isRotating = true
        
        DispatchQueue.global().async {
            let success = rotationManager.rotateKeys(
                for: contactKey,
                walletPublicKey: identityManager.publicKey
            )
            
            DispatchQueue.main.async {
                isRotating = false
                if success {
                    dismiss()
                }
            }
        }
    }
}
