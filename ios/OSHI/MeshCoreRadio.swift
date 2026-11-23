//
//  MeshCoreRadio.swift
//  OSHI — OSHI messages over a MeshCore radio, and the phone as a Meshtastic <-> MeshCore bridge.
//
//  A LoRa radio listens to one network at a time, and MeshCore is not Meshtastic (other radio settings,
//  other packets). So OSHI rides MeshCore the way oshi-meshcore-bridge does, byte for byte:
//
//    OMP DATA frame (OshiMeshRadio.swift)           the same frames as on Meshtastic, origin = our id
//      → "OB" envelope, split to ≤160 B              OSHIBridgeWire below == oshi_bridge/wire.py
//      → MeshCore group-channel datagram             CMD_SEND_CHANNEL_DATA, data_type 0xFF4F,
//                                                    channel "#oshi-bridge" (secret = SHA-256(name)[:16])
//
//  so a phone on a MeshCore radio talks to other such phones through MeshCore repeaters, and to the
//  Meshtastic side through any oshi-meshcore-bridge — or through a phone that holds one radio of each
//  (`LoRaCrossBridge`). MeshCore users without that channel never see the traffic.
//
//  Companion protocol: MeshCore examples/companion_radio/MyMesh.cpp (BLE Nordic UART, one frame per
//  write/notify, request/response strictly one at a time; PUSH codes ≥ 0x80 are unsolicited).
//

import Foundation
import CoreBluetooth
import CryptoKit
import Combine

// MARK: - MeshCore companion protocol

enum MeshCore {
    static let serviceUUID = CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    static let rxUUID = CBUUID(string: "6E400002-B5A3-F393-E0A9-E50E24DCCA9E")   // app → radio (write)
    static let txUUID = CBUUID(string: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E")   // radio → app (notify)

    static let cmdAppStart: UInt8 = 1
    static let cmdSyncNextMessage: UInt8 = 10
    static let cmdGetChannel: UInt8 = 31
    static let cmdSetChannel: UInt8 = 32
    static let cmdSendChannelData: UInt8 = 62

    static let respOK: UInt8 = 0
    static let respErr: UInt8 = 1
    static let respSelfInfo: UInt8 = 5
    static let respNoMoreMessages: UInt8 = 10
    static let respChannelInfo: UInt8 = 18
    static let respChannelDataRecv: UInt8 = 27
    static let pushMsgWaiting: UInt8 = 0x83

    /// MAX_FRAME_SIZE (176) - 9 in the current firmware.
    static let maxChannelData = 167
    static let channelSlots = 8
    static let pathFlood: UInt8 = 0xFF

    static func appStart(appName: String = "OSHI") -> Data {
        Data([cmdAppStart]) + Data(count: 7) + Data(appName.utf8)
    }

    static func getChannel(_ idx: Int) -> Data { Data([cmdGetChannel, UInt8(idx)]) }

    static func setChannel(_ idx: Int, name: String, secret: Data) -> Data {
        var n = Data(name.utf8.prefix(31))
        n.append(Data(count: 32 - n.count))
        return Data([cmdSetChannel, UInt8(idx)]) + n + secret.prefix(16)
    }

    static func sendChannelData(_ idx: Int, dataType: UInt16, payload: Data) -> Data {
        Data([cmdSendChannelData, UInt8(idx), pathFlood, UInt8(dataType & 0xFF), UInt8(dataType >> 8)]) + payload
    }

    static let syncNextMessage = Data([cmdSyncNextMessage])

    /// RESP_CODE_SELF_INFO: code, adv type, tx power, max tx power, public key (32), ...
    static func selfPublicKey(_ f: Data) -> Data? {
        let b = [UInt8](f)
        guard b.count >= 36, b[0] == respSelfInfo else { return nil }
        return Data(b[4..<36])
    }

    struct ChannelInfo: Equatable { var idx: Int; var name: String; var secret: Data }

    /// RESP_CODE_CHANNEL_INFO: code, idx, name (32, NUL-padded), secret (16).
    static func parseChannelInfo(_ f: Data) -> ChannelInfo? {
        let b = [UInt8](f)
        guard b.count >= 50, b[0] == respChannelInfo else { return nil }
        let nameBytes = b[2..<34].prefix { $0 != 0 }
        return ChannelInfo(idx: Int(b[1]), name: String(decoding: nameBytes, as: UTF8.self), secret: Data(b[34..<50]))
    }

    struct ChannelData: Equatable { var channelIdx: Int; var dataType: UInt16; var payload: Data }

    /// RESP_CODE_CHANNEL_DATA_RECV: code, snr×4, 0, 0, channel idx, path len, data type (LE16), len, data.
    static func parseChannelData(_ f: Data) -> ChannelData? {
        let b = [UInt8](f)
        guard b.count >= 9, b[0] == respChannelDataRecv else { return nil }
        let len = Int(b[8])
        guard b.count >= 9 + len else { return nil }
        return ChannelData(channelIdx: Int(b[4]), dataType: UInt16(b[6]) | UInt16(b[7]) << 8,
                           payload: Data(b[9..<(9 + len)]))
    }
}

// MARK: - "OB" envelope (oshi-meshcore-bridge/oshi_bridge/wire.py)

enum OSHIBridgeWire {
    static let marker: [UInt8] = [0x4F, 0x42]   // "OB"
    static let version: UInt8 = 1
    static let headerLen = 9
    static let maxDatagram = 160                // conservative vs MeshCore's 167
    static let dataType: UInt16 = 0xFF4F        // MeshCore's unregistered dev range
    static let channelName = "#oshi-bridge"
    static var channelSecret: Data { Data(SHA256.hash(data: Data(channelName.utf8))).prefix(16) }

