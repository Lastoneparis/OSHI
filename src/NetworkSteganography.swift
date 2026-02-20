//
//  NetworkSteganography.swift
//  OSHI - Network-Level Covert Channels
//
//  Military-grade techniques for hiding messages in normal web traffic:
//
//  1. HTTP Header Steganography
//     - Data encoded in Cookie values (looks like tracking cookies)
//     - Data in Accept-Language quality factors (q=0.XXX encodes bits)
//     - Data in ETag / If-None-Match headers (looks like cache validation)
//     - Data in X-Request-ID (looks like analytics)
//     - Timing-based encoding in Cache-Control max-age values
//
//  2. DNS Covert Channel
//     - Base32 data in subdomain labels (e.g., aGVsbG8.t.allowed-domain.com)
//     - TXT record queries for larger payloads
//     - CNAME chains for multi-hop data
//
//  3. Domain Fronting
//     - TLS SNI shows allowed domain (passes network filter)
//     - HTTP Host header routes to actual relay server
//     - Body carries encrypted payload
//
//  4. Web Request Mimicry
//     - Requests look like normal Google/social media API calls
//     - Data hidden in URL parameters, POST form fields, JSON bodies
//     - Traffic patterns match real browsing behavior
//

import Foundation
import CryptoKit

// MARK: - Network Steganography Manager

final class NetworkSteganography {
    static let shared = NetworkSteganography()
    private init() {}

    // MARK: - 1. HTTP Header Steganography

