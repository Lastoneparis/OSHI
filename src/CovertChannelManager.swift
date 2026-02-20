//
//  CovertChannelManager.swift
//  OSHI - Military-Grade Covert Communication Orchestrator
//
//  Designed for hostile network environments (Iran, Russian-controlled zones, etc.)
//  where only a few whitelisted websites are accessible.
//
//  Supports 6 covert channel families:
//  1. HTTP Header Stego     - Data in cookies, headers, ETags, timing
//  2. DNS Covert Channel    - Data in DNS subdomain queries
//  3. Domain Fronting       - CDN-fronted requests to hide real destination
//  4. Web Request Mimicry   - Normal-looking browsing with embedded payload
//  5. Image Steganography   - Adaptive LSB + DCT coefficient embedding
//  6. Text Steganography    - Zero-width Unicode, homoglyphs, whitespace
//
//  All channels resist: DPI, statistical analysis, ML classifiers, forensic inspection
//

import Foundation
import CryptoKit
import UIKit

// MARK: - Covert Channel Types

/// Available covert channel techniques, ordered by stealth
enum CovertChannelType: String, CaseIterable, Codable {
    case httpHeader
    case dnsSubdomain
    case domainFront
    case webMimicry
    case imageSteganography
    case textSteganography

    var displayName: String {
        switch self {
        case .httpHeader:          return "HTTP Covert"
        case .dnsSubdomain:        return "DNS Tunnel"
        case .domainFront:         return "Domain Front"
        case .webMimicry:          return "Web Mimicry"
        case .imageSteganography:  return "Image Stego"
        case .textSteganography:   return "Text Stego"
        }
    }

    /// Max payload per single operation (bytes)
    var maxPayloadSize: Int {
        switch self {
        case .httpHeader:          return 2048
        case .dnsSubdomain:        return 180
        case .domainFront:         return 65536
        case .webMimicry:          return 4096
        case .imageSteganography:  return 19000
        case .textSteganography:   return 512
        }
    }

    /// Stealth rating 1-10
    var stealthRating: Int {
        switch self {
        case .httpHeader:          return 8
        case .dnsSubdomain:        return 6
        case .domainFront:         return 9
        case .webMimicry:          return 9
        case .imageSteganography:  return 7
        case .textSteganography:   return 8
        }
    }
}

// MARK: - Network Environment Assessment

enum NetworkRestrictionLevel: Int, Comparable {
    case open = 0
    case filtered = 1
    case dpiActive = 2
    case whitelist = 3
    case military = 4

    static func < (lhs: Self, rhs: Self) -> Bool {
        lhs.rawValue < rhs.rawValue
    }

    var displayName: String {
        switch self {
        case .open:      return "Open"
        case .filtered:  return "Filtered"
        case .dpiActive: return "DPI Active"
        case .whitelist: return "Whitelist Only"
        case .military:  return "Military Grade"
        }
    }
}

struct NetworkAssessment {
    let restrictionLevel: NetworkRestrictionLevel
    let accessibleDomains: [String]
    let blockedDomains: [String]
    let dpiDetected: Bool
    let recommendedChannels: [CovertChannelType]
    let timestamp: Date
}

// MARK: - Covert Message Fragment

struct CovertFragment: Codable {
    let messageId: String
    let fragmentIndex: Int
    let totalFragments: Int
    let channel: CovertChannelType
    let payload: Data
    let checksum: Data
}

// MARK: - Domain Front Configuration

struct DomainFrontConfig: Codable {
    let cdnDomain: String       // What SNI/DNS resolves to (e.g. d1234.cloudfront.net)
    let actualHost: String      // HTTP Host header routes here (e.g. relay.oshi-messenger.com)
    let path: String            // Relay endpoint path
    let authToken: String?      // Optional relay auth
}

// MARK: - Covert Carrier (what carries the hidden message)

enum CovertCarrier {
    case httpResponse(HTTPURLResponse, Data)
    case dnsResponse([String])
    case image(UIImage)
    case text(String)
    case rawFragment(CovertFragment)
}