    struct Part: Equatable { var sender: UInt32; var seq: UInt8; var part: Int; var total: Int; var chunk: Data }

    /// `sender` is the bridge field: whoever put the frame on MeshCore (a bridge's Meshtastic node number,
    /// or our own MeshCore-derived id). Receivers ignore their own.
    static func split(_ frame: Data, sender: UInt32, seq: UInt8, maxDatagram: Int = maxDatagram) -> [Data]? {
        let room = maxDatagram - headerLen
        let bytes = [UInt8](frame)
        let chunks: [ArraySlice<UInt8>] = bytes.isEmpty ? [[]] :
            stride(from: 0, to: bytes.count, by: room).map { bytes[$0..<min(bytes.count, $0 + room)] }
        guard chunks.count <= 15 else { return nil }
        var s = sender.littleEndian
        let senderBytes = withUnsafeBytes(of: &s) { Data($0) }
        return chunks.enumerated().map { i, c in
            Data(marker) + Data([version << 4]) + senderBytes + Data([seq, UInt8(i << 4 | chunks.count)]) + Data(c)
        }
    }

    static func parse(_ d: Data) -> Part? {
        let b = [UInt8](d)
        guard b.count >= headerLen, b[0] == marker[0], b[1] == marker[1], b[2] >> 4 == version else { return nil }
        let sender = UInt32(b[3]) | UInt32(b[4]) << 8 | UInt32(b[5]) << 16 | UInt32(b[6]) << 24
        let part = Int(b[8] >> 4), total = Int(b[8] & 0x0F)
        guard total > 0, part < total else { return nil }
        return Part(sender: sender, seq: b[7], part: part, total: total, chunk: Data(b[headerLen...]))
    }

    /// Multi-part envelopes, keyed by (sender, seq); incomplete sets expire.
    final class Joiner {
        private struct Pending { var total: Int; var first: Date; var chunks: [Int: Data] }
        private var pending: [String: Pending] = [:]
        let timeout: TimeInterval
        let maxPending: Int
        init(timeout: TimeInterval = 90, maxPending: Int = 128) { self.timeout = timeout; self.maxPending = maxPending }

