//
//  ContactSelectorView.swift
//  OSHI - Select Contacts to Share
//

import SwiftUI

// MARK: - Brand Color
private let appBrandColor = Theme.brand

struct ContactSelectorView: View {
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var messageManager: MessageManager
    @EnvironmentObject var identityManager: IdentityManager
    @AppStorage("contactAliases") private var contactAliasesData: Data = Data()
    
    let onContactSelected: (String, String?) -> Void
    /// __ANATOLE_1311_IOS__ (N4) The confirm button's label. nil keeps
    /// "Share" for the contact-card flow; the compose menu's call pickers pass
    /// "Voice call" / "Video call".
    var confirmTitle: String? = nil
    /// Which conversation keys may be offered. nil offers them all.
    var includeKey: ((String) -> Bool)? = nil
    
    @State private var searchText = ""
    @State private var selectedContact: String?
    
    private var contactAliases: [String: String] {
        guard !contactAliasesData.isEmpty else { return [:] }
        return (try? JSONDecoder().decode([String: String].self, from: contactAliasesData)) ?? [:]
    }

    private var conversations: [Conversation] {
        guard let includeKey else { return messageManager.conversations }
        return messageManager.conversations.filter { includeKey($0.participantPublicKey) }
    }
    
    /// __SHARED_NICKNAME_2026_09_22__ Nom affiche: alias local > pseudo partage.
    /// (La carte de contact envoyee, elle, porte toujours l'alias seul.)
    private func displayName(for publicKey: String) -> String? {
        SharedNickname.resolve(alias: contactAliases[publicKey],
                               nickname: SharedNickname.nickname(for: publicKey))
    }

    private var filteredConversations: [Conversation] {
        if searchText.isEmpty {
            return conversations
        }
        
        return conversations.filter { conversation in
            let publicKey = conversation.participantPublicKey
            // __SHARED_NICKNAME_2026_09_22__ recherche sur le nom AFFICHE.
            let alias = displayName(for: publicKey) ?? ""
            
            return publicKey.localizedCaseInsensitiveContains(searchText) ||
                   alias.localizedCaseInsensitiveContains(searchText)
        }
    }
    
    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Search bar
                HStack {
                    Image(systemName: "magnifyingglass")
                        .foregroundColor(.gray)
                    
                    TextField("contact_selector.search".localized, text: $searchText)
                        .textFieldStyle(.plain)
                    
                    if !searchText.isEmpty {
                        Button {
                            searchText = ""
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundColor(.gray)
                                .frame(width: 44, height: 44)
                                .contentShape(Rectangle())
                        }
                        .accessibilityLabel("a11y.clear_search".localized)
                        .transition(.opacity)
                    }
                }
                .padding(.leading)
                .padding(.vertical, 4)
                .padding(.trailing, 4)
                .background(Color(.systemGray6))
                
                if filteredConversations.isEmpty {
                    emptyView
                } else {
                    List {
                        ForEach(filteredConversations) { conversation in
                            ContactRow(
                                publicKey: conversation.participantPublicKey,
                                alias: displayName(for: conversation.participantPublicKey),
                                isSelected: selectedContact == conversation.participantPublicKey
                            )
                            .contentShape(Rectangle())
                            .onTapGesture {
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                withAnimation(.easeInOut(duration: 0.15)) {
                                    selectedContact = conversation.participantPublicKey
                                }
                            }
                            .accessibilityAddTraits(selectedContact == conversation.participantPublicKey ? [.isButton, .isSelected] : .isButton)
                        }
                    }
                }
            }
            // Cap content width so contact rows stay centered and legible on
            // iPad / landscape rather than stretching edge-to-edge.
            .frame(maxWidth: 700)
            .frame(maxWidth: .infinity)
            .navigationTitle("contact_selector.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel".localized) {
                        dismiss()
                    }
                }
                
                ToolbarItem(placement: .confirmationAction) {
                    Button(confirmTitle ?? "contact_selector.share".localized) {
                        if let publicKey = selectedContact {
                            let alias = contactAliases[publicKey]
                            onContactSelected(publicKey, alias)
                            dismiss()
                        }
                    }
                    .disabled(selectedContact == nil)
                    .fontWeight(.semibold)
                }
            }
        }
    }
    
    private var emptyView: some View {
        VStack(spacing: 16) {
            Image(systemName: "person.2.slash")
                .font(.scaled(60))
                .foregroundColor(.gray)
            
            Text(searchText.isEmpty ?
                 "contact_selector.no_contacts".localized :
                 "contact_selector.no_results".localized)
                .font(.headline)
                .foregroundColor(.secondary)
            
            if searchText.isEmpty {
                Text("contact_selector.start_chat_hint".localized)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
            }
        }
        .frame(maxWidth: 420)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Contact Row

struct ContactRow: View {
    let publicKey: String
    let alias: String?
    let isSelected: Bool
    
    private var displayName: String {
        alias ?? formatAddress(publicKey)
    }

    var body: some View {
        HStack(spacing: 12) {
            // Avatar — show the contact's profile photo when we have one so it's
            // easy to recognise/authenticate them; falls back to name-initials.
            // Reuses the app-wide avatar component (no presence badge in the picker).
            ContactAvatarWithBadge(publicKey: publicKey, size: 50, showBadge: false, displayName: alias)

            VStack(alignment: .leading, spacing: 4) {
                Text(displayName)
                    .font(.scaled(16, weight: .medium))
                    .foregroundColor(.primary)
                
                Text(publicKey)
                    .font(.scaled(12, design: .monospaced))
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }
            
            Spacer()
            
            if isSelected {
                Image(systemName: "checkmark.circle.fill")
                    .font(.title2)
                    .foregroundColor(appBrandColor)
                    .transition(.scale.combined(with: .opacity))
            }
        }
        .padding(.vertical, 8)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(displayName)
    }
    
    private func formatAddress(_ address: String) -> String {
        guard address.count > 10 else { return address }
        let start = address.prefix(6)
        let end = address.suffix(4)
        return "\(start)...\(end)"
    }
}
