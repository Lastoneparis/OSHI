//
//  DeliveryMethodBadge.swift
//  Enhanced delivery badges showing VPS/Mesh and sent/received status
//

import SwiftUI

// MARK: - Brand Color
private let appBrandColor = Theme.brand

// MARK: - Delivery Method Badge (Full Version)

struct DeliveryMethodBadge: View {
    let method: DeliveryMethod?
    let status: DeliveryStatus
    let isOutgoing: Bool  // true = sent by current user, false = received

    @Environment(\.colorScheme) var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pendingAnimation = false

    init(method: DeliveryMethod?, status: DeliveryStatus, isOutgoing: Bool = true) {
        self.method = method
        self.status = status
        self.isOutgoing = isOutgoing
    }

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: iconName)
                .font(.scaled(10))
            Text(badgeText)
                .font(.scaled(10, weight: .medium))
        }
        .foregroundColor(badgeColor)
        .padding(.horizontal, 6)
        .padding(.vertical, 3)
        .background(badgeColor.opacity(0.15))
        .cornerRadius(6)
        .opacity(pendingAnimation && status == .pending && !reduceMotion ? 0.6 : 1.0)
        .animation(status == .pending && !reduceMotion ? .easeInOut(duration: 0.8).repeatForever(autoreverses: true) : .default, value: pendingAnimation)
        .onAppear {
            if status == .pending && !reduceMotion {
                pendingAnimation = true
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(badgeText)
    }

    private var iconName: String {
        switch status {
        case .pending:
            return "arrow.up.circle"
        case .failed:
            return "exclamationmark.triangle.fill"
        case .sent, .delivered, .read:
            guard let method = method else { return "cloud.fill" }
            switch method {
            case .mesh:
                return isOutgoing ? "arrow.up.right.circle.fill" : "arrow.down.left.circle.fill"
            case .lora:
                return isOutgoing ? "arrow.up.right.circle.fill" : "arrow.down.left.circle.fill"
            case .loraPlaintext:
                return isOutgoing ? "arrow.up.right.circle.fill" : "exclamationmark.circle.fill"
            case .ipfs:
                return isOutgoing ? "arrow.up.circle.fill" : "arrow.down.circle.fill"
            case .pending:
                return "clock.fill"
            }
        }
    }

    private var badgeText: String {
        switch status {
        case .pending:
            return "delivery_status_sending".localized
        case .failed:
            return "delivery_status_failed".localized
        case .sent, .delivered, .read:
            guard let method = method else {
                return isOutgoing ? "delivery_sent_vps".localized : "delivery_received_vps".localized
            }
            switch method {
            case .mesh:
                return isOutgoing ? "delivery_sent_mesh".localized : "delivery_received_mesh".localized
            case .lora:
                return isOutgoing ? "delivery_sent_lora".localized : "delivery_received_lora".localized
            case .loraPlaintext:
                return "delivery_lora_unverified".localized
            case .ipfs:
                return isOutgoing ? "delivery_sent_vps".localized : "delivery_received_vps".localized
            case .pending:
                return "delivery_status_sending".localized
            }
        }
    }

    private var badgeColor: Color {
        switch status {
        case .pending:
            return .orange
        case .failed:
            return .red
        case .sent, .delivered, .read:
            guard let method = method else { return appBrandColor }
            switch method {
            case .mesh:
                return .green
            case .lora:
                return .teal
            case .loraPlaintext:
                return .orange
            case .ipfs:
                return appBrandColor
            case .pending:
                return .orange
            }
        }
    }
}

// MARK: - Compact Icon Version

struct DeliveryMethodIcon: View {
    let method: DeliveryMethod?
    let status: DeliveryStatus
    let isOutgoing: Bool

    init(method: DeliveryMethod?, status: DeliveryStatus = .sent, isOutgoing: Bool = true) {
        self.method = method
        self.status = status
        self.isOutgoing = isOutgoing
    }

    var body: some View {
        HStack(spacing: 2) {
            // Direction arrow
            if status == .sent || status == .delivered {
                Image(systemName: isOutgoing ? "arrow.up" : "arrow.down")
                    .font(.scaled(8, weight: .bold))
            }

            // Method icon
            Image(systemName: iconName)
                .font(.scaled(11))
        }
        .foregroundColor(color)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
    }

    private var accessibilityText: String {
        switch status {
        case .pending:
            return "delivery_status_sending".localized
        case .failed:
            return "delivery_status_failed".localized
        case .sent, .delivered, .read:
            guard let method = method else {
                return isOutgoing ? "delivery_sent_vps".localized : "delivery_received_vps".localized
            }
            switch method {
            case .mesh:
                return isOutgoing ? "delivery_sent_mesh".localized : "delivery_received_mesh".localized
            case .lora:
                return isOutgoing ? "delivery_sent_lora".localized : "delivery_received_lora".localized
            case .loraPlaintext:
                return "delivery_lora_unverified".localized
            case .ipfs:
                return isOutgoing ? "delivery_sent_vps".localized : "delivery_received_vps".localized
            case .pending:
                return "delivery_status_sending".localized
            }
        }
    }

    private var iconName: String {
        switch status {
        case .pending:
            return "clock.fill"
        case .failed:
            return "xmark.circle.fill"
        case .sent, .delivered, .read:
            guard let method = method else { return "cloud.fill" }
            switch method {
            case .mesh:
                return "antenna.radiowaves.left.and.right"
            case .lora:
                return "dot.radiowaves.left.and.right"
            case .loraPlaintext:
                return "exclamationmark.triangle.fill"
            case .ipfs:
                return "cloud.fill"
            case .pending:
                return "clock.fill"
            }
        }
    }

