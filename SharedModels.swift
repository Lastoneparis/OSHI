//
//  SharedModels.swift
//  Shared models used across multiple managers
//  Create this NEW file in your project
//

import Foundation

// MARK: - Public Group Advertisement

struct PublicGroupAd: Codable {
    let groupId: UUID
    let groupName: String
    let adminPublicKey: String
    let memberCount: Int
    let avatar: String?
    let timestamp: Date
}
