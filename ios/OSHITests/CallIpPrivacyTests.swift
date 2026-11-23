//
//  CallIpPrivacyTests.swift
//  OSHITests
//
//  ICE candidates are the one thing a call hands the peer before any media
//  flows, so they are where an IP leaks. Two policies are asserted here:
//
//    1. While a VPN is up, `getifaddrs` still reports en0 / pdp_ip0 alongside
//       utun — iOS does not hide the physical link from apps. Only the tunnel
//       address may be advertised.
//    2. "Always use relay server" must actually stop the peer learning our
//       address, not merely change which pair carries audio.
//
//  Each fixture is built so the PREVIOUS behaviour (advertise everything
//  gathered) produces a different answer from the expected one — a test that
//  merely re-ran the implementation's own filter would pass either way.
//

import XCTest
@testable import OSHI

final class CallIpPrivacyTests: XCTestCase {

    private typealias Addr = P2PTransport.LocalAddress

    private func host(_ ip: String) -> P2PCandidate {
        P2PCandidate(type: .host, ip: ip, port: 5000, priority: 0)
    }
    private func srflx(_ ip: String) -> P2PCandidate {
        P2PCandidate(type: .srflx, ip: ip, port: 5000, priority: 0)
    }
    private func relay(_ ip: String) -> P2PCandidate {
        P2PCandidate(type: .relay, ip: ip, port: 5000, priority: 0)
    }

    // MARK: - 1. Tunnel filtering

    func testWiFiAddressIsWithheldWhileVPNTunnelIsUp() {
        let gathered = [
            Addr(ifName: "en0", ip: "192.168.1.37", family: 4),
            Addr(ifName: "utun3", ip: "10.8.0.2", family: 4)
        ]

        let safe = P2PTransport.hostAddressesSafeToAdvertise(gathered)

        XCTAssertEqual(safe.map(\.ip), ["10.8.0.2"])
        XCTAssertFalse(safe.contains { $0.ip == "192.168.1.37" },
                       "the real LAN address must never reach the peer while a tunnel is up")
    }

    func testGlobalIPv6OnPhysicalLinkIsWithheldWhenTunnelIsV4Only() {
        // The sharpest case: a VPN that does not capture IPv6. The v6 address is
        // globally routable and per-device, so leaking it is worse than the v4.
        let gathered = [
            Addr(ifName: "pdp_ip0", ip: "2a01:cb00:1234:5678:1:2:3:4", family: 6),
            Addr(ifName: "utun3", ip: "10.8.0.2", family: 4)
        ]

        let safe = P2PTransport.hostAddressesSafeToAdvertise(gathered)

        XCTAssertEqual(safe.map(\.ip), ["10.8.0.2"])
    }

    func testEveryAddressIsAdvertisedWhenNoTunnelIsPresent() {
        // Without a VPN nothing changes — LAN-direct calls must still work.
        let gathered = [
            Addr(ifName: "en0", ip: "192.168.1.37", family: 4),
            Addr(ifName: "pdp_ip0", ip: "10.144.2.9", family: 4)
        ]

        XCTAssertEqual(P2PTransport.hostAddressesSafeToAdvertise(gathered), gathered)
    }

    func testTunnelInterfaceNaming() {
        XCTAssertTrue(P2PTransport.isTunnelInterface("utun0"))
        XCTAssertTrue(P2PTransport.isTunnelInterface("utun12"))
        XCTAssertTrue(P2PTransport.isTunnelInterface("ipsec0"))
        XCTAssertTrue(P2PTransport.isTunnelInterface("ppp0"))
        XCTAssertFalse(P2PTransport.isTunnelInterface("en0"))
        XCTAssertFalse(P2PTransport.isTunnelInterface("pdp_ip0"))
        XCTAssertFalse(P2PTransport.isTunnelInterface("awdl0"))
    }

