//
//  SecureWeb3MessengerApp.swift
//  COMPLETE: Mesh + IPFS fallback + GroupManager
//

import SwiftUI
import LocalAuthentication

@main
struct SecureWeb3MessengerApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @StateObject private var walletManager = WalletManager()
    @StateObject private var meshManager = MeshNetworkManager()
    @StateObject private var messageManager = MessageManager()
    @StateObject private var groupManager = GroupManager()
    @State private var isUnlocked = false
    @State private var isInitializing = true
    @AppStorage("biometricEnabled") private var biometricEnabled = false
    
    var body: some Scene {
        WindowGroup {
            ZStack {
                if isInitializing {
                    // Show splash screen while initializing
                    LaunchScreenView()
                } else if !biometricEnabled {
                    // Biometric disabled - go straight to app
                    ContentView()
                        .environmentObject(walletManager)
                        .environmentObject(meshManager)
                        .environmentObject(messageManager)
                        .environmentObject(groupManager)
                } else if isUnlocked {
                    // Biometric enabled and unlocked
                    ContentView()
                        .environmentObject(walletManager)
                        .environmentObject(meshManager)
                        .environmentObject(messageManager)
                        .environmentObject(groupManager)
                } else {
                    // Biometric enabled but not unlocked
                    BiometricLockView(isUnlocked: $isUnlocked)
                }
            }
            .onAppear {
                print("🎬 App initialization starting...")
                initializeApp()
            }
        }
    }
    
    private func initializeApp() {
        // Connect MessageManager to WalletManager
        DispatchQueue.main.async {
            MessageManager.sharedWalletManager = walletManager
            print("✅ MessageManager connected to WalletManager")
        }
        
        // Test Pinata connection (optional - silent fail if VPS down)
        Task {
            do {
                let connected = try await PinataService.shared.testConnection()
                if connected {
                    print("✅ Pinata IPFS fallback available")
                }
            } catch {
                print("⚠️ Pinata unavailable - will use mesh only: \(error.localizedDescription)")
            }
        }
        
        // Wait a bit for initialization
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            withAnimation(.easeInOut(duration: 0.5)) {
                isInitializing = false
                print("✅ App initialization complete")
                print("   📡 Mesh network: Starting")
                print("   ☁️ IPFS fallback: Ready")
            }
        }
        
        // Mesh network starts automatically in MeshNetworkManager init()
    }
}

struct BiometricLockView: View {
    @Binding var isUnlocked: Bool
    @State private var showError = false
    @State private var errorMessage = ""
    
    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color.blue.opacity(0.6), Color.purple.opacity(0.6)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()
            
            VStack(spacing: 30) {
                Image(systemName: "lock.shield.fill")
                    .font(.system(size: 80))
                    .foregroundColor(.white)
                
                Text("OSHI")
                    .font(.title)
                    .fontWeight(.bold)
                    .foregroundColor(.white)
                
                Text("Locked")
                    .font(.headline)
                    .foregroundColor(.white.opacity(0.9))
                
                Button {
                    authenticate()
                } label: {
                    HStack {
                        Image(systemName: "faceid")
                        Text("Unlock with Face ID")
                    }
                    .font(.headline)
                    .foregroundColor(.blue)
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color.white)
                    .cornerRadius(15)
                }
                .padding(.horizontal, 40)
            }
        }
        .onAppear {
            authenticate()
        }
        .alert("Authentication Failed", isPresented: $showError) {
            Button("Try Again") {
                authenticate()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(errorMessage)
        }
    }
    
    func authenticate() {
        let context = LAContext()
        var error: NSError?
        
        if context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) {
            let reason = "Unlock OSHI"
            
            context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason) { success, authenticationError in
                DispatchQueue.main.async {
                    if success {
                        isUnlocked = true
                    } else {
                        errorMessage = authenticationError?.localizedDescription ?? "Failed to authenticate"
                        showError = true
                    }
                }
            }
        } else {
            // No biometrics available, use passcode
            context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Unlock OSHI") { success, authenticationError in
                DispatchQueue.main.async {
                    if success {
                        isUnlocked = true
                    } else {
                        errorMessage = authenticationError?.localizedDescription ?? "Failed to authenticate"
                        showError = true
                    }
                }
            }
        }
    }
}
