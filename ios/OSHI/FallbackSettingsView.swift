//
//  FallbackSettingsView.swift
//  UI for configuring VPS + IPFS fallback - LOCALIZED
//

import SwiftUI

struct FallbackSettingsView: View {
    @State private var onionURL = ""
    @State private var apiKey = ""
    @State private var fallbackEnabled = false
    @State private var showingHelp = false
    @State private var testingConnection = false
    @State private var connectionStatus = ""
    
    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Image(systemName: "network")
                            .foregroundColor(.blue)
                            .font(.title2)
                        
                        VStack(alignment: .leading) {
                            Text("fallback_title".localized)
                                .font(.headline)
                            Text("fallback_subtitle".localized)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                }
            }
            
            Section("fallback_section_configuration".localized) {
                VStack(alignment: .leading, spacing: 5) {
                    Text("fallback_onion_url".localized)
                        .font(.caption)
                        .foregroundColor(.secondary)
                    
                    TextField("fallback_onion_placeholder".localized, text: $onionURL)
                        .autocapitalization(.none)
                        .textFieldStyle(.roundedBorder)
                        .font(.system(.body, design: .monospaced))
                }
                
                VStack(alignment: .leading, spacing: 5) {
                    Text("fallback_api_key".localized)
                        .font(.caption)
                        .foregroundColor(.secondary)
                    
                    SecureField("fallback_api_key_placeholder".localized, text: $apiKey)
                        .textFieldStyle(.roundedBorder)
                        .font(.system(.body, design: .monospaced))
                }
                
                Toggle("fallback_enable_toggle".localized, isOn: $fallbackEnabled)
            }
            
            Section {
                Button {
                    saveConfiguration()
                } label: {
                    HStack {
                        Spacer()
                        Text("fallback_save_button".localized)
                        Spacer()
                    }
                }
                .disabled(onionURL.isEmpty || apiKey.isEmpty)
                
                Button {
                    testConnection()
                } label: {
                    HStack {
                        Spacer()
                        if testingConnection {
                            ProgressView()
                                .scaleEffect(0.8)
                        }
                        Text(testingConnection ? "fallback_testing".localized : "fallback_test_button".localized)
                        Spacer()
                    }
                }
                .disabled(onionURL.isEmpty || apiKey.isEmpty || testingConnection)
                
                if !connectionStatus.isEmpty {
                    HStack {
                        Image(systemName: connectionStatus.contains("✅") ? "checkmark.circle.fill" : "xmark.circle.fill")
                            .foregroundColor(connectionStatus.contains("✅") ? .green : .red)
                            .accessibilityHidden(true)
                        Text(connectionStatus)
                            .font(.caption)
                    }
                    .accessibilityElement(children: .combine)
                    .transition(.opacity)
                }
            }
            
            Section {
                Button {
                    showingHelp = true
                } label: {
                    Label("fallback_setup_instructions".localized, systemImage: "book")
                }
            } footer: {
                Text("fallback_footer_description".localized)
                    .font(.caption)
            }
            
            Section("fallback_section_status".localized) {
                HStack {
                    Text("fallback_status_mode".localized)
                    Spacer()
                    Text(fallbackEnabled ? "fallback_status_enabled".localized : "fallback_status_disabled".localized)
                        .foregroundColor(fallbackEnabled ? .green : .secondary)
                }
                
                HStack {
                    Text("fallback_status_vps".localized)
                    Spacer()
                    Circle()
                        .fill(onionURL.isEmpty ? Color.gray : Color.green)
                        .frame(width: 8, height: 8)
                        .accessibilityHidden(true)
                }
                .accessibilityElement(children: .combine)
                .accessibilityValue(onionURL.isEmpty
                                     ? "fallback_status_disabled".localized
                                     : "fallback_status_enabled".localized)
            }
        }
        .navigationTitle("fallback_navigation_title".localized)
        .onAppear {
            loadConfiguration()
        }
        .sheet(isPresented: $showingHelp) {
            FallbackHelpView()
        }
    }
    
    private func loadConfiguration() {
        onionURL = UserDefaults.standard.string(forKey: "vps_onion_url") ?? ""
        apiKey = UserDefaults.standard.string(forKey: "vps_api_key") ?? ""
        fallbackEnabled = UserDefaults.standard.bool(forKey: "fallback_enabled")
    }
    
    private func saveConfiguration() {
        FallbackService.shared.configure(onionURL: onionURL, apiKey: apiKey)
        UserDefaults.standard.set(fallbackEnabled, forKey: "fallback_enabled")
        connectionStatus = "fallback_config_saved".localized
        UINotificationFeedbackGenerator().notificationOccurred(.success)

        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            withAnimation { connectionStatus = "" }
        }
    }
    
    private func testConnection() {
        testingConnection = true
        connectionStatus = ""
        
        Task {
            do {
                // Test health endpoint
                guard let healthURL = URL(string: "\(onionURL)/health") else {
                    await MainActor.run {
                        connectionStatus = "fallback.error.invalidURL".localized
                        testingConnection = false
                    }
                    return
                }
                var request = URLRequest(url: healthURL)
                request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
                request.timeoutInterval = 2
                
                let (_, response) = try await URLSession.shared.data(for: request)
                
                if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                    await MainActor.run {
                        withAnimation { connectionStatus = "fallback_connection_success".localized }
                        testingConnection = false
                        UINotificationFeedbackGenerator().notificationOccurred(.success)
                    }
                } else {
                    throw FallbackError.uploadFailed
                }
            } catch {
                await MainActor.run {
                    withAnimation { connectionStatus = "fallback_connection_failed".localized }
                    testingConnection = false
                    UINotificationFeedbackGenerator().notificationOccurred(.error)
                }
            }
        }
    }
}

struct FallbackHelpView: View {
    @Environment(\.dismiss) var dismiss
    
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    InfoCard(
                        icon: "server.rack",
                        title: "fallback_help_what_title".localized,
                        description: "fallback_help_what_description".localized
                    )
                    
                    InfoCard(
                        icon: "lock.shield",
                        title: "fallback_help_privacy_title".localized,
                        description: "fallback_help_privacy_description".localized
                    )
                    
                    InfoCard(
                        icon: "gear",
                        title: "fallback_help_setup_title".localized,
                        description: "fallback_help_setup_description".localized
                    )
                    
                    InfoCard(
                        icon: "dollarsign.circle",
                        title: "fallback_help_cost_title".localized,
                        description: "fallback_help_cost_description".localized
                    )
                }
                .padding()
                // Keep help text readable on iPad / landscape.
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
            }
            .navigationTitle("fallback_help_navigation_title".localized)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("done".localized) {
                        dismiss()
                    }
                }
            }
        }
    }
}

struct InfoCard: View {
    let icon: String
    let title: String
    let description: String
    
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: icon)
                    .font(.title2)
                    .foregroundColor(.blue)
                    .accessibilityHidden(true)

                Text(title)
                    .font(.headline)
            }

            Text(description)
                .font(.subheadline)
                .foregroundColor(.secondary)
        }
        .padding()
        .background(Color(.systemGray6))
        .cornerRadius(12)
        .accessibilityElement(children: .combine)
    }
}