    /// The interface table measured on this very iPhone 17 Pro Max on 2026-09-14,
    /// with Mullvad up on utun3.
    ///
    /// The first version of this fix filtered by interface NAME and passed every
    /// synthetic fixture AND a green run on the device — but iOS keeps ~15 ipsec/utun
    /// interfaces up for Wi-Fi Calling and IMS, and ALIASES the carrier's global IPv6
    /// onto them: pdp_ip3, ipsec5 and ipsec9 all carry 2a04:cec0:…:5bab. The name
    /// filter re-advertised it, so the leak survived. Only asserting on the ADDRESS
    /// catches that; asserting "every advertised interface is a tunnel" does not.
    func testCarrierGlobalIPv6DoesNotSurviveBeingAliasedOntoAnIPsecInterface() {
        let realIPhone = [
            Addr(ifName: "pdp_ip0", ip: "10.81.88.217", family: 4),
            Addr(ifName: "pdp_ip1", ip: "10.32.165.223", family: 4),
            Addr(ifName: "pdp_ip3", ip: "2a04:cec0:33:b6b8:96:ff17:37e9:5bab", family: 6),
            Addr(ifName: "pdp_ip3", ip: "2a04:cec0:33:b6b8:19a3:b871:20d9:d9b2", family: 6),
            Addr(ifName: "en2", ip: "169.254.135.240", family: 4),
            Addr(ifName: "ipsec4", ip: "192.0.0.6", family: 4),
            Addr(ifName: "ipsec4", ip: "fd74:6572:6d6e:7573:d:b0a:ffd6:68f3", family: 6),
            Addr(ifName: "utun3", ip: "10.66.71.10", family: 4),
            Addr(ifName: "utun3", ip: "2a0e:7d43:0:26::a:a", family: 6),
            Addr(ifName: "ipsec5", ip: "2a04:cec0:33:b6b8:96:ff17:37e9:5bab", family: 6),
            Addr(ifName: "ipsec9", ip: "2a04:cec0:33:b6b8:96:ff17:37e9:5bab", family: 6),
            Addr(ifName: "bridge100", ip: "172.20.10.1", family: 4),
            Addr(ifName: "ipsec6", ip: "10.32.165.223", family: 4),
            Addr(ifName: "ipsec8", ip: "10.32.165.223", family: 4),
            Addr(ifName: "utun8", ip: "fda3:cb00:59d9::1", family: 6)
        ]

        let advertised = P2PTransport.hostAddressesSafeToAdvertise(realIPhone).map(\.ip)

        XCTAssertFalse(advertised.contains { $0.hasPrefix("2a04:cec0") },
                       "the carrier IPv6 is aliased onto ipsec5/ipsec9 and must not survive: \(advertised)")
        XCTAssertFalse(advertised.contains { $0.hasPrefix("2a0e:7d43") },
                       "the VPN's own global IPv6 is still a global address: \(advertised)")
        XCTAssertFalse(advertised.contains { P2PTransport.isGloballyRoutable($0, family: $0.contains(":") ? 6 : 4) },
                       "nothing globally routable may be advertised: \(advertised)")
        XCTAssertEqual(Set(advertised).count, advertised.count,
                       "an address aliased onto four interfaces must be advertised once: \(advertised)")
    }

    func testGloballyRoutableClassification() {
        XCTAssertTrue(P2PTransport.isGloballyRoutable("93.184.216.34", family: 4))
        XCTAssertTrue(P2PTransport.isGloballyRoutable("2a04:cec0:33:b6b8:96:ff17:37e9:5bab", family: 6))
        XCTAssertTrue(P2PTransport.isGloballyRoutable("2001:db8::1", family: 6))
        XCTAssertFalse(P2PTransport.isGloballyRoutable("192.168.1.37", family: 4))
        XCTAssertFalse(P2PTransport.isGloballyRoutable("10.66.71.10", family: 4))
        XCTAssertFalse(P2PTransport.isGloballyRoutable("172.20.10.1", family: 4))
        XCTAssertFalse(P2PTransport.isGloballyRoutable("100.100.5.5", family: 4))      // CGNAT
        XCTAssertFalse(P2PTransport.isGloballyRoutable("192.0.0.6", family: 4))        // 464XLAT
        XCTAssertFalse(P2PTransport.isGloballyRoutable("169.254.135.240", family: 4))
        XCTAssertFalse(P2PTransport.isGloballyRoutable("fd74:6572::1", family: 6))     // ULA
        // 172.32 is OUTSIDE 172.16/12 — a naive "starts with 172" would get this wrong.
        XCTAssertTrue(P2PTransport.isGloballyRoutable("172.32.0.1", family: 4))
    }

    // MARK: - 2. Force-relay actually hides the address

    func testForceRelayPublishesTheRelayCandidateAndNothingElse() {
        let gathered = [host("192.168.1.37"), srflx("93.184.216.34"), relay("45.67.216.197")]

        let published = P2PTransport.publishableCandidates(gathered, forceRelay: true)

        XCTAssertEqual(published, [relay("45.67.216.197")])
    }

    func testForceRelayWithholdsSrflxBecauseItIsOurPublicAddress() {
        // srflx is the NAT-mapped public IP. Emitting it under a toggle labelled
        // "always use relay" would defeat the toggle even though media relays.
        let gathered = [srflx("93.184.216.34"), relay("45.67.216.197")]

        let published = P2PTransport.publishableCandidates(gathered, forceRelay: true)

        XCTAssertFalse(published.contains { $0.type == .srflx })
    }