        func push(_ p: Part, now: Date = Date()) -> Data? {
            if p.total == 1 { return p.chunk }
            pending = pending.filter { now.timeIntervalSince($0.value.first) <= timeout }
            let key = "\(p.sender):\(p.seq)"
            var e = pending[key]
            if e == nil || e!.total != p.total {
                if pending.count >= maxPending, let oldest = pending.min(by: { $0.value.first < $1.value.first })?.key {
                    pending.removeValue(forKey: oldest)
                }
                e = Pending(total: p.total, first: now, chunks: [:])
            }
            e!.chunks[p.part] = p.chunk
            guard e!.chunks.count == e!.total else { pending[key] = e; return nil }
            pending.removeValue(forKey: key)
            return (0..<e!.total).reduce(into: Data()) { $0.append(e!.chunks[$1] ?? Data()) }
        }
    }
}

// MARK: - The radio

/// One MeshCore companion radio over Bluetooth. Mirrors MeshtasticManager's shape (singleton, main queue,
/// state restoration) so the settings screen and the message pipeline treat both radios alike.
final class MeshCoreManager: NSObject, ObservableObject {
    static let shared = MeshCoreManager()

    enum ConnState: String { case off, scanning, connecting, configuring, ready }

    @Published private(set) var state: ConnState = .off
    @Published private(set) var discovered: [CBPeripheral] = []
    @Published private(set) var connectedName: String?
    /// The slot our "#oshi-bridge" channel lives in, or nil while unknown / when every slot is taken.
    @Published private(set) var channelIdx: Int?
    @Published private(set) var lastError: String?
    @Published private(set) var framesSent = 0
    @Published private(set) var messagesReceived = 0

    var isReady: Bool { state == .ready && channelIdx != nil }

    /// Our OMP origin / envelope sender on MeshCore: the first four bytes of the radio's public key.
    /// Meshtastic node numbers are 32-bit too; a collision only costs a reassembly key, never a message.
    private(set) var nodeId: UInt32 = 0

    private static let pairedKey = "oshi.meshcore.peripheralId"
    private static let restoreIdentifier = "oshi.meshcore.central"
    private static let sendGap: TimeInterval = 2.5   // OMP pacing; a MeshCore datagram is ~0.5-1 s of air

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var rx: CBCharacteristic?
    private var tx: CBCharacteristic?

    /// Companion requests are strictly one at a time: the next goes out when the previous is answered.
    private var commands: [Data] = []
    private var awaiting = false
    private var probing: Int?                    // GET_CHANNEL slot being inspected at setup
    private var firstEmptySlot: Int?
    private var datagrams: [Data] = []
    private var sendTimer: Timer?
    private var seq: UInt8 = 0

    private let joiner = OSHIBridgeWire.Joiner()
    private let reassembler = OMPReassembler()

