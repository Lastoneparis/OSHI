//
//  CovertChannelManager.swift
//  OSHI — legacy carrier boundary
//
//  Authenticated V2 cover transport is the sole production obfuscation path.
//  Legacy DNS, header, domain-fronting, analytics, image and Unicode carriers
//  are not delivery transports and must fail closed if an old call site reaches
//  this compatibility boundary.
//

import Foundation

enum CovertChannelType: String, CaseIterable, Codable {
    case httpHeader
    case dnsSubdomain
    case domainFront
    case webMimicry
    case imageSteganography
    case textSteganography
}

enum NetworkRestrictionLevel: Int, Comparable {
    case open = 0
    case filtered = 1
    case dpiActive = 2
    case whitelist = 3
    case military = 4

    static func < (lhs: Self, rhs: Self) -> Bool { lhs.rawValue < rhs.rawValue }
}

struct NetworkAssessment {
    let restrictionLevel: NetworkRestrictionLevel
    let accessibleDomains: [String]
    let blockedDomains: [String]
    let dpiDetected: Bool
    let recommendedChannels: [CovertChannelType]
    let timestamp: Date
}

/// Inert encrypted payload model retained solely so manual text-carrier decode
/// can authenticate and parse historical local research artefacts. It has no
/// network sender or receiver path.
struct CovertFragment: Codable {
    let messageId: String
    let fragmentIndex: Int
    let totalFragments: Int
    let channel: CovertChannelType
    let payload: Data
    let checksum: Data
}

struct CovertSendResult {
    let messageId: String
    let channel: CovertChannelType
    let fragmentsSent: Int
    let totalSize: Int
    let success: Bool
}

enum CovertError: LocalizedError {
    case noAccessibleDomains
    case encryptionFailed
    case invalidPayload
    case integrityCheckFailed
    case missingFragment(Int)
    case noDomainFrontsConfigured
    case channelFailed(CovertChannelType, String)
    case payloadTooLarge

    var errorDescription: String? {
        switch self {
        case .noAccessibleDomains: return "No accessible domains found"
        case .encryptionFailed: return "Encryption failed"
        case .invalidPayload: return "Invalid covert payload"
        case .integrityCheckFailed: return "Fragment integrity check failed"
        case .missingFragment(let index): return "Missing fragment \(index)"
        case .noDomainFrontsConfigured: return "No domain fronts configured"
        case .channelFailed(let channel, let reason): return "\(channel.rawValue) failed: \(reason)"
        case .payloadTooLarge: return "Payload too large for channel"
        }
    }
}

final class CovertChannelManager {
    static let shared = CovertChannelManager()
    private init() {}

    func sendCovert(
        data: Data,
        secretKey: Data,
        preferredChannel: CovertChannelType? = nil,
        targetDomains: [String]? = nil
    ) async throws -> CovertSendResult {
        _ = (data, secretKey, preferredChannel, targetDomains)
        throw CovertError.channelFailed(.webMimicry,
            "Legacy carrier delivery is removed; use authenticated V2 cover transport")
    }

    func sendMultiChannel(
        data: Data,
        secretKey: Data,
        channels: [CovertChannelType]? = nil,
        targetDomains: [String]? = nil
    ) async throws -> CovertSendResult {
        _ = (data, secretKey, channels, targetDomains)
        throw CovertError.channelFailed(.webMimicry,
            "Legacy multi-carrier delivery is removed; use authenticated V2 cover transport")
    }

    /// Kept for the cover-padding regression suite. This is local framing math,
    /// not a network carrier.
    static func paddedBucket(forContentLength n: Int) -> Int {
        let framed = 4 + n
        return ((framed + 255) / 256) * 256
    }
}