    func testWithoutForceRelayTheFullSetIsPublished() {
        let gathered = [host("192.168.1.37"), srflx("93.184.216.34")]

        XCTAssertEqual(P2PTransport.publishableCandidates(gathered, forceRelay: false), gathered)
    }

    func testForceRelayBeforeAllocationLandsPublishesNothingRatherThanLeaking() {
        // Gathering finishes before TURN allocates. The old code emitted the
        // host+srflx set at that moment; the peer had our address before the
        // relay candidate ever existed.
        let gathered = [host("192.168.1.37"), srflx("93.184.216.34")]

        XCTAssertTrue(P2PTransport.publishableCandidates(gathered, forceRelay: true).isEmpty)
    }
}

// MARK: - Device-only: the real interface table
//
// The fixtures above are synthetic, so they cannot prove that `ifa_name` is
// actually read off the real `getifaddrs` chain — a bug there would make every
// interface look like "" (not a tunnel), the filter would pass everything
// through, and all the fixtures would still be green. These assertions run
// against the hardware the app ships on.

final class CallIpPrivacyDeviceTests: XCTestCase {

    func testRealInterfaceEnumerationCarriesNames() throws {
        let all = P2PTransport.localInterfaceAddresses()
        try XCTSkipIf(all.isEmpty, "no routable interface — device is offline")

        XCTAssertFalse(all.contains { $0.ifName.isEmpty },
                       "every address must carry its interface name, else the tunnel filter is blind: \(all)")
        XCTAssertFalse(all.contains { $0.ip.hasPrefix("fe80:") },
                       "link-local must already be dropped before the filter runs")
        XCTAssertTrue(all.contains { $0.ifName.hasPrefix("en") || $0.ifName.hasPrefix("pdp_ip") },
                      "expected a Wi-Fi or cellular interface on a real device, got \(all.map(\.ifName))")
    }

    func testFilterIsASubsetOfWhatWasGathered() {
        let all = P2PTransport.localInterfaceAddresses()
        let safe = P2PTransport.hostAddressesSafeToAdvertise(all)

        XCTAssertTrue(safe.allSatisfy { all.contains($0) },
                      "the filter must only remove, never invent, an address")
    }

    /// Passes trivially with no VPN; becomes the real assertion the moment one
    /// is switched on, which is how this gets checked on the phone.
    func testWhenATunnelIsUpOnlyTunnelAddressesSurvive() {
        let all = P2PTransport.localInterfaceAddresses()
        let safe = P2PTransport.hostAddressesSafeToAdvertise(all)

        let tunnels = all.filter { P2PTransport.isTunnelInterface($0.ifName) }
        print("🧭 interfaces=\(all.map { "\($0.ifName)=\($0.ip)" }) tunnels=\(tunnels.count) advertised=\(safe.map(\.ip))")

        // The assertion that matters is about ADDRESSES. An earlier version of this
        // test checked that every advertised interface was a tunnel — which was true
        // on this phone while the carrier's global IPv6 was still being advertised,
        // aliased onto ipsec5/ipsec9. It passed, and the leak was wide open.
        XCTAssertFalse(safe.contains { P2PTransport.isGloballyRoutable($0.ip, family: $0.family) },
                       "a globally routable address reached the advertised set on real hardware — \(safe.map(\.ip))")
        XCTAssertEqual(Set(safe.map(\.ip)).count, safe.count,
                       "aliased duplicates must be collapsed — \(safe.map(\.ip))")
        if tunnels.isEmpty {
            XCTAssertTrue(safe.allSatisfy { !P2PTransport.isGloballyRoutable($0.ip, family: $0.family) },
                          "no VPN: private addresses stay so LAN calls still work, globals never leave")
        } else {
            XCTAssertTrue(safe.allSatisfy { P2PTransport.isTunnelInterface($0.ifName) },
                          "VPN up: a physical interface escaped into the advertised set — \(safe)")
        }
    }
}

// MARK: - __TWO_MODE_CALL_PATH_2026_09_23__ (call B37DC742, iPhone 5G+VPN ⇄ Android 5G)
//
// A call rides DIRECT P2P or the VPS relay — never coturn — and a direct pair is selectable
// only after a media-sized probe came back. Mirrors Android `TwoModeCallPathTest`.
final class TwoModeCallPathTests: XCTestCase {

    override func tearDown() {
        P2PTransport.turnRelayMediaEnabled = false
        super.tearDown()
    }

    func testRelayCandidatesAreNotAPathByDefault() {
        P2PTransport.turnRelayMediaEnabled = false
        XCTAssertFalse(P2PTransport.acceptsRemoteCandidate(.relay))
        XCTAssertTrue(P2PTransport.acceptsRemoteCandidate(.host))
        XCTAssertTrue(P2PTransport.acceptsRemoteCandidate(.srflx))
        P2PTransport.turnRelayMediaEnabled = true
        XCTAssertTrue(P2PTransport.acceptsRemoteCandidate(.relay))
    }

