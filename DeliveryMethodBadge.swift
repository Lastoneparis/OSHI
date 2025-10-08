//
//  DeliveryMethodBadge.swift
//  FINAL FIX: No warnings + Clean code
//

import SwiftUI

struct DeliveryMethodBadge: View {
    let method: DeliveryMethod?
    let status: DeliveryStatus
    
    @Environment(\.colorScheme) var colorScheme
    @State private var pendingAnimation = false
    
    var body: some View {
        if status == .sent || status == .delivered, let method = method {
            HStack(spacing: 4) {
                Image(systemName: iconName(for: method))
                    .font(.system(size: 10))
                Text(method.rawValue)
                    .font(.system(size: 10, weight: .medium))
            }
            .foregroundColor(color(for: method))
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(color(for: method).opacity(0.15))
            .cornerRadius(6)
        } else if status == .pending {
            HStack(spacing: 4) {
                Circle()
                    .fill(Color.gray)
                    .frame(width: 6, height: 6)
                    .opacity(pendingAnimation ? 0.3 : 1.0)
                    .animation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true), value: pendingAnimation)
                    .onAppear {
                        pendingAnimation = true
                    }
                
                Text("Sending...")
                    .font(.system(size: 10))
            }
            .foregroundColor(.gray)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
        } else if status == .failed {
            HStack(spacing: 4) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 10))
                Text("Failed")
                    .font(.system(size: 10))
            }
            .foregroundColor(.red)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(Color.red.opacity(0.15))
            .cornerRadius(6)
        }
    }
    
    private func iconName(for method: DeliveryMethod) -> String {
        switch method {
        case .mesh:
            return "antenna.radiowaves.left.and.right"
        case .ipfs:
            return "cloud.fill"
        case .pending:
            return "clock"
        }
    }
    
    private func color(for method: DeliveryMethod) -> Color {
        switch method {
        case .mesh:
            return .green
        case .ipfs:
            return .blue
        case .pending:
            return .gray
        }
    }
}

// MARK: - Alternative: Compact Icon Only Version (FIXED)

struct DeliveryMethodIcon: View {
    let method: DeliveryMethod?
    
    var body: some View {
        // ✅ FIX: Use method != nil instead of unwrapping
        if method != nil {
            Image(systemName: iconName)
                .font(.system(size: 11))
                .foregroundColor(color)
        }
    }
    
    // ✅ Use optional chaining on method property
    private var iconName: String {
        guard let method = method else { return "questionmark" }
        
        switch method {
        case .mesh:
            return "bolt.fill"
        case .ipfs:
            return "icloud.fill"
        case .pending:
            return "clock"
        }
    }
    
    private var color: Color {
        guard let method = method else { return .gray }
        
        switch method {
        case .mesh:
            return .green
        case .ipfs:
            return .blue
        case .pending:
            return .orange
        }
    }
}

// MARK: - Preview

#Preview {
    VStack(spacing: 15) {
        DeliveryMethodBadge(method: .mesh, status: .sent)
        DeliveryMethodBadge(method: .ipfs, status: .sent)
        DeliveryMethodBadge(method: .pending, status: .pending)
        DeliveryMethodBadge(method: nil, status: .failed)
        
        Divider()
        
        HStack(spacing: 10) {
            DeliveryMethodIcon(method: .mesh)
            DeliveryMethodIcon(method: .ipfs)
            DeliveryMethodIcon(method: .pending)
            DeliveryMethodIcon(method: nil)
        }
    }
    .padding()
}
