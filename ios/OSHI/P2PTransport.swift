//
//  P2PTransport.swift
//  OSHI
//
//  ICE2 — multi-pair kept-alive transport with fast failover.
//
//  The previous transport ran hole-punch pings at 5 Hz until the first pong
//  won, then demoted to a 3 s keepalive on the single "active" pair. If that
//  pair silently rotted (carrier change, NAT rebinding, relay path degrading)
//  we only noticed after 10 s of inbound silence — by which time the user had
//  heard seconds of cuts.
//
//  ICE2 keeps EVERY candidate pair alive:
//    - 2 Hz STUN-style ping to every remote candidate on every tick (detects
//      mid-call degradation twice as fast as the original 1 Hz cadence;
//      overhead is negligible — tiny packets).
//    - Each pair tracks EWMA RTT (α = 0.125) and a success rate over the last
//      10 probes.
//    - score = (1 / (RTT_ms + 1)) × successRate, with a 1.5× bias applied to
//      direct (host/srflx) pairs so TURN-relay never wins on a tie. Highest
//      effective score among live pairs is the active one.
//    - Failover: if the active pair's successRate < 0.5 holds for ≥ 2 s OR its
//      EWMA RTT exceeds 300 ms OR it misses 3 consecutive probes, AND a
//      different live pair's effective score exceeds 1.2× the active pair's
//      effective score (hysteresis, prevents flapping), switch immediately.
//    - 200 ms dual-send window after a switch: `sendPacket` writes to the new
//      pair AND the old pair so the receiver's jitter buffer sees an overlap
//      rather than a gap.
//    - TURN relay pair is kept probed as a permanent fallback even when direct
//      pairs are healthy.
//
//  The socket layer is unchanged from the prior rewrite: one raw BSD UDP fd
//  (mirrors Android's DatagramSocket), non-blocking, drained by a
//  DispatchSourceRead onto the transport's serial queue. Session key, AES-GCM,
//  call-id framing, and the 0x01 candidate wire version are untouched.
//

import Foundation
import Network
import Darwin

// MARK: - Candidate types

enum P2PCandidateType: UInt8 {
    case host  = 0x00
    case srflx = 0x01
    case relay = 0x02
}

struct P2PCandidate: Hashable {
    let type: P2PCandidateType
    let ip: String
    let port: UInt16
    let priority: UInt32

    var family: UInt8 { ip.contains(":") ? 6 : 4 }
}

enum P2PTransportState {
    case idle
    case gathering
    case signaling
    case probing
    case connected
    case relay
    case failed
}

// MARK: - P2PTransport

final class P2PTransport {

    // MARK: - Public callbacks (unchanged API)

    var onPacketReceived: ((Data) -> Void)?
    var onStateChanged: ((P2PTransportState) -> Void)?
    var onLocalCandidatesReady: (([P2PCandidate]) -> Void)?
    var onPairSelected: ((_ pair: P2PCandidate, _ rttMs: Int) -> Void)?

    /// The ONE duplication policy, injected by the call manager (see
    /// `VoiceCallManager.mediaDuplicationAffordable()`). Consulted by the
    /// post-switch dual-send window in `sendPacket` so this transport stops
    /// being a second, uncoordinated owner of "send it twice". Called on the
    /// transport queue at media rate, so it must be lock-cheap and must never
    /// hop to MainActor. nil ⇒ duplication allowed (previous behaviour).
    var isDuplicationAffordable: (() -> Bool)?
    /// Rate-limit for the veto log — once per dual-send window, not per frame.
    private var loggedDualSendVeto: Bool = false

    // MARK: - Public state (unchanged)

    private(set) var state: P2PTransportState = .idle {
        didSet {
            if oldValue != state {
                refreshDiagCache()
                onStateChanged?(state)
            }
        }
    }

    private(set) var activeRemote: P2PCandidate?
    private(set) var localCandidates: [P2PCandidate] = []

    // MARK: - Private

    private let queue = DispatchQueue(label: "com.oshi.p2p.transport")
    private var sockFd: Int32 = -1
    private var readSource: DispatchSourceRead?
    /// __IPV6_DIRECT_PATH_2026_08_14__ Second media socket, IPv6 only. See
    /// `bindIPv6Socket(port:)` for why the v4 socket is left untouched.
    private var sockFd6: Int32 = -1
    private var readSource6: DispatchSourceRead?
    private var localPort: UInt16 = 0
    private let callId: UInt64

    /// Remote candidates we know about (from the peer via signal).
    private var remoteCandidates: [P2PCandidate] = []

    /// Per-pair probe/scoring state. Keyed by "ip:port".
    private var pairs: [String: PairState] = [:]

    /// Timer driving the 1 Hz keepalive/probe for ALL pairs.
    private var probeTimer: DispatchSourceTimer?

    /// Timer checking failover conditions on the active pair.
    private var failoverTimer: DispatchSourceTimer?

    /// Timer for the hard direct-probing deadline (triggers TURN allocation).
    private var probingDeadlineTimer: DispatchSourceTimer?

    /// Send timestamps per ping nonce so we can compute RTT on pong.
    /// Keyed by nonceHex → (sentMs, pairKey).
    private var nonceSendTimes: [String: (UInt64, String)] = [:]

    /// Most-recently emitted active pair key (for change detection + dual-send).
    private var activeKey: String?

    /// During a failover, we keep the previous active pair + a deadline. For
    /// the 200 ms window after a switch, sendPacket writes to both the new
    /// pair and `previousActive` so the receiver's jitter buffer doesn't gap.
    private var previousActive: P2PCandidate?
    private var dualSendUntil: Date?
    private let dualSendWindow: TimeInterval = 0.200

    /// Clock at which the active pair's successRate first dropped below 0.5.
    /// Resets to nil whenever the active pair recovers above the threshold.
    private var activeLossStartedAt: Date?
    /// Consecutive probe misses on the active pair (for probe_fail reason).
    private var activeConsecutiveMisses: Int = 0

    /// Last time we received anything (ping/pong/app) on the socket.
    private var lastInboundAt: Date?

    /// "ip:port" of every source we've already logged a DIAG_ICE_PING_RX for.
    /// Keeps the diagnostic one-shot per source instead of spamming on every probe.
    private var loggedPingSources: Set<String> = []

    // Tunables — match the task spec.
    private let probeIntervalMs: Int = 500       // 2 Hz keepalive (was 1 Hz)
    private let failoverCheckMs: Int = 250
    private let successWindow: Int = 10
    private let ewmaAlpha: Double = 0.125
    private let rttFailoverThreshold: Double = 300.0
    private let lossSustainedSeconds: TimeInterval = 2.0
    private let probeFailThreshold: Int = 3
    private let inboundStaleThreshold: TimeInterval = 10.0
    /// Hysteresis: only switch when new_score > switchHysteresis × current_score.
    private let switchHysteresis: Double = 1.2
    /// Direct-P2P bias: host/srflx pair scores are multiplied by this before
    /// comparison to relay pairs. TURN adds a relay hop that is never as good
    /// as direct, so equal nominal scores should prefer direct.
    private let directBias: Double = 1.5

    // MARK: - Two-mode path policy (__TWO_MODE_CALL_PATH_2026_09_23__)
    //
    // A call rides exactly one of two modes: DIRECT peer-to-peer when a pair is proven to
    // carry a media-sized datagram, otherwise the VPS call-server relay (what
    // VoiceCallManager uses whenever this transport is not .connected).
    //
    // coturn is NOT a third media path any more. Measured B37DC742 (2026-09-23, iPhone 5G+VPN
    // ⇄ Android 5G): our relay 45.67.216.197:61234 was allocated, Android ChannelBound TO it,
    // but we never created a permission for Android's relay ⇒ chConfirmed 0/0, every relayed
    // probe dropped by coturn — a half-state, on the same host as the call-server relay that
    // does the job anyway. coturn keeps serving STUN (srflx) — the only traversal aid needed.
    // Android: `P2PPathPolicy.kt` (same constants, same wire).
    static var turnRelayMediaEnabled: Bool = UserDefaults.standard.bool(forKey: "OSHIEnableTurnRelayMedia")
    /// Plain hole-punch ping: [0x31][call_id 8][nonce 12][ts_ms 8].
    static let pingBytes = 29
    /// Media-sized probe: ≥ the largest media datagram (48 kHz PCM frame ≈ 1.93 KB with
    /// header) and < the 2048-byte receive buffers on both platforms (old builds included).
    /// A 29-byte ping proves nothing about a ~2 KB frame: 5G+VPN tunnels drop IP fragments,
    /// so a pair can pong while every media frame is black-holed.
    static let jumboProbeBytes = 2000
    /// __RELAY_FIRST_UPGRADE_2026_09_23__ probe size when every media datagram is compact
    /// (WB-ADPCM 0x18 = 200 B, AAC-ELD ≈ 370 B, video chunks ≤ 1151 B). Probing at 2000 B
    /// asked a 5G+VPN path to carry IP FRAGMENTS it drops, so direct was refused on exactly
    /// the network (iPhone 5G+VPN) where it would have carried the real 200 B frames fine.
    static let compactProbeBytes = 1200
    /// Consent freshness: the active direct pair must have ponged within this (+ RTT) or
    /// the call manager sends the next frame on the relay. Pings go out every 500 ms, so
    /// this is ~2 lost pongs — the "fall back to relay instantly" budget.
    static let consentStaleSeconds: TimeInterval = 1.0
    /// Media-sized probe the call manager wants (evaluated each tick on the P2P queue).
    /// nil ⇒ `jumboProbeBytes` (raw 48 kHz PCM peers need the full 2000 B).
    var mediaProbeBytesProvider: (() -> Int)?
    private var currentProbeBytes: Int {
        max(Self.pingBytes + 1, mediaProbeBytesProvider?() ?? Self.jumboProbeBytes)
    }
    private let consentLock = NSLock()
    private var _consentAt: Date?
    private var _consentRttMs: Double = 0
    /// Age of the last pong on the ACTIVE pair (thread-safe; nil = no active pair yet).
    var activePairPongAge: TimeInterval? {
        consentLock.lock(); defer { consentLock.unlock() }
        return _consentAt.map { Date().timeIntervalSince($0) }
    }
    /// True while the active pair's consent is fresh (last pong within 1 s + RTT).
    var activePairConsentFresh: Bool {
        consentLock.lock(); defer { consentLock.unlock() }
        guard let t = _consentAt else { return false }
        return Date().timeIntervalSince(t) <= Self.consentStaleSeconds + _consentRttMs / 1000.0
    }
    private func noteConsent(_ at: Date?, rttMs: Double = 0) {
        consentLock.lock(); _consentAt = at; _consentRttMs = max(0, rttMs); consentLock.unlock()
    }
    static let jumboMaxAge: TimeInterval = 6.0
    static let jumboEveryTicksUnverified = 2   // 1 Hz until verified (ticks are 500 ms)
    static let jumboEveryTicksVerified = 4     // then every 2 s, to notice a VPN coming up

    static func acceptsRemoteCandidate(_ type: P2PCandidateType) -> Bool {
        type != .relay || turnRelayMediaEnabled
    }
    static func isJumbo(_ length: Int) -> Bool { length >= jumboProbeBytes }
    static func mediaVerified(lastJumboPongAt: Date?, now: Date) -> Bool {
        guard let t = lastJumboPongAt else { return false }
        return now.timeIntervalSince(t) <= jumboMaxAge
    }
    /// A pair may carry media only when live AND it proved it carries a media-sized datagram.
    static func pairSelectable(isLive: Bool, lastJumboPongAt: Date?, now: Date) -> Bool {
        isLive && mediaVerified(lastJumboPongAt: lastJumboPongAt, now: now)
    }
    static func shouldSendJumbo(pairLive: Bool, verified: Bool, tick: Int) -> Bool {
        guard pairLive else { return false }
        return tick % (verified ? jumboEveryTicksVerified : jumboEveryTicksUnverified) == 0
    }
    private var probeTick: Int = 0

