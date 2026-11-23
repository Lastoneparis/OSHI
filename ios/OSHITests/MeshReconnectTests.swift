import XCTest
@testable import OSHI

/// __NOTHING_EVER_DIALLED_A_PEER_TWICE_2026_11_07__ — the iOS half.
///
/// Found on Android from a user screenshot (0 connected, 1 nearby, a Connect
/// button that ran no code) and then looked for here. iOS had the same hole,
/// and one worse on top of it: on 2026-08-18 the Connect button was REMOVED
/// from cross-platform peer rows rather than wired, on the premise — written
/// into `CompactPeerRow` — that `CrossPlatformMesh` "connects by itself on
/// discovery" and there was "no user action to offer on this transport". It
/// connects by itself exactly once, because `NetServiceBrowser` reports each
/// service a single time. PeersView had no Refresh either, so an iPhone
/// looking at a stranded Android peer had no affordance at all.
///
/// The numbers here are pinned against the Kotlin twin by
/// `MeshReconnectParityTest` on the Android side.
final class MeshReconnectTests: XCTestCase {

    // MARK: - The policy

    private func decide(
        connected: Bool = false,
        hasAddress: Bool = true,
        dialInFlight: Bool = false,
        failures: Int = 0,
        msSinceLastDial: Int = Int.max,
        userAsked: Bool = false
    ) -> MeshReconnectPolicy.Attempt {
        MeshReconnectPolicy.decide(
            connected: connected,
            hasAddress: hasAddress,
            dialInFlight: dialInFlight,
            failures: failures,
            msSinceLastDial: msSinceLastDial,
            userAsked: userAsked
        )
    }

    func testALivePeerIsNotDialledAgain() {
        XCTAssertEqual(decide(connected: true), .alreadyConnected)
        XCTAssertEqual(decide(connected: true, userAsked: true), .alreadyConnected)
    }

    func testADialInFlightIsNotDoubled() {
        XCTAssertEqual(decide(dialInFlight: true), .wait)
        XCTAssertEqual(decide(dialInFlight: true, userAsked: true), .wait)
    }

    func testAPeerWithNoAddressAsksDiscoveryAgain() {
        // A BLE sighting with no ip/port. Dialling is not an option; only a
        // fresh browse can supply one.
        XCTAssertEqual(decide(hasAddress: false), .rediscover)
        XCTAssertEqual(decide(hasAddress: false, userAsked: true), .rediscover)
    }

    func testAPressIgnoresTheBackoff() {
        XCTAssertEqual(decide(failures: 5, msSinceLastDial: 1_000), .wait)
        XCTAssertEqual(decide(failures: 5, msSinceLastDial: 1_000, userAsked: true), .dial)
    }

    func testTheFirstRetryIsImmediateAndTheLadderClimbs() {
        XCTAssertEqual(MeshReconnectPolicy.backoff(failures: 0), 0)
        let ladder = (0...6).map { MeshReconnectPolicy.backoff(failures: $0) }
        XCTAssertEqual(ladder, ladder.sorted(), "the ladder must never descend: \(ladder)")
        // It tops out rather than becoming "give up": a peer still announced on
        // the LAN is still worth a try.
        XCTAssertEqual(MeshReconnectPolicy.backoff(failures: 99),
                       MeshReconnectPolicy.backoff(failures: 5))
    }

    func testTheSweepWaitsOutTheBackoffThenDials() {
        let owed = MeshReconnectPolicy.backoff(failures: 3)
        XCTAssertEqual(decide(failures: 3, msSinceLastDial: owed - 1), .wait)
        XCTAssertEqual(decide(failures: 3, msSinceLastDial: owed), .dial)
    }

    func testAFreshlyDiscoveredPeerIsDialledAtOnce() {
        XCTAssertEqual(decide(failures: 0, msSinceLastDial: 0), .dial)
    }

    func testTheSweepCadenceIsSaneAndRarerForBrowseRestarts() {
        XCTAssertTrue((5_000...30_000).contains(MeshReconnectPolicy.sweepIntervalMs))
        XCTAssertGreaterThan(MeshReconnectPolicy.rediscoverMinIntervalMs,
                             MeshReconnectPolicy.sweepIntervalMs,
                             "a browse restart re-resolves every service; it must be rarer than a sweep")
    }

    // MARK: - Which address we dial

    private func pick(_ a: String...) -> String? { MeshReconnectPolicy.pickDialAddress(a) }

