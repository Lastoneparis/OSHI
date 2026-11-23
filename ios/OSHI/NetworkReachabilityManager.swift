//
//  NetworkReachabilityManager.swift
//  OSHI
//
//  Centralized network connectivity monitoring using NWPathMonitor
//  Provides offline detection for message retry and UI feedback
//

import Foundation
import Network
import Combine

class NetworkReachabilityManager: ObservableObject {
    static let shared = NetworkReachabilityManager()

    // MARK: - Published State
    @Published var isConnected: Bool = true
    @Published var connectionType: ConnectionType = .unknown
    @Published var isExpensive: Bool = false  // Cellular data
    @Published var isConstrained: Bool = false  // Low data mode

    // MARK: - Connection Type
    enum ConnectionType {
        case wifi
        case cellular
        case wired
        case unknown
        case offline

        var localizedString: String {
            switch self {
            case .wifi: return "network.type.wifi".localized
            case .cellular: return "network.type.cellular".localized
            case .wired: return "network.type.wired".localized
            case .unknown: return "network.type.unknown".localized
            case .offline: return "network.type.offline".localized
            }
        }

        var icon: String {
            switch self {
            case .wifi: return "wifi"
            case .cellular: return "antenna.radiowaves.left.and.right"
            case .wired: return "cable.connector"
            case .unknown: return "network"
            case .offline: return "wifi.slash"
            }
        }
    }

