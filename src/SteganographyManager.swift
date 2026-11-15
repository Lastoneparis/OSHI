//
//  SteganographyManager.swift
//  OSHI - Experimental image carrier codec (v2.0)
//
//  UPGRADES from v1.0:
//  This is a manual, lossless-carrier experiment. Pixel LSB payloads are
//  destroyed by JPEG/WebP recompression, resizing and image optimization. The
//  AES-GCM tag makes those transformations fail closed; it does not make them
//  resilient or undetectable. It is never used by the message transport.
//
//  Encryption: AES-256-GCM with HMAC-SHA256 key-derived PRNG
//  Scattering: keyed Fisher-Yates shuffle weighted by a distortion cost map
//  Capacity: at most 1% of RGB slots. This is deliberately conservative; it
//  reduces visual/statistical distortion but is not an undetectability claim.
//

import UIKit
import CryptoKit
import SwiftUI

// MARK: - Key-Seeded PRNG (deterministic, reproducible)

private struct KeyPRNG {
    private var state: Data
    private var counter: UInt64 = 0

    init(seed: Data) {
        let key = SymmetricKey(data: SHA256.hash(data: seed))
        let mac = HMAC<SHA256>.authenticationCode(for: Data("STEG_INIT_V2".utf8), using: key)
        self.state = Data(mac)
    }

    mutating func nextUInt32() -> UInt32 {
        counter += 1
        var input = state
        withUnsafeBytes(of: counter) { input.append(contentsOf: $0) }
        let key = SymmetricKey(data: state)
        let mac = HMAC<SHA256>.authenticationCode(for: input, using: key)
        self.state = Data(mac)
        return state.withUnsafeBytes { $0.load(as: UInt32.self) }
    }

    mutating func nextByte() -> UInt8 {
        return UInt8(nextUInt32() & 0xFF)
    }

    mutating func nextDouble() -> Double {
        return Double(nextUInt32()) / Double(UInt32.max)
    }
}

// MARK: - Distortion Cost Map

/// Per-pixel cost of modification — lower cost = safer to modify
private struct DistortionMap {
    let width: Int
    let height: Int
    var costs: [Float]  // One cost per pixel (R, G, B averaged)

    init(width: Int, height: Int) {
        self.width = width
        self.height = height
        self.costs = [Float](repeating: 1.0, count: width * height)
    }

    subscript(x: Int, y: Int) -> Float {
        get { costs[y * width + x] }
        set { costs[y * width + x] = newValue }
    }
}

// MARK: - Experimental image carrier engine

final class SteganographyManager {
    static let shared = SteganographyManager()
    private init() {}

    // The position generator intentionally works in memory. Bound its input so
    // a maliciously large image cannot turn a local experiment into a memory or
    // CPU denial of service. 1024×1024 generated carriers remain supported.
    private let minimumCarrierSide = 64
    private let maximumCarrierPixels = 1_500_000

    /// Upper bound on modified candidate slots. With randomized ciphertext an
    /// expected half of these slots change, so the expected whole-image LSB
    /// modification rate is approximately 0.5% before texture selection.
    private let baseEmbeddingRate: Double = 0.01

    /// Minimum distortion cost to embed (skip very smooth areas)
    private let minCostThreshold: Float = 0.15

    /// A carrier must offer enough textured RGB slots that selection never needs
    /// to fall back to broad smooth areas. The 2× reserve leaves the keyed
    /// shuffle a meaningful choice instead of treating texture as a soft hint.
    private let texturedCostCeiling: Float = 0.85
    private let textureReserveMultiplier = 2

    // MARK: - Encode