    func testARoutableIPv4BeatsEveryIPv6() {
        XCTAssertEqual(pick("fe80::18c3:9aff:fe21:1%en0", "192.168.1.18"), "192.168.1.18")
        XCTAssertEqual(pick("2a01:cb00::1", "10.0.1.7"), "10.0.1.7")
    }

    func testAnIPv6OnlyPeerIsStillReachable() {
        // The old code took the first IPv4 and `break`ed; with none, `ipAddress`
        // stayed nil and NO connection was attempted — ever, since nothing
        // re-resolved.
        XCTAssertEqual(pick("2a01:cb00::1"), "2a01:cb00::1")
        XCTAssertEqual(pick("fe80::1%en0"), "fe80::1%en0")
    }

    func testAnUnscopedLinkLocalIPv6IsNeverDialled() {
        // No zone index means the kernel has no interface to send it on.
        XCTAssertNil(pick("fe80::18c3:9aff:fe21:1"))
    }

    func testWeNeverDialOurselves() {
        XCTAssertNil(pick("127.0.0.1", "::1", "0.0.0.0"))
        XCTAssertEqual(pick("127.0.0.1", "192.168.1.18"), "192.168.1.18")
    }

    func testALinkLocalIPv4IsALastResortAndNotARefusal() {
        // Two phones on a Wi-Fi network with no DHCP server self-assign one and
        // can still reach each other — which for a mesh app is a case that
        // matters. `lanIPv4Address()` treats it the same way on the announce side.
        XCTAssertEqual(pick("169.254.3.4"), "169.254.3.4")
        XCTAssertEqual(pick("169.254.3.4", "192.168.1.18"), "192.168.1.18")
        XCTAssertEqual(pick("169.254.3.4", "2a01:cb00::1"), "2a01:cb00::1")
    }

    func testNothingResolvedMeansNothingDialled() {
        XCTAssertNil(MeshReconnectPolicy.pickDialAddress([]))
        XCTAssertNil(pick("", "   "))
    }

    // MARK: - The wiring
    //
    // Source scanning, because these are SwiftUI and delegate-callback facts a
    // unit test cannot execute. Comments are stripped first: a guard that looks
    // for `action:` must not be satisfied by the comment explaining `action:`.

    private static func strippingComments(_ source: String) -> String {
        // __A_STRING_IS_NOT_A_COMMENT_2026_11_07__ String literals are copied
        // through WITHOUT looking for comment markers inside them.
        //
        // The Kotlin twin of this helper was string-unaware, and `ChatScreen.kt`
        // passes the MIME wildcard (star slash star) to a document picker. The
        // slash-star inside that literal opened a block comment that ran two
        // thousand lines: 120 KB of real code blanked before any guard read it.
        // A guard asserting a pattern is PRESENT then fails loudly, but one
        // asserting a pattern is ABSENT passes VACUOUSLY — it reports the defect
        // gone while the defect sits in the blanked region.
        //
        // No Swift file any guard currently scans carries that sequence in a
        // string (only `CovertHTTP.swift` does, and nothing scans it), so this
        // is closing the hazard rather than fixing a live failure.
        var out = ""
        var i = source.startIndex
        while i < source.endIndex {
            let c = source[i]
            let next = source.index(after: i)
            let n: Character = next < source.endIndex ? source[next] : " "

            if c == "\"" {
                out.append(c)
                i = next
                while i < source.endIndex {
                    let d = source[i]
                    out.append(d)
                    i = source.index(after: i)
                    if d == "\\", i < source.endIndex {
                        out.append(source[i]); i = source.index(after: i); continue
                    }
                    if d == "\"" { break }
                    // An unterminated literal must not eat the file.
                    if d == "\n" { break }
                }
                continue
            }
            if c == "/" && n == "*" {
                i = source.index(after: next)
                while i < source.endIndex {
                    if source[i] == "*" {
                        let after = source.index(after: i)
                        if after < source.endIndex && source[after] == "/" {
                            i = source.index(after: after); break
                        }
                    }
                    if source[i] == "\n" { out.append("\n") }
                    i = source.index(after: i)
                }
                continue
            }
            if c == "/" && n == "/" {
                while i < source.endIndex && source[i] != "\n" { i = source.index(after: i) }
                continue
            }
            out.append(c)
            i = source.index(after: i)
        }
        return out
    }