    /// 5G+VPN drops IP fragments: small pings pass, ~2 KB media does not. Such a pair
    /// must never be selected, and must be dropped when jumbo pongs stop mid-call.
    func testAPairThatOnlyPongsSmallPingsCannotCarryMedia() {
        let now = Date()
        XCTAssertFalse(P2PTransport.pairSelectable(isLive: true, lastJumboPongAt: nil, now: now))
        XCTAssertTrue(P2PTransport.pairSelectable(isLive: true, lastJumboPongAt: now.addingTimeInterval(-0.5), now: now))
        XCTAssertFalse(P2PTransport.pairSelectable(isLive: false, lastJumboPongAt: now, now: now))
        XCTAssertFalse(P2PTransport.pairSelectable(isLive: true,
                                                   lastJumboPongAt: now.addingTimeInterval(-(P2PTransport.jumboMaxAge + 0.1)),
                                                   now: now))
    }

    func testJumboProbeIsMediaSizedAndItsPongKeepsTheSize() {
        let ping = P2PTransport.buildPing(callId: 0x0102030405060708, nonce: Data(repeating: 7, count: 12),
                                          tsMs: 99, size: P2PTransport.jumboProbeBytes)
        XCTAssertEqual(ping.count, P2PTransport.jumboProbeBytes)
        XCTAssertGreaterThanOrEqual(ping.count, 1_930)   // 48 kHz PCM frame + header
        XCTAssertLessThan(ping.count, 2_048)             // receive buffers on both platforms
        let pong = P2PTransport.pongFor(ping: ping)
        XCTAssertEqual(pong?.count, ping.count)
        XCTAssertEqual(pong?.first, 0x32)
        XCTAssertTrue(P2PTransport.isJumbo(pong?.count ?? 0))
        XCTAssertFalse(P2PTransport.isJumbo(P2PTransport.pingBytes))
    }

    func testPlainPingWireIsUnchanged() {
        let nonce = Data((1...12).map { UInt8($0) })
        let ping = P2PTransport.buildPing(callId: 1, nonce: nonce, tsMs: 0x0A0B0C0D0E0F1011)
        XCTAssertEqual(ping.count, 29)
        XCTAssertEqual(ping[0], 0x31)
        XCTAssertEqual(ping[8], 1)
        XCTAssertEqual(ping.subdata(in: 9..<21), nonce)
        XCTAssertEqual(ping[28], 0x11)
    }

    func testJumboCadence() {
        XCTAssertFalse(P2PTransport.shouldSendJumbo(pairLive: false, verified: false, tick: 0))
        XCTAssertTrue(P2PTransport.shouldSendJumbo(pairLive: true, verified: false, tick: 2))
        XCTAssertFalse(P2PTransport.shouldSendJumbo(pairLive: true, verified: false, tick: 3))
        XCTAssertTrue(P2PTransport.shouldSendJumbo(pairLive: true, verified: true, tick: 8))
        XCTAssertFalse(P2PTransport.shouldSendJumbo(pairLive: true, verified: true, tick: 6))
    }
}

// MARK: - __WB_ADPCM_CODEC_2026_09_23__ + __RELAY_FIRST_UPGRADE_2026_09_23__
//
// The cross-platform codec must be bit-identical with Android/Desktop (vectors below are
// produced by the Python reference and asserted verbatim by Android `WbAdpcmCodecTest`),
// and every media datagram must fit the 1200 B the direct path is now verified with.
final class WbAdpcmCodecTests: XCTestCase {

    private func referenceSignal() -> [Int16] {
        var seed: UInt32 = 12345
        return (0..<640).map { n in
            seed = (seed &* 1103515245 &+ 12345) & 0x7fffffff
            let noise = Int32((seed >> 16) & 0x7fff) - 16384
            let v = Int32((8000 * sin(2 * Double.pi * 440 * Double(n) / 16000)).rounded()) + (noise >> 3)
            return Int16(max(-32768, min(32767, v)))
        }
    }

    private func crc32(_ d: Data) -> UInt32 {
        var c: UInt32 = 0xFFFF_FFFF
        for b in d { c ^= UInt32(b); for _ in 0..<8 { c = (c >> 1) ^ (0xEDB8_8320 & (0 &- (c & 1))) } }
        return ~c
    }

