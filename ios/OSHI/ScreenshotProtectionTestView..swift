//
//  ScreenshotProtectionTestView.swift
//  OSHI - Test Screenshot Protection
//
//  Use this view to test if screenshot protection is working
//  This file can be deleted after testing
//

import SwiftUI

#if DEBUG

struct ScreenshotProtectionTestView: View {
    @State private var screenshotCount = 0
    @State private var lastScreenshotTime: Date?
    
    var body: some View {
        ZStack {
            // CRITICAL: This colorful content should NOT appear in screenshots
            LinearGradient(
                colors: [.red, .orange, .yellow, .green, .blue, .purple],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()
            
            VStack(spacing: 30) {
                // Big warning that should NOT appear in screenshot
                Text("⚠️ SECRET CONTENT ⚠️")
                    .font(.scaled(40, weight: .bold))
                    .foregroundColor(.white)
                    .padding()
                    .background(Color.red)
                    .cornerRadius(20)
                
                Text("THIS SHOULD NOT APPEAR IN SCREENSHOT")
                    .font(.title2)
                    .fontWeight(.bold)
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)
                    .padding()
                
                VStack(alignment: .leading, spacing: 15) {
                    Text("🧪 Screenshot Protection Test")
                        .font(.title3)
                        .fontWeight(.bold)
                        .foregroundColor(.white)
                    
                    Divider().background(Color.white)
                    
                    HStack {
                        Text("Screenshots Taken:")
                            .foregroundColor(.white)
                        Spacer()
                        Text("\(screenshotCount)")
                            .fontWeight(.bold)
                            .foregroundColor(.yellow)
                    }
                    
                    if let lastTime = lastScreenshotTime {
                        HStack {
                            Text("Last Screenshot:")
                                .foregroundColor(.white)
                            Spacer()
                            Text(lastTime.formatted(date: .omitted, time: .standard))
                                .fontWeight(.bold)
                                .foregroundColor(.yellow)
                        }
                    }
                    
                    Divider().background(Color.white)
                    
                    VStack(alignment: .leading, spacing: 10) {
                        Text("✅ Test Instructions:")
                            .font(.headline)
                            .foregroundColor(.white)
                        
                        Text("1. Take a screenshot (Power + Volume Up)")
                            .foregroundColor(.white.opacity(0.9))
                        
                        Text("2. Check Photos app")
                            .foregroundColor(.white.opacity(0.9))
                        
                        Text("3. Screenshot should show OSHI logo")
                            .foregroundColor(.white.opacity(0.9))
                        
                        Text("4. NOT this colorful test content!")
                            .foregroundColor(.white.opacity(0.9))
                            .fontWeight(.bold)
                    }
                    .padding()
                    .background(Color.black.opacity(0.3))
                    .cornerRadius(10)
                }
                .padding()
                .background(Color.black.opacity(0.4))
                .cornerRadius(15)
                .padding(.horizontal)
                
                // Message bubbles that should not appear
                VStack(spacing: 15) {
                    TestMessageBubble(text: "This is a secret message", isSent: true)
                    TestMessageBubble(text: "User's private conversation", isSent: false)
                    TestMessageBubble(text: "Confidential information here", isSent: true)
                }
                .padding()
                
                Spacer()
            }
        }
        .onReceive(NotificationCenter.default.publisher(
            for: UIApplication.userDidTakeScreenshotNotification
        )) { _ in
            screenshotCount += 1
            lastScreenshotTime = Date()
            OshiLog.ui.info("🧪 TEST: Screenshot #\(screenshotCount) taken at \(Date())")
        }
    }
}

struct TestMessageBubble: View {
    let text: String
    let isSent: Bool
    
    var body: some View {
        HStack {
            if isSent { Spacer() }
            
            Text(text)
                .padding(12)
                .background(isSent ? Color.blue : Color.gray.opacity(0.3))
                .foregroundColor(.white)
                .cornerRadius(15)
                .frame(maxWidth: 250, alignment: isSent ? .trailing : .leading)
            
            if !isSent { Spacer() }
        }
        .padding(.horizontal)
    }
}

// MARK: - Preview

#Preview {
    ScreenshotProtectionTestView()
}

// MARK: - Usage Instructions
/*
 
 HOW TO USE THIS TEST VIEW:
 
 1. In ContentView.swift, temporarily replace the main view with:
    ScreenshotProtectionTestView()
 
 2. Build and run on a REAL DEVICE (not simulator)
 
 3. Take a screenshot (Power + Volume Up)
 
 4. Check Photos app
 
 5. EXPECTED RESULT:
    ✅ Screenshot shows LaunchScreenView (OSHI logo)
    ❌ Screenshot does NOT show this colorful test view
    ❌ Screenshot does NOT show "SECRET CONTENT" text
    ❌ Screenshot does NOT show message bubbles
 
 6. If screenshot shows this test content:
    - Protection is NOT working
    - Check console logs
    - Verify .protectFromScreenshots() is called
    - Try restarting app
 
 7. If screenshot shows LaunchScreenView:
    ✅ SUCCESS! Protection is working perfectly!
    - This is exactly like WhatsApp's view-once photos
    - Delete this test file
 
 DEBUGGING:
 
 Console should show:
 🔒 Screenshot protection ENABLED (persistent secure layer)
 ✅ LaunchScreenView injected into secure layer
 📸 Screenshots will now show LaunchScreenView
 
 When screenshot is taken:
 📸 Screenshot detected!
 🔒 LaunchScreenView is visible in screenshot (protected)
 🧪 TEST: Screenshot #1 taken at [timestamp]
 
 */

#endif
