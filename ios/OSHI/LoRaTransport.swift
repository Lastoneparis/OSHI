//
//  LoRaTransport.swift
//  OSHI — LoRa as one more transport, and nothing else changed.
//
//  THE CONSTRAINT THIS FILE RESPECTS
//  ---------------------------------
//  Encryption, message routing, the Bluetooth and Wi-Fi meshes and the ~2-3 s
//  internet fallback are NOT touched. They work; they stay. What was missing was
//  a *seat at the table* for LoRa: today the only mention of it in the messaging
//  layer is a single last-resort call inside the IPFS failure branch
//  (`MessageManager.swift`, `queueOrSendSecureMessage`). That call site keeps
//  working exactly as it does now.
//
//  What this file adds is the shape LoRa was missing:
//    * a transport description the rest of the app can reason about,
//    * measured quality per transport, for diagnostics and node health,
//    * one place that answers "can LoRa carry this right now, and how well".
//
//  `MessageTransport` DESCRIBES the transports that already exist rather than
//  replacing them. Mesh and internet keep their own implementations; only LoRa
//  is implemented against this protocol, because only LoRa is new. Retrofitting
//  the others would mean rewriting working delivery code, which is precisely
//  what was asked not to happen.
//

import Foundation
import Combine

// MARK: - Transport identity

/// The transports a message can travel over.
///
/// The ORDER is not here. It is in `TransportPolicy.order(_:)`, which the
/// sending path actually calls — see `__PREFERENCE_RANK_WAS_A_LIE_2026_10_20__`
/// below for why that distinction earned a comment.
public enum TransportKind: String, CaseIterable, Sendable {
    case bluetoothMesh
    case wifiMesh
    case loRa
    case internetRelay

    /// Honest ranges, for the onboarding screen. These are the figures the app
    /// already commits to elsewhere; LoRa's are the usual field numbers and are
    /// presented as ranges because terrain, antenna and regulation dominate.
    public var typicalRange: String {
        switch self {
        case .bluetoothMesh: return "transport.range.ble".localized
        case .wifiMesh:      return "transport.range.wifi".localized
        case .loRa:          return "transport.range.lora".localized
        case .internetRelay: return "transport.range.internet".localized
        }
    }
}

// MARK: - Routing policy

/// WHO decides where a message goes next, and whether the relay may be used at
/// all.
///
/// __PREFERENCE_RANK_WAS_A_LIE_2026_10_20__ A `preferenceRank` used to live on
/// `TransportKind`, carrying this comment:
///
///     The order is DESCRIPTIVE — it documents the behaviour the messaging
///     layer already implements … Changing a case here does not change routing.
///
/// A constant called `preferenceRank`, on a type called `TransportKind`, that
/// has no effect on routing, is a trap rather than documentation: the next
/// person wanting to reorder the transports edits it, observes nothing change,
/// and goes hunting. Meanwhile the real order was spread over three branches of
/// `MessageManager.fallbackToIPFS` and a `meshAvailableNow` ladder 1,200 lines
/// away, so answering "what will this message try next?" meant reading three
/// files.
///
/// It is gone. The order lives here AND is consulted, which is what lets the
/// two preferences below mean anything.
public enum TransportPolicy {

    // MARK: Stored preferences

    public static let internetSendingDisabledKey = "oshi_transport_internet_sending_disabled"
    public static let preferRadioOverRelayKey    = "oshi_transport_prefer_radio_over_relay"

    /// __NEVER_OVER_THE_INTERNET_2026_10_20__ A GUARANTEE, not a preference.
    ///
    /// Someone who pairs a long-range radio so as not to depend on
    /// infrastructure is not asking for the relay to be deprioritised; they are
    /// asking not to be put back on it quietly when the mesh blinks. Today a
    /// 2–3 s mesh timeout is enough for the envelope to leave over the relay,
    /// and the relay keeps what the relay keeps: sender and recipient keys,
    /// device ids, timestamps, sizes. The content is sealed; that envelope is
    /// not.
    ///
    /// So this is enforced INSIDE `v2TrySend` and `uploadToIPFS`, not at the
    /// branches that call them. There are six such call sites, and a guarantee
    /// any one of them can forget is not a guarantee.
    ///
    /// ⚠️ SCOPE, written down here so that no UI string oversells it: this
    /// stops OUTGOING traffic from using the internet. The app still PULLS from
    /// the relay, so the server keeps seeing this device's address and its poll
    /// timing. Stopping that as well would stop incoming messages, which is a
    /// different switch and a different promise.
    public static var internetAllowedForSending: Bool {
        get { !UserDefaults.standard.bool(forKey: internetSendingDisabledKey) }
        set { UserDefaults.standard.set(!newValue, forKey: internetSendingDisabledKey) }
    }