    // MARK: - Private Properties
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "com.oshi.network.monitor", qos: .utility)
    private var cancellables = Set<AnyCancellable>()

    // Track offline transition for retry queue
    private var wasOffline = false

    /// True when the path is carried by a real radio/cable — WiFi, cellular or
    /// ethernet — rather than by a tunnel alone.
    ///
    /// `path.status` is not enough to answer "can this device reach the
    /// internet". A VPN installs a utun interface with its own default route,
    /// and that route keeps the path `.satisfied` after the physical link
    /// underneath it is gone. Anything gated on `isConnected` therefore never
    /// fires for a user behind a VPN — including the off-grid LoRa shortcut in
    /// `MessageManager.fallbackToIPFS`, which is the one place the radio is
    /// reachable at all. Someone with a VPN profile and a paired node would sit
    /// through every internet timeout before the radio was offered the message.
    ///
    /// Deliberately kept separate from `isConnected`: that flag drives the
    /// offline badge and the retry queue, and a VPN over working WiFi is
    /// genuinely online. Only the transport choice needs to know the difference.
    @Published private(set) var hasPhysicalPath: Bool = true

    /// __MSG_DUP_COST_2026_10_09__ Whether the path runs over cellular, by
    /// INTERFACE TYPE.
    ///
    /// Stored rather than derived from `isExpensive`, because the call stack
    /// already measured that `isExpensive` lies: the 95-98%-loss cellular leg
    /// of 2026-07-27 reported `expensive=false` AND `constrained=false`. See
    /// `VoiceCallManager.mediaDuplicationAffordable()`, which says in as many
    /// words not to simplify the rule to `isExpensive`. The value was already
    /// being logged on every path change (`DIAG_NET_PATH ... cell=`); it just
    /// was not kept.
    @Published private(set) var isCellular: Bool = false

    // MARK: - Initialization

    private init() {
        startMonitoring()
    }

    deinit {
        stopMonitoring()
    }

    // MARK: - Public Methods

    /// 🔧 FIX (2026-06-02): debounce false→true→false flicker. NWPathMonitor on
    /// iOS sometimes briefly reports `.unsatisfied` during WiFi/cellular handoffs
    /// or app foreground transitions, which would flash the "offline" badge on
    /// the map even though the device was still on the network. Hold off on
    /// flipping `isConnected = false` for ~1.5 s — if the path returns to
    /// `.satisfied` before then, cancel the pending flip and we never showed
    /// offline. Online transitions stay instant.
    private var pendingDisconnectFlip: DispatchWorkItem?

    func startMonitoring() {
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self = self else { return }

            let wasConnected = self.isConnected
            let pathSatisfied = path.status == .satisfied

            // A tunnel reports as `.other`. Requiring at least one physical
            // interface is what distinguishes "VPN over live WiFi" (online)
            // from "VPN whose underlying link died" (off-grid) — the two look
            // identical through `path.status` alone. See `hasPhysicalPath`.
            let physical = path.availableInterfaces.filter {
                $0.type == .wifi || $0.type == .cellular || $0.type == .wiredEthernet
            }
            var physicalUp = pathSatisfied && !physical.isEmpty

            #if DEBUG
            // 📡 Test affordance: exercise the off-grid LoRa path on a machine
            // that still has WiFi. Only the *verdict* is forced — the radio,
            // the BLE link and the RF hop are all real. `isConnected` is left
            // alone, so nothing else about the app changes.
            // Release-stripped (#if DEBUG) → zero production effect.
            if ProcessInfo.processInfo.arguments.contains("-OSHIForceOffGrid") { physicalUp = false }
            #endif

            // 🔧 DIAG: every path change observable in call_debug.log.
            let ifaceList = path.availableInterfaces.map { "\($0.name):\($0.type)" }.joined(separator: ",")
            CallFileLogger.shared.log("DIAG_NET_PATH | status=\(path.status) | wifi=\(path.usesInterfaceType(.wifi)) | cell=\(path.usesInterfaceType(.cellular)) | physical=\(physicalUp) | ifaces=[\(ifaceList)] | expensive=\(path.isExpensive) | constrained=\(path.isConstrained)")

            let cellular = path.usesInterfaceType(.cellular)

            DispatchQueue.main.async {
                self.isExpensive = path.isExpensive
                self.isConstrained = path.isConstrained
                self.hasPhysicalPath = physicalUp
                self.isCellular = cellular

                // Debounced isConnected: instant ON, delayed OFF.
                if pathSatisfied {
                    self.pendingDisconnectFlip?.cancel()
                    self.pendingDisconnectFlip = nil
                    self.isConnected = true
                } else {
                    self.pendingDisconnectFlip?.cancel()
                    let work = DispatchWorkItem { [weak self] in
                        guard let self = self else { return }
                        self.isConnected = false
                        CallFileLogger.shared.log("DIAG_NET_OFFLINE_CONFIRMED | path unsatisfied for 1.5s+")
                    }
                    self.pendingDisconnectFlip = work
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5, execute: work)
                }

                // Determine connection type
                if path.status != .satisfied {
                    self.connectionType = .offline
                } else if path.usesInterfaceType(.wifi) {
                    self.connectionType = .wifi
                } else if path.usesInterfaceType(.cellular) {
                    self.connectionType = .cellular
                } else if path.usesInterfaceType(.wiredEthernet) {
                    self.connectionType = .wired
                } else {
                    self.connectionType = .unknown
                }

                // Notify on connectivity changes
                if !wasConnected && self.isConnected {
                    // Came back online - trigger retry
                    OshiLog.mesh.info("📶 NetworkReachability: Came back online - triggering message retry")
                    self.wasOffline = true
                    NotificationCenter.default.post(
                        name: .networkBecameReachable,
                        object: nil
                    )
                } else if wasConnected && !self.isConnected {
                    // Went offline
                    OshiLog.mesh.info("📶 NetworkReachability: Went offline")
                    NotificationCenter.default.post(
                        name: .networkBecameUnreachable,
                        object: nil
                    )
                }

                OshiLog.mesh.info("📶 NetworkReachability: \(self.connectionType) - Connected: \(self.isConnected)")
            }
        }

        monitor.start(queue: queue)
        OshiLog.mesh.info("📶 NetworkReachability: Started monitoring")
    }

    func stopMonitoring() {
        monitor.cancel()
        OshiLog.mesh.info("📶 NetworkReachability: Stopped monitoring")
    }

    /// Check if we have internet capability (not just local network)
    func hasInternetCapability() -> Bool {
        return isConnected && connectionType != .offline
    }

    /// Check if should prefer mesh over cloud (low data mode or no internet)
    func shouldPreferMesh() -> Bool {
        return !isConnected || isConstrained || connectionType == .offline
    }

    /// Get human-readable status
    func getStatusDescription() -> String {
        if !isConnected {
            return "network.status.offline".localized
        }

        var status = connectionType.localizedString
        if isExpensive {
            status += " (\("network.status.cellular_data".localized))"
        }
        if isConstrained {
            status += " (\("network.status.low_data".localized))"
        }
        return status
    }
}

// MARK: - Notification Names

extension Notification.Name {
    static let networkBecameReachable = Notification.Name("NetworkBecameReachable")
    static let networkBecameUnreachable = Notification.Name("NetworkBecameUnreachable")
}

// MARK: - String Localization Extension (if not already defined elsewhere)
// Note: This extension should already exist in String+Localization.swift