    // MARK: - TURN

    struct TurnConfig {
        let host: String
        let port: UInt16
        let username: String
        let password: String
        /// Phase 5: when true, the relay runs TURN-over-TLS (TCP) instead of plain
        /// UDP — used for China/forced-relay calls where the GFW drops UDP and
        /// fingerprints the WebSocket relay, but lets TLS (looks like HTTPS) through.
        var useTLS: Bool = false
    }
    private var turnConfig: TurnConfig?
    private var turnClient: TurnClient?
    private var relayAllocated: AllocatedRelay?
    private var relayChannels: [String: UInt16] = [:]
    private var nextChannelNumber: UInt16 = 0x4000
    private var forceRelayOnly: Bool = false
    private var relayAllocationAttempted: Bool = false
    /// Bounded rebuild-and-retry count for a failed/timed-out TURN Allocate.
    private var turnAllocationRetries: Int = 0
    /// True once internalStart() has built the media socket. `configureTurn` only
    /// re-attempts an allocation after this point.
    private var didStart: Bool = false

    /// Channels whose ChannelBind has been ack'd by the TURN server. Until a
    /// channel appears here, ChannelData frames sent through it are dropped by
    /// coturn (RFC 5766 §11.2), so we queue outbound packets in
    /// `pendingRelayPackets` and flush on the `TurnClient.onChannelBound` cb.
    private var relayChannelConfirmed: Set<UInt16> = []
    private var pendingRelayPackets: [UInt16: [Data]] = [:]
    /// Cap to keep memory bounded if a bind never completes — first frames
    /// (~1 s of audio at 50 fps = 50 packets) are what matter for continuity.
    private let pendingRelayPacketsCap: Int = 50

    // MARK: - Diagnostics snapshot (DIAG_PATH)

    /// Everything DIAG_PATH needs from the transport, in one lock-protected
    /// snapshot. Refreshed on the transport queue (every probe tick and on every
    /// state/allocation change) and read from the caller's thread, so the DIAG
    /// line can never deadlock against the transport's serial queue.
    struct DiagSnapshot {
        var state: String = "idle"
        var activePair: String = "none"
        var localMediaPort: UInt16 = 0
        var srflx: String = "—"
        var turnAlloc: String = "none"
        var channelsConfirmed: Int = 0
        var channelsTotal: Int = 0
    }
    private let diagLock = NSLock()
    private var diagCache = DiagSnapshot()

    func diagSnapshot() -> DiagSnapshot {
        diagLock.lock(); defer { diagLock.unlock() }
        return diagCache
    }

    /// MUST be called on `queue`.
    private func refreshDiagCache() {
        var s = DiagSnapshot()
        s.state = "\(state)"
        if let a = activeRemote {
            s.activePair = "\(a.ip):\(a.port)/\(a.type)"
        }
        s.localMediaPort = localPort
        if let sr = localCandidates.first(where: { $0.type == .srflx }) {
            s.srflx = "\(sr.ip):\(sr.port)"
        }
        // 🔧 FIX (2026-07-27): three DIFFERENT failures used to collapse into
        // "none"/"pending". `no_creds` means we never configured TURN because we hold
        // no credentials coturn accepts (fix the /voip/turn-creds fetch); `pending`
        // means an Allocate is genuinely in flight (fix the network/server); `none`
        // means TURN is configured but nothing has tried yet.
        if let alloc = relayAllocated {
            s.turnAlloc = "\(alloc.relayedAddress):\(alloc.relayedPort)"
        } else if turnConfig == nil {
            s.turnAlloc = "no_creds"
        } else if relayAllocationAttempted {
            s.turnAlloc = "pending"
        }
        s.channelsTotal = relayChannels.count
        s.channelsConfirmed = relayChannels.values.filter { relayChannelConfirmed.contains($0) }.count
        diagLock.lock(); diagCache = s; diagLock.unlock()
    }

    // MARK: - Init

    init(callId: UInt64) {
        self.callId = callId
    }

    /// `configureTurn` normally lands AFTER `start()` — the caller waits for real
    /// ephemeral TURN credentials — so `forceRelayOnly` is still false while
    /// gathering runs. Reading the preference directly closes that window; it is
    /// the same key VoiceCallManager reads to build the flag it passes here.
    private var forceRelayEffective: Bool {
        forceRelayOnly || UserDefaults.standard.bool(forKey: "oshi.call.forceRelay")
    }

    /// Single gate between what we gathered and what the peer is told. Every
    /// emission goes through here so no future call site can bypass the policy.
    private func emitLocalCandidates() {
        let publishable = Self.publishableCandidates(localCandidates,
                                                     forceRelay: forceRelayEffective)
        if publishable.count != localCandidates.count {
            OshiLog.mesh.info("🧭 P2P: forceRelay — withholding \(self.localCandidates.count - publishable.count) non-relay candidate(s) from peer")
        }
        guard !publishable.isEmpty else { return }
        onLocalCandidatesReady?(publishable)
    }

    func configureTurn(_ config: TurnConfig, forceRelay: Bool = false) {
        queue.async { [weak self] in
            guard let self = self else { return }
            let hadConfig = (self.turnConfig != nil)
            let credsChanged = (self.turnConfig?.username != config.username)
                || (self.turnConfig?.password != config.password)
            self.turnConfig = config
            self.forceRelayOnly = forceRelay

            // 🔧 FIX (2026-07-27): configureTurn used to be a pure setter, so a
            // configuration that arrived AFTER start() (the normal case now that the
            // caller waits for real ephemeral credentials instead of allocating with
            // ones coturn 401s) never triggered anything — the eager allocation at
            // start() had already run against a nil config and nothing re-ran it.
            // Re-arm the allocation here whenever we have no relay yet and the
            // credentials are new, so late-arriving creds convert into an actual
            // relay for a call already in progress.
            guard self.didStart, self.relayAllocated == nil else {
                self.refreshDiagCache()
                return
            }
            guard !hadConfig || credsChanged else { return }
            guard Self.turnRelayMediaEnabled else { self.refreshDiagCache(); return }
            self.turnClient?.close()
            self.turnClient = nil
            self.relayAllocationAttempted = false
            self.turnAllocationRetries = 0
            OshiLog.mesh.info("🧭 P2P: TURN (re)configured with \(hadConfig ? "new" : "first") credentials — re-attempting Allocate")
            // The 5 s direct-probe deadline may already have declared the transport
            // failed, which parks tickProbes(); a relay candidate would then never be
            // probed and could never be promoted. Put it back to .probing so the
            // pair machinery is alive for the allocation we are about to make.
            if self.state == .failed && self.activeRemote == nil {
                self.state = .probing
                self.armTurnProbingDeadline()
            }
            self.attemptTurnAllocation()
        }
    }

    // MARK: - Lifecycle (unchanged API)

    func start() {
        queue.async { [weak self] in
            self?.internalStart(isRestart: false)
        }
    }

    /// - Parameter preserveSocket: keep the existing UDP socket (and therefore the
    ///   port the peer is already punching at). Pass true ONLY when the restart is
    ///   not caused by a network change; see `preserveSocketOnRestartEnabled`.
    func restart(preserveSocket: Bool = false) {
        queue.async { [weak self] in
            guard let self = self else { return }
            OshiLog.mesh.info("🧭 P2P: ICE restart (preserveSocket=\(preserveSocket))")
            self.teardownProbes()
            self.pairs.removeAll()
            self.activeRemote = nil
            self.activeKey = nil
            self.previousActive = nil
            self.dualSendUntil = nil
            self.activeLossStartedAt = nil
            self.activeConsecutiveMisses = 0
            // 🔧 FIX (TURN relay was never retried, calls fell to VPS):
            // relayAllocationAttempted is sticky across the lifetime of the
            // P2PTransport. When a previous call attempted TURN once and the
            // transport got restarted for a new call/network change, the next
            // probing-timeout handler saw `relayAllocationAttempted == true`,
            // logged "no TURN configured" (misleading), set state .failed, and
            // the audio path immediately demoted to VPS Tor — bad audio.
            // Reset here so a fresh probing session can re-attempt TURN.
            self.relayAllocationAttempted = false
            self.turnAllocationRetries = 0
            // 🔧 FIX (2026-07-27, stale allocation on a dead interface): the OLD
            // TurnClient was never closed. `relayAllocationAttempted = false`
            // guarantees we will build a NEW one, so the previous client stayed
            // alive with its socket, its Refresh ladder and its rebind timer all
            // still running against an allocation on the interface we just left —
            // holding a coturn allocation open, burning battery, and (now that
            // Refresh retransmits) logging failures for a relay nobody uses.
            // Tear the whole relay state down so the restart re-derives it.
            self.turnClient?.close()
            self.turnClient = nil
            self.relayAllocated = nil
            self.relayChannels.removeAll()
            self.relayChannelConfirmed.removeAll()
            self.pendingRelayPackets.removeAll()
            self.nextChannelNumber = 0x4000
            self.refreshDiagCache()
            self.internalStart(isRestart: true, preserveSocket: preserveSocket)
        }
    }

    func addRemoteCandidate(ip: String, port: UInt16, type: P2PCandidateType, priority: UInt32) {
        queue.async { [weak self] in
            guard let self = self else { return }
            // __TWO_MODE_CALL_PATH_2026_09_23__ the peer's coturn relay is not a path we use.
            guard Self.acceptsRemoteCandidate(type) else {
                OshiLog.mesh.info("🧭 P2P: ignoring remote relay candidate \(ip):\(port) (TURN is not a media path)")
                return
            }
            let cand = P2PCandidate(type: type, ip: ip, port: port, priority: priority)
            if !self.remoteCandidates.contains(cand) {
                self.remoteCandidates.append(cand)
            }
            // Create per-pair state if this is a new remote.
            let key = Self.candidateKey(cand)
            if self.pairs[key] == nil {
                self.pairs[key] = PairState(candidate: cand, windowSize: self.successWindow)
            }

            // 🔧 FIX (2026-07-26): gate on the ALLOCATION, not on the client
            // existing. `turnClient` is non-nil from the moment attemptTurnAllocation
            // starts, so every remote candidate arriving during the ~1 s Allocate
            // round-trip issued a ChannelBind against an allocation that did not
            // exist yet → coturn answers 437 Allocation Mismatch, TurnClient retries
            // once and gives up, and the channel never entered relayChannelConfirmed.
            // The post-allocation sweep then no-op'd because ensureChannelBound
            // early-returned on the cached (unconfirmed) channel number, and
            // sendToPair queued 50 packets and dropped the rest forever.
            if self.turnClient != nil && self.relayAllocated != nil {
                self.ensureChannelBound(for: cand)
            }

            // .failed only means "the VPS relay carries the call": candidates that land late
            // (slow signalling) must still be able to upgrade it to direct.
            if self.state == .signaling || self.state == .probing || self.state == .gathering
                || (self.state == .failed && self.activeRemote == nil) {
                if self.state != .probing {
                    self.state = .probing
                    self.startProbeTimer()
                    self.startFailoverTimer()
                    self.armProbingDeadline()
                }
            }
        }
    }

    func addRemoteCandidates(_ candidates: [P2PCandidate]) {
        for c in candidates {
            addRemoteCandidate(ip: c.ip, port: c.port, type: c.type, priority: c.priority)
        }
    }

