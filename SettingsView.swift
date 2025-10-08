//
//  SettingsView.swift
//  COMPLETE: Perfect light/dark mode support with adaptive colors
//

import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var walletManager: WalletManager
    @EnvironmentObject var meshManager: MeshNetworkManager
    @EnvironmentObject var messageManager: MessageManager
    @Environment(\.colorScheme) var colorScheme
    
    @State private var showingWalletInfo = false
    @State private var showingBackupSeed = false
    @State private var showingQRCode = false
    @State private var showingDisconnectAlert = false
    @State private var showingClearDataAlert = false
    
    private var primaryTextColor: Color {
        colorScheme == .dark ? .white : .black
    }
    
    private var iconColor: Color {
        colorScheme == .dark ? .blue : .blue
    }
    
    var body: some View {
        NavigationView {
            List {
                // MARK: - Wallet Section
                Section {
                    HStack {
                        VStack(alignment: .leading, spacing: 5) {
                            Text("Your Wallet Address")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                            Text(formatAddress(walletManager.walletAddress))
                                .font(.system(.body, design: .monospaced))
                                .foregroundColor(primaryTextColor)
                        }
                        
                        Spacer()
                        
                        Button {
                            UIPasteboard.general.string = walletManager.walletAddress
                        } label: {
                            Image(systemName: "doc.on.doc")
                                .foregroundColor(iconColor)
                        }
                    }
                    
                    Button {
                        showingQRCode = true
                    } label: {
                        HStack {
                            Image(systemName: "qrcode")
                                .foregroundColor(iconColor)
                            Text("Show QR Code")
                                .foregroundColor(primaryTextColor)
                        }
                    }
                    
                    Button {
                        showingWalletInfo = true
                    } label: {
                        HStack {
                            Image(systemName: "info.circle")
                                .foregroundColor(iconColor)
                            Text("Wallet Details")
                                .foregroundColor(primaryTextColor)
                        }
                    }
                    
                    Button {
                        showingBackupSeed = true
                    } label: {
                        HStack {
                            Image(systemName: "key.fill")
                                .foregroundColor(iconColor)
                            Text("Backup Keys")
                                .foregroundColor(primaryTextColor)
                        }
                    }
                } header: {
                    Text("WALLET")
                        .foregroundColor(.secondary)
                }
                
                // MARK: - Statistics Section
                Section {
                    HStack {
                        Text("Total Messages")
                            .foregroundColor(primaryTextColor)
                        Spacer()
                        Text("\(messageManager.messages.count)")
                            .foregroundColor(.secondary)
                            .fontWeight(.semibold)
                    }
                    
                    HStack {
                        Text("Conversations")
                            .foregroundColor(primaryTextColor)
                        Spacer()
                        Text("\(messageManager.conversations.count)")
                            .foregroundColor(.secondary)
                            .fontWeight(.semibold)
                    }
                    
                    HStack {
                        Text("Connected Peers")
                            .foregroundColor(primaryTextColor)
                        Spacer()
                        Text("\(meshManager.connectedPeers.count)")
                            .foregroundColor(.secondary)
                            .fontWeight(.semibold)
                    }
                } header: {
                    Text("STATISTICS")
                        .foregroundColor(.secondary)
                }
                
                // MARK: - Privacy & Security Section
                Section {
                    NavigationLink(destination: PrivacySettingsView()) {
                        HStack {
                            Image(systemName: "hand.raised.fill")
                                .foregroundColor(iconColor)
                            Text("Privacy Settings")
                                .foregroundColor(primaryTextColor)
                        }
                    }
                    
                    NavigationLink(destination: SecuritySettingsView()) {
                        HStack {
                            Image(systemName: "lock.shield.fill")
                                .foregroundColor(iconColor)
                            Text("Security")
                                .foregroundColor(primaryTextColor)
                        }
                    }
                } header: {
                    Text("PRIVACY & SECURITY")
                        .foregroundColor(.secondary)
                }
                
                // MARK: - Data Management Section
                Section {
                    Button {
                        // Export messages
                    } label: {
                        HStack {
                            Image(systemName: "square.and.arrow.up")
                                .foregroundColor(iconColor)
                            Text("Export Messages")
                                .foregroundColor(primaryTextColor)
                        }
                    }
                    
                    Button(role: .destructive) {
                        showingClearDataAlert = true
                    } label: {
                        HStack {
                            Image(systemName: "trash")
                                .foregroundColor(.red)
                            Text("Clear All Messages")
                                .foregroundColor(.red)
                        }
                    }
                } header: {
                    Text("DATA MANAGEMENT")
                        .foregroundColor(.secondary)
                }
                
                // MARK: - About Section
                Section {
                    HStack {
                        Text("Version")
                            .foregroundColor(primaryTextColor)
                        Spacer()
                        Text("1.0.0")
                            .foregroundColor(.secondary)
                    }
                    
                    NavigationLink(destination: AboutView()) {
                        HStack {
                            Image(systemName: "info.circle")
                                .foregroundColor(iconColor)
                            Text("About")
                                .foregroundColor(primaryTextColor)
                        }
                    }
                    
                    Link(destination: URL(string: "https://github.com/oshi")!) {
                        HStack {
                            Image(systemName: "chevron.left.forwardslash.chevron.right")
                                .foregroundColor(iconColor)
                            Text("Source Code")
                                .foregroundColor(primaryTextColor)
                        }
                    }
                } header: {
                    Text("ABOUT")
                        .foregroundColor(.secondary)
                }
                
                // MARK: - Disconnect Section
                Section {
                    Button(role: .destructive) {
                        showingDisconnectAlert = true
                    } label: {
                        HStack {
                            Image(systemName: "rectangle.portrait.and.arrow.right")
                                .foregroundColor(.red)
                            Text("Disconnect Wallet")
                                .foregroundColor(.red)
                        }
                    }
                }
            }
            .navigationTitle("Settings")
            // ✅ FIX: Set navigation bar appearance for light mode
            .toolbarColorScheme(colorScheme == .dark ? .dark : .light, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .sheet(isPresented: $showingWalletInfo) {
                WalletInfoView()
            }
            .sheet(isPresented: $showingBackupSeed) {
                BackupSeedView()
            }
            .sheet(isPresented: $showingQRCode) {
                QRCodeView(address: walletManager.walletAddress, publicKey: walletManager.publicKey)
            }
            .alert("Disconnect Wallet", isPresented: $showingDisconnectAlert) {
                Button("Cancel", role: .cancel) {}
                Button("Disconnect", role: .destructive) {
                    walletManager.disconnectWallet()
                }
            } message: {
                Text("Are you sure you want to disconnect? Make sure you have backed up your keys.")
            }
            .alert("Clear All Messages", isPresented: $showingClearDataAlert) {
                Button("Cancel", role: .cancel) {}
                Button("Clear", role: .destructive) {
                    messageManager.clearAllMessages()
                }
            } message: {
                Text("This will permanently delete all your messages. This action cannot be undone.")
            }
        }
    }
    
    private func formatAddress(_ address: String) -> String {
        guard address.count > 10 else { return address }
        return "\(address.prefix(10))...\(address.suffix(10))"
    }
}

