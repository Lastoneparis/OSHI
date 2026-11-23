//
//  IdentitySetupView.swift
//  ✅ FULLY LOCALIZED - Ready for translation
//  APPLE STORE COMPLIANT: Uses "Identity" terminology instead of "Wallet"
//  This is an IDENTITY SYSTEM for messaging, not a cryptocurrency wallet
//

import SwiftUI

// MARK: - Brand Color
private let appBrandColor = Theme.brand

struct IdentitySetupView: View {
    @EnvironmentObject var identityManager: IdentityManager
    @EnvironmentObject var meshNetworkManager: MeshNetworkManager
    @State private var seedPhrase = ""
    @State private var showingSeedInput = false
    @State private var showingInfo = false
    @State private var hasRequestedPermissions = false
    @State private var showingCreateConfirm = false
    @State private var showingRecoverPrompt = false
    @StateObject private var permissionHelper = PermissionHelper()
    /// 🔧 v1.0.20 b40+: visual feedback when "Create new identity" is tapped.
    /// Without this, users reported tapping the button and seeing no
    /// response (the 500ms delay + keychain save + downstream init can
    /// take 1-2 seconds on a fresh install). The spinner makes the tap
    /// feel acknowledged and prevents repeat taps that race the wallet
    /// generation.
    @State private var isCreatingIdentity = false
    /// 🔧 b60: safety net so a stalled setup can never permanently trap the
    /// user on the spinner. If `isConnected` hasn't flipped within the
    /// watchdog window, we drop the spinner and offer a retry.
    @State private var showSetupTimeout = false

    /// True when the device shows residual data from a previous identity that
    /// the keychain could no longer surface (post-update keychain hiccup, etc).
    /// In that case "Create new identity" would wipe it; we must warn first.
    ///
    /// 🔧 v1.0.20 b40+ fix: also require that EITHER keys are still in the
    /// Keychain OR there's actual decryptable data on disk. Without this,
    /// iCloud-restored UserDefaults (which carries `publicKey`) on a fresh
    /// install — where Keychain is wiped by Apple's restore rules — was
    /// reporting hasResidualData=true. User taps "Create new identity" →
    /// alert "Erase existing data?" appears → user dismisses (no real data
    /// to erase) → connectWallet never runs → onboarding deadlocked.
    /// Symptom matched user report: fresh App Store install on iPhone 15
    /// Pro stuck on "Create new identity" with no visual response.
    private var hasResidualData: Bool {
        // Real plaintext messages on disk → genuine residual data.
        if MessageManager.shared?.messages.isEmpty == false { return true }
        // Last-wipe backup → user wiped before, may want to recover.
        if MessageManager.shared?.hasLastWipeBackup == true { return true }
        // Stored publicKey + matching key in Keychain → identity is recoverable.
        // Without the keychain key, the publicKey alone is unusable orphan data
        // (typical of iCloud-restored UserDefaults after delete+reinstall).
        if let stored = UserDefaults.standard.string(forKey: "publicKey"),
           !stored.isEmpty,
           (try? KeychainHelper.load(key: "privateKey")) != nil {
            return true
        }
        return false
    }
    
    var body: some View {
        if !hasRequestedPermissions {
            PermissionRequestView(hasRequestedPermissions: $hasRequestedPermissions)
                .environmentObject(permissionHelper)
                .onAppear {
                    OshiLog.ui.info("📵 Permission screen appeared - mesh NOT started yet")
                }
        } else {
            mainWalletView
                .onAppear {
                    OshiLog.ui.info("🚀 Identity screen appeared")
                    // 🔧 v1.0.20 b40+ fresh-install stuck-on-create fix:
                    // do NOT start mesh networking while the identity screen
                    // is up. Starting mesh triggers the iOS "Local Network"
                    // permission dialog which, on iPhone 15 Pro / iOS 17+,
                    // can intercept tap gestures on the underlying view —
                    // making "Create new identity" appear unresponsive on
                    // a TRULY fresh install. Mesh starts after identity
                    // creation completes (ContentView flips to MainTabView,
                    // which has its own mesh startup hook), so this defer
                    // costs nothing in steady state.
                    //
                    // OshiLog.ui.info("🌐 Mesh networking deferred until after identity creation")
                }
                .onChange(of: permissionHelper.bluetoothAuthorized) { _, isAuthorized in
                    if isAuthorized {
                        OshiLog.ui.info("✅ Bluetooth authorization changed to: \(isAuthorized)")
                        // Same reason as above — don't pop a Local Network
                        // prompt over the identity screen mid-setup.
                    }
                }
        }
    }
    
