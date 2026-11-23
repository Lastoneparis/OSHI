//
//  MeshtasticManager.swift
//  OSHI — Long-range LoRa mesh via a paired Meshtastic radio (M1)
//
//  Connects to any Meshtastic-firmware radio (LILYGO, Heltec, RAK…) over BLE
//  and adds a LoRa transport lane to OSHI:
//   • Auto-DETECTION: scans for the Meshtastic BLE service UUID — any
//     compatible radio nearby shows up, no manual UUID entry.
//   • INTEROP: plain text is sent on TEXT_MESSAGE_APP (portnum 1), so users of
//     the official Meshtastic app (or any other Meshtastic client) receive it.
//   • OSHI↔OSHI: full SecureMessage envelopes ride PRIVATE_APP (portnum 256),
//     chunked to LoRa frame size. E2E encryption is preserved — the radio and
//     every relaying node only ever see ciphertext. Broadcast reaches everyone;
//     only the intended recipient can decrypt (others silently drop), so a
//     message always "finds the correct user" without any routing table.
//
//  Protocol: Meshtastic protobufs (mesh.proto), hand-decoded with a minimal
//  varint/fixed32/length-delimited codec — no external dependency.
//

import Foundation
import CoreBluetooth
import Combine

// MARK: - Minimal protobuf codec (only what Meshtastic M1 needs)

struct ProtoWriter {
    var data = Data()
    static func vint(_ v: UInt64) -> Data {
        var v = v, d = Data()
        repeat { var b = UInt8(v & 0x7F); v >>= 7; if v != 0 { b |= 0x80 }; d.append(b) } while v != 0
        return d
    }
    mutating func varint(_ field: Int, _ v: UInt64) {
        data.append(ProtoWriter.vint(UInt64(field << 3 | 0))); data.append(ProtoWriter.vint(v))
    }
    mutating func fixed32(_ field: Int, _ v: UInt32) {
        data.append(ProtoWriter.vint(UInt64(field << 3 | 5)))
        var le = v.littleEndian; withUnsafeBytes(of: &le) { data.append(contentsOf: $0) }
    }
    mutating func bytes(_ field: Int, _ d: Data) {
        data.append(ProtoWriter.vint(UInt64(field << 3 | 2))); data.append(ProtoWriter.vint(UInt64(d.count))); data.append(d)
    }
}

struct ProtoField { let number: Int; let wire: Int; let varintValue: UInt64; let payload: Data }

func parseProtoFields(_ d: Data) -> [ProtoField] {
    var out: [ProtoField] = []; var i = d.startIndex
    func readVarint() -> UInt64? {
        var v: UInt64 = 0, shift: UInt64 = 0
        while i < d.endIndex {
            let b = d[i]; i = d.index(after: i)
            v |= UInt64(b & 0x7F) << shift
            if b & 0x80 == 0 { return v }
            shift += 7; if shift > 63 { return nil }
        }
        return nil
    }
    while i < d.endIndex {
        guard let key = readVarint() else { break }
        let field = Int(key >> 3), wire = Int(key & 7)
        switch wire {
        case 0:
            guard let v = readVarint() else { return out }
            out.append(ProtoField(number: field, wire: 0, varintValue: v, payload: Data()))
        case 1:
            guard d.distance(from: i, to: d.endIndex) >= 8 else { return out }
            let p = d[i..<d.index(i, offsetBy: 8)]; i = d.index(i, offsetBy: 8)
            out.append(ProtoField(number: field, wire: 1, varintValue: 0, payload: Data(p)))
        case 2:
            guard let len = readVarint(), d.distance(from: i, to: d.endIndex) >= Int(len) else { return out }
            let p = d[i..<d.index(i, offsetBy: Int(len))]; i = d.index(i, offsetBy: Int(len))
            out.append(ProtoField(number: field, wire: 2, varintValue: 0, payload: Data(p)))
        case 5:
            guard d.distance(from: i, to: d.endIndex) >= 4 else { return out }
            let p = d[i..<d.index(i, offsetBy: 4)]; i = d.index(i, offsetBy: 4)
            let v = p.withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) }.littleEndian
            out.append(ProtoField(number: field, wire: 5, varintValue: UInt64(v), payload: Data(p)))
        default: return out
        }
    }
    return out
}

// MARK: - Distance

/// __DISTANCE_FOLLOWS_THE_LOCALE_2026_10_21__ Les distances du maillage etaient
/// ecrites en dur en m/km, dans deux fonctions differentes de l'ecran des
/// reglages. Pour quelqu'un aux Etats-Unis ou au Royaume-Uni, « 4,2 km » n'est
/// pas une information, c'est une conversion a faire de tete.
///
/// Les regles de coupure ne sont pas symetriques, volontairement: on passe aux
/// metres sous 1 km, et aux pieds sous 0,1 mile (≈160 m), parce qu'au-dessous
/// de 0,1 mile l'affichage decimal devient illisible.
public enum MeshDistance {

    /// `.uk` compte comme imperial: le Royaume-Uni mesure les routes en miles.
    public static var localeIsImperial: Bool {
        switch Locale.current.measurementSystem {
        case .us, .uk: return true
        default: return false
        }
    }

    public static func format(km: Double, imperial: Bool) -> String {
        if imperial {
            let miles = km * 0.621371
            return miles < 0.1
                ? String(format: "%.0f ft", km * 3280.84)
                : String(format: "%.1f mi", miles)
        }
        return km < 1
            ? String(format: "%.0f m", km * 1000)
            : String(format: "%.1f km", km)
    }

    /// Une fourchette, dont l'unite est choisie sur la borne HAUTE pour que les
    /// deux moities ne finissent jamais dans des unites differentes.
    public static func span(minKm: Double, maxKm: Double, imperial: Bool) -> String {
        if imperial {
            let lo = minKm * 0.621371, hi = maxKm * 0.621371
            return hi < 0.1
                ? String(format: "%.0f–%.0f ft", minKm * 3280.84, maxKm * 3280.84)
                : String(format: "%.1f–%.1f mi", lo, hi)
        }
        return maxKm < 1
            ? String(format: "%.0f–%.0f m", minKm * 1000, maxKm * 1000)
            : String(format: "%.1f–%.1f km", minKm, maxKm)
    }

    public static func format(km: Double) -> String { format(km: km, imperial: localeIsImperial) }
    public static func span(minKm: Double, maxKm: Double) -> String {
        span(minKm: minKm, maxKm: maxKm, imperial: localeIsImperial)
    }
}

// MARK: - Types

struct MeshtasticNode: Identifiable, Equatable {
    let num: UInt32
    var userId: String        // "!a1b2c3d4"
    var longName: String
    var shortName: String
    var lastHeard: Date
    var snr: Float
    /// Where the node says it is. nil when it has no GPS or has not shared a
    /// position — most bare boards (a Heltec V3 has no GPS) never will, so this
    /// stays nil for them and the UI must cope rather than showing 0 km.
    var latitude: Double?
    var longitude: Double?
    /// Radio hops away. 0 = heard directly, nil = not reported.
    var hopsAway: Int?
    /// Received signal strength of the last packet heard from this node, dBm.
    /// nil until we have actually heard a packet (NodeInfo alone does not carry it).
    var rssi: Int?
    var id: UInt32 { num }

    var hasPosition: Bool { latitude != nil && longitude != nil }

    /// __LORA_NODE_LIST_2026_11_04__ Four buckets, the same boundaries as
    /// `LoRaDiscoveredNode.signalBars` and Android `LoRaFormat.signalBars`:
    /// "-73 dBm" means nothing to the person holding the phone and four bars do.
    ///
    /// 0 when we have never heard a packet from this node, which is a real state:
    /// a NodeInfo tells us a radio exists without telling us how well we hear it.
    var signalBars: Int {
        guard let rssi else { return 0 }
        switch rssi {
        case (-60)...:      return 4
        case (-70)..<(-60): return 3
        case (-85)..<(-70): return 2
        default:            return 1
        }
    }

    /// Whether this radio was heard recently enough to call it present.
    ///
    /// Five minutes, matching Android `LoRaFormat.PRESENT_WINDOW_MS`: long enough
    /// not to flicker, short enough that a radio somebody has walked away with
    /// stops claiming to be here.
    func isPresent(now: Date = Date()) -> Bool {
        let age = now.timeIntervalSince(lastHeard)
        // A node never heard from carries `lastHeard` far in the past; a node
        // whose clock runs ahead carries one in the future and is still here.
        return age < 5 * 60 && age > -86_400
    }

    /// A distance estimated from signal strength, for the majority of nodes that
    /// have no GPS and never broadcast a position.
    ///
    /// This is a log-distance path-loss model, and it is genuinely approximate:
    /// the same RSSI means a very different distance across open water than
    /// through a town. So it deliberately returns a RANGE — computed with a
    /// path-loss exponent from 2.2 (open ground, near line of sight) to 3.0
    /// (built-up) — and the UI must label it as an estimate. A single number
    /// here would be false precision, and someone might make a decision in the
    /// field on the strength of it.
    ///
    /// At 868 MHz, free-space loss at 1 m is 20·log10(0.001) + 20·log10(868) +
    /// 32.44 ≈ 31.2 dB. Assumes ~22 dBm transmit and ~2 dBi at each antenna,
    /// which is typical of the boards OSHI supports; a node running lower power
    /// or a worse antenna will read as further away than it is.
    var estimatedRangeKm: (min: Double, max: Double)? {
        guard let rssi, rssi < 0, rssi > -140 else { return nil }
        let eirp = 22.0 + 2.0 + 2.0            // tx power + both antenna gains
        let lossAtOneMetre = 31.2
        let budget = eirp - lossAtOneMetre - Double(rssi)
        guard budget > 0 else { return nil }

        func metres(exponent n: Double) -> Double { pow(10.0, budget / (10.0 * n)) }
        // Larger exponent = more obstruction = the SAME signal implies a
        // SHORTER distance, so 3.2 gives the near end of the band.
        // 2.4–3.2 spans open-ish ground to built-up. A wider span is more
        // truthful about the physics but produces bands too vague to act on.
        let near = metres(exponent: 3.2) / 1000.0
        let far  = metres(exponent: 2.4) / 1000.0
        guard near.isFinite, far.isFinite else { return nil }

        // Past roughly -105 dBm the band opens to tens of kilometres and the
        // model stops saying anything worth reading — checked across the range:
        // -130 dBm yielded "14 km – 470 km". Showing that is worse than showing
        // nothing, so weak signals get no estimate at all and the row falls back
        // to raw dBm.
        guard far <= 20 else { return nil }
        return (min(near, far), max(near, far))
    }
}

struct LoRaEvent: Identifiable {
    let id = UUID()
    let date = Date()
    let direction: String      // "→" sent, "←" received
    let summary: String
}

// MARK: - Manager

final class MeshtasticManager: NSObject, ObservableObject {
    static let shared = MeshtasticManager()

    // Meshtastic 2.x BLE UUIDs
    static let serviceUUID  = CBUUID(string: "6BA1B218-15A8-461F-9FA8-5DCAE273EAFD")
    static let toRadioUUID  = CBUUID(string: "F75C76D2-129E-4DAD-A1DD-7866124401E7")
    static let fromRadioUUID = CBUUID(string: "2C55E69E-4993-11ED-B878-0242AC120002")
    static let fromNumUUID  = CBUUID(string: "ED9DA18C-A800-4F66-A670-AA7547E34453")

    static let broadcastAddr: UInt32 = 0xFFFFFFFF
    static let oshiPortnum: UInt64 = 256           // PRIVATE_APP
    static let textPortnum: UInt64 = 1             // TEXT_MESSAGE_APP (interop)
    private static let loRaChunk = 180             // payload bytes per LoRa frame (frame budget ~237)
    /// __LORA_INTEROP_REPLY_2026_10_28__ UTF-8 bytes of PLAIN text that fit one
    /// interop frame. Larger than [loRaChunk] because raw text on portnum 1
    /// carries none of the 8-byte OSHI chunk header, and still inside the ~237
    /// byte frame budget with margin. Interop text is never chunked: a stock
    /// Meshtastic client has no reassembly for OSHI's header, so a long reply
    /// would arrive as fragments nobody joins. Refused with a count instead.
    static let interopTextLimit = 200
    private static let pairedKey = "oshi.meshtastic.peripheralId"
    /// Opt-in switch for carrying media over LoRa (default false). Written by the
    /// SwiftUI toggle in MeshtasticSettingsView via @AppStorage on this same key;
    /// read here through UserDefaults because this is a plain class, not a View.
    /// [Media-over-LoRa Phase 0 2026-09-11]
    static let mediaOverLoRaKey = "oshi_media_over_mesh_lora"
    /// Opt-in switch for BEACONING THIS PHONE'S POSITION over the mesh (default false).
    /// Written by the SwiftUI toggle in MeshtasticSettingsView via @AppStorage on this
    /// same key; read from `LoRaPositionBeacon` through UserDefaults. Same key name as
    /// Android so both platforms describe the same thing.
    /// [LoRa position beacon 2026-10-28]
    ///
    /// Default false is not timidity: `broadcastPosition` is a public cleartext broadcast
    /// on a channel whose key is published, so turning this on tells every radio within
    /// tens of kilometres where its owner is. That has to be a deliberate decision.
    static let positionBeaconKey = "oshi_lora_position_beacon"
    /// The channel name THIS PHONE last wrote to a node.
    ///
    /// Not the node's state: nothing reads a channel back off the radio, so this
    /// is only ever "what we asked for last time", and the screen says so.
    /// Same key name as Android so both platforms describe the same thing.
    /// [LoRa channel 2026-10-26]
    static let channelNameKey = "oshi.lora.channelName"
    /// True when the person has explicitly opted media into the LoRa fallback.
    static var mediaOverLoRaEnabled: Bool {
        UserDefaults.standard.bool(forKey: mediaOverLoRaKey)
    }
    /// True when the person has explicitly opted into beaconing their position.
    static var positionBeaconEnabled: Bool {
        UserDefaults.standard.bool(forKey: positionBeaconKey)
    }

    // MARK: Send-cost estimate (pure, for the pre-send confirmation)

    /// The ~12-frame ceiling enforced in `sendSecureMessage`. A message whose
    /// wire form needs more frames than this is refused there as `.tooLarge`.
    static let loRaMaxFrames = 12

    /// Frames a payload of `wireBytes` will occupy — the same chunking the send
    /// path uses (`loRaChunk` bytes per frame), so this matches reality exactly
    /// when given the true wire size.
    static func loRaFrameCount(wireBytes: Int) -> Int {
        max(1, (max(0, wireBytes) + loRaChunk - 1) / loRaChunk)
    }

    /// Airtime for `frames`, at the pacing the send timer uses (~2 s/frame on
    /// LongFast). Returned in whole seconds.
    static func loRaAirtimeSeconds(frames: Int) -> Int { max(0, frames) * 2 }

    /// Compose-time estimate from a raw media size, before the message is built.
    /// `wireFactor` folds the JSON envelope + base64 expansion the encrypted
    /// payload picks up; it is deliberately conservative so the shown figure is
    /// never rosier than the real send. `fits` mirrors the send-path ceiling but
    /// is only a prediction — the true `.tooLarge` check runs on the real wire
    /// bytes and remains the authority.
    static func loRaMediaEstimate(mediaBytes: Int, wireFactor: Double = 1.9)
        -> (frames: Int, seconds: Int, fits: Bool) {
        let wire = Int((Double(max(0, mediaBytes)) * wireFactor).rounded(.up))
        let frames = loRaFrameCount(wireBytes: wire)
        return (frames, loRaAirtimeSeconds(frames: frames), frames <= loRaMaxFrames)
    }

    /// True once a radio has completed its config handshake at least once, i.e.
    /// this device can end up sending over LoRa — whether or not the radio is
    /// connected right this second (`storeAndForward`, below, queues for it).
    ///
    /// Exposed because `MessageTextSplitter` has to know which byte budget a
    /// message must fit: 12 × `loRaChunk` on the air, or the far larger relay
    /// ceiling otherwise. It reads the same key the pairing code writes rather
    /// than a second copy of the string.
    static var isRadioPaired: Bool {
        UserDefaults.standard.string(forKey: pairedKey) != nil
    }

    /// __LORA_LONG_TEXT_OMP_2026_11_18__ The paired radio answered the OSHI Mesh probe. Stored as the
    /// radio's id rather than a bare flag, so pairing another radio forgets it by construction, and
    /// remembered across connections because `MessageTextSplitter` sizes a message at send time — often
    /// while the radio is out of range and the message is being held. Android
    /// `MeshtasticManager.lastKnownOshiFirmware`.
    private static let oshiFirmwareRadioKey = "oshi.meshtastic.oshiFirmwareRadioId"
    static var lastKnownOshiFirmware: Bool {
        guard let paired = UserDefaults.standard.string(forKey: pairedKey) else { return false }
        return UserDefaults.standard.string(forKey: oshiFirmwareRadioKey) == paired
    }
    fileprivate func rememberOshiFirmware(_ oshi: Bool) {
        let d = UserDefaults.standard
        if oshi, let paired = d.string(forKey: Self.pairedKey) {
            d.set(paired, forKey: Self.oshiFirmwareRadioKey)
        } else if !oshi {
            d.removeObject(forKey: Self.oshiFirmwareRadioKey)
        }
    }

    enum ConnState: String { case off, scanning, connecting, configuring, ready }