    func testBitExactVectorsSharedWithAndroidAndDesktop() {
        let x = referenceSignal()
        var st = WbAdpcm.EncoderState()
        let f1 = WbAdpcm.encode(Array(x[0..<320]), state: &st)
        let f2 = WbAdpcm.encode(Array(x[320..<640]), state: &st)
        XCTAssertEqual(f1.count, 163)
        XCTAssertEqual(f1.prefix(12).map { String(format: "%02x", $0) }.joined(), "0000007777777711d20088cb")
        XCTAssertEqual(f2.prefix(12).map { String(format: "%02x", $0) }.joined(), "da8c3e034109431186018388")
        XCTAssertEqual(crc32(f1 + f2), 627_570_784)
    }

    func testEveryPacketDecodesOnItsOwnAndTracksTheSignal() {
        let x = referenceSignal()
        var st = WbAdpcm.EncoderState()
        let f1 = WbAdpcm.encode(Array(x[0..<320]), state: &st)
        let f2 = WbAdpcm.encode(Array(x[320..<640]), state: &st)
        // f2 decoded WITHOUT f1 (f1 "lost") must equal f2 decoded after f1.
        let d2 = WbAdpcm.decode(f2)
        XCTAssertEqual(d2.count, 320)
        var sig = 0.0, err = 0.0
        for (a, b) in zip(x[320..<640], d2) { sig += Double(a) * Double(a); err += Double(Int(a) - Int(b)) * Double(Int(a) - Int(b)) }
        XCTAssertGreaterThan(10 * log10(sig / err), 18, "ADPCM SNR on the second (self-contained) frame")
        XCTAssertEqual(WbAdpcm.decode(f1).count, 320)
        XCTAssertTrue(WbAdpcm.decode(Data([0, 0])).isEmpty, "truncated header must not crash")
    }

    func testTwentyMsFrameIs200BytesOnTheWire() {
        let r = WbResampler()
        let frame48 = [Int16](repeating: 1000, count: 960)
        let pcm16 = r.down(frame48)
        XCTAssertEqual(pcm16.count, 320)
        var st = WbAdpcm.EncoderState()
        let payload = WbAdpcm.encode(pcm16, state: &st)
        let wire = 1 + 8 + 12 + payload.count + 16
        XCTAssertEqual(wire, 200)
        XCTAssertLessThanOrEqual(wire, P2PTransport.compactProbeBytes)
    }

    /// 48 kHz 1 kHz tone → FIR ↓3 → ADPCM → ↑3 FIR → 48 kHz: the voice band survives.
    func testFullChainKeepsA1kHzTone() {
        let tx = WbResampler(), rx = WbResampler()
        var st = WbAdpcm.EncoderState()
        var input: [Int16] = [], output: [Int16] = []
        for f in 0..<25 {
            let frame = (0..<960).map { i -> Int16 in
                Int16(10_000 * sin(2 * Double.pi * 1000 * Double(f * 960 + i) / 48_000))
            }
            input += frame
            output += rx.up(WbAdpcm.decode(WbAdpcm.encode(tx.down(frame), state: &st)))
        }
        XCTAssertEqual(output.count, input.count)
        var best = -Double.infinity
        for lag in 30..<70 {
            var sig = 0.0, err = 0.0
            for i in 4800..<(input.count - 100) {
                let a = Double(input[i]), b = Double(output[i + lag])
                sig += a * a; err += (a - b) * (a - b)
            }
            best = max(best, 10 * log10(sig / err))
        }
        XCTAssertGreaterThan(best, 15, "end-to-end SNR of the 48→16→ADPCM→48 chain")
    }

    func testDirectPathPolicyConstants() {
        // Compact probes cover every compact datagram (video chunks ≤ 1151 B) and stay
        // under the 1200 B the owner's 5G+VPN path carries unfragmented.
        XCTAssertEqual(P2PTransport.compactProbeBytes, 1200)
        XCTAssertGreaterThan(P2PTransport.jumboProbeBytes, 1957)
        // "Fall back to relay instantly": ≤ ~1 s of lost pongs.
        XCTAssertLessThanOrEqual(P2PTransport.consentStaleSeconds, 1.0)
    }

    // __WB_POSTFILTER_2026_09_23__ receiver-side hiss removal. Not a wire contract, but the
    // Float pipeline is bit-identical on iOS/Android/Desktop: this CRC is asserted verbatim
    // by Android and Desktop `WbAdpcmCodecTest.postFilterOutputIsPinnedByACrcSharedWithIos`.
    func testPostFilterOutputIsPinnedByACrcSharedWithKotlin() {
        let x = referenceSignal()
        var st = WbAdpcm.EncoderState()
        let frames = [WbAdpcm.encode(Array(x[0..<320]), state: &st), WbAdpcm.encode(Array(x[320..<640]), state: &st)]
        let pf = WbPostFilter()
        var out = Data()
        for f in frames {
            var steps: [Int32]? = []
            let d = WbAdpcm.decode(f, steps: &steps)
            XCTAssertEqual(d, WbAdpcm.decode(f), "decode(steps:) must not change the samples")
            XCTAssertEqual(steps?.count, 320)
            for s in pf.process(d, steps: steps ?? []) { let u = UInt16(bitPattern: s); out.append(UInt8(u & 0xFF)); out.append(UInt8(u >> 8)) }
        }
        XCTAssertEqual(crc32(out), 2_068_779_908)
    }

