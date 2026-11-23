//
//  MeshtasticNodeAdapter.swift
//  OSHI — makes the existing MeshtasticManager satisfy `LoRaNode`.
//
//  This is the proof that `LoRaNodeProtocol` describes something real rather
//  than something aspirational: it is a pure adapter. It adds no radio logic, no
//  packet handling and no messaging behaviour. Everything it reports is read
//  from `MeshtasticManager`, which keeps doing exactly what it does today.
//
//  ONE THING IT DELIBERATELY DOES NOT DO
//  -------------------------------------
//  It does not republish received messages. `MeshtasticManager.handleOshiChunk`
//  already reassembles an OSHI envelope and posts `MeshMessageReceived` —
//  the same NotificationCenter path the rest of the mesh uses, and the reason
//  LoRa receive works today. Re-emitting the same message through
//  `inboundPublisher` would deliver it twice. `inboundPublisher` therefore
//  exists for FUTURE firmware that hands us raw frames, and stays silent for
//  Meshtastic. A protocol that lies about where data flows is worse than a
//  protocol with an unused member.
//
//  HONEST CAPABILITIES
//  -------------------
//  `MeshtasticManager` parses node info and text/OSHI packets. It does NOT
//  parse device telemetry, so battery, uptime, region and frequency are
//  reported as nil and the corresponding capabilities are false. The settings
//  screen renders "—" for those rather than a plausible zero. Wiring the
//  telemetry protobufs is separate work; claiming it here would put numbers on
//  screen that no packet produced.
//

import Foundation
import Combine
import CoreBluetooth

@MainActor
final class MeshtasticNodeAdapter: NSObject, LoRaNode, ObservableObject {

    // MARK: Identity

    let id: UUID
    let family: LoRaFirmwareFamily = .meshtastic

    /// What the Meshtastic BLE surface actually gives us today. OTA, battery,
    /// position and the config setters stay false until each is implemented and
    /// verified against real hardware — see the file header.
    let capabilities = LoRaNodeCapabilities(
        canConfigureRegion: false,
        canConfigureChannel: false,
        canSetTransmitPower: false,
        canReportBattery: false,
        canReportPosition: false,
        supportsOTA: false,
        supportsRelay: true          // the mesh relays by nature; nothing to enable
    )

    /// Meshtastic's frame budget is ~237 bytes; `MeshtasticManager` chunks OSHI
    /// envelopes at 180 to leave room for its own header. Mirrored, not guessed.
    let maximumPayloadBytes = 180

    // MARK: State

    @Published private(set) var status = LoRaNodeStatus()
    var statusPublisher: AnyPublisher<LoRaNodeStatus, Never> {
        $status.eraseToAnyPublisher()
    }

    /// Silent for Meshtastic, on purpose. See the file header.
    private let inboundSubject = PassthroughSubject<Data, Never>()
    var inboundPublisher: AnyPublisher<Data, Never> {
        inboundSubject.eraseToAnyPublisher()
    }

    private let manager: MeshtasticManager
    private var cancellables = Set<AnyCancellable>()

    // MARK: Init

    /// - Parameters:
    ///   - manager: injected so tests can drive a stand-in. Defaults to the
    ///     singleton the app already uses, so behaviour is unchanged in-app.
    ///   - id: the paired peripheral's identifier when known. A fresh UUID is
    ///     only a placeholder until a real peripheral is connected.
    /// `internal`, not `public`: `MeshtasticManager` is an internal type, so a
    /// public initialiser taking one cannot compile. The adapter is only ever
    /// constructed inside the app, so internal is the honest access level —
    /// widening the manager instead would export the whole radio driver.
    init(manager: MeshtasticManager = .shared, id: UUID = UUID()) {
        self.manager = manager
        self.id = id
        super.init()
        observe()
        refreshStatus()
    }

    /// Mirror the manager's published state into one `LoRaNodeStatus`.
    ///
    /// Each of these is already `@Published`, so this is a fan-in, not polling.
    private func observe() {
        manager.$state
            .sink { [weak self] _ in self?.refreshStatus() }
            .store(in: &cancellables)
        manager.$connectedName
            .sink { [weak self] _ in self?.refreshStatus() }
            .store(in: &cancellables)
        manager.$traffic
            .sink { [weak self] _ in self?.refreshStatus() }
            .store(in: &cancellables)
        manager.$lastError
            .sink { [weak self] _ in self?.refreshStatus() }
            .store(in: &cancellables)
    }

    private func refreshStatus() {
        var s = LoRaNodeStatus()
        s.health = Self.health(for: manager.state)
        s.displayName = manager.connectedName ?? "lora.node.unnamed".localized

        // The best SNR any node in the mesh reported most recently. Presented as
        // the LoRa link quality because it is the only radio-side figure the
        // current parser produces — and it is a real measurement, not a guess.
        if let freshest = manager.nodes.max(by: { $0.lastHeard < $1.lastHeard }) {
            s.loRaSNR = Double(freshest.snr)
            s.lastPacketAt = freshest.lastHeard
        }
        // Traffic is newest-first, so element 0 is the most recent event either
        // way — a sent frame counts as activity too.
        if let latest = manager.traffic.first {
            s.lastPacketAt = max(s.lastPacketAt ?? .distantPast, latest.date)
        }

        // Degrade rather than claim health when the manager is reporting a
        // problem: connected-but-erroring is exactly the state a user needs to
        // see, and it is the one a boolean "connected" flag hides.
        if s.health == .healthy, let err = manager.lastError, !err.isEmpty {
            s.health = .degraded
            s.attention = err
        }
        if manager.permissionDenied {
            s.health = .disconnected
            s.attention = "lora.error.bluetooth_denied".localized
        }

        status = s
    }

    private static func health(for state: MeshtasticManager.ConnState) -> LoRaNodeHealth {
        switch state {
        case .off:                      return .disconnected
        case .scanning:                 return .disconnected
        case .connecting, .configuring: return .connecting
        case .ready:                    return .healthy
        }
    }

    // MARK: Connection

    func connect() async throws {
        guard manager.bluetoothOn else {
            throw manager.permissionDenied
                ? LoRaNodeError.bluetoothPermissionDenied
                : LoRaNodeError.bluetoothUnavailable
        }
        // `MeshtasticManager` reconnects to the paired peripheral by itself once
        // scanning starts; this adapter does not second-guess that logic.
        manager.startScan()
    }

    func disconnect() {
        manager.disconnect(forget: false)
    }

    // MARK: Send

    /// Hand raw ciphertext to the radio.
    ///
    /// ⚠️ Meshtastic's OSHI path is envelope-shaped: `MeshtasticManager` owns the
    /// chunking, the 0x4F 0x4D magic and the reassembly on the far side. There is
    /// no supported way to inject an arbitrary blob through it without
    /// duplicating that framing — and duplicating it is how the two ends stop
    /// agreeing. So this throws instead of half-working, and the app keeps using
    /// the manager's own `queueOrSendSecureMessage` (which is untouched).
    ///
    /// The member stays in the protocol because OSHI firmware will carry raw
    /// frames, and that is the point of the abstraction.
    func send(_ payload: Data) async throws {
        guard payload.count <= maximumPayloadBytes else {
            throw LoRaNodeError.payloadTooLarge(bytes: payload.count,
                                                limit: maximumPayloadBytes)
        }
        throw LoRaNodeError.capabilityUnsupported("lora.error.raw_frames".localized)
    }
}