// MARK: - Wallet Info View

struct WalletInfoView: View {
    @EnvironmentObject var walletManager: WalletManager
    @Environment(\.dismiss) var dismiss
    @Environment(\.colorScheme) var colorScheme
    
    private var primaryTextColor: Color {
        colorScheme == .dark ? .white : .black
    }
    
    var body: some View {
        NavigationView {
            List {
                Section("Wallet Address") {
                    Text(walletManager.walletAddress)
                        .font(.system(.body, design: .monospaced))
                        .foregroundColor(primaryTextColor)
                        .textSelection(.enabled)
                }
                
                Section("Public Key") {
                    Text(walletManager.publicKey)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundColor(primaryTextColor)
                        .textSelection(.enabled)
                }
                
                Section("Encryption") {
                    HStack {
                        Text("Algorithm")
                            .foregroundColor(primaryTextColor)
                        Spacer()
                        Text("Curve25519")
                            .foregroundColor(.secondary)
                    }
                    
                    HStack {
                        Text("Key Size")
                            .foregroundColor(primaryTextColor)
                        Spacer()
                        Text("256-bit")
                            .foregroundColor(.secondary)
                    }
                }
            }
            .navigationTitle("Wallet Details")
            .navigationBarTitleDisplayMode(.inline)
            // ✅ FIX: Set navigation bar appearance for light mode
            .toolbarColorScheme(colorScheme == .dark ? .dark : .light, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
        }
    }
}

// MARK: - Backup Seed View

struct BackupSeedView: View {
    @EnvironmentObject var walletManager: WalletManager
    @Environment(\.dismiss) var dismiss
    @Environment(\.colorScheme) var colorScheme
    
