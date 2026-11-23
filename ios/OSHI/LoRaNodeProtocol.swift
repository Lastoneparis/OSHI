//
//  LoRaNodeProtocol.swift
//  OSHI — the vendor-neutral contract every LoRa node implementation satisfies.
//
//  WHY THIS EXISTS
//  ---------------
//  Today OSHI talks to one firmware family: Meshtastic, over its BLE service
//  (`MeshtasticManager`). Tomorrow it should talk to OSHI's own firmware, and to
//  whatever comes after, without the app knowing the difference. Everything
//  above this file — the settings screen, the transport, diagnostics, OTA — is
//  written against `LoRaNode`, so adding a firmware family means adding one
//  adapter, not editing the app.
//
//  WHAT THIS FILE DOES NOT DO
//  --------------------------
//  It does not touch messaging. Encryption, routing, the Bluetooth and Wi-Fi
//  mesh and the internet fallback are all untouched by design: a node here is a
//  *radio*, and the only thing OSHI asks of it is "carry these opaque bytes".
//  The payload handed to `send` is already-encrypted ciphertext produced by the
//  existing pipeline; a node implementation that tried to interpret it would be
//  wrong.
//
//  THREADING
//  ---------
//  `LoRaNode` is `@MainActor`. CoreBluetooth delivers on the queue it was given
//  (`MeshtasticManager` uses `.main`), the UI observes these types directly, and
//  a single actor removes a whole class of races from a stack whose failure mode
//  is "the radio silently stopped". Anything genuinely slow belongs on a
//  background queue *inside* an implementation, not in this contract.
//

import Foundation
import Combine

// MARK: - Identity and capabilities

/// Which firmware family an implementation speaks. Not cosmetic: OTA, config
/// keys and packet framing all differ per family, and code that branches on it
/// should say so out loud rather than sniffing version strings.
public enum LoRaFirmwareFamily: String, Codable, Sendable, CaseIterable {
    /// Meshtastic 2.x over its published BLE service. What ships today.
    case meshtastic
    /// OSHI's own firmware. Reserved now so the migration is additive.
    case oshi
    /// A node that answered, but whose family we could not establish.
    case unknown
}

/// What a node can actually do. Never assume a capability — a T-Echo has a
/// screen and a Heltec V3 does not, and an older firmware may not support OTA
/// at all. The UI hides what is absent instead of failing at the tap.
public struct LoRaNodeCapabilities: Equatable, Sendable {
    public var canConfigureRegion: Bool
    public var canConfigureChannel: Bool
    public var canSetTransmitPower: Bool
    public var canReportBattery: Bool
    public var canReportPosition: Bool
    /// Firmware update over BLE, with no cable.
    public var supportsOTA: Bool
    /// The node will relay for other nodes rather than only speak for itself.
    public var supportsRelay: Bool

    public init(canConfigureRegion: Bool = false,
                canConfigureChannel: Bool = false,
                canSetTransmitPower: Bool = false,
                canReportBattery: Bool = false,
                canReportPosition: Bool = false,
                supportsOTA: Bool = false,
                supportsRelay: Bool = false) {
        self.canConfigureRegion = canConfigureRegion
        self.canConfigureChannel = canConfigureChannel
        self.canSetTransmitPower = canSetTransmitPower
        self.canReportBattery = canReportBattery
        self.canReportPosition = canReportPosition
        self.supportsOTA = supportsOTA
        self.supportsRelay = supportsRelay
    }

    /// What Meshtastic 2.x over BLE gives us today. OTA is false on purpose:
    /// it is not implemented yet, and claiming it would put a button on screen
    /// that cannot work.
    public static let meshtastic2x = LoRaNodeCapabilities(
        canConfigureRegion: true, canConfigureChannel: true,
        canSetTransmitPower: true, canReportBattery: true,
        canReportPosition: true, supportsOTA: false, supportsRelay: true)
}

// MARK: - Health

/// One value the UI can render without interpreting anything.
///
/// Ordered from worst to best so `<` means "worse", which is what the
/// strongest-node picker in `LoRaManager` needs.
public enum LoRaNodeHealth: Int, Comparable, Sendable {
    case disconnected = 0
    case connecting = 1
    /// Connected, but something is wrong the user should see: weak signal,
    /// low battery, firmware behind.
    case degraded = 2
    case healthy = 3

    public static func < (a: LoRaNodeHealth, b: LoRaNodeHealth) -> Bool {
        a.rawValue < b.rawValue
    }
}

/// Everything the LoRa Node screen shows, in one snapshot.
///
/// Deliberately all-optional for the measured values: a node that has not yet
/// reported its battery must render as "—", never as 0 %. The difference
/// between "unknown" and "empty" is the difference between a blank field and a
/// false alarm.
public struct LoRaNodeStatus: Equatable, Sendable {
    public var health: LoRaNodeHealth
    public var displayName: String
    public var firmwareVersion: String?
    /// 0…1. Nil until the node reports it.
    public var batteryLevel: Double?
    public var isCharging: Bool?
    /// BLE signal to the phone, dBm.
    public var bluetoothRSSI: Int?
    /// Last LoRa packet's signal, dBm, and signal-to-noise ratio, dB.
    public var loRaRSSI: Int?
    public var loRaSNR: Double?
    public var region: String?
    /// MHz.
    public var frequency: Double?
    public var uptime: TimeInterval?
    public var lastPacketAt: Date?
    /// Set when the node is reachable but something needs attention, so the UI
    /// has a sentence to show instead of inventing one.
    public var attention: String?