    /// Send an application packet. Writes to the active pair. If we're still
    /// in a post-switch dual-send window, also writes to the previous active
    /// pair so the receiver's jitter buffer sees overlap instead of a gap.
    func sendPacket(_ packet: Data) {
        queue.async { [weak self] in
            guard let self = self else { return }

            guard let remote = self.activeRemote else { return }

            // Primary write.
            self.sendToPair(packet, remote: remote)

            // Dual-send window: also write to the previous pair so the peer's
            // jitter buffer has overlap during the handoff.
            //
            // 🔧 FIX (2026-07-27): this was a SECOND, uncoordinated duplication
            // owner. `f370cb4` made `chooseAudioTxPrimary` / `txDuplicationAllowed`
            // the single policy for "may this frame ride two paths", including the
            // measured-critical veto on mobile/metered/constrained bearers — and
            // this window ignored all of it, quietly re-doubling the uplink on
            // exactly the link that could least afford it, every time the active
            // pair changed. It now asks the same policy. `isDuplicationAffordable`
            // is injected by VoiceCallManager; nil (unit tests, no host) keeps the
            // old always-on behaviour.
            if let prev = self.previousActive, let until = self.dualSendUntil {
                if Date() < until {
                    if self.isDuplicationAffordable?() ?? true {
                        self.sendToPair(packet, remote: prev)
                    } else if !self.loggedDualSendVeto {
                        self.loggedDualSendVeto = true
                        OshiLog.mesh.info("🧭 P2P: dual-send window SUPPRESSED — bearer cannot afford duplication (single policy)")
                    }
                } else {
                    // Window expired — clear it.
                    self.previousActive = nil
                    self.dualSendUntil = nil
                    self.loggedDualSendVeto = false
                }
            }
        }
    }