    /// Offer the paired radio BEFORE the relay, for text.
    ///
    /// Off by default, and deliberately not "LoRa first for everything": a LoRa
    /// frame carries on the order of 180–240 bytes (`maximumPayloadBytes`) at
    /// seconds per frame, so putting media or the control chatter on it ahead of
    /// a working network path would feel like a broken app rather than a private
    /// one. `order(_:)` keeps media off the radio whenever a physical path
    /// exists.
    public static var preferRadioOverRelay: Bool {
        get { UserDefaults.standard.bool(forKey: preferRadioOverRelayKey) }
        set { UserDefaults.standard.set(newValue, forKey: preferRadioOverRelayKey) }
    }

    // MARK: What the router knows

    /// Everything `order(_:)` is allowed to look at. Passed in rather than
    /// reached for, so the decision is a pure function and can be tested
    /// without a radio, a relay or a peer.
    public struct Conditions: Equatable, Sendable {
        /// A real link exists underneath. NOT `isConnected`: a VPN keeps the
        /// path satisfied from a tunnel route alone once the link under it is
        /// gone, which is exactly how off-grid VPN users lost their radio.
        public var physicalPath: Bool
        /// THIS recipient is a connected mesh peer. A flood to "someone who
        /// might know them" is not reachability.
        public var peerOnMesh: Bool
        /// A radio is paired and healthy enough to accept bytes.
        public var radioReady: Bool
        /// Conservative: true whenever we do not know. A false "offline" pushes
        /// a message onto a slow radio that may have nobody in range.
        public var peerLikelyOnline: Bool
        public var carriesMedia: Bool
        /// __ANTI_JAMMING_PHASE1_2026_11_13__ The radio reports PROBABLE_JAMMING
        /// and the user has not overridden it
        /// (`MeshtasticManager.excludesLoRaForJamming`, from `LoRaJammingDetector`).
        /// LoRa is then left out entirely: whatever else is reachable carries the
        /// message, and with nothing else the list is empty and the message waits.
        /// Not "last resort": frames into a jammed channel are lost airtime that
        /// also reads as "sent" on screen.
        public var radioJammed: Bool

        public init(physicalPath: Bool, peerOnMesh: Bool, radioReady: Bool,
                    peerLikelyOnline: Bool, carriesMedia: Bool, radioJammed: Bool = false) {
            self.physicalPath = physicalPath
            self.peerOnMesh = peerOnMesh
            self.radioReady = radioReady
            self.peerLikelyOnline = peerLikelyOnline
            self.carriesMedia = carriesMedia
            self.radioJammed = radioJammed
        }
    }

    // MARK: The decision

    /// The transports to try, in order, for one envelope under `c`.
    ///
    /// An empty result is a legitimate answer: a radio-only install with no
    /// radio in range has nowhere to put the message right now, and saying so
    /// is better than quietly using the relay.
    public static func order(_ c: Conditions) -> [TransportKind] {
        var out: [TransportKind] = []

        // Mesh first when the peer is actually there — free, local, instant.
        if c.peerOnMesh { out.append(.bluetoothMesh) }

        // Media stays off the radio while a network path exists: LoRa media is
        // minutes on the air. Off-grid it is the only option, so it is allowed.
        let radioUsable = c.radioReady && !c.radioJammed && (!c.carriesMedia || !c.physicalPath)
        let internetUsable = c.physicalPath && internetAllowedForSending

        // The radio goes ahead of the relay when the relay cannot work, when
        // the peer is not reachable by network anyway, or when the user asked
        // for it. The first two are the behaviour that already existed; only
        // the third is new.
        let radioFirst = radioUsable
            && (!internetUsable || !c.peerLikelyOnline || preferRadioOverRelay)

        if radioFirst { out.append(.loRa) }
        if internetUsable { out.append(.internetRelay) }
        if radioUsable && !radioFirst { out.append(.loRa) }
        return out
    }