    private func startMeshNetworkingWhenReady() {
        guard !meshNetworkManager.isAdvertising && !meshNetworkManager.isBrowsing else {
            OshiLog.ui.info("ℹ️ Mesh networking already started")
            return
        }

        // ✅ LAZY PERMISSIONS: Start mesh without gating on Bluetooth.
        // MPC works over Wi-Fi even without Bluetooth. The Local Network
        // permission dialog will appear when advertising/browsing starts,
        // which is the natural time for it (user has reached the identity screen).
        // Bluetooth permission is requested separately when mesh features are toggled on.
        OshiLog.ui.info("🚀 Starting mesh networking NOW (identity screen ready)")
        meshNetworkManager.startMeshAfterSetup()
    }
    
    var mainWalletView: some View {
        NavigationView {
            ZStack {
                LinearGradient(
                    colors: [appBrandColor.opacity(0.6), Color.purple.opacity(0.6)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                .ignoresSafeArea()
                
                VStack(spacing: 30) {
                    Spacer()
                    
                    // Same reasoning as PermissionRequestView: the identity screen is
                    // first-run surface, so it leads with the OSHI mark.
                    Image("LaunchLogo")
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: 88, height: 88)
                        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .shadow(color: .black.opacity(0.35), radius: 18, y: 8)
                        .accessibilityLabel("OSHI")
                        .shadow(radius: 10)
                        .accessibilityHidden(true)

                    VStack(spacing: 10) {
                        Text("identity.app_name".localized)
                            .font(.title)
                            .fontWeight(.bold)
                            .foregroundColor(.white)

                        Text("identity.tagline".localized)
                            .font(.subheadline)
                            .foregroundColor(.white.opacity(0.9))
                            .multilineTextAlignment(.center)
                    }
                    .accessibilityElement(children: .combine)
                    
                    Spacer()
                    
                    VStack(spacing: 15) {
                        // Create New Identity Button
                        Button {
                            guard !isCreatingIdentity else { return }   // prevent double-taps
                            #if !targetEnvironment(macCatalyst)
                            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                            #endif
                            if hasResidualData {
                                // Don't silently wipe pre-existing data: this is exactly
                                // the scenario where users lose all their messages by
                                // accidentally tapping "Create new" on the setup screen.
                                showingCreateConfirm = true
                            } else {
                                OshiLog.ui.info("💚 Creating identity (with delay to prevent XPC error)")
                                isCreatingIdentity = true   // show spinner
                                startSetupWatchdog()        // never trap the user on the spinner
                                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                                    identityManager.connectWallet()  // flips isConnected → ContentView swaps to MainTabView
                                    // 🔧 b60 FIX: start mesh on a LATER runloop so the
                                    // swap to MainTabView paints FIRST. Previously mesh
                                    // start ran in the SAME main-thread turn as
                                    // connectWallet(), so SwiftUI couldn't render the
                                    // swap until multipeer startup (which fires the
                                    // Local Network permission prompt) finished. On some
                                    // fresh installs that stalled the main thread long
                                    // enough that the user was stuck on the "Creating
                                    // your identity…" spinner forever and force-quit —
                                    // and if that happened before the keychain save,
                                    // relaunch showed setup again ("still stuck"). Keys
                                    // are persisted inside connectWallet() before this
                                    // runs, so the identity survives even a force-quit.
                                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                                        meshNetworkManager.startMeshAfterSetup()
                                    }
                                }
                            }
                        } label: {
                            HStack {
                                Image(systemName: "person.badge.plus.fill")
                                    .accessibilityHidden(true)
                                Text("identity.create_new".localized)
                            }
                            .font(.headline)
                            .foregroundColor(appBrandColor)
                            .frame(maxWidth: .infinity)
                            .frame(minHeight: 44)
                            .padding()
                            .background(Color.white)
                            .cornerRadius(15)
                            .shadow(radius: 5)
                        }
                        .accessibilityHint("identity.create_new.hint".localized)

                        // Import Existing Identity Button
                        Button {
                            showingSeedInput = true
                        } label: {
                            HStack {
                                Image(systemName: "key.fill")
                                    .accessibilityHidden(true)
                                Text("identity.import_existing".localized)
                            }
                            .font(.headline)
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(minHeight: 44)
                            .padding()
                            .background(Color.white.opacity(0.2))
                            .cornerRadius(15)
                            .overlay(
                                RoundedRectangle(cornerRadius: 15)
                                    .stroke(Color.white, lineWidth: 2)
                            )
                        }
                        .accessibilityHint("identity.import_existing.hint".localized)
                        .accessibilityIdentifier("setup_import_button")

                        // How it Works Button
                        Button {
                            showingInfo = true
                        } label: {
                            HStack {
                                Image(systemName: "info.circle")
                                    .accessibilityHidden(true)
                                Text("identity.how_it_works".localized)
                            }
                            .font(.subheadline)
                            .foregroundColor(.white.opacity(0.9))
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .contentShape(Rectangle())
                        }
                        .padding(.top, 10)

                        // Demo mode removed — not appropriate for production
                    }
                    .frame(maxWidth: 500)
                    .padding(.horizontal, 30)

                    Spacer()
                }
                .frame(maxWidth: .infinity)
            }
            .sheet(isPresented: $showingSeedInput) {
                IdentityRecoveryView { recoveryKey in
                    showingSeedInput = false
                    // Check for App Store Review demo mode
                    if DemoDataManager.isDemoKey(recoveryKey) {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                            DemoDataManager.loadDemoData(identityManager: identityManager)
                        }
                    } else {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                            identityManager.connectWallet(seedPhrase: recoveryKey)
                        }
                    }
                }
                .environmentObject(identityManager)
            }
            .sheet(isPresented: $showingInfo) {
                InfoView()
            }
            // 🔧 v1.0.20 b40+: spinner overlay while the wallet is being
            // created — covers the 0.5s asyncAfter + keychain save + downstream
            // initialization. Without this, users tapped "Create new identity"
            // and saw NO visual response, then concluded the button was
            // broken and force-quit the app (only to be stuck again on
            // relaunch because the original tap DID work but the screen
            // hadn't re-rendered fast enough).
            .overlay(alignment: .center) {
                if isCreatingIdentity {
                    ZStack {
                        Color.black.opacity(0.45).ignoresSafeArea()
                        VStack(spacing: 16) {
                            ProgressView()
                                .progressViewStyle(.circular)
                                .tint(.white)
                                .scaleEffect(1.3)
                            Text("identity.creating".localized)
                                .font(.headline)
                                .foregroundColor(.white)
                        }
                        .padding(24)
                        .background(Color.black.opacity(0.7).cornerRadius(16))
                        .accessibilityElement(children: .combine)
                        .accessibilityLabel("identity.creating".localized)
                    }
                    .transition(.opacity)
                }
            }
            .animation(.easeInOut(duration: 0.25), value: isCreatingIdentity)
            .alert("identity.setup.erase.title".localized, isPresented: $showingCreateConfirm) {
                Button("common.cancel".localized, role: .cancel) {}
                if MessageManager.shared?.hasLastWipeBackup == true {
                    Button("identity.setup.erase.restore_previous".localized) {
                        showingRecoverPrompt = true
                    }
                }
                Button("identity.setup.erase.create_new".localized, role: .destructive) {
                    OshiLog.ui.info("⚠️ User confirmed wipe — creating new identity")
                    isCreatingIdentity = true
                    startSetupWatchdog()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                        identityManager.connectWallet()
                        // 🔧 b60: defer mesh start so the MainTabView swap paints first (see create button).
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                            meshNetworkManager.startMeshAfterSetup()
                        }
                    }
                }
            } message: {
                Text("identity.setup.erase.message".localized)
            }
            .alert("identity.setup.restore.title".localized, isPresented: $showingRecoverPrompt) {
                Button("common.cancel".localized, role: .cancel) {}
                Button("identity.setup.restore.action".localized) {
                    let ok = MessageManager.shared?.restoreFromLastWipe() ?? false
                    OshiLog.ui.info(ok ? "🛟 Last-wipe restore succeeded" : "❌ Last-wipe restore failed")
                }
            } message: {
                Text("identity.setup.restore.message".localized)
            }
            .alert("identity.setup.timeout.title".localized, isPresented: $showSetupTimeout) {
                Button("identity.setup.timeout.try_again".localized) {
                    isCreatingIdentity = true
                    startSetupWatchdog()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                        identityManager.connectWallet()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                            meshNetworkManager.startMeshAfterSetup()
                        }
                    }
                }
                Button("common.cancel".localized, role: .cancel) {}
            } message: {
                Text("identity.setup.timeout.message".localized)
                .macReadableWidth()   // content only — the gradient behind it stays full-bleed
            }
        }
    }

    /// 🔧 b60: if `isConnected` hasn't flipped within 8 s of tapping Create,
    /// stop the spinner and surface a retry. ContentView swaps away the moment
    /// `isConnected` becomes true, so this only ever fires on a genuine stall —
    /// it can't interrupt a successful setup.
    private func startSetupWatchdog() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 8.0) {
            if isCreatingIdentity && !identityManager.isConnected {
                OshiLog.ui.info("⏱️ Identity setup watchdog fired — surfacing retry")
                isCreatingIdentity = false
                showSetupTimeout = true
            }
        }
    }
}

