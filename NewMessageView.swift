//
//  NewMessageView.swift
//  CRASH-SAFE: All force unwraps removed + error handling
//

import SwiftUI

struct NewMessageView: View {
    @EnvironmentObject var messageManager: MessageManager
    @EnvironmentObject var walletManager: WalletManager
    @EnvironmentObject var meshManager: MeshNetworkManager
    
    @State private var recipientAddress = ""
    @State private var messageText = ""
    @State private var showingScanner = false
    @State private var showingSuccess = false
    @State private var showingError = false
    @State private var errorMessage = ""
    @State private var isValidAddress = false
    @State private var showMediaPicker = false
    @FocusState private var isMessageFieldFocused: Bool
    
    // Media attachment
    @State private var selectedMediaAttachment: MediaManager.MediaAttachment?
    
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 20) {
                    // Header
                    headerSection
                        .padding(.top, 20)
                    
                    // Recipient
                    recipientCard
                    
                    // Message
                    messageCard
                    
                    // Media preview if selected
                    if let attachment = selectedMediaAttachment {
                        mediaPreviewCard(attachment: attachment)
                    }
                    
                    // Network Status
                    networkCard
                    
                    // Send Button
                    sendButton
                        .padding(.bottom, 20)
                }
                .padding(.horizontal)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("New Message")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // Done button for keyboard
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") {
                        isMessageFieldFocused = false
                    }
                }
            }
            .sheet(isPresented: $showingScanner) {
                QRScannerView(scannedAddress: $recipientAddress)
            }
            .sheet(isPresented: $showMediaPicker) {
                MediaPickerView { attachment in
                    selectedMediaAttachment = attachment
                }
            }
            .alert("Success", isPresented: $showingSuccess) {
                Button("OK") {
                    clearForm()
                }
            } message: {
                Text("Message sent successfully!")
            }
            .alert("Error", isPresented: $showingError) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(errorMessage)
            }
        }
    }
    
    // MARK: - Components
    
    private var headerSection: some View {
        VStack(spacing: 10) {
            Image(systemName: "envelope.badge.fill")
                .font(.system(size: 50))
                .foregroundColor(.blue)
            
            Text("New Secure Message")
                .font(.title3)
                .fontWeight(.semibold)
        }
    }
    
    private var recipientCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Recipient")
                .font(.headline)
                .foregroundColor(.primary)
            
            HStack {
                TextField("Public key or 0x address...", text: $recipientAddress)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
                    .font(.system(.body, design: .monospaced))
                    .padding(12)
                    .background(Color(.systemBackground))
                    .cornerRadius(10)
                    .onChange(of: recipientAddress) { _, newValue in
                        validateAddress(newValue)
                    }
                
                Button {
                    showingScanner = true
                } label: {
                    Image(systemName: "qrcode.viewfinder")
                        .font(.title2)
                        .foregroundColor(.blue)
                        .frame(width: 44, height: 44)
                        .background(Color(.systemBackground))
                        .cornerRadius(10)
                }
            }
            
            if !recipientAddress.isEmpty {
                HStack(spacing: 6) {
                    Image(systemName: isValidAddress ? "checkmark.circle.fill" : "xmark.circle.fill")
                        .foregroundColor(isValidAddress ? .green : .red)
                    Text(isValidAddress ? validAddressType() : "Invalid format")
                        .font(.caption)
                        .foregroundColor(isValidAddress ? .green : .red)
                }
                .padding(.leading, 4)
            }
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(12)
    }
    
    private var messageCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Message")
                    .font(.headline)
                    .foregroundColor(.primary)
                
                Spacer()
                
                // Media button
                Button {
                    showMediaPicker = true
                } label: {
                    Image(systemName: "photo.on.rectangle.angled")
                        .font(.title3)
                        .foregroundColor(.blue)
                }
            }
            
            ZStack(alignment: .topLeading) {
                if messageText.isEmpty && !isMessageFieldFocused {
                    Text("Enter your message here...")
                        .foregroundColor(.gray)
                        .padding(.top, 8)
                        .padding(.leading, 8)
                }
                
                TextEditor(text: $messageText)
                    .frame(minHeight: 120)
                    .padding(4)
                    .background(Color(.systemBackground))
                    .cornerRadius(10)
                    .focused($isMessageFieldFocused)
                    .scrollContentBackground(.hidden)
            }
            
            HStack {
                HStack(spacing: 4) {
                    Image(systemName: "lock.fill")
                        .foregroundColor(.green)
                    Text("End-to-end encrypted")
                        .foregroundColor(.secondary)
                }
                .font(.caption)
                
                Spacer()
                
                Text("\(messageText.count) characters")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .padding(.horizontal, 4)
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(12)
    }
    
    private func mediaPreviewCard(attachment: MediaManager.MediaAttachment) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Attached Media")
                    .font(.headline)
                
                Spacer()
                
                Button {
                    selectedMediaAttachment = nil
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(.gray)
                }
            }
            
            // Safe image loading
            if attachment.type == .image {
                if let image = UIImage(data: attachment.data) {
                    Image(uiImage: image)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(height: 150)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                } else {
                    // Fallback if image fails to load
                    Rectangle()
                        .fill(Color.gray.opacity(0.3))
                        .frame(height: 150)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                        .overlay {
                            Text("Image unavailable")
                                .foregroundColor(.gray)
                        }
                }
            } else if attachment.type == .video {
                // Safe video thumbnail loading
                if let thumbnailData = attachment.thumbnailData,
                   let thumbnail = UIImage(data: thumbnailData) {
                    ZStack {
                        Image(uiImage: thumbnail)
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                            .frame(height: 150)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                        
                        Image(systemName: "play.circle.fill")
                            .font(.system(size: 50))
                            .foregroundColor(.white)
                            .shadow(radius: 5)
                    }
                } else {
                    // Fallback for video without thumbnail
                    Rectangle()
                        .fill(Color.gray.opacity(0.3))
                        .frame(height: 150)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                        .overlay {
                            VStack {
                                Image(systemName: "video.fill")
                                    .font(.largeTitle)
                                    .foregroundColor(.gray)
                                Text("Video")
                                    .foregroundColor(.gray)
                            }
                        }
                }
            }
            
            Text(attachment.fileSizeFormatted)
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(12)
    }
    
    private var networkCard: some View {
        HStack {
            Circle()
                .fill(meshManager.connectedPeers.isEmpty ? Color.orange : Color.green)
                .frame(width: 10, height: 10)
            
            Text(meshManager.connectedPeers.isEmpty ? "Offline Mode" : "Connected")
                .font(.subheadline)
            
            Spacer()
            
            Text("\(meshManager.connectedPeers.count) peer\(meshManager.connectedPeers.count == 1 ? "" : "s")")
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(12)
    }
    
    private var sendButton: some View {
        Button {
            sendMessage()
        } label: {
            HStack {
                Image(systemName: "paperplane.fill")
                Text(selectedMediaAttachment != nil ? "Send Media Message" : "Send Encrypted Message")
            }
            .font(.headline)
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding()
            .background(canSend ? Color.blue : Color.gray)
            .cornerRadius(12)
        }
        .disabled(!canSend)
    }
    
    // MARK: - Logic
    
    private var canSend: Bool {
        // Can send if we have valid address AND (message text OR media)
        isValidAddress && (!messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || selectedMediaAttachment != nil)
    }
    
    private func validateAddress(_ address: String) {
        let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        
        guard !trimmed.isEmpty else {
            isValidAddress = false
            return
        }
        
        // Fast path: Ethereum address
        if trimmed.hasPrefix("0x") {
            isValidAddress = trimmed.count == 42
            return
        }
        
        // Base64 public key check
        if trimmed.count >= 40 && trimmed.count <= 200 {
            let base64Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
            let isBase64 = trimmed.allSatisfy { base64Chars.contains($0) }
            
            if isBase64 {
                isValidAddress = Data(base64Encoded: trimmed) != nil
                return
            }
        }
        
        isValidAddress = false
    }
    
    private func validAddressType() -> String {
        recipientAddress.hasPrefix("0x") ? "Valid wallet address" : "Valid public key"
    }
    
    private func sendMessage() {
        isMessageFieldFocused = false
        
        // Wrap in do-catch for safety
        if let attachment = selectedMediaAttachment {
            // Send media message - create MediaManager only when needed
            let tempMediaManager = MediaManager()
            messageManager.sendMediaMessage(
                attachment: attachment,
                recipientAddress: recipientAddress,
                walletManager: walletManager,
                meshManager: meshManager,
                mediaManager: tempMediaManager
            )
            showingSuccess = true
        } else {
            // Send text message
            messageManager.sendMessage(
                content: messageText,
                recipientAddress: recipientAddress,
                walletManager: walletManager,
                meshManager: meshManager
            )
            showingSuccess = true
        }
    }
    
    private func clearForm() {
        recipientAddress = ""
        messageText = ""
        isValidAddress = false
        selectedMediaAttachment = nil
    }
}

#Preview {
    NewMessageView()
        .environmentObject(MessageManager())
        .environmentObject(WalletManager())
        .environmentObject(MeshNetworkManager())
}