    /// __LORA_THEN_INTERNET_2026_10_27__ The radio has been tried and the peer
    /// did not get it. May the internet pick the message up?
    ///
    /// This is the second half of `preferRadioOverRelay`, and without it that
    /// preference was a one-way door. `order(_:)` has always published
    /// `[.loRa, .internetRelay]` — the radio first AND the relay behind it —
    /// but the send path could never reach that second entry, because handing
    /// bytes to a radio always "succeeded": success meant the frames left, not
    /// that anyone heard them. A peer out of radio range therefore got a
    /// message marked sent that was never going anywhere, with a working
    /// internet connection sitting idle.
    ///
    /// Kept here, next to `order(_:)`, for one reason: the rule about when the
    /// relay may carry something is the rule this type owns. A copy of it
    /// spelled out inline in the one `guard` that happens to need it is how
    /// `preferenceRank` became a lie.
    ///
    /// - Parameters:
    ///   - hasPhysicalPath: a real link underneath, not a VPN tunnel route.
    ///   - internetAllowedHere: the app-wide guarantee AND this conversation's
    ///     own offline-only switch, already folded together by
    ///     `internetAllowedForSending(inConversation:)`.
    ///   - alreadyDelivered: another transport got there first; a late radio
    ///     failure must never pull a delivered message back onto the relay.
    public static func internetPicksUpAfterRadio(hasPhysicalPath: Bool,
                                                 internetAllowedHere: Bool,
                                                 alreadyDelivered: Bool) -> Bool {
        if alreadyDelivered { return false }
        // NOT `internetAllowedForSending` (the global) — the caller passes the
        // per-conversation answer, which already includes the global one.
        // Reading the global here as well would let a conversation marked
        // offline-only be escalated onto the relay anyway.
        return hasPhysicalPath && internetAllowedHere
    }

    /// Whether the send path should offer the radio before the relay.
    ///
    /// DERIVED from `order(_:)` rather than re-deriving the condition, so the
    /// list the diagnostics screen shows and the branch the router takes cannot
    /// drift apart — which is the entire failure this type exists to end.
    public static func radioComesBeforeRelay(_ c: Conditions) -> Bool {
        let o = order(c)
        guard let radio = o.firstIndex(of: .loRa) else { return false }
        guard let relay = o.firstIndex(of: .internetRelay) else { return true }
        return radio < relay
    }
}

/// Raised when every offline route is exhausted and the internet is not allowed
/// to pick up the slack. The message is KEPT (`.pending`), never marked failed:
/// nothing went wrong, it is simply waiting for a radio or a peer.
public enum TransportPolicyError: Error {
    case internetSendingDisabled
}

// MARK: - Measured quality

/// Rolling, measured health of one transport. Diagnostics only — nothing here
/// feeds a routing decision, by design.
public struct TransportQuality: Equatable, Sendable {
    public var packetsSent: Int = 0
    public var packetsReceived: Int = 0
    public var failures: Int = 0
    public var retries: Int = 0
    /// Seconds, exponentially smoothed.
    public var averageLatency: TimeInterval?
    public var lastRSSI: Int?
    public var lastSNR: Double?
    public var lastActivity: Date?

    public init() {}

    /// Accepted-for-transmission rate. Nil rather than 100 % before anything has
    /// been sent — a fresh transport is unmeasured, not perfect.
    public var successRate: Double? {
        let attempts = packetsSent + failures
        guard attempts > 0 else { return nil }
        return Double(packetsSent) / Double(attempts)
    }

    /// Smoothing factor chosen so a single slow packet nudges the average
    /// instead of redefining it, while a genuine change still shows within a
    /// handful of samples.
    private static let latencyAlpha = 0.25

    public mutating func recordSent(latency: TimeInterval?) {
        packetsSent += 1
        lastActivity = Date()
        guard let latency else { return }
        averageLatency = averageLatency.map { $0 + Self.latencyAlpha * (latency - $0) } ?? latency
    }