    func testPostFilterRemovesAdpcmNoiseWithoutChangingTheLevel() {
        let n = 16_000
        let tone = (0..<n).map { Int16((8000 * sin(2 * Double.pi * 440 * Double($0) / 16000)).rounded()) }
        var st = WbAdpcm.EncoderState()
        let pf = WbPostFilter()
        var raw: [Int16] = [], flt: [Int16] = []
        for f in 0..<(n / 320) {
            var steps: [Int32]? = []
            let d = WbAdpcm.decode(WbAdpcm.encode(Array(tone[(f * 320)..<(f * 320 + 320)]), state: &st), steps: &steps)
            raw += d
            let y = pf.process(d, steps: steps ?? [])
            XCTAssertEqual(y.count, 320)
            flt += y
        }
        let lag = WbPostFilter.n - WbPostFilter.hop + WbPostFilter.prime   // 192 samples = 12 ms
        func snr(_ y: [Int16], _ l: Int) -> Double {
            var s = 0.0, e = 0.0
            for i in 3200..<(n - 400) { let a = Double(tone[i]), b = Double(y[i + l]); s += a * a; e += (a - b) * (a - b) }
            return 10 * log10(s / e)
        }
        func rms(_ y: [Int16], _ l: Int) -> Double {
            var s = 0.0
            for i in 3200..<(n - 400) { s += Double(y[i + l]) * Double(y[i + l]) }
            return sqrt(s / Double(n - 3600))
        }
        XCTAssertGreaterThan(snr(flt, lag), snr(raw, 0) + 5)
        XCTAssertEqual(20 * log10(rms(flt, lag) / rms(raw, 0)), 0, accuracy: 0.1)
        XCTAssertTrue(WbPostFilter().process([Int16](repeating: 0, count: 320), steps: [Int32](repeating: 7, count: 320)).allSatisfy { $0 == 0 })
    }
}

// MARK: - __OPUS_CODEC_2026_09_23__ Opus 0x19 (libopus 1.5.2 compiled from Vendor/opus)
//
// Round trip, the redundancy that recovers one or two lost packets bit-exactly, the seq
// keying, and a stream ENCODED BY Concentus (the Java port Android/Desktop compile from
// Vendor/concentus) decoded here — the interop the three platforms depend on. Mirrors
// Android/Desktop `OpusCallCodecTest`.
final class OpusCallCodecTests: XCTestCase {

    private func sine(_ frames: Int) -> [Int16] {
        (0..<(frames * OpusWire.frameSamples)).map { Int16((8000 * sin(2 * Double.pi * 1000 * Double($0) / 48000)).rounded()) }
    }

    /// Best normalised cross-correlation of `y` (from sample `from`) against the tone.
    private func toneCorrelation(_ y: [Int16], from: Int) -> Double {
        let x = sine(y.count / OpusWire.frameSamples + 2)
        var best = 0.0
        for lag in 0...1200 {
            var xy = 0.0, xx = 0.0, yy = 0.0
            for i in from..<y.count {
                let a = Double(y[i]), b = Double(x[i - lag + 1200])
                xy += a * b; xx += b * b; yy += a * a
            }
            if xx > 0 && yy > 0 { best = max(best, xy / (xx * yy).squareRoot()) }
        }
        return best
    }

    private func decodeAll(_ payloads: [Data], drop: Set<Int> = []) -> [(offset: Int, pcm: [Int16])] {
        let d = OpusCallDecoder()!
        return payloads.enumerated().filter { !drop.contains($0.offset) }.flatMap { d.decode($0.element) }
    }

    private func unhex(_ h: String) -> Data {
        var d = Data(); var i = h.startIndex
        while i < h.endIndex { let j = h.index(i, offsetBy: 2); d.append(UInt8(h[i..<j], radix: 16)!); i = j }
        return d
    }

    func testRoundTripKeepsToneWithinDatagramBudget() {
        let payloads = OpusCallEncoder()!.encode(sine(12))
        XCTAssertEqual(payloads.count, 12)
        for p in payloads { XCTAssertLessThanOrEqual(p.count, OpusWire.maxPayloadBytes) }
        XCTAssertEqual(OpusWire.unpack(payloads[5])?.frames.count, 3)          // newest + 2 repeats
        let y = decodeAll(payloads).flatMap { $0.pcm }
        XCTAssertEqual(y.count, 12 * OpusWire.frameSamples)
        XCTAssertGreaterThan(toneCorrelation(y, from: 4 * OpusWire.frameSamples), 0.95)
    }