// MARK: - Results

struct CovertSendResult {
    let messageId: String
    let channel: CovertChannelType
    let fragmentsSent: Int
    let totalSize: Int
    let success: Bool
}

struct CovertFragmentResult {
    let success: Bool
    let channel: CovertChannelType
    var responseData: Data?
}

// MARK: - Errors

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
        case .noAccessibleDomains:       return "No accessible domains found"
        case .encryptionFailed:          return "Encryption failed"
        case .invalidPayload:            return "Invalid covert payload"
        case .integrityCheckFailed:      return "Fragment integrity check failed"
        case .missingFragment(let i):    return "Missing fragment \(i)"
        case .noDomainFrontsConfigured:  return "No domain fronts configured"
        case .channelFailed(let c, let r): return "\(c.displayName) failed: \(r)"
        case .payloadTooLarge:           return "Payload too large for channel"
        }
    }
}

// MARK: - Covert Channel Manager

final class CovertChannelManager {
    static let shared = CovertChannelManager()

    private let networkStego = NetworkSteganography.shared
    private let imageStego = SteganographyManager.shared
    private let textStego = TextSteganography.shared

    private var allowedDomains: [String] = []
    private var activeDomainFronts: [DomainFrontConfig] = []
    private(set) var restrictionLevel: NetworkRestrictionLevel = .open
    private(set) var lastAssessment: NetworkAssessment?

    // Fragment reassembly
    private var fragmentBuffer: [String: [Int: CovertFragment]] = [:]
    private let fragmentLock = NSLock()

    // Timing obfuscation
    private var lastRequestTime: Date = .distantPast
    private let minInterRequestDelay: TimeInterval = 0.8
    private let maxInterRequestDelay: TimeInterval = 6.0

    private init() {}

    // MARK: - Network Assessment

    /// Probe the network to determine restriction level and best channels
    func assessNetwork(probeDomains: [String]? = nil) async -> NetworkAssessment {
        let domains = probeDomains ?? defaultProbeDomains
        var accessible: [String] = []
        var blocked: [String] = []

        await withTaskGroup(of: (String, Bool).self) { group in
            for domain in domains {
                group.addTask { [weak self] in
                    guard let self else { return (domain, false) }
                    let reachable = await self.probeDomain(domain)
                    return (domain, reachable)
                }
            }
            for await (domain, reachable) in group {
                if reachable { accessible.append(domain) }
                else { blocked.append(domain) }
            }
        }

        let dpiDetected = await detectDPI(using: accessible)

        let level: NetworkRestrictionLevel
        let blockedRatio = domains.isEmpty ? 0.0 : Double(blocked.count) / Double(domains.count)

        if blockedRatio < 0.1 {
            level = .open
        } else if blockedRatio < 0.4 {
            level = dpiDetected ? .dpiActive : .filtered
        } else if blockedRatio < 0.8 {
            level = dpiDetected ? .military : .whitelist
        } else {
            level = .military
        }

        let recommended = recommendChannels(level: level, accessible: accessible, dpi: dpiDetected)

        self.restrictionLevel = level
        self.allowedDomains = accessible

        let assessment = NetworkAssessment(
            restrictionLevel: level,
            accessibleDomains: accessible,
            blockedDomains: blocked,
            dpiDetected: dpiDetected,
            recommendedChannels: recommended,
            timestamp: Date()
        )
        self.lastAssessment = assessment
        return assessment
    }

    // MARK: - Send Covert Message (Single Channel)