    public mutating func recordReceived(rssi: Int? = nil, snr: Double? = nil) {
        packetsReceived += 1
        lastActivity = Date()
        if let rssi { lastRSSI = rssi }
        if let snr { lastSNR = snr }
    }

    public mutating func recordFailure() {
        failures += 1
        lastActivity = Date()
    }

    public mutating func recordRetry() { retries += 1 }
}

// MARK: - The transport contract

/// A way to move opaque ciphertext between devices.
///
/// Implemented by `LoRaTransport` only. See the file header for why the
/// existing transports are described by `TransportKind` but not retrofitted
/// onto this protocol.
@MainActor
public protocol MessageTransport: AnyObject {
    var kind: TransportKind { get }

    /// True when this transport could carry something *right now*. Callers ask
    /// before offering work; it never throws just to say "not now".
    var isAvailable: Bool { get }

    var quality: TransportQuality { get }

    /// Largest payload this transport accepts in one piece.
    var maximumPayloadBytes: Int { get }

    /// Hand over already-encrypted bytes.
    ///
    /// Success means accepted for transmission, not delivered — see
    /// `LoRaNode.send`.
    func send(_ payload: Data) async throws
}

// MARK: - LoRa

/// LoRa, exposed as a transport.
///
/// A thin, testable seam over whichever node is currently active: it owns no
/// radio logic of its own, so the Meshtastic implementation and a future OSHI
/// firmware are interchangeable underneath it.
@MainActor
public final class LoRaTransport: ObservableObject, MessageTransport {

    public let kind: TransportKind = .loRa

    /// Measured, not asserted. Published so diagnostics can render it live.
    @Published public private(set) var quality = TransportQuality()

    /// The node this transport currently speaks through, or nil when none is
    /// paired. Injected rather than reached for, so tests can supply a fake.
    private let nodeProvider: () -> LoRaNode?

    private var inboundCancellable: AnyCancellable?
    private var boundNodeID: UUID?

    /// Ciphertext arriving over LoRa, republished for whoever injects it into
    /// the existing receive path. This class never interprets a byte of it.
    public let inbound = PassthroughSubject<Data, Never>()

    public init(nodeProvider: @escaping () -> LoRaNode?) {
        self.nodeProvider = nodeProvider
    }

    public var isAvailable: Bool {
        guard let node = nodeProvider() else { return false }
        return node.status.health >= .degraded
    }

    /// Falls back to the smallest sensible frame when no node is attached, so a
    /// caller chunking against this value can never produce a payload the radio
    /// will reject once one appears.
    public var maximumPayloadBytes: Int {
        nodeProvider()?.maximumPayloadBytes ?? 180
    }

    public func send(_ payload: Data) async throws {
        guard let node = nodeProvider() else {
            quality.recordFailure()
            throw LoRaNodeError.notConnected
        }
        guard node.status.health >= .degraded else {
            quality.recordFailure()
            throw LoRaNodeError.notConnected
        }
        guard payload.count <= node.maximumPayloadBytes else {
            quality.recordFailure()
            throw LoRaNodeError.payloadTooLarge(bytes: payload.count,
                                                limit: node.maximumPayloadBytes)
        }

        let started = Date()
        do {
            try await node.send(payload)
            quality.recordSent(latency: Date().timeIntervalSince(started))
        } catch {
            quality.recordFailure()
            throw error
        }
    }

    /// Subscribe to the active node's inbound stream.
    ///
    /// Idempotent per node: called on every status change, it re-subscribes only
    /// when the node actually changed. Without that check, a reconnect would
    /// stack subscriptions and every received packet would be delivered N times.
    public func bindToActiveNode() {
        let node = nodeProvider()
        guard node?.id != boundNodeID else { return }
        inboundCancellable?.cancel()
        boundNodeID = node?.id
        guard let node else { return }
        inboundCancellable = node.inboundPublisher.sink { [weak self] data in
            guard let self else { return }
            self.quality.recordReceived(rssi: node.status.loRaRSSI, snr: node.status.loRaSNR)
            self.inbound.send(data)
        }
    }
}