    func testEncoderRecutsAnyTapSizeInto20msFrames() {
        let e = OpusCallEncoder()!, x = sine(6)
        let cuts = [0, 1024, 2048, 3072, 4096, 5760]
        let counts = zip(cuts, cuts.dropFirst()).map { e.encode(Array(x[$0..<$1])).count }
        XCTAssertEqual(counts, [1, 1, 1, 1, 2])
    }

    func testOneOrTwoLostPacketsAreRecoveredBitExactly() {
        let payloads = OpusCallEncoder()!.encode(sine(12))
        let ref = decodeAll(payloads)
        for drop in [Set([5]), Set([5, 6])] {
            let got = decodeAll(payloads, drop: drop)
            XCTAssertEqual(got.count, ref.count)
            for i in ref.indices { XCTAssertEqual(got[i].pcm, ref[i].pcm, "frame \(i) drop \(drop)") }
        }
        let d = OpusCallDecoder()!
        payloads.prefix(5).forEach { _ = d.decode($0) }
        XCTAssertEqual(d.decode(payloads[8]).map { $0.offset }, [-3, -2, -1, 0])  // 5,6 repeats, 7→5 PLC
    }

    func testDuplicatesAndLatePacketsPlayNothing() {
        let payloads = OpusCallEncoder()!.encode(sine(4))
        let d = OpusCallDecoder()!
        payloads.forEach { _ = d.decode($0) }
        XCTAssertTrue(d.decode(payloads[3]).isEmpty)
        XCTAssertTrue(d.decode(payloads[1]).isEmpty)
        XCTAssertNil(OpusWire.unpack(Data([0x20, 0, 1, 5])))                    // wrong version
        XCTAssertNil(OpusWire.unpack(Data([0x11, 0, 1, 50, 1, 2])))             // length past the end
    }

    func testKeyedFilesRecoveredFramesAndMergesWhenShort() {
        let f: (Int) -> (offset: Int, pcm: [Int16]) = { ($0, [Int16](repeating: Int16($0), count: OpusWire.frameSamples)) }
        let three = [f(-2), f(-1), f(0)]
        XCTAssertEqual(OpusCallDecoder.keyed(three, outerSeq: 100, lastKey: 97).map { $0.key }, [98, 99, 100])
        let merged = OpusCallDecoder.keyed(three, outerSeq: 100, lastKey: 98)
        XCTAssertEqual(merged.map { $0.key }, [99, 100])
        XCTAssertEqual(merged[0].pcm.count, 2 * OpusWire.frameSamples)          // nothing dropped
        XCTAssertTrue(OpusCallDecoder.keyed(three, outerSeq: 100, lastKey: 100).isEmpty)
        XCTAssertEqual(OpusCallDecoder.keyed([f(0)], outerSeq: 100, lastKey: nil).map { $0.key }, [100])
    }