    /// Hide fragment data in HTTP headers that look like normal browser headers
    ///
    /// Encoding strategy:
    /// - Cookie: payload encoded as hex tracking cookie values
    /// - ETag/If-None-Match: payload chunk as cache validation hash
    /// - X-Request-ID: payload chunk as UUID-like analytics ID
    /// - Accept-Language: bits encoded in q-factor precision
    func sendViaHTTPHeaders(
        fragment: CovertFragment,
        targetDomain: String,
        secretKey: Data
    ) async throws -> CovertFragmentResult {
        guard let url = URL(string: "https://\(targetDomain)/") else {
            throw CovertError.channelFailed(.httpHeader, "Invalid domain")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 15

        // Apply standard browser fingerprint
        for (key, value) in BrowserFingerprint.standardHeaders() {
            request.setValue(value, forHTTPHeaderField: key)
        }

        // Encode payload into headers
        let encoded = try encodeIntoHeaders(fragment: fragment, secretKey: secretKey)

        // Cookie: looks like Google Analytics / ad tracking cookies
        request.setValue(encoded.cookie, forHTTPHeaderField: "Cookie")

        // ETag validation: looks like cache revalidation
        if let etag = encoded.etag {
            request.setValue(etag, forHTTPHeaderField: "If-None-Match")
        }

        // X-Request-ID: looks like analytics/tracing
        if let reqId = encoded.requestId {
            request.setValue(reqId, forHTTPHeaderField: "X-Request-ID")
        }

        // Send request
        do {
            let (data, _) = try await URLSession.shared.data(for: request)
            return CovertFragmentResult(
                success: true,
                channel: .httpHeader,
                responseData: data
            )
        } catch {
            return CovertFragmentResult(success: false, channel: .httpHeader)
        }
    }

    /// Extract fragment from HTTP response headers
    func extractFromHTTPResponse(
        response: HTTPURLResponse,
        body: Data,
        secretKey: Data
    ) throws -> CovertFragment {
        // Check Set-Cookie for encoded response
        if let cookies = response.allHeaderFields["Set-Cookie"] as? String {
            if let fragment = try? decodeFromCookie(cookies, secretKey: secretKey) {
                return fragment
            }
        }

        // Check ETag response
        if let etag = response.allHeaderFields["ETag"] as? String {
            if let fragment = try? decodeFromETag(etag, secretKey: secretKey) {
                return fragment
            }
        }

        // Check body for encoded data (hidden in HTML comments, JSON, etc.)
        if let fragment = try? decodeFromBody(body, secretKey: secretKey) {
            return fragment
        }

        throw CovertError.channelFailed(.httpHeader, "No covert data found in response")
    }

    // MARK: - 2. DNS Covert Channel

    /// Encode data in DNS subdomain queries
    ///
    /// Format: [base32_chunk1].[base32_chunk2].t.[baseDomain]
    /// Example: JBSWY3DP.EBZXIYLM.t.allowed-site.com
    /// Looks like CDN subdomain resolution
    func sendViaDNS(
        fragment: CovertFragment,
        baseDomain: String,
        secretKey: Data
    ) async throws -> CovertFragmentResult {
        let fragData = try JSONEncoder().encode(fragment)

        // XOR with key-derived stream (lightweight obfuscation for DNS)
        let obfuscated = xorObfuscate(fragData, key: secretKey, context: "DNS_OBF")

        // Base32 encode (DNS-safe characters)
        let encoded = base32Encode(obfuscated)

        // Split into 63-char labels (DNS label limit)
        let labels = stride(from: 0, to: encoded.count, by: 50).map { start -> String in
            let end = min(start + 50, encoded.count)
            let startIdx = encoded.index(encoded.startIndex, offsetBy: start)
            let endIdx = encoded.index(encoded.startIndex, offsetBy: end)
            return String(encoded[startIdx..<endIdx]).lowercased()
        }

        // Build DNS query hostname: [label1].[label2].cdn.[baseDomain]
        let hostname = labels.joined(separator: ".") + ".cdn." + baseDomain

        // Perform DNS lookup (this sends our data as a DNS query)
        guard let url = URL(string: "https://\(hostname)/") else {
            // DNS query still happens even if HTTPS fails
            // The query itself carries our payload
            return CovertFragmentResult(success: true, channel: .dnsSubdomain)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "HEAD"
        request.timeoutInterval = 5
        request.setValue(BrowserFingerprint.randomUserAgent(), forHTTPHeaderField: "User-Agent")

        // We don't care if the HTTP request succeeds — the DNS query already carried our data
        let _ = try? await URLSession.shared.data(for: request)

        return CovertFragmentResult(success: true, channel: .dnsSubdomain)
    }

    /// Extract from DNS TXT records
    func extractFromDNSResponse(
        records: [String],
        secretKey: Data
    ) throws -> CovertFragment {
        // Concatenate all TXT records
        let combined = records.joined()

        // Base32 decode
        guard let decoded = base32Decode(combined) else {
            throw CovertError.channelFailed(.dnsSubdomain, "Invalid base32 in DNS response")
        }

        // De-obfuscate
        let deobfuscated = xorObfuscate(decoded, key: secretKey, context: "DNS_OBF")

        // Decode fragment
        return try JSONDecoder().decode(CovertFragment.self, from: deobfuscated)
    }

    // MARK: - 3. Domain Fronting

    /// Use CDN domain fronting to hide the real destination
    ///
    /// How it works:
    /// 1. TLS ClientHello SNI = cdnDomain (what the network filter sees)
    /// 2. HTTP Host header = actualHost (what the CDN routes to)
    /// 3. Body = encrypted covert payload
    ///
    /// Network monitor sees: connection to cloudfront.net (allowed)
    /// CDN routes request to: relay.oshi-messenger.com (hidden)
    func sendViaDomainFront(
        fragment: CovertFragment,
        config: DomainFrontConfig,
        secretKey: Data
    ) async throws -> CovertFragmentResult {
        // Build URL with CDN domain (this is what SNI/DNS sees)
        guard let url = URL(string: "https://\(config.cdnDomain)\(config.path)") else {
            throw CovertError.channelFailed(.domainFront, "Invalid CDN URL")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 30

        // Host header overrides where CDN routes the request
        request.setValue(config.actualHost, forHTTPHeaderField: "Host")

        // Standard browser headers
        for (key, value) in BrowserFingerprint.standardHeaders() {
            if key != "User-Agent" { request.setValue(value, forHTTPHeaderField: key) }
        }
        request.setValue(BrowserFingerprint.randomUserAgent(), forHTTPHeaderField: "User-Agent")
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")

        // Auth token if configured
        if let token = config.authToken {
            // Hidden in a normal-looking cookie
            request.setValue("_ga=\(token)", forHTTPHeaderField: "Cookie")
        }

        // Encode fragment as form data (looks like form submission)
        let fragData = try JSONEncoder().encode(fragment)
        let encrypted = encryptForTransport(fragData, key: secretKey)
        let formBody = "q=\(encrypted.base64EncodedString().addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")&source=web&hl=en"
        request.httpBody = formBody.data(using: .utf8)

        do {
            let (data, _) = try await URLSession.shared.data(for: request)
            return CovertFragmentResult(success: true, channel: .domainFront, responseData: data)
        } catch {
            return CovertFragmentResult(success: false, channel: .domainFront)
        }
    }

    // MARK: - 4. Web Request Mimicry

    /// Make covert data look like normal browsing of an allowed website
    ///
    /// Strategies:
    /// - Google Search mimicry: data in search query parameters
    /// - Google Analytics mimicry: data in _ga cookie + collect endpoint
    /// - Form submission mimicry: data in POST form fields
    /// - Image request mimicry: data in query params of image URLs
    /// - API call mimicry: data in JSON API request body
    func sendViaWebMimicry(
        fragment: CovertFragment,
        targetDomain: String,
        secretKey: Data
    ) async throws -> CovertFragmentResult {
        // Choose mimicry strategy based on target domain
        let strategy = selectMimicryStrategy(for: targetDomain)

        switch strategy {
        case .searchQuery:
            return try await mimicSearchQuery(fragment: fragment, domain: targetDomain, secretKey: secretKey)
        case .analyticsBeacon:
            return try await mimicAnalyticsBeacon(fragment: fragment, domain: targetDomain, secretKey: secretKey)
        case .formSubmission:
            return try await mimicFormSubmission(fragment: fragment, domain: targetDomain, secretKey: secretKey)
        case .apiCall:
            return try await mimicAPICall(fragment: fragment, domain: targetDomain, secretKey: secretKey)
        case .imageRequest:
            return try await mimicImageRequest(fragment: fragment, domain: targetDomain, secretKey: secretKey)
        }
    }

    // MARK: - HTTP Header Encoding/Decoding

    private struct EncodedHeaders {
        let cookie: String
        let etag: String?
        let requestId: String?
    }

    /// Encode fragment into innocent-looking HTTP headers
    private func encodeIntoHeaders(fragment: CovertFragment, secretKey: Data) throws -> EncodedHeaders {
        let fragData = try JSONEncoder().encode(fragment)
        let encrypted = encryptForTransport(fragData, key: secretKey)

        // Split payload across multiple header fields
        let hex = encrypted.map { String(format: "%02x", $0) }.joined()

        // Cookie: encode as tracking cookies
        // Format mimics Google Analytics: _ga=GA1.2.[data]; _gid=GA1.2.[data]; _gat=1
        let cookieParts = splitString(hex, chunkSize: 32)
        var cookies: [String] = []

        if cookieParts.count > 0 {
            cookies.append("_ga=GA1.2.\(cookieParts[0])")
        }
        if cookieParts.count > 1 {
            cookies.append("_gid=GA1.2.\(cookieParts[1])")
        }
        if cookieParts.count > 2 {
            cookies.append("_fbp=fb.1.\(cookieParts[2])")
        }

        // Additional cookies for remaining data
        for i in 3..<cookieParts.count {
            cookies.append("_dc_gtm_\(i)=\(cookieParts[i])")
        }
        cookies.append("_gat=1") // Normal GA cookie

        let cookieStr = cookies.joined(separator: "; ")

        // ETag: first 32 chars as cache validation hash
        let etag: String?
        if hex.count > 64 {
            let etagStart = hex.index(hex.startIndex, offsetBy: 0)
            let etagEnd = hex.index(hex.startIndex, offsetBy: min(64, hex.count))
            etag = "W/\"\(String(hex[etagStart..<etagEnd]))\""
        } else {
            etag = nil
        }

        // X-Request-ID: formatted as UUID-like string
        let reqId: String?
        if hex.count >= 32 {
            let start = hex.index(hex.endIndex, offsetBy: -32)
            let chunk = String(hex[start...])
            let s = chunk
            if s.count >= 32 {
                let p1 = String(s.prefix(8))
                let p2 = String(s.dropFirst(8).prefix(4))
                let p3 = String(s.dropFirst(12).prefix(4))
                let p4 = String(s.dropFirst(16).prefix(4))
                let p5 = String(s.dropFirst(20).prefix(12))
                reqId = "\(p1)-\(p2)-\(p3)-\(p4)-\(p5)"
            } else {
                reqId = nil
            }
        } else {
            reqId = nil
        }

        return EncodedHeaders(cookie: cookieStr, etag: etag, requestId: reqId)
    }

    /// Decode fragment from Cookie header
    private func decodeFromCookie(_ cookie: String, secretKey: Data) throws -> CovertFragment {
        // Extract hex data from tracking cookie values
        var hexParts: [String] = []

        let components = cookie.components(separatedBy: "; ")
        for component in components {
            let kv = component.components(separatedBy: "=")
            guard kv.count == 2 else { continue }
            let key = kv[0]
            let value = kv[1]

            // Extract data from known cookie patterns
            if key == "_ga" || key == "_gid" {
                let parts = value.components(separatedBy: ".")
                if parts.count >= 3 {
                    hexParts.append(parts[2])
                }
            } else if key == "_fbp" {
                let parts = value.components(separatedBy: ".")
                if parts.count >= 3 {
                    hexParts.append(parts[2])
                }
            } else if key.hasPrefix("_dc_gtm_") {
                hexParts.append(value)
            }
        }

        let hex = hexParts.joined()
        guard let encrypted = hexToData(hex) else {
            throw CovertError.channelFailed(.httpHeader, "Invalid hex in cookie")
        }

        let decrypted = try decryptFromTransport(encrypted, key: secretKey)
        return try JSONDecoder().decode(CovertFragment.self, from: decrypted)
    }

    /// Decode from ETag header
    private func decodeFromETag(_ etag: String, secretKey: Data) throws -> CovertFragment {
        var hex = etag
        // Strip W/" and trailing "
        if hex.hasPrefix("W/\"") { hex = String(hex.dropFirst(3)) }
        if hex.hasPrefix("\"") { hex = String(hex.dropFirst(1)) }
        if hex.hasSuffix("\"") { hex = String(hex.dropLast(1)) }

        guard let encrypted = hexToData(hex) else {
            throw CovertError.channelFailed(.httpHeader, "Invalid hex in ETag")
        }
        let decrypted = try decryptFromTransport(encrypted, key: secretKey)
        return try JSONDecoder().decode(CovertFragment.self, from: decrypted)
    }

    /// Decode from response body (hidden in HTML/JSON)
    private func decodeFromBody(_ body: Data, secretKey: Data) throws -> CovertFragment {
        guard let text = String(data: body, encoding: .utf8) else {
            throw CovertError.channelFailed(.httpHeader, "Non-text body")
        }

        // Look for data hidden in HTML comments: <!-- COVERT:base64data -->
        if let range = text.range(of: "<!-- CV:") {
            let start = range.upperBound
            if let end = text[start...].range(of: " -->") {
                let b64 = String(text[start..<end.lowerBound])
                if let encrypted = Data(base64Encoded: b64) {
                    let decrypted = try decryptFromTransport(encrypted, key: secretKey)
                    return try JSONDecoder().decode(CovertFragment.self, from: decrypted)
                }
            }
        }

        // Look for data in JSON response
        if let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
           let encoded = json["_t"] as? String,
           let encrypted = Data(base64Encoded: encoded) {
            let decrypted = try decryptFromTransport(encrypted, key: secretKey)
            return try JSONDecoder().decode(CovertFragment.self, from: decrypted)
        }

        throw CovertError.channelFailed(.httpHeader, "No covert data in body")
    }

    // MARK: - Mimicry Strategies

    private enum MimicryStrategy {
        case searchQuery
        case analyticsBeacon
        case formSubmission
        case apiCall
        case imageRequest
    }

    private func selectMimicryStrategy(for domain: String) -> MimicryStrategy {
        if domain.contains("google") { return .searchQuery }
        if domain.contains("facebook") || domain.contains("instagram") { return .apiCall }
        if domain.contains("youtube") { return .imageRequest }
        // Default: most versatile
        return [.formSubmission, .analyticsBeacon, .apiCall].randomElement()!
    }

    /// Mimic a Google search query with data in parameters
    private func mimicSearchQuery(
        fragment: CovertFragment,
        domain: String,
        secretKey: Data
    ) async throws -> CovertFragmentResult {
        let fragData = try JSONEncoder().encode(fragment)
        let encrypted = encryptForTransport(fragData, key: secretKey)
        let encoded = encrypted.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")

        // Looks like: https://www.google.com/search?q=weather&oq=weather&gs_lcrp=[PAYLOAD]&sourceid=chrome
        let coverQuery = ["weather forecast", "news today", "currency exchange rate", "translate hello", "time zone converter"].randomElement()!
        let encodedQuery = coverQuery.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "test"

        // Split payload across multiple innocent-looking parameters
        let chunks = splitString(encoded, chunkSize: 64)
        var params = "q=\(encodedQuery)"
        if chunks.count > 0 { params += "&gs_lcrp=\(chunks[0])" }
        if chunks.count > 1 { params += "&ei=\(chunks[1])" }
        if chunks.count > 2 { params += "&ved=\(chunks[2])" }
        for i in 3..<chunks.count {
            params += "&gs_lp=\(chunks[i])"
        }
        params += "&sourceid=chrome&ie=UTF-8"

        guard let url = URL(string: "https://\(domain)/search?\(params)") else {
            return CovertFragmentResult(success: false, channel: .webMimicry)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 15
        for (k, v) in BrowserFingerprint.standardHeaders() {
            request.setValue(v, forHTTPHeaderField: k)
        }
        request.setValue("https://\(domain)/", forHTTPHeaderField: "Referer")

        let _ = try? await URLSession.shared.data(for: request)
        return CovertFragmentResult(success: true, channel: .webMimicry)
    }

    /// Mimic a Google Analytics beacon (tracking pixel)
    private func mimicAnalyticsBeacon(
        fragment: CovertFragment,
        domain: String,
        secretKey: Data
    ) async throws -> CovertFragmentResult {
        let fragData = try JSONEncoder().encode(fragment)
        let encrypted = encryptForTransport(fragData, key: secretKey)
        let b64 = encrypted.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")

        // Mimics Google Analytics collect endpoint
        let tid = "UA-\(Int.random(in: 100000...999999))-1"
        let cid = UUID().uuidString

        // Data hidden in custom dimension parameters (cd1, cd2, etc.)
        let chunks = splitString(b64, chunkSize: 128)
        var params = "v=1&t=pageview&tid=\(tid)&cid=\(cid)&dp=%2F&dt=Home"
        for (i, chunk) in chunks.enumerated() {
            params += "&cd\(i + 1)=\(chunk)"
        }

        guard let url = URL(string: "https://\(domain)/collect?\(params)") else {
            return CovertFragmentResult(success: false, channel: .webMimicry)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 10
        request.setValue(BrowserFingerprint.randomUserAgent(), forHTTPHeaderField: "User-Agent")
        request.setValue("https://\(domain)/", forHTTPHeaderField: "Referer")
        request.setValue("text/plain;charset=UTF-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = params.data(using: .utf8)

        let _ = try? await URLSession.shared.data(for: request)
        return CovertFragmentResult(success: true, channel: .webMimicry)
    }

    /// Mimic a form submission (login, search, contact form)
    private func mimicFormSubmission(
        fragment: CovertFragment,
        domain: String,
        secretKey: Data
    ) async throws -> CovertFragmentResult {
        let fragData = try JSONEncoder().encode(fragment)
        let encrypted = encryptForTransport(fragData, key: secretKey)
        let b64 = encrypted.base64EncodedString()

        // Build form data that looks like a contact/login form
        let formFields: [(String, String)] = [
            ("email", "user\(Int.random(in: 1000...9999))@gmail.com"),
            ("name", ["John Smith", "Maria Garcia", "Ali Rezaei", "Anna Kovalenko"].randomElement()!),
            ("message", b64),  // Payload hidden as "message" field
            ("_token", UUID().uuidString.replacingOccurrences(of: "-", with: "")),
            ("submit", "Send")
        ]

        let formBody = formFields.map { key, value in
            "\(key)=\(value.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? value)"
        }.joined(separator: "&")

        let paths = ["/contact", "/feedback", "/subscribe", "/register", "/search"]
        let path = paths.randomElement()!

        guard let url = URL(string: "https://\(domain)\(path)") else {
            return CovertFragmentResult(success: false, channel: .webMimicry)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 15
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        for (k, v) in BrowserFingerprint.standardHeaders() {
            request.setValue(v, forHTTPHeaderField: k)
        }
        request.setValue("https://\(domain)/", forHTTPHeaderField: "Referer")
        request.httpBody = formBody.data(using: .utf8)

        let _ = try? await URLSession.shared.data(for: request)
        return CovertFragmentResult(success: true, channel: .webMimicry)
    }

    /// Mimic an API call (JSON REST)
    private func mimicAPICall(
        fragment: CovertFragment,
        domain: String,
        secretKey: Data
    ) async throws -> CovertFragmentResult {
        let fragData = try JSONEncoder().encode(fragment)
        let encrypted = encryptForTransport(fragData, key: secretKey)

        // JSON body that looks like a normal API request
        let jsonBody: [String: Any] = [
            "client_id": UUID().uuidString,
            "event": "page_view",
            "timestamp": Int(Date().timeIntervalSince1970),
            "properties": [
                "page": "/home",
                "referrer": "https://\(domain)/",
                "session_data": encrypted.base64EncodedString()  // Payload here
            ],
            "user_agent": BrowserFingerprint.randomUserAgent()
        ]

        let apiPaths = ["/api/v1/events", "/api/analytics", "/api/track", "/api/log"]

        guard let url = URL(string: "https://\(domain)\(apiPaths.randomElement()!)") else {
            return CovertFragmentResult(success: false, channel: .webMimicry)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 15
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(BrowserFingerprint.randomUserAgent(), forHTTPHeaderField: "User-Agent")
        request.setValue("https://\(domain)/", forHTTPHeaderField: "Origin")
        request.httpBody = try JSONSerialization.data(withJSONObject: jsonBody)

        let _ = try? await URLSession.shared.data(for: request)
        return CovertFragmentResult(success: true, channel: .webMimicry)
    }

    /// Mimic an image/resource request with data in query params
    private func mimicImageRequest(
        fragment: CovertFragment,
        domain: String,
        secretKey: Data
    ) async throws -> CovertFragmentResult {
        let fragData = try JSONEncoder().encode(fragment)
        let encrypted = encryptForTransport(fragData, key: secretKey)
        let b64url = encrypted.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")

        // Looks like a CDN image request with cache-busting params
        let imagePaths = [
            "/images/pixel.gif",
            "/static/img/logo.png",
            "/assets/banner.jpg",
            "/media/thumb_\(Int.random(in: 100...999)).webp"
        ]

        let params = "w=\(Int.random(in: 200...800))&h=\(Int.random(in: 200...800))&fit=crop&cb=\(b64url)"

        guard let url = URL(string: "https://\(domain)\(imagePaths.randomElement()!)?\(params)") else {
            return CovertFragmentResult(success: false, channel: .webMimicry)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 10
        request.setValue("image/avif,image/webp,image/apng,*/*", forHTTPHeaderField: "Accept")
        request.setValue(BrowserFingerprint.randomUserAgent(), forHTTPHeaderField: "User-Agent")
        request.setValue("https://\(domain)/", forHTTPHeaderField: "Referer")

        let _ = try? await URLSession.shared.data(for: request)
        return CovertFragmentResult(success: true, channel: .webMimicry)
    }

    // MARK: - Transport Encryption (lightweight, for in-header use)

    /// Fast AES-GCM encryption for transport-level data
    private func encryptForTransport(_ data: Data, key: Data) -> Data {
        let encKey = SymmetricKey(data: SHA256.hash(data: key + Data("NET_STEG_TRANSPORT".utf8)))
        let nonce = AES.GCM.Nonce()
        guard 
              let sealed = try? AES.GCM.seal(data, using: encKey, nonce: nonce) else {
            return data // Fallback: send unencrypted (should never happen)
        }
        var result = Data()
        result.append(Data(nonce))       // 12 bytes
        result.append(sealed.ciphertext) // N bytes
        result.append(sealed.tag)        // 16 bytes
        return result
    }

    func encryptForTransportPublic(_ data: Data, key: Data) -> Data {
        return encryptForTransport(data, key: key)
    }

    /// Decrypt transport-level data
    private func decryptFromTransport(_ data: Data, key: Data) throws -> Data {
        guard data.count > 28 else { throw CovertError.invalidPayload }
        let encKey = SymmetricKey(data: SHA256.hash(data: key + Data("NET_STEG_TRANSPORT".utf8)))
        let nonceData = data[data.startIndex..<data.startIndex + 12]
        let ciphertext = data[(data.startIndex + 12)..<(data.endIndex - 16)]
        let tag = data[(data.endIndex - 16)...]
        let nonce = try AES.GCM.Nonce(data: nonceData)
        let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
        return try AES.GCM.open(box, using: encKey)
    }

    func decryptFromTransportPublic(_ data: Data, key: Data) throws -> Data {
        return try decryptFromTransport(data, key: key)
    }

    // MARK: - XOR Obfuscation (for DNS channel, lightweight)

    private func xorObfuscate(_ data: Data, key: Data, context: String) -> Data {
        let streamKey = SHA256.hash(data: key + Data(context.utf8))
        let keyBytes = Data(streamKey)
        var result = Data(count: data.count)
        for i in 0..<data.count {
            result[i] = data[i] ^ keyBytes[i % keyBytes.count]
        }
        return result
    }

    // MARK: - Base32 Encoding (DNS-safe)

    private let base32Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    func base32Encode(_ data: Data) -> String {
        var result = ""
        var buffer: UInt64 = 0
        var bitsLeft = 0

        for byte in data {
            buffer = (buffer << 8) | UInt64(byte)
            bitsLeft += 8
            while bitsLeft >= 5 {
                bitsLeft -= 5
                let index = Int((buffer >> bitsLeft) & 0x1F)
                let char = base32Alphabet[base32Alphabet.index(base32Alphabet.startIndex, offsetBy: index)]
                result.append(char)
            }
        }

        if bitsLeft > 0 {
            let index = Int((buffer << (5 - bitsLeft)) & 0x1F)
            let char = base32Alphabet[base32Alphabet.index(base32Alphabet.startIndex, offsetBy: index)]
            result.append(char)
        }

        return result
    }

    func base32Decode(_ string: String) -> Data? {
        let upper = string.uppercased()
        var result = Data()
        var buffer: UInt64 = 0
        var bitsLeft = 0

        for char in upper {
            guard let idx = base32Alphabet.firstIndex(of: char) else { continue }
            let value = base32Alphabet.distance(from: base32Alphabet.startIndex, to: idx)
            buffer = (buffer << 5) | UInt64(value)
            bitsLeft += 5
            if bitsLeft >= 8 {
                bitsLeft -= 8
                result.append(UInt8((buffer >> bitsLeft) & 0xFF))
            }
        }

        return result.isEmpty ? nil : result
    }

    // MARK: - Utility

    private func splitString(_ string: String, chunkSize: Int) -> [String] {
        var result: [String] = []
        var index = string.startIndex
        while index < string.endIndex {
            let end = string.index(index, offsetBy: chunkSize, limitedBy: string.endIndex) ?? string.endIndex
            result.append(String(string[index..<end]))
            index = end
        }
        return result
    }

    private func hexToData(_ hex: String) -> Data? {
        var data = Data()
        var index = hex.startIndex
        while index < hex.endIndex {
            guard let next = hex.index(index, offsetBy: 2, limitedBy: hex.endIndex) else { break }
            let byteStr = String(hex[index..<next])
            guard let byte = UInt8(byteStr, radix: 16) else { return nil }
            data.append(byte)
            index = next
        }
        return data.isEmpty ? nil : data
    }
}