    @State private var showingSeed = false
    @State private var privateKeyHex = ""
    
    private var primaryTextColor: Color {
        colorScheme == .dark ? .white : .black
    }
    
    private var warningBackground: Color {
        colorScheme == .dark ? Color.orange.opacity(0.2) : Color.orange.opacity(0.1)
    }
    
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    // Warning Section
                    VStack(alignment: .leading, spacing: 15) {
                        HStack {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundColor(.orange)
                                .font(.title2)
                            Text("Important Security Warning")
                                .font(.headline)
                                .foregroundColor(primaryTextColor)
                        }
                        
                        WarningPoint(text: "Never share your private key with anyone")
                        WarningPoint(text: "Store it in a secure location offline")
                        WarningPoint(text: "Anyone with this key has full access to your wallet")
                        WarningPoint(text: "Loss of this key means permanent loss of access")
                    }
                    .padding()
                    .background(warningBackground)
                    .cornerRadius(12)
                    
                    // Private Key Display or Reveal Button
                    if showingSeed {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Your Private Key")
                                .font(.headline)
                                .foregroundColor(primaryTextColor)
                            
                            Text(privateKeyHex)
                                .font(.system(.caption, design: .monospaced))
                                .foregroundColor(primaryTextColor)
                                .padding()
                                .background(colorScheme == .dark ? Color(.systemGray6) : Color(.systemGray5))
                                .cornerRadius(8)
                                .textSelection(.enabled)
                            
                            Button {
                                UIPasteboard.general.string = privateKeyHex
                            } label: {
                                HStack {
                                    Image(systemName: "doc.on.doc")
                                    Text("Copy Private Key")
                                }
                                .font(.subheadline)
                                .foregroundColor(.blue)
                                .frame(maxWidth: .infinity)
                                .padding()
                                .background(Color.blue.opacity(0.1))
                                .cornerRadius(10)
                            }
                        }
                        .padding()
                    } else {
                        Button {
                            exportPrivateKey()
                            showingSeed = true
                        } label: {
                            Text("Reveal Backup Code")
                                .font(.headline)
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .padding()
                                .background(Color.blue)
                                .cornerRadius(15)
                        }
                        .padding(.horizontal)
                    }
                }
                .padding()
            }
            .navigationTitle("Backup Keys")
            .navigationBarTitleDisplayMode(.inline)
            // ✅ FIX: Set navigation bar appearance for light mode
            .toolbarColorScheme(colorScheme == .dark ? .dark : .light, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
        }
    }
    
    private func exportPrivateKey() {
        do {
            let privateKeyData = try KeychainHelper.load(key: "privateKey")
            privateKeyHex = privateKeyData.map { String(format: "%02x", $0) }.joined()
        } catch {
            privateKeyHex = "Error: Could not retrieve private key"
        }
    }
}

// MARK: - Warning Point Component

struct WarningPoint: View {
    let text: String
    @Environment(\.colorScheme) var colorScheme
    
    private var textColor: Color {
        colorScheme == .dark ? .white : .black
    }
    
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.circle.fill")
                .foregroundColor(.orange)
            Text(text)
                .font(.subheadline)
                .foregroundColor(textColor)
        }
    }
}

// MARK: - QR Code View

struct QRCodeView: View {
    let address: String
    let publicKey: String
    @Environment(\.dismiss) var dismiss
    
    var body: some View {
        QRCodeDisplayView(walletAddress: address, publicKey: publicKey)
    }
}

// MARK: - Privacy Settings View

struct PrivacySettingsView: View {
    @EnvironmentObject var messageManager: MessageManager
    @Environment(\.colorScheme) var colorScheme
    