    /// First 8 payloads of a 1 kHz tone encoded by Android/Desktop (Concentus, OpusCallCodec.kt).
    private let jvmPayloads = [
        "1000017882e153a6f41529cc90e00000fee17afb8f15596b3c3ac4fd1fa79a4cd5a61bdd99b1a21176c0f240e157c584a47378e9c2e3a24c304fcd1c597dd2255a9d175110b0b64001849d2a7b6397a6bf4c54aa3722cd27a79df8a12181a36cc7c88ba06072bd",
        "110002647882e153a6f41529cc90e00000fee17afb8f15596b3c3ac4fd1fa79a4cd5a61bdd99b1a21176c0f240e157c584a47378e9c2e3a24c304fcd1c597dd2255a9d175110b0b64001849d2a7b6397a6bf4c54aa3722cd27a79df8a12181a36cc7c88ba06072bd78a0b2c0a2a372dfe54f15a6320efc0466017deb94912eb0f848d37404e22081a9f2662141935bfd781109ab98d3247cded24f115e0598417f79b08087ae18cedf48204f18dd7c5858f378fa85",
        "120003647882e153a6f41529cc90e00000fee17afb8f15596b3c3ac4fd1fa79a4cd5a61bdd99b1a21176c0f240e157c584a47378e9c2e3a24c304fcd1c597dd2255a9d175110b0b64001849d2a7b6397a6bf4c54aa3722cd27a79df8a12181a36cc7c88ba06072bd4d78a0b2c0a2a372dfe54f15a6320efc0466017deb94912eb0f848d37404e22081a9f2662141935bfd781109ab98d3247cded24f115e0598417f79b08087ae18cedf48204f18dd7c5858f378fa85789c93de9d25c9669db6ed3b8dde27b10d2b62c75fba5bb39a421d9d3ef1edb136fa11bed88c537885b32c86655da398bd697e61f72a288eb3d0fb9ab505d66fa9f69d6fe17e5fce0c3108",
        "1200044d78a0b2c0a2a372dfe54f15a6320efc0466017deb94912eb0f848d37404e22081a9f2662141935bfd781109ab98d3247cded24f115e0598417f79b08087ae18cedf48204f18dd7c5858f378fa854b789c93de9d25c9669db6ed3b8dde27b10d2b62c75fba5bb39a421d9d3ef1edb136fa11bed88c537885b32c86655da398bd697e61f72a288eb3d0fb9ab505d66fa9f69d6fe17e5fce0c3108789c93de9d25c9669db69101aa7a06ba048e36e825a7e6ff2a7c231e30a3831cd8b7c331a6738d938e8008f6bc7a8d5f3993653310d79d9f82584e265989aa57dc7ef4596c0611058e61ff48",
        "1200054b789c93de9d25c9669db6ed3b8dde27b10d2b62c75fba5bb39a421d9d3ef1edb136fa11bed88c537885b32c86655da398bd697e61f72a288eb3d0fb9ab505d66fa9f69d6fe17e5fce0c31084c789c93de9d25c9669db69101aa7a06ba048e36e825a7e6ff2a7c231e30a3831cd8b7c331a6738d938e8008f6bc7a8d5f3993653310d79d9f82584e265989aa57dc7ef4596c0611058e61ff48789c93de9d25c9669db691029c31234fbfe37cadf37c41cf94a26a6665673dece073eec21c5b6a48c3d7ebeb8968e5bcd70c1d2699660ee39ae64d65f7aec605b57aa06cb21534bfe6dfa948",
        "1200064c789c93de9d25c9669db69101aa7a06ba048e36e825a7e6ff2a7c231e30a3831cd8b7c331a6738d938e8008f6bc7a8d5f3993653310d79d9f82584e265989aa57dc7ef4596c0611058e61ff484c789c93de9d25c9669db691029c31234fbfe37cadf37c41cf94a26a6665673dece073eec21c5b6a48c3d7ebeb8968e5bcd70c1d2699660ee39ae64d65f7aec605b57aa06cb21534bfe6dfa948789c93de9d25c9669db6ed5726097df8cf281d0289f0ffddd9b0fb22ebfcf8860cebd3fd977f1df690ab28c2d63dba112abe0f214aae3b88fba4e9d0a305e935252ff1b25f474b662be5549b4c40",
        "1200074c789c93de9d25c9669db691029c31234fbfe37cadf37c41cf94a26a6665673dece073eec21c5b6a48c3d7ebeb8968e5bcd70c1d2699660ee39ae64d65f7aec605b57aa06cb21534bfe6dfa9484e789c93de9d25c9669db6ed5726097df8cf281d0289f0ffddd9b0fb22ebfcf8860cebd3fd977f1df690ab28c2d63dba112abe0f214aae3b88fba4e9d0a305e935252ff1b25f474b662be5549b4c40789c93de9d25c9669db6eda82bbf68aa11b50abd2a456ac89a66c05835efdb2afc19860364c9a146236654135d24d7356c75185772666916019add4bf12cf1e143f711f7e0a0442e36d65e08",
        "1200084e789c93de9d25c9669db6ed5726097df8cf281d0289f0ffddd9b0fb22ebfcf8860cebd3fd977f1df690ab28c2d63dba112abe0f214aae3b88fba4e9d0a305e935252ff1b25f474b662be5549b4c404c789c93de9d25c9669db6eda82bbf68aa11b50abd2a456ac89a66c05835efdb2afc19860364c9a146236654135d24d7356c75185772666916019add4bf12cf1e143f711f7e0a0442e36d65e08789c93de9d25c9669db66026594abc651059e0e1370cfcb480ef0eedcd82e8a7e4b6128597c477a3ecfcfb1708472e28f06822d2dbf7a74f3b0e39eb73a47b00d599036b1f1f1ce152dfcd50"
    ]

    func testDecodesAStreamEncodedByConcentusOnAndroidAndDesktop() {
        let y = decodeAll(jvmPayloads.map(unhex)).flatMap { $0.pcm }
        XCTAssertEqual(y.count, 8 * OpusWire.frameSamples)
        XCTAssertGreaterThan(toneCorrelation(y, from: 3 * OpusWire.frameSamples), 0.95)
    }

    func testCapabilityByteStaysBelowThePeelFloor() {
        XCTAssertEqual(OpusWire.capFlag, 0x20)
        XCTAssertLessThan(OpusWire.capFlag, 0x2D)
        XCTAssertEqual(OpusWire.wireType, 0x19)
    }
}
