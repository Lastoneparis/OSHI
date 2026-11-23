//
//  CovertPaddingTests.swift
//  OSHITests
//
//  F5 (2026-09-10): the covert fallback channel now pads every sealed payload
//  up to a fixed 256-byte bucket before AES-GCM, so an on-path observer sees one
//  of a few coarse length classes instead of the exact message length. These
//  tests pin the bucket arithmetic — the length-header + padding round-trip is
//  exercised end-to-end by the transport itself; here we guard the one piece of
//  pure logic that decides indistinguishability: two very different message
//  lengths that fall in the same bucket MUST produce the same padded size.
//

import XCTest
@testable import OSHI

final class CovertPaddingTests: XCTestCase {

    // 4-byte length header + content, rounded up to the next 256 multiple.
    func testBucketBoundaries() {
        XCTAssertEqual(CovertChannelManager.paddedBucket(forContentLength: 0), 256)
        XCTAssertEqual(CovertChannelManager.paddedBucket(forContentLength: 1), 256)
        // 252 content + 4 header = 256 exactly -> stays in the 256 bucket.
        XCTAssertEqual(CovertChannelManager.paddedBucket(forContentLength: 252), 256)
        // 253 content + 4 header = 257 -> spills into the 512 bucket.
        XCTAssertEqual(CovertChannelManager.paddedBucket(forContentLength: 253), 512)
        XCTAssertEqual(CovertChannelManager.paddedBucket(forContentLength: 508), 512)
        XCTAssertEqual(CovertChannelManager.paddedBucket(forContentLength: 509), 768)
    }

    // The whole point: distinct short lengths are hidden behind one wire size.
    func testShortMessagesShareABucket() {
        let a = CovertChannelManager.paddedBucket(forContentLength: 1)
        let b = CovertChannelManager.paddedBucket(forContentLength: 251)
        XCTAssertEqual(a, b, "1-byte and 251-byte messages must be indistinguishable on the wire")
    }

    // Overhead is bounded: never more than 255 bytes of padding beyond the frame.
    func testPaddingOverheadIsBounded() {
        for n in [0, 1, 100, 252, 253, 1000, 4096, 65_536] {
            let bucket = CovertChannelManager.paddedBucket(forContentLength: n)
            let framed = 4 + n
            XCTAssertGreaterThanOrEqual(bucket, framed, "bucket must fit the frame (n=\(n))")
            XCTAssertLessThan(bucket - framed, 256, "padding must stay under one bucket (n=\(n))")
            XCTAssertEqual(bucket % 256, 0, "bucket must be a 256 multiple (n=\(n))")
        }
    }