    @Published var bluetoothOn = false
    @Published var permissionDenied = false
    private var wantsScan = false
    /// Set while we are scanning purely to find the already-paired radio again
    /// after a Bluetooth stack reset emptied the system's peripheral cache.
    /// Distinguishes that from a user-initiated scan in the pairing sheet.
    private var reconnectViaScan = false
    @Published var state: ConnState = .off
    @Published var discovered: [(peripheral: CBPeripheral, rssi: Int)] = []
    @Published var connectedName: String?
    @Published var myNodeNum: UInt32 = 0
    @Published var nodes: [MeshtasticNode] = []
    @Published var traffic: [LoRaEvent] = []
    @Published var lastError: String?
    /// The node's regulatory region as REPORTED BY THE NODE, not as we asked
    /// for it. nil until its config has been read.
    @Published var region: LoRaRegion?
    /// False when the radio is explicitly muted. A node with tx disabled looks
    /// identical to a healthy one everywhere except here.
    @Published var transmitEnabled: Bool = true
    /// Pairing mode as REPORTED BY THE NODE. nil until its config has been read.
    @Published var pairingMode: BluetoothPairing?
    /// The channel name this phone last WROTE (empty = the stock LongFast
    /// channel). Deliberately NOT "as reported by the node": nothing reads a
    /// channel back off the radio, and the screen labels it accordingly.
    /// [LoRa channel 2026-10-26]
    @Published var channelName: String = UserDefaults.standard
        .string(forKey: MeshtasticManager.channelNameKey) ?? ""

    /// __NODE_CHANNELS_WERE_DROPPED_2026_11_04__ The channel slots the NODE
    /// reports, ordered by index, with DISABLED slots left out.
    ///
    /// The comment above says "nothing reads a channel back off the radio". That
    /// is no longer true: the node streams all eight slots during the ordinary
    /// `wantConfig` handshake and `handleFromRadio` simply had no case for field
    /// 10. This is the radio's OWN answer, as distinct from ``channelName``,
    /// which is what this phone last WROTE and is read from `UserDefaults` so the
    /// setup screen can show something before the node is even connected.
    ///
    /// Empty until the first handshake completes.
    @Published var nodeChannels: [NodeChannel] = []

    /// One of the node's eight channel slots, as the radio reports it.
    ///
    /// `Channel { index = 1, settings = 2, role = 3 }` and
    /// `Channel.Role { DISABLED = 0, PRIMARY = 1, SECONDARY = 2 }`.
    struct NodeChannel: Identifiable, Equatable {
        let index: Int
        let name: String
        let psk: Data
        let roleRaw: UInt64

        var id: Int { index }
        var isPrimary: Bool { roleRaw == 1 }
        var isSecondary: Bool { roleRaw == 2 }

        /// A DISABLED slot is not a channel the user is on. Most nodes report
        /// seven of them, and showing those would be a list of seven blanks.
        var isDisabled: Bool { roleRaw == 0 }

        /// Blank means the stock default, which users know as LongFast.
        var displayName: String { LoRaChannel.displayName(name) }
    }

    /// Why the last message OSHI tried to put on the radio did not go.
    ///
    /// 🔧 FIX (2026-08-04). `sendSecureMessage` had four refusal paths — no
    /// radio, radio muted, no region, payload too big — and every one of them
    /// returned `false` after writing a line to `OshiLog`. A log nobody can read
    /// on a phone is not a surface. From the user's side a message simply failed
    /// with no reason given, on the one feature they bought hardware for, and
    /// three of those four reasons are things they could have fixed in about ten
    /// seconds had anyone told them.
    ///
    /// Cleared by the first successful send, so it always describes a live
    /// problem rather than a historical one.
    @Published private(set) var lastRefusal: SendRefusal?
    @Published private(set) var lastRefusalAt: Date?

    enum SendRefusal: Equatable {
        case notConnected
        case radioMuted
        case noRegion
        case tooLarge(bytes: Int)
        case carriesMedia

        /// Headline: what happened, in the user's language.
        var title: String {
            switch self {
            case .notConnected: return "lora.error.not_connected".localized
            case .radioMuted:   return "lora.status.tx_off".localized
            case .noRegion:     return "lora.status.needs_region".localized
            case .tooLarge:     return "lora.blocked.too_large".localized
            case .carriesMedia: return "lora.blocked.media".localized
            }
        }

        /// What to actually do about it. The reason the headline alone is not
        /// enough: "One step left" means nothing without "choose a region".
        var detail: String {
            switch self {
            case .notConnected:
                return "lora.status.searching.detail".localized
            case .radioMuted:
                return "lora.status.tx_off.detail".localized
            case .noRegion:
                return "lora.status.needs_region.detail".localized
            case .tooLarge(let bytes):
                return String(format: "lora.blocked.too_large.detail".localized, bytes)
            case .carriesMedia:
                return "lora.blocked.media.detail".localized
            }
        }
    }

    /// Record a refusal and mirror it into the live traffic list, which is the
    /// one place a curious user is already looking when they wonder why nothing
    /// is moving.
    private func refuse(_ reason: SendRefusal) {
        // Only meaningful to someone who owns a radio. Without this, every user
        // with no node at all would collect "no node connected" refusals from
        // the off-grid fallback path — an error about hardware they never bought.
        guard UserDefaults.standard.string(forKey: Self.pairedKey) != nil else { return }
        DispatchQueue.main.async {
            self.lastRefusal = reason
            self.lastRefusalAt = Date()
        }
        log("→", "✕ " + reason.title)
        OshiLog.mesh.info("📡 Meshtastic: send refused — \(reason.title)")
    }

    /// __LORA_TOO_LARGE_WAS_NOT_A_FAILURE_2026_11_05__ The message went out by
    /// another lane, so stop reporting that the radio would not take it.
    ///
    /// Reported from a device: "Message trop long pour LoRa" sitting in the LoRa
    /// screen after messages that had all arrived perfectly well. Nothing was
    /// broken — the radio is ONE lane in a fallback chain, `deliverViaLoRa`
    /// returns false for anything over about 2 KB, and the relay then carries it.
    /// The refusal was true about the radio and false about the message, and the
    /// banner only says the first half.
    ///
    /// Only `.tooLarge` and `.carriesMedia` are cleared here, and that boundary
    /// is the whole point. Those two mean "this particular message did not suit
    /// the radio", which stops being worth saying the moment the message
    /// arrives. The other three — not connected, muted, no region — describe the
    /// RADIO being unusable, which is still true after a message goes out over
    /// the internet and is exactly what the banner exists to tell someone who
    /// bought hardware for this.
    func clearRefusalIfMessageDeliveredElsewhere() {
        switch lastRefusal {
        case .tooLarge, .carriesMedia: clearRefusal()
        default: break
        }
    }

    private func clearRefusal() {
        guard lastRefusal != nil else { return }
        DispatchQueue.main.async {
            self.lastRefusal = nil
            self.lastRefusalAt = nil
        }
    }

    var isReady: Bool { state == .ready }

    /// Meshtastic's own definition of "online": heard within the last 2 hours.
    static let onlineWindow: TimeInterval = 2 * 60 * 60

    /// Other LoRa nodes heard recently (our own radio excluded).
    func onlineNodeCount(now: Date = Date()) -> Int {
        nodes.filter { $0.num != myNodeNum && now.timeIntervalSince($0.lastHeard) < Self.onlineWindow }.count
    }

    /// What to call the connected node on screen.
    ///
    /// `connectedName` is CBPeripheral.name — the BLE *advertised* name, which
    /// iOS caches from the GAP record and does not refresh just because the node
    /// renamed itself. After a rename the settings screen kept showing
    /// "Meshtastic 23d8" for that reason. The node's own longName, learned from
    /// its NodeInfo, is the authoritative answer, so prefer it.
    var displayName: String? {
        if myNodeNum != 0,
           let mine = nodes.first(where: { $0.num == myNodeNum }),
           !mine.longName.isEmpty {
            return mine.longName
        }
        return connectedName
    }

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var toRadio: CBCharacteristic?
    private var fromRadio: CBCharacteristic?
    private var fromNum: CBCharacteristic?
    private var wantConfigId: UInt32 = 0
    /// Identifier of the radio we are mid-handshake with. Promoted to the stored
    /// `pairedKey` only when the node completes its config handshake.
    private var pendingPairId: String?
    private var draining = false

    // OSHI envelope chunk reassembly: msgId -> (total, chunks)
    private var reassembly: [UInt32: (total: Int, parts: [Int: Data], started: Date)] = [:]

    // OSHI Mesh firmware — see OshiMeshRadio.swift. `.meshtastic` until the radio answers the probe.
    @Published private(set) var firmwareFamily: LoRaFirmwareFamily = .meshtastic
    /// True once the OSHI Mesh probe had its chance to be answered: only then does `.meshtastic` mean stock firmware
    /// (the settings screen offers the upgrade on that, and must not flash it at an OSHI Mesh radio still answering).
    @Published private(set) var firmwareProbeDone = false
    /// Messages an OSHI Mesh radio is holding because their destination is not reachable yet.
    @Published private(set) var loraInCustody: Set<String> = []
    private var ompRx = OMPReassembler()
    private var ompOutgoing: [UInt32: String] = [:]
    /// The radio's channel table as reported during config: index -> (role, name).
    private var channelSlots: [Int: (role: UInt64, name: String)] = [:]
    /// Index of the private "OSHI" channel on this radio, once seen or created.
    private(set) var oshiChannelIndex: UInt32?
    // outbound queue paced for LoRa duty cycle. Each frame optionally carries the
    // OSHI message id it belongs to, so the paced drain can report real send
    // progress (one step per frame actually leaving, ~2 s apart) for media sends.
    private var sendQueue: [(frame: Data, messageId: String?)] = []
    private var sendTimer: Timer?
    /// __LORA_SILENCE_IS_A_FAILURE_2026_10_27__ Fires while anything is waiting
    /// for a mesh ACK, so the window can actually EXPIRE. See `sweepExpiredAcks`.
    private var ackTimer: Timer?

    // MARK: Jamming state (__ANTI_JAMMING_PHASE1_2026_11_13__)

    /// What `LoRaJammingDetector` makes of the signals this radio sends us
    /// (telemetry, the OSHI Mesh RADIO frame, packets heard, acks).
    /// PROBABLE_JAMMING makes `queueOrSendSecureMessage` hold messages for later
    /// and the transport policy skip LoRa (`TransportPolicy.Conditions.radioJammed`),
    /// so the Bluetooth mesh / relay carry them — unless the user chose
    /// "Send over LoRa anyway" (`jammingOverrideUntil`).
    @Published private(set) var jamming: LoRaJammingDetector.State = .clear
    /// Non-nil while the user's "Send over LoRa anyway" is in force.
    @Published private(set) var jammingOverrideUntil: Date?
    private let jammingDetector = LoRaJammingDetector()
    private var jammingOverride = LoRaJammingOverride()
    private var jammingTimer: Timer?
    private var jammingTicks = 0
    private var lastCounterStatsAt: Date = .distantPast
    /// How often the jamming state is re-evaluated while connected.
    static let jammingTick: TimeInterval = 30
    /// Ask our radio for LocalStats when no RX counters arrived for this long
    /// (stock firmware sends them every 15 min on its own).
    static let localStatsPoll: TimeInterval = 120
    /// After an unanswered request, try again this much sooner than a full poll.
    static let localStatsRetry: TimeInterval = 60
    /// TELEMETRY_APP.
    static let telemetryPortnum: UInt64 = LoRaRadioTelemetry.portnum
    /// Routing.Error values that mean "the air did not carry it": NO_ROUTE 1,
    /// TIMEOUT 3, MAX_RETRANSMIT 5. Configuration refusals (NO_CHANNEL,
    /// TOO_LARGE, PKI_*, DUTY_CYCLE_LIMIT...) say nothing about jamming.
    static let linkFailureReasons: Set<UInt64> = [1, 3, 5]
    /// Frame counts for media sends in flight, keyed by message id: how many
    /// frames the message needs, and how many have left the queue so far. Drives
    /// MessageManager.uploadProgress so the existing media-bubble bar advances.
    private var loRaFramesTotal: [String: Int] = [:]
    private var loRaFramesSent: [String: Int] = [:]

    /// __LORA_BACKGROUND_IOS_2026_11_04__ The restoration identifier. iOS keys
    /// the preserved connection on this string, so it must never change: a new
    /// value is a new manager as far as the system is concerned, and the radio
    /// link preserved under the old one is dropped silently.
    static let restoreIdentifier = "com.oshi.messenger.lora.central"

    private override init() {
        super.init()
        // __LORA_BACKGROUND_IOS_2026_11_04__ With `bluetooth-central` in
        // UIBackgroundModes and a restore identifier here, iOS keeps this
        // connection alive while OSHI is backgrounded and RELAUNCHES the app
        // into the background when the radio has something to say. Without the
        // identifier the background mode alone only survives suspension, not
        // termination, which is most of the time a phone spends.
        //
        // Only this manager gets one. `CrossPlatformMesh`'s central is
        // phone-to-phone, where the other phone has to be awake anyway, and
        // `PermissionHelper`'s is a transient probe for the authorisation
        // state — giving either a restore identifier would ask iOS to
        // resurrect the app for scans that cannot deliver a message.
        central = CBCentralManager(
            delegate: self,
            queue: .main,
            options: [CBCentralManagerOptionRestoreIdentifierKey: Self.restoreIdentifier]
        )
    }

    // MARK: Detection / pairing