    public init(health: LoRaNodeHealth = .disconnected,
                displayName: String = "",
                firmwareVersion: String? = nil,
                batteryLevel: Double? = nil,
                isCharging: Bool? = nil,
                bluetoothRSSI: Int? = nil,
                loRaRSSI: Int? = nil,
                loRaSNR: Double? = nil,
                region: String? = nil,
                frequency: Double? = nil,
                uptime: TimeInterval? = nil,
                lastPacketAt: Date? = nil,
                attention: String? = nil) {
        self.health = health
        self.displayName = displayName
        self.firmwareVersion = firmwareVersion
        self.batteryLevel = batteryLevel
        self.isCharging = isCharging
        self.bluetoothRSSI = bluetoothRSSI
        self.loRaRSSI = loRaRSSI
        self.loRaSNR = loRaSNR
        self.region = region
        self.frequency = frequency
        self.uptime = uptime
        self.lastPacketAt = lastPacketAt
        self.attention = attention
    }
}

// MARK: - Discovery

/// A node seen while scanning, before any connection exists.
public struct DiscoveredLoRaNode: Identifiable, Equatable, Sendable {
    /// Stable across scans for the same physical node — the CoreBluetooth
    /// peripheral identifier, not the advertised name, which users rename.
    public let id: UUID
    public let name: String
    public let rssi: Int
    public let family: LoRaFirmwareFamily
    /// True when this node is already paired and we are only re-seeing it.
    public let isKnown: Bool

    public init(id: UUID, name: String, rssi: Int,
                family: LoRaFirmwareFamily, isKnown: Bool = false) {
        self.id = id
        self.name = name
        self.rssi = rssi
        self.family = family
        self.isKnown = isKnown
    }

    /// Four buckets, because "-73 dBm" means nothing to the person holding the
    /// phone and "Good" does.
    public var signalBars: Int {
        switch rssi {
        case (-60)...:    return 4
        case (-70)..<(-60): return 3
        case (-85)..<(-70): return 2
        default:          return 1
        }
    }
}

// MARK: - Errors

public enum LoRaNodeError: LocalizedError, Equatable {
    case bluetoothUnavailable
    case bluetoothPermissionDenied
    case notConnected
    /// The payload exceeds what the radio can carry after framing.
    case payloadTooLarge(bytes: Int, limit: Int)
    case capabilityUnsupported(String)
    case timedOut
    case firmwareRejected(String)
    case underlying(String)

    public var errorDescription: String? {
        switch self {
        case .bluetoothUnavailable:      return "lora.error.bluetooth_off".localized
        case .bluetoothPermissionDenied: return "lora.error.bluetooth_denied".localized
        case .notConnected:              return "lora.error.not_connected".localized
        case .payloadTooLarge(let b, let l):
            return String(format: "lora.error.too_large".localized, b, l)
        case .capabilityUnsupported(let what):
            return String(format: "lora.error.unsupported".localized, what)
        case .timedOut:                  return "lora.error.timeout".localized
        case .firmwareRejected(let why): return why
        case .underlying(let why):       return why
        }
    }
}

// MARK: - The node contract

/// A LoRa radio OSHI can talk to.
///
/// Implementations own exactly one physical node. Owning several is
/// `LoRaManager`'s job, so an implementation never has to reason about which
/// node it is.
@MainActor
public protocol LoRaNode: AnyObject {

    /// Stable identity of the physical node, for persistence and reconnection.
    var id: UUID { get }

    var family: LoRaFirmwareFamily { get }
    var capabilities: LoRaNodeCapabilities { get }

    /// Current state, republished whenever anything in it changes.
    var status: LoRaNodeStatus { get }
    var statusPublisher: AnyPublisher<LoRaNodeStatus, Never> { get }

    /// Ciphertext arriving from the mesh, exactly as the radio delivered it.
    /// Subscribers inject it into the EXISTING receive pipeline; nothing here
    /// parses or decrypts it.
    var inboundPublisher: AnyPublisher<Data, Never> { get }

    /// Largest single payload this node will carry after its own framing.
    /// Callers chunk above this; they do not guess it.
    var maximumPayloadBytes: Int { get }

    func connect() async throws
    func disconnect()

    /// Hand opaque ciphertext to the radio.
    ///
    /// Returning normally means the node ACCEPTED the bytes for transmission —
    /// not that anything received them. LoRa has no delivery guarantee and this
    /// API does not pretend otherwise; delivery is observed on the receiving
    /// side, by the existing messaging layer.
    func send(_ payload: Data) async throws
}