    func testLegacyCarrierDeliveryIsRejectedBeforeNetworkUse() async {
        do {
            _ = try await CovertChannelManager.shared.sendCovert(
                data: Data("test payload".utf8),
                secretKey: Data(repeating: 7, count: 32),
                preferredChannel: .dnsSubdomain,
                targetDomains: ["example.invalid"]
            )
            XCTFail("Legacy carrier delivery must never start")
        } catch let error as CovertError {
            guard case .channelFailed(_, let reason) = error else {
                return XCTFail("Unexpected covert error: \(error)")
            }
            XCTAssertTrue(reason.contains("Legacy carrier delivery is removed"))
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testLegacyMultiCarrierDeliveryIsRejectedBeforeNetworkUse() async {
        do {
            _ = try await CovertChannelManager.shared.sendMultiChannel(
                data: Data("test payload".utf8),
                secretKey: Data(repeating: 9, count: 32),
                targetDomains: ["example.invalid"]
            )
            XCTFail("Legacy multi-carrier delivery must never start")
        } catch let error as CovertError {
            guard case .channelFailed(_, let reason) = error else {
                return XCTFail("Unexpected covert error: \(error)")
            }
            XCTAssertTrue(reason.contains("Legacy multi-carrier delivery is removed"))
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testCoverProfileRotationUsesOnlyAcceptedSchemas() throws {
        let request: [String: Any] = ["v": 1, "method": "GET", "path": "/v2/config", "headers": [:], "body": ""]
        var sawEvent = false
        var sawBatch = false
        for _ in 0..<64 {
            let encoded = try CoverTransport.encode(request, mode: "event")
            let json = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded.body) as? [String: Any])
            sawEvent = sawEvent || json["events"] != nil
            sawBatch = sawBatch || json["batch"] != nil
            let decoded = try CoverTransport.decode(encoded.body, type: encoded.type)
            XCTAssertEqual(decoded["path"] as? String, "/v2/config")
        }
        XCTAssertTrue(sawEvent, "event profile was never selected")
        XCTAssertTrue(sawBatch, "batch profile was never selected")
    }

    func testFinalCarrierDoesNotExposeWithinBucketMessageLength() throws {
        let short: [String: Any] = ["v": 1, "body": "x"]
        let long: [String: Any] = ["v": 1, "body": String(repeating: "x", count: 3_000)]
        var lengths = [String: Set<Int>]()
        for request in [short, long] {
            for _ in 0..<64 {
                let carrier = try CoverTransport.encode(request, mode: "event")
                let json = try XCTUnwrap(JSONSerialization.jsonObject(with: carrier.body) as? [String: Any])
                let profile = json["events"] == nil ? "batch" : "event"
                // Since 2026-11-13 the frame may be spread one bucket up: the
                // invariant is per (profile, bucket) class.
                let data = profile == "event"
                    ? (((json["events"] as? [[String: Any]])?.first?["params"] as? [String: Any])?["data"] as? String)
                    : ((json["batch"] as? [[String: Any]])?.first?["payload"] as? String)
                let bucket = try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(data))).count
                lengths["\(profile)/\(bucket)", default: []].insert(carrier.body.count)
            }
        }
        XCTAssertTrue(Set(lengths.keys).isSubset(of: ["event/4096", "batch/4096", "event/16384", "batch/16384"]))
        XCTAssertTrue(lengths.keys.contains("event/4096") && lengths.keys.contains("batch/4096"))
        for (profile, sizes) in lengths {
            XCTAssertEqual(sizes.count, 1, "\(profile) leaked a within-bucket message size")
        }
    }

    func testCoverEndpointRotationUsesOnlyRelayAliases() {
        XCTAssertEqual(Set(CoverTransport.coverPaths), ["/v2/cover", "/v2/collect", "/v2/metrics"])
    }

    func testRejectsFramesOutsideTheSharedPaddingBuckets() {
        XCTAssertThrowsError(try CoverTransport.unframe(Data(repeating: 0, count: 4 * 1024 + 1)))
    }

    func testRejectsMalformedOrOversizedBase64BeforeFrameParsing() throws {
        let malformed: [String: Any] = ["events": [["name": "page_view", "params": ["data": "%"]]]]
        XCTAssertThrowsError(try CoverTransport.decode(JSONSerialization.data(withJSONObject: malformed), type: "application/json"))
        let oversized: [String: Any] = ["batch": [["event": "screen_view", "payload": String(repeating: "A", count: CoverTransport.maxBase64Bytes + 1)]]]
        XCTAssertThrowsError(try CoverTransport.decode(JSONSerialization.data(withJSONObject: oversized), type: "application/json"))
    }