    func startScan() {
        wantsScan = true                                   // resume once BT powers on (fixes first-open race)
        guard bluetoothOn else { return }
        discovered.removeAll()
        state = .scanning
        // Service-filtered scan: ONLY Meshtastic-compatible radios appear.
        central.scanForPeripherals(withServices: [Self.serviceUUID],
                                   options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        OshiLog.mesh.info("📡 Meshtastic: scanning for compatible LoRa radios…")
    }

    func stopScan() {
        central.stopScan()
        wantsScan = false
        reconnectViaScan = false
        // Restore the real state — never leave it wedged at .scanning (which
        // would block isReady and therefore ALL sends until reconnect).
        if state == .scanning { state = (peripheral != nil && toRadio != nil) ? .ready : .off }
    }

    func connect(_ p: CBPeripheral) {
        stopScan()
        peripheral = p
        p.delegate = self
        state = .connecting
        // 🔧 FIX (2026-08-06): the paired id used to be written HERE, before the
        // connection had proven anything. A failed attempt therefore left the
        // radio marked as paired forever — measured on a device: the key pointed
        // at a pairing that never completed, while the screen said "no radio".
        //
        // Worse, that stale key drives `reconnectIfPaired()` on every Bluetooth
        // power-on, so it raced the user's own manual attempt: two concurrent
        // `connect` calls to different peripherals, neither finishing.
        //
        // The id is now written only once the radio has answered the handshake
        // (see the `config_complete_id` branch), which is the first moment we
        // actually know the pairing works.
        pendingPairId = p.identifier.uuidString
        central.connect(p, options: nil)
        OshiLog.mesh.info("📡 Meshtastic: connecting to \(p.name ?? "radio")…")
    }

    func disconnect(forget: Bool = false) {
        if let p = peripheral { central.cancelPeripheralConnection(p) }
        // An in-flight attempt is abandoned here — the 20 s setup timeout calls
        // this — so it must not be promoted later.
        pendingPairId = nil
        if forget { UserDefaults.standard.removeObject(forKey: Self.pairedKey) }
        peripheral = nil; toRadio = nil; fromRadio = nil; fromNum = nil
        connectedName = nil; state = .off; myNodeNum = 0; nodes.removeAll()
        stopJammingWatch()
    }

    /// Reconnect to the previously paired radio (called on BT power-on).
    private func reconnectIfPaired() {
        guard let idStr = UserDefaults.standard.string(forKey: Self.pairedKey),
              let uuid = UUID(uuidString: idStr) else { return }
        let known = central.retrievePeripherals(withIdentifiers: [uuid])
        if let p = known.first { connect(p); return }

        // Cache miss. After a Bluetooth stack reset the system's peripheral
        // cache can come back empty even though the radio is sitting right
        // there advertising — and a stack reset delivers no disconnect
        // callback, so the retry in `didDisconnectPeripheral` never runs
        // either. Without this fallback the radio simply never comes back and
        // the only cure is re-pairing by hand.
        OshiLog.mesh.info("📡 Meshtastic: paired radio not in the system cache — scanning to find it again")
        reconnectViaScan = true
        startScan()

        // Don't scan forever on a radio that is switched off or out of range.
        DispatchQueue.main.asyncAfter(deadline: .now() + 30) { [weak self] in
            guard let self = self, self.reconnectViaScan else { return }
            self.reconnectViaScan = false
            self.stopScan()
            OshiLog.mesh.info("📡 Meshtastic: paired radio did not turn up within 30 s — giving up until the next power-on")
        }
    }

    // MARK: Handshake

    private func startConfig() {
        channelSlots.removeAll()
        oshiChannelIndex = nil
        wantConfigId = UInt32.random(in: 1...UInt32.max)
        var w = ProtoWriter()
        w.varint(3, UInt64(wantConfigId))                 // ToRadio.want_config_id
        write(w.data)
        state = .configuring
        drainFromRadio()
    }

    private func drainFromRadio() {
        guard let p = peripheral, let fr = fromRadio, !draining else { return }
        draining = true
        p.readValue(for: fr)
    }

    // MARK: Send paths

    /// __LORA_POSITION_BEACON_2026_10_28__ The `Position` payload for POSITION_APP.
    ///
    /// A static function rather than bytes built inline, so a test can call the SHIPPED
    /// encoder instead of re-deriving `(lat * 1e7).rounded()` — an arithmetic a test
    /// re-derives agrees with itself whatever the sender does.
    ///
    /// `latitude_i`/`longitude_i` are **sfixed32 scaled by 1e-7**, written little-endian
    /// and read SIGNED. A wrong sign does not corrupt the frame; it moves this node to the
    /// other side of the planet on everybody else's map.
    static func positionPayload(latitude: Double, longitude: Double, timeSec: UInt64) -> Data {
        var w = ProtoWriter()
        w.fixed32(1, UInt32(bitPattern: Int32((latitude * 1e7).rounded())))
        w.fixed32(2, UInt32(bitPattern: Int32((longitude * 1e7).rounded())))
        w.varint(4, timeSec)
        return w.data
    }

    /// __LORA_POSITION_BEACON_2026_10_28__ Broadcast OUR position on POSITION_APP, so
    /// other radios can draw us on their map — what Meshtastic does, and the half this
    /// app did not do. The receive half is `handlePosition`, and the pins went on the
    /// map today.
    ///
    /// ⚠️ THIS IS A PUBLIC, CLEARTEXT BROADCAST. It goes to the broadcast address on the
    /// default LongFast channel, whose key is published in Meshtastic's own source, to
    /// every radio in range — tens of kilometres, to people the user has never met and
    /// cannot enumerate. That is what a mesh beacon IS, and it is why there is no timer
    /// here and no default: the caller must be a deliberate user action. iOS removed
    /// group messages from the mesh for exactly this property (PARITY row 0.17-a: they
    /// "broadcast group bodies in cleartext to every nearby device, member or not").
    ///
    /// A 0/0 fix is REFUSED rather than sent: that is what a receiver with no lock
    /// reports, and broadcasting it puts this node in the Gulf of Guinea on every map
    /// that hears it — the same guard the receive path already applies.
    /// ⛔️ MEASURED NOT TO WORK (2026-10-28). Use `setFixedPosition` instead.
    ///
    /// A client POSITION_APP packet sent to the broadcast address is accepted by the radio
    /// and reaches no other node. Two ThinkNode M1s in one room: ten of these over four
    /// minutes arrived nowhere, while a plain interop TEXT broadcast from the SAME radio in
    /// the same second arrived in 3 seconds, on a mesh where the receiver was reading 36
    /// other nodes' positions correctly. Every write returned true — a write is bytes
    /// leaving the process, not a transmission.
    ///
    /// Kept rather than deleted so the next reader finds the measurement instead of
    /// rediscovering it, and because its encoder is still the correct `Position` encoding —
    /// `setFixedPosition` wraps the same bytes in the admin message the firmware honours.
    @available(*, deprecated, message: "A client POSITION_APP broadcast reaches no node (measured 2026-10-28). Use setFixedPosition(latitude:longitude:).")
    @discardableResult
    func broadcastPosition(latitude: Double, longitude: Double) -> Bool {
        guard isReady else { lastError = "Radio not connected"; return false }
        guard !(latitude == 0 && longitude == 0) else { return false }
        guard latitude >= -90, latitude <= 90, longitude >= -180, longitude <= 180 else { return false }
        let payload = Self.positionPayload(latitude: latitude, longitude: longitude,
                                           timeSec: UInt64(Date().timeIntervalSince1970))
        enqueuePacket(to: Self.broadcastAddr, portnum: Self.positionPortnum,
                      payload: payload, wantAck: false)
        log("→", String(format: "position %.5f, %.5f → ALL", latitude, longitude))
        return true
    }

    /// __LORA_FIXED_POSITION_2026_10_28__ Tell the RADIO where it is and let its own
    /// PositionModule announce it. **This is the one that works.**
    ///
    /// `AdminMessage{ set_fixed_position: Position }` is field **41**, continuing the
    /// enumeration already proven against a real radio here: `set_owner` 32,
    /// `set_channel` 33, `set_config` 34. The firmware owns a node's position; a client
    /// does not announce one on its behalf, and `broadcastPosition` above records what
    /// happens when it tries.
    ///
    /// ⚠️ Unlike a broadcast this WRITES THE NODE'S CONFIGURATION and persists on the
    /// device: a radio told it is in Geneva stays in Geneva for every app that connects to
    /// it afterwards, until `clearFixedPosition()`.
    @discardableResult
    func setFixedPosition(latitude: Double, longitude: Double) -> Bool {
        guard isReady else { lastError = "Radio not connected"; return false }
        guard myNodeNum != 0 else { return false }
        guard !(latitude == 0 && longitude == 0) else { return false }
        guard latitude >= -90, latitude <= 90, longitude >= -180, longitude <= 180 else { return false }
        var position = ProtoWriter()
        position.fixed32(1, UInt32(bitPattern: Int32((latitude * 1e7).rounded())))
        position.fixed32(2, UInt32(bitPattern: Int32((longitude * 1e7).rounded())))
        var admin = ProtoWriter()
        admin.bytes(41, position.data)          // AdminMessage.set_fixed_position
        enqueuePacket(to: myNodeNum, portnum: Self.adminPortnum,
                      payload: admin.data, wantAck: true)
        log("→", String(format: "fixed position %.5f, %.5f → my node", latitude, longitude))
        return true
    }

    /// Clear the node's stored fixed position — `AdminMessage{ remove_fixed_position: true }`,
    /// field 42. The undo for `setFixedPosition`.
    @discardableResult
    func clearFixedPosition() -> Bool {
        guard isReady, myNodeNum != 0 else { return false }
        var admin = ProtoWriter()
        admin.varint(42, 1)
        enqueuePacket(to: myNodeNum, portnum: Self.adminPortnum,
                      payload: admin.data, wantAck: true)
        log("→", "fixed position cleared")
        return true
    }

    /// INTEROP: plain text on the standard Meshtastic text port — readable by
    /// the official Meshtastic app and every other client on the mesh.
    ///
    /// Returns true only once the frame is actually queued to a connected
    /// radio — the same true-means-written contract as `setRegion` above —
    /// so `MessageManager.sendLoRaInteropReply` can tell a reply typed with
    /// no radio in range apart from one that actually went out.
    ///
    /// - Parameter channel: which of the radio's channel slots to transmit on.
    ///   Defaults to 0, the primary, which is where every OSHI envelope and
    ///   every direct message belongs. A LoRa channel ROOM passes its own slot —
    ///   see `__CHANNEL_ROOMS_ALL_SENT_ON_SLOT_ZERO_2026_11_07__`.
    @discardableResult
    func sendText(_ text: String,
                  to node: UInt32 = MeshtasticManager.broadcastAddr,
                  channel: Int = 0) -> Bool {
        guard isReady else { lastError = "Radio not connected"; return false }
        enqueuePacket(to: node, portnum: Self.textPortnum, payload: Data(text.utf8),
                      wantAck: node != Self.broadcastAddr,
                      channel: UInt32(max(0, channel)))
        log("→", "text \(text.prefix(24))\(text.count > 24 ? "…" : "") → \(node == Self.broadcastAddr ? "ALL" : String(format: "!%08x", node))"
            + (channel != 0 ? " ch\(channel)" : ""))
        return true
    }

    // Store-and-forward: envelopes accepted while the radio is off/out of range,
    // flushed once the radio is ready AND its firmware is known.
    // __LORA_HELD_DURABLE_2026_11_18__ Was an in-memory array capped at 20 that dropped the oldest in
    // silence; now persisted (at-rest encrypted FileStorage, meshWireSafe copies only), and anything
    // that cannot stay is reported to MessageManager and shown failed. Main queue only (CoreBluetooth's
    // delegate queue here), like the rest of this class.
    private lazy var storeAndForward = LoRaHeldQueue(
        load: { FileStorage.shared.load(forKey: LoRaHeldQueue.storageKey) },
        save: { FileStorage.shared.save($0, forKey: LoRaHeldQueue.storageKey) })

    /// M2 entry point for MessageManager's last-resort lane. Returns true ONLY
    /// when frames were actually written to a connected radio. A merely-QUEUED
    /// message returns false so the caller keeps its normal .failed +
    /// retry-queue path — the store-and-forward flush is then a free second
    /// chance (receivers dedup by message id across transports), never a
    /// silently-lost "sent".
    @discardableResult
    func queueOrSendSecureMessage(_ message: SecureMessage) -> Bool {
        // Media is checked HERE rather than at the call site so that every LoRa
        // refusal is recorded in one place. It used to be a bare `guard` in
        // `MessageManager.deliverViaLoRa` that returned false and said nothing —
        // and "my photo won't send off-grid" is precisely the case where a
        // person needs to be told that LoRa carries text and nothing else.
        // Media over LoRa is opt-in and off by default (see MeshtasticSettingsView).
        // When it is OFF, keep the historical refusal in one place so the Settings
        // banner explains why the photo did not go out. When the person has turned
        // it ON, fall through: sendSecureMessage transmits only the ciphertext
        // (meshWireSafe keeps encryptedContent and drops the plaintext sidecar),
        // and its ~12-frame ceiling is the real size gate — anything larger is
        // refused there as .tooLarge, the honest answer for a photo too big for
        // the air. [Media-over-LoRa Phase 1 2026-09-11]
        let carriesMedia = message.mediaAttachment != nil || message.originalMediaData != nil
        if carriesMedia && !Self.mediaOverLoRaEnabled {
            refuse(.carriesMedia)
            return false
        }
        if isReady {
            // __ANTI_JAMMING_PHASE1_2026_11_13__ / __LORA_PROBE_BEFORE_FLUSH_2026_11_18__ Held, not
            // sent: the channel is excluded for jamming, or the firmware probe has not been answered
            // yet (sent now, an OSHI Mesh radio's message would take the 12-frame stock path). Return
            // false so the caller's normal chain (mesh, relay, retry queue) carries it now. Receivers
            // dedupe by message id.
            if let why = LoRaRadioGate.holdReason(probeDone: firmwareProbeDone,
                                                  answeredOSHI: firmwareFamily == .oshi,
                                                  excludedForJamming: excludesLoRaForJamming) {
                holdForLater(message, why: why)
                return false
            }
            return sendSecureMessage(message)
        }
        refuse(.notConnected)
        // Radio paired but not currently connected → hold for the flush, but
        // report failure so the message stays visibly unsent + retryable.
        if UserDefaults.standard.string(forKey: Self.pairedKey) != nil {
            holdForLater(message, why: "radio not connected")
        }
        return false
    }

    /// __NEVER_DROP_HELD_2026_11_18__ Keep `message` for the paired radio without trying to send it
    /// now — MessageManager's "internet sending off" exit, where the policy left the radio out and
    /// nothing else may carry it. False when it cannot be held (no radio, media not opted in, too large).
    @discardableResult
    func holdForRadio(_ message: SecureMessage) -> Bool {
        guard UserDefaults.standard.string(forKey: Self.pairedKey) != nil else { return false }
        let carriesMedia = message.mediaAttachment != nil || message.originalMediaData != nil
        if carriesMedia && !Self.mediaOverLoRaEnabled { return false }
        if storeAndForward.contains(message.id) { return true }
        let held = holdForLater(message, why: "kept for the radio: internet sending is off")
        if held { flushStoreAndForward() }
        return held
    }

    func isHeld(_ messageId: String) -> Bool { storeAndForward.contains(messageId) }

    /// The paired radio is (or was, at its last connection) an OSHI Mesh radio.
    private var oshiFirmwareNowOrLast: Bool { firmwareFamily == .oshi || Self.lastKnownOshiFirmware }

    /// False — and `.tooLarge` recorded — when the envelope cannot fit the path it will take; the caller's
    /// chain then decides, and marks the message failed at its end. Was a silent `return`, sized for the
    /// 12-frame stock path even for an OSHI Mesh radio.
    @discardableResult
    private func holdForLater(_ message: SecureMessage, why: String) -> Bool {
        let wireSafe = message.meshWireSafe()
        guard let json = try? JSONEncoder().encode(wireSafe),
              LoRaRadioGate.fits(ompBodyBytes: OMP.encodeBody(json).count, oshiFirmware: oshiFirmwareNowOrLast) else {
            let size = (try? JSONEncoder().encode(message.meshWireSafe()))?.count ?? 0
            refuse(.tooLarge(bytes: size))
            log("→", "not held for radio (\(why)): too large for this radio")
            return false
        }
        let evicted = storeAndForward.add(wireSafe, now: Date())
        log("→", "held for radio: \(why) (\(storeAndForward.count) waiting)")
        if !evicted.isEmpty {
            log("!", "\(evicted.count) held message(s) over the \(storeAndForward.capacity) limit: marked not delivered")
            for id in evicted { MessageManager.shared?.loRaHeldUndeliverable(messageId: id) }
        }
        return true
    }

    /// Fold base64url/padding variants so key comparison can't silently drop a
    /// legitimate recipient (same normalization as BlockedContactsManager).
    static func normalizeKey(_ k: String) -> String {
        k.replacingOccurrences(of: "-", with: "+")
         .replacingOccurrences(of: "_", with: "/")
         .replacingOccurrences(of: "=", with: "")
         .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// __LORA_HELD_DURABLE_2026_11_18__ / __LORA_PROBE_BEFORE_FLUSH_2026_11_18__ Was "copy, clear, send
    /// all" — whatever failed to send was gone — and ran before the firmware probe answered. Each item now
    /// leaves only once its frames are on the radio, or when it is known unwanted (delivered another way,
    /// deleted) or undeliverable (too large for the radio we now have: shown failed). The first transient
    /// refusal (muted, no region) stops the drain and keeps the rest.
    private func flushStoreAndForward() {
        guard LoRaRadioGate.mayFlush(ready: isReady, probeDone: firmwareProbeDone,
                                     answeredOSHI: firmwareFamily == .oshi,
                                     excludedForJamming: excludesLoRaForJamming),
              !storeAndForward.isEmpty else { return }
        for h in storeAndForward.snapshot() {
            guard isReady, !excludesLoRaForJamming else { return }
            let m = h.message
            if let mm = MessageManager.shared, !mm.loRaHeldStillWanted(messageId: m.id) {
                storeAndForward.remove(m.id)
                log("→", "held msg \(m.id.prefix(8)) no longer needs LoRa (delivered or deleted)")
                continue
            }
            let bodyBytes = (try? JSONEncoder().encode(m)).map { OMP.encodeBody($0).count } ?? .max
            if !LoRaRadioGate.fits(ompBodyBytes: bodyBytes, oshiFirmware: firmwareFamily == .oshi) {
                storeAndForward.remove(m.id)
                refuse(.tooLarge(bytes: bodyBytes))
                MessageManager.shared?.loRaHeldUndeliverable(messageId: m.id)
                continue
            }
            if sendSecureMessage(m) {
                storeAndForward.remove(m.id)
                MessageManager.shared?.loRaHeldSent(messageId: m.id)
            } else {
                return
            }
        }
    }

    /// OSHI↔OSHI: full E2E SecureMessage over LoRa, chunked. Broadcast by
    /// default — every OSHI phone on the mesh sees the ciphertext, but only the
    /// recipient whose key matches can decrypt; everyone else drops it.
    ///
    /// That sentence was only true of the BLE/Wi-Fi mesh. `SecureMessage` has no
    /// custom `encode(to:)` and `plaintextContent` is in its CodingKeys, so
    /// encoding the message whole put the user's text on the air in the clear,
    /// side by side with the ciphertext — on the default LongFast channel, whose
    /// key is public. `meshWireSafe()` is the strip the other mesh path has
    /// applied since the [C2] audit; the LoRa path was simply never given it.
    /// __LORA_ACK_2026_10_10__ `to node` is now OPTIONAL, and nil means "work
    /// it out", which is what finally makes delivery confirmation possible.
    ///
    /// THE BUG. The default used to be `broadcastAddr`, and no caller ever
    /// passed anything else — `queueOrSendSecureMessage` and
    /// `flushStoreAndForward` both call this with the message alone. Since
    /// `wantAck = node != broadcastAddr` a few lines down, `wantAck` was
    /// therefore **always false**, so the radio never asked the mesh to confirm
    /// anything and `loraPendingAck` / `loraAcked` / the 90 s window /
    /// `handleRouting`'s ROUTING_APP case — all of it already written and
    /// correct — could never fire for a 1:1 message. The 2026-08-07 fix that
    /// introduced `wantAck` was unreachable from the day it shipped.
    ///
    /// Off-grid is exactly where "did it arrive?" matters most, and it was the
    /// one path that could not answer.
    ///
    /// Resolving the node here rather than at the call sites so every path gets
    /// it. `LoRaNodeIdentityMap` only knows a node number for a peer we have
    /// actually HEARD from over LoRa, which is precisely the condition under
    /// which unicast can work; with no binding we keep broadcasting exactly as
    /// before, and simply get no ack — the old behaviour, not a regression.
    func sendSecureMessage(_ message: SecureMessage, to node: UInt32? = nil) -> Bool {
        // A node with no region set, or with its radio muted, accepts the frame
        // and transmits nothing. Reporting that as sent turns a lost message
        // into a delivered one on screen.
        //
        // But refuse only on EVIDENCE. `region == nil` means we have not read
        // the node's config yet — not that it has no region. Collapsing nil to
        // .unset (which is what `(region ?? .unset)` did) blocked every send
        // whenever the config had not landed, silently, which is a worse
        // failure than the one this guard exists to prevent. Unknown is not
        // zero — the same rule LoRaNodeStatus follows for measured values.
        guard isReady else { refuse(.notConnected); return false }
        guard transmitEnabled else { refuse(.radioMuted); return false }
        if let r = region, r == .unset {
            refuse(.noRegion)
            return false
        }
        guard let json = try? JSONEncoder().encode(message.meshWireSafe()) else { return false }
        // __LORA_ACK_2026_10_10__ Unicast when we know where the peer is,
        // broadcast when we do not. Unicast is what earns an ACK; it is also
        // cheaper on shared airtime than flooding every node on the channel.
        // Resolved before the firmware split so OSHI Mesh radios (OMP dest +
        // custody) and stock radios (OMP frames) address the same node.
        let target = node
            ?? LoRaNodeIdentityMap.shared.node(forPublicKey: message.recipientPublicKey)
            ?? Self.broadcastAddr
        if target != Self.broadcastAddr {
            log("→", "unicast to !\(String(format: "%08x", target)) (ack requested)")
        }
        if firmwareFamily == .oshi {
            return sendViaOMP(json, message: message, to: target)
        }
        // Stock radio: the phone does OMP itself — same DATA frames as an OSHI Mesh radio would send, with our
        // node number as origin, broadcast on the OSHI channel. Stock relays carry them opaque, OSHI Mesh nodes
        // hand them whole to their phone, and the MeshCore bridge recognises them (it ignores "OM").
        let msgId = UInt32.random(in: 1...UInt32.max)
        let body = OMP.encodeBody(json)
        guard let frames = OMP.dataFrames(msgId: msgId, dest: target, body: body, origin: myNodeNum),
              frames.count <= 12 else {                    // ~2 KB cap: radio TX queue is shallow (~16-32) and
                                                           // LongFast airtime ~1-2 s/frame — larger sends overflow
                                                           // the queue and die in reassembly (protocol review)
            refuse(.tooLarge(bytes: body.count))
            return false
        }
        // Media sends get real per-frame progress: register the frame total so
        // the paced drain can advance MessageManager.uploadProgress as each frame
        // leaves (~2 s apart). Text is left untagged — its bubble shows no bar.
        let carriesMedia = message.mediaAttachment != nil || message.originalMediaData != nil
        if carriesMedia {
            loRaFramesTotal[message.id] = frames.count
            loRaFramesSent[message.id] = 0
        }
        // Combines both lineages: OMP `frames` (feat/oshi-mesh-radio) sent to the
        // resolved unicast/broadcast `target` with the ack window armed for
        // broadcasts too (production hardening).
        for (i, frame) in frames.enumerated() {
            // 🔧 FIX (2026-08-07): these went out `wantAck: false`, so the radio
            // never asked the mesh to confirm delivery — and with no ROUTING_APP
            // case in the receive switch, an acknowledgement would have been
            // discarded anyway. A fallback that cannot detect its own failure is
            // not a fallback.
            //
            // Only meaningful when UNICAST: Meshtastic does not acknowledge
            // broadcasts, so asking on one sets a flag nobody will ever answer.
            let wantAck = target != Self.broadcastAddr
            let packetId = enqueuePacket(to: target, portnum: Self.oshiPortnum,
                                         payload: frame, wantAck: wantAck,
                                         messageId: carriesMedia ? message.id : nil,
                                         channel: oshiChannelIndex ?? 0)
            // Track the LAST frame only. The mesh acks per packet, and a
            // multi-frame message is delivered when its final frame lands —
            // tracking every chunk would report "delivered" on the first one.
            //
            // __BROADCAST_SILENCE_IS_ALSO_A_FAILURE_2026_10_27__ The condition
            // was `wantAck && …`, which meant ONLY a unicast was ever watched.
            // `wantAck` is false for a broadcast because Meshtastic does not
            // acknowledge broadcasts — and the code above picks broadcast
            // whenever `LoRaNodeIdentityMap` has no binding for the recipient,
            // which on a fresh install is EVERY contact. So the common case
            // armed nothing: no pending entry, no expiry, no
            // `.loRaDeliveryFailed`, no escalation — and `deliverViaLoRa` still
            // returned true, so the message was marked sent and dropped from
            // the retry queue. That is the very failure 185 was written to kill
            // ("frames left" read as "delivered"), surviving in the branch 185
            // did not touch.
            //
            // A broadcast that cannot be acknowledged is not a send with no
            // verdict; it is a send whose ONLY possible verdict is silence.
            // So it arms the same window, and when the window closes the relay
            // picks the message up. A peer that did hear the broadcast answers
            // with a delivery receipt, which lifts the message to `.delivered`
            // and makes `escalateAfterLoRa`'s `alreadyDelivered` guard refuse
            // the escalation — so the duplicate this could otherwise cause is
            // already handled, by id, one layer up.
            if Self.armsDeliveryWindow(isLastChunk: i == frames.count - 1, unicast: wantAck) {
                trackPendingAck(packetId: packetId, messageId: message.id, unicast: wantAck)
            }
        }
        log("→", "OSHI msg \(message.id.prefix(8)) as OMP via stock radio (\(frames.count) LoRa frame\(frames.count > 1 ? "s" : ""), \(body.count)B)")
        clearRefusal()   // frames are on the wire: whatever was wrong no longer is
        return true
    }

    // MARK: Node configuration (region)

    /// Meshtastic `PortNum.ADMIN_APP`. Admin messages addressed to the node we
    /// are physically connected to are accepted over the local BLE link without
    /// a session passkey — that is only required for administering a REMOTE node.
    /// ROUTING_APP — how Meshtastic reports whether a packet sent with
    /// `want_ack` reached its destination. Without a case for it in the receive
    /// switch, every acknowledgement the mesh sent back was silently discarded,
    /// which is why LoRa delivery could never be confirmed OR disproved.
    static let routingPortnum: UInt64 = 5

    /// POSITION_APP — a node broadcasting where it is.
    ///
    /// __LORA_POSITION_LIVE_2026_10_27__ This had no case in the receive switch,
    /// so every live position a GPS node put on the air was dropped by
    /// `default: break`. Positions still APPEARED, which is what made the gap
    /// invisible: the radio dumps its whole node database as `FromRadio`
    /// `node_info` at connect, and `handleNodeInfo` parses the Position inside
    /// it. So a node that already had a fix when you connected showed up, and
    /// the app looked like it understood GPS.
    ///
    /// What never worked is the case that matters in the mountains: connect at
    /// the car park with no fix, walk up, the node gets its fix — and OSHI goes
    /// on showing nothing, because the only moment it ever learned a position
    /// was the connect it already did. Same for a node that MOVES: its pin
    /// stayed where it was when you paired.
    static let positionPortnum: UInt64 = 3

    /// Message ids the mesh has confirmed. Read by the UI to show a LoRa
    /// message as delivered rather than merely sent.
    @Published private(set) var loraAcked: Set<String> = []

    /// One radio send still waiting for a verdict.
    ///
    /// `unicast` is kept because it decides what silence MEANS: a unicast that
    /// goes unanswered might have been heard and lost its ACK, while a
    /// broadcast was never going to be answered at all.
    struct PendingRadioSend: Equatable {
        let id: String
        let at: Date
        let unicast: Bool
    }

    /// Packet id → the OSHI message it carries, for sends still awaiting a
    /// verdict.
    ///
    /// Pruned on every insert. A map that only grows is a leak in an app that
    /// stays resident for days, and this one is fed by every LoRa send.
    private var loraPendingAck: [UInt32: PendingRadioSend] = [:]

    /// __BROADCAST_SILENCE_IS_ALSO_A_FAILURE_2026_10_27__ Which sends have run
    /// out of time, as a pure function of the map and the clock.
    ///
    /// Extracted from `sweepExpiredAcks` for one reason: the 185 fix
    /// (`__LORA_SILENCE_IS_A_FAILURE_2026_10_27__`) could only be exercised
    /// through a live CoreBluetooth stack, so its 18 tests all sat on
    /// `TransportPolicy` — the half that decides where a message goes — and
    /// none of them could see the half that decides whether anyone is WATCHING
    /// the radio. That is exactly how the broadcast hole survived: the policy
    /// published `[.loRa, .internetRelay]`, the escalation worked when asked,
    /// and nothing ever asked.
    static func expiredPacketIds(_ pending: [UInt32: PendingRadioSend],
                                 now: Date = Date(),
                                 window: TimeInterval = MeshtasticManager.loraAckWindow) -> [UInt32] {
        let cutoff = now.addingTimeInterval(-window)
        return pending.filter { $0.value.at <= cutoff }.keys.sorted()
    }

    /// Whether the frame just handed to the radio must arm the delivery-expiry
    /// window.
    ///
    /// Deliberately does NOT depend on `unicast` — that dependency WAS the bug
    /// (`__BROADCAST_SILENCE_IS_ALSO_A_FAILURE_2026_10_27__`). The parameter
    /// stays so that "does the send mode change the answer?" is asked and
    /// answered in one testable place, instead of being re-derived inside the
    /// chunk loop where the last version of this rule quietly excluded every
    /// broadcast. Only the final frame arms it: the mesh answers per packet, so
    /// arming on an earlier chunk would resolve the message before its last
    /// frame had left.
    static func armsDeliveryWindow(isLastChunk: Bool, unicast: Bool) -> Bool {
        isLastChunk
    }

    /// How long an unacknowledged send stays interesting. Beyond this the mesh
    /// has almost certainly not delivered it: Meshtastic retries for well under
    /// a minute, so a later ACK would be for a packet id already reused.
    static let loraAckWindow: TimeInterval = 90

    static let adminPortnum: UInt64 = 6

    /// Set the node's regulatory region, and enable transmission.
    ///
    /// THIS IS THE STEP THAT MAKES A NEW NODE USABLE. Straight from the flasher a
    /// node boots with `region=UNSET` and will not put anything on the air:
    ///
    ///     INFO | Wanted region 0, using UNSET
    ///
    /// It pairs, it reports connected, and it silently transmits nothing. Until
    /// this is written, the node cannot send or receive a single message.
    ///
    /// ⚠️ The whole `LoRaConfig` is REPLACED by what we send — protobuf omits
    /// defaults, so any field left out arrives as zero. Leaving `usePreset` out
    /// would send `false`, which switches the radio to manual bandwidth/spread
    /// factor of 0 and produces a node that is configured, on the air, and unable
    /// to talk to anyone. Every field that matters is therefore set explicitly.
    ///
    /// - Returns: false when no radio is connected; true once the frame is queued.
    @discardableResult
    // 7, not 8: hop_limit is a 3-bit field on the wire. Meshtastic's
    // config.proto says it outright — "Attempting to set a value > 7
    // results in the default" (3) — so 8 would silently UNDO this change.
    func setRegion(_ region: LoRaRegion,
                   preset: LoRaModemPreset = .longFast,
                   hopLimit: UInt64 = 7) -> Bool {
        guard isReady, myNodeNum != 0 else {
            lastError = "lora.error.not_connected".localized
            return false
        }
        guard region != .unset else { return false }

        // Config.LoRaConfig
        var lora = ProtoWriter()
        lora.varint(1, 1)                       // use_preset = true
        lora.varint(2, preset.rawValue)         // modem_preset
        lora.varint(7, region.rawValue)         // region
        lora.varint(8, hopLimit)                // hop_limit
        lora.varint(9, 1)                       // tx_enabled = true

        var config = ProtoWriter()
        config.bytes(6, lora.data)              // Config.lora

        var admin = ProtoWriter()
        // Field 34, NOT 33. AdminMessage.set_channel is 33 and carries a
        // Channel; set_config is 34 and carries a Config. Sending a Config
        // under 33 meant the node tried to parse it as a Channel and threw it
        // away — so "Set region" silently did nothing, on the one screen whose
        // entire purpose is to get a node out of region=UNSET. Verified against
        // the Meshtastic protobuf descriptors, not from memory.
        admin.bytes(34, config.data)            // AdminMessage.set_config

        // Addressed to ourselves: this configures the node on the other end of
        // the BLE link, not a peer on the mesh.
        enqueuePacket(to: myNodeNum, portnum: Self.adminPortnum,
                      payload: admin.data, wantAck: true)
        log("→", "set region \(region.displayName), preset \(preset.displayName)")
        OshiLog.mesh.info("📡 Meshtastic: region -> \(region.displayName)")
        return true
    }

    /// Join the public Meshtastic mesh used by Chile's community.
    ///
    /// This is deliberately opt-in: replacing slot 0 changes the node's
    /// PRIMARY channel and would otherwise disconnect somebody from a private
    /// mesh. The packets are appended in this order, so the radio receives its
    /// ANZ/LongFast configuration before the primary-channel update.
    @discardableResult
    func setChileMeshDefaults() -> Bool {
        guard setRegion(.anz, preset: .longFast) else { return false }

        var settings = ProtoWriter()
        settings.bytes(2, Data([1]))                 // AQ==, Meshtastic default PSK
        settings.bytes(3, Data("LongFast".utf8))
        var channel = ProtoWriter()
        channel.varint(1, 0)                          // primary slot
        channel.bytes(2, settings.data)
        channel.varint(3, 1)                          // Channel.Role.PRIMARY
        var admin = ProtoWriter()
        admin.bytes(33, channel.data)                 // AdminMessage.set_channel
        enqueuePacket(to: myNodeNum, portnum: Self.adminPortnum,
                      payload: admin.data, wantAck: true)
        log("→", "set Chile community defaults (ANZ, LongFast)")
        return true
    }

    /// How the node asks to be paired over Bluetooth.
    ///
    /// Out of the box a node shows a random 6-digit code on its little OLED and
    /// iOS asks you to type it. That is the safe default and it stays the
    /// default here — but it assumes the node has a working screen you can read,
    /// which is not true of every board (several have no display at all), and it
    /// turns a one-tap pairing into a squint-and-type.
    enum BluetoothPairing: UInt64, CaseIterable, Identifiable {
        case randomPin = 0   // code shown on the node's screen
        case noPin     = 2   // pairs on a single tap

        var id: UInt64 { rawValue }
        var displayName: String {
            switch self {
            case .randomPin: return "lora.pairing.pin".localized
            case .noPin:     return "lora.pairing.nopin".localized
            }
        }
        var detail: String {
            switch self {
            case .randomPin: return "lora.pairing.pin.detail".localized
            case .noPin:     return "lora.pairing.nopin.detail".localized
            }
        }
    }

    /// A node straight from the flasher calls itself "Meshtastic 23d8". Anyone
    /// carrying two of them, or looking at a mesh map, cannot tell which is
    /// theirs or that it belongs to OSHI at all.
    ///
    /// Only renames a node still wearing the factory default. A name the owner
    /// chose is theirs — clobbering it because they happened to pair with us
    /// would be rude, and they may well use the node with other clients too.
    private static let factoryName = try! NSRegularExpression(
        pattern: "^Meshtastic [0-9a-fA-F]{4}$")

    /// The name a node adopts once OSHI adopts it.
    ///
    /// Changed 2026-08-05 from `OSHI_Messenger_23D8` to `OSHI Mesh 23D8`. The old
    /// form was a snake_case identifier, not a name: it is what shows on the
    /// node's own screen, in the official Meshtastic app, and on public mesh
    /// maps, where it read like a debug string. Spaces and the product's actual
    /// name read as a device someone owns.
    ///
    /// The hex suffix stays. Anyone carrying two nodes needs to tell them apart,
    /// and a mesh map with a dozen identical "OSHI Mesh" pins is useless.
    static func adoptedName(for nodeNum: UInt32) -> String {
        "OSHI Mesh \(String(format: "%04X", nodeNum & 0xFFFF))"
    }

    @discardableResult
    func renameIfFactoryDefault() -> Bool {
        guard isReady, myNodeNum != 0 else { return false }
        guard let mine = nodes.first(where: { $0.num == myNodeNum }) else { return false }

        let current = mine.longName
        let range = NSRange(current.startIndex..., in: current)
        // Also adopt nodes still wearing the PREVIOUS OSHI name, so anyone who
        // paired before this change picks up the new one on the next connect
        // instead of being stuck with the identifier form forever.
        let wasOldOSHIName = current.hasPrefix("OSHI_Messenger_")
        guard Self.factoryName.firstMatch(in: current, range: range) != nil || wasOldOSHIName else {
            return false                                   // owner picked this name
        }

        let hex = String(format: "%04X", myNodeNum & 0xFFFF)
        return setOwner(longName: Self.adoptedName(for: myNodeNum), shortName: hex)
    }

    /// __SHORT_NAME_IS_BYTES_2026_10_21__ Meshtastic caps `User.short_name` at
    /// four BYTES, not four characters, and the difference is the whole feature.
    ///
    /// This used to send `Data(shortName.prefix(4).utf8)`. `prefix(4)` counts
    /// Characters, so "ABCD" fitted (4 bytes) but a single emoji is already 4
    /// bytes and four of them are sixteen — the node then stores a truncated
    /// prefix, which cuts a multi-byte sequence in half and leaves a short name
    /// that renders as a replacement glyph on every other client. The old doc
    /// comment said "4 characters", which is exactly the misconception.
    ///
    /// `clampShortName` counts bytes and never splits a character: one emoji
    /// fits, "TEA" fits, "TEAM" fits, "🚀🚀" does not and comes back as one.
    static func clampShortName(_ raw: String) -> String {
        var out = ""
        var used = 0
        for ch in raw {
            let n = String(ch).utf8.count
            if used + n > 4 { break }
            out.append(ch)
            used += n
        }
        return out
    }

    /// AdminMessage.set_owner (32) carrying a User.
    @discardableResult
    func setOwner(longName: String, shortName: String) -> Bool {
        guard isReady, myNodeNum != 0 else {
            lastError = "lora.error.not_connected".localized
            return false
        }

        var user = ProtoWriter()
        user.bytes(1, Data(String(format: "!%08x", myNodeNum).utf8))   // User.id
        user.bytes(2, Data(longName.utf8))                             // User.long_name
        user.bytes(3, Data(Self.clampShortName(shortName).utf8))       // User.short_name (≤4 BYTES)

        var admin = ProtoWriter()
        admin.bytes(32, user.data)                                     // AdminMessage.set_owner

        enqueuePacket(to: myNodeNum, portnum: Self.adminPortnum,
                      payload: admin.data, wantAck: true)
        log("→", "renamed node to \(longName)")
        OshiLog.mesh.info("📡 Meshtastic: renamed node -> \(longName)")
        return true
    }

    /// Put the node on a channel — the "which mesh am I on" setting.
    ///
    /// `AdminMessage.set_channel` is FIELD 33 and carries a `Channel`, the exact
    /// counterpart of the warning in `setRegion`: 34 carries a Config, and
    /// swapping the two is what once made a config write silently do nothing.
    ///
    ///     Channel          { index = 1, settings = 2, role = 3 }
    ///     ChannelSettings  { psk = 2, name = 3 }
    ///     Channel.Role     { DISABLED = 0, PRIMARY = 1, SECONDARY = 2 }
    ///
    /// Index 0 + role PRIMARY is the slot a node's main channel lives in, and
    /// the slot the Meshtastic app edits when it says "Channel".
    ///
    /// Both halves are written explicitly, INCLUDING an empty name: this
    /// replaces the channel wholesale, so a field left out arrives as zero —
    /// and a psk left out is "no encryption at all", not "keep what you had".
    /// An empty name with ``LoRaChannel/defaultPSK`` is the stock LongFast
    /// channel, which is how "put me back on the normal mesh" is reachable.
    ///
    /// [LoRa channel 2026-10-26]
    @discardableResult
    func setChannel(name: String, psk: Data = LoRaChannel.defaultPSK) -> Bool {
        guard isReady, myNodeNum != 0 else {
            lastError = "lora.error.not_connected".localized
            return false
        }
        let clamped = LoRaChannel.clampName(name)

        var settings = ProtoWriter()
        settings.bytes(2, psk)                          // ChannelSettings.psk
        settings.bytes(3, Data(clamped.utf8))           // ChannelSettings.name (≤ 11 BYTES)

        var channel = ProtoWriter()
        channel.varint(1, 0)                            // Channel.index = 0 (primary slot)
        channel.bytes(2, settings.data)                 // Channel.settings
        channel.varint(3, 1)                            // Channel.role = PRIMARY

        var admin = ProtoWriter()
        admin.bytes(33, channel.data)                   // AdminMessage.set_channel

        enqueuePacket(to: myNodeNum, portnum: Self.adminPortnum,
                      payload: admin.data, wantAck: true)
        UserDefaults.standard.set(clamped, forKey: Self.channelNameKey)
        channelName = clamped
        log("→", "set channel \(LoRaChannel.displayName(clamped))")
        OshiLog.mesh.info("📡 Meshtastic: channel -> \(LoRaChannel.displayName(clamped))")
        return true
    }

    /// `Channel { index = 1, settings = 2, role = 3 }` as reported by the node.
    ///
    /// Replaces by INDEX rather than appending: the node re-sends all eight slots
    /// on every handshake, and appending would grow the list without bound and
    /// show the same channel four times after four reconnects.
    ///
    /// DISABLED slots are dropped HERE rather than in the UI, because a node
    /// reports seven of them and every consumer would otherwise have to remember
    /// to filter — the kind of thing one of them eventually forgets.
    private func handleNodeChannel(_ d: Data) {
        var index = 0
        var role: UInt64 = 0
        var name = ""
        var psk = Data()
        var sawSettings = false
        for f in parseProtoFields(d) {
            switch f.number {
            case 1 where f.wire == 0: index = Int(f.varintValue)
            case 3 where f.wire == 0: role = f.varintValue
            case 2 where f.wire == 2:
                sawSettings = true
                for sf in parseProtoFields(f.payload) {
                    if sf.number == 2, sf.wire == 2 { psk = sf.payload }
                    else if sf.number == 3, sf.wire == 2 {
                        name = String(data: sf.payload, encoding: .utf8) ?? ""
                    }
                }
            default: break
            }
        }
        // index defaults to 0 in protobuf, so an empty or unrelated frame would
        // otherwise materialise as a real PRIMARY channel named "" and overwrite
        // the slot the node actually reported.
        guard sawSettings || role != 0 || index != 0 else { return }
        guard index >= 0, index < LoRaChannelSet.maxChannels else { return }

        let channel = NodeChannel(index: index, name: name, psk: psk, roleRaw: role)
        var next = nodeChannels.filter { $0.index != index }
        if !channel.isDisabled { next.append(channel) }
        next.sort { $0.index < $1.index }
        // Compared by value (NodeChannel is Equatable), so an unchanged handshake
        // does not republish and re-render the list.
        if next != nodeChannels { nodeChannels = next }
    }

    /// Join everything in a shared `meshtastic.org/e/#` link: primary into slot 0,
    /// secondaries into 1..n, and every slot beyond that DISABLED.
    ///
    /// Disabling the tail is the part that is easy to leave out and wrong to:
    /// joining a two-channel link onto a node that held five would otherwise leave
    /// three stale channels behind, still receiving, with nothing in the UI to
    /// explain where they came from.
    ///
    /// The radio half of the link is applied ONLY when complete — see
    /// ``LoRaChannelSet/Radio``. A partial LoRaConfig arrives at the node as zeros
    /// and takes it off the air.
    @discardableResult
    func joinChannelSet(_ parsed: LoRaChannelSet.Parsed) -> Bool {
        guard isReady, myNodeNum != 0 else {
            lastError = "lora.error.not_connected".localized
            return false
        }
        let channels = Array(parsed.channels.prefix(LoRaChannelSet.maxChannels))
        guard !channels.isEmpty else { return false }

        for (index, channel) in channels.enumerated() {
            var settings = ProtoWriter()
            settings.bytes(2, channel.psk)
            settings.bytes(3, Data(LoRaChannel.clampName(channel.name).utf8))
            var ch = ProtoWriter()
            ch.varint(1, UInt64(index))
            ch.bytes(2, settings.data)
            ch.varint(3, index == 0 ? LoRaChannelSet.rolePrimary : LoRaChannelSet.roleSecondary)
            var admin = ProtoWriter()
            admin.bytes(33, ch.data)
            enqueuePacket(to: myNodeNum, portnum: Self.adminPortnum,
                          payload: admin.data, wantAck: true)
        }
        for index in channels.count..<LoRaChannelSet.maxChannels {
            var ch = ProtoWriter()
            ch.varint(1, UInt64(index))
            ch.bytes(2, Data())      // settings present but empty
            ch.varint(3, 0)          // Channel.role = DISABLED
            var admin = ProtoWriter()
            admin.bytes(33, ch.data)
            enqueuePacket(to: myNodeNum, portnum: Self.adminPortnum,
                          payload: admin.data, wantAck: true)
        }

        // Mirror the primary into the same key `setChannel` writes, so the setup
        // screen shows the channel just joined rather than the one typed last.
        let primary = LoRaChannel.clampName(channels[0].name)
        UserDefaults.standard.set(primary, forKey: Self.channelNameKey)
        channelName = primary

        if let r = parsed.radio, r.isComplete {
            setRegion(LoRaRegion(rawValue: r.regionRaw!) ?? .unset,
                      preset: LoRaModemPreset(rawValue: r.presetRaw!) ?? .longFast,
                      hopLimit: r.hopLimit!)
        }
        log("→", "joined \(channels.count) channel(s), primary \(LoRaChannel.displayName(primary))")
        OshiLog.mesh.info("📡 Meshtastic: joined \(channels.count) channel(s)")
        return true
    }

    /// A shareable link for what the NODE reports, falling back to the primary we
    /// last wrote when the node has not finished its handshake.
    ///
    /// Nil when there is nothing to share, so the UI can hide the row instead of
    /// offering a link to an empty mesh.
    ///
    /// The radio half is deliberately omitted: we read back only `region` from the
    /// node (in `handleConfig`), not `modem_preset` or `hop_limit`, so including a
    /// partial LoRaConfig would hand everyone who scanned it a radio configured to
    /// zeros. A channel-only link is correct and safe.
    func shareableChannelURL() -> String? {
        let fromNode = nodeChannels
            .sorted { $0.index < $1.index }
            .map { LoRaChannelSet.Channel(name: $0.name, psk: $0.psk) }
        let channels = fromNode.isEmpty
            ? [LoRaChannelSet.Channel(name: channelName, psk: LoRaChannel.defaultPSK)]
            : fromNode
        return LoRaChannelSet.buildURL(channels: channels)
    }

    /// Write the node's Bluetooth pairing mode.
    ///
    /// Takes effect on the node's next advertisement; a node already bonded to
    /// this phone stays bonded, so changing it does not drop the current link.
    @discardableResult
    func setBluetoothPairing(_ mode: BluetoothPairing) -> Bool {
        guard isReady, myNodeNum != 0 else {
            lastError = "lora.error.not_connected".localized
            return false
        }

        // Config.BluetoothConfig
        var bt = ProtoWriter()
        bt.varint(1, 1)                         // enabled = true
        bt.varint(2, mode.rawValue)             // mode

        var config = ProtoWriter()
        config.bytes(7, bt.data)                // Config.bluetooth

        var admin = ProtoWriter()
        // Field 34, NOT 33. AdminMessage.set_channel is 33 and carries a
        // Channel; set_config is 34 and carries a Config. Sending a Config
        // under 33 meant the node tried to parse it as a Channel and threw it
        // away — so "Set region" silently did nothing, on the one screen whose
        // entire purpose is to get a node out of region=UNSET. Verified against
        // the Meshtastic protobuf descriptors, not from memory.
        admin.bytes(34, config.data)            // AdminMessage.set_config

        enqueuePacket(to: myNodeNum, portnum: Self.adminPortnum,
                      payload: admin.data, wantAck: true)
        pairingMode = mode
        log("→", "set pairing \(mode.displayName)")
        OshiLog.mesh.info("📡 Meshtastic: bluetooth pairing -> \(mode.displayName)")
        return true
    }

    /// Returns the packet id it used, so a caller that asked for an ACK can
    /// match the ROUTING_APP reply back to the message it was carrying.
    @discardableResult
    /// - Parameter channel: the radio's channel SLOT to transmit on.
    ///   __CHANNEL_ROOMS_ALL_SENT_ON_SLOT_ZERO_2026_11_07__ `MeshPacket.channel`
    ///   is field 3 and this writer never emitted it, so every frame OSHI has
    ///   ever sent went out on slot 0. For OSHI's own envelopes that is correct
    ///   and unchanged (they ride the primary). For a LoRa channel ROOM it was
    ///   not: text typed into a private channel was broadcast on the primary,
    ///   which is usually LongFast and usually public.
    ///
    ///   Omitted from the wire when 0, because 0 is the proto default and a
    ///   stock Meshtastic encoder does not emit it either — so every frame that
    ///   worked before this change is byte-identical after it.
    private func enqueuePacket(to: UInt32, portnum: UInt64, payload: Data, wantAck: Bool,
                              messageId: String? = nil, channel: UInt32 = 0,
                              wantResponse: Bool = false) -> UInt32 {
        var data = ProtoWriter()
        data.varint(1, portnum)                            // Data.portnum
        data.bytes(2, payload)                             // Data.payload
        // Data.want_response — only for local requests (telemetry). Omitted
        // otherwise, so every other frame stays byte-identical.
        if wantResponse { data.varint(3, 1) }

        let packetId = UInt32.random(in: 1...UInt32.max)
        var pkt = ProtoWriter()
        pkt.fixed32(2, to)                                 // MeshPacket.to
        // MeshPacket.channel (slot index, when sent by a client). The spelling
        // `pkt.varint(3, channel)` is what LoRaChannelSlotParityTest greps for.
        if channel != 0 { let channel = UInt64(channel); pkt.varint(3, channel) }
        pkt.bytes(4, data.data)                            // MeshPacket.decoded
        pkt.fixed32(6, packetId)                           // MeshPacket.id
        if wantAck { pkt.varint(10, 1) }                   // MeshPacket.want_ack

        var toR = ProtoWriter()
        toR.bytes(1, pkt.data)                             // ToRadio.packet
        sendQueue.append((toR.data, messageId))
        pumpSendQueue()
        return packetId
    }

    /// A ROUTING_APP packet: the mesh telling us what became of something we
    /// sent with `want_ack`.
    ///
    /// Routing.error_reason is field 3. Absent or 0 (`NONE`) means delivered;
    /// anything else is a failure the radio is naming — no route, timeout, full
    /// queue. We only care about the distinction, not the specific code, because
    /// the answer to every failure here is the same: the peer did not get it.
    private func handleRouting(_ payload: Data, requestId: UInt32, from: UInt32) {
        var reason: UInt64 = 0
        for f in parseProtoFields(payload) where f.number == 3 && f.wire == 0 {
            reason = f.varintValue
        }
        guard requestId != 0,
              let pending = loraPendingAck.removeValue(forKey: requestId) else { return }
        if reason == 0 || Self.linkFailureReasons.contains(reason) {
            jam { $0.onAckResult(atMs: Self.nowMs(), success: reason == 0) }
        }

        if reason == 0 {
            loraAcked.insert(pending.id)
            log("←", "LoRa ACK for \(pending.id.prefix(8))")
            OshiLog.mesh.info("📡 LoRa: delivery confirmed for \(pending.id.prefix(8))")
            // __LORA_ACK_2026_10_10__ `loraAcked` had NO READER anywhere in the
            // target — its own doc comment says "read by the UI to show a LoRa
            // message as delivered", and nothing ever did. So even once an ACK
            // could arrive, it landed in a set nobody looked at and the bubble
            // still said "sent". Tell the one place that owns delivery state.
            NotificationCenter.default.post(name: .loRaDeliveryConfirmed, object: nil,
                                            userInfo: ["messageId": pending.id])
        } else {
            log("←", "LoRa delivery FAILED for \(pending.id.prefix(8)) (reason \(reason))")
            OshiLog.mesh.info("📡 LoRa: delivery failed for \(pending.id.prefix(8)), reason \(reason)")
            // A named failure from the radio — no route, timeout, full queue.
            // Worth surfacing: the alternative is a message that sits on screen
            // claiming "sent" forever when the mesh has already given up.
            NotificationCenter.default.post(name: .loRaDeliveryFailed, object: nil,
                                            userInfo: ["messageId": pending.id,
                                                       "reason": reason])
        }
    }

    /// Record that `messageId` is riding `packetId`, and start watching for a
    /// verdict. Prunes anything past the window on the way in.
    ///
    /// `unicast: false` means no ACK can ever arrive — see
    /// `__BROADCAST_SILENCE_IS_ALSO_A_FAILURE_2026_10_27__` at the call site
    /// for why that is the case most worth watching rather than the one to skip.
    private func trackPendingAck(packetId: UInt32, messageId: String, unicast: Bool) {
        sweepExpiredAcks()
        loraPendingAck[packetId] = PendingRadioSend(id: messageId, at: Date(), unicast: unicast)
        armAckTimer()
    }

    /// __LORA_SILENCE_IS_A_FAILURE_2026_10_27__ Expire the ACK window and SAY SO.
    ///
    /// THE BUG. `loraPendingAck` was pruned in exactly one place — on the way
    /// into `trackPendingAck` — and the pruned entries were dropped on the
    /// floor. So the only unanswered send that ever resolved was one that
    /// happened to be followed by ANOTHER LoRa send within 90 s, and even then
    /// it resolved silently.
    ///
    /// That left the one outcome that matters most off-grid with no handler at
    /// all: the peer is simply not in range. A radio that cannot route replies
    /// `ROUTING_APP` with a reason and `handleRouting` catches it, but a radio
    /// that hears nothing back replies NOTHING. The message sat on `.sent`
    /// forever — "delivered to the radio" rendered as "delivered".
    ///
    /// Expiry is now an EVENT, reported exactly like a named failure, which is
    /// what lets `MessageManager` escalate to the internet instead of leaving
    /// the message parked on a radio nobody answered.
    private func sweepExpiredAcks() {
        let expired = Self.expiredPacketIds(loraPendingAck)
        guard !expired.isEmpty else { return }
        for packetId in expired {
            guard let pending = loraPendingAck.removeValue(forKey: packetId) else { continue }
            log("←", "LoRa window expired for \(pending.id.prefix(8))")
            // __ANTI_JAMMING_PHASE1_2026_11_13__ A unicast that got NO verdict at
            // all (the radio normally answers MAX_RETRANSMIT well before) counts
            // against the link. A broadcast never gets one, so it says nothing.
            if pending.unicast { jam { $0.onAckResult(atMs: Self.nowMs(), success: false) } }
            if pending.unicast {
                OshiLog.mesh.info("📡 LoRa: no ACK within \(Int(Self.loraAckWindow))s for \(pending.id.prefix(8)) — nobody answered")
            } else {
                // Say which silence this is. A broadcast was never going to be
                // acknowledged, so "no ACK" would read as a fault when it is
                // the protocol working as designed — what it means is that no
                // peer has confirmed at the application layer either.
                OshiLog.mesh.info("📡 LoRa: broadcast for \(pending.id.prefix(8)) went unconfirmed for \(Int(Self.loraAckWindow))s — no binding for this recipient, so it could never be acked")
            }
            // `reason: 0` is RIGHT here and is not the same thing as the ACK's
            // `reason == 0`: there is no radio verdict at all. `timedOut` is
            // what distinguishes "the mesh said no route" from "silence".
            NotificationCenter.default.post(name: .loRaDeliveryFailed, object: nil,
                                            userInfo: ["messageId": pending.id,
                                                       "reason": UInt64(0),
                                                       "timedOut": true])
        }
    }

    /// Keep a timer alive only while something is actually waiting. It checks
    /// every 5 s — the window is 90 s, so that is fifteen times finer than the
    /// resolution anyone can perceive and costs nothing — and invalidates itself
    /// the moment the map empties, because a repeating timer on a manager that
    /// lives for the whole session is a battery bug if it never stops.
    private func armAckTimer() {
        guard ackTimer == nil else { return }
        let t = Timer(timeInterval: 5.0, repeats: true) { [weak self] t in
            guard let self else { t.invalidate(); return }
            self.sweepExpiredAcks()
            if self.loraPendingAck.isEmpty { t.invalidate(); self.ackTimer = nil }
        }
        ackTimer = t
        RunLoop.main.add(t, forMode: .common)
    }

    /// Space writes ~2 s apart — matches LongFast airtime (~1-2 s/frame) so the
    /// radio's shallow TX queue never overflows on multi-chunk messages.
    private func pumpSendQueue() {
        guard sendTimer == nil else { return }
        sendTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] t in
            guard let self else { t.invalidate(); return }
            guard !self.sendQueue.isEmpty else { t.invalidate(); self.sendTimer = nil; return }
            let item = self.sendQueue.removeFirst()
            self.write(item.frame)
            if let id = item.messageId { self.advanceLoRaProgress(id) }
        }
        sendTimer?.fire()
    }