    func stop() {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.teardownAll()
            self.state = .idle
        }
    }

    // MARK: - Internal: start / gathering

    /// 🔧 FIX (2026-08-10, direct P2P never established on a cellular call):
    ///
    /// `isRestart` used to be accepted and then DISCARDED (`_ = isRestart` at the
    /// end of this function), so an ICE restart took the cold-start path: a brand
    /// new UDP socket, a new ephemeral port, a new srflx.
    ///
    /// MEASURED on the 16:14:53 call. The transport started during ringing
    /// (16:14:49), gathered, exchanged candidates, and began probing. At answer
    /// (16:14:53) `startP2PTransport` re-entered, saw the same call id, and called
    /// `restart()` under the comment "genuine ICE restart, same call — reuse
    /// socket". The socket was NOT reused. The port changed, which silently
    /// invalidated the srflx candidate the peer already held and had already
    /// started punching at, and `pairs.removeAll()` threw away the probe state.
    /// The log shows no further remote candidates arriving after that point, and
    /// 10 s later the transport gave up and promoted the TURN relay — on a call
    /// where BOTH sides had a valid srflx on their real media port.
    ///
    /// So the hole punch was killed at the exact moment the user answered.
    ///
    /// Preserving the socket keeps the port, and therefore keeps the candidate
    /// the peer is already punching at, valid across the answer.
    ///
    /// Deliberately opt-IN per call site. `restart()` is also the network-change
    /// handler, and there the old socket is bound to an interface that is GONE —
    /// reusing it would be strictly wrong. Only the same-call-id answer path
    /// passes true; every other caller keeps today's behaviour byte for byte.
    static var preserveSocketOnRestartEnabled: Bool = !UserDefaults.standard.bool(forKey: "OSHIDisableP2PSocketPreserve")

    private func internalStart(isRestart: Bool, preserveSocket: Bool = false) {
        teardownProbes()
        localCandidates.removeAll()
        state = .gathering
        // Gate for the late-configureTurn re-allocation: allocating before the media
        // socket exists would emit a relay candidate for a transport that has not
        // gathered yet.
        didStart = true

        // Every precondition is checked rather than assumed. If any of them does
        // not hold we fall through to a fresh socket — a call that connects over
        // the relay is a far better failure than a call with no media path at all.
        let canReuse = isRestart
            && preserveSocket
            && Self.preserveSocketOnRestartEnabled
            && sockFd >= 0
            && localPort != 0
            && readSource != nil

        if canReuse {
            let port = localPort
            OshiLog.mesh.info("🧭 P2P: ICE restart — REUSING media socket on port \(port) (peer's srflx for us stays valid)")
            CallFileLogger.shared.log("🧭 P2P: restart reuse=1 port=\(port)")

            // __IPV6_DIRECT_PATH_2026_08_14__ The v4 socket survived the restart,
            // so the v6 one normally did too. Rebind only if it did not, because
            // `addHostCandidates` below re-advertises v6 candidates on this port
            // and they must have something listening behind them.
            if sockFd6 < 0 { bindIPv6Socket(port: port) }

            addHostCandidates(port: port)
            // Re-discover srflx ON THE SAME SOCKET. `localCandidates` was just
            // cleared, so the previously advertised srflx is gone from the set and
            // has to come back or we would advertise host candidates only — the
            // exact starvation this fix exists to prevent. Same socket means the
            // NAT mapping is normally identical, so the peer's view of us does not
            // change; and if the NAT did rebind underneath us, this is what
            // notices.
            srflxDiscovered = false
            gatherReflexive()
            attemptTurnAllocation()
            return
        }

        if isRestart && preserveSocket {
            OshiLog.mesh.info("🧭 P2P: ICE restart — socket reuse unavailable (fd=\(self.sockFd) port=\(self.localPort) src=\(self.readSource != nil)), rebinding")
        }

        let fd = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else {
            OshiLog.mesh.info("🧭 P2P: ⚠️ failed to create UDP socket errno=\(errno)")
            state = .failed
            return
        }
        _ = fcntl(fd, F_SETFL, O_NONBLOCK)

        var yes: Int32 = 1
        _ = setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
        _ = setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &yes, socklen_t(MemoryLayout<Int32>.size))
        var sndBuf: Int32 = 256 * 1024
        var rcvBuf: Int32 = 256 * 1024
        _ = setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &sndBuf, socklen_t(MemoryLayout<Int32>.size))
        _ = setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &rcvBuf, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_addr.s_addr = in_addr_t(0)
        addr.sin_port = in_port_t(0).bigEndian
        let bindRc = withUnsafePointer(to: &addr) { ptr -> Int32 in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                Darwin.bind(fd, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        if bindRc != 0 {
            OshiLog.mesh.info("🧭 P2P: ⚠️ failed to bind UDP socket errno=\(errno)")
            Darwin.close(fd)
            state = .failed
            return
        }

        var bound = sockaddr_in()
        var boundLen = socklen_t(MemoryLayout<sockaddr_in>.size)
        let nameRc = withUnsafeMutablePointer(to: &bound) { ptr -> Int32 in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                getsockname(fd, sa, &boundLen)
            }
        }
        guard nameRc == 0 else {
            OshiLog.mesh.info("🧭 P2P: ⚠️ getsockname failed errno=\(errno)")
            Darwin.close(fd)
            state = .failed
            return
        }
        let port = UInt16(bigEndian: bound.sin_port)

        self.sockFd = fd
        self.localPort = port
        OshiLog.mesh.info("🧭 P2P: bound UDP socket on port \(port)")

        let src = DispatchSource.makeReadSource(fileDescriptor: fd, queue: queue)
        src.setEventHandler { [weak self, fd] in
            self?.drainSocket(fd)
        }
        src.setCancelHandler { [fd] in
            Darwin.close(fd)
        }
        src.resume()
        self.readSource = src

        bindIPv6Socket(port: port)

        addHostCandidates(port: port)
        gatherReflexive()
        // Eager TURN allocation — kick the relay alloc off in parallel with
        // direct probing instead of waiting for every direct pair to fail.
        // When direct hole-punch does not succeed (symmetric NAT on a mobile
        // carrier, common case) the relay pair is already in the local set,
        // already exchanged with the peer, and probes alongside direct so the
        // failover tick promotes it in <500 ms. No effect when direct succeeds
        // first. Mirrors the Android P2PTransport.start() change.
        attemptTurnAllocation()
    }

    private func addHostCandidates(port: UInt16) {
        let all = Self.localInterfaceAddresses()
        let safe = Self.hostAddressesSafeToAdvertise(all)
        if safe.count != all.count {
            OshiLog.mesh.info("🧭 P2P: tunnel up — withholding \(all.count - safe.count) physical host candidate(s)")
        }
        for addr in safe {
            let prio = Self.priority(type: .host, family: addr.family)
            let cand = P2PCandidate(type: .host, ip: addr.ip, port: port, priority: prio)
            if !localCandidates.contains(cand) {
                localCandidates.append(cand)
            }
        }
    }

    // MARK: - srflx discovery ON THE MEDIA SOCKET
    //
    // 🔧 FIX (2026-07-26, direct P2P could never work off-LAN): srflx used to come
    // from `StunClient.discoverReflexiveAddress`, which opens a BRAND-NEW
    // NWConnection and cancels it as soon as the reply lands. The NAT mapping it
    // discovers therefore belongs to a THROWAWAY socket — but it was advertised as
    // our srflx candidate while media leaves from `sockFd`/`localPort`. The peer
    // punched at a port that was never the media port and was already closed.
    // Host candidates use the real media port, which is why direct P2P only ever
    // worked on the same LAN.
    //
    // Now the Binding Request is sent FROM `sockFd` and the reply is demuxed in
    // handleInbound, so the mapping we advertise is the one audio actually uses.

    /// Outstanding STUN transaction ids (hex) we sent from the media socket.
    private var stunTxIds: Set<String> = []
    private var srflxDiscovered = false
    /// The `emit` closure of the in-flight gathering round, so a srflx that lands
    /// before the hard cap can publish candidates immediately.
    private var pendingGatherEmit: (() -> Void)?

    private func sendStunBindingFromMediaSocket() {
        guard sockFd >= 0, !srflxDiscovered else { return }
        let (request, txId) = StunClient.buildBindingRequest()
        let hex = txId.map { String(format: "%02x", $0) }.joined()
        stunTxIds.insert(hex)
        if stunTxIds.count > 8 { stunTxIds.removeFirst() }
        let server = P2PCandidate(type: .host,
                                  ip: StunClient.defaultHost,
                                  port: StunClient.defaultPort,
                                  priority: 0)
        sendtoSocket(request, to: server)
    }

    /// Handle a STUN Binding Success Response that arrived on the media socket.
    /// Returns true when the datagram was consumed as STUN.
    private func handleStunOnMediaSocket(_ data: Data) -> Bool {
        guard StunClient.isBindingSuccessResponse(data),
              let respTx = StunClient.transactionId(of: data) else { return false }
        let hex = respTx.map { String(format: "%02x", $0) }.joined()
        guard stunTxIds.contains(hex) else { return false }
        stunTxIds.remove(hex)
        guard let parsed = StunClient.parseBindingResponse(data: data, txId: respTx) else { return true }

        srflxDiscovered = true
        let family: UInt8 = parsed.ip.contains(":") ? 6 : 4
        let cand = P2PCandidate(type: .srflx,
                                ip: parsed.ip,
                                port: UInt16(parsed.port),
                                priority: Self.priority(type: .srflx, family: family))
        if !localCandidates.contains(cand) {
            localCandidates.append(cand)
        }
        // Provable-on-device correlation: the advertised srflx and the socket the
        // media actually leaves from must be the SAME mapping.
        OshiLog.mesh.info("🧭 P2P: srflx v4=\(parsed.ip):\(parsed.port) mediaPort=\(self.localPort) (discovered ON the media socket)")
        pendingGatherEmit?()
        return true
    }

    private func gatherReflexive() {
        let gatherStart = DispatchTime.now()
        let hardCapMs: Int = 2000
        var emitted = false

        let emit: () -> Void = { [weak self] in
            guard let self = self, !emitted else { return }
            emitted = true
            self.pendingGatherEmit = nil
            let elapsedMs = Int(
                Double(DispatchTime.now().uptimeNanoseconds - gatherStart.uptimeNanoseconds) / 1_000_000.0
            )
            let v4 = self.localCandidates.first(where: { $0.type == .srflx && $0.family == 4 })
            let v6 = self.localCandidates.first(where: { $0.type == .srflx && $0.family == 6 })
            let v4s = v4.map { "\($0.ip):\($0.port)" } ?? "—"
            let v6s = v6.map { "[\($0.ip)]:\($0.port)" } ?? "—"
            OshiLog.mesh.info("🧭 P2P: srflx v4=\(v4s)  v6=\(v6s)  (gathered in \(elapsedMs)ms)")

            // WHY: don't downgrade an already-live transport. On ICE restart
            // (network change mid-call), gathering can run while TURN is
            // already allocated and the state is .relay. The old code
            // unconditionally set .signaling here, regressing the live
            // session to a pre-probing state — p2pCanCarryAudio() then
            // returned false for the rest of the call and audio fell back
            // to VPS WS. Only advance from idle/gathering; preserve
            // .probing/.relay/.connected.
            switch self.state {
            case .idle, .gathering:
                self.state = .signaling
            default:
                break
            }
            self.emitLocalCandidates()

            if self.forceRelayOnly && self.turnConfig != nil {
                OshiLog.mesh.info("🧭 P2P: forceRelay=true — skipping direct probe, allocating TURN")
                self.attemptTurnAllocation()
                return
            }

            if !self.remoteCandidates.isEmpty {
                // Same guard: don't regress a live .relay/.connected pair.
                switch self.state {
                case .signaling:
                    self.state = .probing
                default:
                    break
                }
                self.startProbeTimer()
                self.startFailoverTimer()
                self.armProbingDeadline()
            } else if self.state == .signaling {
                // __TWO_MODE_CALL_PATH_2026_09_23__ B37DC742 sat in `.signaling` for the whole
                // call: the peer's candidates never reached us, so nothing ever armed a
                // deadline and the state never resolved. Arm it here: no remote candidates
                // within the budget ⇒ .failed ⇒ the VPS relay carries the call, cleanly; a
                // late candidate still re-opens probing (addRemoteCandidate).
                self.armProbingDeadline()
            }
        }

        pendingGatherEmit = emit
        srflxDiscovered = false
        stunTxIds.removeAll()

        queue.asyncAfter(deadline: .now() + .milliseconds(hardCapMs)) { emit() }

        // Three attempts from the MEDIA socket. UDP to the STUN server is
        // unreliable (the VPS shows ~33% ICMP loss), and a single lost Binding
        // Request used to mean "no srflx candidate" for the whole call.
        sendStunBindingFromMediaSocket()
        queue.asyncAfter(deadline: .now() + .milliseconds(300)) { [weak self] in
            self?.sendStunBindingFromMediaSocket()
        }
        queue.asyncAfter(deadline: .now() + .milliseconds(900)) { [weak self] in
            self?.sendStunBindingFromMediaSocket()
        }
        // NOTE: the old IPv6 probe went through the same IPv4 STUN host, so it
        // could never return a v6 mapping; and the media socket is AF_INET, so a
        // v6 srflx would not be usable for media anyway. Dropped rather than kept
        // as a decorative candidate.
    }

    // MARK: - Internal: probing (ICE2 — all pairs, 1 Hz)

    private func startProbeTimer() {
        probeTimer?.cancel()
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now(), repeating: .milliseconds(probeIntervalMs))
        t.setEventHandler { [weak self] in
            self?.tickProbes()
        }
        t.resume()
        probeTimer = t
    }

    private func startFailoverTimer() {
        failoverTimer?.cancel()
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + .milliseconds(failoverCheckMs),
                   repeating: .milliseconds(failoverCheckMs))
        t.setEventHandler { [weak self] in
            self?.tickFailover()
        }
        t.resume()
        failoverTimer = t
    }

    /// One probe tick: send a ping to every remote candidate (direct and
    /// relay) and, for each pair that had an outstanding probe which never
    /// got answered, count it as a loss.
    private func tickProbes() {
        // Keep the DIAG_PATH snapshot fresh at the probe cadence (2 Hz).
        refreshDiagCache()
        guard state == .probing || state == .signaling
                || state == .connected || state == .relay
                || (state == .failed && !remoteCandidates.isEmpty) else { return }
        probeTick &+= 1

        let now = Date()
        let nowMs = UInt64(now.timeIntervalSince1970 * 1000)

        // 1. Expire stale probes (outstanding > 2 s with no pong → loss).
        let staleCutoff = nowMs - 2000
        var stale: [String] = []
        for (nonceHex, (ts, key)) in nonceSendTimes where ts < staleCutoff {
            stale.append(nonceHex)
            if let p = pairs[key] {
                p.recordMiss()
                if key == activeKey {
                    activeConsecutiveMisses += 1
                }
            }
        }
        for k in stale { nonceSendTimes.removeValue(forKey: k) }

        // 2. Send fresh pings to every remote pair.
        for cand in remoteCandidates {
            guard Self.acceptsRemoteCandidate(cand.type) else { continue }
            let key = Self.candidateKey(cand)
            if pairs[key] == nil {
                pairs[key] = PairState(candidate: cand, windowSize: successWindow)
            }
            let nonce = Self.randomBytes(12)
            let ts = UInt64(now.timeIntervalSince1970 * 1000)
            let jumbo = pairs[key].map {
                Self.shouldSendJumbo(pairLive: $0.isLive,
                                     verified: Self.mediaVerified(lastJumboPongAt: $0.lastJumboPongAt, now: now),
                                     tick: probeTick)
            } ?? false
            let ping = Self.buildPing(callId: callId, nonce: nonce, tsMs: ts,
                                      size: jumbo ? currentProbeBytes : Self.pingBytes)
            let nonceHex = nonce.map { String(format: "%02x", $0) }.joined()
            nonceSendTimes[nonceHex] = (ts, key)
            pairs[key]?.inflightProbes += 1
            // Relay candidates go through TURN ChannelData; everything else via UDP.
            if cand.type == .relay, let client = turnClient,
               let ch = relayChannels[key] ?? ensureChannelBound(for: cand) {
                client.send(data: ping, viaChannel: ch)
            } else {
                sendtoSocket(ping, to: cand)
            }
        }
    }

    /// Every failover tick: recompute active pair. If the current active is
    /// unhealthy (RTT>300 ms, loss>0.5 for ≥ 2 s, or 3 consecutive misses),
    /// switch to the next-best live pair immediately.
    private func tickFailover() {
        guard !pairs.isEmpty else { return }

        // Update the live flag on each pair based on recency of last pong.
        let now = Date()
        for (_, p) in pairs {
            p.refreshLiveness(now: now)
        }

        // __TWO_MODE_CALL_PATH_2026_09_23__ the active pair must keep proving it carries a
        // media-sized datagram (VPN switched on mid-call ⇒ fragments dropped while small
        // pongs still pass). If it stops and no other verified pair exists, hand the call
        // back to the VPS relay instead of sending into a hole the peer never receives.
        if let ak = activeKey, let active = pairs[ak],
           !Self.pairSelectable(isLive: active.isLive, lastJumboPongAt: active.lastJumboPongAt, now: now) {
            if let altKey = bestLivePairKey(), let alt = pairs[altKey] {
                // A verified alternative exists: move now — the unverified active pair may
                // still pong small pings perfectly, so loss/RTT triggers would never fire.
                promoteActive(to: alt.candidate, rttMs: Int(alt.ewmaRttMs), reason: "media_unverified",
                              oldScore: effectiveScore(active), newScore: effectiveScore(alt))
                return
            }
            let age = active.lastJumboPongAt.map { Int(now.timeIntervalSince($0) * 1000) } ?? -1
            OshiLog.mesh.info("DIAG_ICE_DEMOTE | pair=\(active.candidate.ip):\(active.candidate.port) | live=\(active.isLive) | jumboAgeMs=\(age) | → VPS relay")
            noteConsent(nil)
            activeRemote = nil
            activeKey = nil
            previousActive = nil
            dualSendUntil = nil
            activeLossStartedAt = nil
            activeConsecutiveMisses = 0
            state = .probing
            return
        }

        // Pick the highest-scoring live pair.
        let bestKey = bestLivePairKey()

        // No live pair yet — nothing to compare.
        guard let best = bestKey, let bestPair = pairs[best] else {
            // No live direct pair. If TURN relay is configured but not yet
            // allocated and the direct probing deadline has expired, allocate.
            return
        }

        // If we don't yet have an active pair and `best` is live, promote it.
        if activeKey == nil {
            promoteActive(to: bestPair.candidate,
                          rttMs: Int(bestPair.ewmaRttMs),
                          reason: "probe_fail",
                          oldScore: 0.0,
                          newScore: effectiveScore(bestPair))
            return
        }

        // Check failover conditions on the active pair.
        guard let activeKeyUnwrapped = activeKey, let active = pairs[activeKeyUnwrapped] else { return }

        // Condition A: successRate < 0.5 sustained for >= 2 s.
        if active.successRate < 0.5 {
            if activeLossStartedAt == nil {
                activeLossStartedAt = now
            }
        } else {
            activeLossStartedAt = nil
        }
        let lossSustained = (activeLossStartedAt.map { now.timeIntervalSince($0) >= lossSustainedSeconds }) ?? false

        // Condition B: EWMA RTT > 300 ms.
        let rttBad = active.ewmaRttMs > rttFailoverThreshold && active.successRate > 0.0

        // Condition C: consecutive probe misses ≥ 3 (probe_fail).
        let probeFail = activeConsecutiveMisses >= probeFailThreshold

        if lossSustained || rttBad || probeFail {
            // Hysteresis: only switch when the candidate's *effective* score is
            // strictly better than 1.2× the active pair's effective score.
            // Prevents flapping on marginal RTT jitter.
            let activeEff = effectiveScore(active)
            let bestEff = effectiveScore(bestPair)
            if best != activeKeyUnwrapped, bestEff > switchHysteresis * activeEff {
                let reason: String
                if probeFail { reason = "probe_fail" }
                else if rttBad { reason = "rtt" }
                else { reason = "loss" }
                promoteActive(to: bestPair.candidate,
                              rttMs: Int(bestPair.ewmaRttMs),
                              reason: reason,
                              oldScore: activeEff,
                              newScore: bestEff)
                return
            }

            // No healthier direct pair. If TURN hasn't been allocated yet and
            // all direct pairs are bad, kick off TURN so we have a fallback.
            if turnConfig != nil && !relayAllocationAttempted
                && pairs.values.filter({ $0.isLive }).count <= 1 {
                OshiLog.mesh.info("🧭 P2P: direct pair degraded (reason=\(probeFail ? "probe_fail" : rttBad ? "rtt" : "loss")) — allocating TURN fallback")
                attemptTurnAllocation()
            }
        }
    }

    /// Effective score for comparison: direct (host/srflx) pairs get a 1.5×
    /// bias so they win ties against relay pairs (TURN always adds a hop).
    private func effectiveScore(_ p: PairState) -> Double {
        return (p.candidate.type == .relay) ? p.score : p.score * directBias
    }

    /// Among all live pairs, return the key of the one with the highest
    /// *effective* score (direct bias applied).
    private func bestLivePairKey() -> String? {
        var best: (key: String, score: Double)?
        let now = Date()
        for (key, p) in pairs where Self.pairSelectable(isLive: p.isLive, lastJumboPongAt: p.lastJumboPongAt, now: now)
            && Self.acceptsRemoteCandidate(p.candidate.type) {
            let s = effectiveScore(p)
            if best == nil || s > best!.score {
                best = (key, s)
            }
        }
        return best?.key
    }

    /// Switch the active pair. Sets previousActive and opens the 200 ms
    /// dual-send window. Emits DIAG_ICE_SWITCH.
    private func promoteActive(to new: P2PCandidate,
                                rttMs: Int,
                                reason: String,
                                oldScore: Double = 0.0,
                                newScore: Double = 0.0) {
        let oldCand = activeRemote
        let oldKey = activeKey
        let newKey = Self.candidateKey(new)

        if oldKey == newKey { return }

        if let old = oldCand {
            // Keep old pair warm for the jitter-buffer overlap window.
            previousActive = old
            dualSendUntil = Date().addingTimeInterval(dualSendWindow)
        } else {
            previousActive = nil
            dualSendUntil = nil
        }

        activeRemote = new
        activeKey = newKey
        activeLossStartedAt = nil
        activeConsecutiveMisses = 0
        noteConsent(pairs[newKey]?.lastPongAt ?? Date(), rttMs: Double(max(0, rttMs)))

        // State transition: RELAY vs CONNECTED based on candidate type.
        let newState: P2PTransportState = (new.type == .relay) ? .relay : .connected
        state = newState

        let fromDesc = oldCand.map { Self.pairDescription(local: self.localPort, remote: $0) } ?? "-"
        let toDesc = Self.pairDescription(local: self.localPort, remote: new)
        let oldScoreStr = String(format: "%.4f", oldScore)
        let newScoreStr = String(format: "%.4f", newScore)
        let log = "DIAG_ICE_SWITCH | from=\(fromDesc) | to=\(toDesc) | reason=\(reason) | oldScore=\(oldScoreStr) | newScore=\(newScoreStr)"
        OshiLog.mesh.info("\(log)")

        onPairSelected?(new, rttMs)

        // Now that we're connected, stop the 5 s direct-probe deadline timer;
        // keep the probe ticker running forever (that's the "kept alive"
        // requirement).
        probingDeadlineTimer?.cancel()
        probingDeadlineTimer = nil
    }

    private func armProbingDeadline() {
        probingDeadlineTimer?.cancel()
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + .seconds(5))
        t.setEventHandler { [weak self] in
            guard let self = self else { return }
            // 🔧 FIX (5G/5G calls dropping to VPS instead of TURN relay):
            // The 5s deadline was treating "TURN already allocated" the same
            // as "no TURN configured" — both triggered .failed. But on 5G/5G
            // calls the direct probing reliably fails (symmetric CG-NAT) and
            // TURN allocation completes during the first ~1s. By the time the
            // 5s deadline fires, relayAllocationAttempted is true, so the
            // probe path bails to VPS WS even though TURN-via-TURN probing
            // is still in progress and would succeed with another few seconds.
            //
            // New logic:
            //  - direct probing failed AND TURN never tried → try TURN now,
            //    extend deadline 10s for TURN probing
            //  - direct probing failed AND TURN already allocated → just
            //    extend deadline 10s (give TURN probing more time)
            //  - direct probing failed AND no TURN config → give up to VPS
            if self.activeRemote == nil {
                if !Self.turnRelayMediaEnabled {
                    // Two modes: no media-verified direct pair within the budget ⇒ the VPS
                    // relay carries the call. Probing keeps running (tickProbes accepts
                    // .failed), so a pair that verifies later still upgrades the call.
                    OshiLog.mesh.info("🧭 P2P: no media-verified direct pair after 5s — VPS relay carries the call (probing continues)")
                    self.state = .failed
                    return
                }
                if self.turnConfig != nil {
                    if !self.relayAllocationAttempted {
                        OshiLog.mesh.info("🧭 P2P: ⚠️ direct probing failed after 5s — attempting TURN relay")
                        self.attemptTurnAllocation()
                    } else {
                        OshiLog.mesh.info("🧭 P2P: ⚠️ direct probing failed after 5s — TURN already allocated, giving TURN probing 10s more")
                    }
                    self.armTurnProbingDeadline()
                } else {
                    OshiLog.mesh.info("🧭 P2P: ⚠️ no TURN configured — falling back to VPS")
                    self.state = .failed
                }
            }
        }
        t.resume()
        probingDeadlineTimer = t
    }

    /// Extension of armProbingDeadline: after direct probing fails at 5 s and
    /// we attempt TURN, give the TURN pair probing another 10 s to converge
    /// before declaring the whole transport failed. Total ICE budget: 15 s.
    private func armTurnProbingDeadline() {
        probingDeadlineTimer?.cancel()
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + .seconds(10))
        t.setEventHandler { [weak self] in
            guard let self = self else { return }
            if self.activeRemote == nil {
                OshiLog.mesh.info("🧭 P2P: ⚠️ TURN probing failed after 10s more — falling back to VPS")
                self.state = .failed
            }
        }
        t.resume()
        probingDeadlineTimer = t
    }

    // MARK: - TURN relay

    private func attemptTurnAllocation() {
        guard Self.turnRelayMediaEnabled else { return }   // two modes: direct or VPS relay
        guard let cfg = turnConfig, !relayAllocationAttempted else { return }
        relayAllocationAttempted = true

        let client = TurnClient(
            serverHost: cfg.host,
            serverPort: cfg.port,
            username: cfg.username,
            password: cfg.password,
            useTLS: cfg.useTLS
        )
        self.turnClient = client

        // F1: when the TURN server acks a ChannelBind, mark the channel
        // confirmed and flush any outbound packets we queued while waiting.
        // Without this, the first ~50–200 ms of audio after the relay path
        // becomes active would be silently dropped by coturn.
        client.onChannelBound = { [weak self] ch in
            guard let self = self else { return }
            self.queue.async {
                self.relayChannelConfirmed.insert(ch)
                if let queued = self.pendingRelayPackets.removeValue(forKey: ch),
                   let client = self.turnClient {
                    if !queued.isEmpty {
                        OshiLog.mesh.info("🧭 P2P: TURN ch \(String(format: "0x%04X", ch)) confirmed — flushing \(queued.count) queued packet(s)")
                    }
                    for pkt in queued {
                        client.send(data: pkt, viaChannel: ch)
                    }
                }
            }
        }

        client.onRelayedPacket = { [weak self] data, fromIP, fromPort in
            guard let self = self else { return }
            self.queue.async {
                self.lastInboundAt = Date()
                let first = data.first ?? 0
                if first == 0x31 {
                    // Ping echoed via TURN — reply with pong on the same channel.
                    // Same wildcard rule as direct UDP: cid=0 means "peer has no
                    // call_id" (Android P2PTransport today) and is accepted.
                    let incomingCid = Self.readCallId(data: data) ?? 0
                    let cidOk = (incomingCid == 0) || (incomingCid == self.callId)
                    if !cidOk {
                        OshiLog.mesh.info("DIAG_ICE_PING_DROP | via=relay | from=\(fromIP):\(fromPort) | reason=cid_mismatch | incomingCid=\(String(format: "%016x", incomingCid))")
                        return
                    }
                    if let pong = Self.pongFor(ping: data) {
                        let key = "\(fromIP):\(fromPort)"
                        if let ch = self.relayChannels[key] {
                            client.send(data: pong, viaChannel: ch)
                        }
                    }
                    return
                }
                if first == 0x32 {
                    // Pongs correlate by nonce — no call_id check.
                    self.onPongReceived(pongData: data, fromIP: fromIP, fromPort: fromPort, viaRelay: true)
                    return
                }
                // Application packet.
                self.onPacketReceived?(data)
            }
        }

        client.allocate { [weak self] result in
            guard let self = self else { return }
            self.queue.async {
                switch result {
                case .success(let alloc):
                    self.relayAllocated = alloc
                    OshiLog.mesh.info("🧭 P2P: TURN allocation success — relay \(alloc.relayedAddress):\(alloc.relayedPort)")
                    let cand = P2PCandidate(
                        type: .relay,
                        ip: alloc.relayedAddress,
                        port: alloc.relayedPort,
                        priority: Self.priority(type: .relay,
                                                family: alloc.relayedAddress.contains(":") ? 6 : 4)
                    )
                    if !self.localCandidates.contains(cand) {
                        self.localCandidates.append(cand)
                    }
                    self.emitLocalCandidates()

                    // 🔧 FIX: FORCE a rebind here. Any candidate that arrived while
                    // the Allocate was still in flight has a cached-but-unconfirmed
                    // channel; without forceRebind the sweep did nothing for exactly
                    // the candidates that needed it.
                    for rc in self.remoteCandidates {
                        self.ensureChannelBound(for: rc, forceRebind: true)
                    }
                    self.refreshDiagCache()
                case .failure(let err):
                    // 🔧 FIX: an Allocate failure used to be terminal AND silent —
                    // `relayAllocationAttempted` was already true, so nothing ever
                    // retried and the call had no off-LAN path at all. Allow one
                    // full rebuild of the client (fresh socket, fresh nonce).
                    OshiLog.mesh.info("🧭 P2P: ⚠️ TURN allocate failed: \(err) (retries used=\(self.turnAllocationRetries))")
                    self.turnClient?.close()
                    self.turnClient = nil
                    if self.turnAllocationRetries < 1 {
                        self.turnAllocationRetries += 1
                        self.relayAllocationAttempted = false
                        OshiLog.mesh.info("🧭 P2P: retrying TURN allocation in 1s (attempt \(self.turnAllocationRetries + 1))")
                        self.queue.asyncAfter(deadline: .now() + .seconds(1)) { [weak self] in
                            guard let self = self, self.activeRemote == nil else { return }
                            self.attemptTurnAllocation()
                        }
                        return
                    }
                    OshiLog.mesh.info("🧭 P2P: ❌ TURN allocation exhausted — this call has NO relay path")
                    // If we already have a healthy direct pair, don't flip to failed.
                    if self.activeRemote == nil {
                        self.state = .failed
                    }
                }
            }
        }
    }

    @discardableResult
    private func ensureChannelBound(for remote: P2PCandidate, forceRebind: Bool = false) -> UInt16? {
        guard let client = turnClient else { return nil }
        // Phase 5 ICE hygiene: the relay allocation is IPv4, and coturn rejects
        // private/link-local peers ("Forbidden IP") and IPv6 peers ("Address Family
        // Mismatch") — both seen flooding the call logs. Only relay to PUBLIC IPv4
        // peers; private/IPv6 candidates are still usable for direct P2P, just not
        // worth a wasted ChannelBind round-trip through TURN.
        guard Self.isPublicIPv4(remote) else {
            // 🔧 FIX: this silently made the relay IPv4-only — an IPv6-only carrier
            // peer gets NO ChannelBind at all and therefore no off-LAN path
            // whatsoever. Log it loudly instead of dropping it on the floor. (A real
            // fix needs a second, IPv6 TURN allocation on the server side.)
            OshiLog.mesh.info("🧭 P2P: ⚠️ TURN ChannelBind SKIPPED for \(remote.ip):\(remote.port) (family=\(remote.family), not public IPv4) — no relay path to this candidate")
            return nil
        }
        let key = Self.candidateKey(remote)
        // 🔧 FIX: the cache hit must require CONFIRMATION. Returning a cached but
        // never-ack'd channel made the post-allocation sweep a no-op for exactly
        // the candidates whose first bind raced the Allocate (437), so they never
        // became usable and sendToPair queued/dropped their audio forever.
        if let existing = relayChannels[key] {
            if relayChannelConfirmed.contains(existing) && !forceRebind {
                return existing
            }
            // Known but unconfirmed (or a forced sweep) — re-issue the bind on the
            // SAME channel number so any queued packets flush on the ack.
            OshiLog.mesh.info("🧭 P2P: re-issuing ChannelBind for \(remote.ip):\(remote.port) ch=\(String(format: "0x%04X", existing)) (confirmed=\(self.relayChannelConfirmed.contains(existing)))")
            client.bindChannel(peerAddress: remote.ip,
                               peerPort: remote.port,
                               channelNumber: existing)
            return existing
        }
        let ch = nextChannelNumber
        nextChannelNumber = min(nextChannelNumber &+ 1, 0x7FFE)
        relayChannels[key] = ch
        client.bindChannel(peerAddress: remote.ip,
                           peerPort: remote.port,
                           channelNumber: ch)
        return ch
    }

    /// A peer candidate is relayable via our IPv4 TURN allocation only if it's a
    /// public IPv4 address (coturn denies private/link-local + IPv6 peers).
    private static func isPublicIPv4(_ c: P2PCandidate) -> Bool {
        guard c.family == 4 else { return false }
        let ip = c.ip
        if ip.hasPrefix("10.") || ip.hasPrefix("192.168.") || ip.hasPrefix("127.")
            || ip.hasPrefix("169.254.") || ip.hasPrefix("0.") { return false }
        if ip.hasPrefix("172.") {
            let parts = ip.split(separator: ".")
            if parts.count > 1, let b = Int(parts[1]), (16...31).contains(b) { return false }
        }
        return true
    }

    // MARK: - Internal: socket I/O

    /// __IPV6_DIRECT_PATH_2026_08_14__ Bind a second, PERSISTENT socket for IPv6.
    ///
    /// `localInterfaceAddresses()` has always gathered global IPv6 addresses and
    /// `encodeCandidates` has always put them on the wire — but nothing ever
    /// listened on v6, and `sendtoSocket` reached for a throwaway socket it closed
    /// after every single datagram. The source port therefore changed on each
    /// probe and the peer had nowhere to answer: every IPv6 pair we advertised was
    /// dead on arrival, and the call fell back to hole punching or the relay even
    /// when both ends held a globally routable v6 address and needed neither.
    ///
    /// A global IPv6 address IS the public address — no NAT, no STUN, no relay —
    /// which makes this the cheapest direct path on a modern mobile network.
    ///
    /// Bound to the SAME port as the v4 socket because `addHostCandidates(port:)`
    /// advertises one port for every family; a different port here would publish
    /// candidates pointing at nothing. `IPV6_V6ONLY` keeps it from colliding with
    /// the v4 socket, and every failure path just logs and returns — a missing v6
    /// socket must never cost us the IPv4 path that already works.
    private func bindIPv6Socket(port: UInt16) {
        let fd6 = Darwin.socket(AF_INET6, SOCK_DGRAM, IPPROTO_UDP)
        guard fd6 >= 0 else {
            OshiLog.mesh.info("🧭 P2P: no IPv6 socket (errno=\(errno)) — IPv4 path unaffected")
            return
        }
        var on: Int32 = 1
        _ = setsockopt(fd6, IPPROTO_IPV6, IPV6_V6ONLY, &on, socklen_t(MemoryLayout<Int32>.size))
        _ = setsockopt(fd6, SOL_SOCKET, SO_REUSEADDR, &on, socklen_t(MemoryLayout<Int32>.size))
        _ = setsockopt(fd6, SOL_SOCKET, SO_REUSEPORT, &on, socklen_t(MemoryLayout<Int32>.size))
        var bufSize: Int32 = 256 * 1024
        _ = setsockopt(fd6, SOL_SOCKET, SO_SNDBUF, &bufSize, socklen_t(MemoryLayout<Int32>.size))
        _ = setsockopt(fd6, SOL_SOCKET, SO_RCVBUF, &bufSize, socklen_t(MemoryLayout<Int32>.size))
        _ = fcntl(fd6, F_SETFL, O_NONBLOCK)

        var addr6 = sockaddr_in6()
        addr6.sin6_family = sa_family_t(AF_INET6)
        addr6.sin6_addr = in6addr_any
        addr6.sin6_port = in_port_t(port).bigEndian
        let bindRc6 = withUnsafePointer(to: &addr6) { ptr -> Int32 in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                Darwin.bind(fd6, sa, socklen_t(MemoryLayout<sockaddr_in6>.size))
            }
        }
        guard bindRc6 == 0 else {
            OshiLog.mesh.info("🧭 P2P: IPv6 bind on port \(port) failed (errno=\(errno)) — IPv4 path unaffected")
            Darwin.close(fd6)
            return
        }

        let src6 = DispatchSource.makeReadSource(fileDescriptor: fd6, queue: queue)
        src6.setEventHandler { [weak self, fd6] in
            self?.drainSocket(fd6)
        }
        src6.setCancelHandler { [fd6] in
            Darwin.close(fd6)
        }
        // Published BEFORE resume: the handler can fire on the queue as soon as
        // the source is live, and `sendtoSocket` reads `sockFd6` from the call
        // thread the moment a v6 pair is probed.
        self.sockFd6 = fd6
        self.readSource6 = src6
        src6.resume()
        OshiLog.mesh.info("🧭 P2P: bound IPv6 socket on port \(port) — v6 pairs are now answerable")
    }

    /// Drains one socket. Family-agnostic: the read uses `sockaddr_storage` and
    /// `sockaddrToIPPort` already decodes both families, and `handleInbound`
    /// works on strings — so the IPv6 socket shares this path unchanged.
    private func drainSocket(_ fd: Int32) {
        guard fd >= 0 else { return }
        var buf = [UInt8](repeating: 0, count: 2048)
        while true {
            var from = sockaddr_storage()
            var fromLen = socklen_t(MemoryLayout<sockaddr_storage>.size)
            let n: ssize_t = buf.withUnsafeMutableBufferPointer { bp in
                withUnsafeMutablePointer(to: &from) { fromPtr -> ssize_t in
                    fromPtr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                        recvfrom(fd, bp.baseAddress, bp.count, 0, sa, &fromLen)
                    }
                }
            }
            if n < 0 {
                if errno != EAGAIN && errno != EWOULDBLOCK {
                    OshiLog.mesh.info("🧭 P2P: ⚠️ recvfrom errno=\(errno)")
                }
                return
            }
            if n == 0 { return }
            let data = Data(bytes: buf, count: Int(n))
            let (ip, port) = Self.sockaddrToIPPort(&from)
            lastInboundAt = Date()
            handleInbound(data: data, fromIP: ip, fromPort: port)
        }
    }

    /// Write directly over UDP to a given remote candidate.
    private func sendtoSocket(_ data: Data, to remote: P2PCandidate) {
        let fd = sockFd
        guard fd >= 0 else { return }
        if remote.family == 4 {
            var addr = sockaddr_in()
            addr.sin_family = sa_family_t(AF_INET)
            addr.sin_port = in_port_t(remote.port).bigEndian
            let rc = remote.ip.withCString { inet_pton(AF_INET, $0, &addr.sin_addr) }
            guard rc == 1 else { return }
            _ = data.withUnsafeBytes { buf -> ssize_t in
                guard let p = buf.baseAddress else { return -1 }
                return withUnsafePointer(to: &addr) { aptr -> ssize_t in
                    aptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                        sendto(fd, p, buf.count, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
        } else {
            var addr = sockaddr_in6()
            addr.sin6_family = sa_family_t(AF_INET6)
            addr.sin6_port = in_port_t(remote.port).bigEndian
            let rc = remote.ip.withCString { inet_pton(AF_INET6, $0, &addr.sin6_addr) }
            guard rc == 1 else { return }
            // __IPV6_DIRECT_PATH_2026_08_14__ Send from the PERSISTENT v6 socket.
            // This used to open a socket per datagram and close it on the way out,
            // so the source port changed on every probe and the peer had no
            // reachable address to answer — which is why no v6 pair ever
            // validated. Nothing to send from means nothing to send.
            let vfd = sockFd6
            guard vfd >= 0 else { return }
            _ = data.withUnsafeBytes { buf -> ssize_t in
                guard let p = buf.baseAddress else { return -1 }
                return withUnsafePointer(to: &addr) { aptr -> ssize_t in
                    aptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                        sendto(vfd, p, buf.count, 0, sa, socklen_t(MemoryLayout<sockaddr_in6>.size))
                    }
                }
            }
        }
    }

    /// Route an application packet to a specific pair — direct or via TURN.
    private func sendToPair(_ data: Data, remote: P2PCandidate) {
        if remote.type == .relay, let client = turnClient {
            let key = Self.candidateKey(remote)
            if let ch = relayChannels[key] ?? ensureChannelBound(for: remote) {
                // F1: only ChannelData if the server has ack'd this bind.
                // Otherwise coturn drops the frame (RFC 5766 §11.2). Queue
                // the first frames; `onChannelBound` flushes them in order.
                if relayChannelConfirmed.contains(ch) {
                    client.send(data: data, viaChannel: ch)
                } else {
                    var q = pendingRelayPackets[ch] ?? []
                    if q.count < pendingRelayPacketsCap {
                        q.append(data)
                        pendingRelayPackets[ch] = q
                    }
                    // Bounded drop above the cap — first ~1 s of audio is what
                    // matters for continuity; older queued frames are stale anyway.
                }
                return
            }
        }
        sendtoSocket(data, to: remote)
    }

    // MARK: - Internal: inbound

    private func handleInbound(data: Data, fromIP: String, fromPort: UInt16) {
        // 🔧 FIX: STUN Binding Success Responses now arrive on THIS socket (see
        // sendStunBindingFromMediaSocket). Demux them before the app's own type
        // switch. Discriminator is (message type == 0x0101) + magic cookie +
        // known transaction id — NOT `(first & 0xC0) == 0`, which collides with
        // OSHI's own 0x05/0x0E/0x0F/0x14/0x15/0x16/0x17 packet types.
        if handleStunOnMediaSocket(data) { return }
        let first = data[data.startIndex]
        switch first {
        case 0x31:
            // Incoming ping — must always be answered with a pong so the
            // sender can promote the pair. The call_id check is a WILDCARD:
            // bytes [1..9] = 0 means "sender does not carry a call id" (the
            // Android P2PTransport currently does this) and is treated as a
            // match. Any non-zero mismatching call_id is still rejected to
            // prevent cross-call leakage when multiple concurrent calls
            // somehow share a socket.
            let incomingCid = Self.readCallId(data: data) ?? 0
            let cidOk = (incomingCid == 0) || (incomingCid == callId)
            let cidHex = String(format: "%016x", incomingCid)
            if !cidOk {
                OshiLog.mesh.info("DIAG_ICE_PING_DROP | from=\(fromIP):\(fromPort) | reason=cid_mismatch | incomingCid=\(cidHex) | localCid=\(String(format: "%016x", callId))")
                return
            }
            if let pong = Self.pongFor(ping: data) {
                let remote = P2PCandidate(type: .host,
                                          ip: fromIP,
                                          port: fromPort,
                                          priority: 0)
                sendtoSocket(pong, to: remote)
                // RFC 5245 PEER-REFLEXIVE candidate. A ping from an unsignalled
                // source IP:port means the peer's NAT remapped its egress to
                // somewhere we don't know about (symmetric / port-randomising
                // mobile NAT — exactly what's seen in the iPhone⇄Android logs).
                // Add the observed source as a candidate, create its pair so
                // probing + scoring can promote it. Without this, our send
                // path keeps blasting the stale signaled SRFLX port forever.
                ensureRemoteCandidateForObservedSource(ip: fromIP, port: fromPort)
                let key = "\(fromIP):\(fromPort)"
                // Bound this dedup set so a spoofed-source flood can't grow it without limit.
                if loggedPingSources.count < 256, !loggedPingSources.contains(key) {
                    loggedPingSources.insert(key)
                    OshiLog.mesh.info("DIAG_ICE_PING_RX | from=\(fromIP):\(fromPort) | cidMatch=\(incomingCid == callId ? "exact" : "wildcard") | incomingCid=\(cidHex)")
                }
            }
        case 0x32:
            // Pongs: accept regardless of call_id — the 12-byte nonce
            // (correlated via `nonceSendTimes`) is authoritative for
            // linking a pong back to a ping we sent.
            onPongReceived(pongData: data, fromIP: fromIP, fromPort: fromPort, viaRelay: false)
        default:
            onPacketReceived?(data)
        }
    }

    /// Idempotently add an observed source IP:port as a peer-reflexive
    /// candidate so `pairs` and `remoteCandidates` track it. First call
    /// for a given (ip, port) creates a SRFLX-typed candidate, registers the
    /// pair state, and (re)kicks the probe loop — the scoring pass can then
    /// elect it as active when its EWMA RTT and success rate justify it.
    private func ensureRemoteCandidateForObservedSource(ip: String, port: UInt16) {
        let already = remoteCandidates.contains(where: { $0.ip == ip && $0.port == port })
        if already { return }
        // Security: bound peer-reflexive candidate growth. Each unsignalled ping source can
        // create a candidate + pair; a spoofed-source flood would otherwise exhaust memory and
        // turn this device into a packet reflector (H-MSG-2). Cap the number of peer-reflexive
        // (SRFLX) candidates we will learn for a single call.
        let maxPeerReflexiveCandidates = 8
        let peerReflexiveCount = remoteCandidates.filter { $0.type == .srflx }.count
        if peerReflexiveCount >= maxPeerReflexiveCandidates {
            OshiLog.mesh.info("🧭 P2P: peer-reflexive candidate cap (\(maxPeerReflexiveCandidates)) reached; ignoring source \(ip):\(port)")
            return
        }
        let family: UInt8 = ip.contains(":") ? 6 : 4
        let cand = P2PCandidate(type: .srflx,
                                ip: ip,
                                port: port,
                                priority: Self.priority(type: .srflx, family: family))
        remoteCandidates.append(cand)
        let key = Self.candidateKey(cand)
        if pairs[key] == nil {
            pairs[key] = PairState(candidate: cand, windowSize: successWindow)
        }
        // Make sure both timers are armed so the new pair gets probed and
        // the failover loop reconsiders selection. Idempotent — both
        // start* helpers cancel any existing timer before scheduling.
        if state == .signaling || state == .probing {
            startProbeTimer()
            startFailoverTimer()
        }
        OshiLog.mesh.info("🧭 P2P: peer-reflexive candidate added: \(ip):\(port)")
    }

    /// Pong received — update pair stats and (re)evaluate active selection.
    private func onPongReceived(pongData: Data, fromIP: String, fromPort: UInt16, viaRelay: Bool) {
        // Map the source back to a candidate. Prefer exact match; otherwise
        // synthesize a candidate for the observed mapped address (same as the
        // previous behavior — covers symmetric-NAT peers whose mapped endpoint
        // differs from any advertised srflx).
        let matchedCand = remoteCandidates.first(where: { $0.ip == fromIP && $0.port == fromPort })
            ?? P2PCandidate(type: viaRelay ? .relay : .srflx,
                            ip: fromIP,
                            port: fromPort,
                            priority: Self.priority(type: viaRelay ? .relay : .srflx,
                                                    family: fromIP.contains(":") ? 6 : 4))

        let key = Self.candidateKey(matchedCand)
        let firstPongForPair = pairs[key]?.lastPongAt == nil
        if pairs[key] == nil {
            pairs[key] = PairState(candidate: matchedCand, windowSize: successWindow)
        }

        // Recover the nonce from the pong payload so we can correlate to the
        // ping we sent and measure RTT from our monotonic send time.
        var rttMs: Int = -1
        if pongData.count >= 29 {
            let nonceStart = pongData.startIndex + 9
            let nonce = pongData.subdata(in: nonceStart..<(nonceStart + 12))
            let nonceHex = nonce.map { String(format: "%02x", $0) }.joined()
            if let (sentMs, _) = nonceSendTimes.removeValue(forKey: nonceHex) {
                let now = UInt64(Date().timeIntervalSince1970 * 1000)
                rttMs = now >= sentMs ? Int(now - sentMs) : 0
            } else {
                // Fallback: use the embedded ts_ms field.
                let tsStart = pongData.startIndex + 21
                let sub = pongData.subdata(in: tsStart..<(tsStart + 8))
                var ts: UInt64 = 0
                _ = Swift.withUnsafeMutableBytes(of: &ts) { dest in sub.copyBytes(to: dest) }
                ts = UInt64(bigEndian: ts)
                let now = UInt64(Date().timeIntervalSince1970 * 1000)
                rttMs = now >= ts ? Int(now - ts) : 0
            }
        }
        if rttMs < 0 { rttMs = 0 }

        // Apply EWMA + success rolling window.
        pairs[key]?.recordHit(rttMs: Double(rttMs), alpha: ewmaAlpha)
        if pongData.count >= currentProbeBytes, let p = pairs[key] {
            if p.lastJumboPongAt == nil {
                OshiLog.mesh.info("DIAG_ICE_MEDIA_VERIFIED | pair=\(fromIP):\(fromPort) | bytes=\(pongData.count) | rttMs=\(rttMs)")
            }
            p.lastJumboPongAt = Date()
        }

        if firstPongForPair {
            OshiLog.mesh.info("DIAG_ICE_PONG_RX | pair=\(fromIP):\(fromPort) | via=\(viaRelay ? "relay" : "direct") | rttMs=\(rttMs)")
        }

        // If this pong is for the current active pair, reset the miss counter.
        if key == activeKey {
            activeConsecutiveMisses = 0
            noteConsent(Date(), rttMs: pairs[key]?.ewmaRttMs ?? 0)
        }

        // On the very first successful probe across ALL pairs, promote the
        // best live pair to active — this is the ICE2 analogue of the old
        // "first pong wins" behavior but it picks the highest-scoring live
        // pair rather than literally the first one.
        if activeKey == nil {
            if let bestKey = bestLivePairKey(), let bestPair = pairs[bestKey] {
                promoteActive(to: bestPair.candidate,
                              rttMs: Int(bestPair.ewmaRttMs),
                              reason: "probe_fail",
                              oldScore: 0.0,
                              newScore: effectiveScore(bestPair))
            }
        }
    }

    // MARK: - Internal: teardown

    private func teardownProbes() {
        probeTimer?.cancel()
        probeTimer = nil
        failoverTimer?.cancel()
        failoverTimer = nil
        probingDeadlineTimer?.cancel()
        probingDeadlineTimer = nil
        nonceSendTimes.removeAll()
    }

    private func teardownAll() {
        teardownProbes()
        if let src = readSource {
            src.cancel()
            readSource = nil
        } else if sockFd >= 0 {
            Darwin.close(sockFd)
        }
        sockFd = -1
        // __IPV6_DIRECT_PATH_2026_08_14__ Same ownership rule as v4: the cancel
        // handler owns the close, so closing here too would double-close the fd.
        if let src6 = readSource6 {
            src6.cancel()
            readSource6 = nil
        } else if sockFd6 >= 0 {
            Darwin.close(sockFd6)
        }
        sockFd6 = -1
        remoteCandidates.removeAll()
        localCandidates.removeAll()
        pairs.removeAll()
        activeRemote = nil
        activeKey = nil
        previousActive = nil
        dualSendUntil = nil
        activeLossStartedAt = nil
        activeConsecutiveMisses = 0
        lastInboundAt = nil
        loggedPingSources.removeAll()
        localPort = 0
        turnClient?.close()
        turnClient = nil
        relayAllocated = nil
        relayChannels.removeAll()
        relayAllocationAttempted = false
        turnAllocationRetries = 0
        // A configureTurn arriving after teardown must not resurrect a dead socket.
        didStart = false
        srflxDiscovered = false
        stunTxIds.removeAll()
        pendingGatherEmit = nil
        nextChannelNumber = 0x4000
    }

    // MARK: - Helpers: framing

    /// [0x31][call_id 8B BE][nonce 12B][ts_ms 8B BE][zero padding up to `size`].
    /// Padding is backward compatible: every shipped build answers with a byte copy.
    static func buildPing(callId: UInt64, nonce: Data, tsMs: UInt64, size: Int = pingBytes) -> Data {
        var out = Data(capacity: max(size, pingBytes))
        out.append(0x31)
        withUnsafeBytes(of: callId.bigEndian) { out.append(contentsOf: $0) }
        out.append(nonce)
        withUnsafeBytes(of: tsMs.bigEndian) { out.append(contentsOf: $0) }
        if size > out.count { out.append(Data(count: size - out.count)) }
        return out
    }

    static func pongFor(ping: Data) -> Data? {
        guard ping.count >= 29, ping[ping.startIndex] == 0x31 else { return nil }
        var out = ping
        out[out.startIndex] = 0x32
        return out
    }

    fileprivate static func matchesCallId(data: Data, callId: UInt64) -> Bool {
        guard let cid = readCallId(data: data) else { return false }
        return cid == callId
    }

    /// Parse bytes [1..9] as a big-endian UInt64 call_id, or nil when the
    /// packet is too short. A return value of 0 is a valid wildcard that
    /// the caller may or may not treat specially (handleInbound does).
    fileprivate static func readCallId(data: Data) -> UInt64? {
        guard data.count >= 9 else { return nil }
        let sub = data.subdata(in: (data.startIndex + 1)..<(data.startIndex + 9))
        var cid: UInt64 = 0
        _ = Swift.withUnsafeMutableBytes(of: &cid) { dest in sub.copyBytes(to: dest) }
        cid = UInt64(bigEndian: cid)
        return cid
    }

    // MARK: - Helpers: candidates

    private static func candidateKey(_ c: P2PCandidate) -> String {
        return "\(c.ip):\(c.port)"
    }

    private static func pairDescription(local: UInt16, remote: P2PCandidate) -> String {
        let t: String
        switch remote.type {
        case .host:  t = "host"
        case .srflx: t = "srflx"
        case .relay: t = "relay"
        }
        return ":\(local)→\(remote.ip):\(remote.port) \(t)"
    }

    private static func priority(type: P2PCandidateType, family: UInt8) -> UInt32 {
        let base: UInt32
        switch type {
        case .host:  base = 126
        case .srflx: base = 100
        case .relay: base = 0
        }
        let familyAdj: UInt32 = (family == 4) ? 1 : 0
        return (base << 24) | (familyAdj << 8)
    }

    private static func randomBytes(_ n: Int) -> Data {
        var d = Data(count: n)
        _ = d.withUnsafeMutableBytes { buf -> Int32 in
            guard let p = buf.baseAddress else { return -1 }
            return SecRandomCopyBytes(kSecRandomDefault, n, p)
        }
        return d
    }

    private static func sockaddrToIPPort(_ ss: inout sockaddr_storage) -> (String, UInt16) {
        let family = ss.ss_family
        if Int32(family) == AF_INET {
            return withUnsafePointer(to: &ss) { sp -> (String, UInt16) in
                sp.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { sin in
                    var addr = sin.pointee.sin_addr
                    var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
                    inet_ntop(AF_INET, &addr, &buf, socklen_t(buf.count))
                    let ip = String(cString: buf)
                    let port = UInt16(bigEndian: sin.pointee.sin_port)
                    return (ip, port)
                }
            }
        } else if Int32(family) == AF_INET6 {
            return withUnsafePointer(to: &ss) { sp -> (String, UInt16) in
                sp.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { sin6 in
                    var addr = sin6.pointee.sin6_addr
                    var buf = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
                    inet_ntop(AF_INET6, &addr, &buf, socklen_t(buf.count))
                    let ip = String(cString: buf)
                    let port = UInt16(bigEndian: sin6.pointee.sin6_port)
                    return (ip, port)
                }
            }
        }
        return ("", 0)
    }

    /// One interface address, reduced to what candidate selection needs.
    struct LocalAddress: Equatable {
        let ifName: String
        let ip: String
        let family: UInt8
    }

    /// Interface-name prefixes that carry a VPN / tunnel on Apple platforms.
    /// `utun` covers NEPacketTunnelProvider and the built-in IKEv2/WireGuard
    /// clients, `ipsec` the legacy IPsec client, `ppp` L2TP.
    private static let tunnelPrefixes = ["utun", "ipsec", "ppp", "tun", "tap"]

    static func isTunnelInterface(_ name: String) -> Bool {
        tunnelPrefixes.contains { name.hasPrefix($0) }
    }

    /// Which local addresses may be advertised to the peer as host candidates.
    ///
    /// WHY: `getifaddrs` keeps reporting en0 / pdp_ip0 while a VPN is up — iOS
    /// does not hide the physical link from apps. Advertising it hands the peer
    /// the real LAN IP (and, when the VPN does not capture v6, a globally
    /// routable IPv6) of someone who turned a VPN on precisely so that would not
    /// happen. No OS bug is needed for this: it is our own ICE gathering. So as
    /// soon as a tunnel carries a routable address, ONLY tunnel addresses are
    /// advertised. Direct hole-punch over the tunnel usually fails and the pair
    /// falls back to TURN — that is the intended trade.
    ///
    /// Link-local (`fe80:`) addresses are already dropped before this runs, so a
    /// `utun` that iOS keeps up for Handoff/Hotspot with no routable address
    /// does not count as a tunnel and cannot suppress a normal LAN call.
    static func hostAddressesSafeToAdvertise(_ all: [LocalAddress]) -> [LocalAddress] {
        // 1. Never advertise a globally routable address. This is the load-bearing
        //    rule, and interface names cannot carry it: a real iPhone keeps ~15
        //    ipsec/utun interfaces up for Wi-Fi Calling and IMS even with no VPN, and
        //    iOS ALIASES the carrier's global IPv6 onto them — measured on an iPhone
        //    17 Pro Max, pdp_ip3 and ipsec5 and ipsec9 all carried the same 2a04:cec0:…
        //    address, so a name-based filter happily re-advertised it. A host candidate
        //    only ever helps a peer on the same link, so a global address buys no
        //    connectivity there while being the single most identifying thing we could
        //    hand over. Off-link reachability is what srflx and relay are for.
        let privateOnly = all.filter { !isGloballyRoutable($0.ip, family: $0.family) }
        // 2. With a tunnel up, keep only tunnel addresses, so the LAN address of
        //    someone on a VPN does not reach the peer (TunnelCrack's LocalNet shape).
        //    Direct hole-punch over the tunnel usually fails and the pair falls back to
        //    TURN — the intended trade.
        let tunnels = privateOnly.filter { isTunnelInterface($0.ifName) }
        let pool = tunnels.isEmpty ? privateOnly : tunnels
        // 3. The same address is aliased onto several interfaces; advertise once.
        var seen = Set<String>()
        return pool.filter { seen.insert($0.ip).inserted }
    }

    /// Is this address reachable from the public Internet — i.e. does handing it to
    /// the peer identify the user's ISP, carrier or household?
    ///
    /// IPv4: everything outside the private/CGNAT/link-local/loopback blocks and
    /// 192.0.0.0/24 (IETF protocol assignments — 464XLAT's 192.0.0.4/.6 live there and
    /// appear on every iPhone).
    /// IPv6: global unicast is 2000::/3. ULA (fc00::/7) and link-local are not.
    static func isGloballyRoutable(_ ip: String, family: UInt8) -> Bool {
        if family == 6 {
            guard let head = ip.split(separator: ":", omittingEmptySubsequences: false).first,
                  let group = UInt16(head, radix: 16) else { return false }
            return (group & 0xE000) == 0x2000
        }
        let o = ip.split(separator: ".").compactMap { UInt8($0) }
        guard o.count == 4 else { return false }
        switch (o[0], o[1], o[2]) {
        case (10, _, _), (127, _, _):                     return false
        case (172, 16...31, _):                           return false
        case (192, 168, _):                               return false
        case (192, 0, 0):                                 return false
        case (169, 254, _):                               return false
        case (100, 64...127, _):                          return false
        default:                                          return true
        }
    }

    /// What we are willing to hand the peer.
    ///
    /// WHY: "Always use relay server" used to change only which pair carried
    /// media — gathering still emitted host + srflx to the peer, so the peer
    /// learned our address anyway and the toggle did not do what its label
    /// promises. Under force-relay the peer gets the relay candidate and
    /// nothing else.
    static func publishableCandidates(_ all: [P2PCandidate],
                                      forceRelay: Bool) -> [P2PCandidate] {
        forceRelay ? all.filter { $0.type == .relay } : all
    }

    static func localInterfaceAddresses() -> [LocalAddress] {
        var out: [LocalAddress] = []
        var ifaddrPtr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddrPtr) == 0, let first = ifaddrPtr else { return out }
        defer { freeifaddrs(first) }

        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let cur = ptr {
            defer { ptr = cur.pointee.ifa_next }
            guard let addrPtr = cur.pointee.ifa_addr else { continue }
            let family = addrPtr.pointee.sa_family
            guard family == UInt8(AF_INET) || family == UInt8(AF_INET6) else { continue }

            let flags = Int32(cur.pointee.ifa_flags)
            guard (flags & IFF_UP) == IFF_UP,
                  (flags & IFF_LOOPBACK) == 0 else { continue }

            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let sockLen = (family == UInt8(AF_INET))
                ? socklen_t(MemoryLayout<sockaddr_in>.size)
                : socklen_t(MemoryLayout<sockaddr_in6>.size)
            let rc = getnameinfo(addrPtr, sockLen,
                                 &host, socklen_t(host.count),
                                 nil, 0, NI_NUMERICHOST)
            guard rc == 0 else { continue }
            var ip = String(cString: host)
            let ifName = cur.pointee.ifa_name.map { String(cString: $0) } ?? ""

            if family == UInt8(AF_INET6) {
                if let pct = ip.firstIndex(of: "%") { ip = String(ip[..<pct]) }
                if ip.hasPrefix("fe80:") { continue }
                out.append(LocalAddress(ifName: ifName, ip: ip, family: 6))
            } else {
                out.append(LocalAddress(ifName: ifName, ip: ip, family: 4))
            }
        }
        return out
    }

    // MARK: - Candidate encoding (wire-compat — version 0x01)

    static func encodeCandidates(_ cands: [P2PCandidate]) -> Data {
        var out = Data()
        out.append(0x01)
        out.append(UInt8(min(cands.count, 255)))
        for c in cands {
            guard let addrBytes = parseIPToBytes(c.ip) else { continue }
            out.append(c.type.rawValue)
            out.append(c.family)
            var be = c.port.bigEndian
            withUnsafeBytes(of: &be) { out.append(contentsOf: $0) }
            out.append(addrBytes)
            var prio = c.priority.bigEndian
            withUnsafeBytes(of: &prio) { out.append(contentsOf: $0) }
        }
        return out
    }

    static func decodeCandidates(_ data: Data) -> [P2PCandidate] {
        guard data.count >= 2 else { return [] }
        var i = data.startIndex
        let version = data[i]; i += 1
        guard version == 0x01 else { return [] }
        let n = Int(data[i]); i += 1
        var out: [P2PCandidate] = []
        for _ in 0..<n {
            guard i + 4 <= data.endIndex else { break }
            guard let type = P2PCandidateType(rawValue: data[i]) else { break }
            i += 1
            let family = data[i]; i += 1
            guard family == 4 || family == 6 else { break }
            let portHi = UInt16(data[i]); i += 1
            let portLo = UInt16(data[i]); i += 1
            let port = (portHi << 8) | portLo
            let addrLen = Int(family == 4 ? 4 : 16)
            guard i + addrLen + 4 <= data.endIndex else { break }
            let addrSlice = data.subdata(in: i..<(i + addrLen))
            i += addrLen
            let ip = bytesToIPString(addrSlice, family: family)
            var prio: UInt32 = 0
            let prioSlice = data.subdata(in: i..<(i + 4))
            _ = Swift.withUnsafeMutableBytes(of: &prio) { dest in prioSlice.copyBytes(to: dest) }
            prio = UInt32(bigEndian: prio)
            i += 4
            out.append(P2PCandidate(type: type, ip: ip, port: port, priority: prio))
        }
        return out
    }

    private static func parseIPToBytes(_ ip: String) -> Data? {
        if ip.contains(":") {
            var addr = in6_addr()
            let rc = ip.withCString { inet_pton(AF_INET6, $0, &addr) }
            guard rc == 1 else { return nil }
            return withUnsafeBytes(of: &addr) { Data($0) }
        } else {
            var addr = in_addr()
            let rc = ip.withCString { inet_pton(AF_INET, $0, &addr) }
            guard rc == 1 else { return nil }
            return withUnsafeBytes(of: &addr) { Data($0) }
        }
    }

    private static func bytesToIPString(_ data: Data, family: UInt8) -> String {
        if family == 4 {
            let b = [UInt8](data)
            guard b.count == 4 else { return "" }
            return "\(b[0]).\(b[1]).\(b[2]).\(b[3])"
        } else {
            var addr = in6_addr()
            _ = Swift.withUnsafeMutableBytes(of: &addr) { dest in data.copyBytes(to: dest) }
            var buf = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
            let p = inet_ntop(AF_INET6, &addr, &buf, socklen_t(buf.count))
            return p != nil ? String(cString: buf) : ""
        }
    }
}