    private var color: Color {
        switch status {
        case .pending:
            return .orange
        case .failed:
            return .red
        case .sent, .delivered, .read:
            guard let method = method else { return appBrandColor }
            switch method {
            case .mesh:
                return .green
            case .lora:
                return .teal
            case .loraPlaintext:
                return .orange
            case .ipfs:
                return appBrandColor
            case .pending:
                return .orange
            }
        }
    }
}

// MARK: - Minimal Badge (Just Icon + VPS/Mesh)

struct DeliveryBadgeMinimal: View {
    let method: DeliveryMethod?
    let isOutgoing: Bool
    /// __MESHCORE_INTEROP_2026_11_25__ A MeshCore text: named for its network, without the warning triangle
    /// (the note above the composer says who can read the thread).
    var meshCore: Bool = false

    var body: some View {
        // __CHAT_POLISH_2026_09_24__ A capsule chip in the same shape as
        // Android's, with per-scheme inks from `ChatPalette` — the old ink was
        // the raw brand / system green / system teal, which measured 2.1:1
        // (brand, dark), 2.2:1 (green, light) and 2.4:1 (teal, light) on the
        // wallpaper this chip sits on.
        HStack(spacing: 3) {
            Image(systemName: iconName)
                .font(.scaled(9))
                .accessibilityHidden(true)
            Text(methodText)
                .font(.scaled(10, weight: .medium))
        }
        .foregroundColor(color)
        .padding(.horizontal, 6)
        .padding(.vertical, 2)
        .background(color.opacity(ChatPalette.chipFill), in: Capsule())
        .background(ChatPalette.chipSurface, in: Capsule())
        .oledGlassEdge(Capsule())  // __OLED_GLASS_2026_11_16__
        .accessibilityElement(children: .combine)
    }

    private var iconName: String {
        if meshCore { return "dot.radiowaves.left.and.right" }
        guard let method = method else { return "cloud.fill" }
        switch method {
        case .mesh:
            return "bolt.fill"
        case .lora:
            return "dot.radiowaves.left.and.right"
        case .loraPlaintext:
            return "exclamationmark.triangle.fill"
        case .ipfs:
            return "cloud.fill"
        case .pending:
            return "clock"
        }
    }

    private var methodText: String {
        // 🔤 User-facing labels — never expose implementation jargon ("IPFS").
        // The two real transports are the internet relay ("Internet") and the
        // offline Bluetooth/Wi-Fi mesh ("Mesh").
        if meshCore { return "MeshCore" }
        guard let method = method else { return "Internet" }
        switch method {
        case .mesh:
            return "Mesh"
        case .lora:
            return "LoRa"
        case .loraPlaintext:
            return "LoRa · " + "delivery_lora_unverified".localized
        case .ipfs:
            return "Internet"
        case .pending:
            return "..."
        }
    }

    private var color: Color {
        if meshCore { return ChatPalette.transportInk(.lora) }
        guard let method = method else { return ChatPalette.transportInk(.internet) }
        switch method {
        case .mesh:
            return ChatPalette.transportInk(.mesh)
        case .lora:
            return ChatPalette.transportInk(.lora)
        case .loraPlaintext:
            return ChatPalette.transportInk(.pending)
        case .ipfs:
            return ChatPalette.transportInk(.internet)
        case .pending:
            return ChatPalette.transportInk(.pending)
        }
    }
}

// MARK: - Preview

#Preview {
    VStack(spacing: 15) {
        Text("Full Badges").font(.headline)

        HStack(spacing: 10) {
            VStack {
                Text("Outgoing").font(.caption)
                DeliveryMethodBadge(method: .mesh, status: .sent, isOutgoing: true)
                DeliveryMethodBadge(method: .ipfs, status: .sent, isOutgoing: true)
                DeliveryMethodBadge(method: nil, status: .pending, isOutgoing: true)
                DeliveryMethodBadge(method: nil, status: .failed, isOutgoing: true)
            }

            VStack {
                Text("Incoming").font(.caption)
                DeliveryMethodBadge(method: .mesh, status: .delivered, isOutgoing: false)
                DeliveryMethodBadge(method: .ipfs, status: .delivered, isOutgoing: false)
            }
        }

        Divider()

        Text("Compact Icons").font(.headline)
        HStack(spacing: 15) {
            DeliveryMethodIcon(method: .mesh, status: .sent, isOutgoing: true)
            DeliveryMethodIcon(method: .ipfs, status: .sent, isOutgoing: true)
            DeliveryMethodIcon(method: .mesh, status: .delivered, isOutgoing: false)
            DeliveryMethodIcon(method: .ipfs, status: .delivered, isOutgoing: false)
            DeliveryMethodIcon(method: nil, status: .pending, isOutgoing: true)
            DeliveryMethodIcon(method: nil, status: .failed, isOutgoing: true)
        }

        Divider()

        Text("Minimal Badges").font(.headline)
        HStack(spacing: 10) {
            DeliveryBadgeMinimal(method: .mesh, isOutgoing: true)
            DeliveryBadgeMinimal(method: .ipfs, isOutgoing: true)
            DeliveryBadgeMinimal(method: nil, isOutgoing: true)
        }
    }
    .padding()
}