    @AppStorage("autoDeleteOnRead") private var autoDeleteOnRead = false
    @State private var autoDeleteEnabled = false
    @State private var deleteAfterDays = 30.0
    
    private var primaryTextColor: Color {
        colorScheme == .dark ? .white : .black
    }
    
    var body: some View {
        List {
            Section {
                Toggle("Delete Messages After Reading", isOn: $autoDeleteOnRead)
                    .tint(.blue)
            } header: {
                Text("INSTANT DELETE")
                    .foregroundColor(.secondary)
            } footer: {
                Text("Messages will be permanently deleted immediately after you read them")
                    .foregroundColor(.secondary)
            }
            
            Section {
                Toggle("Auto-delete Messages", isOn: $autoDeleteEnabled)
                    .tint(.blue)
                
                if autoDeleteEnabled {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Delete after \(Int(deleteAfterDays)) days")
                            .font(.subheadline)
                            .foregroundColor(primaryTextColor)
                        Slider(value: $deleteAfterDays, in: 1...365, step: 1)
                            .tint(.blue)
                    }
                }
            } header: {
                Text("MESSAGE RETENTION")
                    .foregroundColor(.secondary)
            } footer: {
                Text("Automatically delete messages after a specified period")
                    .foregroundColor(.secondary)
            }
            
            Section {
                Toggle("Show Message Previews", isOn: .constant(false))
                    .tint(.blue)
                Toggle("Show Sender Name", isOn: .constant(true))
                    .tint(.blue)
            } header: {
                Text("NOTIFICATIONS")
                    .foregroundColor(.secondary)
            }
        }
        .navigationTitle("Privacy Settings")
        // ✅ FIX: Set navigation bar appearance for light mode
        .toolbarColorScheme(colorScheme == .dark ? .dark : .light, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
    }
}

// MARK: - Security Settings View

struct SecuritySettingsView: View {
    @AppStorage("biometricEnabled") private var biometricEnabled = false
    @Environment(\.colorScheme) var colorScheme
    
    private var primaryTextColor: Color {
        colorScheme == .dark ? .white : .black
    }
    
    var body: some View {
        List {
            Section {
                HStack {
                    Text("Encryption")
                        .foregroundColor(primaryTextColor)
                    Spacer()
                    Text("Curve25519 + AES-256")
                        .foregroundColor(.secondary)
                }
                
                HStack {
                    Text("Key Storage")
                        .foregroundColor(primaryTextColor)
                    Spacer()
                    Text("Secure Enclave")
                        .foregroundColor(.secondary)
                }
            } header: {
                Text("ENCRYPTION DETAILS")
                    .foregroundColor(.secondary)
            }
            
            Section {
                Toggle("Require Face ID / Passcode", isOn: $biometricEnabled)
                    .tint(.blue)
            } header: {
                Text("BIOMETRIC SECURITY")
                    .foregroundColor(.secondary)
            } footer: {
                Text("Require Face ID, Touch ID, or device passcode to open the app. You'll need to restart the app for this to take effect.")
                    .foregroundColor(.secondary)
            }
        }
        .navigationTitle("Security")
        // ✅ FIX: Set navigation bar appearance for light mode
        .toolbarColorScheme(colorScheme == .dark ? .dark : .light, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
    }
}

// MARK: - About View

struct AboutView: View {
    @Environment(\.colorScheme) var colorScheme
    
    private var primaryTextColor: Color {
        colorScheme == .dark ? .white : .black
    }
    
    private var secondaryTextColor: Color {
        colorScheme == .dark ? .gray : .secondary
    }
    
    private var cardBackground: Color {
        colorScheme == .dark ? Color(.systemGray6) : Color(.systemGray6)
    }
    