    func sendCovert(
        data: Data,
        secretKey: Data,
        preferredChannel: CovertChannelType? = nil,
        targetDomains: [String]? = nil
    ) async throws -> CovertSendResult {
        let domains = targetDomains ?? allowedDomains
        guard !domains.isEmpty else { throw CovertError.noAccessibleDomains }

        let encrypted = try encryptPayload(data, key: secretKey)
        let channel = preferredChannel ?? selectBestChannel(payloadSize: encrypted.count, domains: domains)
        let fragments = fragmentPayload(encrypted, maxSize: channel.maxPayloadSize, channel: channel, secretKey: secretKey)

        var sentFragments: [CovertFragmentResult] = []

        for fragment in fragments {
            // Human-like timing between requests
            await humanDelay()

            let result = try await sendFragment(fragment, channel: channel, domains: domains, secretKey: secretKey)
            sentFragments.append(result)
        }

        return CovertSendResult(
            messageId: fragments.first?.messageId ?? UUID().uuidString,
            channel: channel,
            fragmentsSent: sentFragments.count,
            totalSize: encrypted.count,
            success: sentFragments.allSatisfy { $0.success }
        )
    }

    // MARK: - Send Multi-Channel (Maximum Stealth)

    /// Split message across MULTIPLE different channels — defeats correlation analysis
    func sendMultiChannel(
        data: Data,
        secretKey: Data,
        channels: [CovertChannelType]? = nil,
        targetDomains: [String]? = nil
    ) async throws -> CovertSendResult {
        let domains = targetDomains ?? allowedDomains
        guard !domains.isEmpty else { throw CovertError.noAccessibleDomains }

        let useChannels = channels ?? recommendChannels(
            level: restrictionLevel,
            accessible: domains,
            dpi: lastAssessment?.dpiDetected ?? false
        )

        let encrypted = try encryptPayload(data, key: secretKey)
        let messageId = UUID().uuidString

        // Distribute payload across channels
        var allResults: [CovertFragmentResult] = []
        var offset = 0
        let totalFragments = useChannels.count

        for (idx, channel) in useChannels.enumerated() {
            let remaining = useChannels.count - idx
            let chunkSize: Int
            if idx == useChannels.count - 1 {
                chunkSize = encrypted.count - offset
            } else {
                chunkSize = min(channel.maxPayloadSize - 64, (encrypted.count - offset + remaining - 1) / remaining)
            }

            guard offset < encrypted.count else { break }
            let end = min(offset + chunkSize, encrypted.count)
            let chunk = Data(encrypted[offset..<end])
            offset = end

            let fragment = CovertFragment(
                messageId: messageId,
                fragmentIndex: idx,
                totalFragments: totalFragments,
                channel: channel,
                payload: chunk,
                checksum: computeHMAC(chunk, key: secretKey)
            )

            await humanDelay()
            let result = try await sendFragment(fragment, channel: channel, domains: domains, secretKey: secretKey)
            allResults.append(result)
        }

        return CovertSendResult(
            messageId: messageId,
            channel: .webMimicry,
            fragmentsSent: allResults.count,
            totalSize: encrypted.count,
            success: allResults.allSatisfy { $0.success }
        )
    }

    // MARK: - Receive Covert Message

    func receiveCovert(from carrier: CovertCarrier, secretKey: Data) throws -> Data? {
        let fragment: CovertFragment

        switch carrier {
        case .httpResponse(let response, let data):
            fragment = try networkStego.extractFromHTTPResponse(response: response, body: data, secretKey: secretKey)

        case .dnsResponse(let records):
            fragment = try networkStego.extractFromDNSResponse(records: records, secretKey: secretKey)

        case .image(let image):
            guard let raw = imageStego.decode(from: image, secretKey: secretKey) else { return nil }
            // Image stego decode returns the embedded data (JSON of CovertFragment)
            // Parse as fragment and reassemble (single fragment for direct image stego)
            let fragment = try JSONDecoder().decode(CovertFragment.self, from: raw)
            return try reassembleFragment(fragment, secretKey: secretKey)

        case .text(let text):
            fragment = try textStego.extract(from: text, secretKey: secretKey)

        case .rawFragment(let frag):
            fragment = frag
        }

        return try reassembleFragment(fragment, secretKey: secretKey)
    }

    // MARK: - Configuration

    func configure(allowedDomains: [String]) {
        self.allowedDomains = allowedDomains
    }