    private override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main,
                                   options: [CBCentralManagerOptionRestoreIdentifierKey: Self.restoreIdentifier])
    }

    static var isPaired: Bool { UserDefaults.standard.string(forKey: pairedKey) != nil }

    /// Any LoRa radio this phone can end up sending through — what sets the 12-frame message budget.
    static var anyLoRaRadioPaired: Bool { isPaired || MeshtasticManager.isRadioPaired }

    // MARK: Pairing

    func startScan() {
        guard central.state == .poweredOn else { return }
        discovered = []
        state = .scanning
        central.scanForPeripherals(withServices: [MeshCore.serviceUUID])
    }

    func stopScan() {
        central.stopScan()
        if state == .scanning { state = .off }
    }

    func connect(_ p: CBPeripheral) {
        central.stopScan()
        peripheral = p
        p.delegate = self
        state = .connecting
        UserDefaults.standard.set(p.identifier.uuidString, forKey: Self.pairedKey)
        central.connect(p)
    }

    func forget() {
        if let p = peripheral { central.cancelPeripheralConnection(p) }
        UserDefaults.standard.removeObject(forKey: Self.pairedKey)
        reset()
        state = .off
    }

    private func reconnectIfPaired() {
        guard peripheral == nil, let s = UserDefaults.standard.string(forKey: Self.pairedKey),
              let uuid = UUID(uuidString: s), let p = central.retrievePeripherals(withIdentifiers: [uuid]).first else { return }
        connect(p)
    }

    private func reset() {
        peripheral = nil; rx = nil; tx = nil
        commands = []; awaiting = false; probing = nil; firstEmptySlot = nil
        datagrams = []; sendTimer?.invalidate(); sendTimer = nil
        channelIdx = nil; connectedName = nil; nodeId = 0
    }

    // MARK: Sending

    /// The same entry point as MeshtasticManager.sendSecureMessage: the whole envelope, broadcast, as OMP.
    @discardableResult
    func sendSecureMessage(_ message: SecureMessage) -> Bool {
        guard isReady else { return false }
        // Same opt-in as the Meshtastic side: media over LoRa is off unless the user turned it on.
        let carriesMedia = message.mediaAttachment != nil || message.originalMediaData != nil
        if carriesMedia && !MeshtasticManager.mediaOverLoRaEnabled { return false }
        guard let json = try? JSONEncoder().encode(message.meshWireSafe()) else { return false }
        let body = OMP.encodeBody(json)
        guard let frames = OMP.dataFrames(msgId: UInt32.random(in: 1...UInt32.max), dest: OMP.broadcast,
                                          body: body, origin: nodeId), frames.count <= 12 else { return false }
        frames.forEach { sendFrame($0) }
        return true
    }

    /// One OMP frame onto MeshCore under our envelope. Also what the cross-network bridge calls.
    func sendFrame(_ frame: Data) {
        guard isReady, let idx = channelIdx else { return }
        seq &+= 1
        guard let parts = OSHIBridgeWire.split(frame, sender: nodeId, seq: seq) else { return }
        for p in parts { datagrams.append(MeshCore.sendChannelData(idx, dataType: OSHIBridgeWire.dataType, payload: p)) }
        drainDatagrams()
    }

    private func drainDatagrams() {
        guard sendTimer == nil, !datagrams.isEmpty else { return }
        enqueue(datagrams.removeFirst())
        framesSent += 1
        sendTimer = Timer.scheduledTimer(withTimeInterval: Self.sendGap, repeats: false) { [weak self] _ in
            self?.sendTimer = nil
            self?.drainDatagrams()
        }
    }

    // MARK: Companion request queue

    private func enqueue(_ cmd: Data) {
        commands.append(cmd)
        pump()
    }

    private func pump() {
        guard !awaiting, !commands.isEmpty, let p = peripheral, let rx else { return }
        awaiting = true
        p.writeValue(commands.removeFirst(), for: rx, type: .withResponse)
    }

    // MARK: Receiving

    private func handle(_ f: Data) {
        guard let code = f.first else { return }
        if code >= 0x80 {
            if code == MeshCore.pushMsgWaiting { enqueue(MeshCore.syncNextMessage) }
            return
        }
        awaiting = false
        defer { pump() }
        switch code {
        case MeshCore.respSelfInfo:
            if let key = MeshCore.selfPublicKey(f) {
                let k = [UInt8](key)
                nodeId = UInt32(k[0]) | UInt32(k[1]) << 8 | UInt32(k[2]) << 16 | UInt32(k[3]) << 24
            }
            probing = 0
            enqueue(MeshCore.getChannel(0))
        case MeshCore.respChannelInfo:
            guard let info = MeshCore.parseChannelInfo(f) else { return }
            channelProbed(info)
        case MeshCore.respErr where probing != nil:
            // A slot the firmware does not have ends the scan like an unknown one.
            probing = MeshCore.channelSlots
            channelProbed(nil)
        case MeshCore.respOK where state == .configuring && channelIdx != nil:
            becomeReady()
        case MeshCore.respChannelDataRecv:
            if let d = MeshCore.parseChannelData(f) { receive(d) }
            enqueue(MeshCore.syncNextMessage)
        case MeshCore.respNoMoreMessages:
            break
        case 7, 8, 16, 17:
            // Contact / channel text that is not ours: leave the radio's queue moving.
            enqueue(MeshCore.syncNextMessage)
        default:
            break
        }
    }

    /// Setup: find "#oshi-bridge" in slots 0-7, else claim the first EMPTY slot. Never overwrite a channel
    /// the owner configured: their MeshCore app would silently lose it.
    private func channelProbed(_ info: MeshCore.ChannelInfo?) {
        if let info {
            if info.name == OSHIBridgeWire.channelName && info.secret == OSHIBridgeWire.channelSecret {
                probing = nil
                channelIdx = info.idx
                becomeReady()
                return
            }
            if info.name.isEmpty && info.idx > 0 && firstEmptySlot == nil { firstEmptySlot = info.idx }
            let next = info.idx + 1
            if next < MeshCore.channelSlots { probing = next; enqueue(MeshCore.getChannel(next)); return }
        }
        probing = nil
        guard let slot = firstEmptySlot else {
            lastError = "meshcore.noFreeChannel"
            OshiLog.mesh.info("📡 MeshCore: every channel slot is in use; OSHI cannot add #oshi-bridge")
            return
        }
        channelIdx = slot
        enqueue(MeshCore.setChannel(slot, name: OSHIBridgeWire.channelName, secret: OSHIBridgeWire.channelSecret))
    }

    private func becomeReady() {
        guard state != .ready else { return }
        state = .ready
        lastError = nil
        OshiLog.mesh.info("📡 MeshCore: ready, #oshi-bridge in slot \(self.channelIdx ?? -1), id \(String(format: "%08x", self.nodeId))")
        enqueue(MeshCore.syncNextMessage)   // anything the radio queued while we were away
        NotificationCenter.default.post(name: NSNotification.Name("MeshCoreReady"), object: nil)
    }

    private func receive(_ d: MeshCore.ChannelData) {
        guard d.channelIdx == channelIdx, d.dataType == OSHIBridgeWire.dataType,
              let part = OSHIBridgeWire.parse(d.payload), part.sender != nodeId,
              let frame = joiner.push(part), OMP.type(of: frame) == .data,
              let f = OMP.parseData(frame) else { return }
        LoRaCrossBridge.shared.heardOnMeshCore(frame, parsed: f)
        guard let body = reassembler.accept(f), let json = OMP.decodeBody(body),
              var msg = try? JSONDecoder().decode(SecureMessage.self, from: json) else { return }
        msg = msg.meshInboundSafe()   // __MESH_INBOUND_PLAINTEXT_2026_11_20__
        msg.deliveryMethod = .lora
        if let myKey = MessageManager.sharedIdentityManager?.publicKey, !myKey.isEmpty,
           MeshtasticManager.normalizeKey(msg.recipientPublicKey) != MeshtasticManager.normalizeKey(myKey) {
            return
        }
        messagesReceived += 1
        OshiLog.mesh.info("📡 MeshCore: OSHI msg \(msg.id.prefix(8)) (\(f.count) frames)")
        // No loraNodeNum: that key binds MESHTASTIC node numbers to identities, and ours is MeshCore-derived.
        NotificationCenter.default.post(name: NSNotification.Name("MeshMessageReceived"), object: nil,
                                        userInfo: ["message": msg])
    }
}