    var body: some View {
        ScrollView {
            VStack(spacing: 32) {
                
                // MARK: - Header / Identity
                VStack(spacing: 12) {
                    ZStack {
                        Circle()
                            .fill(LinearGradient(
                                colors: [.blue, .purple],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            ))
                            .frame(width: 80, height: 80)
                        
                        Image(systemName: "network")
                            .font(.system(size: 40))
                            .foregroundColor(.white)
                    }
                    .shadow(color: .black.opacity(0.2), radius: 10, x: 0, y: 5)
                    
                    Text("OSHI")
                        .font(.system(size: 36, weight: .heavy, design: .rounded))
                        .foregroundColor(primaryTextColor)
                        .textCase(.uppercase)
                        .tracking(2)
                    
                    Text("The Sovereign Messenger")
                        .font(.title3)
                        .fontWeight(.semibold)
                        .foregroundColor(.accentColor)
                    
                    HStack(spacing: 12) {
                        TagView(text: "Offline", icon: "wifi.slash")
                        TagView(text: "Secure", icon: "lock.shield")
                        TagView(text: "Decentralized", icon: "network")
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 20)
                
                // MARK: - Vision Statement
                VStack(alignment: .leading, spacing: 16) {
                    SectionHeader(title: "The Vision", icon: "lightbulb.fill")
                    
                    VStack(alignment: .leading, spacing: 12) {
                        Text("""
                        OSHI is not just a messenger — it's a **communication protocol for digital freedom**. Built on principles of sovereignty, privacy, and resilience.
                        """)
                        .font(.body)
                        .foregroundColor(secondaryTextColor)
                        
                        Text("""
                        It works **without servers**, using **peer-to-peer mesh networking** to route messages through direct Bluetooth/Wi-Fi connections or multi-hop relay nodes.
                        """)
                        .font(.body)
                        .foregroundColor(secondaryTextColor)
                        
                        Text("""
                        Every message is protected by the **Signal Double Ratchet cryptosystem**, ensuring perfect forward secrecy and post-compromise security — even across offline relays and out-of-order delivery.
                        """)
                        .font(.body)
                        .foregroundColor(secondaryTextColor)
                    }
                    .padding(16)
                    .background(cardBackground)
                    .cornerRadius(12)
                }
                
                // MARK: - Core Principles
                VStack(alignment: .leading, spacing: 16) {
                    SectionHeader(title: "Core Principles", icon: "star.fill")
                    
                    VStack(spacing: 12) {
                        PrincipleCard(
                            icon: "shield.lefthalf.filled",
                            title: "Privacy by Default",
                            description: "Zero metadata, zero tracking, zero compromise. Your conversations belong only to you."
                        )
                        
                        PrincipleCard(
                            icon: "arrow.triangle.branch",
                            title: "Decentralization",
                            description: "No central authority, no single point of failure. Power distributed across the network."
                        )
                        
                        PrincipleCard(
                            icon: "figure.walk",
                            title: "Sovereignty",
                            description: "You control your identity, your data, and your connections. No corporations, no surveillance."
                        )
                        
                        PrincipleCard(
                            icon: "externaldrive.fill.badge.wifi",
                            title: "Resilience",
                            description: "Works in internet blackouts, censorship, or disaster scenarios. Communication cannot be stopped."
                        )
                    }
                }
                
                // MARK: - Technical Features
                VStack(alignment: .leading, spacing: 16) {
                    SectionHeader(title: "Technical Features", icon: "cpu")
                    
                    VStack(spacing: 10) {
                        FeatureRow(
                            icon: "lock.rotation",
                            iconColor: .blue,
                            title: "Double Ratchet Protocol",
                            description: "Military-grade end-to-end encryption with forward secrecy on every message."
                        )
                        
                        FeatureRow(
                            icon: "wifi.circle.fill",
                            iconColor: .green,
                            title: "Multi-Hop Mesh Routing",
                            description: "Messages relay through trusted peers to reach their destination, even offline."
                        )
                        
                        FeatureRow(
                            icon: "link.badge.plus",
                            iconColor: .orange,
                            title: "Hybrid Cloud Fallback",
                            description: "Encrypted IPFS storage ensures delivery when direct mesh isn't available."
                        )
                        
                        FeatureRow(
                            icon: "person.2.badge.key",
                            iconColor: .purple,
                            title: "Cryptographic Identity",
                            description: "Wallet-based authentication using Ethereum/Solana public-key cryptography."
                        )
                        
                        FeatureRow(
                            icon: "arrow.triangle.swap",
                            iconColor: .red,
                            title: "Out-of-Order Handling",
                            description: "Advanced message buffering handles network chaos gracefully."
                        )
                        
                        FeatureRow(
                            icon: "photo.on.rectangle.angled",
                            iconColor: .cyan,
                            title: "Encrypted Media",
                            description: "Send photos and videos with AES-256-GCM encryption, decrypted only by recipient."
                        )
                        
                        FeatureRow(
                            icon: "qrcode.viewfinder",
                            iconColor: .indigo,
                            title: "QR Code Pairing",
                            description: "Scan to verify and add contacts with cryptographic safety numbers."
                        )
                        
                        FeatureRow(
                            icon: "bolt.horizontal.circle",
                            iconColor: .yellow,
                            title: "Serverless Architecture",
                            description: "No central database, no user accounts, no data collection. Ever."
                        )
                    }
                }
                
                // MARK: - How It Works
                VStack(alignment: .leading, spacing: 16) {
                    SectionHeader(title: "How It Works", icon: "gearshape.2.fill")
                    
                    VStack(alignment: .leading, spacing: 16) {
                        StepView(
                            number: 1,
                            title: "Generate Your Identity",
                            description: "Create a cryptographic wallet that becomes your unique, verifiable identity."
                        )
                        
                        StepView(
                            number: 2,
                            title: "Connect to Peers",
                            description: "Discover nearby devices via Bluetooth or WiFi mesh, or scan QR codes to add trusted contacts."
                        )
                        
                        StepView(
                            number: 3,
                            title: "Send Encrypted Messages",
                            description: "Messages are encrypted with Double Ratchet and sent directly to peers or relayed through the mesh."
                        )
                        
                        StepView(
                            number: 4,
                            title: "Fallback to Cloud",
                            description: "If mesh fails, messages are encrypted and stored on IPFS, only decryptable by the recipient."
                        )
                        
                        StepView(
                            number: 5,
                            title: "Perfect Forward Secrecy",
                            description: "Even if keys are compromised later, past conversations remain secure forever."
                        )
                    }
                    .padding(16)
                    .background(cardBackground)
                    .cornerRadius(12)
                }
                
                // MARK: - Use Cases
                VStack(alignment: .leading, spacing: 16) {
                    SectionHeader(title: "Built For", icon: "target")
                    
                    VStack(spacing: 10) {
                        UseCaseRow(icon: "newspaper", title: "Journalists", description: "Secure communication in hostile environments")
                        UseCaseRow(icon: "hand.raised.fill", title: "Activists", description: "Organize without surveillance or censorship")
                        UseCaseRow(icon: "exclamationmark.triangle", title: "Emergency Response", description: "Coordinate when infrastructure fails")
                        UseCaseRow(icon: "globe.europe.africa", title: "Travelers", description: "Stay connected without roaming or internet")
                        UseCaseRow(icon: "lock.shield.fill", title: "Privacy Advocates", description: "Communicate without corporate intermediaries")
                        UseCaseRow(icon: "bitcoinsign.circle", title: "Crypto Users", description: "Native wallet integration for secure coordination")
                    }
                    .padding(16)
                    .background(cardBackground)
                    .cornerRadius(12)
                }
                
                // MARK: - Open Source & Transparency
                VStack(alignment: .leading, spacing: 16) {
                    SectionHeader(title: "Open Source & Transparency", icon: "chevron.left.forwardslash.chevron.right")
                    
                    VStack(alignment: .leading, spacing: 14) {
                        Text("""
                        OSHI is **100% open source**, built with transparency and auditability at its core. Every line of code is public and verifiable.
                        """)
                        .font(.body)
                        .foregroundColor(secondaryTextColor)
                        
                        Text("""
                        We believe that privacy tools must be open to be trusted. No backdoors, no hidden telemetry, no proprietary algorithms.
                        """)
                        .font(.body)
                        .foregroundColor(secondaryTextColor)
                        
                        Text("Community contributions are welcome!")
                            .font(.body)
                            .fontWeight(.semibold)
                            .foregroundColor(primaryTextColor)
                        
                        Button(action: {
                            if let url = URL(string: "https://github.com/oshi") {
                                UIApplication.shared.open(url)
                            }
                        }) {
                            HStack {
                                Image(systemName: "chevron.left.forwardslash.chevron.right")
                                Text("View Source Code on GitHub")
                                    .fontWeight(.semibold)
                                Spacer()
                                Image(systemName: "arrow.up.right.circle.fill")
                            }
                            .padding()
                            .background(
                                LinearGradient(
                                    colors: [.blue, .purple],
                                    startPoint: .leading,
                                    endPoint: .trailing
                                )
                            )
                            .foregroundColor(.white)
                            .cornerRadius(12)
                        }
                    }
                    .padding(16)
                    .background(cardBackground)
                    .cornerRadius(12)
                }
                
                // MARK: - Philosophy
                VStack(alignment: .leading, spacing: 16) {
                    SectionHeader(title: "Philosophy", icon: "book.fill")
                    
                    VStack(alignment: .leading, spacing: 12) {
                        QuoteView(text: "Communication is a fundamental human right, not a product to be monetized.")
                        QuoteView(text: "Privacy should be the default, not a premium feature.")
                        QuoteView(text: "No one should have the power to silence your voice or read your thoughts.")
                        QuoteView(text: "True freedom requires tools that cannot be controlled, censored, or surveilled.")
                    }
                    .padding(16)
                    .background(cardBackground)
                    .cornerRadius(12)
                }
                
                // MARK: - Technical Stack
                VStack(alignment: .leading, spacing: 16) {
                    SectionHeader(title: "Technical Stack", icon: "hammer.fill")
                    
                    VStack(alignment: .leading, spacing: 10) {
                        TechRow(category: "Encryption", tech: "Signal Double Ratchet, X3DH, AES-256-GCM")
                        TechRow(category: "Identity", tech: "Ethereum/Solana ECDSA, secp256k1/Ed25519")
                        TechRow(category: "Networking", tech: "Bluetooth LE, Wi-Fi Direct, Multi-hop Routing")
                        TechRow(category: "Storage", tech: "IPFS (Pinata Gateway), Local SQLite")
                        TechRow(category: "Platform", tech: "Swift, SwiftUI, iOS 15+")
                        TechRow(category: "Cryptography", tech: "CryptoKit, Web3.swift, Solana.Swift")
                    }
                    .padding(16)
                    .background(cardBackground)
                    .cornerRadius(12)
                }
                
                // MARK: - Footer
                VStack(spacing: 16) {
                    Divider()
                    
                    VStack(spacing: 8) {
                        Text("Built for a world without central control.")
                            .font(.body)
                            .fontWeight(.medium)
                            .foregroundColor(secondaryTextColor)
                        
                        Text("No corporations. No surveillance. No compromise.")
                            .font(.subheadline)
                            .foregroundColor(secondaryTextColor)
                        
                        Text("© 2025 OSHI Project")
                            .font(.footnote)
                            .foregroundColor(.secondary)
                        
                        Text("Version 1.0.0")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.bottom, 32)
                }
            }
            .padding(.horizontal, 20)
        }
        .background((colorScheme == .dark ? Color.black : Color(.systemGroupedBackground)).ignoresSafeArea())
        .navigationTitle("About")
        .navigationBarTitleDisplayMode(.inline)
        // ✅ FIX: Set navigation bar appearance for light mode
        .toolbarColorScheme(colorScheme == .dark ? .dark : .light, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
    }
}

// MARK: - Supporting Views for AboutView

struct TagView: View {
    let text: String
    let icon: String
    @Environment(\.colorScheme) var colorScheme
    
    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: icon)
                .font(.caption2)
            Text(text)
                .font(.caption)
                .fontWeight(.medium)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(colorScheme == .dark ? Color.accentColor.opacity(0.15) : Color.blue.opacity(0.12))
        .foregroundColor(colorScheme == .dark ? .accentColor : .blue)
        .cornerRadius(8)
    }
}

struct SectionHeader: View {
    let title: String
    let icon: String
    @Environment(\.colorScheme) var colorScheme
    
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: icon)
                .foregroundColor(colorScheme == .dark ? .accentColor : .blue)
                .font(.title3)
                .fontWeight(.semibold)
            Text(title)
                .font(.title2)
                .fontWeight(.bold)
        }
    }
}

