//
//  WalletConnectionView.swift
//  View for connecting Web3 wallet
//

import SwiftUI

struct WalletConnectionView: View {
    @EnvironmentObject var walletManager: WalletManager
    @EnvironmentObject var meshNetworkManager: MeshNetworkManager
    @State private var seedPhrase = ""
    @State private var showingSeedInput = false
    @State private var showingInfo = false
    @State private var hasRequestedPermissions = false
    @StateObject private var permissionHelper = PermissionHelper()
    
    var body: some View {
        if !hasRequestedPermissions {
            PermissionRequestView(hasRequestedPermissions: $hasRequestedPermissions)
                .environmentObject(permissionHelper)
                .onAppear {
                    print("🔵 Permission screen appeared - mesh NOT started yet")
                }
        } else {
            mainWalletView
                .onAppear {
                    print("🚀 Wallet screen appeared")
                    startMeshNetworkingWhenReady()
                }
                .onChange(of: permissionHelper.bluetoothAuthorized) { _, isAuthorized in
                    if isAuthorized {
                        print("✅ Bluetooth authorization changed to: \(isAuthorized)")
                        startMeshNetworkingWhenReady()
                    }
                }
        }
    }
    
    private func startMeshNetworkingWhenReady() {
        // Only start mesh networking if Bluetooth is authorized
        guard permissionHelper.bluetoothAuthorized else {
            print("⏳ Waiting for Bluetooth to be authorized before starting mesh...")
            return
        }
        
        // Check if already started
        guard !meshNetworkManager.isAdvertising && !meshNetworkManager.isBrowsing else {
            print("ℹ️ Mesh networking already started")
            return
        }
        
        print("🚀 Starting mesh networking NOW (Bluetooth is ready)")
        meshNetworkManager.startAdvertising()
        meshNetworkManager.startBrowsing()
    }
    
    var mainWalletView: some View {
        NavigationView {
            ZStack {
                LinearGradient(
                    colors: [Color.blue.opacity(0.6), Color.purple.opacity(0.6)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                .ignoresSafeArea()
                
                VStack(spacing: 30) {
                    Spacer()
                    
                    Image(systemName: "lock.shield.fill")
                        .font(.system(size: 100))
                        .foregroundColor(.white)
                        .shadow(radius: 10)
                    
                    VStack(spacing: 10) {
                        Text("Secure Web3 Messenger")
                            .font(.title)
                            .fontWeight(.bold)
                            .foregroundColor(.white)
                        
                        Text("Encrypted • Anonymous • Offline")
                            .font(.subheadline)
                            .foregroundColor(.white.opacity(0.9))
                    }
                    
                    Spacer()
                    
                    VStack(spacing: 15) {
                        Button {
                            print("💚 Creating wallet (with delay to prevent XPC error)")
                            // Small delay to let Bluetooth fully initialize before wallet creation
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                                walletManager.connectWallet()
                            }
                        } label: {
                            HStack {
                                Image(systemName: "plus.circle.fill")
                                Text("Create New Wallet")
                            }
                            .font(.headline)
                            .foregroundColor(.blue)
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(Color.white)
                            .cornerRadius(15)
                            .shadow(radius: 5)
                        }
                        
                        Button {
                            showingSeedInput = true
                        } label: {
                            HStack {
                                Image(systemName: "key.fill")
                                Text("Import Existing Wallet")
                            }
                            .font(.headline)
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(Color.white.opacity(0.2))
                            .cornerRadius(15)
                            .overlay(
                                RoundedRectangle(cornerRadius: 15)
                                    .stroke(Color.white, lineWidth: 2)
                            )
                        }
                        
                        Button {
                            showingInfo = true
                        } label: {
                            HStack {
                                Image(systemName: "info.circle")
                                Text("How it Works")
                            }
                            .font(.subheadline)
                            .foregroundColor(.white.opacity(0.9))
                        }
                        .padding(.top, 10)
                    }
                    .padding(.horizontal, 30)
                    
                    Spacer()
                }
            }
            .sheet(isPresented: $showingSeedInput) {
                SeedPhraseInputView(seedPhrase: $seedPhrase) {
                    // Also add delay for import
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                        walletManager.connectWallet(seedPhrase: seedPhrase)
                    }
                    showingSeedInput = false
                }
            }
            .sheet(isPresented: $showingInfo) {
                InfoView()
            }
        }
    }
}

struct SeedPhraseInputView: View {
    @Binding var seedPhrase: String
    var onConnect: () -> Void
    @Environment(\.dismiss) var dismiss
    
    var body: some View {
        NavigationView {
            VStack(spacing: 20) {
                Text("Enter Your Seed Phrase")
                    .font(.title2)
                    .fontWeight(.bold)
                    .padding(.top)
                
                Text("Enter your recovery phrase to restore your wallet")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
                
                TextEditor(text: $seedPhrase)
                    .frame(height: 150)
                    .padding(10)
                    .background(Color(.systemGray6))
                    .cornerRadius(10)
                    .overlay(
                        RoundedRectangle(cornerRadius: 10)
                            .stroke(Color.blue, lineWidth: 1)
                    )
                    .padding(.horizontal)
                
                Button {
                    onConnect()
                } label: {
                    Text("Import Wallet")
                        .font(.headline)
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(seedPhrase.isEmpty ? Color.gray : Color.blue)
                        .cornerRadius(15)
                }
                .disabled(seedPhrase.isEmpty)
                .padding(.horizontal)
                
                Spacer()
            }
            .navigationBarItems(trailing: Button("Cancel") {
                dismiss()
            })
        }
    }
}

struct InfoView: View {
    @Environment(\.dismiss) var dismiss
    
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 25) {
                    InfoSection(
                        icon: "lock.shield.fill",
                        title: "End-to-End Encryption",
                        description: "Messages are encrypted on your device and can only be read by the intended recipient."
                    )
                    
                    InfoSection(
                        icon: "eye.slash.fill",
                        title: "Complete Anonymity",
                        description: "No phone numbers, emails, or personal data. Your wallet address is your identity."
                    )
                    
                    InfoSection(
                        icon: "wifi.slash",
                        title: "Works Offline",
                        description: "Send messages to nearby devices via Bluetooth and WiFi Direct without internet."
                    )
                    
                    InfoSection(
                        icon: "network",
                        title: "Mesh Network",
                        description: "Messages can hop through multiple devices to reach their destination."
                    )
                    
                    InfoSection(
                        icon: "server.rack",
                        title: "No Central Server",
                        description: "Fully decentralized. No company can read, store, or delete your messages."
                    )
                    
                    InfoSection(
                        icon: "key.fill",
                        title: "You Control Your Keys",
                        description: "Your private keys never leave your device. You are the only one who can decrypt your messages."
                    )
                }
                .padding()
            }
            .navigationTitle("How It Works")
            .navigationBarTitleDisplayMode(.large)
            .navigationBarItems(trailing: Button("Done") {
                dismiss()
            })
        }
    }
}

struct InfoSection: View {
    let icon: String
    let title: String
    let description: String
    
    var body: some View {
        HStack(alignment: .top, spacing: 15) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundColor(.blue)
                .frame(width: 40)
            
            VStack(alignment: .leading, spacing: 5) {
                Text(title)
                    .font(.headline)
                Text(description)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
        }
    }
}

#Preview {
    WalletConnectionView()
        .environmentObject(WalletManager())
        .environmentObject(MeshNetworkManager())
}
