//
//  MediaMessageView.swift
//  FIXED: Sender can see their own media + NaN errors eliminated
//

import SwiftUI
import AVKit
import CryptoKit

// MARK: - Main Media Message View

struct MediaMessageView: View {
    let attachment: MediaManager.MediaAttachment
    let isFromCurrentUser: Bool
    
    @State private var showFullScreen = false
    @State private var isLoading = false
    
    var body: some View {
        VStack(alignment: isFromCurrentUser ? .trailing : .leading, spacing: 5) {
            Button {
                showFullScreen = true
            } label: {
                mediaPreview
                    .overlay(alignment: .bottomTrailing) {
                        fileSizeBadge
                    }
            }
            .buttonStyle(.plain)
        }
        .sheet(isPresented: $showFullScreen) {
            MediaFullScreenView(attachment: attachment)
        }
    }
    
    @ViewBuilder
    private var mediaPreview: some View {
        ZStack {
            if attachment.type == .image {
                imagePreview
            } else {
                videoPreview
            }
        }
    }
    
    @ViewBuilder
    private var imagePreview: some View {
        // ✅ FIX: Try original data first, then thumbnail
        if let image = UIImage(data: attachment.data),
           image.size.width > 0,
           image.size.height > 0 {
            Image(uiImage: image)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(width: 200, height: 200)
                .clipShape(RoundedRectangle(cornerRadius: 15))
        } else if let thumbnailData = attachment.thumbnailData,
                  let thumbnail = UIImage(data: thumbnailData),
                  thumbnail.size.width > 0,
                  thumbnail.size.height > 0 {
            Image(uiImage: thumbnail)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(width: 200, height: 200)
                .clipShape(RoundedRectangle(cornerRadius: 15))
                .overlay {
                    if isLoading {
                        ProgressView()
                            .tint(.white)
                    }
                }
        } else {
            placeholderView
        }
    }
    
    private var placeholderView: some View {
        Rectangle()
            .fill(Color.gray.opacity(0.3))
            .frame(width: 200, height: 200)
            .clipShape(RoundedRectangle(cornerRadius: 15))
            .overlay {
                Image(systemName: "photo")
                    .font(.largeTitle)
                    .foregroundColor(.gray)
            }
    }
    
    @ViewBuilder
    private var videoPreview: some View {
        if let thumbnailData = attachment.thumbnailData,
           let thumbnail = UIImage(data: thumbnailData),
           thumbnail.size.width > 0,
           thumbnail.size.height > 0 {
            ZStack {
                Image(uiImage: thumbnail)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(width: 200, height: 200)
                    .clipShape(RoundedRectangle(cornerRadius: 15))
                
                Circle()
                    .fill(Color.black.opacity(0.5))
                    .frame(width: 50, height: 50)
                
                Image(systemName: "play.fill")
                    .font(.title2)
                    .foregroundColor(.white)
            }
        } else {
            Rectangle()
                .fill(Color.gray.opacity(0.3))
                .frame(width: 200, height: 200)
                .clipShape(RoundedRectangle(cornerRadius: 15))
                .overlay {
                    VStack {
                        Image(systemName: "video")
                            .font(.largeTitle)
                            .foregroundColor(.gray)
                        Text("Video")
                            .font(.caption)
                            .foregroundColor(.gray)
                    }
                }
        }
    }
    
    private var fileSizeBadge: some View {
        Text(attachment.fileSizeFormatted)
            .font(.caption2)
            .padding(6)
            .background(Color.black.opacity(0.6))
            .foregroundColor(.white)
            .cornerRadius(8)
            .padding(8)
    }
}

// MARK: - Full Screen Media Viewer

struct MediaFullScreenView: View {
    let attachment: MediaManager.MediaAttachment
    @Environment(\.dismiss) var dismiss
    @State private var player: AVPlayer?
    
    var body: some View {
        NavigationView {
            ZStack {
                Color.black.ignoresSafeArea()
                
                if attachment.type == .image {
                    imageViewer
                } else {
                    videoPlayer
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    closeButton
                }
                
                ToolbarItem(placement: .navigationBarTrailing) {
                    shareButton
                }
            }
            .onAppear {
                if attachment.type == .video {
                    setupVideoPlayer()
                }
            }
        }
    }
    
    @ViewBuilder
    private var imageViewer: some View {
        if let image = UIImage(data: attachment.data) {
            ImageViewer(image: image)
        }
    }
    
    @ViewBuilder
    private var videoPlayer: some View {
        if let player = player {
            VideoPlayer(player: player)
                .ignoresSafeArea()
        } else {
            ProgressView()
                .tint(.white)
        }
    }
    
    private var closeButton: some View {
        Button {
            player?.pause()
            dismiss()
        } label: {
            Image(systemName: "xmark.circle.fill")
                .foregroundColor(.white)
                .font(.title3)
        }
    }
    
    @ViewBuilder
    private var shareButton: some View {
        if attachment.type == .image,
           let image = UIImage(data: attachment.data) {
            ShareLink(
                item: Image(uiImage: image),
                preview: SharePreview("Image", image: Image(uiImage: image))
            ) {
                Image(systemName: "square.and.arrow.up")
                    .foregroundColor(.white)
            }
        }
    }
    
    private func setupVideoPlayer() {
        let tempURL = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("mp4")
        
        do {
            try attachment.data.write(to: tempURL)
            player = AVPlayer(url: tempURL)
            player?.play()
        } catch {
            print("❌ Failed to setup video player: \(error)")
        }
    }
}

// MARK: - Zoomable Image Viewer

struct ImageViewer: View {
    let image: UIImage
    @State private var scale: CGFloat = 1.0
    @State private var lastScale: CGFloat = 1.0
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero
    