// MARK: - Identity Recovery View (Vault + Import)

struct IdentityRecoveryView: View {
    var onRecover: (String) -> Void
    @EnvironmentObject var identityManager: IdentityManager
    @Environment(\.dismiss) var dismiss

    @State private var vault: [StoredIdentity] = []
    @State private var showPasteImport = false
    @State private var showQRScanner = false
    @State private var importText = ""
    @State private var importError = false

    @State private var deleteTarget: StoredIdentity?
    @State private var showDeleteConfirm = false

    @State private var editingNickname: StoredIdentity?
    @State private var nicknameText = ""

    private let brandColor = Theme.brand

    var body: some View {
        NavigationView {
            List {
                // MARK: - Saved Identities
                if !vault.isEmpty {
                    Section(header: Text("identity.vault.saved_identities".localized)) {
                        ForEach(vault) { identity in
                            Button {
                                selectIdentity(identity)
                            } label: {
                                identityRow(identity)
                            }
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    deleteTarget = identity
                                    showDeleteConfirm = true
                                } label: {
                                    Label("common.delete".localized, systemImage: "trash")
                                }
                            }
                            .contextMenu {
                                Button {
                                    nicknameText = identity.nickname ?? ""
                                    editingNickname = identity
                                } label: {
                                    Label("vault.nickname.title".localized, systemImage: "pencil")
                                }
                                Button(role: .destructive) {
                                    deleteTarget = identity
                                    showDeleteConfirm = true
                                } label: {
                                    Label("common.delete".localized, systemImage: "trash")
                                }
                            }
                        }
                    }
                } else {
                    Section {
                        VStack(spacing: 12) {
                            Image(systemName: "person.crop.circle.badge.questionmark")
                                .font(.scaled(36))
                                .foregroundColor(.secondary)
                                .accessibilityHidden(true)
                            Text("identity.vault.no_saved".localized)
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                            Text("identity.vault.no_saved_hint".localized)
                                .font(.caption)
                                .foregroundColor(.secondary)
                                .multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .accessibilityElement(children: .combine)
                    }
                }

                // MARK: - Import Options
                Section(header: Text("identity.vault.import_section".localized)) {
                    Button {
                        importText = ""
                        importError = false
                        showPasteImport = true
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "doc.on.clipboard")
                                .foregroundColor(brandColor)
                                .frame(width: 24)
                                .accessibilityHidden(true)
                            Text("identity.vault.paste_key".localized)
                                .foregroundColor(.primary)
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundColor(.secondary)
                                .accessibilityHidden(true)
                        }
                        .contentShape(Rectangle())
                    }
                    .accessibilityLabel("identity.vault.paste_key".localized)
                    .accessibilityIdentifier("recovery_paste_button")

                    Button {
                        showQRScanner = true
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "qrcode.viewfinder")
                                .foregroundColor(brandColor)
                                .frame(width: 24)
                                .accessibilityHidden(true)
                            Text("identity.vault.scan_qr".localized)
                                .foregroundColor(.primary)
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundColor(.secondary)
                                .accessibilityHidden(true)
                        }
                        .contentShape(Rectangle())
                    }
                    .accessibilityLabel("identity.vault.scan_qr".localized)
                }
            }
            .navigationTitle("identity.vault.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel".localized) { dismiss() }
                }
            }
            .onAppear { reloadVault() }

            // Delete confirmation
            .alert("identity.vault.delete_title".localized, isPresented: $showDeleteConfirm) {
                Button("common.cancel".localized, role: .cancel) {}
                Button("common.delete".localized, role: .destructive) {
                    if let target = deleteTarget {
                        IdentityVaultManager.shared.deleteIdentity(id: target.id)
                        reloadVault()
                    }
                }
            } message: {
                Text("identity.vault.delete_message".localized)
            }

            // Nickname editor
            .alert("vault.nickname.title".localized, isPresented: .init(
                get: { editingNickname != nil },
                set: { if !$0 { editingNickname = nil } }
            )) {
                TextField("vault.nickname.placeholder".localized, text: $nicknameText)
                Button("common.cancel".localized, role: .cancel) { editingNickname = nil }
                Button("common.save".localized) {
                    if let target = editingNickname {
                        let name = nicknameText.trimmingCharacters(in: .whitespacesAndNewlines)
                        IdentityVaultManager.shared.updateNickname(id: target.id, nickname: name.isEmpty ? nil : name)
                        reloadVault()
                    }
                    editingNickname = nil
                }
            } message: {
                Text("identity.vault.nickname_hint".localized)
            }

            // Paste import sheet
            .sheet(isPresented: $showPasteImport) {
                pasteImportView
            }

            // QR Scanner sheet
            .sheet(isPresented: $showQRScanner) {
                QRScannerImportView { scannedKey in
                    showQRScanner = false
                    // Check for App Store Review demo mode first
                    if DemoDataManager.isDemoKey(scannedKey) {
                        onRecover(scannedKey)
                        return
                    }
                    if let imported = IdentityVaultManager.shared.importIdentity(recoveryKey: scannedKey) {
                        reloadVault()
                        selectIdentity(imported)
                    }
                }
            }
        }
        // On Mac Catalyst a plain NavigationView selects the split-column style.
        // This recovery flow has no detail column, so Catalyst displayed an empty
        // pane instead of the importer (and its Cancel control). Keep it one
        // column on every platform: identity recovery is a modal task, not a
        // master/detail browser.
        .navigationViewStyle(StackNavigationViewStyle())
    }

    // MARK: - Identity Row

    private func identityRow(_ identity: StoredIdentity) -> some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(brandColor.opacity(0.15))
                    .frame(width: 44, height: 44)
                Image(systemName: "person.crop.circle")
                    .font(.scaled(20))
                    .foregroundColor(brandColor)
            }
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 3) {
                Text(identity.nickname ?? "vault.identity.default".localized)
                    .font(.subheadline)
                    .fontWeight(.medium)
                    .foregroundColor(.primary)
                Text(truncatedKey(identity.publicKey))
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .monospaced()
                Text(formatDate(identity.createdAt))
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }

            Spacer()

            Image(systemName: "arrow.right.circle.fill")
                .foregroundColor(brandColor)
                .font(.title3)
                .accessibilityHidden(true)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    // MARK: - Paste Import View

    private var pasteImportView: some View {
        NavigationView {
            VStack(spacing: 20) {
                Image(systemName: "key.horizontal.fill")
                    .font(.scaled(50))
                    .foregroundColor(brandColor)
                    .padding(.top, 20)

                Text("identity.vault.paste_title".localized)
                    .font(.title2)
                    .fontWeight(.bold)

                Text("identity.vault.paste_description".localized)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                TextEditor(text: $importText)
                    // __SECRET_FIELD_NO_LEARNING_2026_11_20__ This is the identity's
                    // PRIVATE key. With autocorrection on, the keyboard spell-checks
                    // and learns it (predictions / user lexicon). Off, like a password.
                    .autocorrectionDisabled(true)
                    .textInputAutocapitalization(.never)
                    .font(.system(.body, design: .monospaced))
                    .frame(minHeight: 100)
                    .padding(8)
                    .background(Color(.systemGray6))
                    .cornerRadius(12)
                    .padding(.horizontal)
                    .accessibilityIdentifier("import_text_editor")

                if importError {
                    HStack {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundColor(.red)
                        Text("identity.vault.import_error".localized)
                            .font(.caption)
                            .foregroundColor(.red)
                    }
                    .padding(.horizontal)
                }

                Button {
                    importError = false
                    let trimmed = importText.trimmingCharacters(in: .whitespacesAndNewlines)
                    // Check for App Store Review demo mode first
                    if DemoDataManager.isDemoKey(trimmed) {
                        showPasteImport = false
                        onRecover(trimmed)
                        return
                    }
                    if let imported = IdentityVaultManager.shared.importIdentity(recoveryKey: trimmed) {
                        showPasteImport = false
                        reloadVault()
                        selectIdentity(imported)
                    } else {
                        importError = true
                    }
                } label: {
                    Text("identity.import_button".localized)
                        .font(.headline)
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(importText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? Color.gray : brandColor)
                        .cornerRadius(14)
                }
                .disabled(importText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .accessibilityIdentifier("import_submit_button")
                .padding(.horizontal)

                Spacer()
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel".localized) { showPasteImport = false }
                }
            }
        }
        .navigationViewStyle(StackNavigationViewStyle())
    }

    // MARK: - Helpers

    private func reloadVault() {
        vault = IdentityVaultManager.shared.loadVault()
    }

    private func selectIdentity(_ identity: StoredIdentity) {
        let combined = identity.encryptionKeyData + identity.signingKeyData
        let recoveryKey = combined.base64EncodedString()
        onRecover(recoveryKey)
    }

    private func truncatedKey(_ key: String) -> String {
        guard key.count > 16 else { return key }
        return "\(key.prefix(8))...\(key.suffix(4))"
    }

    private func formatDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}