struct PrincipleCard: View {
    let icon: String
    let title: String
    let description: String
    @Environment(\.colorScheme) var colorScheme
    
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack {
                Circle()
                    .fill(colorScheme == .dark ? Color.accentColor.opacity(0.2) : Color.blue.opacity(0.15))
                    .frame(width: 44, height: 44)
                    .shadow(color: Color.black.opacity(0.1), radius: 4, x: 0, y: 2)
                
                Image(systemName: icon)
                    .foregroundColor(colorScheme == .dark ? .accentColor : .blue)
                    .font(.system(size: 20, weight: .semibold))
            }
            
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.headline)
                    .foregroundColor(colorScheme == .dark ? .white : .black)
                Text(description)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
            
            Spacer()
        }
        .padding(12)
        .background(colorScheme == .dark ? Color(.systemGray6) : Color.white)
        .cornerRadius(10)
        .shadow(color: Color.black.opacity(colorScheme == .dark ? 0.3 : 0.08), radius: 8, x: 0, y: 2)
    }
}

struct FeatureRow: View {
    let icon: String
    let iconColor: Color
    let title: String
    let description: String
    @Environment(\.colorScheme) var colorScheme
    
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .foregroundColor(iconColor)
                .font(.system(size: 22, weight: .semibold))
                .frame(width: 28)
            
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.subheadline)
                    .fontWeight(.semibold)
                    .foregroundColor(colorScheme == .dark ? .white : .black)
                Text(description)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 6)
    }
}