    /// One tagged media frame just left the paced queue: bump the message's
    /// send progress so the media bubble's existing bar advances in step with
    /// airtime. Cleared when the last frame goes out.
    private func advanceLoRaProgress(_ id: String) {
        guard let total = loRaFramesTotal[id], total > 0 else { return }
        let sent = (loRaFramesSent[id] ?? 0) + 1
        loRaFramesSent[id] = sent
        let fraction = min(1.0, Double(sent) / Double(total))
        DispatchQueue.main.async { MessageManager.shared?.uploadProgress[id] = fraction }
        if sent >= total {
            loRaFramesTotal[id] = nil
            loRaFramesSent[id] = nil
        }
    }

    private func write(_ d: Data) {
        guard let p = peripheral, let tr = toRadio else { return }
        p.writeValue(d, for: tr, type: .withResponse)
    }

    // MARK: Receive

    private func handleFromRadio(_ d: Data) {
        for f in parseProtoFields(d) {
            switch f.number {
            case 2 where f.wire == 2: handleMeshPacket(f.payload)
            case 3 where f.wire == 2:                       // MyNodeInfo
                for mf in parseProtoFields(f.payload) where mf.number == 1 && mf.wire == 0 {
                    myNodeNum = UInt32(truncatingIfNeeded: mf.varintValue)
                }
            case 4 where f.wire == 2: handleNodeInfo(f.payload)
            case 5 where f.wire == 2: handleConfig(f.payload)
            // __NODE_CHANNELS_WERE_DROPPED_2026_11_04__ FromRadio.channel = 10.
            // The node streams all EIGHT of its channel slots during this very
            // handshake, unasked, right alongside my_info/node_info/config. There
            // was no case for field 10, so every one of them was dropped and the
            // app could write the primary channel but never read back a single
            // slot — which is why "which mesh is my node on?" had no answer and
            // secondary channels were invisible. No AdminMessage round trip is
            // needed to list channels; it was already arriving.
            //
            // Both handlers read the same frame: `handleNodeChannel` keeps every
            // slot for the UI and channel rooms, `handleChannel` records the
            // radio's channel table and the #oshi-bridge slot index for OMP.
            case 10 where f.wire == 2:
                handleNodeChannel(f.payload)
                handleChannel(f.payload)
            case 7 where f.wire == 0:                       // config_complete_id
                if UInt32(truncatingIfNeeded: f.varintValue) == wantConfigId {
                    state = .ready
                    // The handshake completed: NOW this radio is genuinely paired.
                    if let id = pendingPairId {
                        UserDefaults.standard.set(id, forKey: Self.pairedKey)
                        pendingPairId = nil
                    }
                    OshiLog.mesh.info("📡 Meshtastic: READY — node !\(String(format: "%08x", self.myNodeNum)), \(self.nodes.count) node(s) known")
                    renameIfFactoryDefault()  // "Meshtastic 23d8" -> "OSHI Mesh 23D8"
                    startJammingWatch()       // fresh baseline for this radio and this link
                    startOshiMeshSetup()      // OSHI Mesh firmware? else give a stock radio the OSHI channel
                    // __LORA_PROBE_BEFORE_FLUSH_2026_11_18__ NOT flushed here any more: the probe has not
                    // been answered, so everything held would take the 12-frame stock path. The probe
                    // answer (handleOmp .beacon) or its 6 s timeout (ensureOshiChannel) flushes instead.
                }
            default: break
            }
        }
    }