    func configureDomainFronts(_ fronts: [DomainFrontConfig]) {
        self.activeDomainFronts = fronts
    }

    func clearFragmentBuffer() {
        fragmentLock.lock()
        fragmentBuffer.removeAll()
        fragmentLock.unlock()
    }

    // MARK: - Private: Send Fragment via Channel

    private func sendFragment(
        _ fragment: CovertFragment,
        channel: CovertChannelType,
        domains: [String],
        secretKey: Data
    ) async throws -> CovertFragmentResult {
        let domain = domains.randomElement() ?? domains[0]

        switch channel {
        case .httpHeader:
            return try await networkStego.sendViaHTTPHeaders(fragment: fragment, targetDomain: domain, secretKey: secretKey)

        case .dnsSubdomain:
            return try await networkStego.sendViaDNS(fragment: fragment, baseDomain: domain, secretKey: secretKey)

        case .domainFront:
            guard let front = activeDomainFronts.first else {
                throw CovertError.noDomainFrontsConfigured
            }
            return try await networkStego.sendViaDomainFront(fragment: fragment, config: front, secretKey: secretKey)

        case .webMimicry:
            return try await networkStego.sendViaWebMimicry(fragment: fragment, targetDomain: domain, secretKey: secretKey)

        case .imageSteganography:
            return try await sendViaImageStego(fragment: fragment, secretKey: secretKey)

        case .textSteganography:
            return try await sendViaTextStego(fragment: fragment, secretKey: secretKey)
        }
    }

    // MARK: - Private: Channel Selection

    private func selectBestChannel(payloadSize: Int, domains: [String]) -> CovertChannelType {
        switch restrictionLevel {
        case .military:
            if !activeDomainFronts.isEmpty && payloadSize <= CovertChannelType.domainFront.maxPayloadSize {
                return .domainFront
            }
            if payloadSize <= CovertChannelType.webMimicry.maxPayloadSize { return .webMimicry }
            return .httpHeader

        case .whitelist:
            if payloadSize <= CovertChannelType.webMimicry.maxPayloadSize { return .webMimicry }
            return .httpHeader

        case .dpiActive:
            if payloadSize <= CovertChannelType.textSteganography.maxPayloadSize { return .textSteganography }
            return .imageSteganography

        case .filtered, .open:
            if payloadSize <= CovertChannelType.httpHeader.maxPayloadSize { return .httpHeader }
            return .imageSteganography
        }
    }

    private func recommendChannels(level: NetworkRestrictionLevel, accessible: [String], dpi: Bool) -> [CovertChannelType] {
        switch level {
        case .military:  return [.domainFront, .webMimicry, .textSteganography]
        case .whitelist: return [.webMimicry, .httpHeader, .textSteganography, .imageSteganography]
        case .dpiActive: return [.textSteganography, .imageSteganography, .domainFront]
        case .filtered:  return [.httpHeader, .imageSteganography, .webMimicry]
        case .open:      return CovertChannelType.allCases
        }
    }

    // MARK: - Private: Encryption

    private func encryptPayload(_ data: Data, key: Data) throws -> Data {
        let encKey = SymmetricKey(data: SHA256.hash(data: key + Data("COVERT_ENC_V2".utf8)))
        let nonce = AES.GCM.Nonce()
        guard let sealed = try? AES.GCM.seal(data, using: encKey, nonce: nonce) else {
            throw CovertError.encryptionFailed
        }
        var result = Data()
        result.append(Data(nonce))
        result.append(sealed.ciphertext)
        result.append(sealed.tag)
        return result
    }

    private func decryptPayload(_ data: Data, key: Data) throws -> Data {
        guard data.count > 28 else { throw CovertError.invalidPayload }
        let encKey = SymmetricKey(data: SHA256.hash(data: key + Data("COVERT_ENC_V2".utf8)))
        let nonceData = data[data.startIndex..<data.startIndex + 12]
        let ciphertext = data[(data.startIndex + 12)..<(data.endIndex - 16)]
        let tag = data[(data.endIndex - 16)...]
        let nonce = try AES.GCM.Nonce(data: nonceData)
        let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
        return try AES.GCM.open(box, using: encKey)
    }