// MARK: - QR Scanner for Identity Import

struct QRScannerImportView: View {
    var onScanned: (String) -> Void
    @Environment(\.dismiss) var dismiss
    @StateObject private var scanner = QRScanner()
    @State private var manualKey = ""
    @State private var errorMessage: String?

    private let brandColor = Theme.brand

    var body: some View {
        NavigationView {
            VStack(spacing: 16) {
                // Camera QR scanner
                ZStack {
                    if scanner.isCameraAvailable {
                        CameraPreviewView(session: scanner.captureSession)
                            .frame(height: 300)
                            .cornerRadius(16)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(brandColor, lineWidth: 3)
                                    .frame(width: 220, height: 220)
                            )
                    } else {
                        VStack(spacing: 16) {
                            Image(systemName: scanner.permissionDenied ? "camera.fill.badge.ellipsis" : "qrcode.viewfinder")
                                .font(.scaled(50))
                                .foregroundColor(.gray)
                            Text(scanner.permissionDenied ?
                                 "qr_scanner_camera_access_denied".localized :
                                 "qr_scanner_camera_unavailable".localized)
                                .font(.headline)
                            if scanner.permissionDenied {
                                Button {
                                    if let url = URL(string: UIApplication.openSettingsURLString) {
                                        UIApplication.shared.open(url)
                                    }
                                } label: {
                                    Label("qr_scanner_open_settings".localized, systemImage: "gear")
                                        .padding(.horizontal, 16).padding(.vertical, 10)
                                        .background(brandColor).foregroundColor(.white).cornerRadius(10)
                                }
                            }
                        }
                        .frame(height: 300).frame(maxWidth: .infinity)
                        .background(Color(.systemGray6)).cornerRadius(16)
                    }
                }
                .padding(.horizontal)

                Text("identity.vault.scan_instructions".localized)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                // Manual paste fallback
                VStack(spacing: 10) {
                    Text("qr_scanner_manual_entry".localized)
                        .font(.caption).foregroundColor(.secondary)

                    TextField("identity.vault.paste_key_placeholder".localized, text: $manualKey)
                        .textFieldStyle(.roundedBorder)
                        .autocapitalization(.none)
                        .autocorrectionDisabled(true)   // __SECRET_FIELD_NO_LEARNING_2026_11_20__ private key
                        .font(.system(.caption, design: .monospaced))
                        .padding(.horizontal)

                    if let error = errorMessage {
                        Text(error).font(.caption).foregroundColor(.red).padding(.horizontal)
                    }

                    Button {
                        validateAndImport(manualKey)
                    } label: {
                        Text("identity.vault.import_key".localized)
                            .frame(maxWidth: .infinity).padding(.vertical, 12)
                            .background(manualKey.isEmpty ? Color.gray : brandColor)
                            .foregroundColor(.white).cornerRadius(10)
                    }
                    .disabled(manualKey.isEmpty)
                    .padding(.horizontal)
                }

                Spacer()
            }
            .navigationTitle("identity.vault.scan_title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel".localized) { dismiss() }
                }
            }
            .task {
                await scanner.requestPermissionAndSetup()
            }
            .onDisappear {
                scanner.stopScanning()
            }
            .onChange(of: scanner.scannedCode) { _, newCode in
                if let code = newCode, !code.isEmpty {
                    validateAndImport(code)
                }
            }
        }
        .navigationViewStyle(StackNavigationViewStyle())
    }

    private func validateAndImport(_ key: String) {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        // Check for App Store Review demo mode first
        if DemoDataManager.isDemoKey(trimmed) {
            errorMessage = nil
            onScanned(trimmed)
            dismiss()
            return
        }
        // Validate: must be a base64-encoded 64-byte key
        guard let data = Data(base64Encoded: trimmed), data.count == 64 else {
            errorMessage = "identity.vault.invalid_key".localized
            return
        }
        errorMessage = nil
        onScanned(trimmed)
        dismiss()
    }
}

