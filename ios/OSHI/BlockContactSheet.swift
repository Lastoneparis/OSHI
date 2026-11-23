//
//  BlockContactSheet.swift
//  OSHI - Block Contact Dialog
//

import SwiftUI

struct BlockContactSheet: View {
    let contactPublicKey: String
    let contactDisplayName: String?
    let onBlock: () -> Void
    
    @Environment(\.presentationMode) var presentationMode
    @State private var selectedReason: BlockReason? = nil
    @State private var showingConfirmation = false
    
    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Header
                VStack(spacing: 16) {
                    ZStack {
                        Circle()
                            .fill(Color.red.opacity(0.1))
                            .frame(width: 80, height: 80)

                        Image(systemName: "hand.raised.fill")
                            .font(.scaled(40))
                            .foregroundColor(.red)
                    }
                    .accessibilityHidden(true)

                    Text("block_contact_title".localized)
                        .font(.title2)
                        .fontWeight(.bold)

                    Text(String(format: "block_contact_subtitle".localized, contactDisplayName ?? "block_contact_this_user".localized))
                        .font(.body)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 20)
                }
                .padding(.top, 30)
                .padding(.bottom, 20)
                .accessibilityElement(children: .combine)
                
                Divider()
                
                // Reasons list
                ScrollView {
                    VStack(spacing: 0) {
                        Text("block_contact_select_reason".localized)
                            .font(.headline)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding()
                        
                        ForEach(BlockReason.allCases, id: \.self) { reason in
                            BlockReasonRow(
                                reason: reason,
                                isSelected: selectedReason == reason
                            ) {
                                selectedReason = reason
                            }
                        }
                    }
                }
                
                Divider()
                
                // Action buttons
                VStack(spacing: 12) {
                    Button(action: {
                        #if !targetEnvironment(macCatalyst)
                        UINotificationFeedbackGenerator().notificationOccurred(.warning)
                        #endif
                        showingConfirmation = true
                    }) {
                        HStack {
                            Image(systemName: "hand.raised.fill")
                            Text("block_contact_block_button".localized)
                                .fontWeight(.semibold)
                        }
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .padding()
                        .background(Color.red)
                        .foregroundColor(.white)
                        .cornerRadius(12)
                    }
                    .disabled(selectedReason == nil)
                    .opacity(selectedReason == nil ? 0.5 : 1.0)
                    .animation(.easeInOut(duration: 0.2), value: selectedReason)
                    .accessibilityHint("block_contact_block_hint".localized)

                    Button(action: {
                        presentationMode.wrappedValue.dismiss()
                    }) {
                        Text("common.cancel".localized)
                            .fontWeight(.medium)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .padding()
                            .background(Color(.systemGray6))
                            .foregroundColor(.primary)
                            .cornerRadius(12)
                    }
                }
                .padding()
            }
            .frame(maxWidth: 600)
            .frame(maxWidth: .infinity)
            .navigationBarHidden(true)
            .alert(isPresented: $showingConfirmation) {
                Alert(
                    title: Text("block_contact_confirm_title".localized),
                    message: Text("block_contact_confirm_message".localized),
                    primaryButton: .destructive(Text("common.block".localized)) {
                        BlockedContactsManager.shared.blockContact(
                            publicKey: contactPublicKey,
                            displayName: contactDisplayName,
                            reason: selectedReason
                        )
                        onBlock()
                        presentationMode.wrappedValue.dismiss()
                    },
                    secondaryButton: .cancel()
                )
            }
        }
    }
}

// MARK: - Block Reason Row

struct BlockReasonRow: View {
    let reason: BlockReason
    let isSelected: Bool
    let onSelect: () -> Void
    
    var body: some View {
        Button(action: onSelect) {
            HStack(spacing: 16) {
                Image(systemName: iconForReason)
                    .font(.title3)
                    .foregroundColor(isSelected ? .white : .red)
                    .frame(width: 30)
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 4) {
                    Text(reason.localizedDescription)
                        .font(.body)
                        .fontWeight(.medium)
                        .foregroundColor(isSelected ? .white : .primary)

                    Text(descriptionForReason)
                        .font(.caption)
                        .foregroundColor(isSelected ? .white.opacity(0.8) : .secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer()

                if isSelected {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.title3)
                        .foregroundColor(.white)
                        .accessibilityHidden(true)
                }
            }
            .padding()
            .frame(minHeight: 44)
            .background(isSelected ? Color.red : Color(.systemBackground))
            .cornerRadius(12)
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(isSelected ? Color.clear : Color(.systemGray4), lineWidth: 1)
            )
            .padding(.horizontal)
            .padding(.vertical, 4)
        }
        .buttonStyle(PlainButtonStyle())
        .animation(.easeInOut(duration: 0.2), value: isSelected)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
    }
    
    private var iconForReason: String {
        switch reason {
        case .spam:
            return "envelope.badge.fill"
        case .harassment:
            return "exclamationmark.triangle.fill"
        case .inappropriate:
            return "hand.raised.fill"
        case .other:
            return "ellipsis.circle.fill"
        }
    }
    
    private var descriptionForReason: String {
        switch reason {
        case .spam:
            return "block_reason_spam_desc".localized
        case .harassment:
            return "block_reason_harassment_desc".localized
        case .inappropriate:
            return "block_reason_inappropriate_desc".localized
        case .other:
            return "block_reason_other_desc".localized
        }
    }
}

// MARK: - Preview

struct BlockContactSheet_Previews: PreviewProvider {
    static var previews: some View {
        BlockContactSheet(
            contactPublicKey: "test123",
            contactDisplayName: "John Doe",
            onBlock: {}
        )
    }
}