    // MARK: - Private: Fragmentation

    private func fragmentPayload(_ data: Data, maxSize: Int, channel: CovertChannelType, secretKey: Data) -> [CovertFragment] {
        let messageId = UUID().uuidString
        let effectiveMax = max(maxSize - 64, 64)

        if data.count <= effectiveMax {
            return [CovertFragment(
                messageId: messageId, fragmentIndex: 0, totalFragments: 1,
                channel: channel, payload: data, checksum: computeHMAC(data, key: secretKey)
            )]
        }

        var fragments: [CovertFragment] = []
        var offset = 0
        let totalFragments = Int(ceil(Double(data.count) / Double(effectiveMax)))

        while offset < data.count {
            let end = min(offset + effectiveMax, data.count)
            let chunk = Data(data[offset..<end])
            fragments.append(CovertFragment(
                messageId: messageId, fragmentIndex: fragments.count, totalFragments: totalFragments,
                channel: channel, payload: chunk, checksum: computeHMAC(chunk, key: secretKey)
            ))
            offset = end
        }
        return fragments
    }

    // MARK: - Private: Reassembly

    private func reassembleFragment(_ fragment: CovertFragment, secretKey: Data) throws -> Data? {
        let expected = computeHMAC(fragment.payload, key: secretKey)
        guard expected == fragment.checksum else { throw CovertError.integrityCheckFailed }

        fragmentLock.lock()
        defer { fragmentLock.unlock() }

        if fragmentBuffer[fragment.messageId] == nil {
            fragmentBuffer[fragment.messageId] = [:]
        }
        fragmentBuffer[fragment.messageId]![fragment.fragmentIndex] = fragment

        let received = fragmentBuffer[fragment.messageId]!
        guard received.count == fragment.totalFragments else { return nil }

        var full = Data()
        for i in 0..<fragment.totalFragments {
            guard let f = received[i] else { throw CovertError.missingFragment(i) }
            full.append(f.payload)
        }

        fragmentBuffer.removeValue(forKey: fragment.messageId)
        return try decryptPayload(full, key: secretKey)
    }

    // MARK: - Private: Integrity

    private func computeHMAC(_ data: Data, key: Data) -> Data {
        let hmacKey = SymmetricKey(data: SHA256.hash(data: key + Data("FRAG_HMAC".utf8)))
        let mac = HMAC<SHA256>.authenticationCode(for: data, using: hmacKey)
        return Data(mac)
    }

    // MARK: - Private: Image/Text Stego Wrappers

    private func sendViaImageStego(fragment: CovertFragment, secretKey: Data) async throws -> CovertFragmentResult {
        let fragData = try JSONEncoder().encode(fragment)
        let carrier = imageStego.generateCarrierImage()
        guard let stegoImage = imageStego.encode(data: fragData, in: carrier, secretKey: secretKey) else {
            return CovertFragmentResult(success: false, channel: .imageSteganography)
        }
        CovertImageBuffer.shared.store(stegoImage, for: fragment.messageId)
        return CovertFragmentResult(success: true, channel: .imageSteganography)
    }

    private func sendViaTextStego(fragment: CovertFragment, secretKey: Data) async throws -> CovertFragmentResult {
        let fragData = try JSONEncoder().encode(fragment)
        let coverText = textStego.generateCoverText()
        guard let stegoText = textStego.embed(data: fragData, in: coverText, secretKey: secretKey) else {
            return CovertFragmentResult(success: false, channel: .textSteganography)
        }
        CovertTextBuffer.shared.store(stegoText, for: fragment.messageId)
        return CovertFragmentResult(success: true, channel: .textSteganography)
    }

    // MARK: - Private: Timing Obfuscation