extension MeshCoreManager: CBCentralManagerDelegate, CBPeripheralDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn { reconnectIfPaired() } else { reset(); state = .off }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        if let p = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral])?.first {
            peripheral = p
            p.delegate = self
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover p: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        if !discovered.contains(where: { $0.identifier == p.identifier }) { discovered.append(p) }
    }

    func centralManager(_ central: CBCentralManager, didConnect p: CBPeripheral) {
        connectedName = p.name
        state = .configuring
        p.discoverServices([MeshCore.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        lastError = error?.localizedDescription
        reset(); state = .off
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        reset(); state = .off
        // Walked out of range or the radio rebooted: keep trying, the way the Meshtastic side does.
        if Self.isPaired { central.connect(p); peripheral = p; p.delegate = self; state = .connecting }
    }

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let s = p.services?.first(where: { $0.uuid == MeshCore.serviceUUID }) else { return }
        p.discoverCharacteristics([MeshCore.rxUUID, MeshCore.txUUID], for: s)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
        rx = s.characteristics?.first { $0.uuid == MeshCore.rxUUID }
        tx = s.characteristics?.first { $0.uuid == MeshCore.txUUID }
        // Subscribing triggers the radio's PIN pairing on first use (iOS shows its own prompt).
        if let tx { p.setNotifyValue(true, for: tx) }
    }

    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor c: CBCharacteristic, error: Error?) {
        guard c.uuid == MeshCore.txUUID, c.isNotifying else {
            if let error { lastError = error.localizedDescription }
            return
        }
        awaiting = false
        enqueue(MeshCore.appStart())
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) {
        guard c.uuid == MeshCore.txUUID, let v = c.value else { return }
        handle(v)
    }
}