    var body: some View {
        GeometryReader { geometry in
            Image(uiImage: image)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .scaleEffect(scale)
                .offset(offset)
                .gesture(magnificationGesture)
                .gesture(dragGesture)
                .onTapGesture(count: 2) {
                    doubleTapAction()
                }
        }
    }
    
    private var magnificationGesture: some Gesture {
        MagnificationGesture()
            .onChanged { value in
                let delta = value / lastScale
                lastScale = value
                scale = min(max(scale * delta, 1), 4)
            }
            .onEnded { _ in
                lastScale = 1.0
                if scale < 1 {
                    withAnimation {
                        scale = 1
                        offset = .zero
                    }
                }
            }
    }
    
    private var dragGesture: some Gesture {
        DragGesture()
            .onChanged { value in
                if scale > 1 {
                    offset = CGSize(
                        width: lastOffset.width + value.translation.width,
                        height: lastOffset.height + value.translation.height
                    )
                }
            }
            .onEnded { _ in
                lastOffset = offset
            }
    }
    
    private func doubleTapAction() {
        withAnimation {
            if scale > 1 {
                scale = 1
                offset = .zero
                lastOffset = .zero
            } else {
                scale = 2
            }
        }
    }
}

// MARK: - Media Chat Bubble (FIXED for Sender)

struct MediaChatBubble: View {
    let message: SecureMessage
    let isFromCurrentUser: Bool
    
    @EnvironmentObject var walletManager: WalletManager
    
    @State private var attachment: MediaManager.MediaAttachment?
    @State private var isDecrypting = true
    
    var body: some View {
        HStack {
            if isFromCurrentUser { Spacer() }
            
            VStack(alignment: isFromCurrentUser ? .trailing : .leading, spacing: 5) {
                mediaContent
                messageFooter
            }
            
            if !isFromCurrentUser { Spacer() }
        }
        .task {
            await loadMedia()
        }
    }
    
    @ViewBuilder
    private var mediaContent: some View {
        if isDecrypting {
            decryptingView
        } else if let attachment = attachment {
            MediaMessageView(
                attachment: attachment,
                isFromCurrentUser: isFromCurrentUser
            )
        } else {
            errorView
        }
    }
    
    private var decryptingView: some View {
        HStack {
            ProgressView()
                .scaleEffect(0.8)
            Text(isFromCurrentUser ? "Loading media..." : "Decrypting media...")
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding()
        .background(Color(.systemGray5))
        .cornerRadius(15)
    }
    
    private var errorView: some View {
        HStack {
            Image(systemName: "exclamationmark.triangle")
                .foregroundColor(.orange)
            Text("Failed to load media")
                .font(.caption)
        }
        .padding()
        .background(Color(.systemGray6))
        .cornerRadius(15)
    }
    
    private var messageFooter: some View {
        HStack(spacing: 5) {
            Text(formatTimestamp(message.timestamp))
                .font(.caption2)
                .foregroundColor(.secondary)
            
            if isFromCurrentUser {
                deliveryStatusIcon
            }
        }
    }
    
    @ViewBuilder
    private var deliveryStatusIcon: some View {
        Image(systemName: statusIconName)
            .font(.caption2)
            .foregroundColor(.secondary)
    }
    
    private var statusIconName: String {
        message.deliveryStatus == .delivered ? "checkmark.circle.fill" : "circle"
    }
    
    // ✅ FIX: Properly load media for both sender and receiver
    private func loadMedia() async {
        // ✅ If it's from current user, use the original media data directly
        if isFromCurrentUser {
            if let originalData = message.originalMediaData,
               let mediaType = message.mediaType {
                await MainActor.run {
                    attachment = MediaManager.MediaAttachment(
                        id: message.id,
                        type: mediaType,
                        data: originalData,
                        thumbnailData: nil,
                        fileName: "media_\(message.id)",
                        fileSize: originalData.count,
                        timestamp: message.timestamp
                    )
                    isDecrypting = false
                }
                print("✅ Loaded sender's own media directly")
                return
            }
        }
        
        // For received media, decrypt it
        guard let mediaData = message.mediaAttachment else {
            await MainActor.run {
                isDecrypting = false
            }
            return
        }
        
        do {
            let sharedSecret = try walletManager.computeSharedSecret(
                with: message.senderPublicKey
            )
            let key = SymmetricKey(data: sharedSecret.prefix(32))
            
            let mediaManager = MediaManager()
            let result = try mediaManager.decryptMedia(mediaData, with: key)
            
            await MainActor.run {
                attachment = result
                isDecrypting = false
            }
            print("✅ Decrypted received media successfully")
        } catch {
            print("❌ Failed to decrypt media: \(error)")
            await MainActor.run {
                isDecrypting = false
            }
        }
    }
    
    private func formatTimestamp(_ date: Date) -> String {
        let formatter = DateFormatter()
        if Calendar.current.isDateInToday(date) {
            formatter.dateFormat = "HH:mm"
        } else if Calendar.current.isDateInYesterday(date) {
            return "Yesterday"
        } else {
            formatter.dateFormat = "MMM d"
        }
        return formatter.string(from: date)
    }
}

// MARK: - Preview

#Preview {
    let sampleData = Data(repeating: 0, count: 1000)
    let attachment = MediaManager.MediaAttachment(
        id: UUID().uuidString,
        type: .image,
        data: sampleData,
        thumbnailData: nil,
        fileName: "test.jpg",
        fileSize: 1000,
        timestamp: Date()
    )
    
    return MediaMessageView(attachment: attachment, isFromCurrentUser: true)
}
