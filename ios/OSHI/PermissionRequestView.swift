//
//  PermissionRequestView.swift
//  ✅ FULLY LOCALIZED - All strings ready for translation
//  APP STORE COMPLIANT: Uses "Continue" instead of "Grant Permission"
//  Fixes: Guideline 5.1.1 - Legal - Privacy - Data Collection and Storage
//

import SwiftUI

struct PermissionRequestView: View {
    @EnvironmentObject var permissionHelper: PermissionHelper
    @Binding var hasRequestedPermissions: Bool
    
    @State private var isRequestingPermissions = false
    
    var body: some View {
        VStack(spacing: 30) {
            Spacer()
            
            // The app's own mark, not a system shield. This is the first thing a
            // new user sees; a stock blue SF Symbol here reads as an unfinished
            // app and shares nothing with the icon they just tapped.
            Image("LaunchLogo")
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: 96, height: 96)
                .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                .shadow(color: Theme.brand.opacity(0.45),
                        radius: 22, y: 10)
                .accessibilityLabel("OSHI")
            
            Text("permission_welcome_title".localized)
                .font(.largeTitle)
                .fontWeight(.bold)
            
            Text("permission_welcome_description".localized)
                .font(.body)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)
            
            VStack(spacing: 15) {
                PermissionRow(
                    icon: "bell.fill",
                    title: "permission_notifications_title".localized,
                    description: "permission_notifications_description".localized
                )

                // Other permissions (Bluetooth, Location, Camera, Calendar, Local Network)
                // are requested lazily when the user taps the feature that needs them.
            }
            .padding(.horizontal)
            
            Spacer()
            
            // ✅ APP STORE COMPLIANT: "Continue" button
            Button {
                requestAllPermissions()
            } label: {
                HStack {
                    if isRequestingPermissions {
                        ProgressView()
                            .progressViewStyle(CircularProgressViewStyle(tint: .white))
                        Text("permission_setting_up".localized)
                    } else {
                        Text("permission_continue".localized)
                            .font(.headline)
                        Image(systemName: "arrow.right.circle.fill")
                    }
                }
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .padding()
                .background(
                    LinearGradient(
                        colors: [Color.blue, Color.purple],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                )
                .cornerRadius(15)
                .shadow(color: .blue.opacity(0.3), radius: 10, x: 0, y: 5)
            }
            .disabled(isRequestingPermissions)
            .padding(.horizontal)
            .padding(.bottom, 40)
        }
        .macReadableWidth()
        .alert("permission_bluetooth_disabled_title".localized, isPresented: $permissionHelper.showBluetoothAlert) {
            Button("permission_open_settings".localized) {
                permissionHelper.openSettings()
            }
            Button("permission_cancel".localized, role: .cancel) {}
        } message: {
            Text("permission_bluetooth_disabled_message".localized)
        }
    }
    
    // ✅ LAZY PERMISSIONS: Only request Notifications at startup.
    // All other permissions (Bluetooth, Location, Camera, Calendar, Local Network)
    // are requested lazily when the user taps the feature that needs them.
    private func requestAllPermissions() {
        guard !isRequestingPermissions else { return }

        isRequestingPermissions = true
        OshiLog.ui.info("🎯 Starting permission flow (notifications only)...")

        // Only request Notifications at startup
        NotificationManager.shared.requestPermissions { notificationGranted in
            OshiLog.ui.info("📱 Notifications: \(notificationGranted ? "✅ Granted" : "❌ Denied")")

            DispatchQueue.main.async {
                self.isRequestingPermissions = false
                self.hasRequestedPermissions = true
                OshiLog.ui.info("✅ Notification permission requested, moving to identity screen")
                OshiLog.ui.info("   ℹ️ Other permissions will be requested on-demand when features are used")
            }
        }
    }
}

struct PermissionRow: View {
    let icon: String
    let title: String
    let description: String
    
    @Environment(\.colorScheme) var colorScheme
    
    var body: some View {
        HStack(spacing: 15) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundColor(.blue)
                .frame(width: 40)
            
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.headline)
                    .foregroundColor(colorScheme == .dark ? .white : .black)
                
                Text(description)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            
            Spacer()
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(colorScheme == .dark ? Color(.systemGray6) : Color(.systemGray5))
        )
    }
}

#Preview {
    PermissionRequestView(hasRequestedPermissions: .constant(false))
        .environmentObject(PermissionHelper())
}