// MARK: - The phone as a bridge

/// When this phone holds a Meshtastic/OSHI radio AND a MeshCore radio, every OSHI DATA frame heard on one
/// side is repeated on the other — what oshi-meshcore-bridge does on a Raspberry Pi, carried in a pocket.
/// Frames keep their origin and msgId, so receivers dedupe them like any relayed frame; a frame already
/// repeated is never repeated again, which stops two bridging phones from ping-ponging it.
final class LoRaCrossBridge {
    static let shared = LoRaCrossBridge()
    static let enabledKey = "oshi.lora.crossBridge"

    private var seen: [String: Date] = [:]
    private static let ttl: TimeInterval = 1800
    private static let maxSeen = 2048

    var isEnabled: Bool { UserDefaults.standard.object(forKey: Self.enabledKey) as? Bool ?? true }

    func heardOnMeshtastic(_ frame: Data, parsed f: OMP.DataFrame) {
        guard isEnabled, MeshCoreManager.shared.isReady, firstTime(f) else { return }
        MeshCoreManager.shared.sendFrame(frame)
    }

    func heardOnMeshCore(_ frame: Data, parsed f: OMP.DataFrame) {
        guard isEnabled, MeshtasticManager.shared.isReady, firstTime(f) else { return }
        MeshtasticManager.shared.relayOMPFrame(frame)
    }

    private func firstTime(_ f: OMP.DataFrame, now: Date = Date()) -> Bool {
        // Only broadcast DATA: unicast OMP belongs to the firmware's SACK/receipt machinery, which a phone
        // repeating fragments would confuse. Everything the OSHI apps send is broadcast.
        guard f.dest == OMP.broadcast else { return false }
        // Not the origin: an OSHI Mesh radio re-stamps the origin of what its phone hands it, the msgId it keeps.
        let key = "\(f.msgId):\(f.idx):\(f.count)"
        if let t = seen[key], now.timeIntervalSince(t) < Self.ttl { return false }
        if seen.count >= Self.maxSeen { seen = seen.filter { now.timeIntervalSince($0.value) < Self.ttl } }
        if seen.count >= Self.maxSeen, let oldest = seen.min(by: { $0.value < $1.value })?.key { seen.removeValue(forKey: oldest) }
        seen[key] = now
        return true
    }
}