// MARK: - Per-pair probe scoring state

/// Tracks EWMA RTT + rolling success rate over the last N probes for one
/// candidate pair. Instances are owned by `P2PTransport.pairs` and only ever
/// read/written from the transport's serial queue.
private final class PairState {
    let candidate: P2PCandidate
    let windowSize: Int

    /// EWMA of RTT in milliseconds. -1 until the first pong lands.
    var ewmaRttMs: Double = -1.0

    /// Circular buffer of recent probe results (true = pong received).
    private var window: [Bool]
    private var windowIndex: Int = 0
    private var windowFilled: Int = 0

    /// Last time we received a pong on this pair.
    var lastPongAt: Date?

    /// Last pong to a JUMBO (media-sized) ping — see `P2PTransport.jumboProbeBytes`.
    var lastJumboPongAt: Date?

    /// Number of probes sent but not yet answered.
    var inflightProbes: Int = 0

    /// "Live" = at least one successful pong AND lastPongAt within 5 s.
    var isLive: Bool = false

    init(candidate: P2PCandidate, windowSize: Int) {
        self.candidate = candidate
        self.windowSize = windowSize
        self.window = Array(repeating: false, count: windowSize)
    }

    var successRate: Double {
        if windowFilled == 0 { return 0.0 }
        let hits = window.prefix(windowFilled).filter { $0 }.count
        return Double(hits) / Double(windowFilled)
    }

    /// score = (1 / (RTT_ms + 1)) × successRate
    /// Higher is better. 0 when no pong received yet.
    var score: Double {
        if ewmaRttMs < 0 { return 0.0 }
        return (1.0 / (ewmaRttMs + 1.0)) * successRate
    }

    func recordHit(rttMs: Double, alpha: Double) {
        if ewmaRttMs < 0 {
            ewmaRttMs = rttMs
        } else {
            ewmaRttMs = alpha * rttMs + (1.0 - alpha) * ewmaRttMs
        }
        pushWindow(true)
        lastPongAt = Date()
        isLive = true
        if inflightProbes > 0 { inflightProbes -= 1 }
    }

    func recordMiss() {
        pushWindow(false)
        if inflightProbes > 0 { inflightProbes -= 1 }
    }

    private func pushWindow(_ hit: Bool) {
        window[windowIndex] = hit
        windowIndex = (windowIndex + 1) % windowSize
        if windowFilled < windowSize { windowFilled += 1 }
    }

    func refreshLiveness(now: Date) {
        if let last = lastPongAt, now.timeIntervalSince(last) > 5.0 {
            isLive = false
        }
    }
}