    func encode(data: Data, in carrierImage: UIImage, secretKey: Data) -> UIImage? {
        guard let cgImage = carrierImage.cgImage else { return nil }

        let width = cgImage.width
        let height = cgImage.height
        guard isSupportedCarrier(width: width, height: height) else { return nil }
        let totalSlots = width * height * 3
        let baseCapacity = Int(Double(totalSlots) * baseEmbeddingRate) / 8

        let overhead = 32 // 4 (len) + 12 (nonce) + 16 (tag)
        let maxDataSize = baseCapacity - overhead
        guard data.count <= maxDataSize, maxDataSize > 0 else { return nil }

        // Step 1: Encrypt with AES-256-GCM
        let encKey = SymmetricKey(data: SHA256.hash(data: secretKey + Data("STEG_ENC_V2".utf8)))
        let nonce = AES.GCM.Nonce()
        guard let sealed = try? AES.GCM.seal(data, using: encKey, nonce: nonce) else { return nil }

        // Step 2: Build payload
        var payload = Data()
        var len = UInt32(sealed.ciphertext.count).bigEndian
        payload.append(Data(bytes: &len, count: 4))
        payload.append(Data(nonce))
        payload.append(sealed.ciphertext)
        payload.append(sealed.tag)

        // Step 3: Get a normalized pixel buffer.
        let bytesPerPixel = 4
        let bytesPerRow = bytesPerPixel * width
        var pixelData = [UInt8](repeating: 0, count: height * bytesPerRow)
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: &pixelData, width: width, height: height,
            bitsPerComponent: 8, bytesPerRow: bytesPerRow,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))

        // The carrier fingerprint ignores LSBs, so it is identical when the
        // recipient decodes the lossless stego image. It binds placement to this
        // carrier instead of reusing one secret-derived coordinate sequence over
        // every image exchanged by the same pair.
        let carrierSeed = secretKey + carrierFingerprint(pixels: pixelData, bytesPerPixel: bytesPerPixel)

        // Step 4: Pad to a carrier-specific fixed size.
        let targetSize = baseCapacity
        if payload.count < targetSize {
            var rng = KeyPRNG(seed: carrierSeed + Data("PAD_V3".utf8))
            let paddingSize = targetSize - payload.count
            var padding = Data(count: paddingSize)
            for i in 0..<paddingSize { padding[i] = rng.nextByte() }
            payload.append(padding)
        }

        // Step 5: Compute distortion cost map (HUGO-inspired)
        let costMap = computeDistortionMap(pixels: pixelData, width: width, height: height, bytesPerPixel: bytesPerPixel)
        guard hasAdequateTexture(costMap: costMap, requiredSlots: baseCapacity * 8) else { return nil }

        // Step 6: Generate weighted shuffled positions (prefer low-cost slots)
        var rng = KeyPRNG(seed: carrierSeed + Data("SCATTER_V3".utf8))
        let positions = generateWeightedPositions(
            costMap: costMap,
            totalSlots: totalSlots,
            rng: &rng
        )

        // Step 7: Embed payload bits at cost-weighted positions
        let totalBits = payload.count * 8

        for bitIdx in 0..<totalBits {
            let slot = positions[bitIdx]
            let pixelIdx = slot / 3
            let channelIdx = slot % 3
            let pixelOffset = pixelIdx * bytesPerPixel + channelIdx

            let byteIdx = bitIdx / 8
            let bitPos = 7 - (bitIdx % 8)
            let targetBit = (payload[byteIdx] >> bitPos) & 1
            let currentBit = pixelData[pixelOffset] & 1

            // Syndrome coding approximation: only modify if needed
            if currentBit != targetBit {
                pixelData[pixelOffset] = (pixelData[pixelOffset] & 0xFE) | targetBit
            }
        }

        // Step 8: Create output image. Do not add unrelated LSB "camouflage":
        // it increases the alteration footprint without improving recoverability
        // or providing a sound claim against steganalysis.
        guard let outCtx = CGContext(
            data: &pixelData, width: width, height: height,
            bitsPerComponent: 8, bytesPerRow: bytesPerRow,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ), let outImage = outCtx.makeImage() else { return nil }

        return UIImage(cgImage: outImage)
    }

    // MARK: - Decode

    func decode(from image: UIImage, secretKey: Data) -> Data? {
        guard let cgImage = image.cgImage else { return nil }

        let width = cgImage.width
        let height = cgImage.height
        guard isSupportedCarrier(width: width, height: height) else { return nil }
        let totalSlots = width * height * 3
        let baseCapacity = Int(Double(totalSlots) * baseEmbeddingRate) / 8

        let bytesPerPixel = 4
        let bytesPerRow = bytesPerPixel * width
        var pixelData = [UInt8](repeating: 0, count: height * bytesPerRow)
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: &pixelData, width: width, height: height,
            bitsPerComponent: 8, bytesPerRow: bytesPerRow,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))

        // Recompute same cost map and carrier-bound seed from pixel data. The
        // fingerprint masks RGB LSBs, which are the only values embedding alters.
        let costMap = computeDistortionMap(pixels: pixelData, width: width, height: height, bytesPerPixel: bytesPerPixel)
        guard hasAdequateTexture(costMap: costMap, requiredSlots: baseCapacity * 8) else { return nil }
        let carrierSeed = secretKey + carrierFingerprint(pixels: pixelData, bytesPerPixel: bytesPerPixel)

        // Regenerate same weighted positions
        var rng = KeyPRNG(seed: carrierSeed + Data("SCATTER_V3".utf8))
        let positions = generateWeightedPositions(
            costMap: costMap,
            totalSlots: totalSlots,
            rng: &rng
        )

        func extractByte(startBit: Int) -> UInt8 {
            var byte: UInt8 = 0
            for i in 0..<8 {
                let slot = positions[startBit + i]
                let pixelIdx = slot / 3
                let channelIdx = slot % 3
                let pixelOffset = pixelIdx * bytesPerPixel + channelIdx
                let bit = pixelData[pixelOffset] & 1
                byte = (byte << 1) | bit
            }
            return byte
        }

        // Read length
        var lengthBytes = Data()
        for i in 0..<4 { lengthBytes.append(extractByte(startBit: i * 8)) }
        let ciphertextLen = lengthBytes.withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }

        let overhead = 32
        guard ciphertextLen > 0, Int(ciphertextLen) + overhead <= baseCapacity else { return nil }

        // Read nonce
        var nonceData = Data()
        for i in 0..<12 { nonceData.append(extractByte(startBit: 32 + i * 8)) }

        // Read ciphertext
        let ctStart = 32 + 96
        var ciphertext = Data()
        for i in 0..<Int(ciphertextLen) {
            ciphertext.append(extractByte(startBit: ctStart + i * 8))
        }

        // Read tag
        let tagStart = ctStart + Int(ciphertextLen) * 8
        var tagData = Data()
        for i in 0..<16 { tagData.append(extractByte(startBit: tagStart + i * 8)) }

        // Decrypt
        let encKey = SymmetricKey(data: SHA256.hash(data: secretKey + Data("STEG_ENC_V2".utf8)))
        guard let nonce = try? AES.GCM.Nonce(data: nonceData),
              let sealedBox = try? AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tagData),
              let plaintext = try? AES.GCM.open(sealedBox, using: encKey) else {
            return nil
        }

        return plaintext
    }

    // MARK: - Distortion Cost Map (HUGO-Inspired)

    /// Compute per-pixel distortion cost using local variance analysis
    /// High texture / noise = low cost (safe to embed)
    /// Smooth areas = high cost (modifications detectable)
    private func computeDistortionMap(
        pixels: [UInt8],
        width: Int,
        height: Int,
        bytesPerPixel: Int
    ) -> DistortionMap {
        var map = DistortionMap(width: width, height: height)

        // 3x3 kernel for local variance (Sobel-like gradient magnitude)
        for y in 1..<(height - 1) {
            for x in 1..<(width - 1) {
                var gradientSum: Float = 0

                for c in 0..<3 { // R, G, B
                    // Horizontal gradient (Sobel-x)
                    let left   = Float(pixels[((y - 1) * width + (x - 1)) * bytesPerPixel + c] & 0xFE)
                    let right  = Float(pixels[((y - 1) * width + (x + 1)) * bytesPerPixel + c] & 0xFE)
                    let mleft  = Float(pixels[(y * width + (x - 1)) * bytesPerPixel + c] & 0xFE)
                    let mright = Float(pixels[(y * width + (x + 1)) * bytesPerPixel + c] & 0xFE)
                    let bleft  = Float(pixels[((y + 1) * width + (x - 1)) * bytesPerPixel + c] & 0xFE)
                    let bright = Float(pixels[((y + 1) * width + (x + 1)) * bytesPerPixel + c] & 0xFE)

                    let gx = (-left + right - 2 * mleft + 2 * mright - bleft + bright) / 8.0
                    let gy = (-left - 2 * Float(pixels[((y - 1) * width + x) * bytesPerPixel + c] & 0xFE) - right
                              + bleft + 2 * Float(pixels[((y + 1) * width + x) * bytesPerPixel + c] & 0xFE) + bright) / 8.0

                    gradientSum += sqrt(gx * gx + gy * gy)
                }

                // Higher gradient = lower cost (more texture = safer to embed)
                let gradientMag = gradientSum / 3.0
                let maxGrad: Float = 50.0

                // Invert: high gradient → low cost
                let cost = max(0.01, 1.0 - min(gradientMag / maxGrad, 0.99))
                map[x, y] = cost
            }
        }

        // Edges get high cost (avoid border artifacts)
        for x in 0..<width {
            map[x, 0] = 1.0
            map[x, height - 1] = 1.0
        }
        for y in 0..<height {
            map[0, y] = 1.0
            map[width - 1, y] = 1.0
        }

        return map
    }

    // MARK: - Weighted Position Generation

    /// Generate embedding positions weighted by distortion cost
    /// Slots in textured areas (low cost) are selected first
    private func generateWeightedPositions(
        costMap: DistortionMap,
        totalSlots: Int,
        rng: inout KeyPRNG
    ) -> [Int] {
        // Assign weights to all slots (inverse of cost = preference)
        var weightedSlots: [(slot: Int, weight: Float)] = []
        weightedSlots.reserveCapacity(totalSlots)

        for slot in 0..<totalSlots {
            let pixelIdx = slot / 3
            let x = pixelIdx % costMap.width
            let y = pixelIdx / costMap.width
            let cost = costMap[x, y]

            // Skip very smooth areas entirely
            if cost > (1.0 - minCostThreshold) {
                // Still include with very low weight (for deterministic position count)
                weightedSlots.append((slot, 0.01))
            } else {
                // Weight = inverse cost (textured areas preferred)
                weightedSlots.append((slot, 1.0 / max(cost, 0.01)))
            }
        }

        // Fisher-Yates shuffle with cost-weighted swap probability
        var positions = weightedSlots.map { $0.slot }
        var weights = weightedSlots.map { $0.weight }

        // Weighted shuffle: prefer moving high-weight items to front
        for i in 0..<min(positions.count, totalSlots) {
            // Generate swap index biased toward high-weight positions
            let remaining = positions.count - i
            if remaining <= 1 { break }

            // Use key-seeded PRNG for deterministic shuffle
            let j = i + Int(rng.nextUInt32()) % remaining

            // Bias: more likely to select higher-weight positions
            let wi = weights[i]
            let wj = weights[j]

            if wj > wi || rng.nextDouble() < 0.3 {
                positions.swapAt(i, j)
                weights.swapAt(i, j)
            }
        }

        return positions
    }

    // MARK: - Carrier Image Generation (v2.0 Photo-Realistic)

    func generateCarrierImage(size: CGSize = CGSize(width: 1024, height: 1024)) -> UIImage {
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { ctx in
            let context = ctx.cgContext
            let w = size.width
            let h = size.height

            // Layer 1: Natural-looking gradient background
            let bgHue = CGFloat.random(in: 0...1)
            let colors = [
                UIColor(hue: bgHue, saturation: CGFloat.random(in: 0.15...0.4), brightness: CGFloat.random(in: 0.7...0.95), alpha: 1),
                UIColor(hue: (bgHue + CGFloat.random(in: 0.05...0.2)).truncatingRemainder(dividingBy: 1.0),
                        saturation: CGFloat.random(in: 0.2...0.5),
                        brightness: CGFloat.random(in: 0.5...0.8), alpha: 1)
            ]
            let gradient = CGGradient(
                colorsSpace: CGColorSpaceCreateDeviceRGB(),
                colors: colors.map { $0.cgColor } as CFArray,
                locations: [0, 1]
            )!
            let angle = CGFloat.random(in: 0...(2 * .pi))
            let dx = cos(angle) * w
            let dy = sin(angle) * h
            context.drawLinearGradient(
                gradient,
                start: CGPoint(x: w/2 - dx/2, y: h/2 - dy/2),
                end: CGPoint(x: w/2 + dx/2, y: h/2 + dy/2),
                options: [.drawsBeforeStartLocation, .drawsAfterEndLocation]
            )

            // Layer 2: Organic shapes (circles, ellipses with varying opacity)
            // These create texture that helps hide steganographic modifications
            for _ in 0..<Int.random(in: 60...120) {
                let rect = CGRect(
                    x: CGFloat.random(in: -50...w),
                    y: CGFloat.random(in: -50...h),
                    width: CGFloat.random(in: 20...300),
                    height: CGFloat.random(in: 20...300)
                )
                let color = UIColor(
                    hue: CGFloat.random(in: 0...1),
                    saturation: CGFloat.random(in: 0.1...0.7),
                    brightness: CGFloat.random(in: 0.3...0.95),
                    alpha: CGFloat.random(in: 0.02...0.15)
                )
                context.setFillColor(color.cgColor)
                context.fillEllipse(in: rect)
            }

            // Layer 3: Fine-grained noise texture (critical for steganalysis resistance)
            // Random small rectangles that mimic sensor noise
            for _ in 0..<Int.random(in: 200...400) {
                let size = CGFloat.random(in: 1...8)
                let rect = CGRect(
                    x: CGFloat.random(in: 0...w),
                    y: CGFloat.random(in: 0...h),
                    width: size,
                    height: size
                )
                let alpha = CGFloat.random(in: 0.01...0.06)
                let gray = CGFloat.random(in: 0...1)
                context.setFillColor(UIColor(white: gray, alpha: alpha).cgColor)
                context.fill(rect)
            }

            // Layer 4: Subtle lines (mimic edges in natural photos)
            for _ in 0..<Int.random(in: 10...30) {
                context.setStrokeColor(UIColor(
                    hue: CGFloat.random(in: 0...1),
                    saturation: CGFloat.random(in: 0.05...0.3),
                    brightness: CGFloat.random(in: 0.4...0.9),
                    alpha: CGFloat.random(in: 0.02...0.08)
                ).cgColor)
                context.setLineWidth(CGFloat.random(in: 0.5...3.0))
                context.move(to: CGPoint(x: CGFloat.random(in: 0...w), y: CGFloat.random(in: 0...h)))
                context.addLine(to: CGPoint(x: CGFloat.random(in: 0...w), y: CGFloat.random(in: 0...h)))
                context.strokePath()
            }
        }
    }

    // MARK: - Capacity

    func maxCapacity(for image: UIImage) -> Int {
        guard let cg = image.cgImage else { return 0 }
        guard isSupportedCarrier(width: cg.width, height: cg.height) else { return 0 }
        let totalSlots = cg.width * cg.height * 3
        return max(0, Int(Double(totalSlots) * baseEmbeddingRate) / 8 - 32)
    }

    private func isSupportedCarrier(width: Int, height: Int) -> Bool {
        guard width >= minimumCarrierSide, height >= minimumCarrierSide else { return false }
        // Divide rather than multiply before comparing to avoid integer overflow
        // on malformed image metadata.
        return width <= maximumCarrierPixels / height
    }

    private func hasAdequateTexture(costMap: DistortionMap, requiredSlots: Int) -> Bool {
        guard requiredSlots > 0, requiredSlots <= Int.max / textureReserveMultiplier else { return false }
        let texturedPixels = costMap.costs.reduce(into: 0) { count, cost in
            if cost <= texturedCostCeiling { count += 1 }
        }
        // Each eligible pixel has three RGB candidate slots.
        return texturedPixels >= (requiredSlots * textureReserveMultiplier + 2) / 3
    }

    /// Stable across this codec's RGB-LSB modifications, but different for
    /// different covers. Hashing the normalized in-memory pixels avoids relying
    /// on file metadata or a lossy image serialization.
    private func carrierFingerprint(pixels: [UInt8], bytesPerPixel: Int) -> Data {
        var normalized = pixels
        for offset in stride(from: 0, to: normalized.count, by: bytesPerPixel) {
            normalized[offset] &= 0xFE
            normalized[offset + 1] &= 0xFE
            normalized[offset + 2] &= 0xFE
        }
        return Data(SHA256.hash(data: Data(normalized)))
    }
}