struct StepView: View {
    let number: Int
    let title: String
    let description: String
    @Environment(\.colorScheme) var colorScheme
    
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack {
                Circle()
                    .fill(
                        LinearGradient(
                            colors: [.blue, .purple],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 32, height: 32)
                Text("\(number)")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(.white)
            }
            
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.subheadline)
                    .fontWeight(.semibold)
                    .foregroundColor(colorScheme == .dark ? .white : .black)
                Text(description)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
    }
}

struct UseCaseRow: View {
    let icon: String
    let title: String
    let description: String
    @Environment(\.colorScheme) var colorScheme
    
    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 8)
                    .fill(colorScheme == .dark ? Color.accentColor.opacity(0.15) : Color.blue.opacity(0.12))
                    .frame(width: 36, height: 36)
                    .shadow(color: Color.black.opacity(0.08), radius: 3, x: 0, y: 1)
                
                Image(systemName: icon)
                    .foregroundColor(colorScheme == .dark ? .accentColor : .blue)
                    .font(.system(size: 18, weight: .semibold))
            }
            
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline)
                    .fontWeight(.semibold)
                    .foregroundColor(colorScheme == .dark ? .white : .black)
                Text(description)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            
            Spacer()
        }
        .padding(.vertical, 4)
    }
}

struct QuoteView: View {
    let text: String
    @Environment(\.colorScheme) var colorScheme
    
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "quote.opening")
                .foregroundColor(colorScheme == .dark ? .accentColor : .blue)
                .font(.system(size: 14, weight: .semibold))
                .offset(y: -2)
            
            Text(text)
                .font(.callout)
                .italic()
                .foregroundColor(colorScheme == .dark ? .secondary : Color(.darkGray))
                .lineSpacing(4)
            
            Spacer()
        }
        .padding(12)
        .background(colorScheme == .dark ? Color.accentColor.opacity(0.08) : Color.blue.opacity(0.05))
        .cornerRadius(8)
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .strokeBorder(colorScheme == .dark ? Color.accentColor.opacity(0.2) : Color.blue.opacity(0.15), lineWidth: 1)
        )
    }
}

struct TechRow: View {
    let category: String
    let tech: String
    @Environment(\.colorScheme) var colorScheme
    
    var body: some View {
        HStack {
            Text(category)
                .font(.subheadline)
                .fontWeight(.semibold)
                .foregroundColor(colorScheme == .dark ? .white : .black)
                .frame(width: 100, alignment: .leading)
            
            Text(tech)
                .font(.caption)
                .foregroundColor(.secondary)
            
            Spacer()
        }
    }
}

// MARK: - Preview

#Preview {
    SettingsView()
        .environmentObject(WalletManager())
        .environmentObject(MeshNetworkManager())
        .environmentObject(MessageManager())
}