    func testRejectsUnknownOuterCarrierFields() throws {
        let carrier = try CoverTransport.encode(["v": 1, "body": "x"], mode: "event")
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: carrier.body) as? [String: Any])
        json["unreviewedExtension"] = "reject-me"
        XCTAssertThrowsError(try CoverTransport.decode(JSONSerialization.data(withJSONObject: json), type: carrier.type))
    }

    // MARK: - 2026-11-13 hardening (docs/COVER_TRANSPORT_STATUS_2026-11-13.md)

    private static let vectorsURL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("docs/fixtures/cover_transport/vectors.json")

    /// Same golden vectors as Android `CoverHardeningTest` and the relay copies
    /// (`tools/covert-regression/cover_vectors.test.js`).
    func testCrossPlatformCoverVectors() throws {
        let v = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: Self.vectorsURL)) as? [String: Any])
        for t in try XCTUnwrap(v["accept"] as? [[String: Any]]) {
            let got = try CoverTransport.decode(Data((t["body"] as! String).utf8), type: t["contentType"] as! String)
            XCTAssertTrue(NSDictionary(dictionary: got).isEqual(to: t["expect"] as! [String: Any]), "\(t["name"] ?? "")")
        }
        for t in try XCTUnwrap(v["reject"] as? [[String: Any]]) {
            XCTAssertThrowsError(try CoverTransport.decode(Data((t["body"] as! String).utf8), type: t["contentType"] as! String),
                                 "\(t["name"] ?? "")")
        }
        XCTAssertEqual(v["endpointPaths"] as? [String], CoverTransport.coverPaths)
    }

    func testEnvelopeHasFourByteShapesAndNoEscapedSlashes() throws {
        let record: [String: Any] = ["v": 1, "body": "cGF5bG9hZA=="]
        var shapes = Set<String>()
        for _ in 0..<400 {
            let e = try CoverTransport.encode(record, mode: "event")
            let text = String(decoding: e.body, as: UTF8.self)
            XCTAssertFalse(text.contains("\\/"))
            shapes.insert(String(text.prefix(18)))
            XCTAssertEqual(try CoverTransport.decode(e.body, type: e.type)["body"] as? String, "cGF5bG9hZA==")
        }
        XCTAssertEqual(shapes.count, 4)
    }

    func testBucketSpreadIsBoundedAndUpwardOnly() throws {
        var counts = [Int: Int]()
        for _ in 0..<4000 { counts[try CoverTransport.frame(["v": 1, "body": "x"], spread: true).count, default: 0] += 1 }
        XCTAssertEqual(Set(counts.keys), [4096, 16384])
        let up = Double(counts[16384] ?? 0) / 4000
        XCTAssertTrue((0.2...0.3).contains(up), "\(up)")
        let big: [String: Any] = ["v": 1, "body": String(repeating: "x", count: 70_000)]
        for _ in 0..<100 { XCTAssertEqual(try CoverTransport.frame(big, spread: true).count, 256 * 1024) }
        XCTAssertEqual(try CoverTransport.frame(["v": 1, "body": "x"]).count, 4096)
    }

    func testOuterPresentationVaries() {
        var queries = Set<String>(), headerSets = Set<String>(), types = Set<String>(), paths = Set<String>()
        for _ in 0..<300 {
            let p = CoverTransport.Presentation.next()
            queries.insert(p.query.map { $0.split(separator: "&").map { String($0.split(separator: "=")[0]) }.joined(separator: ",") } ?? "")
            headerSets.insert(p.headers.map { $0.0 }.joined(separator: ","))
            types.insert(p.contentType); paths.insert(p.path)
            XCTAssertTrue(p.contentType.lowercased().hasPrefix("application/json"))
            XCTAssertFalse(p.headers.contains { $0.0.lowercased().hasPrefix("x-oshi") || $0.0.lowercased() == "cookie" })
        }
        XCTAssertEqual(queries.count, CoverTransport.Presentation.queryTemplates)
        XCTAssertEqual(types, Set(CoverTransport.Presentation.contentTypes))
        XCTAssertEqual(paths, Set(CoverTransport.coverPaths))
        XCTAssertGreaterThanOrEqual(headerSets.count, 10)
    }

    func testReceiverPollJitterKeepsTheMean() {
        let samples = (0..<4000).map { _ in CovertChannelReceiver.jittered(15) }
        XCTAssertTrue(samples.allSatisfy { (7.5...22.5).contains($0) })
        XCTAssertGreaterThan(Set(samples).count, 1000)
        XCTAssertEqual(samples.reduce(0, +) / Double(samples.count), 15, accuracy: 0.5)
    }
}