    /// FromRadio.config → Config.lora → the region the node is actually using.
    ///
    /// Parsed so the UI can tell the difference between "this node is set up" and
    /// "this node is connected but will never transmit". Without reading it back
    /// we would be trusting that our write landed, and the one state that must
    /// never be assumed is the one that silently drops every message.
    private func handleConfig(_ d: Data) {
        for f in parseProtoFields(d) where f.number == 6 && f.wire == 2 {   // Config.lora
            var found: LoRaRegion?
            var txOn = true
            for lf in parseProtoFields(f.payload) {
                switch lf.number {
                case 7 where lf.wire == 0: found = LoRaRegion(rawValue: lf.varintValue)
                case 9 where lf.wire == 0: txOn = lf.varintValue != 0
                default: break
                }
            }
            // A LoRaConfig with no region field at all means the default, UNSET —
            // protobuf omits zero values, so "absent" and "unset" are the same
            // thing here and both mean "cannot transmit".
            region = found ?? .unset
            transmitEnabled = txOn
            OshiLog.mesh.info("📡 Meshtastic: region=\((self.region ?? .unset).displayName) tx=\(txOn)")
        }

        for f in parseProtoFields(d) where f.number == 7 && f.wire == 2 {   // Config.bluetooth
            // Same protobuf rule as region: mode 0 (RANDOM_PIN) is the default
            // and is therefore omitted from the wire, so absent means randomPin.
            var mode: BluetoothPairing = .randomPin
            for bf in parseProtoFields(f.payload) where bf.number == 2 && bf.wire == 0 {
                mode = BluetoothPairing(rawValue: bf.varintValue) ?? .randomPin
            }
            pairingMode = mode
            OshiLog.mesh.info("📡 Meshtastic: pairing=\(mode.displayName)")
        }
    }