// MARK: - Info View

struct InfoView: View {
    @Environment(\.dismiss) var dismiss
    
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 25) {
                    InfoSection(
                        icon: "lock.shield.fill",
                        title: "info.encryption_title".localized,
                        description: "info.encryption_description".localized
                    )
                    
                    InfoSection(
                        icon: "eye.slash.fill",
                        title: "info.anonymity_title".localized,
                        description: "info.anonymity_description".localized
                    )
                    
                    InfoSection(
                        icon: "wifi.slash",
                        title: "info.offline_title".localized,
                        description: "info.offline_description".localized
                    )
                    
                    InfoSection(
                        icon: "network",
                        title: "info.mesh_title".localized,
                        description: "info.mesh_description".localized
                    )
                    
                    InfoSection(
                        icon: "server.rack",
                        title: "info.decentralized_title".localized,
                        description: "info.decentralized_description".localized
                    )
                    
                    InfoSection(
                        icon: "key.fill",
                        title: "info.keys_title".localized,
                        description: "info.keys_description".localized
                    )
                }
                .padding()
            }
            .navigationTitle("identity.how_it_works".localized)
            .navigationBarTitleDisplayMode(.large)
            .navigationBarItems(trailing: Button("common.done".localized) {
                dismiss()
            })
        }
    }
}

// MARK: - Info Section Component

struct InfoSection: View {
    let icon: String
    let title: String
    let description: String
    
    var body: some View {
        HStack(alignment: .top, spacing: 15) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundColor(appBrandColor)
                .frame(width: 40)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 5) {
                Text(title)
                    .font(.headline)
                Text(description)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Preview

#Preview {
    IdentitySetupView()
        .environmentObject(IdentityManager())
        .environmentObject(MeshNetworkManager())
}
