//
//  SharedModels.swift
//  Shared models used across multiple managers
//  Create this NEW file in your project
//

import Foundation
import SwiftUI

// MARK: - Public Group Advertisement

struct PublicGroupAd: Codable {
    let groupId: UUID
    let groupName: String
    let adminPublicKey: String
    let memberCount: Int
    let avatar: String?
    let timestamp: Date
}

// MARK: - Discovery Request (sent to mesh to ask peers to broadcast their public groups)

struct DiscoveryRequest: Codable {
    let requesterId: String  // Public key of the requesting device
    let timestamp: Date
    let type: String
    
    init(requesterId: String, timestamp: Date) {
        self.requesterId = requesterId
        self.timestamp = timestamp
        self.type = "discovery_request"
    }
}

// MARK: - Share Sheet (UIKit Bridge)

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    
    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(
            activityItems: items,
            applicationActivities: nil
        )
        return controller
    }
    
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