    private func handleNodeInfo(_ d: Data) {
        var num: UInt32 = 0; var uid = ""; var lname = ""; var sname = ""; var snr: Float = 0
        var lat: Double?; var lon: Double?; var hops: Int?; var reportedHeard: Date?
        for f in parseProtoFields(d) {
            switch (f.number, f.wire) {
            case (1, 0): num = UInt32(truncatingIfNeeded: f.varintValue)
            case (2, 2):                                    // User
                for uf in parseProtoFields(f.payload) {
                    switch (uf.number, uf.wire) {
                    case (1, 2): uid = String(data: uf.payload, encoding: .utf8) ?? ""
                    case (2, 2): lname = String(data: uf.payload, encoding: .utf8) ?? ""
                    case (3, 2): sname = String(data: uf.payload, encoding: .utf8) ?? ""
                    default: break
                    }
                }
            case (3, 2):                                    // Position
                let p = Self.parsePosition(f.payload)
                lat = p.lat; lon = p.lon
            case (4, 5): snr = Float(bitPattern: UInt32(truncatingIfNeeded: f.varintValue))
            // last_heard (fixed32, Unix seconds, radio clock). A radio that never
            // got the time reports 0 or a 1970 value — ignored, not trusted.
            case (5, 5):
                let secs = TimeInterval(UInt32(truncatingIfNeeded: f.varintValue))
                if secs > 1_600_000_000 { reportedHeard = Date(timeIntervalSince1970: secs) }
            case (9, 0): hops = Int(f.varintValue)
            default: break
            }
        }
        guard num != 0 else { return }

        // Merge, never replace. A NodeInfo that carries no Position must not
        // erase a position we already learned — most updates omit it.
        var node = nodes.first(where: { $0.num == num })
            ?? MeshtasticNode(num: num, userId: uid, longName: lname, shortName: sname,
                              lastHeard: Date(), snr: snr, latitude: nil, longitude: nil,
                              hopsAway: nil, rssi: nil)
        if !uid.isEmpty { node.userId = uid }
        if !lname.isEmpty { node.longName = lname }
        if !sname.isEmpty { node.shortName = sname }
        // __LORA_ONLINE_COUNT_2026_09_22__ The node DB is dumped IN FULL at every
        // connect, so stamping `Date()` here made every node the radio ever
        // stored look "heard just now" — a node from last month counted as
        // online. Use the radio's own last_heard when it has one.
        if let reportedHeard {
            let reported = min(Date(), reportedHeard)
            // Never move a node we already heard directly back in time.
            let known = nodes.contains { $0.num == num }
            node.lastHeard = known ? max(node.lastHeard, reported) : reported
        } else {
            node.lastHeard = Date()
        }
        if snr != 0 { node.snr = snr }
        if let lat, let lon, !(lat == 0 && lon == 0) { node.latitude = lat; node.longitude = lon }
        if let hops { node.hopsAway = hops }

        if let idx = nodes.firstIndex(where: { $0.num == num }) { nodes[idx] = node } else { nodes.append(node) }
    }

    /// Read a Meshtastic `Position` into degrees.
    ///
    /// ONE reader, used by both the node-database dump and the live
    /// POSITION_APP packets, because two copies of this would drift and the bug
    /// below is what drift costs:
    ///
    /// `latitude_i` / `longitude_i` are **sfixed32** scaled by 1e-7, so they
    /// must be read as SIGNED. Read unsigned and everything south of the
    /// equator or west of Greenwich lands near the north pole.
    /// `static` and non-private so the signed-coordinate rule can be tested
    /// without a radio, a BLE stack or a singleton: `parseProtoFields` is a
    /// free function, so this needs no instance at all.
    static func parsePosition(_ d: Data) -> (lat: Double?, lon: Double?) {
        var lat: Double?; var lon: Double?
        for pf in parseProtoFields(d) {
            switch (pf.number, pf.wire) {
            case (1, 5): lat = Double(Int32(bitPattern: UInt32(truncatingIfNeeded: pf.varintValue))) * 1e-7
            case (2, 5): lon = Double(Int32(bitPattern: UInt32(truncatingIfNeeded: pf.varintValue))) * 1e-7
            default: break
            }
        }
        return (lat, lon)
    }

    /// __LORA_POSITION_LIVE_2026_10_27__ A node just said where it is.
    ///
    /// Merged into the node the same way `handleNodeInfo` merges, and for the
    /// same reason: a 0/0 position is what a node with no fix reports, and
    /// writing it would move a node that HAS a fix into the Gulf of Guinea.
    ///
    /// `from` is the ORIGINAL sender in mesh.proto and survives relaying, so
    /// this is the node that owns the GPS, not the last hop that forwarded it.
    ///
    /// A position from a node we have never seen in the node database still
    /// creates an entry: on a fresh radio the position can genuinely arrive
    /// before the NodeInfo, and dropping it would leave the node invisible
    /// until the next full dump. The name fields stay empty until NodeInfo
    /// fills them, which the merge above does without erasing this position.
    private func handlePosition(_ payload: Data, from: UInt32) {
        guard from != 0 else { return }
        let p = Self.parsePosition(payload)
        guard let lat = p.lat, let lon = p.lon, !(lat == 0 && lon == 0) else { return }
        // Plausibility: a corrupt frame must not put a node off the planet.
        guard lat >= -90, lat <= 90, lon >= -180, lon <= 180 else { return }

        if let idx = nodes.firstIndex(where: { $0.num == from }) {
            nodes[idx].latitude = lat
            nodes[idx].longitude = lon
            nodes[idx].lastHeard = max(nodes[idx].lastHeard, Date())
        } else {
            nodes.append(MeshtasticNode(num: from, userId: "", longName: "", shortName: "",
                                        lastHeard: Date(), snr: 0,
                                        latitude: lat, longitude: lon,
                                        hopsAway: nil, rssi: nil))
        }
        log("←", String(format: "position %08x %.5f, %.5f", from, lat, lon))
        OshiLog.mesh.info("📍 LoRa position from \(String(format: "%08x", from))")
    }

    private func handleMeshPacket(_ d: Data) {
        var from: UInt32 = 0; var decoded: Data?
        var rssi: Int?; var snr: Float?
        // __LORA_CHANNEL_IS_A_ROOM_2026_11_04__ `MeshPacket.to` (2, fixed32) and
        // `MeshPacket.channel` (3, varint). Both were being skipped, and without
        // them this file could not tell a public broadcast from someone writing
        // to this node alone — so every packet was filed as a private message.
        var to: UInt32 = 0
        var channelIndex = 0
        for f in parseProtoFields(d) {
            switch (f.number, f.wire) {
            case (1, 5): from = UInt32(truncatingIfNeeded: f.varintValue)
            case (2, 5): to = UInt32(truncatingIfNeeded: f.varintValue)
            case (3, 0): channelIndex = Int(f.varintValue)
            case (4, 2): decoded = f.payload
            case (8, 5): snr = Float(bitPattern: UInt32(truncatingIfNeeded: f.varintValue))
            case (12, 0): rssi = Int(Int32(truncatingIfNeeded: f.varintValue))  // rx_rssi
            default: break
            }
        }
        guard from != 0, let dec = decoded else { return }
        // Our own radio speaks OSHI Mesh to us under its own node number: the probe answer, delivery
        // statuses, and mail it pulled as a gateway. Any other packet "from" ourselves is an echo.
        if from == myNodeNum {
            if let omp = Self.ompPayload(dec) { handleOmp(omp, from: from); return }
            // __ANTI_JAMMING_PHASE1_2026_11_13__ Our radio's own telemetry, and
            // the routing verdicts it generates itself (MAX_RETRANSMIT, or the
            // implicit ack from overhearing a relay) — both were dropped here as
            // "echoes" before, which hid every failed unicast.
            let (port, body, reqId) = Self.decodedData(dec)
            if port == Self.telemetryPortnum { handleOwnTelemetry(body) }
            if port == Self.routingPortnum { handleOwnRouting(body, requestId: reqId) }
            return
        }

        // Another node's packet reached us decoded: the channel carries traffic.
        // OMP from an OSHI Mesh radio may be a REPLAY from its flash inbox, not
        // something heard now, so it does not count.
        if !(firmwareFamily == .oshi && Self.ompPayload(dec) != nil) {
            jam { $0.onValidPacket(atMs: Self.nowMs()) }
        }

        // Signal is measured per PACKET, not per NodeInfo — this is the only
        // place it is available, and it is what lets us estimate a distance for
        // the many nodes that have no GPS.
        if let idx = nodes.firstIndex(where: { $0.num == from }) {
            if let rssi { nodes[idx].rssi = rssi }
            if let snr, snr != 0 { nodes[idx].snr = snr }
            nodes[idx].lastHeard = Date()
        }
        // `request_id` is field 6 of the Data SUBMESSAGE (mesh.proto), not of
        // MeshPacket — it is what ties a ROUTING_APP reply back to the packet we
        // sent. Read here because this is the only place Data is parsed.
        var requestId: UInt32 = 0
        var portnum: UInt64 = 0; var payload = Data()
        for f in parseProtoFields(dec) {
            switch (f.number, f.wire) {
            case (1, 0): portnum = f.varintValue
            case (2, 2): payload = f.payload
            // Emitted as a varint by some firmware builds and fixed32 by others,
            // so accept both rather than silently missing every ACK on one of
            // them.
            case (6, 0): requestId = UInt32(truncatingIfNeeded: f.varintValue)
            case (6, 5): requestId = UInt32(truncatingIfNeeded: f.varintValue)
            default: break
            }
        }
        switch portnum {
        case Self.textPortnum:
            handleInteropText(payload, from: from,
                              isBroadcast: to == Self.broadcastAddr,
                              channelIndex: channelIndex)
        case Self.oshiPortnum:
            // "OS" = OSHI Mesh firmware (OMP); anything else is the older "OM" chunking.
            if payload.count >= 3, payload[payload.startIndex] == OMP.magic0, payload[payload.startIndex + 1] == OMP.magic1 {
                handleOmp(payload, from: from)
            } else {
                handleOshiChunk(payload, from: from)
            }
        case Self.routingPortnum: handleRouting(payload, requestId: requestId, from: from)
        case Self.positionPortnum: handlePosition(payload, from: from)
        default: break
        }
    }