    private func source(_ relative: String) throws -> String {
        // The test bundle sits in DerivedData; walk up to the repo.
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()   // OSHITests/
        dir = dir.deletingLastPathComponent()                                   // repo root
        let url = dir.appendingPathComponent(relative)
        guard let text = try? String(contentsOf: url, encoding: .utf8) else {
            XCTFail("\(relative) not readable at \(url.path)")
            throw NSError(domain: "MeshReconnectTests", code: 1)
        }
        return Self.strippingComments(text)
    }

    private func slice(_ text: String, from start: String, upTo end: String) throws -> String {
        guard let a = text.range(of: start) else {
            XCTFail("could not find \"\(start)\"")
            throw NSError(domain: "MeshReconnectTests", code: 2)
        }
        guard let b = text.range(of: end, range: a.upperBound..<text.endIndex) else {
            XCTFail("could not find \"\(end)\" after \"\(start)\"")
            throw NSError(domain: "MeshReconnectTests", code: 3)
        }
        return String(text[a.upperBound..<b.lowerBound])
    }

    func testTheNearbyCrossPlatformRowHasAButtonAgain() throws {
        let view = try source("OSHI/PeersView.swift")
        // Anchors are CODE, not comments: `source` strips comments, so a
        // comment anchor can only ever fail to be found.
        let rows = try slice(view,
                             from: "ForEach(uniqueDiscoveredCPPeers",
                             upTo: "ForEach(Array(manuallyDisconnectedPeers)")
        XCTAssertTrue(
            rows.contains("action: { connectCrossPlatformPeer(peer) }"),
            "the nearby cross-platform row must offer a Connect action — it had none since 18/08:\n\(rows)"
        )
    }

    func testTheButtonReachesTheMeshAndSaysWhatHappened() throws {
        let view = try source("OSHI/PeersView.swift")
        let fn = try slice(view,
                           from: "private func connectCrossPlatformPeer",
                           upTo: "private func refreshPeers")
        XCTAssertTrue(fn.contains("crossPlatformMesh.connectNow(publicKey:"),
                      "Connect must call the public connectNow:\n\(fn)")
        XCTAssertTrue(fn.contains("connectNotice ="),
                      "and must set a notice — a silent button is indistinguishable from a dead one")
    }

    func testTheScreenCanRestartTheCrossPlatformBrowse() throws {
        let view = try source("OSHI/PeersView.swift")
        let fn = try slice(view, from: "private func refreshPeers", upTo: "private func invitePeer")
        XCTAssertTrue(fn.contains("crossPlatformMesh.restartDiscovery(force: true)"),
                      "Refresh must restart the Bonjour browse; the three toggles only drive MultipeerConnectivity:\n\(fn)")
    }

    func testTheMeshSweepsForPeersItHasNotConnected() throws {
        let mesh = try source("OSHI/CrossPlatformMesh.swift")
        // The CALL inside start(), not the declaration — `mesh.contains` alone
        // is satisfied by `private func scheduleReconnectSweep() {` and stays
        // green with the call commented out. Verified by re-breaking it.
        let bringUp = try slice(mesh, from: "scheduleIdentityAnnounce()", upTo: "self.isRunning = true")
        XCTAssertTrue(bringUp.contains("scheduleReconnectSweep()"),
                      "start() must schedule the reconnect sweep, or a failed dial is a peer's only dial:\n\(bringUp)")
        XCTAssertTrue(mesh.contains("MeshReconnectPolicy.decide("),
                      "the sweep must consult the policy rather than inventing its own timing")
    }

    func testAFailedDialIsCountedRatherThanSwallowed() throws {
        let mesh = try source("OSHI/CrossPlatformMesh.swift")
        // `case .failed(let error):` appears twice in this file — the incoming
        // listener has one too. Anchor on the dial's own log line.
        let handler = try slice(mesh, from: #") failed: \(error)")"#, upTo: "case .cancelled:")
        XCTAssertTrue(
            handler.contains("noteDialFinished(peer.publicKey, failed: true)"),
            "`cleanupConnection` looks the connection up in tcpConnections, where a dial that " +
            "never reached .ready was never put — the failure path was a complete no-op:\n\(handler)"
        )
    }

    func testTheResolverRanksItsAddressesInsteadOfTakingTheFirstIPv4() throws {
        let mesh = try source("OSHI/CrossPlatformMesh.swift")
        let fn = try slice(mesh,
                           from: "func netServiceDidResolveAddress",
                           upTo: "var publicKey = \"\"")
        XCTAssertTrue(fn.contains("MeshReconnectPolicy.pickDialAddress(candidates)"),
                      "an IPv6-only peer used to leave ipAddress nil and was never dialled:\n\(fn)")
    }
}