// MARK: - Steganography Settings View (v2.0)

struct SteganographySettingsView: View {
    @AppStorage("steganographyEnabled") private var steganographyEnabled = false
    @AppStorage("steganographyAutoMode") private var autoMode = false
    @AppStorage("steganographyCarrierSource") private var carrierSource = "generated"
    @AppStorage("covertChannelEnabled") private var covertChannelEnabled = false
    @State private var showDisclaimer = false
    @State private var disclaimerAccepted = false
    @AppStorage("steganographyDisclaimerAccepted") private var savedDisclaimerAccepted = false
    @State private var networkAssessment: NetworkAssessment?
    @State private var isAssessing = false

    private let brandColor = Theme.brand

    var body: some View {
        List {
            // Disclaimer
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Image(systemName: "exclamationmark.shield.fill")
                            .foregroundColor(.orange)
                            .font(.title2)
                        Text("steg.disclaimer.title".localized)
                            .font(.headline)
                            .foregroundColor(.primary)
                    }
                    Text("steg.disclaimer.body".localized)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.vertical, 4)
            } header: {
                Text("steg.section.important".localized)
            }

            // Image Steganography
            Section {
                Toggle(isOn: Binding(
                    get: { steganographyEnabled },
                    set: { newValue in
                        if newValue && !savedDisclaimerAccepted {
                            showDisclaimer = true
                        } else {
                            steganographyEnabled = newValue
                        }
                    }
                )) {
                    Label("steg.enable".localized, systemImage: "eye.slash.fill")
                }
                .tint(brandColor)


            } header: {
                Text("steg.section.settings".localized)
            } footer: {
                Text(steganographyEnabled ?
                     "steg.enabled.footer".localized :
                     "steg.disabled.footer".localized)
            }

            // Covert Channels (Network Steganography)
            Section {
                Toggle(isOn: $covertChannelEnabled) {
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("steg.covert.title".localized)
                            Text("steg.covert.subtitle".localized)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    } icon: {
                        Image(systemName: "network.badge.shield.half.filled")
                    }
                }
                .tint(brandColor)

                if covertChannelEnabled {
                    Text("Authenticated and padded V2 requests. This reduces exact size correlation, but is not anonymity, censorship circumvention, or undetectable steganography.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            } header: {
                Text("steg.section.network_bypass".localized)
            }

            // Capacity info
            if steganographyEnabled {
                Section {
                    HStack {
                        Image(systemName: "info.circle")
                            .foregroundColor(brandColor)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("steg.capacity.title".localized)
                                .font(.subheadline)
                                .fontWeight(.medium)
                            Text("steg.capacity.description".localized)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                } header: {
                    Text("steg.section.info".localized)
                }
            }
        }
        .onAppear { _ = CoverTransport.mode }
        .onChange(of: steganographyEnabled) { _, _ in _ = CoverTransport.mode }
        .onChange(of: covertChannelEnabled) { _, _ in _ = CoverTransport.mode }
        .navigationTitle("steg.title".localized)
        .navigationBarTitleDisplayMode(.inline)
        .alert("steg.disclaimer.alert_title".localized, isPresented: $showDisclaimer) {
            Button("steg.disclaimer.accept".localized) {
                savedDisclaimerAccepted = true
                steganographyEnabled = true
            }
            Button("steg.disclaimer.decline".localized, role: .cancel) {
                steganographyEnabled = false
            }
        } message: {
            Text("steg.disclaimer.alert_body".localized)
        }
    }

    private func restrictionColor(_ level: NetworkRestrictionLevel) -> Color {
        switch level {
        case .open:      return .green
        case .filtered:  return .yellow
        case .dpiActive: return .orange
        case .whitelist: return .red
        case .military:  return .red
        }
    }
}