    /// Plain text from ANY Meshtastic client (official app included).
    ///
    /// A raw TEXT_MESSAGE_APP packet carries NO identity — a node number and a
    /// string, nothing else. So there are exactly two possible outcomes, and the
    /// choice between them is made by `LoRaNodeIdentityMap`, never guessed here:
    ///
    ///  • The node has previously proved an OSHI identity (it sent us an OSHI
    ///    envelope that DECRYPTED) → the text lands in that contact's
    ///    conversation, which is what the user expects when their friend
    ///    switches to the stock Meshtastic app on the same radio.
    ///  • It has proved nothing, or has proved two different identities → the
    ///    original "lora!<nodehex>" conversation, exactly as before. Existing
    ///    "lora!" conversations therefore keep receiving without interruption.
    private func handleInteropText(_ payload: Data, from: UInt32,
                                   isBroadcast: Bool, channelIndex: Int) {
        // 🔧 DIAG (2026-08-11): "a LoRa message between my two OSHI phones made a
        // separate lora! conversation" is unanswerable from a log today, because
        // both inbound paths are silent about which one ran. Two OSHI phones
        // should NEVER reach here — OSHI-to-OSHI rides portnum 256 chunks that
        // carry a real sender key. Seeing this line for a peer who has OSHI means
        // the envelope path failed and the radio fell back to plain text, which
        // is a different bug from the routing rule.
        CallFileLogger.shared.log("DIAG_LORA_ROUTE | path=interop_raw_text | node=\(String(format: "%08x", from)) | adopt=\(LoRaNodeIdentityMap.adoptRawTextIntoContactThread)")
        guard let text = String(data: payload, encoding: .utf8), !text.isEmpty else { return }
        let fallbackKey = LoRaNodeIdentityMap.fallbackConversationKey(from)
        let boundKey = LoRaNodeIdentityMap.shared.publicKey(forNode: from)
        let senderKey = LoRaNodeIdentityMap.shared.conversationKey(forNode: from)
        let display: String = {
            // The name must follow the THREAD, not the radio: the contact's
            // alias is what the rest of the app shows for this conversation, and
            // two different names for one thread reads as two people.
            //
            // __LORA_MANUAL_LINK_2026_10_28__ The identity read here is the one
            // `conversationKey` actually CHOSE, not `publicKey(forNode:)`. The
            // two differ in both directions now: a manual link routes to a
            // contact that was never proved (this named the radio instead), and
            // a proved binding with the adopt switch off routes to the fallback
            // thread (this named the contact on a thread that is not theirs).
            if senderKey != fallbackKey { return MessageManager.resolveContactAlias(senderKey) }
            return nodes.first(where: { $0.num == from }).map {
                $0.longName.isEmpty ? ($0.shortName.isEmpty ? fallbackKey : $0.shortName) : $0.longName
            } ?? fallbackKey
        }()
        log("←", "text from \(display)\(boundKey != nil ? " (known node)" : ""): \(text.prefix(32))")
        let myKey = MessageManager.sharedIdentityManager?.publicKey ?? ""
        // __LORA_CHANNEL_IS_A_ROOM_2026_11_04__ A broadcast belongs to the
        // CHANNEL, not to a private thread with whoever happened to speak.
        //
        // Everyone in radio range on the same channel hears everyone else, so
        // three people talking on LongFast used to produce three separate
        // one-to-one threads that each looked like a direct message, and the
        // channel itself appeared nowhere. Addressing the row to the channel
        // puts it in one room while `senderPublicKey` keeps the real author, so
        // every speaker stays individually identifiable.
        //
        // A DIRECT packet stays a private thread: it was addressed to this node
        // alone and nobody else heard it.
        let channelKey: String? = isBroadcast
            ? LoRaChannelThread.key(
                channelIndex: channelIndex,
                channelName: LoRaChannelThread.name(forIndex: channelIndex, in: nodes_channelsSnapshot())
              )
            : nil
        let msg = SecureMessage(
            id: UUID().uuidString,
            senderAddress: display,
            recipientAddress: String(myKey.prefix(10)),
            encryptedContent: EncryptedMessage(
                ciphertext: payload.base64EncodedString(),
                signature: "lora_interop",
                senderPublicKey: senderKey,
                timestamp: Date()
            ),
            timestamp: Date(),
            isRead: false,
            deliveryStatus: .delivered,
            senderPublicKey: senderKey,
            recipientPublicKey: channelKey ?? myKey,
            plaintextContent: text,
            // __LORA_PLAINTEXT_IS_NOT_THE_SAME_AS_LORA_2026_11_04__ Raw
            // Meshtastic text: plaintext, public-key channel, forgeable
            // `from`. `.lora` is reserved for an OSHI envelope the ratchet
            // authenticated; drawing this one the same way would make an
            // unverified packet look proved.
            deliveryMethod: .loraPlaintext
        )
        NotificationCenter.default.post(name: NSNotification.Name("MeshMessageReceived"),
                                        object: nil, userInfo: ["message": msg])
    }

    /// OSHI envelope chunk ("OM" frames) → reassemble → SecureMessage → the
    /// exact same pipeline as every other mesh message. If the envelope isn't
    /// addressed to us (recipient key mismatch → decryption fails upstream),
    /// the message layer drops it — E2E addressing for free.
    private func handleOshiChunk(_ payload: Data, from: UInt32) {
        guard payload.count > 8, payload[payload.startIndex] == 0x4F, payload[payload.startIndex + 1] == 0x4D else { return }
        let body = payload.dropFirst(2)
        let msgId = body.prefix(4).withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) }.littleEndian
        let seq = Int(body[body.startIndex + 4]); let total = Int(body[body.startIndex + 5])
        let chunk = body.dropFirst(6)
        guard total > 0, seq < total, total <= 40 else { return }

        var entry = reassembly[msgId] ?? (total: total, parts: [:], started: Date())
        entry.parts[seq] = Data(chunk)
        reassembly[msgId] = entry
        // GC stale partials (>3 min)
        reassembly = reassembly.filter { Date().timeIntervalSince($0.value.started) < 180 }

        guard entry.parts.count == entry.total else { return }
        reassembly.removeValue(forKey: msgId)
        var full = Data()
        for i in 0..<entry.total { guard let part = entry.parts[i] else { return }; full.append(part) }
        deliverEnvelope(full, from: from, frames: entry.total)
    }

    /// A reassembled OSHI envelope, from either framing, into the normal mesh pipeline.
    private func deliverEnvelope(_ full: Data, from: UInt32, frames: Int) {
        guard var msg = try? JSONDecoder().decode(SecureMessage.self, from: full) else {
            OshiLog.mesh.info("📡 Meshtastic: OSHI envelope reassembled but not decodable (\(full.count)B)")
            return
        }
        msg = msg.meshInboundSafe()   // __MESH_INBOUND_PLAINTEXT_2026_11_20__ unauthenticated radio frame
        // These bytes came off our own radio, so we KNOW the transport — the
        // sender's stripped field cannot tell us and must not be trusted to.
        // Stamped before the post so `receiveMessage`'s "nil or .pending means
        // mesh" default can't relabel a LoRa message as Bluetooth mesh.
        msg.deliveryMethod = .lora
        CallFileLogger.shared.log("DIAG_LORA_ROUTE | path=oshi_envelope | node=\(String(format: "%08x", from)) | sender=\(msg.senderPublicKey.prefix(12)) | bytes=\(full.count)")
        // Not for us? Drop silently (broadcast fans out to everyone by design).
        // Normalized comparison — base64url/padding skew must not drop a
        // legitimate recipient.
        if let myKey = MessageManager.sharedIdentityManager?.publicKey, !myKey.isEmpty,
           Self.normalizeKey(msg.recipientPublicKey) != Self.normalizeKey(myKey) {
            return
        }
        log("←", "OSHI msg \(msg.id.prefix(8)) via LoRa (\(frames) frames)")
        // The originating node number rides along so `MessageManager` can bind
        // node → identity ONCE THE ENVELOPE HAS DECRYPTED. It is deliberately
        // not bound here: at this point `msg.senderPublicKey` is only a CLAIM,
        // and a claim is exactly what an attacker would forge to have their raw
        // text filed under a contact's name. `from` is the ORIGINAL sender in
        // Meshtastic's mesh.proto — it survives relaying, so it identifies the
        // node that composed the packet, not the last hop.
        NotificationCenter.default.post(name: NSNotification.Name("MeshMessageReceived"),
                                        object: nil,
                                        userInfo: ["message": msg,
                                                   "loraNodeNum": NSNumber(value: from)])
    }

    /// The radio's channel table, for naming the room a packet arrived on.
    ///
    /// A function rather than reading ``nodeChannels`` inline so the access is
    /// in one place: this is read from the Bluetooth delegate queue, and
    /// `@Published` properties are written on the main queue.
    private func nodes_channelsSnapshot() -> [NodeChannel] { nodeChannels }

    private func log(_ dir: String, _ s: String) {
        DispatchQueue.main.async {
            self.traffic.insert(LoRaEvent(direction: dir, summary: s), at: 0)
            if self.traffic.count > 60 { self.traffic.removeLast() }
        }
    }
}

// MARK: - OSHI Mesh firmware (OshiMeshRadio.swift)

extension MeshtasticManager {

    /// FromRadio.channel: Channel{index=1, settings=2{psk=2, name=3}, role=3}. Role absent = DISABLED.
    fileprivate func handleChannel(_ d: Data) {
        var index = 0, role: UInt64 = 0, name = ""
        for f in parseProtoFields(d) {
            switch (f.number, f.wire) {
            case (1, 0): index = Int(f.varintValue)
            case (3, 0): role = f.varintValue
            case (2, 2):
                for sf in parseProtoFields(f.payload) where sf.number == 3 && sf.wire == 2 {
                    name = String(data: sf.payload, encoding: .utf8) ?? ""
                }
            default: break
            }
        }
        channelSlots[index] = (role, name)
        if role != 0 && name == OMP.oshiChannelName { oshiChannelIndex = UInt32(index) }
    }

    /// Probe for OSHI Mesh firmware; a radio that stays silent is stock and gets the OSHI channel instead.
    fileprivate func startOshiMeshSetup() {
        firmwareFamily = .meshtastic
        firmwareProbeDone = false
        enqueuePacket(to: myNodeNum, portnum: Self.oshiPortnum, payload: OMP.beaconProbe(), wantAck: false)
        DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak self] in self?.ensureOshiChannel() }
    }

    /// Stock firmware: OSHI frames on the default channel die at every Meshtastic ROUTER
    /// (`CORE_PORTNUMS_ONLY` drops PRIVATE_APP it can decode). On a private secondary channel the same
    /// ROUTERs relay them untouched. Same frequency: the slot follows the PRIMARY channel's name.
    fileprivate func ensureOshiChannel() {
        firmwareProbeDone = true
        // __LORA_PROBE_BEFORE_FLUSH_2026_11_18__ The probe had its chance: a radio that did not answer is
        // stock (forget any OSHI Mesh memory of it), and what was held goes out now — after the OSHI
        // channel slot below is set, so stock-path frames use it.
        defer {
            if isReady && firmwareFamily == .meshtastic { rememberOshiFirmware(false) }
            flushStoreAndForward()
        }
        guard firmwareFamily == .meshtastic, isReady, myNodeNum != 0 else { return }
        if let idx = channelSlots.first(where: { $0.value.role != 0 && $0.value.name == OMP.oshiChannelName })?.key {
            oshiChannelIndex = UInt32(idx)
            return
        }
        guard let free = channelSlots.filter({ $0.key > 0 && $0.value.role == 0 }).keys.min() else {
            log("!", "no free channel slot for OSHI; LoRa stays on the primary channel")
            return
        }
        var settings = ProtoWriter()
        settings.bytes(2, OMP.oshiChannelPSK)                  // ChannelSettings.psk
        settings.bytes(3, Data(OMP.oshiChannelName.utf8))      // ChannelSettings.name
        var channel = ProtoWriter()
        channel.varint(1, UInt64(free))                         // Channel.index
        channel.bytes(2, settings.data)                         // Channel.settings
        channel.varint(3, 2)                                    // Channel.role = SECONDARY
        var admin = ProtoWriter()
        admin.bytes(33, channel.data)                           // AdminMessage.set_channel
        enqueuePacket(to: myNodeNum, portnum: Self.adminPortnum, payload: admin.data, wantAck: true)
        channelSlots[free] = (2, OMP.oshiChannelName)
        oshiChannelIndex = UInt32(free)
        log("→", "added the OSHI channel at slot \(free)")
    }

    /// OSHI Mesh firmware takes the whole message and owns delivery: repair, custody, receipts.
    fileprivate func sendViaOMP(_ json: Data, message: SecureMessage, to node: UInt32) -> Bool {
        let body = OMP.encodeBody(json)
        let msgId = UInt32.random(in: 1...UInt32.max)
        // Custody only makes sense for a named destination; a broadcast has no one to wait for.
        let flags: UInt8 = node == Self.broadcastAddr ? 0 : OMP.flagCustodyOK
        guard let frames = OMP.dataFrames(msgId: msgId, dest: node, body: body, flags: flags) else {
            refuse(.tooLarge(bytes: body.count))
            return false
        }
        if ompOutgoing.count > 256 { ompOutgoing.removeAll() }
        ompOutgoing[msgId] = message.id
        for f in frames {
            enqueuePacket(to: myNodeNum, portnum: Self.oshiPortnum, payload: f, wantAck: false)
        }
        log("→", "OSHI msg \(message.id.prefix(8)) to OSHI Mesh radio (\(frames.count) frame\(frames.count > 1 ? "s" : ""), \(body.count)B)")
        clearRefusal()
        return true
    }

    /// Repeat one OMP DATA frame heard on another network (MeshCore) onto this mesh, for LoRaCrossBridge.
    /// Stock radio: the frame goes out as is, origin and all, on the OSHI channel. OSHI Mesh radio: it is
    /// handed over like the phone's own frames; the firmware reassembles the message and sends it under its
    /// own node number (the msgId survives, which is what receivers and the bridge dedupe on).
    func relayOMPFrame(_ frame: Data) {
        guard isReady, transmitEnabled, !excludesLoRaForJamming else { return }
        if firmwareFamily == .oshi {
            enqueuePacket(to: myNodeNum, portnum: Self.oshiPortnum, payload: frame, wantAck: false)
        } else {
            enqueuePacket(to: Self.broadcastAddr, portnum: Self.oshiPortnum, payload: frame, wantAck: false,
                          channel: oshiChannelIndex ?? 0)
        }
    }

    /// The payload of a decoded Data when it is an OMP frame on PRIVATE_APP.
    fileprivate static func ompPayload(_ decoded: Data) -> Data? {
        var portnum: UInt64 = 0, payload = Data()
        for f in parseProtoFields(decoded) {
            if f.number == 1 && f.wire == 0 { portnum = f.varintValue }
            if f.number == 2 && f.wire == 2 { payload = f.payload }
        }
        guard portnum == oshiPortnum, OMP.type(of: payload) != nil else { return nil }
        return payload
    }

    fileprivate func handleOmp(_ payload: Data, from: UInt32) {
        switch OMP.type(of: payload) {
        case .beacon?:
            // Only our own radio's answer to the probe reaches us; peers' beacons stay in the firmware.
            guard from == myNodeNum, OMP.parseBeacon(payload) != nil else { return }
            if firmwareFamily != .oshi {
                firmwareFamily = .oshi
                rememberOshiFirmware(true)
                log("←", "radio runs OSHI Mesh firmware")
                OshiLog.mesh.info("📡 Meshtastic: OSHI Mesh firmware detected")
                // __LORA_PROBE_BEFORE_FLUSH_2026_11_18__ Held messages take the OMP path now.
                flushStoreAndForward()
            }
        case .status?:
            guard let st = OMP.parseStatus(payload), let id = ompOutgoing[st.msgId] else { return }
            // __LORA_AIRTIME_HINT_2026_11_18__ QUEUED starts the clock; any later state stops it.
            if st.state == .queued {
                LoRaAirtimeStatus.shared.onQueued(id)
            } else {
                LoRaAirtimeStatus.shared.onProgress(id)
            }
            switch st.state {
            case .delivered:
                loraInCustody.remove(id)
                loraAcked.insert(id)
                ompOutgoing[st.msgId] = nil
                log("←", "OSHI msg \(id.prefix(8)) delivered by !\(String(format: "%08x", st.node))")
            case .inCustody:
                loraInCustody.insert(id)
                log("←", "OSHI msg \(id.prefix(8)) held by !\(String(format: "%08x", st.node)) until the destination is back")
            case .failed, .rejected:
                loraInCustody.remove(id)
                ompOutgoing[st.msgId] = nil
                log("←", "OSHI msg \(id.prefix(8)) \(st.state == .failed ? "expired undelivered" : "refused by the radio")")
            case .queued, .sent, .uplinked:
                break
            }
        case .radio?:
            // __ANTI_JAMMING_PHASE1_2026_11_13__ Our own radio's channel report, every 30 s.
            guard from == myNodeNum, let r = OMP.parseRadio(payload) else { return }
            lastCounterStatsAt = Date()
            let sample = LoRaJammingDetector.Sample(
                atMs: Self.nowMs(),
                channelUtilPct: Float(r.channelUtilPct), airUtilTxPct: Float(r.airUtilTxPct),
                noiseFloorDbm: r.noiseFloorDbm,
                rxTotal: Int64(r.rxGood) + Int64(r.rxBad), rxBad: Int64(r.rxBad))
            jam { $0.onStats(sample) }
        case .data?:
            guard let f = OMP.parseData(payload) else { return }
            // Holding a MeshCore radio too? Every OSHI frame heard here is repeated there (LoRaCrossBridge).
            LoRaCrossBridge.shared.heardOnMeshtastic(payload, parsed: f)
            guard let body = ompRx.accept(f) else { return }
            guard let json = OMP.decodeBody(body) else {
                // 'D' (0x44) is an internet message a gateway pulled for us; the v2 pipeline consumes it.
                if body.first == 0x44 {
                    NotificationCenter.default.post(name: NSNotification.Name("OshiMeshDownlink"), object: nil,
                                                    userInfo: ["frame": body, "gateway": NSNumber(value: f.origin)])
                }
                return
            }
            deliverEnvelope(json, from: f.origin, frames: f.count)
        default:
            break
        }
    }
}