    /// Adds human-like delay between requests (prevents burst detection)
    private func humanDelay() async {
        let elapsed = Date().timeIntervalSince(lastRequestTime)
        let minDelay = minInterRequestDelay
        if elapsed < minDelay {
            let wait = minDelay - elapsed + Double.random(in: 0...maxInterRequestDelay)
            try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
        } else {
            // Variable delay mimicking human browsing
            let wait = Double.random(in: 0.3...2.0)
            try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
        }
        lastRequestTime = Date()
    }

    // MARK: - Private: Domain Probing

    private let defaultProbeDomains = [
        "www.google.com", "www.youtube.com", "www.facebook.com",
        "www.instagram.com", "www.wikipedia.org", "www.amazon.com",
        "www.apple.com", "www.microsoft.com", "ajax.googleapis.com",
        "cdn.jsdelivr.net", "cloudflare.com", "fonts.googleapis.com",
        "api.github.com", "cdn.shopify.com"
    ]

    private func probeDomain(_ domain: String) async -> Bool {
        guard let url = URL(string: "https://\(domain)/") else { return false }
        var request = URLRequest(url: url)
        request.httpMethod = "HEAD"
        request.timeoutInterval = 5
        request.setValue(BrowserFingerprint.randomUserAgent(), forHTTPHeaderField: "User-Agent")
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            if let http = response as? HTTPURLResponse {
                return (200...499).contains(http.statusCode)
            }
            return false
        } catch {
            return false
        }
    }

    /// Detect DPI by sending canary patterns
    private func detectDPI(using accessible: [String]) async -> Bool {
        guard let domain = accessible.first,
              let url = URL(string: "https://\(domain)/") else { return false }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 5
        // Canary: base64-like random string that aggressive DPI might flag
        let canary = Data((0..<32).map { _ in UInt8.random(in: 0...255) }).base64EncodedString()
        request.setValue(canary, forHTTPHeaderField: "X-Request-ID")
        do {
            let _ = try await URLSession.shared.data(for: request)
            return false
        } catch {
            return true
        }
    }
}

// MARK: - Browser Fingerprint Generator

struct BrowserFingerprint {
    static func randomUserAgent() -> String {
        let agents = [
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/131.0.6778.73 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (iPad; CPU OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.81 Mobile Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"
        ]
        return agents.randomElement()!
    }

    static func randomAcceptLanguage() -> String {
        let langs = [
            "en-US,en;q=0.9", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7",
            "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7", "fa-IR,fa;q=0.9,en;q=0.8",
            "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7", "ar-SA,ar;q=0.9,en;q=0.8",
            "uk-UA,uk;q=0.9,ru;q=0.8,en;q=0.7", "tr-TR,tr;q=0.9,en;q=0.8"
        ]
        return langs.randomElement()!
    }

    static func standardHeaders() -> [String: String] {
        return [
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Encoding": "gzip, deflate, br",
            "Accept-Language": randomAcceptLanguage(),
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "DNT": "1",
            "Sec-Fetch-Dest": "document",
            "Sec-Fetch-Mode": "navigate",
            "Sec-Fetch-Site": "none",
            "Sec-Fetch-User": "?1",
            "Upgrade-Insecure-Requests": "1",
            "User-Agent": randomUserAgent()
        ]
    }
}

// MARK: - Buffers for Stego Output

final class CovertImageBuffer {
    static let shared = CovertImageBuffer()
    private var images: [String: UIImage] = [:]
    private let lock = NSLock()
    private init() {}

    func store(_ image: UIImage, for messageId: String) {
        lock.lock(); images[messageId] = image; lock.unlock()
    }

    func retrieve(for messageId: String) -> UIImage? {
        lock.lock(); defer { lock.unlock() }
        return images.removeValue(forKey: messageId)
    }
}

final class CovertTextBuffer {
    static let shared = CovertTextBuffer()
    private var texts: [String: String] = [:]
    private let lock = NSLock()
    private init() {}

    func store(_ text: String, for messageId: String) {
        lock.lock(); texts[messageId] = text; lock.unlock()
    }

    func retrieve(for messageId: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        return texts.removeValue(forKey: messageId)
    }
}