// MARK: - CoreBluetooth delegates

extension MeshtasticManager: CBCentralManagerDelegate, CBPeripheralDelegate {

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        bluetoothOn = central.state == .poweredOn
        permissionDenied = CBCentralManager.authorization == .denied
        if bluetoothOn {
            reconnectIfPaired()
            if wantsScan && peripheral == nil { startScan() }   // resume the scan requested before power-on
        } else {
            // Drop every CoreBluetooth object we hold. A stack reset
            // (.resetting → .poweredOn) invalidates all of them and sends no
            // disconnect callback, so nothing else clears them — and a stale
            // non-nil `peripheral` is exactly what wedges recovery, because the
            // scan fallback above is gated on `peripheral == nil`.
            peripheral = nil; toRadio = nil; fromRadio = nil; fromNum = nil
            connectedName = nil; draining = false
            sendTimer?.invalidate(); sendTimer = nil
            state = .off
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        // Re-pair silently with the radio we already know, so a stack reset
        // heals itself instead of dumping the user back into the pairing sheet.
        if reconnectViaScan,
           self.peripheral == nil,
           peripheral.identifier.uuidString == UserDefaults.standard.string(forKey: Self.pairedKey) {
            reconnectViaScan = false
            OshiLog.mesh.info("📡 Meshtastic: found the paired radio again — reconnecting")
            connect(peripheral)
            return
        }
        if !discovered.contains(where: { $0.peripheral.identifier == peripheral.identifier }) {
            discovered.append((peripheral, RSSI.intValue))
            discovered.sort { $0.rssi > $1.rssi }
        }
    }

    /// __LORA_BACKGROUND_IOS_2026_11_04__ iOS relaunched us for this radio.
    ///
    /// This runs BEFORE `centralManagerDidUpdateState` and before anything else
    /// in the app has had a chance to look at Bluetooth, so the peripheral in
    /// the dictionary is the only record that a connection already exists. Not
    /// adopting it here leaves a live GATT link that nothing in the process
    /// holds: the radio keeps the connection, the app believes it has none, and
    /// the first scan tries to connect to a peripheral it is already connected
    /// to.
    ///
    /// The delegate must be set again. Delegates are not preserved across a
    /// relaunch — only the connection is — so without this line notifications
    /// from the radio arrive at nobody, which is the exact failure this whole
    /// change exists to remove.
    func centralManager(_ central: CBCentralManager,
                        willRestoreState dict: [String: Any]) {
        let restored = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
        guard let radio = restored.first(where: { $0.state == .connected || $0.state == .connecting })
                ?? restored.first else {
            log("↻", "restored with no peripherals")
            return
        }
        peripheral = radio
        radio.delegate = self
        connectedName = radio.name ?? connectedName
        log("↻", "restored \(radio.name ?? "radio") in state \(radio.state.rawValue)")
        // Services are rediscovered rather than assumed: a relaunch can happen
        // after the radio rebooted, and characteristics from the previous
        // session would then be stale handles.
        if radio.state == .connected {
            radio.discoverServices([Self.serviceUUID])
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        connectedName = peripheral.name ?? "LoRa radio"
        peripheral.discoverServices([Self.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        lastError = error?.localizedDescription ?? "Connection failed"
        state = .off
        pendingPairId = nil   // never promote a failed attempt
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        OshiLog.mesh.info("📡 Meshtastic: disconnected (\(error?.localizedDescription ?? "clean"))")
        connectedName = nil; toRadio = nil; fromRadio = nil; fromNum = nil
        state = .off; draining = false
        sendTimer?.invalidate(); sendTimer = nil; sendQueue.removeAll()  // half-sent chunks are useless airtime
        loRaFramesTotal.removeAll(); loRaFramesSent.removeAll()          // and their progress no longer advances
        stopJammingWatch()
        // Auto-reconnect: the radio may just be rebooting or briefly out of range.
        if UserDefaults.standard.string(forKey: Self.pairedKey) != nil {
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
                self?.reconnectIfPaired()
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let svc = peripheral.services?.first(where: { $0.uuid == Self.serviceUUID }) else { return }
        peripheral.discoverCharacteristics([Self.toRadioUUID, Self.fromRadioUUID, Self.fromNumUUID], for: svc)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        for c in service.characteristics ?? [] {
            switch c.uuid {
            case Self.toRadioUUID:   toRadio = c
            case Self.fromRadioUUID: fromRadio = c
            case Self.fromNumUUID:   fromNum = c; peripheral.setNotifyValue(true, for: c)
            default: break
            }
        }
        if toRadio != nil, fromRadio != nil { startConfig() }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if characteristic.uuid == Self.fromNumUUID {
            // Radio signals new data waiting → drain FromRadio.
            draining = false
            drainFromRadio()
            return
        }
        guard characteristic.uuid == Self.fromRadioUUID else { return }
        // Failed read still delivers the PREVIOUS value with error != nil —
        // parsing it would re-deliver a stale packet and loop the read chain.
        guard error == nil else { draining = false; return }
        if let d = characteristic.value, !d.isEmpty {
            handleFromRadio(d)
            // Keep reading until the radio returns an empty value.
            peripheral.readValue(for: characteristic)
        } else {
            draining = false
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error { lastError = error.localizedDescription }
    }
}

// MARK: - Jamming detection (LoRaJammingDetector.swift)

/// __ANTI_JAMMING_PHASE1_2026_11_13__ iOS port of Android's MeshtasticManager
/// jamming watch (commit 2699c942), plus the "Send over LoRa anyway" override.
/// Everything here runs on the main queue, which is the CoreBluetooth delegate
/// queue of this manager, so the detector needs no lock.
extension MeshtasticManager {

    /// The channel is called jammed (whatever the user decided about it).
    var isJammed: Bool { jamming == .probableJamming }

    /// THE question the send path asks: is LoRa off the table right now?
    /// True while PROBABLE_JAMMING, unless "Send over LoRa anyway" is in force.
    var excludesLoRaForJamming: Bool {
        jammingOverride.excludesLoRa(state: jamming, now: Date())
    }

    /// "Send over LoRa anyway": lift the jamming exclusion for
    /// `LoRaJammingOverride.duration` (10 min) or until the channel is CLEAR.
    /// Messages held while the channel was excluded go out now.
    func overrideJammingExclusion() {
        guard jammingOverride.activate(state: jamming, now: Date()) else { return }
        jammingOverrideUntil = jammingOverride.until
        log("!", "jamming warning overridden by the user: LoRa in use for \(Int(LoRaJammingOverride.duration / 60)) min")
        flushStoreAndForward()
    }

    /// Back to the protective default before the 10 minutes are up.
    func cancelJammingOverride() {
        guard jammingOverride.until != nil else { return }
        jammingOverride.cancel()
        jammingOverrideUntil = nil
        log("!", "jamming override cancelled: LoRa left out again")
    }

    fileprivate static func nowMs(_ d: Date = Date()) -> Int64 {
        Int64((d.timeIntervalSince1970 * 1000).rounded())
    }

    /// portnum, payload and request_id of a decoded `Data`.
    fileprivate static func decodedData(_ dec: Data) -> (UInt64, Data, UInt32) {
        var port: UInt64 = 0, payload = Data(), requestId: UInt32 = 0
        for f in parseProtoFields(dec) {
            switch (f.number, f.wire) {
            case (1, 0): port = f.varintValue
            case (2, 2): payload = f.payload
            case (6, 0), (6, 5): requestId = UInt32(truncatingIfNeeded: f.varintValue)
            default: break
            }
        }
        return (port, payload, requestId)
    }

    /// Every detector call goes through here, so a change of state is published,
    /// logged and acted on in exactly one place.
    fileprivate func jam(_ op: (LoRaJammingDetector) -> LoRaJammingDetector.Assessment) {
        let before = jammingDetector.state
        let after = op(jammingDetector)
        let overrideWas = jammingOverride.until
        jammingOverride.observe(after.state)
        if jammingOverride.until != overrideWas { jammingOverrideUntil = jammingOverride.until }
        guard after.state != before else { return }
        jamming = after.state
        let why = after.reasons.isEmpty ? "" : ": " + after.reasons.joined(separator: ", ")
        let name: String
        switch after.state {
        case .clear: name = "clear"
        case .congested: name = "congested"
        case .probableJamming: name = "probably jammed"
        }
        log("!", "radio channel \(name)\(why)")
        OshiLog.mesh.info("📡 LoRa channel \(name)\(why)")
        if before == .probableJamming { flushStoreAndForward() }
    }

    fileprivate func startJammingWatch() {
        jammingDetector.reset(nowMs: Self.nowMs())
        jamming = .clear
        jammingOverride.cancel(); jammingOverrideUntil = nil
        lastCounterStatsAt = .distantPast
        jammingTimer?.invalidate()
        jammingTicks = 0
        // The first LocalStats request goes out a few seconds in, then every
        // `jammingTick` whenever nothing has reported RX counters for a while (an
        // OSHI Mesh radio sends its RADIO frame every 30 s, so it is not polled).
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
            guard let self, self.isReady, self.jammingTimer == nil else { return }
            self.jammingTick()
            let t = Timer(timeInterval: Self.jammingTick, repeats: true) { [weak self] t in
                guard let self, self.isReady else { t.invalidate(); return }
                self.jammingTick()
            }
            self.jammingTimer = t
            RunLoop.main.add(t, forMode: .common)
        }
    }

    fileprivate func stopJammingWatch() {
        jammingTimer?.invalidate(); jammingTimer = nil
        jammingDetector.reset(nowMs: Self.nowMs())
        jamming = .clear
        jammingOverride.cancel(); jammingOverrideUntil = nil
    }

    private func jammingTick() {
        jammingTicks += 1
        sweepExpiredAcks()
        jam { $0.evaluate(nowMs: Self.nowMs()) }
        if jammingOverride.expire(now: Date()) {
            jammingOverrideUntil = nil
            log("!", "jamming override expired: LoRa left out again while the channel looks jammed")
        }
        if Date().timeIntervalSince(lastCounterStatsAt) >= Self.localStatsPoll, myNodeNum != 0 {
            // Addressed to our own node: answered over Bluetooth, never transmitted.
            enqueuePacket(to: myNodeNum, portnum: Self.telemetryPortnum,
                          payload: LoRaRadioTelemetry.localStatsRequest(),
                          wantAck: false, wantResponse: true)
            lastCounterStatsAt = Date().addingTimeInterval(-Self.localStatsPoll + Self.localStatsRetry)
        }
    }

    fileprivate func handleOwnTelemetry(_ payload: Data) {
        guard let t = LoRaRadioTelemetry.parse(payload) else { return }
        if t.isLocalStats { lastCounterStatsAt = Date() }
        let sample = LoRaJammingDetector.Sample(atMs: Self.nowMs(),
                                                channelUtilPct: t.channelUtilPct, airUtilTxPct: t.airUtilTxPct,
                                                noiseFloorDbm: t.noiseFloorDbm, rxTotal: t.rxTotal, rxBad: t.rxBad)
        jam { $0.onStats(sample) }
    }

    /// Verdicts our own radio generates about our unicasts.
    fileprivate func handleOwnRouting(_ payload: Data, requestId: UInt32) {
        guard requestId != 0, loraPendingAck[requestId] != nil else { return }
        var reason: UInt64 = 0
        for f in parseProtoFields(payload) where f.number == 3 && f.wire == 0 { reason = f.varintValue }
        if reason == 0 {
            // An implicit ack: a relay rebroadcast our packet. The link works;
            // delivery is still unproven, so the send stays pending for the
            // destination's own ack.
            jam { $0.onAckResult(atMs: Self.nowMs(), success: true) }
        } else if Self.linkFailureReasons.contains(reason) {
            // The radio gave up (no route / timeout / max retransmit): settle the
            // send exactly like a named failure from the mesh, which also feeds
            // the detector and lets MessageManager escalate now rather than after
            // the 90 s window.
            handleRouting(payload, requestId: requestId, from: myNodeNum)
        }
    }
}


// MARK: - Held queue, firmware gate, airtime hint (pure; pinned by LoRaHeldQueueTests)

/// __LORA_PROBE_BEFORE_FLUSH_2026_11_18__ When a message may go to the radio NOW, and when what the radio
/// holds may go out. Android `LoRaRadioGate` (same names, same answers).
enum LoRaRadioGate {
    static func firmwareKnown(probeDone: Bool, answeredOSHI: Bool) -> Bool { probeDone || answeredOSHI }

    static func holdReason(probeDone: Bool, answeredOSHI: Bool, excludedForJamming: Bool) -> String? {
        if excludedForJamming { return "radio channel looks jammed" }
        if !firmwareKnown(probeDone: probeDone, answeredOSHI: answeredOSHI) { return "waiting for the radio's firmware probe" }
        return nil
    }

    static func mayFlush(ready: Bool, probeDone: Bool, answeredOSHI: Bool, excludedForJamming: Bool) -> Bool {
        ready && !excludedForJamming && firmwareKnown(probeDone: probeDone, answeredOSHI: answeredOSHI)
    }

    /// OSHI Mesh firmware: the whole OMP body, 64 x 182. Otherwise the phone's stock path, 12 frames.
    static func fits(ompBodyBytes: Int, oshiFirmware: Bool) -> Bool {
        guard ompBodyBytes >= 0, ompBodyBytes < Int.max / 2 else { return false }
        let frames = (ompBodyBytes + OMP.maxFragData - 1) / OMP.maxFragData
        return frames <= (oshiFirmware ? OMP.maxFrags : MeshtasticManager.loRaMaxFrames)
    }
}

/// __LORA_HELD_DURABLE_2026_11_18__ Envelopes waiting for the radio. Persisted through injected
/// closures (FileStorage in the app, memory in tests); evictions past `capacity` are RETURNED so the
/// caller shows them failed — never dropped in silence. Not thread-safe by itself: its owner
/// (MeshtasticManager) is main-queue only.
final class LoRaHeldQueue {
    struct Held: Codable {
        let message: SecureMessage
        let heldAt: Date
    }

    static let storageKey = "lora_held_queue"
    static let defaultCapacity = 200

    let capacity: Int
    private var items: [Held]
    private let save: ([Held]) -> Void

    init(capacity: Int = LoRaHeldQueue.defaultCapacity,
         load: () -> [Held]? = { nil },
         save: @escaping ([Held]) -> Void = { _ in }) {
        self.capacity = capacity
        self.items = load() ?? []
        self.save = save
    }

    /// Hold `message` (already meshWireSafe). Returns the ids evicted, oldest first.
    func add(_ message: SecureMessage, now: Date) -> [String] {
        let firstHeld = items.first(where: { $0.message.id == message.id })?.heldAt ?? now
        items.removeAll { $0.message.id == message.id }
        items.append(Held(message: message, heldAt: firstHeld))
        var evicted: [String] = []
        while items.count > capacity { evicted.append(items.removeFirst().message.id) }
        save(items)
        return evicted
    }

    @discardableResult
    func remove(_ id: String) -> Bool {
        let before = items.count
        items.removeAll { $0.message.id == id }
        guard items.count != before else { return false }
        save(items)
        return true
    }

    func snapshot() -> [Held] { items }
    func contains(_ id: String) -> Bool { items.contains { $0.message.id == id } }
    var count: Int { items.count }
    var isEmpty: Bool { items.isEmpty }
}

/// __LORA_AIRTIME_HINT_2026_11_18__ Messages an OSHI Mesh radio accepted (OMP QUEUED) but has not put on
/// the air for over a minute — the regional duty-cycle budget (EU_868: hourly) is spent. The bubble
/// then says "Waiting for radio airtime (legal duty-cycle limit)" instead of looking hung. A small
/// observable of its own so a chat bubble does not observe the whole radio manager.
final class LoRaAirtimeStatus: ObservableObject {
    static let shared = LoRaAirtimeStatus()
    static let threshold: TimeInterval = 60
    static let forgetAfter: TimeInterval = 2 * 3600

    @Published private(set) var waiting: Set<String> = []
    private var queuedAt: [String: Date] = [:]
    private var timer: Timer?

    func onQueued(_ id: String, now: Date = Date()) {
        if queuedAt[id] == nil { queuedAt[id] = now }
        refresh(now: now)
        startTimer()
    }

    func onProgress(_ id: String, now: Date = Date()) {
        queuedAt[id] = nil
        refresh(now: now)
    }

    /// Pure core, exposed for tests.
    static func waiting(_ queuedAt: [String: Date], now: Date) -> Set<String> {
        Set(queuedAt.filter { now.timeIntervalSince($0.value) >= threshold
            && now.timeIntervalSince($0.value) <= forgetAfter }.keys)
    }

    func refresh(now: Date = Date()) {
        queuedAt = queuedAt.filter { now.timeIntervalSince($0.value) <= Self.forgetAfter }
        let w = Self.waiting(queuedAt, now: now)
        if w != waiting { waiting = w }
        if queuedAt.isEmpty { timer?.invalidate(); timer = nil }
    }

    private func startTimer() {
        guard timer == nil else { return }
        let t = Timer(timeInterval: 5, repeats: true) { [weak self] _ in self?.refresh() }
        timer = t
        RunLoop.main.add(t, forMode: .common)
    }
}
